// 通用文件预览器
// 单例模式：FilePreviewer.preview({...}) 全局调用
// 支持左右分栏（原文 + 切片），支持点击切片高亮原文位置
const FilePreviewer = {
    // 状态
    visible: false,
    loading: false,
    converting: false,            // Office 文件转换中
    error: null,
    fileName: '',
    fileType: '',
    previewType: '',
    fileSize: 0,
    fileContent: null,            // 字符串 / ArrayBuffer
    fileContentKind: '',          // 'text' | 'binary'
    fileBlobUrl: '',              // 二进制预览用的 blob URL
    chunks: [],                   // 切片列表
    chunksLoading: false,
    chunkSearch: '',              // 切片搜索词
    activeChunkId: null,          // 当前高亮的切片
    expandedChunks: new Set(),    // 展开的切片 id（>500 字符默认折叠）
    source: '',                   // 'knowledge' | 'workspace'
    sessionId: '',                // 工作空间会话 ID，用于从预览界面下载文件
    filePath: '',                 // 工作空间文件路径，用于从预览界面下载文件
    docId: null,                  // 知识库文档 ID，用于从预览界面下载原文件
    _mounted: false,
    _container: null,
    _app: null,
    _vm: null,

    // ==================== 公共 API ====================

    /**
     * 打开预览器
     * @param {Object} opts
     * @param {'knowledge'|'workspace'} opts.source
     * @param {string} opts.fileName
     * @param {string} opts.fileType - 不带点的小写扩展名
     * @param {number} [opts.fileSize]
     * @param {number} [opts.docId] - 知识库文档 ID
     * @param {string} [opts.sessionId] - 工作空间会话 ID
     * @param {string} [opts.filePath] - 工作空间文件路径
     */
    async preview(opts) {
        this._ensureMounted();
        this._reset();
        this.source = opts.source;
        this.sessionId = opts.sessionId || '';
        this.filePath = opts.filePath || '';
        this.docId = opts.docId ?? null;
        this.fileName = opts.fileName || '';
        this.fileType = this._normalizeFileType(opts.fileType, opts.fileName, opts.filePath);
        this.previewType = opts.previewType || this._previewTypeFor(this.fileType);
        this.fileSize = opts.fileSize || 0;
        this.visible = true;
        this.loading = true;
        this.error = null;
        this._syncView();

        try {
            // 1. 读取文件内容
            if (opts.source === 'knowledge') {
                await this._loadKnowledgeFile(opts.docId);
                // 2. 加载切片（异步，不阻塞预览）
                this._loadKnowledgeChunks(opts.docId);
            } else if (opts.source === 'workspace') {
                await this._loadWorkspaceFile(opts.sessionId, opts.filePath);
            } else {
                throw new Error('未知 source: ' + opts.source);
            }
        } catch (e) {
            console.error('FilePreviewer 加载失败:', e);
            this.error = e.message || '加载失败';
        } finally {
            this.loading = false;
            this._syncView();
        }
    },

    close() {
        this.visible = false;
        if (this.fileBlobUrl) {
            URL.revokeObjectURL(this.fileBlobUrl);
            this.fileBlobUrl = '';
        }
        this._syncView();
    },

    /**
     * 下载当前预览的原文件。
     * 工作空间文件复用沙箱下载接口，知识库文件重新读取原始字节，失败时在页面内给出提示。
     */
    async downloadCurrent() {
        try {
            if (this.source === 'workspace' && this.sessionId && this.filePath) {
                api.downloadFileFromSandbox(this.sessionId, this.filePath);
                return;
            }
            if (this.source === 'knowledge' && this.docId != null) {
                const buffer = await api.getKnowledgeFile(this.docId);
                const blobUrl = URL.createObjectURL(new Blob([buffer], { type: this._mimeOf(this.fileType) }));
                const anchor = document.createElement('a');
                anchor.href = blobUrl;
                anchor.download = this.fileName || '文件';
                document.body.appendChild(anchor);
                anchor.click();
                document.body.removeChild(anchor);
                URL.revokeObjectURL(blobUrl);
                return;
            }
            throw new Error('当前文件缺少下载信息');
        } catch (e) {
            console.error('FilePreviewer 下载失败:', e);
            this._toast(e.message || '下载失败', 'error');
        }
    },

    // ==================== 内部方法 ====================

    _ensureMounted() {
        if (this._mounted) return;
        const container = document.createElement('div');
        container.id = 'file-previewer-root';
        document.body.appendChild(container);
        this._container = container;
        this._app = Vue.createApp(this._createComponent());
        this._vm = this._app.mount(container);
        this._mounted = true;
    },

    _syncView() {
        if (!this._vm) return;
        this._vm.visible = this.visible;
        this._vm.loading = this.loading;
        this._vm.converting = this.converting;
        this._vm.error = this.error;
        this._vm.fileName = this.fileName;
        this._vm.fileType = this.fileType;
        this._vm.previewType = this.previewType;
        this._vm.fileSize = this.fileSize;
        this._vm.fileContent = this.fileContent;
        this._vm.fileContentKind = this.fileContentKind;
        this._vm.fileBlobUrl = this.fileBlobUrl;
        this._vm.chunks = this.chunks;
        this._vm.chunksLoading = this.chunksLoading;
        this._vm.chunkSearch = this.chunkSearch;
        this._vm.activeChunkId = this.activeChunkId;
        this._vm.expandedChunks = this.expandedChunks;
        this._vm.source = this.source;
    },

    _reset() {
        if (this.fileBlobUrl) {
            URL.revokeObjectURL(this.fileBlobUrl);
            this.fileBlobUrl = '';
        }
        this.fileContent = null;
        this.fileContentKind = '';
        this.previewType = '';
        this.converting = false;
        this.sessionId = '';
        this.filePath = '';
        this.docId = null;
        this.chunks = [];
        this.chunkSearch = '';
        this.activeChunkId = null;
        this.expandedChunks = new Set();
        this.error = null;
    },

    async _loadKnowledgeFile(docId) {
        const buffer = await api.getKnowledgeFile(docId);
        // 文本类按 UTF-8 解码；二进制类直接用 Blob URL
        if (this._isTextType(this.previewType)) {
            const decoder = new TextDecoder('utf-8');
            this.fileContent = decoder.decode(buffer);
            this.fileContentKind = 'text';
        } else {
            const mime = this._mimeOf(this.previewType);
            const blob = new Blob([buffer], { type: mime });
            this.fileBlobUrl = URL.createObjectURL(blob);
            this.fileContentKind = 'binary';
        }
    },

    async _loadKnowledgeChunks(docId) {
        this.chunksLoading = true;
        this._syncView();
        try {
            const list = await api.getKnowledgeChunks(docId);
            this.chunks = list || [];
            // 默认折叠 > 500 字符的切片
            this.chunks.forEach(c => {
                if ((c.content || '').length > 500) {
                    this.expandedChunks.add(c.id);
                }
            });
        } catch (e) {
            console.warn('加载切片失败:', e);
            this.chunks = [];
        } finally {
            this.chunksLoading = false;
            this._syncView();
        }
    },

    async _loadWorkspaceFile(sessionId, filePath) {
        // 检测是否是 Office 文件（可能需要转换）
        const isOfficeFile = this._isOfficeType(this.fileType);
        if (isOfficeFile) {
            this.converting = true;
            this._syncView();
        }

        try {
            // 通过沙箱 preview 接口拿字节（inline 行为，正确 Content-Type）
            const buffer = await api.previewFileInSandbox(sessionId, filePath);
            if (this._isTextType(this.previewType)) {
                const decoder = new TextDecoder('utf-8');
                this.fileContent = decoder.decode(buffer);
                this.fileContentKind = 'text';
            } else {
                const mime = this._mimeOf(this.previewType);
                const blob = new Blob([buffer], { type: mime });
                this.fileBlobUrl = URL.createObjectURL(blob);
                this.fileContentKind = 'binary';
            }
        } finally {
            this.converting = false;
            this._syncView();
        }
    },

    // ==================== 类型判断 ====================

    _normalizeFileType(fileType, fileName, filePath) {
        const cleanExt = (value) => {
            if (!value) return '';
            const text = String(value).trim().toLowerCase().replace(/^['"]|['"]$/g, '');
            const mimeMap = {
                'application/pdf': 'pdf',
                'text/plain': 'txt',
                'text/markdown': 'md',
                'text/csv': 'csv',
                'application/json': 'json',
                'image/png': 'png',
                'image/jpeg': 'jpg',
                'image/gif': 'gif',
                'image/svg+xml': 'svg',
                'image/webp': 'webp',
            };
            if (mimeMap[text]) return mimeMap[text];
            const withoutQuery = text.split(/[?#]/)[0];
            const lastSegment = withoutQuery.split(/[\\/]/).pop() || '';
            const dot = lastSegment.lastIndexOf('.');
            return (dot >= 0 ? lastSegment.substring(dot + 1) : withoutQuery.replace(/^\./, ''));
        };

        return cleanExt(fileType) || cleanExt(fileName) || cleanExt(filePath) || '';
    },

    _previewTypeFor(ext) {
        const office = [
            'doc', 'docx', 'odt', 'rtf',
            'xls', 'xlsx', 'ods',
            'ppt', 'pptx', 'odp'
        ];
        return office.includes(ext) ? 'pdf' : ext;
    },

    _isTextType(ext) {
        const textExts = ['txt', 'md', 'json', 'xml', 'yml', 'yaml', 'csv',
            'js', 'ts', 'jsx', 'tsx', 'py', 'java', 'go', 'rs', 'rb', 'php',
            'sh', 'bash', 'sql', 'html', 'css', 'scss', 'less', 'vue', 'svelte',
            'c', 'cpp', 'h', 'hpp', 'm', 'kt', 'swift', 'r', 'scala', 'clj',
            'dockerfile', 'makefile', 'toml', 'ini', 'conf', 'log'];
        return textExts.includes(ext);
    },

    _isCodeType(ext) {
        return ['js', 'ts', 'jsx', 'tsx', 'py', 'java', 'go', 'rs', 'rb', 'php',
            'sh', 'bash', 'sql', 'css', 'scss', 'less', 'html', 'vue', 'svelte',
            'c', 'cpp', 'h', 'hpp', 'm', 'kt', 'swift', 'r', 'scala', 'clj',
            'json', 'xml', 'yml', 'yaml', 'toml', 'ini', 'conf'].includes(ext);
    },

    _isMarkdownType(ext) {
        return ['md', 'markdown'].includes(ext);
    },

    _isImageType(ext) {
        return ['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'bmp', 'ico'].includes(ext);
    },

    _isPdfType(ext) {
        return ext === 'pdf';
    },

    _isOfficeType(ext) {
        return ['doc', 'docx', 'odt', 'rtf',
                'xls', 'xlsx', 'ods',
                'ppt', 'pptx', 'odp'].includes(ext);
    },

    _mimeOf(ext) {
        const map = {
            'pdf': 'application/pdf',
            'png': 'image/png', 'jpg': 'image/jpeg', 'jpeg': 'image/jpeg',
            'gif': 'image/gif', 'svg': 'image/svg+xml', 'webp': 'image/webp',
            'bmp': 'image/bmp',
        };
        return map[ext] || 'application/octet-stream';
    },

    _hljsLangOf(ext) {
        const map = {
            'js': 'javascript', 'ts': 'typescript', 'jsx': 'javascript', 'tsx': 'typescript',
            'py': 'python', 'java': 'java', 'go': 'go', 'rs': 'rust', 'rb': 'ruby',
            'php': 'php', 'sh': 'bash', 'bash': 'bash', 'sql': 'sql',
            'css': 'css', 'scss': 'scss', 'less': 'less', 'html': 'xml', 'vue': 'xml',
            'json': 'json', 'xml': 'xml', 'yml': 'yaml', 'yaml': 'yaml',
            'c': 'c', 'cpp': 'cpp', 'h': 'c', 'hpp': 'cpp',
            'kt': 'kotlin', 'swift': 'swift', 'r': 'r', 'scala': 'scala',
            'toml': 'ini', 'ini': 'ini',
        };
        return map[ext] || 'plaintext';
    },

    // ==================== 渲染辅助 ====================

    _renderCode() {
        if (!this.fileContent) return '';
        const ext = this.fileType;
        let highlighted = '';
        try {
            if (typeof hljs !== 'undefined' && this._isCodeType(ext)) {
                const lang = this._hljsLangOf(ext);
                highlighted = hljs.highlight(this.fileContent, { language: lang, ignoreIllegals: true }).value;
            } else {
                // 纯文本：HTML escape
                highlighted = this._escapeHtml(this.fileContent);
            }
        } catch (e) {
            highlighted = this._escapeHtml(this.fileContent);
        }
        return highlighted;
    },

    _renderMarkdown() {
        if (!this.fileContent) return '';
        try {
            if (typeof marked !== 'undefined') {
                marked.setOptions({
                    highlight: (code, lang) => {
                        if (typeof hljs !== 'undefined' && lang) {
                            try { return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value; }
                            catch (e) { return this._escapeHtml(code); }
                        }
                        return this._escapeHtml(code);
                    },
                    breaks: true,
                    gfm: true,
                });
                return marked.parse(this.fileContent);
            }
        } catch (e) {
            console.warn('Markdown 渲染失败:', e);
        }
        return '<pre>' + this._escapeHtml(this.fileContent) + '</pre>';
    },

    _renderCsv() {
        if (!this.fileContent) return '';
        const lines = this.fileContent.split(/\r?\n/).filter(l => l.length > 0);
        if (lines.length === 0) return '<p>空文件</p>';
        const splitCsv = (line) => {
            // 简单 CSV 解析（处理引号包裹的字段）
            const result = [];
            let cur = '';
            let inQuote = false;
            for (let i = 0; i < line.length; i++) {
                const ch = line[i];
                if (ch === '"') {
                    if (inQuote && line[i + 1] === '"') {
                        cur += '"';
                        i++;
                    } else {
                        inQuote = !inQuote;
                    }
                } else if (ch === ',' && !inQuote) {
                    result.push(cur);
                    cur = '';
                } else {
                    cur += ch;
                }
            }
            result.push(cur);
            return result;
        };
        const header = splitCsv(lines[0]);
        const rows = lines.slice(1).map(splitCsv);
        let html = '<table class="csv-table"><thead><tr>';
        header.forEach(h => { html += `<th>${this._escapeHtml(h)}</th>`; });
        html += '</tr></thead><tbody>';
        rows.forEach(row => {
            html += '<tr>';
            row.forEach(cell => { html += `<td>${this._escapeHtml(cell)}</td>`; });
            html += '</tr>';
        });
        html += '</tbody></table>';
        return html;
    },

    _escapeHtml(s) {
        if (s == null) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    },

    // ==================== 切片交互 ====================

    _filteredChunks() {
        const q = this.chunkSearch.trim().toLowerCase();
        if (!q) return this.chunks;
        return this.chunks.filter(c => (c.content || '').toLowerCase().includes(q));
    },

    _isChunkExpanded(chunk) {
        // 短切片（<=500 字符）默认展开
        if ((chunk.content || '').length <= 500) return true;
        // 长切片默认折叠，用户点击展开
        return this.expandedChunks.has(chunk.id);
    },

    _toggleChunkExpand(chunkId) {
        if (this.expandedChunks.has(chunkId)) {
            this.expandedChunks.delete(chunkId);
        } else {
            this.expandedChunks.add(chunkId);
        }
    },

    _onChunkClick(chunk) {
        this.activeChunkId = chunk.id;
        // 滚动原文到对应位置
        this._scrollOriginalToChunk(chunk);
    },

    _scrollOriginalToChunk(chunk) {
        // 切片的 startOffset/endOffset 是相对于"解析后纯文本"的偏移
        // 而 fileContent 来自沙箱原文件（可能是 PDF/Word 等解析前格式）
        // 限制：仅当 fileContentKind === 'text' 且原文件是解析得到的纯文本时联动有效
        // 知识库 PDF/Word 在前端拿到的是原始二进制 blob，不会联动
        const container = document.querySelector('.fp-original-content');
        if (!container) return;
        const target = container.querySelector(`.fp-original-chunk[data-chunk-id="${chunk.id}"]`);
        if (target) {
            target.scrollIntoView({ behavior: 'smooth', block: 'center' });
        } else {
            // 降级：在原文区顶部显示提示
            console.warn('未找到切片对应原文位置（可能文件类型为二进制）');
        }
    },

    async _copyChunk(chunk) {
        try {
            await navigator.clipboard.writeText(chunk.content || '');
            this._toast('已复制切片内容');
        } catch (e) {
            // 降级方案
            const ta = document.createElement('textarea');
            ta.value = chunk.content || '';
            document.body.appendChild(ta);
            ta.select();
            try { document.execCommand('copy'); this._toast('已复制'); }
            catch (e2) { this._toast('复制失败', 'error'); }
            document.body.removeChild(ta);
        }
    },

    _toast(msg, type = 'info') {
        // 简单的全局提示
        const existing = document.querySelector('.fp-toast');
        if (existing) existing.remove();
        const el = document.createElement('div');
        el.className = 'fp-toast fp-toast-' + type;
        el.textContent = msg;
        document.body.appendChild(el);
        setTimeout(() => el.remove(), 2000);
    },

    // ==================== Vue 组件 ====================

    _createComponent() {
        const self = this;
        return {
            data() {
                return {
                    visible: self.visible,
                    loading: self.loading,
                    converting: self.converting,
                    error: self.error,
                    fileName: self.fileName,
                    fileType: self.fileType,
                    previewType: self.previewType,
                    fileSize: self.fileSize,
                    fileContent: self.fileContent,
                    fileContentKind: self.fileContentKind,
                    fileBlobUrl: self.fileBlobUrl,
                    chunks: self.chunks,
                    chunksLoading: self.chunksLoading,
                    chunkSearch: self.chunkSearch,
                    activeChunkId: self.activeChunkId,
                    expandedChunks: self.expandedChunks,
                    source: self.source,
                };
            },
            computed: {
                filteredChunks() {
                    const q = this.chunkSearch.trim().toLowerCase();
                    if (!q) return this.chunks;
                    return this.chunks.filter(c => (c.content || '').toLowerCase().includes(q));
                },
                showChunks() {
                    return this.source === 'knowledge' && this.chunks !== undefined;
                },
                fileSizeText() {
                    if (!this.fileSize) return '';
                    if (this.fileSize < 1024) return this.fileSize + ' B';
                    if (this.fileSize < 1024 * 1024) return (this.fileSize / 1024).toFixed(1) + ' KB';
                    return (this.fileSize / 1024 / 1024).toFixed(1) + ' MB';
                },
                renderedHtml() {
                    if (this.fileContentKind !== 'text' || !this.fileContent) return '';
                    const ext = this.fileType;
                    if (self._isMarkdownType(ext)) return self._renderMarkdown();
                    if (ext === 'csv') return self._renderCsv();
                    if (self._isCodeType(ext)) return self._renderCode();
                    return '<pre class="fp-plaintext">' + self._escapeHtml(this.fileContent) + '</pre>';
                },
                isImage() { return self._isImageType(this.previewType); },
                isPdf() { return self._isPdfType(this.previewType); },
                isBinary() { return this.fileContentKind === 'binary'; },
            },
            watch: {
                visible(v) { self.visible = v; if (!v) self._reset(); },
                loading(v) { self.loading = v; },
                converting(v) { self.converting = v; },
                error(v) { self.error = v; },
                fileName(v) { self.fileName = v; },
                previewType(v) { self.previewType = v; },
                fileContent(v) { self.fileContent = v; },
                fileBlobUrl(v) { self.fileBlobUrl = v; },
                chunks: {
                    deep: false,
                    handler(v) { self.chunks = v || []; },
                },
                chunksLoading(v) { self.chunksLoading = v; },
                chunkSearch(v) { self.chunkSearch = v; },
                activeChunkId(v) { self.activeChunkId = v; },
            },
            mounted() {
                // 同步初始状态
                this.visible = self.visible;
                this.loading = self.loading;
                this.converting = self.converting;
                this.error = self.error;
                this.fileName = self.fileName;
                this.fileType = self.fileType;
                this.previewType = self.previewType;
                this.fileSize = self.fileSize;
                this.fileContent = self.fileContent;
                this.fileContentKind = self.fileContentKind;
                this.fileBlobUrl = self.fileBlobUrl;
                this.chunks = self.chunks;
                this.source = self.source;
            },
            methods: {
                close() { self.close(); },
                downloadCurrent() { self.downloadCurrent(); },
                isChunkExpanded(chunk) { return self._isChunkExpanded(chunk); },
                toggleChunkExpand(chunk) { self._toggleChunkExpand(chunk.id); },
                onChunkClick(chunk) { self._onChunkClick(chunk); },
                copyChunk(chunk) { self._copyChunk(chunk); },
            },
            template: `
                <div v-if="visible" class="fp-overlay" @click.self="close">
                    <div class="fp-dialog" :class="{
                        'fp-with-chunks': showChunks,
                        'fp-image-dialog': isImage && !showChunks
                    }">
                        <!-- 头部 -->
                        <div class="fp-header">
                            <div class="fp-title">
                                <span class="fp-icon">{{ fileTypeIcon(fileType) }}</span>
                                <span class="fp-name">{{ fileName }}</span>
                                <span class="fp-title-divider" aria-hidden="true">|</span>
                                <button type="button" class="fp-title-download" @click="downloadCurrent">下载</button>
                                <span class="fp-meta" v-if="fileSizeText">{{ fileSizeText }} · {{ fileType.toUpperCase() }}</span>
                            </div>
                            <div class="fp-actions">
                                <button class="fp-btn" @click="close" title="关闭">✕</button>
                            </div>
                        </div>

                        <!-- 加载/错误 -->
                        <div v-if="loading" class="fp-loading">加载中...</div>
                        <div v-else-if="converting" class="fp-converting">
                            <div class="fp-converting-spinner"></div>
                            <div class="fp-converting-text">正在转换文档，请稍候...</div>
                            <div class="fp-converting-hint">首次预览 Office 文档需要转换为 PDF，可能需要 10-30 秒</div>
                        </div>
                        <div v-else-if="error" class="fp-error">⚠ {{ error }}</div>

                        <!-- 主体 -->
                        <div v-else class="fp-body">
                            <!-- 左侧：原文 -->
                            <div class="fp-original">
                                <div class="fp-original-header">原文</div>
                                <div
                                    class="fp-original-content"
                                    :class="{
                                        'fp-content-pdf': isPdf,
                                        'fp-content-image': isImage
                                    }"
                                    ref="originalContent"
                                >
                                    <!-- 二进制图片 -->
                                    <img v-if="isImage && fileBlobUrl" :src="fileBlobUrl" class="fp-image" :alt="fileName" />
                                    <!-- PDF -->
                                    <iframe v-else-if="isPdf && fileBlobUrl" :src="fileBlobUrl" class="fp-pdf" :title="fileName"></iframe>
                                    <!-- 二进制其他类型 -->
                                    <div v-else-if="isBinary" class="fp-binary-hint">
                                        <p>该文件类型暂不支持在线预览</p>
                                        <p class="fp-hint">请下载后查看，或在沙箱中打开</p>
                                    </div>
                                    <!-- 文本/MD/CSV/代码 -->
                                    <div v-else v-html="renderedHtml" class="fp-text" :class="'fp-lang-' + fileType"></div>
                                </div>
                            </div>

                            <!-- 右侧：切片 -->
                            <div v-if="showChunks" class="fp-chunks">
                                <div class="fp-chunks-header">
                                    <span>切片列表</span>
                                    <span class="fp-chunks-count">{{ filteredChunks.length }} / {{ chunks.length }}</span>
                                </div>
                                <div class="fp-chunks-search">
                                    <input v-model="chunkSearch" placeholder="搜索切片内容..." />
                                </div>
                                <div v-if="chunksLoading" class="fp-chunks-loading">加载切片中...</div>
                                <div v-else-if="chunks.length === 0" class="fp-chunks-empty">该文档暂无切片</div>
                                <div v-else class="fp-chunks-list">
                                    <div
                                        v-for="chunk in filteredChunks"
                                        :key="chunk.id"
                                        class="fp-chunk-item"
                                        :class="{ active: activeChunkId === chunk.id }"
                                        @click="onChunkClick(chunk)"
                                    >
                                        <div class="fp-chunk-header">
                                            <span class="fp-chunk-index">#{{ chunk.chunkIndex }}</span>
                                            <span class="fp-chunk-tokens" v-if="chunk.tokenCount">{{ chunk.tokenCount }} tokens</span>
                                            <span class="fp-chunk-offset" v-if="chunk.startOffset != null">[{{ chunk.startOffset }}-{{ chunk.endOffset }}]</span>
                                            <button class="fp-chunk-copy" @click.stop="copyChunk(chunk)" title="复制">📋</button>
                                        </div>
                                        <div class="fp-chunk-content" :class="{ collapsed: !isChunkExpanded(chunk) && chunk.content && chunk.content.length > 500 }">
                                            <pre>{{ chunk.content }}</pre>
                                        </div>
                                        <button v-if="chunk.content && chunk.content.length > 500" class="fp-chunk-toggle" @click.stop="toggleChunkExpand(chunk)">
                                            {{ isChunkExpanded(chunk) ? '收起' : '展开全部' }}
                                        </button>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            `,
            methods2: {
                fileTypeIcon(t) {
                    const map = {
                        'pdf': '📕', 'doc': '📘', 'docx': '📘',
                        'xls': '📗', 'xlsx': '📗', 'csv': '📊',
                        'ppt': '📙', 'pptx': '📙',
                        'txt': '📄', 'md': '📝', 'markdown': '📝',
                        'html': '🌐', 'json': '📋', 'xml': '📋',
                        'py': '🐍', 'js': '📜', 'ts': '📜', 'java': '☕', 'go': '🔵',
                        'png': '🖼️', 'jpg': '🖼️', 'jpeg': '🖼️', 'gif': '🖼️', 'svg': '🖼️',
                    };
                    return map[t] || '📄';
                },
            },
        };
    },
};

// 覆盖 _createComponent 中的 methods（Vue 3 中 methods 只能有一个）
FilePreviewer._createComponent = (function (orig) {
    return function () {
        const comp = orig.call(this);
        // 合并 fileTypeIcon
        comp.methods.fileTypeIcon = function (t) {
            const map = {
                'pdf': '📕', 'doc': '📘', 'docx': '📘',
                'xls': '📗', 'xlsx': '📗', 'csv': '📊',
                'ppt': '📙', 'pptx': '📙',
                'txt': '📄', 'md': '📝', 'markdown': '📝',
                'html': '🌐', 'json': '📋', 'xml': '📋',
                'py': '🐍', 'js': '📜', 'ts': '📜', 'java': '☕', 'go': '🔵',
                'png': '🖼️', 'jpg': '🖼️', 'jpeg': '🖼️', 'gif': '🖼️', 'svg': '🖼️',
            };
            return map[t] || '📄';
        };
        return comp;
    };
})(FilePreviewer._createComponent);

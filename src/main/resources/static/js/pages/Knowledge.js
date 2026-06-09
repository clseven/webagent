// 知识库页组件
const KnowledgePage = {
    template: `
        <div class="knowledge-page">
            <h1>知识库</h1>
            <p class="page-desc">管理知识库和文档，让智能体更了解您的业务</p>

            <!-- 知识库管理 -->
            <div class="kb-section">
                <div class="kb-header">
                    <h3>知识库列表</h3>
                    <button class="btn-primary btn-sm" @click="showCreateKb = true" v-if="!showCreateKb">＋ 新建知识库</button>
                </div>

                <!-- 创建知识库表单 -->
                <div class="create-kb-form" v-if="showCreateKb">
                    <div class="form-group">
                        <label>知识库名称</label>
                        <input v-model="newKb.name" placeholder="如：毕业设计项目" maxlength="100">
                    </div>
                    <div class="form-group">
                        <label>知识库描述</label>
                        <textarea v-model="newKb.description" placeholder="描述知识库包含的内容，Agent 会根据描述判断何时检索..." maxlength="500" rows="2"></textarea>
                    </div>
                    <div class="form-actions">
                        <button class="btn-primary btn-sm" @click="createKb" :disabled="!newKb.name.trim()">创建</button>
                        <button class="btn-secondary btn-sm" @click="showCreateKb = false">取消</button>
                    </div>
                </div>

                <!-- 知识库列表 -->
                <div class="kb-list" v-if="knowledgeBases.length > 0">
                    <div class="kb-item" v-for="kb in knowledgeBases" :key="kb.id"
                         :class="{ active: currentKb && currentKb.id === kb.id }"
                         @click="selectKb(kb)">
                        <div class="kb-info">
                            <div class="kb-name">{{ kb.name }}</div>
                            <div class="kb-desc">{{ kb.description || '暂无描述' }}</div>
                        </div>
                        <div class="kb-actions">
                            <button class="btn-icon" @click.stop="editKb(kb)" title="编辑">✏️</button>
                            <button class="btn-icon" @click.stop="deleteKb(kb)" title="删除">🗑️</button>
                        </div>
                    </div>
                </div>
                <div class="empty-hint" v-else-if="!loadingKb">暂无知识库，请先创建</div>
            </div>

            <!-- 编辑知识库弹窗 -->
            <div class="modal-overlay" v-if="editingKb" @click.self="editingKb = null">
                <div class="modal-content modal-sm">
                    <div class="modal-header">
                        <h3>编辑知识库</h3>
                        <button class="modal-close" @click="editingKb = null">✕</button>
                    </div>
                    <div class="form-group">
                        <label>名称</label>
                        <input v-model="editKbForm.name" maxlength="100">
                    </div>
                    <div class="form-group">
                        <label>描述</label>
                        <textarea v-model="editKbForm.description" maxlength="500" rows="3"></textarea>
                    </div>
                    <div class="form-actions">
                        <button class="btn-primary btn-sm" @click="saveKb">保存</button>
                        <button class="btn-secondary btn-sm" @click="editingKb = null">取消</button>
                    </div>
                </div>
            </div>

            <!-- 当前知识库的文档管理 -->
            <div class="doc-section" v-if="currentKb">
                <div class="doc-header">
                    <h3>📁 {{ currentKb.name }} - 文档管理</h3>
                </div>

                <!-- 统计卡片 -->
                <div class="stats">
                    <div class="stat-card">
                        <div class="stat-number">{{ documents.length }}</div>
                        <div class="stat-label">文档总数</div>
                    </div>
                    <div class="stat-card">
                        <div class="stat-number">{{ totalChunks }}</div>
                        <div class="stat-label">切片总数</div>
                    </div>
                    <div class="stat-card">
                        <div class="stat-number">{{ readyCount }}</div>
                        <div class="stat-label">已就绪</div>
                    </div>
                </div>

                <!-- 上传区域 -->
                <div class="knowledge-upload-area">
                    <div class="upload-drop-zone"
                         :class="{ 'drag-over': isDragging }"
                         @dragover.prevent="isDragging = true"
                         @dragleave="isDragging = false"
                         @drop.prevent="handleDrop">
                        <div class="upload-icon">📄</div>
                        <div class="upload-text">
                            <span v-if="!isDragging">拖拽文件到此处，或</span>
                            <span v-else>释放文件</span>
                            <label class="upload-link" v-if="!isDragging">
                                点击选择文件
                                <input type="file" multiple @change="handleFileSelect"
                                       accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.md,.html,.csv,.json,.xml,.rtf,.odt,.ods,.odp,.epub,.mobi,.jpg,.jpeg,.png,.gif,.bmp,.tiff">
                            </label>
                        </div>
                        <div class="upload-hint">支持 PDF、Word、Excel、PPT、TXT、Markdown、HTML、图片等格式</div>
                    </div>

                    <!-- 切片模式选择 -->
                    <div class="split-mode-section">
                        <div class="split-mode-toggle" @click="showAdvanced = !showAdvanced">
                            <span>切片设置</span>
                            <span class="toggle-arrow" :class="{ open: showAdvanced }">▼</span>
                        </div>

                        <div class="split-mode-quick">
                            <label class="split-option" :class="{ active: splitMode === 'smart' }">
                                <input type="radio" v-model="splitMode" value="smart">
                                <span class="option-label">智能切片</span>
                                <span class="option-desc">按段落自然切分，推荐大多数场景</span>
                            </label>
                            <label class="split-option" :class="{ active: splitMode === 'custom' }">
                                <input type="radio" v-model="splitMode" value="custom">
                                <span class="option-label">自定义切片</span>
                                <span class="option-desc">手动调节切片大小和重叠</span>
                            </label>
                        </div>

                        <div class="split-custom-params" v-if="splitMode === 'custom' && showAdvanced">
                            <div class="param-row">
                                <label>
                                    切片大小（字符数）
                                    <span class="param-hint">推荐: 750</span>
                                </label>
                                <input type="number" v-model.number="chunkSize" min="100" max="10000" step="100">
                                <span class="param-preview">约 {{ Math.round(chunkSize * 0.8) }} tokens</span>
                            </div>
                            <div class="param-row">
                                <label>
                                    重叠大小（字符数）
                                    <span class="param-hint">推荐: 75</span>
                                </label>
                                <input type="number" v-model.number="overlap" min="0" :max="chunkSize" step="50">
                                <span class="param-preview">占比 {{ chunkSize > 0 ? Math.round(overlap / chunkSize * 100) : 0 }}%</span>
                            </div>
                        </div>
                    </div>

                    <!-- 上传队列 -->
                    <div class="upload-queue" v-if="uploadQueue.length > 0">
                        <div class="queue-item" v-for="(file, index) in uploadQueue" :key="index">
                            <span class="queue-file-name">{{ file.name }}</span>
                            <span class="queue-file-size">{{ formatSize(file.size) }}</span>
                            <span class="queue-status" :class="file.status">
                                {{ file.statusText }}
                            </span>
                            <button class="queue-remove" @click="removeFromQueue(index)" v-if="file.status === 'pending'">✕</button>
                        </div>
                        <button class="btn-upload-all" @click="uploadAll" :disabled="uploading">
                            {{ uploading ? '上传中...' : '开始上传' }}
                        </button>
                    </div>
                </div>

                <!-- 检索测试 -->
                <div class="knowledge-search-box" v-if="documents.length > 0">
                    <h3>知识库检索</h3>
                    <div class="search-input-row">
                        <input v-model="searchQuery" @keyup.enter="doSearch"
                               placeholder="输入问题，测试知识库检索效果..." />
                        <button @click="doSearch" :disabled="searching || !searchQuery.trim()">
                            {{ searching ? '检索中...' : '检索' }}
                        </button>
                    </div>
                    <div class="search-results" v-if="searchResults.length > 0">
                        <div class="search-result-item" v-for="(r, i) in searchResults" :key="i">
                            <div class="result-header">
                                <span class="result-doc">{{ r.docName }}</span>
                                <span class="result-score">相似度: {{ (r.score * 100).toFixed(1) }}%</span>
                            </div>
                            <div class="result-content">{{ r.content }}</div>
                        </div>
                    </div>
                    <div class="search-empty" v-else-if="searchDone && searchResults.length === 0">
                        未找到相关内容
                    </div>
                </div>

                <!-- 文档列表 -->
                <div class="knowledge-doc-list">
                    <div class="doc-list-header">
                        <h3>文档列表</h3>
                        <button class="btn-refresh" @click="loadDocuments" :disabled="loading">
                            {{ loading ? '加载中...' : '刷新' }}
                        </button>
                    </div>

                    <div class="loading" v-if="loading">加载中...</div>

                    <div class="empty-msg" v-else-if="documents.length === 0">
                        暂无文档，请上传文件到知识库
                    </div>

                    <div class="doc-list" v-else>
                        <div class="doc-item"
                             v-for="doc in documents"
                             :key="doc.id"
                             :class="{ 'doc-clickable': doc.status === 'READY' }"
                             @click="previewDoc(doc)">
                            <div class="doc-icon">{{ getFileIcon(doc.fileType) }}</div>
                            <div class="doc-info">
                                <div class="doc-name">{{ doc.fileName }}</div>
                                <div class="doc-meta">
                                    <span>{{ doc.fileType?.toUpperCase() }}</span>
                                    <span>{{ formatSize(doc.fileSize) }}</span>
                                    <span>{{ doc.chunkCount }} 个切片</span>
                                    <span v-if="doc.totalTokens">{{ doc.totalTokens }} tokens</span>
                                    <span>{{ formatDate(doc.createdAt) }}</span>
                                </div>
                            </div>
                            <div class="doc-status">
                                <span class="status-badge" :class="doc.status">{{ statusText(doc.status) }}</span>
                            </div>
                            <div class="doc-actions">
                                <button class="btn-delete" @click.stop="deleteDoc(doc)" title="删除">🗑️</button>
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            <div class="empty-msg" v-else-if="knowledgeBases.length > 0" style="margin-top: 2rem;">
                请先选择一个知识库
            </div>
        </div>
    `,
    setup() {
        // 知识库相关
        const knowledgeBases = Vue.ref([]);
        const loadingKb = Vue.ref(false);
        const showCreateKb = Vue.ref(false);
        const newKb = Vue.ref({ name: '', description: '' });
        const currentKb = Vue.ref(null);
        const editingKb = Vue.ref(null);
        const editKbForm = Vue.ref({ name: '', description: '' });

        // 文档相关
        const documents = Vue.ref([]);
        const loading = Vue.ref(false);
        const uploadQueue = Vue.ref([]);
        const uploading = Vue.ref(false);
        const isDragging = Vue.ref(false);
        const searchQuery = Vue.ref('');
        const searchResults = Vue.ref([]);
        const searching = Vue.ref(false);
        const searchDone = Vue.ref(false);

        // 切片参数
        const splitMode = Vue.ref('smart');
        const showAdvanced = Vue.ref(false);
        const chunkSize = Vue.ref(750);
        const overlap = Vue.ref(75);

        const totalChunks = Vue.computed(() => {
            return documents.value.reduce((sum, doc) => sum + (doc.chunkCount || 0), 0);
        });

        const readyCount = Vue.computed(() => {
            return documents.value.filter(doc => doc.status === 'READY').length;
        });

        // ========== 知识库操作 ==========

        async function loadKnowledgeBases() {
            loadingKb.value = true;
            try {
                knowledgeBases.value = await api.listKnowledgeBases();
                // 如果没有选中的知识库，自动选第一个
                if (!currentKb.value && knowledgeBases.value.length > 0) {
                    selectKb(knowledgeBases.value[0]);
                }
            } catch (e) {
                console.error('加载知识库列表失败', e);
            } finally {
                loadingKb.value = false;
            }
        }

        function selectKb(kb) {
            currentKb.value = kb;
            loadDocuments();
        }

        async function createKb() {
            if (!newKb.value.name.trim()) return;
            try {
                await api.createKnowledgeBase(newKb.value.name, newKb.value.description);
                newKb.value = { name: '', description: '' };
                showCreateKb.value = false;
                await loadKnowledgeBases();
            } catch (e) {
                alert('创建失败: ' + e.message);
            }
        }

        function editKb(kb) {
            editingKb.value = kb;
            editKbForm.value = { name: kb.name, description: kb.description || '' };
        }

        async function saveKb() {
            try {
                await api.updateKnowledgeBase(editingKb.value.id, editKbForm.value.name, editKbForm.value.description);
                editingKb.value = null;
                await loadKnowledgeBases();
                // 更新当前选中的知识库
                if (currentKb.value) {
                    currentKb.value = knowledgeBases.value.find(kb => kb.id === currentKb.value.id);
                }
            } catch (e) {
                alert('保存失败: ' + e.message);
            }
        }

        async function deleteKb(kb) {
            if (!confirm(`确定删除知识库「${kb.name}」？该操作会删除知识库下的所有文档。`)) return;
            try {
                await api.deleteKnowledgeBase(kb.id);
                if (currentKb.value && currentKb.value.id === kb.id) {
                    currentKb.value = null;
                    documents.value = [];
                }
                await loadKnowledgeBases();
            } catch (e) {
                alert('删除失败: ' + e.message);
            }
        }

        // ========== 文档操作 ==========

        async function loadDocuments() {
            if (!currentKb.value) return;
            loading.value = true;
            try {
                documents.value = await api.listKnowledgeDocs(currentKb.value.id);
            } catch (e) {
                console.error('加载文档列表失败', e);
                documents.value = [];
            } finally {
                loading.value = false;
            }
        }

        function handleFileSelect(event) {
            const files = Array.from(event.target.files);
            addToQueue(files);
            event.target.value = '';
        }

        function handleDrop(event) {
            isDragging.value = false;
            const files = Array.from(event.dataTransfer.files);
            addToQueue(files);
        }

        function addToQueue(files) {
            for (const file of files) {
                uploadQueue.value.push({
                    file,
                    name: file.name,
                    size: file.size,
                    status: 'pending',
                    statusText: '等待上传'
                });
            }
        }

        function removeFromQueue(index) {
            uploadQueue.value.splice(index, 1);
        }

        async function uploadAll() {
            if (!currentKb.value) {
                alert('请先选择知识库');
                return;
            }
            uploading.value = true;
            for (const item of uploadQueue.value) {
                if (item.status !== 'pending') continue;
                item.status = 'uploading';
                item.statusText = '上传中...';
                try {
                    await api.uploadKnowledge(
                        currentKb.value.id,
                        item.file,
                        splitMode.value,
                        splitMode.value === 'custom' ? chunkSize.value : null,
                        splitMode.value === 'custom' ? overlap.value : null
                    );
                    item.status = 'success';
                    item.statusText = '上传成功';
                } catch (e) {
                    // 检测到重复文件（409 错误）：使用通用 DuplicateHandler 处理
                    if (window.DuplicateHandler.isDuplicateError(e)) {
                        const existingDocId = e.data.existingDocId;
                        const result = await window.DuplicateHandler.handle({
                            file: item.file,
                            error: e,
                            onReplace: () => api.replaceKnowledgeDoc(
                                existingDocId,
                                item.file,
                                splitMode.value,
                                splitMode.value === 'custom' ? chunkSize.value : null,
                                splitMode.value === 'custom' ? overlap.value : null
                            ),
                            onKeepBoth: (newFile) => api.uploadKnowledge(
                                currentKb.value.id,
                                newFile,
                                splitMode.value
                            )
                        });
                        if (result === 'replace-done') {
                            item.status = 'success';
                            item.statusText = '替换成功';
                        } else if (result === 'keep-both-done') {
                            item.status = 'success';
                            item.statusText = '保留两份成功';
                        } else if (result === 'skip') {
                            item.status = 'error';
                            item.statusText = '已跳过（文件已存在）';
                        } else {
                            item.status = 'error';
                            item.statusText = '操作失败';
                        }
                    } else {
                        item.status = 'error';
                        item.statusText = '上传失败: ' + e.message;
                    }
                }
            }
            uploading.value = false;
            uploadQueue.value = uploadQueue.value.filter(f => f.status !== 'success');
            await loadDocuments();
        }

        async function deleteDoc(doc) {
            if (!confirm(`确定删除文档「${doc.fileName}」？`)) return;
            try {
                await api.deleteKnowledgeDoc(doc.id);
                await loadDocuments();
            } catch (e) {
                alert('删除失败: ' + e.message);
            }
        }

        function previewDoc(doc) {
            // 只有 READY 状态的文档可预览
            if (doc.status !== 'READY') {
                alert('文档尚未处理完成：' + statusText(doc.status));
                return;
            }
            FilePreviewer.preview({
                source: 'knowledge',
                docId: doc.id,
                fileName: doc.fileName,
                fileType: (doc.fileType || '').toLowerCase().replace(/^\./, ''),
                fileSize: doc.fileSize || 0
            });
        }

        async function doSearch() {
            if (!searchQuery.value.trim() || !currentKb.value) return;
            searching.value = true;
            searchDone.value = false;
            searchResults.value = [];
            try {
                searchResults.value = await api.searchKnowledge(currentKb.value.id, searchQuery.value, 5);
                searchDone.value = true;
            } catch (e) {
                alert('检索失败: ' + e.message);
            } finally {
                searching.value = false;
            }
        }

        function formatSize(bytes) {
            if (!bytes) return '-';
            if (bytes < 1024) return bytes + ' B';
            if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
            return (bytes / 1024 / 1024).toFixed(1) + ' MB';
        }

        function formatDate(dateStr) {
            if (!dateStr) return '-';
            const d = new Date(dateStr);
            return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        }

        function getFileIcon(type) {
            const icons = {
                'pdf': '📕', 'doc': '📘', 'docx': '📘', 'xls': '📗', 'xlsx': '📗',
                'ppt': '📙', 'pptx': '📙', 'txt': '📄', 'md': '📝', 'html': '🌐',
                'csv': '📊', 'json': '📋', 'xml': '📋', 'jpg': '🖼️', 'jpeg': '🖼️',
                'png': '🖼️', 'gif': '🖼️', 'epub': '📚', 'mobi': '📚'
            };
            return icons[type?.toLowerCase()] || '📄';
        }

        function statusText(status) {
            const map = { 'PENDING': '等待处理', 'PROCESSING': '处理中', 'READY': '已就绪', 'FAILED': '处理失败' };
            return map[status] || status;
        }

        Vue.onMounted(() => {
            loadKnowledgeBases();
        });

        return {
            knowledgeBases, loadingKb, showCreateKb, newKb, currentKb, editingKb, editKbForm,
            documents, loading, uploadQueue, uploading, isDragging,
            searchQuery, searchResults, searching, searchDone,
            totalChunks, readyCount,
            splitMode, showAdvanced, chunkSize, overlap,
            loadKnowledgeBases, selectKb, createKb, editKb, saveKb, deleteKb,
            loadDocuments, handleFileSelect, handleDrop, addToQueue, removeFromQueue,
            uploadAll, deleteDoc, previewDoc, doSearch,
            formatSize, formatDate, getFileIcon, statusText
        };
    }
};

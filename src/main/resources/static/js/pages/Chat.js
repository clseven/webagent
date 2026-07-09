// 聊天图片组工具：过程截图默认不进入最终图片卡片组，图片预览默认只露出前 4 张。
const ChatArtifactGalleryUtils = {
    PREVIEW_LIMIT: 4,
    shouldShowToolArtifact(artifact) {
        if (!artifact) return false;
        if (artifact.sourceTool === 'browser_screenshot') {
            return artifact.deliverToUser === true;
        }
        return true;
    },
    gallerySlice(artifacts, limit = 4) {
        const list = Array.isArray(artifacts) ? artifacts : [];
        const safeLimit = Math.max(1, Number(limit) || 4);
        const visible = list.slice(0, safeLimit);
        return {
            visible,
            hiddenCount: Math.max(0, list.length - visible.length),
            totalCount: list.length,
        };
    },
};

if (typeof window !== 'undefined') {
    window.ChatArtifactGalleryUtils = ChatArtifactGalleryUtils;
}

// 聊天产物卡片：图片直接展示缩略图，文档展示类型图标和文件信息。
const ChatArtifactCard = {
    props: {
        artifact: { type: Object, required: true },
        blobUrl: { type: String, default: '' },
        loadError: { type: Boolean, default: false },
    },
    emits: ['preview', 'download'],
    template: `
        <article v-if="artifact.isImage" class="chat-artifact chat-artifact-image">
            <button type="button" class="chat-artifact-image-preview" @click="$emit('preview', artifact)">
                <img v-if="blobUrl" :src="blobUrl" :alt="artifact.fileName">
                <span v-else-if="loadError" class="chat-artifact-image-state">图片加载失败</span>
                <span v-else class="chat-artifact-image-state">正在加载图片...</span>
            </button>
        </article>
        <article v-else class="chat-artifact chat-artifact-file" @click="$emit('preview', artifact)">
            <span :class="['chat-artifact-type-icon', artifact.iconClass]">{{ artifact.iconText }}</span>
            <span class="chat-artifact-info">
                <strong :title="artifact.fileName">{{ artifact.fileName }}</strong>
                <small>{{ artifact.meta }}</small>
            </span>
            <span class="chat-artifact-actions">
                <button type="button" @click.stop="$emit('preview', artifact)">预览</button>
                <button type="button" @click.stop="$emit('download', artifact)">下载</button>
            </span>
        </article>
    `,
};

// 聊天产物组：图片按 GPT 风格缩略图组展示，非图片继续用文件卡片展示。
const ChatArtifactGallery = {
    components: { ChatArtifactCard },
    props: {
        artifacts: { type: Array, default: () => [] },
        blobUrlFor: { type: Function, required: true },
        loadErrorFor: { type: Function, required: true },
    },
    emits: ['preview', 'preview-gallery', 'download'],
    computed: {
        imageArtifacts() {
            return this.artifacts.filter(artifact => artifact?.isImage);
        },
        fileArtifacts() {
            return this.artifacts.filter(artifact => !artifact?.isImage);
        },
        imageSlice() {
            return ChatArtifactGalleryUtils.gallerySlice(this.imageArtifacts);
        },
        visibleImages() {
            return this.imageSlice.visible;
        },
    },
    template: `
        <div class="chat-artifact-collection">
            <div
                v-if="imageArtifacts.length"
                :class="['chat-artifact-gallery', 'count-' + visibleImages.length]"
            >
                <button
                    v-for="(artifact, index) in visibleImages"
                    :key="artifact.key"
                    type="button"
                    class="chat-artifact-thumb"
                    @click="$emit('preview-gallery', imageArtifacts, index)"
                    :aria-label="'预览图片 ' + artifact.fileName"
                >
                    <img v-if="blobUrlFor(artifact)" :src="blobUrlFor(artifact)" :alt="artifact.fileName">
                    <span v-else-if="loadErrorFor(artifact)" class="chat-artifact-thumb-state">图片加载失败</span>
                    <span v-else class="chat-artifact-thumb-state">加载中...</span>
                    <span
                        v-if="index === visibleImages.length - 1 && imageSlice.hiddenCount > 0"
                        class="chat-artifact-more"
                    >+{{ imageSlice.hiddenCount }}</span>
                </button>
            </div>
            <div v-if="fileArtifacts.length" class="chat-artifact-files">
                <chat-artifact-card
                    v-for="artifact in fileArtifacts"
                    :key="artifact.key"
                    :artifact="artifact"
                    :blob-url="blobUrlFor(artifact)"
                    :load-error="loadErrorFor(artifact)"
                    @preview="$emit('preview', artifact)"
                    @download="$emit('download', artifact)"
                ></chat-artifact-card>
            </div>
        </div>
    `,
};

// 工具行/步骤文案纯函数：提到模块顶层，供 setup 与子组件、流式与历史共用，保证两态渲染一致。
const previewText = (c, max = 90) => { if (!c) return ''; const t = String(c).replace(/\s+/g, ' ').trim(); return t.length > max ? t.substring(0, max) + '...' : t; };
const completedActionText = (text) => {
    const value = String(text || '').trim();
    return value.startsWith('正在') ? `已${value.substring(2)}` : value;
};
const argText = (args, keys, max = 90) => {
    if (!args) return '';
    for (const key of keys) {
        const value = args[key];
        if (value == null || value === '') continue;
        const text = Array.isArray(value) ? value.join(' ') : (typeof value === 'object' ? JSON.stringify(value) : String(value));
        const trimmed = previewText(text, max);
        if (trimmed) return trimmed;
    }
    return '';
};
const firstUrl = (value) => {
    const match = String(value || '').match(/https?:\/\/[^\s"'<>]+/i);
    return match ? match[0].replace(/[),.;，。]+$/, '') : '';
};
const toolPreview = (e) => {
    const tool = e.tool || '';
    const args = e.args || {};
    const resultUrl = firstUrl(e.result);
    const argUrl = firstUrl(argText(args, ['url', 'href', 'target'], 140));
    const codeUrl = firstUrl(args.code || args.script);
    if (resultUrl) return previewText(resultUrl, 140);
    if (argUrl) return previewText(argUrl, 140);
    if (codeUrl) return previewText(codeUrl, 140);
    if (tool.includes('search')) return argText(args, ['query', 'q', 'keyword', 'keywords'], 120);
    if (['read_file', 'write_file', 'file_replace', 'str_replace_editor', 'download_file', 'parse_document', 'convert_to_markdown', 'view_image'].includes(tool)) {
        return argText(args, ['path', 'file_path', 'filePath', 'target_file', 'targetFile', 'source_path', 'sourcePath', 'relativePath'], 120);
    }
    if (tool === 'list_files') return argText(args, ['path', 'directory', 'dir'], 120);
    if (tool === 'execute_command') return argText(args, ['command', 'cmd'], 120);
    if (tool === 'browser_action') return argText(args, ['action_type', 'key', 'keys'], 80);
    if (tool === 'todo_write' && Array.isArray(args.todos)) return `${args.todos.length} 项`;
    return '';
};
const processTitle = (e) => {
    if (e.type === 'toolCall' || e.type === 'toolResult') {
        const reason = e.displayReason || '';
        if (reason) return e.type === 'toolResult' ? completedActionText(reason) : reason;
        return e.type === 'toolResult' ? '已处理当前任务' : '正在处理当前任务';
    }
    switch (e.type) {
        case 'plan': return '规划任务';
        case 'thinking': return '已思考';
        case 'reasoning': return '已推理';
        case 'status': return (e.content || '').length > 40 ? (e.content || '').substring(0, 40) + '...' : (e.content || '状态更新');
        default: return '处理';
    }
};
const processPreview = (e) => {
    if (e.type === 'toolCall' || e.type === 'toolResult') {
        const meta = toolPreview(e);
        if (meta) return meta;
    }
    if (e.type === 'toolCall') return e.elapsed ? `执行中 ${e.elapsed}ms` : '执行中';
    if (e.type === 'toolResult') return e.duration != null ? `${e.duration}ms` : '已完成';
    return previewText(e.content, 90);
};

// 把扁平事件流按 stepIndex 聚合成轮：流式与历史共用同一套分层渲染。
// stepIndex 缺失时回退 groups.length（新建独立轮），避免无序号事件堆到第 0 轮。
const ChatStepGrouper = {
    group(events) {
        const groups = [];
        const stepMap = new Map();
        const ensureStep = (stepIndex) => {
            let g = stepMap.get(stepIndex);
            if (!g) {
                g = { kind: 'step', stepIndex, thinking: '', reasoning: '', tools: [] };
                stepMap.set(stepIndex, g);
                groups.push(g);
            }
            return g;
        };
        for (const e of events || []) {
            if (e.type === 'plan') { groups.push({ kind: 'plan', content: e.content || '' }); continue; }
            if (e.type === 'status') { groups.push({ kind: 'status', content: e.content || '' }); continue; }
            const si = e.stepIndex != null ? e.stepIndex : groups.length;
            const g = ensureStep(si);
            if (e.type === 'thinking') g.thinking = e.content || '';
            else if (e.type === 'reasoning') g.reasoning = e.content || '';
            else if (e.type === 'toolCall' || e.type === 'toolResult') g.tools.push(e);
        }
        return groups;
    },
    allDone(group) {
        return Array.isArray(group?.tools) && group.tools.length > 0
            && group.tools.every(t => t.status === 'completed');
    },
    // 返回 {title, badge}：title 进 nowrap 标题列（短），badge 进可收缩的 preview 列，避免长串撑爆布局。
    overview(group) {
        if (!group || group.kind !== 'step') {
            if (group?.kind === 'plan') return { title: '规划任务', badge: '' };
            if (group?.kind === 'status') return { title: '状态更新', badge: '' };
            return { title: '处理中', badge: '' };
        }
        const tools = group.tools || [];
        if (!tools.length) return { title: group.thinking ? '思考中…' : '处理中', badge: '' };
        const names = tools.map(t => String(t.tool || ''));
        const allSearch = names.every(n => n.toLowerCase().includes('search'));
        const allCommand = names.every(n => n === 'execute_command' || n === 'execute_bash');
        const action = allSearch ? '搜索' : (allCommand ? '命令运行' : '执行');
        if (this.allDone(group)) return { title: `已完成 ${tools.length} 个工具`, badge: '' };
        const running = tools.filter(t => t.status === 'running').length;
        return { title: `${action}中…`, badge: `${tools.length} 个工具 · ${running} 进行中` };
    },
};
if (typeof window !== 'undefined') window.ChatStepGrouper = ChatStepGrouper;

// 单个工具调用行：流式与历史共用，消除两处手写模板重复。
const ChatToolStep = {
    props: {
        event: { type: Object, required: true },
    },
    template: `
        <details class="process-item">
            <summary>
                <span :class="['process-dot', event.status === 'running' ? 'active' : 'completed']"></span>
                <span class="process-item-title">{{ processTitle(event) }}</span>
                <span class="process-preview">{{ processPreview(event) }}</span>
            </summary>
            <div class="process-detail">
                <div v-if="event.displayReason" class="process-tool-reason">{{ event.displayReason }}</div>
                <div class="process-tool-args">参数<pre>{{ JSON.stringify(event.args, null, 2) }}</pre></div>
                <div v-if="event.type === 'toolResult'">结果<pre>{{ event.result }}</pre></div>
                <div v-else>执行中...</div>
            </div>
        </details>
    `,
    methods: { processTitle, processPreview },
};

// 对话页组件
const ChatPage = {
    components: { ChatArtifactCard, ChatArtifactGallery, ChatToolStep },
    template: `
        <div
            class="chat-workspace chatgpt-workspace"
            :class="{ 'tool-dock-open': toolDockOpen, 'tool-dock-resizing': isToolDockResizing }"
            :style="{ '--tool-dock-width': toolDockWidth + 'px' }"
        >
            <!-- 中间：聊天主区 -->
            <div class="chat-main">
                <!-- 对话面板 -->
                <div class="chat-panel chatgpt-panel" style="position:relative;">
                    <!-- 聊天头部（精简） -->
                    <div class="chat-header">
                        <div class="chat-header-left">
                            <span class="chat-app-tag" v-if="currentApp">{{ currentApp.name }}</span>
                            <span class="chat-app-tag chat-app-tag-default" v-else>通用对话</span>
                            <span class="chat-session-id" v-if="currentSessionId">#{{ currentSessionId.substring(0, 8) }}</span>
                        </div>
                        <div class="chat-header-right">
                            <button class="btn btn-ghost btn-sm" @click="copyId" v-if="currentSessionId">
                                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                                {{ copied ? '已复制' : '复制ID' }}
                            </button>
                            <div class="chat-tool-switches" aria-label="右侧工具">
                                <button
                                    type="button"
                                    class="chat-tool-switch"
                                    :class="{ active: activeToolDock === 'sandbox' }"
                                    @click="toggleToolDock('sandbox')"
                                    title="沙箱"
                                >
                                    <span class="chat-tool-switch-icon">
                                        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
                                    </span>
                                    <span>沙箱</span>
                                </button>
                                <button
                                    type="button"
                                    class="chat-tool-switch"
                                    :class="{ active: activeToolDock === 'workspace' }"
                                    @click="toggleToolDock('workspace')"
                                    title="工作目录"
                                >
                                    <span class="chat-tool-switch-icon">
                                        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
                                    </span>
                                    <span>工作目录</span>
                                </button>
                            </div>
                        </div>
                    </div>

                    <!-- 消息区 -->
                    <div class="chat-messages" ref="messagesEl" @click="handleMessageContentClick">
                        <!-- 加载骨架屏 -->
                        <template v-if="loadingHistory">
                            <div class="skeleton-message user">
                                <div class="skeleton-content"><div class="skeleton skeleton-bubble user" style="width:60%; height:36px;"></div></div>
                            </div>
                            <div class="skeleton-message assistant">
                                <div class="skeleton skeleton-avatar" style="width:32px;height:32px;"></div>
                                <div class="skeleton-content">
                                    <div class="skeleton skeleton-text" style="width:90%;"></div>
                                    <div class="skeleton skeleton-text" style="width:75%;"></div>
                                    <div class="skeleton skeleton-text" style="width:40%;"></div>
                                </div>
                            </div>
                            <div class="skeleton-message user">
                                <div class="skeleton-content"><div class="skeleton skeleton-bubble user" style="width:40%; height:36px;"></div></div>
                            </div>
                            <div class="skeleton-message assistant">
                                <div class="skeleton skeleton-avatar" style="width:32px;height:32px;"></div>
                                <div class="skeleton-content">
                                    <div class="skeleton skeleton-text" style="width:80%;"></div>
                                    <div class="skeleton skeleton-text" style="width:55%;"></div>
                                </div>
                            </div>
                        </template>

                        <!-- 空状态 -->
                        <div v-if="!loadingHistory && displayMessages.length === 0 && !streaming" class="chat-empty-hero">
                            <div class="chat-empty-mark">AI</div>
                            <h1>今天想让 WebAgent 做什么？</h1>
                            <p>可以上传文档、整理网页、生成结构图，也可以让智能体继续操作沙箱工作区。</p>
                            <div class="chat-empty-hints" aria-hidden="true">
                                <span>上传实验报告</span>
                                <span>分析网页内容</span>
                                <span>生成结构图</span>
                            </div>
                        </div>

                        <!-- 历史消息 -->
                        <template v-for="msg in displayMessages" :key="msg.timestamp">
                            <div :class="['chat-message', msg.role, msg.error ? 'has-error' : '']">
                                <div class="role-label">{{ msg.role === 'user' ? '你' : '助手' }}</div>
                                <div class="bubble">
                                    <template v-if="msg.role === 'assistant'">
                                        <details v-if="msg.events && msg.events.length > 0" class="process-disclosure">
                                            <summary class="process-summary">
                                                <span class="process-check">✓</span>
                                                <span>已处理</span>
                                                <span class="process-count">{{ historySteps(msg).length }} 轮</span>
                                            </summary>
                                            <div class="process-timeline">
                                                <details v-for="(group, gi) in historySteps(msg)" :key="msg.timestamp + '-step-' + gi" class="process-item live-step">
                                                    <summary v-if="group.kind === 'step'">
                                                        <span :class="['process-dot', stepAllDone(group) ? 'completed' : 'active']"></span>
                                                        <span class="process-item-title">{{ stepOverview(group) }}</span>
                                                        <span v-if="stepOverviewBadge(group)" class="process-preview">{{ stepOverviewBadge(group) }}</span>
                                                    </summary>
                                                    <summary v-else-if="group.kind === 'plan'">
                                                        <span class="process-dot completed"></span>
                                                        <span class="process-item-title">规划任务</span>
                                                    </summary>
                                                    <summary v-else>
                                                        <span class="process-dot active"></span>
                                                        <span class="process-item-title">{{ group.content ? previewText(group.content, 40) : '状态更新' }}</span>
                                                    </summary>
                                                    <div class="process-detail" v-if="group.kind === 'step'">
                                                        <div v-if="group.thinking" class="process-step-text" v-html="renderMarkdown(group.thinking)"></div>
                                                        <div v-if="group.reasoning" class="process-step-text" v-html="renderMarkdown(group.reasoning)"></div>
                                                        <div v-if="group.tools && group.tools.length" class="process-timeline">
                                                            <chat-tool-step v-for="(tool, ti) in group.tools" :key="msg.timestamp + '-tool-' + gi + '-' + ti" :event="tool"></chat-tool-step>
                                                        </div>
                                                    </div>
                                                    <div class="process-detail" v-else v-html="renderMarkdown(group.content || '')"></div>
                                                </details>
                                            </div>
                                        </details>
                                        <div class="assistant-answer" v-html="renderMarkdown(msg.content || '')"></div>
                                        <chat-artifact-gallery
                                            v-if="messageArtifacts(msg).length"
                                            class="chat-artifacts"
                                            :artifacts="messageArtifacts(msg)"
                                            :blob-url-for="artifactBlobUrl"
                                            :load-error-for="artifactLoadError"
                                            @preview="previewArtifact"
                                            @preview-gallery="previewArtifactGallery"
                                            @download="downloadArtifact"
                                        ></chat-artifact-gallery>
                                        <div v-if="msg.error" class="error-retry">
                                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
                                            <span>{{ msg.error }}</span>
                                            <button class="btn btn-sm btn-secondary" @click="retryMessage(msg)">重试</button>
                                        </div>
                                    </template>
                                    <div v-else v-html="renderContent(msg)"></div>
                                </div>
                                <div class="msg-time">{{ formatTime(msg.timestamp) }}</div>
                            </div>
                        </template>

                        <!-- 流式回复 -->
                        <div v-if="streaming" class="chat-message assistant streaming">
                            <div class="role-label">助手</div>
                            <div class="bubble agent-stream-card">
                                <details class="process-disclosure live-process" open>
                                    <summary class="process-summary">
                                        <span class="thinking-spinner small"></span>
                                        <span>{{ streamingStatus }}</span>
                                        <span v-if="groupedSteps.length" class="process-count">{{ groupedSteps.length }} 轮</span>
                                    </summary>
                                    <div class="process-timeline">
                                        <details v-for="(group, gi) in groupedSteps" :key="'live-step-' + gi"
                                                 class="process-item live-step" :open="gi === groupedSteps.length - 1">
                                            <summary v-if="group.kind === 'step'">
                                                <span :class="['process-dot', stepAllDone(group) ? 'completed' : 'active']"></span>
                                                <span class="process-item-title">{{ stepOverview(group) }}</span>
                                                <span v-if="stepOverviewBadge(group)" class="process-preview">{{ stepOverviewBadge(group) }}</span>
                                            </summary>
                                            <summary v-else-if="group.kind === 'plan'">
                                                <span class="process-dot completed"></span>
                                                <span class="process-item-title">规划任务</span>
                                            </summary>
                                            <summary v-else>
                                                <span class="process-dot active"></span>
                                                <span class="process-item-title">{{ group.content ? previewText(group.content, 40) : '状态更新' }}</span>
                                            </summary>
                                            <div class="process-detail" v-if="group.kind === 'step'">
                                                <div v-if="group.thinking" class="process-step-text" v-html="renderMarkdown(group.thinking)"></div>
                                                <div v-if="group.reasoning" class="process-step-text" v-html="renderMarkdown(group.reasoning)"></div>
                                                <div v-if="group.tools && group.tools.length" class="process-timeline">
                                                    <chat-tool-step v-for="(tool, ti) in group.tools" :key="'live-tool-' + gi + '-' + ti" :event="tool"></chat-tool-step>
                                                </div>
                                            </div>
                                            <div class="process-detail" v-else v-html="renderMarkdown(group.content || '')"></div>
                                        </details>
                                        <details v-if="currentReasoning" class="process-item active" open>
                                            <summary>
                                                <span class="process-dot active"></span>
                                                <span class="process-item-title">实时推理中…</span>
                                                <span class="process-preview">{{ previewText(currentReasoning, 80) }}</span>
                                            </summary>
                                            <div class="process-detail" v-html="renderMarkdown(currentReasoning)"></div>
                                        </details>
                                    </div>
                                </details>
                                <div v-if="currentThinking" class="live-thinking">
                                    <div v-html="renderMarkdown(currentThinking)"></div>
                                    <span class="stream-cursor"></span>
                                </div>
                                <div v-if="finalAnswer && !finalAnswerSaved" class="live-final-answer" v-html="renderMarkdown(finalAnswer)"></div>
                                <chat-artifact-gallery
                                    v-if="liveArtifacts.length"
                                    class="chat-artifacts"
                                    :artifacts="liveArtifacts"
                                    :blob-url-for="artifactBlobUrl"
                                    :load-error-for="artifactLoadError"
                                    @preview="previewArtifact"
                                    @preview-gallery="previewArtifactGallery"
                                    @download="downloadArtifact"
                                ></chat-artifact-gallery>
                            </div>
                        </div>

                        <!-- 回到底部 -->
                        <button v-if="showScrollBtn" class="scroll-to-bottom" @click="scrollToBottom" title="回到底部">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>
                        </button>
                    </div>

                    <!-- Chat Composer -->
                    <div class="chat-composer">
                        <div class="composer-files" v-if="pendingFiles.length > 0">
                            <template v-for="(file, index) in pendingFiles" :key="file.name + '-' + index">
                                <article v-if="isImageFile(file)" class="composer-image-card">
                                    <button
                                        type="button"
                                        class="composer-image-preview"
                                        @click="previewPendingImage(file)"
                                        :aria-label="'预览 ' + file.name"
                                    >
                                        <img :src="pendingImagePreviewUrl(file)" :alt="file.name">
                                    </button>
                                    <button
                                        type="button"
                                        class="composer-file-remove composer-image-remove"
                                        @click="removeFile(index)"
                                        :aria-label="'移除 ' + file.name"
                                    >×</button>
                                </article>
                                <article v-else class="composer-file-card">
                                    <span :class="['composer-file-icon', composerFilePresentation(file).iconClass]">
                                        {{ composerFilePresentation(file).iconText }}
                                    </span>
                                    <span class="composer-file-info">
                                        <strong :title="file.name">{{ file.name }}</strong>
                                        <small>{{ composerFileMeta(file) }}</small>
                                    </span>
                                    <button
                                        type="button"
                                        class="composer-file-remove"
                                        @click="removeFile(index)"
                                        :aria-label="'移除 ' + file.name"
                                    >×</button>
                                </article>
                            </template>
                        </div>

                        <textarea
                            v-model="inputText"
                            class="composer-input"
                            placeholder="输入消息，Enter 发送，Shift + Enter 换行"
                            rows="1"
                            :disabled="sending"
                            @keydown.enter.exact.prevent="send"
                            @input="autoResize"
                            ref="composerInput"
                        ></textarea>

                        <div class="composer-toolbar">
                            <div class="composer-tools-left">
                                <label class="composer-tool-btn" title="添加文件">
                                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                        <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/>
                                    </svg>
                                    <span>文件</span>
                                    <input type="file" multiple @change="handleFileSelect">
                                </label>

                                <button
                                    type="button"
                                    class="composer-tool-btn"
                                    :class="{ active: searchEnabled }"
                                    @click="toggleSearch"
                                    :title="searchEnabled ? '已启用联网搜索' : '启用联网搜索'"
                                >
                                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                        <circle cx="12" cy="12" r="10"/>
                                        <line x1="2" y1="12" x2="22" y2="12"/>
                                        <path d="M12 2a15.3 15.3 0 0 1 0 20"/>
                                        <path d="M12 2a15.3 15.3 0 0 0 0 20"/>
                                    </svg>
                                    <span>{{ searchEnabled ? '联网已开' : '联网搜索' }}</span>
                                </button>

                                <button
                                    type="button"
                                    class="composer-tool-btn"
                                    :class="{ active: planningEnabled }"
                                    @click="togglePlanning"
                                    :title="planningEnabled ? '已启用规划模式' : '启用规划模式'"
                                >
                                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                        <path d="M9 6h11"/>
                                        <path d="M9 12h11"/>
                                        <path d="M9 18h11"/>
                                        <path d="M4 6h1"/>
                                        <path d="M4 12h1"/>
                                        <path d="M4 18h1"/>
                                    </svg>
                                    <span>{{ planningEnabled ? '规划已开' : '规划模式' }}</span>
                                </button>
                            </div>

                            <button v-if="streaming" @click="stopStream" class="composer-send-btn stop-btn" title="停止生成">
                                <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                                    <rect x="6" y="6" width="12" height="12" rx="1"/>
                                </svg>
                            </button>

                            <button
                                v-else
                                @click="send"
                                :disabled="sending || (!inputText.trim() && pendingFiles.length === 0)"
                                class="composer-send-btn"
                                title="发送"
                            >
                                <svg v-if="!sending" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                                    <line x1="22" y1="2" x2="11" y2="13"/>
                                    <polygon points="22 2 15 22 11 13 2 9 22 2"/>
                                </svg>
                                <span v-else class="thinking-spinner small" style="border-color:rgba(255,255,255,0.3);border-top-color:#fff;"></span>
                            </button>
                        </div>
                    </div>
                </div>

            </div>

            <!-- 右侧：Agent + 会话面板 -->
            <aside class="chat-sidebar chatgpt-sidebar">
                <div class="chat-sidebar-brand">
                    <div class="chat-sidebar-brand-copy">
                        <strong>WebAgent</strong>
                        <span>Clean</span>
                    </div>
                </div>

                <nav class="chat-sidebar-nav" aria-label="主导航">
                    <button type="button" class="chat-sidebar-link chat-sidebar-link-active" @click="createSession">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>
                        <span>新对话</span>
                    </button>
                    <router-link to="/apps" class="chat-sidebar-link">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></svg>
                        <span>Agent</span>
                    </router-link>
                    <router-link to="/skills" class="chat-sidebar-link">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m13 2-9 12h8l-1 8 9-12h-8Z"/></svg>
                        <span>Skill</span>
                    </router-link>
                    <router-link to="/knowledge" class="chat-sidebar-link">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z"/></svg>
                        <span>文件库</span>
                    </router-link>
                    <router-link to="/mcp" class="chat-sidebar-link">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>
                        <span>MCP</span>
                    </router-link>
                    <router-link to="/token-stats" class="chat-sidebar-link">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>
                        <span>Token</span>
                    </router-link>
                </nav>
                <!-- Agent 选择区 -->
                <div class="sidebar-section">
                    <div class="sidebar-section-header">
                        <span class="sidebar-section-title">智能体</span>
                        <span class="sidebar-section-count">{{ apps.length + 1 }}</span>
                    </div>
                    <div class="agent-list">
                        <div class="agent-item" :class="{ active: currentAppId === '' }" @click="selectApp('')">
                            <div class="agent-avatar agent-avatar-default">
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg>
                            </div>
                            <div class="agent-info">
                                <div class="agent-name">通用对话</div>
                                <div class="agent-desc">无配置</div>
                            </div>
                        </div>
                        <div v-for="app in apps" :key="app.id"
                             class="agent-item"
                             :class="{ active: currentAppId == app.id }"
                             @click="selectApp(app.id)">
                            <div class="agent-avatar">{{ app.name ? app.name.charAt(0).toUpperCase() : 'A' }}</div>
                            <div class="agent-info">
                                <div class="agent-name">{{ app.name }}</div>
                                <div class="agent-desc">
                                    <span v-if="app.knowledgeBaseIds && app.knowledgeBaseIds.length">{{ app.knowledgeBaseIds.length }} KB</span>
                                    <span v-if="app.skillIds && app.skillIds.length">{{ app.skillIds.length }} Skill</span>
                                    <span v-if="(!app.knowledgeBaseIds || !app.knowledgeBaseIds.length) && (!app.skillIds || !app.skillIds.length)">{{ app.description || '无配置' }}</span>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- 会话列表 -->
                <div class="sidebar-section sidebar-section-flex">
                    <div class="sidebar-section-header">
                        <span class="sidebar-section-title">聊天</span>
                        <div class="session-header-actions">
                            <button v-if="filteredSessions.length"
                                    type="button"
                                    class="session-manage-btn"
                                    @click="toggleSessionManagement">
                                {{ sessionManageMode ? '完成' : '管理' }}
                            </button>
                            <button v-if="!sessionManageMode" class="btn-icon btn-icon-sm" @click="createSession" title="新建会话">
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                            </button>
                        </div>
                    </div>
                    <div class="session-list" v-if="filteredSessions.length > 0">
                        <div v-for="s in filteredSessions"
                             :key="s.sessionId"
                             class="session-item"
                             :class="{
                                 active: !sessionManageMode && currentSessionId === s.sessionId,
                                 selected: sessionManageMode && isSessionSelected(s.sessionId)
                             }"
                             @click="handleSessionItemClick(s.sessionId)">
                            <button v-if="sessionManageMode"
                                    type="button"
                                    class="session-select-check"
                                    :class="{ checked: isSessionSelected(s.sessionId) }"
                                    :aria-label="isSessionSelected(s.sessionId) ? '取消选择会话' : '选择会话'"
                                    @click.stop="toggleSessionSelection(s.sessionId)">
                                <svg v-if="isSessionSelected(s.sessionId)" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>
                            </button>
                            <svg v-else width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
                            <div class="session-info">
                                <div class="session-title">{{ sessionTitle(s) }}</div>
                                <div class="session-meta">
                                    <span v-if="s.enabledSkillIds && s.enabledSkillIds.length">{{ s.enabledSkillIds.length }} 技能</span>
                                </div>
                            </div>
                            <button v-if="!sessionManageMode"
                                    class="session-delete-btn"
                                    :disabled="deletingSessionId === s.sessionId"
                                    @click.stop="requestDeleteSession(s)"
                                    title="删除会话">
                                <span v-if="deletingSessionId === s.sessionId" class="thinking-spinner tiny"></span>
                                <svg v-else width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M10 11v6"/><path d="M14 11v6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                            </button>
                        </div>
                    </div>
                    <div v-if="sessionManageMode && filteredSessions.length" class="session-batch-bar">
                        <button type="button" class="session-batch-select-all" @click="toggleSelectAllSessions">
                            {{ allFilteredSessionsSelected ? '取消全选' : '全选' }}
                        </button>
                        <span>已选 {{ selectedSessionCount }} 个</span>
                        <button type="button"
                                class="session-batch-delete"
                                :disabled="selectedSessionCount === 0"
                                @click="requestBatchDelete">
                            删除
                        </button>
                    </div>
                    <div v-if="filteredSessions.length === 0" class="sidebar-empty">
                        <p>暂无会话</p>
                        <button class="btn btn-primary btn-sm" @click="createSession">
                            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                            新建会话
                        </button>
                    </div>
                </div>

                <div class="chat-sidebar-account">
                    <span class="chat-sidebar-avatar">{{ store.username ? store.username.charAt(0).toUpperCase() : 'U' }}</span>
                    <span class="chat-sidebar-user">{{ store.username || '用户' }}</span>
                    <button type="button" @click="logout" title="退出登录">
                        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
                    </button>
                </div>
            </aside>

            <div v-if="toolDockOpen" class="chat-tool-resize-handle" @mousedown="startToolDockResize"></div>
            <aside v-if="toolDockOpen" class="chat-tool-dock">
                <header class="chat-tool-dock-header">
                    <div class="chat-tool-dock-title">
                        <span class="chat-tool-dock-icon">
                            <svg v-if="activeToolDock === 'sandbox'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
                            <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
                        </span>
                        <div>
                            <strong>{{ toolDockTitle }}</strong>
                            <small>{{ toolDockSubtitle }}</small>
                        </div>
                    </div>
                    <button type="button" class="chat-tool-dock-close" @click="closeToolDock" title="关闭">
                        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                    </button>
                </header>
                <div class="chat-tool-dock-body">
                    <section v-if="activeToolDock === 'sandbox'" class="tool-sandbox-panel">
                        <div class="tool-sandbox-status">
                            <div class="sandbox-view-menu" role="tablist" aria-label="沙箱视图">
                                <button
                                    v-for="view in sandboxViews"
                                    :key="view.id"
                                    type="button"
                                    class="sandbox-view-option"
                                    :class="{ active: activeSandboxView === view.id }"
                                    :title="view.title"
                                    @click="switchSandboxView(view.id)"
                                >
                                    <span class="sandbox-view-option-icon" aria-hidden="true">
                                        <svg v-if="view.id === 'browser'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 0 20"/><path d="M12 2a15.3 15.3 0 0 0 0 20"/></svg>
                                        <svg v-else-if="view.id === 'terminal'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>
                                        <svg v-else-if="view.id === 'vscode'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M8 8l-3 4 3 4"/><path d="M16 8l3 4-3 4"/></svg>
                                        <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
                                    </span>
                                    <span>{{ view.label }}</span>
                                </button>
                            </div>
                            <div class="tool-sandbox-actions">
                                <span>{{ vncStatus }}</span>
                                <button type="button" @click="loadVncView" :disabled="!currentSessionId || isSandboxResetting">刷新</button>
                                <button type="button" @click="resetSandbox" :disabled="!currentSessionId || isSandboxResetting">
                                    {{ isSandboxResetting ? '重置中...' : '重置' }}
                                </button>
                            </div>
                        </div>
                        <div class="tool-sandbox-frame">
                            <iframe v-if="vncUrl" :src="vncUrl"></iframe>
                            <div v-else class="vnc-placeholder">
                                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" opacity="0.35"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
                                <span>{{ vncPlaceholder }}</span>
                            </div>
                        </div>
                    </section>
                    <workspace-browser v-else-if="activeToolDock === 'workspace'" embedded></workspace-browser>
                </div>
            </aside>

            <!-- 删除会话确认框：说明删除边界，避免用户误以为沙箱文件也会被清理。 -->
            <div v-if="sessionPendingDelete" class="modal-overlay session-delete-overlay" @click.self="cancelDeleteSession">
                <section class="modal-content modal-sm session-delete-dialog" role="dialog" aria-modal="true" aria-labelledby="session-delete-title">
                    <button class="modal-close session-delete-close"
                            type="button"
                            :disabled="deletingSessionId === sessionPendingDelete.sessionId"
                            @click="cancelDeleteSession"
                            aria-label="关闭删除确认框">×</button>
                    <div class="session-delete-heading">
                        <span class="session-delete-warning" aria-hidden="true">
                            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M8 6V4h8v2"/><path d="M19 6l-1 14H6L5 6"/><path d="M10 11v5"/><path d="M14 11v5"/></svg>
                        </span>
                        <div>
                            <h3 id="session-delete-title">删除这个会话？</h3>
                            <p>会话 <strong>#{{ sessionPendingDelete.sessionId.substring(0, 8) }}</strong> 删除后无法恢复。</p>
                        </div>
                    </div>
                    <div class="session-delete-scope">
                        <div>
                            <span class="session-delete-scope-icon danger">−</span>
                            <span><strong>将删除</strong>聊天记录与工具执行过程</span>
                        </div>
                        <div>
                            <span class="session-delete-scope-icon safe">✓</span>
                            <span><strong>不会删除</strong>沙箱工作区中的文件</span>
                        </div>
                    </div>
                    <p v-if="deleteSessionError" class="session-delete-error">{{ deleteSessionError }}</p>
                    <div class="session-delete-actions">
                        <button type="button"
                                class="btn btn-secondary"
                                :disabled="deletingSessionId === sessionPendingDelete.sessionId"
                                @click="cancelDeleteSession">取消</button>
                        <button type="button"
                                class="btn btn-danger session-delete-confirm"
                                :disabled="deletingSessionId === sessionPendingDelete.sessionId"
                                @click="confirmDeleteSession">
                            <span v-if="deletingSessionId === sessionPendingDelete.sessionId" class="thinking-spinner tiny"></span>
                            {{ deletingSessionId === sessionPendingDelete.sessionId ? '正在删除' : '删除会话' }}
                        </button>
                    </div>
                </section>
            </div>

            <!-- 批量删除确认框：复用单删的视觉语言，并明确本次影响的会话数量。 -->
            <div v-if="batchDeletePending" class="modal-overlay session-delete-overlay" @click.self="cancelBatchDelete">
                <section class="modal-content modal-sm session-delete-dialog" role="dialog" aria-modal="true" aria-labelledby="batch-delete-title">
                    <button class="modal-close session-delete-close"
                            type="button"
                            :disabled="batchDeleting"
                            @click="cancelBatchDelete"
                            aria-label="关闭批量删除确认框">×</button>
                    <div class="session-delete-heading">
                        <span class="session-delete-warning" aria-hidden="true">
                            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M8 6V4h8v2"/><path d="M19 6l-1 14H6L5 6"/><path d="M10 11v5"/><path d="M14 11v5"/></svg>
                        </span>
                        <div>
                            <h3 id="batch-delete-title">删除 {{ selectedSessionCount }} 个会话？</h3>
                            <p>所选会话删除后无法恢复。</p>
                        </div>
                    </div>
                    <div class="session-delete-scope">
                        <div>
                            <span class="session-delete-scope-icon danger">−</span>
                            <span><strong>将删除</strong>所选会话的聊天记录与工具执行过程</span>
                        </div>
                        <div>
                            <span class="session-delete-scope-icon safe">✓</span>
                            <span><strong>不会删除</strong>沙箱工作区中的文件</span>
                        </div>
                    </div>
                    <p v-if="batchDeleteError" class="session-delete-error">{{ batchDeleteError }}</p>
                    <div class="session-delete-actions">
                        <button type="button"
                                class="btn btn-secondary"
                                :disabled="batchDeleting"
                                @click="cancelBatchDelete">取消</button>
                        <button type="button"
                                class="btn btn-danger session-delete-confirm"
                                :disabled="batchDeleting || selectedSessionCount === 0"
                                @click="confirmBatchDelete">
                            <span v-if="batchDeleting" class="thinking-spinner tiny"></span>
                            {{ batchDeleting ? '正在删除' : '删除所选会话' }}
                        </button>
                    </div>
                </section>
            </div>
        </div>
    `,
    setup() {
        marked.use({ highlight: (c, l) => l && hljs.getLanguage(l) ? hljs.highlight(c, { language: l }).value : hljs.highlightAuto(c).value, breaks: true, gfm: true });

        const store = Vue.inject('store');
        const router = VueRouter.useRouter();
        const logout = () => { store.logout(); router.push('/login'); };
        const messagesEl = Vue.ref(null);

        const apps = Vue.ref([]);
        const sessions = Vue.ref([]);
        // 控制会话列表是否进入多选管理模式。
        const sessionManageMode = Vue.ref(false);
        // 保存当前管理范围内已选择的会话 ID；每次修改都替换 Set 以触发 Vue 更新。
        const selectedSessionIds = Vue.ref(new Set());
        // 控制批量删除确认框和请求状态。
        const batchDeletePending = Vue.ref(false);
        const batchDeleteError = Vue.ref('');
        const batchDeleting = Vue.ref(false);
        const DEFAULT_SESSION_TITLE = '新对话';
        // 保存等待用户确认删除的会话，未打开确认框时为 null。
        const sessionPendingDelete = Vue.ref(null);
        // 保存删除失败原因，让错误与当前确认操作保持在同一弹窗内。
        const deleteSessionError = Vue.ref('');
        const deletingSessionId = Vue.ref('');
        const currentAppId = Vue.ref('');
        const currentSessionId = Vue.ref(store.currentSessionId || '');
        const draftSessionActive = Vue.ref(false);
        const messages = Vue.ref([]);
        const inputText = Vue.ref('');
        const pendingFiles = Vue.ref([]);
        const pendingImagePreviewUrls = Vue.reactive({});
        const sentUploadPreviews = Vue.reactive({});
        const searchEnabled = Vue.ref(localStorage.getItem('web_search_enabled') === 'true');
        const planningEnabled = Vue.ref(localStorage.getItem('planning_enabled') !== 'false');

        const toggleSearch = () => {
            searchEnabled.value = !searchEnabled.value;
            localStorage.setItem('web_search_enabled', searchEnabled.value ? 'true' : 'false');
        };
        const togglePlanning = () => {
            planningEnabled.value = !planningEnabled.value;
            localStorage.setItem('planning_enabled', planningEnabled.value ? 'true' : 'false');
        };
        const copied = Vue.ref(false);
        const loadingHistory = Vue.ref(false);
        const showScrollBtn = Vue.ref(false);

        // 当前会话的流式状态来自全局 store，确保切会话或切页面后思考链不会串线或丢失。
        const currentLiveStream = Vue.computed(() => store.getLiveStream(currentSessionId.value));
        const streaming = Vue.computed(() => Boolean(currentLiveStream.value?.streaming));
        const sending = Vue.computed(() => Boolean(currentLiveStream.value?.sending));
        const currentThinking = Vue.computed(() => currentLiveStream.value?.currentThinking || '');
        const currentReasoning = Vue.computed(() => currentLiveStream.value?.currentReasoning || '');
        const currentToolCall = Vue.computed(() => currentLiveStream.value?.currentToolCall || null);
        const finalAnswer = Vue.computed(() => currentLiveStream.value?.finalAnswer || '');
        const finalAnswerSaved = Vue.computed(() => Boolean(currentLiveStream.value?.finalAnswerSaved));
        const streamPhase = Vue.computed(() => currentLiveStream.value?.streamPhase || 'idle');
        const currentEvents = Vue.computed(() => currentLiveStream.value?.currentEvents || []);
        const artifactBlobUrls = Vue.reactive({});
        const artifactLoadErrors = Vue.reactive({});
        const artifactLoadPromises = new Map();
        const displayMessages = Vue.computed(() => {
            const stream = currentLiveStream.value;
            if (!stream?.pendingUserMessage) return messages.value;
            if (messages.value.length >= stream.streamBaselineLength) return messages.value;
            return [...messages.value, stream.pendingUserMessage];
        });

        // 流式事件按 stepIndex 聚合成轮；纯函数见模块顶层 ChatStepGrouper。
        const groupedSteps = Vue.computed(() => ChatStepGrouper.group(currentEvents.value));
        // 历史消息事件同样按轮聚合，让流式与完成态走同一套分层渲染，避免收尾时视觉跳变。
        const historySteps = (msg) => ChatStepGrouper.group(msg?.events || []);
        const stepAllDone = ChatStepGrouper.allDone;
        const stepOverview = (group) => ChatStepGrouper.overview(group).title;
        const stepOverviewBadge = (group) => ChatStepGrouper.overview(group).badge;

        const streamingStatus = Vue.computed(() => {
            const phase = streamPhase.value;
            switch (phase) {
                case 'planning': return '正在规划';
                case 'plan_ready': return '规划完成';
                case 'answer': return '整理回答';
            }
            const groups = groupedSteps.value;
            const last = groups.length ? groups[groups.length - 1] : null;
            if (last && last.kind === 'step' && last.tools && last.tools.length) return stepOverview(last);
            switch (phase) {
                case 'thinking': return '正在思考';
                case 'generating': return '正在生成';
                case 'processing': return '正在处理';
                case 'tool': case 'tool_done': return last ? stepOverview(last) : '执行工具';
                default: return '处理中';
            }
        });

        const activeToolDock = Vue.ref('');
        const toolDockWidth = Vue.ref(460);
        const toolDockOpen = Vue.computed(() => Boolean(activeToolDock.value));
        const toolDockTitle = Vue.computed(() => activeToolDock.value === 'workspace' ? '工作目录' : '沙箱');
        const sandboxViews = [
            { id: 'browser', label: '浏览器', title: '打开浏览器视图', path: '/vnc/index.html?autoconnect=true&resize=scale', websocketPath: 'websockify' },
            { id: 'terminal', label: '终端', title: '打开 Web 终端', path: '/terminal' },
            { id: 'vscode', label: 'VSCode', title: '打开 VSCode 工作区', path: '/code-server/?folder=/home/gem' },
            { id: 'files', label: '文件', title: '打开工作目录', dock: 'workspace' },
        ];
        const activeSandboxView = Vue.ref('browser');
        const sandboxBaseUrl = Vue.ref('');
        const sandboxViewById = (id) => sandboxViews.find(view => view.id === id) || sandboxViews[0];
        const toolDockSubtitle = Vue.computed(() => {
            if (activeToolDock.value === 'workspace') return '当前会话文件';
            return sandboxViewById(activeSandboxView.value).label;
        });
        const isToolDockResizing = Vue.ref(false);
        const vncOpen = Vue.computed(() => activeToolDock.value === 'sandbox');
        const vncUrl = Vue.ref('');
        const vncStatus = Vue.ref('未连接');
        const vncPlaceholder = Vue.ref('请先创建会话');
        const isSandboxResetting = Vue.ref(false);
        let isResizing = false, startX = 0, pendingToolDockWidth = 0, toolDockResizeFrame = 0;

        const currentApp = Vue.computed(() => {
            if (!currentAppId.value) return null;
            return apps.value.find(a => a.id == currentAppId.value) || null;
        });

        const filteredSessions = Vue.computed(() => {
            if (!currentAppId.value) return sessions.value.filter(s => !s.appId);
            return sessions.value.filter(s => s.appId == currentAppId.value);
        });
        const selectedSessionCount = Vue.computed(() => selectedSessionIds.value.size);
        const allFilteredSessionsSelected = Vue.computed(() =>
            filteredSessions.value.length > 0
            && filteredSessions.value.every(session => selectedSessionIds.value.has(session.sessionId))
        );

        const loadApps = async () => {
            try { apps.value = await api.listApps(); } catch (e) { apps.value = []; }
        };

        const loadSessions = async () => {
            try {
                const result = await api.listSessions();
                sessions.value = Array.isArray(result) ? result.filter(s => s && s.sessionId) : [];
            } catch (e) { sessions.value = []; }
        };

        const sessionTitle = (session) => {
            const title = session?.title ? String(session.title).trim() : '';
            return title || DEFAULT_SESSION_TITLE;
        };

        // 将新建或刷新的会话合并进列表，避免为了一个标题刷新重拉整页状态。
        const upsertSession = (session) => {
            if (!session || !session.sessionId) return;
            const index = sessions.value.findIndex(s => s.sessionId === session.sessionId);
            if (index >= 0) {
                sessions.value.splice(index, 1, { ...sessions.value[index], ...session });
                return;
            }
            sessions.value.unshift(session);
        };

        const titleRefreshTimers = new Map();
        // 首轮回复完成后标题在后端异步生成；这里短轮询当前会话，拿到标题后停止。
        const scheduleSessionTitleRefresh = (sessionId) => {
            if (!sessionId || titleRefreshTimers.has(sessionId)) return;
            let attempts = 0;
            const refresh = async () => {
                attempts += 1;
                try {
                    const session = await api.getSession(sessionId);
                    upsertSession(session);
                    if (sessionTitle(session) !== DEFAULT_SESSION_TITLE) {
                        titleRefreshTimers.delete(sessionId);
                        return;
                    }
                } catch (e) {
                    console.warn('刷新会话标题失败:', e);
                }
                if (attempts >= 8) {
                    titleRefreshTimers.delete(sessionId);
                    return;
                }
                const timer = setTimeout(refresh, attempts < 3 ? 1000 : 2500);
                titleRefreshTimers.set(sessionId, timer);
            };
            titleRefreshTimers.set(sessionId, setTimeout(refresh, 700));
        };

        const onAppChange = () => {
            sessionManageMode.value = false;
            selectedSessionIds.value = new Set();
            draftSessionActive.value = false;
            currentSessionId.value = '';
            store.setSession('');
            messages.value = [];
        };

        const selectApp = (appId) => {
            sessionManageMode.value = false;
            selectedSessionIds.value = new Set();
            currentAppId.value = appId || '';
            draftSessionActive.value = false;
            currentSessionId.value = '';
            store.setSession('');
            messages.value = [];
        };

        const selectSession = async (sessionId) => {
            draftSessionActive.value = false;
            currentSessionId.value = sessionId;
            store.setSession(sessionId);
            await loadHistory();
        };

        const createSession = () => {
            sessionManageMode.value = false;
            selectedSessionIds.value = new Set();
            currentSessionId.value = '';
            store.setSession('');
            messages.value = [];
            draftSessionActive.value = true;
            Vue.nextTick(() => composerInput.value?.focus());
        };

        // 切换会话管理模式；退出时清空选择，避免选择状态跨 Agent 残留。
        const toggleSessionManagement = () => {
            sessionManageMode.value = !sessionManageMode.value;
            selectedSessionIds.value = new Set();
            batchDeleteError.value = '';
        };

        // 根据当前模式切换会话或选择状态。
        const handleSessionItemClick = (sessionId) => {
            if (sessionManageMode.value) {
                toggleSessionSelection(sessionId);
                return;
            }
            selectSession(sessionId);
        };

        // 切换单个会话的选择状态。
        const toggleSessionSelection = (sessionId) => {
            const next = new Set(selectedSessionIds.value);
            if (next.has(sessionId)) next.delete(sessionId);
            else next.add(sessionId);
            selectedSessionIds.value = next;
        };

        // 在当前 Agent 的可见会话范围内执行全选或取消全选。
        const toggleSelectAllSessions = () => {
            if (allFilteredSessionsSelected.value) {
                selectedSessionIds.value = new Set();
                return;
            }
            selectedSessionIds.value = new Set(filteredSessions.value.map(session => session.sessionId));
        };

        // 打开批量删除确认框；没有选择时不执行任何操作。
        const requestBatchDelete = () => {
            if (selectedSessionIds.value.size === 0) return;
            batchDeleteError.value = '';
            batchDeletePending.value = true;
        };

        // 关闭批量删除确认框；请求进行中时禁止关闭，避免界面与实际结果不同步。
        const cancelBatchDelete = () => {
            if (batchDeleting.value) return;
            batchDeletePending.value = false;
            batchDeleteError.value = '';
        };

        // 当前会话被删除后停止流式任务、清空聊天状态，并自动选择同 Agent 下剩余的第一条会话。
        const recoverAfterCurrentSessionDeleted = async (deletedSessionIds) => {
            deletedSessionIds.forEach(sessionId => store.clearLiveStream(sessionId, { stop: true }));
            if (!deletedSessionIds.has(currentSessionId.value)) return;
            messages.value = [];

            const next = filteredSessions.value[0];
            if (next) await selectSession(next.sessionId);
            else {
                currentSessionId.value = '';
                store.setSession('');
            }
        };

        // 打开删除确认框，并清除上一次删除尝试留下的错误信息。
        const requestDeleteSession = (session) => {
            if (!session || !session.sessionId) return;
            sessionPendingDelete.value = session;
            deleteSessionError.value = '';
        };

        // 关闭删除确认框；删除请求进行中时禁止关闭，避免用户误判请求状态。
        const cancelDeleteSession = () => {
            if (deletingSessionId.value) return;
            sessionPendingDelete.value = null;
            deleteSessionError.value = '';
        };

        // 删除已确认的当前用户会话；如果删的是当前会话，则清理本地状态并切换到同 Agent 下的下一条。
        const confirmDeleteSession = async () => {
            const session = sessionPendingDelete.value;
            if (!session || !session.sessionId) return;

            deleteSessionError.value = '';
            deletingSessionId.value = session.sessionId;
            const wasCurrent = currentSessionId.value === session.sessionId;
            try {
                await api.deleteSession(session.sessionId);
                sessions.value = sessions.value.filter(s => s.sessionId !== session.sessionId);
                if (wasCurrent) await recoverAfterCurrentSessionDeleted(new Set([session.sessionId]));
                sessionPendingDelete.value = null;
            } catch (e) {
                deleteSessionError.value = `删除失败：${e.message || '请稍后重试'}`;
            } finally {
                deletingSessionId.value = '';
            }
        };

        // 调用批量接口删除所选会话；成功项立即移除，跳过项保留选择并允许用户重试或取消。
        const confirmBatchDelete = async () => {
            const requestedIds = [...selectedSessionIds.value];
            if (requestedIds.length === 0) return;

            batchDeleting.value = true;
            batchDeleteError.value = '';
            try {
                const result = await api.deleteSessions(requestedIds);
                const deletedIds = Array.isArray(result?.deletedSessionIds) ? result.deletedSessionIds : [];
                const skippedIds = Array.isArray(result?.skippedSessionIds) ? result.skippedSessionIds : [];
                const deletedIdSet = new Set(deletedIds);
                sessions.value = sessions.value.filter(session => !deletedIdSet.has(session.sessionId));
                await recoverAfterCurrentSessionDeleted(deletedIdSet);

                if (skippedIds.length > 0) {
                    const existingIds = new Set(sessions.value.map(session => session.sessionId));
                    selectedSessionIds.value = new Set(skippedIds.filter(id => existingIds.has(id)));
                    batchDeleteError.value = `已删除 ${deletedIds.length} 个会话，另有 ${skippedIds.length} 个会话不存在或无权删除。`;
                    return;
                }

                selectedSessionIds.value = new Set();
                batchDeletePending.value = false;
                sessionManageMode.value = false;
            } catch (e) {
                batchDeleteError.value = `批量删除失败：${e.message || '请稍后重试'}`;
            } finally {
                batchDeleting.value = false;
            }
        };

        const switchSession = async () => {
            store.setSession(currentSessionId.value);
            if (currentSessionId.value) await loadHistory();
            else messages.value = [];
        };

        const loadHistory = async () => {
            const sessionId = currentSessionId.value;
            if (!sessionId) return;
            loadingHistory.value = true;
            try {
                const history = await api.getHistory(sessionId);
                if (currentSessionId.value !== sessionId) return;
                messages.value = history || [];
                scrollToBottom();
            } catch (e) { console.error('加载历史失败:', e); }
            finally { loadingHistory.value = false; }
        };

        const send = async () => {
            const text = inputText.value.trim();
            if (!text && pendingFiles.value.length === 0) return;

            let sessionId = currentSessionId.value;
            if (!sessionId) {
                try {
                    const appId = currentAppId.value ? Number(currentAppId.value) : undefined;
                    const session = await api.createSession(appId);
                    sessionId = session.sessionId;
                    draftSessionActive.value = false;
                    currentSessionId.value = sessionId;
                    store.setSession(sessionId);
                    upsertSession(session);
                } catch (e) {
                    alert('创建会话失败: ' + e.message);
                    return;
                }
            }

            const stream = store.createLiveStream(sessionId, { streamPhase: 'planning' });
            const streamBaselineLength = messages.value.length + 1;
            stream.streamBaselineLength = streamBaselineLength;
            inputText.value = '';
            scrollToBottom();

            let uploadedFiles = [];
            if (pendingFiles.value.length > 0) {
                for (const file of pendingFiles.value) {
                    try {
                        const result = await api.uploadFile(sessionId, file);
                        uploadedFiles.push(createUploadedFileRecord(sessionId, file, file.name, result));
                    } catch (e) {
                        if (window.DuplicateHandler && window.DuplicateHandler.isDuplicateError(e)) {
                            let keptFileName = file.name;
                            const handleResult = await window.DuplicateHandler.handle({
                                file, error: e,
                                onReplace: () => api.replaceFile(sessionId, file),
                                onKeepBoth: (newFile) => {
                                    keptFileName = newFile.name;
                                    return api.uploadFile(sessionId, newFile);
                                }
                            });
                            if (handleResult === 'replace-done' || handleResult === 'keep-both-done') {
                                const name = handleResult === 'keep-both-done' ? keptFileName : file.name;
                                uploadedFiles.push(createUploadedFileRecord(sessionId, file, name, '/home/gem/uploads/' + name));
                            }
                        }
                    }
                }
                clearPendingImagePreviews();
                pendingFiles.value = [];
            }

            let fullMessage = text;
            if (uploadedFiles.length > 0) {
                fullMessage = (text ? text + '\n\n' : '') + '【上传的文件】\n' + uploadedFiles.map(f => '📎 ' + f.name).join('\n');
            }

            const userMessage = { role: 'user', content: fullMessage, uploadedFiles, timestamp: Date.now(), _optimistic: true };
            stream.pendingUserMessage = userMessage;
            if (currentSessionId.value === sessionId) {
                messages.value.push(userMessage);
                scrollToBottom();
            }
            const stop = api.createChatStream(sessionId, fullMessage, searchEnabled.value, planningEnabled.value, event => handleStreamEvent(event, sessionId, stream.streamId));
            stream.stopStreamFn = stop;
            startStreamHistorySync(sessionId, stream.streamId);
        };

        // 校验事件是否属于当前仍有效的流，避免旧 SSE 回调污染新会话或新请求。
        const streamForRun = (sessionId, streamId) => {
            const stream = store.getLiveStream(sessionId);
            if (!stream || (streamId && stream.streamId !== streamId)) return null;
            return stream;
        };

        // 当前会话历史尚未返回用户消息时，先用 live stream 里的本地消息维持对话上下文显示。
        const ensurePendingUserMessageVisible = (stream, sessionId) => {
            if (currentSessionId.value !== sessionId || !stream?.pendingUserMessage) return;
            if (messages.value.length >= stream.streamBaselineLength) return;
            messages.value.push(stream.pendingUserMessage);
        };

        // 清理指定流的兜底收尾计时器，新的流或主动停止时不能沿用旧计时。
        const clearAutoFinishTimer = (sessionId, streamId) => {
            const stream = streamForRun(sessionId, streamId);
            if (!stream || !stream.autoFinishTimer) return;
            clearTimeout(stream.autoFinishTimer);
            stream.autoFinishTimer = null;
        };

        // 清理指定流的所有兜底计时器，避免旧请求影响新的对话。
        const clearStreamTimers = (sessionId, streamId) => {
            const stream = streamForRun(sessionId, streamId);
            if (!stream) return;
            clearAutoFinishTimer(sessionId, streamId);
            if (stream.streamSyncTimer) {
                clearInterval(stream.streamSyncTimer);
                stream.streamSyncTimer = null;
            }
        };

        // 后端已保存最终助手消息但 SSE 终止事件未到达时，通过历史接口自动收尾。
        const startStreamHistorySync = (sessionId, streamId) => {
            const stream = streamForRun(sessionId, streamId);
            if (!stream) return;
            if (stream.streamSyncTimer) clearInterval(stream.streamSyncTimer);
            stream.streamSyncTimer = setInterval(() => syncStreamHistory(sessionId, streamId), 2500);
        };

        // 静默拉取指定会话历史，只在检测到新的助手回复时替换当前会话界面并停止转圈。
        const syncStreamHistory = async (sessionId, streamId) => {
            const stream = streamForRun(sessionId, streamId);
            if (!stream || !stream.streaming) return;
            try {
                const history = await api.getHistory(sessionId);
                const latestStream = streamForRun(sessionId, streamId);
                if (!latestStream || !Array.isArray(history) || history.length <= latestStream.streamBaselineLength) return;
                const newMessages = history.slice(latestStream.streamBaselineLength);
                const hasAssistantReply = newMessages.some(msg => msg && msg.role === 'assistant' && msg.content);
                if (!hasAssistantReply) return;
                const stop = latestStream.stopStreamFn;
                latestStream.stopStreamFn = null;
                clearStreamTimers(sessionId, streamId);
                if (stop) stop();
                if (currentSessionId.value === sessionId) {
                    messages.value = history;
                    scrollToBottom();
                }
                store.markLiveStreamCompleted(sessionId, { refreshHistory: true });
                scheduleSessionTitleRefresh(sessionId);
            } catch (e) {
                console.warn('流式历史同步失败:', e);
            }
        };

        // 在收到最终回答后短暂等待 done；若没有等到，就用已有回答直接完成前端状态。
        const scheduleAutoFinish = (sessionId, streamId) => {
            clearAutoFinishTimer(sessionId, streamId);
            const stream = streamForRun(sessionId, streamId);
            if (!stream) return;
            // answer 是后端最终回答事件；done 偶发丢失时，前端也要及时收尾。
            stream.autoFinishTimer = setTimeout(() => {
                const latestStream = streamForRun(sessionId, streamId);
                if (latestStream?.streaming && latestStream.finalAnswer) finishStream(sessionId, streamId, { refreshHistory: true });
            }, 1200);
        };

        // 计算同类步骤的展示序号，支持 toolCall 被替换成 toolResult 后仍连续编号。
        const nextStepIndex = (stream, type) => stream.currentEvents.filter(e => e.type === type || e.originalType === type).length + 1;

        // 将工具调用开始事件立即加入时间线，让用户能实时看到正在执行的工具和参数。
        const appendToolCallEvent = (stream, data) => {
            const event = {
                type: 'toolCall',
                tool: data.tool,
                toolCallId: data.toolCallId || '',
                args: data.args || {},
                displayReason: data.displayReason || '',
                elapsed: data.elapsed || 0,
                status: 'running',
                stepIndex: data.stepIndex || stream.currentStepIndex || nextStepIndex(stream, 'toolCall')
            };
            stream.currentEvents.push(event);
            stream.currentToolCall = { ...event, eventIndex: stream.currentEvents.length - 1 };
        };

        // 将工具结果合并回对应的运行中步骤；如果开始事件丢失，则补一条完整结果。
        const completeToolCallEvent = (stream, data) => {
            // 并发下到达顺序不保证，优先按 toolCallId 把结果配对到对应工具行
            let eventIndex = -1;
            if (data.toolCallId) {
                for (let i = stream.currentEvents.length - 1; i >= 0; i--) {
                    const event = stream.currentEvents[i];
                    if (event.type === 'toolCall' && event.status === 'running' && event.toolCallId === data.toolCallId) {
                        eventIndex = i;
                        break;
                    }
                }
            }
            // id 缺失时回退到单一 currentToolCall（兼容未带 id 的旧流，如消化路径）
            if (eventIndex < 0 && stream.currentToolCall && stream.currentToolCall.eventIndex != null) {
                eventIndex = stream.currentToolCall.eventIndex;
            }
            if (eventIndex < 0) {
                for (let i = stream.currentEvents.length - 1; i >= 0; i--) {
                    const event = stream.currentEvents[i];
                    if (event.type === 'toolCall' && event.status === 'running' && (!data.tool || event.tool === data.tool)) {
                        eventIndex = i;
                        break;
                    }
                }
            }
            const previous = eventIndex >= 0 ? stream.currentEvents[eventIndex] : null;
            const completed = {
                type: 'toolResult',
                originalType: 'toolCall',
                tool: data.tool || previous?.tool || stream.currentToolCall?.tool || '',
                toolCallId: previous?.toolCallId || data.toolCallId || '',
                args: previous?.args || stream.currentToolCall?.args || {},
                displayReason: data.displayReason || previous?.displayReason || stream.currentToolCall?.displayReason || '',
                result: data.result || '',
                duration: data.duration,
                elapsed: data.duration || previous?.elapsed || 0,
                status: 'completed',
                stepIndex: previous?.stepIndex || stream.currentStepIndex || nextStepIndex(stream, 'toolCall')
            };
            if (eventIndex >= 0) stream.currentEvents.splice(eventIndex, 1, completed);
            else stream.currentEvents.push(completed);
            // 并发下 currentToolCall 可能指向别的工具行，仅在配对的就是它时才清空
            if (stream.currentToolCall && stream.currentToolCall.eventIndex === eventIndex) {
                stream.currentToolCall = null;
            }
        };

        // 处理某个会话的 SSE 事件；所有写入都限定在该会话对应的 live stream 上。
        const handleStreamEvent = (event, sessionId, streamId) => {
            const stream = streamForRun(sessionId, streamId);
            if (!stream) return;
            const { type, data = {} } = event || {};
            switch (type) {
                case 'plan': stream.currentEvents.push({ type: 'plan', content: data.content }); stream.streamPhase = 'plan_ready'; scrollToBottom(); break;
                case 'thinking_start': stream.currentThinking = ''; stream.currentReasoning = ''; stream.currentStepIndex = data.stepIndex || 0; stream.streamPhase = 'thinking'; scrollToBottom(); break;
                case 'token': stream.currentThinking += data.content || ''; stream.streamPhase = 'generating'; scrollToBottom(); break;
                case 'reasoning_token': stream.currentReasoning += data.content || ''; stream.streamPhase = 'thinking'; scrollToBottom(); break;
                case 'thinking_end':
                    if (stream.currentThinking) stream.currentEvents.push({ type: 'thinking', content: stream.currentThinking, stepIndex: stream.currentStepIndex || nextStepIndex(stream, 'thinking') });
                    if (stream.currentReasoning) stream.currentEvents.push({ type: 'reasoning', content: stream.currentReasoning, stepIndex: stream.currentStepIndex || nextStepIndex(stream, 'reasoning') });
                    stream.currentThinking = ''; stream.currentReasoning = ''; stream.streamPhase = 'processing'; scrollToBottom(); break;
                case 'tool_call': appendToolCallEvent(stream, data); stream.streamPhase = 'tool'; scrollToBottom(); break;
                case 'tool_executing': {
                    // 并发下按 toolCallId 定位正在执行的工具行；id 缺失时回退到 currentToolCall
                    const execId = data.toolCallId;
                    const targetIndex = execId
                        ? stream.currentEvents.findIndex(e => e.type === 'toolCall' && e.status === 'running' && e.toolCallId === execId)
                        : (stream.currentToolCall ? stream.currentToolCall.eventIndex : -1);
                    if (targetIndex >= 0 && stream.currentEvents[targetIndex]) {
                        stream.currentEvents[targetIndex].elapsed = data.elapsed || 0;
                        if (stream.currentToolCall && stream.currentToolCall.eventIndex === targetIndex) {
                            stream.currentToolCall.elapsed = data.elapsed || 0;
                        }
                    }
                    stream.streamPhase = 'tool';
                    break;
                }
                case 'observation':
                    completeToolCallEvent(stream, data); stream.streamPhase = 'tool_done'; scrollToBottom(); break;
                case 'answer':
                    stream.finalAnswer = data.content || ''; stream.streamPhase = 'answer';
                    const idx = stream.currentEvents.map(e => e.type === 'thinking' ? e.content : null).lastIndexOf(stream.finalAnswer);
                    if (idx >= 0) stream.currentEvents.splice(idx, 1);
                    scheduleAutoFinish(sessionId, streamId);
                    scrollToBottom(); break;
                case 'done': finishStream(sessionId, streamId, { refreshIfEmpty: true, refreshHistory: true }); break;
                case 'interrupted':
                    finishStream(sessionId, streamId, { refreshHistory: false });
                    if (currentSessionId.value === sessionId) messages.value.push({ role: 'assistant', content: '⚠️ 中断: ' + data.reason, timestamp: Date.now(), error: '任务中断' });
                    break;
                case 'error':
                    finishStream(sessionId, streamId, { refreshHistory: false });
                    if (currentSessionId.value === sessionId) messages.value.push({ role: 'assistant', content: '❌ 错误: ' + (data.message || '未知'), timestamp: Date.now(), error: data.message || '未知错误', _lastText: '' });
                    break;
                case 'status':
                    stream.currentEvents.push({ type: 'status', content: data.message || '', stepIndex: stream.currentEvents.length + 1 });
                    stream.streamPhase = 'processing';
                    scrollToBottom();
                    break;
                case 'heartbeat': break;
            }
        };

        // 完成指定会话的流式状态，必要时把最终回答落到当前消息列表，并通知页面刷新历史。
        const finishStream = (sessionId, streamId, options = {}) => {
            const stream = streamForRun(sessionId, streamId);
            if (!stream) return;
            clearStreamTimers(sessionId, streamId);
            stream.streaming = false;
            stream.sending = false;
            stream.stopStreamFn = null;
            if (!stream.finalAnswer && stream.currentThinking) stream.finalAnswer = stream.currentThinking;
            ensurePendingUserMessageVisible(stream, sessionId);
            if (stream.finalAnswer && !stream.finalAnswerSaved && currentSessionId.value === sessionId) {
                messages.value.push({ role: 'assistant', content: stream.finalAnswer, events: [...stream.currentEvents], timestamp: Date.now() });
                stream.finalAnswerSaved = true;
            }
            const shouldRefreshHistory = Boolean(options.refreshHistory || (!stream.finalAnswer && options.refreshIfEmpty));
            store.markLiveStreamCompleted(sessionId, { refreshHistory: shouldRefreshHistory });
            if (shouldRefreshHistory) scheduleSessionTitleRefresh(sessionId);
            if (currentSessionId.value === sessionId) scrollToBottom();
        };

        // 停止当前会话的流式回复，只影响当前会话，不会清理其他会话仍在进行的思考链。
        const stopStream = () => {
            const sessionId = currentSessionId.value;
            const stream = store.getLiveStream(sessionId);
            if (!stream) return;
            const stop = stream.stopStreamFn;
            stream.stopStreamFn = null;
            if (stop) stop();
            ensurePendingUserMessageVisible(stream, sessionId);
            store.markLiveStreamCompleted(sessionId, { refreshHistory: false });
        };
        const retryMessage = (msg) => { inputText.value = msg._lastText || ''; };

        const parseSandboxFileUrl = (href) => {
            if (!href) return null;
            try {
                const url = new URL(href, window.location.origin);
                const match = url.pathname.match(/^\/api\/sessions\/([^/]+)\/files\/(?:download|preview)$/);
                const filePath = url.searchParams.get('path');
                if (!match || !filePath) return null;
                const fileName = filePath.split('/').filter(Boolean).pop() || '文件';
                return { sessionId: decodeURIComponent(match[1]), filePath, fileName, fileType: fileName.includes('.') ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : '' };
            } catch (e) { return null; }
        };

        const imageFileTypes = new Set(['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'bmp']);

        // 根据扩展名提供接近 Office 应用配色的文件图标。
        const artifactTypePresentation = (fileType) => {
            const type = String(fileType || '').toLowerCase();
            if (type === 'pdf') return { iconText: 'PDF', iconClass: 'pdf', typeLabel: 'PDF' };
            if (['doc', 'docx', 'odt', 'rtf'].includes(type)) return { iconText: 'W', iconClass: 'word', typeLabel: type.toUpperCase() };
            if (['xls', 'xlsx', 'ods', 'csv'].includes(type)) return { iconText: 'X', iconClass: 'excel', typeLabel: type.toUpperCase() };
            if (['ppt', 'pptx', 'odp'].includes(type)) return { iconText: 'P', iconClass: 'powerpoint', typeLabel: type.toUpperCase() };
            if (imageFileTypes.has(type)) return { iconText: 'IMG', iconClass: 'image', typeLabel: type.toUpperCase() || '图片' };
            return { iconText: 'FILE', iconClass: 'generic', typeLabel: type.toUpperCase() || '文件' };
        };

        const formatFileSize = (size) => {
            const bytes = Number(size) || 0;
            if (!bytes) return '';
            if (bytes < 1024) return `${bytes} B`;
            if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0)} KB`;
            return `${(bytes / (1024 * 1024)).toFixed(bytes < 10 * 1024 * 1024 ? 1 : 0)} MB`;
        };

        // 从文件名中提取扩展名，用于统一输入区和消息区的文件图标。
        const fileExtensionFromName = (name) => {
            const text = String(name || '').split(/[?#]/)[0];
            const dot = text.lastIndexOf('.');
            return dot >= 0 ? text.substring(dot + 1).toLowerCase() : '';
        };

        // 本地图片预览只保存临时 Blob URL，不把图片字节写进消息历史。
        const pendingImageKey = (file) => `${file?.name || ''}::${file?.size || 0}::${file?.lastModified || 0}`;
        const sentUploadPreviewKey = (sessionId, fileName) => `${sessionId || currentSessionId.value || ''}::${fileName || ''}`;
        const isImageFile = (file) => {
            if (!file) return false;
            if (String(file.type || '').toLowerCase().startsWith('image/')) return true;
            return imageFileTypes.has(fileExtensionFromName(file.name));
        };
        const pendingImagePreviewUrl = (file) => {
            if (!isImageFile(file)) return '';
            const key = pendingImageKey(file);
            if (!pendingImagePreviewUrls[key]) {
                pendingImagePreviewUrls[key] = URL.createObjectURL(file);
            }
            return pendingImagePreviewUrls[key];
        };
        const releasePendingImagePreview = (file, revoke = true) => {
            const key = pendingImageKey(file);
            const url = pendingImagePreviewUrls[key];
            if (url && revoke) URL.revokeObjectURL(url);
            delete pendingImagePreviewUrls[key];
            return url || '';
        };
        const takePendingImagePreview = (file) => releasePendingImagePreview(file, false);
        const clearPendingImagePreviews = () => {
            Object.values(pendingImagePreviewUrls).forEach(url => {
                if (url) URL.revokeObjectURL(url);
            });
            Object.keys(pendingImagePreviewUrls).forEach(key => delete pendingImagePreviewUrls[key]);
        };
        const rememberSentUploadPreview = (sessionId, uploaded) => {
            if (!uploaded?.previewUrl || !uploaded?.name) return;
            sentUploadPreviews[sentUploadPreviewKey(sessionId, uploaded.name)] = uploaded;
        };
        const localPreviewForUpload = (msg, fileName) => {
            const fromMessage = Array.isArray(msg?.uploadedFiles)
                ? msg.uploadedFiles.find(file => file?.name === fileName && file?.previewUrl)
                : null;
            return fromMessage || sentUploadPreviews[sentUploadPreviewKey(currentSessionId.value, fileName)] || null;
        };
        const previewPendingImage = (file) => {
            const previewUrl = pendingImagePreviewUrl(file);
            if (!previewUrl || typeof FilePreviewer === 'undefined') return;
            FilePreviewer.preview({
                source: 'local-image',
                fileName: file.name,
                fileType: fileExtensionFromName(file.name),
                fileSize: file.size,
                previewUrl,
            });
        };
        const previewLocalImage = (fileName, previewUrl, fileType = '', fileSize = 0) => {
            if (!previewUrl || typeof FilePreviewer === 'undefined') return;
            FilePreviewer.preview({
                source: 'local-image',
                fileName,
                fileType: fileType || fileExtensionFromName(fileName),
                fileSize: Number(fileSize) || 0,
                previewUrl,
            });
        };
        const createUploadedFileRecord = (sessionId, file, name, path) => {
            const fileType = fileExtensionFromName(name);
            const previewUrl = isImageFile(file) ? (pendingImagePreviewUrl(file) && takePendingImagePreview(file)) : '';
            const record = {
                name,
                path,
                fileType,
                fileSize: Number(file?.size) || 0,
                previewUrl,
                isImage: Boolean(previewUrl),
            };
            rememberSentUploadPreview(sessionId, record);
            return record;
        };

        // 返回待上传文件在输入框中的视觉元信息。
        const composerFilePresentation = (file) => artifactTypePresentation(fileExtensionFromName(file?.name));

        // 返回待上传文件的类型和大小说明。
        const composerFileMeta = (file) => {
            const presentation = composerFilePresentation(file);
            return [presentation.typeLabel, formatFileSize(file?.size)].filter(Boolean).join(' · ');
        };

        const parseArtifactSize = (result) => {
            const match = String(result || '').match(/大小:\s*(\d+)\s*bytes/i);
            return match ? Number(match[1]) : 0;
        };

        const parseArtifactPath = (result) => {
            const match = String(result || '').match(/(?:文件路径|路径):\s*([^\r\n]+)/);
            return match ? normalizeSandboxPath(match[1]) : '';
        };

        const parseArtifactUrl = (result) => {
            const text = String(result || '');
            const markdownMatch = text.match(/!?\[[^\]]*]\(([^)\s]*\/api\/sessions\/[^)\s]+\/files\/(?:download|preview)\?path=[^)\s]+)\)/i);
            if (markdownMatch) return markdownMatch[1];
            const plainMatch = text.match(/(?:https?:\/\/[^\s`]+)?\/api\/sessions\/[^/\s`]+\/files\/(?:download|preview)\?path=[^\s)`]+/i);
            return plainMatch ? plainMatch[0] : '';
        };

        // 从真实工具结果中提取聊天产物，不依赖模型是否在最终回答中复述文件链接。
        const artifactFromToolEvent = (event) => {
            if (!event || event.type !== 'toolResult' || !['download_file', 'browser_screenshot'].includes(event.tool)) return null;
            const result = String(event.result || '');
            const parsedUrl = parseSandboxFileUrl(parseArtifactUrl(result));
            const filePath = parsedUrl?.filePath || parseArtifactPath(result);
            if (!filePath) return null;
            const fileName = parsedUrl?.fileName || filePath.split('/').filter(Boolean).pop() || '文件';
            const fileType = parsedUrl?.fileType || (fileName.includes('.') ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : '');
            const fileSize = parseArtifactSize(result);
            const presentation = artifactTypePresentation(fileType);
            return {
                key: `${parsedUrl?.sessionId || currentSessionId.value}:${filePath}`,
                sourceTool: event.tool,
                sessionId: parsedUrl?.sessionId || currentSessionId.value,
                filePath,
                fileName,
                fileType,
                fileSize,
                isImage: imageFileTypes.has(fileType),
                deliverToUser: event.tool !== 'browser_screenshot' || event.args?.deliver_to_user === true,
                meta: [presentation.typeLabel, formatFileSize(fileSize)].filter(Boolean).join(' · '),
                ...presentation,
            };
        };

        const extractArtifactsFromEvents = (events) => {
            const artifacts = (events || [])
                .map(artifactFromToolEvent)
                .filter(ChatArtifactGalleryUtils.shouldShowToolArtifact);
            // download_file 表示最终交付文件；存在时忽略截图工具产生的临时副本。
            const delivered = artifacts.filter(artifact => artifact.sourceTool === 'download_file');
            const candidates = delivered.length ? delivered : artifacts;
            const unique = new Map();
            candidates.forEach(artifact => unique.set(artifact.key, artifact));
            return [...unique.values()];
        };

        // 图片必须通过带 Authorization 的预览接口读取，再转换成浏览器可显示的 Blob URL。
        const ensureArtifactPreview = (artifact) => {
            if (!artifact?.isImage || artifactBlobUrls[artifact.key] || artifactLoadErrors[artifact.key] || artifactLoadPromises.has(artifact.key)) return;
            const promise = api.previewFileInSandbox(artifact.sessionId, artifact.filePath)
                .then(buffer => {
                    const mime = {
                        png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg',
                        gif: 'image/gif', svg: 'image/svg+xml', webp: 'image/webp', bmp: 'image/bmp',
                    }[artifact.fileType] || 'application/octet-stream';
                    artifactBlobUrls[artifact.key] = URL.createObjectURL(new Blob([buffer], { type: mime }));
                })
                .catch(error => {
                    console.warn('聊天图片产物加载失败:', artifact.filePath, error);
                    artifactLoadErrors[artifact.key] = true;
                })
                .finally(() => artifactLoadPromises.delete(artifact.key));
            artifactLoadPromises.set(artifact.key, promise);
        };

        const ensureArtifactPreviews = (artifacts) => (artifacts || []).forEach(ensureArtifactPreview);
        const messageArtifacts = (message) => {
            const artifacts = extractArtifactsFromEvents(message?.events);
            ensureArtifactPreviews(artifacts);
            return artifacts;
        };
        const liveArtifacts = Vue.computed(() => {
            const artifacts = extractArtifactsFromEvents(currentEvents.value);
            ensureArtifactPreviews(artifacts);
            return artifacts;
        });
        const artifactBlobUrl = (artifact) => artifactBlobUrls[artifact?.key] || '';
        const artifactLoadError = (artifact) => Boolean(artifactLoadErrors[artifact?.key]);
        const previewArtifact = (artifact) => {
            if (!artifact || typeof FilePreviewer === 'undefined') return;
            FilePreviewer.preview({
                source: 'workspace',
                sessionId: artifact.sessionId || currentSessionId.value,
                filePath: artifact.filePath,
                fileName: artifact.fileName,
                fileType: artifact.fileType,
                fileSize: artifact.fileSize,
            });
        };
        const downloadArtifact = (artifact) => {
            if (artifact) api.downloadFileFromSandbox(artifact.sessionId || currentSessionId.value, artifact.filePath);
        };
        const artifactPreviewItem = (artifact) => ({
            source: 'workspace',
            sessionId: artifact.sessionId || currentSessionId.value,
            filePath: artifact.filePath,
            fileName: artifact.fileName,
            fileType: artifact.fileType,
            fileSize: artifact.fileSize,
        });
        const previewArtifactGallery = (artifacts, index = 0) => {
            const images = (artifacts || []).filter(artifact => artifact?.isImage);
            if (!images.length || typeof FilePreviewer === 'undefined') return;
            if (typeof FilePreviewer.previewGroup === 'function') {
                FilePreviewer.previewGroup({
                    items: images.map(artifactPreviewItem),
                    index: Math.max(0, Number(index) || 0),
                });
                return;
            }
            previewArtifact(images[Math.max(0, Number(index) || 0)] || images[0]);
        };

        const escapeAttribute = (v) => escapeHtml(String(v || ''));
        const chatMarkdownRenderer = new marked.Renderer();

        chatMarkdownRenderer.image = (href, title, text) => {
            const file = parseSandboxFileUrl(href);
            if (!file) return `<img src="${escapeAttribute(href)}" alt="${escapeAttribute(text)}"${title ? ` title="${escapeAttribute(title)}"` : ''}>`;
            return `<button type="button" class="chat-file-preview-card" data-sandbox-preview="true" data-session-id="${escapeAttribute(file.sessionId)}" data-file-path="${escapeAttribute(file.filePath)}" data-file-name="${escapeAttribute(file.fileName)}" data-file-type="${escapeAttribute(file.fileType)}"><span class="chat-file-preview-icon">▧</span><span class="chat-file-preview-info"><strong>${escapeHtml(text || file.fileName)}</strong><small>${escapeHtml(file.fileName)}</small></span><span class="chat-file-preview-action">点击预览</span></button>`;
        };

        chatMarkdownRenderer.link = (href, title, text) => {
            const file = parseSandboxFileUrl(href);
            if (!file) return `<a href="${escapeAttribute(href)}"${title ? ` title="${escapeAttribute(title)}"` : ''} target="_blank" rel="noopener noreferrer">${text}</a>`;
            return `<a href="#" class="chat-sandbox-file-link" data-sandbox-preview="true" data-session-id="${escapeAttribute(file.sessionId)}" data-file-path="${escapeAttribute(file.filePath)}" data-file-name="${escapeAttribute(file.fileName)}" data-file-type="${escapeAttribute(file.fileType)}">${text}</a>`;
        };

        const handleMessageContentClick = (event) => {
            const localImageTarget = event.target.closest('[data-local-image-preview="true"]');
            if (localImageTarget) {
                event.preventDefault();
                previewLocalImage(
                    localImageTarget.dataset.fileName,
                    localImageTarget.dataset.previewUrl,
                    localImageTarget.dataset.fileType,
                    localImageTarget.dataset.fileSize
                );
                return;
            }

            const target = event.target.closest('[data-sandbox-preview="true"]');
            if (!target) return;
            event.preventDefault();
            if (typeof FilePreviewer !== 'undefined') FilePreviewer.preview({ source: 'workspace', sessionId: target.dataset.sessionId || currentSessionId.value, filePath: target.dataset.filePath, fileName: target.dataset.fileName, fileType: target.dataset.fileType });
        };

        // 将用户消息中的上传文件段落转换成和输入区一致的文件卡片。
        const renderUserContent = (msg) => {
            const raw = String(msg?.content || '');
            const marker = '【上传的文件】';
            if (!raw.includes(marker)) {
                return `<div class="user-message-text">${escapeHtml(raw).replace(/\n/g, '<br>')}</div>`;
            }

            const [textPart, filePart = ''] = raw.split(marker);
            const files = filePart
                .split(/\r?\n/)
                .map(line => line.replace(/^📎\s*/, '').trim())
                .filter(Boolean);
            const textHtml = textPart.trim()
                ? `<div class="user-message-text">${escapeHtml(textPart.trim()).replace(/\n/g, '<br>')}</div>`
                : '';
            const fileHtml = files.map(name => {
                const localPreview = localPreviewForUpload(msg, name);
                if (localPreview?.previewUrl && imageFileTypes.has(localPreview.fileType || fileExtensionFromName(name))) {
                    return `
                        <button
                            type="button"
                            class="user-upload-image"
                            data-local-image-preview="true"
                            data-file-name="${escapeAttribute(name)}"
                            data-file-type="${escapeAttribute(localPreview.fileType || fileExtensionFromName(name))}"
                            data-file-size="${escapeAttribute(localPreview.fileSize || 0)}"
                            data-preview-url="${escapeAttribute(localPreview.previewUrl)}"
                            aria-label="预览 ${escapeAttribute(name)}"
                        >
                            <img src="${escapeAttribute(localPreview.previewUrl)}" alt="${escapeAttribute(name)}">
                        </button>
                    `;
                }
                const presentation = artifactTypePresentation(fileExtensionFromName(name));
                return `
                    <article class="user-upload-card">
                        <span class="user-upload-icon ${presentation.iconClass}">${presentation.iconText}</span>
                        <span class="user-upload-info">
                            <strong title="${escapeAttribute(name)}">${escapeHtml(name)}</strong>
                            <small>${escapeHtml(presentation.typeLabel || '文件')}</small>
                        </span>
                    </article>
                `;
            }).join('');
            return `${textHtml}<div class="user-upload-list">${fileHtml}</div>`;
        };
        const renderContent = (msg) => msg.role === 'assistant' ? marked.parse(msg.content || '', { renderer: chatMarkdownRenderer }) : renderUserContent(msg);
        const renderMarkdown = (c) => c ? marked.parse(c, { renderer: chatMarkdownRenderer }) : '';
        // previewText / processTitle / processPreview / toolPreview 等纯函数已提到模块顶层，供子组件与历史/流式共用。
        const formatTime = (ts) => { if (!ts) return ''; const d = new Date(ts); const now = new Date(); const hh = String(d.getHours()).padStart(2, '0'), mm = String(d.getMinutes()).padStart(2, '0'); return d.toDateString() === now.toDateString() ? `${hh}:${mm}` : `${d.getMonth() + 1}/${d.getDate()} ${hh}:${mm}`; };

        const scrollToBottom = () => { Vue.nextTick(() => { if (messagesEl.value) { messagesEl.value.scrollTop = messagesEl.value.scrollHeight; checkScrollPosition(); } }); };
        const checkScrollPosition = () => { if (!messagesEl.value) return; const { scrollTop, scrollHeight, clientHeight } = messagesEl.value; showScrollBtn.value = scrollHeight - scrollTop - clientHeight > 200; };

        const copyId = () => { if (currentSessionId.value) { navigator.clipboard.writeText(currentSessionId.value); copied.value = true; setTimeout(() => copied.value = false, 1500); } };
        const handleFileSelect = (e) => {
            Array.from(e.target.files).forEach(file => {
                if (pendingFiles.value.some(p => p.name === file.name)) return;
                pendingFiles.value.push(file);
                if (isImageFile(file)) pendingImagePreviewUrl(file);
            });
            e.target.value = '';
        };
        const removeFile = (i) => {
            const [file] = pendingFiles.value.splice(i, 1);
            if (file) releasePendingImagePreview(file);
        };

        const composerInput = Vue.ref(null);
        const autoResize = () => {
            const el = composerInput.value;
            if (!el) return;
            el.style.height = 'auto';
            el.style.height = Math.min(el.scrollHeight, 160) + 'px';
        };
        const escapeHtml = (s) => s ? s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;') : '';

        const closeToolDock = () => {
            activeToolDock.value = '';
        };
        const sandboxProxyPath = (baseUrl) => {
            try {
                const url = new URL(baseUrl, window.location.origin);
                return url.pathname.split('/').filter(Boolean).join('/');
            } catch (e) {
                return String(baseUrl || '').split('/').filter(Boolean).join('/');
            }
        };
        const sandboxWebsocketPath = (baseUrl, websocketPath) => {
            const proxyPath = sandboxProxyPath(baseUrl);
            return [proxyPath, websocketPath.replace(/^\/+/, '')]
                .filter(Boolean)
                .join('/');
        };
        const sandboxViewUrl = (baseUrl, view) => {
            if (!baseUrl || !view || !view.path) return '';
            const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
            let path = view.path;
            if (view.websocketPath) {
                const params = new URLSearchParams();
                params.set('path', sandboxWebsocketPath(baseUrl, view.websocketPath));
                path += (path.includes('?') ? '&' : '?') + params.toString();
            }
            return base + path;
        };
        const toggleToolDock = (dock) => {
            activeToolDock.value = activeToolDock.value === dock ? '' : dock;
            if (activeToolDock.value === 'sandbox' && currentSessionId.value && !vncUrl.value) {
                loadVncView();
            }
        };
        const toggleVnc = () => toggleToolDock('sandbox');
        const loadVncView = async () => {
            if (!currentSessionId.value) { vncPlaceholder.value = '请先创建会话'; return; }
            const view = sandboxViewById(activeSandboxView.value);
            vncStatus.value = '连接中...'; vncPlaceholder.value = '获取沙箱地址...';
            try {
                const url = await api.getAioViewUrl(currentSessionId.value);
                if (url) {
                    sandboxBaseUrl.value = url;
                    vncUrl.value = sandboxViewUrl(url, view);
                    vncStatus.value = `已连接：${view.label}`;
                } else {
                    vncPlaceholder.value = '无法获取地址';
                    vncStatus.value = '失败';
                }
            }
            catch (e) { vncPlaceholder.value = '连接错误'; vncStatus.value = '失败'; }
        };
        const resetSandbox = async () => {
            const sessionId = currentSessionId.value;
            if (!sessionId || isSandboxResetting.value) return;
            const confirmed = window.confirm('重置沙箱会中断当前浏览器、终端和 VSCode 连接，沙箱内未保存状态可能丢失。确定继续吗？');
            if (!confirmed) return;

            isSandboxResetting.value = true;
            sandboxBaseUrl.value = '';
            vncUrl.value = '';
            vncStatus.value = '重置中...';
            vncPlaceholder.value = '正在重置沙箱...';
            try {
                await api.resetSandbox(sessionId);
                if (currentSessionId.value === sessionId) {
                    vncStatus.value = '重置完成';
                    vncPlaceholder.value = '沙箱已重置，正在重新连接...';
                    await loadVncView();
                }
            } catch (e) {
                if (currentSessionId.value === sessionId) {
                    vncStatus.value = '重置失败';
                    vncPlaceholder.value = '重置失败，请稍后重试';
                }
            } finally {
                isSandboxResetting.value = false;
            }
        };
        const switchSandboxView = async (viewId) => {
            const view = sandboxViewById(viewId);
            if (view.dock === 'workspace') {
                activeToolDock.value = view.dock;
                return;
            }
            activeSandboxView.value = view.id;
            if (!currentSessionId.value) {
                vncUrl.value = '';
                vncPlaceholder.value = '请先创建会话';
                vncStatus.value = '未连接';
                return;
            }
            if (!sandboxBaseUrl.value) {
                await loadVncView();
                return;
            }
            vncUrl.value = sandboxViewUrl(sandboxBaseUrl.value, view);
            vncStatus.value = `已连接：${view.label}`;
        };
        const resizeVnc = (d) => { toolDockWidth.value = Math.max(340, Math.min(820, toolDockWidth.value + d * 8)); };
        const startToolDockResize = (e) => {
            isResizing = true; startX = e.clientX;
            const startWidth = toolDockWidth.value;
            pendingToolDockWidth = startWidth;
            isToolDockResizing.value = true;
            document.body.style.cursor = 'col-resize';
            document.body.style.userSelect = 'none';
            const m = (ev) => {
                if (!isResizing) return;
                const maxWidth = Math.max(340, Math.min(860, window.innerWidth - 560));
                pendingToolDockWidth = Math.max(340, Math.min(maxWidth, startWidth + startX - ev.clientX));
                if (toolDockResizeFrame) return;
                toolDockResizeFrame = requestAnimationFrame(() => {
                    toolDockResizeFrame = 0;
                    toolDockWidth.value = pendingToolDockWidth;
                });
            };
            const u = () => {
                isResizing = false;
                isToolDockResizing.value = false;
                if (toolDockResizeFrame) {
                    cancelAnimationFrame(toolDockResizeFrame);
                    toolDockResizeFrame = 0;
                }
                toolDockWidth.value = pendingToolDockWidth;
                document.body.style.cursor = '';
                document.body.style.userSelect = '';
                document.removeEventListener('mousemove', m);
                document.removeEventListener('mouseup', u);
            };
            document.addEventListener('mousemove', m); document.addEventListener('mouseup', u);
        };
        const startResize = startToolDockResize;

        // 消费全局流式完成标记，确保跨页面回来时能拉到后端已保存的最终消息。
        const consumeCompletedStream = async () => {
            const sessionId = currentSessionId.value;
            if (!sessionId) return;
            const completed = store.consumeCompletedLiveStream(sessionId);
            if (completed?.refreshHistory) await loadHistory();
        };

        Vue.watch(currentSessionId, () => {
            vncUrl.value = '';
            sandboxBaseUrl.value = '';
            activeSandboxView.value = 'browser';
            vncStatus.value = '未连接';
            vncPlaceholder.value = '请先创建会话';
            if (activeToolDock.value === 'sandbox') loadVncView();
            consumeCompletedStream();
        });

        Vue.watch(() => store.liveStreamVersion, () => {
            consumeCompletedStream();
        });

        Vue.watch(
            () => [
                currentSessionId.value,
                currentLiveStream.value?.streamPhase,
                currentLiveStream.value?.currentEvents?.length || 0,
                currentLiveStream.value?.currentThinking || '',
                currentLiveStream.value?.currentReasoning || '',
                currentLiveStream.value?.finalAnswer || ''
            ],
            () => { if (streaming.value) scrollToBottom(); }
        );

        Vue.onMounted(() => {
            const p = new URLSearchParams(window.location.hash.split('?')[1] || ''); const aid = p.get('appId'); if (aid) currentAppId.value = aid;
            Promise.all([loadApps(), loadSessions()]).then(async () => {
                if (currentSessionId.value) await loadHistory();
                await consumeCompletedStream();
            });
            Vue.nextTick(() => { if (messagesEl.value) messagesEl.value.addEventListener('scroll', checkScrollPosition); });
        });

        Vue.onBeforeUnmount(() => {
            clearPendingImagePreviews();
            Object.values(sentUploadPreviews).forEach(item => {
                if (item?.previewUrl) URL.revokeObjectURL(item.previewUrl);
            });
            Object.keys(sentUploadPreviews).forEach(key => delete sentUploadPreviews[key]);
            Object.values(artifactBlobUrls).forEach(url => {
                if (url) URL.revokeObjectURL(url);
            });
            titleRefreshTimers.forEach(timer => clearTimeout(timer));
            titleRefreshTimers.clear();
        });

        return {
            store, logout, messagesEl, apps, sessions, currentAppId, currentApp, filteredSessions, currentSessionId,
            draftSessionActive, sessionTitle,
            messages, displayMessages, inputText, sending, pendingFiles, copied, loadingHistory, showScrollBtn,
            searchEnabled, toggleSearch, planningEnabled, togglePlanning, composerInput, autoResize,
            composerFilePresentation, composerFileMeta, isImageFile, pendingImagePreviewUrl, previewPendingImage,
            sessionManageMode, selectedSessionCount, allFilteredSessionsSelected,
            batchDeletePending, batchDeleteError, batchDeleting,
            sessionPendingDelete, deleteSessionError, deletingSessionId,
            streaming, streamingStatus, streamPhase, currentThinking, currentReasoning, currentToolCall,
            finalAnswer, finalAnswerSaved, currentEvents, groupedSteps, historySteps, stepOverview, stepOverviewBadge, stepAllDone,
            liveArtifacts, messageArtifacts, artifactBlobUrl, artifactLoadError,
            previewArtifact, previewArtifactGallery, downloadArtifact,
            onAppChange, selectApp, selectSession, createSession,
            toggleSessionManagement, handleSessionItemClick, isSessionSelected: sessionId => selectedSessionIds.value.has(sessionId),
            toggleSessionSelection, toggleSelectAllSessions, requestBatchDelete, cancelBatchDelete, confirmBatchDelete,
            requestDeleteSession, cancelDeleteSession, confirmDeleteSession,
            switchSession, send, stopStream, retryMessage,
            renderContent, renderMarkdown, previewText, processTitle, processPreview,
            handleMessageContentClick, formatTime, copyId, handleFileSelect, removeFile,
            activeToolDock, toolDockOpen, toolDockWidth, toolDockTitle, toolDockSubtitle, isToolDockResizing,
            toggleToolDock, closeToolDock, startToolDockResize,
            sandboxViews, activeSandboxView, switchSandboxView,
            vncOpen, vncUrl, vncStatus, vncPlaceholder, isSandboxResetting,
            toggleVnc, loadVncView, resetSandbox, resizeVnc, startResize, scrollToBottom
        };
    }
};

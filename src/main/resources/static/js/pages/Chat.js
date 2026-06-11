// 对话页组件
const ChatPage = {
    template: `
        <div class="chat-page" style="display: flex; gap: 0;">
            <!-- 左侧：对话区域 -->
            <div style="flex: 1; min-width: 0;">
                <!-- 会话选择器 -->
                <div class="session-selector">
                    <label>Agent 应用：</label>
                    <select v-model="currentAppId" @change="onAppChange">
                        <option value="">-- 通用对话 --</option>
                        <option v-for="app in apps" :key="app.id" :value="app.id">
                            {{ app.name }}
                        </option>
                    </select>
                    <label style="margin-left: 1rem;">会话：</label>
                    <select v-model="currentSessionId" @change="switchSession">
                        <option value="">-- 新建会话 --</option>
                        <option v-for="s in filteredSessions" :key="s.sessionId" :value="s.sessionId">
                            {{ s.sessionId ? s.sessionId.substring(0, 8) : '未知' }}... ({{ s.enabledSkillIds ? s.enabledSkillIds.length : 0 }} 技能)
                        </option>
                    </select>
                    <button @click="createSession">＋ 新建</button>
                    <button v-if="currentSessionId" @click="copyId" :style="{ background: copied ? '#4CAF50' : '#2196F3' }">
                        {{ copied ? '已复制!' : '复制 ID' }}
                    </button>
                </div>

                <!-- 当前应用信息 -->
                <div class="app-info-bar" v-if="currentApp">
                    <span class="app-badge">{{ currentApp.name }}</span>
                    <span class="app-desc">{{ currentApp.description || '' }}</span>
                    <span class="app-kb" v-if="currentApp.knowledgeBaseIds && currentApp.knowledgeBaseIds.length > 0">
                        📚 {{ currentApp.knowledgeBaseIds.length }} 个知识库
                    </span>
                    <span class="app-skill" v-if="currentApp.skillIds && currentApp.skillIds.length > 0">
                        ⚡ {{ currentApp.skillIds.length }} 个技能
                    </span>
                </div>

                <!-- 对话面板 -->
                <div class="chat-panel" v-if="currentSessionId">
                    <div class="chat-header">
                        <h3>💬 对话</h3>
                    </div>
                    <div class="chat-messages" ref="messagesEl" @click="handleMessageContentClick">
                        <div v-if="messages.length === 0" class="empty">开始对话</div>

                        <!-- 历史消息 -->
                        <template v-for="msg in messages" :key="msg.timestamp">
                            <div :class="['chat-message', msg.role]">
                                <div class="role-label">{{ msg.role === 'user' ? '你' : '助手' }}</div>
                                <div class="bubble">
                                    <template v-if="msg.role === 'assistant'">
                                        <details v-if="msg.events && msg.events.length > 0"
                                                 class="process-disclosure">
                                            <summary class="process-summary">
                                                <span class="process-check">✓</span>
                                                <span>已处理</span>
                                                <span class="process-count">{{ msg.events.length }} 个步骤</span>
                                            </summary>
                                            <div class="process-timeline">
                                                <details v-for="(event, idx) in msg.events"
                                                         :key="msg.timestamp + '-event-' + idx"
                                                         class="process-item">
                                                    <summary>
                                                        <span class="process-dot"></span>
                                                        <span class="process-item-title">{{ processTitle(event) }}</span>
                                                        <span class="process-preview">{{ processPreview(event) }}</span>
                                                    </summary>
                                                    <div class="process-detail">
                                                        <div v-if="event.type === 'toolResult'" class="process-tool-args">
                                                            参数
                                                            <pre>{{ JSON.stringify(event.args, null, 2) }}</pre>
                                                        </div>
                                                        <div v-if="event.type === 'toolResult'">
                                                            结果
                                                            <pre>{{ event.result }}</pre>
                                                        </div>
                                                        <div v-else v-html="renderMarkdown(event.content || '')"></div>
                                                    </div>
                                                </details>
                                            </div>
                                        </details>
                                        <div class="assistant-answer" v-html="renderMarkdown(msg.content || '')"></div>
                                    </template>
                                    <div v-else v-html="renderContent(msg)"></div>
                                </div>
                            </div>
                        </template>

                        <!-- 当前流式回复：发送后立即出现，并持续更新 -->
                        <div v-if="streaming" class="chat-message assistant streaming">
                            <div class="role-label">助手</div>
                            <div class="bubble agent-stream-card">
                                <details class="process-disclosure live-process" open>
                                    <summary class="process-summary">
                                        <span class="thinking-spinner small"></span>
                                        <span>{{ streamingStatus }}</span>
                                        <span v-if="currentEvents.length" class="process-count">
                                            已完成 {{ currentEvents.length }} 个步骤
                                        </span>
                                    </summary>
                                    <div class="process-timeline">
                                        <details v-for="(event, idx) in currentEvents"
                                                 :key="'live-event-' + idx"
                                                 class="process-item">
                                            <summary>
                                                <span class="process-dot completed"></span>
                                                <span class="process-item-title">{{ processTitle(event) }}</span>
                                                <span class="process-preview">{{ processPreview(event) }}</span>
                                            </summary>
                                            <div class="process-detail">
                                                <div v-if="event.type === 'toolResult'" class="process-tool-args">
                                                    参数
                                                    <pre>{{ JSON.stringify(event.args, null, 2) }}</pre>
                                                </div>
                                                <div v-if="event.type === 'toolResult'">
                                                    结果
                                                    <pre>{{ event.result }}</pre>
                                                </div>
                                                <div v-else v-html="renderMarkdown(event.content || '')"></div>
                                            </div>
                                        </details>

                                        <details v-if="currentReasoning" class="process-item active" open>
                                            <summary>
                                                <span class="process-dot active"></span>
                                                <span class="process-item-title">正在推理</span>
                                                <span class="process-preview">{{ previewText(currentReasoning, 80) }}</span>
                                            </summary>
                                            <div class="process-detail" v-html="renderMarkdown(currentReasoning)"></div>
                                        </details>

                                        <details v-if="currentToolCall" class="process-item active" open>
                                            <summary>
                                                <span class="process-dot active"></span>
                                                <span class="process-item-title">调用工具 {{ currentToolCall.tool }}</span>
                                                <span class="process-preview">
                                                    {{ currentToolCall.elapsed > 0 ? currentToolCall.elapsed + 'ms' : '执行中' }}
                                                </span>
                                            </summary>
                                            <div class="process-detail">
                                                <pre>{{ JSON.stringify(currentToolCall.args, null, 2) }}</pre>
                                            </div>
                                        </details>
                                    </div>
                                </details>

                                <!-- 模型内容 token 实时输出 -->
                                <div v-if="currentThinking" class="live-thinking">
                                    <div v-html="renderMarkdown(currentThinking)"></div>
                                    <span class="stream-cursor"></span>
                                </div>

                                <!-- 最终答案 -->
                                <div v-if="finalAnswer && !finalAnswerSaved"
                                     class="live-final-answer"
                                     v-html="renderMarkdown(finalAnswer)"></div>
                            </div>
                        </div>
                    </div>

                    <!-- 上传区域 -->
                    <div class="upload-area">
                        <label class="upload-btn">
                            <span>📎</span>
                            <span>添加文件</span>
                            <input type="file" multiple @change="handleFileSelect">
                        </label>
                        <div class="upload-file-list" v-if="pendingFiles.length > 0">
                            <span class="upload-file-tag" v-for="(file, index) in pendingFiles" :key="index">
                                <span>{{ getFileIcon(file.name) }}</span>
                                <span>{{ file.name }}</span>
                                <span class="remove-btn" @click="removeFile(index)">×</span>
                            </span>
                        </div>
                    </div>

                    <div class="chat-input-area">
                        <input
                            v-model="inputText"
                            placeholder="输入消息..."
                            @keyup.enter="send"
                        >
                        <button v-if="streaming" @click="stopStream" class="stop-btn">
                            停止
                        </button>
                        <button v-else @click="send" :disabled="sending">
                            {{ sending ? '处理中...' : '发送' }}
                        </button>
                    </div>
                </div>

                <div v-else class="empty" style="height: 60vh; display: flex; align-items: center; justify-content: center;">
                    请先创建或选择会话
                </div>
            </div>

            <!-- 右侧：VNC 面板 -->
            <div v-if="vncOpen" class="vnc-resize-handle" @mousedown="startResize"></div>
            <div class="vnc-panel" :class="{ open: vncOpen }" :style="{ width: vncOpen ? vncWidth + '%' : '0' }">
                <div class="vnc-header">
                    <h3>沙箱实时视图</h3>
                    <div class="vnc-actions">
                        <span class="vnc-status">{{ vncStatus }}</span>
                        <button @click="resizeVnc(-10)" title="缩小">-</button>
                        <button @click="resizeVnc(10)" title="放大">+</button>
                        <button @click="toggleVnc" title="关闭">✕</button>
                    </div>
                </div>
                <div class="vnc-container">
                    <iframe v-if="vncUrl" :src="vncUrl"></iframe>
                    <div v-else class="vnc-placeholder">{{ vncPlaceholder }}</div>
                </div>
            </div>

            <!-- VNC 浮动按钮 -->
            <button class="vnc-float-btn" @click="toggleVnc" :style="{ background: vncOpen ? '#f44336' : '#4CAF50' }">
                {{ vncOpen ? '✕' : '◀' }}
            </button>
        </div>
    `,
    setup() {
        const store = Vue.inject('store');
        const messagesEl = Vue.ref(null);

        const apps = Vue.ref([]);
        const sessions = Vue.ref([]);
        const currentAppId = Vue.ref('');
        const currentSessionId = Vue.ref(store.currentSessionId || '');
        const messages = Vue.ref([]);
        const inputText = Vue.ref('');
        const sending = Vue.ref(false);
        const pendingFiles = Vue.ref([]);
        const copied = Vue.ref(false);

        // 流式状态
        const streaming = Vue.ref(false);
        const stopStreamFn = Vue.ref(null);
        const currentThinking = Vue.ref('');
        const currentReasoning = Vue.ref('');
        const currentToolCall = Vue.ref(null);
        const finalAnswer = Vue.ref('');
        const finalAnswerSaved = Vue.ref(false);
        const streamPhase = Vue.ref('idle');

        // 当前消息的事件收集
        const currentEvents = Vue.ref([]);
        const streamingStatus = Vue.computed(() => {
            switch (streamPhase.value) {
                case 'planning':
                    return '正在规划';
                case 'plan_ready':
                    return '规划完成';
                case 'thinking':
                    return '正在思考';
                case 'generating':
                    return '正在生成';
                case 'processing':
                    return '正在处理';
                case 'tool':
                    return currentToolCall.value
                        ? `正在执行工具 ${currentToolCall.value.tool}`
                        : '正在执行工具';
                case 'tool_done':
                    return '工具执行完成';
                case 'answer':
                    return '正在整理回答';
                default:
                    return '正在处理';
            }
        });

        // VNC 状态
        const vncOpen = Vue.ref(false);
        const vncUrl = Vue.ref('');
        const vncStatus = Vue.ref('未连接');
        const vncPlaceholder = Vue.ref('请先创建会话');
        const vncWidth = Vue.ref(70);
        let isResizing = false;
        let startX = 0;

        // 当前应用
        const currentApp = Vue.computed(() => {
            if (!currentAppId.value) return null;
            return apps.value.find(a => a.id == currentAppId.value) || null;
        });

        // 按应用过滤会话
        const filteredSessions = Vue.computed(() => {
            if (!currentAppId.value) {
                return sessions.value.filter(s => !s.appId);
            }
            return sessions.value.filter(s => s.appId == currentAppId.value);
        });

        // 加载应用列表
        const loadApps = async () => {
            try {
                apps.value = await api.listApps();
            } catch (e) {
                console.error('加载应用列表失败:', e);
                apps.value = [];
            }
        };

        // 加载会话列表
        const loadSessions = async () => {
            try {
                const result = await api.listSessions();
                sessions.value = Array.isArray(result) ? result.filter(s => s && s.sessionId) : [];
            } catch (e) {
                console.error('加载会话列表失败:', e);
                sessions.value = [];
            }
        };

        // 应用切换
        const onAppChange = () => {
            currentSessionId.value = '';
            store.setSession('');
            messages.value = [];
        };

        // 创建新会话
        const createSession = async () => {
            try {
                const appId = currentAppId.value ? Number(currentAppId.value) : undefined;
                const session = await api.createSession(appId);
                currentSessionId.value = session.sessionId;
                store.setSession(session.sessionId);
                await loadSessions();
                await loadHistory();
            } catch (e) {
                alert('创建会话失败: ' + e.message);
            }
        };

        // 切换会话
        const switchSession = async () => {
            store.setSession(currentSessionId.value);
            if (currentSessionId.value) {
                await loadHistory();
            } else {
                messages.value = [];
            }
        };

        // 加载历史消息
        const loadHistory = async () => {
            if (!currentSessionId.value) return;
            try {
                const history = await api.getHistory(currentSessionId.value);
                messages.value = history || [];
                scrollToBottom();
            } catch (e) {
                console.error('加载历史消息失败:', e);
            }
        };

        // 发送消息（流式）
        const send = async () => {
            if (!currentSessionId.value) {
                alert('请先创建会话');
                return;
            }
            const text = inputText.value.trim();
            if (!text && pendingFiles.value.length === 0) return;

            sending.value = true;
            streaming.value = true;
            inputText.value = '';
            scrollToBottom();

            // 重置流式状态
            currentThinking.value = '';
            currentReasoning.value = '';
            currentToolCall.value = null;
            finalAnswer.value = '';
            finalAnswerSaved.value = false;
            currentEvents.value = [];
            streamPhase.value = 'planning';

            // 先上传文件
            let uploadedFiles = [];
            if (pendingFiles.value.length > 0) {
                for (const file of pendingFiles.value) {
                    try {
                        const result = await api.uploadFile(currentSessionId.value, file);
                        uploadedFiles.push({ name: file.name, path: result });
                    } catch (e) {
                        // 检测到重复文件（409）：用 DuplicateHandler 通用处理
                        if (window.DuplicateHandler && window.DuplicateHandler.isDuplicateError(e)) {
                            const handleResult = await window.DuplicateHandler.handle({
                                file: file,
                                error: e,
                                onReplace: () => api.replaceFile(currentSessionId.value, file),
                                onKeepBoth: (newFile) => api.uploadFile(currentSessionId.value, newFile)
                            });
                            if (handleResult === 'replace-done' || handleResult === 'keep-both-done') {
                                const uploadedName = handleResult === 'keep-both-done' ? newFile?.name || file.name : file.name;
                                // 重新查询一下沙箱路径（这里简化处理，使用 /home/gem/uploads/{name}）
                                const path = '/home/gem/uploads/' + uploadedName;
                                uploadedFiles.push({ name: uploadedName, path: path });
                            } else {
                                console.warn('用户跳过文件:', file.name);
                            }
                        } else {
                            console.error('上传文件失败:', e);
                        }
                    }
                }
                pendingFiles.value = [];
            }

            // 构造消息
            let fullMessage = text;
            if (uploadedFiles.length > 0) {
                const fileList = uploadedFiles.map(f => `📎 ${f.name}`).join('\n');
                fullMessage = (text ? text + '\n\n' : '') + '【上传的文件】\n' + fileList;
            }

            // 显示用户消息
            messages.value.push({ role: 'user', content: fullMessage, timestamp: Date.now() });
            scrollToBottom();

            // 启动 SSE 流
            const stop = api.createChatStream(currentSessionId.value, fullMessage, handleStreamEvent);
            stopStreamFn.value = stop;
        };

        // 处理 SSE 事件
        const handleStreamEvent = (event) => {
            const { type, data } = event;

            switch (type) {
                case 'plan':
                    currentEvents.value.push({ type: 'plan', content: data.content });
                    streamPhase.value = 'plan_ready';
                    scrollToBottom();
                    break;

                case 'thinking_start':
                    currentThinking.value = '';
                    currentReasoning.value = '';
                    streamPhase.value = 'thinking';
                    scrollToBottom();
                    break;

                case 'token':
                    currentThinking.value += data.content;
                    streamPhase.value = 'generating';
                    scrollToBottom();
                    break;

                case 'reasoning_token':
                    // 思考链 token（推理模型的思考过程），单独存
                    currentReasoning.value += data.content;
                    streamPhase.value = 'thinking';
                    scrollToBottom();
                    break;

                case 'thinking_end':
                    // 本轮思考结束，保存为事件
                    if (currentThinking.value) {
                        currentEvents.value.push({
                            type: 'thinking',
                            content: currentThinking.value,
                            stepIndex: data.stepIndex || currentEvents.value.filter(e => e.type === 'thinking').length + 1
                        });
                    }
                    if (currentReasoning.value) {
                        currentEvents.value.push({
                            type: 'reasoning',
                            content: currentReasoning.value,
                            stepIndex: data.stepIndex || currentEvents.value.filter(e => e.type === 'reasoning').length + 1
                        });
                    }
                    currentThinking.value = '';
                    currentReasoning.value = '';
                    streamPhase.value = 'processing';
                    scrollToBottom();
                    break;

                case 'tool_call':
                    currentToolCall.value = { tool: data.tool, args: data.args, elapsed: 0 };
                    streamPhase.value = 'tool';
                    scrollToBottom();
                    break;

                case 'tool_executing':
                    if (currentToolCall.value) {
                        currentToolCall.value.elapsed = data.elapsed;
                    }
                    streamPhase.value = 'tool';
                    break;

                case 'observation':
                    // 工具执行完成
                    if (currentToolCall.value) {
                        currentEvents.value.push({
                            type: 'toolResult',
                            tool: currentToolCall.value.tool,
                            args: currentToolCall.value.args,
                            result: data.result,
                            duration: data.duration
                        });
                    }
                    currentToolCall.value = null;
                    streamPhase.value = 'tool_done';
                    scrollToBottom();
                    break;

                case 'answer':
                    finalAnswer.value = data.content || '';
                    streamPhase.value = 'answer';
                    const lastThinkingIndex = currentEvents.value
                        .map(event => event.type === 'thinking' ? event.content : null)
                        .lastIndexOf(finalAnswer.value);
                    if (lastThinkingIndex >= 0) {
                        currentEvents.value.splice(lastThinkingIndex, 1);
                    }
                    scrollToBottom();
                    break;

                case 'done':
                    finishStream();
                    break;

                case 'interrupted':
                    finishStream();
                    messages.value.push({
                        role: 'assistant',
                        content: '⚠️ 任务被中断: ' + data.reason,
                        timestamp: Date.now()
                    });
                    break;

                case 'error':
                    finishStream();
                    messages.value.push({
                        role: 'assistant',
                        content: '❌ 错误: ' + (data.message || '未知错误'),
                        timestamp: Date.now()
                    });
                    break;

                case 'heartbeat':
                    // 心跳事件，保持 SSE 连接活跃，无需处理
                    break;
            }
        };

        // 完成流
        const finishStream = () => {
            streaming.value = false;
            sending.value = false;
            stopStreamFn.value = null;

            // 保存最终答案为消息
            if (finalAnswer.value) {
                messages.value.push({
                    role: 'assistant',
                    content: finalAnswer.value,
                    events: [...currentEvents.value],
                    timestamp: Date.now()
                });
                finalAnswerSaved.value = true;
            }

            currentThinking.value = '';
            currentReasoning.value = '';
            currentToolCall.value = null;
            currentEvents.value = [];
            streamPhase.value = 'idle';
            scrollToBottom();
        };

        // 停止流
        const stopStream = () => {
            if (stopStreamFn.value) {
                stopStreamFn.value();
                stopStreamFn.value = null;
            }
        };

        // 渲染内容
        const parseSandboxFileUrl = (href) => {
            if (!href) return null;
            try {
                const url = new URL(href, window.location.origin);
                const match = url.pathname.match(/^\/api\/sessions\/([^/]+)\/files\/(?:download|preview)$/);
                const filePath = url.searchParams.get('path');
                if (!match || !filePath) return null;

                const fileName = filePath.split('/').filter(Boolean).pop() || '文件';
                const extension = fileName.includes('.')
                    ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                    : '';
                return {
                    sessionId: decodeURIComponent(match[1]),
                    filePath,
                    fileName,
                    fileType: extension
                };
            } catch (e) {
                return null;
            }
        };

        const escapeAttribute = (value) => escapeHtml(String(value || ''));
        const chatMarkdownRenderer = new marked.Renderer();

        chatMarkdownRenderer.image = (href, title, text) => {
            const file = parseSandboxFileUrl(href);
            if (!file) {
                const titleAttr = title ? ` title="${escapeAttribute(title)}"` : '';
                return `<img src="${escapeAttribute(href)}" alt="${escapeAttribute(text)}"${titleAttr}>`;
            }

            return `
                <button type="button"
                        class="chat-file-preview-card chat-image-preview-card"
                        data-sandbox-preview="true"
                        data-session-id="${escapeAttribute(file.sessionId)}"
                        data-file-path="${escapeAttribute(file.filePath)}"
                        data-file-name="${escapeAttribute(file.fileName)}"
                        data-file-type="${escapeAttribute(file.fileType)}">
                    <span class="chat-file-preview-icon">▧</span>
                    <span class="chat-file-preview-info">
                        <strong>${escapeHtml(text || file.fileName)}</strong>
                        <small>${escapeHtml(file.fileName)}</small>
                    </span>
                    <span class="chat-file-preview-action">点击预览</span>
                </button>
            `;
        };

        chatMarkdownRenderer.link = (href, title, text) => {
            const file = parseSandboxFileUrl(href);
            if (!file) {
                const titleAttr = title ? ` title="${escapeAttribute(title)}"` : '';
                return `<a href="${escapeAttribute(href)}"${titleAttr} target="_blank" rel="noopener noreferrer">${text}</a>`;
            }

            return `
                <a href="#"
                   class="chat-sandbox-file-link"
                   data-sandbox-preview="true"
                   data-session-id="${escapeAttribute(file.sessionId)}"
                   data-file-path="${escapeAttribute(file.filePath)}"
                   data-file-name="${escapeAttribute(file.fileName)}"
                   data-file-type="${escapeAttribute(file.fileType)}">${text}</a>
            `;
        };

        const handleMessageContentClick = (event) => {
            const target = event.target.closest('[data-sandbox-preview="true"]');
            if (!target) return;

            event.preventDefault();
            if (typeof FilePreviewer === 'undefined') {
                console.warn('FilePreviewer 尚未加载');
                return;
            }

            FilePreviewer.preview({
                source: 'workspace',
                sessionId: target.dataset.sessionId || currentSessionId.value,
                filePath: target.dataset.filePath,
                fileName: target.dataset.fileName,
                fileType: target.dataset.fileType
            });
        };

        const renderContent = (msg) => {
            if (msg.role === 'assistant') {
                return marked.parse(msg.content || '', { renderer: chatMarkdownRenderer });
            }
            return escapeHtml(msg.content || '');
        };

        // 渲染 Markdown
        const renderMarkdown = (content) => {
            if (!content) return '';
            return marked.parse(content, { renderer: chatMarkdownRenderer });
        };

        const previewText = (content, maxLength = 90) => {
            if (!content) return '';
            const text = String(content).replace(/\s+/g, ' ').trim();
            return text.length > maxLength ? text.substring(0, maxLength) + '...' : text;
        };

        const processTitle = (event) => {
            switch (event.type) {
                case 'plan':
                    return '规划任务';
                case 'thinking':
                    return `思考过程 · 第 ${event.stepIndex || 1} 轮`;
                case 'reasoning':
                    return `推理过程 · 第 ${event.stepIndex || 1} 轮`;
                case 'toolResult':
                    return `调用工具 ${event.tool || ''}`;
                default:
                    return '处理步骤';
            }
        };

        const processPreview = (event) => {
            if (event.type === 'toolResult') {
                return event.duration != null ? `${event.duration}ms` : '已完成';
            }
            return previewText(event.content, 90);
        };

        // 滚动到底部
        const scrollToBottom = () => {
            Vue.nextTick(() => {
                if (messagesEl.value) {
                    messagesEl.value.scrollTop = messagesEl.value.scrollHeight;
                }
            });
        };

        // 复制会话 ID
        const copyId = () => {
            if (currentSessionId.value) {
                navigator.clipboard.writeText(currentSessionId.value);
                copied.value = true;
                setTimeout(() => copied.value = false, 1500);
            }
        };

        // 文件选择
        const handleFileSelect = (event) => {
            const files = Array.from(event.target.files);
            files.forEach(file => {
                if (!pendingFiles.value.some(f => f.name === file.name)) {
                    pendingFiles.value.push(file);
                }
            });
            event.target.value = '';
        };

        // 移除文件
        const removeFile = (index) => {
            pendingFiles.value.splice(index, 1);
        };

        // 获取文件图标
        const getFileIcon = (filename) => {
            const ext = filename.split('.').pop().toLowerCase();
            const icons = {
                pdf: '📄', xlsx: '📊', xls: '📊', csv: '📋',
                doc: '📝', docx: '📝', txt: '📃', md: '📃',
                zip: '📦', rar: '📦', '7z': '📦',
                png: '🖼️', jpg: '🖼️', jpeg: '🖼️', gif: '🖼️',
                html: '🌐', css: '🎨', js: '📜', py: '🐍'
            };
            return icons[ext] || '📁';
        };

        // HTML 转义
        const escapeHtml = (str) => {
            if (!str) return '';
            return str.replace(/&/g, '&amp;')
                      .replace(/</g, '&lt;')
                      .replace(/>/g, '&gt;')
                      .replace(/"/g, '&quot;');
        };

        // VNC 相关方法
        const toggleVnc = () => {
            vncOpen.value = !vncOpen.value;
            if (vncOpen.value && currentSessionId.value && !vncUrl.value) {
                loadVncView();
            }
        };

        const loadVncView = async () => {
            if (!currentSessionId.value) {
                vncPlaceholder.value = '请先创建会话';
                return;
            }
            vncStatus.value = '连接中...';
            vncPlaceholder.value = '正在获取沙箱地址...';
            try {
                const endpoint = await api.getAioEndpoint(currentSessionId.value);
                if (endpoint) {
                    vncUrl.value = `http://${endpoint}/`;
                    vncStatus.value = '已连接';
                } else {
                    vncPlaceholder.value = '无法获取沙箱地址';
                    vncStatus.value = '连接失败';
                }
            } catch (e) {
                vncPlaceholder.value = '连接错误: ' + e.message;
                vncStatus.value = '连接失败';
            }
        };

        const resizeVnc = (delta) => {
            vncWidth.value = Math.max(30, Math.min(90, vncWidth.value + delta));
        };

        const startResize = (e) => {
            isResizing = true;
            startX = e.clientX;
            document.body.style.cursor = 'col-resize';
            document.body.style.userSelect = 'none';

            const onMouseMove = (e) => {
                if (!isResizing) return;
                const wrapper = document.querySelector('.chat-page');
                if (!wrapper) return;
                const wrapperWidth = wrapper.offsetWidth;
                const diff = startX - e.clientX;
                const currentWidthPx = (vncWidth.value / 100) * wrapperWidth;
                const newWidthPx = currentWidthPx + diff;
                vncWidth.value = Math.max(20, Math.min(80, (newWidthPx / wrapperWidth) * 100));
                startX = e.clientX;
            };

            const onMouseUp = () => {
                isResizing = false;
                document.body.style.cursor = '';
                document.body.style.userSelect = '';
                document.removeEventListener('mousemove', onMouseMove);
                document.removeEventListener('mouseup', onMouseUp);
            };

            document.addEventListener('mousemove', onMouseMove);
            document.addEventListener('mouseup', onMouseUp);
        };

        // 监听会话变化，重置 VNC
        Vue.watch(currentSessionId, () => {
            vncUrl.value = '';
            vncStatus.value = '未连接';
            vncPlaceholder.value = '请先创建会话';
        });

        // 初始化
        Vue.onMounted(async () => {
            // 配置 marked
            marked.setOptions({
                highlight: function(code, lang) {
                    if (lang && hljs.getLanguage(lang)) {
                        return hljs.highlight(code, { language: lang }).value;
                    }
                    return hljs.highlightAuto(code).value;
                },
                breaks: true,
                gfm: true
            });

            // 从 URL 参数中获取 appId
            const urlParams = new URLSearchParams(window.location.hash.split('?')[1] || '');
            const appIdFromUrl = urlParams.get('appId');
            if (appIdFromUrl) {
                currentAppId.value = appIdFromUrl;
            }

            await Promise.all([loadApps(), loadSessions()]);

            if (currentSessionId.value) {
                await loadHistory();
            }
        });

        return {
            store,
            messagesEl,
            apps, sessions, currentAppId, currentApp, filteredSessions,
            currentSessionId,
            messages, inputText, sending, pendingFiles, copied,
            streaming, streamingStatus, streamPhase, currentThinking, currentReasoning, currentToolCall,
            finalAnswer, finalAnswerSaved, currentEvents,
            onAppChange, createSession, switchSession, send, stopStream,
            renderContent, renderMarkdown, previewText, processTitle, processPreview,
            handleMessageContentClick,
            copyId, handleFileSelect, removeFile, getFileIcon,
            vncOpen, vncUrl, vncStatus, vncPlaceholder, vncWidth,
            toggleVnc, resizeVnc, startResize
        };
    }
};

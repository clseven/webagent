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

// 对话页组件
const ChatPage = {
    components: { ChatArtifactCard },
    template: `
        <div class="chat-workspace">
            <!-- 中间：聊天主区 -->
            <div class="chat-main">
                <!-- 对话面板 -->
                <div class="chat-panel" v-if="currentSessionId" style="position:relative;">
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
                            <button class="btn btn-ghost btn-sm" @click="toggleVnc" :class="{ 'btn-active': vncOpen }">
                                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
                                沙箱
                            </button>
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
                        <div v-if="!loadingHistory && messages.length === 0" class="empty-state chat-empty">
                            <svg viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
                                <rect x="20" y="30" width="80" height="55" rx="12" stroke="currentColor" stroke-width="2" opacity="0.2"/>
                                <circle cx="45" cy="57" r="6" fill="currentColor" opacity="0.15"/>
                                <circle cx="60" cy="57" r="6" fill="currentColor" opacity="0.15"/>
                                <circle cx="75" cy="57" r="6" fill="currentColor" opacity="0.15"/>
                                <path d="M30 80h60" stroke="currentColor" stroke-width="2" opacity="0.1"/>
                            </svg>
                            <h3>开始对话</h3>
                            <p>输入消息，AI 助手将为你提供帮助</p>
                        </div>

                        <!-- 历史消息 -->
                        <template v-for="msg in messages" :key="msg.timestamp">
                            <div :class="['chat-message', msg.role, msg.error ? 'has-error' : '']">
                                <div class="role-label">{{ msg.role === 'user' ? '你' : '助手' }}</div>
                                <div class="bubble">
                                    <template v-if="msg.role === 'assistant'">
                                        <details v-if="msg.events && msg.events.length > 0" class="process-disclosure">
                                            <summary class="process-summary">
                                                <span class="process-check">✓</span>
                                                <span>已处理</span>
                                                <span class="process-count">{{ msg.events.length }} 个步骤</span>
                                            </summary>
                                            <div class="process-timeline">
                                                <details v-for="(event, idx) in msg.events" :key="msg.timestamp + '-event-' + idx" class="process-item">
                                                    <summary>
                                                        <span :class="['process-dot', event.status === 'running' ? 'active' : 'completed']"></span>
                                                        <span class="process-item-title">{{ processTitle(event) }}</span>
                                                        <span class="process-preview">{{ processPreview(event) }}</span>
                                                    </summary>
                                                    <div class="process-detail">
                                                        <div v-if="event.type === 'toolCall' || event.type === 'toolResult'" class="process-tool-args">参数<pre>{{ JSON.stringify(event.args, null, 2) }}</pre></div>
                                                        <div v-if="event.type === 'toolResult'">结果<pre>{{ event.result }}</pre></div>
                                                        <div v-else-if="event.type !== 'toolCall'" v-html="renderMarkdown(event.content || '')"></div>
                                                        <div v-else>执行中...</div>
                                                    </div>
                                                </details>
                                            </div>
                                        </details>
                                        <div class="assistant-answer" v-html="renderMarkdown(msg.content || '')"></div>
                                        <div v-if="messageArtifacts(msg).length" class="chat-artifacts">
                                            <chat-artifact-card
                                                v-for="artifact in messageArtifacts(msg)"
                                                :key="artifact.key"
                                                :artifact="artifact"
                                                :blob-url="artifactBlobUrl(artifact)"
                                                :load-error="artifactLoadError(artifact)"
                                                @preview="previewArtifact"
                                                @download="downloadArtifact"
                                            ></chat-artifact-card>
                                        </div>
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
                                        <span v-if="currentEvents.length" class="process-count">已完成 {{ currentEvents.length }} 个步骤</span>
                                    </summary>
                                    <div class="process-timeline">
                                        <details v-for="(event, idx) in currentEvents" :key="'live-event-' + idx" class="process-item">
                                            <summary>
                                                <span :class="['process-dot', event.status === 'running' ? 'active' : 'completed']"></span>
                                                <span class="process-item-title">{{ processTitle(event) }}</span>
                                                <span class="process-preview">{{ processPreview(event) }}</span>
                                            </summary>
                                            <div class="process-detail">
                                                <div v-if="event.type === 'toolCall' || event.type === 'toolResult'" class="process-tool-args">参数<pre>{{ JSON.stringify(event.args, null, 2) }}</pre></div>
                                                <div v-if="event.type === 'toolResult'">结果<pre>{{ event.result }}</pre></div>
                                                <div v-else-if="event.type !== 'toolCall'" v-html="renderMarkdown(event.content || '')"></div>
                                                <div v-else>执行中...</div>
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
                                    </div>
                                </details>
                                <div v-if="currentThinking" class="live-thinking">
                                    <div v-html="renderMarkdown(currentThinking)"></div>
                                    <span class="stream-cursor"></span>
                                </div>
                                <div v-if="finalAnswer && !finalAnswerSaved" class="live-final-answer" v-html="renderMarkdown(finalAnswer)"></div>
                                <div v-if="liveArtifacts.length" class="chat-artifacts">
                                    <chat-artifact-card
                                        v-for="artifact in liveArtifacts"
                                        :key="artifact.key"
                                        :artifact="artifact"
                                        :blob-url="artifactBlobUrl(artifact)"
                                        :load-error="artifactLoadError(artifact)"
                                        @preview="previewArtifact"
                                        @download="downloadArtifact"
                                    ></chat-artifact-card>
                                </div>
                            </div>
                        </div>

                        <!-- 回到底部 -->
                        <button v-if="showScrollBtn" class="scroll-to-bottom" @click="scrollToBottom" title="回到底部">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>
                        </button>
                    </div>

                    <!-- 上传区 -->
                    <div class="upload-area">
                        <label class="upload-btn" title="添加文件">
                            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>
                            <span>添加文件</span>
                            <input type="file" multiple @change="handleFileSelect">
                        </label>
                        <div class="upload-file-list" v-if="pendingFiles.length > 0">
                            <span class="upload-file-tag" v-for="(file, index) in pendingFiles" :key="index">
                                <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                                {{ file.name }}
                                <span class="remove-btn" @click="removeFile(index)">×</span>
                            </span>
                        </div>
                    </div>

                    <!-- 输入区 -->
                    <div class="chat-input-area">
                        <input
                            v-model="inputText"
                            placeholder="输入消息... (Enter 发送)"
                            @keyup.enter="send"
                            :disabled="sending"
                        >
                        <button v-if="streaming" @click="stopStream" class="btn-send stop-btn" title="停止生成">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="1"/></svg>
                        </button>
                        <button v-else @click="send" :disabled="sending || !inputText.trim()" class="btn-send" title="发送">
                            <svg v-if="!sending" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
                            <span v-else class="thinking-spinner small" style="border-color:rgba(255,255,255,0.3);border-top-color:#fff;"></span>
                        </button>
                    </div>
                </div>

                <!-- 无会话 -->
                <div v-else class="empty-state chat-empty" style="flex:1; background: var(--color-surface); border-radius: var(--radius-lg); border:1px solid var(--color-border-light);">
                    <svg viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <circle cx="60" cy="55" r="24" fill="currentColor" opacity="0.08"/>
                        <path d="M48 62c4 6 20 6 24 0" stroke="currentColor" stroke-width="2" opacity="0.15"/>
                        <circle cx="52" cy="50" r="3" fill="currentColor" opacity="0.2"/>
                        <circle cx="68" cy="50" r="3" fill="currentColor" opacity="0.2"/>
                    </svg>
                    <h3>选择或创建会话</h3>
                    <p>在右侧选择 Agent 和会话开始对话</p>
                    <button class="btn btn-primary" @click="createSession">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                        新建会话
                    </button>
                </div>
            </div>

            <!-- 右侧：Agent + 会话面板 -->
            <aside class="chat-sidebar">
                <!-- Agent 选择区 -->
                <div class="sidebar-section">
                    <div class="sidebar-section-header">
                        <span class="sidebar-section-title">Agent</span>
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
                        <span class="sidebar-section-title">会话</span>
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
                                <div class="session-title">会话 {{ s.sessionId.substring(0, 8) }}</div>
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
            </aside>

            <!-- VNC 面板 -->
            <div v-if="vncOpen" class="vnc-resize-handle" @mousedown="startResize"></div>
            <div class="vnc-panel" :class="{ open: vncOpen }" :style="{ width: vncOpen ? vncWidth + '%' : '0' }">
                <div class="vnc-header">
                    <h3>
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
                        沙箱视图
                    </h3>
                    <div class="vnc-actions">
                        <span class="vnc-status">{{ vncStatus }}</span>
                        <button @click="resizeVnc(-10)" title="缩小">−</button>
                        <button @click="resizeVnc(10)" title="放大">+</button>
                        <button @click="toggleVnc" title="关闭">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                        </button>
                    </div>
                </div>
                <div class="vnc-container">
                    <iframe v-if="vncUrl" :src="vncUrl"></iframe>
                    <div v-else class="vnc-placeholder">
                        <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" opacity="0.3"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
                        <span>{{ vncPlaceholder }}</span>
                    </div>
                </div>
            </div>

            <!-- VNC 浮动按钮 -->
            <button class="vnc-float-btn" @click="toggleVnc" :style="{ background: vncOpen ? '#EF4444' : '' }" :title="vncOpen ? '关闭' : '沙箱视图'">
                <svg v-if="!vncOpen" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
                <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>

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
        const store = Vue.inject('store');
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
        // 保存等待用户确认删除的会话，未打开确认框时为 null。
        const sessionPendingDelete = Vue.ref(null);
        // 保存删除失败原因，让错误与当前确认操作保持在同一弹窗内。
        const deleteSessionError = Vue.ref('');
        const deletingSessionId = Vue.ref('');
        const currentAppId = Vue.ref('');
        const currentSessionId = Vue.ref(store.currentSessionId || '');
        const messages = Vue.ref([]);
        const inputText = Vue.ref('');
        const sending = Vue.ref(false);
        const pendingFiles = Vue.ref([]);
        const copied = Vue.ref(false);
        const loadingHistory = Vue.ref(false);
        const showScrollBtn = Vue.ref(false);

        const streaming = Vue.ref(false);
        const stopStreamFn = Vue.ref(null);
        const currentThinking = Vue.ref('');
        const currentReasoning = Vue.ref('');
        const currentToolCall = Vue.ref(null);
        const finalAnswer = Vue.ref('');
        const finalAnswerSaved = Vue.ref(false);
        const streamPhase = Vue.ref('idle');
        const currentEvents = Vue.ref([]);
        const artifactBlobUrls = Vue.reactive({});
        const artifactLoadErrors = Vue.reactive({});
        const artifactLoadPromises = new Map();
        // 记录最终回答后的兜底收尾计时器，避免 done 事件异常缺失时界面一直转圈。
        let autoFinishTimer = null;
        // 记录执行阶段兜底同步计时器，用于 SSE 后续事件未进入页面时自动恢复显示。
        let streamSyncTimer = null;
        // 记录本次发送前后的消息长度，用于判断历史里是否已经出现新的助手回复。
        let streamBaselineLength = 0;

        const streamingStatus = Vue.computed(() => {
            switch (streamPhase.value) {
                case 'planning': return '正在规划';
                case 'plan_ready': return '规划完成';
                case 'thinking': return '正在思考';
                case 'generating': return '正在生成';
                case 'processing': return '正在处理';
                case 'tool': return currentToolCall.value ? `执行 ${currentToolCall.value.tool}` : '执行工具';
                case 'tool_done': return '工具完成';
                case 'answer': return '整理回答';
                default: return '处理中';
            }
        });

        const vncOpen = Vue.ref(false);
        const vncUrl = Vue.ref('');
        const vncStatus = Vue.ref('未连接');
        const vncPlaceholder = Vue.ref('请先创建会话');
        const vncWidth = Vue.ref(70);
        let isResizing = false, startX = 0;

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

        const onAppChange = () => {
            sessionManageMode.value = false;
            selectedSessionIds.value = new Set();
            currentSessionId.value = '';
            store.setSession('');
            messages.value = [];
        };

        const selectApp = (appId) => {
            sessionManageMode.value = false;
            selectedSessionIds.value = new Set();
            currentAppId.value = appId || '';
            currentSessionId.value = '';
            store.setSession('');
            messages.value = [];
        };

        const selectSession = async (sessionId) => {
            currentSessionId.value = sessionId;
            store.setSession(sessionId);
            await loadHistory();
        };

        const createSession = async () => {
            try {
                const appId = currentAppId.value ? Number(currentAppId.value) : undefined;
                const session = await api.createSession(appId);
                currentSessionId.value = session.sessionId;
                store.setSession(session.sessionId);
                await loadSessions();
                await loadHistory();
            } catch (e) { alert('创建会话失败: ' + e.message); }
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
            if (!deletedSessionIds.has(currentSessionId.value)) return;
            clearStreamTimers();
            if (stopStreamFn.value) {
                stopStreamFn.value();
                stopStreamFn.value = null;
            }
            streaming.value = false;
            sending.value = false;
            messages.value = [];
            currentThinking.value = '';
            currentReasoning.value = '';
            currentToolCall.value = null;
            finalAnswer.value = '';
            finalAnswerSaved.value = false;
            currentEvents.value = [];
            streamPhase.value = 'idle';

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
            if (!currentSessionId.value) return;
            loadingHistory.value = true;
            try {
                const history = await api.getHistory(currentSessionId.value);
                messages.value = history || [];
                scrollToBottom();
            } catch (e) { console.error('加载历史失败:', e); }
            finally { loadingHistory.value = false; }
        };

        const send = async () => {
            if (!currentSessionId.value) { alert('请先创建会话'); return; }
            const text = inputText.value.trim();
            if (!text && pendingFiles.value.length === 0) return;

            sending.value = true;
            streaming.value = true;
            inputText.value = '';
            scrollToBottom();
            currentThinking.value = '';
            currentReasoning.value = '';
            currentToolCall.value = null;
            finalAnswer.value = '';
            finalAnswerSaved.value = false;
            currentEvents.value = [];
            streamPhase.value = 'planning';
            clearStreamTimers();

            let uploadedFiles = [];
            if (pendingFiles.value.length > 0) {
                for (const file of pendingFiles.value) {
                    try {
                        const result = await api.uploadFile(currentSessionId.value, file);
                        uploadedFiles.push({ name: file.name, path: result });
                    } catch (e) {
                        if (window.DuplicateHandler && window.DuplicateHandler.isDuplicateError(e)) {
                            const handleResult = await window.DuplicateHandler.handle({
                                file, error: e,
                                onReplace: () => api.replaceFile(currentSessionId.value, file),
                                onKeepBoth: (newFile) => api.uploadFile(currentSessionId.value, newFile)
                            });
                            if (handleResult === 'replace-done' || handleResult === 'keep-both-done') {
                                const name = handleResult === 'keep-both-done' ? (newFile?.name || file.name) : file.name;
                                uploadedFiles.push({ name, path: '/home/gem/uploads/' + name });
                            }
                        }
                    }
                }
                pendingFiles.value = [];
            }

            let fullMessage = text;
            if (uploadedFiles.length > 0) {
                fullMessage = (text ? text + '\n\n' : '') + '【上传的文件】\n' + uploadedFiles.map(f => '📎 ' + f.name).join('\n');
            }

            messages.value.push({ role: 'user', content: fullMessage, timestamp: Date.now() });
            streamBaselineLength = messages.value.length;
            scrollToBottom();
            const stop = api.createChatStream(currentSessionId.value, fullMessage, handleStreamEvent);
            stopStreamFn.value = stop;
            startStreamHistorySync();
        };

        // 清理兜底收尾计时器，新的流或主动停止时不能沿用旧计时。
        const clearAutoFinishTimer = () => {
            if (autoFinishTimer) {
                clearTimeout(autoFinishTimer);
                autoFinishTimer = null;
            }
        };

        // 清理所有流式兜底计时器，避免旧请求影响新的对话。
        const clearStreamTimers = () => {
            clearAutoFinishTimer();
            if (streamSyncTimer) {
                clearInterval(streamSyncTimer);
                streamSyncTimer = null;
            }
        };

        // 后端已保存最终助手消息但 SSE 终止事件未到达时，通过历史接口自动收尾。
        const startStreamHistorySync = () => {
            if (streamSyncTimer) clearInterval(streamSyncTimer);
            streamSyncTimer = setInterval(syncStreamHistory, 2500);
        };

        // 静默拉取历史，只在检测到新的助手回复时替换界面并停止转圈。
        const syncStreamHistory = async () => {
            if (!streaming.value || !currentSessionId.value) return;
            try {
                const history = await api.getHistory(currentSessionId.value);
                if (!Array.isArray(history) || history.length <= streamBaselineLength) return;
                const newMessages = history.slice(streamBaselineLength);
                const hasAssistantReply = newMessages.some(msg => msg && msg.role === 'assistant' && msg.content);
                if (!hasAssistantReply) return;
                const stop = stopStreamFn.value;
                stopStreamFn.value = null;
                clearStreamTimers();
                if (stop) stop();
                streaming.value = false;
                sending.value = false;
                finalAnswerSaved.value = true;
                currentThinking.value = '';
                currentReasoning.value = '';
                currentToolCall.value = null;
                currentEvents.value = [];
                streamPhase.value = 'idle';
                messages.value = history;
                scrollToBottom();
            } catch (e) {
                console.warn('流式历史同步失败:', e);
            }
        };

        // 在收到最终回答后短暂等待 done；若没有等到，就用已有回答直接完成前端状态。
        const scheduleAutoFinish = () => {
            clearAutoFinishTimer();
            // answer 是后端最终回答事件；done 偶发丢失时，前端也要及时收尾。
            autoFinishTimer = setTimeout(() => {
                if (streaming.value && finalAnswer.value) finishStream();
            }, 1200);
        };

        // 计算同类步骤的展示序号，支持 toolCall 被替换成 toolResult 后仍连续编号。
        const nextStepIndex = (type) => currentEvents.value.filter(e => e.type === type || e.originalType === type).length + 1;

        // 将工具调用开始事件立即加入时间线，让用户能实时看到正在执行的工具和参数。
        const appendToolCallEvent = (data) => {
            const event = {
                type: 'toolCall',
                tool: data.tool,
                args: data.args || {},
                elapsed: data.elapsed || 0,
                status: 'running',
                stepIndex: data.stepIndex || nextStepIndex('toolCall')
            };
            currentEvents.value.push(event);
            currentToolCall.value = { ...event, eventIndex: currentEvents.value.length - 1 };
        };

        // 将工具结果合并回对应的运行中步骤；如果开始事件丢失，则补一条完整结果。
        const completeToolCallEvent = (data) => {
            let eventIndex = currentToolCall.value && currentToolCall.value.eventIndex != null
                ? currentToolCall.value.eventIndex
                : -1;
            if (eventIndex < 0) {
                for (let i = currentEvents.value.length - 1; i >= 0; i--) {
                    const event = currentEvents.value[i];
                    if (event.type === 'toolCall' && event.status === 'running' && (!data.tool || event.tool === data.tool)) {
                        eventIndex = i;
                        break;
                    }
                }
            }
            const previous = eventIndex >= 0 ? currentEvents.value[eventIndex] : null;
            const completed = {
                type: 'toolResult',
                originalType: 'toolCall',
                tool: data.tool || previous?.tool || currentToolCall.value?.tool || '',
                args: previous?.args || currentToolCall.value?.args || {},
                result: data.result || '',
                duration: data.duration,
                elapsed: data.duration || previous?.elapsed || 0,
                status: 'completed',
                stepIndex: previous?.stepIndex || nextStepIndex('toolCall')
            };
            if (eventIndex >= 0) currentEvents.value.splice(eventIndex, 1, completed);
            else currentEvents.value.push(completed);
            currentToolCall.value = null;
        };

        const handleStreamEvent = (event) => {
            const { type, data = {} } = event || {};
            switch (type) {
                case 'plan': currentEvents.value.push({ type: 'plan', content: data.content }); streamPhase.value = 'plan_ready'; scrollToBottom(); break;
                case 'thinking_start': currentThinking.value = ''; currentReasoning.value = ''; streamPhase.value = 'thinking'; scrollToBottom(); break;
                case 'token': currentThinking.value += data.content || ''; streamPhase.value = 'generating'; scrollToBottom(); break;
                case 'reasoning_token': currentReasoning.value += data.content || ''; streamPhase.value = 'thinking'; scrollToBottom(); break;
                case 'thinking_end':
                    if (currentThinking.value) currentEvents.value.push({ type: 'thinking', content: currentThinking.value, stepIndex: nextStepIndex('thinking') });
                    if (currentReasoning.value) currentEvents.value.push({ type: 'reasoning', content: currentReasoning.value, stepIndex: nextStepIndex('reasoning') });
                    currentThinking.value = ''; currentReasoning.value = ''; streamPhase.value = 'processing'; scrollToBottom(); break;
                case 'tool_call': appendToolCallEvent(data); streamPhase.value = 'tool'; scrollToBottom(); break;
                case 'tool_executing':
                    if (currentToolCall.value) {
                        currentToolCall.value.elapsed = data.elapsed || 0;
                        const eventIndex = currentToolCall.value.eventIndex;
                        if (eventIndex != null && currentEvents.value[eventIndex]) currentEvents.value[eventIndex].elapsed = currentToolCall.value.elapsed;
                    }
                    streamPhase.value = 'tool'; break;
                case 'observation':
                    completeToolCallEvent(data); streamPhase.value = 'tool_done'; scrollToBottom(); break;
                case 'answer':
                    finalAnswer.value = data.content || ''; streamPhase.value = 'answer';
                    const idx = currentEvents.value.map(e => e.type === 'thinking' ? e.content : null).lastIndexOf(finalAnswer.value);
                    if (idx >= 0) currentEvents.value.splice(idx, 1);
                    scheduleAutoFinish();
                    scrollToBottom(); break;
                case 'done': finishStream({ refreshIfEmpty: true }); break;
                case 'interrupted': finishStream(); messages.value.push({ role: 'assistant', content: '⚠️ 中断: ' + data.reason, timestamp: Date.now(), error: '任务中断' }); break;
                case 'error': finishStream(); messages.value.push({ role: 'assistant', content: '❌ 错误: ' + (data.message || '未知'), timestamp: Date.now(), error: data.message || '未知错误', _lastText: '' }); break;
                case 'heartbeat': break;
            }
        };

        const finishStream = (options = {}) => {
            clearStreamTimers();
            streaming.value = false; sending.value = false; stopStreamFn.value = null;
            if (!finalAnswer.value && currentThinking.value) finalAnswer.value = currentThinking.value;
            if (finalAnswer.value && !finalAnswerSaved.value) { messages.value.push({ role: 'assistant', content: finalAnswer.value, events: [...currentEvents.value], timestamp: Date.now() }); finalAnswerSaved.value = true; }
            if (!finalAnswer.value && options.refreshIfEmpty) loadHistory();
            currentThinking.value = ''; currentReasoning.value = ''; currentToolCall.value = null; currentEvents.value = []; streamPhase.value = 'idle'; scrollToBottom();
        };

        const stopStream = () => { clearStreamTimers(); if (stopStreamFn.value) { stopStreamFn.value(); stopStreamFn.value = null; } };
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
                meta: [presentation.typeLabel, formatFileSize(fileSize)].filter(Boolean).join(' · '),
                ...presentation,
            };
        };

        const extractArtifactsFromEvents = (events) => {
            const artifacts = (events || []).map(artifactFromToolEvent).filter(Boolean);
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
            const target = event.target.closest('[data-sandbox-preview="true"]');
            if (!target) return;
            event.preventDefault();
            if (typeof FilePreviewer !== 'undefined') FilePreviewer.preview({ source: 'workspace', sessionId: target.dataset.sessionId || currentSessionId.value, filePath: target.dataset.filePath, fileName: target.dataset.fileName, fileType: target.dataset.fileType });
        };

        const renderContent = (msg) => msg.role === 'assistant' ? marked.parse(msg.content || '', { renderer: chatMarkdownRenderer }) : escapeHtml(msg.content || '');
        const renderMarkdown = (c) => c ? marked.parse(c, { renderer: chatMarkdownRenderer }) : '';
        const previewText = (c, max = 90) => { if (!c) return ''; const t = String(c).replace(/\s+/g, ' ').trim(); return t.length > max ? t.substring(0, max) + '...' : t; };
        const processTitle = (e) => { switch (e.type) { case 'plan': return '规划任务'; case 'thinking': return `思考 · ${e.stepIndex || 1}`; case 'reasoning': return `推理 · ${e.stepIndex || 1}`; case 'toolCall': return `工具 ${e.tool || ''}`; case 'toolResult': return `工具 ${e.tool || ''}`; default: return '处理'; } };
        const processPreview = (e) => {
            if (e.type === 'toolCall') return e.elapsed ? `执行中 ${e.elapsed}ms` : '执行中';
            if (e.type === 'toolResult') return e.duration != null ? `${e.duration}ms` : '已完成';
            return previewText(e.content, 90);
        };
        const formatTime = (ts) => { if (!ts) return ''; const d = new Date(ts); const now = new Date(); const hh = String(d.getHours()).padStart(2, '0'), mm = String(d.getMinutes()).padStart(2, '0'); return d.toDateString() === now.toDateString() ? `${hh}:${mm}` : `${d.getMonth() + 1}/${d.getDate()} ${hh}:${mm}`; };

        const scrollToBottom = () => { Vue.nextTick(() => { if (messagesEl.value) { messagesEl.value.scrollTop = messagesEl.value.scrollHeight; checkScrollPosition(); } }); };
        const checkScrollPosition = () => { if (!messagesEl.value) return; const { scrollTop, scrollHeight, clientHeight } = messagesEl.value; showScrollBtn.value = scrollHeight - scrollTop - clientHeight > 200; };

        const copyId = () => { if (currentSessionId.value) { navigator.clipboard.writeText(currentSessionId.value); copied.value = true; setTimeout(() => copied.value = false, 1500); } };
        const handleFileSelect = (e) => { Array.from(e.target.files).forEach(f => { if (!pendingFiles.value.some(p => p.name === f.name)) pendingFiles.value.push(f); }); e.target.value = ''; };
        const removeFile = (i) => { pendingFiles.value.splice(i, 1); };
        const escapeHtml = (s) => s ? s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;') : '';

        const toggleVnc = () => { vncOpen.value = !vncOpen.value; if (vncOpen.value && currentSessionId.value && !vncUrl.value) loadVncView(); };
        const loadVncView = async () => {
            if (!currentSessionId.value) { vncPlaceholder.value = '请先创建会话'; return; }
            vncStatus.value = '连接中...'; vncPlaceholder.value = '获取沙箱地址...';
            try { const ep = await api.getAioEndpoint(currentSessionId.value); if (ep) { vncUrl.value = `http://${ep}/`; vncStatus.value = '已连接'; } else { vncPlaceholder.value = '无法获取地址'; vncStatus.value = '失败'; } }
            catch (e) { vncPlaceholder.value = '连接错误'; vncStatus.value = '失败'; }
        };
        const resizeVnc = (d) => { vncWidth.value = Math.max(30, Math.min(90, vncWidth.value + d)); };
        const startResize = (e) => {
            isResizing = true; startX = e.clientX; document.body.style.cursor = 'col-resize'; document.body.style.userSelect = 'none';
            const m = (ev) => { if (!isResizing) return; const w = document.querySelector('.chat-page'); if (!w) return; const dw = startX - ev.clientX; vncWidth.value = Math.max(20, Math.min(80, (vncWidth.value / 100) * w.offsetWidth + dw) / w.offsetWidth * 100); startX = ev.clientX; };
            const u = () => { isResizing = false; document.body.style.cursor = ''; document.body.style.userSelect = ''; document.removeEventListener('mousemove', m); document.removeEventListener('mouseup', u); };
            document.addEventListener('mousemove', m); document.addEventListener('mouseup', u);
        };

        Vue.watch(currentSessionId, () => { vncUrl.value = ''; vncStatus.value = '未连接'; vncPlaceholder.value = '请先创建会话'; });

        Vue.onMounted(() => {
            marked.setOptions({ highlight: (c, l) => l && hljs.getLanguage(l) ? hljs.highlight(c, { language: l }).value : hljs.highlightAuto(c).value, breaks: true, gfm: true });
            const p = new URLSearchParams(window.location.hash.split('?')[1] || ''); const aid = p.get('appId'); if (aid) currentAppId.value = aid;
            Promise.all([loadApps(), loadSessions()]).then(() => { if (currentSessionId.value) loadHistory(); });
            Vue.nextTick(() => { if (messagesEl.value) messagesEl.value.addEventListener('scroll', checkScrollPosition); });
        });

        Vue.onBeforeUnmount(() => {
            Object.values(artifactBlobUrls).forEach(url => {
                if (url) URL.revokeObjectURL(url);
            });
        });

        return {
            store, messagesEl, apps, sessions, currentAppId, currentApp, filteredSessions, currentSessionId,
            messages, inputText, sending, pendingFiles, copied, loadingHistory, showScrollBtn,
            sessionManageMode, selectedSessionCount, allFilteredSessionsSelected,
            batchDeletePending, batchDeleteError, batchDeleting,
            sessionPendingDelete, deleteSessionError, deletingSessionId,
            streaming, streamingStatus, streamPhase, currentThinking, currentReasoning, currentToolCall,
            finalAnswer, finalAnswerSaved, currentEvents,
            liveArtifacts, messageArtifacts, artifactBlobUrl, artifactLoadError,
            previewArtifact, downloadArtifact,
            onAppChange, selectApp, selectSession, createSession,
            toggleSessionManagement, handleSessionItemClick, isSessionSelected: sessionId => selectedSessionIds.value.has(sessionId),
            toggleSessionSelection, toggleSelectAllSessions, requestBatchDelete, cancelBatchDelete, confirmBatchDelete,
            requestDeleteSession, cancelDeleteSession, confirmDeleteSession,
            switchSession, send, stopStream, retryMessage,
            renderContent, renderMarkdown, previewText, processTitle, processPreview,
            handleMessageContentClick, formatTime, copyId, handleFileSelect, removeFile,
            vncOpen, vncUrl, vncStatus, vncPlaceholder, vncWidth, toggleVnc, resizeVnc, startResize, scrollToBottom
        };
    }
};

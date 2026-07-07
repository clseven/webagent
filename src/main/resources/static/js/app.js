// Vue 应用入口
(function() {
    // Toast 组件
    const ToastContainer = {
        template: `
            <div class="toast-container">
                <transition-group name="toast">
                    <div v-for="toast in toasts" :key="toast.id" :class="['toast', toast.type]" @click="remove(toast.id)">
                        <svg class="toast-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <circle v-if="toast.type === 'success'" cx="12" cy="12" r="10"/><path v-if="toast.type === 'success'" d="m9 12 2 2 4-4"/>
                            <circle v-else-if="toast.type === 'error'" cx="12" cy="12" r="10"/><line v-if="toast.type === 'error'" x1="15" y1="9" x2="9" y2="15"/><line v-if="toast.type === 'error'" x1="9" y1="9" x2="15" y2="15"/>
                            <path v-else-if="toast.type === 'warning'" d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line v-if="toast.type === 'warning'" x1="12" y1="9" x2="12" y2="13"/><line v-if="toast.type === 'warning'" x1="12" y1="17" x2="12.01" y2="17"/>
                            <circle v-else cx="12" cy="12" r="10"/><line v-if="toast.type === 'info'" x1="12" y1="16" x2="12" y2="12"/><line v-if="toast.type === 'info'" x1="12" y1="8" x2="12.01" y2="8"/>
                        </svg>
                        <span class="toast-message">{{ toast.message }}</span>
                        <button class="toast-close" @click.stop="remove(toast.id)">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                        </button>
                    </div>
                </transition-group>
            </div>
        `,
        props: ['toasts'],
        emits: ['remove'],
        setup(props, { emit }) {
            const remove = (id) => emit('remove', id);
            return { remove };
        }
    };

    // 全局状态
    const store = Vue.reactive({
        token: localStorage.getItem('auth_token') || null,
        username: localStorage.getItem('username') || '',
        currentSessionId: localStorage.getItem('agent_session_id') || null,
        toasts: [],
        // 按会话保存正在进行的流式回复，避免 Chat 页面卸载或切会话后丢失思考链。
        liveStreams: {},
        // 记录流式状态变化版本，供页面在跨路由恢复时感知完成事件。
        liveStreamVersion: 0,
        // 记录已完成但可能尚未被当前页面消费的流式会话。
        completedLiveStreams: {},

        get isAuthenticated() {
            return !!this.token;
        },

        setAuth(token, username) {
            this.token = token;
            this.username = username;
            localStorage.setItem('auth_token', token);
            localStorage.setItem('username', username);
        },

        logout() {
            Object.keys(this.liveStreams).forEach(sessionId => this.clearLiveStream(sessionId, { stop: true }));
            this.token = null;
            this.username = '';
            this.currentSessionId = null;
            this.toasts = [];
            this.completedLiveStreams = {};
            localStorage.removeItem('auth_token');
            localStorage.removeItem('username');
            localStorage.removeItem('agent_session_id');
        },

        setSession(id) {
            this.currentSessionId = id;
            if (id) {
                localStorage.setItem('agent_session_id', id);
            } else {
                localStorage.removeItem('agent_session_id');
            }
        },

        // 为指定会话创建一份新的流式回复状态；同会话旧流会被停止并替换。
        createLiveStream(sessionId, initial = {}) {
            if (!sessionId) return null;
            this.clearLiveStream(sessionId, { stop: true });
            delete this.completedLiveStreams[sessionId];
            const stream = {
                sessionId,
                streamId: Date.now() + Math.random().toString(36).slice(2),
                streaming: true,
                sending: true,
                stopStreamFn: null,
                currentThinking: '',
                currentReasoning: '',
                currentToolCall: null,
                currentStepIndex: 0,
                finalAnswer: '',
                finalAnswerSaved: false,
                streamPhase: 'idle',
                currentEvents: [],
                pendingUserMessage: null,
                streamBaselineLength: 0,
                autoFinishTimer: null,
                streamSyncTimer: null,
                ...initial
            };
            this.liveStreams[sessionId] = stream;
            this.liveStreamVersion++;
            return stream;
        },

        // 获取指定会话的流式回复状态；没有正在进行的流时返回 null。
        getLiveStream(sessionId) {
            return sessionId ? this.liveStreams[sessionId] || null : null;
        },

        // 清理指定会话的流式状态；必要时同时中止底层 SSE 请求。
        clearLiveStream(sessionId, { stop = false, remove = true } = {}) {
            const stream = this.getLiveStream(sessionId);
            if (!stream) return;
            if (stream.autoFinishTimer) clearTimeout(stream.autoFinishTimer);
            if (stream.streamSyncTimer) clearInterval(stream.streamSyncTimer);
            stream.autoFinishTimer = null;
            stream.streamSyncTimer = null;
            if (stop && stream.stopStreamFn) stream.stopStreamFn();
            stream.stopStreamFn = null;
            stream.streaming = false;
            stream.sending = false;
            if (remove) delete this.liveStreams[sessionId];
            this.liveStreamVersion++;
        },

        // 标记某个流式会话已完成，让当前或下次进入 Chat 页时决定是否刷新历史。
        markLiveStreamCompleted(sessionId, { refreshHistory = true } = {}) {
            if (!sessionId) return;
            this.clearLiveStream(sessionId);
            this.completedLiveStreams[sessionId] = {
                refreshHistory,
                completedAt: Date.now()
            };
            this.liveStreamVersion++;
        },

        // 读取并移除指定会话的完成标记，避免重复刷新同一段历史。
        consumeCompletedLiveStream(sessionId) {
            if (!sessionId || !this.completedLiveStreams[sessionId]) return null;
            const completed = this.completedLiveStreams[sessionId];
            delete this.completedLiveStreams[sessionId];
            this.liveStreamVersion++;
            return completed;
        },

        showToast({ type = 'info', message = '', duration = 3500 } = {}) {
            const id = Date.now() + Math.random().toString(36).substr(2, 6);
            this.toasts.push({ id, type, message });
            if (duration > 0) {
                setTimeout(() => this.removeToast(id), duration);
            }
            return id;
        },

        removeToast(id) {
            const idx = this.toasts.findIndex(t => t.id === id);
            if (idx >= 0) this.toasts.splice(idx, 1);
        }
    });

    // 路由配置
    const routes = [
        { path: '/', redirect: '/chat' },
        { path: '/login', component: LoginPage },
        { path: '/apps', component: AgentAppsPage, meta: { requiresAuth: true } },
        { path: '/chat', component: ChatPage, meta: { requiresAuth: true } },
        { path: '/skills', component: SkillsPage, meta: { requiresAuth: true } },
        { path: '/knowledge', component: KnowledgePage, meta: { requiresAuth: true } },
        { path: '/mcp', component: McpPage, meta: { requiresAuth: true } },
        { path: '/token-stats', component: TokenStatsPage, meta: { requiresAuth: true } }
    ];

    const router = VueRouter.createRouter({
        history: VueRouter.createWebHashHistory(),
        routes
    });

    // 路由守卫
    router.beforeEach(async (to, from, next) => {
        if (to.path === '/login') {
            if (store.isAuthenticated) {
                next('/');
            } else {
                next();
            }
            return;
        }

        if (to.meta.requiresAuth) {
            if (!store.isAuthenticated) {
                next('/login');
                return;
            }

            try {
                const user = await api.me();
                store.username = user.username;
            } catch (e) {
                store.logout();
                next('/login');
                return;
            }
        }

        next();
    });

    // 创建 Vue 应用
    const app = Vue.createApp({
        template: `
            <MainLayout v-if="store.isAuthenticated" />
            <LoginPage v-else />
        `,
        setup() {
            return { store };
        }
    });

    // 提供全局状态
    app.provide('store', store);

    // 注册组件
    app.component('MainLayout', MainLayout);
    app.component('LoginPage', LoginPage);
    app.component('AgentAppsPage', AgentAppsPage);
    app.component('ChatPage', ChatPage);
    app.component('SkillsPage', SkillsPage);
    app.component('KnowledgePage', KnowledgePage);
    app.component('McpPage', McpPage);
    app.component('WorkspaceBrowser', WorkspaceBrowser);
    app.component('TokenStatsPage', TokenStatsPage);

    // 使用路由
    app.use(router);

    // 挂载应用
    app.mount('#app');

    // 挂载全局 Toast 容器
    const toastApp = Vue.createApp({
        template: `<ToastContainer :toasts="store.toasts" @remove="store.removeToast" />`,
        setup() {
            return { store };
        }
    });
    toastApp.component('ToastContainer', ToastContainer);
    toastApp.provide('store', store);
    const toastEl = document.createElement('div');
    document.body.appendChild(toastEl);
    toastApp.mount(toastEl);
})();

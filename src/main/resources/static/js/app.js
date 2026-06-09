// Vue 应用入口
(function() {
    // 全局状态
    const store = Vue.reactive({
        token: localStorage.getItem('auth_token') || null,
        username: localStorage.getItem('username') || '',
        currentSessionId: localStorage.getItem('agent_session_id') || null,

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
            this.token = null;
            this.username = '';
            this.currentSessionId = null;
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
        // 如果是登录页，直接放行
        if (to.path === '/login') {
            if (store.isAuthenticated) {
                next('/');
            } else {
                next();
            }
            return;
        }

        // 需要认证的页面
        if (to.meta.requiresAuth) {
            if (!store.isAuthenticated) {
                next('/login');
                return;
            }

            // 验证 token 是否有效
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
})();

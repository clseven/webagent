// 主布局组件 - 顶栏导航
const MainLayout = {
    template: `
        <div :class="['app-shell', isChatRoute ? 'chat-shell-mode' : '']">
            <!-- 顶部导航栏 -->
            <header v-if="!isChatRoute" class="topbar">
                <div class="topbar-left">
                    <div class="brand">
                        <div class="brand-logo">AI</div>
                        <span class="brand-name">智能体平台</span>
                    </div>
                </div>

                <nav class="topbar-nav">
                    <router-link to="/chat" class="topbar-link" active-class="active">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
                        <span>对话</span>
                    </router-link>
                    <router-link to="/apps" class="topbar-link" active-class="active">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>
                        <span>Agent</span>
                    </router-link>
                    <router-link to="/skills" class="topbar-link" active-class="active">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
                        <span>Skill</span>
                    </router-link>
                    <router-link to="/knowledge" class="topbar-link" active-class="active">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
                        <span>知识库</span>
                    </router-link>
                    <router-link to="/mcp" class="topbar-link" active-class="active">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>
                        <span>MCP</span>
                    </router-link>
                    <router-link to="/token-stats" class="topbar-link" active-class="active">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>
                        <span>Token</span>
                    </router-link>
                </nav>

                <div class="topbar-right">
                    <account-menu></account-menu>
                </div>
            </header>

            <!-- 主内容区 -->
            <main class="app-main">
                <router-view />
            </main>

            <workspace-browser v-if="!isChatRoute" />
        </div>
    `,
    setup() {
        const store = Vue.inject('store');
        const route = VueRouter.useRoute();
        const isChatRoute = Vue.computed(() => route.path === '/chat');
        return { store, isChatRoute };
    }
};

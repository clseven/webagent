// 主布局组件
const MainLayout = {
    template: `
        <div class="app-wrapper">
            <!-- 侧边栏 -->
            <nav class="sidebar">
                <div class="sidebar-header">通用智能体平台</div>
                <router-link to="/apps" class="sidebar-item">
                    <span class="icon">🤖</span>
                    <span>Agent 应用</span>
                </router-link>
                <router-link to="/chat" class="sidebar-item">
                    <span class="icon">💬</span>
                    <span>对话</span>
                </router-link>
                <router-link to="/skills" class="sidebar-item">
                    <span class="icon">⚡</span>
                    <span>Skill</span>
                </router-link>
                <router-link to="/knowledge" class="sidebar-item">
                    <span class="icon">📚</span>
                    <span>知识库</span>
                </router-link>
                <router-link to="/mcp" class="sidebar-item">
                    <span class="icon">🔌</span>
                    <span>MCP</span>
                </router-link>
                <router-link to="/token-stats" class="sidebar-item">
                    <span class="icon">📊</span>
                    <span>Token</span>
                </router-link>
                <div class="sidebar-footer">
                    <div class="user-info">
                        <span>👤</span>
                        <span>{{ store.username }}</span>
                    </div>
                    <button class="logout-btn" @click="logout">退出登录</button>
                </div>
            </nav>

            <!-- 主内容区 -->
            <div class="main-content">
                <router-view />
            </div>

            <!-- 工作空间浏览器 -->
            <workspace-browser />
        </div>
    `,
    setup() {
        const store = Vue.inject('store');
        const router = VueRouter.useRouter();

        const logout = () => {
            store.logout();
            router.push('/login');
        };

        return { store, logout };
    }
};

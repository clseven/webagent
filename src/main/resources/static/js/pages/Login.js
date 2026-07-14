// 登录页组件
const LoginPage = {
    template: `
        <div class="auth-overlay webagent-auth">
            <main class="auth-shell" aria-labelledby="auth-title">
                <section class="auth-card">
                    <div class="auth-card-header">
                        <div class="auth-logo" aria-hidden="true">W</div>
                        <h1 id="auth-title">Welcome to WebAgent</h1>
                        <p class="sub">登录 WebAgent 账号继续；没有账号可以直接注册。</p>
                    </div>
                    <form class="auth-form" @submit.prevent="login">
                        <label class="auth-field">
                            <span>用户名</span>
                            <input
                                class="input"
                                v-model.trim="username"
                                placeholder="输入用户名"
                                autocomplete="username"
                                :disabled="loading"
                            >
                        </label>
                        <label class="auth-field">
                            <span>密码</span>
                            <input
                                class="input"
                                v-model="password"
                                type="password"
                                placeholder="输入密码"
                                autocomplete="current-password"
                                :disabled="loading"
                            >
                        </label>
                        <div class="error-msg" v-if="error">{{ error }}</div>
                        <div class="btn-row">
                            <button class="btn btn-primary auth-primary" type="submit" :disabled="loading">
                                {{ loading && activeAction === 'login' ? '登录中...' : '登录' }}
                            </button>
                            <button class="btn btn-secondary auth-secondary" type="button" @click="register" :disabled="loading">
                                {{ loading && activeAction === 'register' ? '注册中...' : '注册' }}
                            </button>
                        </div>
                    </form>
                </section>
            </main>
        </div>
    `,
    setup() {
        const store = Vue.inject('store');
        const router = VueRouter.useRouter();

        const username = Vue.ref('');
        const password = Vue.ref('');
        const error = Vue.ref('');
        const loading = Vue.ref(false);
        const activeAction = Vue.ref('');

        const validate = () => {
            if (!username.value || !password.value) {
                error.value = '请输入用户名和密码';
                return false;
            }
            return true;
        };

        const login = async () => {
            if (!validate()) return;
            loading.value = true;
            activeAction.value = 'login';
            try {
                error.value = '';
                const data = await api.login(username.value, password.value);
                store.setAuth(data.token, data.user.username, data.user.id);
                router.push('/');
            } catch (e) {
                error.value = e.message;
            } finally {
                loading.value = false;
                activeAction.value = '';
            }
        };

        const register = async () => {
            if (!validate()) return;
            loading.value = true;
            activeAction.value = 'register';
            try {
                error.value = '';
                const data = await api.register(username.value, password.value);
                store.setAuth(data.token, data.user.username, data.user.id);
                router.push('/');
            } catch (e) {
                error.value = e.message;
            } finally {
                loading.value = false;
                activeAction.value = '';
            }
        };

        return { username, password, error, loading, activeAction, login, register };
    }
};

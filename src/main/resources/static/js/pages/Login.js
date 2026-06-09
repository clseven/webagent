// 登录页组件
const LoginPage = {
    template: `
        <div class="auth-overlay">
            <div class="auth-card">
                <h2>登录 / 注册</h2>
                <p class="sub">输入账号密码，没有账号将自动注册</p>
                <div class="error-msg" v-if="error">{{ error }}</div>
                <input
                    v-model="username"
                    placeholder="用户名"
                    autocomplete="username"
                    @keyup.enter="login"
                >
                <input
                    v-model="password"
                    type="password"
                    placeholder="密码"
                    autocomplete="current-password"
                    @keyup.enter="login"
                >
                <div class="btn-row">
                    <button class="btn-login" @click="login">登录</button>
                    <button class="btn-register" @click="register">注册</button>
                </div>
            </div>
        </div>
    `,
    setup() {
        const store = Vue.inject('store');
        const router = VueRouter.useRouter();

        const username = Vue.ref('');
        const password = Vue.ref('');
        const error = Vue.ref('');

        const login = async () => {
            if (!username.value || !password.value) {
                error.value = '请输入用户名和密码';
                return;
            }
            try {
                error.value = '';
                const data = await api.login(username.value, password.value);
                store.setAuth(data.token, data.user.username);
                router.push('/');
            } catch (e) {
                error.value = e.message;
            }
        };

        const register = async () => {
            if (!username.value || !password.value) {
                error.value = '请输入用户名和密码';
                return;
            }
            try {
                error.value = '';
                const data = await api.register(username.value, password.value);
                store.setAuth(data.token, data.user.username);
                router.push('/');
            } catch (e) {
                error.value = e.message;
            }
        };

        return { username, password, error, login, register };
    }
};

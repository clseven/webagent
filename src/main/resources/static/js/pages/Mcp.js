// 用户私有 MCP 管理页组件
const McpPage = {
    template: `
        <div class="mcp-page page-container">
            <div class="mcp-page-header">
                <div>
                    <h1>MCP</h1>
                    <p class="page-desc">连接你自己的 Streamable HTTP MCP 服务</p>
                </div>
                <div class="mcp-header-actions">
                    <button class="btn-secondary" @click="reloadAll" :disabled="loading || reloading">
                        {{ reloading ? '连接中...' : '重新连接' }}
                    </button>
                    <button class="btn btn-primary" @click="openCreate">+ 添加 Server</button>
                </div>
            </div>

            <div class="mcp-security-note">
                <strong>用户私有配置</strong>
                <span>配置保存在你的 Sandbox：/home/gem/.mcp/servers.json。Header 值不会在页面回显，但 Sandbox 内是明文文件。</span>
            </div>

            <div class="mcp-loading" v-if="loading">正在读取 MCP 配置...</div>
            <div class="mcp-empty" v-else-if="servers.length === 0">
                <div class="mcp-empty-icon">↗</div>
                <h3>还没有私有 MCP Server</h3>
                <p>添加服务地址、认证 Header 和推荐超时时间后即可使用。</p>
                <button class="btn btn-primary" @click="openCreate">添加第一个 Server</button>
            </div>

            <div class="mcp-server-list" v-else>
                <article class="mcp-server-card" v-for="server in servers" :key="server.id">
                    <div class="mcp-server-top">
                        <div class="mcp-server-title">
                            <span class="mcp-status-dot" :class="server.connected ? 'connected' : 'disconnected'"></span>
                            <div>
                                <h3>{{ server.id }}</h3>
                                <div class="mcp-status-text">
                                    {{ !server.enabled ? '已停用' : (server.connected ? '已连接' : '连接失败') }}
                                </div>
                            </div>
                        </div>
                        <div class="mcp-card-actions">
                            <label class="mcp-switch" :title="server.enabled ? '停用' : '启用'">
                                <input type="checkbox" :checked="server.enabled" @change="toggleServer(server, $event.target.checked)">
                                <span></span>
                            </label>
                            <button class="btn-secondary btn-sm" @click="openEdit(server)">编辑</button>
                            <button class="btn-danger btn-sm" @click="removeServer(server)">删除</button>
                        </div>
                    </div>
                    <div class="mcp-endpoint">{{ server.url }}</div>
                    <div class="mcp-meta-row">
                        <span>Streamable HTTP</span>
                        <span>超时：{{ server.requestTimeoutSeconds ? server.requestTimeoutSeconds + ' 秒' : '系统默认' }}</span>
                        <span>Headers：{{ server.headerNames.length }}</span>
                        <span>工具：{{ server.toolNames.length }}</span>
                    </div>
                    <div class="mcp-tool-list" v-if="server.toolNames.length">
                        <span v-for="tool in server.toolNames" :key="tool">{{ tool }}</span>
                    </div>
                    <div class="mcp-error" v-if="server.lastError">
                        <strong>{{ server.lastError.code }}</strong>
                        <span>{{ server.lastError.reason || server.lastError.detail || '连接失败' }}</span>
                    </div>
                </article>
            </div>

            <div class="modal-overlay" v-if="editing" @click.self="closeEditor">
                <div class="modal-content mcp-editor">
                    <div class="modal-header">
                        <div>
                            <h3>{{ editing.id ? '编辑 MCP Server' : '添加 MCP Server' }}</h3>
                            <p>当前仅支持用户私有 Streamable HTTP 服务</p>
                        </div>
                        <button class="modal-close" @click="closeEditor">×</button>
                    </div>
                    <div class="form-group">
                        <label>Server ID</label>
                        <input class="input" v-model.trim="form.id" :disabled="!!editing.id"
                               placeholder="例如 modao" maxlength="64">
                        <small>小写字母、数字、点、下划线或短横线</small>
                    </div>
                    <div class="form-group">
                        <label>服务器地址</label>
                        <input class="input" v-model.trim="form.url"
                               placeholder="https://example.com/mcp">
                    </div>
                    <div class="form-group">
                        <label>Request Timeout（秒）</label>
                        <input class="input" type="number" min="1" max="3600"
                               v-model="form.requestTimeoutSeconds" placeholder="留空使用系统默认值">
                        <small>按服务方建议填写，例如 300～600 秒；只影响一次工具调用的等待时间。</small>
                    </div>
                    <div class="mcp-header-editor">
                        <div class="mcp-editor-row-title">
                            <label>Headers</label>
                            <button class="btn-secondary btn-sm" @click="addHeader">+ 添加 Header</button>
                        </div>
                        <div class="mcp-header-row" v-for="(header, index) in form.headers" :key="index">
                            <input class="input" v-model.trim="header.name" placeholder="Header 名称">
                            <input class="input" type="password" v-model="header.value"
                                   :placeholder="header.configured ? '留空保留原值' : 'Header 值'">
                            <button class="mcp-remove-header" @click="removeHeader(index)" title="删除 Header">×</button>
                        </div>
                        <small v-if="form.headers.length">编辑已有 Header 时留空会保留原值；删除整行才会移除。</small>
                        <small v-else>例如：modao-token / 你的个人 Token。Token 前是否加 Bearer 以服务方文档为准。</small>
                    </div>
                    <div class="form-actions mcp-form-actions">
                        <button class="btn-secondary" @click="closeEditor">取消</button>
                        <button class="btn btn-primary" @click="saveServer" :disabled="saving || !canSave">
                            {{ saving ? '保存并连接中...' : '保存并连接' }}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    `,
    setup() {
        const store = Vue.inject('store');
        const servers = Vue.ref([]);
        const loading = Vue.ref(false);
        const reloading = Vue.ref(false);
        const saving = Vue.ref(false);
        const editing = Vue.ref(null);
        const form = Vue.reactive(emptyForm());

        function emptyForm() {
            return { id: '', url: '', enabled: true, requestTimeoutSeconds: '', headers: [] };
        }

        function resetForm(value) {
            Object.assign(form, emptyForm(), value || {});
        }

        async function loadServers() {
            loading.value = true;
            try {
                servers.value = await api.listMcpServers();
            } catch (e) {
                store.showToast({ type: 'error', message: '读取 MCP 配置失败：' + e.message });
            } finally {
                loading.value = false;
            }
        }

        function openCreate() {
            resetForm();
            editing.value = {};
        }

        function openEdit(server) {
            resetForm({
                id: server.id,
                url: server.url,
                enabled: server.enabled,
                requestTimeoutSeconds: server.requestTimeoutSeconds || '',
                headers: (server.headerNames || []).map(name => ({ name, value: '', configured: true }))
            });
            editing.value = server;
        }

        function closeEditor() {
            if (!saving.value) editing.value = null;
        }

        function addHeader() {
            form.headers.push({ name: '', value: '', configured: false });
        }

        function removeHeader(index) {
            form.headers.splice(index, 1);
        }

        function payload(enabled = form.enabled) {
            const headers = {};
            for (const header of form.headers) {
                if (header.name) headers[header.name] = header.value || '';
            }
            return {
                id: form.id,
                url: form.url,
                enabled,
                requestTimeoutSeconds: form.requestTimeoutSeconds === ''
                    ? null : Number(form.requestTimeoutSeconds),
                headers
            };
        }

        function reloadError(result) {
            const failed = result && result.failed ? Object.values(result.failed) : [];
            if (!failed.length) return null;
            return failed[0].reason || failed[0].detail || '连接失败';
        }

        async function saveServer() {
            saving.value = true;
            try {
                const result = editing.value.id
                    ? await api.updateMcpServer(editing.value.id, payload())
                    : await api.createMcpServer(payload());
                const error = reloadError(result);
                editing.value = null;
                await loadServers();
                store.showToast(error
                    ? { type: 'error', message: '配置已保存，但连接失败：' + error }
                    : { type: 'success', message: 'MCP Server 已保存并连接' });
            } catch (e) {
                store.showToast({ type: 'error', message: '保存失败：' + e.message });
            } finally {
                saving.value = false;
            }
        }

        async function toggleServer(server, enabled) {
            try {
                const headers = Object.fromEntries((server.headerNames || []).map(name => [name, '']));
                await api.updateMcpServer(server.id, {
                    id: server.id,
                    url: server.url,
                    enabled,
                    requestTimeoutSeconds: server.requestTimeoutSeconds,
                    headers
                });
                await loadServers();
            } catch (e) {
                store.showToast({ type: 'error', message: '更新状态失败：' + e.message });
                await loadServers();
            }
        }

        async function removeServer(server) {
            const confirmed = await store.confirm({
                title: '删除 MCP Server？',
                message: `确定删除「${server.id}」及其用户私有配置吗？`,
                confirmText: '删除',
                type: 'danger'
            });
            if (!confirmed) return;
            try {
                await api.deleteMcpServer(server.id);
                await loadServers();
            } catch (e) {
                store.showToast({ type: 'error', message: '删除失败：' + e.message });
            }
        }

        async function reloadAll() {
            reloading.value = true;
            try {
                const result = await api.reloadMcpServers();
                await loadServers();
                const error = reloadError(result);
                store.showToast(error
                    ? { type: 'error', message: '部分连接失败：' + error }
                    : { type: 'success', message: 'MCP 连接已刷新' });
            } catch (e) {
                store.showToast({ type: 'error', message: '重新连接失败：' + e.message });
            } finally {
                reloading.value = false;
            }
        }

        const canSave = Vue.computed(() => {
            const idOk = /^[a-z0-9][a-z0-9._-]{0,63}$/.test(form.id);
            const timeout = form.requestTimeoutSeconds;
            const timeoutOk = timeout === '' || (Number(timeout) >= 1 && Number(timeout) <= 3600);
            const headersOk = form.headers.every(item => item.name && (item.value || item.configured));
            return idOk && !!form.url && timeoutOk && headersOk;
        });

        Vue.onMounted(loadServers);
        return {
            servers, loading, reloading, saving, editing, form, canSave,
            openCreate, openEdit, closeEditor, addHeader, removeHeader,
            saveServer, toggleServer, removeServer, reloadAll
        };
    }
};

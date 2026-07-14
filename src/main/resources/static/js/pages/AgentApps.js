// Agent 应用管理页组件
const AgentAppsPage = {
    template: `
        <div class="agent-apps-page page-container">
            <h1>Agent 应用</h1>
            <p class="page-desc">创建和管理您的智能体应用，配置知识库和技能</p>

            <!-- 创建应用 -->
            <div class="create-app-section">
                <button class="btn btn-primary" @click="showCreate = true" v-if="!showCreate">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                        创建应用
                    </button>
                <div class="create-app-form" v-if="showCreate">
                    <h3>创建新应用</h3>
                    <div class="form-group">
                        <label>应用名称</label>
                        <input class="input" v-model="newApp.name" placeholder="如：毕业设计助手" maxlength="100">
                    </div>
                    <div class="form-group">
                        <label>应用描述</label>
                        <textarea class="input" v-model="newApp.description" placeholder="描述这个应用的用途..." maxlength="500" rows="3"></textarea>
                    </div>
                    <div class="form-actions">
                        <button class="btn-primary" @click="createApp" :disabled="!newApp.name.trim()">创建</button>
                        <button class="btn-secondary" @click="showCreate = false">取消</button>
                    </div>
                </div>
            </div>

            <!-- 应用列表 -->
            <div class="apps-list" v-if="apps.length > 0">
                <div class="app-card" v-for="app in apps" :key="app.id" :class="{ active: editingApp && editingApp.id === app.id }">
                    <div class="app-header">
                        <div class="app-info">
                            <h3>{{ app.name }}</h3>
                            <p class="app-desc">{{ app.description || '暂无描述' }}</p>
                        </div>
                        <div class="app-actions">
                            <button class="btn-secondary" @click="openChat(app)">对话</button>
                            <button class="btn-secondary" @click="editApp(app)">配置</button>
                            <button class="btn-danger" @click="deleteApp(app)">删除</button>
                        </div>
                    </div>
                    <div class="app-meta">
                        <span>关联知识库: {{ app.knowledgeBaseIds ? app.knowledgeBaseIds.length : 0 }} 个</span>
                        <span>关联技能: {{ app.skillIds ? app.skillIds.length : 0 }} 个</span>
                        <span>创建时间: {{ formatDate(app.createdAt) }}</span>
                    </div>
                </div>
            </div>

            <div class="empty-msg" v-else-if="!loading">
                暂无应用，点击上方按钮创建
            </div>

            <!-- 配置弹窗 -->
            <div class="modal-overlay" v-if="editingApp" @click.self="editingApp = null">
                <div class="modal-content">
                    <div class="modal-header">
                        <h3>配置应用: {{ editingApp.name }}</h3>
                        <button class="modal-close" @click="editingApp = null">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                    </button>
                    </div>

                    <!-- 基本信息 -->
                    <div class="config-section">
                        <h4>基本信息</h4>
                        <div class="form-group">
                            <label>应用名称</label>
                            <input class="input" v-model="editForm.name" maxlength="100">
                        </div>
                        <div class="form-group">
                            <label>应用描述</label>
                            <textarea class="input" v-model="editForm.description" maxlength="500" rows="2"></textarea>
                        </div>
                        <button class="btn-primary btn-sm" @click="saveAppInfo">保存信息</button>
                    </div>

                    <!-- 关联知识库 -->
                    <div class="config-section">
                        <h4>关联知识库</h4>
                        <div class="tag-list" v-if="knowledgeBases.length > 0">
                            <label class="tag-item" v-for="kb in knowledgeBases" :key="kb.id"
                                   :class="{ selected: selectedKbIds.has(kb.id) }">
                                <input type="checkbox" :value="kb.id" v-model="kbCheckboxes[kb.id]">
                                <span class="tag-name">{{ kb.name }}</span>
                                <span class="tag-desc">{{ kb.description || '' }}</span>
                            </label>
                        </div>
                        <div class="empty-hint" v-else>暂无知识库，请先在知识库页面创建</div>
                        <button class="btn-primary btn-sm" @click="saveKnowledgeBases" :disabled="!kbChanged">保存知识库关联</button>
                    </div>

                    <!-- 关联技能 -->
                    <div class="config-section">
                        <h4>关联技能</h4>
                        <div class="tag-list" v-if="skills.length > 0">
                            <label class="tag-item" v-for="skill in skills" :key="skill.id"
                                   :class="{ selected: selectedSkillIds.has(skill.id) }">
                                <input type="checkbox" :value="skill.id" v-model="skillCheckboxes[skill.id]">
                                <span class="tag-name">{{ skill.name || skill.id }}</span>
                                <span class="tag-desc">{{ skill.description || '' }}</span>
                            </label>
                        </div>
                        <div class="empty-hint" v-else>暂无技能</div>
                        <button class="btn-primary btn-sm" @click="saveSkills" :disabled="!skillChanged">保存技能关联</button>
                    </div>
                </div>
            </div>
        </div>
    `,
    setup() {
        const store = Vue.inject('store');
        const apps = Vue.ref([]);
        const loading = Vue.ref(false);
        const showCreate = Vue.ref(false);
        const newApp = Vue.ref({ name: '', description: '' });
        const editingApp = Vue.ref(null);
        const editForm = Vue.ref({ name: '', description: '' });

        const knowledgeBases = Vue.ref([]);
        const skills = Vue.ref([]);
        const selectedKbIds = Vue.ref(new Set());
        const selectedSkillIds = Vue.ref(new Set());
        const kbCheckboxes = Vue.reactive({});
        const skillCheckboxes = Vue.reactive({});

        const kbChanged = Vue.ref(false);
        const skillChanged = Vue.ref(false);

        async function loadApps() {
            loading.value = true;
            try {
                apps.value = await api.listApps();
            } catch (e) {
                console.error('加载应用列表失败', e);
            } finally {
                loading.value = false;
            }
        }

        async function loadKnowledgeBases() {
            try {
                knowledgeBases.value = await api.listKnowledgeBases();
            } catch (e) {
                console.error('加载知识库列表失败', e);
            }
        }

        async function loadSkills() {
            try {
                skills.value = await api.listSkills();
            } catch (e) {
                console.error('加载技能列表失败', e);
            }
        }

        async function createApp() {
            if (!newApp.value.name.trim()) return;
            try {
                await api.createApp(newApp.value.name, newApp.value.description);
                newApp.value = { name: '', description: '' };
                showCreate.value = false;
                await loadApps();
            } catch (e) {
                store.showToast({ type: 'error', message: '创建失败：' + e.message });
            }
        }

        async function deleteApp(app) {
            const confirmed = await store.confirm({
                title: '删除应用？',
                message: `确定删除应用「${app.name}」？`,
                confirmText: '删除',
                type: 'danger'
            });
            if (!confirmed) return;
            try {
                await api.deleteApp(app.id);
                await loadApps();
            } catch (e) {
                store.showToast({ type: 'error', message: '删除失败：' + e.message });
            }
        }

        async function editApp(app) {
            editingApp.value = app;
            editForm.value = { name: app.name, description: app.description || '' };
            selectedKbIds.value = new Set(app.knowledgeBaseIds || []);
            selectedSkillIds.value = new Set(app.skillIds || []);
            kbChanged.value = false;
            skillChanged.value = false;

            // 初始化 checkbox 状态
            for (const kb of knowledgeBases.value) {
                kbCheckboxes[kb.id] = selectedKbIds.value.has(kb.id);
            }
            for (const skill of skills.value) {
                skillCheckboxes[skill.id] = selectedSkillIds.value.has(skill.id);
            }
        }

        async function saveAppInfo() {
            try {
                await api.updateApp(editingApp.value.id, editForm.value.name, editForm.value.description);
                await loadApps();
                // 更新 editingApp 引用
                editingApp.value = apps.value.find(a => a.id === editingApp.value.id);
            } catch (e) {
                store.showToast({ type: 'error', message: '保存失败：' + e.message });
            }
        }

        async function saveKnowledgeBases() {
            const kbIds = Object.entries(kbCheckboxes)
                .filter(([_, checked]) => checked)
                .map(([id]) => Number(id));
            try {
                await api.updateAppKnowledgeBases(editingApp.value.id, kbIds);
                selectedKbIds.value = new Set(kbIds);
                kbChanged.value = false;
                await loadApps();
                editingApp.value = apps.value.find(a => a.id === editingApp.value.id);
            } catch (e) {
                store.showToast({ type: 'error', message: '保存失败：' + e.message });
            }
        }

        async function saveSkills() {
            const skillIds = Object.entries(skillCheckboxes)
                .filter(([_, checked]) => checked)
                .map(([id]) => id);
            try {
                await api.updateAppSkills(editingApp.value.id, skillIds);
                selectedSkillIds.value = new Set(skillIds);
                skillChanged.value = false;
                await loadApps();
                editingApp.value = apps.value.find(a => a.id === editingApp.value.id);
            } catch (e) {
                store.showToast({ type: 'error', message: '保存失败：' + e.message });
            }
        }

        function openChat(app) {
            // 跳转到对话页面，并带上 appId
            window.location.hash = '#/chat?appId=' + app.id;
        }

        function formatDate(dateStr) {
            if (!dateStr) return '-';
            const d = new Date(dateStr);
            return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        }

        // 监听 checkbox 变化
        Vue.watch(kbCheckboxes, () => {
            if (!editingApp.value) return;
            const current = Object.entries(kbCheckboxes)
                .filter(([_, checked]) => checked)
                .map(([id]) => Number(id));
            kbChanged.value = JSON.stringify(current.sort()) !== JSON.stringify([...selectedKbIds.value].sort());
        }, { deep: true });

        Vue.watch(skillCheckboxes, () => {
            if (!editingApp.value) return;
            const current = Object.entries(skillCheckboxes)
                .filter(([_, checked]) => checked)
                .map(([id]) => id);
            skillChanged.value = JSON.stringify(current.sort()) !== JSON.stringify([...selectedSkillIds.value].sort());
        }, { deep: true });

        Vue.onMounted(async () => {
            await Promise.all([loadApps(), loadKnowledgeBases(), loadSkills()]);
        });

        return {
            apps, loading, showCreate, newApp, editingApp, editForm,
            knowledgeBases, skills, selectedKbIds, selectedSkillIds,
            kbCheckboxes, skillCheckboxes, kbChanged, skillChanged,
            loadApps, createApp, deleteApp, editApp,
            saveAppInfo, saveKnowledgeBases, saveSkills,
            openChat, formatDate
        };
    }
};

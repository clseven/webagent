// 技能管理页组件
const SkillsPage = {
    template: `
        <div class="page-container">
            <div class="page-header">
                <div>
                    <h1>Skill 管理</h1>
                    <p class="page-desc">配置和管理 AI 技能，扩展智能体能力</p>
                </div>
            </div>

            <div class="page-section">
                <div class="section-label">技能目录</div>
                <div class="skill-root-row">
                    <input
                        class="input"
                        v-model="rootPath"
                        placeholder="输入技能根目录路径，例如: skills/ 或 D:/my-skills/"
                        @keyup.enter="loadSkills"
                    >
                    <button class="btn btn-primary" @click="loadSkills" :disabled="loading">
                        <span v-if="loading" class="thinking-spinner small" style="border-color:rgba(255,255,255,0.3);border-top-color:#fff;margin-right:4px;"></span>
                        {{ loading ? '加载中' : '加载' }}
                    </button>
                </div>
            </div>

            <div class="stats" v-if="skills.length > 0">
                <div class="stat-card">
                    <div class="stat-number">{{ skills.length }}</div>
                    <div class="stat-label">技能总数</div>
                </div>
                <div class="stat-card">
                    <div class="stat-number">{{ enabledCount }}</div>
                    <div class="stat-label">已启用</div>
                </div>
            </div>

            <div class="page-section" v-if="skills.length > 0">
                <div class="section-label">技能列表</div>
                <div class="skill-list">
                    <div v-for="skill in skills" :key="skill.id" class="skill-item">
                        <div class="skill-info">
                            <div class="skill-name">{{ skill.name || skill.id }}</div>
                            <div class="skill-id">{{ skill.id }}</div>
                            <div class="skill-desc">{{ skill.description || '暂无描述' }}</div>
                        </div>
                        <div class="skill-switch">
                            <label class="switch">
                                <input type="checkbox" :checked="enabledIds.has(skill.id)" :disabled="!store.currentSessionId" @change="toggleSkill(skill.id, $event.target.checked)">
                                <span class="slider"></span>
                            </label>
                        </div>
                    </div>
                </div>
            </div>

            <div v-if="loading" class="loading">加载中...</div>
            <div v-else-if="skills.length === 0 && !loading" class="empty-state" style="min-height:200px;">
                <h3>暂无技能</h3>
                <p>设置技能根目录后加载可用技能</p>
            </div>
        </div>
    `,
    setup() {
        const store = Vue.inject('store');
        const skills = Vue.ref([]);
        const rootPath = Vue.ref(localStorage.getItem('skill_root') || '');
        const enabledIds = Vue.ref(new Set());
        const loading = Vue.ref(false);
        const enabledCount = Vue.computed(() => enabledIds.value.size);

        const loadSkills = async () => {
            if (!rootPath.value) { alert('请输入技能根目录'); return; }
            localStorage.setItem('skill_root', rootPath.value);
            loading.value = true;
            try { await api.setSkillRoot(rootPath.value); skills.value = await api.listSkills(); }
            catch (e) { alert('加载技能失败: ' + e.message); }
            finally { loading.value = false; }
        };

        const loadEnabledSkills = async () => {
            if (!store.currentSessionId) return;
            try { const ids = await api.getEnabledSkills(store.currentSessionId); enabledIds.value = new Set(ids || []); }
            catch (e) { console.error('加载已启用技能失败:', e); }
        };

        const toggleSkill = async (skillId, enabled) => {
            if (!store.currentSessionId) { alert('请先选择或创建会话'); return; }
            try {
                if (enabled) { await api.enableSkill(store.currentSessionId, skillId); enabledIds.value.add(skillId); }
                else { await api.disableSkill(store.currentSessionId, skillId); enabledIds.value.delete(skillId); }
                enabledIds.value = new Set(enabledIds.value);
            } catch (e) { alert('操作失败: ' + e.message); }
        };

        Vue.onMounted(async () => { if (rootPath.value) await loadSkills(); await loadEnabledSkills(); });
        Vue.watch(() => store.currentSessionId, () => { loadEnabledSkills(); });

        return { store, skills, rootPath, enabledIds, loading, enabledCount, loadSkills, toggleSkill };
    }
};

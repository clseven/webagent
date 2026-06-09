// 技能管理页组件
const SkillsPage = {
    template: `
        <div class="skill-page">
            <h1>Skill 管理</h1>

            <!-- 技能根目录选择器 -->
            <div class="skill-root-selector">
                <label>技能根目录：</label>
                <input
                    v-model="rootPath"
                    placeholder="例如: skills/ 或 D:/my-skills/"
                    @keyup.enter="loadSkills"
                >
                <button @click="loadSkills">加载</button>
            </div>

            <!-- 统计卡片 -->
            <div class="stats">
                <div class="stat-card">
                    <div class="stat-number">{{ skills.length }}</div>
                    <div class="stat-label">技能总数</div>
                </div>
                <div class="stat-card">
                    <div class="stat-number">{{ enabledCount }}</div>
                    <div class="stat-label">已启用</div>
                </div>
            </div>

            <!-- 技能列表 -->
            <div class="skill-list">
                <div v-if="loading" class="loading">加载中...</div>
                <div v-else-if="skills.length === 0" class="empty">暂无技能，请设置技能根目录并加载</div>
                <div v-else v-for="skill in skills" :key="skill.id" class="skill-item">
                    <div class="skill-info">
                        <div class="skill-name">{{ skill.name || skill.id }}</div>
                        <div class="skill-id">{{ skill.id }}</div>
                        <div class="skill-desc">{{ skill.description || '暂无描述' }}</div>
                    </div>
                    <div class="skill-switch">
                        <label class="switch">
                            <input
                                type="checkbox"
                                :checked="enabledIds.has(skill.id)"
                                :disabled="!store.currentSessionId"
                                @change="toggleSkill(skill.id, $event.target.checked)"
                            >
                            <span class="slider"></span>
                        </label>
                    </div>
                </div>
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

        // 加载技能列表
        const loadSkills = async () => {
            if (!rootPath.value) {
                alert('请输入技能根目录');
                return;
            }

            localStorage.setItem('skill_root', rootPath.value);
            loading.value = true;

            try {
                await api.setSkillRoot(rootPath.value);
                skills.value = await api.listSkills();
            } catch (e) {
                alert('加载技能失败: ' + e.message);
            } finally {
                loading.value = false;
            }
        };

        // 加载已启用的技能
        const loadEnabledSkills = async () => {
            if (!store.currentSessionId) return;
            try {
                const ids = await api.getEnabledSkills(store.currentSessionId);
                enabledIds.value = new Set(ids || []);
            } catch (e) {
                console.error('加载已启用技能失败:', e);
            }
        };

        // 切换技能状态
        const toggleSkill = async (skillId, enabled) => {
            if (!store.currentSessionId) {
                alert('请先选择或创建会话');
                return;
            }

            try {
                if (enabled) {
                    await api.enableSkill(store.currentSessionId, skillId);
                    enabledIds.value.add(skillId);
                } else {
                    await api.disableSkill(store.currentSessionId, skillId);
                    enabledIds.value.delete(skillId);
                }
                // 触发响应式更新
                enabledIds.value = new Set(enabledIds.value);
            } catch (e) {
                alert('操作失败: ' + e.message);
            }
        };

        // 初始化
        Vue.onMounted(async () => {
            if (rootPath.value) {
                await loadSkills();
            }
            await loadEnabledSkills();
        });

        // 监听会话变化
        Vue.watch(() => store.currentSessionId, () => {
            loadEnabledSkills();
        });

        return {
            store,
            skills,
            rootPath,
            enabledIds,
            loading,
            enabledCount,
            loadSkills,
            toggleSkill
        };
    }
};

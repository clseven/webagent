// 技能管理页组件
// 数据来源策略：
//  - 有会话时：调用 /api/sessions/{sid}/skills/available 拿融合视图（本地仓库 ∪ 沙箱发现），
//    每项带 source（local/sandbox/both）和 enabled 标记。
//  - 无会话时：回退到 /api/skills 只读展示本地仓库列表，开关禁用。
//  - 切换会话或点"刷新沙箱"会重新拉融合视图，覆盖 Agent 在沙箱里下载/生成的新 skill。
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
                <div class="section-label">本地技能仓库</div>
                <div class="skill-root-row">
                    <input
                        class="input"
                        v-model="rootPath"
                        placeholder="输入技能根目录路径，例如: skills/ 或 D:/my-skills/"
                        @keyup.enter="loadLocal"
                    >
                    <button class="btn btn-primary" @click="loadLocal" :disabled="loading">
                        <span v-if="loading" class="thinking-spinner small" style="border-color:rgba(255,255,255,0.3);border-top-color:#fff;margin-right:4px;"></span>
                        {{ loading ? '加载中' : '加载' }}
                    </button>
                    <button v-if="store.currentSessionId" class="btn btn-secondary" @click="refreshSandbox" :disabled="loading" title="重新扫描沙箱 /home/gem/skills/">
                        🔄 刷新沙箱
                    </button>
                </div>
                <p class="page-desc" style="margin-top:6px;font-size:12px;">
                    沙箱目录: <code>/home/gem/skills/</code>
                    {{ store.currentSessionId ? '（已绑定当前会话，显示融合视图）' : '（未选会话，只显示本地仓库）' }}
                </p>
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
                <div class="stat-card" v-if="sandboxOnlyCount > 0">
                    <div class="stat-number">{{ sandboxOnlyCount }}</div>
                    <div class="stat-label">沙箱新发现</div>
                </div>
            </div>

            <div class="page-section" v-if="skills.length > 0">
                <div class="section-label">技能列表</div>
                <div class="skill-list">
                    <div v-for="skill in skills" :key="skill.id" class="skill-item">
                        <div class="skill-info">
                            <div class="skill-name-row">
                                <span class="skill-name">{{ skill.name || skill.id }}</span>
                                <span :class="['skill-source-badge', sourceOf(skill)]">
                                    {{ sourceLabel(sourceOf(skill)) }}
                                </span>
                            </div>
                            <div class="skill-id">{{ skill.id }}</div>
                            <div class="skill-desc">{{ skill.description || '暂无描述' }}</div>
                        </div>
                        <div class="skill-switch">
                            <label class="switch">
                                <input type="checkbox"
                                       :checked="isEnabled(skill)"
                                       :disabled="!store.currentSessionId"
                                       @change="toggleSkill(skill.id, $event.target.checked)">
                                <span class="slider"></span>
                            </label>
                        </div>
                    </div>
                </div>
            </div>

            <div v-if="loading" class="loading">加载中...</div>
            <div v-else-if="skills.length === 0 && !loading" class="empty-state" style="min-height:200px;">
                <h3>暂无技能</h3>
                <p v-if="!rootPath && !store.currentSessionId">设置技能根目录后加载可用技能</p>
                <p v-else-if="!store.currentSessionId">本地仓库为空，或目录下没有 SKILL.md</p>
                <p v-else>沙箱 /home/gem/skills/ 下没有技能，且本地仓库未配置</p>
            </div>
        </div>
    `,
    setup() {
        const store = Vue.inject('store');
        // 列表项形态：{ id, name, description, source?, enabled? }
        // - 融合视图（有会话）来自 listSessionSkills：携带 source + enabled
        // - 仅本地（无会话）来自 listSkills：不带 source，由 sourceOf() 兜底返回 'local'
        const skills = Vue.ref([]);
        const rootPath = Vue.ref(localStorage.getItem('skill_root') || '');
        // 无会话时仍要展示开关状态——这里只在融合视图模式下没用，保留以兼容旧逻辑
        const enabledIds = Vue.ref(new Set());
        const loading = Vue.ref(false);

        const enabledCount = Vue.computed(() => skills.value.filter(isEnabled).length);
        const sandboxOnlyCount = Vue.computed(() =>
            skills.value.filter(s => sourceOf(s) === 'sandbox').length);

        function sourceOf(skill) {
            return skill.source || 'local';
        }

        function sourceLabel(src) {
            switch (src) {
                case 'local': return '本地';
                case 'sandbox': return '沙箱新增';
                case 'both': return '已同步';
                default: return src;
            }
        }

        function isEnabled(skill) {
            // 融合视图项自带 enabled；无会话退回旧 enabledIds 集合
            if (typeof skill.enabled === 'boolean') return skill.enabled;
            return enabledIds.value.has(skill.id);
        }

        // 加载本地仓库（设置根目录 + 拉本地列表；若有会话再拉融合视图覆盖）
        const loadLocal = async () => {
            if (!rootPath.value) { alert('请输入技能根目录'); return; }
            localStorage.setItem('skill_root', rootPath.value);
            loading.value = true;
            try {
                await api.setSkillRoot(rootPath.value);
                if (store.currentSessionId) {
                    skills.value = await api.listSessionSkills(store.currentSessionId);
                } else {
                    const localList = await api.listSkills();
                    // 不带 source/enabled，sourceOf 默认 'local'，enabledIds 决定开关
                    skills.value = localList || [];
                    await loadEnabledIds();
                }
            } catch (e) {
                alert('加载技能失败: ' + e.message);
            } finally {
                loading.value = false;
            }
        };

        // 刷新当前会话的融合视图（沙箱扫描）
        const refreshSandbox = async () => {
            if (!store.currentSessionId) return;
            loading.value = true;
            try {
                skills.value = await api.listSessionSkills(store.currentSessionId);
            } catch (e) {
                alert('刷新沙箱失败: ' + e.message);
            } finally {
                loading.value = false;
            }
        };

        const loadEnabledIds = async () => {
            if (!store.currentSessionId) { enabledIds.value = new Set(); return; }
            try {
                const ids = await api.getEnabledSkills(store.currentSessionId);
                enabledIds.value = new Set(ids || []);
            } catch (e) {
                console.error('加载已启用技能失败:', e);
            }
        };

        const toggleSkill = async (skillId, enabled) => {
            if (!store.currentSessionId) { alert('请先选择或创建会话'); return; }
            try {
                if (enabled) {
                    await api.enableSkill(store.currentSessionId, skillId);
                } else {
                    await api.disableSkill(store.currentSessionId, skillId);
                }
                // 本地状态同步：直接改列表项的 enabled，避免整列表重渲染
                const item = skills.value.find(s => s.id === skillId);
                if (item && typeof item.enabled === 'boolean') {
                    item.enabled = enabled;
                } else {
                    if (enabled) enabledIds.value.add(skillId);
                    else enabledIds.value.delete(skillId);
                    enabledIds.value = new Set(enabledIds.value);
                }
            } catch (e) {
                alert('操作失败: ' + e.message);
            }
        };

        // 会话变化时重新加载融合视图
        const onSessionChange = async () => {
            if (store.currentSessionId) {
                try {
                    loading.value = true;
                    skills.value = await api.listSessionSkills(store.currentSessionId);
                } catch (e) {
                    console.error('加载融合视图失败:', e);
                } finally {
                    loading.value = false;
                }
            } else {
                // 退出会话上下文：保留显示的列表但 enabled 不再可信
                await loadEnabledIds();
            }
        };

        Vue.onMounted(async () => {
            if (store.currentSessionId) {
                await onSessionChange();
            } else if (rootPath.value) {
                await loadLocal();
            }
        });
        Vue.watch(() => store.currentSessionId, onSessionChange);

        return {
            store, skills, rootPath, loading,
            enabledCount, sandboxOnlyCount,
            sourceOf, sourceLabel, isEnabled,
            loadLocal, refreshSandbox, toggleSkill,
        };
    }
};

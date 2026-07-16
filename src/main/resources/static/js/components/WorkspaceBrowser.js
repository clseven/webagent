// 工作空间文件浏览器 - IDEA 风格懒加载树
const WorkspaceBrowser = {
    props: {
        embedded: { type: Boolean, default: false }
    },
    template: `
        <div :class="['workspace-browser', embedded ? 'workspace-browser-embedded' : '']">
            <!-- 浮动按钮 -->
            <button v-if="!embedded" class="workspace-toggle-btn" @click="togglePanel" :class="{ active: isOpen }" :title="isOpen ? '关闭工作空间' : '打开工作空间'">
                <svg class="workspace-folder-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
                </svg>
            </button>

            <!-- 抽屉面板 -->
            <transition name="slide">
                <div v-if="isOpen || embedded" :class="embedded ? 'workspace-dock-content' : 'workspace-drawer'">
                    <div class="drawer-header">
                        <h3>
                            <svg class="workspace-folder-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-right:6px;vertical-align:-2px;">
                                <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
                            </svg>
                            工作空间
                        </h3>
                        <div class="drawer-actions">
                            <button @click="refreshRoot" class="action-btn" title="刷新">
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" :class="{ spin: rootLoading }">
                                    <polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
                                </svg>
                            </button>
                            <button v-if="!embedded" @click="togglePanel" class="action-btn" title="关闭">
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                            </button>
                        </div>
                    </div>

                    <!-- 树形文件列表 -->
                    <div class="file-tree" v-if="!rootLoading || nodes.length > 0">
                        <div v-if="rootError" class="error-msg">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:-3px;margin-right:6px;"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
                            {{ rootError }}
                        </div>
                        <div v-else-if="nodes.length === 0 && !rootLoading" class="empty-msg">
                            工作空间为空
                        </div>
                        <div v-else>
                            <workspace-tree-node
                                v-for="node in rootNodes"
                                :key="node.path"
                                :node="node"
                                :depth="0"
                                @toggle="toggleNode"
                                @file-click="onFileClick"
                            ></workspace-tree-node>
                        </div>
                    </div>
                    <div v-else class="loading-msg">
                        <span class="thinking-spinner small" style="display:inline-block;margin-right:8px;vertical-align:middle;"></span>
                        加载中...
                    </div>

                    <!-- 统计信息 -->
                    <div class="drawer-footer" v-if="!rootLoading && !rootError && nodes.length > 0">
                        <span><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:-2px;margin-right:3px;"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg> {{ stats.dirs }} 个目录</span>
                        <span><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:-2px;margin-right:3px;"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg> {{ stats.files }} 个文件</span>
                    </div>
                </div>
            </transition>
        </div>
    `,
    components: {
        'workspace-tree-node': {
            name: 'WorkspaceTreeNode',
            props: {
                node: { type: Object, required: true },
                depth: { type: Number, default: 0 }
            },
            template: `
                <div class="tree-node">
                    <div
                        class="tree-row"
                        :class="{
                            'is-dir': node.isDir,
                            'is-file': !node.isDir,
                            'selected': node.selected
                        }"
                        :style="{ paddingLeft: (depth * 18 + 8) + 'px' }"
                        @click="handleClick"
                    >
                        <span
                            v-if="node.isDir"
                            class="tree-arrow"
                            :class="{ expanded: node.expanded, loading: node.loading }"
                            @click.stop="toggleExpand"
                        >
                            <svg v-if="node.loading" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="spin"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>
                            <svg v-else width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" :style="{ transform: node.expanded ? 'rotate(0deg)' : 'rotate(-90deg)', transition: 'transform 0.15s' }"><polyline points="6 9 12 15 18 9"/></svg>
                        </span>
                        <span v-else class="tree-arrow-spacer"></span>
                        <span class="tree-icon" v-html="getIcon()"></span>
                        <span class="tree-name" :title="node.path">{{ node.name }}</span>
                        <span class="tree-size" v-if="!node.isDir && node.size">{{ formatSize(node.size) }}</span>
                    </div>
                    <div v-if="node.isDir && node.expanded" class="tree-children">
                        <workspace-tree-node
                            v-for="child in node.children"
                            :key="child.path"
                            :node="child"
                            :depth="depth + 1"
                            @toggle="$emit('toggle', $event)"
                            @file-click="$emit('file-click', $event)"
                        ></workspace-tree-node>
                    </div>
                </div>
            `,
            methods: {
                handleClick() {
                    if (this.node.isDir) {
                        this.toggleExpand();
                    } else {
                        this.$emit('file-click', this.node);
                    }
                },
                toggleExpand() {
                    if (!this.node.isDir) return;
                    this.$emit('toggle', this.node);
                },
                getIcon() {
                    if (this.node.isDir) {
                        return this.node.expanded
                            ? '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:-3px;"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/><path d="M2 9h20" stroke-opacity="0.4"/></svg>'
                            : '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:-3px;"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>';
                    }
                    return this.getFileIconSvg(this.node.name);
                },
                getFileIconSvg(fileName) {
                    const ext = fileName.split('.').pop().toLowerCase();
                    const color = 'currentColor';
                    // 代码文件
                    if (['js','ts','jsx','tsx','py','rb','go','rs','java','php','swift','kt'].includes(ext)) {
                        return '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="'+color+'" stroke-width="2" style="vertical-align:-3px;color:#7C3AED;"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>';
                    }
                    // 样式文件
                    if (['css','scss','less','sass'].includes(ext)) {
                        return '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="'+color+'" stroke-width="2" style="vertical-align:-3px;color:#06B6D4;"><path d="M12 2l9 4.5v7L12 22l-9-4.5v-7L12 2z"/></svg>';
                    }
                    // HTML
                    if (['html','htm','xml','svg'].includes(ext)) {
                        return '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="'+color+'" stroke-width="2" style="vertical-align:-3px;color:#F59E0B;"><path d="m5 8 7 7-7 7"/><path d="m19 8-7 7 7 7"/></svg>';
                    }
                    // 文档
                    if (['md','txt','log'].includes(ext)) {
                        return '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="'+color+'" stroke-width="2" style="vertical-align:-3px;color:#64748B;"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>';
                    }
                    // JSON/CSV/数据
                    if (['json','csv','yml','yaml','toml'].includes(ext)) {
                        return '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="'+color+'" stroke-width="2" style="vertical-align:-3px;color:#10B981;"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/></svg>';
                    }
                    // 图片
                    if (['png','jpg','jpeg','gif','svg','webp','ico'].includes(ext)) {
                        return '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="'+color+'" stroke-width="2" style="vertical-align:-3px;color:#EC4899;"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>';
                    }
                    // PDF
                    if (ext === 'pdf') {
                        return '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="'+color+'" stroke-width="2" style="vertical-align:-3px;color:#EF4444;"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><path d="M9 13h6"/></svg>';
                    }
                    // 压缩包
                    if (['zip','tar','gz','rar','7z'].includes(ext)) {
                        return '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="'+color+'" stroke-width="2" style="vertical-align:-3px;color:#8B5CF6;"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/></svg>';
                    }
                    // Shell / 配置
                    if (['sh','bash','zsh','fish','env','cfg','conf','ini'].includes(ext)) {
                        return '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="'+color+'" stroke-width="2" style="vertical-align:-3px;color:#64748B;"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>';
                    }
                    // 默认文件图标
                    return '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="'+color+'" stroke-width="2" style="vertical-align:-3px;color:#94A3B8;"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>';
                },
                formatSize(bytes) {
                    if (!bytes) return '';
                    if (bytes < 1024) return bytes + ' B';
                    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
                    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
                    return (bytes / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
                }
            }
        }
    },
    setup(props) {
        const store = Vue.inject('store');

        const isOpen = Vue.ref(props.embedded);
        const rootPath = Vue.ref('/home/gem');
        const nodes = Vue.ref([]);
        const rootLoading = Vue.ref(false);
        const rootError = Vue.ref(null);
        const expandedSet = Vue.ref(new Set());

        const rootNodes = Vue.computed(() => nodes.value);

        const stats = Vue.computed(() => {
            let dirs = 0, files = 0;
            const walk = (list) => {
                for (const n of list) {
                    if (n.isDir) dirs++;
                    else files++;
                    if (n.children) walk(n.children);
                }
            };
            walk(nodes.value);
            return { dirs, files };
        });

        const shellQuote = (value) => {
            return "'" + String(value).replace(/'/g, "'\"'\"'") + "'";
        };

        const buildWorkspaceTreeCommand = (dirPath) => {
            return `find ${shellQuote(dirPath)} -mindepth 1 ! -path '*/.*' -printf '%y\\t%s\\t%p\\n' 2>/dev/null`;
        };

        const togglePanel = () => {
            isOpen.value = !isOpen.value;
            if (isOpen.value && nodes.value.length === 0) {
                loadWorkspaceTree();
            }
        };

        const refreshRoot = () => {
            expandedSet.value = new Set();
            if (store.currentSessionId) {
                api.refreshWorkspace(store.currentSessionId)
                    .catch(e => console.warn('刷新工作空间状态失败:', e));
            }
            loadWorkspaceTree();
        };

        const parseFindOutput = (output) => {
            const lines = output.split('\n').filter(line => line.trim());
            const result = [];
            for (const line of lines) {
                const match = line.match(/^([a-z])\s+(\d+)\s+(.+)$/);
                if (!match) continue;
                const type = match[1];
                const size = parseInt(match[2], 10) || 0;
                const fullPath = match[3];
                const name = fullPath.substring(fullPath.lastIndexOf('/') + 1);
                if (name === '.' || name === '..' || name.startsWith('.')) continue;
                result.push({
                    name,
                    path: fullPath,
                    isDir: type === 'd',
                    size: type === 'd' ? null : size
                });
            }
            return result;
        };

        const sortTree = (list) => {
            list.sort((a, b) => {
                if (a.isDir !== b.isDir) return a.isDir ? -1 : 1;
                return a.name.localeCompare(b.name);
            });
            for (const node of list) {
                if (node.children && node.children.length > 0) {
                    sortTree(node.children);
                }
            }
        };

        const buildTree = (items, basePath) => {
            const root = { children: [] };
            const byPath = new Map([[basePath, root]]);
            const normalized = items
                .filter(item => item.path.startsWith(basePath + '/'))
                .sort((a, b) => a.path.split('/').length - b.path.split('/').length);
            for (const item of normalized) {
                const parentPath = item.path.substring(0, item.path.lastIndexOf('/')) || basePath;
                const parent = byPath.get(parentPath);
                if (!parent) continue;
                const node = {
                    name: item.name,
                    path: item.path,
                    isDir: item.isDir,
                    size: item.size,
                    children: [],
                    loaded: item.isDir,
                    loading: false,
                    expanded: expandedSet.value.has(item.path),
                    selected: false
                };
                parent.children.push(node);
                if (node.isDir) {
                    byPath.set(node.path, node);
                }
            }
            sortTree(root.children);
            return root.children;
        };

        const loadWorkspaceTree = async () => {
            if (rootLoading.value) return;
            rootLoading.value = true;
            rootError.value = null;
            try {
                // 嵌入右侧工具栏时可能还没有会话，先显示明确状态，避免请求空 session。
                if (!store.currentSessionId) {
                    nodes.value = [];
                    rootError.value = '请先创建会话';
                    return;
                }
                const result = await api.executeCommand(
                    store.currentSessionId,
                    buildWorkspaceTreeCommand(rootPath.value)
                );
                if (result && result.success !== false) {
                    const items = parseFindOutput(result.body || '');
                    nodes.value = buildTree(items, rootPath.value);
                } else {
                    rootError.value = '加载失败: ' + (result.body || '未知错误');
                }
            } catch (e) {
                rootError.value = '加载失败: ' + e.message;
                console.error('加载目录失败:', e);
            } finally {
                rootLoading.value = false;
            }
        };

        const toggleNode = (node) => {
            if (!node.isDir) return;
            if (node.loaded) {
                node.expanded = !node.expanded;
                if (node.expanded) {
                    expandedSet.value.add(node.path);
                } else {
                    expandedSet.value.delete(node.path);
                }
            } else {
                node.expanded = true;
                expandedSet.value.add(node.path);
            }
        };

        const onFileClick = (node) => {
            if (node.isDir) return;
            const fileName = node.name;
            const fileType = (fileName.split('.').pop() || '').toLowerCase();
            FilePreviewer.preview({
                source: 'workspace',
                sessionId: store.currentSessionId,
                filePath: node.path,
                fileName: fileName,
                fileType: fileType,
                fileSize: node.size || 0
            });
        };

        Vue.watch(() => store.currentSessionId, () => {
            if (isOpen.value) {
                nodes.value = [];
                expandedSet.value = new Set();
                loadWorkspaceTree();
            }
        });

        Vue.onMounted(() => {
            if (props.embedded) {
                loadWorkspaceTree();
            }
        });

        return {
            store, isOpen, nodes, rootNodes, rootLoading, rootError, stats,
            togglePanel, refreshRoot, toggleNode, onFileClick
        };
    }
};

// 工作空间文件浏览器 - IDEA 风格懒加载树
const WorkspaceBrowser = {
    template: `
        <div class="workspace-browser">
            <!-- 浮动按钮 -->
            <button class="workspace-toggle-btn" @click="togglePanel" :class="{ active: isOpen }">
                <span class="icon">📁</span>
            </button>

            <!-- 抽屉面板 -->
            <transition name="slide">
                <div v-if="isOpen" class="workspace-drawer">
                    <div class="drawer-header">
                        <h3>工作空间</h3>
                        <div class="drawer-actions">
                            <button @click="refreshRoot" class="action-btn" title="刷新根目录">
                                <span :class="{ 'spin': rootLoading }">🔄</span>
                            </button>
                            <button @click="togglePanel" class="action-btn" title="关闭">✕</button>
                        </div>
                    </div>

                    <!-- 树形文件列表 -->
                    <div class="file-tree" v-if="!rootLoading || nodes.length > 0">
                        <div v-if="rootError" class="error-msg">{{ rootError }}</div>
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
                        加载中...
                    </div>

                    <!-- 统计信息 -->
                    <div class="drawer-footer" v-if="!rootLoading && !rootError && nodes.length > 0">
                        <span>{{ stats.dirs }} 个目录</span>
                        <span>{{ stats.files }} 个文件</span>
                    </div>
                </div>
            </transition>
        </div>
    `,
    components: {
        // 递归树节点组件
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
                        :style="{ paddingLeft: (depth * 16 + 8) + 'px' }"
                        @click="handleClick"
                    >
                        <!-- 展开/折叠箭头（仅目录） -->
                        <span
                            v-if="node.isDir"
                            class="tree-arrow"
                            :class="{ expanded: node.expanded, loading: node.loading }"
                            @click.stop="toggleExpand"
                        >{{ node.loading ? '⟳' : (node.expanded ? '▼' : '▶') }}</span>
                        <span v-else class="tree-arrow-spacer"></span>

                        <!-- 图标 -->
                        <span class="tree-icon">{{ getIcon() }}</span>

                        <!-- 名称 -->
                        <span class="tree-name" :title="node.path">{{ node.name }}</span>

                        <!-- 文件大小 -->
                        <span class="tree-size" v-if="!node.isDir && node.size">{{ formatSize(node.size) }}</span>
                    </div>

                    <!-- 递归渲染子节点 -->
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
                        return this.node.expanded ? '📂' : '📁';
                    }
                    return this.getFileIcon(this.node.name);
                },
                formatSize(bytes) {
                    if (!bytes) return '';
                    if (bytes < 1024) return bytes + ' B';
                    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
                    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
                    return (bytes / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
                },
                getFileIcon(fileName) {
                    const ext = fileName.split('.').pop().toLowerCase();
                    const iconMap = {
                        'py': '🐍', 'js': '📜', 'ts': '📜', 'html': '🌐', 'css': '🎨',
                        'json': '📋', 'md': '📝', 'txt': '📄', 'csv': '📊',
                        'xlsx': '📊', 'pdf': '📕',
                        'png': '🖼️', 'jpg': '🖼️', 'jpeg': '🖼️', 'gif': '🖼️',
                        'sh': '⚙️', 'yml': '⚙️', 'yaml': '⚙️', 'xml': '📄',
                        'java': '☕', 'go': '🔵', 'rs': '🦀', 'rb': '💎',
                        'php': '🐘', 'sql': '🗄️', 'zip': '📦', 'tar': '📦', 'gz': '📦',
                    };
                    return iconMap[ext] || '📄';
                }
            }
        }
    },
    setup() {
        const store = Vue.inject('store');

        const isOpen = Vue.ref(false);
        const rootPath = Vue.ref('/home/gem');
        const nodes = Vue.ref([]);              // 根目录的子节点列表
        const rootLoading = Vue.ref(false);
        const rootError = Vue.ref(null);
        const expandedSet = Vue.ref(new Set()); // 当前展开的目录路径集合

        // 根目录展示的节点
        const rootNodes = Vue.computed(() => nodes.value);

        // 统计
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

        // 切换面板
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

        // 刷新根目录
        const refreshRoot = () => {
            expandedSet.value = new Set();
            loadWorkspaceTree();
        };

        // 解析 find 输出
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

        // 目录优先，然后按名称排序
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

        // 切换节点的展开/折叠
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

        // 文件点击：调用 FilePreviewer
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

        // 监听会话变化
        Vue.watch(() => store.currentSessionId, () => {
            if (isOpen.value) {
                nodes.value = [];
                expandedSet.value = new Set();
                loadWorkspaceTree();
            }
        });

        return {
            store,
            isOpen,
            nodes,
            rootNodes,
            rootLoading,
            rootError,
            stats,
            togglePanel,
            refreshRoot,
            toggleNode,
            onFileClick
        };
    }
};

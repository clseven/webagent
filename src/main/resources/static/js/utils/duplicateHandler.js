// 重复文件处理通用模块
// 场景：知识库上传、Chat 沙箱文件上传等场景共用同一套"替换/保留两份/跳过"逻辑
//
// 用法：
//   const choice = await DuplicateHandler.handle({
//       file: file,
//       error: e,              // { code: 409, data: { fileName, existingDocId? } }
//       onReplace: async () => { /* 调用替换接口 */ },
//       onKeepBoth: async (newFile) => { /* 用新文件名上传 */ }
//   });
//   // choice: 'replace' | 'keep-both' | 'skip'
const DuplicateHandler = {

    /**
     * 处理重复文件：弹窗让用户选择 + 返回用户选择
     *
     * @param {Object} ctx
     * @param {File}   ctx.file          - 当前上传的文件
     * @param {Object} ctx.error         - 错误对象，code=409，data 包含 fileName
     * @param {Function} [ctx.onReplace] - 用户选择"替换"时调用（可返回 Promise）
     * @param {Function} [ctx.onKeepBoth]- 用户选择"保留两份"时调用，参数是自动重命名后的新 File
     * @param {string} [ctx.title]       - 自定义弹窗标题
     * @returns {Promise<'replace'|'keep-both'|'skip'|'replace-done'|'keep-both-done'|'error'>}
     */
    async handle(ctx) {
        const { file, error, onReplace, onKeepBoth, title } = ctx;
        const info = (error && error.data) || {};
        const fileName = info.fileName || (file && file.name) || '未知文件';

        return new Promise((resolve) => {
            this._showDialog({
                title: title || '文件已存在',
                fileName: fileName,
                extraInfo: info,
                onChoice: async (choice) => {
                    if (choice === 'replace') {
                        if (onReplace) {
                            try {
                                await onReplace();
                                resolve('replace-done');
                            } catch (e) {
                                console.error('替换失败:', e);
                                resolve('error');
                            }
                        } else {
                            resolve('replace');
                        }
                    } else if (choice === 'keep-both') {
                        if (onKeepBoth) {
                            try {
                                const newFile = this.renameFile(file, this._getExistingNames());
                                await onKeepBoth(newFile);
                                resolve('keep-both-done');
                            } catch (e) {
                                console.error('保留两份失败:', e);
                                resolve('error');
                            }
                        } else {
                            resolve('keep-both');
                        }
                    } else {
                        resolve('skip');
                    }
                }
            });
        });
    },

    /**
     * 自动重命名文件为 xxx (1).pdf 形式（如果 (1) 已被占用则尝试 (2)、(3)...）
     *
     * @param {File} originalFile     - 原文件
     * @param {Set<string>} [existingNames] - 已存在的文件名集合（不传则使用内置列表）
     * @returns {File} 重命名后的新 File 对象
     */
    renameFile(originalFile, existingNames) {
        const name = originalFile.name;
        const dotIdx = name.lastIndexOf('.');
        const base = dotIdx > 0 ? name.substring(0, dotIdx) : name;
        const ext = dotIdx > 0 ? name.substring(dotIdx) : '';

        const existing = existingNames || this._getExistingNames();
        let counter = 1;
        let newName = `${base} (${counter})${ext}`;
        while (existing.has(newName)) {
            counter++;
            // 防御：避免无限循环（最大尝试 9999 次）
            if (counter > 9999) break;
            newName = `${base} (${counter})${ext}`;
        }
        return new File([originalFile], newName, { type: originalFile.type });
    },

    /**
     * 检查错误是否为"重复文件"错误（409）
     */
    isDuplicateError(e) {
        return e && e.code === 409 && e.data && e.data.fileName;
    },

    // ==================== 内部方法 ====================

    /**
     * 获取当前已存在的文件名集合（内置列表）
     * 业务代码可以通过传入 existingNames 参数覆盖
     */
    _getExistingNames() {
        return this._existingNamesCache || new Set();
    },

    /**
     * 设置已存在的文件名集合（在加载文档列表后调用）
     */
    setExistingNames(names) {
        this._existingNamesCache = new Set(names || []);
    },

    /**
     * 显示通用弹窗（创建 DOM 元素，复用同一份 HTML）
     */
    _showDialog({ title, fileName, extraInfo, onChoice }) {
        // 移除已存在的弹窗
        const existing = document.getElementById('dup-dialog-root');
        if (existing) existing.remove();

        const root = document.createElement('div');
        root.id = 'dup-dialog-root';
        root.className = 'modal-overlay';
        root.innerHTML = `
            <div class="modal-content modal-sm dup-dialog">
                <div class="modal-header">
                    <h3>${this._escapeHtml(title)}</h3>
                </div>
                <div class="modal-body">
                    <p>文件 <strong class="dup-filename">${this._escapeHtml(fileName)}</strong> 已经存在。</p>
                    <p class="dup-hint">请选择处理方式：</p>
                </div>
                <div class="form-actions dup-actions">
                    <button class="btn-primary btn-sm" data-choice="replace">替换</button>
                    <button class="btn-secondary btn-sm" data-choice="keep-both">保留两份</button>
                    <button class="btn-secondary btn-sm" data-choice="skip">跳过</button>
                </div>
            </div>
        `;
        document.body.appendChild(root);

        // 点击遮罩关闭 = 跳过
        root.addEventListener('click', (e) => {
            if (e.target === root) {
                this._closeDialog();
                onChoice('skip');
            }
        });

        // 按钮点击
        root.querySelectorAll('button[data-choice]').forEach(btn => {
            btn.addEventListener('click', () => {
                const choice = btn.getAttribute('data-choice');
                this._closeDialog();
                onChoice(choice);
            });
        });
    },

    _closeDialog() {
        const root = document.getElementById('dup-dialog-root');
        if (root) root.remove();
    },

    _escapeHtml(s) {
        if (s == null) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }
};

// 暴露为全局
window.DuplicateHandler = DuplicateHandler;

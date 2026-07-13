const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const apiSource = fs.readFileSync(
    path.join(__dirname, '../../main/resources/static/js/api.js'),
    'utf8'
);
const chatSource = fs.readFileSync(
    path.join(__dirname, '../../main/resources/static/js/pages/Chat.js'),
    'utf8'
);

let requestedUrl = null;
const requests = [];
const sandbox = {
    console,
    FormData: class FormData {},
    localStorage: {
        getItem(key) {
            return key === 'auth_token' ? 'test-token' : null;
        },
        removeItem() {},
    },
    window: {
        location: {
            origin: 'http://localhost:8081',
            reload() {},
        },
    },
    fetch(url, options = {}) {
        requestedUrl = url;
        requests.push({ url, options });
        return Promise.resolve({
            ok: true,
            json: () => Promise.resolve({ code: 200, data: {} }),
            arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
        });
    },
};

vm.createContext(sandbox);
vm.runInContext(apiSource, sandbox);
const api = sandbox.createApiClient();

const chatProcessSandbox = { window: {} };
const chatProcessSource = chatSource.slice(0, chatSource.indexOf('// 对话页组件'))
    + '\nwindow.__chatProcessTest = { processTitle, processPreview, ChatStepGrouper };';
vm.createContext(chatProcessSandbox);
vm.runInContext(chatProcessSource, chatProcessSandbox);
const { processTitle, processPreview, ChatStepGrouper } = chatProcessSandbox.window.__chatProcessTest;

(async () => {
    await api.previewFileInSandbox(
        'session-1',
        "/home/gem/knowledge/'庄博然(4).pdf'"
    );

    assert.equal(
        requestedUrl,
        '/api/sessions/session-1/files/preview?path=%2Fhome%2Fgem%2Fknowledge%2F%E5%BA%84%E5%8D%9A%E7%84%B6(4).pdf'
    );

    await api.sendMessage('session-1', '你好', true, false, true);

    const chatRequest = requests.find(request => request.url === '/api/sessions/session-1/chat');
    assert.ok(chatRequest);
    assert.equal(chatRequest.options.method, 'POST');
    assert.deepEqual(JSON.parse(chatRequest.options.body), {
        message: '你好',
        searchEnabled: true,
        planningEnabled: false,
        knowledgeEnabled: true,
    });

    const runningSearch = {
        type: 'toolCall',
        tool: 'web_search',
        args: { query: '2025年7月10日 游戏发售 新游戏' },
        displayReason: '正在搜索网页',
        status: 'running',
    };
    const completedSearch = {
        ...runningSearch,
        type: 'toolResult',
        result: 'https://example.com/result',
        status: undefined,
    };

    assert.equal(processTitle(runningSearch), '已搜索网页（2025年7月10日 游戏发售 新游戏）');
    assert.equal(processTitle(completedSearch), '已搜索网页（2025年7月10日 游戏发售 新游戏）');
    assert.equal(processPreview(runningSearch), '');
    assert.equal(processPreview(completedSearch), '');
    assert.equal(processPreview({ type: 'toolCall', tool: 'unknown_tool', args: {} }), '');
    assert.equal(processPreview({ type: 'toolResult', tool: 'unknown_tool', args: {} }), '');
    assert.equal(ChatStepGrouper.overview({ kind: 'step', thinking: '分析任务', tools: [] }).title, '已运行');
    assert.equal(ChatStepGrouper.overview({ kind: 'step', thinking: '', tools: [runningSearch] }).title, '已运行');
    assert.equal(ChatStepGrouper.overview({ kind: 'step', thinking: '', tools: [runningSearch] }).badge, '');
    assert.equal(ChatStepGrouper.allDone({ kind: 'step', tools: [completedSearch] }), true);
    assert.match(chatSource, /<span>已运行<\/span>/);
})();

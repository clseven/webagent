const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const apiSource = fs.readFileSync(
    path.join(__dirname, '../../main/resources/static/js/api.js'),
    'utf8'
);

let requestedUrl = null;
const sandbox = {
    console,
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
    fetch(url) {
        requestedUrl = url;
        return Promise.resolve({
            ok: true,
            arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
        });
    },
};

vm.createContext(sandbox);
vm.runInContext(apiSource, sandbox);
const api = sandbox.createApiClient();

(async () => {
    await api.previewFileInSandbox(
        'session-1',
        "/home/gem/knowledge/'庄博然(4).pdf'"
    );

    assert.equal(
        requestedUrl,
        '/api/sessions/session-1/files/preview?path=%2Fhome%2Fgem%2Fknowledge%2F%E5%BA%84%E5%8D%9A%E7%84%B6(4).pdf'
    );
})();

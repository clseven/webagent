const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(
    path.join(__dirname, '../../main/resources/static/js/components/FilePreviewer.js'),
    'utf8'
);

const sandbox = { console, window: {}, URL, Blob };
vm.createContext(sandbox);
vm.runInContext(
    source + '\nthis.previewTypeFor = FilePreviewer._previewTypeFor.bind(FilePreviewer);',
    sandbox
);

assert.equal(sandbox.previewTypeFor('docx'), 'pdf');
assert.equal(sandbox.previewTypeFor('xlsx'), 'pdf');
assert.equal(sandbox.previewTypeFor('pptx'), 'pdf');
assert.equal(sandbox.previewTypeFor('png'), 'png');
assert.equal(sandbox.previewTypeFor('md'), 'md');

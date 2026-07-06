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

const chatSource = fs.readFileSync(
    path.join(__dirname, '../../main/resources/static/js/pages/Chat.js'),
    'utf8'
);
const chatSandbox = { console, window: {} };
vm.createContext(chatSandbox);
vm.runInContext(chatSource, chatSandbox);

const { shouldShowToolArtifact, gallerySlice } = chatSandbox.window.ChatArtifactGalleryUtils;

assert.equal(shouldShowToolArtifact({ sourceTool: 'download_file' }), true);
assert.equal(shouldShowToolArtifact({ sourceTool: 'browser_screenshot', deliverToUser: true }), true);
assert.equal(shouldShowToolArtifact({ sourceTool: 'browser_screenshot', deliverToUser: false }), false);
assert.equal(shouldShowToolArtifact({ sourceTool: 'browser_screenshot' }), false);

const galleryResult = gallerySlice(Array.from({ length: 7 }, (_, index) => ({
    key: `image-${index + 1}`,
    isImage: true,
})));

assert.deepEqual(galleryResult.visible.map(item => item.key), ['image-1', 'image-2', 'image-3', 'image-4']);
assert.equal(galleryResult.hiddenCount, 3);
assert.equal(galleryResult.totalCount, 7);

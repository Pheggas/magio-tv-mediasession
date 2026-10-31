const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const code = fs.readFileSync(path.join(__dirname, 'hermes_state.js'), 'utf8');

const noop = () => {};
const window = { addEventListener: noop };
const document = {
  documentElement: {},
  querySelector: () => null,
  querySelectorAll: () => [],
  getElementById: () => null
};
const localStorage = {
  getItem: () => null,
  setItem: noop
};
const context = {
  window,
  document,
  localStorage,
  location: { hash: '' },
  MutationObserver: class {
    observe() {}
  },
  setInterval: noop,
  setTimeout: noop,
  clearTimeout: noop,
  console
};
vm.runInNewContext(code, context);

const { clockMs, span, archive } = window.__hermesTest;
assert.strictEqual(clockMs('0:40'), 2400000, '0:40 is 40 minutes');
assert.strictEqual(clockMs('19:44'), 71040000, '19:44 is wall-clock minutes');
assert.strictEqual(span('23:50', '00:20'), 1800000, 'midnight wrap is 30 minutes');

const archiveState = archive({
  streamURL: { url: 'https://example.test?start=2026-10-30T23:00:00Z&end=2026-10-31T00:05:00Z' },
  position: 60000
});
assert.strictEqual(archiveState.duration, 3900000, 'archive duration is 65 minutes');
assert.strictEqual(archiveState.position, 60000, 'archive position is milliseconds');

console.log('OK');

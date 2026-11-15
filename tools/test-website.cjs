// Run from the repository root: node --test tools/test-website.cjs
// The static site has no bundler or test dependencies. Exercise its real stage
// lifecycle with a small DOM/viewer double, without starting Filament or a GPU.
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { test } = require('node:test');
const { runInNewContext } = require('node:vm');

const root = resolve(process.env.SITE_ROOT || 'website-static');
const read = (path) => readFileSync(resolve(root, path), 'utf8');
const source = read('assets/site.js');
const home = read('index.html');
const companies = read('companies.html');
const staticHtml = (html) => html.replace(/<!--[\s\S]*?-->|<script\b[^>]*>[\s\S]*?<\/script>/gi, '');

test('both pages use only the approved public repository count claim', () => {
  for (const [page, html] of [['index.html', home], ['companies.html', companies]]) {
    const text = staticHtml(html).replace(/<[^>]+>/g, '').replace(/\s+/g, ' ');
    const claims = text.match(/\b(?:over\s+|at\s+least\s+|more\s+than\s+)?\d[\d,]*\+?\s+(?:distinct\s+)?public\s+repositories(?:\s+declare\s+it\s+as\s+a\s+dependency)?/gi) || [];
    assert.ok(claims.length, `${page} must include the adoption claim`);
    for (const claim of claims) {
      assert.equal(claim, 'over 500 public repositories declare it as a dependency', page);
    }
  }
});

test('the adoption figure links directly to a dated, reproducible source', () => {
  assert.match(home, /href="\/companies\.html#adoption"[^>]*>over 500 public repositories/);
  const adoption = companies.match(/<section id="adoption"[\s\S]*?<\/section>/)[0];
  const link = adoption.match(/href="(https:\/\/github\.com\/search\?[^"]+)"/);
  assert.ok(link, 'the adoption section must link to GitHub code search');
  const url = new URL(link[1].replaceAll('&amp;', '&'));
  assert.equal(url.searchParams.get('type'), 'code');
  assert.equal(url.searchParams.get('q'), '"io.github.sceneview:" -org:sceneview -user:ThomasGorisse');
  assert.match(adoption, /<time datetime="\d{4}-\d{2}-\d{2}">[^<]+<\/time>/);
});

test('the companies anchor reserves the fixed header plus the home page gap', () => {
  assert.match(companies, /html\s*\{\s*scroll-padding-top:\s*calc\(var\(--nav-height\) \+ 16px\)/);
});

test('both pages include adopter names in their HTML without JavaScript', () => {
  const proof = staticHtml(home).match(/<section[^>]+id="proof"[\s\S]*?<\/section>/)[0];
  const adoption = staticHtml(companies).match(/<section id="adoption"[\s\S]*?<\/section>/)[0];
  for (const name of ['Medal', 'IKEA', 'Bitkey', 'Vocable']) {
    assert.ok(proof.includes(name), `home HTML must name ${name}`);
    assert.ok(adoption.includes(name), `companies HTML must name ${name}`);
  }
  assert.doesNotMatch(home + companies, /<script[^>]+adoption-orgs\.js/);
});

function fixture({ live = true, failure = new Error('HTTP 404') } = {}) {
  const classes = new Set(live ? ['is-live'] : ['is-loading']);
  const poster = { src: 'old.webp' };
  const style = new Map();
  const box = { style: { setProperty: (key, value) => style.set(key, value) } };
  const canvas = {
    replaced: false,
    cloneNode: () => ({ fresh: true }),
    replaceWith(fresh) { this.replaced = fresh.fresh; },
  };
  let disposed = 0, detached = 0;
  const sv = {
    loadModel: async () => { if (failure) throw failure; },
    dispose: () => { disposed++; },
    frameModel() {},
  };
  const stage = {
    dataset: {}, _sv: sv, _lock: {}, _scale: 1.2, _idle: 7,
    classList: {
      add: (...names) => names.forEach((name) => classes.add(name)),
      remove: (...names) => names.forEach((name) => classes.delete(name)),
      contains: (name) => classes.has(name),
    },
    querySelector: (selector) => ({ 'canvas': canvas, '.stage__poster': poster, '.stage__box': box })[selector],
  };
  const warnings = [];
  const window = {};
  // Expose only the private handler under test at the existing debug seam;
  // leave mount pending, as it would be before DOMContentLoaded.
  const seam = 'window.__sv = {';
  assert.ok(source.includes(seam));
  runInNewContext(source.replace(seam, 'window.__sv = { swapModel: swapModel,'), {
    window,
    document: { documentElement: {}, readyState: 'loading', addEventListener() {} },
    navigator: {}, location: { search: '?live=0' }, innerWidth: 1280,
    matchMedia: () => ({ matches: false }), URLSearchParams,
    clearTimeout() {}, setTimeout() {},
    console: { warn: (...args) => warnings.push(args) },
  });
  const api = window.__sv;
  api.live.push({ stage, sv, off: () => { detached++; } });
  return { api, stage, sv, classes, poster, canvas, style, warnings,
    disposed: () => disposed, detached: () => detached };
}

const chip = { dataset: { file: 'missing.glb', poster: 'selected.webp' } };
const settle = () => new Promise(setImmediate);

for (const live of [true, false]) {
  test(`a failed swap restores the selected poster (${live ? 'live' : 'initial load'})`, async () => {
    const f = fixture({ live });
    const other = { sv: {} };
    f.api.live.push(other);
    f.api.swapModel(f.stage, chip);
    await settle();
    assert.equal(f.stage._sv, null, 'a failed viewer must be parked');
    assert.equal(f.stage._lock, null);
    assert.equal(f.stage._idle, 0);
    assert.equal(f.disposed(), 1);
    assert.equal(f.detached(), 1);
    assert.equal(f.canvas.replaced, true, 'retry needs a fresh canvas');
    assert.equal(f.classes.has('is-live'), false, 'the poster must be visible');
    assert.equal(f.classes.has('is-loading'), false, 'the load button must return');
    assert.equal(f.poster.src, 'selected.webp');
    assert.equal(f.stage.dataset.src, '/models/platforms/missing.glb');
    assert.equal(f.style.get('--obj-scale'), 1.2);
    assert.equal(f.api.live.length, 1);
    assert.equal(f.api.live[0], other, 'another stage must stay live');
  });
}

test('a superseded swap does not park the viewer used by the next chip', async () => {
  const failure = new Error('replaced'); failure.name = 'AbortError';
  const f = fixture({ failure });
  f.api.swapModel(f.stage, chip);
  await settle();
  assert.equal(f.stage._sv, f.sv);
  assert.equal(f.disposed(), 0);
  assert.equal(f.classes.has('is-loading'), true);
  assert.equal(f.warnings.length, 0);
});

test('an older failure does not park a replacement viewer', async () => {
  const f = fixture();
  f.api.swapModel(f.stage, chip);
  const replacement = f.stage._sv = {};
  await settle();
  assert.equal(f.stage._sv, replacement);
  assert.equal(f.disposed(), 0);
  assert.equal(f.warnings.length, 0);
});

test('a successful swap keeps the live viewer', async () => {
  const f = fixture({ failure: null });
  f.api.swapModel(f.stage, chip);
  await settle();
  assert.equal(f.stage._sv, f.sv);
  assert.equal(f.disposed(), 0);
  assert.equal(f.classes.has('is-live'), true);
  assert.equal(f.classes.has('is-loading'), false);
});

// Exercise the real dispose/restore path without a GPU. Emscripten's registry
// keeps the same record as canvas.GLctxObject after Filament.Engine.destroy().
function contextFixture({ lost = false, extension = true, registered = true } = {}) {
  const viewerSource = read('js/sceneview.js');
  const seam = 'global.SceneView = {';
  assert.ok(viewerSource.includes(seam));
  const sandbox = { setTimeout, clearTimeout };
  runInNewContext(viewerSource.replace(seam,
    'global.contextTests = { Instance: SceneViewInstance, released: _releasedContexts, ' +
    'restore: _restoreContext }; ' + seam), sandbox);
  const api = sandbox.contextTests;
  const calls = [];
  const canvas = new EventTarget();
  const gl = { isContextLost: () => lost, getExtension: () => extension ? ext : null };
  gl.canvas = canvas;
  const ext = {
    loseContext() {
      calls.push('lose');
      lost = true;
      setImmediate(() => {
        const event = new Event('webglcontextlost', { cancelable: true });
        canvas.dispatchEvent(event);
        assert.equal(event.defaultPrevented, true, 'same-canvas reuse requires cancellation');
      });
    },
    restoreContext() {
      calls.push('restore');
      setImmediate(() => {
        lost = false;
        canvas.dispatchEvent(new Event('webglcontextrestored'));
      });
    },
  };
  canvas.getContext = () => gl;
  const record = { GLctx: gl };
  if (registered) canvas.GLctxObject = record;
  // The table survives for the page's lifetime, even when the canvas is detached.
  const registry = [record, { GLctx: { otherViewer: true } }];
  for (const hook of ['_setupControls', '_setupResizeObserver', '_setupVisibilityGating',
    '_startRenderLoop']) api.Instance.prototype[hook] = () => {};
  function viewer() {
    const instance = new api.Instance(canvas);
    instance._releaseNative = () => {
      assert.equal(record.GLctx, gl, 'native teardown must precede registry cleanup');
      calls.push('destroy');
    };
    return instance;
  }
  return { api, canvas, gl, record, registry, calls, viewer };
}

test('dispose severs the Emscripten registry reference after native teardown (#4443)', () => {
  const f = contextFixture();
  const instance = f.viewer();
  instance.dispose();
  assert.equal(f.registry[0].GLctx, null, 'the global registry must not retain the canvas/context');
  assert.equal(f.canvas.GLctxObject, undefined);
  assert.equal(f.registry[1].GLctx.otherViewer, true, 'another viewer must stay registered');
  assert.equal(f.gl.isContextLost(), true, 'Chromium still needs explicit context loss');
  instance.dispose();
  assert.deepEqual(f.calls, ['destroy', 'lose'], 'dispose must remain idempotent');
});

test('dispose leaves an unrecognised Emscripten record alone', () => {
  const f = contextFixture();
  const other = { GLctx: {} };
  f.canvas.GLctxObject = other;
  f.viewer().dispose();
  assert.equal(f.canvas.GLctxObject, other);
  assert.equal(f.record.GLctx, f.gl);
  assert.deepEqual(f.calls, ['destroy', 'lose']);
});

for (const options of [{ lost: true }, { extension: false }]) {
  test(`dispose drops the registry reference even with ${JSON.stringify(options)} (#4443)`, () => {
    const f = contextFixture(options);
    f.viewer().dispose();
    assert.equal(f.record.GLctx, null);
    assert.equal(f.canvas.GLctxObject, undefined);
    assert.deepEqual(f.calls, ['destroy']);
    assert.equal(f.api.released.has(f.canvas), false, 'no restore may wait for a loss event');
  });
}

for (const registered of [true, false]) {
  test(`a disposed canvas can restore its context (Emscripten record: ${registered})`, async () => {
    const f = contextFixture({ registered });
    f.viewer().dispose();
    await f.api.restore(f.canvas, f.api.released.get(f.canvas));
    assert.equal(f.gl.isContextLost(), false);
    assert.equal(f.api.released.has(f.canvas), false);
    assert.deepEqual(f.calls, ['destroy', 'lose', 'restore']);
  });
}

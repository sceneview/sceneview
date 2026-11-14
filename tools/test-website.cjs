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

test('the adoption figure links directly to a dated, reproducible source', () => {
  assert.match(home, /href="\/companies\.html#adoption"[^>]*>over 500 public repositories/);
  const adoption = companies.match(/<section id="adoption"[\s\S]*?<\/section>/)[0];
  const link = adoption.match(/href="(https:\/\/github\.com\/search\?[^"]+)"/);
  assert.ok(link, 'the adoption section must link to GitHub code search');
  const url = new URL(link[1].replaceAll('&amp;', '&'));
  assert.equal(url.searchParams.get('type'), 'code');
  assert.equal(url.searchParams.get('q'), '"io.github.sceneview:" -org:sceneview -user:ThomasGorisse');
  assert.match(adoption, /at least \d+ distinct public repositories/i);
  assert.match(adoption, /<time datetime="\d{4}-\d{2}-\d{2}">[^<]+<\/time>/);
});

test('the companies anchor reserves the fixed header plus the home page gap', () => {
  assert.match(companies, /html\s*\{\s*scroll-padding-top:\s*calc\(var\(--nav-height\) \+ 16px\)/);
});

test('both pages include adopter names in their HTML without JavaScript', () => {
  const staticHtml = (html) => html.replace(/<!--[\s\S]*?-->|<script\b[^>]*>[\s\S]*?<\/script>/gi, '');
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

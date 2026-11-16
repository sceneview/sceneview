// Run from the repository root: node --test tools/test-web-tone-mapping.cjs
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const { runInNewContext } = require('node:vm');
const { BEFORE_INTENSITY, pixelMetrics, measureRegion, beforeSource, studioIntensity, boardGlb } = require('./measure-web-studio.cjs');

// Exercise private implementation without adding a testing API to the shipped viewer.
const source = readFileSync('website-static/js/sceneview.js', 'utf8');
const context = {};
runInNewContext(source.replace('global.SceneView = {',
  'global.testInternals = { SceneViewInstance, _clearColorFor, _studioShadowTexels }; global.SceneView = {'), context);
const { SceneViewInstance, _clearColorFor: clearColorFor, _studioShadowTexels: shadowTexels } = context.testInternals;
const decode = s => s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
const filmic = x => x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14);

function viewerFixture(env = { url: '/custom.ktx', intensity: 17000 }) {
  const lights = new Set(['key', 'fill', 'rim']), loads = [], clears = [];
  const viewer = Object.assign(Object.create(SceneViewInstance.prototype), {
    _studio: null, _baseLights: [...lights], _envSource: env,
    _fov: 45, _nearPlane: 0.1, _minRadius: 1, _maxRadius: 30, _minHeight: -2, _maxHeight: 8,
    _defaultIblUrl: '/environments/neutral_ibl.ktx', _defaultIblIntensity: 40000,
    _scene: { remove: e => lights.delete(e), addEntity: e => lights.add(e) },
    _renderer: { setClearOptions: options => clears.push(options) },
    _loadDefaultEnvironment(url, intensity, studio) {
      this._envSource = { url, intensity, studio: !!studio }; loads.push(this._envSource);
    },
    setEnvironmentSH(bands, intensity) { this._envSource = { bands, intensity }; },
    _studioRebuild() {}, _studioDestroyParts() {}, _studioSetMirror() {},
    _studioRestorePost() {}, _applyProjection() {}, _resizeCanvas() {},
    requestRender() { return this; },
  });
  return { viewer, lights, loads, clears };
}

test('both studio themes reduce IBL by 30% and remove the plain-viewer lights', () => {
  const { viewer, lights, loads } = viewerFixture();
  const original = viewer._envSource;
  viewer.setStudio('light');
  assert.equal(lights.size, 0);
  assert.equal(viewer._envSource.url, '/environments/studio_showroom_light_ibl.ktx');
  assert.equal(viewer._envSource.intensity / BEFORE_INTENSITY, 0.7);
  viewer.setStudio('light');
  assert.equal(loads.length, 1, 'reapplying a theme does not reload it');
  viewer.setStudio('dark');
  assert.equal(viewer._envSource.url, '/environments/studio_showroom_dark_ibl.ktx');
  assert.equal(viewer._envSource.intensity / BEFORE_INTENSITY, 0.7);
  assert.equal(viewer._studioSaved.env, original, 'theme switch preserves the original environment');
});

test('leaving the studio restores custom IBL, lights, framing and background', () => {
  const { viewer, lights, clears } = viewerFixture();
  viewer._pageBackground = [0.3, 0.5, 0.8, 1];
  viewer.setStudio('dark');
  viewer._fov = 30;
  viewer.setStudio(false);
  assert.equal(viewer._studio, null);
  assert.deepEqual([...lights], ['key', 'fill', 'rim']);
  assert.equal(viewer._envSource.url, '/custom.ktx');
  assert.equal(viewer._envSource.intensity, 17000);
  assert.equal(viewer._fov, 45);
  assert.deepEqual(Array.from(clears[0].clearColor), Array.from(clearColorFor(viewer._pageBackground, false)));
  viewer.setStudio('light').setStudio(false);
  assert.equal(viewer._envSource.intensity, 17000, 're-entry must not compound the lighting reduction');
});

test('leaving restores SH lighting or the unchanged plain-viewer default', () => {
  const bands = Array(27).fill(0.1);
  const { viewer } = viewerFixture({ bands, intensity: 23000 });
  viewer.setStudio('light').setStudio(false);
  assert.equal(viewer._envSource.bands, bands);
  assert.equal(viewer._envSource.intensity, 23000);
  const fallback = viewerFixture(null).viewer;
  fallback.setStudio('dark').setStudio(false);
  assert.equal(fallback._envSource.url, '/environments/neutral_ibl.ktx');
  assert.equal(fallback._envSource.intensity, 40000);
});

test('an environment explicitly loaded in the studio survives exit', () => {
  const { viewer } = viewerFixture();
  viewer.setStudio('light');
  viewer._loadDefaultEnvironment('/new.ktx', 31000);
  viewer.setStudio(false);
  assert.equal(viewer._envSource.url, '/new.ktx');
  assert.equal(viewer._envSource.intensity, 31000);
});

test('opaque backgrounds still round-trip through the existing Filmic transform', () => {
  const levels = [0, 0.01, 0.1, 0.5, 0.9, 1];
  for (const r of levels) for (const g of levels) for (const b of levels) {
    const input = [r, g, b, 1], hdr = clearColorFor(input, false);
    for (let i = 0; i < 3; i++) {
      assert.ok(Number.isFinite(hdr[i]) && hdr[i] >= 0);
      assert.ok(Math.abs(filmic(hdr[i]) - decode(input[i])) < 1e-9, `${input}: channel ${i}`);
    }
  }
  assert.deepEqual(Array.from(clearColorFor([0.8, 0.4, 0.2, 0.5], true)), [0.8, 0.4, 0.2, 0.5]);
});

test('studio shadow texels preserve the coloured floor shade', () => {
  const colour = [0.8, 0.4, 0.2];
  const conf = { pool: colour, floor: colour, horizon: colour, shadow: 0.8 };
  const layout = { L: 1, cx: 0, cz: 0, hx: 0.5, hz: 0.5 };
  const shadow = { w: 1, h: 1, ts: 1, x0: 0, z0: 0, s: [0.5], u: [0] };
  const texels = shadowTexels(layout, conf, false, shadow);
  const base = clearColorFor([...colour, 1], false);
  for (let c = 0; c < 3; c++) {
    const displayed = filmic(base[c] * decode(texels[c] / 255));
    assert.ok(Math.abs(displayed - decode(colour[c] * 0.6)) < 0.002, `floor channel ${c}`);
  }
  assert.equal(texels[3], 255);
});

test('the render comparison restores only the old studio intensity', () => {
  const before = beforeSource(source);
  assert.equal(studioIntensity(before), BEFORE_INTENSITY);
  assert.equal(before.replace('var _STUDIO_IBL_INTENSITY = 40000;', 'var _STUDIO_IBL_INTENSITY = 28000;'), source);
});

test('colour metrics use per-pixel HSV S and D65 CIELAB L*, including black', () => {
  assert.deepEqual(pixelMetrics([0, 0, 0]), { saturation: 0, lightness: 0 });
  assert.ok(Math.abs(pixelMetrics([255, 255, 255]).lightness - 100) < 0.001);
  assert.equal(pixelMetrics([255, 0, 0]).saturation, 1);
  assert.ok(Math.abs(pixelMetrics([255, 0, 0]).lightness - 53.24) < 0.01);
  const result = measureRegion(Uint8Array.from([255, 0, 0, 255, 128, 128, 128, 255]), 2, 1,
    { x: 0.5, y: 0, radius: 1, circle: false });
  assert.equal(result.pixels, 2);
  assert.equal(result.saturation, 0.5, 'average saturations, not the saturation of average RGB');
  assert.ok(Math.abs(result.lightness - (pixelMetrics([255, 0, 0]).lightness + pixelMetrics([128, 128, 128]).lightness) / 2) < 1e-9);
  assert.throws(() => measureRegion(new Uint8Array(4), 1, 1, { x: 2, y: 2, radius: 0.1 }), /mask/);
});

test('the self-contained board uses six lit spheres sharing one valid GLB buffer', () => {
  const glb = boardGlb();
  assert.equal(glb.readUInt32LE(0), 0x46546c67);
  assert.equal(glb.readUInt32LE(8), glb.length);
  const jsonSize = glb.readUInt32LE(12);
  const gltf = JSON.parse(glb.subarray(20, 20 + jsonSize).toString());
  assert.equal(gltf.nodes.length, 6);
  assert.equal(gltf.materials.length, 6);
  assert.equal(gltf.extensionsUsed, undefined, 'spheres must not use unlit materials');
  assert.equal(glb.readUInt32LE(20 + jsonSize), gltf.buffers[0].byteLength);
  assert.equal(gltf.buffers[0].byteLength, glb.length - 28 - jsonSize);
  for (const view of gltf.bufferViews) {
    assert.equal(view.byteOffset % 4, 0);
    assert.ok(view.byteOffset + view.byteLength <= gltf.buffers[0].byteLength);
  }
  for (const m of gltf.materials) {
    assert.equal(m.pbrMetallicRoughness.metallicFactor, 0);
    assert.equal(m.pbrMetallicRoughness.roughnessFactor, 0.45);
  }
});

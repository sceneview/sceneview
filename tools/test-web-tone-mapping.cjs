// Run from the repository root: node --test tools/test-web-tone-mapping.cjs
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const { runInNewContext } = require('node:vm');

// Exercise the site's real inverse without adding a public testing API.
const source = readFileSync('website-static/js/sceneview.js', 'utf8');
const context = {};
runInNewContext(source.replace('global.SceneView = {',
  'global.clearColorForTest = _clearColorFor; global.shadowTexelsForTest = _studioShadowTexels; global.SceneView = {'), context);

const weights = [0.2126730, 0.7151520, 0.0721750];
const luminance = (rgb) => rgb.reduce((sum, c, i) => sum + c * weights[i], 0);
const decode = (s) => s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
const filmic = (x) => x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14);

// Filament's EVILS luminance scaling, including the highlight reserves branch.
function forward(rgb) {
  const peak = Math.max(...rgb);
  if (!peak) return [0, 0, 0];
  const ratio = rgb.map((c) => c / peak);
  const ratioLuminance = luminance(ratio);
  const out = filmic(luminance(rgb));
  const difference = Math.max(out - ratioLuminance, 0);
  return ratio.map((c) => c * (out - difference) / ratioLuminance +
    (1 - c) * difference / Math.max(1 - ratioLuminance, Number.MIN_VALUE));
}

test('opaque backgrounds round-trip through luminance-scaled Filmic, including saturated colours', () => {
  const levels = [0, 0.01, 0.1, 0.5, 0.9, 1];
  for (const r of levels) for (const g of levels) for (const b of levels) {
    const input = [r, g, b, 1];
    const hdr = context.clearColorForTest(input, false);
    assert.equal(hdr[3], 1);
    const output = forward(Array.from(hdr).slice(0, 3));
    for (let i = 0; i < 3; i++) {
      assert.ok(Number.isFinite(hdr[i]) && hdr[i] >= 0, `${input}: finite HDR`);
      assert.ok(Math.abs(output[i] - decode(input[i])) < 1e-9, `${input}: channel ${i}`);
    }
  }
});

test('transparent canvas clears still bypass tone mapping', () => {
  assert.deepEqual(Array.from(context.clearColorForTest([0.8, 0.4, 0.2, 0.5], true)), [0.8, 0.4, 0.2, 0.5]);
  assert.deepEqual(Array.from(context.clearColorForTest([1, 1, 1, 0], true)), [1, 1, 1, 0]);
});

test('studio shadow texels preserve the requested coloured floor shade', () => {
  const colour = [0.8, 0.4, 0.2];
  const conf = { pool: colour, floor: colour, horizon: colour, shadow: 0.8 };
  const layout = { L: 1, cx: 0, cz: 0, hx: 0.5, hz: 0.5 };
  const shadow = { w: 1, h: 1, ts: 1, x0: 0, z0: 0, s: [0.5], u: [0] };
  const texels = context.shadowTexelsForTest(layout, conf, false, shadow);
  const base = context.clearColorForTest([...colour, 1], false);
  const shaded = Array.from(base).slice(0, 3).map((v, c) => v * decode(texels[c] / 255));
  const displayed = forward(shaded);
  for (let c = 0; c < 3; c++) {
    assert.ok(Math.abs(displayed[c] - decode(colour[c] * 0.6)) < 0.002, `floor channel ${c}`);
  }
  assert.equal(texels[3], 255);
});

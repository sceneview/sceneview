#!/usr/bin/env node
// Reproduce #4623 from the repository root (no model downloads):
//   cd samples/web-demo && npm ci && npx playwright install chromium && cd ../..
//   node tools/measure-web-studio.cjs --output samples/web-demo/test-results/studio-colours
// Optionally select an installed Chromium with --executable /path/to/chrome.
//
// Both columns run the CURRENT website viewer, real Filament WASM and the same GLB,
// camera and materials. Before uses IBL 40000; after uses light 28000 / dark 40000.
// The plain viewer is also rendered as a control. This is a controlled lighting
// comparison, not a checkout of the rejected tone-mapping PR.
//
// Each sphere is a dielectric (metallic=0, roughness=0.45). Swatches show its authored
// linear base colour converted to sRGB, without lighting or tone mapping. Metrics
// average PER-PIXEL HSV S and CIELAB L* (sRGB, D65) over the central 70% radius of each
// sphere and the central swatch square. The mask excludes silhouette antialiasing,
// floor and background; specular highlights inside it are deliberately included.
// PNGs and measurements.json record inputs, masks, runtime/browser and full results.
// Desktop quality is fixed to prevent adaptive tier changes from confounding exposure.
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const { readFileSync, mkdirSync, writeFileSync } = require('node:fs');
const { createServer } = require('node:http');
const { createRequire } = require('node:module');
const { resolve, join } = require('node:path');

const ROOT = resolve(__dirname, '..');
const BEFORE_INTENSITY = 40000;
const WIDTH = 1200, HEIGHT = 320, DISTANCE = 5, RADIUS = 0.48, FOV = 30;
const COLORS = [
  { name: 'red', linear: [0.8, 0.05, 0.04] },
  { name: 'green', linear: [0.06, 0.65, 0.08] },
  { name: 'blue', linear: [0.04, 0.1, 0.8] },
  { name: 'yellow', linear: [0.9, 0.55, 0.04] },
  { name: 'magenta', linear: [0.65, 0.04, 0.6] },
  { name: 'cyan', linear: [0.04, 0.65, 0.7] },
];
const decode = s => s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
const encode = l => l <= 0.0031308 ? 12.92 * l : 1.055 * l ** (1 / 2.4) - 0.055;
const xAt = i => (i - 2.5) * 1.35;
const focal = HEIGHT / (2 * Math.tan(FOV * Math.PI / 360));

function pixelMetrics(rgb) {
  const s = rgb.map(c => c / 255);
  const max = Math.max(...s), min = Math.min(...s);
  const [r, g, b] = s.map(decode);
  const y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
  const epsilon = 216 / 24389, kappa = 24389 / 27;
  return { saturation: max ? (max - min) / max : 0,
    lightness: y > epsilon ? 116 * Math.cbrt(y) - 16 : kappa * y };
}

function measureRegion(pixels, width, height, mask) {
  let saturation = 0, lightness = 0, count = 0;
  for (let y = Math.max(0, Math.ceil(mask.y - mask.radius)); y < Math.min(height, mask.y + mask.radius); y++) {
    for (let x = Math.max(0, Math.ceil(mask.x - mask.radius)); x < Math.min(width, mask.x + mask.radius); x++) {
      if (mask.circle && (x - mask.x) ** 2 + (y - mask.y) ** 2 > mask.radius ** 2) continue;
      const offset = 4 * (y * width + x);
      const m = pixelMetrics(Array.from(pixels.slice(offset, offset + 3)));
      saturation += m.saturation; lightness += m.lightness; count++;
    }
  }
  assert.ok(count, 'measurement mask must contain pixels');
  return { saturation: saturation / count, lightness: lightness / count, pixels: count };
}

function studioIntensity(source, theme) {
  assert.ok(['light', 'dark'].includes(theme), 'expected a studio theme');
  const themes = source.match(/var _STUDIO_THEMES = ([\s\S]*?);/);
  assert.ok(themes, 'viewer must declare studio themes');
  const match = themes[1].match(new RegExp(`${theme}: [{][^}]*iblIntensity: (\\d+)`));
  assert.ok(match, `viewer must declare the ${theme} studio IBL intensity`);
  return Number(match[1]);
}

function beforeSource(source) {
  for (const theme of ['light', 'dark']) studioIntensity(source, theme);
  return source.replace(/iblIntensity: \d+/g, `iblIntensity: ${BEFORE_INTENSITY}`);
}

function boardGlb() {
  const positions = [], normals = [], indices = [];
  const rings = 32, segments = 64;
  for (let y = 0; y <= rings; y++) for (let x = 0; x <= segments; x++) {
    const theta = y * Math.PI / rings, phi = x * 2 * Math.PI / segments;
    const n = [Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi)];
    normals.push(...n); positions.push(...n.map(c => c * RADIUS));
  }
  for (let y = 0; y < rings; y++) for (let x = 0; x < segments; x++) {
    const a = y * (segments + 1) + x, b = a + segments + 1;
    indices.push(a, a + 1, b, a + 1, b + 1, b);
  }
  const buffers = [new Float32Array(positions), new Float32Array(normals), new Uint16Array(indices)]
    .map(a => Buffer.from(a.buffer));
  const bin = Buffer.concat(buffers);
  let offset = 0;
  const gltf = {
    asset: { version: '2.0', generator: 'SceneView studio colour board' }, scene: 0,
    scenes: [{ nodes: COLORS.map((_, i) => i) }],
    nodes: COLORS.map((c, i) => ({ name: c.name, mesh: i, translation: [xAt(i), 0, 0] })),
    meshes: COLORS.map((_, i) => ({ primitives: [{ attributes: { POSITION: 0, NORMAL: 1 }, indices: 2, material: i }] })),
    materials: COLORS.map(c => ({ name: c.name, pbrMetallicRoughness: {
      baseColorFactor: [...c.linear, 1], metallicFactor: 0, roughnessFactor: 0.45,
    } })),
    buffers: [{ byteLength: bin.length }],
    bufferViews: buffers.map(b => {
      const view = { buffer: 0, byteOffset: offset, byteLength: b.length };
      offset += b.length; return view;
    }),
    accessors: [
      { bufferView: 0, componentType: 5126, count: positions.length / 3, type: 'VEC3',
        min: [-RADIUS, -RADIUS, -RADIUS], max: [RADIUS, RADIUS, RADIUS] },
      { bufferView: 1, componentType: 5126, count: normals.length / 3, type: 'VEC3' },
      { bufferView: 2, componentType: 5123, count: indices.length, type: 'SCALAR' },
    ],
  };
  const json = Buffer.from(JSON.stringify(gltf));
  const padded = Buffer.alloc(Math.ceil(json.length / 4) * 4, 0x20);
  json.copy(padded);
  const header = Buffer.alloc(20), binHeader = Buffer.alloc(8);
  header.writeUInt32LE(0x46546c67, 0); header.writeUInt32LE(2, 4);
  header.writeUInt32LE(28 + padded.length + bin.length, 8);
  header.writeUInt32LE(padded.length, 12); header.writeUInt32LE(0x4e4f534a, 16);
  binHeader.writeUInt32LE(bin.length, 0); binHeader.writeUInt32LE(0x004e4942, 4);
  return Buffer.concat([header, padded, binHeader, bin]);
}

function boardHtml(column) {
  const swatches = COLORS.map((c, i) => {
    const rgb = c.linear.map(l => Math.round(255 * encode(l)));
    const left = WIDTH / 2 + xAt(i) * focal / DISTANCE - 40;
    return `<div style="position:absolute;left:${left}px;top:350px;width:80px;text-align:center">` +
      `<div style="height:32px;background:rgb(${rgb.join(',')})"></div>${c.name}</div>`;
  }).join('');
  return `<!doctype html><html><body style="margin:0;background:#ddd;color:#111;font:14px sans-serif">` +
    `<canvas id="board" style="width:${WIDTH}px;height:${HEIGHT}px"></canvas>${swatches}` +
    `<script src="/js/filament/filament.js"></script><script src="/${column}/sceneview.js"></script></body></html>`;
}

async function render(page, origin, column, theme, output) {
  const errors = [];
  const onError = e => errors.push(String(e));
  page.on('pageerror', onError);
  await page.goto(`${origin}/${column}.html`);
  const setup = await page.evaluate(async ({ theme, distance, fov }) => {
    const viewer = await window.SceneView.create('board', {
      studio: theme === 'plain' ? false : theme, autoRotate: false, renderMode: 'continuous',
    });
    // Keep the default desktop quality fixed across both columns even on a slow CPU.
    viewer._studioTrackFrame = () => {};
    await viewer.loadModel('/board.glb');
    await viewer.whenReady();
    viewer.setCameraOrbit({ angle: 0, height: 0, radius: distance, target: [0, 0, 0], fov });
    window.boardViewer = viewer;
    // Count actual drawn frames, not elapsed wall time (software WebGL may be slow).
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { off(); reject(new Error('Board did not draw 16 frames')); }, 60000);
      let frames = 0;
      const off = viewer.onFrame(() => { if (++frames === 16) { clearTimeout(timer); off(); resolve(); } });
      viewer.requestRender();
    });
    return { ktxLoaded: !!(viewer._ibl && viewer._ibl.ktx),
      source: viewer._envSource, orbit: viewer.getCameraOrbit(), qualityTier: viewer._studioTier };
  }, { theme, distance: DISTANCE, fov: FOV });
  assert.equal(setup.ktxLoaded, true, `${column}/${theme}: real KTX environment loaded, no synthetic fallback`);
  const png = await page.screenshot({ path: join(output, `${theme}-${column}.png`) });
  const decoded = await page.evaluate(async uri => {
    const image = new Image(); image.src = uri; await image.decode();
    const canvas = document.createElement('canvas'); canvas.width = image.width; canvas.height = image.height;
    const ctx = canvas.getContext('2d'); ctx.drawImage(image, 0, 0);
    return { width: image.width, height: image.height,
      data: Array.from(ctx.getImageData(0, 0, image.width, image.height).data) };
  }, 'data:image/png;base64,' + png.toString('base64'));
  const pixels = Uint8Array.from(decoded.data);
  const samples = COLORS.map((c, i) => {
    const x = WIDTH / 2 + xAt(i) * focal / DISTANCE;
    const sphereMask = { x, y: HEIGHT / 2, radius: 0.7 * RADIUS * focal / DISTANCE, circle: true };
    const swatchMask = { x, y: 366, radius: 10, circle: false };
    return { name: c.name, sphereMask, swatchMask,
      sphere: measureRegion(pixels, decoded.width, decoded.height, sphereMask),
      swatch: measureRegion(pixels, decoded.width, decoded.height, swatchMask) };
  });
  await page.evaluate(() => window.boardViewer.dispose());
  page.off('pageerror', onError);
  assert.deepEqual(errors, [], `${column}/${theme}: no browser errors`);
  return { column, theme, ...setup, samples };
}

async function main() {
  const options = { output: 'samples/web-demo/test-results/studio-colours' };
  for (let i = 2; i < process.argv.length; i += 2) {
    const name = process.argv[i].slice(2);
    assert.ok(['output', 'executable'].includes(name) && process.argv[i + 1], 'expected --output DIR or --executable CHROMIUM');
    options[name] = process.argv[i + 1];
  }
  const source = readFileSync(join(ROOT, 'website-static/js/sceneview.js'), 'utf8');
  const output = resolve(ROOT, options.output); mkdirSync(output, { recursive: true });
  const board = boardGlb();
  // Serve a fixed allowlist only; all assets come from the checkout, never a CDN.
  const files = new Map([
    ['/before.html', ['text/html', boardHtml('before')]],
    ['/after.html', ['text/html', boardHtml('after')]],
    ['/before/sceneview.js', ['text/javascript', beforeSource(source)]],
    ['/after/sceneview.js', ['text/javascript', source]],
    ['/board.glb', ['model/gltf-binary', board]],
    ...['filament.js', 'filament.wasm'].map(name => [`/js/filament/${name}`,
      [name.endsWith('.wasm') ? 'application/wasm' : 'text/javascript', readFileSync(join(ROOT, 'website-static/js/filament', name))]]),
    ...['neutral', 'studio_showroom_light', 'studio_showroom_dark'].map(name => [`/environments/${name}_ibl.ktx`,
      ['application/octet-stream', readFileSync(join(ROOT, 'website-static/environments', `${name}_ibl.ktx`))]]),
  ]);
  const server = createServer((req, res) => {
    const file = files.get(req.url);
    if (!file) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': file[0] }); res.end(file[1]);
  });
  let browser;
  try {
    const requireDemo = createRequire(join(ROOT, 'samples/web-demo/package.json'));
    const { chromium } = requireDemo('playwright');
    browser = await chromium.launch({ headless: true, executablePath: options.executable,
      args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--force-color-profile=srgb'] });
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
    const origin = `http://127.0.0.1:${server.address().port}`;
    const page = await browser.newPage({ viewport: { width: WIDTH, height: 410 }, deviceScaleFactor: 1,
      reducedMotion: 'reduce' });
    page.setDefaultTimeout(60000);
    const results = [];
    for (const theme of ['light', 'dark', 'plain']) for (const column of ['before', 'after']) {
      const result = await render(page, origin, column, theme, output);
      results.push(result);
      for (const sample of result.samples) for (const kind of ['sphere', 'swatch']) {
        const m = sample[kind];
        console.log(`${theme.padEnd(5)} ${column.padEnd(6)} ${sample.name.padEnd(7)} ${kind.padEnd(6)} S=${m.saturation.toFixed(4)} L*=${m.lightness.toFixed(2)} n=${m.pixels}`);
      }
    }
    const hashes = {};
    for (const [path, [, bytes]] of files) hashes[path] = createHash('sha256').update(bytes).digest('hex');
    writeFileSync(join(output, 'measurements.json'), JSON.stringify({
      beforeIntensity: BEFORE_INTENSITY, afterIntensity: Object.fromEntries(['light', 'dark'].map(theme => [theme, studioIntensity(source, theme)])), browser: browser.version(),
      colors: COLORS, camera: { distance: DISTANCE, fov: FOV }, radius: RADIUS,
      metallic: 0, roughness: 0.45, hashes, results,
    }, null, 2) + '\n');
    // Put the exact measured captures side by side in one shareable board.
    await page.setViewportSize({ width: WIDTH * 2, height: 3 * 440 });
    const rows = ['light', 'dark', 'plain'].map(theme => ['before', 'after'].map(column => {
      const png = readFileSync(join(output, `${theme}-${column}.png`)).toString('base64');
      return `<div><b>${theme} / ${column}</b><br><img src="data:image/png;base64,${png}"></div>`;
    }).join('')).join('');
    await page.setContent(`<body style="margin:0;font:18px sans-serif;background:#ddd;display:grid;grid-template-columns:1fr 1fr">${rows}</body>`);
    await page.evaluate(() => Promise.all(Array.from(document.images, img => img.decode())));
    await page.screenshot({ path: join(output, 'board.png'), fullPage: true });
    // Save evidence before checking it. A dark/blank frame must never count as a fix.
    for (const theme of ['light', 'dark', 'plain']) {
      const before = results.find(r => r.theme === theme && r.column === 'before');
      const after = results.find(r => r.theme === theme && r.column === 'after');
      if (theme !== 'plain') {
        assert.equal(before.source.intensity, BEFORE_INTENSITY, `${theme}: baseline intensity`);
        assert.equal(after.source.intensity, studioIntensity(source, theme), `${theme}: theme intensity`);
      }
      for (let i = 0; i < COLORS.length; i++) {
        const b = before.samples[i], a = after.samples[i];
        assert.ok(a.sphere.lightness > 25, `${theme}/${a.name}: sphere must remain visible`);
        assert.ok(Math.abs(a.swatch.saturation - b.swatch.saturation) < 0.005 &&
          Math.abs(a.swatch.lightness - b.swatch.lightness) < 0.5, `${theme}/${a.name}: swatch control changed`);
        if (theme !== 'light') {
          assert.ok(Math.abs(a.sphere.saturation - b.sphere.saturation) < 0.01 &&
            Math.abs(a.sphere.lightness - b.sphere.lightness) < 1, `${theme}/${a.name}: unchanged lighting must stay stable`);
        } else {
          assert.ok(a.sphere.saturation > b.sphere.saturation + 0.015, `${theme}/${a.name}: saturation must improve`);
          assert.ok(a.sphere.lightness < b.sphere.lightness && a.sphere.lightness > b.sphere.lightness - 25,
            `${theme}/${a.name}: avoid clipping or excessive darkening`);
        }
      }
    }
    console.log('PASS: light studio saturation improves; dark studio, swatches and plain viewer stay stable.');
  } finally {
    if (browser) await browser.close();
    await new Promise(resolve => server.close(resolve));
  }
}

module.exports = { COLORS, BEFORE_INTENSITY, pixelMetrics, measureRegion, studioIntensity, beforeSource, boardGlb };
if (require.main === module) main().catch(e => { console.error(e); process.exitCode = 1; });

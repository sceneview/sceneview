import { test, expect } from '@playwright/test';

// A self-contained, unlit colour swatch: no environment, texture or network asset
// can change its RGB ratios. glTF factors are linear, before the view's tone map.
function swatchGlb(): Buffer {
  const positions = new Float32Array([-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, 1, 1, 0, -1, 1, 0]);
  const bin = Buffer.from(positions.buffer);
  const json = {
    asset: { version: '2.0' }, extensionsUsed: ['KHR_materials_unlit'], scene: 0,
    scenes: [{ nodes: [0] }], nodes: [{ mesh: 0 }],
    meshes: [{ primitives: [{ attributes: { POSITION: 0 }, material: 0 }] }],
    materials: [{ extensions: { KHR_materials_unlit: {} }, doubleSided: true,
      pbrMetallicRoughness: { baseColorFactor: [0.6, 0.3, 0.06, 1] } }],
    buffers: [{ byteLength: bin.length }], bufferViews: [{ buffer: 0, byteLength: bin.length }],
    accessors: [{ bufferView: 0, componentType: 5126, count: 6, type: 'VEC3', min: [-1, -1, 0], max: [1, 1, 0] }],
  };
  const text = JSON.stringify(json);
  const jsonBytes = Buffer.from(text.padEnd(Math.ceil(text.length / 4) * 4, ' '));
  const header = Buffer.alloc(20);
  header.writeUInt32LE(0x46546c67, 0);
  header.writeUInt32LE(2, 4);
  header.writeUInt32LE(28 + jsonBytes.length + bin.length, 8);
  header.writeUInt32LE(jsonBytes.length, 12);
  header.writeUInt32LE(0x4e4f534a, 16);
  const binHeader = Buffer.alloc(8);
  binHeader.writeUInt32LE(bin.length, 0);
  binHeader.writeUInt32LE(0x004e4942, 4);
  return Buffer.concat([header, jsonBytes, binHeader, bin]);
}

for (const runtime of ['website', 'kotlin'] as const) {
  test(`${runtime} preserves the chromaticity of a rendered material (#4623)`, async ({ page }) => {
    const filament = runtime === 'website' ? '/js/filament/filament.js' : '/kotlin-bundle/filament.js';
    const viewer = runtime === 'website' ? '/js/sceneview.js' : '/kotlin-bundle/sceneview-web.js';
    await page.route('**/__tone-map.html', (route) => route.fulfill({
      contentType: 'text/html',
      body: `<body style="margin:0"><canvas id="scene-canvas" style="width:320px;height:320px"></canvas>` +
        `<script src="${filament}"></script><script src="${viewer}"></script></body>`,
    }));
    await page.route('**/__swatch.glb', (route) => route.fulfill({ body: swatchGlb(), contentType: 'model/gltf-binary' }));
    await page.goto('/__tone-map.html');
    await page.evaluate(async (kind) => {
      const w = window as any;
      const sv = kind === 'website'
        ? await w.SceneView.create('scene-canvas', { studio: false, autoRotate: false })
        : await w.sceneview.createViewer('scene-canvas');
      sv.setAutoRotate(false);
      await sv.loadModel('/__swatch.glb');
    }, runtime);
    // Wait for the on-demand render and temporal AA to settle before reading the canvas.
    await page.waitForTimeout(1000);
    const canvas = page.locator('#scene-canvas');
    await canvas.screenshot({ path: `test-results/tone-mapping-${runtime}.png` });
    const box = (await canvas.boundingBox())!;
    const png = await page.screenshot({ clip: { x: box.x + 156, y: box.y + 156, width: 9, height: 9 } });
    const rgb = await page.evaluate(async (uri) => {
      const image = new Image();
      image.src = uri;
      await image.decode();
      const c = document.createElement('canvas');
      c.width = c.height = 9;
      const ctx = c.getContext('2d')!;
      ctx.drawImage(image, 0, 0);
      const pixels = ctx.getImageData(0, 0, 9, 9).data;
      return [0, 1, 2].map((channel) => {
        let sum = 0;
        for (let i = channel; i < pixels.length; i += 4) sum += pixels[i];
        return sum / 81;
      });
    }, 'data:image/png;base64,' + png.toString('base64'));
    const linear = rgb.map((v) => v / 255).map((s) => s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4);
    console.log(`[tone-mapping] ${runtime}: sRGB=${rgb.map((v) => v.toFixed(1))}, G/R=${(linear[1] / linear[0]).toFixed(3)}`);
    expect(rgb[0], 'the swatch rendered').toBeGreaterThan(50);
    expect(rgb[0], 'the swatch is below clipping').toBeLessThan(250);
    expect(linear[1] / linear[0], 'linear G/R retains the authored 0.3/0.6 ratio').toBeCloseTo(0.5, 1);
    expect(linear[2] / linear[0], 'linear B/R retains the authored 0.06/0.6 ratio').toBeCloseTo(0.1, 1);
  });
}

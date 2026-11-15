import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { test, expect, Page } from '@playwright/test';
import { WIDGET_3D_VIEWER_HTML } from '../../../mcp/src/widgets';

const root = join(__dirname, '../../..');
const vertices = [[0, 0, 0], [100, 0, 0], [0, 100, 0], [0, 0, 100]];
const faces = [[0, 2, 1], [0, 1, 3], [0, 3, 2], [1, 2, 3]];
const obj = vertices.map(v => `v ${v.join(' ')}`).join('\n') + '\n' +
  faces.map(f => `f ${f.map(i => i + 1).join(' ')}`).join('\n') + '\n';
const stl = 'solid part\n' + faces.map(f => 'facet normal 0 0 0\nouter loop\n' +
  f.map(i => `vertex ${vertices[i].join(' ')}`).join('\n') + '\nendloop\nendfacet').join('\n') + '\nendsolid part\n';
const ply = 'ply\nformat ascii 1.0\nelement vertex 4\nproperty float x\nproperty float y\nproperty float z\nelement face 4\nproperty list uchar int vertex_indices\nend_header\n' +
  vertices.map(v => v.join(' ')).join('\n') + '\n' + faces.map(f => `3 ${f.join(' ')}`).join('\n') + '\n';

// Exercise the real widget HTML with the locally built KMP bundle and the site's
// vendored Filament runtime. No renderer or converter is mocked.
test.beforeEach(async ({ page }) => {
  await page.route('https://sceneview.github.io/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const file = path === '/js/sceneview-web.js'
      ? join(root, 'sceneview-web/build/kotlin-webpack/js/productionExecutable/sceneview-web.js')
      : join(root, 'website-static', path);
    await route.fulfill({ path: file, headers: { 'Access-Control-Allow-Origin': '*' } });
  });
  await page.route('**/formats/widget*', route => route.fulfill({ contentType: 'text/html', body: WIDGET_3D_VIEWER_HTML }));
  await page.route('**/formats/filament.*', route => route.fulfill({
    path: join(root, 'build/js/node_modules/filament', new URL(route.request().url()).pathname.split('/').pop()!),
  }));
  await page.route('**/formats/bundle', route => route.fulfill({ contentType: 'text/html', body:
    '<canvas id="scene-canvas" width="640" height="480"></canvas>' +
    '<script src="/formats/filament.js"></script><script src="/js/sceneview-web.js"></script>',
  }));
  await page.route('**/formats/part.*', async route => {
    const ext = new URL(route.request().url()).pathname.split('.').pop();
    const body = ext === '3mf'
      ? await readFile(join(root, 'samples/web-demo/site/models/printed-icosahedron.3mf'))
      : Buffer.from(ext === 'obj' ? obj : ext === 'ply' ? ply : stl);
    await route.fulfill({ contentType: 'application/octet-stream', body });
  });
  await page.route('**/open/', async route => {
    await route.fulfill({ contentType: 'text/html', body: await readFile(join(root, 'website-static/open/index.html'), 'utf8') });
  });
  await page.route('http://localhost:8080/js/**', route => route.fulfill({ path: join(root, 'website-static', new URL(route.request().url()).pathname) }));
  await page.route('http://localhost:8080/environments/**', route => route.fulfill({ path: join(root, 'website-static', new URL(route.request().url()).pathname) }));
  await page.route('**/js/sceneview-web.js*', route => route.fulfill({ path: join(root, 'sceneview-web/build/kotlin-webpack/js/productionExecutable/sceneview-web.js') }));
});

async function assertRendered(page: Page, selector: string) {
  const png = await page.locator(selector).screenshot();
  const variance = await page.evaluate(async uri => {
    const image = new Image();
    image.src = uri;
    await image.decode();
    const canvas = document.createElement('canvas');
    canvas.width = image.width; canvas.height = image.height;
    const context = canvas.getContext('2d')!;
    context.drawImage(image, 0, 0);
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    let sum = 0, square = 0;
    for (let i = 0; i < pixels.length; i += 4) {
      const y = (pixels[i] + pixels[i + 1] + pixels[i + 2]) / 3;
      sum += y; square += y * y;
    }
    const count = pixels.length / 4;
    return square / count - (sum / count) ** 2;
  }, `data:image/png;base64,${png.toString('base64')}`);
  expect(variance, 'model must produce non-uniform canvas pixels').toBeGreaterThan(64);
}

for (const format of ['3mf', 'stl', 'obj', 'ply']) {
  test(`widget renders ${format}`, async ({ page }, info) => {
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    const modelUrl = `http://localhost:8080/formats/part.${format}`;
    await page.goto(`/formats/widget?modelUrl=${encodeURIComponent(modelUrl)}&fileName=part.${format}&unit=millimeter`);
    await expect(page.locator('#canvas')).toHaveCSS('opacity', '1', { timeout: 45000 });
    await expect(page.locator('#format')).toHaveText(format.toUpperCase());
    await assertRendered(page, '#canvas');
    const screenshot = info.outputPath(`widget-${format}.png`);
    await page.screenshot({ path: screenshot });
    await info.attach(`widget-${format}`, { path: screenshot, contentType: 'image/png' });
    expect(errors).toEqual([]);
  });
}

test('/open drops an STL, changes units, and picks an OBJ', async ({ page }, info) => {
  await page.goto('/open/');
  await page.evaluate(text => {
    const transfer = new DataTransfer();
    transfer.items.add(new File([text], 'part.stl'));
    window.dispatchEvent(new DragEvent('drop', { dataTransfer: transfer, bubbles: true }));
  }, stl);
  await expect(page.locator('#viewer-status')).toBeHidden({ timeout: 45000 });
  await expect(page.locator('#unit-control')).toBeVisible();
  await assertRendered(page, '#viewer-canvas');
  await page.selectOption('#model-unit', 'meter');
  await expect(page.locator('#viewer-status')).toBeHidden({ timeout: 30000 });
  await page.screenshot({ path: info.outputPath('open-stl.png') });
  await page.locator('#model-file').setInputFiles({ name: 'part.obj', mimeType: 'text/plain', buffer: Buffer.from(obj) });
  await expect(page.locator('#viewer-title')).toHaveText('part.obj');
  await expect(page.locator('#viewer-status')).toBeHidden({ timeout: 30000 });
  await assertRendered(page, '#viewer-canvas');
});

test('/open shares the remote model URL and unit in its phone QR', async ({ page }) => {
  const modelUrl = 'http://localhost:8080/formats/part.stl';
  await page.goto(`/open/?url=${encodeURIComponent(modelUrl)}&unit=meter`);
  await expect(page.locator('#viewer-status')).toBeHidden({ timeout: 45000 });
  await expect(page.locator('#model-phone')).toBeVisible();
  await expect(page.locator('#model-qr svg')).toBeVisible();
  await expect(page.locator('#model-unit')).toHaveValue('meter');
  const link = new URL(await page.locator('#model-phone-link').getAttribute('href') as string);
  expect(link.searchParams.get('url')).toBe(modelUrl);
  expect(link.searchParams.get('unit')).toBe('meter');
});

// The npm/CDN viewer uses its own version-matched Filament, unlike the widget,
// which uses the bundle only for conversion and renders with the website runtime.
test('compiled loadModel accepts URL and ArrayBuffer and rejects unsupported formats', async ({ page }) => {
  await page.goto('/formats/bundle');
  const result = await page.evaluate(async () => {
    const api = (window as any).sceneview;
    const viewer = await api.createViewer('scene-canvas');
    await viewer.loadModel('/formats/part.stl', 'part.stl', 'millimeter');
    const bytes = await (await fetch('/formats/part.obj')).arrayBuffer();
    await viewer.loadModel(bytes, 'part.obj', 'millimeter');
    viewer.setCameraOrbit(0.9, 1.2, 0.5);
    try {
      await viewer.loadModel(new ArrayBuffer(4), 'part.fbx');
      return { name: 'resolved unexpectedly' };
    } catch (error: any) {
      return { name: error.name, code: error.code };
    }
  });
  expect(result).toEqual({ name: 'UnsupportedModelFormatError', code: 'unsupported-format' });
  await assertRendered(page, '#scene-canvas');
});

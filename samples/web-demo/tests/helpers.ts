import { Page, expect } from '@playwright/test';

/**
 * Shared helpers for the SceneView Web Demo Playwright suite.
 *
 * Extracted from `render.spec.ts` so the catalog-coverage suite
 * (`catalog.spec.ts`) and the original visual-regression suite reuse the
 * same canvas-sampling and console-capture logic.
 */

/** Collected console / page diagnostics for a single test. */
export interface PageDiagnostics {
  /** `console.error(...)` messages emitted by the page. */
  consoleErrors: string[];
  /** Uncaught exceptions / unhandled promise rejections (`pageerror`). */
  pageErrors: string[];
}

/**
 * Attach console + pageerror listeners to a page and return a live
 * diagnostics object. Call this before `page.goto(...)`.
 *
 * Network/CDN noise (Sketchfab auth 401s, jsDelivr hiccups) is filtered out
 * — those are environmental, not demo bugs. We only fail on real script
 * errors and unhandled rejections.
 */
export function captureDiagnostics(page: Page): PageDiagnostics {
  const diag: PageDiagnostics = { consoleErrors: [], pageErrors: [] };

  page.on('console', (msg) => {
    if (msg.type() !== 'error') return;
    const text = msg.text();
    if (isIgnorableNoise(text)) return;
    diag.consoleErrors.push(text);
  });

  page.on('pageerror', (err) => {
    const text = err.message || String(err);
    if (isIgnorableNoise(text)) return;
    diag.pageErrors.push(text);
  });

  return diag;
}

/**
 * CDN / third-party failures are not demo regressions. Sketchfab download
 * endpoints return 401 without auth (the demo handles that path), and the
 * remote model gallery CDN can rate-limit or be offline. Filter those — both
 * the upstream network error AND its downstream glTF-parse symptom — so the
 * suite stays deterministic.
 *
 * The engine itself (filament.js / sceneview.js / filament.wasm) is now
 * self-hosted next to index.html (issue #1586), so a genuine engine-load
 * failure is no longer environmental and is NOT filtered here.
 */
function isIgnorableNoise(text: string): boolean {
  const t = text.toLowerCase();
  return (
    t.includes('sketchfab') ||
    t.includes('401') ||
    t.includes('403') ||
    t.includes('429') ||
    t.includes('net::err_') ||
    t.includes('failed to load resource') ||
    t.includes('the server responded with a status') ||
    // Downstream symptom of a model-CDN miss: a 403/404 HTML body fed to the
    // glTF loader. The remote model gallery is a best-effort feature (like
    // Sketchfab search) — a miss must not fail the suite.
    t.includes('unable to parse gltf') ||
    t.includes('failed to parse model') ||
    t.includes('load error:')
  );
}

/** Assert no real console errors / unhandled rejections were collected. */
export function expectNoPageErrors(diag: PageDiagnostics, context: string): void {
  expect(
    diag.pageErrors,
    `Unhandled errors during "${context}": ${diag.pageErrors.join(' | ')}`,
  ).toEqual([]);
  expect(
    diag.consoleErrors,
    `Console errors during "${context}": ${diag.consoleErrors.join(' | ')}`,
  ).toEqual([]);
}

/** Wait for the Filament loading overlay to clear (engine ready). */
export async function waitForEngineReady(page: Page): Promise<void> {
  const overlay = page.locator('#loading-overlay');
  await expect(overlay).toHaveClass(/hidden/, { timeout: 45_000 });
}

/**
 * Sample the rendered scene canvas and report whether it shows real content.
 *
 * IMPORTANT — why this screenshots instead of `gl.readPixels`:
 * Filament.js creates its WebGL context with `preserveDrawingBuffer: false`
 * (the default). Once a frame is presented to the compositor the default
 * framebuffer is undefined, so a later `gl.readPixels` on that context
 * returns all-zero pixels EVEN WHEN the canvas is visibly rendering. That
 * produced false "Canvas appears blank" failures across the catalog suite
 * while the demo was rendering perfectly (#1586). The browser compositor, on
 * the other hand, captures the buffer at the correct point — so a Playwright
 * element screenshot is the reliable signal.
 *
 * `headlessGpuOk` stays in the return shape for callers that soft-skip on
 * GPU-less runners; it is `false` only when the canvas itself is missing /
 * zero-sized (a genuine "cannot sample" state), `true` otherwise.
 */
export async function sampleCanvas(
  page: Page,
): Promise<{ hasContent: boolean; headlessGpuOk: boolean }> {
  const canvas = page.locator('#scene-canvas');
  if ((await canvas.count()) === 0) {
    return { hasContent: false, headlessGpuOk: false };
  }
  const box = await canvas.boundingBox();
  if (!box || box.width === 0 || box.height === 0) {
    return { hasContent: false, headlessGpuOk: false };
  }

  // Screenshot a block at the centre of the canvas — that is where the framed
  // model / pendulum / geometry sits — as a lossless PNG, then decode it back
  // to pixels inside the browser (an <img> + 2D canvas natively decodes PNG;
  // this never touches the WebGL context so the `preserveDrawingBuffer`
  // problem above does not apply).
  const side = 200;
  const png = await page.screenshot({
    type: 'png',
    clip: {
      x: box.x + box.width / 2 - side / 2,
      y: box.y + box.height / 2 - side / 2,
      width: side,
      height: side,
    },
  });
  const dataUri = 'data:image/png;base64,' + png.toString('base64');

  // A blank / uniform region has near-zero luminance variance; a rendered
  // scene (model shading, the pendulum links, geometry edges) shows a wide
  // spread of luminance values. Variance is robust to the demo's dark theme.
  return page.evaluate(async (uri: string) => {
    const img = new Image();
    await new Promise<void>((resolve, reject) => {
      img.onload = () => resolve();
      img.onerror = () => reject(new Error('decode failed'));
      img.src = uri;
    });
    const c = document.createElement('canvas');
    c.width = img.width;
    c.height = img.height;
    const ctx = c.getContext('2d');
    if (!ctx) return { hasContent: false, headlessGpuOk: false };
    ctx.drawImage(img, 0, 0);
    const { data } = ctx.getImageData(0, 0, c.width, c.height);
    let sum = 0;
    let sumSq = 0;
    let n = 0;
    for (let i = 0; i < data.length; i += 4) {
      // Rec. 601 luma.
      const y = 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2];
      sum += y;
      sumSq += y * y;
      n++;
    }
    const mean = sum / n;
    const variance = sumSq / n - mean * mean;
    // A flat dark block has variance < ~20; a rendered model is in the
    // hundreds-to-thousands. 64 is a wide safety margin either side.
    return { hasContent: variance > 64, headlessGpuOk: true };
  }, dataUri);
}

/**
 * Hard-assert the WebGL context backing `#scene-canvas` is still alive.
 *
 * Catches GPU-process crashes and lost contexts: the canvas DOM element is
 * still in the page but nothing will ever paint to it again. Cheaper than a
 * screenshot — call this first inside `assertRendered`.
 *
 * Why the `?? true` fallback: if the canvas has no WebGL context at all (e.g.
 * the engine never initialised because `--enable-unsafe-swiftshader` is
 * missing on a GPU-less runner — issue #1674), `getContext('webgl2')` returns
 * `null`. Treating that as "lost" is the right outcome; we want the test to
 * fail loudly instead of green-on-nothing.
 */
export async function assertCanvasContextAlive(
  page: Page,
  context: string,
): Promise<void> {
  const lost = await page.evaluate(() => {
    const c = document.querySelector('#scene-canvas') as HTMLCanvasElement | null;
    if (!c) return true;
    const gl =
      (c.getContext('webgl2') as WebGLRenderingContext | null) ??
      (c.getContext('webgl') as WebGLRenderingContext | null);
    if (!gl) return true;
    return gl.isContextLost();
  });
  expect(
    lost,
    `[${context}] WebGL context is lost or missing — Filament can no longer ` +
      `render to the canvas (GPU-process crash, or no context was ever created)`,
  ).toBe(false);
}

/**
 * Drive an orbit gesture on the canvas: press, drag across, release.
 * Mirrors what a user does to rotate the camera. Returns nothing — the
 * caller re-samples the canvas afterwards to confirm the scene survived.
 */
export async function dragCanvas(page: Page): Promise<void> {
  const canvas = page.locator('#scene-canvas');
  const box = await canvas.boundingBox();
  if (!box) return;
  const cx = box.x + box.width / 2;
  const cy = box.y + box.height / 2;

  // Mouse orbit drag.
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx + 120, cy + 40, { steps: 12 });
  await page.mouse.move(cx + 40, cy - 60, { steps: 12 });
  await page.mouse.up();

  // Touch-style tap (covers the touch listener path without needing a
  // touch-enabled context — a plain click exercises the pointer handlers).
  await page.mouse.click(cx, cy);

  // Scroll-wheel zoom.
  await page.mouse.move(cx, cy);
  await page.mouse.wheel(0, -240);
  await page.mouse.wheel(0, 240);
}

/**
 * Wait for the demo's inline model-loading chip (`#loading-chip`) to clear.
 *
 * `loadModel()` in the demo shows `#loading-chip.visible` while a model
 * downloads + uploads to Filament, then removes `.visible` on success/failure.
 * Catalog tests previously used a blind `waitForTimeout(2500)` after a model
 * chip click — which both wastes budget when the GPU is fast AND, more
 * importantly, under-waits on a GPU-less CI runner where the WASM model
 * upload is several times slower (the cause of the device-QA web-leg timeouts,
 * harness umbrella #1560). Waiting on the real completion signal makes the
 * step deterministic across runners.
 *
 * The chip may never appear at all if the load finished before the poll — so
 * we only wait for the *hidden* state, with a generous ceiling for slow
 * software-rasterised CI.
 */
export async function waitForModelChipIdle(
  page: Page,
  timeout = 30_000,
): Promise<void> {
  const chip = page.locator('#loading-chip');
  await expect(chip).not.toHaveClass(/visible/, { timeout });
}

/** Switch to a catalog tab and assert its panel becomes active. */
export async function switchTab(page: Page, tab: string): Promise<void> {
  await page.locator(`.tab-btn[data-tab="${tab}"]`).click();
  await expect(page.locator(`.tab-btn[data-tab="${tab}"]`)).toHaveClass(/active/);
  await expect(page.locator(`#panel-${tab}`)).toHaveClass(/active/);
}

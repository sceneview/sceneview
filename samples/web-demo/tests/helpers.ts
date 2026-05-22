import { Page, expect, test as base } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

/**
 * Shared helpers for the SceneView Web Demo Playwright suite.
 *
 * Extracted from `render.spec.ts` so the catalog-coverage suite
 * (`catalog.spec.ts`) and the original visual-regression suite reuse the
 * same canvas-sampling and console-capture logic.
 */

/**
 * Per-test screencast recording — parity with the Maestro Android / iOS
 * device-QA legs (issue #1748, item 3).
 *
 * Playwright 1.59 added `page.screencast.start({path, size})` /
 * `page.screencast.stop()` plus `showChapter()` annotations. The legacy
 * `video: 'on'` knob in `playwright.config.ts` writes a hidden-by-default
 * `.webm` next to the trace and is awkward to surface to a human reviewer.
 * The fixture below brackets EVERY test with a deterministic recording at
 *
 *     test-results/screencasts/<slugified-test-title>.webm
 *
 * and exposes a `screencast` handle so a test can drop a chapter annotation
 * at meaningful boundaries (tab switch, model load, failure).
 *
 * On a GPU-less CI runner the recording is software-rendered — same caveat
 * that already applies to the canvas itself. The recording is a human-review
 * artefact; the hard regression signal is still `sampleCanvas` below.
 *
 * Usage:
 *   import { test, expect } from './helpers';
 *
 *   test('...', async ({ page, screencast }) => {
 *     await screencast.chapter('Models tab — first card');
 *     // ... interactions ...
 *   });
 */
const SCREENCAST_DIR = 'test-results/screencasts';

function slugifyTitle(title: string): string {
  return title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 120) || 'untitled';
}

export interface ScreencastHandle {
  /** Absolute path the .webm is being written to. */
  path: string;
  /**
   * Drop a centred chapter overlay onto the recording. No-op if `showChapter`
   * is unavailable (Playwright < 1.59 has neither the method nor the fixture
   * — we'd fail to start the recording first).
   */
  chapter(title: string, description?: string): Promise<void>;
}

export const test = base.extend<{ screencast: ScreencastHandle }>({
  screencast: [async ({ page }, use, testInfo) => {
    const dir = path.resolve(testInfo.project.outputDir, '..', SCREENCAST_DIR);
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, `${slugifyTitle(testInfo.title)}.webm`);

    // `page.screencast` is a Playwright 1.59+ surface. If a downstream consumer
    // pins an older version the start call rejects — the fixture swallows that
    // so the suite still runs without per-run video on legacy Playwright.
    let started = false;
    try {
      await page.screencast.start({
        path: file,
        size: { width: 1280, height: 720 },
      });
      started = true;
    } catch (e) {
      console.warn(
        `[screencast] page.screencast.start unavailable — recording skipped (${(e as Error).message}). ` +
        `Upgrade @playwright/test to >= 1.59 (see samples/web-demo/package.json).`,
      );
    }

    const handle: ScreencastHandle = {
      path: file,
      async chapter(title: string, description?: string) {
        if (!started) return;
        try {
          await page.screencast.showChapter(title, {
            description,
            duration: 1200,
          });
        } catch {
          /* showChapter is best-effort */
        }
      },
    };

    await handle.chapter(`▶ ${testInfo.title}`);
    await use(handle);

    if (started) {
      // Annotate failure before stopping so the last second of the recording
      // surfaces the reason. The .webm is finalised by stop().
      if (testInfo.status !== testInfo.expectedStatus) {
        await handle.chapter('✖ failed', testInfo.error?.message?.split('\n')[0]);
      }
      try {
        await page.screencast.stop();
        // Attach to the HTML report so the per-test page links the .webm.
        if (fs.existsSync(file)) {
          await testInfo.attach('screencast', { path: file, contentType: 'video/webm' });
        }
      } catch {
        /* shutdown best-effort — the page may already be closed */
      }
    }
  }, { auto: true }],
});

export { expect };

/**
 * Inject the IWER (Immersive Web Emulation Runtime) WebXR shim into the page
 * before any page script runs, so that `navigator.xr.isSessionSupported(...)`
 * and `requestSession(...)` resolve under a software-emulated Quest 3 device
 * profile instead of being `undefined` on a headless Chromium with no real XR
 * hardware (#1878).
 *
 * IWER ships a UMD bundle at `iwer/build/iwer.js` that exposes
 * `globalThis.IWER` with `{ XRDevice, metaQuest3, metaQuest2, ... }`. We
 * `addInitScript` it (Playwright runs it BEFORE the page's own scripts), then
 * a second `addInitScript` instantiates the device and calls
 * `installRuntime()` — both per the documented API in
 * https://meta-quest.github.io/immersive-web-emulation-runtime/getting-started.html
 *
 * The injection is best-effort: if IWER fails to load (e.g. CI sandbox blocked
 * the install or the package was hoisted to a node_modules path that
 * `require.resolve` can't find), the helper logs a warning and the test
 * continues — the DOM-presence assertions on `#enter-ar` / `#enter-vr` still
 * run, mirroring the soft-skip pattern of `screencast`.
 *
 * Best-effort caveat (parity with the Android record/replay docs in
 * `arsceneview/`): IWER is not a real headset. It validates wire-level WebXR
 * API access — session creation, controller events, hit-test stubs — not real
 * spatial tracking. A recorded session fixture is the next step (see
 * follow-up issue tracked in the placeholder `webxr.spec.ts`).
 */
export async function installIwer(page: Page): Promise<{ injected: boolean; error?: string }> {
  let iwerPath: string;
  try {
    // `require` is available in Playwright tests (node runtime) but the type
    // signature in TS strict mode wants an explicit declaration — fall back to
    // a `createRequire` via `module` if the global `require` is absent.
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    iwerPath = require.resolve('iwer/build/iwer.js');
  } catch (e) {
    return { injected: false, error: `iwer package not resolvable: ${(e as Error).message}` };
  }

  // 1. Inject the UMD bundle — exposes window.IWER.
  await page.addInitScript({ path: iwerPath });

  // 2. Boot a Quest 3 emulated device and install the runtime hooks. Guard
  //    every step: if the bundle didn't register IWER for any reason (e.g. a
  //    later IWER version drops the UMD output), do NOT crash the page —
  //    leave navigator.xr undefined and let the test fall through to its
  //    soft-skip branch.
  await page.addInitScript(() => {
    try {
      const I = (globalThis as any).IWER;
      if (!I || !I.XRDevice || !I.metaQuest3) {
        console.warn('[iwer] global IWER missing XRDevice/metaQuest3 — runtime not installed');
        return;
      }
      const device = new I.XRDevice(I.metaQuest3);
      device.installRuntime();
      // Surface a flag tests can check to confirm the shim took, plus the
      // device handle itself so `driveWebXrSession` can nudge pose /
      // controllers programmatically (no recorded fixture needed — #1674/#1748
      // item 4). `__IWER_DEVICE__` is the XRDevice the runtime is bound to.
      (globalThis as any).__IWER_INSTALLED__ = true;
      (globalThis as any).__IWER_DEVICE__ = device;
    } catch (err) {
      console.warn('[iwer] installRuntime threw: ' + (err as Error).message);
    }
  });

  return { injected: true };
}

/** Outcome of a programmatically-driven IWER WebXR session. */
export interface XrSessionRun {
  /** The session-mode that was driven (`immersive-ar` / `immersive-vr`). */
  mode: 'immersive-ar' | 'immersive-vr';
  /** `true` once the demo's `requestSession(...)` promise resolved. */
  sessionStarted: boolean;
  /** `true` once the session's `end` event fired after `session.end()`. */
  sessionEnded: boolean;
  /** Number of synthetic `requestAnimationFrame` XR frames observed. */
  framesObserved: number;
  /** A non-fatal note when the run could not be completed (soft path). */
  note?: string;
}

/**
 * Drive a full WebXR session end-to-end against the IWER-emulated device —
 * the web analogue of the Android AR record/replay harness, WITHOUT needing a
 * recorded fixture or real headset hardware.
 *
 * Why no recorded fixture: IWER's `XRDevice` is a *programmable* emulator. Its
 * pose, controller transforms and `requestAnimationFrame` pump are all driven
 * from script, so a deterministic session can be synthesised in-test. A
 * recorded `.json` capture (IWER's `ActionRecorder` format) still requires a
 * real Quest / Vision Pro to author — but it is NOT a prerequisite for
 * exercising the demo's WebXR code path. This helper closes issue #1674 item 4
 * / #1748 item 4 by replacing the fixture-pending soft-skip with a real run.
 *
 * What it does, inside the page context:
 *  1. Confirms IWER mocked `navigator.xr` and the mode is supported.
 *  2. Calls `navigator.xr.requestSession(mode, ...)` — exactly what the demo's
 *     `#enter-ar` / `#enter-vr` handler does (`requiredFeatures: ['hit-test']`
 *     for AR) — and waits for it to resolve.
 *  3. Pumps a handful of `session.requestAnimationFrame` callbacks so the
 *     session's XR frame loop actually runs (catches a frame-loop throw).
 *  4. Nudges the emulated device pose / first controller so input plumbing is
 *     exercised, not just session creation.
 *  5. Ends the session and waits for its `end` event.
 *
 * Best-effort: if IWER is not installed or the mode is unsupported the helper
 * returns `sessionStarted:false` with a `note` instead of throwing, so the
 * caller can `test.skip(...)` with a discoverable reason (mirrors the
 * soft-degrade contract of `installIwer` / `screencast`).
 */
export async function driveWebXrSession(
  page: Page,
  mode: 'immersive-ar' | 'immersive-vr' = 'immersive-ar',
): Promise<XrSessionRun> {
  return page.evaluate(async (sessionMode: 'immersive-ar' | 'immersive-vr') => {
    const result: {
      mode: 'immersive-ar' | 'immersive-vr';
      sessionStarted: boolean;
      sessionEnded: boolean;
      framesObserved: number;
      note?: string;
    } = {
      mode: sessionMode,
      sessionStarted: false,
      sessionEnded: false,
      framesObserved: 0,
    };

    const xr = (navigator as any).xr;
    if (!xr || typeof xr.requestSession !== 'function') {
      result.note = 'navigator.xr unavailable — IWER runtime not installed';
      return result;
    }

    // The emulated device handle IWER hangs off the global so the test can
    // nudge pose / controllers. Absent on a pre-2.x IWER — degrade softly.
    const device = (globalThis as any).__IWER_DEVICE__;

    let supported = false;
    try {
      supported = await xr.isSessionSupported(sessionMode);
    } catch {
      /* isSessionSupported can reject — treat as unsupported */
    }
    if (!supported) {
      result.note = `${sessionMode} not supported by the emulated device`;
      return result;
    }

    // Same option shape the demo's #enter-ar handler uses.
    const opts = sessionMode === 'immersive-ar'
      ? { requiredFeatures: ['hit-test'] }
      : {};

    let session: any;
    try {
      session = await xr.requestSession(sessionMode, opts);
    } catch (e) {
      result.note = `requestSession(${sessionMode}) rejected: ${(e as Error).message}`;
      return result;
    }
    result.sessionStarted = true;

    let ended = false;
    session.addEventListener('end', () => { ended = true; });

    // The session's frame loop only pumps once a baseLayer is attached via
    // `updateRenderState` — IWER's XRSession bails its device-frame callback
    // while `renderState.baseLayer === null`. A real WebXR app wires its own
    // GL context here; the demo's #enter-ar handler does not, so this helper
    // attaches a throwaway `XRWebGLLayer` over an offscreen WebGL context so
    // the frame loop runs and `requestAnimationFrame` callbacks actually fire.
    try {
      const xrCanvas = document.createElement('canvas');
      xrCanvas.width = 64;
      xrCanvas.height = 64;
      const xrGl =
        (xrCanvas.getContext('webgl2', { xrCompatible: true } as any) as WebGLRenderingContext | null) ??
        (xrCanvas.getContext('webgl', { xrCompatible: true } as any) as WebGLRenderingContext | null);
      const XRWebGLLayerCtor = (globalThis as any).XRWebGLLayer;
      if (xrGl && typeof XRWebGLLayerCtor === 'function') {
        // `makeXRCompatible` is required before an XRWebGLLayer can wrap a
        // context — guarded, some emulated contexts already report compatible.
        if (typeof (xrGl as any).makeXRCompatible === 'function') {
          await (xrGl as any).makeXRCompatible();
        }
        const layer = new XRWebGLLayerCtor(session, xrGl);
        await session.updateRenderState({ baseLayer: layer });
      }
    } catch {
      /* render-state setup is best-effort — frames may still pump on some
       * IWER versions, and the frame-count assertion below catches a miss */
    }

    // Pump a few XR frames so the session's animation loop actually executes.
    // A frame-loop throw (the most common WebXR regression) surfaces here.
    const FRAMES = 8;
    for (let i = 0; i < FRAMES; i++) {
      await new Promise<void>((resolve) => {
        let settled = false;
        const done = () => { if (!settled) { settled = true; resolve(); } };
        try {
          session.requestAnimationFrame(() => {
            result.framesObserved++;
            done();
          });
        } catch {
          done();
        }
        // Safety valve: never hang the suite if a frame never fires.
        setTimeout(done, 250);
      });

      // Nudge the emulated device so input plumbing is exercised, not just
      // session creation. All guarded — IWER API drift must not crash the run.
      if (device) {
        try {
          const ctrl = device.controllers?.right || device.controllers?.left;
          if (ctrl && ctrl.position) {
            ctrl.position.x = 0.1 * i;
          }
          if (device.position) {
            device.position.z = -0.05 * i;
          }
        } catch {
          /* pose nudge is best-effort */
        }
      }
    }

    // End the session and wait (briefly) for its `end` event to fire.
    try {
      await session.end();
    } catch {
      /* end() can reject if already ended */
    }
    for (let i = 0; i < 20 && !ended; i++) {
      await new Promise((r) => setTimeout(r, 25));
    }
    result.sessionEnded = ended;

    return result;
  }, mode);
}

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
 *
 * `minVariance` is the luminance-variance floor a region must clear to count
 * as "rendered content". It defaults to 64 — a wide margin above a flat dark
 * block (variance < ~20) and far below a normally-lit model (hundreds to
 * thousands). A caller may pass a *modestly* lower floor for a scene that
 * renders legitimately dim on the SwiftShader software rasteriser (e.g. a
 * single point light, whose inverse-square falloff leaves the canvas much
 * darker than a directional light); the floor must still stay clear of a
 * genuinely blank block so the assertion keeps catching a black canvas.
 */
export async function sampleCanvas(
  page: Page,
  region: 'centre' | 'full' = 'centre',
  minVariance = 64,
): Promise<{ hasContent: boolean; headlessGpuOk: boolean; variance: number }> {
  const canvas = page.locator('#scene-canvas');
  if ((await canvas.count()) === 0) {
    return { hasContent: false, headlessGpuOk: false, variance: 0 };
  }
  const box = await canvas.boundingBox();
  if (!box || box.width === 0 || box.height === 0) {
    return { hasContent: false, headlessGpuOk: false, variance: 0 };
  }

  // Screenshot a block of the canvas as a lossless PNG, then decode it back to
  // pixels inside the browser (an <img> + 2D canvas natively decodes PNG; this
  // never touches the WebGL context so the `preserveDrawingBuffer` problem
  // above does not apply).
  //
  // `region: 'centre'` (default) samples a 200px block at the canvas centre —
  // where a tightly auto-framed model / pendulum / geometry sits. `'full'`
  // samples the entire canvas: the same variance methodology, but it does not
  // assume the rendered subject is centred. Use `'full'` for scenes whose
  // auto-framing legitimately places the subject off-centre (e.g. the
  // skinned-model Animation tab, whose rest-pose bounding box offsets the
  // frame) — it is an equally hard blank-canvas signal, not a weaker one.
  const side = 200;
  const clip =
    region === 'full'
      ? { x: box.x, y: box.y, width: box.width, height: box.height }
      : {
          x: box.x + box.width / 2 - side / 2,
          y: box.y + box.height / 2 - side / 2,
          width: side,
          height: side,
        };
  const png = await page.screenshot({ type: 'png', clip });
  const dataUri = 'data:image/png;base64,' + png.toString('base64');

  // A blank / uniform region has near-zero luminance variance; a rendered
  // scene (model shading, the pendulum links, geometry edges) shows a wide
  // spread of luminance values. Variance is robust to the demo's dark theme.
  return page.evaluate(async (args: { uri: string; minVariance: number }) => {
    const img = new Image();
    await new Promise<void>((resolve, reject) => {
      img.onload = () => resolve();
      img.onerror = () => reject(new Error('decode failed'));
      img.src = args.uri;
    });
    const c = document.createElement('canvas');
    c.width = img.width;
    c.height = img.height;
    const ctx = c.getContext('2d');
    if (!ctx) return { hasContent: false, headlessGpuOk: false, variance: 0 };
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
    // hundreds-to-thousands. `minVariance` (default 64) is a wide safety
    // margin either side — a caller may lower it modestly for a legitimately
    // dim scene, but never below the flat-block ceiling.
    return { hasContent: variance > args.minVariance, headlessGpuOk: true, variance };
  }, { uri: dataUri, minVariance });
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

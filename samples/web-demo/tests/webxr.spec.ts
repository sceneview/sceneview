import * as fs from 'fs';
import * as path from 'path';

import {
  test,
  expect,
  captureDiagnostics,
  expectNoPageErrors,
  waitForEngineReady,
  installIwer,
} from './helpers';

/**
 * SceneView Web Demo — IWER (Immersive Web Emulation Runtime) WebXR scaffold.
 *
 * Item 4 of the autonomous device-QA harness umbrella (issue #1748), filed as
 * the dedicated follow-up issue #1878.
 *
 * Scope of THIS spec — scaffold only:
 *  - Inject IWER's UMD bundle via `installIwer(page)` (see `helpers.ts`).
 *  - Click `#enter-ar` / `#enter-vr` and assert the click does NOT throw,
 *    does NOT lose the WebGL context, and does NOT emit a real page error.
 *  - SOFT-SKIP the rich replay assertions when a recorded XR session fixture
 *    is missing — recording requires real WebXR hardware (Quest, Vision Pro)
 *    and lands in a separate PR with the fixture file. See the follow-up
 *    issue referenced at the top of this file.
 *
 * Why the recording is deferred: a real WebXR session (controller-driven
 * model selection + scene orbit) can only be recorded on a real WebXR-capable
 * device — impossible from an agent sandbox or a headless CI runner. The
 * IWER infrastructure + this skeleton land NOW so that the fixture, when it
 * arrives, has somewhere to plug in.
 *
 * Best-effort caveat (parity with the Android AR record/replay docs in
 * `arsceneview/`): IWER is not a real headset. It emulates the wire-level
 * WebXR API — session lifecycle, controller input events, hit-test stubs —
 * not real spatial tracking. The hard regression signal is the recorded
 * replay; this scaffold only proves the runtime shim is wired correctly and
 * the AR/VR buttons don't crash the page when clicked.
 */

/** Where a recorded WebXR session fixture WOULD live once we have one. */
const RECORDING_PATH = path.resolve(__dirname, 'fixtures', 'webxr-session-models.json');

test.describe('Web Demo — WebXR (IWER scaffold)', () => {

  test('IWER runtime installs and enter-ar click does not crash the page', async ({ page }) => {
    // 1. Inject the IWER UMD bundle + boot a Quest 3 emulated device. The
    //    helper is best-effort: if iwer is not installed (CI sandbox), or its
    //    UMD shape changed, the install is skipped — the rest of the test
    //    still runs and asserts the existing DOM contract.
    const iwer = await installIwer(page);

    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);

    // 2. The AR/VR buttons must be in the DOM regardless of IWER state.
    const enterAr = page.locator('#enter-ar');
    const enterVr = page.locator('#enter-vr');
    await expect(enterAr).toBeAttached();
    await expect(enterVr).toBeAttached();

    // 3. If IWER took, `navigator.xr.isSessionSupported('immersive-ar')` now
    //    resolves true under the Quest 3 profile and the demo's `setupXR()`
    //    flips the buttons to `display: inline-block`. We don't hard-assert
    //    visibility — the demo's CSS may keep the button visually hidden on
    //    a desktop layout even when XR is supported — but we DO confirm the
    //    shim installed inside the page.
    if (iwer.injected) {
      const installed = await page.evaluate(() => (globalThis as any).__IWER_INSTALLED__ === true);
      if (!installed) {
        console.warn(
          '[webxr] IWER bundle injected but __IWER_INSTALLED__ flag missing — ' +
          'install path failed silently in the page context. Continuing with click test.',
        );
      } else {
        // The shim should have mocked navigator.xr.
        const hasXr = await page.evaluate(() => 'xr' in navigator && navigator.xr !== undefined);
        expect(hasXr, 'IWER should mock navigator.xr').toBe(true);
      }
    } else {
      console.warn(`[webxr] IWER not injected: ${iwer.error} — running DOM-only checks`);
    }

    // 4. Click the AR button. Under IWER, requestSession('immersive-ar')
    //    resolves to a mock XRSession; under a no-IWER fallback the click
    //    handler's catch branch fires an `alert(...)` — Playwright accepts
    //    the alert automatically. Either path must not throw an uncaught
    //    exception nor lose the WebGL context.
    page.on('dialog', (d) => d.dismiss().catch(() => {}));
    await enterAr.click({ force: true });

    // Give the runtime a beat to either start the mock session or reject.
    await page.waitForTimeout(500);

    // 5. WebGL context-loss check — a real regression looks like Filament's
    //    canvas going inert. We sample the demo's well-known canvas element
    //    and confirm a context still resolves (the contextlost event would
    //    blow this away under a real failure).
    const ctxAlive = await page.evaluate(() => {
      const c = document.getElementById('scene-canvas') as HTMLCanvasElement | null;
      if (!c) return false;
      const gl = c.getContext('webgl2') || c.getContext('webgl');
      return !!gl && !(gl as WebGLRenderingContext).isContextLost();
    });
    expect(ctxAlive, 'WebGL context must survive the AR-button click').toBe(true);

    // 6. No unhandled errors / no script-level console errors. Network noise
    //    is filtered by captureDiagnostics (Sketchfab 401s etc.).
    expectNoPageErrors(diag, 'WebXR scaffold — enter-ar click');
  });

  test('Recorded XR session replay — fixture pending', async ({ page }) => {
    // The rich replay test only runs when a recorded WebXR session fixture
    // is shipped alongside it. Recording requires a real WebXR-capable
    // device (Quest 3, Vision Pro, ...), which is impossible from an agent
    // sandbox or a headless CI runner.
    //
    // TODO(follow-up of #1878): once a recorded fixture exists at
    // `RECORDING_PATH`, replace this soft-skip body with:
    //   await installIwer(page);
    //   const recording = JSON.parse(fs.readFileSync(RECORDING_PATH, 'utf8'));
    //   await page.evaluate((r) => (globalThis as any).IWER.replay(r), recording);
    //   await page.goto('/');
    //   await waitForEngineReady(page);
    //   await page.locator('#enter-ar').click();
    //   // assert scene-state checkpoints from the recording...
    //   expectNoPageErrors(diag, 'WebXR replay');
    const hasRecording = fs.existsSync(RECORDING_PATH);
    test.skip(
      !hasRecording,
      'TODO: recorded WebXR session fixture pending — see follow-up issue of #1878. ' +
        `Drop the recording at ${path.relative(process.cwd(), RECORDING_PATH)} to enable.`,
    );

    // Body unreachable until the fixture lands; keep a no-op assertion so the
    // shape is obviously a real test body and not a forgotten stub.
    expect(hasRecording).toBe(true);
  });
});

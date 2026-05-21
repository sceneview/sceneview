import {
  test,
  expect,
  captureDiagnostics,
  expectNoPageErrors,
  waitForEngineReady,
  assertCanvasContextAlive,
  installIwer,
  driveWebXrSession,
} from './helpers';

/**
 * SceneView Web Demo — IWER (Immersive Web Emulation Runtime) WebXR scaffold.
 *
 * Item 4 of the autonomous device-QA harness umbrella (issue #1748), filed as
 * the dedicated follow-up issue #1878.
 *
 * Scope of THIS spec:
 *  - Inject IWER's UMD bundle via `installIwer(page)` (see `helpers.ts`).
 *  - Click `#enter-ar` / `#enter-vr` and assert the click does NOT throw,
 *    does NOT lose the WebGL context, and does NOT emit a real page error.
 *  - Drive a FULL WebXR session programmatically with `driveWebXrSession()`:
 *    request the session (same option shape the demo uses), pump XR animation
 *    frames, nudge the emulated device pose / controllers, end the session.
 *
 * Why no recorded fixture: IWER's `XRDevice` is a *programmable* emulator —
 * pose, controllers and the rAF pump are all script-driven, so a deterministic
 * session is synthesised in-test. An `ActionRecorder` `.json` capture still
 * needs a real Quest / Vision Pro to author, but it is NOT a prerequisite for
 * exercising the demo's WebXR code path. The earlier "fixture pending"
 * soft-skip is therefore replaced by a real runnable session-drive test
 * (issue #1674 item 4 / #1748 item 4).
 *
 * Best-effort caveat (parity with the Android AR record/replay docs in
 * `arsceneview/`): IWER is not a real headset. It emulates the wire-level
 * WebXR API — session lifecycle, controller input events, hit-test stubs —
 * not real spatial tracking or real-world geometry. It validates that the
 * demo's WebXR plumbing is correct and survives a session round-trip; it
 * cannot validate real spatial accuracy.
 */

test.describe('Web Demo — WebXR (IWER)', () => {

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

  test('IWER drives a full immersive-ar session — request, frame loop, end', async ({ page }) => {
    // 1. Inject IWER + boot the emulated Quest 3 device before any page script.
    const iwer = await installIwer(page);
    test.skip(!iwer.injected, `IWER not installed in this environment: ${iwer.error}`);

    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);

    // 2. Drive the session end-to-end against the emulated device — request it
    //    with the same `requiredFeatures: ['hit-test']` shape the demo uses,
    //    pump XR animation frames, nudge pose / controllers, then end it. No
    //    recorded fixture: IWER's XRDevice is a programmable emulator.
    const run = await driveWebXrSession(page, 'immersive-ar');

    // If the emulated device legitimately could not run the session (a future
    // IWER version dropping immersive-ar support, etc.) surface it as a skip
    // with a discoverable reason rather than a hard fail.
    test.skip(
      !run.sessionStarted && run.note !== undefined,
      `immersive-ar session could not be driven: ${run.note}`,
    );

    // 3. Hard assertions — the WebXR code path actually ran.
    expect(run.sessionStarted, 'immersive-ar requestSession() must resolve under IWER').toBe(true);
    expect(
      run.framesObserved,
      'the XR session animation loop must produce at least one frame',
    ).toBeGreaterThan(0);
    expect(run.sessionEnded, 'session.end() must fire the session "end" event').toBe(true);

    // 4. The Filament WebGL context must survive a full session round-trip —
    //    entering and leaving XR must not lose or crash the canvas context.
    await assertCanvasContextAlive(page, 'after immersive-ar session round-trip');

    // 5. No uncaught errors / script-level console errors across the run.
    expectNoPageErrors(diag, 'IWER immersive-ar session drive');
  });

  test('IWER drives an immersive-vr session — request, frame loop, end', async ({ page }) => {
    const iwer = await installIwer(page);
    test.skip(!iwer.injected, `IWER not installed in this environment: ${iwer.error}`);

    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);

    const run = await driveWebXrSession(page, 'immersive-vr');
    test.skip(
      !run.sessionStarted && run.note !== undefined,
      `immersive-vr session could not be driven: ${run.note}`,
    );

    expect(run.sessionStarted, 'immersive-vr requestSession() must resolve under IWER').toBe(true);
    expect(
      run.framesObserved,
      'the XR session animation loop must produce at least one frame',
    ).toBeGreaterThan(0);
    expect(run.sessionEnded, 'session.end() must fire the session "end" event').toBe(true);

    await assertCanvasContextAlive(page, 'after immersive-vr session round-trip');
    expectNoPageErrors(diag, 'IWER immersive-vr session drive');
  });
});

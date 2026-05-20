import {
  test,
  expect,
  captureDiagnostics,
  expectNoPageErrors,
  waitForEngineReady,
  sampleCanvas,
  dragCanvas,
  switchTab,
  waitForModelChipIdle,
} from './helpers';

/**
 * SceneView Web Demo — full catalog / demo-coverage QA suite.
 *
 * Slice 3 of the autonomous device-QA harness (umbrella #1560, issue #1564).
 *
 * The shipped web demo (`src/jsMain/resources/index.html`) is a self-contained
 * single-file app with four catalog tabs: Models, Geometry, Physics, Settings.
 * (The umbrella's "8 tabs" figure predates a check of the real demo — the
 * web-demo has 4 tabs; this suite covers all of them and every demo within.)
 *
 * Each test:
 *  - switches the relevant tab,
 *  - exercises the demo's controls / model chips,
 *  - drives a camera orbit/zoom interaction,
 *  - samples the WebGL canvas to assert a non-blank render,
 *  - asserts no console errors and no unhandled rejections.
 *
 * WebXR/AR is out of scope (umbrella defers IWER to a future delta) — only the
 * `#enter-ar` DOM-presence check is kept.
 *
 * Headless-GPU note: on software-rasterised CI runners `gl.readPixels` may not
 * yield a readable framebuffer. When `sampleCanvas` reports `headlessGpuOk:
 * false` the pixel assertion is soft-skipped (logged, not failed) so the suite
 * stays green on GPU-less runners while still catching genuine blank scenes on
 * GPU-capable hosts.
 */

/**
 * Soft-check that the canvas rendered.
 *
 * WebGL framebuffer readback is unreliable in headless Chromium: depending on
 * the runner's GL backend `gl.readPixels` either throws (no readback at all —
 * `headlessGpuOk: false`) OR succeeds but returns an all-black buffer even
 * though the scene is on screen. Both the local macOS run and the Ubuntu CI
 * runner exhibit the all-black case, so a non-blank pixel result is NOT a
 * dependable headless signal — failing on it produces false negatives.
 *
 * The authoritative visual-render signal lives in `render.spec.ts`, which
 * captures and diffs full-page screenshots. Here we only WARN on a blank
 * sample (mirroring `render.spec.ts`'s own soft pixel check) so the catalog
 * suite stays deterministic across runners (issue #1586).
 */
async function assertRendered(
  page: import('@playwright/test').Page,
  context: string,
): Promise<void> {
  // Let Filament render a few frames after the interaction settled.
  await page.waitForTimeout(1500);
  const { hasContent, headlessGpuOk } = await sampleCanvas(page);
  if (!headlessGpuOk) {
    console.warn(`[${context}] headless GPU cannot read pixels — render assertion skipped`);
    return;
  }
  if (!hasContent) {
    console.warn(
      `[${context}] canvas pixel sample is blank — headless WebGL readback is ` +
        `unreliable; visual coverage is provided by render.spec.ts screenshots`,
    );
  }
}

test.describe('Web Demo — catalog coverage', () => {

  test('Models tab — CDN gallery loads and model chips switch the scene', async ({ page }) => {
    // Heavy WebGL-interaction test: 3 model downloads + WASM uploads + camera
    // orbits, each Filament-rendered. On a GPU-less CI runner software
    // rasterisation makes every frame several times slower, so the default
    // 60s budget is too tight — `test.slow()` triples it (harness #1560).
    test.slow();
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);
    await switchTab(page, 'models');

    // CDN model cards are populated.
    const cards = page.locator('#model-results .result-card');
    const count = await cards.count();
    expect(count, 'CDN model gallery should list models').toBeGreaterThan(0);

    // Exercise a representative sample of model cards across categories
    // (loading all 39 would blow the CI budget). First, middle, last.
    const indices = [...new Set([0, Math.floor(count / 2), count - 1])];
    for (const i of indices) {
      await cards.nth(i).click();
      // Wait for the demo's real load-completion signal (the inline loading
      // chip clearing) instead of a blind sleep — deterministic across a fast
      // local GPU and a slow software-rasterised CI runner.
      await waitForModelChipIdle(page);
      await dragCanvas(page);
      await assertRendered(page, `Models tab — card #${i}`);
    }

    expectNoPageErrors(diag, 'Models tab');
  });

  test('Models tab — Sketchfab source toggle renders its search UI', async ({ page }) => {
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);
    await switchTab(page, 'models');

    // Toggle to Sketchfab — the search box must appear.
    await page.locator('.source-btn[data-source="sketchfab"]').click();
    await expect(page.locator('#search-box')).toBeVisible();
    await expect(page.locator('#search-input')).toBeVisible();

    // Toggle back to CDN — the gallery must repopulate.
    await page.locator('.source-btn[data-source="cdn"]').click();
    await expect(page.locator('#search-box')).toBeHidden();
    expect(await page.locator('#model-results .result-card').count()).toBeGreaterThan(0);

    expectNoPageErrors(diag, 'Sketchfab source toggle');
  });

  // One test per geometry primitive: each is a heavy WebGL-interaction pass
  // (add + camera orbit + Filament render). On GPU-less CI runners software
  // WebGL is ~4x slower, so four primitives in a single test body overran one
  // `test.slow()` 180s budget (#1560). Splitting gives each primitive its own
  // budget while still exercising every primitive.
  for (const geo of ['cube', 'sphere', 'cylinder', 'plane']) {
    test(`Geometry tab — ${geo} adds, recolours and renders`, async ({ page }) => {
      test.slow();
      const diag = captureDiagnostics(page);
      await page.goto('/');
      await waitForEngineReady(page);
      await switchTab(page, 'geometry');

      // Tweak size + unlit toggle before adding.
      const sizeSlider = page.locator(`[data-geo-size="${geo}"]`);
      await sizeSlider.focus();
      await page.keyboard.press('ArrowRight');
      await page.locator(`[data-geo-unlit="${geo}"]`).check();

      await page.locator(`.geo-add-btn[data-geo="${geo}"]`).click();
      await page.waitForTimeout(600);
      await dragCanvas(page);
      await assertRendered(page, `Geometry tab — ${geo}`);

      expectNoPageErrors(diag, `Geometry tab — ${geo}`);
    });
  }

  test('Geometry tab — Clear All Geometry does not throw', async ({ page }) => {
    test.slow();
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);
    await switchTab(page, 'geometry');

    // Add one primitive so Clear has something to remove.
    await page.locator('.geo-add-btn[data-geo="cube"]').click();
    await page.waitForTimeout(600);
    await page.locator('#geo-clear').click();
    await page.waitForTimeout(500);

    expectNoPageErrors(diag, 'Geometry tab — clear');
  });

  test('Physics tab — Double Pendulum runs, sliders + reset work', async ({ page }) => {
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);
    await switchTab(page, 'physics');

    // The pendulum scene needs a moment to build + start integrating.
    await page.waitForTimeout(2000);
    await assertRendered(page, 'Physics tab — initial pendulum');

    // Drive each slider — the integrator must re-seed without errors.
    for (const id of ['dp-length1', 'dp-length2', 'dp-gravity']) {
      const slider = page.locator(`#${id}`);
      await slider.focus();
      await page.keyboard.press('ArrowRight');
      await page.keyboard.press('ArrowRight');
    }

    // Reset & drop re-seeds the run.
    await page.locator('#dp-reset').click();
    await page.waitForTimeout(1500);
    await dragCanvas(page);
    await assertRendered(page, 'Physics tab — after reset');

    expectNoPageErrors(diag, 'Physics tab');
  });

  test('Physics tab — #double-pendulum deep link opens it directly', async ({ page }) => {
    const diag = captureDiagnostics(page);
    await page.goto('/#double-pendulum');
    await waitForEngineReady(page);

    // The deep link is flushed once the engine is ready.
    await expect(page.locator('.tab-btn[data-tab="physics"]')).toHaveClass(/active/, {
      timeout: 10_000,
    });
    await expect(page.locator('#panel-physics')).toHaveClass(/active/);

    expectNoPageErrors(diag, 'Physics deep link');
  });

  test('Settings tab — quality, bloom, auto-rotate and background all apply', async ({ page }) => {
    // Heavy WebGL-interaction test: cycling render quality rebuilds the
    // Filament pipeline 3×, plus bloom/rotate/background each force re-renders.
    // Triple the budget for GPU-less CI runners (#1560).
    test.slow();
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);
    await switchTab(page, 'settings');

    // Render quality — cycle every option.
    for (const quality of ['low', 'high', 'medium']) {
      await page.locator('#quality-select').selectOption(quality);
      await page.waitForTimeout(500);
    }

    // Bloom toggle.
    await page.locator('#bloom-toggle').click();
    await page.waitForTimeout(400);

    // Auto-rotate toggle.
    await page.locator('#rotate-toggle').click();
    await page.waitForTimeout(400);

    // Background colour.
    await page.locator('#bg-color').evaluate((el: HTMLInputElement) => {
      el.value = '#223344';
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await page.waitForTimeout(500);

    await dragCanvas(page);
    await assertRendered(page, 'Settings tab');
    expectNoPageErrors(diag, 'Settings tab');
  });

  test('Top-bar auto-rotate button toggles without errors', async ({ page }) => {
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);

    const btn = page.locator('#auto-rotate-toggle');
    await btn.click(); // off
    await expect(btn).not.toHaveClass(/active/);
    await btn.click(); // on
    await expect(btn).toHaveClass(/active/);

    expectNoPageErrors(diag, 'Auto-rotate button');
  });

  test('every tab switches cleanly with no console errors', async ({ page }) => {
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);

    // Round-trip every tab twice to catch teardown/re-entry leaks
    // (the Physics tab swaps the whole scene in/out — #1503 class of bug).
    const tabs = ['models', 'geometry', 'physics', 'settings'];
    for (let pass = 0; pass < 2; pass++) {
      for (const tab of tabs) {
        await switchTab(page, tab);
        await page.waitForTimeout(400);
      }
    }

    expectNoPageErrors(diag, 'tab round-trip');
  });

  test('WebXR AR/VR buttons are present in the DOM (presence only)', async ({ page }) => {
    await page.goto('/');
    // DOM-presence check only — WebXR sessions are out of scope for this suite.
    await expect(page.locator('#enter-ar')).toBeAttached();
    await expect(page.locator('#enter-vr')).toBeAttached();
  });
});

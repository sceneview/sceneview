import { test, expect, sampleCanvas, assertCanvasContextAlive } from './helpers';

/**
 * SceneView Web Demo — visual regression / smoke tests.
 *
 * These tests load the web demo page, wait for the Filament engine to
 * initialize, and capture screenshots for visual comparison.
 *
 * Screenshots are stored in `tests/render.spec.ts-snapshots/` and compared
 * on subsequent runs. Update baselines with `--update-snapshots`.
 *
 * Full per-tab / per-demo catalog coverage lives in `catalog.spec.ts`
 * (issue #1564). This file stays as the lightweight load + branding +
 * tab-regression smoke layer.
 */

test.describe('SceneView Web Demo Rendering', () => {

  test('page loads and shows canvas', async ({ page }) => {
    await page.goto('/');

    // Wait for the canvas element to be present
    const canvas = page.locator('#scene-canvas');
    await expect(canvas).toBeVisible({ timeout: 30_000 });

    // The loading overlay should eventually disappear
    const overlay = page.locator('#loading-overlay');
    await expect(overlay).toHaveClass(/hidden/, { timeout: 45_000 });

    // Capture full-page screenshot
    await page.screenshot({
      path: 'test-results/01_page_loaded.png',
      fullPage: false,
    });
  });

  test('canvas renders non-blank content', async ({ page }) => {
    await page.goto('/');

    // Wait for loading to complete
    const overlay = page.locator('#loading-overlay');
    await expect(overlay).toHaveClass(/hidden/, { timeout: 45_000 });

    // Give Filament a moment to render frames
    await page.waitForTimeout(2000);

    // Hard-assert the WebGL context is alive and the canvas shows pixels.
    // SwiftShader (`--enable-unsafe-swiftshader` in playwright.config.ts) gives
    // headless CI a real software-rasterised context, so this can be a true
    // failure signal — no more green-on-nothing (issues #1593, #1674).
    await assertCanvasContextAlive(page, 'canvas renders non-blank content');

    const { hasContent, headlessGpuOk } = await sampleCanvas(page);

    // Capture screenshot regardless of content check
    await page.screenshot({
      path: 'test-results/02_canvas_content.png',
      fullPage: false,
    });

    expect(headlessGpuOk, 'canvas element is missing or zero-sized').toBe(true);
    expect(
      hasContent,
      'canvas appears blank — Filament rendered nothing visible',
    ).toBe(true);
  });

  test('model results panel is visible', async ({ page }) => {
    await page.goto('/');

    const overlay = page.locator('#loading-overlay');
    await expect(overlay).toHaveClass(/hidden/, { timeout: 45_000 });

    // The Models panel lists CDN model cards.
    const panel = page.locator('#panel-models');
    await expect(panel).toBeVisible();

    const cards = page.locator('#model-results .result-card');
    expect(await cards.count()).toBeGreaterThan(0);

    // Screenshot with UI visible
    await page.screenshot({
      path: 'test-results/03_model_results.png',
      fullPage: false,
    });
  });

  test('every tab activates its matching panel', async ({ page }) => {
    await page.goto('/');

    const overlay = page.locator('#loading-overlay');
    await expect(overlay).toHaveClass(/hidden/, { timeout: 45_000 });

    // Regression guard for issue #1503: switchTab() must toggle every
    // `panel-*` div, not just `panel-models`/`panel-geometry`. A blank side
    // panel on Models / Physics / Settings shipped because the panel-ID list
    // drifted out of sync with the `data-tab` attributes.
    //
    // Issue #1362: the catalog gained Lighting / Animation / Text /
    // Environment tabs — every one must activate its matching panel.
    const tabs = [
      'models', 'geometry', 'lighting', 'animation',
      'text', 'environment', 'physics', 'settings',
    ];

    for (const tab of tabs) {
      await page.locator(`.tab-btn[data-tab="${tab}"]`).click();

      // The clicked tab button is active.
      await expect(page.locator(`.tab-btn[data-tab="${tab}"]`)).toHaveClass(/active/);

      // Its matching panel is active and visible.
      const panel = page.locator(`#panel-${tab}`);
      await expect(panel).toHaveClass(/active/);
      await expect(panel).toBeVisible();

      // No other panel is left active.
      for (const other of tabs) {
        if (other === tab) continue;
        await expect(page.locator(`#panel-${other}`)).not.toHaveClass(/active/);
      }
    }

    await page.screenshot({
      path: 'test-results/06_tabs.png',
      fullPage: false,
    });
  });

  test('catalog tabs expose working controls (issue #1362)', async ({ page }) => {
    await page.goto('/');

    const overlay = page.locator('#loading-overlay');
    await expect(overlay).toHaveClass(/hidden/, { timeout: 45_000 });

    // Lighting — add a directional light, then clear added lights.
    await page.locator('.tab-btn[data-tab="lighting"]').click();
    await page.locator('.geo-add-btn[data-light="directional"]').click();
    await page.locator('.geo-add-btn[data-light="point"]').click();
    await expect(page.locator('#light-clear')).toBeVisible();
    await page.locator('#light-clear').click();

    // Animation — model selector + Play/Stop controls present.
    await page.locator('.tab-btn[data-tab="animation"]').click();
    await expect(page.locator('#anim-model-select')).toBeVisible();
    await expect(page.locator('#anim-play')).toBeEnabled();
    await expect(page.locator('#anim-stop')).toBeEnabled();

    // Text — type and add a text node.
    await page.locator('.tab-btn[data-tab="text"]').click();
    await page.locator('#text-input').fill('Hello SceneView');
    await page.locator('#text-add').click();
    await expect(page.locator('#text-clear')).toBeVisible();

    // Environment — preset select, intensity and bloom controls present.
    await page.locator('.tab-btn[data-tab="environment"]').click();
    await expect(page.locator('#env-preset')).toBeVisible();
    await page.locator('#env-preset').selectOption('cool');
    await page.locator('#env-bloom-toggle').click();
    await expect(page.locator('#env-bloom-toggle')).toHaveClass(/active/);

    await page.screenshot({
      path: 'test-results/07_catalog_tabs.png',
      fullPage: false,
    });
  });

  test('switching models updates the scene', async ({ page }) => {
    await page.goto('/');

    const overlay = page.locator('#loading-overlay');
    await expect(overlay).toHaveClass(/hidden/, { timeout: 45_000 });
    await page.waitForTimeout(1000);

    // The CDN gallery renders `.result-card` entries — the demo has no
    // `.model-chip` element (the old selector silently matched nothing).
    const cards = page.locator('#model-results .result-card');
    const count = await cards.count();
    expect(count, 'CDN model gallery should list models').toBeGreaterThan(1);

    // Click the second model card and wait for the model to load.
    await cards.nth(1).click();
    await page.waitForTimeout(3000);

    // Capture after switch
    await page.screenshot({
      path: 'test-results/04_model_switched.png',
      fullPage: false,
    });
  });

  test('top bar branding is correct', async ({ page }) => {
    await page.goto('/');

    // Check logo text
    const logoText = page.locator('.logo-text');
    await expect(logoText).toHaveText('SceneView Web Demo');

    // Check version badge
    const version = page.locator('.logo-version');
    await expect(version).toHaveText(/^v\d+\.\d+\.\d+$/);

    await page.screenshot({
      path: 'test-results/05_branding.png',
      fullPage: false,
    });
  });

  test('XR buttons exist (may be hidden)', async ({ page }) => {
    await page.goto('/');

    // AR and VR buttons should exist in DOM even if hidden
    const arBtn = page.locator('#enter-ar');
    const vrBtn = page.locator('#enter-vr');

    await expect(arBtn).toBeAttached();
    await expect(vrBtn).toBeAttached();
  });
});

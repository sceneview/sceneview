import {
  test,
  expect,
  captureDiagnostics,
  expectNoPageErrors,
  waitForEngineReady,
  sampleCanvas,
  assertCanvasContextAlive,
  dragCanvas,
  switchTab,
  waitForModelChipIdle,
} from './helpers';

/**
 * SceneView Web Demo — full catalog / demo-coverage QA suite.
 *
 * Slice 3 of the autonomous device-QA harness (umbrella #1560, issue #1564).
 *
 * The shipped web demo (`site/index.html`) is a self-contained single-file app
 * with nine catalog tabs: Models, Geometry, Lighting, Animation, Text,
 * Environment, Physics, Spatial Audio, Settings. This suite covers all of them
 * and every demo within. (The Spatial Audio panel additionally has a dedicated
 * Web Audio API regression suite in `audio.spec.ts` — issue #1944.)
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
 * Headless-GPU note: Chromium is launched with `--enable-unsafe-swiftshader`
 * (see `playwright.config.ts`), so even GPU-less CI runners get a real WebGL
 * context via the SwiftShader software rasteriser. Combined with the
 * compositor-screenshot variance signal in `sampleCanvas`, that lets us
 * HARD-fail on a genuinely blank canvas (issues #1593 + #1674).
 */

/**
 * Hard-assert that the canvas actually rendered after an interaction.
 *
 * Two complementary signals — both must hold or the test fails:
 *
 * 1. WebGL context is alive: `gl.isContextLost() === false`. This catches a
 *    GPU-process crash / lost context — the canvas is still in the DOM but
 *    nothing will ever paint to it again.
 * 2. Pixels were drawn: compositor screenshot of the canvas centre shows a
 *    non-flat luminance variance (see `sampleCanvas` for why we use a
 *    compositor screenshot instead of `canvas.toDataURL()` — Filament.js
 *    creates its context with `preserveDrawingBuffer: false`, so any direct
 *    WebGL readback returns zeros after present).
 *
 * Network noise (Sketchfab 401s, model-CDN parse errors) is still filtered
 * via `isIgnorableNoise()` in `helpers.ts` — that is a separate concern.
 */
async function assertRendered(
  page: import('@playwright/test').Page,
  context: string,
  region: 'centre' | 'full' = 'centre',
  minVariance = 64,
): Promise<void> {
  // Let Filament render a few frames after the interaction settled.
  await page.waitForTimeout(1500);
  // 1. WebGL context must still be alive — catches GPU-process crashes.
  await assertCanvasContextAlive(page, context);
  // 2. Compositor screenshot must show non-flat pixels — catches the
  //    blank/black-canvas regression class that the old soft-warn missed
  //    (issue #1593). `region` is 'centre' for tightly-framed scenes and
  //    'full' for scenes whose auto-frame legitimately off-centres the subject
  //    (the skinned-model Animation tab) — both are equally hard signals.
  //    `minVariance` defaults to 64; a caller may lower it modestly for a
  //    scene that renders legitimately dim on the SwiftShader software
  //    rasteriser (e.g. a single point light) — it must still stay clear of
  //    a genuinely blank/black canvas (variance < ~20).
  const { hasContent, headlessGpuOk, variance } = await sampleCanvas(
    page,
    region,
    minVariance,
  );
  expect(
    headlessGpuOk,
    `[${context}] cannot sample canvas — element missing or zero-sized`,
  ).toBe(true);
  expect(
    hasContent,
    `[${context}] canvas appears blank — Filament rendered nothing visible ` +
      `(luminance variance ${variance.toFixed(1)} <= ${minVariance}; ` +
      `SwiftShader CI context loss, asset load failure, or genuine blank-scene regression)`,
  ).toBe(true);
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

  // One test per light type: adding a Filament light rebuilds the lighting
  // pipeline and forces a re-render, so each is a heavy WebGL-interaction pass.
  // Splitting per type gives every light its own `test.slow()` budget on
  // GPU-less CI runners (#1560, #2099).
  for (const light of ['directional', 'point', 'spot']) {
    test(`Lighting tab — ${light} light adds, recolours and renders`, async ({ page }) => {
      test.slow();
      const diag = captureDiagnostics(page);
      await page.goto('/');
      await waitForEngineReady(page);
      await switchTab(page, 'lighting');

      // Tweak intensity + colour before adding.
      const intensity = page.locator(`[data-light-intensity="${light}"]`);
      await intensity.focus();
      await page.keyboard.press('ArrowRight');
      await page.locator(`[data-light-color="${light}"]`).evaluate((el: HTMLInputElement) => {
        el.value = '#ff8800';
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      });

      await page.locator(`.geo-add-btn[data-light="${light}"]`).click();
      // A point light's inverse-square falloff leaves the scene markedly
      // darker than a directional/spot light, and the SwiftShader software
      // rasteriser dims it further still — its tightly-framed centre block
      // can dip below the default variance floor even though the canvas is
      // genuinely rendering (#1643). Give that one light extra render-settle
      // time, sample the FULL canvas (more lit geometry in frame than the
      // 200px centre crop), and apply a modestly lower variance floor of 28
      // — still well clear of a flat/black block (variance < ~20), so the
      // assertion keeps hard-failing on a genuinely blank canvas.
      await page.waitForTimeout(light === 'point' ? 1200 : 600);
      await dragCanvas(page);
      if (light === 'point') {
        await assertRendered(page, 'Lighting tab — point', 'full', 28);
      } else {
        await assertRendered(page, `Lighting tab — ${light}`);
      }

      expectNoPageErrors(diag, `Lighting tab — ${light}`);
    });
  }

  test('Lighting tab — Remove Added Lights does not throw', async ({ page }) => {
    test.slow();
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);
    await switchTab(page, 'lighting');

    // Add one light so the clear has something to remove.
    await page.locator('.geo-add-btn[data-light="point"]').click();
    await page.waitForTimeout(600);
    await page.locator('#light-clear').click();
    await page.waitForTimeout(500);

    expectNoPageErrors(diag, 'Lighting tab — clear');
  });

  test('Animation tab — animated model plays, loops and stops', async ({ page }) => {
    // Loads a skinned glTF model + drives keyframe animation — a heavy WebGL
    // pass that overruns the default budget on software-rasterised CI (#2099).
    test.slow();
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);
    await switchTab(page, 'animation');

    // Pick a model, then play it.
    await page.locator('#anim-model-select').selectOption('animated_dragon.glb');
    await page.locator('#anim-play').click();
    // The model is downloaded + parsed + uploaded to WASM before the clip runs.
    // The Animation panel reports completion through `#anim-status` (not the
    // shared `#loading-chip`), so wait for the "Playing" status text — a
    // deterministic signal across a fast local GPU and a slow CI rasteriser.
    await expect(page.locator('#anim-status')).toContainText('Playing', {
      timeout: 30_000,
    });
    await dragCanvas(page);
    // The animated-model auto-frame uses the skinned mesh's static rest-pose
    // bounding box, which legitimately off-centres the subject — sample the
    // full canvas so a real render still hard-asserts (the demo's animated-model
    // framing quirk is tracked separately, not by this coverage suite).
    await assertRendered(page, 'Animation tab — playing', 'full');

    // Stop must not throw and the scene must still be alive.
    await page.locator('#anim-stop').click();
    await page.waitForTimeout(500);
    await assertRendered(page, 'Animation tab — stopped', 'full');

    expectNoPageErrors(diag, 'Animation tab');
  });

  test('Text tab — 3D text adds, restyles, renders and clears', async ({ page }) => {
    test.slow();
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);
    await switchTab(page, 'text');

    // Edit the text, recolour and resize before adding.
    await page.locator('#text-input').fill('Hello SceneView');
    await page.locator('#text-color').evaluate((el: HTMLInputElement) => {
      el.value = '#ffcc00';
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    });
    const size = page.locator('#text-size');
    await size.focus();
    await page.keyboard.press('ArrowRight');

    await page.locator('#text-add').click();
    await page.waitForTimeout(800);
    await dragCanvas(page);
    await assertRendered(page, 'Text tab — added');

    // Remove Added Text must not throw.
    await page.locator('#text-clear').click();
    await page.waitForTimeout(500);

    expectNoPageErrors(diag, 'Text tab');
  });

  test('Environment tab — IBL preset, intensity, background and bloom all apply', async ({
    page,
  }) => {
    // Cycling IBL presets rebuilds the indirect-lighting environment, plus
    // bloom forces a post-process re-render — heavy enough to need the tripled
    // budget on GPU-less CI runners (#2099).
    test.slow();
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);
    await switchTab(page, 'environment');

    // IBL preset — cycle every option.
    for (const preset of ['cool', 'dramatic', 'warm']) {
      await page.locator('#env-preset').selectOption(preset);
      await page.waitForTimeout(500);
    }

    // IBL intensity slider.
    const intensity = page.locator('#env-intensity');
    await intensity.focus();
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('ArrowRight');

    // Background colour.
    await page.locator('#env-bg-color').evaluate((el: HTMLInputElement) => {
      el.value = '#112233';
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await page.waitForTimeout(400);

    // Bloom toggle + strength.
    await page.locator('#env-bloom-toggle').click();
    const strength = page.locator('#env-bloom-strength');
    await strength.focus();
    await page.keyboard.press('ArrowRight');
    await page.waitForTimeout(500);

    await dragCanvas(page);
    await assertRendered(page, 'Environment tab');
    expectNoPageErrors(diag, 'Environment tab');
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

  test('Spatial Audio tab — orbiting bell scene builds and renders', async ({ page }) => {
    // The Spatial Audio panel renders a 3D bell that orbits the scene; the
    // Web Audio graph itself (PannerNode/HRTF) has dedicated coverage in
    // `audio.spec.ts` (#1944). Here we only assert the catalog tab switches
    // cleanly and its scene renders — mirroring the other tab tests (#2099).
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);
    await switchTab(page, 'audio');

    // The orbiting-bell scene needs a moment to build.
    await page.waitForTimeout(1500);

    // Cycle the falloff model — re-seeds the audio attenuation without errors.
    for (const falloff of ['linear', 'none', 'inverse']) {
      await page.locator('#audio-falloff').selectOption(falloff);
      await page.waitForTimeout(300);
    }

    await dragCanvas(page);
    await assertRendered(page, 'Spatial Audio tab');
    expectNoPageErrors(diag, 'Spatial Audio tab');
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
    // 9 tabs × 2 passes — every switch rebuilds a Filament scene (the Physics
    // tab swaps the whole scene in/out). On a GPU-less CI runner each rebuild
    // is software-rasterised and several times slower, so the 18 switches
    // overran the default 60s budget. `test.slow()` triples it (#1560, #1643).
    test.slow();
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);

    // Round-trip every tab twice to catch teardown/re-entry leaks
    // (the Physics tab swaps the whole scene in/out — #1503 class of bug).
    const tabs = [
      'models',
      'geometry',
      'lighting',
      'animation',
      'text',
      'environment',
      'physics',
      'audio',
      'settings',
    ];
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

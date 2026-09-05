import { test, expect, waitForEngineReady } from './helpers';

/**
 * Filament.js shadow options — regression + visible-effect proof (#3456).
 *
 * With the Filament.js 1.70.x runtime the repo used to vendor, BOTH public
 * shadow-option entry points threw an Embind `UnboundTypeError`:
 *
 *     Cannot call LightManager._setShadowOptions due to unbound types: 0x...
 *
 * `LightManager::ShadowOptions::transform` is a `math::quatf`, and that build
 * never registered `quatf` with Embind (google/filament#10116, first shipped in
 * Filament v1.72.1). Nothing on the web could configure the shadow map — no
 * `mapSize`, no `normalBias`, no contact shadows.
 *
 * Both tests run against the SAME runtime the demo ships
 * (`site/js/filament/filament.js`), through the demo's own `SceneView.create()`
 * instance (`sceneViewInstance` in `site/index.html`), so a runtime swap that
 * re-breaks the bindings fails here before it reaches the site.
 */

interface ShadowOptionErrors {
  builder?: string;
  setter?: string;
}

async function pixelDiffRatio(page: import('@playwright/test').Page, a: Buffer, b: Buffer): Promise<number> {
  return page.evaluate(
    async (args: { a: string; b: string }) => {
      const decode = (uri: string) =>
        new Promise<HTMLImageElement>((resolve, reject) => {
          const img = new Image();
          img.onload = () => resolve(img);
          img.onerror = () => reject(new Error('decode failed'));
          img.src = uri;
        });
      const [ia, ib] = await Promise.all([decode(args.a), decode(args.b)]);
      const w = Math.min(ia.width, ib.width);
      const h = Math.min(ia.height, ib.height);
      const read = (img: HTMLImageElement) => {
        const c = document.createElement('canvas');
        c.width = w;
        c.height = h;
        const ctx = c.getContext('2d')!;
        ctx.drawImage(img, 0, 0);
        return ctx.getImageData(0, 0, w, h).data;
      };
      const da = read(ia);
      const db = read(ib);
      let changed = 0;
      for (let i = 0; i < da.length; i += 4) {
        const d = Math.max(
          Math.abs(da[i] - db[i]),
          Math.abs(da[i + 1] - db[i + 1]),
          Math.abs(da[i + 2] - db[i + 2]),
        );
        if (d > 12) changed++;
      }
      return changed / (w * h);
    },
    { a: 'data:image/png;base64,' + a.toString('base64'), b: 'data:image/png;base64,' + b.toString('base64') },
  );
}

test.describe('Filament.js shadow options (#3456)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForEngineReady(page);
  });

  test('Builder.shadowOptions() and LightManager.setShadowOptions() do not throw', async ({ page }) => {
    const errors = await page.evaluate((): ShadowOptionErrors => {
      const F = (window as any).Filament;
      const sv = (window as any).sceneViewInstance ?? (globalThis as any).sceneViewInstance;
      const engine = sv._engine;
      const lm = engine.getLightManager();
      const out: ShadowOptionErrors = {};

      // Route 1 — the builder, exactly as the issue reports it.
      const viaBuilder = F.EntityManager.get().create();
      try {
        F.LightManager.Builder(F.LightManager$Type.SUN)
          .direction([0.6, -1.0, -0.8])
          .castShadows(true)
          .shadowOptions({ mapSize: 2048 })
          .build(engine, viaBuilder);
      } catch (e: any) {
        out.builder = String(e?.message ?? e);
      }

      // Route 2 — the instance setter on an already-built light.
      const viaSetter = F.EntityManager.get().create();
      F.LightManager.Builder(F.LightManager$Type.SUN)
        .direction([0.6, -1.0, -0.8])
        .castShadows(true)
        .build(engine, viaSetter);
      try {
        lm.setShadowOptions(lm.getInstance(viaSetter), {
          mapSize: 2048,
          normalBias: 0.35,
          screenSpaceContactShadows: true,
          stepCount: 12,
          maxShadowDistance: 0.45,
        });
      } catch (e: any) {
        out.setter = String(e?.message ?? e);
      }

      // Neither light was added to the scene; drop the components again.
      for (const entity of [viaBuilder, viaSetter]) {
        try { lm.destroy(entity); } catch (_) { /* best effort */ }
        try { engine.destroyEntity(entity); } catch (_) { /* best effort */ }
      }
      return out;
    });

    // Before Filament 1.72.1 both entries were
    // "Cannot call LightManager._setShadowOptions due to unbound types: 0x...".
    expect(errors, 'shadow-option entry points threw').toEqual({});
  });

  test('mapSize visibly changes the rendered shadow', async ({ page, screencast }) => {
    // The demo loads its default model best-effort AFTER the overlay clears;
    // let that land first, or its auto-framing would replace the bench and
    // re-enable auto-rotate under the screenshots.
    try {
      await expect
        .poll(() => page.evaluate(() => !!(window as any).sceneViewInstance._asset), { timeout: 30_000 })
        .toBe(true);
    } catch (_) {
      // Default model never arrived (offline runner) — the bench still works.
    }

    // Shadow test bench in a fresh Scene on the demo's live engine/view: the
    // demo's own IBL, a ground slab, a caster on it, and ONE sun light built
    // with `.castShadows(true).shadowOptions({ mapSize })` — the issue's
    // snippet verbatim. A fresh scene is needed because Filament honours only
    // the first directional light of a scene, and the demo's non-shadowing sun
    // is not reachable from outside `SceneView.create()`. Both primitives go
    // through the demo's gltfio path (lit ubershader, receives shadows).
    await page.evaluate(() => {
      const F = (window as any).Filament;
      const sv = (window as any).sceneViewInstance;
      const engine = sv._engine;
      sv.setAutoRotate(false);
      sv._angle = 0.785;
      sv._orbitRadius = 5.5;
      sv._orbitHeight = 3.2;
      sv._orbitTarget = [0, -0.3, 0];

      const previous = sv._scene;
      const scene = engine.createScene();
      try { const ibl = previous.getIndirectLight(); if (ibl) scene.setIndirectLight(ibl); } catch (_) { /* no IBL yet */ }
      sv._view.setScene(scene);
      sv._scene = scene;
      sv.createBox([0, -0.6, 0], [7, 0.1, 7], [0.4, 0.4, 0.4, 1]);
      sv.createBox([0, -0.1, 0], [0.9, 0.9, 0.9], [0.9, 0.35, 0.2, 1]);
      // Keep the sun-lit slab out of clipping so the shadow keeps its contrast.
      sv._camera.setExposure(16, 1 / 125, 40);

      const sun = F.EntityManager.get().create();
      F.LightManager.Builder(F.LightManager$Type.SUN)
        .color([0.98, 0.92, 0.89])
        .intensity(110_000)
        .direction([0.6, -1.0, -0.8])
        .castShadows(true)
        .shadowOptions({ mapSize: 64 })
        .build(engine, sun);
      scene.addEntity(sun);
      (window as any).__shadowSun = sun;
    });
    const withSun = (fn: string) =>
      page.evaluate((src) => {
        const sv = (window as any).sceneViewInstance;
        const lm = sv._engine.getLightManager();
        const inst = lm.getInstance((window as any).__shadowSun);
        new Function('lm', 'inst', src)(lm, inst);
      }, fn);

    const canvas = page.locator('#scene-canvas');
    const box = (await canvas.boundingBox())!;
    const clip = { x: box.x, y: box.y, width: box.width, height: box.height };
    const shot = (path?: string) => page.screenshot({ type: 'png', clip, path });

    await page.waitForTimeout(1500);
    await screencast.chapter('mapSize 64', 'shadow map 64x64 — blocky shadow');
    const coarse = await shot('test-results/shadow-options-mapsize-64.png');
    await page.waitForTimeout(300);
    const coarseAgain = await shot();

    // Control: same light with shadow casting off, so the test proves a shadow
    // exists at all before measuring how its resolution changes it.
    await withSun('lm.setShadowCaster(inst, false)');
    await page.waitForTimeout(1500);
    await screencast.chapter('no shadow', 'setShadowCaster(false) — control frame');
    const noShadow = await shot('test-results/shadow-options-no-shadow.png');

    // Retune the SAME light through the instance setter.
    await withSun('lm.setShadowCaster(inst, true); lm.setShadowOptions(inst, { mapSize: 4096 })');
    await page.waitForTimeout(1500);
    await screencast.chapter('mapSize 4096', 'shadow map 4096x4096 — crisp shadow');
    const fine = await shot('test-results/shadow-options-mapsize-4096.png');

    const noise = await pixelDiffRatio(page, coarse, coarseAgain);
    const presence = await pixelDiffRatio(page, noShadow, fine);
    const effect = await pixelDiffRatio(page, coarse, fine);
    console.log(
      `[shadow-options] frame noise=${(noise * 100).toFixed(3)}%  ` +
        `shadow on/off diff=${(presence * 100).toFixed(3)}%  ` +
        `mapSize 64→4096 diff=${(effect * 100).toFixed(3)}%`,
    );

    // Same scene, same camera, same light — only the shadow toggle / the
    // shadow-map resolution moved, so both deltas must sit well above
    // frame-to-frame noise.
    expect(noise, 'scene was still moving between two frames').toBeLessThan(0.01);
    expect(presence, 'the sun casts no visible shadow').toBeGreaterThan(0.003);
    expect(effect, 'mapSize change did not alter the rendered shadow').toBeGreaterThan(0.001);
    expect(effect).toBeGreaterThan(noise * 4);
  });
});

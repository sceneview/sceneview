import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { test, expect, sampleCanvas, assertCanvasContextAlive } from './helpers';

/**
 * SceneView Web — SplatNode (#2646 P2) in-browser gate against the COMPILED
 * Kotlin/JS bundle.
 *
 * This is the BLOCKING web-leg coverage for the Gaussian-Splatting port: it
 * loads the real `sceneview-web.js` artifact (not the hand-authored
 * `js/sceneview.js`) exactly as an npm/CDN consumer does, calls the new
 * `addSplatNode(url)` surface on the byte-identical `rainbow_sphere.ply` the
 * Android splat-preview demo ships (8000 gaussians), and asserts:
 *
 *   1. `addSplatNode(...)` RESOLVES — the splat material (`splat_web.filamat`,
 *      compiled with the web `filamentWeb` matc) actually loads and the
 *      instanced renderable builds on the WebGL2 backend.
 *   2. The cloud renders NON-BLANK — a real signal that the vertex-texture
 *      fetch + premultiplied-alpha blend produce visible pixels.
 *   3. The intra-batch alpha blend stays STABLE across a slow camera orbit —
 *      the web port of the P1b device gate ("no splat popping across an
 *      orbit"): every pose renders non-blank and the WebGL context never dies
 *      as the painter's sort re-uploads the data textures each move.
 *
 * The bundle + a version-matched filament.js/.wasm are staged into
 * `site/kotlin-bundle/` by `.claude/scripts/web-bundle-smoke.sh`. When they are
 * NOT staged (the lean node-only `device-qa.sh --platform=web` leg has no JDK)
 * the whole describe self-skips — the real gate runs in the `web-desktop` CI
 * job (gradle + node) and locally via that script.
 */

const BUNDLE_PATH = join(__dirname, '..', 'site', 'kotlin-bundle', 'sceneview-web.js');
const BUNDLE_STAGED = existsSync(BUNDLE_PATH);

test.describe('SceneView Kotlin/JS bundle — SplatNode (#2646)', () => {
  test.skip(
    !BUNDLE_STAGED,
    'Kotlin bundle not staged at site/kotlin-bundle/sceneview-web.js — ' +
      'run `bash .claude/scripts/web-bundle-smoke.sh` (builds ' +
      ':sceneview-web:jsBrowserProductionWebpack and stages the artifacts).',
  );

  test('addSplatNode() resolves, renders the cloud, and stays stable in orbit', async ({ page }) => {
    test.slow(); // splat parse + 8000-instance upload on software WebGL is heavy.
    const pageErrors: string[] = [];
    page.on('pageerror', (err) => pageErrors.push(err.message));

    await page.goto('/kotlin-bundle/splat.html');

    // The bundle must register the global API.
    await expect
      .poll(() => page.evaluate(() => typeof (window as any).sceneview?.createViewer), {
        timeout: 15_000,
        message: 'window.sceneview.createViewer was never registered by the bundle',
      })
      .toBe('function');

    // The viewer + splat load must SETTLE (never hang). `no-splat-api` is the
    // fixture's own signal that the viewer instance lacks `addSplatNode` — i.e.
    // the new #2646 surface did not survive `@JsExport` into the bundle.
    await expect
      .poll(() => page.evaluate(() => (window as any).__smoke?.status), {
        timeout: 30_000,
        message: 'createViewer() never settled — the Promise hung',
      })
      .not.toBe('pending');

    const smoke = await page.evaluate(() => (window as any).__smoke);
    expect(
      smoke.status,
      `viewer settled as "${smoke.status}"${smoke.error ? ` (${smoke.error})` : ''} — ` +
        'expected "resolved" (a "no-splat-api" here means addSplatNode was not exported).',
    ).toBe('resolved');

    // The new splat surface must exist on the returned viewer instance.
    const hasSplatApi = await page.evaluate(
      () => typeof (window as any).__sv?.addSplatNode === 'function',
    );
    expect(hasSplatApi, 'viewer instance is missing the addSplatNode() method').toBe(true);

    // No swallowed init crash, no uncaught page error.
    const initErrors: string[] = await page.evaluate(() => (window as any).__initErrors ?? []);
    expect(initErrors, 'SceneView.create() logged a Filament init failure.').toEqual([]);
    expect(pageErrors, `uncaught page errors: ${pageErrors.join(' | ')}`).toEqual([]);

    await assertCanvasContextAlive(page, 'splat bundle addSplatNode');

    // The splat cloud must finish parsing + uploading and be in the scene.
    await expect
      .poll(() => page.evaluate(() => (window as any).__smoke?.splatLoaded === true), {
        timeout: 30_000,
        message: 'addSplatNode() never resolved — the splat cloud never loaded through the bundle',
      })
      .toBe(true);

    // Let a few frames upload + present.
    await page.waitForTimeout(1500);

    // (2) NON-BLANK: the rainbow sphere fills the frame, so sample 'full'.
    const first = await sampleCanvas(page, 'full');
    expect(
      first.hasContent,
      `splat cloud rendered blank (luminance variance ${first.variance.toFixed(1)} — ` +
        'the material did not draw visible pixels on WebGL2).',
    ).toBe(true);

    // (3) BLEND-STABILITY GATE — slow orbit, sample every pose. The painter's
    // sort re-uploads the two data textures on each camera move; a broken blend
    // or a lost context would blank a pose. Port of the P1b Android gate.
    const POSES = 8; // ~full revolution in 45° steps
    const DISTANCE = 1.6; // frames the ~0.5 m-radius sphere
    const PHI = 75 * (Math.PI / 180);
    const variances: number[] = [first.variance];
    for (let i = 1; i <= POSES; i++) {
      const theta = (i / POSES) * 2 * Math.PI;
      await page.evaluate(
        (args: { t: number; p: number; d: number }) => (window as any).__orbitTo(args.t, args.p, args.d),
        { t: theta, p: PHI, d: DISTANCE },
      );
      // A few frames for the orbit controller to move + the re-sort to upload.
      await page.waitForTimeout(400);
      await assertCanvasContextAlive(page, `splat orbit pose ${i}`);
      const s = await sampleCanvas(page, 'full');
      expect(
        s.hasContent,
        `splat cloud went blank at orbit pose ${i}/${POSES} ` +
          `(theta=${theta.toFixed(2)} rad, variance ${s.variance.toFixed(1)}) — ` +
          'blend instability / popping across the orbit.',
      ).toBe(true);
      variances.push(s.variance);
    }

    // Numeric evidence for the QA log: the per-pose luminance variance series.
    // Every entry cleared the non-blank floor above; the spread just documents
    // that the cloud stays substantively rendered through the whole revolution.
    const min = Math.min(...variances).toFixed(1);
    const max = Math.max(...variances).toFixed(1);
    console.log(
      `[splat-bundle] blend-stability: ${variances.length} poses rendered, ` +
        `variance ∈ [${min}, ${max}] = [${variances.map((v) => v.toFixed(0)).join(', ')}]`,
    );

    // Final canvas screenshot as visual proof for the QA report.
    await page
      .locator('#scene-canvas')
      .screenshot({ path: 'test-results/splat_bundle_canvas.png' });
  });
});

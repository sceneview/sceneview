import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { test, expect, sampleCanvas, assertCanvasContextAlive } from './helpers';

/**
 * SceneView Web — 3MF (#3482) in-browser gate against the COMPILED Kotlin/JS
 * bundle.
 *
 * ChatGPT emits a `.3mf` when it turns a drawing into a printable model, and so
 * does every slicer. Nothing on the web opened one in 3D. `sceneview-web` now
 * converts it in `loadModel`, through the shared KMP `ThreeMfLoader` — the very
 * same code Android's `ModelLoader` runs — so there is no second parser and no
 * new API: a `.3mf` URL goes where a `.glb` URL goes.
 *
 * This is the BLOCKING web-leg coverage for that claim. It loads the real
 * `sceneview-web.js` artifact (not the hand-authored `js/sceneview.js`) exactly
 * as an npm/CDN consumer does, points it at two real 3MF files — a byte-identical
 * copy of the fixture the `sceneview-core` unit tests parse (a 20 mm cube, for the
 * byte-level surface) and a 150 mm print-scale part (the one rendered) — and asserts:
 *
 *   1. The byte surface works: `isThreeMf(bytes)` is `true` and
 *      `threeMfToGlb(bytes)` returns something starting with the `glTF` magic
 *      — i.e. the conversion survived `@JsExport` into the bundle and produces
 *      a GLB, not a truncated buffer.
 *   2. `loadModel('….3mf')` RESOLVES — the converted GLB is accepted by
 *      Filament.js `createAsset` on the WebGL2 backend.
 *   3. The part renders NON-BLANK once framed, and the WebGL context survives a
 *      camera orbit around it — at 0.5 m from a part the file declares as 150 mm,
 *      which is only the right distance if the mm→m unit scaling ran. An unscaled
 *      model would be 150 m across, with the camera sitting inside it.
 *
 * The bundle + a version-matched filament.js/.wasm are staged into
 * `site/kotlin-bundle/` by `.claude/scripts/web-bundle-smoke.sh`. When they are
 * NOT staged (the lean node-only `device-qa.sh --platform=web` leg has no JDK)
 * the whole describe self-skips — the real gate runs in the `web-desktop` CI
 * job (gradle + node) and locally via that script.
 */

const BUNDLE_PATH = join(__dirname, '..', 'site', 'kotlin-bundle', 'sceneview-web.js');
const BUNDLE_STAGED = existsSync(BUNDLE_PATH);

test.describe('SceneView Kotlin/JS bundle — 3MF (#3482)', () => {
  test.skip(
    !BUNDLE_STAGED,
    'Kotlin bundle not staged at site/kotlin-bundle/sceneview-web.js — ' +
      'run `bash .claude/scripts/web-bundle-smoke.sh` (builds ' +
      ':sceneview-web:jsBrowserProductionWebpack and stages the artifacts).',
  );

  test('loadModel() takes a .3mf URL, converts it, and renders it', async ({ page }) => {
    const pageErrors: string[] = [];
    page.on('pageerror', (err) => pageErrors.push(err.message));

    await page.goto('/kotlin-bundle/threemf.html');

    // The bundle must register the global API.
    await expect
      .poll(() => page.evaluate(() => typeof (window as any).sceneview?.createViewer), {
        timeout: 15_000,
        message: 'window.sceneview.createViewer was never registered by the bundle',
      })
      .toBe('function');

    // (1) BYTE SURFACE — `isThreeMf` / `threeMfToGlb` reached the bundle and work.
    // Asserted first because it needs no GPU: if the whole WebGL leg is dead on
    // this host, this still tells you whether the converter shipped.
    await expect
      .poll(() => page.evaluate(() => (window as any).__smoke?.sniffed), {
        timeout: 20_000,
        message: 'sceneview.isThreeMf(bytes) never returned — is the export missing?',
      })
      .toBe(true);

    const convert = await page.evaluate(() => (window as any).__smoke);
    expect(
      convert.convertError,
      `sceneview.threeMfToGlb() threw: ${convert.convertError}`,
    ).toBeNull();
    expect(
      convert.glbMagic,
      `threeMfToGlb() returned bytes starting "${convert.glbMagic}" — expected the GLB magic.`,
    ).toBe('glTF');

    // (2) The viewer + model load must SETTLE (never hang).
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
        'expected "resolved".',
    ).toBe('resolved');

    await expect
      .poll(() => page.evaluate(() => (window as any).__smoke?.modelLoaded === true), {
        timeout: 30_000,
        message:
          "loadModel('printed-icosahedron.3mf') never resolved — the converted GLB " +
          'was not accepted by Filament.js',
      })
      .toBe(true);

    expect(pageErrors, `uncaught page errors: ${pageErrors.join(' | ')}`).toEqual([]);
    await assertCanvasContextAlive(page, '3mf bundle loadModel');

    // Let the framing settle and a few frames present.
    await page.waitForTimeout(1500);

    // (3) NON-BLANK. `'full'` rather than `'centre'`: the auto-centre pass frames
    // the part against its own bounding box while the camera keeps orbiting, so
    // the subject is not pinned to the canvas centre. Same variance methodology,
    // an equally hard blank-canvas signal — the choice the splat gate makes too.
    const first = await sampleCanvas(page, 'full');
    expect(
      first.hasContent,
      `the 3MF part rendered blank (luminance variance ${first.variance.toFixed(1)}) — ` +
        'the converted GLB produced no visible pixels on WebGL2.',
    ).toBe(true);

    // Orbit: every facet of the two-coloured part must keep drawing, and the
    // context must survive. 0.5 m frames a 150 mm print — a distance that only
    // works because the mm→m scaling ran; an unscaled part would be 150 m across
    // and the camera would sit inside it.
    const POSES = 4;
    const DISTANCE = 0.5;
    const PHI = 70 * (Math.PI / 180);
    const variances: number[] = [first.variance];
    for (let i = 1; i <= POSES; i++) {
      const theta = (i / POSES) * 2 * Math.PI;
      await page.evaluate(
        (args: { t: number; p: number; d: number }) =>
          (window as any).__orbitTo(args.t, args.p, args.d),
        { t: theta, p: PHI, d: DISTANCE },
      );
      await page.waitForTimeout(400);
      await assertCanvasContextAlive(page, `3mf orbit pose ${i}`);
      const s = await sampleCanvas(page, 'full');
      expect(
        s.hasContent,
        `the 3MF part went blank at orbit pose ${i}/${POSES} ` +
          `(theta=${theta.toFixed(2)} rad, variance ${s.variance.toFixed(1)}).`,
      ).toBe(true);
      variances.push(s.variance);
    }

    console.log(
      `[threemf-bundle] bundle v${smoke.version} · ${variances.length} poses rendered, ` +
        `variance = [${variances.map((v) => v.toFixed(0)).join(', ')}]`,
    );

    // Visual proof for the QA report.
    await page
      .locator('#scene-canvas')
      .screenshot({ path: 'test-results/threemf_bundle_canvas.png' });
  });
});

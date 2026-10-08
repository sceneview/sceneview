import { existsSync } from 'node:fs';
import { join } from 'node:path';
import type { Page } from '@playwright/test';
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
 *      as the painter's sort re-uploads only the order texture each move.
 *
 * Two generated clouds then pin what "non-blank" cannot (#4046):
 *
 *   4. COLOUR — one opaque splat of a known display colour reads back at the
 *      canvas centre within 2 levels: the material's sRGB + inverse-Filmic
 *      stage and the view's Filmic tone mapping cancel out.
 *   5. ORDER ACROSS BATCHES — a 70 000-splat cloud (two draw batches; indices
 *      above 65 535 need the order texture's third byte) shows its nearest
 *      splat on top from the front AND from the back, so the re-sort, the
 *      index decode, `instanceOffset` and the batch blend order all hold.
 *
 * The bundle + a version-matched filament.js/.wasm are staged into
 * `site/kotlin-bundle/` by `.claude/scripts/web-bundle-smoke.sh`. When they are
 * NOT staged (the lean node-only `device-qa.sh --platform=web` leg has no JDK)
 * the whole describe self-skips — the real gate runs in the `web-desktop` CI
 * job (gradle + node) and locally via that script.
 */

const BUNDLE_PATH = join(__dirname, '..', 'site', 'kotlin-bundle', 'sceneview-web.js');
const BUNDLE_STAGED = existsSync(BUNDLE_PATH);

/** SH degree-0 basis: an INRIA `.ply` stores colour as `(c - 0.5) / SH_C0`. */
const SH_C0 = 0.28209479177387814;

interface TestSplat {
  position: [number, number, number];
  /** Display-referred colour, 0..1. */
  colour: [number, number, number];
  /** Isotropic standard deviation, metres. */
  sigma: number;
}

/** An INRIA 3DGS binary `.ply` of fully opaque, unrotated splats. */
function plyOf(count: number, splatAt: (index: number) => TestSplat): Buffer {
  const properties = ['x', 'y', 'z', 'f_dc_0', 'f_dc_1', 'f_dc_2', 'opacity',
    'scale_0', 'scale_1', 'scale_2', 'rot_0', 'rot_1', 'rot_2', 'rot_3'];
  const header =
    `ply\nformat binary_little_endian 1.0\nelement vertex ${count}\n` +
    properties.map((name) => `property float ${name}\n`).join('') +
    'end_header\n';
  const floats = new Float32Array(count * properties.length);
  for (let i = 0; i < count; i++) {
    const { position, colour, sigma } = splatAt(i);
    const logScale = Math.log(sigma);
    floats.set(
      [
        ...position,
        ...colour.map((c) => (c - 0.5) / SH_C0),
        30, // logit opacity: sigmoid(30) is 1 in float32
        logScale, logScale, logScale,
        1, 0, 0, 0, // rot_0 is w: identity
      ],
      i * properties.length,
    );
  }
  return Buffer.concat([Buffer.from(header, 'ascii'), Buffer.from(floats.buffer)]);
}

/**
 * Loads `splat.html` on a generated cloud served at `/__generated.ply` and waits
 * until the splat node is in the scene.
 */
async function openGeneratedCloud(page: Page, ply: Buffer, theme: 'light' | 'dark'): Promise<void> {
  // A pathname predicate, not a glob: the page URL carries the same path in its query.
  await page.route(
    (url) => url.pathname === '/__generated.ply',
    (route) => route.fulfill({ body: ply, contentType: 'application/octet-stream' }),
  );
  await page.goto(`/kotlin-bundle/splat.html?asset=rainbow&theme=${theme}&assetUrl=/__generated.ply`);
  await expect
    .poll(() => page.evaluate(() => (window as any).__smoke?.status), { timeout: 45_000 })
    .not.toBe('pending');
  const smoke = await page.evaluate(() => (window as any).__smoke);
  expect(smoke.status, `viewer settled as "${smoke.status}" (${smoke.error ?? 'no error'})`).toBe('resolved');
  await expect
    .poll(() => page.evaluate(() => (window as any).__smoke?.splatLoaded === true), {
      timeout: 45_000,
      message: 'addSplatNode() never resolved on the generated cloud',
    })
    .toBe(true);
}

/** Moves the orbit camera and waits for the re-sort and a few presented frames. */
async function orbitTo(page: Page, theta: number, phi: number, distance: number): Promise<void> {
  await page.evaluate(
    (args: { t: number; p: number; d: number }) => (window as any).__orbitTo(args.t, args.p, args.d),
    { t: theta, p: phi, d: distance },
  );
  await page.waitForTimeout(1000);
}

/**
 * Mean 8-bit colour of the 9x9 block at the canvas centre, from a lossless
 * screenshot (the same reason as `sampleCanvas`: `readPixels` is undefined after
 * present). The mean absorbs Filament's +-1 level dithering.
 */
async function centreColour(page: Page): Promise<[number, number, number]> {
  const box = await page.locator('#scene-canvas').boundingBox();
  if (!box) throw new Error('#scene-canvas has no bounding box');
  const side = 9;
  const png = await page.screenshot({
    type: 'png',
    clip: {
      x: Math.round(box.x + box.width / 2) - (side - 1) / 2,
      y: Math.round(box.y + box.height / 2) - (side - 1) / 2,
      width: side,
      height: side,
    },
  });
  return page.evaluate(async (uri: string) => {
    const img = new Image();
    img.src = uri;
    await img.decode();
    const c = document.createElement('canvas');
    c.width = img.width;
    c.height = img.height;
    const ctx = c.getContext('2d')!;
    ctx.drawImage(img, 0, 0);
    const { data } = ctx.getImageData(0, 0, c.width, c.height);
    const sum = [0, 0, 0];
    for (let i = 0; i < data.length; i += 4) {
      sum[0] += data[i];
      sum[1] += data[i + 1];
      sum[2] += data[i + 2];
    }
    const n = data.length / 4;
    return [sum[0] / n, sum[1] / n, sum[2] / n] as [number, number, number];
  }, 'data:image/png;base64,' + png.toString('base64'));
}

function expectColour(
  measured: [number, number, number],
  expected: [number, number, number],
  tolerance: number,
  context: string,
): void {
  const text = `${context}: measured (${measured.map((v) => v.toFixed(1)).join(', ')}), ` +
    `expected (${expected.map((v) => v.toFixed(1)).join(', ')}) +-${tolerance}`;
  for (let channel = 0; channel < 3; channel++) {
    expect(Math.abs(measured[channel] - expected[channel]), text).toBeLessThanOrEqual(tolerance);
  }
}

test.describe('SceneView Kotlin/JS bundle — SplatNode (#2646)', () => {
  test.skip(
    !BUNDLE_STAGED,
    'Kotlin bundle not staged at site/kotlin-bundle/sceneview-web.js — ' +
      'run `bash .claude/scripts/web-bundle-smoke.sh` (builds ' +
      ':sceneview-web:jsBrowserProductionWebpack and stages the artifacts).',
  );

  for (const theme of ['light', 'dark'] as const) {
    test(`addSplatNode() resolves, renders the cloud, and stays stable in orbit (${theme})`, async ({ page }) => {
      test.slow(); // splat parse + 8000-instance upload on software WebGL is heavy.
      const pageErrors: string[] = [];
      page.on('pageerror', (err) => pageErrors.push(err.message));

      await page.goto(`/kotlin-bundle/splat.html?asset=rainbow&theme=${theme}`);

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
      // sort re-uploads the order texture on each camera move; a broken blend
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
        .screenshot({ path: `test-results/splat_bundle_canvas_${theme}.png` });
    });
  }

  test('an opaque splat of a known colour reads back at the centre within 2 levels', async ({ page }) => {
    test.slow();
    const pageErrors: string[] = [];
    page.on('pageerror', (err) => pageErrors.push(err.message));

    // Saturated and unequal on purpose: a wrong curve or a channel swap moves it far.
    const colour: [number, number, number] = [0.9, 0.3, 0.1];
    const ply = plyOf(1, () => ({ position: [0, 0, 0], colour, sigma: 0.4 }));
    await openGeneratedCloud(page, ply, 'dark');
    await orbitTo(page, 0, Math.PI / 2, 1.0);

    await assertCanvasContextAlive(page, 'known-colour splat');
    const measured = await centreColour(page);
    console.log(`[splat-bundle] known colour: centre = (${measured.map((v) => v.toFixed(1)).join(', ')})`);
    expectColour(
      measured,
      [colour[0] * 255, colour[1] * 255, colour[2] * 255],
      2,
      'display-referred splat colour at the canvas centre',
    );
    expect(pageErrors, `uncaught page errors: ${pageErrors.join(' | ')}`).toEqual([]);
  });

  test('a 70 000-splat cloud draws its two batches in painter order, front and back', async ({ page }) => {
    test.slow();
    const pageErrors: string[] = [];
    page.on('pageerror', (err) => pageErrors.push(err.message));

    // Index 0 is a red splat at z = +0.1, the last index a blue one at z = -0.1; both
    // are opaque and cover the canvas centre. Every splat in between is a small green
    // one on a ring in the z = 0 plane: off the centre, and between the two in depth
    // from either side. 70 000 > 65 535, so the cloud takes two draw batches and the
    // blue index (69 999) only decodes with the order texture's third byte.
    const COUNT = 70_000;
    const RED: [number, number, number] = [0.9, 0.1, 0.1];
    const BLUE: [number, number, number] = [0.1, 0.1, 0.9];
    const ply = plyOf(COUNT, (i) => {
      if (i === 0) return { position: [0, 0, 0.1], colour: RED, sigma: 0.3 };
      if (i === COUNT - 1) return { position: [0, 0, -0.1], colour: BLUE, sigma: 0.3 };
      const angle = (i / COUNT) * 2 * Math.PI * 97; // 97 turns: spreads the ring evenly
      const radius = 0.3 + 0.2 * ((i % 1000) / 1000);
      return {
        position: [radius * Math.cos(angle), radius * Math.sin(angle), 0],
        colour: [0.1, 0.9, 0.1],
        sigma: 0.002,
      };
    });
    await openGeneratedCloud(page, ply, 'light');

    const DISTANCE = 1.5;
    const to255 = (c: [number, number, number]): [number, number, number] => [c[0] * 255, c[1] * 255, c[2] * 255];

    // Front (camera on +z): red is nearest, so it sits in the LAST slot — second batch —
    // and must cover blue, which the first batch draws.
    await orbitTo(page, 0, Math.PI / 2, DISTANCE);
    await assertCanvasContextAlive(page, 'multi-batch front view');
    const front = await centreColour(page);
    expectColour(front, to255(RED), 2, 'front view, nearest splat (index 0, last slot)');

    // Back (camera on -z): the re-sort puts blue (index 69 999) in the last slot.
    await orbitTo(page, Math.PI, Math.PI / 2, DISTANCE);
    await assertCanvasContextAlive(page, 'multi-batch back view');
    const back = await centreColour(page);
    expectColour(back, to255(BLUE), 2, 'back view, nearest splat (index 69 999, last slot)');

    console.log(
      `[splat-bundle] multi-batch order: front = (${front.map((v) => v.toFixed(1)).join(', ')}), ` +
        `back = (${back.map((v) => v.toFixed(1)).join(', ')})`,
    );
    expect(pageErrors, `uncaught page errors: ${pageErrors.join(' | ')}`).toEqual([]);
  });
});

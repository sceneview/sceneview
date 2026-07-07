import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { test, expect } from './helpers';

/**
 * SceneView Web — `@JsExport` `NodeHandle` in-browser test (#2024 slice 3 / P4).
 *
 * The rest of the web-demo drives the hand-authored `js/sceneview.js`; this
 * spec drives the COMPILED Kotlin/JS bundle's exported node surface the way a
 * real plain-JS consumer does. It creates and addresses a node via the
 * `window.sceneview` `NodeHandle` factories (`addCubeNode` / `addNode` /
 * `addModelNode`), mutates a transform (`setPosition` / `setScaleUniform`) and
 * a hierarchy (`addChild`), and asserts the effect through the handle's own
 * `getWorldPosition` — the exported API round-tripping against the real
 * Filament `TransformManager` composition (which `jsTest`/Karma cannot exercise
 * because it stubs the Filament WASM module).
 *
 * Staged by `.claude/scripts/web-bundle-smoke.sh` alongside `kotlin-bundle`;
 * self-skips when the bundle is not built (same rationale as
 * `kotlin-bundle.spec.ts`).
 */

const BUNDLE_PATH = join(__dirname, '..', 'site', 'kotlin-bundle', 'sceneview-web.js');
const BUNDLE_STAGED = existsSync(BUNDLE_PATH);

test.describe('SceneView Kotlin/JS bundle — NodeHandle export', () => {
  test.skip(
    !BUNDLE_STAGED,
    'Kotlin bundle not staged at site/kotlin-bundle/sceneview-web.js — ' +
      'run `bash .claude/scripts/web-bundle-smoke.sh`.',
  );

  test('NodeHandle factories create + address a node, transforms compose (#2024 P4)', async ({
    page,
  }) => {
    const pageErrors: string[] = [];
    page.on('pageerror', (err) => pageErrors.push(err.message));

    await page.goto('/kotlin-bundle/index.html');

    // Wait for the fixture's own createViewer() to resolve — `window.__sv` is
    // the live SceneViewer the fixture exposes.
    await expect
      .poll(() => page.evaluate(() => (window as any).__smoke?.status), { timeout: 30_000 })
      .toBe('resolved');

    const result = await page.evaluate(() => {
      const sv = (window as any).__sv;
      if (!sv || typeof sv.addCubeNode !== 'function') {
        return { error: 'NodeHandle factories missing on the SceneViewer' };
      }
      let step = 'addCubeNode';
      try {
        // A cube node, addressed after create() — the thing the builder DSL
        // could never do.
        const cube = sv.addCubeNode(1.0);
        if (typeof cube.setPosition !== 'function') return { error: 'NodeHandle.setPosition missing' };

        step = 'setPosition';
        cube.setPosition(2, 0, 0);
        step = 'setScaleUniform';
        cube.setScaleUniform(1.5);
        step = 'cube.getWorldPosition (root)';
        const cubeWorld = cube.getWorldPosition();

        // An empty pivot node translated on X, with the cube re-parented under
        // it — the cube's world X must compose (pivot + local).
        step = 'addNode';
        const pivot = sv.addNode();
        step = 'pivot.setPosition';
        pivot.setPosition(10, 0, 0);
        step = 'pivot.addChild(cube)';
        pivot.addChild(cube);
        step = 'cube.getWorldPosition (under pivot)';
        const cubeWorldUnderPivot = cube.getWorldPosition();

        // visibility flag round-trips through the handle.
        step = 'setVisible';
        cube.setVisible(false);
        const hiddenFlag = cube.visible;
        cube.setVisible(true);
        const shownFlag = cube.visible;

        // destroy is idempotent and does not throw.
        step = 'destroy';
        const spare = sv.addSphereNode(0.5);
        spare.destroy();
        spare.destroy();

        return {
          cubeWorldX: cubeWorld[0],
          cubeWorldLen: cubeWorld.length,
          cubeWorldXUnderPivot: cubeWorldUnderPivot[0],
          hiddenFlag,
          shownFlag,
        };
      } catch (e: any) {
        return { error: `${step}: ${e?.name ?? ''} ${e?.message ?? String(e)}` };
      }
    });

    expect(
      (result as any).error,
      `NodeHandle probe failed: ${(result as any).error}`,
    ).toBeUndefined();

    // Root cube: local X = 2 → world X = 2.
    expect((result as any).cubeWorldX).toBeCloseTo(2, 3);
    expect((result as any).cubeWorldLen).toBe(3);
    // Under a pivot translated to X=10, the cube's local X=2 composes to world X=12.
    expect((result as any).cubeWorldXUnderPivot).toBeCloseTo(12, 3);
    // Visibility flag flips through the handle.
    expect((result as any).hiddenFlag).toBe(false);
    expect((result as any).shownFlag).toBe(true);

    expect(pageErrors, `uncaught page errors: ${pageErrors.join(' | ')}`).toEqual([]);
  });

  test('addModelNode resolves a NodeHandle and its transform composes (#2024 P4)', async ({
    page,
  }) => {
    await page.goto('/kotlin-bundle/index.html');
    await expect
      .poll(() => page.evaluate(() => (window as any).__smoke?.status), { timeout: 30_000 })
      .toBe('resolved');

    const result = await page.evaluate(async () => {
      const sv = (window as any).__sv;
      if (!sv || typeof sv.addModelNode !== 'function') {
        return { error: 'addModelNode missing on the SceneViewer' };
      }
      try {
        // Load the same local GLB the fixture uses — network-free.
        const model = await sv.addModelNode('../models/khronos_damaged_helmet.glb');
        if (typeof model.setPosition !== 'function') return { error: 'resolved value is not a NodeHandle' };
        model.setPosition(3, 1, 0);
        const world = model.getWorldPosition();
        return { worldX: world[0], worldY: world[1] };
      } catch (e: any) {
        return { error: `${e?.name ?? ''} ${e?.message ?? String(e)}` };
      }
    });

    expect((result as any).error, `addModelNode probe failed: ${(result as any).error}`).toBeUndefined();
    expect((result as any).worldX).toBeCloseTo(3, 3);
    expect((result as any).worldY).toBeCloseTo(1, 3);
  });
});

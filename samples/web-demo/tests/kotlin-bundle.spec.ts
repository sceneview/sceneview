import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { test, expect, sampleCanvas, assertCanvasContextAlive } from './helpers';

/**
 * SceneView Web — COMPILED Kotlin/JS bundle in-browser smoke test (#2410).
 *
 * The rest of the web-demo (render.spec / catalog.spec) drives the
 * hand-authored plain-JS `js/sceneview.js`. That left the actual published
 * artifact — the Kotlin/JS bundle shipped to npm + the CDN as
 * `sceneview-web@<v>/sceneview-web.js` — with ZERO in-browser coverage: the
 * `jsTest` (Karma) suite stubs the Filament externals, so an init-path crash
 * that only happens against the real Filament WASM module is invisible to CI.
 *
 * That gap hid a hard init crash: `SceneView.create()` did
 * `js("Filament.EntityManager.get().create()") as Entity`, and the Kotlin `as`
 * cast against the `external class Entity` compiled to `tmp instanceof Entity`.
 * `Entity` is not a runtime constructor in Filament.js (entities are integers),
 * so the right-hand side was `undefined` → `TypeError: Right-hand side of
 * 'instanceof' is not an object`. `SceneView.create()` swallowed it into a
 * `console.error`, so `createViewer().then(...)` never settled and every
 * consumer of the bundle hung forever on a blank canvas.
 *
 * This spec loads the bundle the way a real consumer does (a bare page with
 * `filament.js` + `sceneview-web.js`, see `site/kotlin-bundle/index.html`) and
 * asserts the viewer Promise actually RESOLVES and renders.
 *
 * The bundle + a version-matched filament.js/.wasm are staged into
 * `site/kotlin-bundle/` by `.claude/scripts/web-bundle-smoke.sh` (build
 * `:sceneview-web:jsBrowserProductionWebpack` + copy). When they are NOT staged
 * — e.g. the lean node-only `device-qa.sh --platform=web` leg that has no JDK —
 * the whole describe self-skips so it never red-blocks a context that simply
 * could not produce the artifact. The real gate runs in the `web-desktop` CI
 * job (gradle + node) and locally via that script.
 */

const BUNDLE_PATH = join(__dirname, '..', 'site', 'kotlin-bundle', 'sceneview-web.js');
const BUNDLE_STAGED = existsSync(BUNDLE_PATH);

test.describe('SceneView Kotlin/JS bundle — browser init', () => {
  test.skip(
    !BUNDLE_STAGED,
    'Kotlin bundle not staged at site/kotlin-bundle/sceneview-web.js — ' +
      'run `bash .claude/scripts/web-bundle-smoke.sh` (builds ' +
      ':sceneview-web:jsBrowserProductionWebpack and stages the artifacts).',
  );

  test('createViewer() resolves, renders, and never throws during init', async ({ page }) => {
    const consoleErrors: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });
    const pageErrors: string[] = [];
    page.on('pageerror', (err) => pageErrors.push(err.message));

    await page.goto('/kotlin-bundle/index.html');

    // The global API must be registered by the bundle (`window.sceneview`).
    await expect
      .poll(() => page.evaluate(() => typeof (window as any).sceneview?.createViewer), {
        timeout: 15_000,
        message: 'window.sceneview.createViewer was never registered by the bundle',
      })
      .toBe('function');

    // CORE REGRESSION: the viewer Promise must SETTLE. The bug left it
    // 'pending' forever (createViewer().then() hung), so this poll timing out
    // *is* the reproduction of the hang — surface it with a clear message
    // rather than a bare assertion timeout.
    await expect
      .poll(() => page.evaluate(() => (window as any).__smoke?.status), {
        timeout: 30_000,
        message:
          'createViewer() never settled — the Promise hung (init crash swallowed ' +
          'into console.error). This is the `as Entity` → `instanceof undefined` regression.',
      })
      .not.toBe('pending');

    const smoke = await page.evaluate(() => (window as any).__smoke);

    // It must RESOLVE — not reject, and not fail to expose the API.
    expect(
      smoke.status,
      `createViewer() settled as "${smoke.status}"${smoke.error ? ` (${smoke.error})` : ''} ` +
        '— expected "resolved".',
    ).toBe('resolved');

    // The init-crash signature must never have been logged.
    const initErrors: string[] = await page.evaluate(() => (window as any).__initErrors ?? []);
    expect(
      initErrors,
      'SceneView.create() logged a Filament init failure — the bundle cannot ' +
        'initialise in a browser.',
    ).toEqual([]);

    // No uncaught page error escaped either.
    expect(pageErrors, `uncaught page errors: ${pageErrors.join(' | ')}`).toEqual([]);

    // The WebGL2 context the engine created must be live.
    await assertCanvasContextAlive(page, 'kotlin bundle createViewer');

    // Wait for the local model to finish loading, then let a few frames render.
    await expect
      .poll(() => page.evaluate(() => (window as any).__smoke?.modelLoaded === true), {
        timeout: 30_000,
        message: 'local model never finished loading through the bundle',
      })
      .toBe(true);
    await page.waitForTimeout(1500);

    // Hard blank-canvas signal — a real lit model has wide luminance variance.
    // `--enable-unsafe-swiftshader` (playwright.config.ts) gives headless CI a
    // genuine software-rasterised context, so this is a true signal. Sample the
    // FULL canvas (not the centre block): this smoke test guards "the bundle
    // initialised and rendered visible content", and a bare 640×480 fixture
    // without the demo's full framing setup does not guarantee the subject is
    // dead-centre. Per `sampleCanvas`'s own docs, 'full' is an equally hard
    // blank signal, just framing-agnostic.
    const canvas = page.locator('#scene-canvas');
    await canvas.screenshot({ path: 'test-results/kotlin_bundle_canvas.png' });
    const { hasContent, headlessGpuOk } = await sampleCanvas(page, 'full');
    expect(headlessGpuOk, 'canvas element is missing or zero-sized').toBe(true);
    expect(hasContent, 'canvas is blank — the Kotlin bundle rendered nothing').toBe(true);
  });

  /**
   * #2024 P1 in-browser proof: `TransformManager.setParent` exists on the
   * pinned Filament.js WASM and composes `world = parentWorld * local`.
   *
   * This is the design doc's §7 risk mitigation ("P1 unit-test asserts a
   * 2-entity composition before any node code depends on it") that Karma
   * could never run (no WASM). The node graph (`Node`, `ModelNode`,
   * `GeometryNode`, the delegating `model{}`/`geometry{}` DSL — slices 1+2)
   * re-parents every asset root through this exact call, so this spec is
   * the release gate's early tripwire for a Filament.js version bump that
   * drops or changes it.
   */
  test('TransformManager.setParent composes world transforms (#2024 P1)', async ({ page }) => {
    await page.goto('/kotlin-bundle/index.html');

    // Wait for the bundle's own init to finish so `window.Filament` is the
    // fully-initialised WASM module (not the pre-init loader shim).
    await expect
      .poll(() => page.evaluate(() => (window as any).__smoke?.status), { timeout: 30_000 })
      .toBe('resolved');

    const result = await page.evaluate(() => {
      const F = (window as any).Filament;
      if (!F?.Engine?.create) return { error: 'Filament global not initialised' };

      // A tiny second engine on an offscreen canvas — independent of the
      // viewer's, so this probe can't disturb the rendering assertions above.
      const probeCanvas = document.createElement('canvas');
      probeCanvas.width = 8;
      probeCanvas.height = 8;
      document.body.appendChild(probeCanvas);
      let step = 'Engine.create';
      try {
        const engine = F.Engine.create(probeCanvas);
        step = 'getTransformManager';
        const tm = engine.getTransformManager();
        if (typeof tm.setParent !== 'function') return { error: 'setParent missing' };

        step = 'EntityManager.create';
        const em = F.EntityManager.get();
        const parent = em.create();
        const child = em.create();
        step = 'tm.create';
        tm.create(parent);
        tm.create(child);

        // Parent FIRST, then write both locals — each out-of-transaction
        // setTransform updates the subtree's world transforms immediately.
        step = 'setParent(child, parent)';
        tm.setParent(tm.getInstance(child), tm.getInstance(parent));
        step = 'setTransform(parent)';
        tm.setTransform(tm.getInstance(parent), [1,0,0,0, 0,1,0,0, 0,0,1,0, 2,0,0,1]);
        step = 'setTransform(child)';
        tm.setTransform(tm.getInstance(child),  [1,0,0,0, 0,1,0,0, 0,0,1,0, 3,0,0,1]);
        step = 'getWorldTransform(child)';
        const worldX = tm.getWorldTransform(tm.getInstance(child))[12];

        // Detach. embind REJECTS a JS `null` parent ("null is not a valid
        // TransformManager$Instance") and raw ints are not Entities — the
        // working null INSTANCE is `getInstance()` of an entity that has NO
        // transform component (native instance 0), exactly Android's
        // `setParent(i, 0)`.
        step = 'null-instance via component-less entity';
        const detachSentinel = em.create();
        const nullInstance = tm.getInstance(detachSentinel);
        step = 'setParent(child, nullInstance)';
        tm.setParent(tm.getInstance(child), nullInstance);
        step = 'getWorldTransform(detached)';
        const detachedWorldX = tm.getWorldTransform(tm.getInstance(child))[12];

        return { worldX, detachedWorldX };
      } catch (e: any) {
        return { error: `${step}: ${e?.name ?? ''} ${e?.message ?? String(e)}` };
      }
    });

    expect((result as any).error, `setParent probe failed: ${(result as any).error}`).toBeUndefined();
    // world = parentWorld(x+2) * local(x+3) → x = 5
    expect((result as any).worldX).toBeCloseTo(5, 3);
    // detached → world = local → x = 3
    expect((result as any).detachedWorldX).toBeCloseTo(3, 3);
  });

  /**
   * #2024 P1 in-browser proof (slice 2b): the `LightManager` instance mutators
   * that `LightNode` pushes its config through — `getInstance`, `setIntensity`,
   * `setColor`, `setDirection`, `setPosition` — exist on the pinned Filament.js
   * WASM and round-trip a value.
   *
   * embind REJECTS `null` and raw ints as instances (BindingError), and Karma
   * stubs the Filament externals, so `jsTest` can NEVER validate a
   * `LightManager` binding — only the real WASM module can. `LightNode`'s
   * setters (`nodes/LightNode.kt`) call these on `getInstance(entity)` of a
   * real light entity, so this spec is the gate that proves the binding design
   * BEFORE the node code depends on it (the mandatory embind probe-first rule).
   */
  test('LightManager instance mutators round-trip (#2024 P1 slice 2b)', async ({ page }) => {
    await page.goto('/kotlin-bundle/index.html');
    await expect
      .poll(() => page.evaluate(() => (window as any).__smoke?.status), { timeout: 30_000 })
      .toBe('resolved');

    const result = await page.evaluate(() => {
      const F = (window as any).Filament;
      if (!F?.Engine?.create) return { error: 'Filament global not initialised' };

      const probeCanvas = document.createElement('canvas');
      probeCanvas.width = 8;
      probeCanvas.height = 8;
      document.body.appendChild(probeCanvas);
      let step = 'Engine.create';
      try {
        const engine = F.Engine.create(probeCanvas);
        step = 'getLightManager';
        const lm = engine.getLightManager();
        for (const m of ['getInstance', 'setIntensity', 'getIntensity', 'setColor',
                         'getColor', 'setDirection', 'getDirection', 'setPosition']) {
          if (typeof lm[m] !== 'function') return { error: `LightManager.${m} missing` };
        }

        step = 'EntityManager.create';
        const entity = F.EntityManager.get().create();

        // Build a directional light the exact way SceneView.addLight /
        // LightNode does — enum TYPE object (not a raw int), then build(engine,
        // entity). A bare int for the type throws a BindingError.
        step = 'LightManager.Builder(DIRECTIONAL).build';
        F.LightManager.Builder(F.LightManager$Type.DIRECTIONAL)
          .intensity(50000.0)
          .direction([0.0, -1.0, -0.5])
          .color([1.0, 1.0, 1.0])
          .castShadows(true)
          .build(engine, entity);

        step = 'getInstance';
        const inst = lm.getInstance(entity);

        // Mutate through the SAME instance bindings LightNode's setters use,
        // then read back — proving each embind method accepts the instance and
        // its argument shape (Double for intensity, float3 array for the rest).
        step = 'setIntensity';
        lm.setIntensity(inst, 12345.0);
        const intensity = lm.getIntensity(inst);
        step = 'setColor';
        lm.setColor(inst, [0.25, 0.5, 0.75]);
        const color = lm.getColor(inst);
        step = 'setDirection';
        lm.setDirection(inst, [1.0, 0.0, 0.0]);
        const direction = lm.getDirection(inst);

        return { intensity, color, direction };
      } catch (e: any) {
        return { error: `${step}: ${e?.name ?? ''} ${e?.message ?? String(e)}` };
      }
    });

    expect((result as any).error, `LightManager probe failed: ${(result as any).error}`).toBeUndefined();
    expect((result as any).intensity).toBeCloseTo(12345, 0);
    // color round-trips as a float3 [r,g,b].
    expect((result as any).color[0]).toBeCloseTo(0.25, 2);
    expect((result as any).color[1]).toBeCloseTo(0.5, 2);
    expect((result as any).color[2]).toBeCloseTo(0.75, 2);
    // direction is normalised by Filament; the +x unit vector stays [1,0,0].
    expect((result as any).direction[0]).toBeCloseTo(1, 2);
  });

  /**
   * #2024 P1 in-browser proof (slice 2b): `Camera.setModelMatrix` accepts a
   * column-major flat 16-number mat4 and round-trips through `getModelMatrix`.
   *
   * `CameraNode` (`nodes/CameraNode.kt`) syncs the camera from its node
   * `worldTransform` every frame via this exact call (Android's
   * `camera.setModelMatrix(worldTransform)` pattern). Karma stubs `Camera`, so
   * only the real WASM module proves the binding — the embind probe-first rule.
   */
  test('Camera.setModelMatrix round-trips a mat4 (#2024 P1 slice 2b)', async ({ page }) => {
    await page.goto('/kotlin-bundle/index.html');
    await expect
      .poll(() => page.evaluate(() => (window as any).__smoke?.status), { timeout: 30_000 })
      .toBe('resolved');

    const result = await page.evaluate(() => {
      const F = (window as any).Filament;
      if (!F?.Engine?.create) return { error: 'Filament global not initialised' };

      const probeCanvas = document.createElement('canvas');
      probeCanvas.width = 8;
      probeCanvas.height = 8;
      document.body.appendChild(probeCanvas);
      let step = 'Engine.create';
      try {
        const engine = F.Engine.create(probeCanvas);
        step = 'createCamera';
        const cameraEntity = F.EntityManager.get().create();
        const camera = engine.createCamera(cameraEntity);
        if (typeof camera.setModelMatrix !== 'function') return { error: 'setModelMatrix missing' };

        // A pure translation to (2,3,4) — the same column-major layout
        // Transform.copyColumnsInto produces (translation in elements 12/13/14).
        step = 'setModelMatrix';
        camera.setModelMatrix([1,0,0,0, 0,1,0,0, 0,0,1,0, 2,3,4,1]);
        step = 'getPosition';
        const pos = camera.getPosition();
        return { px: pos[0], py: pos[1], pz: pos[2] };
      } catch (e: any) {
        return { error: `${step}: ${e?.name ?? ''} ${e?.message ?? String(e)}` };
      }
    });

    expect((result as any).error, `setModelMatrix probe failed: ${(result as any).error}`).toBeUndefined();
    expect((result as any).px).toBeCloseTo(2, 3);
    expect((result as any).py).toBeCloseTo(3, 3);
    expect((result as any).pz).toBeCloseTo(4, 3);
  });
});

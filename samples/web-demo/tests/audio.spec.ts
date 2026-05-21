import { test, expect } from './helpers';

/**
 * SceneView Web Demo — Spatial Audio regression tests.
 *
 * Guards issue #1944: the Spatial Audio panel's `#audio-play` button shipped
 * dead — clicking it produced ZERO Web Audio API activity (no `AudioContext`,
 * no `decodeAudioData`, no `PannerNode`, no `.start()`). The wiring lived only
 * in `Main.kt::setupSpatialAudio()`, but `index.html` loads the hand-written
 * `js/sceneview.js` runtime, NOT the Kotlin/JS bundle — so that code never ran.
 *
 * CI built the bundle green but never clicked the button. These tests close
 * that gap: they install Web Audio API hooks BEFORE the page's own scripts
 * (`addInitScript`), then click the real visible button and assert the graph
 * was actually constructed.
 */

/**
 * Installs counters on the Web Audio API constructors/methods, mirroring the
 * CDP instrumentation from the issue. Runs before any page script so it
 * captures the demo's very first audio call.
 */
async function installAudioHooks(page: import('@playwright/test').Page): Promise<void> {
  await page.addInitScript(() => {
    const w = window as unknown as {
      __audioHooks: {
        contextCtor: number;
        createPanner: number;
        createBufferSource: number;
        createGain: number;
        decodeAudioData: number;
        sourceStart: number;
      };
      AudioContext?: typeof AudioContext;
      webkitAudioContext?: typeof AudioContext;
    };
    w.__audioHooks = {
      contextCtor: 0,
      createPanner: 0,
      createBufferSource: 0,
      createGain: 0,
      decodeAudioData: 0,
      sourceStart: 0,
    };

    const hooks = w.__audioHooks;

    // Hook the BaseAudioContext prototype methods.
    const baseProto = (window as unknown as { BaseAudioContext?: { prototype: AudioContext } })
      .BaseAudioContext?.prototype;
    if (baseProto) {
      const wrap = (name: keyof typeof hooks, method: string) => {
        const original = (baseProto as unknown as Record<string, unknown>)[method];
        if (typeof original === 'function') {
          (baseProto as unknown as Record<string, unknown>)[method] = function (
            this: AudioContext,
            ...args: unknown[]
          ) {
            hooks[name]++;
            return (original as (...a: unknown[]) => unknown).apply(this, args);
          };
        }
      };
      wrap('createPanner', 'createPanner');
      wrap('createBufferSource', 'createBufferSource');
      wrap('createGain', 'createGain');
      wrap('decodeAudioData', 'decodeAudioData');
    }

    // Hook `start` on BOTH AudioBufferSourceNode.prototype and
    // AudioScheduledSourceNode.prototype. Chromium gives `AudioBufferSourceNode`
    // its own `start` (the offset/duration override) — it does NOT inherit the
    // parent's — so hooking only the scheduled-source prototype misses the call.
    const startProtos = [
      (window as unknown as { AudioBufferSourceNode?: { prototype: AudioBufferSourceNode } })
        .AudioBufferSourceNode?.prototype,
      (
        window as unknown as {
          AudioScheduledSourceNode?: { prototype: AudioBufferSourceNode };
        }
      ).AudioScheduledSourceNode?.prototype,
    ];
    for (const proto of startProtos) {
      if (proto && Object.prototype.hasOwnProperty.call(proto, 'start')) {
        const originalStart = proto.start;
        proto.start = function (this: AudioBufferSourceNode, ...args: number[]) {
          hooks.sourceStart++;
          return originalStart.apply(this, args as [number?, number?, number?]);
        };
      }
    }

    // Hook the AudioContext / webkitAudioContext constructors.
    const Ctx = w.AudioContext || w.webkitAudioContext;
    if (Ctx) {
      const Hooked = new Proxy(Ctx, {
        construct(target, args) {
          hooks.contextCtor++;
          return Reflect.construct(target, args);
        },
      });
      w.AudioContext = Hooked as typeof AudioContext;
      if (w.webkitAudioContext) w.webkitAudioContext = Hooked as typeof AudioContext;
    }
  });
}

type AudioHooks = {
  contextCtor: number;
  createPanner: number;
  createBufferSource: number;
  createGain: number;
  decodeAudioData: number;
  sourceStart: number;
};

async function readAudioHooks(page: import('@playwright/test').Page): Promise<AudioHooks> {
  return page.evaluate(
    () => (window as unknown as { __audioHooks: AudioHooks }).__audioHooks,
  );
}

test.describe('SceneView Web Demo — Spatial Audio', () => {

  test('Spatial Audio panel exposes Play / Stop controls', async ({ page }) => {
    await page.goto('/');

    const overlay = page.locator('#loading-overlay');
    await expect(overlay).toHaveClass(/hidden/, { timeout: 45_000 });

    await page.locator('.tab-btn[data-tab="audio"]').click();
    await expect(page.locator('#panel-audio')).toHaveClass(/active/);
    await expect(page.locator('#audio-play')).toBeEnabled();
    await expect(page.locator('#audio-stop')).toBeEnabled();
    await expect(page.locator('#audio-falloff')).toBeVisible();
  });

  test('clicking Play constructs an AudioContext and the PannerNode graph (#1944)', async ({
    page,
  }) => {
    // Hooks MUST be installed before the page scripts so the demo's first
    // audio call is captured — exactly the methodology from issue #1944.
    await installAudioHooks(page);
    await page.goto('/');

    const overlay = page.locator('#loading-overlay');
    await expect(overlay).toHaveClass(/hidden/, { timeout: 45_000 });

    await page.locator('.tab-btn[data-tab="audio"]').click();
    await expect(page.locator('#panel-audio')).toHaveClass(/active/);

    // Before clicking Play: no Web Audio activity at all.
    const before = await readAudioHooks(page);
    expect(before.contextCtor, 'no AudioContext should exist before Play').toBe(0);

    // Real click — counts as a user gesture, so the AudioContext is allowed
    // to start un-suspended.
    await page.locator('#audio-play').click();

    // The bell is decoded asynchronously; wait for the success log the demo
    // emits once the graph is playing.
    await expect
      .poll(async () => (await readAudioHooks(page)).sourceStart, {
        message: 'AudioBufferSourceNode.start() never ran — Play is a dead no-op',
        timeout: 15_000,
      })
      .toBeGreaterThan(0);

    const after = await readAudioHooks(page);
    expect(after.contextCtor, 'an AudioContext must be constructed').toBeGreaterThan(0);
    expect(after.decodeAudioData, 'bell.wav must be decoded').toBeGreaterThan(0);
    expect(after.createPanner, 'a PannerNode must be created').toBeGreaterThan(0);
    expect(after.createGain, 'a GainNode must be created').toBeGreaterThan(0);
    expect(after.createBufferSource, 'an AudioBufferSourceNode must be created').toBeGreaterThan(
      0,
    );
  });

  test('clicking Stop after Play tears the graph down', async ({ page }) => {
    await installAudioHooks(page);
    await page.goto('/');

    const overlay = page.locator('#loading-overlay');
    await expect(overlay).toHaveClass(/hidden/, { timeout: 45_000 });

    await page.locator('.tab-btn[data-tab="audio"]').click();
    await page.locator('#audio-play').click();

    await expect
      .poll(async () => (await readAudioHooks(page)).sourceStart, { timeout: 15_000 })
      .toBeGreaterThan(0);

    // Stop must not throw — the page stays error-free.
    await page.locator('#audio-stop').click();
    await page.waitForTimeout(200);
    await expect(page.locator('#audio-play')).toBeEnabled();
  });
});

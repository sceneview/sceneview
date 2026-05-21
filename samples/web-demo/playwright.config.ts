import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright configuration for SceneView Web Demo visual regression tests.
 *
 * Run with:
 *   npx playwright test
 *
 * Update screenshots:
 *   npx playwright test --update-snapshots
 */
export default defineConfig({
  testDir: './tests',
  outputDir: './test-results',

  /* Timeout per test — WebGL/WASM loading can be slow */
  timeout: 60_000,

  /* Expect timeout for assertions */
  expect: {
    timeout: 10_000,
    toHaveScreenshot: {
      /* Allow up to 1% pixel difference for GPU/driver variance */
      maxDiffPixelRatio: 0.01,
      /* Threshold per pixel channel (0-1 scale) */
      threshold: 0.2,
    },
  },

  /* Run tests serially — single WebGL context is heavy */
  fullyParallel: false,
  workers: 1,

  /* Reporter
   *
   * - `html`     — local debugging report
   * - `list`     — console output
   * - `json`     — raw Playwright result, kept for tooling
   * - custom QA  — flat `test-results/web-qa-summary.json` consumed by the
   *                autonomous device-QA orchestrator runner (issue #1566).
   */
  reporter: [
    ['html', { outputFolder: './playwright-report', open: 'never' }],
    ['list'],
    ['json', { outputFile: './test-results/playwright-results.json' }],
    ['./tests/qa-summary-reporter.ts'],
  ],

  /* Shared settings for all projects */
  use: {
    /* Base URL — set via env or default to local dev server */
    baseURL: process.env.WEB_DEMO_URL || 'http://localhost:8080',

    /* Capture screenshot on failure */
    screenshot: 'only-on-failure',

    /* Capture trace on first retry */
    trace: 'on-first-retry',

    /* Per-test recording is driven by the `screencast` fixture in
     * `tests/helpers.ts` (Playwright 1.59+ `page.screencast.start/stop`), not
     * by the legacy `video: 'on'` config. The fixture brackets each test, adds
     * a chapter annotation per `loadCatalogPage(...)` / tab switch, and lands
     * the .webm at `test-results/screencasts/<test-name>.webm` — parity with
     * the Maestro Android / iOS device-QA legs. See `helpers.ts:test` for the
     * extended fixture and `device-qa.sh` for how the runner collects the
     * recordings into `$ARTIFACTS/`. Issue #1748 item 3. */
    video: 'off',

    /* Viewport size for consistent screenshots */
    viewport: { width: 1280, height: 720 },
  },

  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        /* Enable WebGL for Filament.js rendering */
        launchOptions: {
          args: [
            '--enable-webgl',
            '--use-gl=angle',
            '--enable-features=Vulkan',
            '--ignore-gpu-blocklist',
            // Chrome removed the automatic SwiftShader fallback for WebGL. On a
            // GPU-less CI runner ANGLE has no hardware path and nothing to fall
            // back to, so WebGL context creation fails outright — the demo
            // never gets a context and the suite would go green-on-nothing.
            // This flag re-enables the software rasteriser so the Filament.js
            // viewer still gets a context on headless CI.
            '--enable-unsafe-swiftshader',
          ],
        },
      },
    },
  ],

  /* Dev server — start the web demo if not already running */
  webServer: process.env.WEB_DEMO_URL ? undefined : {
    command: 'npx http-server site -p 8080 -s',
    port: 8080,
    timeout: 30_000,
    reuseExistingServer: true,
  },
});

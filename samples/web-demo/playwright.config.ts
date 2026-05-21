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

  /* Reporter */
  reporter: [
    ['html', { outputFolder: './playwright-report', open: 'never' }],
    ['list'],
  ],

  /* Shared settings for all projects */
  use: {
    /* Base URL — set via env or default to local dev server */
    baseURL: process.env.WEB_DEMO_URL || 'http://localhost:8080',

    /* Capture screenshot on failure */
    screenshot: 'only-on-failure',

    /* Capture trace on first retry */
    trace: 'on-first-retry',

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
          ],
        },
      },
    },
  ],

  /* Dev server — serve the static web demo (index.html) if not already running */
  webServer: process.env.WEB_DEMO_URL ? undefined : {
    command: 'npx http-server . -p 8080 -s',
    port: 8080,
    timeout: 30_000,
    reuseExistingServer: true,
  },
});

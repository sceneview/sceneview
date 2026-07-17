import {
  test,
  expect,
  captureDiagnostics,
  expectNoPageErrors,
  waitForEngineReady,
  switchTab,
  waitForModelChipIdle,
} from './helpers';

/**
 * SceneView Web Demo — multi-source Explore parity (#2722).
 *
 * Deterministic coverage of the source-agnostic catalog layer ported from
 * Android (#2685) / iOS (#2721): the source picker, persisted selection,
 * reset-on-switch, per-feed resilience, and the in-app render of a
 * Creative-Commons catalog model.
 *
 * The network catalogs (Icosa, Poly Haven, Sketchfab) are flaky/rate-limited
 * over the live internet, so every network-dependent assertion here mocks the
 * API with `page.route(...)` — the suite never depends on a live third party.
 * One mocked download resolves to a REAL, locally-served GLB so the whole
 * download → Filament render path is exercised end-to-end, not stubbed.
 */

/** Absolute URL of a real GLB served by the Playwright dev server. */
function localGlbUrl(page: import('@playwright/test').Page): string {
  return new URL('/models/khronos_damaged_helmet.glb', page.url()).toString();
}

/** Build one Icosa list-asset whose renderable format points at `glbUrl`. */
function icosaAsset(id: string, name: string, glbUrl: string) {
  return {
    assetId: id,
    displayName: name,
    authorName: 'Test Author',
    license: 'CREATIVE_COMMONS_BY',
    triangleCount: 12345,
    tags: ['test'],
    formats: [{ formatType: 'GLTF2', root: { url: glbUrl } }],
  };
}

/**
 * Stub the Icosa Gallery API. The list/feed/search endpoint (`/v1/assets?…`)
 * returns three assets; the detail endpoint (`/v1/assets/<id>`) returns one
 * asset whose GLB format root points at the real local GLB. `failNewest`
 * makes the "Recently Added" feed (orderBy=NEWEST) 500 so the resilience path
 * can be asserted.
 */
async function mockIcosa(
  page: import('@playwright/test').Page,
  glbUrl: string,
  opts: { failNewest?: boolean } = {},
): Promise<void> {
  const json = (body: unknown) => ({
    status: 200,
    contentType: 'application/json',
    headers: { 'access-control-allow-origin': '*' },
    body: JSON.stringify(body),
  });

  // Detail endpoint: /v1/assets/<id>  (no further path segments).
  await page.route(/api\.icosa\.gallery\/v1\/assets\/[^/?]+(\?|$)/, async (route) => {
    await route.fulfill(json(icosaAsset('helmet', 'Damaged Helmet', glbUrl)));
  });

  // List / feed / search endpoint: /v1/assets?<query>.
  await page.route(/api\.icosa\.gallery\/v1\/assets\?/, async (route) => {
    const url = route.request().url();
    if (opts.failNewest && url.includes('orderBy=NEWEST')) {
      await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
      return;
    }
    await route.fulfill(
      json({
        assets: [
          icosaAsset('helmet', 'Damaged Helmet', glbUrl),
          icosaAsset('chair', 'Sheen Chair', glbUrl),
          icosaAsset('watch', 'Chronograph Watch', glbUrl),
        ],
      }),
    );
  });
}

test.describe('Web Demo — multi-source Explore (#2722)', () => {
  test('source picker shows keyless catalogs and hides Sketchfab without a key', async ({ page }) => {
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);
    await switchTab(page, 'models');

    // Keyless build: bundled samples + the two CC catalogs, no Sketchfab chip.
    await expect(page.locator('.source-chip[data-source="sceneview"]')).toBeVisible();
    await expect(page.locator('.source-chip[data-source="icosa"]')).toBeVisible();
    await expect(page.locator('.source-chip[data-source="polyhaven"]')).toBeVisible();
    expect(await page.locator('.source-chip[data-source="sketchfab"]').count()).toBe(0);

    // Default source is the bundled samples — its cards render offline and the
    // search box is hidden (curated has no search).
    await expect(page.locator('.source-chip[data-source="sceneview"]')).toHaveClass(/active/);
    await expect(page.locator('#search-box')).toBeHidden();
    await expect(page.locator('#model-results .result-card').first()).toBeVisible();
    expect(await page.locator('#model-results .result-card').count()).toBeGreaterThan(0);

    expectNoPageErrors(diag, 'source picker (keyless)');
  });

  test('Sketchfab chip appears when an API key is provided', async ({ page }) => {
    await page.goto('/?sketchfab_key=test-key-not-committed');
    await waitForEngineReady(page);
    await switchTab(page, 'models');
    await expect(page.locator('.source-chip[data-source="sketchfab"]')).toBeVisible();
  });

  test('switching to Icosa shows its feed sections from mocked data', async ({ page }) => {
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);
    await mockIcosa(page, localGlbUrl(page));
    await switchTab(page, 'models');

    await page.locator('.source-chip[data-source="icosa"]').click();
    // Icosa is searchable → the search box appears.
    await expect(page.locator('#search-box')).toBeVisible();
    // Three feeds (Trending / Staff Picks / Recently Added) each render cards.
    await expect(page.locator('.result-section-label')).toHaveCount(3);
    await expect(page.locator('.result-section-label').first()).toHaveText('Trending');
    expect(await page.locator('#model-results .result-card').count()).toBeGreaterThan(0);
    // The attribution line credits the source + license.
    await expect(page.locator('.result-card .result-author').first()).toContainText('via Icosa Gallery');

    expectNoPageErrors(diag, 'Icosa feed sections');
  });

  test('switching source resets the search query', async ({ page }) => {
    await page.goto('/');
    await waitForEngineReady(page);
    await mockIcosa(page, localGlbUrl(page));
    await switchTab(page, 'models');

    await page.locator('.source-chip[data-source="icosa"]').click();
    await page.locator('#search-input').fill('dragon');
    await expect(page.locator('#search-input')).toHaveValue('dragon');

    // Switch to Poly Haven — the query must clear (browse reset), parity with Android.
    await page.locator('.source-chip[data-source="polyhaven"]').click();
    await expect(page.locator('#search-input')).toHaveValue('');
  });

  test('a degraded feed never blanks the tab — survivors still render', async ({ page }) => {
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);
    await mockIcosa(page, localGlbUrl(page), { failNewest: true });
    await switchTab(page, 'models');

    await page.locator('.source-chip[data-source="icosa"]').click();
    // Trending + Staff Picks render cards; Recently Added shows an honest note.
    // Web-first (retrying) assertions — the three feeds resolve asynchronously,
    // so wait for the rendered outcome rather than snapshotting a bare count().
    await expect(page.locator('.result-section-label.failed')).toContainText("couldn't load");
    await expect(page.locator('#model-results .result-card').first()).toBeVisible();
    expect(await page.locator('#model-results .result-card').count()).toBeGreaterThan(0);

    expectNoPageErrors(diag, 'degraded feed resilience');
  });

  test('selecting an Icosa model downloads and renders it in-app', async ({ page }) => {
    // A real GLB download + WASM upload + Filament render — slow on software GPU.
    test.slow();
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);
    await mockIcosa(page, localGlbUrl(page));
    await switchTab(page, 'models');

    await page.locator('.source-chip[data-source="icosa"]').click();
    await expect(page.locator('#model-results .result-card').first()).toBeVisible();
    await page.locator('#model-results .result-card').first().click();

    // The download streams the real local GLB, hands it to Filament, and the
    // loading chip clears on a successful in-app render (no external tab).
    await waitForModelChipIdle(page);
    expectNoPageErrors(diag, 'Icosa in-app render');
  });

  test('dead web.archive.org mirrors are never fetched when a live format exists', async ({ page }) => {
    // Regression guard for the live bug found on Icosa asset 5rf3YuZfJAW: the
    // legacy Poly-era GLB mirror lives on web.archive.org (404 + no CORS —
    // fetch() throws), and it used to win format selection because its URL
    // ends in `.glb`. `preferredFormat` must pick the live Icosa-hosted format
    // and never touch the archive host.
    test.slow();
    const diag = captureDiagnostics(page);
    await page.goto('/');
    await waitForEngineReady(page);

    let archiveHit = false;
    await page.route(/web\.archive\.org/, async (route) => {
      archiveHit = true;
      await route.fulfill({ status: 404, body: '' });
    });
    const glbUrl = localGlbUrl(page);
    const deadGlb = 'https://web.archive.org/web/20250101010101id_/https://poly.googleusercontent.com/downloads/dead.glb';
    // Asset whose FIRST format is the dead archive .glb, followed by a live one.
    const asset = {
      ...icosaAsset('helmet', 'Damaged Helmet', glbUrl),
      formats: [
        { formatType: 'GLB', root: { url: deadGlb } },
        { formatType: 'GLTF2', root: { url: glbUrl } },
      ],
    };
    await page.route(/api\.icosa\.gallery\/v1\/assets\/[^/?]+(\?|$)/, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'access-control-allow-origin': '*' },
        body: JSON.stringify(asset),
      });
    });
    await page.route(/api\.icosa\.gallery\/v1\/assets\?/, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'access-control-allow-origin': '*' },
        body: JSON.stringify({ assets: [asset] }),
      });
    });
    await switchTab(page, 'models');
    await page.locator('.source-chip[data-source="icosa"]').click();
    await expect(page.locator('#model-results .result-card').first()).toBeVisible();
    await page.locator('#model-results .result-card').first().click();
    await waitForModelChipIdle(page);

    expect(archiveHit, 'web.archive.org must never be fetched when a live format exists').toBe(false);
    expectNoPageErrors(diag, 'archive-mirror deprioritization');
  });

  test('the selected source persists across a reload', async ({ page }) => {
    await page.goto('/');
    await waitForEngineReady(page);
    await mockIcosa(page, localGlbUrl(page));
    await switchTab(page, 'models');
    await page.locator('.source-chip[data-source="polyhaven"]').click();
    await expect(page.locator('.source-chip[data-source="polyhaven"]')).toHaveClass(/active/);

    // Reload — the persisted choice (localStorage) is restored.
    await page.reload();
    await waitForEngineReady(page);
    await switchTab(page, 'models');
    await expect(page.locator('.source-chip[data-source="polyhaven"]')).toHaveClass(/active/);
  });
});

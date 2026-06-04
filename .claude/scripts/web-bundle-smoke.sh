#!/usr/bin/env bash
# web-bundle-smoke.sh — in-browser smoke test for the COMPILED Kotlin/JS bundle.
#
# WHY THIS EXISTS
# ---------------
# `sceneview-web` ships to npm + the CDN as `sceneview-web@<v>/sceneview-web.js`
# — the Kotlin/JS webpack bundle. The repo's other web coverage exercises the
# SEPARATE hand-authored `website-static/js/sceneview.js`, and the Kotlin
# `jsTest` (Karma) suite STUBS the Filament externals. So nothing ran the real
# bundle against the real Filament WASM module in a browser — and a whole chain
# of init-path bugs (a Kotlin `as`/companion access against an `external` class
# that is `undefined` until `Filament.init()`; embind strict-arity / wrong
# buffer-type calls) shipped invisibly: `createViewer()` threw, the Promise was
# never settled, and every consumer hung on a blank canvas.
#
# This script is the single source of truth that closes that gap. It:
#   1. builds the production webpack bundle,
#   2. stages it next to a VERSION-MATCHED filament.js/.wasm (the exact npm
#      version the bundle was compiled against, from the Kotlin/JS build's
#      node_modules — NOT the demo's hand-hosted copy, which can differ),
#   3. ensures Playwright + chromium are present,
#   4. runs `tests/kotlin-bundle.spec.ts`, which loads the bundle the way a real
#      consumer does and asserts `createViewer().then(...)` RESOLVES and renders.
#
# It runs locally (`bash .claude/scripts/web-bundle-smoke.sh`) and in CI (the
# `web-desktop` job in ci.yml, which already has both gradle/JDK and node). The
# spec self-skips when the bundle is not staged, so the lean node-only
# `device-qa.sh --platform=web` leg never red-blocks on a missing artifact.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

WEBDIR="samples/web-demo"
STAGE="$WEBDIR/site/kotlin-bundle"
BUNDLE_OUT="sceneview-web/build/kotlin-webpack/js/productionExecutable/sceneview-web.js"
FILAMENT_DIR="build/js/node_modules/filament"

log() { printf '[web-bundle-smoke] %s\n' "$*"; }

# 1. Build the production bundle (the artifact that actually ships).
log "building :sceneview-web:jsBrowserProductionWebpack"
./gradlew :sceneview-web:jsBrowserProductionWebpack --console=plain

[[ -f "$BUNDLE_OUT" ]] || { echo "[web-bundle-smoke] FATAL: bundle not produced at $BUNDLE_OUT" >&2; exit 1; }

# 2. Stage bundle + version-matched filament.js/.wasm beside the fixture page.
#    filament.js resolves filament.wasm relative to its own URL, so all three
#    must sit together in $STAGE (the fixture index.html is checked in there).
mkdir -p "$STAGE"
cp "$BUNDLE_OUT" "$STAGE/sceneview-web.js"
if [[ -f "$FILAMENT_DIR/filament.js" && -f "$FILAMENT_DIR/filament.wasm" ]]; then
  cp "$FILAMENT_DIR/filament.js" "$STAGE/filament.js"
  cp "$FILAMENT_DIR/filament.wasm" "$STAGE/filament.wasm"
else
  echo "[web-bundle-smoke] FATAL: version-matched filament not found under $FILAMENT_DIR" >&2
  echo "  (the Kotlin/JS build downloads it — did the gradle build above succeed?)" >&2
  exit 1
fi
log "staged: $(cd "$STAGE" && ls -1 sceneview-web.js filament.js filament.wasm | tr '\n' ' ')"

# 3. Ensure Playwright + chromium. `@playwright/test` + `http-server` are
#    already declared devDependencies, so a plain `npm install` materialises
#    them WITHOUT rewriting package.json/lock (no `--save-dev`, which would dirty
#    the tree with an incidental version bump).
log "ensuring Playwright + chromium ($WEBDIR)"
(
  cd "$WEBDIR"
  npm install --no-audit --no-fund >/dev/null 2>&1
  npx playwright install chromium --with-deps >/dev/null 2>&1 \
    || npx playwright install chromium >/dev/null 2>&1
)

# 4. Run the smoke spec. playwright.config.ts auto-starts http-server on :8080
#    (override with WEB_DEMO_URL). Its own reporters drive the output.
log "running tests/kotlin-bundle.spec.ts"
( cd "$WEBDIR" && npx playwright test tests/kotlin-bundle.spec.ts )

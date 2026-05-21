<!-- category: Added -->
- CI: daily `maintenance.yml` job that monitors Android App Links + iOS/macOS Universal Links verification health — cross-checks the hosted `assetlinks.json` / `apple-app-site-association` against the committed source of truth and the demo apps' intent-filters/entitlements, opening a tracking issue when the QR → demo deep-link flow is broken (#1695).

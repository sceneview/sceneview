<!-- category: Added -->
<!-- breaking: false -->
The iOS and web demos now credit every bundled third-party asset where a user can see it,
as the Android demo already did. `generate-credits.py` gained two JSON scopes next to the
Markdown ones — `samples/ios-demo/SceneViewDemo/Resources/BundledCredits.json` (41 assets)
and `samples/web-demo/site/credits.json` (15 assets) — generated from `assets/catalog.json`
and gated by the same `--check`, so a bundled file nobody declared fails the gate on every
platform. The iOS About → Credits sheet lists the bundled models, HDR environments and media
("by author — license", tap to open the source) above the streamed Sketchfab models it
already showed; the web demo gets a Credits tab (`#credits` deep link) rendered from
`credits.json` at runtime.

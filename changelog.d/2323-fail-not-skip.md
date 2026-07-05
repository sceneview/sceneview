<!-- category: Tests -->
- `DemoRenderingScreenshotTest` now FAILS (instead of silently assume-skipping) when a slug listed in `BASELINED_GOLDENS` has no committed golden — a deleted/renamed baseline can no longer disable its own regression guard unnoticed (#2323 suggestion 2). New slugs keep the quiet first-run capture flow.

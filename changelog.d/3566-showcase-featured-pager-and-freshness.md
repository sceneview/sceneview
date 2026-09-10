<!-- category: Added -->
- **The Showcase tells you what is new, and its featured banner scrolls
  ([#3566](https://github.com/sceneview/sceneview/issues/3566),
  [#3567](https://github.com/sceneview/sceneview/issues/3567)).** Nothing on the grid said
  which of the 49 samples had changed since the last release, so a returning user — the
  maintainer included — had no way to know which feature was worth opening again. Two
  optional fields on `DemoEntry`, `sinceVersion` and `updatedIn`, are now declared in each
  demo's own fragment (the file a PR already edits, so no shared registry to conflict on)
  and compared against `BuildConfig.VERSION_NAME`: a demo carries a **New** or **Updated**
  chip on its card for one minor version, then goes quiet on its own. `updatedIn` means
  *user-visible behaviour changed* — a refactor or a lint fix does not move it, or half the
  grid would wear a badge permanently and the badge would mean nothing. Seven demos are
  marked for 4.35. The static hero at the top of the screen became a `HorizontalPager` with
  a dot indicator: it looked like a carousel and swiping it did nothing. Its first page is
  **"New in 4.35 — 7 samples are new or updated"**, which opens the what's-new sheet; the
  three that follow open Model Viewer, Materials and Lighting.

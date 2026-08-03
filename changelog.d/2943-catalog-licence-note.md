<!-- category: Docs -->
- **The CC-BY indicate-changes note names the artefact that actually changed.**
  `assets/catalog.json` attached the modification record to
  `models/usdz/tree_scene.usdz` — the untracked, unmodified original — while the
  stripped derivative lives in the iOS demo bundle. The note now says which copy
  is which and points at the checksum pin that protects it, and it drops the
  "bit-identical bounding box" claim two reviewers could not reproduce, keeping
  only what is independently checkable (a purely subtractive strip whose 47
  surviving meshes keep byte-identical `extent` arrays).

<!-- category: Added -->
- **AR Augmented Images — on-device runtime registration.** New `RuntimeAugmentedImageDatabase`
  helper (`rememberRuntimeAugmentedImageDatabase()`) lets you register a brand-new reference
  image at runtime — e.g. from a photo the user just took — without a pre-bundled `arcoreimg`
  database. `addImage(name, bitmap, widthInMeters)` runs the ARCore feature extraction off the
  main thread and re-applies the session config on the main thread itself, returning a typed
  `AddImageResult` (`Added` / `LowQuality` / `Error`) so low-quality captures are recoverable.
  New `Frame.captureCameraBitmap()` and `Image.toArgbBitmap()` extensions grab the live AR
  camera frame as an upright `ARGB_8888` bitmap ready for the database. The Image Tracking demo
  now ships a "Capture this view" button demonstrating the full on-device flow (#1553).

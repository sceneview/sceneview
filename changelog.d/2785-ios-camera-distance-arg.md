<!-- category: Added -->
The iOS demo host now accepts a `-camera_distance <float>` launch argument, matching
Android's `camera_distance` intent extra (#2652): `DeepLinkRouter.validateCameraDistance`
applies the identical `0.05...100` clamp on both platforms. `ModelViewerDemo` — the demo
used by the App Store screenshot pipeline — threads the validated value into its
`.framingMargin(_:)` override, taking precedence over both the interactive and `qa_mode`
defaults, so a tight store frame no longer needs Android-only tooling (#2785).

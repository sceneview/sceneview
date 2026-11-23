<!-- category: Docs -->
- **Migration guide: a transparent background needs `isOpaque = false` as well as `TextureSurface`.** Section 6 said `surfaceType = SurfaceType.TextureSurface` replaces `isOpaque = false`; following it renders an opaque black background. It now shows both, with an environment without a skybox.

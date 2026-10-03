<!-- category: Fixed -->
- **sceneview-web: `camera { fov(…); near(…); far(…) }` now reaches the projection ([#3896](https://github.com/sceneview/sceneview/issues/3896)).** Explicit valid values override automatic projection and fit values independently, while unspecified or invalid clip planes remain content-derived. `createViewerFull(fov)` is now honoured too.

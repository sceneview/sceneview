<!-- category: Fixed -->
- **Web: release disposed contexts from Filament's registry ([#4443](https://github.com/sceneview/sceneview/issues/4443)).** Detached canvases can now be garbage-collected on Safari/WebKit, which continues to count explicitly lost contexts. Explicit context loss and restoration when reusing the same canvas are preserved; collection timing remains browser-controlled.

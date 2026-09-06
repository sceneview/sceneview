<!-- category: Added -->
- **The web opens a `.3mf` too — in the viewer, on `/open`, and inside ChatGPT
  ([#3482](https://github.com/sceneview/sceneview/issues/3482)).** The 3MF reader that landed in
  `sceneview-core` is pure Kotlin and already compiled for Kotlin/JS, so closing the gap on the web
  is plumbing, not a second parser. `sceneview-web`'s `loadModel(url)` now sniffs what it fetched
  and converts a 3MF to GLB before Filament.js sees it: `sceneview.modelViewer("canvas",
  "print.3mf")` works with no new call, exactly as `ModelLoader` does on Android, and a payload
  without the ZIP magic is passed through as the same `ArrayBuffer` instance rather than copied.
  Two functions are added to the `sceneview` namespace for pages that hold the *bytes* instead of a
  URL — a dropped file, a fetch the page made itself: `sceneview.isThreeMf(bytes)` (cheap, never
  throws) and `sceneview.threeMfToGlb(bytes)` (a `Uint8Array` GLB, throws on an unreadable 3MF).
- **`sceneview.github.io/open` renders a printable model instead of dead-ending on it.** The page
  the app's verified link already points at now accepts `?url=<model>` and a `.3mf` dropped anywhere
  on it, converts it in the browser and shows it on a SceneView stage — the answer to "ChatGPT just
  made me a `.3mf`, now what?" on a desktop with no app installed. `?demo=<id>` keeps its existing
  deep-link and QR behaviour untouched. The converter is fetched only once a payload actually starts
  with the ZIP magic, so a `.glb` link costs nothing extra.
- **`view_3d_model` previews a `.3mf` in ChatGPT.** The MCP tool and its widget accept `.3mf` /
  `model/3mf` alongside glTF and GLB, so the assistant can show the print it just generated. The
  widget converts in the browser through the same compiled core and labels the format pill `3MF`;
  a `.glb` or `.gltf` URL short-circuits before any extra fetch or download.

<!-- category: Fixed -->
- **A model authored away from the origin is now framed where it actually is, not where it was
  measured ([#3482](https://github.com/sceneview/sceneview/issues/3482)).** `sceneview-web`'s
  auto-centre pass moves content onto the origin through the content-root pivot, then auto-dollies
  the camera to fit — but it fitted the bounding box measured *before* that move, so the camera
  aimed at the content's old position. A glTF authored around the origin has a ~zero offset, which
  is why it went unnoticed; a 3MF is authored in the positive octant by specification, so a
  converted print rendered a third of a frame off-centre.

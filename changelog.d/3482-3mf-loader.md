<!-- category: Added -->
- **SceneView opens `.3mf` — the format AI print flows emit, that nothing on Android or the web
  could view ([#3482](https://github.com/sceneview/sceneview/issues/3482)).** Ask ChatGPT for a 3D
  print from a drawing and you get a `.3mf`: an OPC/ZIP package whose `3D/3dmodel.model` part is XML
  with `<vertices>`, `<triangles>`, `<components>` and a `<build>` plate, in millimetres and Z-up.
  Until now no Android app and no web page opened one in 3D, let alone in AR. `sceneview-core` now
  reads 3MF in pure Kotlin on **every** platform — no `java.util.zip`, no XML library, no
  `expect`/`actual` — and converts it to GLB in memory, so the whole existing glTF path (materials,
  gestures, AR placement, the web viewer) is reused instead of a second loader per renderer.
  **There is no new API to learn on Android:** `ModelLoader` sniffs the payload by its ZIP magic, so
  `rememberModelInstance(modelLoader, uri.toString())`, `loadModel("print.3mf")` and every other
  entry point already accept a 3MF; a payload that is not a ZIP costs a 4-byte comparison.
  Conversion scales the file's declared unit to metres (a 60 mm print is life-size in AR without a
  magic number), rotates the printer's Z-up to glTF's Y-up so the part stands up instead of lying on
  its back, and gives every face its own normal — flat shading is what a printed part looks like,
  and a smoothed normal would round over the facets the slicer will extrude. `<basematerials>` and
  the materials extension's `<colorgroup>` become one glTF material per colour, per object and per
  triangle, all `doubleSided` because generated meshes are often inconsistently wound. Unrecognised
  3MF extensions (slice, beamlattice, production) are skipped rather than rejected: an unknown
  extension must not stop a print from being previewed. For a custom pipeline,
  `ThreeMfLoader.parse()` returns the file's own objects, meshes and build items, and
  `ThreeMfLoader.toGlb()` / `isThreeMf()` are public in `sceneview-core`.

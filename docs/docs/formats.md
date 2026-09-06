# Model Formats

SceneView loads a model from a path, a URI or a byte buffer. **The format is detected from
the bytes, not from the file name** — a file shared into your app with no extension and a
`application/octet-stream` MIME type still opens.

| Format | Extensions | Where it works | Notes |
|---|---|---|---|
| **glTF / GLB** | `.gltf`, `.glb` | Android · Web · Desktop · TV · Flutter · React Native | The native format across the SDK. Skeletal and morph animations, PBR materials, Draco and WebP textures. |
| **USDZ / Reality** | `.usdz`, `.reality` | Apple (iOS · macOS · visionOS) | Loaded by RealityKit through `SceneViewSwift`. |
| **3MF** | `.3mf` | Android | Converted to GLB in memory. The parser lives in `sceneview-core` (Kotlin Multiplatform). |

---

## 3MF — the format AI print flows emit

Ask ChatGPT for a 3D print from a drawing and it hands back a `.3mf`: an OPC/ZIP package
whose `3D/3dmodel.model` part is XML, with `<vertices>`, `<triangles>`, `<components>` and a
`<build>` plate, in millimetres and Z-up. It is what every slicer and every image-to-print
flow produces — and until SceneView read it, no Android app opened one in 3D, let alone in
AR.

### There is no new API

`ModelLoader` sniffs the payload by its ZIP magic and converts it to GLB before the bytes
reach Filament, so every entry point you already use accepts a 3MF:

```kotlin
val modelLoader = rememberModelLoader(engine)

SceneView(modifier = Modifier.fillMaxSize(), engine = engine, modelLoader = modelLoader) {
    rememberModelInstance(modelLoader, "print.3mf")?.let {
        ModelNode(modelInstance = it, scaleToUnits = 1.0f)
    }
}
```

A file shared in from another app works the same way — pass the `content://` URI as a
string:

```kotlin
rememberModelInstance(modelLoader, uri.toString())?.let {
    ModelNode(modelInstance = it, scaleToUnits = 1.0f)
}
```

A payload that is not a ZIP costs one four-byte comparison, so the check is free for the
glTF path.

### Receiving a file from another app

The Android demo declares `ACTION_VIEW` and `ACTION_SEND` filters for `.3mf`, `.glb` and
`.gltf`, so it appears in the app chooser and the share sheet
([#3510](https://github.com/sceneview/sceneview/pull/3510)). Two things the platform forces,
if you do the same in your own app:

- **Sniff the bytes, not the metadata.** A `.3mf` sent through the share sheet arrives with
  `application/octet-stream` as its type *and* with no queryable display name — both signals
  blank. A name-or-MIME check refuses a file the SDK reads perfectly.
- **Read the ClipData, not a bare `EXTRA_STREAM`.** A sender's
  `FLAG_GRANT_READ_URI_PERMISSION` covers `getData()` and the ClipData only; read the extra
  of a share that carries both and `openInputStream` returns `null`.

`samples/android-demo/src/main/java/io/github/sceneview/demo/OpenedModel.kt` is the working
implementation.

### What the conversion does

| Step | Why |
|---|---|
| **Millimetres → metres** | 3MF carries real-world size (`unit="millimeter"` unless stated otherwise). A 60 mm print is life-size in AR without a magic number. |
| **Z-up → Y-up** | 3MF uses the printer's build-plate axes; glTF and SceneView use Y-up. The part stands up instead of lying on its back. |
| **Flat normals** | 3MF stores no normals. Faces are de-indexed and given per-face normals — flat shading is what a printed part looks like, and a smoothed normal would round over the facets the slicer will extrude. |
| **Colours → glTF materials** | `<basematerials>` and the materials extension's `<colorgroup>` become one glTF material per colour, per object and per triangle. |
| **`doubleSided`** | Generated meshes are often inconsistently wound, and a one-sided print renders inside-out. |

Unrecognised 3MF extensions (slice, beamlattice, production) are **skipped, never
rejected** — an unknown extension must not stop a print from being previewed.

### Custom pipelines

The parser is pure Kotlin in `sceneview-core` — no `java.util.zip`, no XML library, no
`expect`/`actual` — so it runs on every Kotlin target:

```kotlin
if (ThreeMfLoader.isThreeMf(bytes)) {
    val model = ThreeMfLoader.parse(bytes)   // objects, meshes, build items
    val glb = ThreeMfLoader.toGlb(bytes)     // ready for any glTF consumer
}
```

---

## Roadmap

The 3MF parser is deliberately dependency-free so the same shape carries the rest of the
formats a print or scan pipeline emits. These are tracked and **not yet shipped** — check
the issue before relying on one:

| Format | Issue |
|---|---|
| STL (binary + ASCII) | [#3486](https://github.com/sceneview/sceneview/issues/3486) |
| PLY (mesh, vertex colours) | [#3487](https://github.com/sceneview/sceneview/issues/3487) |
| OBJ + MTL | [#3488](https://github.com/sceneview/sceneview/issues/3488) |
| One `ModelFormat` entry point (`supportedFormats`, `unit`, typed errors) | [#3489](https://github.com/sceneview/sceneview/issues/3489) |
| Open-with for every format in the Android demo | [#3490](https://github.com/sceneview/sceneview/issues/3490) |
| Every format on the web (`loadModel`, `/open`, the `view_3d_model` widget) | [#3491](https://github.com/sceneview/sceneview/issues/3491) |

---

## See also

- [`llms.txt`](https://sceneview.github.io/llms.txt) — the 3MF section in the machine-readable API reference
- [Nodes Reference](nodes.md) — what to do with a model once it is loaded
- [Quickstart](quickstart.md) — the shortest path to a rendered model

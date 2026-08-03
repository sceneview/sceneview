---
title: iOS visual-polish pipeline — HDR skybox + PBR materials + AR Quick Look
description: "Make SceneViewSwift demos look hand-crafted: wrap-around HDR skybox, physically-based materials with chrome-mirror reflections, and a one-line Apple AR Quick Look entry point."
---

# iOS visual-polish pipeline

**Intent:** "My iOS SceneView demo looks flat. How do I get the polished, reflective,
starfield-wrapped look without writing a custom RealityKit scene?"

Three ingredients turn a flat SceneViewSwift scene into a hand-crafted-looking one,
and together they cost almost no extra code:

1. **An HDR skybox** — a 360° image-based light that both *lights* the scene and
   *paints the background*.
2. **PBR materials** — physically-based metallic/roughness surfaces that reflect that
   HDR. This is what produces chrome-mirror polish.
3. **Apple AR Quick Look** — a one-line `.usdz` hand-off to iOS's system AR viewer.

!!! tip "Credit"
    This pipeline is decoded from [Eliott Radcliffe (@radcli14)](https://github.com/radcli14)'s
    MIT-licensed [`twolinks`](https://github.com/radcli14/twolinks) project. The
    skybox-renders-as-background fix and true-orbit camera ([PR #1215](https://github.com/sceneview/sceneview/pull/1215)),
    the PBR default ([#1223](https://github.com/sceneview/sceneview/issues/1223)) and
    the bundled `night_sky` HDR ([#1219](https://github.com/sceneview/sceneview/issues/1219))
    all shipped in **v4.4.0** — this page shows how to use them together.

---

## 1. HDR skybox

SceneViewSwift loads a `.hdr`/`.exr` panorama as a RealityKit `EnvironmentResource`.
As of v4.4.0 it does two things at once:

- **Image-based lighting (IBL)** — every PBR material samples the HDR for ambient
  light and reflections.
- **Background** — when `showSkybox` is `true`, the HDR is also painted behind the
  scene (`content.environment = .skybox(resource)`), so the camera looks *into* the
  panorama instead of a flat void.

### Use a bundled preset

```swift
SceneView {
    GeometryNode.sphere(radius: 0.2, material: .pbr(color: .white, metallic: 0.97, roughness: 0.05))
}
.environment(.nightSky)   // dramatic Milky Way starfield, CC0, ships with the demos
.cameraControls(.orbit)
```

`SceneEnvironment.allPresets` — `.studio`, `.outdoor`, `.sunset`, `.night`, `.warm`,
`.autumn`, `.nightSky` — all carry an HDR. `.nightSky` is the most dramatic and is the
direct counterpart to the `twolinks` starfield. Iterate `allPresets` to build an
environment picker and every preset (including future ones) surfaces automatically.

### Use your own HDR

```swift
.environment(.custom(name: "City", hdrFile: "city.hdr", intensity: 1.0, showSkybox: true))
```

- `hdrFile` resolves against the app bundle — drop `city.hdr` into the Xcode project.
- `intensity` scales the IBL contribution (dim a bright sky so it doesn't blow out
  skin tones; `nightSky` ships at `0.5` for exactly this reason).
- `showSkybox: false` keeps the HDR as a *light source only* and leaves the
  background transparent — the correct choice for AR, where the camera feed is the
  background.

!!! warning "If the scene renders dim, with no background"
    RealityKit's `EnvironmentResource(named:)` does not accept every file that
    carries an `.hdr` extension — Radiance RGBE panoramas (what Poly Haven and
    ambientCG serve) are rejected by it. SceneViewSwift falls back to decoding
    the file through ImageIO and building the resource from the equirectangular
    image, so both encodings load ([#2896](https://github.com/sceneview/sceneview/issues/2896)).

    When *both* paths fail — a genuinely missing or unreadable file — the scene
    keeps rendering with RealityKit's own default lighting and prints:

    ```
    [SceneViewSwift] Failed to load environment '<name>': <error>
    ```

    That line is the signal to check the file is actually in the bundle. Without
    it, a dim subject on a black background is a *framing or intensity* problem,
    not a loading one.

!!! info "Where to source HDRs"
    [Poly Haven](https://polyhaven.com/hdris) and [ambientCG](https://ambientcg.com/list?type=hdri)
    both publish **CC0** (public-domain) HDRIs in 1K–16K. The bundled `night_sky`
    preset is Poly Haven's [`dikhololo_night`](https://polyhaven.com/a/dikhololo_night)
    by Greg Zaal. Pick a resolution per platform — 2K is plenty for a phone.

---

## 2. PBR materials

A `SimpleMaterial` is effectively unlit-flat: it does not react to the HDR, so even a
4K skybox leaves it looking like dull plastic. A `PhysicallyBasedMaterial` reflects the
environment — *that* is the chrome polish.

**As of v4.4.0 every procedural shape already defaults to PBR** — `GeometryNode.cube(color:)`,
`.sphere(color:)`, `ShapeNode`, `LineNode`, lit `ImageNode` — so the dull-plastic look
is gone by default. You only reach for the explicit material API to *tune* metallic and
roughness:

```swift
// Mirror-chrome — high metallic, near-zero roughness
GeometryNode.sphere(radius: 0.2, material: .pbr(color: UIColor(white: 0.75, alpha: 1),
                                                metallic: 0.97, roughness: 0.05))

// Satin-painted metal — half metallic, soft highlights
GeometryNode.cube(size: 0.2, material: .pbr(color: .systemBlue,
                                            metallic: 0.5, roughness: 0.4))
```

| Look | `metallic` | `roughness` |
|---|---|---|
| Mirror chrome | `0.97` | `0.05` |
| Brushed metal | `0.9` | `0.4` |
| Satin paint | `0.5` | `0.4` |
| Matte plastic (library default) | `0.0` | `0.5` |

For textured surfaces use `.textured(baseColor:normal:metallic:roughness:tint:)`.
To opt *back into* the flat look — debug overlays, unlit gizmos — pass `unlit: true`,
e.g. `GeometryNode.cube(color: .red, unlit: true)`.

!!! warning "PBR needs an HDR to reflect"
    A metallic material in a scene with no environment reflects nothing and looks
    black. PBR and the HDR skybox are a package deal — always pair them.

---

## 3. Apple AR Quick Look

For a `.usdz` model, you do not need to build an AR scene to let the user place it in
their room. Hand the file to iOS's system AR viewer with `QuickLook`:

```swift
import QuickLook

// In a SwiftUI view, present QLPreviewController over your scene:
.quickLookPreview($arPreviewURL)   // arPreviewURL: URL? bound to the bundled .usdz
```

```swift
// Resolve the bundled USDZ and present it:
if let url = Bundle.main.url(forResource: "car", withExtension: "usdz") {
    arPreviewURL = url   // Quick Look opens; its built-in "AR" button (top-right)
                         // drops the model into the user's environment.
}
```

This is the pattern the **Model Viewer** screen in `samples/ios-demo` uses: a "View in
AR" button appears whenever the displayed model has a bundled `.usdz`, and Quick Look's
own AR button does the placement. No `ARSceneView`, no anchor management.

!!! tip "Planet props"
    `twolinks`'s earth/moon/sun planets are Apple's free
    [AR Quick Look "Sky Objects" gallery](https://developer.apple.com/augmented-reality/quick-look/)
    USDZ assets — fine to ship inside a demo app, not redistributable as a standalone
    library. They are good ready-made PBR props to drop into a `night_sky` scene.

---

## Putting it together

```swift
import SwiftUI
import QuickLook
import SceneViewSwift

struct PolishedScene: View {
    @State private var arURL: URL?

    var body: some View {
        ZStack {
            SceneView { root in
                let chrome = GeometryNode.sphere(
                    radius: 0.25,
                    material: .pbr(color: UIColor(white: 0.75, alpha: 1),
                                   metallic: 0.97, roughness: 0.05))
                chrome.entity.position = .init(x: 0, y: 0, z: -1.5)
                root.addChild(chrome.entity)
            }
            .environment(.nightSky)      // HDR lights + wraps the scene
            .cameraControls(.orbit)      // true-orbit camera (v4.4.0)
            .ignoresSafeArea()

            VStack {
                Spacer()
                Button("View in AR") {
                    arURL = Bundle.main.url(forResource: "moon", withExtension: "usdz")
                }
            }
        }
        .quickLookPreview($arURL)        // Apple AR Quick Look hand-off
    }
}
```

That is the entire `twolinks` look: a chrome sphere under a starfield with a one-tap
AR entry point — no custom RealityKit scene, no hand-tuned lighting rig.

## See also

- [Blender → SceneView asset pipeline](blender-pipeline.md) — author your own PBR
  `.usdz` props.
- [API Cheatsheet (Apple)](../cheatsheet-ios.md) — full `SceneEnvironment` and
  `GeometryMaterial` reference.
- [Dynamic Sky guide](../codelabs/guide-dynamic-sky.md) — time-of-day environment
  switching.
- [Migration Guide](../migration.md) — the v4.4.0 `showSkybox` background-render change.

---

*This recipe is decoded from [@radcli14](https://github.com/radcli14)'s
[`twolinks`](https://github.com/radcli14/twolinks) project (MIT licensed). Thanks to
Eliott Radcliffe for the reference implementation.*

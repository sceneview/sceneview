<!-- category: Fixed -->
**iOS demo app**: 17 more `SceneView` call sites stop re-creating the
`RealityView` with SwiftUI's `.id(_:)` — the pattern that intermittently
leaves the viewport black on iOS 26 Simulator (#3008). Each scene now stays
mounted for its whole lifetime and swaps or rebuilds its content in place via
`SceneView.contentID(_:)`: the Explore viewer and gallery (4 sites), Materials,
Environment, Lighting, Fog, Dynamic Sky, Movable Light, Gesture Editing, Video
Texture, Collision, Shape Extrude, Physics, Double Pendulum, the AR Placement
Reticle preview and the Depth Collider simulator fallback. Continuous
parameters — fog density, time of day, light intensity, marker visibility,
auto-rotation, the HDR environment — are applied to the live scene instead of
rebuilding it per slider tick. Materials and Environment also keep the scene
mounted while their model loads (spinner in an overlay). The Video Texture
demo's Loop toggle now actually replaces the previous quad and pauses its
player. The three `ARSceneView` re-keys (AR tab, AR Lighting, AR Instant
Placement) are left as they are: `ARSceneView` has no `contentID`, and there
the re-key means "restart the AR session". (#3020)

#if os(iOS) || os(macOS) || os(visionOS)
import SwiftUI
import RealityKit

/// A SwiftUI view for rendering 3D content using RealityKit.
///
/// Mirrors SceneView Android's `SceneView { }` composable — declare nodes declaratively
/// inside the content builder, with built-in orbit camera, environment lighting,
/// and gesture handling.
///
/// ```swift
/// @State private var model: ModelNode?
///
/// SceneView { root in
///     if let model {
///         root.addChild(model.entity)
///     }
/// }
/// .environment(.studio)
/// .cameraControls(.orbit)
/// .onEntityTapped { entity in
///     print("Tapped: \(entity)")
/// }
/// .task {
///     model = try? await ModelNode.load("models/car.usdz")
/// }
/// ```
/// How a `SceneView` should provision a main / fill light slot.
///
/// Mirrors SceneView Android's `mainLightNode: LightNode?` / `fillLightNode: LightNode?`
/// composable parameters (`SceneView.kt`), but with a 3-state enum instead of a double-Optional
/// sentinel — clearer at call sites and exhaustive in `switch`.
///
/// ```swift
/// SceneView { /* ... */ }
///   .fillLight(.systemDefault)              // (no-op — default fill at 3 000 lux)
///   .fillLight(.disabled)                   // single-light setup
///   .fillLight(.custom(LightNode.fill(intensity: 5_000)))   // user override
/// ```
///
/// Slot values are diffed reactively: `SceneView(...).fillLight(.custom(newLight))`
/// from a `@State` mutation triggers a swap on the next render frame (the
/// `RealityView.update:` closure walks the scene root for tagged entities and
/// replaces them when the slot value changes). Closes #1017.
public enum LightSlot: Equatable {

    /// Equatable conformance compares cases, with `.custom` cases compared by
    /// `Entity` reference identity (`===`) — sufficient because a single
    /// `LightNode` instance owns a single `Entity` and the slot is the only
    /// retainer of interest here.
    public static func == (lhs: LightSlot, rhs: LightSlot) -> Bool {
        switch (lhs, rhs) {
        case (.systemDefault, .systemDefault), (.disabled, .disabled):
            return true
        case (.custom(let a), .custom(let b)):
            return a.entity === b.entity
        default:
            return false
        }
    }

    /// Use the built-in default for this light slot.
    /// - Main slot default: directional at `10_000` lux, oriented straight down, casts shadows.
    /// - Fill slot default: directional at `3_000` lux, oriented from `(0.5, -0.5, 0.5)`, no shadow.
    case systemDefault

    /// Remove this light slot entirely. Use `.disabled` on the fill slot to get a single-light setup.
    case disabled

    /// Provision the slot with the caller's `LightNode`. The entity is added to the scene root
    /// during setup; orient it via ``LightNode/lookAt(_:)`` or ``LightNode/position(_:)``
    /// before passing it in.
    case custom(LightNode)
}

public struct SceneView: View {
    let content: (Entity) -> Void
    var sceneEnvironment: SceneEnvironment?
    var cameraControlMode: CameraControlMode?
    var onEntityTapped: ((Entity) -> Void)?
    var enableAutoRotate: Bool = false
    var autoRotateSpeed: Float = 0.3

    // Light slot overrides — read once during scene setup. Defaults to `.systemDefault`
    // which provisions Android-parity 10 000-lux main + 3 000-lux fill (matches the
    // v4.1.0 BREAKING render-defaults change on Android, finally reaching iOS in v4.2.0).
    // Reactive replacement is tracked as a follow-up issue (#1017).
    var mainLightSlot: LightSlot = .systemDefault
    var fillLightSlot: LightSlot = .systemDefault

    // Render-quality preset — applied after scene setup to toggle shadows + IBL
    // intensity per-tier. Mirrors Android's `RenderQuality` enum (`#1004`).
    // Default `.default` preserves the scene's loaded settings.
    var renderQualityPreset: RenderQuality = .default

    // Auto-centre user content the first frame the bounds are non-empty so
    // demos placing objects at e.g. `z = -2` show up visually centred in
    // the viewport. Mirrors Android's per-demo `ModelNode(centerOrigin: …)`
    // pattern but at the library level. Default `true`; opt out via
    // `.autoCenterContent(false)`. Closes #1026.
    var autoCenterContentEnabled: Bool = true

    // Multiplier on the fit-to-bounds camera distance computed by
    // `refreshContentCentering()`. `1.0` puts the content's bounding sphere
    // exactly tangent to the frustum; the `1.15` default keeps the pre-#2896
    // framing. Values below `1.0` dolly closer, cropping the empty corners of
    // the bounding sphere for a tighter subject. Set via `.framingMargin(_:)`.
    var framingMargin: Float = CameraControls.defaultFitMargin

    // Initial orbit pose, seeded once during scene setup. `nil` keeps
    // `CameraControls`' own defaults (azimuth 0, elevation 30°). Set via
    // `.cameraOrbit(azimuth:elevation:)`.
    var initialOrbitAzimuth: Float?
    var initialOrbitElevation: Float?

    // Host-driven camera pose, re-applied whenever the *value* changes. `nil`
    // (the default) leaves the camera entirely to the gestures, auto-rotate and
    // the framing pass — the behaviour of every scene written before this
    // existed. Set via `.cameraPose(_:)`.
    var requestedCameraPose: SceneCameraPose?

    // Monotonic stamp on `requestedCameraPose`. `0` (the default) means "compare by
    // value", which is right for a SwiftUI caller following the documented
    // `.onCameraChanged { pose = $0 }` pattern: their pose tracks the camera, so a
    // re-write always differs from the live value. A bridge that suppresses its own
    // read-back echoes has no such tracking, and needs to say "this is a new request"
    // out of band — see `SceneViewerHostView`.
    var requestedCameraPoseGeneration: Int = 0

    // Read-back for the pose above: invoked whenever the applied camera state
    // actually changes (drag, pinch, auto-rotate step, framing re-fit, or a
    // `.cameraPose(_:)` write landing clamped). `nil` by default — a scene that
    // does not ask for the read-back pays nothing. Set via `.onCameraChanged(_:)`.
    var onCameraChanged: ((SceneCameraPose) -> Void)?

    // Whether the built-in orbit / pinch gestures respond at all. Set via
    // `.cameraGesturesEnabled(_:)`; default `true`, so no existing scene changes.
    var cameraGesturesEnabled: Bool = true

    // Tap handler that also reports the world-space hit point. Kept separate
    // from `onEntityTapped` (rather than an arity overload) so no existing
    // `.onEntityTapped { … }` call site can become ambiguous.
    var onEntityTappedHit: ((SceneTapHit) -> Void)?

    // visionOS only: set by `.immersiveSpace(true)` when the caller embeds
    // this `SceneView` inside an `ImmersiveSpace` with the `.full` style. In
    // that surface the HDR `showSkybox` environment is rendered via an
    // inverted-sphere skybox under a `WorldComponent` root, since
    // `RealityViewContent.environment` is `@available(visionOS, unavailable)`.
    // Default `false` — windowed / volumetric visionOS scenes keep
    // passthrough. No-op on iOS / macOS (those use the `.skybox(_:)` path).
    // Closes #1235.
    var immersiveSpace: Bool = false

    // When true, switching back to `.orbit` resets the orbit pivot to the
    // content centroid (world origin under auto-centering) so repeated
    // pan-then-orbit cycles don't drift the centroid out of frame.
    // Default `false` — pan-then-orbit keeps the panned pivot. Closes #1236.
    var recentersTargetOnOrbit: Bool = false

    // Identity of the value the `content` closure builds from. `nil` (the
    // default) means the content is built exactly once, on `RealityView`
    // `make:` — the behaviour every existing caller gets. Set via
    // `.contentID(_:)` to re-run the builder in place when the value changes.
    // Closes #3008.
    var contentIdentity: AnyHashable?

    /// Creates a 3D scene with imperative content setup.
    ///
    /// - Parameter content: A closure that populates the scene. Receives a root
    ///   entity — add your content as children of this entity.
    public init(_ content: @escaping (Entity) -> Void) {
        self.content = content
    }

    /// Creates a 3D scene with declarative content using `@NodeBuilder`.
    ///
    /// Mirrors the Android `SceneView { }` composable pattern — declare nodes inline:
    ///
    /// ```swift
    /// SceneView {
    ///     GeometryNode.cube(size: 0.3, color: .red)
    ///         .position(.init(x: -1, y: 0, z: -2))
    ///     GeometryNode.sphere(radius: 0.2, color: .blue)
    ///         .position(.init(x: 1, y: 0, z: -2))
    ///     LightNode.directional(intensity: 1000)
    /// }
    /// ```
    public init(@NodeBuilder content: @escaping () -> [Entity]) {
        self.content = { root in
            for entity in content() {
                root.addChild(entity)
            }
        }
    }

    public var body: some View {
        SceneViewRepresentation(
            content: content,
            sceneEnvironment: sceneEnvironment,
            cameraControlMode: cameraControlMode ?? .orbit,
            onEntityTapped: onEntityTapped,
            enableAutoRotate: enableAutoRotate,
            autoRotateSpeed: autoRotateSpeed,
            mainLightSlot: mainLightSlot,
            fillLightSlot: fillLightSlot,
            renderQualityPreset: renderQualityPreset,
            autoCenterContentEnabled: autoCenterContentEnabled,
            framingMargin: framingMargin,
            initialOrbitAzimuth: initialOrbitAzimuth,
            initialOrbitElevation: initialOrbitElevation,
            immersiveSpace: immersiveSpace,
            recentersTargetOnOrbit: recentersTargetOnOrbit,
            contentIdentity: contentIdentity,
            requestedCameraPose: requestedCameraPose,
            requestedCameraPoseGeneration: requestedCameraPoseGeneration,
            onCameraChanged: onCameraChanged,
            cameraGesturesEnabled: cameraGesturesEnabled,
            onEntityTappedHit: onEntityTappedHit
        )
    }

    // MARK: - View Modifiers

    /// Sets the IBL environment for the scene.
    public func environment(_ environment: SceneEnvironment) -> SceneView {
        var copy = self
        copy.sceneEnvironment = environment
        return copy
    }

    /// Sets the camera control mode (orbit, pan, first-person).
    public func cameraControls(_ mode: CameraControlMode) -> SceneView {
        var copy = self
        copy.cameraControlMode = mode
        return copy
    }

    /// Controls whether switching back to ``CameraControlMode/orbit`` resets
    /// the orbit pivot to the content centroid. Default `false`.
    ///
    /// ``CameraControlMode/pan`` translates the orbit target laterally;
    /// over repeated pan-then-orbit cycles the pivot drifts away from the
    /// content centroid and the model can creep out of frame (#1236
    /// limitation 2). Pass `true` to snap the orbit pivot back to the
    /// centroid — the world origin under ``autoCenterContent(_:)`` — every
    /// time orbit mode is (re-)entered. The camera keeps its current
    /// azimuth / elevation / radius, so only the framing recenters.
    ///
    /// ```swift
    /// SceneView { /* ... */ }
    ///   .cameraControls(mode)
    ///   .recentersTargetOnOrbit(true)   // pan → orbit re-pivots on centroid
    /// ```
    public func recentersTargetOnOrbit(_ enabled: Bool) -> SceneView {
        var copy = self
        copy.recentersTargetOnOrbit = enabled
        return copy
    }

    /// Re-runs the content closure, in place, whenever `id` changes.
    ///
    /// Use this — **not** SwiftUI's `.id(_:)` — to swap the model a scene
    /// displays. `.id(_:)` changes the view's identity, which makes SwiftUI
    /// discard the whole `RealityView` and build a brand-new one; on iOS 26
    /// Simulator a re-created `RealityView` intermittently renders nothing at
    /// all — no model, and no skybox either — and never recovers (#3008).
    /// `.contentID(_:)` keeps one `RealityView` for the scene's lifetime and
    /// only rebuilds what is under the content root, so the renderer is never
    /// torn down.
    ///
    /// ```swift
    /// SceneView { root in
    ///     if let model { root.addChild(model.entity) }
    /// }
    /// .contentID(model == nil ? nil : selectedID)   // ← swaps the model
    /// .environment(.studio)
    /// ```
    ///
    /// The id must change **every time the closure would build something
    /// different**, including the transition from "still loading" to "loaded":
    /// keying on the selected item alone leaves the scene empty forever,
    /// because the id is already at its final value while the model is still
    /// being fetched. Passing an `Optional` covers that — it changes from `nil`
    /// to a value when the load lands.
    ///
    /// On each change the previous content is removed from the scene (its
    /// per-entity gesture handlers are unregistered first, so it deallocates
    /// rather than leaking — see #2038), the closure repopulates the content
    /// root, the render-quality preset is re-applied to the new subtree, and
    /// the auto-framing pass is re-armed so the new content gets fitted to the
    /// viewport instead of inheriting the previous subject's camera distance.
    ///
    /// Without this modifier the closure runs exactly once, when the scene is
    /// created — the behaviour of every scene written before this API existed.
    ///
    /// - Parameter id: A value identifying what the content closure builds.
    public func contentID(_ id: some Hashable) -> SceneView {
        var copy = self
        copy.contentIdentity = AnyHashable(id)
        return copy
    }

    /// Called when an entity in the scene is tapped.
    public func onEntityTapped(_ handler: @escaping (Entity) -> Void) -> SceneView {
        var copy = self
        copy.onEntityTapped = handler
        return copy
    }

    /// Enables automatic camera rotation around the scene.
    ///
    /// Passing `0` leaves the camera on its authored pose and starts no
    /// rotation loop at all — the idiom for freezing a scene on a
    /// deterministic frame (screenshot capture, UI tests). Negative values
    /// rotate the other way.
    ///
    /// - Parameter speed: Rotation speed in radians per second. Default 0.3.
    public func autoRotate(speed: Float = 0.3) -> SceneView {
        var copy = self
        copy.enableAutoRotate = true
        copy.autoRotateSpeed = speed
        return copy
    }

    /// Configures the main / key directional light slot.
    ///
    /// Mirrors SceneView Android's `mainLightNode: LightNode? = rememberMainLightNode(engine)`
    /// composable parameter (`SceneView.kt`). Pass `.disabled` for a rare IBL-only setup;
    /// `.custom(LightNode)` to provide your own.
    ///
    /// Default (`.systemDefault`): directional light at `10 000` lux pointing straight
    /// down (`-Y`), shadow casting enabled. Mirrors the Android v4.1.0 BREAKING
    /// render-defaults change finally reaching iOS in v4.2.0.
    ///
    /// ```swift
    /// SceneView { /* ... */ }
    ///   .mainLight(.custom(LightNode.directional(intensity: 5_000)
    ///       .lookAt(.zero)))                              // weaker key, custom angle
    ///
    /// SceneView { /* ... */ }
    ///   .mainLight(.disabled)                              // IBL-only (rare)
    /// ```
    public func mainLight(_ slot: LightSlot) -> SceneView {
        var copy = self
        copy.mainLightSlot = slot
        return copy
    }

    /// Configures the secondary fill directional light slot.
    ///
    /// Mirrors SceneView Android's `fillLightNode: LightNode? = rememberFillLightNode(engine)`
    /// composable parameter (`SceneView.kt`). Pair with ``mainLight(_:)`` to fully
    /// override the key+fill setup.
    ///
    /// Default (`.systemDefault`): ``LightNode/fill(color:intensity:castsShadow:)`` at
    /// `3 000` lux, no shadow, oriented along the canonical Android direction
    /// `(0.5, -0.5, 0.5)` (upper-back-left → down-front-right) so the unlit side of
    /// objects gets a soft kick without flattening them.
    ///
    /// ```swift
    /// SceneView { /* ... */ }
    ///   .fillLight(.custom(LightNode.fill(intensity: 6_000)))   // brighter fill
    ///
    /// SceneView { /* ... */ }
    ///   .fillLight(.disabled)                                    // single-light setup
    /// ```
    public func fillLight(_ slot: LightSlot) -> SceneView {
        var copy = self
        copy.fillLightSlot = slot
        return copy
    }

    /// Applies a coherent set of rendering-quality defaults via a single preset.
    ///
    /// Mirrors Android's `SceneView(renderQuality = ...)` composable parameter and
    /// `View.applyRenderQuality(...)` extension (`RenderQuality.kt`). Choose:
    ///
    /// - ``RenderQuality/cinematic`` — hero shots, product viewers (full shadow distance, IBL ≥ 1.0).
    /// - ``RenderQuality/default`` — out-of-the-box balanced setup (preserves loaded settings).
    /// - ``RenderQuality/performance`` — low-end / AR camera-feed (drops shadows, halves IBL).
    ///
    /// See ``RenderQuality`` for the iOS↔Android parity table — RealityKit's render-options
    /// API is narrower than Filament's so the iOS preset honours what RealityKit exposes
    /// (per-light shadow toggle + IBL intensity exponent) and documents the gaps for
    /// SSAO / MSAA / HDR-buffer-quality / bloom strength which RealityKit does not expose.
    ///
    /// ```swift
    /// SceneView { /* ... */ }
    ///   .renderQuality(.cinematic)         // full key+fill+shadow, bright IBL
    ///
    /// SceneView { /* ... */ }
    ///   .renderQuality(.performance)       // shadows off, IBL halved
    /// ```
    public func renderQuality(_ preset: RenderQuality) -> SceneView {
        var copy = self
        copy.renderQualityPreset = preset
        return copy
    }

    /// Controls whether the library translates the user-content root entity
    /// so its centroid lands at the orbit pivot on the first frame the
    /// scene's `visualBounds` is non-empty. Default `true`. Closes #1026.
    ///
    /// Pass `false` for scenes that rely on intentional off-centre
    /// placement (e.g. carousels, narrative dioramas, story-mode demos
    /// where the camera is meant to look down on content placed below).
    /// All existing demos and code that previously called
    /// `ModelNode(centerOrigin: …)` work either way — the centring is
    /// idempotent and reverses an off-centre placement to origin.
    ///
    /// **iOS-only behaviour vs Android (#1026)**: Android achieves the
    /// same effect per-demo via `ModelNode(centerOrigin = Position.ZERO)`
    /// and there is no library-level auto-centre on Android. Cross-platform
    /// code that ports Android scenes verbatim will see iOS implicitly
    /// re-centre content that was laid out with explicit positions —
    /// pass `false` here to disable the library-level pass and restore
    /// strict Android-parity placement semantics.
    ///
    /// ```swift
    /// SceneView { root in
    ///     // Hero piece placed deliberately off-centre for a horizon-line shot
    ///     root.addChild(model.entity)
    /// }
    /// .autoCenterContent(false)
    /// ```
    public func autoCenterContent(_ enabled: Bool) -> SceneView {
        var copy = self
        copy.autoCenterContentEnabled = enabled
        return copy
    }

    /// Tightens or loosens the fit-to-bounds camera distance chosen by the
    /// auto-framing pass (#1041).
    ///
    /// The pass dollies the orbit camera until the content's **bounding
    /// sphere** fits the narrower frustum axis, then scales that distance by
    /// this margin. Because a sphere fit is orbit-angle invariant, `1.0`
    /// guarantees the content never clips at any azimuth — which is what makes
    /// it safe for an auto-rotating scene.
    ///
    /// - `1.15` (default) — the pre-#2896 framing: the whole bounding sphere
    ///   plus 15 % of air.
    /// - `1.0` — bounding sphere exactly tangent to the frustum.
    /// - `< 1.0` — dollies closer. Only the *empty corners* of the bounding
    ///   sphere leave the frame for a typical mesh, so a subject fills far
    ///   more of a tall portrait viewport. On an `autoRotate` scene stay at or
    ///   above ~`0.95`: the visible azimuth is arbitrary, and a long model
    ///   clips at its broadside angle below that. Used by the store-screenshot
    ///   demos `model-viewer` and `multi-model` — on iOS this is the framing
    ///   lever Android's demo host gets from its `camera_distance` launch
    ///   extra (#2785 tracks the iOS launch-argument half).
    ///
    /// Values are clamped to a sane `0.2 ... 10` range; the fitted distance is
    /// still clamped to the camera's `minRadius` / `maxRadius`.
    ///
    /// ```swift
    /// SceneView { root in
    ///     root.addChild(hero.entity)
    /// }
    /// .framingMargin(0.95)   // hero fills the frame, safe while auto-rotating
    /// ```
    ///
    /// No effect when auto-framing is off (``autoCenterContent(_:)`` set to
    /// `false`) — the camera then keeps whatever radius it already had.
    public func framingMargin(_ margin: Float) -> SceneView {
        var copy = self
        copy.framingMargin = margin.clamped(to: 0.2...10)
        return copy
    }

    /// Seeds the orbit camera's initial pose.
    ///
    /// The defaults (`azimuth: 0`, `elevation: 30°`) look *down* on the
    /// content. With SceneView's 60° vertical FOV, a 30° downward pitch puts
    /// the horizon exactly on the top edge of the frame — so a scene with a
    /// ``SceneEnvironment/showSkybox`` environment shows essentially none of
    /// its sky, however the content is framed. Lower the elevation to bring
    /// the horizon (and the sky above it) into view; that is what makes the
    /// `dynamic-sky` demo read as a sky rather than a lit floor (#2896).
    ///
    /// Only the *initial* pose: dragging, ``autoRotate(speed:)`` and the
    /// fit-to-bounds pass all keep mutating the live camera afterwards, and
    /// the fit changes only the distance, never these angles.
    ///
    /// - Parameters:
    ///   - azimuth: Horizontal orbit angle in radians. `0` faces the content
    ///     head-on. Largely cosmetic on an auto-rotating scene, which sweeps
    ///     past every azimuth anyway.
    ///   - elevation: Vertical orbit angle in radians, positive looking down.
    ///     Clamped on apply to the camera's own
    ///     ``CameraControls/minElevation`` / ``CameraControls/maxElevation``
    ///     (±85° by default), so a seeded pose can never start in gimbal lock.
    ///
    /// ```swift
    /// SceneView { root in /* skyline */ }
    ///     .environment(.outdoor)              // showSkybox == true
    ///     .cameraOrbit(elevation: .pi / 18)   // 10° — horizon in frame
    /// ```
    public func cameraOrbit(azimuth: Float? = nil, elevation: Float? = nil) -> SceneView {
        var copy = self
        if let azimuth { copy.initialOrbitAzimuth = azimuth }
        if let elevation { copy.initialOrbitElevation = elevation }
        return copy
    }

    /// Drives the orbit camera from a pose you own, for as long as you keep
    /// changing it.
    ///
    /// ``cameraOrbit(azimuth:elevation:)`` seeds the pose *once*, during scene setup,
    /// and covers neither distance nor target. This is the continuous form: pass a new
    /// ``SceneCameraPose`` and the camera moves to it on the next frame.
    ///
    /// **A pose is applied only when the value changes.** Handing back the same pose
    /// every frame is a no-op, which is what lets this coexist with the gestures rather
    /// than fight them: the user drags, the camera moves, and the unchanged pose you are
    /// still passing does not yank it back. Pair it with ``onCameraChanged(_:)`` to keep
    /// your own copy current, and write a new pose only when *you* mean to move the
    /// camera.
    ///
    /// ```swift
    /// SceneView { root in /* ... */ }
    ///     .cameraPose(pose)                       // your state drives the camera
    ///     .onCameraChanged { new in               // gestures drive your state
    ///         Task { @MainActor in pose = new }   // hop: see onCameraChanged(_:)
    ///     }
    /// ```
    ///
    /// The hop is not decoration. ``onCameraChanged(_:)`` fires from inside RealityKit's
    /// update pass, so assigning a `@State` directly — including from the fit-to-bounds
    /// re-frame, which also runs there — mutates state during view evaluation.
    ///
    /// A written pose goes through the same clamps a dragged one does — elevation into
    /// ``CameraControls/minElevation``…``CameraControls/maxElevation``, distance into
    /// ``CameraControls/minRadius``…``CameraControls/maxRadius``. ``onCameraChanged(_:)``
    /// reports the clamped result, so a pose that could not be honoured verbatim says so
    /// instead of leaving your state and the screen disagreeing.
    ///
    /// Has no effect in the native camera modes (``CameraControlMode/none``, `.tilt`,
    /// `.dolly`, `.gimbal`), where Apple's `realityViewCameraControls(_:)` owns the
    /// camera transform outright.
    ///
    /// - Parameter pose: The pose to apply, angles in radians. `nil` (the default)
    ///   leaves the camera to gestures, auto-rotate and the framing pass.
    public func cameraPose(_ pose: SceneCameraPose?) -> SceneView {
        var copy = self
        copy.requestedCameraPose = pose
        return copy
    }

    /// Marks the ``cameraPose(_:)`` value as a *new request* even when it is unchanged.
    ///
    /// Only needed by a host that filters its own read-back — a cross-language bridge
    /// mirroring the camera into another runtime's state, where the pose handed back
    /// during a drag must not be mistaken for the app driving the camera. Such a host's
    /// requested pose stays frozen at the app's last write for the whole gesture, so a
    /// re-write of that same pose ("reset view", or re-selecting the current preset)
    /// would compare equal and be dropped, leaving the camera where the finger left it
    /// while the app's own state says otherwise.
    ///
    /// A plain SwiftUI caller never needs this: the documented
    /// `.onCameraChanged { pose = $0 }` pattern keeps their pose tracking the camera, so
    /// a re-write always differs from the live value and lands on its own.
    ///
    /// - Parameter generation: Any value that changes per request. Monotonic is the
    ///   obvious choice.
    func cameraPoseGeneration(_ generation: Int) -> SceneView {
        var copy = self
        copy.requestedCameraPoseGeneration = generation
        return copy
    }

    /// Reports the orbit pose after **every** change to it — each drag tick, each pinch
    /// tick, each auto-rotate step, each fit-to-bounds re-frame.
    ///
    /// This is the half that makes a hoisted camera state honest. Without it a host can
    /// only echo what it last wrote: the user orbits the model, the screen moves, and
    /// every read still returns the initial pose, with nothing anywhere to indicate that
    /// the two have diverged.
    ///
    /// Called on the main actor, from inside RealityKit's update pass. Do not
    /// synchronously mutate SwiftUI state that this same `SceneView` depends on — hop
    /// through a `Task { @MainActor in … }` if you must, or you will re-enter the view
    /// update you are being called from. Storing the value in a plain reference type, or
    /// forwarding it across a bridge, is safe.
    ///
    /// - Parameter handler: Receives the new pose, angles in radians.
    public func onCameraChanged(_ handler: @escaping (SceneCameraPose) -> Void) -> SceneView {
        var copy = self
        copy.onCameraChanged = handler
        return copy
    }

    /// Enables or disables the built-in camera drag / pinch gestures.
    ///
    /// `false` freezes the camera under the user's finger while leaving everything else
    /// working — ``cameraPose(_:)`` still drives it, auto-rotate still runs, per-entity
    /// ``NodeGesture`` handlers still fire, and taps are still delivered.
    ///
    /// This is deliberately *not* ``CameraControlMode/none``. That mode hands the camera
    /// to Apple's `realityViewCameraControls(_:)`, which also switches off SceneView's
    /// own camera math — a scene that merely wanted to lock the viewpoint would find
    /// ``cameraPose(_:)`` silently stop working too.
    ///
    /// - Parameter enabled: Default `true`.
    public func cameraGesturesEnabled(_ enabled: Bool) -> SceneView {
        var copy = self
        copy.cameraGesturesEnabled = enabled
        return copy
    }

    /// Called when an entity is tapped, with a world-space position for it.
    ///
    /// ``onEntityTapped(_:)`` reports *which* entity was tapped; this adds *where it
    /// is*, which is what a host mirroring a hit-test result — a position and a
    /// distance-from-camera — actually needs.
    ///
    /// The position is the tapped entity's bounds centre, not the exact surface point:
    /// RealityKit does not expose one for a SwiftUI `RealityView` outside visionOS.
    /// ``SceneTapHit`` spells out what that does and does not give you, including the
    /// divergence from SceneView Android's true ray-surface `HitResult`. Read it before
    /// building on the value.
    ///
    /// Both handlers can be installed at once and both fire.
    ///
    /// ### Why a different base name rather than `onEntityTapped(hit:)`
    ///
    /// An overload distinguished only by its argument label does **not** keep existing
    /// call sites unambiguous, which is what the obvious naming would have assumed. Under
    /// SE-0286 forward-scan matching an *unlabelled trailing closure* ignores the
    /// parameter's label, so both overloads stay candidates for `.onEntityTapped { … }`
    /// and every closure body that type-checks against both `Entity` and ``SceneTapHit``
    /// becomes ambiguous. Measured: `.onEntityTapped { entity in }` compiles before the
    /// overload exists and fails to compile after it — and that exact line is published
    /// in `llms.txt`, the iOS cheatsheets and the SwiftUI codelab, so the break would
    /// have reached users through the documentation the project tells assistants to read.
    ///
    /// - Parameter handler: Receives the tapped entity and its world-space position.
    public func onEntityTapHit(_ handler: @escaping (SceneTapHit) -> Void) -> SceneView {
        var copy = self
        copy.onEntityTappedHit = handler
        return copy
    }

    /// Declares that this `SceneView` is hosted inside a fully immersive
    /// visionOS `ImmersiveSpace` (the `.full` style).
    ///
    /// A windowed or volumetric `RealityView` on visionOS composites over
    /// passthrough and ignores the RealityKit environment setter, so the
    /// `.environment(_:)` HDR only lights the scene — its `showSkybox` flag
    /// is silently a no-op on visionOS (see #1215). A fully immersive scene
    /// *can* host an HDR background: opt in with this modifier and the
    /// `showSkybox` environment is rendered as an inverted-sphere skybox
    /// (parented under a `WorldComponent` root, since
    /// `RealityViewContent.environment` is unavailable on visionOS).
    ///
    /// No effect on iOS / macOS — those always use the windowed
    /// `.skybox(_:)` path. visionOS does not expose a runtime "am I in an
    /// `ImmersiveSpace`?" query, so this opt-in is required. Closes #1235.
    ///
    /// ```swift
    /// ImmersiveSpace(id: "scene") {
    ///     SceneView { root in /* ... */ }
    ///         .environment(.nightSky)   // showSkybox == true
    ///         .immersiveSpace()         // render the HDR as a skybox
    /// }
    /// .immersionStyle(selection: .constant(.full), in: .full)
    /// ```
    public func immersiveSpace(_ enabled: Bool = true) -> SceneView {
        var copy = self
        copy.immersiveSpace = enabled
        return copy
    }
}

// MARK: - Scene entities holder

/// Holds the persistent RealityKit entity references for a scene.
/// Using a class ensures the Entity objects are never accidentally copied
/// or recreated across SwiftUI re-renders — critical because @State on a
/// reference type does not guarantee identity stability in all SwiftUI versions.
private final class SceneEntities: ObservableObject {
    /// The orbit / scale pivot. Receives the camera-controls rotation and
    /// scale every frame so the scene appears to orbit / zoom around its
    /// origin. Lights are added here so the auto-centering translation
    /// applied to ``contentRoot`` does not move them.
    let root = Entity()
    /// Holds user-supplied content. Translated by the auto-center helper
    /// on the first frame where ``Entity/visualBounds(relativeTo:)`` is
    /// non-empty, so demos placing objects at e.g. `z = -2` show up
    /// visually centred in the viewport without each demo having to
    /// `centerOrigin` itself. Closes #1026.
    let contentRoot = Entity()
    let ibl = Entity()
    #if os(iOS) || os(macOS)
    /// Holds the perspective camera so gesture handlers can mutate its
    /// field-of-view directly. Used by ``CameraControlMode/firstPerson``
    /// pinch — see #1034. visionOS renders through the headset, no manual
    /// camera entity exists.
    let perspCamera = PerspectiveCamera()
    #endif

    deinit {
        // Real teardown path for per-entity gesture handlers (#2038).
        // A `NodeGesture` handler closure that captures the node it is
        // registered on (the documented `onDrag(cube.entity) { cube … }`
        // pattern) forms a retain cycle: entity → GestureHandlers
        // component → closure → node → entity. Stripping the component
        // from every entity in this scene's subtree when the scene's
        // `SceneEntities` is released breaks that cycle, so the content
        // entities and their resources deallocate with the scene instead
        // of leaking for the process lifetime.
        //
        // `deinit` of a non-`@MainActor` class runs on whatever thread
        // released the last reference. `MainActor.assumeIsolated` traps
        // the process if called off-main (#2068). In practice SwiftUI
        // tears `@StateObject`s down on the main actor, but that is not a
        // hard Apple contract. Guard with `Thread.isMainThread`: if we are
        // already on main, use `assumeIsolated` (satisfies the Swift 6
        // strict-concurrency checker — the isolation is real); if not,
        // dispatch synchronously to main first, then `assumeIsolated` inside
        // the sync block (we just ensured we're on main, so the assumption
        // holds). No task, no heap escape — safe in deinit.
        if Thread.isMainThread {
            MainActor.assumeIsolated {
                NodeGesture.removeAllHandlers(under: root)
            }
        } else {
            DispatchQueue.main.sync {
                MainActor.assumeIsolated {
                    NodeGesture.removeAllHandlers(under: root)
                }
            }
        }
    }
}

// MARK: - Internal implementation

/// Internal view that manages the RealityView, camera, gestures, and environment.
private struct SceneViewRepresentation: View {
    let content: (Entity) -> Void
    let sceneEnvironment: SceneEnvironment?
    let cameraControlMode: CameraControlMode
    let onEntityTapped: ((Entity) -> Void)?
    let enableAutoRotate: Bool
    let autoRotateSpeed: Float
    let mainLightSlot: LightSlot
    let fillLightSlot: LightSlot
    let renderQualityPreset: RenderQuality
    /// When true (default), the first frame where the user content's
    /// `visualBounds` is non-empty triggers a one-time translation of
    /// `entities.contentRoot` so the scene's centroid lands at the orbit
    /// pivot. Closes #1026.
    let autoCenterContentEnabled: Bool
    /// Padding factor the auto-framing pass applies to the fitted orbit
    /// radius. Provided by ``SceneView/framingMargin(_:)``; defaults to
    /// ``CameraControls/defaultFitMargin``. Closes #2896.
    let framingMargin: Float
    /// Initial orbit pose seeded once in `setupScene`, from
    /// ``SceneView/cameraOrbit(azimuth:elevation:)``. `nil` keeps the
    /// `CameraControls` defaults. Closes #2896.
    let initialOrbitAzimuth: Float?
    let initialOrbitElevation: Float?
    /// visionOS only: when true, the `showSkybox` HDR environment is
    /// rendered as an inverted-sphere skybox under a `WorldComponent` root
    /// for fully immersive (`ImmersiveSpace`) consumers. Closes #1235.
    let immersiveSpace: Bool

    /// When true, re-entering `.orbit` resets the orbit pivot to the
    /// content centroid so repeated pan→orbit cycles don't drift. Closes #1236.
    let recentersTargetOnOrbit: Bool

    /// Identity of what the `content` closure builds, from
    /// ``SceneView/contentID(_:)``. `nil` means the closure runs once in
    /// `setupScene` and never again — the pre-#3008 behaviour. Any other value
    /// makes a change re-run the closure under the *same* `RealityView`.
    let contentIdentity: AnyHashable?

    /// Host-written camera pose from ``SceneView/cameraPose(_:)``. Applied in
    /// `applyCamera()` only when the *value* changed since the last application,
    /// so re-passing the same pose every frame never fights a live drag.
    let requestedCameraPose: SceneCameraPose?

    /// Stamp making an unchanged `requestedCameraPose` count as a fresh request.
    let requestedCameraPoseGeneration: Int

    /// Camera read-back from ``SceneView/onCameraChanged(_:)``. Fired from
    /// `applyCamera()` at the single point that already knows the camera really
    /// moved — the far side of the #2331 diff-guard.
    let onCameraChanged: ((SceneCameraPose) -> Void)?

    /// Gate for the built-in drag / pinch camera gestures, from
    /// ``SceneView/cameraGesturesEnabled(_:)``.
    let cameraGesturesEnabled: Bool

    /// Tap handler that also wants the world-space hit point, from
    /// ``SceneView/onEntityTapHit(_:)``.
    let onEntityTappedHit: ((SceneTapHit) -> Void)?

    /// Mutable camera-orbit state, held in a **reference type** so mutating it
    /// (auto-rotate, drag, pinch) does NOT invalidate the SwiftUI body. The
    /// value default `CameraControls(mode: .orbit)` uses the struct's own
    /// `orbitRadius = 2.0` public default, which matches the camera-
    /// to-target distance of the pre-v4.4.0 fake-orbit `[0, 0.3, 2]`
    /// so existing demos retain their on-screen framing. Direct
    /// constructors of `CameraControls` see the same `2.0` value, so
    /// there is no internal / external default split.
    ///
    /// **Why a box (#2277):** when this was `@State private var camera:
    /// CameraControls` (a value type), the auto-rotate `.task` loop called
    /// `camera.applyAutoRotation(dt:)` every ~16 ms → every mutation
    /// invalidated the entire `body`, rebuilding all six `Gesture` chains and
    /// re-running `RealityView.update:` (applyCamera + refreshLightSlot ×2 +
    /// refreshContentCentering + skybox diff) at 60 Hz for the lifetime of any
    /// auto-rotating scene. Holding the struct inside a reference type means a
    /// mutation never changes the `@State` value (the box reference), so
    /// SwiftUI never sees a state change — the same trick already used for
    /// `AppliedCache` and `SceneEntities` below. The camera transform is
    /// instead pushed onto the entity directly by calling `applyCamera()` from
    /// the mutating sites (the auto-rotate task and the drag / pinch gesture
    /// handlers), decoupling the visual update from SwiftUI body re-evaluation.
    private final class CameraControlsBox {
        var value: CameraControls
        init(_ value: CameraControls) { self.value = value }
    }
    @State private var cameraBox = CameraControlsBox(CameraControls(mode: .orbit))

    /// Convenience accessor for the boxed camera state. Reads and writes go
    /// through the reference, so a write here never invalidates the body.
    private var camera: CameraControls {
        get { cameraBox.value }
        nonmutating set { cameraBox.value = newValue }
    }
    @StateObject private var entities = SceneEntities()
    @State private var lastDragTranslation: CGSize = .zero

    /// Per-entity drag baseline + the cumulative→per-frame-delta conversion that
    /// `entityDragGesture` performs, extracted into the directly-testable
    /// ``EntityDragState`` (#2313). Held in a reference type so the per-frame
    /// writes during a drag never change this `@State` value and so never
    /// invalidate the SwiftUI body / re-run `RealityView.update:` (same rationale
    /// as `cameraBox`, #2277/#2283).
    @State private var entityDragState = EntityDragState()

    @State private var initialPinchRadius: Float? = nil
    /// Snapshotted FOV when a pinch begins in ``CameraControlMode/firstPerson``
    /// — kept separate from `initialPinchRadius` so the orbit/pan dolly path
    /// stays untouched. Closes #1034.
    @State private var initialPinchFov: Float? = nil
    @State private var isDragging = false

    /// Set to `true` once ``refreshContentCentering()`` has translated
    /// `entities.contentRoot` AND dollied the orbit radius to fit the
    /// content bounds AND the content bounds have stopped growing across
    /// consecutive framing ticks, so subsequent frames are a cheap no-op.
    /// Closes #1026 / #1041 / #1391.
    @State private var didCenterContent = false

    /// Tracks whether the content-union AABB has held steady long enough to
    /// latch the framing pass. `refreshContentCentering()` re-frames whenever
    /// the union diagonal grows (a streamed model just landed) and latches
    /// `didCenterContent` only once the diagonal has been stable for a
    /// sustained wall-clock window — see ``FramingStabilityTracker``.
    ///
    /// This replaces #1514's fragile "two consecutive ticks" latch, which
    /// fired inside the multi-second gap between two streamed `Multi-Model
    /// Park` models landing and so froze the camera on a partial 1–2-model
    /// union (#1391, reopened). The duration-based hold spans those gaps.
    @State private var framingStability = FramingStabilityTracker(
        epsilon: SceneViewRepresentation.framingStabilityEpsilon,
        stableHoldSeconds: SceneViewRepresentation.framingStableHoldSeconds
    )

    /// World-space centroid of the content union AABB, as last computed by
    /// ``refreshContentCentering()``. The auto-framing pass points the orbit
    /// pivot (`camera.target`) here instead of translating `contentRoot`,
    /// which is what avoids the `AnchorEntity` re-pin feedback loop (#1391).
    /// Cached so a `recentersTargetOnOrbit` re-entry can snap the pivot back
    /// to the content centroid — the content is no longer assumed to sit at
    /// the world origin. `.zero` until the first valid framing pass.
    @State private var contentWorldCenter: SIMD3<Float> = .zero

    /// Monotonic counter bumped by the content-framing driver task while
    /// ``didCenterContent`` is still `false`. Reading it inside the
    /// `RealityView` body forces SwiftUI to re-evaluate the view — and
    /// therefore re-run the `update:` closure — every ~33 ms until the
    /// async-loaded content's `visualBounds` becomes valid and the
    /// fit-to-bounds pass runs. Without this driver the `update:` closure
    /// only fires on the handful of frames right after `setupScene`
    /// (before streamed models populate) and then stalls, so the camera
    /// never re-frames. Closes #1026 / #1041.
    @State private var framingTick: Int = 0

    /// Identity of the framing-driver task. Bumped by
    /// ``rebuildContentIfNeeded()`` so a `.contentID(_:)` swap restarts the
    /// driver: the driver exits for good once ``didCenterContent`` latches (or
    /// after its timeout), so merely clearing the latch would leave the new
    /// content un-framed on any static scene — nothing else pumps `update:`
    /// once auto-rotate is driving `applyCamera()` directly. Closes #3008.
    @State private var framingEpoch: Int = 0

    /// Live viewport aspect ratio (`width / height`), captured by the
    /// `GeometryReader` wrapping the `RealityView`. Drives the
    /// fit-to-bounds framing (#1041) so the camera distance accounts for
    /// the real frustum width — portrait phones are `< 1` and clip
    /// horizontally if the framing assumes a square viewport. `nil` until
    /// the first layout pass; the fit math falls back to the iPhone-16e
    /// portrait ratio while unknown.
    @State private var viewportAspect: Float? = nil

    // Reactive light-slot caches — compared in `update:` closure to detect when the
    // caller mutated `.mainLight(_:)` / `.fillLight(_:)` so the corresponding entity
    // can be swapped in-place without a full RealityView teardown. Closes #1017.
    //
    // `appliedCache.skyboxResource` mirrors the discipline above for the HDR skybox:
    // compared against `loadedSkyboxResource` so `content.environment` is only
    // reassigned when the resource identity actually changes (~10-50 µs/frame saved).
    //
    // All are held in a reference-type class so mutations inside
    // `RealityView.update:` don't trigger SwiftUI's "Modifying state during view
    // update" warning. A class property mutation never changes the @State value
    // (the reference), so SwiftUI never detects a state change during body evaluation.
    //
    // `main/fillLightEntity` cache the provisioned light entity by reference so
    // `refreshLightSlot` can remove the previous light directly (#2278) instead
    // of walking `entities.root.children.first { … }` on every slot change —
    // mirroring `ARSceneView`'s `coordinator.main/fillLightAnchor` pattern
    // (`ARSceneView.swift`). `immersiveSkyboxHost` does the same for the
    // visionOS inverted-sphere skybox host so `refreshImmersiveSkybox` likewise
    // skips the children tree-walk. The slot guard means these reads happen only
    // on a genuine slot change today, but caching the ref keeps the path O(1)
    // and guards against a future equality regression turning a per-frame
    // no-op into an O(n) children walk.
    // `AppliedCameraState` (the per-frame camera diff-guard snapshot) lives in
    // its own top-level `internal` file — `Camera/AppliedCameraState.swift` —
    // so the diff-guard can be unit-tested against the same code `applyCamera()`
    // runs (#2412, mirroring the #2313 `EntityDragState` extraction).
    private final class AppliedCache {
        var mainSlot: LightSlot? = nil
        var fillSlot: LightSlot? = nil
        var skyboxResource: EnvironmentResource? = nil
        /// Last camera state pushed onto the RealityKit entities by
        /// ``applyCamera()``. Compared (with float tolerance) each frame so a
        /// no-op camera apply — the common case while idle, and on every
        /// auto-rotate / framing-task `update:` tick where the orbit did not
        /// actually move — skips the redundant entity-transform writes. `nil`
        /// until the first apply. Closes #2331.
        var camera: AppliedCameraState? = nil
        /// Last ``SceneView/cameraPose(_:)`` value written onto `camera`. The
        /// application is gated on this changing, not on the pose differing from
        /// the live camera: a host that keeps passing the pose it was last told
        /// about must not thereby pin the camera and cancel the drag in progress.
        /// `nil` until the first host-written pose. Distinguished from "no pose
        /// requested" by `requestedCameraPose` itself being `nil`.
        var requestedPose: SceneCameraPose? = nil
        /// Generation of the last applied request. Compared alongside the pose so a
        /// re-request of an identical value is honoured when the caller says it is new.
        var requestedPoseGeneration: Int = 0
        /// The currently-attached main-slot light entity, cached by reference
        /// for direct removal in `refreshLightSlot`. `nil` when the main slot
        /// is `.disabled`. Closes #2278.
        var mainLightEntity: Entity? = nil
        /// The currently-attached fill-slot light entity, cached by reference
        /// for direct removal in `refreshLightSlot`. `nil` when the fill slot
        /// is `.disabled`. Closes #2278.
        var fillLightEntity: Entity? = nil
        #if os(visionOS)
        /// The currently-attached visionOS immersive-skybox host entity, cached
        /// by reference for direct removal in `refreshImmersiveSkybox`. `nil`
        /// when no skybox host is attached. Closes #2278.
        var immersiveSkyboxHost: Entity? = nil
        #endif
        /// `true` once `setupScene` has populated the content root at least
        /// once. Guards the `.contentID(_:)` rebuild against firing before
        /// the scene exists — SwiftUI gives no ordering guarantee between a
        /// `.task(id:)` and the `RealityView` `make:` closure. Closes #3008.
        var didBuildContent = false
        /// The ``SceneViewRepresentation/contentIdentity`` the currently-built
        /// content was built from, so an identity that did not actually change
        /// (a re-run `.task(id:)`) rebuilds nothing. Closes #3008.
        var contentIdentity: AnyHashable? = nil
    }
    @State private var appliedCache = AppliedCache()

    /// Loaded HDR resource cached for the `RealityView.update:` closure so
    /// it can apply `content.environment = .skybox(resource)` every frame
    /// when the scene environment has `showSkybox == true`. Stays `nil`
    /// when the IBL should light the scene but not render as a background.
    /// Ported from Eliott Radcliffe's sceneview-swift PR #1.
    @State private var loadedSkyboxResource: EnvironmentResource? = nil

    #if os(visionOS)
    /// visionOS-only: the equirectangular HDR texture loaded for the
    /// immersive-space skybox. `RealityViewContent.environment` is
    /// `@available(visionOS, unavailable)`, so the windowed `.skybox(_:)`
    /// path above cannot be used — instead this texture is mapped onto an
    /// inverted sphere by ``VisionOSSkybox``. Loaded in the same `.task(id:)`
    /// closure as the IBL; `nil` while loading, on failure, or when the
    /// environment has `showSkybox == false`. Closes #1235.
    @State private var loadedImmersiveSkyboxTexture: TextureResource? = nil

    /// HDR resource name of the immersive skybox host currently attached to
    /// the scene — compared against the loaded environment in `update:` so
    /// the inverted-sphere host is rebuilt only when the resource changes.
    /// Same diff-write discipline as `appliedCache.skyboxResource` / the light
    /// slots. Closes #1235.
    @State private var appliedImmersiveSkyboxResource: String? = nil
    #endif

    var body: some View {
        cameraInteractionView
            .simultaneousGesture(tapGesture)
            // Per-entity gestures — dispatched to handlers registered via
            // `Entity.onTap / onDrag / onScale / onRotate / onLongPress`
            // (NodeGesture system). Each gesture is `.targetedToAnyEntity()`
            // so it only fires when the user touches a real entity with a
            // collision shape, and the camera-control gestures above keep
            // working on empty-space drags. Closes the #928 silent-stub
            // batch for NodeGesture dispatch.
            .simultaneousGesture(entityTapGesture)
            .simultaneousGesture(entityDragGesture)
            .simultaneousGesture(entityMagnifyGesture)
            .simultaneousGesture(entityRotateGesture)
            .simultaneousGesture(entityLongPressGesture)
            .task {
                // Auto-rotation loop — custom modes only (#1049).
                // For native-mode cameras (none/tilt/dolly/gimbal) Apple owns
                // the camera transform, so our azimuth mutation would fight it.
                //
                // A zero speed exits here rather than spinning a 60 Hz timer
                // that advances the azimuth by 0 rad every frame: callers freeze
                // a scene by passing `autoRotate(speed: 0)` (the QA-capture
                // path does exactly that), and that read as "enabled" (#2896).
                guard enableAutoRotate, autoRotateSpeed != 0, cameraControlMode.isCustom else { return }
                camera.isAutoRotating = true
                camera.autoRotateSpeed = autoRotateSpeed
                var lastTime = CFAbsoluteTimeGetCurrent()
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 16_666_667) // ~60 fps
                    let now = CFAbsoluteTimeGetCurrent()
                    let dt = Float(now - lastTime)
                    lastTime = now
                    if !isDragging {
                        // Mutate the boxed orbit state (no body invalidation —
                        // #2277) and push the new transform straight onto the
                        // camera entity. `applyCamera()` only reads `camera` and
                        // writes `entities.*`, so calling it here reproduces the
                        // per-frame visual update the `RealityView.update:`
                        // closure used to give us when this loop forced a body
                        // re-eval every 16 ms — but now without rebuilding the
                        // gesture chains, the light-slot diffs, the framing pass,
                        // or the skybox diff every frame. `.task` is MainActor-
                        // isolated, so the `@MainActor applyCamera()` call is safe.
                        camera.applyAutoRotation(dt: dt)
                        applyCamera()
                    }
                }
            }
            .task(id: contentIdentity) {
                // Re-run the content closure in place when `.contentID(_:)`
                // reports the caller would now build something different.
                // Keyed rather than `onChange`-driven so the closure that runs
                // is the one from the latest body evaluation — the same
                // guarantee the environment loader below relies on. Closes #3008.
                rebuildContentIfNeeded()
            }
            .task(id: framingEpoch) {
                // Content-framing driver (#1026 / #1041). The RealityView
                // `update:` closure only fires while SwiftUI re-evaluates
                // the view — for a static (non-auto-rotating) scene that is
                // just the first few frames after `setupScene`, before
                // async-loaded models / streamed Sketchfab assets have
                // populated their meshes. Their `visualBounds` is still
                // empty then, so the fit-to-bounds pass keeps deferring and
                // the camera never re-frames.
                //
                // This task bumps `framingTick` (which the RealityView body
                // reads) every ~33 ms so `update:` keeps running until the
                // bounds become valid, every streamed model has landed, and
                // `refreshContentCentering()` flips `didCenterContent`. It
                // then exits — zero ongoing cost for the rest of the scene's
                // lifetime.
                //
                // The timeout caps the poll for content that never produces
                // bounds. It must exceed the streamed-load window of the
                // worst-case demo: `Multi-Model Park` streams four glTF
                // models concurrently over 30–70 s on the simulator. #1514's
                // 10 s cap stopped the driver long before the last models
                // landed, so the camera never re-framed for them (#1391,
                // reopened) — 90 s comfortably spans the streaming window
                // while still bounding a genuinely stuck scene.
                guard autoCenterContentEnabled else { return }
                var elapsed: UInt64 = 0
                let interval: UInt64 = 33_000_000          // ~30 Hz
                let timeout: UInt64 = 90_000_000_000       // 90 s
                while !Task.isCancelled && !didCenterContent && elapsed < timeout {
                    try? await Task.sleep(nanoseconds: interval)
                    elapsed += interval
                    framingTick &+= 1
                }
            }
            .task(id: "\(sceneEnvironment?.name ?? "")|\(sceneEnvironment?.showSkybox == true)") {
                // Load and apply IBL environment when it changes. The id
                // also tracks `showSkybox` so toggling the skybox flag on
                // the same environment re-runs the loader and updates
                // `loadedSkyboxResource` for the update: closure.
                //
                // Clear the cached resource BEFORE the await so the
                // `update:` closure's diff falls back to `.default`
                // during the load window — without this, switching
                // from `.studio` → `.night` would keep the studio
                // skybox visible under the night IBL for the 50-300 ms
                // it takes the new HDR to decode (~30 MB at 4K). The
                // next frame after `loadEnvironment` completes will
                // diff the new resource in.
                loadedSkyboxResource = nil
                #if os(visionOS)
                loadedImmersiveSkyboxTexture = nil
                #endif
                guard let env = sceneEnvironment else { return }
                await loadEnvironment(env)
            }
    }

    // MARK: - Camera interaction layer (#1049)

    /// Wraps `realityViewContent` with the appropriate camera-interaction
    /// mechanism for the current ``CameraControlMode``:
    ///
    /// - **Custom modes** (`.orbit`, `.pan`, `.firstPerson`): applies
    ///   SceneView's own `DragGesture` + `MagnifyGesture` wiring. Apple's
    ///   `realityViewCameraControls` is **not** applied — our hand-rolled
    ///   math gives orbit inertia, auto-rotate, and fit-to-bounds framing
    ///   that the Apple modifier does not support.
    ///
    /// - **Native modes** (`.none`, `.tilt`, `.dolly`, `.gimbal`): applies
    ///   Apple's `realityViewCameraControls(_:)` modifier and skips the custom
    ///   drag / pinch gestures entirely. The Apple modifier drives the camera
    ///   directly — `applyCamera()` is a no-op for these modes (#1049 Phase 2).
    @ViewBuilder
    private var cameraInteractionView: some View {
        // The mode switch is inlined here to let Swift infer the
        // `CameraControls` type for `realityViewCameraControls(_:)` from the
        // call site, avoiding the ambiguity between `RealityKit.CameraControls`
        // and `SceneViewSwift.CameraControls` that arises from an explicit
        // return-type annotation on a helper property. (#1049)
        switch cameraControlMode {
        case .orbit, .pan, .firstPerson:
            // Hand-rolled gesture path. Apple's `realityViewCameraControls`
            // is not applied — our custom math drives orbit inertia,
            // auto-rotate, and fit-to-bounds framing.
            realityViewContent
                .gesture(dragGesture)
                .gesture(pinchGesture)
        #if os(visionOS)
        case .none, .tilt, .dolly, .gimbal:
            // `realityViewCameraControls(_:)` is entirely unavailable on
            // visionOS — fall back to the hand-rolled orbit gesture path.
            realityViewContent
                .gesture(dragGesture)
                .gesture(pinchGesture)
        #elseif os(macOS)
        case .none:
            realityViewContent.realityViewCameraControls(.none)
        case .tilt:
            realityViewContent.realityViewCameraControls(.tilt)
        case .dolly:
            realityViewContent.realityViewCameraControls(.dolly)
        case .gimbal:
            // CameraControls.gimbal is iOS/iPadOS-only — RealityKit on macOS
            // does not expose this mode. Fall back to the orbit gesture path.
            realityViewContent
                .gesture(dragGesture)
                .gesture(pinchGesture)
        #else
        case .none:
            realityViewContent.realityViewCameraControls(RealityKit.CameraControls.none)
        case .tilt:
            realityViewContent.realityViewCameraControls(RealityKit.CameraControls.tilt)
        case .dolly:
            realityViewContent.realityViewCameraControls(RealityKit.CameraControls.dolly)
        case .gimbal:
            // CameraControls.gimbal requires iOS 18.2+ / macOS 15.2+ SDK.
            // Older SDK versions (Xcode 16.0–16.1) do not expose this member,
            // causing a compile error. Fall back to the orbit gesture path
            // until the minimum deployment / CI SDK is confirmed to be ≥ 18.2.
            // TODO(#1049 Phase 3): enable `realityViewCameraControls(.gimbal)`
            // when all CI runners use Xcode 16.2+ (iOS 18.2 SDK).
            realityViewContent
                .gesture(dragGesture)
                .gesture(pinchGesture)
        #endif
        }
    }

    @ViewBuilder
    private var realityViewContent: some View {
        // GeometryReader captures the live viewport aspect ratio so the
        // fit-to-bounds framing (#1041) accounts for the real frustum
        // width. Portrait phones have aspect < 1 and would clip content
        // horizontally if the framing assumed a square viewport.
        GeometryReader { proxy in
            realityView
                .onChange(of: proxy.size) { _, newSize in
                    updateViewportAspect(newSize)
                }
                .onAppear { updateViewportAspect(proxy.size) }
                // The framing-driver task bumps `framingTick` every ~33 ms
                // until the content is framed. `onChange` is a guaranteed
                // delivery point (unlike relying on the RealityView
                // `update:` closure re-firing), so the fit-to-bounds pass
                // runs here directly until it succeeds. Closes #1026 / #1041.
                .onChange(of: framingTick) { _, _ in
                    if autoCenterContentEnabled {
                        refreshContentCentering()
                    }
                }
        }
    }

    /// Records the viewport aspect ratio from a layout size. Re-frames the
    /// content on the next `update:` tick if the aspect changed materially
    /// (device rotation, split-view resize) so the fit stays correct.
    private func updateViewportAspect(_ size: CGSize) {
        guard size.width > 0, size.height > 0 else { return }
        let aspect = Float(size.width / size.height)
        let previous = viewportAspect
        viewportAspect = aspect
        // A rotation / resize after the initial fit invalidates the framing.
        // Re-arm the one-shot pass so the next frame re-fits to the new
        // frustum. Threshold avoids re-framing on sub-pixel layout jitter.
        if let previous, abs(previous - aspect) > 0.01 {
            didCenterContent = false
        }
    }

    @ViewBuilder
    private var realityView: some View {
        // `RealityView(make:update:)` with an `inout RealityViewCameraContent`
        // is `@available(visionOS, unavailable)` — visionOS renders through the
        // headset's spatial compositor and has no virtual-camera content type.
        // Use the cross-platform `RealityViewContent` initializer on visionOS
        // and the camera-content one on iOS / macOS (where `.camera = .virtual`
        // is required to avoid the simulator black screen).
        #if os(visionOS)
        RealityView { content in
            setupScene(&content)
        } update: { content in
            applyCamera()
            // Also here, not only in the `.task(id:)`: `update:` always runs on
            // the CURRENT view, so this closes the one ordering hole the task
            // cannot — a `make:` that ran from a stale view value would record
            // an identity the task has already moved past, and nothing else
            // would ever re-fire. Costs one `AnyHashable?` compare per tick,
            // and short-circuits on `nil != nil` for every scene that does not
            // use `.contentID(_:)`. Closes #3008.
            rebuildContentIfNeeded()
            refreshLightSlot(.main, slot: mainLightSlot)
            refreshLightSlot(.fill, slot: fillLightSlot)
            if autoCenterContentEnabled {
                refreshContentCentering()
            }
            // visionOS immersive-space skybox. `RealityViewContent.environment`
            // is unavailable on visionOS, so a fully immersive consumer that
            // opted in via `.immersiveSpace()` gets the HDR rendered as an
            // inverted-sphere skybox under a `WorldComponent` root instead.
            // Closes #1235.
            refreshImmersiveSkybox()
        }
        #else
        RealityView { realityContent in
            setupScene(&realityContent)
        } update: { content in
            applyCamera()
            // Re-run the content closure if `.contentID(_:)` moved. Duplicated
            // from the `.task(id:)` on purpose: `update:` always runs on the
            // CURRENT view, so it closes the one ordering hole the task cannot
            // — a `make:` that ran from a stale view value would record an
            // identity the task has already moved past, and nothing else would
            // ever re-fire, leaving the previous model on screen for good.
            // Costs one `AnyHashable?` compare per tick, and short-circuits on
            // `nil != nil` for every scene that does not use the modifier.
            rebuildContentIfNeeded()
            // Diff light slots and swap entities when the caller's modifier value
            // changed since last frame. Closes #1017 (Android `prevFillLightRef`
            // pattern in `SceneView.kt:287-305` ported to iOS).
            refreshLightSlot(.main, slot: mainLightSlot)
            refreshLightSlot(.fill, slot: fillLightSlot)
            // Auto-center user content the first frame where `visualBounds`
            // becomes non-empty (i.e. async-loaded models have populated).
            // No-op once centered; opt-out via `.autoCenterContent(false)`.
            // Closes #1026.
            if autoCenterContentEnabled {
                refreshContentCentering()
            }
            // Apply the HDR resource as a skybox background when the scene
            // environment has `showSkybox = true`. Without this the IBL
            // affects lighting but the background stays default — visible
            // as the same neutral void regardless of environment chosen.
            // visionOS uses passthrough by default and ignores the
            // RealityKit `environment` API; gate to iOS + macOS only.
            // Ported from Eliott Radcliffe's sceneview-swift PR #1.
            //
            // Diff-write: only assign when the resource identity changed
            // since the last applied value. Same `applied*` cache pattern
            // as the light-slot reactive plumbing above. Saves an ARC
            // bump + descriptor re-validation per frame (`content.environment`
            // is not a free no-op — internally re-wraps the resource
            // even when assigned an equal value).
            if loadedSkyboxResource !== appliedCache.skyboxResource {
                if let resource = loadedSkyboxResource {
                    content.environment = .skybox(resource)
                } else {
                    content.environment = .default
                }
                appliedCache.skyboxResource = loadedSkyboxResource
            }
        }
        #endif
    }

    // MARK: - Scene Setup

    #if os(visionOS)
    /// The RealityView content type for the current platform.
    ///
    /// visionOS renders through the headset's spatial compositor and has no
    /// virtual-camera content type — `RealityViewCameraContent` is
    /// `@available(visionOS, unavailable)`. The cross-platform
    /// `RealityViewContent` is used instead.
    private typealias RealitySceneContent = RealityViewContent
    #else
    private typealias RealitySceneContent = RealityViewCameraContent
    #endif

    /// Populates the RealityView content tree. The content type resolves to
    /// `RealityViewCameraContent` on iOS / macOS and `RealityViewContent` on
    /// visionOS (see ``RealitySceneContent``).
    private func setupScene(_ realityContent: inout RealitySceneContent) {
        // Seed the initial orbit pose before anything reads the camera. Done
        // here (once, on RealityView `make:`) rather than in the `@State`
        // initializer, which cannot see this view's stored properties. Drag,
        // auto-rotate and the fit-to-bounds pass own the camera from here on —
        // the fit only changes the distance, so these angles survive it.
        // Closes #2896.
        if let azimuth = initialOrbitAzimuth {
            camera.azimuth = azimuth
        }
        if let elevation = initialOrbitElevation {
            camera.elevation = elevation.clamped(
                to: camera.minElevation...camera.maxElevation
            )
        }

        // Use virtual camera (fixed perspective) instead of AR spatial tracking.
        // Without this, RealityKit defaults to .spatialTracking which requires a
        // physical device camera — causing a black screen in the simulator.
        #if !os(visionOS)
        // Virtual camera: fixed perspective, no AR spatial tracking.
        // spatialTracking (the default) requires a physical device camera →
        // black screen in the simulator.
        realityContent.camera = .virtual

        // Perspective camera: positioned for a nice 3/4 view.
        // Content is placed at origin (0,0,0) and rotated by applyCamera() to
        // simulate orbit. The slight Y elevation gives a natural bird's-eye angle.
        // look(at:from:) handles both position and orientation — in RealityKit,
        // entities face +Z by default so explicit orientation is required.
        // We keep a ref on the SceneEntities holder so `.firstPerson` pinch can
        // mutate the FOV at runtime (#1034).
        entities.perspCamera.camera.fieldOfViewInDegrees = 60
        entities.perspCamera.look(at: .zero, from: [0, 0.3, 2], relativeTo: nil)
        realityContent.add(entities.perspCamera)
        #endif

        // Root entity holds all user content
        realityContent.add(entities.root)
        // Intermediate contentRoot — user content goes here so the
        // auto-center pass (refreshContentCentering, called from the
        // RealityView.update: closure) can translate it independently of
        // lights, IBL, and camera. Closes #1026.
        entities.root.addChild(entities.contentRoot)

        // IBL entity (will be configured when environment loads)
        realityContent.add(entities.ibl)

        // Provision both light slots via the shared helper so the same code path runs
        // on initial setup AND on reactive swap (refreshLightSlot below). Lights are
        // tagged with `LightSlotMarker` so the diff in `update:` can locate + remove
        // them when the caller's modifier value changes. Closes #1017.
        provisionLightSlot(.main, slot: mainLightSlot)
        provisionLightSlot(.fill, slot: fillLightSlot)
        appliedCache.mainSlot = mainLightSlot
        appliedCache.fillSlot = fillLightSlot

        // Populate user content. Goes into `contentRoot` (a child of root)
        // instead of `root` directly so the auto-center translation in
        // `refreshContentCentering()` only affects user content, not the
        // lights provisioned just above.
        buildContent(isInitialBuild: true)
    }

    // MARK: - Content (re)build (#3008)

    /// Runs the caller's content closure into `entities.contentRoot` and
    /// applies the render-quality preset to the resulting tree.
    ///
    /// Shared by the initial `setupScene` and the ``SceneView/contentID(_:)``
    /// rebuild so a swapped model goes through exactly the same provisioning
    /// as the first one — a second code path here is how a rebuilt scene ends
    /// up with different shadows or IBL from a freshly-created one.
    ///
    /// - Parameter isInitialBuild: `true` from `setupScene`, `false` from a
    ///   `.contentID(_:)` swap. It gates the IBL half of the render-quality
    ///   preset, and it has to: `applyRenderQuality`'s IBL branch is written in
    ///   RAW EXPONENTS, not the linear multipliers `SceneEnvironment.intensity`
    ///   speaks since #2897, and it says in as many words that this is latent
    ///   only because its sole caller runs *before* the async `loadEnvironment`
    ///   installs the `ImageBasedLightComponent`, so it always finds no
    ///   component and no-ops. A swap runs long after that load, so passing the
    ///   receiver here would wake those inverted units up and pop the scene's
    ///   brightness on the first swap under `.cinematic` / `.performance`.
    ///   The shadow / light half still applies to the new subtree either way.
    @MainActor
    private func buildContent(isInitialBuild: Bool) {
        content(entities.contentRoot)

        // Make the whole content subtree eligible for entity-targeted SwiftUI gestures.
        //
        // Done here, on the root, rather than in each node factory: `GeometryNode`,
        // `MeshNode`, `TextNode`, `ImageNode`, `ShapeNode`, `ViewNode` and `PhysicsNode`
        // all call `generateCollisionShapes` for hit testing, and a collision shape alone
        // is not enough — see ``Entity/makeInputTargetable()``. Fixing only the loader
        // would leave the repo's own `CollisionHitTestDemo`, which builds
        // `GeometryNode.cube`, still unable to receive the tap it exists to demonstrate.
        // One call on the root covers every node type, including any added later.
        //
        // Harmless on entities with no collision shape: `InputTargetComponent` makes an
        // entity *eligible* for hit-testing, it does not create a hit target.
        entities.contentRoot.makeInputTargetable()

        // Apply RenderQuality preset to all directional lights + the IBL receiver entity.
        // Runs AFTER lights + content are added so the preset walks the full scene tree.
        // Cinematic bumps shadow distance + ensures IBL ≥ 1.0; Performance drops shadows
        // + halves IBL. Default preserves whatever the scene loaded. Mirrors Android's
        // `View.applyRenderQuality(RenderQuality.X)` pattern (#1004).
        _ = applyRenderQuality(
            renderQualityPreset,
            to: entities.root,
            iblReceiver: isInitialBuild ? entities.ibl : nil
        )

        appliedCache.didBuildContent = true
        appliedCache.contentIdentity = contentIdentity
    }

    /// Swaps the scene's content in place when ``SceneView/contentID(_:)``
    /// reports a change, without touching the `RealityView` itself.
    ///
    /// This is the fix for #3008: the demos used to re-key the whole view with
    /// SwiftUI's `.id(_:)` to show a different model, which destroys and
    /// re-creates the `RealityView` — and a re-created `RealityView` on iOS 26
    /// Simulator intermittently renders nothing at all (no model, no skybox)
    /// for the rest of its life. Rebuilding under a stable view keeps the
    /// renderer that is already working.
    @MainActor
    private func rebuildContentIfNeeded() {
        // `.task(id:)` also fires on first appearance, and SwiftUI does not
        // order it against the `RealityView` `make:` closure. If the scene has
        // not been set up yet there is nothing to rebuild — `setupScene` will
        // build the current identity itself.
        //
        // This early return is why `update:` calls this method too: it means a
        // pre-`make:` invocation is a no-op, so if `make:` then ran from a
        // stale view value the task would never fire again (its id has already
        // settled) and the scene would keep the wrong content forever. The
        // `update:` call is on the current view by construction and corrects
        // that on the very next tick.
        guard appliedCache.didBuildContent else { return }
        guard appliedCache.contentIdentity != contentIdentity else { return }

        // Detach the previous content. Unregister its gesture handlers first:
        // a handler closure that captures the node it is registered on forms a
        // retain cycle (entity → GestureHandlers → closure → node → entity), so
        // without this the outgoing model stays alive for the process lifetime
        // and every swap leaks another one — the same cycle `SceneEntities.deinit`
        // breaks at scene teardown (#2038).
        // `under: contentRoot`, not per-child: a handler registered on the root
        // the closure is handed (`root.onTap { … }` capturing the outgoing
        // node) is not under any child, so a per-child sweep would leave that
        // cycle intact and retain the previous model until scene teardown.
        // The closure re-registers whatever it wants on the root when it runs
        // again, just below.
        NodeGesture.removeAllHandlers(under: entities.contentRoot)
        for child in Array(entities.contentRoot.children) {
            child.removeFromParent()
        }

        buildContent(isInitialBuild: false)

        // Re-arm the auto-framing pass for the new content. Without this the
        // new subject inherits the previous one's orbit radius and pivot —
        // `refreshContentCentering()` latches `didCenterContent` once the first
        // subject's bounds hold steady and then returns immediately forever.
        // The stability tracker has to be re-armed too, or the new bounds are
        // compared against the *old* subject's diagonal and latch on the first
        // tick, before a streamed model has finished landing (#1391).
        framingStability = FramingStabilityTracker(
            epsilon: Self.framingStabilityEpsilon,
            stableHoldSeconds: Self.framingStableHoldSeconds
        )
        didCenterContent = false
        // Restart the driver task: it exits permanently once the latch is set,
        // and clearing the latch alone does not bring it back.
        framingEpoch &+= 1
    }

    // MARK: - Light slot reactive plumbing (#1017)

    /// Tags entities provisioned by ``provisionLightSlot(_:slot:)`` so the
    /// reactive diff in ``refreshLightSlot(_:slot:)`` can locate them on
    /// subsequent renders and replace them when the caller's `LightSlot`
    /// value changes.
    fileprivate struct LightSlotMarker: Component {
        enum Slot { case main, fill }
        let slot: Slot
    }

    /// Provisions a light entity for the given slot under `entities.root`,
    /// applying the per-slot defaults when `slot == .systemDefault` and
    /// tagging the entity with `LightSlotMarker` for later diffing.
    /// Mirrors the original setupScene logic but factored out so the
    /// reactive swap path can re-use it.
    @MainActor
    private func provisionLightSlot(_ which: LightSlotMarker.Slot, slot: LightSlot) {
        let entity: Entity?
        switch slot {
        case .systemDefault:
            switch which {
            case .main:
                let main = LightNode.directional(
                    color: .white,
                    intensity: 10_000,
                    castsShadow: true
                )
                main.entity.look(at: .zero, from: [0, 1, 0], relativeTo: nil)
                entity = main.entity
            case .fill:
                let fill = LightNode.fill(intensity: 3_000)
                fill.entity.look(at: .zero, from: [-0.5, 0.5, -0.5], relativeTo: nil)
                entity = fill.entity
            }
        case .disabled:
            entity = nil
        case .custom(let node):
            entity = node.entity
        }
        if let entity {
            entity.components.set(LightSlotMarker(slot: which))
            entities.root.addChild(entity)
        }
        // Cache the provisioned entity (or `nil` for a `.disabled` slot) by
        // reference so `refreshLightSlot` can remove it directly instead of
        // tree-walking `entities.root.children` (#2278). Mirrors how
        // `provisionARLightSlot` records `coordinator.main/fillLightAnchor`.
        switch which {
        case .main: appliedCache.mainLightEntity = entity
        case .fill: appliedCache.fillLightEntity = entity
        }
    }

    /// Diffs the current slot value against the cached `applied{Main,Fill}Slot`
    /// and swaps the tagged entity in-place when they differ. Called from the
    /// `RealityView.update:` closure on every render. Closes #1017.
    ///
    /// Mirrors Android's `SceneView.kt:287-305` `prevFillLightRef` pattern — the
    /// idea is the same (cache the previously-applied value, diff on
    /// recomposition / re-render, swap the scene-attached entity) but the
    /// mechanism differs because RealityKit's `RealityViewContent` is
    /// inout-only inside the `update:` closure (no explicit remove API on the
    /// content; we go through the entity hierarchy via `removeFromParent()`).
    @MainActor
    private func refreshLightSlot(_ which: LightSlotMarker.Slot, slot: LightSlot) {
        let applied: LightSlot? = (which == .main) ? appliedCache.mainSlot : appliedCache.fillSlot
        guard applied != slot else { return }   // no change since last frame
        // Remove the previously-provisioned entity directly via its cached
        // reference rather than walking `entities.root.children.first { … }`
        // (#2278). Mirrors `refreshARLightSlot`, which removes
        // `coordinator.main/fillLightAnchor` by ref. `provisionLightSlot`
        // overwrites the cached ref below (with the new entity or `nil`).
        let toRemove = (which == .main) ? appliedCache.mainLightEntity : appliedCache.fillLightEntity
        toRemove?.removeFromParent()
        // Provision the new one (or none if the new slot is .disabled).
        // `provisionLightSlot` updates `appliedCache.main/fillLightEntity`.
        provisionLightSlot(which, slot: slot)
        // Update the cache so the next frame's diff is a no-op.
        if which == .main {
            appliedCache.mainSlot = slot
        } else {
            appliedCache.fillSlot = slot
        }
    }

    // MARK: - visionOS immersive skybox (#1235)

    #if os(visionOS)
    /// Diffs the loaded immersive-skybox HDR against the host currently
    /// attached to the scene and rebuilds the inverted-sphere skybox
    /// in-place when they differ. Called from the `RealityView.update:`
    /// closure on every render.
    ///
    /// `RealityViewContent.environment` is `@available(visionOS, unavailable)`,
    /// so the iOS / macOS `.skybox(_:)` write cannot be used here. The skybox
    /// is instead a `WorldComponent`-tagged entity (placed in absolute
    /// immersive-space world coordinates) carrying an inverted HDR sphere.
    /// Same diff-write discipline as ``refreshLightSlot(_:slot:)``, keyed on
    /// the HDR resource name. Closes #1235.
    @MainActor
    private func refreshImmersiveSkybox() {
        // Identity of the skybox that should be on screen this frame: the
        // HDR resource name when a texture is loaded and the caller opted
        // into immersive mode, otherwise `nil` (no skybox).
        let desired: String? = (immersiveSpace && loadedImmersiveSkyboxTexture != nil)
            ? sceneEnvironment?.hdrResource
            : nil
        guard desired != appliedImmersiveSkyboxResource else { return }
        // Remove the previously-attached host via its cached reference rather
        // than walking `entities.root.children.first { … }` (#2278). Same
        // by-ref removal discipline as `refreshLightSlot` / `refreshARLightSlot`.
        appliedCache.immersiveSkyboxHost?.removeFromParent()
        appliedCache.immersiveSkyboxHost = nil
        // Build + attach the new host, if a texture is available.
        if let desired, let texture = loadedImmersiveSkyboxTexture {
            let host = VisionOSSkybox.makeHost(texture: texture, hdrResource: desired)
            entities.root.addChild(host)
            appliedCache.immersiveSkyboxHost = host
        }
        appliedImmersiveSkyboxResource = desired
    }
    #endif

    // MARK: - Auto-center content (#1026)

    /// Translates ``SceneEntities/contentRoot`` so the centroid of its
    /// `visualBounds` lands at the parent (`entities.root`) origin, and
    /// dollies the orbit camera to a distance that fits the content
    /// bounding box inside the viewport frustum. Runs once on the first
    /// frame the bounds are non-empty — most async model loads complete
    /// within a handful of frames after `setupScene`.
    ///
    /// Without this, iOS demos render badly framed: the default
    /// perspective camera sat at a fixed `[0, 0.3, 2]` looking at world
    /// origin, so content placed at e.g. `z = -2` rendered in the
    /// bottom-third of the viewport (#1026), and even centred content was
    /// the wrong *size* — small models a speck in a near-empty viewport,
    /// large models overflowing and clipped on every edge (#1041). This
    /// pass fixes both: it centres the content centroid on the orbit
    /// pivot AND scales the orbit radius to fit the bounds. Android
    /// achieves the same effect via the per-demo `ModelNode(centerOrigin
    /// = …)` parameter + hand-tuned camera distance; iOS now does it at
    /// the library level so every demo benefits without per-demo plumbing.
    ///
    /// Opt-out via ``SceneView/autoCenterContent(_:)`` for scenes that
    /// rely on intentional off-centre placement.
    /// Smallest mesh extent (in meters) the auto-centre pass will accept
    /// as "content has loaded enough to be centred". Below this threshold
    /// the bounds are assumed to come from an async load that hasn't
    /// populated yet, and the pass is deferred to the next frame.
    private static let minVisualExtent: Float = 0.001

    /// Relative change in the content union diagonal below which the
    /// framing is considered "the same size". Tolerant enough to ignore
    /// sub-millimetre jitter, tight enough that a freshly streamed model
    /// (which grows the union materially) always re-frames. Fed into
    /// ``FramingStabilityTracker``.
    private static let framingStabilityEpsilon: Float = 0.01

    /// How long the content union must hold steady before the framing pass
    /// latches `didCenterContent`. Must exceed the worst-case gap between two
    /// streamed `Multi-Model Park` models landing — they load over 30–70 s,
    /// so a multi-second hold reliably spans the inter-model gaps. Fed into
    /// ``FramingStabilityTracker``. Closes #1391.
    private static let framingStableHoldSeconds: Double = 2.5

    /// Computes the union axis-aligned bounding box of every content
    /// entity, expressed in **`entities.root`-local** space.
    ///
    /// Walks the whole `contentRoot` sub-tree and unions each entity's
    /// `visualBounds(relativeTo: entities.root)`. Two reasons this is the
    /// `entities.root` frame and not the `contentRoot` frame (#1391, retry):
    ///
    /// 1. **It must not feed back into the framing pass.** #1514 measured
    ///    relative to `contentRoot` and then translated `contentRoot` to
    ///    centre the content. When a demo nests its models under an
    ///    `AnchorEntity` (e.g. `Multi-Model Park`), RealityKit's anchoring
    ///    system continuously re-pins that anchor to its world target —
    ///    so the moment `contentRoot` is translated, the anchor's local
    ///    transform shifts the opposite way to compensate, the next
    ///    measurement relative to `contentRoot` reports a *different*
    ///    centre, `contentRoot` is translated again, and the centre runs
    ///    away to infinity frame after frame. The viewport ends up framed
    ///    on empty space (the reopened #1391 black screen).
    /// 2. `entities.root` carries identity transform in every camera mode
    ///    on iOS / macOS (see `applyCamera()` — orbit / pan / firstPerson
    ///    all reset `root` to `position .zero`, `scale 1`, identity
    ///    orientation and move the *camera* instead). So the `root` frame
    ///    is world space, and a box measured in it is stable regardless of
    ///    any `AnchorEntity` drift underneath.
    ///
    /// Unioning every descendant box (rather than reading one entity's
    /// `visualBounds`) is the part of the #1514 fix that stays: it frames
    /// the *combined* extent of all streamed models, and is robust to a
    /// model whose own bounds are momentarily empty while a sibling's are
    /// valid.
    ///
    /// - Returns: The union AABB in `entities.root`-local (world) space, or
    ///   `nil` if no descendant has finite, non-empty bounds yet (async
    ///   loads still in flight).
    @MainActor
    private func contentUnionBounds() -> BoundingBox? {
        var boxes: [BoundingBox] = []
        // Iterative pre-order walk — no recursion depth concerns, and the
        // `contentRoot` itself is skipped (it is a transform-only node).
        var stack: [Entity] = Array(entities.contentRoot.children)
        while let entity = stack.popLast() {
            stack.append(contentsOf: entity.children)
            // `relativeTo: entities.root` gives every box in the stable
            // world frame so the union is meaningful AND immune to the
            // `AnchorEntity` re-pin feedback described above. `visualBounds`
            // already includes the entity's own descendants, but unioning
            // per-entity is harmless (idempotent) and lets a child whose
            // parent reports empty still contribute.
            let box = entity.visualBounds(relativeTo: entities.root)
            boxes.append(box)
        }
        return ContentBounds.union(of: boxes)
    }

    @MainActor
    private func refreshContentCentering() {
        guard !didCenterContent else { return }
        // Union AABB of every content entity, in `entities.root`-local
        // (world) space — see `contentUnionBounds()`. Sampling in the
        // stable `root` frame (rather than `contentRoot`) is what makes
        // this immune to the `AnchorEntity` re-pin feedback loop that the
        // #1514 attempt fell into (the reopened #1391 black screen).
        guard let bounds = contentUnionBounds() else { return }
        let extents = bounds.extents
        // Skip empty / degenerate bounds — async loads not done yet.
        guard extents.x.isFinite, extents.y.isFinite, extents.z.isFinite else { return }
        let extentMax = max(extents.x, extents.y, extents.z)
        guard extentMax > Self.minVisualExtent else { return }
        // The union centre must be finite too — a half-loaded scene can
        // briefly produce a finite extent around a non-finite centre.
        let center = bounds.center
        guard center.x.isFinite, center.y.isFinite, center.z.isFinite else { return }

        // Re-frame whenever the union changed (a streamed model just
        // landed). The diagonal is the single scalar that captures "size
        // changed". The latch only fires once the diagonal has held steady
        // for a sustained wall-clock window — `FramingStabilityTracker`
        // spans the multi-second gap between two streamed `Multi-Model Park`
        // models landing, which #1514's "two consecutive ticks" latch did
        // not, so it froze the camera on a partial 1–2-model union (#1391).
        let diagonal = simd_length(extents)
        let stable = framingStability.register(
            diagonal: diagonal,
            now: CFAbsoluteTimeGetCurrent()
        )

        // 1. Orbit the camera around the content's world-space centroid.
        //    #1514 instead translated `entities.contentRoot` so the
        //    centroid landed at the world origin — but when content is
        //    nested under an `AnchorEntity` (the `Multi-Model Park`
        //    layout) RealityKit re-pins the anchor every frame and the
        //    translation runs away to infinity (see `contentUnionBounds()`).
        //    Pointing the orbit pivot at wherever the content actually is
        //    moves no scene node, so there is no feedback loop. Cache the
        //    centroid so a `recentersTargetOnOrbit` re-entry can snap back
        //    to it (the content is no longer assumed to sit at the origin).
        contentWorldCenter = center
        camera.target = center
        // 2. Adapt the zoom-radius limits to the content size BEFORE
        //    computing the fit, so `fitRadius`'s internal clamp does not
        //    fight the fit. The fixed `minRadius = 1.0` / `maxRadius = 50`
        //    defaults are tuned for ~1 m demo content; a 0.1 m model needs
        //    a closer min and a 30 m scene a farther max. Bracket the
        //    limits around the bounding-sphere radius so the user can
        //    still pinch in to roughly fill the frame and out to ~10×.
        let sphereRadius = simd_length(extents * 0.5)
        if sphereRadius.isFinite, sphereRadius > 0 {
            camera.minRadius = max(sphereRadius * 0.5, 0.05)
            camera.maxRadius = max(camera.maxRadius, sphereRadius * 20)
        }
        // 3. Dolly the orbit radius so the bounding box fits the frustum
        //    with a small margin, accounting for the vertical FOV and the
        //    live viewport aspect ratio (#1041). Without this the camera
        //    sat at the fixed `2.0` default — too far for small models,
        //    too close for large ones. The fit keeps the camera's
        //    azimuth / elevation so only the distance changes.
        camera.orbitRadius = camera.fitRadius(
            boundsExtents: extents,
            fovYDegrees: Self.baselineFov,
            aspect: viewportAspect ?? 0.46,
            margin: framingMargin
        )
        // Only latch once the union has held steady for the sustained hold
        // window (`FramingStabilityTracker`) — until then the driver task
        // and the `update:` closure keep re-framing as more models land.
        if stable {
            didCenterContent = true
        }
        // 4. Apply the new pose to the perspective camera entity in THIS
        //    frame. `applyCamera()` runs *before* this method in the
        //    `update:` closure, so without an immediate apply the fitted
        //    radius would only take effect on a subsequent `update:` tick
        //    — and mutating `@State` from inside `update:` does not
        //    reliably schedule one. Re-running `applyCamera()` here makes
        //    the fit visible on the same frame the bounds became valid.
        applyCamera()
    }

    // MARK: - Environment IBL

    @MainActor
    private func loadEnvironment(_ env: SceneEnvironment) async {
        do {
            let resource = try await env.load()
            // Apply IBL to the scene via ImageBasedLightComponent
            entities.ibl.components.set(
                ImageBasedLightComponent(
                    source: .single(resource),
                    // `intensity` is authored — and documented, and mirrored from
                    // Android — as a LINEAR multiplier, but RealityKit's parameter
                    // is an exponent of two: the IBL is scaled by `2^x`. Feeding the
                    // multiplier straight through made every preset wrong the moment
                    // #2896 got the IBL to load at all: `.studio` 1.0 → ×2.0, and
                    // `.night` 0.4 → ×1.32, i.e. BRIGHTENING where it should dim to
                    // ×0.4 — ~3.3× off Android under the same preset name (#2897).
                    // `intensityExponent(forMultiplier:)` converts the authored
                    // multiplier into the exponent the API wants, so `1.0` is now a
                    // true no-op (2^0) and the presets keep their linear authoring.
                    // It lives on `SceneEnvironment` so it is unit-testable without
                    // a live RealityKit scene — see `SceneEnvironmentTests`.
                    intensityExponent: SceneEnvironment.intensityExponent(
                        forMultiplier: env.intensity
                    )
                )
            )
            // Make root entity receive IBL
            entities.root.components.set(
                ImageBasedLightReceiverComponent(imageBasedLight: entities.ibl)
            )
            // Cache the resource for the update: closure when the caller
            // asked for the IBL to also render as a skybox background. Set
            // to `nil` when `showSkybox = false` so the update: closure
            // reverts to `.default` and the background goes neutral again.
            // Ported from Eliott Radcliffe's sceneview-swift PR #1.
            loadedSkyboxResource = env.showSkybox ? resource : nil

            // visionOS immersive-space skybox: also load the equirectangular
            // HDR as a `TextureResource` for the inverted-sphere skybox.
            // `EnvironmentResource.skybox` is a cubemap and cannot UV-map
            // onto a sphere, so the HDR file itself is loaded. Only when the
            // caller opted into `.immersiveSpace()` and `showSkybox` is set —
            // skipped entirely for windowed visionOS scenes. Closes #1235.
            #if os(visionOS)
            if immersiveSpace, env.showSkybox, let hdr = env.hdrResource {
                loadedImmersiveSkyboxTexture =
                    await VisionOSSkybox.loadEquirectangularTexture(named: hdr)
            } else {
                loadedImmersiveSkyboxTexture = nil
            }
            #endif
        } catch {
            // Environment loading failed — scene continues with default lighting
            print("[SceneViewSwift] Failed to load environment '\(env.name)': \(error)")
            loadedSkyboxResource = nil
            #if os(visionOS)
            loadedImmersiveSkyboxTexture = nil
            #endif
        }
    }

    // MARK: - Camera

    /// Default field-of-view when not in `.firstPerson` mode. Used by
    /// `applyCamera()` to keep orbit / pan FOV stable while `.firstPerson`
    /// remains free to mutate `camera.fov` via pinch.
    private static let baselineFov: Float = 60

    @MainActor
    private func applyCamera() {
        // Native modes (none/tilt/dolly/gimbal) delegate to Apple's
        // `realityViewCameraControls` modifier — our custom camera math must
        // not run or it will fight Apple's gesture system. Early-out here;
        // the modifier applied in `cameraInteractionView` does the work.
        // Closes #1049 (Phase 2).
        guard cameraControlMode.isCustom else { return }

        // Sync the camera state's mode with the modifier-provided value. Done
        // every frame because the modifier propagates as a `let` while the
        // camera state is `@State` — without this, calling
        // `.cameraControls(.pan)` would be silently ignored. Closes #1034.
        if camera.mode != cameraControlMode {
            let previousMode = camera.mode
            camera.mode = cameraControlMode
            if camera.mode == .firstPerson {
                // ENTERING firstPerson: pin the camera at exactly its
                // current orbit/pan world position so the mode switch
                // does not teleport (#1236 limitation 1). The look-around
                // then yaws / pitches the camera in place around this eye.
                camera.enterFirstPerson()
            } else if previousMode == .firstPerson {
                // LEAVING firstPerson: re-derive the orbit pivot from the
                // fixed eye + current look direction so orbit resumes
                // continuously (the recomputed orbit camera position
                // equals the firstPerson eye). Then reset the pinched FOV
                // back to baseline — a user who pinched FOV down to 30°
                // in firstPerson then switched to orbit must not keep the
                // 30° zoom (R3 MAJOR — FOV bleed).
                camera.exitFirstPerson()
                if camera.fov != Self.baselineFov {
                    camera.fov = Self.baselineFov
                }
            }
            // Opt-in pivot recentre on (re-)entering orbit so repeated
            // pan→orbit cycles don't drift the centroid out of frame
            // (#1236 limitation 2). The auto-framing pass orbits the
            // content's world-space centroid (`contentWorldCenter`, #1391)
            // rather than translating content to the origin, so snap the
            // pivot back to that cached centroid — not the world origin.
            if camera.mode == .orbit && recentersTargetOnOrbit {
                camera.recenterTarget(contentWorldCenter)
            }
        }

        // Host-written pose (`.cameraPose(_:)`). Gated on the REQUEST changing,
        // never on the request differing from the live camera: the host is
        // expected to mirror `onCameraChanged` into its own state and hand that
        // value straight back, so "differs from live" is false exactly once per
        // drag tick and true for the rest — which would re-pin the camera to a
        // stale pose one frame after every gesture and read as violent jitter.
        // Sits after the mode-sync so a pose written on the same frame as a mode
        // change survives `enterFirstPerson()` / `recenterTarget()`, and before
        // the diff-guard so the write it performs is what the guard compares.
        if let requested = requestedCameraPose,
           requested != appliedCache.requestedPose
            || requestedCameraPoseGeneration != appliedCache.requestedPoseGeneration {
            appliedCache.requestedPose = requested
            appliedCache.requestedPoseGeneration = requestedCameraPoseGeneration
            camera.apply(pose: requested)
        }

        // Diff-guard (#2331): every per-mode branch below is a pure function of
        // the orbit state captured here, so when nothing that drives the write
        // changed since the last apply the RealityKit transforms would just be
        // rewritten with identical values. This is the common path — `update:`
        // re-runs `applyCamera()` on every auto-rotate / framing-task tick and
        // on each SwiftUI re-eval, but the orbit only actually moves on a
        // gesture / auto-rotate step. The mode-sync above (which mutates
        // `camera`) has already run, so `desired` reflects post-sync state and
        // is never skipped wrongly. A real re-fit (`refreshContentCentering`
        // mutates `camera.target` / `orbitRadius` then re-calls `applyCamera`)
        // changes the key and re-applies on the same frame.
        let desired = AppliedCameraState(
            mode: camera.mode,
            azimuth: camera.azimuth,
            elevation: camera.elevation,
            orbitRadius: camera.orbitRadius,
            target: camera.target,
            fov: camera.fov,
            firstPersonEye: camera.firstPersonEye
        )
        if let last = appliedCache.camera, last.approximatelyMatches(desired) {
            return
        }
        appliedCache.camera = desired

        // Read-back (`.onCameraChanged(_:)`). Deliberately here rather than in the
        // gesture handlers: this is the one point that fires for *every* real
        // camera move — drag, pinch, auto-rotate step, fit-to-bounds re-frame, and
        // a `.cameraPose(_:)` write landing clamped — and, thanks to the guard
        // just above, for nothing else. Wiring it per-gesture instead would report
        // orbit but stay silent through a pinch or a re-frame, and the host's
        // camera state would drift from the screen with no symptom until someone
        // read a distance that had been wrong for a minute.
        onCameraChanged?(camera.pose)

        // Per-mode application of orientation, zoom, and translation.
        // Mirrors Android's `CameraGestureDetector` modes
        // (ORBIT / PAN / FREE_FLIGHT) — closes #1034 (last #928 silent-stub
        // item).
        //
        // History (Eliott Radcliffe, sceneview-swift PR #1): orbit + pan
        // used to "fake" the camera move by rotating + scaling the scene
        // root while the perspective camera stayed pinned at `[0, 0.3, 2]`.
        // That made a globally-applied skybox appear stationary while the
        // content visually orbited around the user — wrong from the
        // camera's POV. The fix moves the actual perspective camera in
        // world-space via `cameraPosition()` so the skybox correctly wraps
        // and content stays still. visionOS has no manual camera entity
        // (the headset is the camera), so it retains the rotate-root path.
        switch camera.mode {
        case .orbit, .pan:
            // True orbit / pan: position the camera in world-space; the
            // scene root stays at origin with identity transform so the
            // skybox wraps correctly. `cameraPosition()` already encodes
            // `camera.target`, so pan's lateral target drift propagates
            // directly into the camera position. Pinch in/out changes
            // `camera.orbitRadius`, which is now the camera's literal
            // distance to the target (vs. the previous `5.0 / radius`
            // scene-scale hack).
            entities.root.orientation = simd_quatf(angle: 0, axis: [0, 1, 0])
            entities.root.scale = SIMD3<Float>(repeating: 1)
            entities.root.position = .zero
            #if os(iOS) || os(macOS)
            let pos = camera.cameraPosition()
            entities.perspCamera.look(at: camera.target, from: pos, relativeTo: nil)
            // Use the static baseline FOV for orbit / pan rather than
            // mirroring `camera.fov`, which the firstPerson pinch mutates.
            // Without this, switching firstPerson → orbit kept the 30°
            // pinched FOV on the perspective camera (R3 MAJOR — "FOV
            // bleed"). The static-baseline write is a no-op on identical
            // values so the property doesn't churn.
            if entities.perspCamera.camera.fieldOfViewInDegrees != Self.baselineFov {
                entities.perspCamera.camera.fieldOfViewInDegrees = Self.baselineFov
            }
            #else
            // visionOS fallback: rotate the scene root since there's no
            // manual camera entity to move. Skybox wrap correctness is
            // moot under passthrough.
            let yaw = simd_quatf(angle: -camera.azimuth, axis: [0, 1, 0])
            let pitch = simd_quatf(angle: -camera.elevation, axis: [1, 0, 0])
            entities.root.orientation = yaw * pitch
            let zoomScale = 2.0 / max(camera.orbitRadius, camera.minRadius)
            entities.root.scale = SIMD3<Float>(repeating: zoomScale)
            entities.root.position = camera.mode == .pan ? -camera.target * zoomScale : .zero
            #endif

        case .firstPerson:
            // True look-around (#1236 / #1034): the perspective camera
            // stands still at a fixed eye and only its ORIENTATION yaws /
            // pitches — it does not orbit and does not rotate the scene
            // root. The eye was captured (`enterFirstPerson()`) from the
            // exact orbit/pan camera position at the mode switch, so
            // entering firstPerson never teleports. Pinch mutates FOV
            // (see pinchGesture) rather than the orbit radius.
            //
            // The scene root stays at identity for ALL three modes — the
            // skybox therefore wraps correctly and content never moves
            // under the camera.
            entities.root.orientation = simd_quatf(angle: 0, axis: [0, 1, 0])
            entities.root.scale = SIMD3<Float>(repeating: 1)
            entities.root.position = .zero
            #if os(iOS) || os(macOS)
            // Eye is normally set by `enterFirstPerson()` on the orbit→
            // firstPerson switch. When firstPerson is the INITIAL mode no
            // such switch ever fires, so capture it once here from the
            // current orbit position — otherwise dragging would re-derive
            // a moving `cameraPosition()` each frame (the old orbit bug).
            if camera.firstPersonEye == nil {
                camera.enterFirstPerson()
            }
            let eye = camera.firstPersonEye ?? camera.cameraPosition()
            entities.perspCamera.position = eye
            // Rotate the camera in place — `lookOrientation()` reproduces
            // the same forward vector orbit looks along, so orbit↔first-
            // person is continuous in both directions.
            entities.perspCamera.orientation = camera.lookOrientation()
            // firstPerson is the ONLY mode that mirrors `camera.fov` so
            // pinch can zoom without affecting orbit's baseline (see the
            // orbit/pan case above for the corresponding non-write).
            if entities.perspCamera.camera.fieldOfViewInDegrees != camera.fov {
                entities.perspCamera.camera.fieldOfViewInDegrees = camera.fov
            }
            #else
            // visionOS fallback: no manual camera entity (the headset is
            // the camera). Rotate the scene root to simulate look-around.
            entities.root.orientation = camera.sceneRotation()
            #endif

        case .none, .tilt, .dolly, .gimbal:
            // Native modes are guarded out above by `isCustom` — this branch
            // is unreachable at runtime. The exhaustive case keeps the Swift
            // compiler satisfied when the `isCustom` guard is inlined here.
            break
        }
    }

    // MARK: - Gestures

    private var dragGesture: some Gesture {
        DragGesture()
            .onChanged { value in
                // `.cameraGesturesEnabled(false)` freezes the camera under the finger.
                //
                // The baseline is updated FIRST, and unconditionally. Returning early
                // instead — which is what an earlier version did, with a comment
                // asserting the opposite — freezes `lastDragTranslation` while the finger
                // keeps travelling, so re-enabling mid-drag computes a delta spanning the
                // whole suppressed distance and the camera snaps across it in one frame.
                // Keeping the baseline current means a re-enabled drag resumes from where
                // the finger actually is.
                let delta = CGSize(
                    width: value.translation.width - lastDragTranslation.width,
                    height: value.translation.height - lastDragTranslation.height
                )
                lastDragTranslation = value.translation
                guard cameraGesturesEnabled else { return }
                camera.handleDrag(delta)
                isDragging = true
                // Push the dragged orbit straight onto the camera entity.
                // Now that `camera` lives in a reference box (#2277), mutating
                // it no longer invalidates the body, so the `RealityView.update:`
                // closure no longer re-fires per drag tick — apply directly.
                applyCamera()
            }
            .onEnded { _ in
                lastDragTranslation = .zero
                isDragging = false
            }
    }

    private var pinchGesture: some Gesture {
        // Mode-aware pinch: orbit/pan dollies the radius, firstPerson scales
        // the perspective camera's FOV (#1034). We snapshot the value at
        // gesture start so the delta is applied to a stable base — same
        // pattern as Android's `Manipulator.scrollBegin / scrollUpdate`.
        MagnifyGesture()
            .onChanged { value in
                // Same shape as the drag: snapshot the baseline first, gate second.
                // `initialPinchRadius` is the pinch's equivalent of
                // `lastDragTranslation` — left unsnapshotted while disabled, re-enabling
                // at magnification 2.0 would take it as the first tick and dolly to half
                // the radius instantly.
                //
                // Note this path does NOT go through `CameraControls.handlePinch` (it
                // scales a snapshotted baseline instead), so `CameraControls.isEnabled`
                // alone would silently disable orbit while leaving zoom live.
                if initialPinchRadius == nil, camera.mode == .orbit || camera.mode == .pan {
                    initialPinchRadius = camera.orbitRadius
                }
                if initialPinchFov == nil, camera.mode == .firstPerson {
                    initialPinchFov = camera.fov
                }
                guard cameraGesturesEnabled else { return }
                switch camera.mode {
                case .orbit, .pan:
                    if initialPinchRadius == nil {
                        initialPinchRadius = camera.orbitRadius
                    }
                    if let initial = initialPinchRadius {
                        let newRadius = initial / Float(value.magnification)
                        camera.orbitRadius = min(
                            max(newRadius, camera.minRadius),
                            camera.maxRadius
                        )
                    }
                case .firstPerson:
                    if initialPinchFov == nil {
                        initialPinchFov = camera.fov
                    }
                    if let initial = initialPinchFov {
                        let newFov = initial / Float(value.magnification)
                        camera.fov = min(
                            max(newFov, camera.minFov),
                            camera.maxFov
                        )
                    }
                case .none, .tilt, .dolly, .gimbal:
                    // This gesture is only applied for custom modes (see
                    // cameraInteractionView). Unreachable, but required for
                    // exhaustive switch.
                    break
                }
                // Push the new radius / FOV straight onto the camera entity —
                // the boxed `camera` no longer invalidates the body (#2277), so
                // the `update:` closure no longer re-fires per pinch tick.
                applyCamera()
            }
            .onEnded { _ in
                initialPinchRadius = nil
                initialPinchFov = nil
            }
    }

    private var tapGesture: some Gesture {
        // Wire real entity hit-testing via SwiftUI's `targetedToAnyEntity()` — the
        // SpatialTapGesture's `value.entity` is the actual RealityKit entity at the
        // tap location, not the scene root. Closes part of #928 (this used to pass
        // `entities.root` unconditionally regardless of where the user tapped, which
        // made the callback useless for picking objects in the scene).
        //
        // Requires iOS 17+ / macOS 14+ / visionOS 1+ (RealityKit 2.x). All current
        // SceneViewSwift platforms already require those baselines.
        SpatialTapGesture()
            .targetedToAnyEntity()
            .onEnded { value in
                onEntityTapped?(value.entity)
                // Resolves the position from the entity's visual bounds. The exact
                // surface point is not reachable here: `location3D` on
                // `SpatialTapGesture.Value` and `EntityTargetValue.convert(_:from:to:)`
                // are visionOS-only, and a SwiftUI `RealityView` exposes no scene
                // raycast on iOS / macOS. `SceneTapHit` documents the consequence
                // rather than papering over it.
                onEntityTappedHit?(SceneTapHit(entity: value.entity))
            }
    }

    // MARK: - Per-entity gestures (NodeGesture dispatch)

    /// Dispatches a tap to any per-entity `Entity.onTap { }` handler registered
    /// via the NodeGesture system. Fires in addition to `onEntityTapped(_:)`
    /// because both contracts are useful: the modifier callback gets every
    /// entity tap (broad listener), while NodeGesture lets the caller scope
    /// handlers per-entity at construction time.
    private var entityTapGesture: some Gesture {
        SpatialTapGesture()
            .targetedToAnyEntity()
            .onEnded { value in
                NodeGesture.dispatchTap(on: value.entity)
            }
    }

    /// Per-entity drag — translates the gesture's screen-space delta into a
    /// world-space SIMD3 and dispatches to the entity's `onDrag` handler.
    /// `targetedToAnyEntity()` ensures empty-space drags continue to drive the
    /// camera (`dragGesture` above), not the entity dispatch.
    private var entityDragGesture: some Gesture {
        DragGesture(minimumDistance: 1)
            .targetedToAnyEntity()
            .onChanged { value in
                // RealityKit doesn't expose a one-line "screen → world" inverse;
                // we approximate world-space translation by treating the drag's
                // 2D screen delta as a planar XY translation at the entity's
                // depth. Sufficient for sticker-style drag interactions; users
                // who need full 3D-projected drags can compute via the camera
                // ray themselves in the handler.
                //
                // `value.translation` is SwiftUI's *cumulative* translation
                // since the gesture began, but `NodeGesture.onDrag`'s contract
                // promises a per-frame *delta* ("translation delta in world
                // space"). Dispatch `current − previous` so the natural
                // `entity.position += delta` handler integrates once, not twice
                // — the previous code dispatched the cumulative value every
                // tick, so the entity accelerated off-screen (#2283).
                let current = EntityDragState.worldTranslation(
                    width: value.translation.width,
                    height: value.translation.height
                )
                let key = ObjectIdentifier(value.entity)
                NodeGesture.dispatchDrag(
                    on: value.entity,
                    translation: entityDragState.delta(forKey: key, cumulative: current)
                )
            }
            .onEnded { value in
                // Reset the per-gesture baseline so the next drag on this
                // entity starts from zero rather than the last gesture's
                // final cumulative offset.
                entityDragState.end(forKey: ObjectIdentifier(value.entity))
            }
    }

    /// Per-entity pinch-to-scale.
    private var entityMagnifyGesture: some Gesture {
        MagnifyGesture()
            .targetedToAnyEntity()
            .onChanged { value in
                NodeGesture.dispatchScale(on: value.entity, magnification: Float(value.magnification))
            }
    }

    /// Per-entity two-finger rotate.
    private var entityRotateGesture: some Gesture {
        RotateGesture()
            .targetedToAnyEntity()
            .onChanged { value in
                NodeGesture.dispatchRotate(on: value.entity, angle: Float(value.rotation.radians))
            }
    }

    /// Per-entity long press.
    private var entityLongPressGesture: some Gesture {
        LongPressGesture(minimumDuration: 0.5)
            .targetedToAnyEntity()
            .onEnded { value in
                NodeGesture.dispatchLongPress(on: value.entity)
            }
    }
}
#endif // os(iOS) || os(macOS) || os(visionOS)

#if canImport(UIKit) && (os(iOS) || os(visionOS))
import UIKit
import SwiftUI
import RealityKit
import Foundation
import simd

/// A flat, `@objc`-visible description of what to render in a ``SceneViewerHostView``.
///
/// Every member is a primitive on purpose. This type is what a cross-language bridge
/// copies its own model into field by field — Kotlin/Native's `SceneViewerSpec`, a
/// Flutter method-channel argument map, a React Native prop bag — and none of those can
/// carry a Swift enum, a `SIMD3`, or an `Optional<Float>` across. Vectors are three
/// separate scalars for the same reason.
///
/// **Angles are in degrees**, matching the Kotlin `CameraState` this mirrors;
/// ``SceneViewerHostView`` converts to radians on the way in and back to degrees on the
/// way out.
@objc(SVSceneViewerConfiguration)
public final class SceneViewerConfiguration: NSObject {

    // MARK: Model — at most one of the three is set

    /// Bundle resource path, e.g. `"models/helmet.usdz"`.
    ///
    /// **RealityKit loads `.usdz` and `.reality` only.** A glTF / GLB path — which is
    /// what the same code loads on Android — throws, and the failure is logged rather
    /// than rendered: the viewport keeps showing the environment, exactly as it looks
    /// while a load is still in flight. Convert with `tools/convert-usdz.sh`.
    @objc public var modelAssetPath: String?

    /// Absolute `http` / `https` URL of a `.usdz` / `.reality` file.
    @objc public var modelURLString: String?

    /// Model bytes already in memory. Must be a self-contained `.usdz` / `.reality`.
    @objc public var modelBytes: Data?

    /// Extension used for the temporary file ``modelBytes`` is written to.
    ///
    /// RealityKit dispatches on the file extension, so bytes with the wrong one fail to
    /// load however valid they are. Default `"usdz"`.
    @objc public var modelBytesFileExtension: String = "usdz"

    /// Several models at once, replacing the single-model fields above when non-empty.
    ///
    /// The three fields above are the single-model shorthand and stay the whole story for
    /// a caller that shows one thing — `sceneview-compose` never sets this. A bridge whose
    /// public surface is a *list* (the Flutter plugin's `loadModel` / `clearScene`, the
    /// React Native `modelNodes` prop) sets this instead and leaves them alone.
    ///
    /// Set ``SceneViewerModel/identity`` on each entry unless two entries can never share
    /// a source — see that property.
    @objc public var models: [SceneViewerModel] = []

    // MARK: Camera — angles in DEGREES

    @objc public var cameraTargetX: Float = 0
    @objc public var cameraTargetY: Float = 0
    @objc public var cameraTargetZ: Float = 0
    @objc public var cameraDistance: Float = 4
    @objc public var cameraAzimuthDegrees: Float = 0
    @objc public var cameraElevationDegrees: Float = 15

    /// Whether drag-to-orbit and pinch-to-zoom respond. Default `true`.
    @objc public var cameraGesturesEnabled: Bool = true

    /// `"orbit"`, `"pan"` or `"firstPerson"`. Anything else is treated as `"orbit"`.
    ///
    /// A wire name rather than the `CameraControlMode` enum, which does not cross into
    /// Objective-C — and the same three strings the Flutter and React Native bridges
    /// already accept from Dart and JavaScript.
    @objc public var cameraControlMode: String = "orbit"

    /// Whether the scene frames its content to the viewport on load. Default `false`.
    ///
    /// Off by default because the fit pass **owns the orbit radius**: with it on, the
    /// distance in ``cameraDistance`` is computed rather than honoured, and is then
    /// reported back through ``SceneViewerHostView/onCameraMoved`` — so a host that
    /// mirrors that callback into its own state watches the distance it just wrote get
    /// overwritten. SceneView Android honours the authored distance verbatim, and matching
    /// it is what makes one camera state mean one thing on both platforms.
    ///
    /// Turn it on when the caller has no opinion about the camera and wants the model
    /// framed — which is what both the Flutter and React Native bridges expose, and why
    /// they default it to `true` on their own surfaces. **Setting this leaves
    /// ``cameraDistance`` with no effect.**
    @objc public var autoCenterContent: Bool = false

    /// Whether the camera fields above describe a pose the caller is actually authoring.
    ///
    /// Set this to `false` when the camera belongs to the scene rather than to you: the
    /// pose fields are then ignored entirely and the camera is left to the fit pass and
    /// the user's gestures.
    ///
    /// It exists because "the caller did not set a camera" and "the caller set the default
    /// camera" are indistinguishable in a struct of primitives with defaults, and the two
    /// need opposite handling. ``SceneViewerHostView/applyConfiguration(_:)`` is called on
    /// every update — every method-channel call, every prop change — and each call would
    /// otherwise re-assert `distance 4, elevation 15°` as a genuine write, snapping the
    /// camera back and discarding both the fit pass and wherever the user had orbited to.
    /// A bridge that never exposed a camera would acquire one that fights its user.
    @objc public var cameraPoseAuthored: Bool = true

    // MARK: Lighting

    /// Direction the key light *travels*, in world space.
    @objc public var lightDirectionX: Float = 0.3
    @objc public var lightDirectionY: Float = -1
    @objc public var lightDirectionZ: Float = -0.5

    /// Key-light intensity in lux.
    @objc public var lightIntensity: Float = 100_000

    /// Multiplier on the image-based light.
    ///
    /// Applies only when ``environmentKind`` is `"hdr"`: that is the only case with an
    /// authored IBL to scale. With `"default"` or `"color"` the scene is lit by
    /// RealityKit's own default environment, whose level is not exposed.
    @objc public var ambientIntensity: Float = 1

    @objc public var castShadows: Bool = true

    // MARK: Environment

    /// `"default"`, `"color"` or `"hdr"`. Anything else is treated as `"default"`.
    @objc public var environmentKind: String = "default"

    /// Linear components, `0...1`, used when ``environmentKind`` is `"color"`.
    @objc public var environmentRed: Float = 0
    @objc public var environmentGreen: Float = 0
    @objc public var environmentBlue: Float = 0
    @objc public var environmentAlpha: Float = 1

    /// Bundle resource name of the `.hdr` / `.exr`, used when ``environmentKind`` is
    /// `"hdr"`.
    @objc public var environmentHdrPath: String?

    /// Whether an `"hdr"` environment is also drawn as the background.
    @objc public var environmentShowSkybox: Bool = true

    @objc public override init() {
        super.init()
    }
}

/// A `UIView` that renders a ``SceneView`` and is driven entirely by primitives.
///
/// ### What it is for
///
/// SceneView's Apple renderer is SwiftUI-only. Three separate bridges need it as a
/// `UIView` instead — `sceneview-compose` (Kotlin Multiplatform, through
/// `UIKitView` and its `SceneViewerBridge` factory), the Flutter plugin's
/// `FlutterPlatformView`, and the React Native Fabric component. None of them can hold a
/// SwiftUI `View`: KMP cannot see SwiftUI types through cinterop at all, and the other
/// two are handed a `UIView` by their host framework. This is the one wrapper they can
/// share, so that "hosting a SwiftUI scene in UIKit correctly" is solved once.
///
/// All three now use it for their **3D** path. The Flutter and React Native bridges keep
/// their own platform-view classes — those own a method channel and a prop bag, which is
/// not this type's business — but the SwiftUI hosting, the model loading and the scene
/// itself are here. Their **AR** paths are untouched: `ARSceneView` is anchor-driven, has
/// no content closure, and shares nothing with this beyond the word "scene".
///
/// What migrating them added, and why it is worth knowing before extending this further:
/// each of those bridges had behaviour that could not survive the move unless the
/// configuration could express it. ``SceneViewerConfiguration/models`` (both are model
/// *lists*), ``SceneViewerConfiguration/cameraControlMode`` and
/// ``SceneViewerConfiguration/autoCenterContent`` (both expose them publicly),
/// ``onTapEntity`` (the two report *different* things about a tapped entity, and both are
/// public API) and ``SceneViewerConfiguration/cameraPoseAuthored`` (neither has a camera
/// at all) are all there because a bridge would otherwise have regressed silently.
///
/// ### Using it
///
/// ```swift
/// let host = SceneViewerHostView()
/// host.onCameraMoved = { distance, azimuthDegrees, elevationDegrees in
///     // mirror into your own camera state — see the note below, this matters
/// }
/// host.onTap = { hit, x, y, z, distance in /* ... */ }
///
/// let config = SceneViewerConfiguration()
/// config.modelAssetPath = "models/helmet.usdz"
/// host.applyConfiguration(config)       // and again on every change; never rebuild
/// ```
///
/// Call ``applyConfiguration(_:)`` for every update rather than creating a new host:
/// rebuilding reloads the model and throws away wherever the user had orbited to. The
/// verbose name is for the Kotlin side — `apply` is one of the most-used extensions in
/// the Kotlin standard library, and a bridge method that shadows it at every call site
/// is a trap the Swift-side brevity does not pay for.
///
/// ### Two limits, reported rather than papered over
///
/// - **There is no per-frame callback.** ``SceneView`` publishes none, and a polled
///   timer would report times that are not the renderer's. A host's `onFrame` equivalent
///   is not called on iOS.
/// - **A tap that misses the model produces no callback.** RealityKit's hit-testing
///   gesture only fires when it hits something, so — unlike Android, where a miss calls
///   back with a null hit — a miss here is silence. ``onTap``'s `hit` flag exists for
///   signature parity across the bridges and is always `true`.
///
/// And one more worth knowing before you debug a wrong-looking number: the tap position
/// is the tapped entity's bounds centre, not the exact surface point. See ``SceneTapHit``.
///
/// ### Two contracts the compiler will not enforce for you
///
/// - **Main thread.** Every member is main-actor isolated, but the `@objc` entry points
///   are reachable from Objective-C and Kotlin/Native without an actor check. Call
///   ``applyConfiguration(_:)`` and set the callbacks on the main thread.
/// - **Do not capture this view, or its owner, strongly in ``onTap`` / ``onCameraMoved``.**
///   The view holds the callbacks, so `host → state → closure → owner → host` is a cycle
///   this type cannot break for you. Capture `[weak self]`, or route through a value that
///   does not own the view — which is what the Compose bridge does, since its closures
///   capture Compose state rather than any UIKit object.
@objc(SVSceneViewerHostView)
@MainActor
public final class SceneViewerHostView: UIView {

    /// Observable state the hosted SwiftUI scene reads. Also owns the callbacks, so the
    /// scene never captures this view and no retain cycle can form through them.
    private let state = SceneViewerState()

    private let hostingController: UIHostingController<SceneViewerRootView>

    /// Identity of the last applied background colour, so the per-frame assignment is
    /// gated the same way the `@Published` ones are.
    private var appliedBackgroundKey: String?

    /// Called after a tap lands on the model, with `(hit, x, y, z, distanceFromCamera)`.
    ///
    /// `hit` is always `true` — see the type's documentation. Coordinates are world
    /// space; `distance` is from the camera to that point, both derived through the same
    /// orbit convention the renderer uses.
    @objc public var onTap: ((Bool, Float, Float, Float, Float) -> Void)? {
        get { state.onTap }
        set { state.onTap = newValue }
    }

    /// Called after a tap lands on the model, with the hit itself.
    ///
    /// The Swift-only counterpart to ``onTap``, which flattens a hit into five primitives
    /// because it has to cross into Kotlin. This one hands over the ``SceneTapHit``,
    /// including the tapped `Entity` — so a caller can read the name, walk to an ancestor,
    /// or take the entity's own origin rather than the hit position.
    ///
    /// That is not a convenience. Two bridges publish a tapped node under two different
    /// definitions — one walks up to the first named ancestor and strips the extension,
    /// the other reports the tapped entity's raw name — and a third reports the entity's
    /// origin where ``onTap`` reports the bounds centre. Every one of those is public API
    /// someone has already shipped against. Flattening them into one convention here would
    /// change all three; handing over the entity lets each keep its own, which is why this
    /// exists at all.
    ///
    /// Both callbacks can be set at once and both fire. Not `@objc`: ``SceneTapHit``
    /// carries RealityKit types that do not cross into Objective-C, and the consumers that
    /// need it — the Flutter and React Native bridges — are Swift.
    public var onTapEntity: ((SceneTapHit) -> Void)? {
        get { state.onTapEntity }
        set { state.onTapEntity = newValue }
    }

    /// Called after **every** camera change, with `(distance, azimuthDegrees,
    /// elevationDegrees)`.
    ///
    /// **Wire this.** It is what keeps a hoisted camera state honest: without it a host
    /// can only ever report the pose it last wrote, so the user orbits the model, the
    /// screen moves, and every read still returns the initial pose — with nothing
    /// anywhere to indicate that the two have diverged. It also reports back a written
    /// pose that had to be clamped, which is the only way the caller learns that the
    /// value it set is not the value on screen.
    @objc public var onCameraMoved: ((Float, Float, Float) -> Void)? {
        get { state.onCameraMoved }
        set { state.onCameraMoved = newValue }
    }

    /// Creates a host with a zero frame, to be sized by its superview.
    ///
    /// Declared explicitly rather than left to Swift's automatic initialiser
    /// inheritance. Whether an *inherited* `init()` survives into the generated
    /// Objective-C header — which is what Kotlin/Native cinterop reads, and therefore
    /// what decides whether `SVSceneViewerHostView()` compiles from Kotlin — is not
    /// something the source makes obvious to a reader. Four lines remove the question.
    @objc public convenience init() {
        self.init(frame: .zero)
    }

    @objc public override init(frame: CGRect) {
        let state = self.state
        self.hostingController = UIHostingController(
            rootView: SceneViewerRootView(state: state)
        )
        super.init(frame: frame)
        installHostedView()
    }

    /// Creates a host already showing `configuration`.
    ///
    /// Equivalent to `init(frame:)` followed by ``applyConfiguration(_:)``, but applies the
    /// configuration before the first layout, so the scene is never briefly built from
    /// the defaults.
    @objc public convenience init(configuration: SceneViewerConfiguration) {
        self.init(frame: .zero)
        applyConfiguration(configuration)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("SceneViewerHostView is created in code, not from a nib")
    }

    /// Attaches the hosting controller to the nearest parent view controller.
    ///
    /// Without this the controller's view renders, but the controller sits outside the
    /// responder and appearance chains: no `viewWillAppear` / `viewDidDisappear`, no
    /// trait-collection or safe-area inheritance. Tolerable in one bespoke bridge;
    /// not in the wrapper three of them are meant to share.
    public override func didMoveToWindow() {
        super.didMoveToWindow()
        guard let parent = nearestViewController else {
            hostingController.willMove(toParent: nil)
            hostingController.removeFromParent()
            return
        }
        guard hostingController.parent !== parent else { return }
        parent.addChild(hostingController)
        hostingController.didMove(toParent: parent)
    }

    private var nearestViewController: UIViewController? {
        var responder: UIResponder? = next
        while let current = responder {
            if let controller = current as? UIViewController { return controller }
            responder = current.next
        }
        return nil
    }

    private func installHostedView() {
        let hosted = hostingController.view!
        hosted.frame = bounds
        hosted.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        // Clear, so this view's own `backgroundColor` — which is what a `"color"`
        // environment sets — shows through the RealityView's transparent background.
        hosted.backgroundColor = .clear
        addSubview(hosted)
    }

    // MARK: - Configuration

    /// Applies `configuration` to the live scene, mutating what changed and nothing else.
    ///
    /// Safe to call on every update of your own framework's render loop, however often
    /// that is: every field is compared before it is assigned. That is not an
    /// optimisation but a correctness requirement — a `@Published` property fires its
    /// observers even when assigned an identical value, so assigning unconditionally
    /// would re-evaluate the SwiftUI body, re-diff the light slots, and re-run the
    /// environment loader on every single frame of a drag.
    @objc public func applyConfiguration(_ configuration: SceneViewerConfiguration) {
        applyModels(configuration)
        applyCameraControls(configuration)
        applyCamera(configuration)
        // Separate from `applyCamera`, which returns early when the incoming pose is an
        // echo of its own read-back. Folding the gesture flag in there would make it
        // reachable only on frames that also carry a camera move — so toggling gestures
        // alone would land on the next drag, or never.
        applyGestures(configuration)
        applyLighting(configuration)
        applyEnvironment(configuration)
    }

    private func applyModels(_ configuration: SceneViewerConfiguration) {
        Self.warnIfURLRefused(configuration)
        let entries = SceneViewerModelEntry.entries(from: configuration)
        let key = sceneViewerListKey(entries)
        guard key != state.modelListKey else { return }
        // The entries first: the `@Published` key is what wakes the loading task, and it
        // must not observe a key whose payload has not landed yet.
        state.modelEntries = entries
        state.modelListKey = key
    }

    private func applyCameraControls(_ configuration: SceneViewerConfiguration) {
        let mode = sceneViewerCameraControlMode(configuration.cameraControlMode)
        if state.cameraControlMode != mode {
            state.cameraControlMode = mode
        }
        if state.autoCenterContent != configuration.autoCenterContent {
            state.autoCenterContent = configuration.autoCenterContent
        }
    }

    private func applyCamera(_ configuration: SceneViewerConfiguration) {
        // A caller that authors no camera gets none applied — see `cameraPoseAuthored`.
        // Checked before the pose is even built, so no echo state is touched either:
        // `lastReportedPose` must keep tracking what the scene reports, since that is what
        // `reportTap` measures its distance from.
        guard configuration.cameraPoseAuthored else { return }

        let incoming = SceneCameraPose(
            azimuth: SceneViewerAngle.radians(fromDegrees: configuration.cameraAzimuthDegrees),
            elevation: SceneViewerAngle.radians(fromDegrees: configuration.cameraElevationDegrees),
            distance: configuration.cameraDistance,
            target: SIMD3<Float>(
                configuration.cameraTargetX,
                configuration.cameraTargetY,
                configuration.cameraTargetZ
            )
        )

        // Echo suppression. A host that wires `onCameraMoved` writes what it is told back
        // into its own state, and its next update hands that same pose straight back
        // here. Re-applying it would be harmless if it arrived instantly — but it arrives
        // one or more frames late, so mid-drag it would pin the camera to where the
        // finger was two frames ago and the orbit would judder or stall outright.
        //
        // Comparing against what was last *reported* (not against what was last
        // requested) is what makes this correct in both directions: a genuine write from
        // the app differs from the last report and is applied, including a write that
        // happens to restore a pose the app had requested earlier and the user has since
        // dragged away from.
        if let reported = state.lastReportedPose, incoming.approximatelyMatches(reported) {
            return
        }
        // Everything past the echo check is a genuine write from the app, and it is
        // stamped with a generation rather than compared by value.
        //
        // Comparing by value here is what broke the canonical case: `state.cameraPose`
        // does not advance during a gesture (every echo returns above), so it stays
        // frozen at the app's last genuine write. A "reset view" button re-writing that
        // exact pose after the user has dragged away therefore matched the frozen value
        // and was dropped — the camera stayed where the finger left it while the app's
        // state read the reset pose, with nothing to reconcile them. The earlier
        // verification missed it by only ever writing a pose that had never been
        // requested before.
        //
        // The generation cannot be replaced by simply letting `cameraPose` track the
        // reported pose either: `SceneView` would then re-apply a one-frame-stale pose on
        // every echo and yank the camera backwards mid-drag.
        state.cameraPoseGeneration &+= 1
        state.cameraPose = incoming
    }

    private func applyLighting(_ configuration: SceneViewerConfiguration) {
        let lighting = SceneViewerLighting(
            direction: SIMD3<Float>(
                configuration.lightDirectionX,
                configuration.lightDirectionY,
                configuration.lightDirectionZ
            ),
            intensity: configuration.lightIntensity,
            ambientIntensity: configuration.ambientIntensity,
            castShadows: configuration.castShadows
        )
        if state.lighting != lighting {
            state.lighting = lighting
        }
    }

    private func applyGestures(_ configuration: SceneViewerConfiguration) {
        if state.cameraGesturesEnabled != configuration.cameraGesturesEnabled {
            state.cameraGesturesEnabled = configuration.cameraGesturesEnabled
        }
    }

    private func applyEnvironment(_ configuration: SceneViewerConfiguration) {
        let kind = configuration.environmentKind

        // A flat colour is this view's own background, not a RealityKit environment:
        // `SceneEnvironment` only models an HDR resource, and RealityKit exposes no
        // solid-colour environment for a SwiftUI `RealityView`. The model therefore
        // stays lit by RealityKit's default IBL, where Android's flat-colour environment
        // means "key light only" — a divergence the module README states rather than one
        // this code pretends away.
        // Gated like every `@Published` assignment, and for the same reason: this runs on
        // every frame of a drag, and an unconditional assignment allocates a fresh
        // `UIColor` and dirties the layer each time.
        let backgroundKey = kind == "color"
            ? "\(configuration.environmentRed),\(configuration.environmentGreen),"
                + "\(configuration.environmentBlue),\(configuration.environmentAlpha)"
            : "clear"
        if backgroundKey != appliedBackgroundKey {
            appliedBackgroundKey = backgroundKey
            backgroundColor = kind == "color"
                ? UIColor(
                    red: CGFloat(configuration.environmentRed),
                    green: CGFloat(configuration.environmentGreen),
                    blue: CGFloat(configuration.environmentBlue),
                    alpha: CGFloat(configuration.environmentAlpha)
                )
                : .clear
        }

        let environment: SceneEnvironment?
        if kind == "hdr", let path = configuration.environmentHdrPath, !path.isEmpty {
            environment = SceneEnvironment(
                name: path,
                hdrResource: path,
                intensity: configuration.ambientIntensity,
                showSkybox: configuration.environmentShowSkybox
            )
        } else {
            environment = nil
        }

        // `SceneEnvironment` is not `Equatable`, and the HDR loader is a multi-hundred-
        // millisecond decode of a ~30 MB texture — re-running it per frame would be
        // ruinous. Compare the fields that identify one instead.
        let key = environment.map { "\($0.name)|\($0.intensity)|\($0.showSkybox)" }
        guard key != state.environmentKey else { return }
        state.environmentKey = key
        state.environment = environment
    }

    /// Logs any URL the scheme allowlist refused.
    ///
    /// Resolving a configuration drops a refused entry silently — `compactMap` cannot say
    /// why something is missing — and a refused scheme renders exactly like "no model
    /// set". Logged rather than silently degraded, which is the whole reason
    /// `SceneViewerModelRequest.make` distinguishes `nil` from `.none`.
    private static func warnIfURLRefused(_ configuration: SceneViewerConfiguration) {
        func check(_ urlString: String?, _ assetPath: String?, _ bytes: Data?) {
            guard SceneViewerModelRequest.make(
                assetPath: assetPath,
                urlString: urlString,
                bytes: bytes,
                bytesFileExtension: "usdz"
            ) == nil else { return }
            NSLog(
                "[SceneViewSwift] SceneViewerHostView rejected model URL '%@': "
                    + "only http and https are accepted",
                urlString ?? ""
            )
        }

        if configuration.models.isEmpty {
            check(
                configuration.modelURLString,
                configuration.modelAssetPath,
                configuration.modelBytes
            )
        } else {
            for model in configuration.models {
                check(model.urlString, model.assetPath, model.bytes)
            }
        }
    }
}

// MARK: - Observable state

/// The scene's live inputs, and the callbacks out of it.
///
/// Holding the callbacks here rather than on the view is what keeps the object graph
/// acyclic: the hosted SwiftUI tree needs to invoke them, and if it reached them through
/// the view it would have to capture the view — which owns the hosting controller, which
/// owns the tree. That is exactly the retain cycle `SceneViewPlugin.swift` documents
/// (issue #2069), avoided here by construction rather than by a weak capture.
@MainActor
final class SceneViewerState: ObservableObject {

    /// Joint identity of the models to display; drives the loading task.
    @Published var modelListKey: String = ""

    /// The payload behind ``modelListKey``. Not `@Published` — nothing renders from it
    /// directly, and republishing megabytes of `Data` would invalidate the SwiftUI body.
    var modelEntries: [SceneViewerModelEntry] = []

    /// Bumped on every genuine app write to ``cameraPose``. `SceneView` applies on a
    /// generation change, not on a value change, so re-writing a pose the camera has
    /// since been dragged away from is honoured instead of dropped as a no-op.
    @Published var cameraPoseGeneration: Int = 0

    @Published var cameraPose = SceneCameraPose(
        azimuth: 0,
        elevation: SceneViewerAngle.radians(fromDegrees: 15),
        distance: 4,
        target: .zero
    )
    @Published var cameraGesturesEnabled = true
    @Published var cameraControlMode: CameraControlMode = .orbit
    @Published var autoCenterContent = false
    @Published var lighting = SceneViewerLighting(
        direction: SIMD3<Float>(0.3, -1, -0.5),
        intensity: 100_000,
        ambientIntensity: 1,
        castShadows: true
    )
    @Published var environment: SceneEnvironment?

    /// Identity of ``environment``, for the change check in `applyEnvironment`.
    var environmentKey: String?

    /// The last pose handed out through ``onCameraMoved``.
    ///
    /// The reference point for echo suppression — see `SceneViewerHostView.applyCamera`.
    /// Not `@Published`: it changes on every frame of a drag and nothing renders from it.
    var lastReportedPose: SceneCameraPose?

    var onTap: ((Bool, Float, Float, Float, Float) -> Void)?
    var onTapEntity: ((SceneTapHit) -> Void)?
    var onCameraMoved: ((Float, Float, Float) -> Void)?

    func reportCameraMoved(_ pose: SceneCameraPose) {
        lastReportedPose = pose
        onCameraMoved?(
            pose.distance,
            SceneViewerAngle.degrees(fromRadians: pose.azimuth),
            SceneViewerAngle.degrees(fromRadians: pose.elevation)
        )
    }

    func reportTap(_ hit: SceneTapHit) {
        // Handed over before the flattening, and unconditionally: a consumer of the full
        // hit is not interested in whether the primitive one is wired.
        onTapEntity?(hit)
        // Distance is measured from where the camera actually is, which is the last
        // reported pose when there is one — the requested pose can be several frames
        // stale mid-drag, and after any gesture it is not where the camera is at all.
        let pose = lastReportedPose ?? cameraPose
        let position = hit.worldPosition
        let distance = simd_length(position - pose.cameraPosition())
        onTap?(true, position.x, position.y, position.z, distance)
    }
}

// MARK: - Hosted SwiftUI scene

/// Persistent per-scene objects that must survive every SwiftUI re-render.
///
/// `@StateObject`-held, so the content root keeps its identity: rebuilding it would
/// orphan the loaded model, and rebuilding the light node would make ``LightSlot`` — which
/// compares `.custom` by entity reference — read every render as a light change and swap
/// the entity out of the scene at frame rate.
@MainActor
final class SceneViewerSceneObjects: ObservableObject {
    let contentRoot = SceneViewerContentRoot()

    private var appliedLighting: SceneViewerLighting?
    private var lightNode: LightNode?

    /// The key `LightNode` for `lighting`, rebuilt only when the values change.
    func light(for lighting: SceneViewerLighting) -> LightNode {
        if let lightNode, appliedLighting == lighting { return lightNode }
        // Directional lights emit along their forward (-Z) axis, so aiming one is
        // `look(at:)` from the origin toward the direction the light travels.
        let node = LightNode
            .directional(intensity: lighting.intensity, castsShadow: lighting.castShadows)
            .position(.zero)
            .lookAt(lighting.normalizedDirection)
        appliedLighting = lighting
        self.lightNode = node
        return node
    }
}

/// Owns the entity the models are attached to, and reconciles it with the request.
@MainActor
final class SceneViewerContentRoot {

    /// Handed to ``SceneView`` once, during scene setup, and mutated afterwards.
    ///
    /// `SceneView`'s content closure runs once (or once per `.contentID(_:)` change), so
    /// a model that finishes loading later cannot appear through it. A stable root that
    /// the loader attaches to is the pattern the SceneViewSwift docs prescribe for
    /// async-loaded content, and the one the Flutter bridge already uses.
    let entity = Entity()

    /// Attached model entities, keyed by ``SceneViewerModelEntry/key``.
    ///
    /// Keyed rather than a single slot so a removal detaches exactly the entity that left
    /// the list and disturbs nothing else — which is what makes one reconciliation express
    /// both of the shapes its callers have: Flutter appends one model at a time and
    /// expects the others to stay put, React Native hands over a whole new list and
    /// expects the old one gone. Neither is special-cased; both fall out of "detach what
    /// is no longer asked for, load what is not yet attached".
    private var loaded: [String: Entity] = [:]

    /// Monotonic token identifying the in-flight load.
    ///
    /// The supersession check cannot compare the *key*, because a key is not unique over
    /// time. An A → B → A swap inside one load window (a model picker; simulator loads
    /// run for hundreds of milliseconds) gives two live tasks that both observe `loaded`
    /// without an entry for `"asset:A"` on resume — `Entity(named:)` is not
    /// cancellation-aware, so the first task's continuation still runs — and both attach.
    /// The first model becomes a child that `loaded` then stops pointing at, so nothing
    /// can ever detach it: two coincident copies render, and one leaks for the life of
    /// the view.
    private var loadGeneration: Int = 0

    /// Reconciles what is attached with `entries`, loading and detaching the difference.
    func sync(to entries: [SceneViewerModelEntry]) async {
        let desired = Set(entries.map(\.key))

        // Detach what left the list. Snapshot the keys first — mutating the dictionary
        // while iterating its `keys` view is undefined. An empty list detaches everything,
        // which is what both bridges' `clearScene` / empty-prop path means.
        for key in Array(loaded.keys) where !desired.contains(key) {
            loaded.removeValue(forKey: key)?.removeFromParent()
        }

        loadGeneration &+= 1
        let generation = loadGeneration

        for entry in entries where loaded[entry.key] == nil {
            do {
                let node = try await Self.load(entry.request)
                // Compare the token captured before the `await`, not the key.
                guard loadGeneration == generation else { return }
                // The list may have been re-applied while this was in flight and this
                // entry loaded by the newer pass; attaching again would render two copies.
                guard loaded[entry.key] == nil else { continue }

                // Only what the caller actually specified — writing a default position or
                // an identity scale would overwrite the transform the asset authored.
                if let position = entry.position { _ = node.position(position) }
                if let scale = entry.scale { _ = node.scale(scale) }
                if let nodeName = entry.nodeName { node.entity.name = nodeName }
                if let animationName = entry.animationName {
                    node.playAnimation(named: animationName)
                } else if entry.autoPlayAllAnimations, node.animationCount > 0 {
                    node.playAllAnimations()
                }

                loaded[entry.key] = node.entity
                entity.addChild(node.entity)
            } catch {
                // Logged, not thrown: there is no failed state in the viewer façade, and a
                // failed load leaves the environment on screen — indistinguishable from a
                // load still in progress. The log is the only thing that tells them apart,
                // which is precisely why it must exist.
                //
                // One bad entry does not abandon the rest of the list, and a failure
                // leaves nothing in `loaded`, so the entry is retried the next time the
                // list is synced. Left marked as loaded, a transient failure — a network
                // blip on a `.url`, a resource missing from a freshly-installed bundle —
                // would be permanent for that model until the caller changed the list.
                NSLog(
                    "[SceneViewSwift] SceneViewerHostView failed to load model '%@': %@",
                    entry.key,
                    error.localizedDescription
                )
            }
        }
    }

    private static func load(_ request: SceneViewerModelRequest) async throws -> ModelNode {
        switch request {
        case .none:
            throw SceneViewerHostError.noModel
        case .asset(let path):
            return try await ModelNode.load(path)
        case .url(let url):
            return try await ModelNode.load(from: url)
        case .bytes(let data, let fileExtension):
            // RealityKit has no data-based loader — it dispatches on the file extension,
            // so bytes have to become a file with the right one before it will look at
            // them.
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension(fileExtension)
            // `defer` registered BEFORE the write, not after: `Data.write(to:)` is
            // non-atomic by default, so a throw mid-write (disk full) leaves a partial
            // file behind — and a cleanup registered on the next line never runs for the
            // one case that creates the garbage. Removing a file that was never created
            // is a no-op, so the early registration costs nothing.
            defer { try? FileManager.default.removeItem(at: url) }
            try data.write(to: url)
            return try await ModelNode.load(contentsOf: url)
        }
    }
}

enum SceneViewerHostError: LocalizedError {
    case noModel

    var errorDescription: String? {
        switch self {
        case .noModel: return "No model source was set on the configuration"
        }
    }
}

/// The SwiftUI scene the host view hosts.
struct SceneViewerRootView: View {

    @ObservedObject var state: SceneViewerState

    @StateObject private var scene = SceneViewerSceneObjects()

    var body: some View {
        var view = SceneView { root in
            root.addChild(scene.contentRoot.entity)
        }
        .cameraControls(state.cameraControlMode)
        // Defaults to off, and the reason is on `SceneViewerConfiguration.autoCenterContent`:
        // the fit-to-bounds pass owns the orbit radius and would overwrite the distance the
        // caller wrote, then overwrite the caller's own state with it through
        // `onCameraChanged`. Callers that author no camera at all — the Flutter and React
        // Native bridges — turn it on and want exactly that framing.
        .autoCenterContent(state.autoCenterContent)
        .cameraGesturesEnabled(state.cameraGesturesEnabled)
        .cameraPose(state.cameraPose)
        .cameraPoseGeneration(state.cameraPoseGeneration)
        .onCameraChanged { pose in
            state.reportCameraMoved(pose)
        }
        .onEntityTapHit { hit in
            state.reportTap(hit)
        }
        .mainLight(.custom(scene.light(for: state.lighting)))
        // The façade exposes one key light. A second, unexposed light would keep lighting
        // the scene with an intensity the caller can neither see nor turn off — the same
        // reason the Android implementation passes `fillLightNode = null`.
        .fillLight(.disabled)

        if let environment = state.environment {
            view = view.environment(environment)
        }

        return view.task(id: state.modelListKey) {
            await scene.contentRoot.sync(to: state.modelEntries)
        }
    }
}
#endif

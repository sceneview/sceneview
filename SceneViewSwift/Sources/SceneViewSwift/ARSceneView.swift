#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import CoreImage

/// A SwiftUI view for augmented reality using ARKit + RealityKit.
///
/// Mirrors SceneView Android's `ARSceneView { }` composable — place content
/// relative to real-world surfaces, images, and anchors.
///
/// Uses `ARView` (UIKit) wrapped in `UIViewRepresentable` for full ARKit
/// support on iPhone. Provides plane detection, tap-to-place hit testing,
/// and coaching overlay.
///
/// ```swift
/// ARSceneView(
///     planeDetection: .horizontal,
///     onTapOnPlane: { position in
///         // Place a 10 cm cube at the tapped surface
///         let cube = GeometryNode.cube(size: 0.1, color: .blue)
///         let anchor = AnchorNode.world(position: position)
///         anchor.add(cube.entity)
///         // arView.scene.addAnchor(anchor.entity) — done automatically
///     }
/// )
/// ```
public struct ARSceneView: UIViewRepresentable {
    private var planeDetection: PlaneDetectionMode
    private var showPlaneOverlay: Bool
    private var showCoachingOverlay: Bool
    private var cameraExposure: Float?
    private var onTapOnPlane: ((SIMD3<Float>, ARView) -> Void)?
    private var onSessionStarted: ((ARView) -> Void)?
    private var imageTrackingDatabase: Set<ARReferenceImage>?
    private var onImageDetected: ((String, AnchorNode, ARView) -> Void)?
    private var onFrame: ((ARFrame, ARView) -> Void)?

    // Light slot overrides — read once during scene setup and re-applied via
    // `updateUIView` when the caller mutates the modifier value. Defaults to
    // `.systemDefault` which provisions Android-parity 10 000-lux main + 3 000-lux
    // fill (matches PR #1136 / #1063 — `ARSceneView` Android exposes
    // `mainLightNode` + `fillLightNode` parameters with the same defaults).
    //
    // ARCore's `ENVIRONMENTAL_HDR` light estimation only drives `mainLightNode`
    // on Android (it returns SH coefficients and main-light direction/intensity).
    // ARKit's `.automatic` environment texturing is the closest equivalent — it
    // populates a runtime cubemap on `ARView` for PBR reflections — but does NOT
    // mutate any explicit directional light. The fill light therefore keeps its
    // baseline intensity each frame on both platforms.
    var mainLightSlot: LightSlot = .systemDefault
    var fillLightSlot: LightSlot = .systemDefault

    /// Plane detection modes matching Android's ARCore config.
    public enum PlaneDetectionMode: Sendable {
        case none
        case horizontal
        case vertical
        case both

        var arPlaneDetection: ARWorldTrackingConfiguration.PlaneDetection {
            switch self {
            case .none: return []
            case .horizontal: return .horizontal
            case .vertical: return .vertical
            case .both: return [.horizontal, .vertical]
            }
        }
    }

    /// Creates an AR scene with plane detection, image tracking, and tap-to-place.
    ///
    /// - Parameters:
    ///   - planeDetection: Which plane orientations to detect. Default horizontal.
    ///   - showPlaneOverlay: Whether to visualize detected planes. Default true.
    ///   - showCoachingOverlay: Whether to show coaching when tracking limited. Default true.
    ///   - imageTrackingDatabase: Set of reference images to detect. Use
    ///     `AugmentedImageNode.createImageDatabase()` or
    ///     `AugmentedImageNode.referenceImages(inGroupNamed:)` to create.
    ///   - cameraExposure: Optional exposure compensation for the camera feed, in EV
    ///     (exposure value) stops. When non-nil, a post-processing brightness adjustment
    ///     is applied to the rendered frame via `ARView.renderCallbacks.postProcess`.
    ///     Positive values brighten the scene; negative values darken it. A value of `0.0`
    ///     leaves the camera feed unchanged. Pass `nil` (the default) to skip any
    ///     exposure override and rely on ARKit's built-in auto-exposure.
    ///
    ///     Mirrors Android's `ARSceneView(cameraExposure: Float?)` parameter, which
    ///     overrides Filament's camera aperture/shutter/ISO when ARCore's auto-exposure
    ///     does not match the Camera2 output on a given device.
    ///
    ///     Requires iOS 15.0+. On earlier OS versions the value is stored but has no effect.
    ///   - onTapOnPlane: Called with (worldPosition, arView) when user taps on a plane.
    ///   - onImageDetected: Called with (imageName, anchorNode, arView) when a reference
    ///     image is detected. Add content to the anchor and call
    ///     `arView.scene.addAnchor(anchor.entity)`.
    public init(
        planeDetection: PlaneDetectionMode = .horizontal,
        showPlaneOverlay: Bool = true,
        showCoachingOverlay: Bool = true,
        cameraExposure: Float? = nil,
        imageTrackingDatabase: Set<ARReferenceImage>? = nil,
        onTapOnPlane: ((SIMD3<Float>, ARView) -> Void)? = nil,
        onImageDetected: ((String, AnchorNode, ARView) -> Void)? = nil,
        onFrame: ((ARFrame, ARView) -> Void)? = nil
    ) {
        self.planeDetection = planeDetection
        self.showPlaneOverlay = showPlaneOverlay
        self.showCoachingOverlay = showCoachingOverlay
        self.cameraExposure = cameraExposure
        self.imageTrackingDatabase = imageTrackingDatabase
        self.onTapOnPlane = onTapOnPlane
        self.onImageDetected = onImageDetected
        self.onFrame = onFrame
    }

    /// Called once when the AR session starts. Use to add initial content.
    public func onSessionStarted(
        _ handler: @escaping (ARView) -> Void
    ) -> ARSceneView {
        var copy = self
        copy.onSessionStarted = handler
        return copy
    }

    /// Sets an exposure compensation override for the AR camera feed.
    ///
    /// Positive values brighten the rendered scene; negative values darken it. One stop
    /// equals a doubling or halving of brightness. Pass `nil` to remove any override and
    /// rely on ARKit's built-in auto-exposure.
    ///
    /// Mirrors Android's `cameraExposure` parameter on `ARSceneView`, which overrides
    /// Filament's camera aperture/shutter/ISO when ARCore's auto-exposure does not match
    /// the Camera2 output on a given device.
    ///
    /// Implemented via `ARView.renderCallbacks.postProcess` (iOS 15.0+) using a
    /// `CIColorControls` brightness filter. On earlier OS versions, the call is a no-op.
    ///
    /// - Parameter ev: Exposure compensation in EV stops. `0.0` = no change, positive
    ///   values brighten, negative values darken.
    /// - Returns: A copy of this view with the exposure override applied.
    public func cameraExposure(_ ev: Float?) -> ARSceneView {
        var copy = self
        copy.cameraExposure = ev
        return copy
    }

    /// Called on every updated AR frame. Use for debug logging (e.g.
    /// streaming to the Rerun viewer via ``RerunBridge``) or custom
    /// per-frame analysis. Mirrors Android's `onSessionUpdated` callback.
    ///
    /// Runs on the ARKit delegate queue — do NOT block here. For I/O,
    /// hand the frame off to a background queue.
    public func onFrame(
        _ handler: @escaping (ARFrame, ARView) -> Void
    ) -> ARSceneView {
        var copy = self
        copy.onFrame = handler
        return copy
    }

    /// Configures the main / key directional light slot for the AR scene.
    ///
    /// Mirrors SceneView Android's `ARSceneView(mainLightNode = ...)`
    /// composable parameter (`ARScene.kt:294`). Default (`.systemDefault`):
    /// directional light at `10 000` lux pointing straight down (`-Y`),
    /// shadow casting enabled — same baseline as the 3D ``SceneView``.
    ///
    /// On Android, ARCore's `ENVIRONMENTAL_HDR` light estimation mutates
    /// the main light's color + intensity each frame. ARKit has no
    /// equivalent on the explicit-light side; instead `.automatic`
    /// environment texturing populates the IBL cubemap for PBR reflections.
    /// The directional light therefore keeps its baseline values unless
    /// you override them via `.custom(LightNode...)`.
    ///
    /// ```swift
    /// ARSceneView(planeDetection: .horizontal)
    ///   .mainLight(.custom(LightNode.directional(intensity: 5_000)))  // dimmer key
    ///
    /// ARSceneView(planeDetection: .horizontal)
    ///   .mainLight(.disabled)                                          // IBL-only AR
    /// ```
    public func mainLight(_ slot: LightSlot) -> ARSceneView {
        var copy = self
        copy.mainLightSlot = slot
        return copy
    }

    /// Configures the secondary fill directional light slot for the AR scene.
    ///
    /// Mirrors SceneView Android's `ARSceneView(fillLightNode = ...)`
    /// composable parameter shipped in PR #1136 (closes the iOS half of
    /// `#1063`). Default (`.systemDefault`): ``LightNode/fill(color:intensity:castsShadow:)``
    /// at `3 000` lux, no shadow, oriented along the canonical Android
    /// direction `(0.5, -0.5, 0.5)` (upper-back-left → down-front-right)
    /// so the unlit side of objects gets a soft kick without flattening
    /// the AR shading.
    ///
    /// Pair with ``mainLight(_:)`` to fully override the dual-light setup.
    /// Pass `.disabled` for a single-light AR scene (rare — most demos
    /// look harsh with a single hard directional light + no fill).
    ///
    /// ```swift
    /// ARSceneView(planeDetection: .horizontal)
    ///   .fillLight(.custom(LightNode.fill(intensity: 6_000)))   // brighter fill
    ///
    /// ARSceneView(planeDetection: .horizontal)
    ///   .fillLight(.disabled)                                    // single-light AR
    /// ```
    public func fillLight(_ slot: LightSlot) -> ARSceneView {
        var copy = self
        copy.fillLightSlot = slot
        return copy
    }

    // MARK: - UIViewRepresentable

    public func makeUIView(context: Context) -> ARView {
        let arView = ARView(frame: .zero)
        arView.automaticallyConfigureSession = false

        // Configure AR session
        let config = ARWorldTrackingConfiguration()
        config.planeDetection = planeDetection.arPlaneDetection
        // RealityKit's `.automatic` environment texturing is the functional
        // equivalent of ARCore's `Config.LightEstimationMode.ENVIRONMENTAL_HDR`
        // (Android default since v4.3.0 / `#1063`) — ARKit auto-builds the
        // runtime cubemap that drives PBR reflections on metallic + glossy
        // materials. There is no enum to toggle: it's on by default, off when
        // unsupported. Closes the env-texturing half of #1138.
        config.environmentTexturing = .automatic

        // Image tracking
        if let images = imageTrackingDatabase, !images.isEmpty {
            config.detectionImages = images
            config.maximumNumberOfTrackedImages = images.count
        }

        if ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh) {
            config.sceneReconstruction = .mesh
        }

        arView.session.run(config, options: [.resetTracking, .removeExistingAnchors])
        arView.session.delegate = context.coordinator

        // Plane visualization.
        //
        // `arView.debugOptions.insert(.showAnchorGeometry)` is a *developer
        // debug* aid — RealityKit renders detected `ARPlaneAnchor`s as a solid,
        // fully-opaque fluorescent-green fill that overpaints the camera feed.
        // It must never ship to end users (#1557). Instead the coordinator
        // tracks `ARPlaneAnchor` add/update/remove events and renders a subtle
        // translucent overlay entity per plane — see `PlaneVisualizer`.
        context.coordinator.showPlaneOverlay = showPlaneOverlay && planeDetection != .none

        // Coaching overlay
        if showCoachingOverlay {
            let coaching = ARCoachingOverlayView()
            coaching.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            coaching.session = arView.session
            coaching.goal = coachingGoal
            coaching.activatesAutomatically = true
            arView.addSubview(coaching)
        }

        // Tap gesture
        let tapRecognizer = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleTap(_:))
        )
        arView.addGestureRecognizer(tapRecognizer)

        // Store reference for coordinator
        context.coordinator.arView = arView

        // Provision both light slots BEFORE the host app's session-started callback
        // runs, so user-supplied content sees the dual-light baseline already in
        // place. Mirrors Android's `ARSceneView { content }` ordering where the
        // composable's `mainLightNode` / `fillLightNode` parameters are added to
        // the scene before the trailing `content` lambda. Closes the iOS half
        // of #1063 / #1138.
        provisionARLightSlot(.main, slot: mainLightSlot, in: arView, coordinator: context.coordinator)
        provisionARLightSlot(.fill, slot: fillLightSlot, in: arView, coordinator: context.coordinator)
        context.coordinator.appliedMainSlot = mainLightSlot
        context.coordinator.appliedFillSlot = fillLightSlot

        // Initial content callback
        onSessionStarted?(arView)

        return arView
    }

    public func updateUIView(_ arView: ARView, context: Context) {
        context.coordinator.onTapOnPlane = onTapOnPlane
        context.coordinator.onImageDetected = onImageDetected
        context.coordinator.onFrame = onFrame

        // Apply camera exposure override via post-processing (iOS 15.0+).
        // Converts the EV value to a CIColorControls brightness offset and installs
        // (or removes) a post-process render callback on the ARView.
        applyExposure(cameraExposure, to: arView)

        // Diff light slots and swap entities when the caller's modifier value
        // changed since last frame. Mirrors the reactive light path in
        // ``SceneView`` (#1017) and Android's `prevFillLightRef` pattern in
        // `ARScene.kt:540`. Closes #1138.
        refreshARLightSlot(.main, slot: mainLightSlot, in: arView, coordinator: context.coordinator)
        refreshARLightSlot(.fill, slot: fillLightSlot, in: arView, coordinator: context.coordinator)
    }

    // MARK: - Light slot provisioning (#1138)

    /// Identifies the active light anchor in `arView.scene.anchors` so the
    /// reactive diff can locate it on subsequent renders.
    fileprivate enum LightSlotKind { case main, fill }

    /// Provisions a directional light anchor on `arView.scene` for the given
    /// slot, applying the per-slot defaults when `slot == .systemDefault`.
    /// The anchor reference is cached on the coordinator so the reactive
    /// diff in ``refreshARLightSlot(_:slot:in:coordinator:)`` can replace it
    /// when the caller's `LightSlot` value changes.
    ///
    /// Lights are added as `AnchorEntity(world: .zero)` children of
    /// `arView.scene` — RealityKit AR scenes are rooted in `AnchorEntity`s,
    /// not bare `Entity`s, so this is the only way to inject a fixed-pose
    /// directional light. The light still renders relative to the world
    /// origin (which is where ARKit sets the session origin at session start).
    @MainActor
    private func provisionARLightSlot(
        _ which: LightSlotKind,
        slot: LightSlot,
        in arView: ARView,
        coordinator: Coordinator
    ) {
        let lightEntity: Entity?
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
                lightEntity = main.entity
            case .fill:
                let fill = LightNode.fill(intensity: 3_000)
                fill.entity.look(at: .zero, from: [-0.5, 0.5, -0.5], relativeTo: nil)
                lightEntity = fill.entity
            }
        case .disabled:
            lightEntity = nil
        case .custom(let node):
            lightEntity = node.entity
        }
        guard let lightEntity = lightEntity else { return }
        // Wrap in a fixed world anchor so RealityKit accepts it at the scene
        // root (an AR scene's `anchors` collection only takes AnchorEntities).
        let anchor = AnchorEntity(world: .zero)
        anchor.addChild(lightEntity)
        arView.scene.addAnchor(anchor)
        switch which {
        case .main: coordinator.mainLightAnchor = anchor
        case .fill: coordinator.fillLightAnchor = anchor
        }
    }

    /// Diffs the current slot value against the cached `applied{Main,Fill}Slot`
    /// stored on the coordinator and swaps the anchored light in-place when
    /// they differ. Called from `updateUIView(_:context:)`. Closes #1138.
    @MainActor
    private func refreshARLightSlot(
        _ which: LightSlotKind,
        slot: LightSlot,
        in arView: ARView,
        coordinator: Coordinator
    ) {
        let applied: LightSlot? = (which == .main)
            ? coordinator.appliedMainSlot
            : coordinator.appliedFillSlot
        guard applied != slot else { return }   // no change since last frame
        // Remove the old tagged anchor if present.
        let cachedAnchor: AnchorEntity?
        switch which {
        case .main: cachedAnchor = coordinator.mainLightAnchor
        case .fill: cachedAnchor = coordinator.fillLightAnchor
        }
        if let cached = cachedAnchor {
            arView.scene.removeAnchor(cached)
        }
        switch which {
        case .main: coordinator.mainLightAnchor = nil
        case .fill: coordinator.fillLightAnchor = nil
        }
        // Provision the new one (or none if the new slot is .disabled).
        provisionARLightSlot(which, slot: slot, in: arView, coordinator: coordinator)
        // Update the cache so the next frame's diff is a no-op.
        switch which {
        case .main: coordinator.appliedMainSlot = slot
        case .fill: coordinator.appliedFillSlot = slot
        }
    }

    // MARK: - Exposure helpers

    /// Applies (or removes) a brightness post-process callback on `arView` to simulate
    /// the `cameraExposure` EV override from Android's ARSceneView.
    ///
    /// An EV of `+1` doubles perceived brightness (maps to +0.5 CIColorControls
    /// brightness), an EV of `-1` halves it (maps to -0.5). The mapping is linear and
    /// intentionally simple — Filament on Android uses physical aperture/shutter/ISO,
    /// but RealityKit does not expose those parameters, so a CIFilter post-process is
    /// the closest available approximation.
    @available(iOS 15.0, *)
    private func applyExposurePostProcess(_ ev: Float, to arView: ARView) {
        arView.renderCallbacks.postProcess = { [ev] context in
            guard
                let filter = CIFilter(name: "CIColorControls"),
                // CIImage(mtlTexture:) is failable — texture format must be supported.
                let ciImage = CIImage(mtlTexture: context.sourceColorTexture, options: nil)
            else { return }
            // Map EV stops to CIColorControls brightness range [-1, 1]:
            // each EV stop equals 0.5 brightness units so that ±2 EV covers the full range.
            let brightness = NSNumber(value: Double(ev) * 0.5)
            filter.setValue(ciImage, forKey: kCIInputImageKey)
            filter.setValue(brightness, forKey: kCIInputBrightnessKey)
            guard let outputImage = filter.outputImage else { return }
            let ciContext = CIContext(mtlDevice: context.device)
            ciContext.render(
                outputImage,
                to: context.targetColorTexture,
                commandBuffer: context.commandBuffer,
                bounds: outputImage.extent,
                colorSpace: CGColorSpaceCreateDeviceRGB()
            )
        }
    }

    private func applyExposure(_ ev: Float?, to arView: ARView) {
        guard let ev = ev else {
            // Remove any previously installed post-process callback.
            if #available(iOS 15.0, *) {
                arView.renderCallbacks.postProcess = nil
            }
            return
        }
        if #available(iOS 15.0, *) {
            applyExposurePostProcess(ev, to: arView)
        }
        // On iOS < 15 the value is stored (via the modifier / init) but silently ignored.
    }

    public func makeCoordinator() -> Coordinator {
        Coordinator(
            onTapOnPlane: onTapOnPlane,
            planeDetection: planeDetection,
            onImageDetected: onImageDetected,
            onFrame: onFrame,
            imageTrackingDatabase: imageTrackingDatabase,
            // Mesh reconstruction is implicitly enabled in makeUIView when
            // supported; mirror the flag here so it's re-applied after an
            // interruption (closes part of #928).
            enableMeshReconstruction: true,
            environmentTexturing: .automatic
        )
    }

    private var coachingGoal: ARCoachingOverlayView.Goal {
        switch planeDetection {
        case .horizontal: return .horizontalPlane
        case .vertical: return .verticalPlane
        case .both, .none: return .anyPlane
        }
    }

    // MARK: - Coordinator

    public class Coordinator: NSObject, ARSessionDelegate {
        var onTapOnPlane: ((SIMD3<Float>, ARView) -> Void)?
        var onImageDetected: ((String, AnchorNode, ARView) -> Void)?
        var onFrame: ((ARFrame, ARView) -> Void)?
        var planeDetection: PlaneDetectionMode
        // Tracking config that must survive an interruption — previously only
        // `planeDetection` was preserved, so any image database / mesh recon /
        // environment-texturing flag was lost the moment the user backgrounded
        // the app and returned. Closes part of #928. The full set is re-applied
        // in `sessionInterruptionEnded(_:)` below.
        var imageTrackingDatabase: Set<ARReferenceImage>?
        var enableMeshReconstruction: Bool = true
        var environmentTexturing: ARWorldTrackingConfiguration.EnvironmentTexturing = .automatic
        weak var arView: ARView?
        private var detectedImageNames: Set<String> = []

        /// Whether detected planes should be visualized with a translucent
        /// overlay. Set from `makeUIView` once plane detection is known.
        var showPlaneOverlay: Bool = false

        /// Translucent overlay entities keyed by their `ARPlaneAnchor` id, so
        /// `didUpdate` can resize them and `didRemove` can tear them down.
        private var planeOverlays: [UUID: PlaneVisualizer] = [:]

        // Light-slot reactive plumbing — same pattern as ``SceneView`` (#1017)
        // adapted for AR. The anchor refs let the diff in `refreshARLightSlot`
        // tear down the previous light's `AnchorEntity` before adding a new one.
        // `applied{Main,Fill}Slot` is the cached previous value; the diff is a
        // no-op when it equals the current `LightSlot`.
        var mainLightAnchor: AnchorEntity?
        var fillLightAnchor: AnchorEntity?
        var appliedMainSlot: LightSlot?
        var appliedFillSlot: LightSlot?

        init(
            onTapOnPlane: ((SIMD3<Float>, ARView) -> Void)?,
            planeDetection: PlaneDetectionMode,
            onImageDetected: ((String, AnchorNode, ARView) -> Void)? = nil,
            onFrame: ((ARFrame, ARView) -> Void)? = nil,
            imageTrackingDatabase: Set<ARReferenceImage>? = nil,
            enableMeshReconstruction: Bool = true,
            environmentTexturing: ARWorldTrackingConfiguration.EnvironmentTexturing = .automatic
        ) {
            self.onTapOnPlane = onTapOnPlane
            self.planeDetection = planeDetection
            self.onImageDetected = onImageDetected
            self.onFrame = onFrame
            self.imageTrackingDatabase = imageTrackingDatabase
            self.enableMeshReconstruction = enableMeshReconstruction
            self.environmentTexturing = environmentTexturing
        }

        @objc func handleTap(_ recognizer: UITapGestureRecognizer) {
            guard let arView = recognizer.view as? ARView else { return }
            let location = recognizer.location(in: arView)

            // Raycast against detected planes
            let results = arView.raycast(
                from: location,
                allowing: .estimatedPlane,
                alignment: .any
            )
            if let firstResult = results.first {
                let column = firstResult.worldTransform.columns.3
                let position = SIMD3<Float>(column.x, column.y, column.z)
                onTapOnPlane?(position, arView)
            }
        }

        // MARK: - ARSessionDelegate

        public func session(_ session: ARSession, didUpdate frame: ARFrame) {
            guard let arView = arView, let onFrame = onFrame else { return }
            onFrame(frame, arView)
        }

        public func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
            guard let arView = arView else { return }

            for anchor in anchors {
                // Detected-plane visualization — a subtle translucent overlay,
                // never the opaque `.showAnchorGeometry` debug fill (#1557).
                if showPlaneOverlay, let planeAnchor = anchor as? ARPlaneAnchor {
                    let visualizer = PlaneVisualizer(planeAnchor: planeAnchor)
                    planeOverlays[planeAnchor.identifier] = visualizer
                    arView.scene.addAnchor(visualizer.anchor)
                    continue
                }

                guard let onImageDetected = onImageDetected,
                      let imageAnchor = anchor as? ARImageAnchor,
                      let imageName = imageAnchor.referenceImage.name,
                      !detectedImageNames.contains(imageName) else { continue }

                detectedImageNames.insert(imageName)
                let anchorEntity = AnchorEntity(anchor: imageAnchor)
                let anchorNode = AnchorNode(entity: anchorEntity)
                onImageDetected(imageName, anchorNode, arView)
            }
        }

        public func session(_ session: ARSession, didUpdate anchors: [ARAnchor]) {
            for anchor in anchors {
                guard let planeAnchor = anchor as? ARPlaneAnchor,
                      let visualizer = planeOverlays[planeAnchor.identifier] else { continue }
                visualizer.update(with: planeAnchor)
            }
        }

        public func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
            for anchor in anchors {
                guard let planeAnchor = anchor as? ARPlaneAnchor,
                      let visualizer = planeOverlays.removeValue(forKey: planeAnchor.identifier) else { continue }
                arView?.scene.removeAnchor(visualizer.anchor)
            }
        }

        public func session(
            _ session: ARSession,
            didFailWithError error: Error
        ) {
            print("[SceneViewSwift] AR session error: \(error.localizedDescription)")
        }

        public func sessionWasInterrupted(_ session: ARSession) {
            print("[SceneViewSwift] AR session interrupted")
        }

        public func sessionInterruptionEnded(_ session: ARSession) {
            print("[SceneViewSwift] AR session interruption ended — resuming with full config")
            // Re-apply the FULL tracking configuration that was active before the
            // interruption — previously only `planeDetection` survived, so consumers
            // doing image tracking / mesh reconstruction / non-default environment
            // texturing lost those features on every background→foreground cycle.
            // Closes part of #928 (ARSceneView.sessionInterruptionEnded silent stub).
            let config = ARWorldTrackingConfiguration()
            config.planeDetection = planeDetection.arPlaneDetection
            config.environmentTexturing = environmentTexturing
            if let images = imageTrackingDatabase, !images.isEmpty {
                config.detectionImages = images
                config.maximumNumberOfTrackedImages = images.count
            }
            if enableMeshReconstruction,
               ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh) {
                config.sceneReconstruction = .mesh
            }
            // Reset detected images so they can be re-detected after interruption.
            // (The anchors themselves are kept — we don't pass .removeExistingAnchors
            // here because the user's content + AnchorEntity references remain valid
            // across an interruption per Apple's ARKit guide.)
            detectedImageNames.removeAll()
            session.run(config)
        }
    }
}

// MARK: - Detected-plane visualization (#1557)

/// Renders a single detected `ARPlaneAnchor` as a *subtle, translucent*
/// overlay so the user can see where surfaces have been found without the
/// real-world camera feed being obscured.
///
/// This replaces RealityKit's `.showAnchorGeometry` debug option, which
/// renders detected planes as a solid, fully-opaque fluorescent-green fill —
/// a developer debug aid that must never reach end users (#1557, part of
/// #1373). The overlay here uses a low-opacity tinted material (~12 %),
/// matching how a polished ARKit app surfaces plane detection.
///
/// The overlay is purely cosmetic — it carries no `CollisionComponent`, so
/// it never interferes with the tap-to-place raycast in `handleTap`, which
/// raycasts against ARKit's `.estimatedPlane`, not scene geometry.
///
/// Not `@MainActor`-isolated: it is only ever constructed and mutated from
/// `ARSessionDelegate` callbacks, which ARKit delivers on the session's
/// delegate queue (the main thread for `ARSceneView`). This matches the
/// surrounding image-anchor handling in `session(_:didAdd:)`.
final class PlaneVisualizer {
    /// The world-anchored entity that hosts the overlay mesh. Added to /
    /// removed from `arView.scene` by the coordinator.
    let anchor: AnchorEntity

    /// The translucent fill entity, regenerated on each `update` because the
    /// plane's `extent` grows as ARKit refines its estimate.
    private let fill: ModelEntity

    /// Low-opacity tint for the plane fill. A faint white keeps the overlay
    /// readable against any background without overpainting the camera feed.
    private static let overlayColor: SimpleMaterial.Color =
        .init(white: 1.0, alpha: 0.12)

    init(planeAnchor: ARPlaneAnchor) {
        anchor = AnchorEntity(anchor: planeAnchor)
        fill = ModelEntity()
        anchor.addChild(fill)
        update(with: planeAnchor)
    }

    /// Resizes and re-centers the overlay to match the latest plane estimate.
    func update(with planeAnchor: ARPlaneAnchor) {
        let extent = planeAnchor.planeExtent
        // A thin (1 mm) box gives the overlay a flat footprint that conforms
        // to the detected surface. The plane's local frame is X/Z, so the
        // box width maps to X and depth to Z.
        let mesh = MeshResource.generateBox(
            size: [extent.width, 0.001, extent.height]
        )
        var material = UnlitMaterial(color: Self.overlayColor)
        // Transparent blending so the camera feed shows through — the core of
        // the #1557 fix. Without this the overlay is an opaque fill.
        material.blending = .transparent(opacity: .init(floatLiteral: 1.0))
        fill.model = ModelComponent(mesh: mesh, materials: [material])
        // `center` is the plane center relative to the anchor's transform.
        fill.position = [
            planeAnchor.center.x,
            planeAnchor.center.y,
            planeAnchor.center.z,
        ]
    }
}

// MARK: - AR Anchor helpers (mirrors Android's ARCore anchor API)

/// A wrapper around ARKit anchors for placing content in the real world.
///
/// Mirrors SceneView Android's `AnchorNode`.
public struct AnchorNode: Sendable {
    /// The underlying RealityKit anchor entity.
    public let entity: AnchorEntity

    /// Creates an anchor at a world position.
    public static func world(position: SIMD3<Float>) -> AnchorNode {
        let anchor = AnchorEntity(world: position)
        return AnchorNode(entity: anchor)
    }

    /// Creates an anchor on a detected plane.
    ///
    /// - Parameters:
    ///   - alignment: Horizontal or vertical plane.
    ///   - minimumBounds: Minimum plane size to anchor to.
    public static func plane(
        alignment: PlaneAlignment = .horizontal,
        minimumBounds: SIMD2<Float> = .init(0.1, 0.1)
    ) -> AnchorNode {
        let anchor: AnchorEntity
        switch alignment {
        case .horizontal:
            anchor = AnchorEntity(plane: .horizontal, minimumBounds: minimumBounds)
        case .vertical:
            anchor = AnchorEntity(plane: .vertical, minimumBounds: minimumBounds)
        }
        return AnchorNode(entity: anchor)
    }

    /// Creates an anchor that tracks a detected reference image.
    ///
    /// Mirrors SceneView Android's `AugmentedImageNode`. The image must already be
    /// registered via `ARSceneView(imageTrackingDatabase:)` so ARKit knows what to
    /// look for. Once the image is detected, content added to this anchor renders
    /// at the image's pose in the real world.
    ///
    /// - Parameters:
    ///   - group: AR Resource group name in the asset catalog (e.g. `"AR Resources"`).
    ///   - name: Reference image name within the group.
    public static func image(group: String, name: String) -> AnchorNode {
        let anchor = AnchorEntity(.image(group: group, name: name))
        return AnchorNode(entity: anchor)
    }

    /// Creates an anchor that tracks a detected face (front-camera).
    ///
    /// Mirrors SceneView Android's `AugmentedFaceNode` (closes part of #894).
    /// Requires `ARFaceTrackingConfiguration` to be active — set via
    /// `ARSceneView(faceTracking: true)` (when wired). Content added to this
    /// anchor renders attached to the user's face at runtime.
    ///
    /// **Limitation**: RealityKit's `AnchorEntity(.face)` provides face-pose
    /// tracking but no `ARSCNFaceGeometry`-equivalent mesh. For the morphing
    /// face-mesh overlay seen in Android's AugmentedFaceDemo, drop down to a
    /// raw `ARFaceAnchor` + custom mesh entity.
    public static func face() -> AnchorNode {
        let anchor = AnchorEntity(.face)
        return AnchorNode(entity: anchor)
    }

    /// Creates an anchor that tracks a detected body (rear-camera, iOS 13+).
    ///
    /// Requires `ARBodyTrackingConfiguration` to be active. Content added to
    /// this anchor renders at the detected human body's root joint.
    public static func body() -> AnchorNode {
        let anchor = AnchorEntity(.body)
        return AnchorNode(entity: anchor)
    }

    /// Adds a child entity to this anchor.
    public func add(_ child: Entity) {
        entity.addChild(child)
    }

    /// Removes a child entity from this anchor.
    public func remove(_ child: Entity) {
        entity.removeChild(child)
    }

    /// Removes all child entities from this anchor.
    public func removeAll() {
        for child in entity.children {
            entity.removeChild(child)
        }
    }

    /// Plane alignment type matching Android's Plane.Type.
    public enum PlaneAlignment: Sendable {
        case horizontal
        case vertical
    }
}
#endif // os(iOS)

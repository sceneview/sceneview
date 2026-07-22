// @sceneId     wall-placement
// @title       Wall Placement
// @subtitle    Mount a TV on a wall — floor↔wall edge alignment, Amazon AR-View style
// @category    ar
// @available   true
// @icon        tv.fill
// @iosOnly     true
// @status      inReview
import SwiftUI
#if os(iOS)
import ARKit
import RealityKit
import SceneViewSwift
#endif

/// iOS port of Android's `wall-placement` demo (#2740 / #2840).
///
/// Mirrors `samples/android-demo/.../demos/WallPlacementDemo.kt`, which itself
/// reproduces the Amazon "AR View" wall flow documented by the #2740 teardown.
/// Lands `inReview` to match Android's own `InReview` status — the flow and the
/// geometry compile and are reviewable, but live wall tracking cannot be
/// exercised on a Simulator (see ``WallPlacementDemoView``).
enum WallPlacementScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(WallPlacementDemoView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}

#if os(iOS)

/// Wall-placement demo — mounts a **procedural** TV flat against a vertical
/// surface, walking the user through the same four phases Android ships
/// (`WallPlacementPhase`):
///
/// 1. a **phase banner** drives FINDING_FLOOR → FINDING_WALL → ALIGNING_EDGE →
///    PLACED;
/// 2. during ALIGNING_EDGE a fixed **orange guide line** is drawn on screen —
///    the user physically aligns it with the floor↔wall seam before tapping
///    (the trick that makes wall placement work without relying on the seam
///    itself being perfectly tracked);
/// 3. after placement a **D-pad** fine-tunes the TV (2 cm nudges along the
///    wall, 2° yaw steps);
/// 4. the TV is **procedural** (two boxes: matte body + glossy screen) so the
///    demo bundles no asset and stays deterministic.
///
/// ### Where iOS differs from Android
///
/// - **Wall detection.** ARKit classifies plane anchors natively
///   (`ARPlaneAnchor.classification == .wall`) — the primitive ARCore lacks,
///   where Android has to settle for `Plane.Type.VERTICAL`. This port uses the
///   classification when the device reports one, and falls back to the plane
///   *alignment* when it is still undetermined, so the demo keeps working on
///   hardware whose classifier never resolves.
/// - **Geometry.** The placement math is a direct port of
///   `arsceneview/.../WallPlacement.kt`: orientation comes from the wall (a
///   pure yaw, never inheriting the hit pose's pitch/roll noise), vertical
///   position comes from the floor (`floorY + mountHeight`), so the panel does
///   not drift while ARKit refines the wall plane.
/// - **No wall contact shadow.** Android grounds the panel with a procedural
///   `ContactShadowContext.Wall` pool. RealityKit's `GroundingShadowComponent`
///   only projects *downward* onto a surface below an entity, and
///   `SceneViewSwift` has no `ContactShadow` equivalent yet
///   (`contact-shadow-preview` is still an iOS stub) — so this port ships
///   **without** the wall shadow rather than faking one. Stated in the
///   settings sheet, not hidden.
///
/// ### Verification status
///
/// The AR code path compiles for a real device, but ARKit world tracking does
/// **not** run on a Simulator: live plane classification, the floor-relative
/// mount height, and the D-pad's behaviour against a real wall are unverified
/// until this runs on hardware. That is why the demo lands `inReview`.
struct WallPlacementDemoView: View {

    // MARK: - Tuning (mirrors the Android constants)

    /// TV centre ~1.1 m above the floor — typical living-room mount height.
    /// Same value as the Android demo's `mountHeight = 1.1f`.
    private static let mountHeight: Float = 1.1

    /// D-pad step: 2 cm per nudge, 2° per rotation press.
    private static let nudgeStep: Float = 0.02
    private static let yawStep: Float = 2

    /// Height of the orange alignment-guide drawing container.
    ///
    /// **Must be non-zero.** The Android original carries the same warning for
    /// its Compose `Canvas`: a drawing container with no height clips the
    /// stroke away entirely and the guide silently renders nothing — a failure
    /// that never shows up in a build log.
    private static let guideLineHeight: CGFloat = 16

    /// Amazon-style orange for the alignment guide (Android: `0xFFFF8A00`).
    private static let guideColor = Color(red: 1.0, green: 0.541, blue: 0.0)

    /// How long a placement-error capsule stays on screen, seconds. Long
    /// enough to read a sentence, short enough that it never becomes furniture
    /// over the camera feed.
    private static let errorBannerDuration: Double = 3

    /// Maximum camera-to-hit distance accepted for a wall placement, metres.
    /// Mirrors Android's `MAX_WALL_PLACEMENT_DISTANCE` — wall planes converge
    /// noisily, so the reach is deliberately shorter than for floor placement.
    private static let maxWallPlacementDistance: Float = 4.0

    // MARK: - Phase machine

    /// Onboarding phases, mirroring Android's `WallPlacementPhase`.
    enum Phase {
        case findingFloor
        case findingWall
        case aligningEdge
        case placed

        /// Coaching copy, mirroring `strings_demo_wall_placement.xml`.
        var coachingText: String {
            switch self {
            case .findingFloor: return "Point at the floor and move slowly to scan it"
            case .findingWall: return "Floor locked — now aim at the wall"
            case .aligningEdge: return "Align the orange line with the floor↔wall edge, then tap the wall"
            case .placed: return "Placed — fine-tune with the D-pad, or tap the wall again"
            }
        }
    }

    /// Pure phase-transition rule — a direct port of Android's
    /// `nextWallPlacementPhase`. Placement is terminal: once anything is
    /// placed the phase stays `.placed` regardless of momentary tracking loss.
    static func nextPhase(floorFound: Bool, wallFound: Bool, placed: Bool) -> Phase {
        if placed { return .placed }
        if floorFound && wallFound { return .aligningEdge }
        if floorFound { return .findingWall }
        return .findingFloor
    }

    // MARK: - Geometry (direct port of arsceneview/.../WallPlacement.kt)

    /// A degenerate horizontal length below which a "wall normal" has no
    /// usable heading (e.g. a normal pointing straight up — not a wall).
    /// Mirrors Android's `WALL_NORMAL_EPSILON`.
    private static let wallNormalEpsilon: Float = 1e-6

    /// The upright yaw (radians, about world +Y) that makes an object's local
    /// **+Z** point along `wallNormal` — flush against the wall, facing into
    /// the room. Direct port of Android's `wallYaw`.
    ///
    /// A wall-mounted object rotates about the vertical axis **only**, so the
    /// whole orientation is a single yaw. The normal's vertical component is
    /// dropped and the horizontal part renormalised, so a slightly-off ARKit
    /// normal still yields a clean upright heading; a normal with no
    /// horizontal component is not a wall and falls back to `0` instead of
    /// producing a NaN out of `atan2(0, 0)`.
    static func wallYaw(_ wallNormal: SIMD3<Float>) -> Float {
        let horizontalLength = (wallNormal.x * wallNormal.x + wallNormal.z * wallNormal.z).squareRoot()
        guard horizontalLength >= wallNormalEpsilon else { return 0 }
        return atan2(wallNormal.x / horizontalLength, wallNormal.z / horizontalLength)
    }

    /// Ensures a wall normal points **into the room** (toward the viewer).
    /// Neither ARCore nor ARKit guarantees a vertical plane's +Y faces the
    /// observer — it can point wall-ward, which would seat the panel with its
    /// screen inside the wall. Direct port of Android's `roomFacingNormal`.
    static func roomFacingNormal(
        _ wallNormal: SIMD3<Float>,
        towardViewer: SIMD3<Float>
    ) -> SIMD3<Float> {
        simd_dot(wallNormal, towardViewer) < 0 ? -wallNormal : wallNormal
    }

    /// The final wall-anchor transform: flush and upright against the wall
    /// (yaw only) at a floor-relative height. Direct port of Android's
    /// `wallAnchorPose`.
    ///
    /// The horizontal placement (X/Z) comes from where the user tapped the
    /// wall; the height is `floorY + mountHeight`, decoupling the vertical
    /// position from the noisy wall hit so the panel stays put while ARKit
    /// refines the wall plane.
    static func wallAnchorTransform(
        wallHit: SIMD3<Float>,
        wallNormal: SIMD3<Float>,
        floorY: Float,
        mountHeight: Float
    ) -> simd_float4x4 {
        var transform = simd_float4x4(simd_quatf(angle: wallYaw(wallNormal), axis: SIMD3<Float>(0, 1, 0)))
        transform.columns.3 = SIMD4<Float>(wallHit.x, floorY + mountHeight, wallHit.z, 1)
        return transform
    }

    /// Whether a plane anchor is usable as a wall.
    ///
    /// ARKit's classification is the primitive Android has to approximate, so
    /// prefer it — but a plane whose classification is still `.none(…)`
    /// (undetermined, or hardware without a classifier) is accepted on
    /// alignment alone, which is exactly the Android behaviour. Windows and
    /// doors lie in the wall plane and are accepted too; a horizontal
    /// classification reported on a vertical plane is rejected.
    static func isWallPlane(_ anchor: ARPlaneAnchor) -> Bool {
        guard anchor.alignment == .vertical else { return false }
        switch anchor.classification {
        case .wall, .window, .door: return true
        case .none(_): return true
        default: return false
        }
    }

    /// Whether a plane anchor is a ceiling — excluded from the floor search,
    /// the ARKit stand-in for ARCore's `HORIZONTAL_UPWARD_FACING` filter
    /// (`ARPlaneAnchor.alignment` does not distinguish up- from down-facing).
    static func isCeiling(_ anchor: ARPlaneAnchor) -> Bool {
        if case .ceiling = anchor.classification { return true }
        return false
    }

    // MARK: - Per-TV fine-adjust

    /// One placed TV's fine-adjust, in the anchor's local frame: `offset.x`
    /// runs along the wall, `offset.y` is up; `yawDegrees` is an extra
    /// rotation on top of the wall-facing orientation. Mirrors Android's
    /// `Adjustment`.
    private struct Adjustment {
        var offset: SIMD2<Float> = .zero
        var yawDegrees: Float = 0

        var transform: Transform {
            Transform(
                scale: .one,
                rotation: simd_quatf(angle: yawDegrees * .pi / 180, axis: SIMD3<Float>(0, 1, 0)),
                translation: SIMD3<Float>(offset.x, offset.y, 0)
            )
        }
    }

    /// Hot, per-frame tracking state.
    ///
    /// Deliberately a reference box rather than `@State`: the frame callback
    /// fires at display rate and writing SwiftUI state from it would
    /// re-render the whole view every frame. Only the derived phase — which
    /// changes a handful of times per session — crosses into `@State`.
    private final class TrackingBox {
        /// Lowest tracked non-ceiling horizontal plane = the floor (mirrors
        /// Android's `HORIZONTAL_UPWARD_FACING` minimum).
        var floorY: Float?
        /// Whether at least one currently-tracked plane qualifies as a wall.
        var wallFound: Bool = false
        weak var arView: ARView?
        /// The most recently placed TV's adjust root — what the D-pad drives.
        /// Earlier TVs keep their own tweak because each adjustment lives on
        /// that TV's own entity transform, never in one shared value.
        var latestAdjustRoot: Entity?
        /// Everything this demo added, so "Clear placed TVs" can tear down
        /// both halves (RealityKit anchor entity + the ARKit anchor).
        var placedAnchors: [(arAnchor: ARAnchor, entity: AnchorEntity)] = []
        /// Retains the tap handler: `UIGestureRecognizer` holds its target
        /// weakly, so without this the handler would be collected at once.
        ///
        /// This strong reference closes a **retain cycle** — the handler's
        /// closure captures the view, whose `@State` storage owns this box —
        /// so it is not self-breaking. ``WallPlacementDemoView/teardown()``
        /// cuts it on `onDisappear`; without that, every placed TV's anchors,
        /// meshes and materials would be leaked once per visit to the demo.
        var tapHandler: AnyObject?
        /// The recogniser this demo installed, so teardown can detach it from
        /// the `ARView` instead of leaving a dead-target recogniser behind.
        var tapRecognizer: UIGestureRecognizer?
    }

    // MARK: - State

    @State private var phase: Phase = .findingFloor
    @State private var placedCount: Int = 0
    @State private var adjustment = Adjustment()
    @State private var lastError: String?
    @State private var tracking = TrackingBox()

    var body: some View {
        ZStack {
            #if !targetEnvironment(simulator)
            arScene
                .ignoresSafeArea()
            #else
            simulatorPlaceholder
            #endif

            phaseBanner
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)

            if phase == .aligningEdge {
                alignmentGuide
            }

            if phase == .placed {
                dPad
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            }

            if let lastError {
                errorBanner(lastError)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                    .padding(.bottom, 180)
            }
        }
        .demoSettingsSheet { controlsSheet }
        // The error capsule is this port's own addition (Android shows no such
        // banner), so it also owns dismissing itself: a user who taps a
        // non-wall once and then walks away must not be left with a red
        // capsule pinned over the camera feed forever. `.task(id:)` cancels
        // and restarts on every change, so a new message always gets a full
        // window rather than inheriting the previous one's remaining time.
        .task(id: lastError) {
            guard lastError != nil else { return }
            try? await Task.sleep(for: .seconds(Self.errorBannerDuration))
            guard !Task.isCancelled else { return }
            lastError = nil
        }
        .onDisappear { teardown() }
    }

    /// Breaks the tap-handler retain cycle and drops every placed TV.
    ///
    /// `tracking.tapHandler` is held strongly (the recogniser's target is
    /// weak) and its closure captures this view, whose `@State` storage owns
    /// `tracking` — a cycle that nothing else collects. Leaving it in place
    /// would strand the box, and with it `placedAnchors`' `ARAnchor`s,
    /// `AnchorEntity`s, meshes and materials, once per visit to the demo.
    ///
    /// The recogniser is detached too: `ARView` retains its recognisers, so
    /// one left installed with a nil target would linger as a dead no-op.
    private func teardown() {
        if let recognizer = tracking.tapRecognizer {
            tracking.arView?.removeGestureRecognizer(recognizer)
        }
        tracking.tapRecognizer = nil
        tracking.tapHandler = nil
        tracking.latestAdjustRoot = nil
        tracking.placedAnchors.removeAll()
        tracking.arView = nil
    }

    // MARK: - AR scene

    #if !targetEnvironment(simulator)
    private var arScene: some View {
        ARSceneView(
            planeDetection: .both,
            showPlaneOverlay: true,
            showCoachingOverlay: true,
            // Grounding shadows project straight down onto a detected surface;
            // a wall-mounted panel gets nothing useful out of them (see the
            // "No wall contact shadow" note on this type).
            groundingShadows: false,
            onFrame: { frame, _ in
                updateTracking(with: frame)
            }
        )
        .onSessionStarted { arView in
            tracking.arView = arView
            installTapHandler(on: arView)
        }
    }

    /// Refreshes the tracked-surface state each frame and pushes the derived
    /// phase into SwiftUI **only when it actually changes** — the Android
    /// scene guards the same way (`if (newPhase != phase)`).
    ///
    /// `ARSceneView` installs no `delegateQueue`, so ARKit delivers
    /// `session(_:didUpdate:)` — and therefore this callback — on the main
    /// thread; the library's own reticle update in the same delegate method
    /// relies on that too. No queue hop is needed, and none is taken.
    private func updateTracking(with frame: ARFrame) {
        var floorY: Float?
        var wallFound = false
        for anchor in frame.anchors {
            guard let plane = anchor as? ARPlaneAnchor else { continue }
            if plane.alignment == .horizontal, !Self.isCeiling(plane) {
                let y = plane.transform.columns.3.y
                floorY = min(floorY ?? y, y)
            } else if Self.isWallPlane(plane) {
                wallFound = true
            }
        }

        tracking.floorY = floorY
        tracking.wallFound = wallFound

        let next = Self.nextPhase(
            floorFound: floorY != nil,
            wallFound: wallFound,
            placed: placedCount > 0
        )
        if phase != next { phase = next }
    }

    // MARK: - Placement

    /// Adds this demo's own tap recogniser to the live `ARView`.
    ///
    /// `ARSceneView`'s built-in `onTapOnPlane` hands back only a world
    /// position — wall placement needs the *plane anchor* behind the hit (its
    /// classification and its normal), so the raycast is run here instead.
    /// `ARSceneView`'s own recogniser stays installed but no-ops, because no
    /// `onTapOnPlane` closure was supplied.
    private func installTapHandler(on arView: ARView) {
        let handler = TapHandler { recognizer in
            guard let view = recognizer.view as? ARView else { return }
            placeTV(at: recognizer.location(in: view), in: view)
        }
        let recognizer = UITapGestureRecognizer(target: handler, action: #selector(TapHandler.handleTap(_:)))
        arView.addGestureRecognizer(recognizer)
        // `UIGestureRecognizer` holds its target weakly — keep the handler
        // alive. That strong reference is a retain cycle; ``teardown()``
        // breaks it on `onDisappear`.
        tracking.tapHandler = handler
        tracking.tapRecognizer = recognizer
    }

    /// Closure-carrying `@objc` target for the tap recogniser. UIKit always
    /// delivers gesture callbacks on the main thread, hence `@MainActor`.
    @MainActor
    private final class TapHandler: NSObject {
        private let action: (UITapGestureRecognizer) -> Void

        init(action: @escaping (UITapGestureRecognizer) -> Void) {
            self.action = action
            super.init()
        }

        @objc func handleTap(_ recognizer: UITapGestureRecognizer) {
            action(recognizer)
        }
    }

    private func placeTV(at screenPoint: CGPoint, in arView: ARView) {
        // Vertical-plane geometry only — the ARKit analogue of Android's
        // "vertical plane, hit pose inside the polygon" acceptance rule.
        let hits = arView.raycast(from: screenPoint, allowing: .existingPlaneGeometry, alignment: .vertical)
        guard let hit = hits.first, let planeAnchor = hit.anchor as? ARPlaneAnchor else {
            lastError = "Tap a wall — no vertical surface under that point yet."
            return
        }
        guard Self.isWallPlane(planeAnchor) else {
            lastError = "ARKit does not classify that surface as a wall."
            return
        }
        guard let frame = arView.session.currentFrame else {
            lastError = "Tracking is not ready yet — move the device slowly."
            return
        }
        guard case .normal = frame.camera.trackingState else {
            lastError = "Tracking is not ready yet — move the device slowly."
            return
        }

        let hitColumn = hit.worldTransform.columns.3
        let hitPosition = SIMD3<Float>(hitColumn.x, hitColumn.y, hitColumn.z)
        let cameraColumn = frame.camera.transform.columns.3
        let cameraPosition = SIMD3<Float>(cameraColumn.x, cameraColumn.y, cameraColumn.z)

        guard simd_distance(cameraPosition, hitPosition) <= Self.maxWallPlacementDistance else {
            lastError = "Too far from the wall — step closer and tap again."
            return
        }

        // A vertical plane anchor's local +Y is its normal; ARKit does not
        // guarantee its sign, so flip it toward the camera (into the room).
        let rawNormal = planeAnchor.transform.columns.1
        let normal = Self.roomFacingNormal(
            simd_normalize(SIMD3<Float>(rawNormal.x, rawNormal.y, rawNormal.z)),
            towardViewer: cameraPosition - hitPosition
        )

        // Floor-relative height when a floor is known; otherwise seat at the
        // raw hit height so placement still works before the floor converges
        // (the same fallback the Android scene takes).
        let floorY = tracking.floorY ?? (hitPosition.y - Self.mountHeight)
        let transform = Self.wallAnchorTransform(
            wallHit: hitPosition,
            wallNormal: normal,
            floorY: floorY,
            mountHeight: Self.mountHeight
        )

        // A real ARKit anchor rather than a bare world transform, so the panel
        // rides ARKit's drift corrections as the session refines the room.
        let arAnchor = ARAnchor(name: "wall-placement-tv", transform: transform)
        arView.session.add(anchor: arAnchor)
        let anchorEntity = AnchorEntity(anchor: arAnchor)

        let adjustRoot = Entity()
        adjustRoot.addChild(Self.makeTV())
        anchorEntity.addChild(adjustRoot)
        arView.scene.addAnchor(anchorEntity)

        tracking.latestAdjustRoot = adjustRoot
        tracking.placedAnchors.append((arAnchor: arAnchor, entity: anchorEntity))
        // Each placed TV owns its adjustment — it lives on that TV's own
        // entity transform, so an earlier TV keeps its tweak when a new one
        // lands (a single shared offset would drag every earlier TV along).
        adjustment = Adjustment()
        placedCount += 1
        phase = .placed
        lastError = nil
        SceneViewHaptic.shared.light()
    }

    /// Removes every placed TV and rewinds the flow to whichever phase the
    /// live tracking state justifies.
    private func clearPlaced() {
        if let arView = tracking.arView {
            for placed in tracking.placedAnchors {
                arView.scene.removeAnchor(placed.entity)
                arView.session.remove(anchor: placed.arAnchor)
            }
        }
        tracking.placedAnchors.removeAll()
        tracking.latestAdjustRoot = nil
        adjustment = Adjustment()
        placedCount = 0
        phase = Self.nextPhase(
            floorFound: tracking.floorY != nil,
            wallFound: tracking.wallFound,
            placed: false
        )
        SceneViewHaptic.shared.medium()
    }
    #endif

    // MARK: - Procedural TV

    /// The procedural 55" TV — two boxes, matte body + glossy screen, exactly
    /// as the Android demo builds it from two `CubeNode`s. No bundled asset,
    /// so the demo stays deterministic and works offline.
    private static func makeTV() -> Entity {
        var bodyMaterial = PhysicallyBasedMaterial()
        bodyMaterial.baseColor = .init(tint: UIColor(red: 0.125, green: 0.141, blue: 0.165, alpha: 1))
        bodyMaterial.roughness = 0.8
        bodyMaterial.metallic = 0.0

        var screenMaterial = PhysicallyBasedMaterial()
        screenMaterial.baseColor = .init(tint: UIColor(red: 0.024, green: 0.031, blue: 0.047, alpha: 1))
        screenMaterial.roughness = 0.15
        screenMaterial.metallic = 0.0

        // Body slightly proud of the wall, screen on its front face — the same
        // dimensions and +Z offsets as the Android `CubeNode`s. +Z is the room
        // side because `wallYaw` aims local +Z along the room-facing normal.
        let body = ModelEntity(
            mesh: .generateBox(width: 1.26, height: 0.74, depth: 0.04),
            materials: [bodyMaterial]
        )
        body.position = SIMD3<Float>(0, 0, 0.02)

        let screen = ModelEntity(
            mesh: .generateBox(width: 1.20, height: 0.68, depth: 0.01),
            materials: [screenMaterial]
        )
        screen.position = SIMD3<Float>(0, 0, 0.045)

        let root = Entity()
        root.name = "wall-placement-tv-root"
        root.addChild(body)
        root.addChild(screen)
        return root
    }

    // MARK: - Phase banner

    private var phaseBanner: some View {
        Text(phase.coachingText)
            .font(.callout)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .padding(16)
            .animation(.easeInOut(duration: 0.2), value: phase)
    }

    private func errorBanner(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .background(Color.red.opacity(0.85), in: Capsule())
            .foregroundStyle(.white)
            .padding(.horizontal, 24)
    }

    // MARK: - Orange alignment guide

    /// Fixed screen-space line the user physically aligns with the floor↔wall
    /// seam (the Amazon "orange line" from the #2740 teardown).
    ///
    /// The explicit `.frame(height:)` is load-bearing — see
    /// ``guideLineHeight``. A zero-height drawing container renders nothing at
    /// all, and nothing about that failure surfaces in a build.
    private var alignmentGuide: some View {
        Canvas { context, size in
            let midY = size.height / 2
            var path = Path()
            path.move(to: CGPoint(x: 0, y: midY))
            path.addLine(to: CGPoint(x: size.width, y: midY))
            context.stroke(
                path,
                with: .color(Self.guideColor),
                style: StrokeStyle(lineWidth: 6, lineCap: .round)
            )
        }
        .frame(height: Self.guideLineHeight)
        .padding(.horizontal, 24)
        .allowsHitTesting(false)
    }

    // MARK: - D-pad fine-adjust

    /// 2 cm nudges along the wall / 2° yaw steps — the "precise" half of
    /// #2740's dual manipulation model. Drives the most recently placed TV.
    private var dPad: some View {
        VStack(spacing: 6) {
            dPadButton("chevron.up", label: "Nudge up") { nudge(dx: 0, dy: Self.nudgeStep) }
            HStack(spacing: 6) {
                dPadButton("arrow.counterclockwise", label: "Rotate left") { rotate(by: Self.yawStep) }
                dPadButton("chevron.left", label: "Nudge left") { nudge(dx: -Self.nudgeStep, dy: 0) }
                dPadButton("chevron.right", label: "Nudge right") { nudge(dx: Self.nudgeStep, dy: 0) }
                dPadButton("arrow.clockwise", label: "Rotate right") { rotate(by: -Self.yawStep) }
            }
            dPadButton("chevron.down", label: "Nudge down") { nudge(dx: 0, dy: -Self.nudgeStep) }
        }
        .padding(12)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .padding(.bottom, 24)
    }

    private func dPadButton(
        _ systemImage: String,
        label: String,
        action: @escaping () -> Void
    ) -> some View {
        Button {
            action()
            SceneViewHaptic.shared.selection()
        } label: {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .semibold))
                .frame(width: 44, height: 44)
                .background(Color.primary.opacity(0.12), in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func nudge(dx: Float, dy: Float) {
        adjustment.offset += SIMD2<Float>(dx, dy)
        applyAdjustment()
    }

    private func rotate(by degrees: Float) {
        adjustment.yawDegrees += degrees
        applyAdjustment()
    }

    /// Writes the current adjustment onto the latest TV's adjust root. That
    /// root is a child of the wall anchor, so a local +X translation slides
    /// the panel *along the wall* and +Y raises it — the anchor-local frame
    /// the Android demo works in.
    private func applyAdjustment() {
        tracking.latestAdjustRoot?.transform = adjustment.transform
    }

    // MARK: - Settings sheet

    @ViewBuilder
    private var controlsSheet: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Scan the floor, then aim at a wall. Align the orange line with the floor↔wall edge and tap the wall to mount a TV \(String(format: "%.2f", Self.mountHeight)) m above the floor.")
                .font(.caption)
                .foregroundStyle(.secondary)

            Label(
                "ARKit classifies wall planes natively (ARPlaneAnchor.classification == .wall). On a device that reports no classification, this demo falls back to plane alignment — the rule the Android original uses.",
                systemImage: "sparkles"
            )
            .font(.caption2)
            .foregroundStyle(.secondary)

            Label(
                "Not mirrored from Android: the procedural contact shadow on the wall. RealityKit's grounding shadows only project downward onto a surface below an entity, and SceneViewSwift has no ContactShadow equivalent yet — so the panel has no shadow pool behind it here.",
                systemImage: "exclamationmark.triangle"
            )
            .font(.caption2)
            .foregroundStyle(.orange)

            #if !targetEnvironment(simulator)
            Button(role: .destructive) {
                clearPlaced()
            } label: {
                Label("Clear placed TVs", systemImage: "trash")
                    .font(.subheadline.weight(.semibold))
            }
            .disabled(placedCount == 0)
            #endif
        }
    }

    // MARK: - Simulator placeholder

    private var simulatorPlaceholder: some View {
        VStack(spacing: 16) {
            Image(systemName: "tv.fill")
                .font(.system(size: 56))
                .foregroundStyle(.secondary)
            Text("Wall Placement")
                .font(.headline)
            Text("Wall detection needs ARKit world tracking, which does not run on a Simulator. Run on an iPhone or iPad to scan a floor, find a wall, and mount the TV.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
    }
}

#endif

// @sceneId     ar-depth-collider
// @title       Depth Collider
// @subtitle    Virtual balls bounce off the real floor / table (depth-driven physics)
// @category    ar
// @available   true
// @icon        circle.grid.cross.fill
// @iosOnly     true
// @status      knownIssue
// @order       26
// @tags        ar,depth,physics,collision,rigid-body
import SwiftUI

enum ArDepthColliderScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARDepthColliderDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}

#if os(iOS)
import RealityKit
import ARKit
import SceneViewSwift

/// iOS port of Android's `ar-depth-collider` demo (#2838 — visual acceptance for #1713).
///
/// Drops small bouncy balls (5 cm radius spheres, SceneView brand blue — Android's
/// `SceneViewColors.Ramp4[0]` / `#005BC1`, see DESIGN.md) roughly 50 cm in front of, and
/// slightly above, the **live** camera pose so they always fall into view, then lets
/// gravity + RealityKit's physics engine take over. Each ball collides with the REAL
/// floor / table / wall via ARKit scene reconstruction (LiDAR) instead of a static plane
/// at the scene origin — the RealityKit analogue of Android's `DepthCollider`.
///
/// ### The #2838 correction this demo mirrors
///
/// An earlier draft of #2838 said iOS should show an "unavailable" state when depth is
/// missing. That was wrong: Android's own demo never gates itself off — when the depth
/// subsystem can't run, it silently falls back to a static floor (`floorY = -1f`) so the
/// demo still shows *a* bounce instead of a black void. This port mirrors that fallback
/// in two situations, neither of which blocks the Drop / Drop 5 / Reset controls:
///
///   - **Real device, no LiDAR** (or LiDAR present but somehow pre-iOS 17 — moot at this
///     target's iOS 18.0 deployment floor, kept as an honest defensive check anyway since
///     `SceneReconstructionNode.enablePhysics` itself is iOS 17+ only):
///     ``addFallbackFloor(in:)`` adds one static, collidable, 20 x 20 m plane 1 m below
///     the AR session origin (mirrors Android's `floorY = -1f` exactly) so the ball still
///     visibly bounces on the live camera feed. Critically, this plane and every dropped
///     ball are parented under the SAME shared ``simRoot`` anchor — RealityKit gives each
///     `AnchorEntity` hierarchy its own physics simulation, so entities under two
///     different anchors can never collide no matter how their world positions overlap.
///   - **Simulator**: `ARWorldTrackingConfiguration` cannot run at all (no camera), so
///     the screen is the shared ``ARUnavailableStage`` — the same answer every other AR
///     demo gives. A static floor inside the studio skybox used to stand in here; it
///     read as a room photo pretending to be AR, which this app never does.
///
/// ### Honest-subset notes vs Android
///
/// - Android builds a manual `DepthCollider` from a `DepthMeshNode` and republishes a
///   per-frame "bodies region" so the collider only rebuilds within a padded AABB around
///   the live balls (#1810/#1842 — a hand-rolled performance optimization). RealityKit
///   needs none of this: enabling `SceneUnderstanding.Options.physics` on the AR session
///   hands the ENTIRE reconstructed mesh to RealityKit's own physics engine as static
///   collision geometry, so any dynamic `PhysicsBodyComponent` entity collides with it
///   automatically — simpler on iOS, at the cost of not having Android's fine-grained
///   region-culling knob.
/// - Android's "Show depth mesh (dev)" toggle flips a custom `DepthMeshNode`'s visibility
///   on a Filament mesh. iOS has no equivalent custom mesh to toggle — the nearest honest
///   parallel is RealityKit's own built-in `ARView.debugOptions.showSceneUnderstanding`
///   wireframe (the same debug option `ArSceneMeshScene` already uses), wired to an
///   equivalent toggle label. It is only shown when LiDAR is actually active — toggling it
///   with nothing to visualize would be a dead control.
/// - The fallback floor is intentionally given a faint translucent tint (12% white, the
///   same recipe `ARSceneView`'s own detected-plane overlay uses) instead of Android's
///   fully invisible `floorY` check, purely so a non-LiDAR user has SOME visual grounding
///   for where the invisible floor sits. A deliberate small improvement, not a hidden
///   capability.
/// - Camera-pose capture: Android caches the latest `Pose` every frame via
///   `onSessionUpdated` so a Drop tap always uses a fresh pose. iOS reads
///   `ARSession.currentFrame` directly at tap time instead (a synchronous, always-fresh
///   pull) — same guarantee, no per-frame `@State` churn 60x/sec.
///
/// `@status knownIssue`: Android's own `ar-depth-collider` is `KnownIssue` — its own KDoc
/// says real-hardware visual QA is still the merge gate. This port has the same
/// characteristic: it compiles, and the static-floor fallback path is genuinely
/// exercisable in the Simulator, but the real LiDAR-mesh collision path has NOT been
/// verified against physical LiDAR hardware (no such device is available in this
/// environment, #2838). `knownIssue` is the honest label here, not `working`.
struct ARDepthColliderDemo: View {
    // MARK: - Shared state

    /// Read by the status pill and the Reset button's enabled state on BOTH platform
    /// branches, so it is declared unconditionally.
    @State private var droppedCount = 0

    private static let ballColor = UIColor(red: 0x00 / 255.0, green: 0x5B / 255.0, blue: 0xC1 / 255.0, alpha: 1.0)
    private static let ballRadius: Float = 0.05
    private static let restitution: Float = 0.7
    /// Mirrors Android's `PhysicsNode(floorY = -1f)` fallback value exactly.
    private static let fallbackFloorY: Float = -1

    // MARK: - Device-path state (AR session, LiDAR gate, dropped-ball bookkeeping)

    #if !targetEnvironment(simulator)
    @State private var capturedARView: ARView?
    @State private var isLiDARSupported = false
    @State private var isDepthPhysicsActive = false
    @State private var fallbackFloorAdded = false
    @State private var showDepthMesh = false
    /// The SINGLE shared RealityKit physics-simulation root every dropped ball AND the
    /// fallback floor are parented under. Created once in `onSessionStarted`. This is not
    /// an optional nicety — RealityKit treats each `AnchorEntity` hierarchy as its own
    /// physics simulation island, so two entities under two DIFFERENT anchors can never
    /// collide with each other no matter how their world positions overlap. An earlier
    /// revision of this file gave the fallback floor and each ball their own
    /// `AnchorEntity(world:)`, which meant a dropped ball fell straight through the
    /// "floor" on any non-LiDAR device — caught in review before merge. Mirrors
    /// `PhysicsDemo.swift`'s single `root` parameter, which does the same thing for its
    /// non-AR scene.
    @State private var simRoot: AnchorEntity?
    /// Dropped-ball entities, so `resetBalls()` can detach exactly the balls it added
    /// (not the floor) via `removeFromParent()`.
    @State private var ballEntities: [Entity] = []
    #endif

    var body: some View {
        #if !targetEnvironment(simulator)
        arSceneView
            .demoChrome(
                dock: [
                    DockItem(icon: "circle.fill", label: "Drop") { dropBalls(1) },
                    DockItem(icon: "circle.grid.3x3.fill", label: "Drop 5") { dropBalls(5) },
                    DockItem(icon: "arrow.counterclockwise", label: "Reset",
                             enabled: droppedCount > 0) { resetBalls() },
                ],
                onReset: resetBalls,
                chromeMode: .ar,
                accessory: { DemoHint(statusText) }
            ) {
                if isLiDARSupported {
                    depthMeshToggleRow
                }
            }
        #else
        // No camera, no depth, no AR session: the demo is a device demo. The
        // previous simulator branch drew a grey floor and balls inside the
        // studio skybox with a "fallback" pill — a room photo standing in for
        // AR, which is the one thing this app never does (#3766 P2 §8).
        ARUnavailableStage(
            icon: "circle.grid.cross.fill",
            message: "Run on iPhone or iPad to drop balls that bounce off the real floor — LiDAR depth when the device has it, a virtual floor otherwise."
        )
        .demoChrome(chromeMode: .ar)
        #endif
    }

    // MARK: - AR view (physical device)

    #if !targetEnvironment(simulator)
    private var arSceneView: some View {
        ARSceneView(
            planeDetection: .horizontal,
            showPlaneOverlay: false,
            showCoachingOverlay: true
        )
        .onSessionStarted { arView in
            capturedARView = arView

            // The one shared simulation root — see the `simRoot` doc comment above for
            // why every ball and the fallback floor MUST share this single anchor.
            let root = AnchorEntity(world: .zero)
            arView.scene.addAnchor(root)
            simRoot = root

            isLiDARSupported = SceneReconstructionNode.isSupported
            if isLiDARSupported {
                if #available(iOS 17.0, *) {
                    SceneReconstructionNode.enablePhysics(in: arView)
                    isDepthPhysicsActive = true
                }
            }
            if !isDepthPhysicsActive {
                addFallbackFloor(in: root)
            }
        }
    }

    private func dropBalls(_ count: Int) {
        guard let arView = capturedARView,
              let root = simRoot,
              let cameraTransform = arView.session.currentFrame?.camera.transform else { return }
        for _ in 0..<count {
            let i = droppedCount
            droppedCount += 1

            // Same per-index offsets, and the same two-step forward/world-vertical split,
            // as Android's ARDepthColliderDemo.kt (#1874 / #2466): project ONLY the forward
            // offset through the camera pose, then add the horizontal scatter and drop
            // height in WORLD space — so aiming at the floor doesn't rotate the drop height
            // into a screen corner.
            let xOffset = Float(i % 5 - 2) * 0.05
            let zOffset = -0.5 - Float(i / 5) * 0.05
            let startY: Float = 0.5 + Float(i / 5) * 0.05

            let ahead = worldPoint(from: cameraTransform, localOffset: SIMD3<Float>(0, 0, zOffset))
            let position = SIMD3<Float>(ahead.x + xOffset, ahead.y + startY, ahead.z)

            let ball = GeometryNode.sphere(
                radius: Self.ballRadius,
                material: .pbr(color: Self.ballColor, metallic: 0.3, roughness: 0.35)
            )
            // `root` is anchored at world `.zero` with identity rotation, so a LOCAL
            // position under it is numerically identical to the WORLD position computed
            // above — no transform correction needed. Parenting under the SAME `root` the
            // fallback floor uses (see `addFallbackFloor`) is what puts them in one shared
            // physics simulation instead of two islands that can never collide.
            ball.entity.position = position
            PhysicsNode.dynamic(ball.entity, restitution: Self.restitution)

            root.addChild(ball.entity)
            ballEntities.append(ball.entity)
        }
    }

    private func resetBalls() {
        for entity in ballEntities {
            entity.removeFromParent()
        }
        ballEntities.removeAll()
        droppedCount = 0
    }

    /// Projects a camera-local offset into world space via the live `ARCamera.transform` —
    /// the RealityKit/simd equivalent of Android's `Pose.transformPoint(FloatArray)`.
    private func worldPoint(from cameraTransform: simd_float4x4, localOffset: SIMD3<Float>) -> SIMD3<Float> {
        let world4 = cameraTransform * SIMD4<Float>(localOffset, 1)
        return SIMD3<Float>(world4.x, world4.y, world4.z)
    }

    /// Android `floorY = -1f` fallback, mirrored (#2838 correction) — see the struct-level
    /// doc comment for the full rationale. Adds ONE static, collidable, faintly-tinted
    /// plane 1 m below the AR session origin, parented under the SAME shared `simRoot`
    /// anchor `dropBalls` attaches balls to — required so they land in one RealityKit
    /// physics simulation instead of two separate ones that can never collide (see the
    /// `simRoot` doc comment).
    ///
    /// Sized 20 x 20 m — not Android's zero-extent analytic half-space, and much larger
    /// than "just cover the spawn point" would need. A user who walks several metres from
    /// the AR session's origin before tapping Drop still lands on this floor. Re-centring
    /// a small plane under the live drop point on every tap was rejected instead: moving a
    /// `static` physics body while other bodies may already be resting or colliding on it
    /// risks a visible pop in RealityKit's physics engine, and a flat plane's render +
    /// collision cost is the same 2 triangles regardless of its width/depth — so "just
    /// make it big" has no real downside here.
    private func addFallbackFloor(in root: AnchorEntity) {
        guard !fallbackFloorAdded else { return }
        fallbackFloorAdded = true

        let mesh = MeshResource.generatePlane(width: 20, depth: 20)
        var material = UnlitMaterial(color: .init(white: 1.0, alpha: 0.12))
        material.blending = .transparent(opacity: .init(floatLiteral: 0.12))
        let floorEntity = ModelEntity(mesh: mesh, materials: [material])
        floorEntity.generateCollisionShapes(recursive: false)
        floorEntity.position = SIMD3<Float>(0, Self.fallbackFloorY, 0)
        PhysicsNode.static(floorEntity, restitution: Self.restitution)

        root.addChild(floorEntity)
    }
    #endif

    #if !targetEnvironment(simulator)
    // MARK: - Status line

    private var statusText: String {
        let surface = isDepthPhysicsActive ? "real depth (LiDAR)" : "a virtual floor (no LiDAR on this device)"
        return droppedCount == 0
            ? "Aim at the floor or a table, then Drop — bounces off \(surface)"
            : "\(droppedCount) ball\(droppedCount == 1 ? "" : "s") · bounces off \(surface)"
    }
    #endif

    #if !targetEnvironment(simulator)
    private var depthMeshToggleRow: some View {
        Toggle("Show depth mesh (dev)", isOn: $showDepthMesh)
            .font(.subheadline)
            .onChange(of: showDepthMesh) { _, enabled in
                guard let arView = capturedARView else { return }
                if enabled {
                    arView.debugOptions.insert(.showSceneUnderstanding)
                } else {
                    arView.debugOptions.remove(.showSceneUnderstanding)
                }
            }
    }
    #endif
}
#endif // os(iOS)

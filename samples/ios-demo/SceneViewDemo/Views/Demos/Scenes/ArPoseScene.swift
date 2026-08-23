// @sceneId     ar-pose
// @title       Pose Placement
// @subtitle    Free pose positioning
// @category    ar
// @available   true
// @status      working
// @icon        move.3d
// @iosOnly     true
// @order       36
// @tags        ar,pose,transform,gesture,anchor
import SwiftUI

/// Free pose placement — mirrors Android's `ARPoseDemo`
/// (`samples/android-demo/.../demos/ARPoseDemo.kt`, issue #2837).
///
/// ### Single-file structure (deliberate)
///
/// Every other working demo in this catalog splits into a thin
/// `Scenes/<Name>Scene.swift` wrapper plus a `Views/Demos/<Name>Demo.swift`
/// implementation. This port keeps the whole implementation in the Scene file
/// instead: `SceneViewDemo.xcodeproj` still registers Swift sources as
/// hand-written `PBXFileReference`/`PBXBuildFile` entries rather than a
/// file-system-synchronized group, so a brand-new file needs a
/// `project.pbxproj` edit to even compile. This port is scoped to touching
/// only the Scene file + `parity-manifest.yml` + a changelog fragment, and
/// `ArPoseScene.swift` already has its own pbxproj entry — so the demo body
/// lives here rather than adding a file the build wouldn't pick up.
enum ArPoseScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARPoseDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}

#if os(iOS)
import RealityKit
import ARKit
import SceneViewSwift

/// ### What this mirrors — and what it deliberately does NOT
///
/// Android's current `ar-pose` demo is **not** a raycast-and-drag demo: it
/// places a bundled lantern model at a fixed base pose (1 m in front of the
/// camera, captured once when ARCore first reports `TrackingState.TRACKING`)
/// and lets the user nudge it with three X/Y/Z sliders. A red/green/blue axes
/// gizmo marks the base pose so the slider offsets read as a coordinate
/// system. There is no plane-tap placement and no drag/rotate gesture in
/// `ARPoseDemo.kt` today — this view reproduces that actual UX rather than
/// the "raycast + drag/rotate" framing in the original issue text, which does
/// not match the current Android source (see the PR description).
///
/// - Base pose: computed once, on the first tracked `ARFrame`, as 1 m along
///   the camera's forward vector — mirrors Android's
///   `cameraPose.transformPoint(floatArrayOf(0f, 0f, -1f))`.
/// - Axes gizmo: `LineNode.axisGizmo` (the RealityKit mirror of Android's
///   `Axes3DNode`) anchored at the base pose with the same `length: 0.4`,
///   `thickness: 0.005` Android uses, unaffected by the sliders.
/// - Lantern: the same bundled `khronos_lantern` asset (USDZ here, GLB on
///   Android), `scaleToUnits(0.3)` to match, positioned at the base pose plus
///   the live slider offsets.
/// - Coordinate readout: a top-center pill showing the live X/Y/Z offsets,
///   mirroring Android's screen-anchored `Surface` pill (#2727/#2748).
///
/// ### Honest platform gaps
///
/// - **Simulator**: ARKit world tracking does not run on the iOS Simulator,
///   so this shows the same "AR requires a physical device" placeholder every
///   other AR demo in this catalog uses (`ARPlacementDemo`, `ARPlaneNodeDemo`,
///   …) instead of silently rendering nothing. This build was only verified
///   to compile and launch on the Simulator — the AR interaction itself is
///   unverified on this host (see PR description).
/// - **Camera warm-up scrim**: Android covers the ~1-3 s ARCore warm-up with a
///   manual `ARCameraInitScrim` (#2484). No iOS AR demo in this codebase
///   reimplements that overlay — ARKit's own `showCoachingOverlay` already
///   covers the view with system guidance until tracking is usable, so it is
///   enabled here instead of inventing a bespoke overlay.
private struct ARPoseDemo: View {
    // MARK: - AR state

    /// `true` once ARKit reports `.normal` tracking — the closest iOS
    /// analogue of ARCore's `TrackingState.TRACKING` (Android gates on
    /// `frame.camera.trackingState == TrackingState.TRACKING`). ARKit's
    /// `.limited`/`.notAvailable` both map to "not yet usable", matching
    /// ARCore's non-`TRACKING` states.
    @State private var isTracking = false

    /// World-space base pose — 1 m in front of the camera at the moment
    /// tracking first became usable. Captured once and never recomputed, so
    /// the lantern stays anchored in world space across any later tracking
    /// blips — mirrors Android's `basePose` (`remember { mutableStateOf<Pose?>(null) }`).
    @State private var basePosition: SIMD3<Float>?

    /// The RealityKit anchor hosting the axes gizmo + lantern, created once
    /// `basePosition` is known. `poseAnchor == nil` doubles as the "not yet
    /// placed" guard in `onFrame` below.
    @State private var poseAnchor: AnchorEntity?

    /// Loaded lantern model — loaded independently of AR tracking state,
    /// mirroring Android's `rememberModelInstance`, which also loads up front
    /// and is only *rendered* once tracking + base pose are both ready.
    @State private var lanternModel: ModelNode?

    /// Live handle to the lantern's entity so slider drags mutate its
    /// position directly instead of forcing a view rebuild — same direct-
    /// mutation pattern as `MovableLightDemo`/`GestureEditingDemo`.
    @State private var lanternEntity: Entity?

    // MARK: - Slider offsets — same ranges as Android's ARPoseDemo.kt

    @State private var x: Float = 0
    @State private var y: Float = 0
    @State private var z: Float = 0

    var body: some View {
        ZStack {
            #if !targetEnvironment(simulator)
            arScene
                .ignoresSafeArea()
            #else
            simulatorPlaceholder
            #endif

            if isTracking, basePosition != nil {
                coordinatePill
                    .padding(.top, 8)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }
        }
        .background(Color.black)
        .demoChrome { controlsSheet }
        .task { await loadLantern() }
    }

    // MARK: - AR scene

    #if !targetEnvironment(simulator)
    private var arScene: some View {
        ARSceneView(
            planeDetection: .both,
            showPlaneOverlay: true,
            showCoachingOverlay: true
        )
        .onFrame { frame, arView in
            let tracking = frame.camera.trackingState == .normal
            if tracking != isTracking { isTracking = tracking }
            // Mirrors Android's `PoseNode(visibleCameraTrackingStates = {TRACKING})`
            // default — the placed content hides while tracking is lost and
            // reappears (at the SAME base pose) once it resumes.
            poseAnchor?.isEnabled = tracking

            guard tracking, poseAnchor == nil else { return }

            // 1 m along the camera's forward vector (its local -Z axis,
            // rotated into world space by the camera's current orientation) —
            // mirrors ARCore's `cameraPose.transformPoint(floatArrayOf(0f, 0f, -1f))`.
            // Uses the camera's FULL rotation (not yaw-only), matching what
            // Android's code actually computes.
            let transform = frame.camera.transform
            let forward = -SIMD3<Float>(transform.columns.2.x, transform.columns.2.y, transform.columns.2.z)
            let cameraPosition = SIMD3<Float>(transform.columns.3.x, transform.columns.3.y, transform.columns.3.z)
            let base = cameraPosition + forward
            basePosition = base

            let anchorNode = AnchorNode.world(position: base)
            for line in LineNode.axisGizmo(at: .zero, length: 0.4, thickness: 0.005) {
                anchorNode.add(line.entity)
            }
            arView.scene.addAnchor(anchorNode.entity)
            poseAnchor = anchorNode.entity

            attachLanternIfReady()
        }
    }
    #endif

    // MARK: - Model loading (independent of AR tracking, mirrors Android)

    @MainActor
    private func loadLantern() async {
        lanternModel = try? await ModelNode.load("khronos_lantern")
        attachLanternIfReady()
    }

    /// Attaches the lantern as a child of the pose anchor the first time both
    /// the model has finished loading and the base pose has been captured —
    /// whichever happens second. Mirrors Android's `lanternInstance?.let { … }`
    /// guard inside the `isTracking && base != null` branch.
    @MainActor
    private func attachLanternIfReady() {
        guard lanternEntity == nil, let anchor = poseAnchor, let model = lanternModel else { return }
        _ = model.scaleToUnits(0.3)
        model.entity.position = SIMD3<Float>(x, y, z)
        anchor.addChild(model.entity)
        lanternEntity = model.entity
    }

    // MARK: - Controls sheet

    @ViewBuilder
    private var controlsSheet: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Position Controls")
                .font(.subheadline.weight(.semibold))

            offsetSlider(label: "X", value: $x, range: -1...1)
            offsetSlider(label: "Y", value: $y, range: -0.5...0.5)
            offsetSlider(label: "Z", value: $z, range: -0.5...0.5)
        }
    }

    private func offsetSlider(label: String, value: Binding<Float>, range: ClosedRange<Float>) -> some View {
        LabeledSlider(label: label, value: value, range: range)
            .onChange(of: value.wrappedValue) { _, _ in
                lanternEntity?.position = SIMD3<Float>(x, y, z)
            }
    }

    // MARK: - Overlays

    private var coordinatePill: some View {
        Text(String(format: "X %.2f   Y %.2f   Z %.2f", x, y, z))
            .font(.system(.body, design: .monospaced, weight: .semibold))
            .foregroundColor(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Color(red: 0x16 / 255.0, green: 0x1B / 255.0, blue: 0x22 / 255.0).opacity(0.8))
            .clipShape(Capsule())
    }

    private var simulatorPlaceholder: some View {
        VStack(spacing: 16) {
            Image(systemName: "move.3d")
                .font(.system(size: 56))
                .foregroundStyle(.secondary)
            Text("Pose Placement")
                .font(.headline)
            Text("Free pose placement requires a physical device with a rear camera.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
#endif

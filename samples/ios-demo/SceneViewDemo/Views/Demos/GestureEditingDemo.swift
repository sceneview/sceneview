import SwiftUI
import RealityKit
import SceneViewSwift

/// Demonstrates per-entity gesture editing of a 3D model.
///
/// Mirrors SceneView Android's `GestureEditingDemo`.
///
/// In **Edit Mode** the camera is locked and the user can:
/// - **Drag** (one finger) — move the model in the XZ ground plane
/// - **Pinch** — scale the model up or down
/// - **Rotate** (two-finger twist) — spin the model around its Y axis
///
/// In **View Mode** the camera orbits freely (standard `.orbit` mode).
///
/// Toggles between modes via a gear-sheet control. A **Reset** button restores
/// the model to its original position / rotation / scale.
struct GestureEditingDemo: View {

    // MARK: - State

    @State private var loadedModel: ModelNode?
    @State private var isLoading = true
    @State private var loadError: String?

    /// Live reference to the model's RealityKit entity — updated each scene build
    /// so gesture handlers can mutate the entity directly without rebuilding the scene.
    @State private var modelEntityRef: Entity?

    /// When `true` camera is locked and gestures move/scale/rotate the model.
    /// When `false` camera orbits freely.
    @State private var isEditable = true

    /// Height of the floor the car stands on. The model is bottom-aligned at
    /// load (Android's `centerOrigin = Position(y = -1f)`), so a pivot at this
    /// height puts the wheels on the floor at any scale, as on Android.
    private static let floorY: Float = -0.45

    // Transform state (mirrors the pivot's transform; preserved across scene rebuilds)
    @State private var modelPosition = SIMD3<Float>(0, Self.floorY, -2)
    @State private var modelScale: Float = 0.6
    @State private var modelRotationY: Float = 0

    // Gesture tracking
    @State private var lastDragTranslation: CGSize = .zero
    @State private var pinchBaseScale: Float = 0.6
    @State private var isPinching = false
    @State private var lastRotationAngle: Double = 0
    @State private var isRotating = false

    // MARK: - Body

    var body: some View {
        sceneWithOverlays
            // The hint is the scaffold's accessory — a glass pill above the
            // dock, in the same glass group — not a stage overlay: pinned to
            // the stage's top edge it sat under the Dynamic Island.
            .demoChrome(accessory: {
                DemoHint(isEditable ? "Drag model to move · Pinch to resize · Twist to rotate"
                                    : "Orbit mode — open Settings to edit")
            }) { settingsSheet }
    }

    // MARK: - Scene

    @ViewBuilder
    private var sceneWithOverlays: some View {
        ZStack {
            sceneView

            if isEditable {
                gestureOverlay
            }

            loadingOverlay
        }
        .background(Color.black)
        .task { await loadModel() }
    }

    private var sceneView: some View {
        SceneView { root in
            if let model = loadedModel {
                // The gestures move, scale and turn a pivot on the floor; the
                // model under it keeps its bottom-aligned offset, so its wheels
                // stay on the floor whatever the pinch.
                let pivot = Entity()
                pivot.position = modelPosition
                pivot.scale = SIMD3(repeating: modelScale)
                pivot.orientation = simd_quatf(angle: modelRotationY, axis: [0, 1, 0])
                pivot.addChild(model.entity)
                root.addChild(pivot)
                // Capture entity for direct mutation by gesture overlay
                DispatchQueue.main.async { modelEntityRef = pivot }
            }

            // Ground plane for depth reference
            let floor = GeometryNode.plane(width: 6, depth: 6, color: .darkGray)
            floor.entity.position = SIMD3(0, Self.floorY, -2)
            root.addChild(floor.entity)
        }
        .cameraControls(isEditable ? .none : .orbit)
        // The loaded subject is the bundled Ferrari F40 — a PBR USDZ with
        // metallic paint — and with no IBL it has nothing to reflect while
        // the user drags/pinches/rotates it. Same `.studio` preset as
        // ModelViewerDemo (#2114).
        .environment(.studio)
        // The scene stays mounted while the model loads and the content is
        // rebuilt in place once it lands — never re-keyed with `.id(_:)`,
        // which discards the `RealityView` and intermittently leaves it black
        // on iOS 26 Simulator (#3008). `isEditable` is deliberately not part
        // of the key: the camera mode changes without a content rebuild.
        .contentID(loadedModel == nil ? nil : "loaded")
        .ignoresSafeArea()
    }

    // MARK: - Gesture overlay (edit mode only)

    private var gestureOverlay: some View {
        Color.clear
            .contentShape(Rectangle())
            .simultaneousGesture(dragGesture)
            .simultaneousGesture(pinchGesture)
            .simultaneousGesture(rotateGesture)
    }

    private var dragGesture: some Gesture {
        DragGesture(minimumDistance: 4)
            .onChanged { value in
                let dx = Float(value.translation.width - lastDragTranslation.width) * 0.003
                let dz = Float(value.translation.height - lastDragTranslation.height) * 0.003
                modelPosition.x += dx
                modelPosition.z += dz
                lastDragTranslation = value.translation
                // Direct entity mutation — no SceneView rebuild
                modelEntityRef?.position = modelPosition
            }
            .onEnded { _ in
                lastDragTranslation = .zero
            }
    }

    private var pinchGesture: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                let mag = Float(value.magnification)
                if !isPinching {
                    pinchBaseScale = modelScale
                    isPinching = true
                }
                let s = max(0.1, min(4.0, pinchBaseScale * mag))
                modelScale = s
                modelEntityRef?.scale = SIMD3(repeating: s)
            }
            .onEnded { value in
                let s = max(0.1, min(4.0, pinchBaseScale * Float(value.magnification)))
                modelScale = s
                modelEntityRef?.scale = SIMD3(repeating: s)
                isPinching = false
            }
    }

    private var rotateGesture: some Gesture {
        RotateGesture()
            .onChanged { value in
                let delta = Float(value.rotation.radians - lastRotationAngle)
                modelRotationY -= delta
                lastRotationAngle = value.rotation.radians
                modelEntityRef?.orientation = simd_quatf(angle: modelRotationY, axis: [0, 1, 0])
            }
            .onEnded { _ in
                lastRotationAngle = 0
            }
    }

    // MARK: - Overlays

    @ViewBuilder
    private var loadingOverlay: some View {
        if isLoading {
            ProgressView()
                .progressViewStyle(.circular)
                .tint(.white)
                .scaleEffect(1.4)
        }
        if let err = loadError {
            Text(err)
                .font(.caption2)
                .foregroundStyle(.white)
                .padding(8)
                .glassBackground(in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.xs, style: .continuous))
        }
    }

    // MARK: - Settings sheet

    @ViewBuilder
    private var settingsSheet: some View {
        VStack(spacing: 18) {
            Toggle(isOn: $isEditable) {
                Label("Edit Mode", systemImage: "hand.pinch")
            }
            .tint(.orange)

            HStack {
                Spacer()
                Button {
                    resetTransform()
                } label: {
                    Label("Reset", systemImage: "arrow.counterclockwise")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                Spacer()
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Scale: \(String(format: "%.2f", modelScale))×")
                Text("Rotation: \(Int(modelRotationY * 180 / .pi))°")
                Text("Position: (\(String(format: "%.2f", modelPosition.x)), \(String(format: "%.2f", modelPosition.z)))")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: - Helpers

    private func resetTransform() {
        modelPosition = SIMD3(0, Self.floorY, -2)
        modelScale = 0.6
        modelRotationY = 0
        pinchBaseScale = 0.6
        modelEntityRef?.position = modelPosition
        modelEntityRef?.scale = SIMD3(repeating: modelScale)
        modelEntityRef?.orientation = simd_quatf(angle: 0, axis: [0, 1, 0])
    }

    @MainActor
    private func loadModel() async {
        do {
            // Ferrari F40 — bundled PBR USDZ, looks great when scaled/rotated
            let node = try await ModelNode.load("ferrari_f40")
            // Bottom-aligned before it is parented: the tyres' lowest point
            // becomes the node origin, which the pivot puts on the floor.
            node.centerOrigin(normalized: [0, -1, 0])
            loadedModel = node
            isLoading = false
        } catch {
            loadError = "Model unavailable"
            isLoading = false
        }
    }
}

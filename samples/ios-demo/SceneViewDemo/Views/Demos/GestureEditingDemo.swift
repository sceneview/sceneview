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

    // Transform state (mirrors entity transform; preserved across scene rebuilds)
    @State private var modelPosition = SIMD3<Float>(0, 0, -2)
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
            .demoChrome { settingsSheet }
    }

    // MARK: - Scene

    @ViewBuilder
    private var sceneWithOverlays: some View {
        ZStack {
            sceneView

            if isEditable {
                gestureOverlay
            }

            topHint
            loadingOverlay
        }
        .background(Color.black)
        .task { await loadModel() }
    }

    private var sceneView: some View {
        SceneView { root in
            if let model = loadedModel {
                model.entity.position = modelPosition
                model.entity.scale = SIMD3(repeating: modelScale)
                model.entity.orientation = simd_quatf(angle: modelRotationY, axis: [0, 1, 0])
                root.addChild(model.entity)
                // Capture entity for direct mutation by gesture overlay
                DispatchQueue.main.async { modelEntityRef = model.entity }
            }

            // Ground plane for depth reference
            let floor = GeometryNode.plane(width: 6, depth: 6, color: .darkGray)
            floor.entity.position = SIMD3(0, -0.45, -2)
            root.addChild(floor.entity)
        }
        // .cameraControls must precede .id — .id wraps to some View which loses the modifier.
        .cameraControls(isEditable ? .none : .orbit)
        // The loaded subject is the bundled Ferrari F40 — a PBR USDZ with
        // metallic paint — and with no IBL it has nothing to reflect while
        // the user drags/pinches/rotates it. Same `.studio` preset as
        // ModelViewerDemo (#2114); must also precede .id (see above).
        .environment(.studio)
        // Don't include isEditable in the id — camera mode changes without scene rebuild.
        .id("gesture-\(loadedModel != nil)")
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

    private var topHint: some View {
        VStack {
            HStack(spacing: 6) {
                Image(systemName: isEditable ? "hand.draw.fill" : "camera.fill")
                Text(isEditable ? "Drag · Pinch · Rotate" : "Orbit mode — tap ⚙️ to edit")
                    .font(.caption)
            }
            .foregroundStyle(.white.opacity(0.8))
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial)
            .clipShape(Capsule())
            .padding(.top, 12)
            .allowsHitTesting(false)
            Spacer()
        }
    }

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
                .background(.ultraThinMaterial)
                .clipShape(RoundedRectangle(cornerRadius: 8))
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
        modelPosition = SIMD3(0, 0, -2)
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
            loadedModel = node
            isLoading = false
        } catch {
            loadError = "Model unavailable"
            isLoading = false
        }
    }
}

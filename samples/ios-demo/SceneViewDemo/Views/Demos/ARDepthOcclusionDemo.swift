#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import SceneViewSwift

/// AR Depth Occlusion demo — mirrors Android's depth occlusion functionality (#910).
///
/// Uses ARKit scene reconstruction (LiDAR) to occlude virtual objects behind real
/// world surfaces. Place a coloured floating cube and move around it — when you
/// pass behind a wall or table, the cube disappears behind the real geometry.
///
/// On LiDAR-equipped devices (iPhone 12 Pro+, iPad Pro), the real-world mesh is
/// built in realtime and used as an occlusion mask. On non-LiDAR devices, a
/// graceful "Device not supported" banner is shown.
///
/// Requires a physical iOS device — the simulator shows a placeholder.
struct ARDepthOcclusionDemo: View {
    @State private var isOcclusionEnabled: Bool = true
    @State private var isLiDARSupported: Bool = false
    @State private var placedCount: Int = 0
    @State private var capturedARView: ARView?

    var body: some View {
        ZStack {
            #if !targetEnvironment(simulator)
            arSceneView
                .ignoresSafeArea()
            if !isLiDARSupported {
                lidarUnsupportedBanner
            }
            #else
            simulatorPlaceholder
            #endif

            VStack {
                Spacer()
                if isLiDARSupported {
                    controlsPanel
                        .padding(.bottom, 28)
                }
            }
        }
        .background(Color.black)
    }

    // MARK: - AR view

    #if !targetEnvironment(simulator)
    private var arSceneView: some View {
        ARSceneView(
            planeDetection: .horizontal,
            showPlaneOverlay: true,
            showCoachingOverlay: true,
            onTapOnPlane: { position, arView in
                // Drop a small metallic cube at the tap point
                var mat = SimpleMaterial()
                mat.color = .init(tint: UIColor(
                    hue: CGFloat(placedCount % 6) / 6.0,
                    saturation: 0.85,
                    brightness: 0.9,
                    alpha: 1.0
                ))
                mat.metallic = .float(0.6)
                mat.roughness = .float(0.3)
                let cube = ModelEntity(
                    mesh: .generateBox(size: 0.08, cornerRadius: 0.01),
                    materials: [mat]
                )
                cube.position.y += 0.04   // sit just on top of the plane
                let anchor = AnchorEntity(world: position)
                anchor.addChild(cube)
                arView.scene.addAnchor(anchor)
                placedCount += 1
            }
        )
        .onSessionStarted { arView in
            capturedARView = arView
            isLiDARSupported = SceneReconstructionNode.isSupported
            if SceneReconstructionNode.isSupported && isOcclusionEnabled {
                SceneReconstructionNode.enableOcclusion(in: arView)
            }
        }
    }
    #endif

    // MARK: - Controls

    private var controlsPanel: some View {
        VStack(spacing: 10) {
            HStack {
                Image(systemName: isOcclusionEnabled ? "square.3.layers.3d.down.right" : "square.3.layers.3d")
                    .foregroundStyle(.white)
                Toggle("Depth Occlusion", isOn: $isOcclusionEnabled)
                    .labelsHidden()
                    .onChange(of: isOcclusionEnabled) { _, enabled in
                        if let arView = capturedARView {
                            if enabled {
                                SceneReconstructionNode.enableOcclusion(in: arView)
                            } else {
                                // Disable by removing the option
                                if #available(iOS 17.0, *) {
                                    arView.environment.sceneUnderstanding.options.remove(.occlusion)
                                }
                            }
                        }
                    }
                Text("Depth Occlusion")
                    .font(.caption)
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 12))

            Text("Tap a plane to place cubes — LiDAR hides them behind real geometry")
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.7))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
        }
        .padding(.horizontal, 20)
    }

    private var lidarUnsupportedBanner: some View {
        VStack {
            HStack {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.yellow)
                Text("LiDAR not available on this device")
                    .font(.caption)
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(.black.opacity(0.7))
            .clipShape(Capsule())
            .padding(.top, 60)
            Spacer()
        }
    }

    // MARK: - Simulator placeholder

    private var simulatorPlaceholder: some View {
        VStack(spacing: 16) {
            Image(systemName: "square.3.layers.3d.down.right")
                .font(.system(size: 60))
                .foregroundStyle(.secondary)
            Text("AR requires a physical device")
                .font(.headline)
            Text("LiDAR depth occlusion requires a real camera feed.\nRun on iPhone 12 Pro+ or LiDAR iPad to try this demo.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
    }
}

#endif // os(iOS)

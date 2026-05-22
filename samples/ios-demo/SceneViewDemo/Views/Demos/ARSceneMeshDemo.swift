#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import SceneViewSwift

/// AR Scene Mesh demo — LiDAR-powered real-time 3D mesh of the environment (#910).
///
/// Uses `ARWorldTrackingConfiguration.sceneReconstruction = .meshWithClassification` to
/// build a polygonal mesh of the physical world in real time. A toggle switches between
/// the wireframe debug overlay (so you can see the mesh) and a clean AR view. Mirrors the
/// Android `ar-scene-mesh` demo.
///
/// Requires a LiDAR-equipped device (iPhone 12 Pro+ or LiDAR iPad Pro).
struct ARSceneMeshDemo: View {
    @State private var showMeshDebug = true
    @State private var isLiDARSupported = false
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
            showCoachingOverlay: true
        )
        .onSessionStarted { arView in
            capturedARView = arView
            isLiDARSupported = ARWorldTrackingConfiguration.supportsSceneReconstruction(.meshWithClassification)
            guard isLiDARSupported else { return }

            // Restart session with mesh reconstruction enabled.
            let config = ARWorldTrackingConfiguration()
            config.sceneReconstruction = .meshWithClassification
            config.planeDetection = [.horizontal, .vertical]
            arView.session.run(config, options: [])

            // Enable debug wireframe to visualise the mesh.
            arView.environment.sceneUnderstanding.options.insert([.physics, .occlusion])
            if showMeshDebug {
                arView.debugOptions.insert(.showSceneUnderstanding)
            }
        }
    }

    private func applyDebugOption(arView: ARView, enabled: Bool) {
        if enabled {
            arView.debugOptions.insert(.showSceneUnderstanding)
        } else {
            arView.debugOptions.remove(.showSceneUnderstanding)
        }
    }
    #endif

    // MARK: - Controls

    private var controlsPanel: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: showMeshDebug ? "grid" : "square.3.layers.3d")
                    .foregroundStyle(.white)
                Toggle("Show mesh wireframe", isOn: $showMeshDebug)
                    .labelsHidden()
                    .onChange(of: showMeshDebug) { _, enabled in
                        #if !targetEnvironment(simulator)
                        if let arView = capturedARView {
                            applyDebugOption(arView: arView, enabled: enabled)
                        }
                        #endif
                    }
                Text("Show mesh wireframe")
                    .font(.caption)
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 12))

            Text("Slowly pan around — LiDAR builds a 3D mesh of walls, floors and objects")
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
            Image(systemName: "grid")
                .font(.system(size: 60))
                .foregroundStyle(.secondary)
            Text("AR requires a physical device")
                .font(.headline)
            Text("Scene mesh reconstruction requires a real camera feed and LiDAR.\nRun on iPhone 12 Pro+ or LiDAR iPad Pro.")
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

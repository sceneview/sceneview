#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import SceneViewSwift

/// AR People Occlusion demo — virtual objects correctly hide behind real people (#910).
///
/// Uses ARKit person segmentation (`personSegmentationWithDepth`) so placed cubes
/// disappear behind real people who walk in front of them, exactly mirroring the
/// Android ARCore semantic-segmentation-based `ar-people-occlusion` demo.
///
/// Requires a physical iOS device with an A12+ chip (iPhone XS / XR+).
/// The simulator shows a placeholder.
struct ARPeopleOcclusionDemo: View {
    @State private var isOcclusionEnabled = true
    @State private var isSupported = false
    @State private var capturedARView: ARView?
    @State private var placedCount = 0

    var body: some View {
        ZStack {
            #if !targetEnvironment(simulator)
            arSceneView
                .ignoresSafeArea()
            if !isSupported {
                unsupportedBanner
            }
            #else
            simulatorPlaceholder
            #endif

            VStack {
                Spacer()
                if isSupported {
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
                var mat = SimpleMaterial()
                mat.color = .init(tint: UIColor(
                    hue: CGFloat(placedCount % 7) / 7.0,
                    saturation: 0.85,
                    brightness: 0.9,
                    alpha: 1
                ))
                mat.metallic = .float(0.5)
                mat.roughness = .float(0.3)
                let entity = ModelEntity(
                    mesh: .generateBox(size: 0.12, cornerRadius: 0.01),
                    materials: [mat]
                )
                entity.position.y += 0.06
                let anchor = AnchorEntity(world: position)
                anchor.addChild(entity)
                arView.scene.addAnchor(anchor)
                placedCount += 1
            }
        )
        .onSessionStarted { arView in
            capturedARView = arView
            isSupported = ARWorldTrackingConfiguration.supportsFrameSemantics(
                .personSegmentationWithDepth
            )
            if isSupported {
                setOcclusion(arView: arView, enabled: isOcclusionEnabled)
            }
        }
    }

    private func setOcclusion(arView: ARView, enabled: Bool) {
        if enabled {
            arView.environment.sceneUnderstanding.options.insert(.occlusion)
        } else {
            arView.environment.sceneUnderstanding.options.remove(.occlusion)
        }
        guard let config = arView.session.configuration?.copy() as? ARWorldTrackingConfiguration else {
            return
        }
        if enabled {
            config.frameSemantics.insert(.personSegmentationWithDepth)
        } else {
            config.frameSemantics.remove(.personSegmentationWithDepth)
        }
        arView.session.run(config)
    }
    #endif

    // MARK: - Controls

    private var controlsPanel: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: isOcclusionEnabled ? "person.fill.viewfinder" : "person")
                    .foregroundStyle(.white)
                Toggle("People Occlusion", isOn: $isOcclusionEnabled)
                    .labelsHidden()
                    .onChange(of: isOcclusionEnabled) { _, enabled in
                        #if !targetEnvironment(simulator)
                        if let arView = capturedARView {
                            setOcclusion(arView: arView, enabled: enabled)
                        }
                        #endif
                    }
                Text("People Occlusion")
                    .font(.caption)
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 12))

            Text("Tap a plane to place cubes — walk in front to hide them behind real people")
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.7))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
        }
        .padding(.horizontal, 20)
    }

    private var unsupportedBanner: some View {
        VStack {
            HStack {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.yellow)
                Text("Requires iPhone XS / XR or later (A12+ chip)")
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
            Image(systemName: "person.fill.viewfinder")
                .font(.system(size: 60))
                .foregroundStyle(.secondary)
            Text("AR requires a physical device")
                .font(.headline)
            Text("People occlusion requires a real camera feed and A12+ chip.\nPlace virtual cubes — walk in front to watch them hide behind real people.")
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

#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import SceneViewSwift

/// AR Point Cloud demo — renders ARKit's live tracking feature points (#910).
///
/// Mirrors the Android `ARPointCloudDemo`: reads `ARFrame.rawFeaturePoints` each
/// frame and uses `ARView.debugOptions = .showFeaturePoints` for the native
/// RealityKit visualisation (white/yellow dots). A live counter shows the
/// current point count so the user gets honest tracking-quality feedback.
///
/// ### iOS vs Android parity note
/// Android uses ARCore's `Frame.acquirePointCloud()` + a custom Filament POINTS
/// primitive (`PointCloudNode`). On iOS, ARKit exposes the same data through
/// `ARFrame.rawFeaturePoints` (a `ARPointCloud` containing world-space
/// `SIMD3<Float>` positions). `ARView.debugOptions.showFeaturePoints` renders
/// them natively without a custom mesh, so the visual result is identical while
/// keeping this demo dependency-free. The confidence threshold Slider is omitted
/// because `ARPointCloud` on iOS doesn't expose per-point confidence scores —
/// ARKit only publishes the subset it considers reliable for motion tracking.
struct ARPointCloudDemo: View {
    @State private var showFeaturePoints = true
    @State private var pointCount = 0
    @State private var capturedARView: ARView?

    var body: some View {
        ZStack {
            #if !targetEnvironment(simulator)
            arSceneView
                .ignoresSafeArea()
            #else
            simulatorPlaceholder
            #endif

            VStack {
                statusPill
                    .padding(.top, 8)
                Spacer()
                toggleBar
                    .padding(.bottom, 28)
            }
        }
        .background(Color.black)
    }

    // MARK: - AR view

    #if !targetEnvironment(simulator)
    private var arSceneView: some View {
        ARSceneView(
            planeDetection: .horizontal,
            showPlaneOverlay: false,
            showCoachingOverlay: true
        )
        .onSessionStarted { arView in
            capturedARView = arView
            applyFeaturePointsOption(arView: arView)
        }
        .onFrame { frame, _ in
            pointCount = frame.rawFeaturePoints?.points.count ?? 0
        }
    }

    private func applyFeaturePointsOption(arView: ARView) {
        if showFeaturePoints {
            arView.debugOptions.insert(.showFeaturePoints)
        } else {
            arView.debugOptions.remove(.showFeaturePoints)
        }
    }
    #endif

    // MARK: - Status pill

    private var statusPill: some View {
        Text(pointCount == 0
             ? "Move to detect feature points"
             : "\(pointCount) feature point\(pointCount == 1 ? "" : "s")")
            .font(.system(.caption, design: .monospaced, weight: .semibold))
            .foregroundColor(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .background(.black.opacity(0.65))
            .clipShape(Capsule())
            .animation(.easeInOut(duration: 0.2), value: pointCount)
    }

    // MARK: - Toggle bar

    private var toggleBar: some View {
        HStack(spacing: 14) {
            Image(systemName: showFeaturePoints
                  ? "dot.scope" : "dot.scope")
                .symbolVariant(showFeaturePoints ? .fill : .none)
                .foregroundStyle(showFeaturePoints ? .yellow : .secondary)
            Text("Show Feature Points")
                .font(.subheadline)
                .foregroundStyle(.white)
            Spacer()
            Toggle("", isOn: Binding(
                get: { showFeaturePoints },
                set: { newValue in
                    showFeaturePoints = newValue
                    #if !targetEnvironment(simulator)
                    if let arView = capturedARView {
                        applyFeaturePointsOption(arView: arView)
                    }
                    #endif
                }
            ))
            .labelsHidden()
            .tint(.yellow)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal, 20)
    }

    // MARK: - Simulator placeholder

    private var simulatorPlaceholder: some View {
        VStack(spacing: 16) {
            Image(systemName: "camera.metering.spot")
                .font(.system(size: 56))
                .foregroundStyle(.secondary)
            Text("AR Point Cloud")
                .font(.headline)
            Text("Feature-point detection requires a physical device with a rear camera.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
#endif

#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import SceneViewSwift

/// AR Image Tracking demo — mirrors Android's `ARImageDemo.kt` (#910).
///
/// Points the camera at the bundled QR code target (shown as an on-screen card)
/// and a 3D box pops up on top of it. ARKit's image tracking uses
/// `AugmentedImageNode.createImageDatabase()` to build a reference-image set
/// from the bundled `qrcode.png` at launch time.
///
/// The demo shows:
/// - How to create an image-tracking database with `AugmentedImageNode`.
/// - How to use `ARSceneView(imageTrackingDatabase:onImageDetected:)`.
/// - How to place 3D content anchored to a real-world image.
///
/// On the simulator, AR image tracking is unavailable — a placeholder screen is
/// shown instead (Maestro asserts on "AR requires a physical device").
struct ARImageTrackingDemo: View {
    /// Tracking state shown in the status bar.
    @State private var trackingStatus: String = "Point camera at the QR code target"
    /// Number of images currently detected.
    @State private var detectedCount: Int = 0
    @State private var arViewRef: ARView? = nil

    // MARK: - Image database

    /// Reference image database built at view-init time from the bundled QR code PNG.
    private static let imageDatabase: Set<ARReferenceImage>? = {
        guard
            let url = Bundle.main.url(forResource: "qrcode", withExtension: "png"),
            let data = try? Data(contentsOf: url),
            let uiImage = UIImage(data: data)
        else {
            return nil
        }
        let referenceImages = try? AugmentedImageNode.ReferenceImage(
            name: "sceneview-qr",
            image: uiImage,
            physicalWidth: 0.15    // real-world QR code is ~15 cm wide
        )
        guard let ref = referenceImages else { return nil }
        return AugmentedImageNode.createImageDatabase([ref])
    }()

    // MARK: - Body

    var body: some View {
        ZStack(alignment: .top) {
            #if !targetEnvironment(simulator)
            arSceneView
                .ignoresSafeArea()
            #else
            simulatorPlaceholder
            #endif

            VStack(spacing: 0) {
                Spacer()
                statusBar
                targetCard
                    .padding(.bottom, 24)
            }
        }
        .background(Color.black)
    }

    // MARK: - AR view

    #if !targetEnvironment(simulator)
    private var arSceneView: some View {
        ARSceneView(
            planeDetection: .none,
            showPlaneOverlay: false,
            showCoachingOverlay: false,
            imageTrackingDatabase: Self.imageDatabase,
            onImageDetected: { imageName, anchorNode, arView in
                arViewRef = arView
                // Avoid duplicating content if the image re-enters frame
                guard anchorNode.entity.children.isEmpty else { return }
                // Place a small floating cube above the detected image
                let box = GeometryNode.cube(
                    size: 0.04,
                    material: .pbr(
                        color: UIColor(red: 0.2, green: 0.6, blue: 1.0, alpha: 1.0),
                        metallic: 0.5,
                        roughness: 0.3
                    )
                )
                box.entity.position = SIMD3<Float>(0, 0.03, 0)
                anchorNode.add(box.entity)
                arView.scene.addAnchor(anchorNode.entity)

                detectedCount += 1
                trackingStatus = "Tracking: \(imageName)"
            }
        )
    }
    #endif

    // MARK: - UI

    private var statusBar: some View {
        HStack {
            Image(systemName: detectedCount > 0 ? "checkmark.circle.fill" : "viewfinder")
                .foregroundStyle(detectedCount > 0 ? .green : .white)
            Text(trackingStatus)
                .font(.caption)
                .foregroundStyle(.white)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.black.opacity(0.55))
        .clipShape(Capsule())
        .padding(.bottom, 8)
    }

    /// Card showing the reference image so the user knows what to scan.
    private var targetCard: some View {
        HStack(spacing: 12) {
            Image(systemName: "qrcode")
                .font(.system(size: 36))
                .foregroundStyle(.white)
                .frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text("Target")
                    .font(.caption.bold())
                    .foregroundStyle(.white)
                Text("Print or display qrcode.png\n(~15 cm wide) and point\nthe camera at it.")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.75))
            }
        }
        .padding(12)
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal, 24)
    }

    // MARK: - Simulator placeholder

    private var simulatorPlaceholder: some View {
        VStack(spacing: 16) {
            Image(systemName: "qrcode.viewfinder")
                .font(.system(size: 60))
                .foregroundStyle(.secondary)
            Text("AR requires a physical device")
                .font(.headline)
            Text("ARKit image tracking requires a real camera feed.\nRun on iPhone or iPad to track the QR code target.")
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

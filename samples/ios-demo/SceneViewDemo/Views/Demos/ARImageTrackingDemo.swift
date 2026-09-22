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
    /// True while ARKit still has the target in view. Driven by the per-frame
    /// anchor state, not by the one-shot detection callback: an image that
    /// leaves the frame stops being tracked, and the card has to say so.
    @State private var isTracked: Bool = false
    /// The overlay ground is theme-independent (the backdrop is a camera
    /// frame) but its opacity is not — `DESIGN.md` "AR Coaching Overlay".
    @Environment(\.colorScheme) private var colorScheme

    // MARK: - Reference image

    /// The bundled target, as a file URL — the same one the database is built
    /// from, so what the card shows is always what ARKit is looking for.
    static let targetURL: URL? = Bundle.main.url(forResource: "qrcode", withExtension: "png")

    /// The physical width the reference image is registered at. Printing it
    /// any other size is the single most common reason tracking never fires.
    static let targetPhysicalWidth: Measurement<UnitLength> =
        Measurement(value: 0.15, unit: .meters)

    private static let targetImage: UIImage? = {
        guard let url = targetURL, let data = try? Data(contentsOf: url) else { return nil }
        return UIImage(data: data)
    }()

    // MARK: - Image database

    /// Reference image database built at view-init time from the bundled QR code PNG.
    private static let imageDatabase: Set<ARReferenceImage>? = {
        guard
            let uiImage = targetImage
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
        // Detection fires once. Tracking is a per-frame fact: `onFrame` is the
        // only hook the SDK exposes onto live anchor state today, so the status
        // is read from the image anchors themselves every frame.
        .onFrame { frame, _ in
            let imageAnchors = frame.anchors.compactMap { $0 as? ARImageAnchor }
            let live = imageAnchors.filter(\.isTracked)
            let nowTracked = !live.isEmpty
            // Only write on a real change: this runs 60 times a second.
            guard live.count != detectedCount || nowTracked != isTracked else { return }
            detectedCount = live.count
            isTracked = nowTracked
            if let name = live.first?.referenceImage.name {
                trackingStatus = "Tracking: \(name)"
            } else if imageAnchors.isEmpty {
                trackingStatus = "Point camera at the QR code target"
            } else {
                trackingStatus = "Target lost — bring it back into view"
            }
        }
    }
    #endif

    // MARK: - UI

    private var statusBar: some View {
        HStack {
            Image(systemName: isTracked ? "checkmark.circle.fill" : "viewfinder")
                .foregroundStyle(isTracked ? .green : .white)
            Text(trackingStatus)
                .font(.caption)
                .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
        }
        .padding(.horizontal, SceneViewTokens.Space.md)
        .padding(.vertical, SceneViewTokens.Space.sm)
        .background(SceneViewTokens.ARChrome.scrim(colorScheme), in: Capsule())
        .overlay(
            Capsule().strokeBorder(SceneViewTokens.ARChrome.border(colorScheme),
                                   lineWidth: SceneViewTokens.ARChrome.borderWidth)
        )
        .padding(.bottom, 8)
    }

    /// Card showing the actual reference image, at the physical size it is
    /// registered at, with a way to get it onto something printable. A
    /// generic `qrcode` glyph here told the user nothing: any QR code looked
    /// like the target, and none but this one tracks.
    private var targetCard: some View {
        HStack(spacing: 12) {
            Group {
                if let image = Self.targetImage {
                    Image(uiImage: image)
                        .resizable()
                        .interpolation(.none)
                        .scaledToFit()
                } else {
                    Image(systemName: "qrcode")
                        .font(.system(size: 36))
                        .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
                }
            }
            .frame(width: 56, height: 56)
            .background(.white)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .accessibilityLabel("Reference image ARKit is tracking")

            VStack(alignment: .leading, spacing: 2) {
                Text("Target")
                    .font(.caption.bold())
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
                Text("Print or display this exact image at \(physicalWidthLabel) wide, then point the camera at it.")
                    .font(.caption2)
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrimDim)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if let url = Self.targetURL {
                ShareLink(item: url) {
                    Image(systemName: "square.and.arrow.up")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
                        .frame(width: 36, height: 36)
                }
                .accessibilityLabel("Share the reference image")
                .accessibilityIdentifier("image-target-share")
            }
        }
        // `ar-scrim`, not `.ultraThinMaterial`: a material resolves near-white
        // in light mode, and white text on it is unreadable — over a camera
        // feed the ground has to be near-opaque and theme-independent
        // (`DESIGN.md` "AR Overlay Card").
        .padding(SceneViewTokens.Space.md)
        .background(SceneViewTokens.ARChrome.scrim(colorScheme),
                    in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg))
        .overlay(
            RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg)
                .strokeBorder(SceneViewTokens.ARChrome.border(colorScheme),
                              lineWidth: SceneViewTokens.ARChrome.borderWidth)
        )
        .padding(.horizontal, SceneViewTokens.Space.lg)
    }

    /// The registered physical width, formatted for the user's locale — a
    /// hardcoded "15 cm" would read wrong wherever inches are the unit.
    private var physicalWidthLabel: String {
        Self.targetPhysicalWidth.formatted(
            .measurement(width: .abbreviated, usage: .general)
        )
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

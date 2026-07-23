#if os(iOS)
import RealityKit
import ARKit
import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Detects real-world images and overlays 3D content on them.
///
/// Mirrors SceneView Android's `AugmentedImageNode` — uses ARKit image tracking
/// to detect physical images in the camera feed and position content on them.
///
/// ```swift
/// // 1. Create an image database
/// let images = AugmentedImageNode.createImageDatabase([
///     AugmentedImageNode.ReferenceImage(
///         name: "poster",
///         image: UIImage(named: "poster_reference")!,
///         physicalWidth: 0.3 // 30 cm wide
///     )
/// ])
///
/// // 2. Use in ARSceneView with image tracking
/// ARSceneView(
///     planeDetection: .horizontal,
///     imageTrackingDatabase: images,
///     onImageDetected: { imageName, anchor, arView in
///         let cube = GeometryNode.cube(size: 0.1, color: .blue)
///         anchor.add(cube.entity)
///         arView.scene.addAnchor(anchor.entity)
///     }
/// )
/// ```
public struct AugmentedImageNode: Sendable {

    /// Errors thrown during reference-image construction.
    public enum Error: Swift.Error, CustomStringConvertible, Sendable {
        /// The supplied `UIImage` had no `cgImage` representation
        /// (e.g. CIImage- or vector-backed images). Convert via
        /// `UIGraphicsImageRenderer.image { _ in image.draw(...) }.cgImage` first.
        case cgImageUnavailable

        public var description: String {
            switch self {
            case .cgImageUnavailable:
                return "AugmentedImageNode.ReferenceImage: UIImage has no cgImage representation"
            }
        }
    }

    /// A reference image to detect in the real world.
    public struct ReferenceImage: Sendable {
        /// Unique name to identify this image when detected.
        public let name: String

        /// The reference image to detect.
        public let cgImage: CGImage

        /// Physical width of the image in meters.
        public let physicalWidth: CGFloat

        /// Creates a reference image from a UIImage.
        ///
        /// Throws when `image.cgImage` is `nil` — that happens for CIImage- or
        /// `CGImageSource`-backed UIImages (e.g. from `UIGraphicsImageRenderer`
        /// with vector content). Pre-fix this initializer force-unwrapped and
        /// crashed; now callers get a recoverable error path. (#883)
        ///
        /// - Parameters:
        ///   - name: Unique identifier for this image.
        ///   - image: The image to detect. Must be CGImage-backed.
        ///   - physicalWidth: Real-world width in meters.
        /// - Throws: `Error.cgImageUnavailable` when the UIImage has no
        ///   `cgImage` representation.
        public init(name: String, image: UIImage, physicalWidth: CGFloat) throws {
            guard let cg = image.cgImage else {
                throw Error.cgImageUnavailable
            }
            self.name = name
            self.cgImage = cg
            self.physicalWidth = physicalWidth
        }

        /// Creates a reference image from a CGImage.
        ///
        /// - Parameters:
        ///   - name: Unique identifier for this image.
        ///   - cgImage: The image to detect.
        ///   - physicalWidth: Real-world width in meters.
        public init(name: String, cgImage: CGImage, physicalWidth: CGFloat) {
            self.name = name
            self.cgImage = cgImage
            self.physicalWidth = physicalWidth
        }
    }

    /// Creates an ARKit reference image set from an array of reference images.
    ///
    /// Use the returned set with `ARWorldTrackingConfiguration.detectionImages`.
    ///
    /// - Parameter images: Array of reference images to detect.
    /// - Returns: A set of `ARReferenceImage` for ARKit configuration.
    public static func createImageDatabase(
        _ images: [ReferenceImage]
    ) -> Set<ARReferenceImage> {
        Set(images.map { ref in
            let arImage = ARReferenceImage(
                ref.cgImage,
                orientation: .up,
                physicalWidth: ref.physicalWidth
            )
            arImage.name = ref.name
            return arImage
        })
    }

    /// Creates a reference image set from named images in an asset catalog group.
    ///
    /// - Parameter groupName: The AR Resource Group name in the asset catalog.
    /// - Returns: A set of `ARReferenceImage` from the asset catalog, or nil if not found.
    public static func referenceImages(
        inGroupNamed groupName: String
    ) -> Set<ARReferenceImage>? {
        ARReferenceImage.referenceImages(inGroupNamed: groupName, bundle: nil)
    }

    /// Tracking state of a detected image.
    public enum TrackingState: Sendable {
        /// The image is currently being tracked with high confidence.
        case tracking
        /// The image was detected but tracking is limited.
        case limited
        /// The image is no longer visible.
        case notTracking
    }

    /// Converts an ARImageAnchor's tracking state to our TrackingState enum.
    public static func trackingState(for anchor: ARImageAnchor) -> TrackingState {
        if anchor.isTracked {
            return .tracking
        } else {
            return .notTracking
        }
    }

    /// The name of the detected image (from the reference image database).
    public let imageName: String

    /// The estimated physical size of the detected image.
    public let estimatedSize: CGSize

    /// The anchor entity placed at the detected image's position.
    public let anchorEntity: AnchorEntity

    /// Creates an AugmentedImageNode from a detected ARImageAnchor.
    ///
    /// This is typically called from the AR session delegate when an image is detected.
    ///
    /// - Parameter imageAnchor: The detected image anchor from ARKit.
    /// - Returns: An `AugmentedImageNode` positioned at the image location.
    public static func fromDetection(_ imageAnchor: ARImageAnchor) -> AugmentedImageNode {
        let name = imageAnchor.referenceImage.name ?? "unknown"
        let size = imageAnchor.referenceImage.physicalSize
        let anchor = AnchorEntity(anchor: imageAnchor)

        return AugmentedImageNode(
            imageName: name,
            estimatedSize: size,
            anchorEntity: anchor
        )
    }

    /// Adds a child entity to the detected image's anchor.
    public func add(_ child: Entity) {
        anchorEntity.addChild(child)
    }

    /// Removes all child content from the image anchor.
    public func removeAll() {
        // Snapshot into an `Array` first: `anchorEntity.children` is a live
        // view, so removing while iterating it directly re-indexes the
        // collection mid-loop and skips every other child. See #2878.
        for child in Array(anchorEntity.children) {
            anchorEntity.removeChild(child)
        }
    }
}

#endif // os(iOS)

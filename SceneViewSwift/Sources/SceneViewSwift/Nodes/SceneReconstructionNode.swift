#if os(iOS)
import RealityKit
import ARKit
import Foundation

/// Provides access to ARKit scene reconstruction mesh data.
///
/// Mirrors SceneView Android's `StreetscapeGeometryNode` — on Apple platforms,
/// this wraps ARKit's scene reconstruction (LiDAR mesh) to provide
/// real-world geometry as collidable entities in the scene.
///
/// Scene reconstruction requires a LiDAR-equipped device (iPhone 12 Pro+, iPad Pro).
///
/// ```swift
/// ARSceneView(planeDetection: .horizontal)
///     .onSessionStarted { arView in
///         SceneReconstructionNode.enableReconstruction(in: arView)
///     }
/// ```
public enum SceneReconstructionNode {

    /// Whether the device supports scene reconstruction (LiDAR).
    public static var isSupported: Bool {
        ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh)
    }

    /// Whether the device supports scene reconstruction with classification.
    public static var isClassificationSupported: Bool {
        ARWorldTrackingConfiguration.supportsSceneReconstruction(.meshWithClassification)
    }

    /// Turns the LiDAR mesh on in the session the view is already running.
    ///
    /// The live configuration is **amended**, not replaced: frame semantics
    /// (people occlusion, person segmentation), plane detection, environment
    /// texturing, detection images and every other option the session was
    /// started with are kept. The session is re-run with no options — no
    /// `.resetTracking`, no `.removeExistingAnchors` — so tracking, the world
    /// map and the anchors already placed stay where they are.
    ///
    /// Nothing visible is added to the view. RealityKit's
    /// `.showSceneUnderstanding` wireframe is a developer overlay; pass
    /// `showDebugMeshOverlay: true` to draw it, and remove it again with
    /// ``hideMeshVisualization(in:)``.
    ///
    /// - Parameters:
    ///   - arView: The ARView whose session gets the mesh.
    ///   - classification: Whether to enable mesh classification. Default false.
    ///   - showDebugMeshOverlay: Draws RealityKit's debug wireframe over the
    ///     mesh. Default false — debug options are never turned on implicitly.
    /// - Returns: `true` when the session now runs with the requested
    ///   reconstruction; `false` when the device has no LiDAR (or no
    ///   classification support), or when the session runs a configuration
    ///   other than `ARWorldTrackingConfiguration` (face or body tracking),
    ///   which is left untouched rather than replaced.
    @discardableResult
    public static func enableReconstruction(
        in arView: ARView,
        classification: Bool = false,
        showDebugMeshOverlay: Bool = false
    ) -> Bool {
        let requested: ARConfiguration.SceneReconstruction =
            classification ? .meshWithClassification : .mesh
        guard ARWorldTrackingConfiguration.supportsSceneReconstruction(requested) else {
            return false
        }
        let current = arView.session.configuration
        guard let amendment = amend(current, with: requested) else {
            let running = current.map { String(describing: type(of: $0)) } ?? "nothing"
            print("[SceneViewSwift] SceneReconstructionNode: the session runs \(running), which has no scene reconstruction — left unchanged")
            return false
        }
        if amendment.needsRun {
            arView.session.run(amendment.configuration, options: [])
        }
        if showDebugMeshOverlay {
            arView.debugOptions.insert(.showSceneUnderstanding)
        }
        return true
    }

    /// What to run so that `current` gains `reconstruction`.
    ///
    /// - A world-tracking `current` is amended **in place** — only its
    ///   `sceneReconstruction` changes — and the same instance is returned,
    ///   which is Apple's own pattern for scene reconstruction. It is not
    ///   `copy()`'d: `ARWorldTrackingConfiguration`'s `NSCopying` drops
    ///   plane detection, detection images, world alignment and
    ///   collaboration (measured on the iOS 27 Simulator), which is the very
    ///   loss this function exists to avoid. `needsRun` is `false` when the
    ///   reconstruction was already on.
    /// - No configuration at all (the session never ran) yields a fresh
    ///   world-tracking configuration with the classic defaults.
    /// - Any other configuration class yields `nil`: swapping a face- or
    ///   body-tracking session for world tracking would change camera and
    ///   throw the host's whole session away.
    static func amend(
        _ current: ARConfiguration?,
        with reconstruction: ARConfiguration.SceneReconstruction
    ) -> (configuration: ARWorldTrackingConfiguration, needsRun: Bool)? {
        guard let current else {
            let fresh = ARWorldTrackingConfiguration()
            fresh.planeDetection = [.horizontal, .vertical]
            fresh.environmentTexturing = .automatic
            fresh.sceneReconstruction = reconstruction
            return (fresh, true)
        }
        guard let world = current as? ARWorldTrackingConfiguration else { return nil }
        if world.sceneReconstruction == reconstruction { return (world, false) }
        world.sceneReconstruction = reconstruction
        return (world, true)
    }

    /// Removes RealityKit's `.showSceneUnderstanding` debug wireframe (the
    /// mesh itself stays active for occlusion and physics).
    ///
    /// - Parameter arView: The ARView.
    public static func hideMeshVisualization(in arView: ARView) {
        arView.debugOptions.remove(.showSceneUnderstanding)
    }

    /// Enables occlusion so virtual objects are hidden behind real-world surfaces.
    ///
    /// - Parameter arView: The ARView.
    public static func enableOcclusion(in arView: ARView) {
        if #available(iOS 17.0, *) {
            arView.environment.sceneUnderstanding.options.insert(.occlusion)
        }
    }

    /// Enables physics interaction with the reconstruction mesh.
    ///
    /// Virtual objects will collide with real-world surfaces detected by LiDAR.
    ///
    /// - Parameter arView: The ARView.
    public static func enablePhysics(in arView: ARView) {
        if #available(iOS 17.0, *) {
            arView.environment.sceneUnderstanding.options.insert(.physics)
        }
    }

    /// Mesh classification types from ARKit scene reconstruction.
    public enum Classification: String, Sendable, CaseIterable {
        case wall
        case floor
        case ceiling
        case table
        case seat
        case window
        case door
        case none

        /// Converts from ARKit's `ARMeshClassification`.
        public static func from(_ arClassification: ARMeshClassification) -> Classification {
            switch arClassification {
            case .wall: return .wall
            case .floor: return .floor
            case .ceiling: return .ceiling
            case .table: return .table
            case .seat: return .seat
            case .window: return .window
            case .door: return .door
            case .none: return .none
            @unknown default: return .none
            }
        }
    }
}

#endif // os(iOS)

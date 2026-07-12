#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// A wrapper around RealityKit's `ModelEntity` for loading and displaying 3D models.
///
/// Mirrors SceneView Android's `ModelNode` — supports USDZ natively, with glTF
/// support planned via GLTFKit2.
///
/// ```swift
/// @State private var model: ModelNode?
///
/// SceneView { content in
///     if let model {
///         content.addChild(model.entity)
///     }
/// }
/// .task {
///     model = try? await ModelNode.load("models/car.usdz")
/// }
/// ```
public struct ModelNode: @unchecked Sendable {
    /// The underlying RealityKit entity.
    public let entity: ModelEntity

    /// Stored tap handler invoked by the scene's gesture recognizer.
    /// - Note: Managed externally — the scene checks `tapHandler` after a hit test.
    public var tapHandler: (() -> Void)?

    /// Reference-type registry that tracks every `AnimationPlaybackController`
    /// returned by `playAnimation*` so `pauseAllAnimations` / `resumeAllAnimations`
    /// can target them. The `ModelNode` itself is a value type, so this class
    /// gives the per-node animation state a stable identity across copies of
    /// the wrapper struct (the underlying ModelEntity is already a reference).
    private final class AnimationRegistry: @unchecked Sendable {
        var controllers: [AnimationPlaybackController] = []
    }
    private let registry: AnimationRegistry

    /// Active controllers — internal hook for the pause/resume implementation
    /// below. Public callers should drive playback through the `playAnimation*`
    /// + `pauseAllAnimations` / `resumeAllAnimations` / `stopAllAnimations` APIs.
    fileprivate var activeControllers: [AnimationPlaybackController] {
        get { registry.controllers }
        nonmutating set { registry.controllers = newValue }
    }
    fileprivate func trackController(_ controller: AnimationPlaybackController) {
        registry.controllers.append(controller)
    }

    /// World-space position.
    public var position: SIMD3<Float> {
        get { entity.position }
        nonmutating set { entity.position = newValue }
    }

    /// Orientation as a quaternion.
    public var rotation: simd_quatf {
        get { entity.orientation }
        nonmutating set { entity.orientation = newValue }
    }

    /// Scale factor (uniform or per-axis).
    public var scale: SIMD3<Float> {
        get { entity.scale }
        nonmutating set { entity.scale = newValue }
    }

    /// Wraps an existing `ModelEntity`.
    public init(_ entity: ModelEntity) {
        self.entity = entity
        self.tapHandler = nil
        self.registry = AnimationRegistry()
    }

    /// Loads a 3D model from a bundle resource path.
    ///
    /// Supports `.usdz` and `.reality` files natively.
    ///
    /// - Parameters:
    ///   - path: Bundle resource name (e.g. `"models/car.usdz"`).
    ///   - enableCollision: Whether to generate a collision shape for hit testing.
    /// - Returns: A `ModelNode` wrapping the loaded entity.
    /// - Throws: If the file cannot be found or loaded.
    @MainActor
    public static func load(
        _ path: String,
        enableCollision: Bool = true
    ) async throws -> ModelNode {
        let loadedEntity = try await Entity(named: path)
        let modelEntity = loadedEntity as? ModelEntity ?? {
            let me = ModelEntity()
            me.addChild(loadedEntity)
            return me
        }()

        // Generate collision shapes for tap interaction
        if enableCollision {
            modelEntity.generateCollisionShapes(recursive: true)
        }

        return ModelNode(modelEntity)
    }

    /// Loads a 3D model from a URL.
    ///
    /// - Parameters:
    ///   - url: File URL to the model.
    ///   - enableCollision: Whether to generate collision shapes.
    /// - Returns: A `ModelNode` wrapping the loaded entity.
    /// - Throws: If the file cannot be loaded.
    @MainActor
    public static func load(
        contentsOf url: URL,
        enableCollision: Bool = true
    ) async throws -> ModelNode {
        let loadedEntity = try await Entity(contentsOf: url)
        let modelEntity = loadedEntity as? ModelEntity ?? {
            let me = ModelEntity()
            me.addChild(loadedEntity)
            return me
        }()

        if enableCollision {
            modelEntity.generateCollisionShapes(recursive: true)
        }

        return ModelNode(modelEntity)
    }

    /// Loads a 3D model from a remote HTTP/HTTPS URL.
    ///
    /// Downloads the file to a temporary directory, then loads it with RealityKit.
    /// Supports USDZ and Reality files. The temporary file is cleaned up after loading.
    ///
    /// ```swift
    /// let model = try await ModelNode.load(
    ///     from: URL(string: "https://example.com/model.usdz")!
    /// )
    /// ```
    ///
    /// - Parameters:
    ///   - remoteURL: An HTTP or HTTPS URL pointing to a USDZ or Reality file.
    ///   - enableCollision: Whether to generate collision shapes for hit testing.
    ///   - timeout: Download timeout in seconds (default: 60).
    @MainActor
    public static func load(
        from remoteURL: URL,
        enableCollision: Bool = true,
        timeout: TimeInterval = 60.0
    ) async throws -> ModelNode {
        // Download to temporary file
        var request = URLRequest(url: remoteURL)
        request.timeoutInterval = timeout

        let (tempURL, response) = try await URLSession.shared.download(for: request)

        // Validate HTTP response
        if let httpResponse = response as? HTTPURLResponse,
           !(200..<300).contains(httpResponse.statusCode) {
            throw URLError(.badServerResponse)
        }

        // Move to a named temp file with correct extension (RealityKit needs it)
        let ext = remoteURL.pathExtension.isEmpty ? "usdz" : remoteURL.pathExtension
        let namedTempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(ext)
        try FileManager.default.moveItem(at: tempURL, to: namedTempURL)

        defer {
            try? FileManager.default.removeItem(at: namedTempURL)
        }

        return try await load(contentsOf: namedTempURL, enableCollision: enableCollision)
    }

    // MARK: - Transform helpers (mirrors Android's Node API)

    /// Returns self positioned at the given coordinates.
    @discardableResult
    public func position(_ position: SIMD3<Float>) -> ModelNode {
        entity.position = position
        return self
    }

    /// Returns self scaled uniformly.
    @discardableResult
    public func scale(_ uniform: Float) -> ModelNode {
        entity.scale = .init(repeating: uniform)
        return self
    }

    /// Returns self scaled per-axis.
    @discardableResult
    public func scale(_ scale: SIMD3<Float>) -> ModelNode {
        entity.scale = scale
        return self
    }

    /// Returns self rotated by the given quaternion.
    @discardableResult
    public func rotation(_ rotation: simd_quatf) -> ModelNode {
        entity.orientation = rotation
        return self
    }

    /// Returns self rotated by angle around axis.
    @discardableResult
    public func rotation(angle: Float, axis: SIMD3<Float>) -> ModelNode {
        entity.orientation = simd_quatf(angle: angle, axis: axis)
        return self
    }

    // MARK: - Scale to units (mirrors Android's ModelNode.scaleToUnits)

    /// Scales the model to fit within a cube of the given size.
    ///
    /// Mirrors Android's `ModelNode(scaleToUnits = 1f)`.
    ///
    /// - Parameter units: Target size in meters (default 1.0).
    /// - Returns: Self scaled to fit.
    @discardableResult
    public func scaleToUnits(_ units: Float = 1.0) -> ModelNode {
        let bounds = entity.visualBounds(relativeTo: nil)
        let extents = bounds.extents
        let maxExtent = max(extents.x, max(extents.y, extents.z))
        guard maxExtent > 0 else { return self }
        let scaleFactor = units / maxExtent
        return scale(scaleFactor)
    }

    // MARK: - Animation (mirrors Android's ModelNode animation API)

    /// The number of available animations on this model.
    public var animationCount: Int {
        entity.availableAnimations.count
    }

    /// Whether any animation is currently playing.
    ///
    /// - Note: Returns `true` if the entity has available animations.
    ///   For precise tracking, retain the `AnimationPlaybackController` returned
    ///   by `entity.playAnimation(_:)` and check its `.isComplete` property.
    public var isAnimating: Bool {
        !entity.availableAnimations.isEmpty
    }

    /// Plays all animations on the model.
    ///
    /// Mirrors Android's `ModelNode(autoAnimate = true)`.
    ///
    /// - Parameters:
    ///   - loop: Whether animations should repeat. Default `true`.
    ///   - speed: Playback speed multiplier. Default 1.0.
    public func playAllAnimations(loop: Bool = true, speed: Float = 1.0) {
        // Drive playback speed through `AnimationPlaybackController.speed` —
        // the parameter was previously dropped silently. (#883)
        for animation in entity.availableAnimations {
            let resource = if loop { animation.repeat() } else { animation }
            let controller = entity.playAnimation(
                resource,
                transitionDuration: 0.0,
                startsPaused: false
            )
            controller.speed = speed
            trackController(controller)
        }
    }

    /// Plays a specific animation by index.
    ///
    /// - Parameters:
    ///   - index: Zero-based animation index.
    ///   - loop: Whether the animation should repeat.
    ///   - speed: Playback speed multiplier.
    ///   - transitionDuration: Blend time when transitioning from another animation.
    public func playAnimation(
        at index: Int,
        loop: Bool = true,
        speed: Float = 1.0,
        transitionDuration: TimeInterval = 0.2
    ) {
        guard index < entity.availableAnimations.count else { return }
        let animation = entity.availableAnimations[index]
        let resource = if loop { animation.repeat() } else { animation }
        let controller = entity.playAnimation(
            resource,
            transitionDuration: transitionDuration
        )
        // (#883) speed was dropped silently before this fix — set it on the
        // returned AnimationPlaybackController so the caller's parameter
        // actually drives playback.
        controller.speed = speed
        trackController(controller)
    }

    /// Names of all available animations on this model.
    ///
    /// Names are extracted from the animation resource definitions.
    /// Useful for discovering which animations a model provides before
    /// calling `playAnimation(named:)`.
    public var animationNames: [String] {
        entity.availableAnimations.map { $0.name ?? "" }
    }

    /// Plays a specific animation by name.
    ///
    /// If no animation matches the given name, this method does nothing.
    ///
    /// - Parameters:
    ///   - name: The animation name (as authored in the 3D file).
    ///   - loop: Whether the animation should repeat. Default `true`.
    ///   - speed: Playback speed multiplier. Default 1.0.
    ///   - transitionDuration: Blend time when transitioning from another animation.
    public func playAnimation(
        named name: String,
        loop: Bool = true,
        speed: Float = 1.0,
        transitionDuration: TimeInterval = 0.2
    ) {
        guard let animation = entity.availableAnimations.first(where: {
            $0.name == name
        }) else { return }
        let resource = if loop { animation.repeat() } else { animation }
        let controller = entity.playAnimation(
            resource,
            transitionDuration: transitionDuration
        )
        controller.speed = speed
        trackController(controller)
    }

    /// Stops all animations on the model — destroys playhead. Re-playing
    /// restarts from t=0. Also clears any tracked playback controllers.
    public func stopAllAnimations() {
        entity.stopAllAnimations()
        activeControllers.removeAll()
    }

    /// Pauses every active animation playback controller in place.
    ///
    /// The previous implementation called `stopAllAnimations()` which destroyed
    /// the playhead and made the contract a lie — stop, not pause. We now track
    /// controllers returned by `playAnimation(...)` (in [activeControllers])
    /// so pause/resume can target them individually. (#883)
    public func pauseAllAnimations() {
        for controller in activeControllers {
            controller.pause()
        }
    }

    /// Resumes every paused animation playback controller in place.
    public func resumeAllAnimations() {
        for controller in activeControllers {
            controller.resume()
        }
    }

    // MARK: - Center origin (mirrors Android's ModelNode.centerOrigin)

    /// Re-centres the model so that its bounding-box centre lands at the given target.
    ///
    /// Many 3D assets (e.g. glTF/USDZ files exported from DCC tools) place their
    /// authored pivot at the floor of the bounding box, which causes the model to
    /// render in the lower portion of an orbit camera framed at world origin.
    /// Calling `centerOrigin()` after `scaleToUnits(_:)` shifts the model so its
    /// geometric centre coincides with `target` (defaults to world origin).
    ///
    /// Mirrors Android's `ModelNode(centerOrigin = Position(0, 0, 0))`.
    ///
    /// - Parameter target: Local-space target for the bounding-box centre. Defaults to origin.
    /// - Returns: Self with the centring offset applied.
    @discardableResult
    public func centerOrigin(_ target: SIMD3<Float> = .zero) -> ModelNode {
        let bounds = entity.visualBounds(relativeTo: nil)
        let center = bounds.center
        let offset = target - center
        entity.position = entity.position + offset
        return self
    }

    /// Aligns the AABB point selected by a **normalized** origin with the node origin —
    /// Android `ModelNode.centerOrigin(Position)` parity.
    ///
    /// Unlike the absolute ``centerOrigin(_:)`` above (whose argument is a model-space
    /// point in metres that the bounding-box **centre** is moved to), `origin` here is a
    /// point of the bounding box in **normalized AABB coordinates** — `0` = box centre,
    /// `±1` = box faces, per axis. The model is translated so that the selected AABB point
    /// lands exactly on the node's local origin, whatever the asset's authored pivot:
    ///
    /// - `.zero` — the bounding-box centre lands on the origin (identical to `centerOrigin(.zero)`).
    /// - `SIMD3(0, -1, 0)` — centre-horizontal, **bottom aligned**: the model sits on the origin.
    /// - `SIMD3(0, 1, 0)` — top aligned: the model hangs from the origin.
    /// - `SIMD3(-1, 1, 0)` — left | top aligned.
    ///
    /// On an **unpositioned** entity this lets an Android grounding snippet port across:
    /// `centerOrigin(normalized: SIMD3(0, -1, 0))` grounds a model like Android's
    /// `Position(0, -1, 0)`, replacing the former manual workaround
    /// `centerOrigin(SIMD3(0, bounds.extents.y / 2, 0))`. Apply after ``scaleToUnits(_:)``
    /// so the bounds reflect the final scale.
    ///
    /// **Apply before positioning.** Unlike Android's `centerOrigin` (which composes additively —
    /// `position += translation`, independent of the current position), this reads the entity's
    /// **world** visual-bounds, so it does not compose with a previously-set position: call it on
    /// an unpositioned, unparented entity (load → scaleToUnits → centerOrigin, before `.position(_:)`
    /// or anchoring), and a later `.position(_:)` replaces the grounding offset.
    ///
    /// Mirrors Android's `ModelNode.centerOrigin(origin)` and its
    /// `-(center + origin * halfExtent) * scale` translation (scale is already baked into the
    /// RealityKit visual bounds here).
    ///
    /// - Parameter origin: AABB point in normalized coordinates (`-1...1` per axis, `0` = centre).
    /// - Returns: Self with the alignment offset applied.
    @discardableResult
    public func centerOrigin(normalized origin: SIMD3<Float>) -> ModelNode {
        let bounds = entity.visualBounds(relativeTo: nil)
        entity.position = entity.position + Self.centerOriginTranslation(
            center: bounds.center,
            extents: bounds.extents,
            origin: origin
        )
        return self
    }

    /// Computes the ``centerOrigin(normalized:)`` translation: the offset that moves the AABB
    /// point selected by `origin` (normalized coordinates, `-1...1` per axis, `0` = centre) onto
    /// the node's local origin.
    ///
    /// The anchor point in model space is `center + origin * (extents / 2)`; negating it yields the
    /// translation that cancels it out. Using the AABB `center` (not just the extents) keeps the
    /// alignment correct for assets whose bounding box is not authored centred on their pivot.
    ///
    /// Pure math, extracted so the formula is unit-testable without a live RealityKit scene —
    /// mirrors Android's `centerOriginTranslation` (`ModelNodeCenterOriginFormulaTest`).
    static func centerOriginTranslation(
        center: SIMD3<Float>,
        extents: SIMD3<Float>,
        origin: SIMD3<Float>
    ) -> SIMD3<Float> {
        -(center + origin * (extents / 2))
    }

    // MARK: - Collision

    /// Generates collision shapes for this model, enabling hit testing.
    public func enableCollision() {
        entity.generateCollisionShapes(recursive: true)
    }

    /// The axis-aligned bounding box of the collision shape, relative to the entity.
    ///
    /// Returns `nil` if no collision shapes have been generated.
    public var collisionBounds: BoundingBox? {
        guard entity.collision != nil else { return nil }
        return entity.visualBounds(relativeTo: nil)
    }

    /// Registers a tap handler for this model.
    ///
    /// The scene's gesture recognizer should check `tapHandler` after a hit test.
    ///
    /// - Parameter handler: Closure invoked when the model is tapped.
    /// - Returns: Self for chaining.
    @discardableResult
    public mutating func onTap(_ handler: @escaping () -> Void) -> ModelNode {
        tapHandler = handler
        return self
    }

    // MARK: - Material properties

    /// Sets the base color of all materials on this model.
    ///
    /// - Parameter color: The new base color.
    /// - Returns: Self for chaining.
    @discardableResult
    public func setColor(_ color: SimpleMaterial.Color) -> ModelNode {
        guard var model = entity.model else { return self }
        model.materials = model.materials.map { material in
            if var pbr = material as? PhysicallyBasedMaterial {
                #if canImport(UIKit)
                pbr.baseColor = .init(tint: color)
                #else
                pbr.baseColor = .init(tint: color)
                #endif
                return pbr
            } else if var simple = material as? SimpleMaterial {
                simple.color = .init(tint: color)
                return simple
            }
            return material
        }
        entity.model = model
        return self
    }

    /// Sets the metallic factor on all PBR materials.
    ///
    /// - Parameter value: Metallic factor (0 = dielectric, 1 = fully metallic).
    /// - Returns: Self for chaining.
    @discardableResult
    public func setMetallic(_ value: Float) -> ModelNode {
        guard var model = entity.model else { return self }
        model.materials = model.materials.map { material in
            if var pbr = material as? PhysicallyBasedMaterial {
                pbr.metallic = .init(floatLiteral: value)
                return pbr
            }
            return material
        }
        entity.model = model
        return self
    }

    /// Sets the roughness factor on all PBR materials.
    ///
    /// - Parameter value: Roughness factor (0 = smooth/mirror, 1 = fully rough).
    /// - Returns: Self for chaining.
    @discardableResult
    public func setRoughness(_ value: Float) -> ModelNode {
        guard var model = entity.model else { return self }
        model.materials = model.materials.map { material in
            if var pbr = material as? PhysicallyBasedMaterial {
                pbr.roughness = .init(floatLiteral: value)
                return pbr
            }
            return material
        }
        entity.model = model
        return self
    }

    /// Sets the opacity of all materials on this model.
    ///
    /// - Parameter value: Opacity factor (0 = fully transparent, 1 = fully opaque).
    /// - Returns: Self for chaining.
    @discardableResult
    public func opacity(_ value: Float) -> ModelNode {
        guard var model = entity.model else { return self }
        model.materials = model.materials.map { material in
            if var pbr = material as? PhysicallyBasedMaterial {
                pbr.blending = .transparent(opacity: .init(floatLiteral: value))
                return pbr
            } else if var simple = material as? SimpleMaterial {
                simple.color = .init(
                    tint: simple.color.tint.withAlphaComponent(CGFloat(value))
                )
                return simple
            }
            return material
        }
        entity.model = model
        return self
    }

    // MARK: - Shadow

    /// Adds a grounding shadow beneath the model.
    @discardableResult
    public func withGroundingShadow() -> ModelNode {
        if #available(iOS 18.0, visionOS 2.0, *) {
            entity.components.set(GroundingShadowComponent(castsShadow: true))
        }
        return self
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)

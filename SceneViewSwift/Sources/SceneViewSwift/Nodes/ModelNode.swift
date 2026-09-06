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
    /// Supports every format ``load(contentsOf:unit:enableCollision:)`` does — `.usdz`
    /// and `.reality` through RealityKit, `.stl`, `.obj` and `.ply` through ModelIO.
    ///
    /// - Parameters:
    ///   - path: Bundle resource name (e.g. `"models/car.usdz"`, `"parts/bracket.stl"`).
    ///   - unit: The unit a unitless mesh file is authored in — see
    ///     ``load(contentsOf:unit:enableCollision:)``.
    ///   - enableCollision: Whether to generate a collision shape for hit testing.
    ///   - bundle: Bundle to search. Defaults to `.main`.
    /// - Returns: A `ModelNode` wrapping the loaded entity.
    /// - Throws: If the file cannot be found or loaded.
    @MainActor
    public static func load(
        _ path: String,
        unit: ModelUnit? = nil,
        enableCollision: Bool = true,
        bundle: Bundle = .main
    ) async throws -> ModelNode {
        // ModelIO formats are not part of `Entity(named:)`'s vocabulary, so they have to
        // be resolved to a URL first. USD/Reality keep the RealityKit path, which also
        // resolves Reality Composer Pro scene names that are not plain files.
        if let format = ModelFormat(fileExtension: (path as NSString).pathExtension),
           format.loader != .realityKit,
           let url = resourceURL(for: path, in: bundle) {
            return try await load(contentsOf: url, unit: unit, enableCollision: enableCollision)
        }

        let loadedEntity = try await Entity(named: path)
        let modelEntity = loadedEntity as? ModelEntity ?? {
            let me = ModelEntity()
            me.addChild(loadedEntity)
            return me
        }()

        // Generate collision shapes for tap interaction
        if enableCollision {
            modelEntity.generateCollisionShapes(recursive: true)
            modelEntity.makeInputTargetable()
        }

        return ModelNode(modelEntity)
    }

    /// Resolves a bundle resource path like `"models/part.stl"` to a file URL.
    ///
    /// Two lookups because Xcode's two ways of adding resources produce two layouts: a
    /// folder reference keeps `models/` as a real subdirectory, while a group flattens
    /// everything into the bundle root under its bare file name.
    @MainActor
    private static func resourceURL(for path: String, in bundle: Bundle) -> URL? {
        let ns = path as NSString
        let ext = ns.pathExtension
        let withoutExtension = ns.deletingPathExtension
        let directory = (withoutExtension as NSString).deletingLastPathComponent
        let name = (withoutExtension as NSString).lastPathComponent

        if !directory.isEmpty,
           let url = bundle.url(forResource: name, withExtension: ext, subdirectory: directory) {
            return url
        }
        return bundle.url(forResource: name, withExtension: ext)
    }

    /// Loads a 3D model from a URL — **the one entry point that opens every supported
    /// format**.
    ///
    /// The format is decided by ``ModelFormat/sniff(contentsOf:)``, which reads the
    /// file's own bytes before believing its extension, then the file goes down one of
    /// two paths:
    ///
    /// | Formats | Path |
    /// |---|---|
    /// | `.usdz` `.usda` `.usdc` `.usd` `.reality` | RealityKit's `Entity(contentsOf:)` — already metric |
    /// | `.stl` `.obj` `.ply` | ModelIO → ``MeshAsset`` → `MeshResource`, with `unit` applied |
    ///
    /// ```swift
    /// // A slicer STL — millimetres, so this is 21 cm tall in AR, not 210 m.
    /// let print = try await ModelNode.load(contentsOf: stlURL)
    ///
    /// // A scan authored in centimetres.
    /// let scan = try await ModelNode.load(contentsOf: objURL, unit: .centimeters)
    /// ```
    ///
    /// - Parameters:
    ///   - url: File URL to the model.
    ///   - unit: The unit the file's coordinates are in, for the formats that do not say
    ///     (STL, OBJ, PLY). `nil` uses ``ModelFormat/defaultUnit`` — millimetres for STL,
    ///     metres for OBJ and PLY. Ignored for USD and Reality files, which RealityKit
    ///     has already converted to metres.
    ///   - enableCollision: Whether to generate collision shapes.
    /// - Returns: A `ModelNode` wrapping the loaded entity, at real-world scale.
    /// - Throws: ``ModelLoadingError/unsupportedFormat(fileExtension:)`` — carrying the
    ///   extension, so a viewer can say which format it was asked for — or the underlying
    ///   read error.
    @MainActor
    public static func load(
        contentsOf url: URL,
        unit: ModelUnit? = nil,
        enableCollision: Bool = true
    ) async throws -> ModelNode {
        let format = try ModelFormat.sniff(contentsOf: url)
        guard format.loader == .realityKit else {
            let asset = try MeshAsset.load(contentsOf: url, format: format, unit: unit)
            return try await ModelNode(asset, enableCollision: enableCollision)
        }

        let loadedEntity = try await Entity(contentsOf: url)
        let modelEntity = loadedEntity as? ModelEntity ?? {
            let me = ModelEntity()
            me.addChild(loadedEntity)
            return me
        }()

        if enableCollision {
            modelEntity.generateCollisionShapes(recursive: true)
            modelEntity.makeInputTargetable()
        }

        return ModelNode(modelEntity)
    }

    /// Loads a 3D model from a remote HTTP/HTTPS URL.
    ///
    /// Downloads the file to a temporary directory, then hands it to
    /// ``load(contentsOf:unit:enableCollision:)`` — so it opens every supported format,
    /// not just USDZ. The temporary file is cleaned up after loading.
    ///
    /// ```swift
    /// let model = try await ModelNode.load(
    ///     from: URL(string: "https://example.com/model.usdz")!
    /// )
    /// ```
    ///
    /// - Parameters:
    ///   - unit: The unit a unitless mesh file is authored in — see
    ///     ``load(contentsOf:unit:enableCollision:)``.
    ///   - remoteURL: An HTTP or HTTPS URL pointing to a model file. Any other
    ///     scheme throws `URLError(.unsupportedURL)` — this is now enforced, not merely
    ///     documented. `URLSession` will happily fetch a `file://` URL, so a caller
    ///     forwarding a user- or network-supplied string could otherwise turn this into
    ///     a local-file read. Load a local file with ``load(contentsOf:enableCollision:)``.
    ///   - enableCollision: Whether to generate collision shapes for hit testing.
    ///   - timeout: Download **inactivity** timeout in seconds (default: 60) — not a
    ///     wall-clock or size bound. The size bound is ``maxBytes``.
    ///   - maxBytes: Ceiling on the downloaded body, in bytes. Default 64 MB, matching
    ///     the Android downloader's `MAX_MODEL_BYTES`. Enforced on every chunk so an
    ///     endless body is *stopped*, not merely detected afterwards; a server that
    ///     announces an oversized `Content-Length` is refused before a byte is read.
    ///     Exceeding it throws `URLError(.dataLengthExceedsMaximum)`.
    @MainActor
    public static func load(
        from remoteURL: URL,
        unit: ModelUnit? = nil,
        enableCollision: Bool = true,
        timeout: TimeInterval = 60.0,
        maxBytes: Int64 = 64 * 1024 * 1024
    ) async throws -> ModelNode {
        // Enforce the scheme this method's own documentation promises. `URL` accepts any
        // scheme and `URLSession.download` honours `file://` — measured, not assumed: it
        // returns the bytes of a local path, with a response that is not an
        // `HTTPURLResponse` and therefore skipped the status check below entirely. So a
        // caller forwarding an attacker-influenced string (a deep link, a remote-config
        // value, a bridge argument) turned this into an in-sandbox file read handed to
        // RealityKit's USD parser. Use `load(contentsOf:)` for a local file — that is
        // what it is for.
        guard let scheme = remoteURL.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            throw URLError(.unsupportedURL)
        }

        var request = URLRequest(url: remoteURL)
        request.timeoutInterval = timeout

        // `download(for:delegate:)` rather than a byte-by-byte `bytes(for:)` loop:
        // `URLSession.AsyncBytes` yields one `UInt8` per iteration, which is fine for a
        // log stream and ruinous for a model — a 10 MB `.usdz` is 10 million awaits. The
        // delegate watches the same transfer the system is already streaming to disk and
        // cancels it the moment it goes over, so the cap *stops* the body instead of
        // discovering afterwards that it was too big.
        //
        // `timeout` is an inactivity timeout, not a wall-clock or size bound: without
        // this, a host trickling an endless body keeps the connection alive and fills the
        // device's storage. Android has enforced a cap since the façade shipped
        // (`MAX_MODEL_BYTES`); this is the matching one.
        let capDelegate = SizeCappedDownload(maxBytes: maxBytes)
        let tempURL: URL
        let response: URLResponse
        do {
            (tempURL, response) = try await URLSession.shared.download(
                for: request,
                delegate: capDelegate
            )
        } catch let error as URLError where error.code == .cancelled && capDelegate.didExceed {
            // The delegate cancels on overflow, and a cancelled task surfaces as
            // `.cancelled`. Re-map it so the caller can tell "too big" from "the caller
            // cancelled me" — otherwise the cap is indistinguishable from a normal
            // cooperative cancellation.
            throw URLError(.dataLengthExceedsMaximum)
        }
        // Registered immediately: every path below can throw, and `URLSession`'s
        // downloaded file is the caller's to clean up once the call returns.
        defer { try? FileManager.default.removeItem(at: tempURL) }

        // `guard let`, not `if let` — a conditional cast silently *skipped* validation
        // for any non-HTTP response rather than rejecting it, which is precisely the hole
        // the scheme guard above closes from the other side.
        guard let httpResponse = response as? HTTPURLResponse,
              (200..<300).contains(httpResponse.statusCode) else {
            throw URLError(.badServerResponse)
        }

        // Backstop for a server that under-reports or omits `Content-Length`: the
        // delegate's running check is the real guard, this catches a body that slipped
        // past it entirely.
        let downloaded = (try? FileManager.default
            .attributesOfItem(atPath: tempURL.path)[.size] as? Int64) ?? nil
        if let downloaded, downloaded > maxBytes {
            throw URLError(.dataLengthExceedsMaximum)
        }

        // Move to a named temp file with correct extension (RealityKit needs it)
        let ext = remoteURL.pathExtension.isEmpty ? "usdz" : remoteURL.pathExtension
        let namedTempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(ext)
        // Also registered before the operation that creates the file, so a `moveItem`
        // that fails part-way does not leave one behind.
        defer {
            try? FileManager.default.removeItem(at: namedTempURL)
        }
        try FileManager.default.moveItem(at: tempURL, to: namedTempURL)

        return try await load(
            contentsOf: namedTempURL,
            unit: unit,
            enableCollision: enableCollision
        )
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


/// Cancels a download the moment it exceeds a byte ceiling.
///
/// Separate from the `async` call site because `URLSession`'s size information only
/// arrives through the delegate: `download(for:)` hands back the response *after* the
/// body has already landed on disk, which is too late for a cap to mean anything.
private final class SizeCappedDownload: NSObject, URLSessionTaskDelegate,
                                        URLSessionDownloadDelegate, @unchecked Sendable {

    private let maxBytes: Int64

    /// Set when this delegate is the reason the task was cancelled, so the call site can
    /// tell an overflow from a caller-initiated cancellation — both surface as
    /// `URLError.cancelled`.
    private(set) var didExceed = false

    init(maxBytes: Int64) {
        self.maxBytes = maxBytes
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        // Both an announced oversize and a running one. The announcement is a hint a
        // hostile server can omit or lie about, so it is an early-out, not the guard.
        guard totalBytesExpectedToWrite > maxBytes || totalBytesWritten > maxBytes else {
            return
        }
        didExceed = true
        downloadTask.cancel()
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        // Required by the protocol. The `async` overload of `download(for:delegate:)`
        // takes ownership of the file itself, so there is nothing to do here.
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)

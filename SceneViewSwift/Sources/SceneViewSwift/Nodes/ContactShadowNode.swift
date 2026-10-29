#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import CoreGraphics
import Foundation

/// The kind of surface a ``ContactShadowNode`` grounds an object against.
///
/// A contact shadow is not one shape: a lamp on a table casts a tight, crisp patch, while a TV
/// hanging on a wall reads as a soft haze pushed *below* the panel, because indoor light comes
/// from above and grazes the wall. Each context carries the gradient that sells its situation —
/// value for value the presets of Android's `ContactShadowContext` (`contact_shadow.mat`).
///
/// ``center`` and ``radius`` are in the quad's UV space, where `0.5` reaches the quad's edge
/// and `v` points up the quad (toward −Z on a ``ContactShadowNode/Plane/horizontal`` quad,
/// toward +Y on a ``ContactShadowNode/Plane/vertical`` one).
public enum ContactShadowContext: String, CaseIterable, Sendable, Hashable {
    /// An object resting on the floor: a centred, isotropic and fairly dense pool.
    case floor
    /// An object mounted flat against a wall (TV, framed art): fainter, wider than tall and
    /// pushed below the object, where light from the ceiling would put it.
    case wall
    /// A small object on a table or shelf: a tighter, crisper patch than ``floor``.
    case tableTop

    /// Peak opacity at the darkest point of the pool, in `0...1`.
    public var intensity: Float {
        switch self {
        case .floor: 0.55
        case .wall: 0.38
        case .tableTop: 0.60
        }
    }

    /// Offset of the pool centre from the quad centre, in `-0.5...0.5` UV units.
    public var center: SIMD2<Float> {
        switch self {
        case .floor, .tableTop: [0, 0]
        case .wall: [0, -0.10]
        }
    }

    /// Half-axes of the fully dark ellipse, in UV units.
    public var radius: SIMD2<Float> {
        switch self {
        case .floor: [0.30, 0.30]
        case .wall: [0.34, 0.20]
        case .tableTop: [0.26, 0.26]
        }
    }

    /// Width of the fade beyond the ellipse, in units of ``radius``. Small = a hard contact
    /// patch, large = a diffuse ambient-occlusion haze.
    public var softness: Float {
        switch self {
        case .floor: 0.55
        case .wall: 0.85
        case .tableTop: 0.35
        }
    }
}

/// Marks an entity as a contact-shadow quad and stores its settings, so the value-type
/// ``ContactShadowNode`` wrapper stays stateless and the SDK can recognise the decal: it is
/// left out of automatic content framing, never given a `GroundingShadowComponent`, and never
/// counted in the bounds a shadow is fitted to.
struct ContactShadowComponent: Component {
    var context: ContactShadowContext
    var intensity: Float
    /// Added by ``ARSceneView`` for ``ARPlacementShadow/contact(_:intensity:)``, so a later
    /// change of that style retunes these shadows and leaves the app's own ones alone.
    var isPlacementShadow = false
}

/// A procedural contact shadow — the soft dark pool that makes an object read as *touching* a
/// surface instead of floating above it.
///
/// Mirrors SceneView Android's `ContactShadowNode` / `ContactShadow { }`. It draws its own
/// elliptical gradient on a transparent quad: no shadow map, no light, no
/// `GroundingShadowComponent`. So it works at any light angle, on any surface — a wall
/// included, where RealityKit's grounding shadow cannot reach — with a tunable ``intensity``
/// and a preset shape (``ContactShadowContext``).
///
/// **Under a model** (the usual case) — sized from the model's bounds, resting at its lowest
/// point, added as its child so it follows the model and fades with its `OpacityComponent`:
/// ```swift
/// SceneView { root in
///     root.addChild(model.entity)
/// }
/// .task {
///     model = try? await ModelNode.load("models/chair.usdz")
///         .withContactShadow()                     // or .withContactShadow(.tableTop, intensity: 0.4)
/// }
/// ```
///
/// **Free-standing** — a quad you size and place yourself, e.g. on a wall:
/// ```swift
/// let shadow = ContactShadowNode(width: 2.4, length: 1.6, context: .wall, plane: .vertical)
///     .position([0, 1.3, -1.99])                    // a few mm in front of the wall
/// root.addChild(shadow.entity)
/// ```
///
/// The quad is a decal: it never casts or receives a shadow, is never auto-framed by
/// ``SceneView``, and is not a tap target. Size it generously — the gradient fades out well
/// before the quad's edge. Fitted shadows are measured once: call ``fit(under:spread:)`` again
/// after the model's mesh or scale changes.
public struct ContactShadowNode: @unchecked Sendable {

    /// Which plane the quad lies in.
    public enum Plane: Sendable, Hashable {
        /// XZ plane, facing +Y — a floor or a tabletop (Android `normal = Direction(y = 1f)`).
        case horizontal
        /// XY plane, facing +Z — a wall (Android `normal = Direction(z = 1f)`).
        case vertical
    }

    /// The underlying RealityKit entity — a unit quad scaled to ``size``.
    public let entity: ModelEntity

    /// The plane the quad lies in, fixed at creation.
    public let plane: Plane

    /// How far (metres) ``under(_:context:intensity:spread:)`` lifts the quad above the
    /// model's lowest point, so it never z-fights a floor drawn there. Same 5 mm as Android's
    /// `ContactShadowNode.SURFACE_OFFSET_METERS`.
    public static let surfaceOffset: Float = 0.005

    /// How much wider than the model's footprint a fitted quad is. With the ``ContactShadowContext/floor``
    /// radius, the dark core then spans about the footprint and the fade spills just past it.
    /// Same 1.6 as the `ShadowSpread` Android's AR placement sizes its `ContactShadow` with.
    public static let defaultSpread: Float = 1.6

    /// The smallest side (metres, in world space) of a fitted quad, so a thin or tiny model still
    /// gets a visible pool. Same 0.1 m floor as Android's AR placement shadow.
    public static let minimumFittedSize: Float = 0.1

    /// Creates a free-standing contact shadow.
    ///
    /// - Parameters:
    ///   - width: Quad size along X, in metres.
    ///   - length: Quad size along Z for a ``Plane/horizontal`` quad, along Y for a
    ///     ``Plane/vertical`` one.
    ///   - context: The gradient preset.
    ///   - plane: The plane the quad lies in. Default ``Plane/horizontal``.
    ///   - intensity: Peak opacity in `0...1`; `nil` uses the context's
    ///     ``ContactShadowContext/intensity``.
    @MainActor
    public init(
        width: Float = 1,
        length: Float = 1,
        context: ContactShadowContext = .floor,
        plane: Plane = .horizontal,
        intensity: Float? = nil
    ) {
        let mesh: MeshResource
        switch plane {
        case .horizontal: mesh = .generatePlane(width: 1, depth: 1)
        case .vertical: mesh = .generatePlane(width: 1, height: 1)
        }
        let entity = ModelEntity(mesh: mesh)
        entity.name = "contact-shadow"
        self.entity = entity
        self.plane = plane
        if #available(iOS 18.0, macOS 15.0, visionOS 2.0, *) {
            // A decal must not throw a real shadow of its own quad.
            entity.components.set(DynamicLightShadowComponent(castsShadow: false))
        }
        size = [width, length]
        apply(ContactShadowComponent(
            context: context,
            intensity: Self.clampedIntensity(intensity ?? context.intensity)
        ))
    }

    /// Wraps an entity previously created by ``ContactShadowNode``; `nil` for any other entity.
    @MainActor
    public init?(_ entity: Entity) {
        guard let model = entity as? ModelEntity,
              model.components.has(ContactShadowComponent.self) else { return nil }
        self.entity = model
        let extents = model.model?.mesh.bounds.extents ?? .zero
        self.plane = extents.y > extents.z ? .vertical : .horizontal
    }

    // MARK: - Properties

    /// The gradient preset. Setting it rebuilds the material.
    @MainActor
    public var context: ContactShadowContext {
        get { component.context }
        nonmutating set {
            var settings = component
            settings.context = newValue
            apply(settings)
        }
    }

    /// Peak opacity of the pool, clamped to `0...1`. Setting it rebuilds the material.
    @MainActor
    public var intensity: Float {
        get { component.intensity }
        nonmutating set {
            var settings = component
            settings.intensity = Self.clampedIntensity(newValue)
            apply(settings)
        }
    }

    /// Quad size in metres (in the parent's units): `x` is the width, `y` the length along Z
    /// (``Plane/horizontal``) or the height along Y (``Plane/vertical``).
    public var size: SIMD2<Float> {
        get {
            switch plane {
            case .horizontal: [entity.scale.x, entity.scale.z]
            case .vertical: [entity.scale.x, entity.scale.y]
            }
        }
        nonmutating set {
            let w = Self.positive(newValue.x)
            let l = Self.positive(newValue.y)
            switch plane {
            case .horizontal: entity.scale = [w, 1, l]
            case .vertical: entity.scale = [w, l, 1]
            }
        }
    }

    /// Position relative to the parent.
    public var position: SIMD3<Float> {
        get { entity.position }
        nonmutating set { entity.position = newValue }
    }

    // MARK: - Builders

    /// Sets the position relative to the parent and returns self for chaining.
    @discardableResult
    public func position(_ position: SIMD3<Float>) -> ContactShadowNode {
        entity.position = position
        return self
    }

    /// Sets the quad size and returns self for chaining. See ``size``.
    @discardableResult
    public func size(width: Float, length: Float) -> ContactShadowNode {
        size = [width, length]
        return self
    }

    /// Sets the peak opacity and returns self for chaining.
    @MainActor @discardableResult
    public func intensity(_ value: Float) -> ContactShadowNode {
        intensity = value
        return self
    }

    /// Sets the gradient preset and returns self for chaining.
    @MainActor @discardableResult
    public func context(_ value: ContactShadowContext) -> ContactShadowNode {
        context = value
        return self
    }

    // MARK: - Fitting to a model

    /// Creates a horizontal contact shadow under `model` and adds it as the model's child.
    ///
    /// The quad is centred under the model's footprint, sized from its bounds (times
    /// `spread`, at least ``minimumFittedSize``) and rests at its lowest point, lifted by
    /// ``surfaceOffset``. It stays level: it follows the model's turn around the vertical axis
    /// but not a tilt of its root (a Z-up asset's correction, say). As a child it follows the
    /// model and fades with any `OpacityComponent` on it. It is measured now: call this once
    /// the model's mesh is loaded, after `scaleToUnits` / `centerOrigin`.
    ///
    /// - Parameters:
    ///   - model: The entity to ground. Its other contact shadows are not counted in its bounds.
    ///   - context: The gradient preset. Default ``ContactShadowContext/floor``.
    ///   - intensity: Peak opacity in `0...1`; `nil` uses the context's.
    ///   - spread: Quad size relative to the footprint. Default ``defaultSpread``.
    /// - Returns: The shadow, already added to `model`.
    @MainActor @discardableResult
    public static func under(
        _ model: Entity,
        context: ContactShadowContext = .floor,
        intensity: Float? = nil,
        spread: Float = defaultSpread
    ) -> ContactShadowNode {
        let shadow = ContactShadowNode(context: context, intensity: intensity)
        model.addChild(shadow.entity)
        shadow.fit(under: model, spread: spread)
        return shadow
    }

    /// Re-measures `model` and moves, turns and resizes this shadow under it, in the shadow's
    /// parent space. The model is measured in a level frame (the parent's world transform
    /// without its tilt), so the quad lies flat even under a tilted root. A root that is both
    /// tilted and non-uniformly scaled is measured as if its scale were uniform. Does nothing while
    /// `model` has no visible geometry yet (an async load in flight).
    @MainActor
    public func fit(under model: Entity, spread: Float = defaultSpread) {
        let reference = entity.parent ?? model
        let worldOrientation = reference.orientation(relativeTo: nil)
        let level = Self.yaw(of: worldOrientation)
        let worldScale = reference.scale(relativeTo: nil)
        let frame = Entity()
        frame.transform = Transform(scale: worldScale, rotation: level,
                                    translation: reference.position(relativeTo: nil))
        guard let bounds = model.contactShadowFreeBounds(relativeTo: frame) else { return }
        let lift = Self.surfaceOffset / Self.finiteScale(worldScale.y)
        let minimum = SIMD2<Float>(Self.minimumFittedSize / Self.finiteScale(worldScale.x),
                                   Self.minimumFittedSize / Self.finiteScale(worldScale.z))
        let footprint = Self.footprint(of: bounds, spread: spread, lift: lift, minimum: minimum)
        // The level frame differs from the parent only by the parent's tilt: undo it.
        var untilt = worldOrientation.inverse * level
        if abs(untilt.real) > 0.999_999 { untilt = simd_quatf(ix: 0, iy: 0, iz: 0, r: 1) }
        entity.orientation = untilt
        entity.position = untilt.act(footprint.position)
        size = footprint.size
    }

    // MARK: - Internals

    @MainActor
    private var component: ContactShadowComponent {
        entity.components[ContactShadowComponent.self]
            ?? ContactShadowComponent(context: .floor, intensity: ContactShadowContext.floor.intensity)
    }

    @MainActor
    private func apply(_ settings: ContactShadowComponent) {
        entity.components.set(settings)
        entity.model?.materials = [Self.material(settings.context, intensity: settings.intensity)]
    }

    /// Sets the preset and the peak opacity together, rebuilding the material once.
    @MainActor
    func retune(context: ContactShadowContext, intensity: Float) {
        var settings = component
        settings.context = context
        settings.intensity = Self.clampedIntensity(intensity)
        apply(settings)
    }

    /// Where a fitted quad goes and how big it is, from the bounds it grounds (in the level
    /// frame it is measured in): centred under the footprint, `lift` above its lowest point,
    /// `spread` times its X/Z extents but at least `minimum`, never degenerate.
    static func footprint(
        of bounds: BoundingBox,
        spread: Float,
        lift: Float,
        minimum: SIMD2<Float> = .zero
    ) -> (position: SIMD3<Float>, size: SIMD2<Float>) {
        let center = bounds.center
        let factor = spread.isFinite && spread > 0 ? spread : defaultSpread
        let extents = bounds.extents
        let floor = SIMD2<Float>(minimum.x.isFinite ? minimum.x : 0, minimum.y.isFinite ? minimum.y : 0)
        return (
            position: [center.x, bounds.min.y + (lift.isFinite ? lift : 0), center.z],
            size: [positive(max(extents.x * factor, floor.x)), positive(max(extents.z * factor, floor.y))]
        )
    }

    /// The turn of `orientation` around the vertical axis alone, without any tilt.
    static func yaw(of orientation: simd_quatf) -> simd_quatf {
        let forward = orientation.act([0, 0, -1])
        let angle: Float
        if forward.x * forward.x + forward.z * forward.z > 1e-6 {
            angle = atan2(-forward.x, -forward.z)
        } else {
            // Facing straight up or down: the right vector still lies in the horizontal plane.
            let right = orientation.act([1, 0, 0])
            angle = atan2(-right.z, right.x)
        }
        return simd_quatf(angle: angle, axis: [0, 1, 0])
    }

    static func clampedIntensity(_ value: Float) -> Float {
        value.isFinite ? value.clamped(to: 0...1) : 0
    }

    /// Floors a size at 1 mm so a flat or empty footprint never collapses the quad (a zero
    /// scale makes RealityKit drop the entity and its bounds).
    static func positive(_ value: Float) -> Float {
        value.isFinite ? max(value, 0.001) : 0.001
    }

    private static func finiteScale(_ value: Float) -> Float {
        let scale = abs(value)
        return scale.isFinite && scale > 1e-6 ? scale : 1
    }

    /// One texture per preset, shared by every shadow.
    @MainActor private static var masks: [ContactShadowContext: TextureResource] = [:]

    @MainActor
    static func material(_ context: ContactShadowContext, intensity: Float) -> RealityKit.Material {
        var material = UnlitMaterial(color: .black)
        if let mask = mask(context) {
            material.blending = .transparent(opacity: .init(scale: intensity, texture: .init(mask)))
        } else {
            material.blending = .transparent(opacity: .init(floatLiteral: 0))
        }
        return material
    }

    @MainActor
    private static func mask(_ context: ContactShadowContext) -> TextureResource? {
        if let cached = masks[context] { return cached }
        guard let image = ContactShadowMask.image(context),
              let texture = try? TextureResource(image: image, options: .init(semantic: .raw))
        else { return nil }
        masks[context] = texture
        return texture
    }
}

// MARK: - Mask math

/// The pool's opacity, as Android's `contact_shadow.mat` computes it per fragment — here baked
/// once per preset into a small texture, since RealityKit has no custom fragment shader on
/// every platform SceneViewSwift targets.
enum ContactShadowMask {
    /// Width of the fade at the quad's border, in UV units.
    static let edgeFade: Float = 0.08

    /// Opacity before intensity at `(u, v)` in `0...1`, `v` pointing up the quad: a soft
    /// ellipse, faded at the quad's edges so it never ends on a hard line.
    static func alpha(u: Float, v: Float, context: ContactShadowContext) -> Float {
        let p = SIMD2<Float>(u, v) - 0.5 - context.center
        let d = simd_length(p / context.radius)
        var a = 1 - smoothstep(1, 1 + context.softness, d)
        a *= smoothstep(0, edgeFade, min(min(u, v), min(1 - u, 1 - v)))
        return a
    }

    static func smoothstep(_ edge0: Float, _ edge1: Float, _ x: Float) -> Float {
        let t = ((x - edge0) / (edge1 - edge0)).clamped(to: 0...1)
        return t * t * (3 - 2 * t)
    }

    /// The preset's opacity mask as a single grey channel. Not RGBA: RealityKit un-premultiplies
    /// an alpha image on load, which turns every non-zero texel white and the falloff into a
    /// hard, texel-stepped cut-out.
    static func image(_ context: ContactShadowContext, size: Int = 128) -> CGImage? {
        guard size > 0 else { return nil }
        var pixels = [UInt8](repeating: 0, count: size * size)
        for row in 0..<size {
            // Row 0 is the top of the image, which RealityKit maps to the top of the plane.
            let v = 1 - (Float(row) + 0.5) / Float(size)
            for column in 0..<size {
                let u = (Float(column) + 0.5) / Float(size)
                pixels[row * size + column] = UInt8((alpha(u: u, v: v, context: context) * 255).rounded())
            }
        }
        guard let provider = CGDataProvider(data: Data(pixels) as CFData) else { return nil }
        return CGImage(width: size, height: size, bitsPerComponent: 8, bitsPerPixel: 8,
                       bytesPerRow: size, space: CGColorSpaceCreateDeviceGray(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true,
                       intent: .defaultIntent)
    }
}

// MARK: - Bounds without the decal

extension Entity {
    /// The visual bounds of this entity's subtree relative to `reference`, leaving out every
    /// contact-shadow quad (and anything under one). `nil` while nothing has visible geometry.
    func contactShadowFreeBounds(relativeTo reference: Entity?) -> BoundingBox? {
        Self.contactShadowFreeBounds(of: [self], relativeTo: reference)
    }

    /// The union of the visual bounds of `roots` and their descendants relative to
    /// `reference`, leaving out every contact-shadow quad (and anything under one). `nil` while
    /// nothing has visible geometry. ``SceneView``'s automatic framing measures its content
    /// with it.
    ///
    /// Walks the subtrees with non-recursive `visualBounds` (same union, since every
    /// descendant is visited): a recursive query on an ancestor would fold the shadow's own
    /// quad — wider than the model by design — back in. Empty or non-finite boxes (an
    /// entity without a mesh) are skipped.
    static func contactShadowFreeBounds(of roots: [Entity], relativeTo reference: Entity?) -> BoundingBox? {
        var accumulator = ContentBounds.Accumulator()
        var stack = roots
        while let entity = stack.popLast() {
            if entity.components.has(ContactShadowComponent.self) { continue }
            stack.append(contentsOf: entity.children)
            accumulator.add(entity.visualBounds(recursive: false, relativeTo: reference))
        }
        return accumulator.result
    }

    /// Whether this entity's subtree holds a contact-shadow quad.
    var containsContactShadow: Bool {
        var stack: [Entity] = [self]
        while let entity = stack.popLast() {
            if entity.components.has(ContactShadowComponent.self) { return true }
            stack.append(contentsOf: entity.children)
        }
        return false
    }

    /// `visualBounds(relativeTo:)` without the contact-shadow quads in the subtree, so sizing
    /// and centring code measures the model rather than its wider decal. Exactly
    /// `visualBounds(relativeTo:)` when the subtree has no contact shadow.
    func visualBoundsExcludingContactShadows(relativeTo reference: Entity?) -> BoundingBox {
        guard containsContactShadow else { return visualBounds(relativeTo: reference) }
        return contactShadowFreeBounds(relativeTo: reference) ?? visualBounds(relativeTo: reference)
    }

    /// `generateCollisionShapes(recursive: true)`, then strips the collision shapes it gave
    /// contact-shadow quads: a decal is not a tap target.
    func generateCollisionShapesExcludingContactShadows() {
        generateCollisionShapes(recursive: true)
        var stack: [Entity] = [self]
        while let entity = stack.popLast() {
            if entity.components.has(ContactShadowComponent.self) {
                var shadowTree: [Entity] = [entity]
                while let part = shadowTree.popLast() {
                    part.components.remove(CollisionComponent.self)
                    shadowTree.append(contentsOf: part.children)
                }
                continue
            }
            stack.append(contentsOf: entity.children)
        }
    }
}

// MARK: - ModelNode convenience

extension ModelNode {
    /// Grounds the model with a procedural contact shadow sized from its bounds and resting at
    /// its lowest point — see ``ContactShadowNode/under(_:context:intensity:spread:)``. Call it
    /// last, after `scaleToUnits` / `centerOrigin`, which measure the model's bounds.
    ///
    /// Unlike ``withGroundingShadow()``, the pool has a tunable intensity and needs no light.
    @MainActor @discardableResult
    public func withContactShadow(
        _ context: ContactShadowContext = .floor,
        intensity: Float? = nil,
        spread: Float = ContactShadowNode.defaultSpread
    ) -> ModelNode {
        ContactShadowNode.under(entity, context: context, intensity: intensity, spread: spread)
        return self
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)

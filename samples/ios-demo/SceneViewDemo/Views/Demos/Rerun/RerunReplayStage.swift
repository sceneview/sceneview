#if os(iOS)
import Metal
import RealityKit
import SwiftUI
import UIKit

/// The replay's 3D view: the recorded room drawn in RealityKit from the orbit camera.
///
/// SceneViewSwift's `SceneView` owns its camera and has no per-frame hook, and this view needs
/// both — the room is rebuilt as the playhead moves and the camera cranes in then drifts — so
/// it hosts its own `RealityView`, the same way the library does under the hood.
///
/// `compact` is the camera view's picture-in-picture: no touch, smaller points, no lift.
struct RerunReplayStage: View {
    let session: RerunReplaySession
    var compact = false
    /// The map's near-vertical view.
    var overhead = false
    /// Bumped to hand the camera back to the automatic framing (the 3D button tapped again).
    var recenterToken = 0
    /// The slow turntable drift and the crane-in intro. Off in QA so captures are deterministic.
    var drift = true

    @Environment(\.displayScale) private var displayScale
    @State private var renderer = RerunStageRenderer()

    var body: some View {
        GeometryReader { proxy in
            RealityView { content in
                content.camera = .virtual
                renderer.install(in: &content, session: session, compact: compact, drift: drift, overhead: overhead)
            }
            .onAppear { renderer.resize(proxy.size, scale: displayScale) }
            .onChange(of: proxy.size) { _, size in renderer.resize(size, scale: displayScale) }
        }
        .background(SceneViewTokens.Stage.background)
        .onChange(of: overhead) { _, value in renderer.setOverhead(value) }
        .onChange(of: recenterToken) { _, _ in renderer.recenter() }
        .modifier(OrbitGestures(enabled: !compact, renderer: renderer, displayScale: displayScale))
        .accessibilityElement()
        .accessibilityLabel("Recorded room in 3D")
        .accessibilityHint(compact ? "" : "Drag to orbit, pinch to zoom, double-tap to recenter.")
    }
}

/// One finger orbits (with inertia), a pinch zooms, a double tap recenters.
private struct OrbitGestures: ViewModifier {
    let enabled: Bool
    let renderer: RerunStageRenderer
    let displayScale: CGFloat
    @State private var lastTranslation: CGSize?

    func body(content: Content) -> some View {
        if enabled {
            content
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 2)
                        .onChanged { value in
                            let last = lastTranslation ?? .zero
                            if lastTranslation == nil { renderer.orbit.dragBegan() }
                            lastTranslation = value.translation
                            renderer.orbit.dragged(dx: Float((value.translation.width - last.width) * displayScale),
                                                   dy: Float((value.translation.height - last.height) * displayScale))
                        }
                        .onEnded { _ in
                            lastTranslation = nil
                            renderer.orbit.dragEnded()
                        }
                )
                .simultaneousGesture(
                    MagnifyGesture()
                        .onChanged { value in renderer.orbit.pinched(magnification: Float(value.magnification)) }
                        .onEnded { _ in renderer.orbit.pinchEnded() }
                )
                .onTapGesture(count: 2) { renderer.recenter() }
        } else {
            content.allowsHitTesting(false)
        }
    }
}

/// Everything the stage draws, kept in step with the session once per rendered frame. Each part
/// is rebuilt only when its key — the inputs its geometry depends on — changes.
@MainActor
final class RerunStageRenderer {
    /// Photos under the grid; flat layers by Android's paint priorities; photos over frustums.
    private enum Order {
        static let planePhoto: Int32 = 0
        static let grid: Int32 = 10
        static let fill: Int32 = 11
        static let outline: Int32 = 20
        static let shadow: Int32 = 21
        static let points: Int32 = 30
        static let keyframes: Int32 = 31
        static let line: Int32 = 40
        static let photo: Int32 = 50
        static let live: Int32 = 60
    }

    static let frameInterval: Double = 0.05
    static let statsInterval: Double = 0.25
    static let anchorModelSize: Float = 0.3
    /// The main stage lifts the room into the clear band between the HUD and the filmstrip.
    static let mainLift: Float = 0.07
    /// The picture-in-picture packs the room into a few hundred pixels: points shrink there.
    static let pipPointScale: Float = 0.55

    var orbit = RerunOrbitController()

    private weak var session: RerunReplaySession?
    private var compact = false
    private var viewport = CGSize(width: 1, height: 1)
    private var scale: CGFloat = 3
    private lazy var root = Entity()
    private lazy var camera = PerspectiveCamera()
    private var subscription: EventSubscription?
    private lazy var sortGroup = ModelSortGroup(depthPass: nil)

    private var layers: [RerunLayer: ModelEntity] = [:]
    private var planePhotos: [Int: ModelEntity] = [:]
    private var points: ModelEntity?
    private var shadow: ModelEntity?
    private var photoSlots: [PhotoSlot] = []
    private var frameTextures: [String: TextureResource] = [:]
    private var imageQuads: [Float: MeshResource] = [:]
    private var shibaTemplate: Entity?
    private var shibas: [Int: Entity] = [:]
    private var keys: [String: Any] = [:]

    private var frame: RerunFrame?
    private var clock: Double = 0
    private var frameAt: Double = -1
    private var statsAt: Double = -1
    private var fpsMeter = RerunFpsMeter()

    // MARK: Lifecycle

    func install(in content: inout RealityViewCameraContent, session: RerunReplaySession,
                 compact: Bool, drift: Bool, overhead: Bool) {
        self.session = session
        self.compact = compact
        orbit = RerunOrbitController(drift: drift)
        orbit.overhead = overhead

        camera.components.set(PerspectiveCameraComponent(near: 0.02, far: 200,
                                                         fieldOfViewInDegrees: RerunFraming.verticalFov,
                                                         fieldOfViewOrientation: .vertical))
        root.addChild(camera)
        buildEntities(session)
        content.add(root)
        subscription = content.subscribe(to: SceneEvents.Update.self) { [weak self] event in
            MainActor.assumeIsolated { self?.update(Float(event.deltaTime)) }
        }
        update(0)
        Task { @MainActor [weak self] in
            guard let url = Bundle.main.url(forResource: "shiba", withExtension: "usdz"),
                  let model = try? await Entity(contentsOf: url) else { return }
            self?.adoptShiba(model)
        }
    }

    func resize(_ size: CGSize, scale: CGFloat) {
        guard size.width > 0, size.height > 0 else { return }
        viewport = size
        self.scale = scale
    }

    func setOverhead(_ value: Bool) { orbit.overhead = value }

    func recenter() { orbit.recenter() }

    private var heightPixels: Float { Float(viewport.height * scale) }

    // MARK: Frame

    private func update(_ delta: Float) {
        guard let session else { return }
        clock += Double(delta)
        session.tick(delta)
        if fpsMeter.tick(clock) { session.fps = fpsMeter.fps }

        let time = session.time
        let current: RerunFrame
        if let frame, frame.time == time || clock - frameAt < Self.frameInterval {
            current = frame
        } else {
            current = session.pack.trace.frameAt(time)
            frame = current
            frameAt = clock
        }

        // A recording is framed whole from its first frame: the camera and the grid hold still
        // while it plays.
        let whole = session.whole
        let bounds = RerunGeometry.contentBounds(whole)
        let aspect = Float(viewport.width / max(viewport.height, 1))
        let home = RerunFraming.home(bounds: bounds, azimuth: orbit.home.azimuth, aspect: aspect,
                                     elevation: orbit.homeElevation, margin: RerunFraming.replayMargin)
        if orbit.following { orbit.home = home }
        if !orbit.hasFramedContent, bounds != nil {
            orbit.hasFramedContent = true
            if orbit.drift { orbit.playIntro(from: RerunIntro.start(for: home)) } else { orbit.snap(to: home) }
        }
        orbit.update(delta: delta)
        let lift = compact ? 0 : Self.mainLift
        let (eye, target) = orbit.eyeAndTarget(lift: lift, heightPixels: heightPixels)
        camera.look(at: target, from: eye, relativeTo: nil)

        let style = RerunStyle.forOrbit(distance: orbit.pose.distance, verticalFov: RerunFraming.verticalFov,
                                        heightPixels: heightPixels)
        let pointStyle = compact ? RerunStyle(metresPerPixel: style.metresPerPixel * Self.pipPointScale) : style
        let floorY = RerunGeometry.floorHeight(whole)
        sync(current, whole: whole, style: style, pointStyle: pointStyle, floorY: floorY, session: session)

        if clock - statsAt >= Self.statsInterval {
            statsAt = clock
            let stats = RerunStats(frame: current)
            if stats != session.stats { session.stats = stats }
        }
    }

    private func changed<K: Equatable>(_ name: String, _ key: K) -> Bool {
        if let old = keys[name] as? K, old == key { return false }
        keys[name] = key
        return true
    }

    private func sync(_ frame: RerunFrame, whole: RerunFrame, style: RerunStyle, pointStyle: RerunStyle,
                      floorY: Float, session: RerunReplaySession) {
        let lens = session.pack.manifest.lens
        let mpp = style.metresPerPixel
        var out: [RerunLayer: RerunMesh] = [:]
        var touched: [RerunLayer] = []

        let stage = RerunGeometry.stageBounds(whole)
        if changed("stage", [stage.0, stage.1, SIMD3(floorY, mpp, 0)]) {
            RerunGeometry.buildStage(bounds: stage, y: floorY, style: style, into: &out)
            touched += [.gridMinor, .gridMajor, .axisX, .axisY, .axisZ]
        }
        if session.isVisible(.planes), changed("planes", Key(frame.planes, mpp)) {
            RerunGeometry.buildPlanes(frame.planes, style: style, textured: { self.planePhotos[$0] != nil }, into: &out)
            touched += [.planeFloor, .planeWall, .planeOther, .outlineFloor, .outlineWall, .outlineOther]
        }
        if session.isVisible(.points), changed("live", Key([frame.liveKey, frame.livePoints.count], pointStyle.metresPerPixel)) {
            var live = RerunMesh()
            RerunGeometry.buildLivePoints(frame.livePoints, style: pointStyle, into: &live)
            out[.livePoints] = live
            touched.append(.livePoints)
        }
        if session.isVisible(.trail) {
            if changed("trail", Key(frame.trail.count, mpp)) {
                RerunGeometry.buildTrail(frame.trail, style: style, into: &out)
                touched += RerunLayer.trailSteps + [.trailHead]
            }
            if changed("camera", Key(frame.camera, SIMD2(Float(frame.keyframes.count), mpp))) {
                RerunGeometry.buildCamera(frame, style: style, lens: lens, into: &out)
                touched += [.keyframes, .frustum]
            }
        }
        if session.isVisible(.anchors), changed("anchors", Key(frame.anchors, mpp)) {
            var rings = RerunMesh()
            RerunGeometry.buildAnchors(frame.anchors, style: style, into: &rings)
            out[.anchors] = rings
            touched.append(.anchors)
        }
        for layer in touched { upload(out[layer], to: layers[layer]) }
        for (layer, entity) in layers {
            let visible = layer.group.map(session.isVisible) ?? true
            entity.isEnabled = visible && hasMesh(entity)
        }

        syncPlanePhotos(frame, floorY: floorY, shown: session.isVisible(.planes), session: session)
        syncPoints(frame, style: pointStyle, shown: session.isVisible(.points))
        syncAnchors(frame, shown: session.isVisible(.anchors))
        syncPhotos(frame, lens: lens, shown: session.isVisible(.trail), session: session)
    }

    private var meshed: Set<ObjectIdentifier> = []

    private func hasMesh(_ entity: ModelEntity) -> Bool { meshed.contains(ObjectIdentifier(entity)) }

    private func upload(_ mesh: RerunMesh?, to entity: ModelEntity?) {
        guard let entity else { return }
        guard let mesh, let resource = Self.resource(mesh) else {
            meshed.remove(ObjectIdentifier(entity))
            entity.isEnabled = false
            return
        }
        entity.model?.mesh = resource
        meshed.insert(ObjectIdentifier(entity))
    }

    private func syncPlanePhotos(_ frame: RerunFrame, floorY: Float, shown: Bool, session: RerunReplaySession) {
        for (id, entity) in planePhotos {
            guard shown, let plane = frame.planes.first(where: { $0.id == id }),
                  let texture = session.pack.manifest.texture(for: id) else {
                entity.isEnabled = false
                continue
            }
            if changed("plane-\(id)", Key(plane, floorY)) {
                var mesh = RerunMesh()
                let flatten = plane.kind == .floor ? floorY - RerunGeometry.floorUnderGrid : nil
                RerunGeometry.addTexturedPlane(&mesh, plane.polygon, texture: texture, flattenToY: flatten)
                upload(mesh, to: entity)
            }
            entity.isEnabled = hasMesh(entity)
        }
    }

    private func syncPoints(_ frame: RerunFrame, style: RerunStyle, shown: Bool) {
        guard let points else { return }
        guard shown else {
            points.isEnabled = false
            return
        }
        if changed("points", Key(frame.mapPointCount, style.metresPerPixel)) {
            var mesh = RerunMesh()
            RerunGeometry.addColoredPoints(&mesh, frame.mapPoints, range: 0..<frame.mapPointCount,
                                           radius: style.mapPointRadius * RerunGeometry.pointScale)
            upload(mesh, to: points)
        }
        points.isEnabled = hasMesh(points)
    }

    private func syncAnchors(_ frame: RerunFrame, shown: Bool) {
        let anchors = shown ? frame.anchors : []
        if let shadow {
            if changed("shadow", anchors) {
                var mesh = RerunMesh()
                for anchor in anchors {
                    RerunGeometry.addShadow(&mesh, anchor.pose.position + SIMD3(0, RerunGeometry.shadowLift, 0),
                                            radius: RerunGeometry.shadowRadius)
                }
                upload(mesh, to: shadow)
            }
            shadow.isEnabled = !anchors.isEmpty && hasMesh(shadow)
        }
        let ids = Set(anchors.map(\.id))
        for (id, dog) in shibas where !ids.contains(id) {
            dog.removeFromParent()
            shibas[id] = nil
        }
        guard let template = shibaTemplate else { return }
        for anchor in anchors {
            let dog = shibas[anchor.id] ?? {
                let clone = template.clone(recursive: true)
                root.addChild(clone)
                shibas[anchor.id] = clone
                return clone
            }()
            dog.transform = Transform(scale: .one, rotation: anchor.pose.rotation, translation: anchor.pose.position)
        }
    }

    private func syncPhotos(_ frame: RerunFrame, lens: RerunLens, shown: Bool, session: RerunReplaySession) {
        var shots: [(RerunPose, String?, Float)] = []
        for (i, pose) in frame.keyframes.enumerated() {
            shots.append((pose, i < frame.keyframeImages.count ? frame.keyframeImages[i] : nil, RerunGeometry.keyframeDepth))
        }
        if let camera = frame.camera { shots.append((camera, frame.image, RerunGeometry.frustumDepth)) }
        for (i, slot) in photoSlots.enumerated() {
            guard shown, i < shots.count, let path = shots[i].1, let texture = frameTexture(path, session: session) else {
                slot.entity.isEnabled = false
                continue
            }
            let (pose, _, depth) = shots[i]
            if slot.path != path {
                slot.path = path
                var material = Self.photoMaterial(texture)
                material.faceCulling = .none
                slot.entity.model?.materials = [material]
            }
            if slot.depth != depth, let quad = imageQuad(depth: depth, lens: lens) {
                slot.depth = depth
                slot.entity.model?.mesh = quad
            }
            slot.entity.transform = Transform(scale: .one, rotation: pose.rotation, translation: pose.position)
            slot.entity.isEnabled = true
        }
    }

    private func imageQuad(depth: Float, lens: RerunLens) -> MeshResource? {
        if let quad = imageQuads[depth] { return quad }
        var mesh = RerunMesh()
        RerunGeometry.addImageQuad(&mesh, RerunPose(position: .zero), depth: depth, lens: lens)
        let quad = Self.resource(mesh)
        imageQuads[depth] = quad
        return quad
    }

    private func frameTexture(_ path: String, session: RerunReplaySession) -> TextureResource? {
        if let texture = frameTextures[path] { return texture }
        guard let image = session.media.thumbnails[path], let texture = Self.texture(image) else { return nil }
        frameTextures[path] = texture
        return texture
    }

    // MARK: Entities

    private final class PhotoSlot {
        let entity: ModelEntity
        var path: String?
        var depth: Float = -1
        init(entity: ModelEntity) { self.entity = entity }
    }

    private func buildEntities(_ session: RerunReplaySession) {
        for layer in RerunLayer.allCases {
            let entity = makeEntity(Self.flatMaterial(Self.paint(layer)), order: Self.order(layer))
            layers[layer] = entity
        }
        for (id, image) in session.media.planeImages {
            guard let texture = Self.texture(image) else { continue }
            planePhotos[id] = makeEntity(Self.photoMaterial(texture), order: Order.planePhoto)
        }
        if let atlas = Self.atlasTexture(session.whole) {
            var material = UnlitMaterial(applyPostProcessToneMap: false)
            material.color = .init(tint: .white, texture: .init(atlas, sampler: Self.nearest))
            material.faceCulling = .none
            points = makeEntity(material, order: Order.points)
        }
        if let disc = Self.shadowImage().flatMap(Self.texture) {
            var material = UnlitMaterial(applyPostProcessToneMap: false)
            material.color = .init(tint: .white, texture: .init(disc))
            material.blending = .transparent(opacity: .init(floatLiteral: 1))
            material.writesDepth = false
            material.faceCulling = .none
            shadow = makeEntity(material, order: Order.shadow)
        }
        let slots = session.whole.keyframes.count + 2
        photoSlots = (0..<slots).map { _ in
            PhotoSlot(entity: makeEntity(Self.flatMaterial(0xFF00_0000), order: Order.photo))
        }
    }

    private func makeEntity(_ material: some RealityKit.Material, order: Int32) -> ModelEntity {
        let entity = ModelEntity(mesh: Self.placeholder, materials: [material])
        entity.components.set(ModelSortGroupComponent(group: sortGroup, order: order))
        entity.isEnabled = false
        root.addChild(entity)
        return entity
    }

    private func adoptShiba(_ model: Entity) {
        // Fitted to 30 cm on its largest side, feet on the anchor, like Android's `scaleToUnits`.
        let bounds = model.visualBounds(relativeTo: nil)
        let largest = max(bounds.extents.x, bounds.extents.y, bounds.extents.z)
        guard largest > 0 else { return }
        let fit = Self.anchorModelSize / largest
        model.scale = SIMD3(repeating: fit)
        model.position = SIMD3(-bounds.center.x * fit, -bounds.min.y * fit, -bounds.center.z * fit)
        let holder = Entity()
        holder.addChild(model)
        shibaTemplate = holder
        keys["shadow"] = nil
    }

    // MARK: Resources

    private static let placeholder: MeshResource = MeshResource.generatePlane(width: 0.001, depth: 0.001)

    private static let nearest: MaterialParameters.Texture.Sampler = {
        let descriptor = MTLSamplerDescriptor()
        descriptor.minFilter = .nearest
        descriptor.magFilter = .nearest
        descriptor.mipFilter = .notMipmapped
        descriptor.sAddressMode = .clampToEdge
        descriptor.tAddressMode = .clampToEdge
        return MaterialParameters.Texture.Sampler(descriptor)
    }()

    /// RealityKit samples textures from the bottom-left; the shared geometry writes them from
    /// the top-left like Filament, so v is flipped here, once.
    static func resource(_ mesh: RerunMesh) -> MeshResource? {
        guard !mesh.isEmpty else { return nil }
        var descriptor = MeshDescriptor()
        descriptor.positions = MeshBuffers.Positions(mesh.positions)
        if mesh.uvs.count == mesh.positions.count {
            descriptor.textureCoordinates = MeshBuffers.TextureCoordinates(mesh.uvs.map { SIMD2($0.x, 1 - $0.y) })
        }
        descriptor.primitives = .triangles(mesh.indices)
        return try? MeshResource.generate(from: [descriptor])
    }

    static func texture(_ image: CGImage) -> TextureResource? {
        try? TextureResource(image: image, withName: nil, options: .init(semantic: .color))
    }

    static func uiColor(_ argb: UInt32, opaque: Bool = false) -> UIColor {
        UIColor(red: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255,
                blue: CGFloat(argb & 0xFF) / 255, alpha: opaque ? 1 : CGFloat((argb >> 24) & 0xFF) / 255)
    }

    /// Unlit and not tone-mapped, so each layer shows its token colour exactly.
    static func flatMaterial(_ argb: UInt32) -> UnlitMaterial {
        var material = UnlitMaterial(applyPostProcessToneMap: false)
        material.color = .init(tint: uiColor(argb, opaque: true))
        let alpha = Float((argb >> 24) & 0xFF) / 255
        if alpha < 1 {
            material.blending = .transparent(opacity: .init(floatLiteral: alpha))
            material.writesDepth = false
        }
        material.faceCulling = .none
        return material
    }

    static func photoMaterial(_ texture: TextureResource) -> UnlitMaterial {
        var material = UnlitMaterial(applyPostProcessToneMap: false)
        material.color = .init(tint: .white, texture: .init(texture))
        material.faceCulling = .none
        return material
    }

    /// Colour of each flat layer: `SceneViewTokens.DebugView`, the trail on the brand ramp.
    static func paint(_ layer: RerunLayer) -> UInt32 {
        typealias T = SceneViewTokens.DebugView
        switch layer {
        case .gridMinor: return T.gridMinor
        case .gridMajor: return T.gridMajor
        case .axisX: return T.axisX
        case .axisY: return T.axisY
        case .axisZ: return T.axisZ
        case .planeFloor: return T.floorFill
        case .planeWall: return T.wallFill
        case .planeOther: return T.otherFill
        case .outlineFloor: return T.floorOutline
        case .outlineWall: return T.wallOutline
        case .outlineOther: return T.otherOutline
        case .livePoints: return T.livePoint
        case .trailHead: return T.trailNew
        case .keyframes: return T.keyframe
        case .frustum: return T.frustum
        case .anchors: return T.anchor
        default:
            let steps = RerunLayer.trailSteps
            let f = Float(steps.firstIndex(of: layer) ?? 0) / Float(steps.count - 1)
            return f < 0.5 ? lerp(T.trailOld, T.trailMid, f * 2) : lerp(T.trailMid, T.trailNew, (f - 0.5) * 2)
        }
    }

    static func order(_ layer: RerunLayer) -> Int32 {
        switch layer {
        case .gridMinor, .gridMajor: Order.grid
        case .planeFloor, .planeWall, .planeOther: Order.fill
        case .outlineFloor, .outlineWall, .outlineOther: Order.outline
        case .keyframes: Order.keyframes
        case .livePoints: Order.live
        default: Order.line
        }
    }

    static func lerp(_ a: UInt32, _ b: UInt32, _ f: Float) -> UInt32 {
        var out: UInt32 = 0
        for shift in stride(from: 0, through: 24, by: 8) {
            let x = Float((a >> UInt32(shift)) & 0xFF), y = Float((b >> UInt32(shift)) & 0xFF)
            out |= UInt32((x + (y - x) * f).rounded()) << UInt32(shift)
        }
        return out
    }

    /// One texel per map point, read nearest so each point keeps its own colour.
    static func atlasTexture(_ whole: RerunFrame) -> TextureResource? {
        let size = RerunPointAtlas.size
        let pixels = RerunPointAtlas.pixels(whole.mapPointColors, count: whole.mapPointCount)
        guard let provider = CGDataProvider(data: Data(pixels) as CFData),
              let image = CGImage(width: size, height: size, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: size * 4,
                                  space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue),
                                  provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
        else { return nil }
        return try? TextureResource(image: image, withName: nil, options: .init(semantic: .color, mipmapsMode: .none))
    }

    /// A soft black disc fading to nothing: a contact shadow without a shadow pass.
    static func shadowImage() -> CGImage? {
        let size = 64
        guard let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
              let gradient = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB)!,
                                        colors: [CGColor(srgbRed: 0, green: 0, blue: 0, alpha: 150.0 / 255),
                                                 CGColor(srgbRed: 0, green: 0, blue: 0, alpha: 0)] as CFArray,
                                        locations: [0.15, 1])
        else { return nil }
        let centre = CGPoint(x: Double(size) / 2, y: Double(size) / 2)
        context.drawRadialGradient(gradient, startCenter: centre, startRadius: 0, endCenter: centre,
                                   endRadius: Double(size) / 2, options: [.drawsBeforeStartLocation])
        return context.makeImage()
    }
}

/// A two-part equatable key.
private struct Key<A: Equatable, B: Equatable>: Equatable {
    let a: A
    let b: B
    init(_ a: A, _ b: B) {
        self.a = a
        self.b = b
    }
}
#endif

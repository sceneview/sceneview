#if os(iOS)
import SwiftUI
import RealityKit
import SceneViewSwift

/// The AR tab's hero: what AR *does*, playing on its own before the camera is
/// ever opened — the iOS twin of Android's `ArHeroStage`.
///
/// A dark stage (the Home hero's, in both themes) with a detected floor — a
/// perspective field of dots, projected with the 3D camera's own lens so it is
/// the plane the models stand on — that a ripple crosses every couple of
/// seconds, like plane detection sweeping a room. On it a reticle, and bundled
/// models placed on the reticle one after the other: the fox, the piano, the
/// shiba. Each one drops in with the placement ease, turns slowly, then gives
/// way.
///
/// Only the stage: the copy and the call to action are the caller's, drawn
/// over it. Nothing here uses ARKit — it is rendered, so it plays on every
/// device and in the Simulator.
struct ARHeroStage: View {
    /// The hero is on screen. False parks the 3D and stops the ripple.
    let active: Bool

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var renderer = ARHeroRenderer()

    var body: some View {
        let tokens = SceneViewTokens.ArHero.self
        let moving = active && scenePhase == .active && !reduceMotion
        ZStack {
            LinearGradient(colors: [tokens.stageTop, tokens.stageBottom],
                           startPoint: .top, endPoint: .bottom)
            ARHeroPlane(moving: moving)
            if active {
                ARHeroScene(renderer: renderer, moving: moving)
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

// MARK: - Detected plane

/// The floor the camera "detected": dots on the world's y = 0 plane, projected
/// through the stage camera's own lens, so the reticle and the models stand
/// exactly on them. A ripple runs out from the reticle. Positions are computed
/// once per size; each frame only reads the ripple phase.
private struct ARHeroPlane: View {
    let moving: Bool
    @State private var cache = ProjectedPlaneCache()

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !moving)) { timeline in
            let phase = moving
                ? timeline.date.timeIntervalSinceReferenceDate
                    .truncatingRemainder(dividingBy: SceneViewTokens.ArHero.rippleSeconds)
                    / SceneViewTokens.ArHero.rippleSeconds
                : HeroScene.restRipple
            Canvas { context, size in
                let plane = cache.plane(for: size)
                drawShadow(plane, in: &context)
                drawDots(plane, phase: phase, in: &context)
            }
        }
    }

    private func drawShadow(_ plane: ProjectedPlane, in context: inout GraphicsContext) {
        guard plane.shadowRadiusX > 0 else { return }
        let rx = plane.shadowRadiusX, ry = plane.shadowRadiusY
        // A radial gradient in a circle, squashed by the camera's pitch.
        var shadow = context
        shadow.translateBy(x: plane.originX, y: plane.originY)
        shadow.scaleBy(x: 1, y: ry / rx)
        shadow.fill(Path(ellipseIn: CGRect(x: -rx, y: -rx, width: rx * 2, height: rx * 2)),
                    with: .radialGradient(Gradient(colors: [SceneViewTokens.ArHero.contactShadow, .clear]),
                                          center: .zero, startRadius: 0, endRadius: rx))
    }

    private func drawDots(_ plane: ProjectedPlane, phase: Double, in context: inout GraphicsContext) {
        let tokens = SceneViewTokens.ArHero.self
        let front = HeroScene.rippleReach * phase
        let fadeOut = 1 - phase
        let shading = GraphicsContext.Shading.color(tokens.planeDot)
        for dot in plane.dots {
            let band = (dot.distance - front) / HeroScene.rippleWidth
            let wave = exp(-band * band) * fadeOut
            let alpha = min((tokens.planeDotAlpha + tokens.planeRippleAlpha * wave) * dot.fade, 1)
            guard alpha > 0.01 else { continue }
            let r = tokens.planeDotRadius * dot.depthScale * (1 + CGFloat(wave) * HeroScene.rippleGrowth)
            var layer = context
            layer.opacity = alpha
            layer.fill(Path(ellipseIn: CGRect(x: dot.x - r, y: dot.y - r, width: r * 2, height: r * 2)),
                       with: shading)
        }
    }
}

/// Screen-space floor, one entry per dot.
private struct ProjectedPlane {
    struct Dot {
        var x: CGFloat
        var y: CGFloat
        var depthScale: CGFloat
        var fade: Double
        /// World distance from the reticle, for the ripple.
        var distance: Double
    }

    var dots: [Dot] = []
    var originX: CGFloat = 0
    var originY: CGFloat = 0
    var shadowRadiusX: CGFloat = 0
    var shadowRadiusY: CGFloat = 0
}

/// Holds the last projection so a frame only re-projects when the size moves.
private final class ProjectedPlaneCache {
    private var size: CGSize = .zero
    private var cached = ProjectedPlane()

    func plane(for size: CGSize) -> ProjectedPlane {
        if size != self.size {
            self.size = size
            cached = Self.project(size)
        }
        return cached
    }

    /// Pinhole projection matching the stage camera: the vertical field of
    /// view of Android's 28 mm lens, looking from `HeroScene.eye` at
    /// `HeroScene.target`.
    private static func project(_ size: CGSize) -> ProjectedPlane {
        let eye = HeroScene.eye, target = HeroScene.target
        let f = simd_normalize(target - eye)
        let r = simd_normalize(SIMD3<Float>(-f.z, 0, f.x))
        let u = simd_cross(r, f)
        let halfW = Float(size.width) / 2, halfH = Float(size.height) / 2
        let focal = halfH / tan(HeroScene.verticalFov * .pi / 360)

        func project(_ w: SIMD3<Float>) -> SIMD3<Float>? {
            let d = w - eye
            let zc = simd_dot(d, f)
            guard zc >= 0.05 else { return nil }
            return [halfW + simd_dot(d, r) / zc * focal, halfH - simd_dot(d, u) / zc * focal, zc]
        }

        var plane = ProjectedPlane()
        let steps = HeroScene.gridSteps
        let span = HeroScene.gridHalfSpan
        let step = span * 2 / Float(steps - 1)
        let reference = simd_length(SIMD3<Float>(eye.x - target.x, eye.y, eye.z - target.z))
        let width = Float(size.width), height = Float(size.height)
        for i in 0..<steps {
            for j in 0..<steps {
                let wx = -span + Float(i) * step
                let wz = -span + Float(j) * step + HeroScene.gridOffsetZ
                guard let p = project([wx, 0, wz]) else { continue }
                guard p.x >= -width * 0.02, p.x <= width * 1.02, p.y >= 0, p.y <= height else { continue }
                let depth = reference / p.z
                let d = (wx * wx + wz * wz).squareRoot()
                // Far dots dissolve toward the horizon, and the field's edge is soft.
                let fade = min(max(1 - d / (span * 1.1), 0), 1) * min(max(depth, 0), 1)
                plane.dots.append(.init(x: CGFloat(p.x), y: CGFloat(p.y),
                                        depthScale: CGFloat(min(max(depth, 0.35), 1.8)),
                                        fade: Double(fade), distance: Double(d)))
            }
        }
        if let o = project(.zero) {
            plane.originX = CGFloat(o.x)
            plane.originY = CGFloat(o.y)
            plane.shadowRadiusX = CGFloat(HeroScene.shadowRadius / o.z * focal)
            // Foreshortened by the camera's pitch.
            plane.shadowRadiusY = plane.shadowRadiusX * CGFloat(min(max(-f.y, 0.2), 1))
        }
        return plane
    }
}

// MARK: - 3D

/// The `RealityView` over the plane: the reticle and the placed models. Its
/// own view, not SceneViewSwift's `SceneView`, like the Home hero and the About
/// mark: the stage needs its own camera and a per-frame hook. The background
/// stays clear — the gradient and the dots show through.
private struct ARHeroScene: View {
    let renderer: ARHeroRenderer
    let moving: Bool

    var body: some View {
        RealityView { content in
            content.camera = .virtual
            renderer.install(in: &content)
        }
        .onAppear { renderer.moving = moving }
        .onChange(of: moving) { _, value in renderer.moving = value }
        .onDisappear { renderer.detach() }
    }
}

/// Builds the reticle and loads the models once, then poses them every frame
/// from a clock that only runs while the stage is moving — Android's
/// `driveArHero`, in the same world units, degrees and seconds.
@MainActor
final class ARHeroRenderer {
    var moving = false

    private let root = Entity()
    private let camera = PerspectiveCamera()
    private let reticle = Entity()
    private var slots: [Entity] = []
    private var built = false
    /// Scene seconds; starts on the rest pose so the first frame is composed.
    private var seconds = HeroScene.restSeconds
    private var stopUpdates: (() -> Void)?

    func install(in content: inout RealityViewCameraContent) {
        content.add(root)
        content.add(camera)
        if !built { build() }
        stopUpdates?()
        let subscription = content.subscribe(to: SceneEvents.Update.self) { [weak self] event in
            MainActor.assumeIsolated { self?.update(event.deltaTime) }
        }
        stopUpdates = { subscription.cancel() }
        pose()
    }

    func detach() {
        stopUpdates?()
        stopUpdates = nil
    }

    private func update(_ delta: Double) {
        guard moving else { return }
        seconds += delta
        pose()
    }

    private func build() {
        built = true
        camera.components.set(PerspectiveCameraComponent(near: 0.05, far: 50,
                                                         fieldOfViewInDegrees: HeroScene.verticalFov,
                                                         fieldOfViewOrientation: .vertical))
        camera.look(at: HeroScene.target, from: HeroScene.eye, relativeTo: nil)

        let key = DirectionalLight()
        key.light.intensity = HeroScene.keyLux
        key.look(at: HeroScene.keyLight, from: .zero, relativeTo: nil)
        root.addChild(key)

        let c = SceneViewTokens.ArHero.planeDotRGB
        var reticleMaterial = UnlitMaterial(applyPostProcessToneMap: false)
        reticleMaterial.color = .init(tint: UIColor(red: c.r, green: c.g, blue: c.b, alpha: 1))
        if let ring = Self.torus(major: HeroScene.reticleRadius, tube: HeroScene.reticleTube,
                                 rings: HeroScene.reticleSegments, sides: HeroScene.reticleTubeSegments) {
            reticle.addChild(ModelEntity(mesh: ring, materials: [reticleMaterial]))
        }
        reticle.addChild(ModelEntity(mesh: .generateSphere(radius: HeroScene.reticleDot),
                                     materials: [reticleMaterial]))
        root.addChild(reticle)

        slots = HeroScene.models.map { _ in
            let holder = Entity()
            holder.scale = .init(repeating: HeroScene.hiddenScale)
            root.addChild(holder)
            return holder
        }

        // Loaded once the RealityKit view is in the hierarchy: `Entity(named:)`
        // does not resume before that (see `HomeHeroRenderer`).
        Task { @MainActor [weak self] in
            guard let self else { return }
            if let resource = try? await SceneEnvironment.warm.load() {
                root.components.set(ImageBasedLightComponent(source: .single(resource), intensityExponent: 0))
            }
            for (index, spec) in HeroScene.models.enumerated() {
                guard let model = try? await ModelNode.load(spec.asset) else { continue }
                _ = model.scaleToUnits(spec.units)
                _ = model.centerOrigin(normalized: [0, -1, 0])
                if let animation = spec.animation, model.animationNames.contains(animation) {
                    model.playAnimation(named: animation)
                } else if spec.animation != nil {
                    model.playAllAnimations()
                }
                model.entity.components.set(ImageBasedLightReceiverComponent(imageBasedLight: root))
                slots[index].addChild(model.entity)
            }
        }
    }

    /// One frame of the placement loop.
    private func pose() {
        let period = SceneViewTokens.ArHero.placementSeconds
        let cycle = Int(floor(seconds / period))
        let current = cycle % max(slots.count, 1)
        let local = Float(seconds - Double(cycle) * period)
        let t = Float(seconds)
        let enter = min(max(local / HeroScene.enterSeconds, 0), 1)
        let exit = min(max((local - (Float(period) - HeroScene.exitSeconds)) / HeroScene.exitSeconds, 0), 1)
        for (i, node) in slots.enumerated() {
            guard i == current else {
                node.scale = .init(repeating: HeroScene.hiddenScale)
                continue
            }
            let drop = 1 - Self.easeOutCubic(enter)
            let grow = Self.easeOutBack(enter) * (1 - Self.easeInCubic(exit))
            let scale = max(HeroScene.enterScale + (1 - HeroScene.enterScale) * grow, HeroScene.hiddenScale)
            node.position = [0, HeroScene.dropUnits * drop, 0]
            node.orientation = simd_quatf(angle: (HeroScene.yawStart + t * HeroScene.yawPerSecond) * .pi / 180,
                                          axis: [0, 1, 0])
            node.scale = .init(repeating: scale)
        }
        // The reticle breathes, and answers each landing with one pulse.
        let landing = min(max((local - HeroScene.enterSeconds * 0.7) / HeroScene.pulseSeconds, 0), 1)
        let pulse = landing < 1 ? HeroScene.pulseScale * sin(landing * .pi) : 0
        let breath = HeroScene.breathScale * sin(t * 2 * .pi / HeroScene.breathPeriod)
        reticle.position = [0, HeroScene.reticleLift, 0]
        reticle.scale = .init(repeating: 1 + pulse + breath)
    }

    private static func easeOutCubic(_ x: Float) -> Float { let u = 1 - x; return 1 - u * u * u }
    private static func easeInCubic(_ x: Float) -> Float { x * x * x }
    private static func easeOutBack(_ x: Float) -> Float {
        let c1: Float = 1.4, c3 = c1 + 1, u = x - 1
        return 1 + c3 * u * u * u + c1 * u * u
    }

    /// A torus in the XZ plane: RealityKit has no generator for one.
    private static func torus(major: Float, tube: Float, rings: Int, sides: Int) -> MeshResource? {
        var positions: [SIMD3<Float>] = []
        var normals: [SIMD3<Float>] = []
        for i in 0...rings {
            let u = Float(i) / Float(rings) * 2 * .pi
            let centre = SIMD3<Float>(cos(u) * major, 0, sin(u) * major)
            for j in 0...sides {
                let v = Float(j) / Float(sides) * 2 * .pi
                let normal = SIMD3<Float>(cos(u) * cos(v), sin(v), sin(u) * cos(v))
                normals.append(normal)
                positions.append(centre + normal * tube)
            }
        }
        var indices: [UInt32] = []
        let stride = UInt32(sides + 1)
        for i in 0..<UInt32(rings) {
            for j in 0..<UInt32(sides) {
                let a = i * stride + j, b = (i + 1) * stride + j
                indices += [a, a + 1, b, b, a + 1, b + 1]
            }
        }
        var descriptor = MeshDescriptor(name: "reticle")
        descriptor.positions = MeshBuffers.Positions(positions)
        descriptor.normals = MeshBuffers.Normals(normals)
        descriptor.primitives = .triangles(indices)
        return try? MeshResource.generate(from: [descriptor])
    }
}

/// Android's `ArHeroScene`: art direction in world units, degrees and seconds,
/// not UI tokens.
private enum HeroScene {
    struct Model {
        let asset: String
        let units: Float
        var animation: String?
    }

    /// Raised and to the left of the reticle, so the models stand on the right
    /// of the copy.
    static let eye = SIMD3<Float>(-0.4, 0.74, 1.9)
    static let target = SIMD3<Float>(-0.46, 0.1, 0)
    /// Android's 28 mm lens on Filament's 24 mm-tall sensor: `2·atan(12/28)`.
    static let verticalFov: Float = 46.4
    /// Key light from the upper right, a little in front (Android `ShellStage`).
    static let keyLight = SIMD3<Float>(-0.45, -0.82, -0.36)
    /// RealityKit's exposure, not Filament's 70 000 lux — see `AboutMarkRenderer`.
    static let keyLux: Float = 2_000

    /// Android places the fox, the sheen chair, the shiba. iOS bundles no sheen
    /// chair, so the piano stands in for the furniture.
    static let models: [Model] = [
        Model(asset: "khronos_fox", units: 0.64, animation: "Survey"),
        Model(asset: "retro_piano", units: 0.5),
        Model(asset: "shiba", units: 0.5),
    ]

    /// Pose drawn under Reduce Motion: the fox, landed, three-quarter view.
    static let restSeconds: Double = 1.6
    static let restRipple: Double = 0.4

    static let hiddenScale: Float = 0.0001
    static let enterSeconds: Float = 0.6
    static let exitSeconds: Float = 0.3
    static let enterScale: Float = 0.55
    static let dropUnits: Float = 0.32
    static let yawStart: Float = -30
    static let yawPerSecond: Float = 14

    static let reticleRadius: Float = 0.3
    static let reticleTube: Float = 0.005
    static let reticleDot: Float = 0.014
    static let reticleSegments = 72
    static let reticleTubeSegments = 6
    /// Just above the floor, so the ring never z-fights the model's feet.
    static let reticleLift: Float = 0.003
    static let pulseSeconds: Float = 0.45
    static let pulseScale: Float = 0.22
    static let breathScale: Float = 0.03
    static let breathPeriod: Float = 2.4

    static let gridSteps = 21
    static let gridHalfSpan: Float = 2.4
    /// The field runs further behind the reticle than in front of it.
    static let gridOffsetZ: Float = -0.9
    static let rippleReach: Double = 2.8
    static let rippleWidth: Double = 0.22
    static let rippleGrowth: CGFloat = 0.6
    static let shadowRadius: Float = 0.34
}

// MARK: - Viewfinder

/// Four viewfinder corners: the stage is a camera view, before the camera is
/// opened.
struct ARHeroViewfinder: View {
    var body: some View {
        let tokens = SceneViewTokens.ArHero.self
        Canvas { context, size in
            let inset = tokens.bracketInset, length = tokens.bracketLength
            var path = Path()
            let corners: [(CGFloat, CGFloat, CGFloat, CGFloat)] = [
                (inset, inset, 1, 1),
                (size.width - inset, inset, -1, 1),
                (inset, size.height - inset, 1, -1),
                (size.width - inset, size.height - inset, -1, -1),
            ]
            for (x, y, dx, dy) in corners {
                path.move(to: CGPoint(x: x + dx * length, y: y))
                path.addLine(to: CGPoint(x: x, y: y))
                path.addLine(to: CGPoint(x: x, y: y + dy * length))
            }
            context.stroke(path, with: .color(tokens.bracket),
                           style: StrokeStyle(lineWidth: tokens.bracketStroke, lineCap: .round, lineJoin: .round))
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
#endif

import SwiftUI
import RealityKit
import SceneViewSwift

/// **Materials** — nine spheres, one material each, on a wall the camera sweeps across.
///
/// The iOS half of Android's material studio (`MaterialsDemo.kt`, `MaterialStudio.kt`): the
/// same nine presets in the same 3 × 3 order, the same ball size and spacing, the same label
/// under each ball, the same slow ±24° camera sweep so the reflections travel, and the same
/// "tap a sphere to zoom in on it". The previous iOS screen streamed one Sketchfab model at a
/// time and, in the field, mostly showed its offline stand-in — a scanned mosquito in amber —
/// where Android shows the whole family of materials at once (#3907 parity audit).
///
/// Everything here is procedural, so the screen needs no network and no API key.
///
/// ## RealityKit mapping
///
/// Android writes its own Filament material (`studio_pbr` / `studio_glass`); RealityKit's
/// `PhysicallyBasedMaterial` carries the same lobes under other names:
///
/// | Android parameter          | `PhysicallyBasedMaterial`              |
/// |----------------------------|----------------------------------------|
/// | `metallic`, `roughness`    | `metallic`, `roughness`                |
/// | `reflectance`              | `specular`                             |
/// | `clearCoat`, `…Roughness`  | `clearcoat`, `clearcoatRoughness`      |
/// | `sheenColor`               | `sheen`                                |
/// | `emissive` × strength      | `emissiveColor`, `emissiveIntensity`   |
///
/// **Transmission has no RealityKit equivalent** — there is no refraction lobe. The crystal
/// ball is therefore a clear, glossy, *transparent* dielectric, and its label says
/// "transparency", not "transmission": it lets the backdrop through without bending it.
struct MaterialsDemo: View {

    @State private var wall = MaterialWall()
    /// The ball the camera flew onto, `nil` on the wall view.
    @State private var focused: Int?
    /// Android's "Animate": the camera sweeps the wall so reflections move.
    @State private var sweeping = true
    /// Sweep phase at the moment the sweep (re)started, and when that was.
    @State private var sweepStart = Date()
    @State private var sweepStartPhase: Double = MaterialWall.staticSweepPhase
    /// The pose the camera holds while the sweep is paused.
    @State private var heldPose: SceneCameraPose?
    @State private var viewport: CGSize = .zero
    @State private var metallic: Double = 0
    @State private var roughness: Double = 0

    @Environment(\.colorScheme) private var colorScheme
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false

    var body: some View {
        GeometryReader { proxy in
            stage
                .onAppear {
                    viewport = proxy.size
                    if qaMode { sweeping = false }
                    heldPose = wallPose(phase: MaterialWall.staticSweepPhase)
                }
                .onChange(of: proxy.size) { _, size in
                    viewport = size
                    if focused == nil { heldPose = wallPose(phase: currentPhase()) }
                }
        }
        .ignoresSafeArea()
        .demoChrome(
            dock: [
                DockItem(icon: sweeping ? "pause.fill" : "play.fill", label: "Animate",
                         selected: sweeping) { setSweeping(!sweeping) },
            ],
            onReset: { showWall() },
            accessory: { DemoHint(hint) }
        ) {
            controlsSheet
        }
        .onChange(of: metallic) { _, _ in pushFocusedMaterial() }
        .onChange(of: roughness) { _, _ in pushFocusedMaterial() }
    }

    // MARK: Stage

    private var stage: some View {
        ZStack {
            LinearGradient(colors: [SceneViewTokens.Stage.skyHorizon, SceneViewTokens.Stage.skyGround],
                           startPoint: .top, endPoint: .bottom)
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !sweeping || focused != nil)) { context in
                SceneView { root in
                    wall.install(in: root, dark: colorScheme == .dark)
                }
                .environment(Self.environment)
                .cameraControls(.orbit)
                .autoCenterContent(false)
                .contentID(colorScheme == .dark ? "dark" : "light")
                .cameraPose(pose(at: context.date))
                .onCameraChanged { pose in
                    Task { @MainActor in noteCamera(pose) }
                }
                .onEntityTapped { entity in
                    if let index = MaterialWall.index(of: entity) { focus(index) }
                }
            }
        }
    }

    /// The studio HDR lights and is mirrored by the balls; the backdrop is the themed stage
    /// gradient behind the view, so the labels always sit on a quiet ground.
    private static var environment: SceneEnvironment {
        var studio = SceneEnvironment.studio
        studio.showSkybox = false
        return studio
    }

    // MARK: Camera

    private func pose(at date: Date) -> SceneCameraPose? {
        if let focused { return focusPose(focused) }
        guard sweeping else { return heldPose }
        return wallPose(phase: phase(at: date))
    }

    private func phase(at date: Date) -> Double {
        sweepStartPhase + date.timeIntervalSince(sweepStart) / MaterialWall.sweepPeriod
    }

    private func currentPhase() -> Double {
        sweeping ? phase(at: Date()) : sweepStartPhase
    }

    /// The whole wall, fitted into the band between the top chrome and the dock, seen from
    /// the sweep's yaw at `phase` — Android's `wallCameraPose` on SceneViewSwift's 60° lens.
    private func wallPose(phase: Double) -> SceneCameraPose {
        let width = Float(max(viewport.width, 1))
        let height = Float(max(viewport.height, 1))
        let top = Float(SceneViewTokens.Chrome.scrimTop)
        let bottom = Float(SceneViewTokens.Chrome.scrimBottomMin)
        let band = max(height - top - bottom, height * 0.4)
        let tanV = tan(Float.pi / 6)
        let tanH = tanV * width / height
        let tanBand = tanV * band / height
        let fill: Float = 0.94
        let extent = MaterialWall.extent
        let distance = max(extent.x / 2 / (tanH * fill), extent.y / 2 / (tanBand * fill))
        // Centre the wall in the band rather than in the screen: the dock is taller than the
        // title bar, so a screen-centred wall would sit under the hint.
        let bandOffset = (bottom - top) / 2 / (height / 2) * tanV * distance
        let yaw = MaterialWall.sweepYawDegrees(phase: phase) * .pi / 180
        return SceneCameraPose(azimuth: yaw, elevation: 0, distance: distance,
                               target: SIMD3(0, MaterialWall.extentCentreY - bandOffset, 0))
    }

    private func focusPose(_ index: Int) -> SceneCameraPose {
        SceneCameraPose(azimuth: 0, elevation: 0, distance: MaterialWall.focusDistance,
                        target: MaterialWall.position(of: index))
    }

    /// A drag while the sweep runs means the viewer wants the camera: stop the sweep where
    /// they took it, like Android's orbit hand-off.
    private func noteCamera(_ reported: SceneCameraPose) {
        guard sweeping, focused == nil else { return }
        let expected = wallPose(phase: phase(at: Date()))
        let moved = abs(reported.azimuth - expected.azimuth) > 0.08
            || abs(reported.elevation - expected.elevation) > 0.08
            || abs(reported.distance - expected.distance) > 0.15
        if moved {
            sweepStartPhase = phase(at: Date())
            heldPose = reported
            sweeping = false
        }
    }

    private func setSweeping(_ on: Bool) {
        if on {
            focused = nil
            sweepStart = Date()
        } else {
            sweepStartPhase = phase(at: Date())
            heldPose = wallPose(phase: sweepStartPhase)
        }
        sweeping = on
    }

    private func focus(_ index: Int) {
        if sweeping { setSweeping(false) }
        focused = index
        let material = MaterialWall.library[index]
        metallic = Double(material.metallic)
        roughness = Double(material.roughness)
        wall.reset(except: index)
    }

    private func showWall() {
        focused = nil
        wall.reset(except: nil)
        heldPose = wallPose(phase: MaterialWall.staticSweepPhase)
        sweepStartPhase = MaterialWall.staticSweepPhase
        sweeping = false
    }

    private func pushFocusedMaterial() {
        guard let focused else { return }
        wall.update(focused, metallic: Float(metallic), roughness: Float(roughness))
    }

    // MARK: Copy

    private var hint: String {
        guard let focused else { return "Tap a sphere to zoom in on it" }
        let material = MaterialWall.library[focused]
        return "\(material.name) · \(material.summary(metallic: Float(metallic), roughness: Float(roughness)))"
    }

    private var controlsSheet: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
            if let focused {
                let material = MaterialWall.library[focused]
                Text(material.name)
                    .font(SceneViewTokens.TypeScale.card)
                Text(material.note)
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
                LabeledSlider(label: "Metallic", value: $metallic, range: 0...1)
                LabeledSlider(label: "Roughness", value: $roughness, range: 0...1)
                Button("Back to all nine") { showWall() }
            } else {
                Text("Nine materials, one sphere each. Metals mirror the room; dielectrics keep "
                     + "their own color under a small highlight; the last row adds a coat, a sheen, "
                     + "transparency and light of its own. Tap a sphere to fly onto it and tune it.")
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
                ForEach(MaterialWall.library.indices, id: \.self) { index in
                    let material = MaterialWall.library[index]
                    Button {
                        focus(index)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(material.name).font(SceneViewTokens.TypeScale.bodySemibold)
                            Text(material.summary()).font(.caption)
                                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

// MARK: - The wall

/// One preset — Android's `StudioMaterial`, same values.
struct StudioMaterial: Sendable {
    enum Trait: Sendable { case none, clearcoat, sheen, transparency, emissive }

    let id: String
    let name: String
    let note: String
    let color: SIMD3<Float>
    let metallic: Float
    let roughness: Float
    var reflectance: Float = 0.5
    var trait: Trait = .none
    var traitAmount: Float = 0
    var traitRoughness: Float = 0.1
    var traitColor: SIMD3<Float> = SIMD3(1, 1, 1)

    func summary(metallic: Float? = nil, roughness: Float? = nil) -> String {
        var text = "metallic \(Self.format(metallic ?? self.metallic))"
            + " · roughness \(Self.format(roughness ?? self.roughness))"
        switch trait {
        case .none: break
        case .clearcoat: text += " · clear coat \(Self.format(traitAmount))"
        case .sheen: text += " · sheen \(Self.format(traitAmount))"
        case .transparency: text += " · opacity \(Self.format(1 - traitAmount))"
        case .emissive: text += " · emissive \(Self.format(traitAmount))"
        }
        return text
    }

    private static func format(_ value: Float) -> String { String(format: "%.2f", value) }
}

/// The nine balls and their labels, kept so a slider can repaint one ball in place instead of
/// rebuilding the scene.
@MainActor
final class MaterialWall {
    private var balls: [ModelEntity] = []

    // Android's `MaterialStudio` constants.
    static let columns = 3
    static let ballRadius: Float = 0.2
    static let ballSpacing: Float = 0.6
    static let focusDistance: Float = 1.25
    static let sweepDegrees: Double = 24
    static let sweepPeriod: Double = 14
    /// The still frame QA mode and Reset land on — Android's `STATIC_SWEEP_PHASE`.
    static let staticSweepPhase: Double = 0.375
    /// Label chip scale: metres per SwiftUI point, so a 13 pt caption reads at about 13 pt
    /// on the wall view.
    private static let metresPerPoint: Float = 0.0042
    private static let labelGap: Float = 0.035
    private static let namePrefix = "material-ball-"

    // Albedos are physical data, as on Android: gold, copper, chromium and aluminium are the
    // metals' measured reflectance; the brand-tinted ones come from `SceneViewColors`.
    static let library: [StudioMaterial] = [
        StudioMaterial(id: "chrome", name: "Polished chrome",
                       note: "A metal at roughness 0.02 reflects the studio almost perfectly.",
                       color: rgb(0x8C8E8D), metallic: 1, roughness: 0.02),
        StudioMaterial(id: "gold", name: "Polished gold",
                       note: "Metals have no diffuse color: this tint is gold's own reflectance.",
                       color: rgb(0xFFC356), metallic: 1, roughness: 0.14),
        StudioMaterial(id: "copper", name: "Satin copper",
                       note: "The same shading, one notch rougher — the highlight spreads and softens.",
                       color: rgb(0xF4A289), metallic: 1, roughness: 0.32),
        StudioMaterial(id: "aluminium", name: "Brushed aluminum",
                       note: "Roughness 0.55: the environment is still reflected, just scattered.",
                       color: rgb(0xE9EBEC), metallic: 1, roughness: 0.55),
        StudioMaterial(id: "ceramic", name: "Glazed ceramic",
                       note: "A dielectric keeps its own color and adds a small, sharp highlight.",
                       color: rgb(0xF2EFE9), metallic: 0, roughness: 0.06, reflectance: 0.7),
        StudioMaterial(id: "car-paint", name: "Car paint — clearcoat",
                       note: "A glossy clear coat over a metallic flake base — two specular lobes.",
                       color: rgb(0x005BC1), metallic: 0.85, roughness: 0.42,
                       trait: .clearcoat, traitAmount: 1, traitRoughness: 0.03),
        StudioMaterial(id: "velvet", name: "Velvet — sheen",
                       note: "Sheen adds a retro-reflective rim that lights up at grazing angles.",
                       color: rgb(0x5A32A3), metallic: 0, roughness: 0.85, reflectance: 0.2,
                       trait: .sheen, traitAmount: 1, traitRoughness: 0.3, traitColor: rgb(0xD2A8FF)),
        StudioMaterial(id: "crystal", name: "Crystal — transparency",
                       note: "A clear, glossy dielectric you can see through. RealityKit has no "
                           + "transmission lobe, so it does not bend what is behind it.",
                       color: rgb(0xEFF6FF), metallic: 0, roughness: 0.05, reflectance: 0.6,
                       trait: .transparency, traitAmount: 0.75),
        StudioMaterial(id: "glow", name: "Neon sign — emissive",
                       note: "Emission owes nothing to the environment — it still lights at night.",
                       color: rgb(0x161B22), metallic: 0, roughness: 0.6, reflectance: 0.35,
                       trait: .emissive, traitAmount: 1.5, traitColor: rgb(0xA4C1FF)),
    ]

    static var rows: Int { (library.count + columns - 1) / columns }

    static func position(of index: Int) -> SIMD3<Float> {
        let row = index / columns
        let column = index % columns
        let xOffset = Float(columns - 1) * ballSpacing / 2
        let yOffset = Float(rows - 1) * ballSpacing / 2
        return SIMD3(Float(column) * ballSpacing - xOffset, yOffset - Float(row) * ballSpacing, 0)
    }

    /// Width and height of the wall, labels included (a two-line label hangs ~0.14 m).
    static var extent: SIMD2<Float> {
        SIMD2(Float(columns - 1) * ballSpacing + 2 * ballRadius + 0.1,
              Float(rows - 1) * ballSpacing + 2 * ballRadius + labelGap + 0.14)
    }

    /// Vertical centre of ``extent``: the labels hang below the last row.
    static var extentCentreY: Float { -(labelGap + 0.14) / 2 }

    static func sweepYawDegrees(phase: Double) -> Float {
        Float(-sweepDegrees * cos(2 * .pi * phase))
    }

    static func index(of entity: Entity) -> Int? {
        var current: Entity? = entity
        while let node = current {
            if node.name.hasPrefix(namePrefix), let index = Int(node.name.dropFirst(namePrefix.count)) {
                return index
            }
            current = node.parent
        }
        return nil
    }

    func install(in root: Entity, dark: Bool) {
        balls = []
        for (index, preset) in Self.library.enumerated() {
            let position = Self.position(of: index)
            let ball = ModelEntity(mesh: .generateSphere(radius: Self.ballRadius),
                                   materials: [Self.material(preset)])
            ball.name = Self.namePrefix + String(index)
            ball.position = position
            ball.generateCollisionShapes(recursive: false)
            root.addChild(ball)
            balls.append(ball)

            if let label = Self.label(preset.name, dark: dark) {
                label.position = position - SIMD3(0, Self.ballRadius + Self.labelGap, 0)
                root.addChild(label)
            }
        }
    }

    func update(_ index: Int, metallic: Float, roughness: Float) {
        guard balls.indices.contains(index) else { return }
        balls[index].model?.materials = [Self.material(Self.library[index],
                                                      metallic: metallic, roughness: roughness)]
    }

    /// Restores every ball but `kept` to its preset, so a tuned ball does not stay tuned after
    /// the viewer moved on.
    func reset(except kept: Int?) {
        for index in balls.indices where index != kept {
            balls[index].model?.materials = [Self.material(Self.library[index])]
        }
    }

    // MARK: Materials

    static func material(_ preset: StudioMaterial, metallic: Float? = nil,
                         roughness: Float? = nil) -> PhysicallyBasedMaterial {
        var material = PhysicallyBasedMaterial()
        material.baseColor = .init(tint: color(preset.color))
        material.metallic = .init(floatLiteral: metallic ?? preset.metallic)
        material.roughness = .init(floatLiteral: roughness ?? preset.roughness)
        material.specular = .init(floatLiteral: preset.reflectance)
        switch preset.trait {
        case .none:
            break
        case .clearcoat:
            material.clearcoat = .init(floatLiteral: preset.traitAmount)
            material.clearcoatRoughness = .init(floatLiteral: preset.traitRoughness)
        case .sheen:
            material.sheen = .init(tint: color(preset.traitColor * preset.traitAmount))
        case .transparency:
            material.clearcoat = .init(floatLiteral: 1)
            material.clearcoatRoughness = .init(floatLiteral: 0.02)
            material.blending = .transparent(opacity: .init(floatLiteral: 1 - preset.traitAmount))
        case .emissive:
            material.emissiveColor = .init(color: color(preset.traitColor))
            material.emissiveIntensity = preset.traitAmount
        }
        return material
    }

    // MARK: Labels

    /// A caption chip under a ball — Android draws its labels as Compose chips over the
    /// scene; here the chip is rendered once to a texture and hung on the wall, so it moves
    /// with the camera like the ball it names.
    private static func label(_ text: String, dark: Bool) -> Entity? {
        let chip = Text(text)
            .font(SceneViewTokens.TypeScale.caption)
            .multilineTextAlignment(.center)
            .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
            .padding(.horizontal, SceneViewTokens.Space.sm)
            .padding(.vertical, SceneViewTokens.Space.xs)
            .frame(maxWidth: 104)
            .background(SceneViewTokens.HomeColor.surfaceContainer,
                        in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.xs, style: .continuous))
            .fixedSize(horizontal: false, vertical: true)
            .environment(\.colorScheme, dark ? .dark : .light)
        let renderer = ImageRenderer(content: chip)
        renderer.scale = 3
        guard let image = renderer.cgImage,
              let texture = try? TextureResource(image: image, withName: nil,
                                                 options: .init(semantic: .color)) else { return nil }
        let widthPoints = Float(image.width) / 3
        let heightPoints = Float(image.height) / 3
        let width = widthPoints * metresPerPoint
        let height = heightPoints * metresPerPoint
        var material = UnlitMaterial(applyPostProcessToneMap: false)
        material.color = .init(tint: .white, texture: .init(texture))
        material.blending = .transparent(opacity: .init(floatLiteral: 1))
        let plane = ModelEntity(mesh: .generatePlane(width: width, height: height), materials: [material])
        // Hang from the top edge, so a two-line label grows downwards, away from its ball.
        plane.position.y = -height / 2
        let anchor = Entity()
        anchor.addChild(plane)
        return anchor
    }

    // MARK: Colour helpers

    private static func rgb(_ hex: UInt32) -> SIMD3<Float> {
        SIMD3(Float((hex >> 16) & 0xFF), Float((hex >> 8) & 0xFF), Float(hex & 0xFF)) / 255
    }

    private static func color(_ value: SIMD3<Float>) -> SimpleMaterial.Color {
        let clamped = simd_clamp(value, SIMD3(repeating: 0), SIMD3(repeating: 1))
        return SimpleMaterial.Color(red: CGFloat(clamped.x), green: CGFloat(clamped.y),
                                    blue: CGFloat(clamped.z), alpha: 1)
    }
}

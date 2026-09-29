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
            TimelineView(.animation(minimumInterval: 1.0 / 30,
                                    paused: !sweeping || focused != nil || qaMode)) { context in
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

    /// Sweep phase at `date`. QA mode keeps Animate on, as Android does, but holds the camera
    /// on Android's still frame so the capture is deterministic.
    private func phase(at date: Date) -> Double {
        if qaMode { return MaterialWall.staticSweepPhase }
        return sweepStartPhase + date.timeIntervalSince(sweepStart) / MaterialWall.sweepPeriod
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

    /// Back to the opening state: all nine presets, the sweep running from Android's still
    /// frame.
    private func showWall() {
        focused = nil
        wall.reset(except: nil)
        heldPose = wallPose(phase: MaterialWall.staticSweepPhase)
        sweepStartPhase = MaterialWall.staticSweepPhase
        sweepStart = Date()
        sweeping = true
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
                    .font(SceneViewTokens.TypeScale.body)
                    .fixedSize(horizontal: false, vertical: true)
                LabeledSlider(label: "Metallic", value: $metallic, range: 0...1)
                LabeledSlider(label: "Roughness", value: $roughness, range: 0...1)
                Button("Back to all nine") { showWall() }
            } else {
                Text("Nine materials, one sphere each. Metals mirror the room; dielectrics keep "
                     + "their own color under a small highlight; the last row adds a coat, a sheen, "
                     + "transparency and light of its own. Tap a sphere to fly onto it and tune it.")
                    .font(SceneViewTokens.TypeScale.body)
                    .fixedSize(horizontal: false, vertical: true)
                ForEach(MaterialWall.library.indices, id: \.self) { index in
                    let material = MaterialWall.library[index]
                    Button {
                        focus(index)
                    } label: {
                        VStack(alignment: .leading, spacing: SceneViewTokens.Space.xs) {
                            Text(material.name).font(SceneViewTokens.TypeScale.bodySemibold)
                            Text(material.summary()).font(SceneViewTokens.TypeScale.captionRegular)
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

/// One preset — Android's `StudioMaterial`, same values except the crystal (see
/// ``MaterialWall/library``).
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
    /// The ball the sliders retuned, kept so a rebuilt wall (theme switch) keeps the tuning.
    private var tuning: (index: Int, metallic: Float, roughness: Float)?

    // Android's `MaterialStudio` constants.
    static let columns = 3
    static let ballRadius: Float = 0.2
    static let ballSpacing: Float = 0.6
    static let sweepDegrees: Double = 24
    static let sweepPeriod: Double = 14
    /// The still frame QA mode and Reset land on — Android's `STATIC_SWEEP_PHASE`.
    static let staticSweepPhase: Double = 0.375
    /// Where the tap-to-focus camera stops. Android derives its dolly distance from the
    /// Inspect hero ball it hands over to; iOS has no Inspect stage, so this is chosen on
    /// the 60° lens instead: the 0.4 m ball fills about 28 % of the frame height
    /// (0.4 / (2 × 1.25 × tan 30°)), its neighbours stay just in view at the edges, and the
    /// eye stays well outside Android's 3 × radius floor.
    static let focusDistance: Float = 1.25
    /// Label chip scale: metres per SwiftUI point. The wall view shows about 207 pt per
    /// metre, so a 13 pt caption reads at about 13 pt, and a chip (``chipWidth``) is
    /// 0.56 m wide — inside the 0.6 m ball spacing with a gap between neighbours, as on
    /// Android.
    private static let metresPerPoint: Float = 0.0048
    /// Chip width in points — Android's caption width (a third of 94 % of a 402 pt screen,
    /// less `Space.xs` either side), so the same names wrap onto two lines.
    private static let chipWidth: CGFloat = 116
    private static let labelGap: Float = 0.035
    private static let namePrefix = "material-ball-"

    // Albedos are physical data, as on Android: gold, copper, chromium and aluminium are the
    // metals' measured reflectance; the brand-tinted ones come from `SceneViewColors`.
    // One deliberate difference: the crystal. Android's is a near-white (#EFF6FF) dielectric
    // whose transmission lobe refracts — and so darkens and bends — the studio behind it.
    // RealityKit has no transmission, and a near-white ball that only lets the pale stage
    // through renders as a flat white disc. The iOS crystal is therefore a cool grey-blue
    // glass at 50 % opacity: the tint stands in for the absorption refraction gives, and the
    // clear coat keeps the sharp reflections that say "glass".
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
                       color: rgb(0x7B90A8), metallic: 0, roughness: 0.05, reflectance: 0.6,
                       trait: .transparency, traitAmount: 0.5),
        // Primary × 1.5, Android's preset (#4065). The ball is not drawn with this colour
        // on iOS: `material(_:)` emits what Android renders it as, see its `.emissive` case.
        StudioMaterial(id: "glow", name: "Neon sign — emissive",
                       note: "Emission owes nothing to the environment — it still lights at night.",
                       color: rgb(0x161B22), metallic: 0, roughness: 0.6, reflectance: 0.35,
                       trait: .emissive, traitAmount: 1.5, traitColor: rgb(0x005BC1)),
    ]

    static var rows: Int { (library.count + columns - 1) / columns }

    static func position(of index: Int) -> SIMD3<Float> {
        let row = index / columns
        let column = index % columns
        let xOffset = Float(columns - 1) * ballSpacing / 2
        let yOffset = Float(rows - 1) * ballSpacing / 2
        return SIMD3(Float(column) * ballSpacing - xOffset, yOffset - Float(row) * ballSpacing, 0)
    }

    /// Width and height of the wall, labels included.
    static var extent: SIMD2<Float> {
        SIMD2(Float(columns - 1) * ballSpacing + 2 * ballRadius + 0.1,
              Float(rows - 1) * ballSpacing + 2 * ballRadius + labelGap + labelReserve)
    }

    /// Vertical centre of ``extent``: the labels hang below the last row.
    static var extentCentreY: Float { -(labelGap + labelReserve) / 2 }

    /// Height of the tallest chip, in metres, measured on the chips themselves rather than
    /// guessed, so a bottom-row label never reaches into the dock.
    static let labelReserve: Float = library
        .compactMap { chipImage($0.name, dark: false) }
        .map { Float($0.height) / chipScale * metresPerPoint }
        .max() ?? 0.2

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
            let tuned = tuning.flatMap { $0.index == index ? $0 : nil }
            let ball = ModelEntity(mesh: .generateSphere(radius: Self.ballRadius),
                                   materials: [Self.material(preset, metallic: tuned?.metallic,
                                                             roughness: tuned?.roughness)])
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
        guard Self.library.indices.contains(index) else { return }
        tuning = (index, metallic, roughness)
        guard balls.indices.contains(index) else { return }
        balls[index].model?.materials = [Self.material(Self.library[index],
                                                      metallic: metallic, roughness: roughness)]
    }

    /// Restores every ball but `kept` to its preset, so a tuned ball does not stay tuned after
    /// the viewer moved on.
    func reset(except kept: Int?) {
        if let tuning, tuning.index != kept { self.tuning = nil }
        for index in balls.indices where index != kept {
            balls[index].model?.materials = [Self.material(Self.library[index])]
        }
    }

    // MARK: Materials

    /// The emission that makes RealityKit draw the glow sphere at the pixels Android shows
    /// (see the `.emissive` case below), and the preset strength it stands for. Tuned by
    /// capture: RealityKit's own tone curve compresses the top end, so the emitted colour
    /// sits slightly above the target and the intensity above 1.
    private static let glowRenderedColor = rgb(0x7CE0FF)
    private static let glowRenderedIntensity: Float = 1.4
    private static let glowReferenceStrength: Float = 1.5

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
            // Match Android's rendered pixels, not its constant. Android emits Primary
            // (#005BC1) × 1.5, and Filament's tone mapping and bloom push it to a flat
            // light cyan: (135, 215, 238) at the centre and the rim, in both themes
            // (emulator-5554 capture, #4175). RealityKit has no Filament tone mapping, so
            // the same constant renders as a dark saturated blue that reads as car paint.
            // iOS therefore emits a light cyan tuned until its rendered pixels match those,
            // and the preset's strength (1.5, still the figure the summary prints) scales it.
            material.emissiveColor = .init(color: color(Self.glowRenderedColor))
            material.emissiveIntensity = preset.traitAmount / Self.glowReferenceStrength
                * Self.glowRenderedIntensity
        }
        return material
    }

    // MARK: Labels

    /// A caption chip under a ball — Android draws its labels as Compose chips over the
    /// scene; here the chip is rendered once to a texture and hung on the wall, so it moves
    /// with the camera like the ball it names.
    private static func label(_ text: String, dark: Bool) -> Entity? {
        guard let image = chipImage(text, dark: dark),
              let texture = try? TextureResource(image: image, withName: nil,
                                                 options: .init(semantic: .color)) else { return nil }
        let width = Float(image.width) / chipScale * metresPerPoint
        let height = Float(image.height) / chipScale * metresPerPoint
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

    /// Render scale of the chip textures (pixels per point).
    private static let chipScale: Float = 3

    /// Android's caption pill (`MaterialsDemo.kt`): the page surface with on-surface text —
    /// white and dark text in light, dark and light text in dark — `Radius.sm` corners,
    /// `Space.xs` padding, a fixed width, and at most two centred lines.
    private static func chipImage(_ text: String, dark: Bool) -> CGImage? {
        // Keep the dash with the word before it, so a two-line name breaks after the dash
        // ("Car paint —" / "clearcoat") as Android's does, not before it.
        let chip = Text(text.replacingOccurrences(of: " — ", with: "\u{00A0}— "))
            .font(SceneViewTokens.TypeScale.caption)
            .multilineTextAlignment(.center)
            .lineLimit(2)
            .fixedSize(horizontal: false, vertical: true)
            .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
            .padding(SceneViewTokens.Space.xs)
            .frame(width: chipWidth)
            .background(SceneViewTokens.HomeColor.surface,
                        in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.sm, style: .continuous))
            // Android's pills sit on the grey studio; the iOS stage is the pale themed
            // gradient, so a hairline keeps a white pill from dissolving into it.
            .overlay(
                RoundedRectangle(cornerRadius: SceneViewTokens.Radius.sm, style: .continuous)
                    .strokeBorder(SceneViewTokens.HomeColor.outline, lineWidth: 1)
            )
            .environment(\.colorScheme, dark ? .dark : .light)
        let renderer = ImageRenderer(content: chip)
        renderer.scale = CGFloat(chipScale)
        renderer.proposedSize = ProposedViewSize(width: chipWidth, height: nil)
        return renderer.cgImage
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

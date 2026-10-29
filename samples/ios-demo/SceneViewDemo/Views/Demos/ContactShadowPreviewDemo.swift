import SwiftUI
import Combine
import RealityKit
import SceneViewSwift

/// Contact Shadow Preview — the iOS port of Android's `ContactShadowPreviewDemo.kt`.
///
/// Two identical boxes stand in a neutral room. The left one bounces and lands on a soft
/// contact shadow that tightens, darkens and slides back under it as it touches down; the
/// right one hovers with no shadow at all. Above the wall, a TV hangs over its own contact
/// shadow, whose preset (Floor / Wall / Table) is picked next to it.
///
/// **Why a procedural quad and not `GroundingShadowComponent`.** RealityKit's grounding
/// shadow is a yes/no switch: no intensity, no preset shape, and it only falls on the
/// ground, so it can neither darken under the slider nor sit on the wall behind the TV.
/// The demo uses the SDK's `ContactShadowNode` instead — what Android's `contact_shadow.mat`
/// draws: a black quad whose opacity is a soft elliptical falloff per preset, scaled by the
/// preset intensity and the slider, and faded and spread as the box lifts
/// (`ContactShadowMath` mirrors Android's `DemoMath`).
///
/// The key light deliberately casts no shadow, as on Android: the contact shadow must be
/// the only grounding cue on screen.
struct ContactShadowPreviewDemo: View {
    @State private var stage = ContactShadowStage()
    @State private var pose = ContactShadowMath.homePose
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            SceneViewTokens.Stage.background
            SceneView { root in
                stage.install(in: root)
            }
            .environment(Self.studio)
            .mainLight(.custom(stage.keyLight))
            .autoCenterContent(false)
            .cameraControls(.orbit)
            .cameraPose(pose)
            .onCameraChanged { reported in
                Task { @MainActor in
                    stage.cameraAzimuth = reported.azimuth
                    if pose != reported { pose = reported }
                }
            }
            .accessibilityIdentifier("contact-shadow-stage")
        }
        .ignoresSafeArea()
        .demoChrome(
            title: "Contact Shadow Preview",
            dock: [
                DockItem(icon: "circle.lefthalf.filled", label: "Shadows", control: "shadows",
                         selected: stage.shadowsEnabled) {
                    stage.setShadowsEnabled(!stage.shadowsEnabled)
                },
            ],
            onReset: reset,
            accessory: { wallBeat },
            controls: { controls }
        )
        .onAppear { stage.qaMode = qaMode }
        .onChange(of: qaMode) { _, value in stage.qaMode = value }
        .onChange(of: scenePhase) { _, phase in stage.active = phase == .active }
        .onDisappear { stage.stop() }
    }

    /// The studio HDR lights the matte room; its skybox fills what the wall does not cover,
    /// as Android's `createSkybox = true` does.
    private static var studio: SceneEnvironment {
        var environment = SceneEnvironment.studio
        environment.intensity = 1.0
        return environment
    }

    /// Android's reset: shadows on, intensity 1×, the Wall preset, the hop clock back to 0
    /// and the camera home. Motion is left as the viewer set it.
    private func reset() {
        stage.reset()
        pose = ContactShadowMath.homePose
    }

    /// The TV's preset picker with a one-line verdict for the preset in force — Android's
    /// `WallShadowBeat`, kept on the stage so the wall pool can be watched while it changes.
    private var wallBeat: some View {
        VStack(spacing: SceneViewTokens.Chrome.clusterGap) {
            DemoOptionStrip(ContactShadowContext.allCases, selection: Binding(
                get: { stage.wallContext }, set: { stage.setWallContext($0) }
            )) { $0.chipLabel }
            DemoHint(stage.wallContext.wallVerdict)
        }
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
            Text(stage.shadowVisible ? "Grounded vs floating" : "Shadows off — grounding cue gone")
                .font(.headline)
                .accessibilityIdentifier("contact-shadow-peek")
            Toggle("Contact shadows", isOn: Binding(
                get: { stage.shadowsEnabled }, set: { stage.setShadowsEnabled($0) }
            ))
            .accessibilityIdentifier("contact-shadow-toggle")
            Toggle("Bounce motion", isOn: Binding(
                get: { stage.motionEnabled }, set: { stage.motionEnabled = $0 }
            ))
            .accessibilityIdentifier("contact-shadow-motion")
            LabeledSlider(
                label: "Shadow intensity",
                value: Binding(get: { stage.intensityFactor }, set: { stage.setIntensityFactor($0) }),
                range: 0...1.5,
                valueText: stage.intensityFactor
                    .formatted(.number.precision(.fractionLength(2)).locale(Locale(identifier: "en_US"))) + "×"
            )
        }
    }
}

// MARK: - Presets

/// Demo-only labels for the SDK's `ContactShadowContext` presets (Android's chip and legend
/// strings).
extension ContactShadowContext {
    var chipLabel: String {
        switch self {
        case .floor: "Floor"
        case .wall: "Wall"
        case .tableTop: "Table"
        }
    }

    /// What each preset does on a wall — every preset gets one, as on Android.
    var wallVerdict: String {
        switch self {
        case .floor: "A broader, darker shadow suited to objects on the floor."
        case .wall: "A soft shadow behind the TV shows where it meets the wall."
        case .tableTop: "A compact shadow suited to objects resting on a table."
        }
    }
}

// MARK: - Math

/// Pure functions shared with Android's `DemoMath` and `contact_shadow.mat`, so the motion
/// and the pool read the same on both platforms (and can be unit-tested).
enum ContactShadowMath {
    static let boxEdge: Float = 0.38
    static let boxHalfSpacing: Float = 0.38
    static let boxesZ: Float = 0.35
    static let shadowQuad: Float = 0.8
    static let keyLightDirection = SIMD3<Float>(-0.35, -1, -0.4)
    static let maxHop: Float = 0.34
    static let hopPeriod: Double = 2.6
    static let hoverPeriod: Double = 3.4

    /// Android's eye (0, 1.35, 3.3). Android aims at (0, 0.75, -0.5) for its landscape card; a
    /// portrait phone with the option strip and hint at the bottom would hide the grounded box's
    /// pool behind them, so iOS tilts down to (0, 0.4, -0.5) and lifts the boxes to mid-screen.
    static let cameraTarget = SIMD3<Float>(0, 0.4, -0.5)
    static var homePose: SceneCameraPose {
        let eye = SIMD3<Float>(0, 1.35, 3.3)
        let offset = eye - cameraTarget
        let distance = simd_length(offset)
        return SceneCameraPose(azimuth: atan2(offset.x, offset.z),
                               elevation: asin(offset.y / distance),
                               distance: distance,
                               target: cameraTarget)
    }

    /// Height of the bouncing box above the floor: |sin| hops, landing every period.
    static func bounceHeight(_ seconds: Double) -> Float {
        guard seconds > 0 else { return 0 }
        let phase = (seconds / hopPeriod).truncatingRemainder(dividingBy: 1)
        return maxHop * Float(abs(sin(Double.pi * phase)))
    }

    /// The pool fades as the box lifts…
    static func intensityFactor(_ height: Float) -> Float {
        1 - 0.55 * clamp01(height / maxHop)
    }

    /// …widens…
    static func spread(_ height: Float) -> Float {
        1 + 0.5 * clamp01(height / maxHop)
    }

    /// …and slides along the light's ground projection (x, z).
    static func shadowOffset(_ height: Float) -> SIMD2<Float> {
        let d = keyLightDirection
        let down = max(abs(d.y), 1e-4)
        return [d.x * height / down, d.z * height / down]
    }

    /// Centre height of the floating twin: high, bobbing slowly, never landing.
    static func floatHoverY(_ seconds: Double) -> Float {
        let phase = (max(seconds, 0) / hoverPeriod).truncatingRemainder(dividingBy: 1)
        return 0.62 + 0.05 * Float(sin(2 * Double.pi * phase))
    }

    /// Degrees the orbit has turned away from front-on, in 0…180.
    static func yawDeviationDegrees(_ azimuth: Float) -> Float {
        var degrees = (azimuth * 180 / .pi).truncatingRemainder(dividingBy: 360)
        if degrees > 180 { degrees -= 360 }
        if degrees < -180 { degrees += 360 }
        return abs(degrees)
    }

    /// Android's `orbitLabelFadeAlpha`: labels hold to 25°, are gone by 45°, smoothstep
    /// between, so "Shadow" and "No shadow" never collapse into one blob side-on.
    static func labelAlpha(azimuth: Float) -> Float {
        let deviation = yawDeviationDegrees(azimuth)
        if deviation <= 25 { return 1 }
        if deviation >= 45 { return 0 }
        let t = (deviation - 25) / 20
        return 1 - t * t * (3 - 2 * t)
    }

    static func clamp01(_ x: Float) -> Float { min(max(x, 0), 1) }
}

// MARK: - Stage

/// Owns the room's entities and drives them from RealityKit's update event, so the hop
/// never rebuilds SwiftUI. Controls write straight to the materials.
@MainActor @Observable
final class ContactShadowStage {
    private(set) var shadowsEnabled = true
    private(set) var intensityFactor: Float = 1
    private(set) var wallContext: ContactShadowContext = .wall
    var motionEnabled = true

    /// Read by the update loop only; never drives a view.
    @ObservationIgnored var qaMode = false
    @ObservationIgnored var active = true
    @ObservationIgnored var cameraAzimuth: Float = 0

    var shadowVisible: Bool { shadowsEnabled && intensityFactor > 0 }

    /// Created once: `.mainLight(.custom)` compares lights by entity identity.
    let keyLight: LightNode = {
        let light = LightNode.directional(color: .white, intensity: 3_000, castsShadow: false)
        let origin = SIMD3<Float>(0, 4, 0)
        return light.position(origin).lookAt(origin + ContactShadowMath.keyLightDirection)
    }()

    @ObservationIgnored private var clock: Double = 0
    private let groundedBox = ContactShadowStage.box()
    private let floatingBox = ContactShadowStage.box()
    private let floorShadow = ContactShadowNode(
        width: ContactShadowMath.shadowQuad, length: ContactShadowMath.shadowQuad, context: .floor)
    private let wallShadow = ContactShadowNode(width: 2.4, length: 1.6, context: .wall, plane: .vertical)
    private let groundedLabel = ContactShadowStage.label(["Shadow", "No shadow"])
    private let floatingLabel = ContactShadowStage.label(["No shadow"])
    @ObservationIgnored private var updates: (any Cancellable)?
    @ObservationIgnored private var attachment: Task<Void, Never>?

    func install(in root: Entity) {
        buildRoom(in: root)
        floorShadow.entity.name = "contact-shadow-floor"
        wallShadow.entity.name = "contact-shadow-wall"
        wallShadow.position = [0, 1.3, -1.99]
        root.addChild(wallShadow.entity)
        root.addChild(floorShadow.entity)
        root.addChild(groundedBox)
        root.addChild(floatingBox)
        root.addChild(groundedLabel.entity)
        root.addChild(floatingLabel.entity)
        applyShadowState()
        updateFrame()

        updates?.cancel()
        // Entity subscriptions need the root attached; the cancellable task owns that wait.
        attachment?.cancel()
        attachment = Task { @MainActor [weak self, weak root] in
            while let root, root.scene == nil {
                guard !Task.isCancelled else { return }
                try? await Task.sleep(for: .milliseconds(16))
            }
            guard !Task.isCancelled, let self, let scene = root?.scene else { return }
            self.updates = scene.subscribe(to: SceneEvents.Update.self) { [weak self] event in
                let delta = event.deltaTime
                MainActor.assumeIsolated { self?.tick(delta) }
            }
        }
    }

    func stop() {
        attachment?.cancel()
        attachment = nil
        updates?.cancel()
        updates = nil
    }

    func setShadowsEnabled(_ value: Bool) {
        shadowsEnabled = value
        applyShadowState()
    }

    func setIntensityFactor(_ value: Float) {
        intensityFactor = min(max(value, 0), 1.5)
        applyShadowState()
    }

    func setWallContext(_ value: ContactShadowContext) {
        wallContext = value
        applyShadowState()
    }

    func reset() {
        shadowsEnabled = true
        intensityFactor = 1
        wallContext = .wall
        clock = 0
        applyShadowState()
        updateFrame()
    }

    // MARK: Frame

    private func tick(_ delta: TimeInterval) {
        // As on Android, QA mode and the Motion toggle both park the clock at t = 0: the box
        // lands and the pool shows at full strength, instead of freezing mid-hop.
        if qaMode || !motionEnabled {
            clock = 0
        } else if active {
            clock += min(delta, 0.1)
        }
        updateFrame()
    }

    private func updateFrame() {
        let edge = ContactShadowMath.boxEdge
        let half = ContactShadowMath.boxHalfSpacing
        let z = ContactShadowMath.boxesZ
        let hop = ContactShadowMath.bounceHeight(clock)
        let hover = ContactShadowMath.floatHoverY(clock)

        groundedBox.position = [-half, edge / 2 + hop, z]
        floatingBox.position = [half, hover, z]

        let slide = ContactShadowMath.shadowOffset(hop)
        floorShadow.position = [-half + slide.x, 0.004, z + slide.y]
        floorShadow.size = SIMD2(repeating: ContactShadowMath.shadowQuad * ContactShadowMath.spread(hop))
        floorShadow.entity.components.set(OpacityComponent(opacity: ContactShadowMath.intensityFactor(hop)))

        let alpha = ContactShadowMath.labelAlpha(azimuth: cameraAzimuth)
        groundedLabel.entity.position = [-half, edge + hop + 0.2, z]
        floatingLabel.entity.position = [half, hover + edge / 2 + 0.2, z]
        for label in [groundedLabel, floatingLabel] {
            label.entity.isEnabled = alpha > 0.01
            label.entity.components.set(OpacityComponent(opacity: alpha))
        }
    }

    /// Shadow visibility, preset and intensity, written straight to the two quads.
    private func applyShadowState() {
        floorShadow.entity.isEnabled = shadowsEnabled
        wallShadow.entity.isEnabled = shadowsEnabled
        floorShadow.intensity = ContactShadowContext.floor.intensity * intensityFactor
        wallShadow.context = wallContext
        wallShadow.intensity = wallContext.intensity * intensityFactor
        // The grounded box says "No shadow" whenever none is drawn, as Android's legend does.
        groundedLabel.show(shadowVisible ? 0 : 1)
    }

    // MARK: Room

    private func buildRoom(in root: Entity) {
        let floor = ModelEntity(mesh: .generatePlane(width: 6, depth: 6),
                                materials: [Self.matte(0xCFCBC4, roughness: 0.85)])
        root.addChild(floor)
        let wall = ModelEntity(mesh: .generatePlane(width: 6, height: 3),
                               materials: [Self.matte(0xE8E6E1, roughness: 0.9)])
        wall.position = [0, 1.5, -2]
        root.addChild(wall)

        let tv = Entity()
        tv.position = [0, 1.3, -1.98]
        let body = ModelEntity(mesh: .generateBox(width: 1.26, height: 0.74, depth: 0.04),
                               materials: [Self.matte(0x20242A, roughness: 0.8)])
        body.position.z = 0.02
        let screen = ModelEntity(mesh: .generateBox(width: 1.20, height: 0.68, depth: 0.01),
                                 materials: [Self.matte(0x06080C, roughness: 0.15)])
        screen.position.z = 0.045
        tv.addChild(body)
        tv.addChild(screen)
        root.addChild(tv)
    }

    /// One material for both boxes: the shadow must be the only difference between them.
    private static let boxMaterial = matte(0xB4693C, roughness: 0.7)

    private static func box() -> ModelEntity {
        ModelEntity(mesh: .generateBox(size: ContactShadowMath.boxEdge), materials: [boxMaterial])
    }

    private static func matte(_ hex: UInt32, roughness: Float) -> PhysicallyBasedMaterial {
        var material = PhysicallyBasedMaterial()
        material.baseColor = .init(tint: UIColor(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1))
        material.roughness = .init(floatLiteral: roughness)
        material.metallic = .init(floatLiteral: 0)
        return material
    }

    // MARK: Labels

    /// A camera-facing caption on a dark scrim (Android's 0.62 × 0.16 m `TextNode`).
    /// Text is real geometry, so it stays sharp at any zoom.
    @MainActor struct ShadowLabel {
        let entity: Entity
        let texts: [ModelEntity]

        func show(_ index: Int) {
            for (i, text) in texts.enumerated() { text.isEnabled = i == index }
        }
    }

    private static func label(_ strings: [String]) -> ShadowLabel {
        let card = Entity()
        // Opaque and outside tone mapping: a translucent scrim blends over the HDR floor and the
        // tone-mapped ink tops out near 85 % grey, which left the text near 3.5:1. Solid near-black
        // under pure white holds well past 7:1; `OpacityComponent` still fades the whole card.
        var scrim = UnlitMaterial(applyPostProcessToneMap: false)
        scrim.color = .init(tint: UIColor(white: 0.12, alpha: 1))
        let background = ModelEntity(mesh: .generatePlane(width: 0.62, height: 0.16, cornerRadius: 0.05),
                                     materials: [scrim])
        card.addChild(background)
        let font = MeshResource.Font.systemFont(ofSize: 0.075, weight: .semibold)
        var ink = UnlitMaterial(applyPostProcessToneMap: false)
        ink.color = .init(tint: .white)
        let texts = strings.map { string -> ModelEntity in
            let mesh = MeshResource.generateText(string, extrusionDepth: 0.001, font: font)
            let text = ModelEntity(mesh: mesh, materials: [ink])
            let bounds = mesh.bounds
            text.position = [-bounds.center.x, -bounds.center.y, 0.004]
            card.addChild(text)
            return text
        }
        let label = ShadowLabel(entity: BillboardNode(child: card).entity, texts: texts)
        label.show(0)
        return label
    }
}

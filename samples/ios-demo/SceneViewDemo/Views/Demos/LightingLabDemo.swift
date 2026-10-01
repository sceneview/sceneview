import SwiftUI
import RealityKit
import SceneViewSwift

/// **Lighting Lab** — one fixed key over a warm studio image, every environment knob live.
///
/// iOS twin of Android's `LightingLabDemo` (`samples/android-demo/.../demos/LightingLabDemo.kt`):
/// the same stage as `lighting` (the Damaged Helmet at 0.5 m, a chrome and a matte probe ball at
/// its feet, a slate floor), the same single focused spot key at 48° / 38° with its unlit marker,
/// the same `studio_warm` bench environment and the same sunset reflection probe.
///
/// ## What is real here
///
/// This id used to route to a "Reflection Probes" screen whose probe HDR *was* the global HDR and
/// whose every mesh sat inside the probe, so switching the probe changed nothing
/// (`parity: fake` in `parity-manifest.yml`). Every control below now drives RealityKit:
///
/// - **Environment intensity** — ``SceneEnvironment/intensity``, applied live to the
///   `ImageBasedLightComponent` exponent.
/// - **Draw the sky** — ``SceneEnvironment/showSkybox``.
/// - **Local reflections** / **Reflection area** — a ``ReflectionProbeNode`` sphere at the stage
///   centre carrying the *sunset* HDR. While the camera is inside its radius, every stage mesh
///   receives the probe's light instead of the studio's (`ImageBasedLightReceiverComponent`);
///   shrink the area under the camera's distance and the stage goes back to the studio. That is
///   Android's `ReflectionProbeNode(cameraPosition:)` rule.
///
/// ## What iOS cannot do, and does not pretend to
///
/// Android's environment rotation, Camera section (exposure) and Frame section (contact
/// shading, fog, edge smoothing, dithering) are Filament options. Turning the entity that
/// carries the `ImageBasedLightComponent` was measured on the iOS 26.3 simulator: 0° and 174°
/// render pixel-identical, so RealityKit gives no way to rotate the environment light either. RealityKit's `RealityView` on iOS exposes none of them
/// — no exposure, no SSAO toggle, no fog, no MSAA / FXAA / dithering switch — so those controls
/// are absent here rather than simulated, and the sheet says so. The manifest row carries the
/// same reason.
struct LightingLabDemo: View {

    // MARK: - Controls (Android defaults)

    /// Linear multiplier on the bench HDR. Android's 500–60 000 lux around a 10 000 lux default is
    /// the same ×0.05–×6 span; iOS IBL has no absolute unit, so the slider shows the ratio.
    @State private var iblIntensity: Double = 1
    @State private var showSky = false
    @State private var probeEnabled = false
    @State private var probeZone: Double = Self.probeZoneDefault

    /// The sunset HDR the probe carries, decoded the first time the probe is switched on —
    /// Android's `probeEnvironmentRequested` latch: never before, and kept once built.
    @State private var probeEnvironment: EnvironmentResource?
    @State private var probeLoadStarted = false
    /// Whether the camera stands inside the probe's sphere. Only written when it flips.
    @State private var cameraInsideZone = true
    /// Distance from the camera to the probe centre, for the sheet's readout (per 5 cm).
    @State private var cameraDistance: Float = 0
    /// Live handles the content closure publishes, so a slider tick can reach them without
    /// rebuilding the scene. A reference type on purpose: writing it must not re-render.
    @State private var live = LiveHandles()

    /// The hero, loaded once. `nil` until it lands (or if it fails — the probes still stand).
    @State private var heroNode: ModelNode?
    @State private var heroLoadFailed = false
    @Environment(\.analyticsSampleId) private var analyticsSampleId
    /// Android's idle orbit: on until the viewer takes the camera.
    @State private var orbiting = true
    @State private var orbitStart = Date()
    @State private var orbitStartYaw: Double = Self.staticYawDegrees
    @State private var heldPose: SceneCameraPose?
    @State private var viewport: CGSize = .zero

    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false

    // MARK: - Stage constants (Android `LightingStage`)

    private static let heroUnits: Float = 0.5
    private static let probeRadius: Float = 0.065
    private static let probeSpacing: Float = 0.34
    private static let probeOffsetZ: Float = 0.14
    private static let floorY: Float = -heroUnits / 2 - 0.01
    private static let floorSize: Float = 60

    private static let orbitElevationDegrees: Float = 20
    private static let orbitPeriod: Double = 26
    private static let staticYawDegrees: Double = 32
    private static let subjectExtent = SIMD3<Float>((0.34 + 0.065) * 2, 0.5, 0.5)

    /// The bench key — Android's `BENCH_KEY_AZIMUTH` and `KEY_ELEVATION_DEGREES`, on the
    /// `RIG_RADIUS` sphere, at the lumens the iOS `lighting` rig uses at that radius.
    private static let keyAzimuthDegrees: Double = 48
    private static let keyElevationDegrees: Double = 38
    private static let rigRadius: Float = 1.35
    private static let keyIntensity: Float = 90_000 * 0.38

    static let probeZoneDefault: Double = 2.5
    static let probeZoneRange: ClosedRange<Double> = 0.5...6
    static let intensityRange: ClosedRange<Double> = 0.05...6
    /// Where the probe sphere sits: the stage centre, like Android's `Position(0, 0, 0)`.
    static let probeCentre: SIMD3<Float> = .zero

    // MARK: - Derived state

    /// The bench environment with every live knob applied.
    private var environment: SceneEnvironment {
        var bench = SceneEnvironment.warm
        bench.intensity = SceneEnvironment.warm.intensity * Float(iblIntensity)
        bench.showSkybox = showSky
        return bench
    }

    private var probeActive: Bool {
        probeEnabled && probeEnvironment != nil && cameraInsideZone
    }

    /// Rebuild key for `.contentID(_:)`: only what the content closure reads. Intensity
    /// and the sky are applied live by `SceneView` and the probe handle.
    private var contentKey: String {
        "\(heroNode == nil ? "bare" : "hero")-\(probeActive ? "probe" : "studio")"
    }

    // MARK: - Body

    var body: some View {
        GeometryReader { proxy in
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !orbiting)) { context in
                scene(pose: pose(at: context.date))
            }
            .onAppear {
                viewport = proxy.size
                if qaMode { orbiting = false }
                heldPose = framingPose(yawDegrees: Self.staticYawDegrees)
                if let heldPose { noteCameraPosition(heldPose.cameraPosition()) }
            }
            .onChange(of: proxy.size) { _, size in
                viewport = size
                if !orbiting { heldPose = framingPose(yawDegrees: orbitStartYaw) }
            }
        }
        .ignoresSafeArea()
        .background(SceneViewTokens.Stage.background)
        .task { await loadHeroIfNeeded() }
        .onChange(of: probeEnabled) { _, enabled in
            if enabled { Task { await loadProbeEnvironmentIfNeeded() } }
        }
        .onChange(of: probeZone) { _, _ in
            if let position = live.cameraPosition { noteCameraPosition(position) }
        }
        .onChange(of: iblIntensity) { _, _ in _ = live.probe?.intensity(probeIntensity) }
        .demoChrome(
            dock: [
                DockItem(icon: "cloud.fill", label: "Sky", selected: showSky) { showSky.toggle() },
                DockItem(icon: "circle.fill", label: "Reflections", selected: probeEnabled) {
                    probeEnabled.toggle()
                },
            ],
            onReset: reset,
            accessory: { DemoHint("Toggle the sky or reflections to compare") }
        ) {
            controls
        }
    }

    private func scene(pose: SceneCameraPose?) -> some View {
        SceneView { root in
            let stage = Entity()
            stage.name = "LightingLabStage"
            root.addChild(stage)
            addStage(to: stage)
            addKey(to: root)
            if probeActive, let probeEnvironment {
                let probe = Self.makeProbe(radius: Float(probeZone), intensity: probeIntensity,
                                           environment: probeEnvironment)
                root.addChild(probe.entity)
                Self.attach(stage, to: probe)
                live.probe = probe
            } else {
                live.probe = nil
            }
        }
        // One key, one image: the system key and fill would light the stage on top of both.
        .mainLight(.disabled)
        .fillLight(.disabled)
        .environment(environment)
        .contentID(contentKey)
        .cameraControls(.orbit)
        .autoCenterContent(false)
        .cameraPose(pose)
        .onCameraChanged { reported in
            Task { @MainActor in noteCamera(reported) }
        }
    }

    /// The probe carries the sunset at its own preset level, times the same intensity knob, so
    /// the slider keeps working inside the zone.
    private var probeIntensity: Float {
        SceneEnvironment.sunset.intensity * Float(iblIntensity)
    }

    private func reset() {
        iblIntensity = 1
        showSky = false
        probeEnabled = false
        probeZone = Self.probeZoneDefault
        orbitStartYaw = Self.staticYawDegrees
        heldPose = framingPose(yawDegrees: Self.staticYawDegrees)
        orbiting = false
    }

    // MARK: - Controls

    @ViewBuilder
    private var controls: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
            Text("Compare how the environment light and local reflections change the same model. Toggle one effect to see the difference.")
                .font(SceneViewTokens.TypeScale.body)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                .fixedSize(horizontal: false, vertical: true)

            Text("Environment")
                .font(SceneViewTokens.TypeScale.bodySemibold)
            Text("Environment lighting uses a panoramic image to light the model and create reflections.")
                .font(SceneViewTokens.TypeScale.body)
                .fixedSize(horizontal: false, vertical: true)
            LabeledSlider(label: "Environment intensity", value: $iblIntensity,
                          range: Self.intensityRange, decimals: 2, unit: "×")
            Toggle("Draw the sky", isOn: $showSky)
            Toggle("Local reflections", isOn: $probeEnabled)
            VStack(alignment: .leading, spacing: SceneViewTokens.Space.xs) {
                LabeledSlider(label: "Reflection area", value: $probeZone,
                              range: Self.probeZoneRange, decimals: 1, unit: "m")
                    .disabled(!probeEnabled)
                Text(probeReadout)
                    .font(SceneViewTokens.TypeScale.caption)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                    .monospacedDigit()
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("lighting-lab-probe-readout")
            }

            Text("Environment rotation, exposure, contact shading, fog and edge smoothing are Android-only: the iOS RealityKit view has no setting for them.")
                .font(SceneViewTokens.TypeScale.caption)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                .fixedSize(horizontal: false, vertical: true)
        }
        .tint(SceneViewTheme.primary)
    }

    /// What the probe is doing right now, in words.
    private var probeReadout: String {
        let distance = String(format: "%.1f", cameraDistance)
        guard probeEnabled else {
            return "Inside the area the model reflects a sunset instead of the studio. Camera \(distance) m from the centre."
        }
        guard probeEnvironment != nil else { return "Loading the sunset…" }
        return cameraInsideZone
            ? "Camera \(distance) m from the centre: inside the area, the model reflects the sunset."
            : "Camera \(distance) m from the centre: outside the area, the model reflects the studio."
    }

    // MARK: - Camera

    private func pose(at date: Date) -> SceneCameraPose? {
        guard orbiting else { return heldPose }
        return framingPose(yawDegrees: yaw(at: date))
    }

    private func yaw(at date: Date) -> Double {
        orbitStartYaw + date.timeIntervalSince(orbitStart) / Self.orbitPeriod * 360
    }

    /// Every reported pose feeds the probe's enter/exit test; a drag while the orbit runs also
    /// hands the camera to the viewer.
    private func noteCamera(_ reported: SceneCameraPose) {
        noteCameraPosition(reported.cameraPosition())
        guard orbiting else { return }
        let expected = framingPose(yawDegrees: yaw(at: Date()))
        var yawDelta = abs(reported.azimuth - expected.azimuth)
            .truncatingRemainder(dividingBy: 2 * .pi)
        yawDelta = min(yawDelta, 2 * .pi - yawDelta)
        if yawDelta > 0.1 || abs(reported.elevation - expected.elevation) > 0.08
            || abs(reported.distance - expected.distance) > 0.15 {
            orbitStartYaw = yaw(at: Date())
            heldPose = reported
            orbiting = false
        }
    }

    /// Android's probe rule: the override applies while the camera is inside the sphere.
    private func noteCameraPosition(_ position: SIMD3<Float>) {
        live.cameraPosition = position
        let inside = Self.zoneContains(camera: position, radius: Float(probeZone))
        if inside != cameraInsideZone { cameraInsideZone = inside }
        let distance = simd_distance(position, Self.probeCentre)
        if abs(distance - cameraDistance) >= 0.05 { cameraDistance = distance }
    }

    /// The pose that fits the helmet and both probes between the top chrome and the dock at any
    /// yaw — the same fit as the `lighting` screen, so the two stages frame identically.
    private func framingPose(yawDegrees: Double) -> SceneCameraPose {
        let width = Float(max(viewport.width, 1))
        let height = Float(max(viewport.height, 1))
        let top = Float(SceneViewTokens.Chrome.scrimTop)
        let bottom = Float(SceneViewTokens.Chrome.scrimBottomMin)
        let band = max(height - top - bottom, height * 0.35)
        let tanV = tan(Float.pi / 6)
        let tanH = tanV * width / height
        let tanBand = tanV * band / height
        let fill: Float = qaMode ? 0.9 : 0.8
        let elevation = Self.orbitElevationDegrees * .pi / 180
        var distance: Float = 0.5
        for step in 0..<24 {
            let a = Float(step) * .pi / 12
            let right = SIMD3<Float>(cos(a), 0, -sin(a))
            let up = SIMD3<Float>(-sin(elevation) * sin(a), cos(elevation), -sin(elevation) * cos(a))
            let back = SIMD3<Float>(cos(elevation) * sin(a), sin(elevation), cos(elevation) * cos(a))
            for sx in [-1, 1] as [Float] {
                for sy in [-1, 1] as [Float] {
                    for sz in [-1, 1] as [Float] {
                        let corner = Self.subjectExtent / 2 * SIMD3(sx, sy, sz)
                        let depth = simd_dot(corner, back)
                        distance = max(distance, depth + abs(simd_dot(corner, right)) / (tanH * fill))
                        distance = max(distance, depth + abs(simd_dot(corner, up)) / (tanBand * fill))
                    }
                }
            }
        }
        let a = Float(yawDegrees * .pi / 180)
        let up = SIMD3<Float>(-sin(elevation) * sin(a), cos(elevation), -sin(elevation) * cos(a))
        let bandCentreOffset = (top + band / 2) - height / 2
        let shift = bandCentreOffset / (height / 2) * distance * tanV
        return SceneCameraPose(azimuth: a, elevation: elevation, distance: distance,
                               target: up * shift)
    }

    // MARK: - Stage

    @MainActor
    private func loadHeroIfNeeded() async {
        guard heroNode == nil, !heroLoadFailed else { return }
        do {
            let node = try await ModelNode.load("khronos_damaged_helmet")
            _ = node.scaleToUnits(Self.heroUnits)
            _ = node.centerOrigin()
            heroNode = node
        } catch {
            heroLoadFailed = true
            DemoAnalytics.shared.log(.modelLoadFailed(sampleId: analyticsSampleId ?? "lighting-lab",
                                                      reason: DemoAnalytics.modelLoadReason(for: error)))
        }
    }

    @MainActor
    private func loadProbeEnvironmentIfNeeded() async {
        guard !probeLoadStarted else { return }
        probeLoadStarted = true
        probeEnvironment = try? await SceneEnvironment.sunset.load()
        if probeEnvironment == nil { probeLoadStarted = false }
    }

    /// The hero, the gaffer's pair and a slate floor — the `lighting` stage, value for value.
    @MainActor
    private func addStage(to stage: Entity) {
        if let hero = heroNode {
            stage.addChild(hero.entity)
        }
        let chrome = GeometryNode.sphere(
            radius: Self.probeRadius,
            material: .pbr(color: .white, metallic: 1.0, roughness: 0.05)
        )
        chrome.entity.position = .init(x: -Self.probeSpacing, y: Self.floorY + Self.probeRadius,
                                       z: Self.probeOffsetZ)
        stage.addChild(chrome.entity)

        let matte = GeometryNode.sphere(
            radius: Self.probeRadius,
            material: .pbr(
                color: SimpleMaterial.Color(red: 0.73, green: 0.75, blue: 0.78, alpha: 1),
                metallic: 0.0,
                roughness: 0.85
            )
        )
        matte.entity.position = .init(x: Self.probeSpacing, y: Self.floorY + Self.probeRadius,
                                      z: Self.probeOffsetZ)
        stage.addChild(matte.entity)

        var floorMaterial = PhysicallyBasedMaterial()
        floorMaterial.baseColor = .init(tint: SceneViewTokens.Stage.lightingFloor)
        floorMaterial.roughness = .init(floatLiteral: 0.45)
        floorMaterial.metallic = .init(floatLiteral: 0)
        floorMaterial.specular = .init(floatLiteral: 0.55)
        let floor = ModelEntity(
            mesh: .generatePlane(width: Self.floorSize, depth: Self.floorSize),
            materials: [floorMaterial]
        )
        floor.position = .init(x: 0, y: Self.floorY, z: 0)
        stage.addChild(floor)
    }

    /// Android's `FOCUSED_SPOT` bench key: fixed, shadow-casting, drawn as an unlit marker.
    @MainActor
    private func addKey(to root: Entity) {
        let a = Float(Self.keyAzimuthDegrees * .pi / 180)
        let e = Float(Self.keyElevationDegrees * .pi / 180)
        let horizontal = Self.rigRadius * cos(e)
        let position = SIMD3<Float>(horizontal * sin(a), Self.rigRadius * sin(e), horizontal * cos(a))
        let key = LightNode.spot(
            color: .warm,
            intensity: Self.keyIntensity,
            innerAngle: .pi / 9,
            outerAngle: .pi / 5,
            attenuationRadius: 8
        )
        .position(position)
        .lookAt(.zero)
        .castsShadow(true)
        root.addChild(key.entity)

        let marker = GeometryNode.sphere(
            radius: 0.045,
            material: .unlit(color: SimpleMaterial.Color(red: 1, green: 0xF6 / 255, blue: 0xE8 / 255,
                                                         alpha: 1))
        )
        marker.entity.position = position
        root.addChild(marker.entity)
    }

    // MARK: - Probe (unit-tested in `LightingLabDemoTests`)

    /// The local probe: a sphere of `radius` at ``probeCentre`` carrying `environment`.
    @MainActor
    static func makeProbe(radius: Float, intensity: Float,
                          environment: EnvironmentResource?) -> ReflectionProbeNode {
        let probe = ReflectionProbeNode.sphere(radius: radius, intensity: intensity)
            .position(probeCentre)
        if let environment {
            probe.environmentTexture(environment)
        }
        return probe
    }

    /// Points `entity` and everything under it at the probe instead of the scene's global IBL.
    ///
    /// The receiver `environmentTexture(_:)` installs lives on the probe entity itself, so the
    /// geometry that should *show* the probe needs its own receiver targeting it — otherwise it
    /// keeps reflecting the global environment and the probe contributes nothing (#3158). Set on
    /// every descendant too, so a loaded model's inner meshes follow its root.
    @MainActor
    static func attach(_ entity: Entity, to probe: ReflectionProbeNode) {
        entity.components.set(ImageBasedLightReceiverComponent(imageBasedLight: probe.entity))
        for child in entity.children {
            attach(child, to: probe)
        }
    }

    /// Whether a camera at `camera` stands inside a probe sphere of `radius` at ``probeCentre``.
    static func zoneContains(camera: SIMD3<Float>, radius: Float) -> Bool {
        simd_distance(camera, probeCentre) <= radius
    }
}

/// Handles the content closure hands back to the view, outside SwiftUI's state graph.
@MainActor
private final class LiveHandles {
    var probe: ReflectionProbeNode?
    var cameraPosition: SIMD3<Float>?
}

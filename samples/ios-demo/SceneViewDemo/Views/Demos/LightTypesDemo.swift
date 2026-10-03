import SwiftUI
import RealityKit
import SceneViewSwift

/// **Lighting** — the three real answers to *where does a frame's light come from?*
///
/// ## Why this screen was rebuilt (#3587)
///
/// The previous version was a "pick a `LightManager.Type`" demo: three buttons —
/// Directional, Point, Spot — each adding one analytic light to a row of five
/// white spheres. QA on an iPhone SE reported it as broken ("je vois aucune
/// différence en fait", "les balles sont pas réflectives ?"), and it was, for
/// two independent reasons:
///
/// 1. **The demo's light was never the scene's light.** `SceneView` defaults
///    both light slots to `.systemDefault` (`SceneView.swift:88-89`), and
///    `provisionLightSlot` (`SceneView.swift:1573-1584`) adds a 10 000 lux
///    shadow-casting key plus a 3 000 lux fill on every build. The demo never
///    called `.mainLight(.disabled)`, so its own 2 000 lux directional / 5 000 lm
///    point / 8 000 lm spot was a rounding error on top of a fixed 13 000 lux
///    rig. Swapping it changed a few percent of the illumination.
/// 2. **Nothing in the scene could reflect.** The spheres were
///    `.pbr(color: .white, metallic: 0.3, roughness: 0.4)` and the view set no
///    `.environment(_:)`, so there was no IBL. A half-rough, mostly-dielectric
///    white ball with no environment has nothing to mirror.
///
/// Android hit the same wall and says so in `LightingDemo.kt`: *"The IBL is
/// dimmed for the analytic rigs: at full strength the ambient does the modelling
/// the key light is there to do, and moving the key barely changes the frame."*
///
/// ## Alignment with Android
///
/// Android's `lighting` demo was rebuilt from scratch in #3496 / #3497 into
/// three **rigs** over one shared stage. This screen is the iOS counterpart of
/// that rebuild, deliberately kept to the smallest thing that carries the same
/// idea:
///
/// - **Image** — an HDR environment and nothing else. The chrome probe mirrors
///   it, the matte probe reads it. Swapping the environment is the control.
/// - **Studio** — a three-point analytic rig (shadow-casting key, cool fill,
///   cold rim), each drawn as an unlit marker so a light is a thing you can
///   *see* rather than infer. The key's azimuth is the control.
/// - **Sun** — one sun on a clock via `DynamicSkyNode`: the hour drives colour,
///   elevation and intensity, and the sky swaps with it.
///
/// ## The stage
///
/// Android's `LightingStage`, value for value: the Khronos Damaged Helmet as the hero (0.5 m,
/// centred on the orbit target), a slate floor just under it, and the gaffer's pair of probe
/// balls at its feet. The first iOS cut had the probes alone, 0.28 m each, on a 4 m grey slab
/// that the auto-framing fitted instead of the subject — the balls came out as two small dots
/// on a grey band (#3907 parity audit). The camera now frames the subject, not the floor, and
/// circles it slowly like Android's idle orbit so the reflections travel.
///
/// The two probes, shared by all three rigs:
///
/// - The **chrome** probe (`metallic: 1, roughness: 0.05`) is a mirror. It shows
///   what the environment *is*, which is what makes an IBL swap legible at all.
///   This is the ball whose absence was the whole of "les balles sont pas
///   réflectives ?".
/// - The **matte** probe (`metallic: 0, roughness: 0.85`) shows where the light
///   *comes from*: its terminator is the key direction.
///
/// Every rig disables both system light slots, so what you see is the rig and
/// nothing else. That is the entire fix for "aucune différence".
struct LightingDemo: View {

    /// The three rigs, in the order Android lists them. `Image` opens because it
    /// is the rig most apps actually ship.
    enum Rig: Int, CaseIterable, Identifiable {
        case image, studio, sun

        var id: Int { rawValue }

        var analyticsMode: String {
            switch self {
            case .image: return "image"
            case .studio: return "studio"
            case .sun: return "sun"
            }
        }

        /// The rig a deep-link token names, `.image` for none or an unknown one.
        static func initial(_ token: String?) -> Rig {
            switch token {
            case "studio", "1", "movable-light": return .studio
            case "sun", "2", "dynamic-sky": return .sun
            default: return .image
            }
        }

        var title: String {
            switch self {
            case .image: return "Image"
            case .studio: return "Studio"
            case .sun: return "Sun"
            }
        }

        var icon: String {
            switch self {
            case .image: return "photo.fill"
            case .studio: return "lightbulb.fill"
            case .sun: return "sun.max.fill"
            }
        }

        /// Mirrors Android's `demo_lighting_rig_*_explainer` strings.
        var explainer: String {
            switch self {
            case .image:
                return "An HDR environment is the whole rig: the chrome ball mirrors it, the matte ball reads it. Swap the environment and the light changes with it."
            case .studio:
                return "A three-point rig of analytic lights — a shadow-casting key, a cool fill, a cold rim — each drawn where it stands. Move the key and watch the modeling change."
            case .sun:
                return "One sun on a clock. The hour drives its color, height and strength, and the sky swaps with it, so midnight is night rather than a dark noon."
            }
        }
    }

    /// Opens on Image unless a link asked for a rig: `?tab=` (`studio`, `1`…)
    /// or one of the cards this screen absorbed in the samples audit, step 0
    /// — `environment` (Image), `movable-light` (Studio), `dynamic-sky` (Sun),
    /// re-keyed to `lighting` by `DemoDeepLinkRegistry.routeTab(for:)`. Same
    /// indices as Android's `ALIAS_INITIAL_TAB`.
    /// Peeked here, taken in `onAppear` (see `DeepLinkRouter.peekTab(for:)`).
    @State private var rig: Rig = Rig.initial(DeepLinkRouter.peekTab(for: "lighting"))
    /// Index into ``imageEnvironments``. Held as an index, not a
    /// `SceneEnvironment`, because `SceneEnvironment` is not `Equatable` and
    /// `.contentID(_:)` needs a `Hashable` key.
    @State private var environmentIndex: Int = 0
    /// Key azimuth in degrees for the Studio rig.
    @State private var keyAzimuth: Double = 45
    /// Hour of day for the Sun rig, 0…24.
    @State private var hour: Double = 15

    /// The hero, loaded once. `nil` until it lands (or if it fails — the probes still stand).
    @State private var heroNode: ModelNode?
    @State private var heroLoadFailed = false
    /// Android's idle orbit: on until the viewer takes the camera.
    @State private var orbiting = true
    @State private var orbitStart = Date()
    @State private var orbitStartYaw: Double = Self.staticYawDegrees
    /// The pose held once the orbit stops.
    @State private var heldPose: SceneCameraPose?
    @State private var viewport: CGSize = .zero

    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false
    @Environment(\.analyticsSampleId) private var analyticsSampleId

    // MARK: - Stage constants (Android `LightingStage`)

    /// Largest side of the hero helmet.
    private static let heroUnits: Float = 0.5
    /// Radius of both probe balls.
    private static let probeRadius: Float = 0.065
    /// The probes stand either side of the helmet and a little in front of it.
    private static let probeSpacing: Float = 0.34
    private static let probeOffsetZ: Float = 0.14
    private static let stageZ: Float = 0
    /// Top of the floor: 1 cm under the helmet's underside.
    private static let floorY: Float = -heroUnits / 2 - 0.01
    /// Runs well past the frame at every orbit distance, so its edge is never seen.
    private static let floorSize: Float = 60

    /// Camera: 20° above the subject, a revolution every 26 s, 32° yaw when still.
    private static let orbitElevationDegrees: Float = 20
    private static let orbitPeriod: Double = 26
    private static let staticYawDegrees: Double = 32
    /// What the camera fits: both probes across, the helmet up and deep.
    private static let subjectExtent = SIMD3<Float>((0.34 + 0.065) * 2, 0.5, 0.5)

    /// The analytic rigs need the IBL *dimmed*, not off: at full strength the
    /// ambient does the modelling the key light exists to do. Off entirely and
    /// the chrome probe goes black, which reads as a broken material rather
    /// than as a studio. Android solves it the same way with
    /// `STUDIO_IBL_INTENSITY`.
    private static let studioIBLIntensity: Float = 0.12

    /// Distance of every analytic light from the stage centre — Android's `RIG_RADIUS`, so
    /// the markers stand inside the frame instead of beyond it.
    private static let rigRadius: Float = 1.35
    private static let keyElevationDegrees: Double = 38
    private static let fillAzimuthOffsetDegrees: Double = 155
    private static let fillElevationDegrees: Double = 12
    private static let rimAzimuthOffsetDegrees: Double = -125
    private static let rimElevationDegrees: Double = 46
    /// The rig moved from 2.2 m to 1.35 m: its lumens scale by (1.35 / 2.2)² so the light
    /// landing on the stage stays what it was.
    private static let rigIntensityScale: Float = 0.38

    /// Environments offered by the Image rig. A subset of
    /// `SceneEnvironment.allPresets` chosen so consecutive entries look nothing
    /// alike — the point of the row is that tapping it changes the frame.
    private static let imageEnvironments: [SceneEnvironment] = [
        .studio, .outdoor, .sunset, .nightSky
    ]

    // MARK: - Derived state

    /// The environment for the current rig.
    ///
    /// Studio uses the neutral HDR at a low intensity purely as ambient fill —
    /// a studio is a dark surround by definition, so its sky is never drawn.
    /// Sun draws no HDR at all: `DynamicSkyNode` *is* the sky.
    private var environment: SceneEnvironment {
        switch rig {
        case .image:
            return Self.imageEnvironments[environmentIndex]
        case .studio:
            var studio = SceneEnvironment.studio
            studio.intensity = Self.studioIBLIntensity
            studio.showSkybox = false
            return studio
        case .sun:
            // `DynamicSkyNode` is a *sun*, not a sky: it contributes the
            // directional light and nothing to the background, so a Sun rig
            // with no HDR renders its subject against pure black — which is
            // what the first pass of this screen did at every hour. The HDR
            // swapped by the clock is what makes 15:00 look like afternoon and
            // 02:00 look like night. Same mapping as the former `DynamicSkyDemo`
            // (`skyEnvironment`) and as Android's
            // `LightingStage.skyEnvironmentFor(hour)`.
            var sky = Self.skyEnvironment(forHour: hour)
            // Dimmed, for the same reason Studio's is: at full strength the HDR
            // does the modelling the sun is there to do, and dragging the clock
            // only changes the backdrop.
            sky.intensity *= 0.35
            return sky
        }
    }

    /// The HDR that stands behind the sun at a given hour.
    private static func skyEnvironment(forHour hour: Double) -> SceneEnvironment {
        switch hour {
        case ..<6, 19...: return .nightSky
        case ..<9, 17...: return .sunset
        default: return .outdoor
        }
    }

    /// Rebuild key for `.contentID(_:)`.
    ///
    /// Every value the content closure reads has to appear here, or the scene
    /// keeps a rig it has already been told to drop. `.contentID(_:)` swaps the
    /// content inside the `RealityView` that is already rendering rather than
    /// re-keying the view with `.id(_:)` — a re-created `RealityView`
    /// intermittently renders nothing at all on iOS 26 Simulator, permanently
    /// (#3008).
    private var contentKey: String {
        let hero = heroNode == nil ? "" : "-hero"
        switch rig {
        case .image:
            return "image-\(environmentIndex)\(hero)"
        case .studio:
            return "studio-\(Int(keyAzimuth))\(hero)"
        case .sun:
            return "sun-\(Int(hour * 4))\(hero)"
        }
    }

    private var rigSelection: Binding<Rig> {
        Binding(
            get: { rig },
            set: { next in
                guard next != rig else { return }
                if let analyticsSampleId {
                    DemoAnalytics.shared.interaction(
                        analyticsSampleId,
                        DemoAnalytics.modeControl(next.analyticsMode)
                    )
                }
                rig = next
            }
        )
    }

    // MARK: - Body

    var body: some View {
        GeometryReader { proxy in
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !orbiting)) { context in
                scene(pose: pose(at: context.date))
            }
            .onAppear {
                viewport = proxy.size
                // A link that landed after `init` took its tab (see `rig`).
                if let token = DeepLinkRouter.consumeTab(for: "lighting") { rig = Rig.initial(token) }
                if qaMode { orbiting = false }
                heldPose = framingPose(yawDegrees: Self.staticYawDegrees)
            }
            .onChange(of: proxy.size) { _, size in
                viewport = size
                if !orbiting { heldPose = framingPose(yawDegrees: orbitStartYaw) }
            }
        }
        .ignoresSafeArea()
        .task { await loadHeroIfNeeded() }
        // The rig picker and the rig's one control ride the scaffold's
        // accessory cluster on glass; the explainer lives in the sheet. The
        // previous `.ultraThinMaterial` card went near-white in dark mode
        // and the screen had no back button at all (#3766 P2 §3, §6).
        .demoChrome(
            dock: [
                DockItem(icon: orbiting ? "pause.fill" : "play.fill", label: "Animate",
                         selected: orbiting) { setOrbiting(!orbiting) },
            ],
            onReset: {
                orbitStartYaw = Self.staticYawDegrees
                heldPose = framingPose(yawDegrees: Self.staticYawDegrees)
                orbiting = false
            },
            accessory: {
                VStack(spacing: SceneViewTokens.Chrome.clusterGap) {
                    rigControl
                    DemoOptionStrip(Rig.allCases, selection: rigSelection) { $0.title }
                }
            }
        ) {
            Text(rig.explainer)
                .font(SceneViewTokens.TypeScale.body)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func scene(pose: SceneCameraPose?) -> some View {
        SceneView { root in
            addStage(to: root)
            switch rig {
            case .image:
                break                       // the environment is the whole rig
            case .studio:
                addStudioRig(to: root)
            case .sun:
                addSunRig(to: root)
            }
        }
        // The fix for "aucune différence": without these two the scene
        // carries a 10 000 lux key and a 3 000 lux fill that no rig here
        // asked for, and every rig looks like every other one.
        .mainLight(.disabled)
        .fillLight(.disabled)
        .environment(environment)
        .contentID(contentKey)
        .cameraControls(.orbit)
        // The camera frames the subject, never the floor — see `framingPose`.
        .autoCenterContent(false)
        .cameraPose(pose)
        .onCameraChanged { reported in
            Task { @MainActor in noteCamera(reported) }
        }
    }

    // MARK: - Camera

    private func pose(at date: Date) -> SceneCameraPose? {
        guard orbiting else { return heldPose }
        return framingPose(yawDegrees: yaw(at: date))
    }

    private func yaw(at date: Date) -> Double {
        orbitStartYaw + date.timeIntervalSince(orbitStart) / Self.orbitPeriod * 360
    }

    private func setOrbiting(_ on: Bool) {
        if on {
            orbitStart = Date()
        } else {
            orbitStartYaw = yaw(at: Date())
            heldPose = framingPose(yawDegrees: orbitStartYaw)
        }
        orbiting = on
    }

    /// A drag while the orbit runs hands the camera to the viewer.
    private func noteCamera(_ reported: SceneCameraPose) {
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

    /// The pose that fits the helmet and both probes between the top chrome and the dock at
    /// any yaw — Android's `rememberFitOrbitRadius` on the subject extent, not the floor.
    /// The distance is the worst case over a full turn, so the orbit never breathes.
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
        // Centre the subject on the band between the chrome, not on the screen.
        let bandCentreOffset = (top + band / 2) - height / 2
        let shift = bandCentreOffset / (height / 2) * distance * tanV
        return SceneCameraPose(azimuth: a, elevation: elevation, distance: distance,
                               target: SIMD3(0, 0, Self.stageZ) + up * shift)
    }

    // MARK: - Stage

    /// Loads the hero once. On failure the stage keeps its probes and floor.
    @MainActor
    private func loadHeroIfNeeded() async {
        guard heroNode == nil, !heroLoadFailed else { return }
        do {
            let node = try await ModelNode.load("khronos_damaged_helmet")
            _ = node.scaleToUnits(Self.heroUnits)
            _ = node.centerOrigin()
            node.entity.position = .init(x: 0, y: 0, z: Self.stageZ)
            heroNode = node
        } catch {
            heroLoadFailed = true
        }
    }

    /// The hero, the gaffer's pair and a floor. Shared by all three rigs so that
    /// switching rig changes the *light* and nothing else.
    @MainActor
    private func addStage(to root: Entity) {
        if let hero = heroNode {
            root.addChild(hero.entity)
        }
        // Chrome: a mirror. Metallic 1 / roughness ~0 is the ball that shows
        // what the environment is.
        let chrome = GeometryNode.sphere(
            radius: Self.probeRadius,
            material: .pbr(color: .white, metallic: 1.0, roughness: 0.05)
        )
        chrome.entity.position = .init(x: -Self.probeSpacing, y: Self.floorY + Self.probeRadius,
                                       z: Self.stageZ + Self.probeOffsetZ)
        root.addChild(chrome.entity)

        // Matte: a diffuse grey. Its terminator is the key direction and the
        // softness of that terminator is the source size.
        let matte = GeometryNode.sphere(
            radius: Self.probeRadius,
            material: .pbr(
                color: SimpleMaterial.Color(red: 0.73, green: 0.75, blue: 0.78, alpha: 1),
                metallic: 0.0,
                roughness: 0.85
            )
        )
        matte.entity.position = .init(x: Self.probeSpacing, y: Self.floorY + Self.probeRadius,
                                      z: Self.stageZ + Self.probeOffsetZ)
        root.addChild(matte.entity)

        // A slate floor, so the key's shadow has somewhere to land — Android's
        // `FLOOR_COLOR` at roughness 0.45: glossy enough to hold a soft
        // reflection of the helmet, which is what seats it on the floor.
        var floorMaterial = PhysicallyBasedMaterial()
        floorMaterial.baseColor = .init(tint: SceneViewTokens.Stage.lightingFloor)
        floorMaterial.roughness = .init(floatLiteral: 0.45)
        floorMaterial.metallic = .init(floatLiteral: 0)
        floorMaterial.specular = .init(floatLiteral: 0.55)
        let floor = ModelEntity(
            mesh: .generatePlane(width: Self.floorSize, depth: Self.floorSize),
            materials: [floorMaterial]
        )
        floor.position = .init(x: 0, y: Self.floorY, z: Self.stageZ)
        root.addChild(floor)
    }

    // MARK: - Studio rig

    /// Position on the rig sphere for an azimuth/elevation pair, in degrees.
    private static func rigPosition(azimuth: Double, elevation: Double) -> SIMD3<Float> {
        let a = Float(azimuth * .pi / 180)
        let e = Float(elevation * .pi / 180)
        let horizontal = Self.rigRadius * cos(e)
        return .init(
            x: horizontal * sin(a),
            y: Self.rigRadius * sin(e),
            z: Self.stageZ + horizontal * cos(a)
        )
    }

    private var stageCentre: SIMD3<Float> { .init(x: 0, y: 0, z: Self.stageZ) }

    /// Key + fill + rim, each with an unlit marker.
    ///
    /// Fill and rim are kept in a fixed ratio to the key so the rig stays
    /// balanced as the key moves — the control changes the *modelling*, not the
    /// exposure.
    @MainActor
    private func addStudioRig(to root: Entity) {
        let keyPosition = Self.rigPosition(azimuth: keyAzimuth, elevation: Self.keyElevationDegrees)
        let fillPosition = Self.rigPosition(
            azimuth: keyAzimuth + Self.fillAzimuthOffsetDegrees,
            elevation: Self.fillElevationDegrees
        )
        let rimPosition = Self.rigPosition(
            azimuth: keyAzimuth + Self.rimAzimuthOffsetDegrees,
            elevation: Self.rimElevationDegrees
        )

        // Key — warm, focused, and the only light in the rig that casts a
        // shadow. The shadow is what makes moving it legible on the floor.
        let key = LightNode.spot(
            color: .warm,
            intensity: 90_000 * Self.rigIntensityScale,
            innerAngle: .pi / 9,
            outerAngle: .pi / 5,
            attenuationRadius: 8
        )
        .position(keyPosition)
        .lookAt(stageCentre)
        .castsShadow(true)
        root.addChild(key.entity)

        // Fill — cool and soft, opposite the key. Lifts the shadow side without
        // competing with the key's modelling.
        let fill = LightNode.point(
            color: .custom(r: 0.55, g: 0.68, b: 1.0),
            intensity: 12_000 * Self.rigIntensityScale,
            attenuationRadius: 8
        )
        .position(fillPosition)
        root.addChild(fill.entity)

        // Rim — cold and behind, to separate the probes from the dark surround.
        let rim = LightNode.spot(
            color: .custom(r: 0.72, g: 0.86, b: 1.0),
            intensity: 45_000 * Self.rigIntensityScale,
            innerAngle: .pi / 10,
            outerAngle: .pi / 6,
            attenuationRadius: 8
        )
        .position(rimPosition)
        .lookAt(stageCentre)
        root.addChild(rim.entity)

        // Markers are unlit on purpose: a marker's job is "the source is here",
        // not "the source is lit", so it must never go dark when its own light
        // turns away from the camera.
        addMarker(at: keyPosition, color: .orange, to: root)
        addMarker(at: fillPosition, color: .cyan, to: root)
        addMarker(at: rimPosition, color: .white, to: root)
    }

    @MainActor
    private func addMarker(
        at position: SIMD3<Float>,
        color: SimpleMaterial.Color,
        to root: Entity
    ) {
        let marker = GeometryNode.sphere(radius: 0.045, material: .unlit(color: color))
        marker.entity.position = position
        root.addChild(marker.entity)
    }

    // MARK: - Sun rig

    @MainActor
    private func addSunRig(to root: Entity) {
        let sky = DynamicSkyNode(
            timeOfDay: Float(hour),
            turbidity: 3,
            sunIntensity: 2_500
        )
        root.addChild(sky.entity)
    }

    // MARK: - Controls

    /// One control per rig — the handful of knobs that rig actually needs.
    @ViewBuilder
    private var rigControl: some View {
        switch rig {
        case .image:
            DemoOptionStrip(Array(Self.imageEnvironments.indices), selection: $environmentIndex) {
                Self.imageEnvironments[$0].name
            }
            .accessibilityLabel("Environment")
        case .studio:
            glassSlider {
                LabeledSlider(
                    label: "Key angle",
                    value: $keyAzimuth,
                    range: 0...360,
                    step: 5,
                    decimals: 0,
                    unit: "°"
                )
            }
        case .sun:
            glassSlider {
                LabeledSlider(
                    label: "Time of day",
                    value: $hour,
                    range: 0...24,
                    step: 0.25,
                    valueText: Self.clock(hour)
                )
            }
        }
    }

    /// A slider on the same glass as the option strip beside it.
    private func glassSlider<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        content()
            .padding(.horizontal, SceneViewTokens.Glass.pillPaddingHorizontal)
            .padding(.vertical, SceneViewTokens.Space.sm)
            .glassBackground(in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg,
                                                  style: .continuous))
    }

    /// `15.25` → `"15:15"`. A bare decimal hour reads as a number, not a time.
    private static func clock(_ hour: Double) -> String {
        let total = Int((hour * 60).rounded())
        return String(format: "%02d:%02d", (total / 60) % 24, total % 60)
    }
}

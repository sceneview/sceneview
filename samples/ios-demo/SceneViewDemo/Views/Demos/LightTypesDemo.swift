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
/// Two probe balls and a floor, shared by all three rigs — the gaffer's pair:
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
    private enum Rig: Int, CaseIterable, Identifiable {
        case image, studio, sun

        var id: Int { rawValue }

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
                return "A three-point rig of analytic lights — a shadow-casting key, a cool fill, a cold rim — each drawn where it stands. Move the key and watch the modelling change."
            case .sun:
                return "One sun on a clock. The hour drives its colour, height and strength, and the sky swaps with it, so midnight is night rather than a dark noon."
            }
        }
    }

    @State private var rig: Rig = .image
    /// Index into ``imageEnvironments``. Held as an index, not a
    /// `SceneEnvironment`, because `SceneEnvironment` is not `Equatable` and
    /// `.contentID(_:)` needs a `Hashable` key.
    @State private var environmentIndex: Int = 0
    /// Key azimuth in degrees for the Studio rig.
    @State private var keyAzimuth: Double = 45
    /// Hour of day for the Sun rig, 0…24.
    @State private var hour: Double = 15

    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false

    // MARK: - Stage constants

    /// Radius of both probe balls.
    private static let probeRadius: Float = 0.28
    /// The probes sit either side of the origin, far enough apart that the
    /// chrome one never mirrors the matte one across most of the orbit.
    private static let probeSpacing: Float = 0.42
    private static let stageZ: Float = -1.8
    private static let floorY: Float = -0.3

    /// The analytic rigs need the IBL *dimmed*, not off: at full strength the
    /// ambient does the modelling the key light exists to do. Off entirely and
    /// the chrome probe goes black, which reads as a broken material rather
    /// than as a studio. Android solves it the same way with
    /// `STUDIO_IBL_INTENSITY`.
    private static let studioIBLIntensity: Float = 0.12

    /// Distance of every analytic light from the stage centre.
    private static let rigRadius: Float = 2.2
    private static let keyElevationDegrees: Double = 35
    private static let fillAzimuthOffsetDegrees: Double = 150
    private static let rimAzimuthOffsetDegrees: Double = -110

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
            // 02:00 look like night. Same mapping as `DynamicSkyDemo`
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
        switch rig {
        case .image:
            return "image-\(environmentIndex)"
        case .studio:
            return "studio-\(Int(keyAzimuth))"
        case .sun:
            return "sun-\(Int(hour * 4))"
        }
    }

    // MARK: - Body

    var body: some View {
        ZStack {
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
            // A low orbit keeps the floor, both probes and the sky in frame.
            .cameraOrbit(elevation: .pi / 12)
            .framingMargin(qaMode ? 0.75 : 1.05)
            .ignoresSafeArea()

            VStack {
                Spacer()
                controls
            }
        }
        .background(Color.black)
    }

    // MARK: - Stage

    /// The gaffer's pair plus a floor. Shared by all three rigs so that
    /// switching rig changes the *light* and nothing else.
    @MainActor
    private func addStage(to root: Entity) {
        // Chrome: a mirror. Metallic 1 / roughness ~0 is the ball that shows
        // what the environment is.
        let chrome = GeometryNode.sphere(
            radius: Self.probeRadius,
            material: .pbr(color: .white, metallic: 1.0, roughness: 0.05)
        )
        chrome.entity.position = .init(x: -Self.probeSpacing, y: 0, z: Self.stageZ)
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
        matte.entity.position = .init(x: Self.probeSpacing, y: 0, z: Self.stageZ)
        root.addChild(matte.entity)

        // A mid-grey floor, so the key's shadow has somewhere to land. Without
        // it the Studio rig's shadow toggle is invisible.
        let floor = GeometryNode.plane(
            width: 4,
            depth: 4,
            color: SimpleMaterial.Color(red: 0.22, green: 0.23, blue: 0.25, alpha: 1)
        )
        floor.entity.position = .init(x: 0, y: Self.floorY, z: Self.stageZ)
        root.addChild(floor.entity)
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
            elevation: 12
        )
        let rimPosition = Self.rigPosition(
            azimuth: keyAzimuth + Self.rimAzimuthOffsetDegrees,
            elevation: 45
        )

        // Key — warm, focused, and the only light in the rig that casts a
        // shadow. The shadow is what makes moving it legible on the floor.
        let key = LightNode.spot(
            color: .warm,
            intensity: 90_000,
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
            intensity: 12_000,
            attenuationRadius: 8
        )
        .position(fillPosition)
        root.addChild(fill.entity)

        // Rim — cold and behind, to separate the probes from the dark surround.
        let rim = LightNode.spot(
            color: .custom(r: 0.72, g: 0.86, b: 1.0),
            intensity: 45_000,
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
        let marker = GeometryNode.sphere(radius: 0.06, material: .unlit(color: color))
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

    @ViewBuilder
    private var controls: some View {
        VStack(spacing: 12) {
            Text(rig.explainer)
                .font(.caption)
                .foregroundStyle(.white.opacity(0.75))
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)

            rigControl

            HStack(spacing: 8) {
                ForEach(Rig.allCases) { option in
                    Button {
                        rig = option
                        #if os(iOS)
                        SceneViewHaptic.shared.selection()
                        #endif
                    } label: {
                        VStack(spacing: 4) {
                            Image(systemName: option.icon)
                                .font(.body)
                            Text(option.title)
                                .font(.caption2)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(
                            option == rig
                                ? AnyShapeStyle(.orange)
                                : AnyShapeStyle(.white.opacity(0.15))
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .foregroundStyle(.white)
                    }
                    .accessibilityLabel("\(option.title) lighting rig")
                    .accessibilityAddTraits(option == rig ? .isSelected : [])
                }
            }
        }
        .padding()
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding()
    }

    /// One control per rig — the handful of knobs that rig actually needs.
    @ViewBuilder
    private var rigControl: some View {
        switch rig {
        case .image:
            HStack(spacing: 8) {
                ForEach(Array(Self.imageEnvironments.enumerated()), id: \.offset) { index, preset in
                    Button {
                        environmentIndex = index
                        #if os(iOS)
                        SceneViewHaptic.shared.selection()
                        #endif
                    } label: {
                        Text(preset.name)
                            .font(.caption2)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                            .background(
                                index == environmentIndex
                                    ? AnyShapeStyle(.white.opacity(0.35))
                                    : AnyShapeStyle(.white.opacity(0.12))
                            )
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .foregroundStyle(.white)
                    }
                    .accessibilityLabel("\(preset.name) environment")
                    .accessibilityAddTraits(index == environmentIndex ? .isSelected : [])
                }
            }
        case .studio:
            LabeledSlider(
                label: "Key angle",
                value: $keyAzimuth,
                range: 0...360,
                step: 5,
                decimals: 0,
                unit: "°"
            )
            .tint(.orange)
        case .sun:
            LabeledSlider(
                label: "Time of day",
                value: $hour,
                range: 0...24,
                step: 0.25,
                valueText: Self.clock(hour)
            )
            .tint(.orange)
        }
    }

    /// `15.25` → `"15:15"`. A bare decimal hour reads as a number, not a time.
    private static func clock(_ hour: Double) -> String {
        let total = Int((hour * 60).rounded())
        return String(format: "%02d:%02d", (total / 60) % 24, total % 60)
    }
}

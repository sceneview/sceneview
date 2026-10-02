import SwiftUI
#if os(iOS)
import RealityKit
import SceneViewSwift
#endif

/// About tab — per the SceneView design system (`DESIGN.md` "Demo App About").
///
/// The identity block (launcher icon, name, version, tagline) flat on the page,
/// the support card — the screen's one emphasised surface — then a series of
/// `.regularMaterial` row cards (Open Source, Docs, GitHub, 3D Playground,
/// Credits), and a footer with attribution.
struct AboutTab: View {
    private static let version: String = {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    }()

    // #1152 Stage 3 — Credits sheet (CC-BY attribution for every streamed
    // Sketchfab model in `SampleAssets`); #3214 — bundled assets too, from
    // the generated `BundledCredits.json`.
    @State private var showCreditsSheet = false

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 20) {
                    identity
                    if !HDPackStore.shared.manifest.assets.isEmpty {
                        HDPackSettingsRow()
                    }
                    aboutCards
                    // Shown before Firebase is configured too: in the consent zone the
                    // switch is how a refusal is reversed.
                    if FirebaseTelemetry.hasBundledConfig {
                        PrivacySettingsSection()
                    }
                    footer
                }
                // One gutter and one bottom inset for the three tabs: the same
                // tokens the Showcase grid uses, so a tab switch moves nothing.
                .padding(.horizontal, SceneViewTokens.Home.contentPadding)
                .padding(.top, SceneViewTokens.Home.heroTopGap)
                .padding(.bottom, SceneViewTokens.Home.gridBottomInset)
            }
            // The page never set a ground, so dark fell back to the system
            // black (#000) while Showcase and Explore sit on `surface` (#0D1117).
            .background(SceneViewTokens.HomeColor.surface)
            .navigationTitle("About")
            .sheet(isPresented: $showCreditsSheet) {
                CreditsSheet()
            }
        }
    }

    // MARK: - Identity

    /// The mark, the name, the installed version, one sentence — the iOS twin
    /// of Android's `AboutIdentity` (#3564, #3808).
    ///
    /// Deliberately **not** a card (`DESIGN.md` "Demo App About"): a slab here
    /// is a second emphasised surface competing with the support card right
    /// under it. On the page's `surface` the block reads as a masthead instead.
    private var identity: some View {
        VStack(spacing: SceneViewTokens.Space.sm) {
            // The stage: the launcher icon's cube as a real object, turning
            // between two orbit rings. The icon itself (`about-mark`) stands
            // in its place until the stage has drawn.
            AboutMarkStage()
                .accessibilityHidden(true)

            Text("SceneView")
                .font(SceneViewTokens.TypeScale.title)
                .tracking(SceneViewTokens.TypeScale.titleTracking)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                .accessibilityAddTraits(.isHeader)

            // Version as text, not a material pill: a pill is one more surface
            // in a block that must sit flat on the page.
            Text("Version \(Self.version)")
                .font(.footnote)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)

            Text("3D & AR for Jetpack Compose, SwiftUI, and the Web.\nDeclarative, AI-friendly, open source.")
                .font(.callout)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, SceneViewTokens.Space.sm)
    }

    // MARK: - About cards

    private var aboutCards: some View {
        VStack(spacing: 12) {
            AboutCard(
                icon: "heart.circle.fill",
                iconColor: .pink,
                title: "Open Source",
                subtitle: "Apache 2.0 — free for any project, forever",
                trailing: nil,
                action: nil
            )

            AboutCard(
                icon: "book.fill",
                iconColor: .blue,
                title: "Documentation",
                subtitle: "Guides, API reference, recipes",
                trailing: .link,
                url: URL(string: "https://sceneview.github.io")
            )

            AboutCard(
                icon: "chevron.left.forwardslash.chevron.right",
                iconColor: .indigo,
                title: "GitHub",
                subtitle: "Source, issues, releases",
                trailing: .link,
                url: URL(string: "https://github.com/sceneview/sceneview")
            )

            AboutCard(
                icon: "sparkles",
                iconColor: .orange,
                title: "3D Playground",
                subtitle: "Try every feature in the browser",
                trailing: .link,
                url: URL(string: "https://sceneview.github.io/playground.html")
            )

            AboutCard(
                icon: "person.2.fill",
                iconColor: .teal,
                title: "Credits",
                subtitle: "Authors & licenses for every bundled and streamed 3D asset",
                trailing: .chevron,
                action: { showCreditsSheet = true }
            )
        }
    }

    // MARK: - Support

    // No donation links on iOS: App Review rejected 4.48.0 under guideline
    // 3.1.1 (donations must use In-App Purchase outside the US storefront).
    // Android keeps `AboutSupportCard`; the GitHub row below stays.

    // MARK: - Footer

    private var footer: some View {
        VStack(spacing: 4) {
            HStack(spacing: 4) {
                Text("Made with")
                Image(systemName: "heart.fill")
                    .foregroundStyle(.red)
                Text("by Thomas Gorisse")
            }
            .font(.caption)
            .foregroundStyle(.secondary)

            Text("and the SceneView contributors")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.top, 8)
    }
}

// MARK: - Liquid Glass row card

private struct AboutCard: View {
    enum Trailing {
        case link
        case chevron
    }

    let icon: String
    let iconColor: Color
    let title: String
    let subtitle: String
    let trailing: Trailing?
    var url: URL? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        if let url = url {
            Link(destination: url) {
                cardContent
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(title): \(subtitle). Opens \(url.host ?? "link")")
        } else if let action = action {
            Button(action: action) { cardContent }
                .buttonStyle(.plain)
                .accessibilityLabel("\(title): \(subtitle)")
        } else {
            cardContent
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(title): \(subtitle)")
        }
    }

    private var cardContent: some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(iconColor.opacity(0.18))
                Image(systemName: icon)
                    .font(.title3)
                    .foregroundStyle(iconColor)
            }
            .frame(width: 44, height: 44)
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            Spacer(minLength: 4)

            if let trailing = trailing {
                Image(systemName: trailing == .link ? "arrow.up.right" : "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .materialGlassBackground(in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.06), lineWidth: 0.5)
        )
    }
}

// MARK: - About mark stage

/// The SceneView mark as a real object: the launcher icon's cube, glossy, lit
/// by the studio HDR, floating over the About page between two tilted orbit rings (the Cosmos
/// ringed world, told with the brand's own shape). It turns a sixteenth of a
/// turn a second and bobs; two satellites ride the rings. No card behind it:
/// the identity block is not a card (#3565).
///
/// Until the lighting has settled and the reveal frame has drawn, the launcher
/// icon stands where the cube will be; then the two crossfade. Under Reduce
/// Motion the stage holds its rest pose and nothing moves. It only renders
/// while the About tab is on screen, and holds still while the app is not active.
private struct AboutMarkStage: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var visible = false
    #if os(iOS)
    @State private var host = AboutMarkHost()
    #endif
    @State private var drawn = false

    var body: some View {
        let about = SceneViewTokens.About.self
        ZStack {
            // The contact shadow is SwiftUI, under the transparent stage: a
            // flat ellipse the mark hovers over. It does not follow the bob —
            // a still ground reads as calm.
            Ellipse()
                .fill(RadialGradient(colors: [SceneViewTokens.MarkColor.shadow, .clear],
                                     center: .center, startRadius: 0,
                                     endRadius: about.stageShadowWidth / 2))
                .frame(width: about.stageShadowWidth, height: about.stageShadowHeight)
                .frame(maxHeight: .infinity, alignment: .bottom)
                .padding(.bottom, SceneViewTokens.Space.sm)

            #if os(iOS)
            if visible {
                AboutMarkScene(renderer: host.renderer,
                               moving: scenePhase == .active && !reduceMotion,
                               onReveal: {
                                   // A cut under Reduce Motion, the fade otherwise.
                                   withAnimation(reduceMotion ? nil : SceneViewTokens.Spring.fade) { drawn = true }
                               })
                    .opacity(drawn ? 1 : 0)
            }
            #endif

            Image("about_mark")
                .resizable()
                .interpolation(.high)
                .frame(width: about.markSize, height: about.markSize)
                .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.xl, style: .continuous))
                .opacity(drawn ? 0 : 1)
        }
        .frame(maxWidth: .infinity)
        .frame(height: about.stageHeight)
        .allowsHitTesting(false)
        .onAppear { visible = true }
        // The next stage is a new `RealityView`: the icon stands in again
        // until it has drawn, rather than a blank band for a frame.
        .onDisappear { visible = false; drawn = false }
    }
}

#if os(iOS)
/// Holds the About mark's renderer across the stage's re-creations. Cheap to
/// create: SwiftUI builds a throwaway one each time it re-initialises the
/// view, so the renderer and its entities are only built on first use — the
/// `HomeHeroFlightHost` pattern.
@MainActor
private final class AboutMarkHost {
    private var built: AboutMarkRenderer?

    var renderer: AboutMarkRenderer {
        if let built { return built }
        let renderer = AboutMarkRenderer()
        built = renderer
        return renderer
    }
}

/// The `RealityView` that shows the mark. Its own view, not SceneViewSwift's
/// `SceneView`, for the same reason as the Home hero: the stage needs its own
/// camera and a per-frame hook. The background stays clear — the page shows
/// through.
private struct AboutMarkScene: View {
    let renderer: AboutMarkRenderer
    let moving: Bool
    let onReveal: () -> Void

    var body: some View {
        RealityView { content in
            content.camera = .virtual
            renderer.install(in: &content, onReveal: onReveal)
        }
        .onAppear { renderer.moving = moving }
        .onChange(of: moving) { _, value in renderer.moving = value }
        .onDisappear { renderer.detach() }
    }
}

/// Builds the mark once and poses it every frame from a clock that only runs
/// while the stage is moving.
@MainActor
private final class AboutMarkRenderer {
    /// Whether the clock runs. A held stage stops its per-frame callback once
    /// it has revealed (`idleIfDone`); setting this wakes it again.
    var moving = false {
        didSet { if moving != oldValue { wake() } }
    }

    private let root = Entity()
    private let camera = PerspectiveCamera()
    private let body = Entity()
    private let ringA = Entity()
    private let ringB = Entity()
    private let satelliteA = Entity()
    private let satelliteB = Entity()
    private var built = false
    /// Scene seconds; starts on the rest pose so the first frame is composed.
    private var seconds = Mark.restSeconds
    private var stopUpdates: (() -> Void)?
    /// Called once after the lighting has settled and the final look has drawn.
    private var onReveal: (() -> Void)?
    /// The studio IBL has landed, or failed to: the lighting is final.
    private var lightingSettled = false
    /// Frames drawn since the lighting settled, for this `RealityView`.
    private var framesLit = 0
    /// Whether a `RealityView` shows this renderer right now.
    private var attached = false

    func install(in content: inout RealityViewCameraContent, onReveal: @escaping () -> Void) {
        content.add(root)
        content.add(camera)
        attached = true
        self.onReveal = onReveal
        framesLit = 0
        stopUpdates?()
        let subscription = content.subscribe(to: SceneEvents.Update.self) { [weak self] event in
            MainActor.assumeIsolated { self?.update(event.deltaTime) }
        }
        stopUpdates = { subscription.cancel() }
        if !built { build() }
        pose()
    }

    func detach() {
        attached = false
        stopUpdates?()
        stopUpdates = nil
    }

    private func update(_ delta: Double) {
        // Reveal only on a finished frame: the IBL loads asynchronously, and a
        // cube drawn before it lands is lit by the key alone. The frame that
        // first carries the IBL is followed by one more before the crossfade.
        if lightingSettled { framesLit += 1 }
        if let onReveal, framesLit >= Mark.framesBeforeReveal {
            self.onReveal = nil
            onReveal()
        }
        if moving {
            // One long frame (the app back from the background) must not jump
            // the mark forward, just as Android's `HeroClock` caps its step.
            seconds += min(max(delta, 0), Mark.maxFrameSeconds)
        }
        pose()
        idleIfDone()
    }

    /// A held stage (Reduce Motion, app inactive) is final once it has
    /// revealed and drawn a while at full opacity: no per-frame work until it
    /// moves again, and RealityKit keeps showing the last frame. Idling on the
    /// reveal frame itself left the band blank under Reduce Motion — the view
    /// had last drawn while still transparent.
    private func idleIfDone() {
        guard !moving, onReveal == nil,
              framesLit >= Mark.framesBeforeReveal + Mark.framesHeldAfterReveal else { return }
        stopUpdates?()
        stopUpdates = nil
    }

    /// Back to one callback per frame after the stage went idle.
    private func wake() {
        guard stopUpdates == nil, attached, let scene = root.scene else { return }
        let subscription = scene.subscribe(to: SceneEvents.Update.self) { [weak self] event in
            MainActor.assumeIsolated { self?.update(event.deltaTime) }
        }
        stopUpdates = { subscription.cancel() }
    }

    // MARK: Build

    private func build() {
        built = true
        camera.components.set(PerspectiveCameraComponent(near: 0.05, far: 50,
                                                         fieldOfViewInDegrees: Mark.verticalFov,
                                                         fieldOfViewOrientation: .vertical))
        camera.look(at: Mark.target, from: Mark.eye, relativeTo: nil)

        let key = DirectionalLight()
        key.light.intensity = Mark.keyLux
        key.look(at: Mark.keyLight, from: .zero, relativeTo: nil)
        root.addChild(key)

        let bodyMaterial = Self.lit(SceneViewTokens.MarkColor.body, roughness: Mark.bodyRoughness)
        let lidMaterial = Self.lit(SceneViewTokens.MarkColor.lid, roughness: Mark.lidRoughness)
        var orbit = UnlitMaterial(applyPostProcessToneMap: false)
        let o = SceneViewTokens.MarkColor.orbit
        orbit.color = .init(tint: UIColor(red: o.r, green: o.g, blue: o.b, alpha: 1))

        body.addChild(ModelEntity(mesh: .generateBox(size: Mark.cubeUnits), materials: [bodyMaterial]))
        let lid = ModelEntity(mesh: .generateBox(width: Mark.lidUnits, height: Mark.lidThickness,
                                                 depth: Mark.lidUnits),
                              materials: [lidMaterial])
        lid.position.y = Mark.cubeUnits / 2 + Mark.lidThickness / 2
        body.addChild(lid)
        root.addChild(body)

        if let torus = Self.torus(major: Mark.ringARadius) {
            ringA.addChild(ModelEntity(mesh: torus, materials: [orbit]))
        }
        satelliteA.addChild(ModelEntity(mesh: .generateBox(size: Mark.satAUnits), materials: [bodyMaterial]))
        ringA.addChild(satelliteA)
        root.addChild(ringA)

        if let torus = Self.torus(major: Mark.ringBRadius) {
            ringB.addChild(ModelEntity(mesh: torus, materials: [orbit]))
        }
        satelliteB.addChild(ModelEntity(mesh: .generateSphere(radius: Mark.satBRadius), materials: [orbit]))
        ringB.addChild(satelliteB)
        root.addChild(ringB)

        Task { @MainActor [weak self] in
            let resource = try? await SceneEnvironment.studio.load()
            guard let self else { return }
            if let resource {
                root.components.set(ImageBasedLightComponent(source: .single(resource),
                                                             intensityExponent: Mark.iblExponent))
                for entity in [body, satelliteA] {
                    entity.components.set(ImageBasedLightReceiverComponent(imageBasedLight: root))
                }
            }
            // Settled either way: without the IBL the key still draws the mark.
            lightingSettled = true
            wake()
        }
    }

    private static func lit(_ c: (r: Double, g: Double, b: Double), roughness: Float) -> PhysicallyBasedMaterial {
        var material = PhysicallyBasedMaterial()
        material.baseColor = .init(tint: UIColor(red: c.r, green: c.g, blue: c.b, alpha: 1))
        material.metallic = .init(floatLiteral: 0)
        material.roughness = .init(floatLiteral: roughness)
        material.specular = .init(floatLiteral: Mark.bodyReflectance)
        return material
    }

    /// A torus in the XZ plane: RealityKit has no generator for one.
    private static func torus(major: Float) -> MeshResource? {
        let rings = Mark.ringSegments, sides = Mark.ringTubeSegments
        var positions: [SIMD3<Float>] = []
        var normals: [SIMD3<Float>] = []
        positions.reserveCapacity((rings + 1) * (sides + 1))
        for i in 0...rings {
            let u = Float(i) / Float(rings) * 2 * .pi
            let centre = SIMD3<Float>(cos(u) * major, 0, sin(u) * major)
            for j in 0...sides {
                let v = Float(j) / Float(sides) * 2 * .pi
                let normal = SIMD3<Float>(cos(u) * cos(v), sin(v), sin(u) * cos(v))
                normals.append(normal)
                positions.append(centre + normal * Mark.ringTube)
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
        var descriptor = MeshDescriptor(name: "orbit")
        descriptor.positions = MeshBuffers.Positions(positions)
        descriptor.normals = MeshBuffers.Normals(normals)
        descriptor.primitives = .triangles(indices)
        return try? MeshResource.generate(from: [descriptor])
    }

    // MARK: Pose

    private func pose() {
        let t = Float(seconds)
        let bob = Mark.bobUnits * Self.wave(t, Mark.bobPeriod)
        body.position = [0, bob, 0]
        body.orientation = Self.rotation(yaw: Mark.yawStart + t * Mark.yawPerSecond,
                                         pitch: Mark.wobble * Self.wave(t, Mark.wobblePeriodX),
                                         roll: Mark.wobble * Self.wave(t, Mark.wobblePeriodZ))
        ringA.position = [0, bob * Mark.ringBobShare, 0]
        ringA.orientation = Self.rotation(yaw: Mark.ringAYaw + t * Mark.ringAPrecession,
                                          pitch: Mark.ringAPitch, roll: Mark.ringARoll)
        ringB.position = [0, bob * Mark.ringBobShare, 0]
        ringB.orientation = Self.rotation(yaw: Mark.ringBYaw + t * Mark.ringBPrecession,
                                          pitch: Mark.ringBPitch, roll: Mark.ringBRoll)
        let angleA = Mark.satAStart + t * Mark.satAPerSecond
        let a = angleA * .pi / 180
        satelliteA.position = [Mark.ringARadius * cos(a), 0, Mark.ringARadius * sin(a)]
        satelliteA.orientation = Self.rotation(yaw: angleA * 2, pitch: Mark.satATilt, roll: 0)
        let b = (Mark.satBStart + t * Mark.satBPerSecond) * .pi / 180
        satelliteB.position = [Mark.ringBRadius * cos(b), 0, Mark.ringBRadius * sin(b)]
    }

    /// A sine of the given period in seconds, in `[-1, 1]`.
    private static func wave(_ t: Float, _ period: Float) -> Float { sin(t * 2 * .pi / period) }

    /// Yaw about Y, then pitch about X, then roll about Z — degrees.
    private static func rotation(yaw: Float, pitch: Float, roll: Float) -> simd_quatf {
        let d = Float.pi / 180
        return simd_quatf(angle: yaw * d, axis: [0, 1, 0])
            * simd_quatf(angle: pitch * d, axis: [1, 0, 0])
            * simd_quatf(angle: roll * d, axis: [0, 0, 1])
    }

    /// Art direction in world units, degrees and seconds.
    private enum Mark {
        /// Raised ~24°, so the top face reads as the mark's lit rhombus.
        static let eye = SIMD3<Float>(0, 1.32, 3.0)
        static let target = SIMD3<Float>(0, 0.02, 0)
        /// Android's 28 mm lens on Filament's 24 mm-tall sensor.
        static let verticalFov: Float = 46.4
        /// Key light from the upper right, a little in front.
        static let keyLight = SIMD3<Float>(-0.45, -0.82, -0.36)
        /// Android lights the mark with a 70 000-lux key over its default IBL.
        /// The Home hero keeps Android's 95 000 lux because its helmet is dark
        /// metal under a dim sunset sky; a saturated blue cube is not. From
        /// 10 000 lux, RealityKit's tone mapping pushes the lit face's blue
        /// past its top and the face turns sky-cyan; at 2 000 lux over the
        /// untouched studio IBL the faces go dull and grey. Tuned side by side
        /// with Android's capture (QA/demo-wow-ios/about/v2/tune): a 6 000-lux
        /// key over the studio IBL at √2 (exponent 0.5) keeps the three
        /// saturated blues of the mark.
        static let keyLux: Float = 6_000
        /// The studio IBL's intensity, as a power of two.
        static let iblExponent: Float = 0.5
        /// Pose drawn under Reduce Motion: both satellites in front of the cube.
        static let restSeconds: Double = 1.4
        /// Frames drawn with the lighting settled before the icon crossfades.
        static let framesBeforeReveal = 2
        /// Frames a held stage keeps drawing after its reveal before it idles.
        static let framesHeldAfterReveal = 30
        /// Longest step the clock takes in one frame.
        static let maxFrameSeconds: Double = 0.1

        static let cubeUnits: Float = 1
        static let lidUnits: Float = 0.64
        static let lidThickness: Float = 0.03
        static let bodyRoughness: Float = 0.2
        static let lidRoughness: Float = 0.35
        static let bodyReflectance: Float = 0.6

        static let bobUnits: Float = 0.06
        static let bobPeriod: Float = 4.8
        /// The launcher icon's view: a corner toward the camera.
        static let yawStart: Float = 45
        static let yawPerSecond: Float = 16
        static let wobble: Float = 3
        static let wobblePeriodX: Float = 7.3
        static let wobblePeriodZ: Float = 6.1

        static let ringBobShare: Float = 0.6
        static let ringTube: Float = 0.011
        static let ringSegments = 96
        static let ringTubeSegments = 8

        static let ringARadius: Float = 1.08
        static let ringAYaw: Float = 20
        static let ringAPrecession: Float = 6
        static let ringAPitch: Float = 16
        static let ringARoll: Float = -12

        static let ringBRadius: Float = 1.34
        static let ringBYaw: Float = -35
        static let ringBPrecession: Float = -4
        static let ringBPitch: Float = -9
        static let ringBRoll: Float = 20

        static let satAUnits: Float = 0.13
        static let satAStart: Float = 60
        static let satAPerSecond: Float = 34
        static let satATilt: Float = 25

        static let satBRadius: Float = 0.045
        static let satBStart: Float = 150
        static let satBPerSecond: Float = -22
    }
}
#endif

import SwiftUI
import RealityKit
import SceneViewSwift
import os

/// Positional 3D audio — a looping bell on a sphere that orbits a drawn ring.
///
/// iOS twin of Android's `SpatialAudioDemo`
/// (`samples/android-demo/.../demos/SpatialAudioDemo.kt`), scene for scene:
///
/// - The **purple sphere is the sound source**: the bell is a
///   ``SpatialAudioNode`` parented to it. Translucent shells pulse out of it so
///   the emitter reads even with the sound off.
/// - The **thin ring is its orbit path**, not a second object.
/// - The **listener is the camera — you**. It has no body in the scene, so the
///   glass legend says so in words and reports the live source-to-you distance
///   and the volume the falloff gives at that distance, with a meter.
///
/// ## What is real here
///
/// The previous version fed the falloff the orbiter's distance to the *world
/// origin* — a constant 0.6 m on a circle round the origin — so the gain never
/// moved and the Inverse | Linear picker changed nothing (`parity: fake` in
/// `parity-manifest.yml`). Now:
///
/// 1. The distance is **source → camera**, taken from the pose SceneView
///    renders with (the ``SceneView/onCameraChanged(_:)`` read-back and
///    ``SceneCameraPose/cameraPosition()``), every tick, so orbiting or pinching
///    the camera changes it just like the orbit does.
/// 2. That distance goes through ``SpatialAudioNode/updateGain(forDistance:)``,
///    which sets the RealityKit `AudioPlaybackController.gain` and — from its
///    first call — turns RealityKit's own fixed rolloff off, so the picked curve
///    is the only distance law and the meter shows what the ears get.
/// 3. RealityKit still renders direction: the emitter's world transform against
///    the active camera pans the bell between the ears.
///
/// A Debug build logs distance, falloff, computed gain and the dB value read
/// back from the playback controller twice a second (subsystem
/// `io.github.sceneview.demo`, category `spatial-audio`): that log is how the
/// attenuation is verified on a simulator, which has no ears.
struct SpatialAudioDemo: View {
    @State private var falloffMode: FalloffMode = .inverse
    @StateObject private var coordinator = SpatialAudioCoordinator()

    var body: some View {
        // Read the safe area outside the chrome: inside it the stage ignores the
        // safe area, the inset reads 0 and the legend slides under the title row.
        GeometryReader { proxy in
            ZStack(alignment: .top) {
                scene
                    .ignoresSafeArea()
                SpatialAudioLegend(distance: coordinator.distance, gain: coordinator.gain)
                    // Chrome over the stage is theme-independent, like the title
                    // row: light mode would wash the glass out to grey on grey.
                    .environment(\.colorScheme, .dark)
                    .padding(.horizontal, SceneViewTokens.Chrome.margin)
                    .padding(.top, Self.topReserve(safeTop: proxy.safeAreaInsets.top))
                    .ignoresSafeArea(edges: .top)
            }
            .background(SceneViewTokens.Stage.background)
            .demoChrome {
                controls
            }
        }
        .task { await coordinator.loadIfNeeded() }
        .onChange(of: falloffMode) { _, mode in coordinator.setFalloff(mode.falloff) }
        .onDisappear { coordinator.stop() }
    }

    private var scene: some View {
        SceneView { root in
            coordinator.attach(to: root)
        }
        .environment(Self.environment)
        .cameraControls(.orbit)
        // The orbit is centred on the origin by construction; auto-centring would
        // chase the moving sphere and slide the world under the listener.
        .autoCenterContent(false)
        .cameraPose(SpatialAudioCoordinator.homePose)
        .onCameraChanged { pose in
            Task { @MainActor in coordinator.cameraPose = pose }
        }
    }

    @ViewBuilder
    private var controls: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
            Text("The purple sphere is the sound source: the bell loop is attached to it, and the shells pulsing out of it are the sound leaving. You are the listener — the camera. Drag to orbit and the bell pans between your ears and changes volume with distance.")
                .font(SceneViewTokens.TypeScale.body)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                .fixedSize(horizontal: false, vertical: true)
            Text("Distance falloff")
                .font(SceneViewTokens.TypeScale.bodySemibold)
            Picker("Distance falloff", selection: $falloffMode) {
                ForEach(FalloffMode.allCases) { mode in
                    Text(mode.rawValue).tag(mode)
                }
            }
            .pickerStyle(.segmented)
            if let loadError = coordinator.loadError {
                Label(loadError, systemImage: "speaker.slash.fill")
                    .font(SceneViewTokens.TypeScale.caption)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
            }
        }
    }

    /// The identity row's bottom edge plus a gap: where the legend starts.
    private static func topReserve(safeTop: CGFloat) -> CGFloat {
        let slop = (SceneViewTokens.Layout.touchTarget - SceneViewTokens.Glass.iconButtonSize) / 2
        return safeTop + SceneViewTokens.Chrome.topGap - slop + SceneViewTokens.Layout.touchTarget
            + SceneViewTokens.Space.sm
    }

    /// Studio IBL for the sphere's shading, no backdrop: the stage stays dark like
    /// Android's so the ring and the shells read.
    private static let environment: SceneEnvironment = {
        var studio = SceneEnvironment.studio
        studio.showSkybox = false
        return studio
    }()
}

/// Android's two curves, same parameters (`SpatialAudioDemo.kt`, `activeFalloff`).
private enum FalloffMode: String, CaseIterable, Identifiable {
    case inverse = "Inverse"
    case linear = "Linear"
    var id: String { rawValue }

    var falloff: AudioFalloff {
        switch self {
        case .inverse: return .inverse(refDistance: 0.5, maxDistance: 6)
        case .linear: return .linear(refDistance: 0.2, maxDistance: 4)
        }
    }
}

// MARK: - Legend

/// Who is who in the scene, the live distance, and the gain the falloff applies.
/// Glass over the stage, so white in both themes — Android's `SpatialAudioLegend`.
private struct SpatialAudioLegend: View {
    let distance: Float
    let gain: Float

    var body: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
            row(label: "Sound source — the bell is attached to this sphere") {
                Circle().fill(SpatialAudioPalette.source)
            }
            row(label: "Listener — the camera, i.e. you") {
                Circle().strokeBorder(SceneViewTokens.Glass.onGlass,
                                      lineWidth: SceneViewTokens.Glass.borderWidth * 1.5)
            }
            Text("\(String(format: "%.2f", distance)) m away · \(Int((gain * 100).rounded()))% volume")
                .font(SceneViewTokens.TypeScale.caption)
                .foregroundStyle(SceneViewTokens.Glass.onGlass)
                .monospacedDigit()
                .accessibilityIdentifier("spatial-audio-readout")
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(SceneViewTokens.Glass.onGlassMuted.opacity(0.32))
                    Capsule().fill(SceneViewTokens.Glass.onGlass)
                        .frame(width: proxy.size.width * CGFloat(min(max(gain, 0), 1)))
                }
            }
            .frame(height: 4)
            .accessibilityHidden(true)
        }
        .padding(SceneViewTokens.Space.md)
        .frame(maxWidth: 320, alignment: .leading)
        .glassBackground(in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md,
                                              style: .continuous))
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func row<Swatch: View>(label: String, @ViewBuilder swatch: () -> Swatch) -> some View {
        HStack(spacing: SceneViewTokens.Space.sm) {
            swatch().frame(width: 12, height: 12)
            Text(label)
                .font(SceneViewTokens.TypeScale.caption)
                .foregroundStyle(SceneViewTokens.Glass.onGlassMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Android's `SceneViewColors.TintSoft` (source, shells) and `TintLight` (ring).
private enum SpatialAudioPalette {
    static let source = Color(red: 0xD2 / 255, green: 0xA8 / 255, blue: 0xFF / 255)
    static let sourceUI = UIColor(red: 0xD2 / 255, green: 0xA8 / 255, blue: 0xFF / 255, alpha: 1)
    static let ringUI = UIColor(red: 0xA4 / 255, green: 0xC1 / 255, blue: 0xFF / 255, alpha: 0.85)
}

// MARK: - Coordinator

/// Owns the emitter, the shells, the ring, the ``SpatialAudioNode`` and the
/// per-frame tick, off the SwiftUI struct so they survive `body` re-evaluation.
@MainActor
private final class SpatialAudioCoordinator: ObservableObject {
    /// Source-to-camera distance, metres — republished per centimetre.
    @Published private(set) var distance: Float = 0
    /// Linear gain the falloff gives at ``distance`` — the value pushed to RealityKit.
    @Published private(set) var gain: Float = 1
    @Published private(set) var loadError: String?

    /// Latest camera pose SceneView rendered, from `.onCameraChanged`.
    var cameraPose: SceneCameraPose = homePose

    /// Android's camera home, `Position(0, 0.55, 1.9)` looking at the origin.
    static let homePose = SceneCameraPose(
        azimuth: 0,
        elevation: atan2(0.55, 1.9),
        distance: (0.55 * 0.55 + 1.9 * 1.9).squareRoot(),
        target: .zero
    )

    // Android constants, value for value.
    private static let orbitRadius: Float = 0.32
    private static let emitterRadius: Float = 0.10
    private static let waveBaseRadius: Float = 0.12
    private static let waveMinScale: Float = 0.9
    private static let waveMaxScale: Float = 1.9
    private static let wavePeriod: Float = 1.6
    private static let waveAlphaMax: Float = 0.30
    private static let wavePhases: [Float] = [0, 1.0 / 3.0, 2.0 / 3.0]
    private static let revolutionsPerSecond: Float = 0.5

    private let emitter: ModelEntity = {
        var material = PhysicallyBasedMaterial()
        material.baseColor = .init(tint: SpatialAudioPalette.sourceUI)
        material.roughness = .init(floatLiteral: 0.35)
        material.metallic = .init(floatLiteral: 0)
        return ModelEntity(mesh: .generateSphere(radius: emitterRadius), materials: [material])
    }()
    private let waves: [ModelEntity] = wavePhases.map { _ in
        var material = UnlitMaterial(color: SpatialAudioPalette.sourceUI)
        material.blending = .transparent(opacity: .init(floatLiteral: waveAlphaMax))
        let shell = ModelEntity(mesh: .generateSphere(radius: waveBaseRadius), materials: [material])
        shell.components.set(OpacityComponent(opacity: 1))
        return shell
    }
    private let ring = PathNode.circle(radius: orbitRadius, segments: 64, thickness: 0.004,
                                       color: SpatialAudioPalette.ringUI)

    private var audioNode: SpatialAudioNode?
    private var falloff: AudioFalloff = FalloffMode.inverse.falloff
    private var timer: Timer?
    private var start = Date()
    private var loadStarted = false
    private var lastLog = Date.distantPast
    #if DEBUG
    private let log = Logger(subsystem: "io.github.sceneview.demo", category: "spatial-audio")
    #endif

    func attach(to root: Entity) {
        root.addChild(ring.entity)
        for shell in waves { root.addChild(shell) }
        root.addChild(emitter)
        tick()
        startTicking()
    }

    func loadIfNeeded() async {
        guard !loadStarted else { return }
        loadStarted = true
        do {
            let node = try await SpatialAudioNode.spatial(
                named: "bell.wav",
                falloff: falloff,
                loop: true,
                autoPlay: true
            )
            emitter.addChild(node.entity)
            audioNode = node
            tick()
        } catch {
            loadError = "Could not load the bell sound: \(error.localizedDescription)"
        }
    }

    func setFalloff(_ newFalloff: AudioFalloff) {
        falloff = newFalloff
        audioNode?.setFalloff(newFalloff)
        tick()
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        audioNode?.stop()
    }

    private func startTicking() {
        guard timer == nil else { return }
        start = Date()
        let t = Timer(timeInterval: 1.0 / 60.0, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    /// One frame: move the emitter, pulse the shells, re-measure source → camera
    /// and push the gain the picked falloff gives at that distance.
    private func tick() {
        let elapsed = Float(Date().timeIntervalSince(start))
        let angle = (elapsed * 2 * .pi * Self.revolutionsPerSecond)
            .truncatingRemainder(dividingBy: 2 * .pi)
        let position = SIMD3<Float>(cos(angle) * Self.orbitRadius, 0, sin(angle) * Self.orbitRadius)
        emitter.position = position

        let progress = (elapsed / Self.wavePeriod).truncatingRemainder(dividingBy: 1)
        for (index, shell) in waves.enumerated() {
            let t = (progress + Self.wavePhases[index]).truncatingRemainder(dividingBy: 1)
            shell.position = position
            shell.scale = .init(repeating: Self.waveMinScale + (Self.waveMaxScale - Self.waveMinScale) * t)
            shell.components.set(OpacityComponent(opacity: 1 - t))
        }

        // Both ends in world space: the listener is the rendered camera pose.
        let source = emitter.position(relativeTo: nil)
        let measured = simd_distance(source, cameraPose.cameraPosition())
        let pushed = audioNode?.updateGain(forDistance: measured)
            ?? AudioFalloff.gain(for: falloff, distance: measured)
        if abs(measured - distance) >= 0.01 { distance = measured }
        if abs(pushed - gain) >= 0.005 { gain = pushed }

        #if DEBUG
        let now = Date()
        if now.timeIntervalSince(lastLog) >= 0.5 {
            lastLog = now
            let db = audioNode?.playbackGainDecibels.map { String(format: "%.1f", $0) } ?? "n/a"
            let line = String(format: "falloff=%@ distance=%.3fm gain=%.3f controllerGainDb=%@",
                              falloffName, measured, pushed, db)
            log.notice("\(line, privacy: .public)")
        }
        #endif
    }

    private var falloffName: String {
        switch falloff {
        case .inverse: return "inverse"
        case .linear: return "linear"
        case .none: return "none"
        }
    }
}

import SwiftUI
import Combine
import RealityKit
import SceneViewSwift

/// A character animation workbench: the same bundled fox and clips as Android.
struct AnimationDemo: View {
    @State private var player = FoxAnimationPlayer()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                SceneViewTokens.Stage.background
                SceneView { root in
                    player.install(in: root)
                }
                .contentID(player.model != nil)
                .environment(Self.studio)
                .autoCenterContent(false)
                .cameraControls(.orbit)
                .cameraPose(Self.framing(geometry.size))
                .accessibilityIdentifier("animation-stage")

                if let error = player.error {
                    ContentUnavailableView {
                        Label("Fox could not load", systemImage: "exclamationmark.triangle")
                    } description: {
                        Text(error)
                    } actions: {
                        Button("Try again") { Task { await player.load() } }
                    }
                    .foregroundStyle(SceneViewTokens.Glass.onGlass)
                } else if player.model == nil {
                    ProgressView("Preparing the fox…")
                        .tint(SceneViewTokens.Glass.onGlass)
                        .foregroundStyle(SceneViewTokens.Glass.onGlass)
                }
            }
        }
        .demoChrome(
            title: "Animation",
            dock: [DockItem(icon: "repeat", label: "Loop", selected: player.loop) {
                player.setLoop(!player.loop)
            }],
            accent: DockItem(icon: player.isPlaying ? "pause.fill" : "play.fill",
                             label: player.isPlaying ? "Pause" : "Play",
                             enabled: player.ready) { player.togglePlayback() },
            accessory: { playbackPanel },
            controls: { details }
        )
        .task { await player.load() }
        .onChange(of: scenePhase) { _, phase in player.setActive(phase == .active) }
        .onDisappear { player.stop() }
    }

    private static var studio: SceneEnvironment {
        var environment = SceneEnvironment.studio
        environment.showSkybox = false
        environment.intensity = 0.8
        return environment
    }

    /// Fit the fox, leaving space below it for the transport. The floor is deliberately
    /// excluded from framing; otherwise its bounds shrink the character to a speck.
    private static func framing(_ size: CGSize) -> SceneCameraPose {
        let aspect = Float(max(size.width, 1) / max(size.height, 1))
        return SceneCameraPose(azimuth: .pi / 3, elevation: 0.23,
                               distance: max(2.5, 1.65 / aspect),
                               target: [0, -0.08, 0])
    }

    private var playbackPanel: some View {
        VStack(spacing: SceneViewTokens.Space.sm) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: SceneViewTokens.Space.xs) {
                    Text("Fox")
                        .font(SceneViewTokens.TypeScale.chromeLabel)
                    Text("Drag to orbit · Pinch to zoom")
                        .font(SceneViewTokens.TypeScale.chromeCaption)
                        .foregroundStyle(SceneViewTokens.Glass.onGlassMuted)
                }
                Spacer()
                Text(player.error != nil ? "Unavailable" : !player.ready ? "Loading"
                     : player.isPlaying ? "Playing" : "Paused")
                    .font(SceneViewTokens.TypeScale.chromeCaption)
                    .accessibilityIdentifier("animation-status")
            }
            DemoOptionStrip(Array(FoxAnimationPlayer.ranges.indices), selection: Binding(
                get: { player.selectedClip }, set: { player.select($0) }
            )) { FoxAnimationPlayer.ranges[$0].name }
            .disabled(!player.ready)

            HStack(spacing: SceneViewTokens.Space.md) {
                Image(systemName: "speedometer")
                    .accessibilityHidden(true)
                Slider(value: Binding(get: { player.speed }, set: { player.setSpeed($0) }),
                       in: 0.25...2, step: 0.25)
                    .accessibilityLabel("Playback speed")
                    .accessibilityIdentifier("animation-speed")
                Text(player.speed.formatted(.number.precision(.fractionLength(0...2))
                        .locale(Locale(identifier: "en_US"))) + "×")
                    .font(SceneViewTokens.TypeScale.chromeLabel.monospacedDigit())
                    .frame(minWidth: SceneViewTokens.Layout.touchTarget)
            }
            .disabled(!player.ready)
        }
        .foregroundStyle(SceneViewTokens.Glass.onGlass)
        .padding(SceneViewTokens.Space.md)
        .glassBackground(in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg), id: "transport")
    }

    private var details: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
            Text("A little character, three ways to move.")
                .font(.headline)
            Text("Survey looks around, Walk takes an easy stride, and Run breaks into a gallop. Tap a clip to play it; transitions blend smoothly between poses.")
            Text("Loop repeats the selected clip. Turn it off to play once. Pause holds the pose while you explore it from any angle.")
            Text("Fox by PixelMannen · rigging and animation by tomkranis. Khronos glTF Sample Assets · CC BY 4.0.")
                .font(SceneViewTokens.TypeScale.captionRegular)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
        }
    }
}

/// Owns entities and playback across SwiftUI updates. Playback starts from the scene's
/// update event, which proves attachment, rather than racing a timed attachment poll.
@MainActor @Observable
private final class FoxAnimationPlayer {
    struct ClipRange {
        let name: String
        let start: Double
        let end: Double
    }

    // USDZ concatenates the glTF clips at 24 fps, starting at frame 1.
    static let ranges = [
        ClipRange(name: "Survey", start: 0, end: 82.0 / 24),
        ClipRange(name: "Walk", start: 83.0 / 24, end: 100.0 / 24),
        ClipRange(name: "Run", start: 101.0 / 24, end: 128.0 / 24),
    ]
    var model: ModelNode?
    var error: String?
    var selectedClip = 0
    var isPlaying = true
    var speed = 1.0
    var loop = true
    var ready = false
    private var resources: [AnimationResource] = []
    private var controller: AnimationPlaybackController?
    /// `controller.time` when the current clip started, so Once measures the clip's own
    /// elapsed time whether RealityKit counts a trimmed view from 0 or from its trim start.
    private var clipStart: TimeInterval = 0
    private var updates: (any Cancellable)?
    private var attachment: Task<Void, Never>?
    private var active = true

    func load() async {
        guard model == nil else { return }
        error = nil
        do {
            let fox = try await ModelNode.load("khronos_fox_clips")
            fox.scaleToUnits(1.6).centerOrigin(normalized: [0, -1, 0])
            guard let source = fox.entity.availableAnimations.first else {
                throw AnimationError.missingClips
            }
            let clips = try Self.ranges.map { range in
                try AnimationResource.generate(with: AnimationView(
                    source: source.definition, name: range.name, fillMode: .forwards,
                    trimStart: range.start, trimEnd: range.end
                ))
            }
            guard !Task.isCancelled else { return }
            resources = clips
            model = fox
        } catch {
            self.error = error.localizedDescription
        }
    }

    func install(in root: Entity) {
        guard let model else { return }
        root.addChild(model.entity)

        var material = PhysicallyBasedMaterial()
        material.baseColor = .init(tint: SceneViewTokens.Stage.trayFloor(dark: true))
        material.roughness = 0.8
        let floor = ModelEntity(mesh: .generateCylinder(height: 0.06, radius: 1.15),
                                materials: [material])
        floor.position.y = -0.035
        root.addChild(floor)
        let key = LightNode.directional(color: .warm, intensity: 2800, castsShadow: true)
            .position([2, 4, 3]).lookAt([0, 0, 0])
        root.addChild(key.entity)
        let rim = LightNode.spot(color: .white, intensity: 1600,
                                 innerAngle: .pi / 5, outerAngle: .pi / 3,
                                 attenuationRadius: 8)
            .position([-2, 2, -2]).lookAt([0, 0.3, 0])
        root.addChild(rim.entity)

        updates?.cancel()
        // The builder may run before the root enters a scene. Entity subscriptions can
        // only be installed once attached; the cancellable task owns that short wait.
        attachment?.cancel()
        attachment = Task { @MainActor [weak self, weak root] in
            while let root, root.scene == nil {
                guard !Task.isCancelled, self?.model != nil else { return }
                try? await Task.sleep(for: .milliseconds(16))
            }
            guard !Task.isCancelled, let self, let scene = root?.scene else { return }
            self.updates = scene.subscribe(to: SceneEvents.Update.self) { [weak self] _ in
                MainActor.assumeIsolated { self?.updatePlayback() }
            }
        }
    }

    private func updatePlayback() {
        if !ready {
            ready = true
            play()
        } else if !loop, isPlaying, reachedEnd {
            controller?.pause()
            isPlaying = false
        }
    }

    /// A forwards-filled AnimationView keeps its controller alive after the last pose;
    /// `isComplete` alone therefore never changes the transport to Play on this asset.
    private var reachedEnd: Bool {
        guard let controller else { return false }
        let range = Self.ranges[selectedClip]
        return controller.isComplete
            || (!loop && controller.time - clipStart >= range.end - range.start - 0.001)
    }

    func select(_ index: Int) {
        guard ready, resources.indices.contains(index) else { return }
        selectedClip = index
        isPlaying = true
        play()
    }

    private func play() {
        guard ready, let model, resources.indices.contains(selectedClip) else { return }
        let resource = resources[selectedClip]
        // Keep the outgoing controller alive: RealityKit blends from it on the same layer.
        controller = model.entity.playAnimation(loop ? resource.repeat() : resource,
                                                transitionDuration: 0.35,
                                                startsPaused: !isPlaying || !active)
        controller?.speed = Float(speed)
        clipStart = controller?.time ?? 0
    }

    func setLoop(_ value: Bool) {
        loop = value
        play()
    }

    func setSpeed(_ value: Double) {
        speed = value
        controller?.speed = Float(value)
    }

    func togglePlayback() {
        guard ready else { return }
        isPlaying.toggle()
        if isPlaying {
            if reachedEnd { play() } else { controller?.resume() }
        } else {
            controller?.pause()
        }
    }

    func setActive(_ value: Bool) {
        active = value
        if value && isPlaying { controller?.resume() } else { controller?.pause() }
    }

    func stop() {
        attachment?.cancel()
        attachment = nil
        updates?.cancel()
        updates = nil
        controller?.stop()
        controller = nil
        ready = false
        // Drop the model too: when the view comes back, `.task` reloads it and the new
        // content ID reinstalls the stage and its update subscription.
        model = nil
    }

    private enum AnimationError: LocalizedError {
        case missingClips
        var errorDescription: String? { "The bundled fox has no animation clips." }
    }
}

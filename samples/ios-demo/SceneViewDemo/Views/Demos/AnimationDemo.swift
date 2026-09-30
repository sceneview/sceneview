import SwiftUI
import RealityKit
import SceneViewSwift

/// Animation — a rigged character on a turntable, one clip at a time.
///
/// Mirrors Android's `AnimationPhysicsDemo` (`samples/android-demo/.../AnimationPhysicsDemo.kt`):
/// it opens on the same Khronos fox, with the same clip picker (Survey · Walk · Run), the
/// same status card (clip · Playing/Paused · time / duration), play/pause, scrub, speed and
/// Loop/Once, and the same subjects after it (Soldier, then the streamed `animation` slugs).
///
/// ### One USDZ, three clips
///
/// A USDZ binds a single `SkelAnimation`, so RealityKit exposes one timeline where the glTF
/// has three named clips. `khronos_fox_clips.usdz` lays Survey, Walk and Run end to end on
/// that timeline (Blender NLA, frames 1–83 / 84–101 / 102–129 at 24 fps), and ``FoxClips``
/// cuts it back into three resources with `AnimationView` trims. The playback controller's
/// `time` then runs from 0 inside the chosen clip, exactly like Android's clip time.
///
/// ### Stage
///
/// A skybox-less studio IBL over ``SceneViewTokens/Stage/studioBackdrop`` — Android's themed
/// studio skybox: neutral grey in light, `stage-background` in dark.
///
/// ### Not ported (said in the sheet)
///
/// The cinematic camera shots, the brightness dial and the two-clip blend drive Filament's
/// camera, IBL and `Animator.applyCrossFade` directly; iOS keeps the orbit camera with a
/// slow turntable and one clip at a time.
struct AnimationDemo: View {
    /// A subject of the carousel — exactly one of `bundledAsset` / `streamedSlug` is set.
    private struct AnimationSubject {
        let displayName: String
        let streamedSlug: SketchfabSlug?
        let bundledAsset: String?
        let scale: Float
        /// Clip table cutting the model's single timeline into named clips, or `nil` for
        /// one clip spanning the whole timeline.
        let clipTable: [ClipRange]?
        /// Clip the subject opens on — Android's `defaultAnimationIndex`.
        let defaultClip: Int

        init(displayName: String, streamedSlug: SketchfabSlug? = nil, bundledAsset: String? = nil,
             scale: Float, clipTable: [ClipRange]? = nil, defaultClip: Int = 0) {
            precondition((streamedSlug == nil) != (bundledAsset == nil),
                         "AnimationSubject must define exactly one of streamedSlug or bundledAsset.")
            self.displayName = displayName
            self.streamedSlug = streamedSlug
            self.bundledAsset = bundledAsset
            self.scale = scale
            self.clipTable = clipTable
            self.defaultClip = defaultClip
        }
    }

    /// A named span of a model's timeline, in seconds from its first frame.
    private struct ClipRange {
        let name: String
        let start: Double
        let end: Double
    }

    /// A clip ready to play: a looping and a play-once resource over the same span.
    private struct Clip {
        let name: String
        let duration: Double
        let looping: AnimationResource
        let once: AnimationResource
    }

    /// The fox's three glTF clips on the concatenated timeline of `khronos_fox_clips.usdz`
    /// (24 fps, time 0 = frame 1): Survey 1–83, Walk 84–101, Run 102–129.
    private static let foxClips: [ClipRange] = [
        ClipRange(name: "Survey", start: 0 / 24, end: 82 / 24),
        ClipRange(name: "Walk", start: 83 / 24, end: 100 / 24),
        ClipRange(name: "Run", start: 101 / 24, end: 128 / 24),
    ]

    /// The three.js soldier's moving clips on the timeline of `threejs_soldier_clips.usdz`
    /// (24 fps, time 0 = frame 1): Idle 1–49, Walk 50–75, Run 76–93. The glTF's static
    /// TPose is left out.
    private static let soldierClips: [ClipRange] = [
        ClipRange(name: "Idle", start: 0 / 24, end: 48 / 24),
        ClipRange(name: "Walk", start: 49 / 24, end: 74 / 24),
        ClipRange(name: "Run", start: 75 / 24, end: 92 / 24),
    ]

    private static let subjects: [AnimationSubject] = {
        var items: [AnimationSubject] = [
            // Same first subject as Android: the Khronos fox, its three clips.
            AnimationSubject(displayName: "Fox", bundledAsset: "khronos_fox_clips", scale: 1.0,
                             clipTable: foxClips),
            // Android's `threejs_soldier.glb`, opening on Walk like Android.
            AnimationSubject(displayName: "Soldier", bundledAsset: "threejs_soldier_clips", scale: 1.0,
                             clipTable: soldierClips, defaultClip: 1),
        ]
        for slug in SampleAssets.byCategory["animation"] ?? [] {
            items.append(AnimationSubject(displayName: slug.displayName, streamedSlug: slug,
                                          scale: slug.scaleToUnits))
        }
        return items
    }()

    @State private var selectedIndex = 0
    @State private var clips: [Clip] = []
    @State private var selectedClip = 0
    @State private var isPlaying = true
    @State private var loop = true
    @State private var speed: Double = 1.0
    /// Seconds into the selected clip — what the status card, the progress bar and the
    /// scrub slider read. Sampled from the playback controller while playing.
    @State private var clipTime: Double = 0
    @State private var controller: AnimationPlaybackController?
    @State private var loadedNode: ModelNode?
    @State private var loadError: String?
    /// What the resolver handed back for a *streamed* subject (#2960).
    @State private var resolvedURL: URL?

    private let hasSketchfabKey: Bool = SketchfabConfig.apiKey != nil

    private var selectedSubject: AnimationSubject { Self.subjects[selectedIndex] }

    private var activeClip: Clip? { clips.indices.contains(selectedClip) ? clips[selectedClip] : nil }

    /// `nil` for bundled subjects: they are loaded from the app bundle and labelled as
    /// themselves, so there is no origin question to answer.
    private var assetSource: AssetSourceState? {
        guard selectedSubject.streamedSlug != nil else { return nil }
        return AssetSourceProbe.of(resolvedURL: resolvedURL, hasAPIKey: hasSketchfabKey,
                                   loaded: loadedNode != nil)
    }

    var body: some View {
        stage
            .demoChrome(
                title: "Animation",
                dock: [
                    DockItem(icon: "repeat", label: "Loop", selected: loop) { loop.toggle() },
                ],
                accent: DockItem(icon: isPlaying ? "pause.fill" : "play.fill",
                                 label: isPlaying ? "Pause" : "Play",
                                 enabled: activeClip != nil) { togglePlayback() },
                accessory: {
                    VStack(spacing: SceneViewTokens.Space.sm) {
                        statusCard
                        if clips.count > 1 {
                            DemoOptionStrip(Array(clips.indices), selection: $selectedClip) { clips[$0].name }
                        }
                    }
                },
                status: {
                    AssetSourceStatus(state: assetSource,
                                      isPlaceholder: selectedSubject.streamedSlug?.fallbackRole == .placeholder)
                },
                controls: { controlsSheet }
            )
            .task { _ = await SketchfabAssetResolver.shared.prefetchAll(category: "animation") }
            .task(id: selectedIndex) { await loadSelectedSubject() }
            .task(id: isPlaying) { await sampleClipTime() }
            .onChange(of: selectedClip) { _, _ in startClip(at: 0) }
            .onChange(of: loop) { _, _ in startClip(at: clipTime) }
            .onChange(of: speed) { _, newSpeed in controller?.speed = Float(newSpeed) }
            .onDisappear { controller?.stop() }
    }

    // MARK: Stage

    private var stage: some View {
        ZStack {
            SceneViewTokens.Stage.studioBackdrop
            // The scene stays mounted for the whole demo — never wrapped in `if let` and
            // never re-keyed with `.id(_:)`: a re-created `RealityView` on iOS 26 Simulator
            // intermittently renders nothing at all, for good (#3008). `.contentID(_:)`
            // swaps the model inside the scene that is already rendering. The key is
            // optional so it also changes when the model finishes loading.
            SceneView { root in
                guard let loadedNode else { return }
                loadedNode.entity.position = .init(x: 0, y: 0, z: -2)
                root.addChild(loadedNode.entity)
                // RealityKit ignores `playAnimation` on an entity outside a scene, so the
                // character stood in its rest pose on first open (#3907). Start the clip
                // once the entity is attached.
                Task { @MainActor in await startWhenAttached(loadedNode) }
            }
            .cameraControls(.orbit)
            .autoRotate(speed: 0.3)
            .environment(Self.environment)
            .contentID(loadedContentKey)

            if loadedNode == nil {
                VStack(spacing: SceneViewTokens.Space.sm) {
                    ProgressView()
                    Text(loadError ?? "Loading \(selectedSubject.displayName)…")
                        .font(SceneViewTokens.TypeScale.caption)
                        .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, SceneViewTokens.Space.lg)
                }
            }
        }
    }

    /// The studio IBL lights the character; the backdrop behind it is the themed colour.
    private static var environment: SceneEnvironment {
        var studio = SceneEnvironment.studio
        studio.showSkybox = false
        return studio
    }

    private var loadedContentKey: String? {
        guard loadedNode != nil else { return nil }
        return selectedSubject.streamedSlug?.uid ?? selectedSubject.bundledAsset ?? "none"
    }

    // MARK: Chrome

    /// Android's top card — clip name, "Playing · 1.2 / 3.4 s" and a progress bar — as a
    /// glass card above the dock.
    private var statusCard: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.xs) {
            if let clip = activeClip {
                Text(clip.name)
                    .font(SceneViewTokens.TypeScale.chromeLabel)
                Text("\(isPlaying ? "Playing" : "Paused") · \(Self.seconds(clipTime)) / \(Self.seconds(clip.duration)) s")
                    .font(SceneViewTokens.TypeScale.chromeCaption.monospacedDigit())
                    .foregroundStyle(SceneViewTokens.Glass.onGlassMuted)
                ProgressView(value: min(clipTime / max(clip.duration, 0.0001), 1))
                    .tint(SceneViewTokens.HomeColor.primary)
            } else {
                Text(selectedSubject.displayName)
                    .font(SceneViewTokens.TypeScale.chromeLabel)
                Text("Loading…")
                    .font(SceneViewTokens.TypeScale.chromeCaption)
                    .foregroundStyle(SceneViewTokens.Glass.onGlassMuted)
                ProgressView(value: 0.0)
                    .tint(SceneViewTokens.HomeColor.primary)
            }
        }
        .foregroundStyle(SceneViewTokens.Glass.onGlass)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(SceneViewTokens.Space.md - SceneViewTokens.Space.xs)
        .glassBackground(in: RoundedRectangle(cornerRadius: SceneViewTokens.Glass.pillHeight / 2,
                                              style: .continuous),
                         id: "status")
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("animation-status")
    }

    @ViewBuilder
    private var controlsSheet: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
            if clips.count > 1 {
                sheetLabel("Animation clip")
                Picker("Animation clip", selection: $selectedClip) {
                    ForEach(clips.indices, id: \.self) { Text(clips[$0].name).tag($0) }
                }
                .pickerStyle(.segmented)
            }

            if let clip = activeClip {
                LabeledSlider(
                    label: "Scrub pose · pauses playback",
                    value: Binding(get: { min(clipTime, clip.duration) }, set: { scrub(to: $0) }),
                    range: 0...max(clip.duration, 0.0001),
                    valueText: "\(Self.seconds(clipTime)) / \(Self.seconds(clip.duration)) s"
                )
            }

            HStack {
                sheetLabel("Playback")
                Spacer()
                Button(action: togglePlayback) {
                    Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .frame(width: SceneViewTokens.Layout.touchTarget,
                               height: SceneViewTokens.Layout.touchTarget)
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.circle)
                .tint(SceneViewTokens.HomeColor.primary)
                .disabled(activeClip == nil)
                .accessibilityLabel(isPlaying ? "Pause" : "Play")
            }

            LabeledSlider(label: "Speed", value: $speed, range: 0.25...3.0, step: 0.25,
                          valueText: String(format: "%.1f×", speed))

            Picker("Repeat", selection: $loop) {
                Text("Loop").tag(true)
                Text("Once").tag(false)
            }
            .pickerStyle(.segmented)

            sheetLabel("Subject")
            Picker("Subject", selection: $selectedIndex) {
                ForEach(Self.subjects.indices, id: \.self) { Text(Self.subjects[$0].displayName).tag($0) }
            }
            .pickerStyle(.menu)
            .tint(SceneViewTokens.HomeColor.primary)

            Text("RealityKit plays one clip of the model's timeline at a time: AnimationView trims "
                 + "each clip, and the playback controller's speed, pause and time drive it. The "
                 + "cinematic camera shots, brightness dial and two-clip blend are on Android only.")
                .font(SceneViewTokens.TypeScale.captionRegular)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)

            if let slug = selectedSubject.streamedSlug {
                // Credits the model actually on screen — streamed author or the bundled
                // fallback's own author and licence (#2966).
                AssetCreditLine(slug: slug, source: assetSource ?? .streaming)
            }
        }
    }

    private func sheetLabel(_ text: String) -> some View {
        Text(text).font(.subheadline.weight(.semibold))
    }

    private static func seconds(_ value: Double) -> String { String(format: "%.1f", value) }

    // MARK: Loading

    @MainActor
    private func loadSelectedSubject() async {
        let subject = selectedSubject
        controller?.stop()
        controller = nil
        loadedNode = nil
        clips = []
        clipTime = 0
        loadError = nil
        resolvedURL = nil
        do {
            let node: ModelNode
            if let slug = subject.streamedSlug {
                let url = try await SketchfabAssetResolver.shared.resolve(slug)
                resolvedURL = url
                node = try await ModelNode.load(contentsOf: url)
            } else if let bundled = subject.bundledAsset {
                node = try await ModelNode.load(bundled)
            } else {
                return
            }
            _ = node.scaleToUnits(subject.scale)
            _ = node.centerOrigin()
            let built = Self.clips(of: node, table: subject.clipTable)
            guard !Task.isCancelled else { return }
            clips = built
            selectedClip = built.indices.contains(subject.defaultClip) ? subject.defaultClip : 0
            isPlaying = true
            // The clip starts from the scene's content closure, once attached.
            loadedNode = node
        } catch {
            loadError = error.localizedDescription
        }
    }

    /// Cuts the model's timeline into playable clips. RealityKit lists the same timeline
    /// twice (a "global scene" and a "default subtree" animation), so only the first is used.
    private static func clips(of node: ModelNode, table: [ClipRange]?) -> [Clip] {
        guard let source = node.entity.availableAnimations.first else { return [] }
        let full = source.definition.duration
        let ranges = table ?? [ClipRange(name: "Clip 1", start: 0, end: full)]
        return ranges.compactMap { range in
            let end = min(range.end, full)
            guard end > range.start else { return nil }
            let loopView = AnimationView(source: source.definition, name: range.name,
                                         trimStart: range.start, trimEnd: end)
            // `.forwards` holds the last pose when a play-once clip ends, like Android.
            let onceView = AnimationView(source: source.definition, name: range.name, fillMode: .forwards,
                                         trimStart: range.start, trimEnd: end)
            guard let looping = try? AnimationResource.generate(with: loopView),
                  let once = try? AnimationResource.generate(with: onceView) else { return nil }
            return Clip(name: range.name, duration: end - range.start,
                        looping: looping.repeat(), once: once)
        }
    }

    // MARK: Playback

    @MainActor
    private func startWhenAttached(_ node: ModelNode) async {
        for _ in 0..<30 where node.entity.scene == nil {
            try? await Task.sleep(for: .milliseconds(16))
            guard !Task.isCancelled else { return }
        }
        // A newer subject may have replaced this one while we waited.
        guard node.entity === loadedNode?.entity else { return }
        startClip(at: 0)
    }

    /// (Re)starts the selected clip at `time` seconds, paused or playing as the UI says.
    @MainActor
    private func startClip(at time: Double) {
        guard let node = loadedNode, node.entity.scene != nil, let clip = activeClip else { return }
        controller?.stop()
        let playback = node.entity.playAnimation(loop ? clip.looping : clip.once,
                                                 transitionDuration: 0.2, startsPaused: !isPlaying)
        playback.speed = Float(speed)
        let start = min(max(time, 0), clip.duration)
        playback.time = start
        controller = playback
        clipTime = start
    }

    @MainActor
    private func togglePlayback() {
        guard let clip = activeClip else { return }
        if isPlaying {
            controller?.pause()
            isPlaying = false
        } else {
            isPlaying = true
            // A play-once clip that already reached its end starts over, like Android.
            if controller == nil || controller?.isComplete == true || (!loop && clipTime >= clip.duration - 0.01) {
                startClip(at: 0)
            } else {
                controller?.resume()
            }
        }
    }

    @MainActor
    private func scrub(to time: Double) {
        isPlaying = false
        guard let clip = activeClip else { return }
        if controller == nil || controller?.isComplete == true { startClip(at: time) }
        controller?.pause()
        clipTime = min(max(time, 0), clip.duration)
        controller?.time = clipTime
    }

    /// While playing, samples the controller's time about 30 times a second into
    /// `clipTime`, and flips to Paused when a play-once clip ends.
    @MainActor
    private func sampleClipTime() async {
        while isPlaying && !Task.isCancelled {
            if let clip = activeClip, let controller {
                if !loop && (controller.isComplete || controller.time >= clip.duration) {
                    clipTime = clip.duration
                    isPlaying = false
                    return
                }
                clipTime = loop ? controller.time.truncatingRemainder(dividingBy: clip.duration)
                                : min(controller.time, clip.duration)
            }
            try? await Task.sleep(for: .milliseconds(33))
        }
    }
}

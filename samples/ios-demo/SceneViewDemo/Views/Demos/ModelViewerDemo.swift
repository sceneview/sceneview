import SwiftUI
import RealityKit
import SceneViewSwift
#if os(iOS)
import ARKit
#endif

/// Full-screen 3D model viewer — the iOS twin of Android's `ModelViewerDemo.kt`
/// after the showcase redesign.
///
/// **Stage.** A `#0B0F16` stage (`SceneViewTokens.Stage.background`), no
/// auto-rotate: the model sits still on its fitted framing (12 % margin,
/// `framingMargin(1.12)`) until the user orbits it. Under `qa_mode` the
/// authored three-quarter pose is what a capture lands on.
///
/// **Dock.** Recenter · Environment · Models · Animate (only when the loaded
/// entity has animation clips) · accent "View in AR", enabled when ARKit world
/// tracking is available, which presents the existing `ARPlacementDemo` armed
/// with the selected bundled model.
///
/// **Sheets.** Models — the bundled USDZ grid (1:1 `model_thumb_*` tiles copied
/// from Android) plus the "Surprise me" Sketchfab row (hidden without an API
/// key) and "Browse online models"; Environment — the bundled HDRs with their
/// `env_thumb_*` tiles, an IBL intensity slider and a skybox toggle.
///
/// Honours the umbrella's hard rules: no Sketchfab WebView, local file URLs
/// only, something useful renders offline (the bundled hero).
struct ModelViewerDemo: View {
    /// Bundled models offered in the Models sheet. The Khronos set mirrors
    /// Android's grid; the hovercar is the iOS store hero, selected under
    /// `qa_mode` by ``storeHeroAssetName`` rather than by being first here.
    private static let bundledModels: [BundledViewerModel] = [
        BundledViewerModel(assetName: "khronos_damaged_helmet", displayName: "Damaged Helmet"),
        BundledViewerModel(assetName: "khronos_fox", displayName: "Fox"),
        BundledViewerModel(assetName: "khronos_lantern", displayName: "Lantern"),
        BundledViewerModel(assetName: "khronos_toy_car", displayName: "Toy Car"),
        BundledViewerModel(assetName: "cyberpunk_hovercar", displayName: "Cyberpunk Hovercar"),
        BundledViewerModel(assetName: "animated_butterfly", displayName: "Butterfly"),
    ]

    /// Bundled HDRs offered in the Environment sheet, in Android's order.
    private static let environments: [ViewerEnvironment] = [
        ViewerEnvironment(assetName: "studio", displayName: "Studio"),
        ViewerEnvironment(assetName: "studio_warm", displayName: "Studio Warm"),
        ViewerEnvironment(assetName: "sunset", displayName: "Sunset"),
        ViewerEnvironment(assetName: "outdoor_cloudy", displayName: "Outdoor Cloudy"),
        ViewerEnvironment(assetName: "night_sky", displayName: "Night Sky"),
        ViewerEnvironment(assetName: "rooftop_night", displayName: "Rooftop Night"),
    ]

    /// The subject App Store slot 1 is meant to show. The interactive default
    /// is `bundledModels[0]` (Damaged Helmet) — the Khronos reference model a
    /// first-run user should land on — but `dynamic-sky`, which fills slot 2,
    /// loads that *same* helmet since #3003. Left alone the listing showed one
    /// subject twice: the redesign (#3308) rewrote this view and kept the
    /// "hovercar is the iOS store hero" comment while defaulting to index 0,
    /// so the store hero silently stopped being captured (#3006). Under
    /// `qa_mode` only — the interactive first-run subject is unchanged.
    private static let storeHeroAssetName = "cyberpunk_hovercar"

    /// Fitted framing: the bounding sphere plus 12 % of air, which clears the
    /// dock band at the bottom of the viewport.
    private static let framingMargin: Float = 1.12
    /// Under `qa_mode` the pose is frozen, so the store capture fills the frame.
    ///
    /// Tighter than `DynamicSkyDemo`'s 0.75 because the subjects differ in
    /// aspect, not in preference: the auto-fit pass inscribes the *space
    /// diagonal* of the union bounds in a sphere and fits that sphere to the
    /// narrower of the two FOV axes — width, in a portrait store frame. A
    /// near-isotropic subject (the helmet) fills that sphere; the hovercar is
    /// wide and short, so the same margin leaves it visibly smaller.
    ///
    /// 0.62 is the floor, not a preference: swept on the 6.9" simulator with
    /// the `-camera_distance` override (#2785), 0.75 leaves the car at roughly
    /// 45 % of the frame width and 0.5 clips its tail against the right edge.
    /// It cannot go lower while the car renders off-centre — the union bounds
    /// this pass fits are visibly wider than the car's silhouette, so the car
    /// sits right of the frame centre and runs out of room on that side long
    /// before the empty left third is used. Closing that gap is a
    /// `CameraControls.fitRadius` change (fit the projected AABB rather than
    /// the space-diagonal sphere), not a constant, and is out of scope here.
    private static let captureFramingMargin: Float = 0.62

    private enum ViewerSheet: Identifiable {
        case models, environment
        var id: Self { self }
    }

    @State private var selectedModel: BundledViewerModel = ModelViewerDemo.bundledModels[0]
    @State private var loadedNode: ModelNode?
    @State private var loadError: String?
    @State private var loadCount = 0
    @State private var recenterGeneration = 0
    @State private var sheet: ViewerSheet?
    @State private var showExplore = false
    @State private var showAR = false

    @State private var environment: ViewerEnvironment = ModelViewerDemo.environments[0]
    @State private var iblIntensity: Float = 1
    @State private var showSkybox = false

    @State private var animationNames: [String] = []
    @State private var animationBarOpen = false
    @State private var selectedAnimation = 0
    @State private var animationPlaying = true
    @State private var animationProgress: Float = 0
    @State private var playback: AnimationPlaybackController?

    @State private var surpriseInFlight = false
    @State private var surpriseError: String?
    @State private var streamedDisplayName: String?

    private let hasSketchfabKey: Bool = SketchfabConfig.apiKey != nil

    /// `-qa_mode 1` / `?qa_mode=1` — keeps the authored pose for captures.
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false

    private var arSupported: Bool {
        #if os(iOS) && !targetEnvironment(simulator)
        return ARWorldTrackingConfiguration.isSupported
        #else
        return false
        #endif
    }

    private var sceneEnvironment: SceneEnvironment {
        SceneEnvironment.custom(
            name: environment.displayName,
            hdrFile: "\(environment.assetName).hdr",
            intensity: iblIntensity,
            showSkybox: showSkybox
        )
    }

    private var dock: [DockItem] {
        var items = [
            DockItem(icon: "scope", label: "Recenter") { recenterGeneration += 1 },
            DockItem(icon: "sun.max", label: "Environment") { sheet = .environment },
            DockItem(icon: "cube.transparent", label: "Models") { sheet = .models },
        ]
        if !animationNames.isEmpty {
            items.append(DockItem(icon: "play.circle", label: "Animate", selected: animationBarOpen) {
                withAnimation(SceneViewTokens.Spring.animation) { animationBarOpen.toggle() }
            })
        }
        return items
    }

    /// Raw `-camera_distance <float>` launch-arg override, written by
    /// `SceneViewDemoApp` into `UserDefaults` (#2785). `0` is the "unset"
    /// sentinel — see `DeepLinkRouter.cameraDistanceDefaultsKey`.
    @AppStorage(DeepLinkRouter.cameraDistanceDefaultsKey) private var cameraDistanceRaw: Double = 0

    /// Validated `-camera_distance` override, or `nil` when absent — mirrors
    /// Android's nullable `DemoSettings.cameraDistance`. Threaded into
    /// ``sceneView``'s `.framingMargin(_:)` in place of the demo's own
    /// interactive / `qa_mode` defaults, the same "explicit override wins
    /// over auto-fit" precedence Android's `rememberHeroOrbitCameraManipulator`
    /// applies to `radius` (`DemoHelpers.kt`, #1571). Note `.framingMargin(_:)`
    /// itself clamps to `0.2...10` (`SceneView.swift`) — narrower than
    /// `DeepLinkRouter`'s accepted `0.05...100`, since it is a fit-margin
    /// multiplier, not the absolute-metre distance Android's `radius` is;
    /// callers targeting a store-tight frame want small values (< 1) anyway.
    private var cameraDistanceOverride: Float? {
        cameraDistanceRaw > 0 ? Float(cameraDistanceRaw) : nil
    }

    var body: some View {
        ZStack {
            SceneViewTokens.Stage.background.ignoresSafeArea()
            sceneView
            VStack {
                Spacer()
                if let surpriseError {
                    errorBanner(surpriseError)
                        .padding(.bottom, SceneViewTokens.Space.sm)
                }
                if let name = streamedDisplayName {
                    GlassPill {
                        Text("Streamed: \(name)")
                            .font(SceneViewTokens.TypeScale.caption)
                            .foregroundStyle(SceneViewTokens.Glass.onGlass)
                            .lineLimit(1)
                    }
                    .padding(.bottom, SceneViewTokens.Space.sm)
                }
                if animationBarOpen && !animationNames.isEmpty {
                    AnimationBar(
                        clipNames: animationNames,
                        selectedClip: $selectedAnimation,
                        playing: $animationPlaying,
                        progress: $animationProgress,
                        onScrub: scrub
                    )
                    .padding(.horizontal, SceneViewTokens.Space.md)
                    .transition(.opacity)
                }
            }
            // Stack above the dock band.
            .padding(.bottom, SceneViewTokens.Layout.dockHeight + SceneViewTokens.Space.md * 2)
        }
        .demoChrome(
            title: "Model Viewer",
            dock: dock,
            accent: DockItem(icon: "arkit", label: "View in AR", enabled: arSupported) { showAR = true },
            onReset: resetAll
        )
        .sheet(item: $sheet) { which in
            Group {
                switch which {
                case .models:
                    ModelPickerSheet(
                        models: Self.bundledModels,
                        selected: selectedModel,
                        surpriseAvailable: hasSketchfabKey,
                        surpriseLoading: surpriseInFlight,
                        onSelect: { model in
                            sheet = nil
                            selectedModel = model
                            Task { await loadBundled(model) }
                        },
                        onSurprise: {
                            sheet = nil
                            Task { await rollSurpriseModel() }
                        },
                        onBrowse: {
                            sheet = nil
                            showExplore = true
                        }
                    )
                case .environment:
                    EnvironmentSheet(
                        environments: Self.environments,
                        selected: environment,
                        intensity: $iblIntensity,
                        showSkybox: $showSkybox,
                        onSelect: { environment = $0 },
                        onReset: {
                            environment = Self.environments[0]
                            iblIntensity = 1
                            showSkybox = false
                        }
                    )
                }
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            #if os(iOS)
            .presentationBackgroundInteraction(.enabled(upThrough: .medium))
            .presentationBackground(.regularMaterial)
            .presentationCornerRadius(SceneViewTokens.Radius.xl)
            #endif
        }
        .sheet(isPresented: $showExplore) {
            ExploreTab()
        }
        #if os(iOS)
        .fullScreenCover(isPresented: $showAR) {
            NavigationStack {
                ARPlacementDemo(initialModel: selectedModel.assetName)
                    .navigationTitle("Tap to Place")
                    .navigationBarTitleInline()
            }
            .environment(\.demoTitle, "Tap to Place")
        }
        #endif
        .task {
            // Under `qa_mode` the store hero replaces the first-run default,
            // so slot 1 captures the hovercar instead of repeating slot 2's
            // helmet (#3006). Assigned before the load so a single pass runs.
            if qaMode,
               let hero = Self.bundledModels.first(where: { $0.assetName == Self.storeHeroAssetName }) {
                selectedModel = hero
            }
            await loadBundled(selectedModel)
        }
        .onChange(of: selectedAnimation) { _, index in
            play(clip: index)
        }
        .onChange(of: animationPlaying) { _, playing in
            if playing { playback?.resume() } else { playback?.pause() }
        }
    }

    @ViewBuilder
    private var sceneView: some View {
        ZStack {
            // Mounted once and never re-keyed with `.id(_:)` — see #3008.
            // `.contentID(_:)` swaps the model inside the live scene and re-arms
            // the fit-to-bounds pass, which is also what "Recenter" relies on.
            SceneView { root in
                guard let loadedNode else { return }
                root.addChild(loadedNode.entity)
            }
            .cameraControls(.orbit)
            .cameraOrbit(azimuth: .pi / 5)
            .environment(sceneEnvironment)
            // `cameraDistanceOverride` — the `-camera_distance <float>` launch
            // arg (#2785) — wins over both when present, same as Android's
            // `DemoSettings.cameraDistance` beating its own `radius` default.
            .framingMargin(cameraDistanceOverride ?? (qaMode ? Self.captureFramingMargin : Self.framingMargin))
            .contentID(loadedNode == nil ? nil : "\(loadCount)-\(recenterGeneration)")
            .ignoresSafeArea()

            if loadedNode == nil {
                VStack(spacing: SceneViewTokens.Space.sm + 4) {
                    ProgressView().tint(.white)
                    if let loadError {
                        Text(loadError)
                            .font(SceneViewTokens.TypeScale.captionRegular)
                            .foregroundStyle(SceneViewTokens.Glass.onGlassMuted)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, SceneViewTokens.Space.xl)
                    }
                }
            }
        }
    }

    private func errorBanner(_ message: String) -> some View {
        Text(message)
            .font(SceneViewTokens.TypeScale.captionRegular)
            .foregroundStyle(SceneViewTokens.Glass.onGlass)
            .lineLimit(2)
            .multilineTextAlignment(.center)
            .padding(.horizontal, SceneViewTokens.Space.md)
            .padding(.vertical, SceneViewTokens.Space.sm)
            .background(glassBackground(in: Capsule()))
            .padding(.horizontal, SceneViewTokens.Space.lg)
    }

    // MARK: - Loading

    @MainActor
    private func loadBundled(_ model: BundledViewerModel) async {
        loadError = nil
        streamedDisplayName = nil
        do {
            let node = try await ModelNode.load(model.assetName)
            install(node)
        } catch {
            loadError = "Could not load \(model.displayName): \(error.localizedDescription)"
        }
    }

    /// Puts a freshly loaded node on stage: normalised to 0.6 units and
    /// centred (auto-fit framing then adapts the orbit radius), animation
    /// state rebuilt from the entity's clips.
    @MainActor
    private func install(_ node: ModelNode) {
        _ = node.scaleToUnits(0.6)
        _ = node.centerOrigin()
        playback = nil
        loadedNode = node
        loadCount += 1
        animationNames = node.entity.availableAnimations.enumerated().map { index, clip in
            clip.name ?? "Clip \(index + 1)"
        }
        selectedAnimation = 0
        animationProgress = 0
        animationPlaying = !qaMode
        if !animationNames.isEmpty {
            play(clip: 0)
        } else {
            animationBarOpen = false
        }
    }

    private func resetAll() {
        recenterGeneration += 1
        environment = Self.environments[0]
        iblIntensity = 1
        showSkybox = false
        selectedModel = Self.bundledModels[0]
        Task { await loadBundled(selectedModel) }
    }

    // MARK: - Animation

    @MainActor
    private func play(clip index: Int) {
        guard let entity = loadedNode?.entity, entity.availableAnimations.indices.contains(index) else { return }
        entity.stopAllAnimations()
        let controller = entity.playAnimation(entity.availableAnimations[index].repeat(), transitionDuration: 0.2)
        if !animationPlaying { controller.pause() }
        playback = controller
        animationProgress = 0
    }

    @MainActor
    private func scrub(_ value: Float) {
        animationProgress = value
        guard let playback else { return }
        playback.time = Double(value) * playback.duration
    }

    // MARK: - Surprise me

    /// Picks a random downloadable CC-BY model via ``SketchfabService.search``
    /// and loads the downloaded USDZ from its local cache URL.
    @MainActor
    private func rollSurpriseModel() async {
        surpriseInFlight = true
        surpriseError = nil
        defer { surpriseInFlight = false }

        do {
            let queries = ["pbr", "modern", "scan"]
            var picked: (uid: String, name: String)?
            for query in queries {
                let results = try await SketchfabService.shared.search(query: query, downloadable: true, limit: 24)
                let viable = results.filter { $0.downloadable && (1..<200_000).contains($0.faceCount) }
                if let hit = viable.randomElement() {
                    picked = (hit.uid, hit.name)
                    break
                }
            }
            guard let pick = picked else {
                surfaceTransientError("No surprise model available right now — try again.")
                return
            }
            let downloaded = try await SketchfabService.shared.downloadModel(uid: pick.uid)
            let node = try await ModelNode.load(contentsOf: downloaded)
            install(node)
            streamedDisplayName = pick.name
        } catch {
            surfaceTransientError("Couldn't roll a model: \(error.localizedDescription)")
        }
    }

    @MainActor
    private func surfaceTransientError(_ message: String) {
        surpriseError = message
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if surpriseError == message {
                surpriseError = nil
            }
        }
    }
}

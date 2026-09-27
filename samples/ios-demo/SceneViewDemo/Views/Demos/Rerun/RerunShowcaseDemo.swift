#if os(iOS)
import SwiftUI

/// The Rerun AR Replay showcase (`sceneview://demo/ar-rerun`), the iOS twin of Android's
/// `ARRerunDemo`: a real room recorded with a phone, rebuilt in 3D and replayed — the camera's
/// path and photos, the floor and table with their photos, the room's coloured points and two
/// models on their anchors — then the same replay on a session the user records with Live AR.
/// Share exports whichever session is on screen to four open formats, made on the phone.
///
/// The recorded room is the very pack Android ships (`rerun/showcase/`), read from the same
/// files, so both apps replay the same session to the same figures.
struct RerunShowcaseDemo: View {
    enum Mode: Equatable { case scene, map, camera, live }

    /// QA states for scripted captures: `-rerunState replay-map`. Any state freezes the drift
    /// and the intro so a capture is deterministic, and parks the playhead at ``qaFraction``.
    enum QAState: String {
        case replay
        case replayPlay = "replay-play"
        case replayMap = "replay-map"
        case replayCamera = "replay-camera"
        case replayExport = "replay-export"
        case live
    }

    static let qaFraction: Float = 0.62
    static let qaStateKey = "rerunState"

    static let intro = "A real room, filmed with a phone and rebuilt in 3D: the camera's path and photos, the floor "
        + "and table, the room's points and two models placed on them. The path and points were "
        + "reconstructed from the video. Live AR records your own session, straight from ARKit."

    @State private var mode: Mode
    @State private var session: RerunReplaySession?
    @State private var showcase: RerunReplaySession?
    @State private var loadFailed = false
    @State private var recenterToken = 0
    @State private var exportPresented = false
    private let qa: QAState?

    init() {
        let state = UserDefaults.standard.string(forKey: Self.qaStateKey).flatMap(QAState.init(rawValue:))
        qa = state
        switch state {
        case .replayMap: _mode = State(initialValue: .map)
        case .replayCamera: _mode = State(initialValue: .camera)
        case .live: _mode = State(initialValue: .live)
        default: _mode = State(initialValue: .scene)
        }
    }

    private var drift: Bool { qa == nil }

    var body: some View {
        GeometryReader { proxy in
            DemoScaffold(
                "Rerun AR Replay",
                dock: dock,
                accent: DockItem(icon: "square.and.arrow.up", label: "Export and share this space", caption: "Share",
                                 enabled: session != nil && mode != .live) {
                    exportPresented = true
                },
                chromeMode: mode == .live ? .ar : .stage
            ) {
                stage(topInset: Self.topReserve(safeTop: proxy.safeAreaInsets.top))
            } accessory: {
                if let session, mode != .live {
                    RerunFilmstripCard(session: session, title: session.pack.isShowcase ? "Recorded AR session" : "Your AR session",
                                       caption: caption)
                        .transition(.opacity)
                }
            } controls: {
                controls
            }
        }
        .task { await loadShowcase() }
        .sheet(isPresented: $exportPresented) {
            if let session {
                RerunExportSheet(pack: session.pack)
            }
        }
    }

    /// The identity row's bottom edge, then a gap: where the HUD starts.
    private static func topReserve(safeTop: CGFloat) -> CGFloat {
        let slop = (SceneViewTokens.Layout.touchTarget - SceneViewTokens.Glass.iconButtonSize) / 2
        return safeTop + SceneViewTokens.Chrome.topGap - slop + SceneViewTokens.Layout.touchTarget
            + SceneViewTokens.Space.sm
    }

    private var caption: String {
        switch mode {
        case .map: "Top-down map of the room"
        case .camera: "What the camera saw"
        default: "Drag to orbit · pinch to zoom"
        }
    }

    // MARK: Stage

    @ViewBuilder
    private func stage(topInset: CGFloat) -> some View {
        ZStack(alignment: .top) {
            if mode == .live {
                RerunLiveCaptureView { capture in finishCapture(capture) }
            } else if let session {
                if mode == .camera {
                    RerunCameraView(session: session)
                } else {
                    RerunReplayStage(session: session, overhead: mode == .map, recenterToken: recenterToken, drift: drift)
                }
                VStack(alignment: .trailing, spacing: SceneViewTokens.Space.sm) {
                    RerunReplayHud(session: session)
                        .frame(maxWidth: RerunChromeMetrics.maxWidth)
                        .frame(maxWidth: .infinity)
                    if mode == .camera {
                        RerunPipCard(session: session, drift: drift) { select(.scene) }
                    } else {
                        RerunCameraCard(session: session) { select(.camera) }
                    }
                }
                .padding(.horizontal, SceneViewTokens.Chrome.margin)
                .padding(.top, topInset)
            } else if loadFailed {
                failure
            } else {
                RerunReplayLoading()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
        .animation(SceneViewTokens.Motion.expressive(SceneViewTokens.Motion.medium), value: mode)
    }

    private var failure: some View {
        ZStack {
            SceneViewTokens.Stage.background
            Text("The recorded session could not be read.")
                .font(SceneViewTokens.TypeScale.body)
                .foregroundStyle(SceneViewTokens.ARChrome.onScrimDim)
        }
    }

    // MARK: Dock

    private var dock: [DockItem] {
        [
            DockItem(icon: "rotate.3d", label: "3D view", caption: "3D", selected: mode == .scene) {
                if mode == .scene { recenterToken += 1 } else { select(.scene) }
            },
            DockItem(icon: "map", label: "Map view", caption: "Map", selected: mode == .map) { select(.map) },
            DockItem(icon: "film", label: "Camera frames", caption: "Camera", selected: mode == .camera) {
                select(.camera)
            },
            DockItem(icon: "video", label: "Record a live AR session", caption: "Live AR", selected: mode == .live) {
                select(.live)
            },
        ]
    }

    private func select(_ next: Mode) {
        guard next != mode else { return }
        mode = next
        if next != .live, session?.playing == false, qa == nil { session?.resume() }
    }

    // MARK: Settings

    @ViewBuilder
    private var controls: some View {
        Text(Self.intro)
            .font(SceneViewTokens.TypeScale.body)
            .foregroundStyle(.secondary)
        Label("Everything stays on your iPhone.", systemImage: "lock.fill")
            .font(SceneViewTokens.TypeScale.captionRegular)
            .foregroundStyle(.secondary)
        if let showcase, session !== showcase {
            Button("Replay the recorded room") {
                session = showcase
                mode = .scene
                showcase.playFromStart()
            }
            .accessibilityIdentifier("rerun-back-to-showcase")
        }
    }

    // MARK: Sessions

    private func loadShowcase() async {
        guard session == nil else { return }
        let pack: RerunPack
        do {
            pack = try await Task.detached(priority: .userInitiated) { try RerunPack.loadShowcase() }.value
        } catch {
            loadFailed = true
            return
        }
        let loaded = await RerunReplaySession.load(pack)
        showcase = loaded
        session = loaded
        start(loaded)
    }

    private func start(_ session: RerunReplaySession) {
        guard let qa, qa != .live else {
            session.playFromStart()
            return
        }
        session.scrub(to: session.duration * Self.qaFraction)
        switch qa {
        case .replayPlay: session.resume()
        case .replayExport: exportPresented = true
        default: break
        }
    }

    /// Stop in Live AR: the capture replaces the replay on screen, from its first frame.
    private func finishCapture(_ capture: RerunCapturePack) {
        Task {
            let pack: RerunPack
            do {
                pack = try await Task.detached(priority: .userInitiated) {
                    try RerunPack.load(manifest: capture.manifest, log: capture.log, media: capture.media,
                                       title: "My AR session", isShowcase: false)
                }.value
            } catch {
                return
            }
            let loaded = await RerunReplaySession.load(pack)
            session = loaded
            mode = .scene
            loaded.playFromStart()
        }
    }
}

// MARK: - Export sheet

/// The four open formats of the session on screen, written on the phone, each shared on its own
/// or all four together.
struct RerunExportSheet: View {
    let pack: RerunPack

    @State private var files: [RerunExportFormat: URL] = [:]
    @State private var failed: Set<RerunExportFormat> = []
    @State private var sizes: [RerunExportFormat: Int] = [:]

    private var ready: [URL] { RerunExportFormat.allCases.compactMap { files[$0] } }

    var body: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
            Text("Export your space — open formats, ready for your AI tools.")
                .font(SceneViewTokens.TypeScale.title)
                .tracking(SceneViewTokens.TypeScale.titleTracking)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: 0) {
                ForEach(RerunExportFormat.allCases) { format in
                    row(format)
                    if format != RerunExportFormat.allCases.last {
                        Divider().padding(.leading, SceneViewTokens.Layout.touchTarget + SceneViewTokens.Space.sm)
                    }
                }
            }
            ShareLink(items: ready) {
                Label(ready.count == RerunExportFormat.allCases.count ? "Share all four" : "Preparing files…",
                      systemImage: "square.and.arrow.up")
                    .font(SceneViewTokens.TypeScale.bodySemibold)
                    // `on-primary`: the system's white label on the dark theme's light
                    // primary fill measured 1.8:1.
                    .foregroundStyle(SceneViewTokens.HomeColor.onPrimary)
                    .frame(maxWidth: .infinity, minHeight: SceneViewTokens.Layout.touchTarget)
            }
            .buttonStyle(.borderedProminent)
            .tint(SceneViewTokens.HomeColor.primary)
            .buttonBorderShape(.capsule)
            .disabled(ready.count != RerunExportFormat.allCases.count)
            .accessibilityIdentifier("rerun-export-all")
            Label("Everything stays on your iPhone until you share it.", systemImage: "lock.fill")
                .font(SceneViewTokens.TypeScale.captionRegular)
                .foregroundStyle(.secondary)
        }
        .padding(SceneViewTokens.Space.lg)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(SceneViewTokens.Radius.xl)
        .accessibilityIdentifier("rerun-export-sheet")
        .task { await export() }
    }

    private func row(_ format: RerunExportFormat) -> some View {
        HStack(spacing: SceneViewTokens.Space.sm) {
            Image(systemName: format.icon)
                .font(SceneViewTokens.TypeScale.card)
                .foregroundStyle(.tint)
                .frame(width: SceneViewTokens.Layout.touchTarget, height: SceneViewTokens.Layout.touchTarget)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: SceneViewTokens.Space.xs) {
                    Text(format.title).font(SceneViewTokens.TypeScale.bodySemibold)
                    Text(".\(format.fileExtension)")
                        .font(SceneViewTokens.TypeScale.caption)
                        .foregroundStyle(.secondary)
                }
                Text(sizes[format].map { "\(format.detail) · \(ByteCountFormatter.string(fromByteCount: Int64($0), countStyle: .file))" }
                     ?? format.detail)
                    .font(SceneViewTokens.TypeScale.captionRegular)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Spacer(minLength: SceneViewTokens.Space.sm)
            if let url = files[format] {
                ShareLink(item: url) {
                    Image(systemName: "square.and.arrow.up")
                        .font(SceneViewTokens.TypeScale.card)
                        .frame(width: SceneViewTokens.Layout.touchTarget, height: SceneViewTokens.Layout.touchTarget)
                }
                .accessibilityLabel("Share the \(format.title.lowercased())")
                .accessibilityIdentifier("rerun-export-\(format.rawValue)")
            } else if failed.contains(format) {
                Image(systemName: "exclamationmark.triangle")
                    .foregroundStyle(SceneViewTokens.HomeColor.danger)
                    .frame(width: SceneViewTokens.Layout.touchTarget, height: SceneViewTokens.Layout.touchTarget)
                    .accessibilityLabel("Could not make this file")
            } else {
                ProgressView()
                    .frame(width: SceneViewTokens.Layout.touchTarget, height: SceneViewTokens.Layout.touchTarget)
            }
        }
        .padding(.vertical, SceneViewTokens.Space.xs)
    }

    /// Builds the scene once, then writes each format in turn off the main actor, so the rows
    /// fill in one by one.
    private func export() async {
        guard files.isEmpty else { return }
        let pack = pack
        let scene = await Task.detached(priority: .userInitiated) { RerunExportAdapter.scene(for: pack) }.value
        let directory = RerunExportAdapter.freshDirectory()
        for format in RerunExportFormat.allCases {
            let result = await Task.detached(priority: .userInitiated) {
                Result { try RerunExportAdapter.write(format, scene: scene, directory: directory) }
            }.value
            guard !Task.isCancelled else { return }
            switch result {
            case .success(let url):
                files[format] = url
                sizes[format] = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize
            case .failure:
                failed.insert(format)
            }
        }
    }
}
#endif

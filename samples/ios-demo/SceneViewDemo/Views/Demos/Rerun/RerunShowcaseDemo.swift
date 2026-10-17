#if os(iOS)
import SwiftUI
import UniformTypeIdentifiers

/// The Room Scan demo (`sceneview://demo/ar-rerun`), the iOS twin of Android's
/// `ARRerunDemo`: record a room with ARKit and replay it in 3D — the camera's path and photos,
/// the surfaces with their photos, the room's points and the models placed in it.
///
/// It opens on "Your sessions" (``RerunSessionsLanding``): Record your room, a sample session,
/// Open file, and every session kept on this iPhone. Stopping a recording saves it and opens
/// the very replay the sample plays, built from the user's own data. Share exports whichever
/// session is on screen to four open formats, made on the phone.
///
/// The sample is the very pack Android ships (`rerun/showcase/`), read from the same files, so
/// both apps replay it to the same figures.
struct RerunShowcaseDemo: View {
    enum Screen: Equatable { case landing, replay, record }
    enum Mode: Equatable { case scene, map, camera }

    /// QA states for scripted captures: `-rerunState replay-map`. A replay state freezes the
    /// drift and the intro so a capture is deterministic, and parks the playhead at
    /// ``qaFraction``. With `-rerunImport <path>` the file goes through the real import first;
    /// a replay state then opens the imported session instead of the sample.
    enum QAState: String {
        case landing
        case replay
        case replayPlay = "replay-play"
        case replayMap = "replay-map"
        case replayCamera = "replay-camera"
        case replayExport = "replay-export"
        case live
    }

    static let qaFraction: Float = 0.62
    static let qaStateKey = "rerunState"
    static let qaImportKey = "rerunImport"

    static let intro = "Record a room with your iPhone and SceneView rebuilds it in 3D: the camera's path and "
        + "photos, the surfaces, the room's points and the models you placed. Stop, and the replay "
        + "opens. Share exports it to .rrd, .glb, .usdz and .ply, made on your iPhone."

    @Environment(\.scenePhase) private var scenePhase
    @State private var screen: Screen
    @State private var mode: Mode
    @State private var session: RerunReplaySession?
    @State private var showcase: RerunReplaySession?
    @State private var loadFailed = false
    @State private var sessions: [RerunStoredSession] = []
    @State private var importing = false
    @State private var notice: String?
    @State private var recording = false
    @State private var importerPresented = false
    @State private var recenterToken = 0
    @State private var exportPresented = false
    /// The replay the latest open asked for; an older load that lands late is dropped.
    @State private var openToken = UUID()
    private let qa: QAState?
    /// `-rerunImport` runs once per launch, not on every visit.
    @MainActor private static var consumedQAImport = false
    private let store = RerunSessionStore.standard
    private let inbox = RerunInbox.shared

    init() {
        let state = UserDefaults.standard.string(forKey: Self.qaStateKey).flatMap(QAState.init(rawValue:))
        qa = state
        switch state {
        case nil, .landing: _screen = State(initialValue: .landing)
        case .live: _screen = State(initialValue: .record)
        default: _screen = State(initialValue: .replay)
        }
        switch state {
        case .replayMap: _mode = State(initialValue: .map)
        case .replayCamera: _mode = State(initialValue: .camera)
        default: _mode = State(initialValue: .scene)
        }
    }

    private var drift: Bool { qa == nil }

    var body: some View {
        GeometryReader { proxy in
            DemoScaffold(
                "Room Scan",
                dock: dock,
                accent: accent,
                chromeMode: screen == .record ? .ar : .themedStage
            ) {
                stage(topInset: Self.topReserve(safeTop: proxy.safeAreaInsets.top),
                      bottomInset: Self.bottomReserve(safeBottom: proxy.safeAreaInsets.bottom))
            } accessory: {
                if screen == .replay, let session {
                    RerunFilmstripCard(session: session, title: session.pack.isShowcase ? "Sample session" : session.pack.title,
                                       caption: caption)
                        .transition(.opacity)
                }
            } controls: {
                controls
            }
        }
        .task { await boot() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { session?.resumeIfSuspended() } else { session?.suspend() }
        }
        .onDisappear { session?.pause() }
        .onChange(of: inbox.pending) { _, pending in
            if pending != nil { Task { await importPending() } }
        }
        .fileImporter(isPresented: $importerPresented, allowedContentTypes: Self.openableTypes) { result in
            guard case .success(let url) = result else { return }
            guard let copy = RerunInbox.copyIn(url) else {
                notice = "\u{201C}\(url.lastPathComponent)\u{201D} could not be read."
                return
            }
            Task { await importFile(copy) }
        }
        .sheet(isPresented: $exportPresented) {
            if let session {
                RerunExportSheet(pack: session.pack)
            }
        }
    }

    /// What Open file offers: the app's scan files and Rerun recordings (`Info.plist`).
    static let openableTypes: [UTType] = [
        UTType(exportedAs: RerunScanFile.typeIdentifier),
        UTType(importedAs: "io.rerun.rrd"),
    ]

    /// The identity row's bottom edge, then a gap: where the HUD starts.
    private static func topReserve(safeTop: CGFloat) -> CGFloat {
        let slop = (SceneViewTokens.Layout.touchTarget - SceneViewTokens.Glass.iconButtonSize) / 2
        return safeTop + SceneViewTokens.Chrome.topGap - slop + SceneViewTokens.Layout.touchTarget
            + SceneViewTokens.Space.sm
    }

    /// The dock's top edge, then a gap: where the landing's list stops scrolling.
    private static func bottomReserve(safeBottom: CGFloat) -> CGFloat {
        SceneViewTokens.Chrome.dockBottom(safeArea: safeBottom) + SceneViewTokens.Layout.dockHeight
            + SceneViewTokens.Space.md
    }

    private var caption: String {
        switch mode {
        case .map: "Top-down map of the room"
        case .camera: "What the camera saw"
        case .scene: "Drag to orbit · pinch to zoom"
        }
    }

    // MARK: Stage

    @ViewBuilder
    private func stage(topInset: CGFloat, bottomInset: CGFloat) -> some View {
        ZStack(alignment: .top) {
            switch screen {
            case .landing:
                RerunSessionsLanding(
                    sessions: sessions, store: store, importing: importing, notice: notice,
                    topInset: topInset, bottomInset: bottomInset,
                    onRecord: { show(.record) },
                    onSample: { Task { await watchSample() } },
                    onOpenFile: { importerPresented = true },
                    onOpen: { stored in Task { await open(stored) } },
                    onDelete: delete
                )
            case .record:
                RerunLiveCaptureView(onRecordingChange: { recording = $0 }) { capture in
                    Task { await finishCapture(capture) }
                }
            case .replay:
                replay(topInset: topInset)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
        .animation(SceneViewTokens.Motion.expressive(SceneViewTokens.Motion.medium), value: mode)
    }

    @ViewBuilder
    private func replay(topInset: CGFloat) -> some View {
        if let session {
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

    private var failure: some View {
        ZStack {
            SceneViewTokens.RoomScan.background
            Text("This session could not be read.")
                .font(SceneViewTokens.TypeScale.body)
                .foregroundStyle(SceneViewTokens.RoomScan.secondaryText)
        }
    }

    // MARK: Dock

    private var sessionsItem: DockItem {
        DockItem(icon: "square.stack.3d.up", label: "Your sessions", caption: "Sessions") { show(.landing) }
    }

    private var dock: [DockItem] {
        switch screen {
        case .landing:
            return []
        case .record:
            // While recording, only Stop ends it: nothing in the dock can drop it by accident.
            return recording ? [] : [sessionsItem]
        case .replay:
            return [
                DockItem(icon: "rotate.3d", label: "3D view", caption: "3D", selected: mode == .scene) {
                    if mode == .scene { recenterToken += 1 } else { select(.scene) }
                },
                DockItem(icon: "map", label: "Map view", caption: "Map", selected: mode == .map) { select(.map) },
                DockItem(icon: "film", label: "Camera frames", caption: "Camera", selected: mode == .camera) {
                    select(.camera)
                },
                sessionsItem,
            ]
        }
    }

    private var accent: DockItem? {
        guard screen == .replay else { return nil }
        return DockItem(icon: "square.and.arrow.up", label: "Export and share this space", caption: "Share",
                        enabled: session != nil) {
            exportPresented = true
        }
    }

    private func select(_ next: Mode) {
        guard next != mode else { return }
        mode = next
        if session?.playing == false, qa == nil { session?.resume() }
    }

    private func show(_ next: Screen) {
        guard next != screen else { return }
        if next == .landing { reload() }
        notice = next == .landing ? notice : nil
        screen = next
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
    }

    // MARK: Sessions

    private func boot() async {
        reload()
        if inbox.pending != nil { await importPending() }
        if let path = UserDefaults.standard.string(forKey: Self.qaImportKey), qa != nil, !Self.consumedQAImport {
            Self.consumedQAImport = true
            if let copy = RerunInbox.copyIn(URL(fileURLWithPath: path)) {
                await importFile(copy, openAfter: qa != .landing)
                return
            }
        }
        if screen == .replay, session == nil { await watchSample() }
    }

    private func reload() {
        sessions = store.list()
    }

    private func delete(_ stored: RerunStoredSession) {
        try? store.delete(stored.id)
        reload()
    }

    private func importPending() async {
        guard let url = inbox.take() else { return }
        await importFile(url)
    }

    /// Reads a scan file or `.rrd` in, keeps it as a session and opens its replay. The file is
    /// the app's own copy; it goes once read.
    private func importFile(_ url: URL, openAfter: Bool = true) async {
        screen = .landing
        notice = nil
        importing = true
        let store = store
        let result = await Task.detached(priority: .userInitiated) {
            Result { try store.importFile(at: url) }
        }.value
        importing = false
        try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
        reload()
        switch result {
        case .success(let stored):
            if openAfter { await open(stored) }
        case .failure(let error):
            notice = Self.message(for: error, file: url.lastPathComponent)
        }
    }

    static func message(for error: Error, file: String) -> String {
        switch error as? RerunSessionStore.Failure {
        case .unsupportedFile: "\u{201C}\(file)\u{201D} is not a SceneView scan or a Rerun recording."
        case .empty: "\u{201C}\(file)\u{201D} holds no camera path, points or surfaces to replay."
        default: "\u{201C}\(file)\u{201D} could not be read. SceneView opens the scans and .rrd files it makes."
        }
    }

    private func watchSample() async {
        let token = UUID()
        openToken = token
        mode = .scene
        loadFailed = false
        screen = .replay
        if let showcase {
            session = showcase
            start(showcase)
            return
        }
        session = nil
        let pack: RerunPack
        do {
            pack = try await Task.detached(priority: .userInitiated) { try RerunPack.loadShowcase() }.value
        } catch {
            loadFailed = true
            return
        }
        let loaded = await RerunReplaySession.load(pack)
        showcase = loaded
        guard openToken == token else { return }
        session = loaded
        start(loaded)
    }

    /// The replay of a kept session — the same stage, HUD, filmstrip and export as the sample.
    private func open(_ stored: RerunStoredSession) async {
        let token = UUID()
        openToken = token
        mode = .scene
        loadFailed = false
        session = nil
        screen = .replay
        let store = store
        let pack = await Task.detached(priority: .userInitiated) { () -> RerunPack? in
            guard let capture = store.capture(for: stored.id) else { return nil }
            return try? RerunPack.load(manifest: capture.manifest, log: capture.log, media: capture.media,
                                       title: stored.title)
        }.value
        guard openToken == token else { return }
        guard let pack else {
            loadFailed = true
            return
        }
        let loaded = await RerunReplaySession.load(pack)
        guard openToken == token else { return }
        session = loaded
        start(loaded)
    }

    private func start(_ session: RerunReplaySession) {
        guard let qa, qa != .live, qa != .landing else {
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

    /// Stop in Record: the capture is kept as a session, then its replay opens.
    private func finishCapture(_ capture: RerunCapturePack) async {
        let store = store
        let title = RerunSessionStore.recordingTitle(at: Date())
        let result = await Task.detached(priority: .userInitiated) {
            Result { try store.save(capture, title: title, source: .recorded) }
        }.value
        reload()
        switch result {
        case .success(let stored):
            await open(stored)
        case .failure(let error):
            notice = Self.message(for: error, file: title)
            screen = .landing
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

#if os(iOS)
import SwiftUI
import UniformTypeIdentifiers

/// The Room Scan demo (`sceneview://demo/ar-rerun`), the iOS twin of Android's `ARRerunDemo`:
/// record a room with ARKit and replay it in 3D — the camera's path and photos, the surfaces
/// with their photos, the room's coloured points and the models placed in it.
///
/// It opens on "Your sessions" (``RerunSessionsLanding``): Record your room, a sample session,
/// Open file, and every session kept on this iPhone. Stopping a recording saves it and opens
/// the very replay the sample plays, built from the user's own data. Share exports whichever
/// session is on screen to four open formats, made on the phone.
///
/// The sample is the very pack Android ships (`rerun/showcase/`), read from the same files, so
/// both apps replay it to the same figures.
///
/// The scene keeps the screen (#4379). The replay floats one glass timeline bar over the room —
/// above the dock on a tall window, in the title row on a wide one — and a tap on the room puts
/// title, timeline and dock away. A scan in progress says one line in the title row. Everything
/// read once (the layers and their figures, the room's size, a scan's counts) is a row of the
/// settings sheet.
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
        /// The replay as a tap on the room leaves it: no chrome at all.
        case replayImmersive = "replay-immersive"
        case live
        #if DEBUG
        /// The Record screen as a scan in progress leaves it (``RerunLiveQAScene``): steady,
        /// a limit in sight, a limit reached. Debug builds only.
        case record
        case recordNear = "record-near"
        case recordFull = "record-full"
        case recordTime = "record-time"
        #endif
    }

    static let qaFraction: Float = 0.62
    static let qaStateKey = "rerunState"
    static let qaImportKey = "rerunImport"

    static let intro = "Record a room with your iPhone and SceneView rebuilds it in 3D: the camera's path and "
        + "photos, the surfaces, the room's points and the models you placed. Stop, and the replay "
        + "opens. Share exports it to .rrd, .glb, .usdz and .ply, made on your iPhone."

    @State private var screen: Screen
    @State private var mode: Mode
    @State private var session: RerunReplaySession?
    @State private var showcase: RerunReplaySession?
    @State private var loadFailed = false
    @State private var sessions: [RerunStoredSession] = []
    @State private var importing = false
    @State private var notice: String?
    /// The scan on the Record screen: its phase and the recorder's counts.
    @State private var live = RerunLiveStatus()
    /// A tap on the replay put the chrome away.
    @State private var chromeHidden = false
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
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    init() {
        let state = UserDefaults.standard.string(forKey: Self.qaStateKey).flatMap(QAState.init(rawValue:))
        qa = state
        switch state {
        case nil, .landing: _screen = State(initialValue: .landing)
        case .live: _screen = State(initialValue: .record)
        default:
            #if DEBUG
            let scanning = state.flatMap { RerunLiveQAScene(rawValue: $0.rawValue) } != nil
            #else
            let scanning = false
            #endif
            _screen = State(initialValue: scanning ? .record : .replay)
        }
        switch state {
        case .replayMap: _mode = State(initialValue: .map)
        case .replayCamera: _mode = State(initialValue: .camera)
        default: _mode = State(initialValue: .scene)
        }
    }

    private var drift: Bool { qa == nil }
    private var recording: Bool { live.phase != .idle }
    /// The session on screen, once its replay is up.
    private var replaying: RerunReplaySession? { screen == .replay ? session : nil }
    /// Nothing but the room: the replay is up and a tap put the chrome away.
    private var immersive: Bool { chromeHidden && replaying != nil }
    /// A window too short to stack the timeline above the dock (a phone on its side).
    private var wide: Bool { verticalSizeClass == .compact }

    var body: some View {
        GeometryReader { proxy in
            let top = Self.chromeTop(safeTop: proxy.safeAreaInsets.top)
            let bottom = Self.chromeBottom(safeBottom: proxy.safeAreaInsets.bottom)
            DemoScaffold(
                "Room Scan",
                dock: dock,
                accent: accent,
                chromeMode: screen == .record ? .arGlass : .stage,
                chromeHidden: immersive
            ) {
                stage(top: top, bottom: bottom,
                      side: max(proxy.safeAreaInsets.leading, proxy.safeAreaInsets.trailing))
            } accessory: {
                if !wide, let replaying {
                    RerunTimelineBar(session: replaying)
                        .transition(.opacity)
                }
            } status: {
                status
            } controls: {
                controls
            }
        }
        .statusBarHidden(immersive)
        .persistentSystemOverlays(immersive ? .hidden : .automatic)
        .task { await boot() }
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

    /// The title row's bottom edge, from the top of the window.
    private static func chromeTop(safeTop: CGFloat) -> CGFloat {
        let slop = (SceneViewTokens.Layout.touchTarget - SceneViewTokens.Glass.iconButtonSize) / 2
        return safeTop + SceneViewTokens.Chrome.topGap - slop + SceneViewTokens.Layout.touchTarget
    }

    /// The dock's top edge, from the bottom of the window.
    private static func chromeBottom(safeBottom: CGFloat) -> CGFloat {
        SceneViewTokens.Chrome.dockBottom(safeArea: safeBottom) + SceneViewTokens.Layout.dockHeight
    }

    /// What the timeline adds above the dock, where it stands there.
    private var timelineRise: CGFloat {
        wide ? 0 : SceneViewTokens.Chrome.clusterGap + RerunChromeMetrics.barHeight
    }

    /// The title row's trailing end on a wide window: a scan in progress, or the timeline.
    /// Upright, the scan's line stands under the row, beside its 3D card.
    @ViewBuilder
    private var status: some View {
        if screen == .record, recording, wide {
            RerunCaptureStatusLine(status: live)
        } else if wide, let replaying {
            RerunTimelineBar(session: replaying)
                .frame(width: SceneViewTokens.DebugView.compactCardWidth)
        }
    }

    // MARK: Stage

    /// The Record screen, inside what the title row, the dock and the window's sides leave.
    private func capture(top: CGFloat, bottom: CGFloat, side: CGFloat) -> some View {
        var view = RerunLiveCaptureView(topInset: top + SceneViewTokens.Space.sm,
                                        bottomInset: bottom + SceneViewTokens.Space.md,
                                        sideInset: side,
                                        onStatusChange: { live = $0 }) { capture in
            Task { await finishCapture(capture) }
        }
        #if DEBUG
        view.qa = qa.flatMap { RerunLiveQAScene(rawValue: $0.rawValue) }
        #endif
        return view
    }

    /// `top` and `bottom` are what the title row and the dock take of the window, `side` what
    /// it keeps clear on its sides.
    @ViewBuilder
    private func stage(top: CGFloat, bottom: CGFloat, side: CGFloat = 0) -> some View {
        ZStack(alignment: .top) {
            switch screen {
            case .landing:
                RerunSessionsLanding(
                    sessions: sessions, store: store, importing: importing, notice: notice,
                    topInset: top + SceneViewTokens.Space.sm, bottomInset: bottom + SceneViewTokens.Space.md,
                    onRecord: { show(.record) },
                    onSample: { Task { await watchSample() } },
                    onOpenFile: { importerPresented = true },
                    onOpen: { stored in Task { await open(stored) } },
                    onDelete: delete
                )
            case .record:
                capture(top: top, bottom: bottom, side: side)
            case .replay:
                replay(top: top, bottom: bottom + timelineRise)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
        .animation(SceneViewTokens.Motion.expressive(SceneViewTokens.Motion.medium), value: mode)
    }

    /// The room, or the camera's frames, edge to edge. The room is framed in what the chrome
    /// leaves, and in the whole view once a tap puts that chrome away.
    @ViewBuilder
    private func replay(top: CGFloat, bottom: CGFloat) -> some View {
        if let session {
            if mode == .camera {
                RerunCameraView(session: session, chromeHidden: chromeHidden) { chromeHidden.toggle() }
            } else {
                RerunReplayStage(session: session, overhead: mode == .map, recenterToken: recenterToken, drift: drift,
                                 chromeTop: top, chromeBottom: bottom, chromeHidden: chromeHidden) {
                    chromeHidden.toggle()
                }
            }
        } else if loadFailed {
            failure
        } else {
            RerunReplayLoading()
        }
    }

    private var failure: some View {
        ZStack {
            SceneViewTokens.Stage.background
            Text("This session could not be read.")
                .font(SceneViewTokens.TypeScale.body)
                .foregroundStyle(SceneViewTokens.ARChrome.onScrimDim)
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
        chromeHidden = false
        screen = next
    }

    // MARK: Settings

    /// The sheet reads out what the screen no longer wears: the replay's layers and the room's
    /// size, a scan's counts; anywhere else, what the demo does.
    @ViewBuilder
    private var controls: some View {
        if let replaying {
            RerunReplaySettings(session: replaying)
        } else if screen == .record, recording {
            RerunCaptureSettings(status: live)
        } else {
            Text(Self.intro)
                .font(SceneViewTokens.TypeScale.body)
                .foregroundStyle(.secondary)
        }
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
        chromeHidden = false
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

    /// The replay of a kept session — the same stage, timeline, sheet and export as the sample.
    private func open(_ stored: RerunStoredSession) async {
        let token = UUID()
        openToken = token
        mode = .scene
        chromeHidden = false
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
        case .replayMap: mode = .map
        case .replayCamera: mode = .camera
        case .replayExport: exportPresented = true
        case .replayImmersive: chromeHidden = true
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

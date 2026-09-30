import SwiftUI

#if canImport(AppKit)
import AppKit
/// Maps UIColor to NSColor on macOS so code compiles cross-platform.
typealias UIColor = NSColor

extension NSColor {
    /// iOS systemGray2 equivalent on macOS.
    static var systemGray2: NSColor { NSColor.systemGray.withAlphaComponent(0.8) }
    /// iOS systemGray3 equivalent on macOS.
    static var systemGray3: NSColor { NSColor.systemGray.withAlphaComponent(0.6) }
}
#endif

/// SceneView — Explore, visualize, and interact with 3D models.
///
/// Browse a curated gallery of 3D models, view them in augmented reality,
/// save favorites, and share screenshots with friends.
@main
struct SceneViewDemoApp: App {
    /// Demo id parsed from a deep-link URL (`sceneview://demo/<id>` or, in the
    /// future once Universal Links ship, `https://sceneview.github.io/open?demo=<id>`).
    /// Reset to `nil` after presentation so a config change doesn't replay it.
    @State private var pendingDeepLinkDemo: String?

    /// A 3D file handed over by Files, Mail, Messages or any share sheet — the
    /// `CFBundleDocumentTypes` entries in `Info.plist`. Presented full screen by
    /// `OpenedFileViewer`; reset to `nil` on dismissal so a config change does not
    /// replay it.
    @State private var openedFile: OpenedDocument?

    /// Wraps a file URL so SwiftUI's `.fullScreenCover(item:)` accepts it — the same
    /// shape as `ContentView.DemoLink`, for the same reason (`URL` is not
    /// `Identifiable`, and retro-conforming a Foundation type to make it so would leak
    /// out of this file).
    struct OpenedDocument: Identifiable {
        let url: URL
        var id: String { url.absoluteString }
    }

    /// A file path pre-seeded from `-open_file <path>`, the deterministic twin of
    /// opening a document from the share sheet.
    ///
    /// The screenshot pipeline needs the "Open with" screen without going through
    /// SpringBoard's "Open in …?" confirmation, exactly as `-demo <id>` exists so a
    /// demo can be captured without `simctl openurl`'s dialog. Ignored when the path
    /// does not exist, so a stale argument cannot wedge a normal launch.
    private static let launchArgOpenFile: OpenedDocument? = {
        let args = CommandLine.arguments
        guard let index = args.firstIndex(of: "-open_file"), index + 1 < args.count else {
            return nil
        }
        let path = args[index + 1]
        guard FileManager.default.fileExists(atPath: path) else { return nil }
        return OpenedDocument(url: URL(fileURLWithPath: path))
    }()

    /// Demo id pre-seeded from a launch argument (`-demo <id>`), used by the
    /// reproducible App Store screenshot capture pipeline. Launching with a
    /// `-demo` argument routes straight to the demo on first frame, with no
    /// SpringBoard "Open in …?" confirmation dialog that `simctl openurl`
    /// otherwise raises — see `samples/ios-demo/appstore-screenshots/README.md`
    /// and `capture-appstore-screenshots.sh`. Unknown / absent ids are ignored
    /// and the app launches normally.
    private static let launchArgDemo: String? = {
        let args = CommandLine.arguments
        guard let idx = args.firstIndex(of: "-demo"), idx + 1 < args.count else { return nil }
        let id = args[idx + 1]
        // Propagate `-qa_mode 1` launch arg so the screenshot harness can
        // freeze auto-rotation — mirrors the `?qa_mode=1` deep-link param.
        if let qIdx = args.firstIndex(of: "-qa_mode"),
           qIdx + 1 < args.count, args[qIdx + 1] == "1" {
            UserDefaults.standard.set(true, forKey: DeepLinkRouter.qaModeDefaultsKey)
        }
        // Propagate `-camera_distance <float>` so the App Store screenshot
        // pipeline can frame the hero demo tighter than the interactive
        // auto-fit default — mirrors Android's `camera_distance` intent
        // extra (#2652) and its dual-ingress precedence (extra beats deep
        // link). Unlike the Android Bundle extra, `CommandLine.arguments`
        // has no typed-value channel — every launch arg, from `xcrun simctl
        // launch` or Xcode's own scheme arguments, arrives as a `String` —
        // so there is no `Number` case to mirror here, just `Float.init?`.
        // [DeepLinkRouter.validateCameraDistance] applies the identical
        // clamp as Android either way: absent, unparseable, non-finite, or
        // out-of-range all leave the sentinel `0` (== "no override"). #2785.
        if let dIdx = args.firstIndex(of: "-camera_distance"), dIdx + 1 < args.count,
           let distance = DeepLinkRouter.validateCameraDistance(Float(args[dIdx + 1])) {
            UserDefaults.standard.set(Double(distance), forKey: DeepLinkRouter.cameraDistanceDefaultsKey)
        }
        return DemoDeepLinkRegistry.allowedIds.contains(id) ? id : nil
    }()

    /// App Store update checker — queried on every `.active` ScenePhase
    /// transition. The published state drives the `UpdateToast` that
    /// `ContentView` puts above the tab bar. See [AppStoreUpdater] for the
    /// throttle/snooze rules; `-update_qa available` forces it in DEBUG.
    @StateObject private var updater = AppStoreUpdater(forcedVersion: AppStoreUpdater.launchArgForcedVersion)

    #if os(iOS)
    /// Receives iOS's wake-up for finished HD pack transfers.
    @UIApplicationDelegateAdaptor(HDPackAppDelegate.self) private var hdPackDelegate
    #endif

    @Environment(\.scenePhase) private var scenePhase

    /// A document handed to the app. A SceneView scan or a Rerun recording (`.svscan`,
    /// `.rrd`) opens in the Rerun demo, which keeps it as one of "Your sessions"; any other
    /// file opens in the 3D viewer.
    private func open(_ url: URL) {
        #if os(iOS)
        if RerunInbox.handles(url) {
            if RerunInbox.shared.accept(url) { pendingDeepLinkDemo = "ar-rerun" }
            return
        }
        #endif
        openedFile = OpenedDocument(url: url)
    }

    var body: some SwiftUI.Scene {
        WindowGroup {
            ContentView(
                pendingDeepLinkDemo: $pendingDeepLinkDemo,
                launchArgDemo: Self.launchArgDemo
            )
                .environmentObject(updater)
                #if os(iOS)
                .fullScreenCover(item: $openedFile) { document in
                    OpenedFileViewer(url: document.url)
                }
                #else
                .sheet(item: $openedFile) { document in
                    OpenedFileViewer(url: document.url)
                }
                #endif
                .task {
                    #if os(iOS)
                    TmpCapture.start() // TEMP — never committed
                    #endif
                    // Prune stale HD files, re-attach to running transfers and
                    // prefetch the pack on an unmetered network.
                    HDPackStore.shared.bootstrap()
                    if openedFile == nil, let document = Self.launchArgOpenFile {
                        open(document.url)
                    }
                }
                .onOpenURL { url in
                    // A file URL is a document the system handed us through
                    // `CFBundleDocumentTypes`, not a deep link — and it is checked
                    // first, because `DeepLinkRouter` would otherwise see a `file`
                    // scheme it has no business parsing.
                    if url.isFileURL {
                        open(url)
                    } else if let id = DeepLinkRouter.parse(url, allowedDemos: DemoDeepLinkRegistry.allowedIds) {
                        pendingDeepLinkDemo = id
                    } else if let candidate = DeepLinkRouter.extractCandidate(url) {
                        // A well-formed `sceneview://demo/<id>` (or the
                        // Universal Link) whose id is not in the registry.
                        // Surface it so `DemoDeepLinkRegistry.destination(for:)`
                        // shows the "not available" placeholder — never a
                        // silent no-op (the registry doc-comment's guarantee).
                        // Malformed URLs (wrong scheme/host) yield `nil` here
                        // and are correctly ignored.
                        pendingDeepLinkDemo = candidate
                    }
                }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active {
                        Task { await updater.checkForUpdate() }
                    }
                }
        }
        #if os(macOS)
        .defaultSize(width: 1200, height: 800)
        #endif
    }
}

struct ContentView: View {
    @Binding var pendingDeepLinkDemo: String?

    /// Demo id supplied via the `-demo <id>` launch argument (screenshot
    /// pipeline). Presented once, on the first `.task`, then never replayed.
    let launchArgDemo: String?

    @State private var selectedTab = 0

    /// Wraps a demo id so SwiftUI's `.fullScreenCover(item:)` accepts it.
    private struct DemoLink: Identifiable {
        let id: String
    }
    @State private var presentedDemo: DemoLink?

    /// Guards the one-shot launch-argument presentation so a view refresh
    /// doesn't re-present the demo.
    @State private var didConsumeLaunchArg = false

    var body: some View {
        // Showcase · AR View · About — the same three destinations as the
        // Android bottom bar. The online gallery (`ExploreTab`) lives behind
        // the Showcase grid's "Browse online models" card.
        // `Tab(value:)` (iOS 18) — the values are the old `.tag`s, so the
        // deep-link and launch-argument routing below keep selecting `0`.
        TabView(selection: $selectedTab) {
            Tab("Showcase", systemImage: "square.grid.2x2.fill", value: 0) {
                // `isActive` gates the home hero's live 3D stage: only the visible
                // tab, with no demo presented over it, may run a scene.
                ShowcaseTab(isActive: selectedTab == 0 && presentedDemo == nil)
                    .accessibilityLabel("Showcase")
                    .updateToast()
            }

            #if os(iOS)
            Tab("AR View", systemImage: "arkit", value: 1) {
                ARTab()
                    .accessibilityLabel("Augmented Reality Viewer")
            }
            #endif

            Tab("About", systemImage: "info.circle.fill", value: 2) {
                AboutTab()
                    .accessibilityLabel("About This App")
                    .updateToast()
            }
            // Not on the AR View tab: the bottom of an AR screen holds its live
            // controls — the Android snackbar steps aside there too.
        }
        .tabBarMinimizesOnScrollDown()
        .tint(SceneViewTheme.primary)
        .task {
            // One-shot: route to the launch-argument demo on first frame so
            // the App Store screenshot pipeline lands directly on a rendered
            // scene — no SpringBoard confirmation dialog.
            guard !didConsumeLaunchArg, let id = launchArgDemo else { return }
            didConsumeLaunchArg = true
            selectedTab = 0
            presentedDemo = DemoLink(id: id)
        }
        .onChange(of: pendingDeepLinkDemo) { _, newId in
            guard let id = newId else { return }
            // Switch to the Samples tab so the deep-link surface feels
            // contextual; then present the demo above it as a modal so we
            // don't have to thread navigation through SamplesTab.
            selectedTab = 0
            presentedDemo = DemoLink(id: id)
            pendingDeepLinkDemo = nil
        }
        // Same host as a catalogue launch (`ShowcaseTab`'s `DemoCover`), so a
        // deep-linked demo always has a Close control and the edge-swipe
        // dismissal — see `DemoDeepLinkRegistry.cover(for:onClose:)`.
        #if os(iOS)
        .fullScreenCover(item: $presentedDemo) { link in
            DemoDeepLinkRegistry.cover(for: link.id) { presentedDemo = nil }
        }
        #elseif os(macOS)
        .sheet(item: $presentedDemo) { link in
            DemoDeepLinkRegistry.cover(for: link.id) { presentedDemo = nil }
                // Same floor as `ShowcaseTab`: a macOS sheet has no size of its own.
                .frame(minWidth: 960, minHeight: 640)
        }
        #endif
    }
}

private extension View {
    /// iOS 26+: the Liquid Glass tab bar shrinks to its selected item while the
    /// user scrolls down a tab's content and comes back on scroll up.
    @ViewBuilder
    func tabBarMinimizesOnScrollDown() -> some View {
        #if os(iOS)
        if #available(iOS 26, *) {
            self.tabBarMinimizeBehavior(.onScrollDown)
        } else {
            self
        }
        #else
        self
        #endif
    }
}


#if os(iOS)
import RealityKit
// TEMP device capture hook — never committed.
@MainActor enum TmpCapture {
    static func start() {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-tmpcapture"), i + 1 < args.count else { return }
        let marks = args[i + 1].split(separator: ",").compactMap { Double($0) }
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("tmpcap")
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        print("TMPCAP start \(marks)")
        for m in marks {
            DispatchQueue.main.asyncAfter(deadline: .now() + m) {
                MainActor.assumeIsolated { capture(label: String(format: "%02ds", Int(m)), dir: dir) }
            }
        }
    }

    private static func findARView(_ v: UIView) -> ARView? {
        if let a = v as? ARView { return a }
        for s in v.subviews { if let a = findARView(s) { return a } }
        return nil
    }

    static func capture(label: String, dir: URL) {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
              let win = scene.windows.first(where: { $0.isKeyWindow }) ?? scene.windows.first else {
            print("TMPCAP no window"); return
        }
        let renderer = UIGraphicsImageRenderer(bounds: win.bounds)
        let ui = renderer.image { _ in _ = win.drawHierarchy(in: win.bounds, afterScreenUpdates: false) }
        try? ui.pngData()?.write(to: dir.appendingPathComponent("\(label)-window.png"))
        guard let ar = findARView(win) else { print("TMPCAP \(label) no ARView"); return }
        let frame = ar.convert(ar.bounds, to: win)
        ar.snapshot(saveToHDR: false) { snap in
            guard let snap else { print("TMPCAP \(label) snapshot nil"); return }
            try? snap.pngData()?.write(to: dir.appendingPathComponent("\(label)-arview.png"))
            let comp = renderer.image { _ in
                snap.draw(in: frame)
                ui.draw(in: win.bounds)
            }
            try? comp.pngData()?.write(to: dir.appendingPathComponent("\(label)-comp.png"))
            print("TMPCAP \(label) saved")
        }
    }
}
#endif

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
        #if DEBUG
        .onAppear { ABFHarness.startIfRequested() }
        .onReceive(NotificationCenter.default.publisher(for: .abfTab)) { selectedTab = ($0.object as? Int) ?? 0 }
        .onReceive(NotificationCenter.default.publisher(for: .abfDeepLink)) { note in
            let raw = (note.object as? String) ?? ""
            if raw.hasPrefix("stay-") { presentedDemo = DemoLink(id: String(raw.dropFirst(5))); return }
            selectedTab = 0
            presentedDemo = DemoLink(id: raw)
        }
        .onReceive(NotificationCenter.default.publisher(for: .abfClose)) { _ in presentedDemo = nil }
        #endif
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

// ABF-HARNESS — temporary device-QA driver, NEVER COMMIT.
#if DEBUG && os(iOS)
import ARKit
import RealityKit
import UIKit

extension Notification.Name {
    static let abfTab = Notification.Name("abf.tab")
    static let abfHome = Notification.Name("abf.home")
    static let abfDeepLink = Notification.Name("abf.deeplink")
    static let abfClose = Notification.Name("abf.close")
}

@MainActor
enum ABFHarness {
    static var started = false
    static var shot = 0
    static let seen = NSHashTable<ARView>.weakObjects()
    static let dir: URL = {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("abf")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()

    static func log(_ s: String) {
        let line = String(format: "%.2f ", ProcessInfo.processInfo.systemUptime) + s + "\n"
        print("[ABF] " + s)
        let url = dir.appendingPathComponent("log.txt")
        if let h = try? FileHandle(forWritingTo: url) {
            h.seekToEndOfFile(); h.write(line.data(using: .utf8)!); try? h.close()
        } else {
            try? line.data(using: .utf8)!.write(to: url)
        }
    }

    static func startIfRequested() {
        guard !started else { return }
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "-abf_script"), i + 1 < args.count else { return }
        started = true
        UIApplication.shared.isIdleTimerDisabled = true
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let steps = args[i + 1].split(separator: ";").map(String.init)
        log("SCRIPT start \(steps.count) steps")
        Task { @MainActor in
            for step in steps { await run(step) }
            log("SCRIPT end")
        }
    }

    static func run(_ step: String) async {
        let parts = step.split(separator: ":", maxSplits: 1).map(String.init)
        let cmd = parts[0]
        let arg = parts.count > 1 ? parts[1] : ""
        log("STEP \(step)")
        switch cmd {
        case "wait": try? await Task.sleep(for: .seconds(Double(arg) ?? 1))
        case "tab": NotificationCenter.default.post(name: .abfTab, object: Int(arg) ?? 0)
        case "home": NotificationCenter.default.post(name: .abfHome, object: arg)
        case "deeplink": NotificationCenter.default.post(name: .abfDeepLink, object: arg)
        case "close": NotificationCenter.default.post(name: .abfClose, object: nil)
        case "probe": probe(arg)
        default: log("unknown step \(step)")
        }
    }

    static func window() -> UIWindow? {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows).first { $0.isKeyWindow }
    }

    static func arViews(in view: UIView, into out: inout [ARView]) {
        if let ar = view as? ARView { out.append(ar) }
        for sub in view.subviews { arViews(in: sub, into: &out) }
    }

    static func metalLayers(in layer: CALayer, into out: inout [CAMetalLayer]) {
        if let m = layer as? CAMetalLayer { out.append(m) }
        for sub in layer.sublayers ?? [] { metalLayers(in: sub, into: &out) }
    }

    static func probe(_ name: String) {
        guard let window = window() else { log("PROBE \(name) no window"); return }
        var views: [ARView] = []
        arViews(in: window, into: &views)
        for v in views { seen.add(v) }
        let alive = seen.allObjects.map { "\(Unmanaged.passUnretained($0).toOpaque())[\($0.cameraMode == .ar ? "ar" : "nonAR"),win=\($0.window != nil)]" }.joined(separator: " ")
        log("  ALIVE \(seen.allObjects.count): \(alive)")
        for v in views {
            var layers: [CAMetalLayer] = []
            metalLayers(in: v.layer, into: &layers)
            let drawables = layers.map { "\(Int($0.drawableSize.width))x\(Int($0.drawableSize.height))" }.joined(separator: ",")
            let mode = v.cameraMode == .ar ? "ar" : "nonAR"
            let frameAge: String
            if let f = v.session.currentFrame {
                frameAge = String(format: "%.2f", ProcessInfo.processInfo.systemUptime - f.timestamp)
            } else {
                frameAge = "nil"
            }
            log("  ARVIEW \(Unmanaged.passUnretained(v).toOpaque()) \(type(of: v)) mode=\(mode) size=\(v.frame.size) inWindow=\(v.window != nil) drawable=\(drawables) frameAge=\(frameAge) bg=\(v.environment.background) anchors=\(v.scene.anchors.count) opts=\(v.renderOptions.rawValue)")
            func dump(_ view: UIView, _ depth: Int) {
                let l = view.layer
                var extra = ""
                if let m = l as? CAMetalLayer {
                    extra = " METAL drawable=\(m.drawableSize) fmt=\(m.pixelFormat.rawValue) fbOnly=\(m.framebufferOnly) pwt=\(m.presentsWithTransaction) contents=\(l.contents != nil) opaque=\(l.isOpaque)"
                }
                log("    " + String(repeating: " ", count: depth) + "\(type(of: view)) frame=\(view.frame) hidden=\(view.isHidden) alpha=\(view.alpha) layer=\(type(of: l))\(extra) sublayers=\(l.sublayers?.count ?? 0)")
                if depth < 3 { for s in view.subviews { dump(s, depth + 1) } }
            }
            dump(v, 0)
        }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: false)
        }
        var dark = 0, total = 0, sum = 0
        if let cg = image.cgImage, let data = cg.dataProvider?.data, let ptr = CFDataGetBytePtr(data) {
            let w = cg.width, h = cg.height, bpr = cg.bytesPerRow, bpp = cg.bitsPerPixel / 8
            for y in stride(from: h / 5, to: h * 4 / 5, by: 4) {
                for x in stride(from: w / 10, to: w * 9 / 10, by: 4) {
                    let o = y * bpr + x * bpp
                    let l = (Int(ptr[o]) * 299 + Int(ptr[o + 1]) * 587 + Int(ptr[o + 2]) * 114) / 1000
                    sum += l
                    total += 1
                    if l < 24 { dark += 1 }
                }
            }
        }
        let frac = total > 0 ? Double(dark) / Double(total) : -1
        let mean = total > 0 ? sum / total : -1
        shot += 1
        let file = String(format: "%03d-%@.png", shot, name)
        try? image.pngData()?.write(to: dir.appendingPathComponent(file))
        log("PROBE \(name) arViews=\(views.count) meanLuma=\(mean) darkFrac=\(String(format: "%.2f", frac)) verdict=\(frac > 0.85 ? "BLACK" : "FEED") shot=\(file)")
    }
}
#endif

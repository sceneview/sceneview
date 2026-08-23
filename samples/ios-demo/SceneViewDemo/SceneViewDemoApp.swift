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
        return DemoDeepLinkRegistry.allowedIds.contains(id) ? id : nil
    }()

    /// App Store update checker — queried on every `.active` ScenePhase
    /// transition. The published state drives `UpdateBanner` overlaid on
    /// `ContentView`. See [AppStoreUpdater] for the throttle/snooze rules.
    @StateObject private var updater = AppStoreUpdater()

    @Environment(\.scenePhase) private var scenePhase

    var body: some SwiftUI.Scene {
        WindowGroup {
            ContentView(
                pendingDeepLinkDemo: $pendingDeepLinkDemo,
                launchArgDemo: Self.launchArgDemo
            )
                .environmentObject(updater)
                .overlay(alignment: .top) {
                    UpdateBanner()
                        .environmentObject(updater)
                }
                .onOpenURL { url in
                    if let id = DeepLinkRouter.parse(url, allowedDemos: DemoDeepLinkRegistry.allowedIds) {
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
        TabView(selection: $selectedTab) {
            ShowcaseTab()
                .tabItem {
                    Label("Showcase", systemImage: "square.grid.2x2.fill")
                }
                .tag(0)
                .accessibilityLabel("Showcase")

            #if os(iOS)
            ARTab()
                .tabItem {
                    Label("AR View", systemImage: "arkit")
                }
                .tag(1)
                .accessibilityLabel("Augmented Reality Viewer")
            #endif

            AboutTab()
                .tabItem {
                    Label("About", systemImage: "info.circle.fill")
                }
                .tag(2)
                .accessibilityLabel("About This App")
        }
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
        #if os(iOS)
        .fullScreenCover(item: $presentedDemo) { link in
            DemoDeepLinkRegistry.destination(for: link.id)
        }
        #else
        .sheet(item: $presentedDemo) { link in
            DemoDeepLinkRegistry.destination(for: link.id)
        }
        #endif
    }
}

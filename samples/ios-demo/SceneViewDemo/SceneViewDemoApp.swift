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

    /// App Store update checker — queried on every `.active` ScenePhase
    /// transition. The published state drives `UpdateBanner` overlaid on
    /// `ContentView`. See [AppStoreUpdater] for the throttle/snooze rules.
    @StateObject private var updater = AppStoreUpdater()

    @Environment(\.scenePhase) private var scenePhase

    var body: some SwiftUI.Scene {
        WindowGroup {
            ContentView(pendingDeepLinkDemo: $pendingDeepLinkDemo)
                .environmentObject(updater)
                .overlay(alignment: .top) {
                    UpdateBanner()
                        .environmentObject(updater)
                }
                .onOpenURL { url in
                    if let id = DeepLinkRouter.parse(url, allowedDemos: DemoDeepLinkRegistry.allowedIds) {
                        pendingDeepLinkDemo = id
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
    @State private var selectedTab = 0

    /// Wraps a demo id so SwiftUI's `.fullScreenCover(item:)` accepts it.
    private struct DemoLink: Identifiable {
        let id: String
    }
    @State private var presentedDemo: DemoLink?

    var body: some View {
        TabView(selection: $selectedTab) {
            ExploreTab()
                .tabItem {
                    Label("Explore", systemImage: "cube.fill")
                }
                .tag(0)
                .accessibilityLabel("3D Model Gallery")

            #if os(iOS)
            ARTab()
                .tabItem {
                    Label("AR View", systemImage: "arkit")
                }
                .tag(1)
                .accessibilityLabel("Augmented Reality Viewer")
            #endif

            SamplesTab()
                .tabItem {
                    Label("Samples", systemImage: "square.grid.2x2.fill")
                }
                .tag(2)
                .accessibilityLabel("Sample Presets")

            AboutTab()
                .tabItem {
                    Label("About", systemImage: "info.circle.fill")
                }
                .tag(3)
                .accessibilityLabel("About This App")
        }
        .tint(SceneViewTheme.primary)
        .onChange(of: pendingDeepLinkDemo) { _, newId in
            guard let id = newId else { return }
            // Switch to the Samples tab so the deep-link surface feels
            // contextual; then present the demo above it as a modal so we
            // don't have to thread navigation through SamplesTab.
            selectedTab = 2
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

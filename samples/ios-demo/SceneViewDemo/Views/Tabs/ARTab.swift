#if os(iOS)
import SwiftUI
import ARKit
import AVFoundation
import SceneViewSwift

/// The tab launches the same placement experience as the catalogue and viewers.
struct ARTab: View {
    @State private var sessionStarted = false
    @State private var presentedDemo: FeaturedARDemo?
    /// A demo from the "All AR demos" grid, in the Home's full-screen host.
    @State private var coverScene: DemoItem?
    /// Zoom transition namespace: each launcher card expands into the demo
    /// it opens, as the Home rows do (#3599).
    @Namespace private var cardNamespace

    private var arSupported: Bool {
        #if targetEnvironment(simulator)
        false
        #else
        ARWorldTrackingConfiguration.isSupported
        #endif
    }

    var body: some View {
        // A NavigationStack owns the title, as on the About tab: the large
        // title collapses into the inline bar over the system scroll-edge
        // backdrop, so nothing scrolls under the status bar or the Dynamic
        // Island (#3791). The title used to be a plain `Text` inside the
        // launcher's scroll view, with no safe-area chrome above it.
        NavigationStack {
            ARLauncherScreen(
                arSupported: arSupported,
                onStartArSession: { sessionStarted = true },
                onDemoTap: { presentedDemo = $0 },
                onSceneTap: { coverScene = $0 },
                transitionNamespace: cardNamespace
            )
            .navigationTitle("AR Experiences")
            .navigationBarTitleLarge()
        }
        .fullScreenCover(isPresented: $sessionStarted) {
            NavigationStack {
                ARExperienceContainer(onBack: { sessionStarted = false }) {
                    ARPlacementExperience()
                }
            }
        }
        .fullScreenCover(item: $coverScene) { scene in
            DemoCover(scene: scene, source: .other) { coverScene = nil }
                .zoomTransition(from: ARLauncherScreen.sceneSourceId(scene), in: cardNamespace)
                // No system pinch / drag-down dismissal over a 3D stage (#4008).
                .interactiveDismissDisabled()
        }
        .fullScreenCover(item: $presentedDemo) { demo in
            NavigationStack {
                ARExperienceContainer(
                    requirement: .forScene(id: demo.id),
                    onBack: { presentedDemo = nil }
                ) { demo.destination }
                .environment(\.analyticsSampleId, demo.id)
                .navigationTitle(demo.title)
                .navigationBarTitleInline()
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close") { presentedDemo = nil }
                    }
                }
            }
            // Opened from the AR tab, not through `DemoCover`: same sample session.
            .onAppear {
                let section = GeneratedScenes.all().first { $0.sceneId == demo.id }?.section
                DemoAnalytics.shared.sampleOpened(demo.id, category: DemoAnalytics.category(for: section), source: .other)
            }
            .onDisappear { DemoAnalytics.shared.sampleClosed(demo.id) }
            .zoomTransition(from: ARLauncherScreen.featuredSourceId(demo), in: cardNamespace)
            .interactiveDismissDisabled()
        }
    }
}

// MARK: - Launcher screen (entry / exit gate)

/// What the launcher CTA should do, derived from device capability +
/// the camera authorization status. Mirrors Android's `ArLauncherScreen`
/// CTA states (checking / grant-camera / start / unsupported).
private enum ARLauncherState {
    /// ARKit available + camera not-yet-denied — CTA starts the session.
    case ready
    /// ARKit world-tracking unavailable on this device — CTA disabled.
    case unsupported
    /// Camera permission was denied/restricted in a previous run — CTA
    /// deep-links to Settings so the user can re-enable it (Android's
    /// launcher offers the equivalent "Grant Camera Access" path).
    case cameraDenied
}

/// One headline AR demo surfaced on the launcher's discovery grid. Each
/// entry carries the SwiftUI destination so a card tap can present the demo
/// full-screen — mirroring Android's `FEATURED_AR_DEMOS` list on
/// `ArViewTab.kt`, where every card routes to a real demo screen.
///
/// Only AR demos with a *working* iOS port are listed — the launcher is a
/// discovery surface, not a "coming soon" teaser wall (the Samples tab
/// already shows the full catalogue including not-yet-ported demos).
///
/// `@MainActor`-isolated: the erased `AnyView` destination is built from
/// SwiftUI views, which are themselves main-actor-isolated, so the type and
/// its static `all` catalogue live on the main actor (it's UI-only data).
@MainActor
struct FeaturedARDemo: Identifiable {
    /// `nonisolated` so it satisfies `Identifiable`'s non-isolated `id`
    /// requirement even though the enclosing type is `@MainActor`.
    nonisolated let id: String
    let title: String
    let subtitle: String
    let icon: String
    /// Builds the demo view to present. `@ViewBuilder`-erased so heterogeneous
    /// demo types share one collection.
    let destination: AnyView

    /// The headline AR demos shown on the launcher grid. Picked to mirror
    /// Android's launcher card set as closely as the iOS port allows — all of
    /// these have a real, shipping iOS destination.
    ///
    /// Samples audit, step 0: `ar-lighting` is now the Light mode of AR
    /// Placement and `ar-record-playback` the shared Record action of every
    /// screen, so neither is a card any more. Face accessories takes the slot,
    /// Android's second featured card.
    static let all: [FeaturedARDemo] = [
        FeaturedARDemo(
            id: "ar-placement",
            title: "AR Placement",
            subtitle: "One object on the first usable surface",
            icon: "arkit",
            // The whole card, with its Place / Wall / Free pose / Light modes.
            destination: ArPlacementScene.destination
        ),
        FeaturedARDemo(
            id: "ar-face",
            title: "Face Accessories",
            subtitle: "Pin accessories to a tracked face anchor",
            icon: "face.smiling.inverse",
            destination: ArAugmentedFacesScene.destination
        ),
        FeaturedARDemo(
            id: "ar-orbital",
            title: "Orbital AR",
            subtitle: "Models orbit around you in a personal solar system",
            icon: "globe.europe.africa.fill",
            destination: AnyView(OrbitalARDemo())
        ),
        FeaturedARDemo(
            id: "ar-rerun",
            title: "3D Scan",
            subtitle: "Move around a space and watch it take shape in 3D",
            icon: "point.3.connected.trianglepath.dotted",
            destination: AnyView(RerunShowcaseDemo())
        ),
    ]
}

/// Static launcher shown when the AR tab is opened, before the user explicitly
/// starts the camera session. Mirrors Android's `ArLauncherScreen` on
/// `ArViewTab.kt` (#1211 item 3): hero icon + tagline, a "Featured" grid of
/// the headline AR demos, an "All AR demos" grid of the rest, and a floating
/// glass dock carrying the "Start AR Camera" call to action. Every card opens
/// a real demo full-screen above the AR tab.
private struct ARLauncherScreen: View {
    let arSupported: Bool
    let onStartArSession: () -> Void
    /// Invoked when one of the "Featured" cards is tapped.
    let onDemoTap: (FeaturedARDemo) -> Void
    /// Invoked when a card of the "All AR demos" grid is tapped.
    var onSceneTap: (DemoItem) -> Void = { _ in }
    /// Where the cards register as zoom sources; none in previews.
    var transitionNamespace: Namespace.ID? = nil

    static func featuredSourceId(_ demo: FeaturedARDemo) -> String { "ar-featured-\(demo.id)" }
    static func sceneSourceId(_ item: DemoItem) -> String { "ar-demo-\(item.sceneId)" }

    /// The catalogue, read once — `GeneratedScenes.all()` builds every item.
    @State private var scenes: [DemoItem] = []

    /// Re-read on scene-foreground so a user who tapped "Open Settings",
    /// flipped the camera switch and returned sees the CTA recover to
    /// "Start AR Camera" without an app relaunch.
    @State private var cameraStatus: AVAuthorizationStatus =
        AVCaptureDevice.authorizationStatus(for: .video)
    /// `.onAppear` does NOT re-fire when the app returns from Settings for
    /// an already-mounted view — `scenePhase` does.
    @Environment(\.scenePhase) private var scenePhase

    private var state: ARLauncherState {
        if !arSupported { return .unsupported }
        switch cameraStatus {
        case .denied, .restricted: return .cameraDenied
        default: return .ready   // .notDetermined / .authorized
        }
    }

    private var ctaTitle: String {
        switch state {
        case .ready:       return "Start AR Camera"
        case .unsupported: return "AR not supported on this device"
        case .cameraDenied: return "Open Settings to enable Camera"
        }
    }

    private var ctaIcon: String {
        switch state {
        case .ready:       return "camera.viewfinder"
        case .unsupported: return "xmark.octagon"
        case .cameraDenied: return "gearshape.fill"
        }
    }

    private var caption: String {
        switch state {
        case .ready:       return "Camera permission is requested when you start the camera."
        case .unsupported:
            #if targetEnvironment(simulator)
            // ARKit world-tracking needs a real device camera — it is never
            // available in the iOS Simulator regardless of the iPhone model.
            return "AR is not available in the iOS Simulator. Run on a physical device to use the camera."
            #else
            return "ARKit world-tracking isn't available on this device."
            #endif
        case .cameraDenied: return "Camera access was turned off for this app. Re-enable it in Settings to place models in AR."
        }
    }

    private func onCtaTap() {
        switch state {
        case .ready:
            // Request camera permission from the CTA tap (a user action —
            // Apple's recommended trigger) BEFORE entering the live session.
            // This closes the first-run hole: previously a `.notDetermined`
            // user tapped Start → ARSceneView mounted → OS dialog → if they
            // denied there they were stranded in a black AR view. Now the
            // launcher owns the prompt: granted → enter; denied → the CTA
            // recomputes to `.cameraDenied` (Open Settings) and the user
            // never sees a dead camera view.
            switch cameraStatus {
            case .authorized:
                onStartArSession()
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    Task { @MainActor in
                        cameraStatus = AVCaptureDevice.authorizationStatus(for: .video)
                        if granted { onStartArSession() }
                    }
                }
            default:
                break  // .denied / .restricted handled by the .cameraDenied case
            }
        case .cameraDenied:
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        case .unsupported:
            break  // button is disabled
        }
    }

    /// Android's featured AR cards, as catalogue items so they draw as the
    /// Home's picture cards.
    private var featured: [(FeaturedARDemo, DemoItem)] {
        FeaturedARDemo.all.compactMap { demo in
            scenes.first { $0.sceneId == demo.id }.map { (demo, $0) }
        }
    }

    /// Every other AR demo the Home lists — Android's "All AR demos" grid.
    private var others: [DemoItem] {
        let featuredIds = Set(FeaturedARDemo.all.map(\.id))
        return scenes.filter {
            $0.category == .ar && $0.status.isAvailable
                && HomeCatalogue.isOnHome($0.sceneId) && !featuredIds.contains($0.sceneId)
        }
    }

    private var columns: [GridItem] {
        [GridItem(.flexible(), spacing: SceneViewTokens.Home.gridGutter, alignment: .top),
         GridItem(.flexible(), spacing: SceneViewTokens.Home.gridGutter, alignment: .top)]
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // Compact hero — the icon beside the tagline, as on Android's
                // `ArLauncherScreen`. The title is the navigation title.
                HStack(spacing: SceneViewTokens.Space.md) {
                    RoundedRectangle(cornerRadius: SceneViewTokens.Radius.sm, style: .continuous)
                        .fill(LinearGradient(colors: [SceneViewTokens.HomeColor.primary,
                                                      SceneViewTokens.HomeColor.tertiary],
                                             startPoint: .topLeading, endPoint: .bottomTrailing))
                        .frame(width: SceneViewTokens.Home.iconTileGlyph, height: SceneViewTokens.Home.iconTileGlyph)
                        .overlay {
                            Image(systemName: "arkit")
                                .font(.system(size: SceneViewTokens.Layout.dockIconSize, weight: .semibold))
                                .foregroundStyle(SceneViewTokens.HomeColor.onPrimary)
                        }
                        .accessibilityHidden(true)
                    Text("Place models, scan faces, anchor to terrain.")
                        .font(SceneViewTokens.TypeScale.body)
                        .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.top, SceneViewTokens.Space.sm)

                sectionHeader("Featured")
                LazyVGrid(columns: columns, spacing: SceneViewTokens.Home.gridGutter) {
                    let featured = featured
                    ForEach(featured.indices, id: \.self) { index in
                        let (demo, item) = featured[index]
                        DemoMediaCard(demo: item) { onDemoTap(demo) }
                            .zoomTransitionSource(id: Self.featuredSourceId(demo), in: transitionNamespace)
                            .accessibilityIdentifier("ar-featured-\(demo.id)")
                    }
                }

                if !others.isEmpty {
                    sectionHeader("All AR demos (\(others.count + featured.count))")
                    LazyVGrid(columns: columns, spacing: SceneViewTokens.Home.gridGutter) {
                        ForEach(others, id: \.sceneId) { item in
                            DemoMediaCard(demo: item) { onSceneTap(item) }
                                .zoomTransitionSource(id: Self.sceneSourceId(item), in: transitionNamespace)
                                .accessibilityIdentifier("ar-demo-\(item.sceneId)")
                        }
                    }
                }
            }
            .padding(.horizontal, SceneViewTokens.Home.contentPadding)
            .padding(.bottom, SceneViewTokens.Home.gridBottomInset)
        }
        .background(SceneViewTokens.HomeColor.surface)
        // The camera's way in floats over the grids — Android's launcher dock.
        .safeAreaInset(edge: .bottom, spacing: 0) { dock }
        .onAppear { if scenes.isEmpty { scenes = GeneratedScenes.all() } }
        .onChange(of: scenePhase) { _, phase in
            // Re-sync the CTA when the app returns to the foreground — the
            // user may have flipped the camera switch in Settings.
            if phase == .active {
                cameraStatus = AVCaptureDevice.authorizationStatus(for: .video)
            }
        }
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(SceneViewTokens.TypeScale.card)
            .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
            .padding(.top, SceneViewTokens.Home.sectionHeaderTopGap)
            .padding(.bottom, SceneViewTokens.Home.sectionHeaderBottomGap)
    }

    // MARK: - Dock

    /// The floating glass dock: the "Start AR Camera" call to action — or,
    /// where there is nothing to tap, the reason — and the caption under it.
    private var dock: some View {
        VStack(spacing: SceneViewTokens.Space.sm) {
            if state == .unsupported {
                // Nothing to tap, so no button: a status line, as on
                // Android's `ArLauncherScreen` (#3790). `on-surface` on
                // `chip-bg` is 15.3:1 light / 13.5:1 dark.
                Label(ctaTitle, systemImage: ctaIcon)
                    .font(SceneViewTokens.TypeScale.bodySemibold)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                    .labelStyle(StatusLabelStyle())
                    .frame(maxWidth: .infinity, minHeight: SceneViewTokens.Layout.touchTarget)
                    .padding(.horizontal, SceneViewTokens.Space.md)
                    .background(SceneViewTokens.HomeColor.chipBackground, in: Capsule())
                    .accessibilityElement(children: .combine)
                    .accessibilitySortPriority(1)
                    .accessibilityIdentifier("ar-dock-status")
            } else {
                Button(action: onCtaTap) {
                    Label(ctaTitle, systemImage: ctaIcon)
                        .font(SceneViewTokens.TypeScale.bodySemibold)
                        .frame(maxWidth: .infinity, minHeight: SceneViewTokens.Layout.touchTarget)
                        .background(SceneViewTokens.HomeColor.primary, in: Capsule())
                        .foregroundStyle(SceneViewTokens.HomeColor.onPrimary)
                        .contentShape(Capsule())
                }
                .buttonStyle(PressScaleButtonStyle())
                .accessibilityLabel(ctaTitle)
                .accessibilitySortPriority(1)
                .accessibilityIdentifier("ar-dock-start")
            }
            Text(caption)
                .font(SceneViewTokens.TypeScale.captionRegular)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(SceneViewTokens.Space.sm + SceneViewTokens.Space.xs)
        .glassBackground(in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.xl, style: .continuous),
                         interactive: true)
        .padding(.horizontal, SceneViewTokens.Home.contentPadding)
        .padding(.bottom, SceneViewTokens.Space.sm)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("ar-dock")
    }
}

/// Status line label: the glyph in `danger` (3.5:1 light / 3.8:1 dark on
/// `surface-container-high`, above the 3:1 a graphic needs), the text in
/// whatever foreground the caller set.
private struct StatusLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: SceneViewTokens.Space.sm) {
            configuration.icon
                .foregroundStyle(SceneViewTokens.HomeColor.danger)
            configuration.title
        }
    }
}

#Preview("Launcher — supported") {
    ARLauncherScreen(arSupported: true, onStartArSession: {}, onDemoTap: { _ in })
}

#Preview("Launcher — unsupported") {
    ARLauncherScreen(arSupported: false, onStartArSession: {}, onDemoTap: { _ in })
}
#endif

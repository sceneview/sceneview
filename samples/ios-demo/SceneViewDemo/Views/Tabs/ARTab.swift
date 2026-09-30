#if os(iOS)
import SwiftUI
import ARKit
import AVFoundation
import SceneViewSwift

/// The tab launches the same placement experience as the catalogue and viewers.
struct ARTab: View {
    @State private var sessionStarted = false
    @State private var presentedDemo: DemoItem?
    @State private var comingSoonDemo: DemoItem?

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
                onDemoTap: { demo in
                    if demo.status.isAvailable {
                        presentedDemo = demo
                    } else {
                        comingSoonDemo = demo
                    }
                }
            )
            .navigationTitle("AR Experiences")
        }
        .fullScreenCover(isPresented: $sessionStarted) {
            NavigationStack {
                ARExperienceContainer(onBack: { sessionStarted = false }) {
                    ARPlacementExperience()
                }
            }
        }
        // The same host as a Home row: `DemoCover` over the catalogue entry,
        // whose destination already enters through `ARExperienceContainer`
        // with the scene's own requirement (collate-ios-demos.sh).
        .fullScreenCover(item: $presentedDemo) { demo in
            DemoCover(scene: demo) { presentedDemo = nil }
        }
        .sheet(item: $comingSoonDemo) { demo in
            ComingSoonScreen(
                title: demo.title,
                subtitle: demo.subtitle,
                icon: demo.icon,
                androidOnlyReason: demo.androidOnlyReason
            )
            .presentationDetents([.medium, .large])
            .partialSheetBackground(.regularMaterial)
            .presentationCornerRadius(SceneViewTokens.Radius.xl)
            .presentationDragIndicator(.visible)
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

/// The launcher's AR demos: the curator's pick on top, then every other AR
/// demo — Android's `FEATURED_AR_DEMOS` and "All AR demos" on `ArViewTab.kt`.
/// Each is the catalogue's own `DemoItem` (its picture, status and freshness),
/// so a card here opens exactly what the Home row opens.
@MainActor
enum ARLauncherCatalogue {
    /// The featured tiles, in Android's order, each under its curated title
    /// and subtitle (Android `featured_ar_*` strings). An id with no iOS
    /// registry entry is dropped rather than drawn with no demo behind it.
    /// Scene Geometry keeps the iOS registry's subtitle: Android's promises
    /// Geospatial building meshes, which ARKit has no equivalent of — the iOS
    /// demo is the LiDAR room mesh.
    static let featuredPicks: [(id: String, title: String, subtitle: String?)] = [
        ("ar-placement", "Place in AR", "Point at the floor and the model appears"),
        ("ar-face", "Augmented Faces", "Face mesh tracking and overlays"),
        ("ar-cloud-anchor", "Cloud Anchors", "Persistent multi-user anchors"),
        ("ar-scene-mesh", "Scene Geometry", nil),
        ("ar-depth-occlusion", "Depth Occlusion", "Real-world depth masks virtual objects"),
        ("ar-pose", "Pose Placement", "Free pose positioning"),
    ]

    static let featured: [DemoItem] = {
        let byId = registry
        return featuredPicks.compactMap { pick in
            guard var demo = byId[pick.id] else { return nil }
            demo.title = pick.title
            if let subtitle = pick.subtitle { demo.subtitle = subtitle }
            return demo
        }
    }()

    /// Every other AR demo the Home lists, in editorial order.
    static let others: [DemoItem] = {
        let featuredIds = Set(featured.map(\.sceneId))
        return GeneratedScenes.all()
            .filter { $0.category == .ar && !featuredIds.contains($0.sceneId) }
            .filter { HomeCatalogue.isOnHome($0.sceneId) }
            .sorted { $0.order < $1.order }
    }()

    private static var registry: [String: DemoItem] {
        Dictionary(GeneratedScenes.all().filter { HomeCatalogue.isOnHome($0.sceneId) }
                       .map { ($0.sceneId, $0) },
                   uniquingKeysWith: { first, _ in first })
    }
}

/// Static launcher shown when the AR tab is opened, before the user explicitly
/// starts the camera session. Mirrors Android's `ArLauncherScreen` on
/// `ArViewTab.kt` (#1211 item 3): the 3D hero (`ARHeroStage`) with the tagline,
/// the AR status and the "Start AR Camera" CTA,
/// followed by the AR demos as the Home's picture cards (`DemoMediaCard`,
/// #4200): "Featured", then "All AR demos". Every card routes to a real demo
/// screen presented full-screen above the AR tab.
private struct ARLauncherScreen: View {
    let arSupported: Bool
    let onStartArSession: () -> Void
    /// Invoked when one of the demo cards is tapped.
    let onDemoTap: (DemoItem) -> Void

    /// Re-read on scene-foreground so a user who tapped "Open Settings",
    /// flipped the camera switch and returned sees the CTA recover to
    /// "Start AR Camera" without an app relaunch.
    @State private var cameraStatus: AVAuthorizationStatus =
        AVCaptureDevice.authorizationStatus(for: .video)
    /// `.onAppear` does NOT re-fire when the app returns from Settings for
    /// an already-mounted view — `scenePhase` does.
    @Environment(\.scenePhase) private var scenePhase
    /// Some of the hero is on screen: its 3D only runs then.
    @State private var heroOnScreen = true

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

    /// The device's AR status, on the hero under the stage — Android's
    /// `ar_status_*` line.
    private var statusText: String {
        switch state {
        case .ready:        return "AR supported on this device."
        case .unsupported:  return "AR not supported on this device."
        case .cameraDenied: return "Camera access is off for this app."
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

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                // The hero (#wow): AR playing on its own before the camera is
                // ever opened — a detected floor, a reticle, bundled models
                // placed on it — with the tagline, the device's AR status and
                // the one call to action drawn over the stage (Android's
                // `ArHero`). The title stays the navigation title (#3791).
                hero
                    .padding(.horizontal, SceneViewTokens.Home.contentPadding)
                    .padding(.top, SceneViewTokens.Space.sm)

                Text(caption)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

                // The AR demos as the Home's picture cards — Android's
                // Featured grid then "All AR demos (N)" (#4200).
                demoSection(title: "Featured", demos: ARLauncherCatalogue.featured)
                    .padding(.top, SceneViewTokens.Space.sm)
                if !ARLauncherCatalogue.others.isEmpty {
                    demoSection(
                        title: "All AR demos (\(ARLauncherCatalogue.featured.count + ARLauncherCatalogue.others.count))",
                        demos: ARLauncherCatalogue.others
                    )
                    .padding(.top, SceneViewTokens.Space.sm)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.bottom, 24)
        }
        .background(SceneViewTokens.HomeColor.surface)
        .onChange(of: scenePhase) { _, phase in
            // Re-sync the CTA when the app returns to the foreground — the
            // user may have flipped the camera switch in Settings.
            if phase == .active {
                cameraStatus = AVCaptureDevice.authorizationStatus(for: .video)
            }
        }
    }

    // MARK: - Hero

    /// The hero card: `ARHeroStage` playing behind the tagline, the device's
    /// AR status and the one call to action. Dark in both themes, like the
    /// Home hero — the copy is white, the button is the Home hero's white pill.
    private var hero: some View {
        let tokens = SceneViewTokens.ArHero.self
        let home = SceneViewTokens.HomeColor.self
        return ZStack {
            ARHeroStage(active: heroOnScreen)
            ARHeroViewfinder()
            // Copy scrim at the foot of the stage: the status and the pill
            // always read, whatever model is standing behind them.
            LinearGradient(colors: [.clear, tokens.copyScrim], startPoint: .top, endPoint: .bottom)
                .frame(height: tokens.height / 2)
                .frame(maxHeight: .infinity, alignment: .bottom)
                .allowsHitTesting(false)

            Text("Place models, scan faces, anchor to terrain.")
                .font(.body.weight(.semibold))
                .foregroundStyle(home.heroTitle)
                .containerRelativeFrame(.horizontal) { width, _ in width * tokens.copyWidthShare }
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding([.top, .leading], SceneViewTokens.Space.lg)

            VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm + SceneViewTokens.Space.xs) {
                HStack(spacing: SceneViewTokens.Space.sm) {
                    Image(systemName: state == .ready ? "checkmark.circle.fill" : "xmark.circle.fill")
                        .font(.system(size: tokens.statusIcon))
                        .foregroundStyle(state == .ready ? tokens.planeDot : SceneViewTokens.ARChrome.danger)
                    Text(statusText)
                        .font(.subheadline)
                        .foregroundStyle(home.heroSubtitle)
                }
                .padding(.leading, SceneViewTokens.Space.sm)
                .accessibilityElement(children: .combine)

                if state != .unsupported {
                    Button(action: onCtaTap) {
                        Label(ctaTitle, systemImage: ctaIcon)
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: tokens.ctaHeight)
                            .background(home.heroPillBackground, in: Capsule())
                            .foregroundStyle(home.heroPillText)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(ctaTitle)
                    .accessibilitySortPriority(1)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            .padding(SceneViewTokens.Space.md)
        }
        .frame(height: tokens.height)
        .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.xl, style: .continuous))
        .environment(\.colorScheme, .dark)
        // The stage only renders while some of it is on screen.
        .onScrollVisibilityChange(threshold: 0.01) { heroOnScreen = $0 }
        .onAppear { heroOnScreen = true }
        .onDisappear { heroOnScreen = false }
    }

    // MARK: - Demo cards

    /// A section title, then two `DemoMediaCard`s a row, `home-grid-gutter`
    /// apart. A row's two cards end level (`levelledRow()`, Android's
    /// `rowPeers`); a last card alone keeps half the width.
    private func demoSection(title: String, demos: [DemoItem]) -> some View {
        let gutter = SceneViewTokens.Home.gridGutter
        let rows = stride(from: 0, to: demos.count, by: 2).map { Array(demos[$0..<min($0 + 2, demos.count)]) }
        return VStack(alignment: .leading, spacing: gutter) {
            Text(title)
                .font(.headline)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                .accessibilityAddTraits(.isHeader)
                .padding(.leading, SceneViewTokens.Space.xs)
            ForEach(rows, id: \.first!.sceneId) { row in
                HStack(alignment: .top, spacing: gutter) {
                    ForEach(row, id: \.sceneId) { demo in
                        DemoMediaCard(demo: demo) {
                            SceneViewHaptic.shared.light()
                            onDemoTap(demo)
                        }
                        .frame(maxWidth: .infinity)
                        .accessibilityIdentifier("ar-card-\(demo.sceneId)")
                    }
                    if row.count == 1 {
                        Color.clear.frame(maxWidth: .infinity)
                    }
                }
                .levelledRow()
            }
        }
        .padding(.horizontal, SceneViewTokens.Home.contentPadding)
    }
}

#Preview("Launcher — supported") {
    ARLauncherScreen(arSupported: true, onStartArSession: {}, onDemoTap: { _ in })
}

#Preview("Launcher — unsupported") {
    ARLauncherScreen(arSupported: false, onStartArSession: {}, onDemoTap: { _ in })
}
#endif

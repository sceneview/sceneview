#if os(iOS)
import SwiftUI
import ARKit
import AVFoundation
import SceneViewSwift

/// The tab launches the same placement experience as the catalogue and viewers.
struct ARTab: View {
    @State private var sessionStarted = false
    @State private var presentedDemo: FeaturedARDemo?

    private var arSupported: Bool {
        #if targetEnvironment(simulator)
        false
        #else
        ARWorldTrackingConfiguration.isSupported
        #endif
    }

    var body: some View {
        ARLauncherScreen(
            arSupported: arSupported,
            onStartArSession: { sessionStarted = true },
            onDemoTap: { presentedDemo = $0 }
        )
        .fullScreenCover(isPresented: $sessionStarted) {
            NavigationStack {
                ARExperienceContainer(onBack: { sessionStarted = false }) {
                    ARPlacementExperience()
                }
            }
        }
        .fullScreenCover(item: $presentedDemo) { demo in
            NavigationStack {
                ARExperienceContainer(
                    requirement: .forScene(id: demo.id),
                    onBack: { presentedDemo = nil }
                ) { demo.destination }
                .navigationTitle(demo.title)
                .navigationBarTitleInline()
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close") { presentedDemo = nil }
                    }
                }
            }
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

    /// The six headline AR demos shown on the launcher grid. Picked to mirror
    /// Android's launcher card set as closely as the iOS port allows — all of
    /// these have a real, shipping iOS destination.
    static let all: [FeaturedARDemo] = [
        FeaturedARDemo(
            id: "ar-placement",
            title: "AR Placement",
            subtitle: "One object on the first usable surface",
            icon: "arkit",
            destination: AnyView(ARPlacementDemo())
        ),
        FeaturedARDemo(
            id: "ar-instant-placement",
            title: "Automatic Placement",
            subtitle: "Place immediately when a surface is usable",
            icon: "bolt.fill",
            destination: AnyView(ARInstantPlacementDemo())
        ),
        FeaturedARDemo(
            id: "ar-lighting",
            title: "AR Lighting",
            subtitle: "Compare main / fill light modifier presets",
            icon: "lightbulb.max.fill",
            destination: AnyView(ARLightingDemo())
        ),
        FeaturedARDemo(
            // Canonicalized to match Android's DemoRegistry id (#2799); the
            // deep-link registry still accepts the old "ar-recording" id as
            // a legacy alias (see `DemoDeepLinkRegistry.allowedIds`).
            id: "ar-record-playback",
            title: "AR Recording",
            subtitle: "Capture the AR session as a screen video",
            icon: "record.circle",
            destination: AnyView(ARRecorderDemo())
        ),
        FeaturedARDemo(
            id: "ar-orbital",
            title: "Orbital AR",
            subtitle: "Models orbit around you in a personal solar system",
            icon: "circle.dotted",
            destination: AnyView(OrbitalARDemo())
        ),
        FeaturedARDemo(
            id: "ar-rerun",
            title: "Rerun Debug",
            subtitle: "Stream camera pose and planes to the Rerun viewer",
            icon: "antenna.radiowaves.left.and.right",
            destination: AnyView(RerunDebugDemo())
        ),
    ]
}

/// Static launcher shown when the AR tab is opened, before the user explicitly
/// starts the camera session. Mirrors Android's `ArLauncherScreen` on
/// `ArViewTab.kt` (#1211 item 3): hero icon + tagline + "Start AR Camera" CTA,
/// followed by a 2×3 grid of headline AR demo cards so the launcher doubles
/// as a discovery surface (issue #1253 item 1) — every card routes to a real
/// demo screen presented full-screen above the AR tab.
private struct ARLauncherScreen: View {
    let arSupported: Bool
    let onStartArSession: () -> Void
    /// Invoked when one of the discovery-grid cards is tapped.
    let onDemoTap: (FeaturedARDemo) -> Void

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

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                // Hero icon — same gradient + corner radius idiom as the Android
                // launcher's 56dp box.
                ZStack {
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [
                                    Color.accentColor.opacity(0.85),
                                    .blue.opacity(0.55),
                                    .purple.opacity(0.45),
                                ],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 96, height: 96)
                    Image(systemName: "arkit")
                        .font(.system(size: 44, weight: .semibold))
                        .foregroundStyle(.white)
                        .accessibilityHidden(true)
                }
                .padding(.top, 32)

                VStack(spacing: 8) {
                    Text("AR Experiences")
                        .font(.title.weight(.bold))
                        // Bump VoiceOver focus order so the title gets read
                        // first then the CTA — without this the screen reader
                        // walks the Spacer / hero / etc before reaching the
                        // action.
                        .accessibilitySortPriority(2)
                    Text("Place 3D models in your space, scan faces, anchor to terrain.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                }

                Button(action: onCtaTap) {
                    Label(ctaTitle, systemImage: ctaIcon)
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(.tint, in: Capsule())
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 24)
                .disabled(state == .unsupported)
                .opacity(state == .unsupported ? 0.5 : 1.0)
                .accessibilityLabel(ctaTitle)
                .accessibilitySortPriority(1)

                Text(caption)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

                // Discovery grid — mirrors Android's `FEATURED_AR_DEMOS` 2×3
                // card grid on `ArLauncherScreen`. Gives the user something
                // to explore even before (or instead of) starting the live
                // camera session. Each card opens a real AR demo full-screen.
                demoGrid
                    .padding(.top, 8)
            }
            .frame(maxWidth: .infinity)
            .padding(.bottom, 24)
        }
        .onChange(of: scenePhase) { _, phase in
            // Re-sync the CTA when the app returns to the foreground — the
            // user may have flipped the camera switch in Settings.
            if phase == .active {
                cameraStatus = AVCaptureDevice.authorizationStatus(for: .video)
            }
        }
    }

    // MARK: - Discovery grid

    private var demoGrid: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Try an AR demo")
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, 24)

            LazyVGrid(
                columns: [
                    GridItem(.flexible(), spacing: 12),
                    GridItem(.flexible(), spacing: 12),
                ],
                spacing: 12
            ) {
                ForEach(FeaturedARDemo.all) { demo in
                    Button {
                        onDemoTap(demo)
                    } label: {
                        ARDemoCard(demo: demo)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(demo.title): \(demo.subtitle)")
                }
            }
            .padding(.horizontal, 24)
        }
    }
}

/// A single discovery-grid card on the AR launcher. Visually mirrors the
/// Samples-tab card idiom (gradient icon header + title + subtitle) so the
/// two surfaces feel like one app, matching Android's `ArDemoCard`.
private struct ARDemoCard: View {
    let demo: FeaturedARDemo

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack {
                Rectangle()
                    .fill(
                        LinearGradient(
                            colors: [
                                Color.green.opacity(0.32),
                                Color.green.opacity(0.14),
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                Image(systemName: demo.icon)
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(.green)
                    .accessibilityHidden(true)
            }
            .frame(height: 56)

            VStack(alignment: .leading, spacing: 4) {
                Text(demo.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(demo.subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
        }
        .frame(maxWidth: .infinity)
        .frame(minHeight: 150, alignment: .top)
        .glassBackground(in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.06), lineWidth: 0.5)
        )
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

#Preview("Launcher — supported") {
    ARLauncherScreen(arSupported: true, onStartArSession: {}, onDemoTap: { _ in })
}

#Preview("Launcher — unsupported") {
    ARLauncherScreen(arSupported: false, onStartArSession: {}, onDemoTap: { _ in })
}
#endif

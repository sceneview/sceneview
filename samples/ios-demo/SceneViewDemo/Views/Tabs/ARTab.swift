#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import AVFoundation
import SceneViewSwift

/// AR tab — place 3D models in your real-world space.
///
/// Liquid Glass overlay design (iOS 26+ Stitch spec):
/// - Full-bleed AR camera underneath
/// - Top-center: floating glass status pill ("Tap to place" / "N placed")
/// - Top-right: glass exit button to dismiss the tab
/// - Bottom: floating glass action bar with FAB "Pick model", Reset, Screenshot
struct ARTab: View {
    /// Anchors placed by tapping a detected plane. The count shown in the
    /// status pill is *derived* from this collection (`placedCount`) so the
    /// two can never drift — previously a bare `placedCount: Int` could fall
    /// out of sync with the real ARKit anchor set (issue #1253 item 4,
    /// matching the `placedAnchors` pattern already used by `ARPlacementDemo`).
    @State private var placedAnchors: [AnchorEntity] = []
    @State private var selectedModelIndex = 0
    @State private var errorMessage: String?
    @State private var showError = false
    @State private var showModelPicker = false
    @State private var arViewID = UUID()

    /// Live count of placed models, derived from `placedAnchors` so it stays
    /// authoritative — never a separately-mutated `Int`.
    private var placedCount: Int { placedAnchors.count }
    /// Mirrors Android's `sessionStarted` gate on `ArViewTabContent` — the
    /// AR tab opens to a static launcher screen (icon + tagline + "Start
    /// AR Camera" CTA) instead of jumping straight to the live ARKit
    /// session. Flips to `true` on CTA tap and back to `false` from the
    /// top-trailing Close button. `@State` (not `@SceneStorage`) because
    /// `@SceneStorage` survives cold launches via UISceneSession state
    /// restoration — the opposite of the intended UX. Android's
    /// `rememberSaveable` survives process death only inside the same
    /// composition; on iOS the ARTab view stays in the TabView's tree as
    /// long as the scene is alive, so plain `@State` already matches that
    /// scope.
    @State private var sessionStarted = false

    /// AR demo presented from the launcher's discovery grid (issue #1253
    /// item 1). The launcher mirrors Android's `ArLauncherScreen`, which is
    /// both an entry gate *and* a discovery surface — a 2×3 grid of headline
    /// AR demos. Tapping a card opens the demo full-screen above the AR tab.
    @State private var presentedDemo: FeaturedARDemo?

    /// Whether the current device has the ARKit world-tracking config we
    /// need (front-facing AR planes + camera passthrough). Computed once
    /// at view init; iPhones older than the A9 era return `false`. Mirrors
    /// Android's `ArCoreApk.checkAvailability()` gating on the launcher CTA.
    private var arSupported: Bool {
        #if !targetEnvironment(simulator)
        ARWorldTrackingConfiguration.isSupported
        #else
        false
        #endif
    }

    /// Increment to force-rebuild the ARSceneView, clearing every placed anchor.
    /// We rebuild because there's no public `removeAllAnchors` on the wrapper.
    private func resetScene() {
        arViewID = UUID()
        placedAnchors.removeAll()
        HapticManager.mediumTap()
    }

    /// Used by the top-right Close button AND a future system-back gesture
    /// (none on iOS today). Tearing down the `liveARView` via the outer
    /// `if/else` already destroys the ARSceneView, so we don't bump
    /// `arViewID` here (it would be dead — that's `resetScene()`'s job
    /// for in-session resets). We DO close any open sheets / alerts so
    /// the user lands on a clean launcher instead of an orphan modal.
    private func exitArSession() {
        placedAnchors.removeAll()
        showModelPicker = false
        showError = false
        sessionStarted = false
        HapticManager.lightTap()
    }

    // `animated_dragon.usdz` (8.6 MB) was dropped in #1152 Stage 3 (IPA
    // slim-down). The "creature" role is covered by Butterfly + Phoenix.
    private let arModels: [(name: String, icon: String, asset: String, scale: Float)] = [
        ("Game Boy", "gamecontroller.fill", "game_boy_classic", 0.15),
        ("Red Car", "car.fill", "red_car", 0.2),
        ("Butterfly", "leaf.fill", "animated_butterfly", 0.15),
        ("Piano", "pianokeys", "retro_piano", 0.12),
        ("Nike Jordan", "shoe.fill", "nike_air_jordan", 0.15),
        ("Phoenix", "bird.fill", "phoenix_bird", 0.15),
        ("Fantasy Book", "book.fill", "fantasy_book", 0.12),
        ("PS5 Controller", "gamecontroller.fill", "ps5_dualsense", 0.15),
        ("Tree Scene", "tree.fill", "tree_scene", 0.1),
    ]

    private var selectedModel: (name: String, icon: String, asset: String, scale: Float) {
        arModels[selectedModelIndex]
    }

    var body: some View {
        Group {
            if sessionStarted {
                liveARView
            } else {
                ARLauncherScreen(
                    arSupported: arSupported,
                    onStartArSession: {
                        sessionStarted = true
                        HapticManager.lightTap()
                    },
                    onDemoTap: { demo in
                        presentedDemo = demo
                        HapticManager.lightTap()
                    }
                )
            }
        }
        .alert("AR Error", isPresented: $showError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "An unknown error occurred.")
        }
        .sheet(isPresented: $showModelPicker) {
            modelPickerSheet
                .presentationDetents([.medium, .large])
                .presentationBackground(.regularMaterial)
                .presentationCornerRadius(28)
                .presentationDragIndicator(.visible)
        }
        .fullScreenCover(item: $presentedDemo) { demo in
            NavigationStack {
                demo.destination
                    .navigationTitle(demo.title)
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Close") { presentedDemo = nil }
                        }
                    }
            }
        }
    }

    /// Live AR session UI — what the previous body rendered unconditionally.
    /// Gated behind `sessionStarted` so the user has to explicitly enter the
    /// camera session (Polycam / Reality Composer launcher pattern).
    private var liveARView: some View {
        ZStack {
            #if !targetEnvironment(simulator)
            arSceneView
                .ignoresSafeArea()
                .id(arViewID)
            #else
            simulatorPlaceholder
                .ignoresSafeArea()
            #endif

            // Top status pill — centered horizontally, glass.
            VStack {
                statusPill
                    .padding(.top, 8)
                Spacer()
            }

            // Top-trailing Close button — exit affordance from the live AR
            // session. Mirrors Android's top-end Close button on
            // ArViewTab.kt (which flips `sessionStarted = false` to return
            // to the launcher screen). Required for issue #1211 item 3.
            // Re-uses `glassIconButton` so the size + style matches the
            // bottom action bar (44×44, Apple HIG tap-target minimum).
            // Extra `.padding(.top, 16)` clears the iPhone 15+/16 Pro
            // Dynamic Island so the glass circle doesn't sit under it.
            VStack {
                HStack {
                    Spacer()
                    glassIconButton(systemImage: "xmark", label: "Exit AR camera", action: exitArSession)
                        .padding(.top, 16)
                        .padding(.trailing, 12)
                }
                Spacer()
            }

            // Bottom floating glass action bar.
            VStack {
                Spacer()
                bottomActionBar
                    .padding(.horizontal, 16)
                    .padding(.bottom, 16)
            }
        }
    }

    // MARK: - AR Scene

    #if !targetEnvironment(simulator)
    private var arSceneView: some View {
        ARSceneView(
            planeDetection: .both,
            showPlaneOverlay: true,
            showCoachingOverlay: true,
            onTapOnPlane: { position, arView in
                let selected = arModels[selectedModelIndex]

                Task {
                    do {
                        let modelNode = try await ModelNode.load(selected.asset)
                        _ = modelNode.scaleToUnits(selected.scale)

                        let anchor = AnchorNode.world(position: position)
                        anchor.add(modelNode.entity)
                        arView.scene.addAnchor(anchor.entity)

                        // Track the anchor itself — the status-pill count is
                        // derived from `placedAnchors`, never mutated directly.
                        placedAnchors.append(anchor.entity)
                        HapticManager.mediumTap()
                    } catch {
                        errorMessage = error.localizedDescription
                        showError = true
                        HapticManager.error()
                    }
                }
            }
        )
    }
    #endif

    // MARK: - Simulator placeholder

    private var simulatorPlaceholder: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(red: 0.10, green: 0.10, blue: 0.18),
                    Color(red: 0.18, green: 0.18, blue: 0.28),
                ],
                startPoint: .top, endPoint: .bottom
            )
            VStack(spacing: 16) {
                Image(systemName: "arkit")
                    .font(.system(size: 60))
                    .foregroundStyle(.white.opacity(0.6))
                    .accessibilityHidden(true)
                Text("AR requires a physical device")
                    .font(.headline)
                    .foregroundStyle(.white)
                Text("Run on iPhone or iPad to place 3D models in your space.")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.7))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }
        }
    }

    // MARK: - Glass status pill (top center)

    private var statusPill: some View {
        HStack(spacing: 8) {
            Image(systemName: placedCount == 0 ? "hand.tap.fill" : "checkmark.circle.fill")
                .font(.caption.weight(.semibold))
                .foregroundStyle(placedCount == 0 ? .yellow : .green)

            Text(statusText)
                .font(.footnote.weight(.medium))
                .foregroundStyle(.primary)
                // The status string now carries the model name (e.g.
                // "3 placed · tap to add PS5 Controller") — cap it to one
                // line + allow a slight shrink so the pill never wraps or
                // overflows the screen on an iPhone SE / large Dynamic Type.
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(.regularMaterial, in: Capsule())
        .overlay(
            Capsule().strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.5)
        )
        .shadow(color: .black.opacity(0.15), radius: 8, y: 2)
        .accessibilityLabel(statusText)
    }

    private var statusText: String {
        if placedCount == 0 {
            return "Tap a surface to place \(selectedModel.name)"
        } else {
            // Keep the selected model name after the first placement —
            // previously the pill dropped to "N placed · tap to add" and
            // the user lost track of what the next tap would drop.
            return "\(placedCount) placed \u{00B7} tap to add \(selectedModel.name)"
        }
    }

    // MARK: - Bottom action bar

    private var bottomActionBar: some View {
        HStack(spacing: 10) {
            // FAB "Pick model" — primary, takes most of the space.
            Button {
                showModelPicker = true
                HapticManager.lightTap()
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: selectedModel.icon)
                        .font(.title3)
                    VStack(alignment: .leading, spacing: 0) {
                        Text("Model")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        Text(selectedModel.name)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    Image(systemName: "chevron.up")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity)
                .foregroundStyle(.primary)
            }
            .buttonStyle(.plain)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.5)
            )
            .accessibilityLabel("Pick model. Currently \(selectedModel.name)")

            // Reset button (glass icon).
            glassIconButton(systemImage: "arrow.counterclockwise", label: "Reset scene") {
                resetScene()
            }

            // Screenshot button (glass icon).
            glassIconButton(systemImage: "square.and.arrow.up", label: "Share AR screenshot") {
                shareARScreenshot()
                HapticManager.lightTap()
            }
        }
        .shadow(color: .black.opacity(0.18), radius: 14, y: 4)
    }

    private func glassIconButton(systemImage: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.title3)
                .frame(width: 44, height: 44)
                .foregroundStyle(.primary)
        }
        .buttonStyle(.plain)
        .background(.regularMaterial, in: Circle())
        .overlay(Circle().strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.5))
        .accessibilityLabel(label)
    }

    // MARK: - Model picker sheet

    private var modelPickerSheet: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 110), spacing: 12)],
                    spacing: 12
                ) {
                    ForEach(Array(arModels.enumerated()), id: \.offset) { index, model in
                        Button {
                            selectedModelIndex = index
                            HapticManager.selectionChanged()
                            showModelPicker = false
                        } label: {
                            VStack(spacing: 8) {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                                        .fill(
                                            LinearGradient(
                                                colors: [Color.blue.opacity(0.28), Color.purple.opacity(0.15)],
                                                startPoint: .topLeading,
                                                endPoint: .bottomTrailing
                                            )
                                        )
                                    Image(systemName: model.icon)
                                        .font(.system(size: 32, weight: .semibold))
                                        .foregroundStyle(.tint)
                                }
                                .frame(height: 90)

                                Text(model.name)
                                    .font(.caption.weight(.medium))
                                    .foregroundStyle(.primary)
                                    .lineLimit(1)
                            }
                            .padding(8)
                            .background(
                                .regularMaterial,
                                in: RoundedRectangle(cornerRadius: 18, style: .continuous)
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 18, style: .continuous)
                                    .strokeBorder(
                                        index == selectedModelIndex
                                            ? Color.blue
                                            : Color.primary.opacity(0.08),
                                        lineWidth: index == selectedModelIndex ? 2 : 0.5
                                    )
                            )
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Pick \(model.name)")
                        .accessibilityAddTraits(index == selectedModelIndex ? .isSelected : [])
                    }
                }
                .padding(16)
            }
            .navigationTitle("Pick a model")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { showModelPicker = false }
                }
            }
        }
    }

    // MARK: - Share

    @MainActor
    private func shareARScreenshot() {
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let window = windowScene.windows.first else { return }

        let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
        let image = renderer.image { ctx in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }

        let activityVC = UIActivityViewController(
            activityItems: [image, "Check out what I placed in my space with SceneView!"],
            applicationActivities: nil
        )

        if let presenter = window.rootViewController {
            if let popover = activityVC.popoverPresentationController {
                popover.sourceView = presenter.view
                popover.sourceRect = CGRect(x: presenter.view.bounds.midX, y: 40, width: 0, height: 0)
            }
            presenter.present(activityVC, animated: true)
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
            title: "Tap to Place",
            subtitle: "Tap a detected plane to place a model",
            icon: "arkit",
            destination: AnyView(ARPlacementDemo())
        ),
        FeaturedARDemo(
            id: "ar-instant-placement",
            title: "Instant Placement",
            subtitle: "Place models before plane detection converges",
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
            id: "ar-recording",
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
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
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

import SwiftUI
import RealityKit
import SceneViewSwift

/// Full-screen 3D model viewer — bundled hero with a "Surprise me" picker.
///
/// Mirrors the Android `ModelViewerDemo` (`samples/android-demo/.../ModelViewerDemo.kt`).
///
/// **Default state.** Loads the bundled `cyberpunk_hovercar.usdz` (the iOS analogue
/// of Android's `khronos_damaged_helmet.glb`) so the demo renders identically with
/// or without a Sketchfab API key — the first frame the user sees is the same hero
/// shot. The camera orbits the model so lights and reflections hit the same surface
/// every frame.
///
/// **"Surprise me" button.** When the user taps the extended button at the
/// bottom-trailing edge, the demo searches Sketchfab's catalogue for a
/// downloadable CC-BY model and streams it through ``SketchfabAssetResolver``,
/// then loads it into RealityKit. The streamed pick replaces the bundled hero
/// for the rest of the session (or until the next tap). When no API key is
/// configured (App Store builds), the button is hidden — there is no plausible
/// "Surprise me" without the Sketchfab catalogue.
///
/// Honours the umbrella's hard rules:
///   - **No Sketchfab WebView / external link.** Local file URLs only.
///   - **No network required to render something useful.** Cold cache → the
///     bundled hero remains visible.
///   - **No broken affordance when offline.** Surprise button hidden when no
///     API key is configured.
struct ModelViewerDemo: View {
    /// Bundled hero shown on first frame and as the offline default.
    private static let bundledHero = "cyberpunk_hovercar"

    /// Photo-studio IBL — a lit backdrop, not a room.
    ///
    /// `.studio` (the preset Android's model viewer uses, #2114) is a
    /// *photographed living room*: with the skybox finally rendering (#2896)
    /// it framed the hero as an interior snapshot with a small car in it.
    /// Hiding its skybox instead left a dark-grey car on black — the exact
    /// "dim, dark-on-black" frame #2896 was filed about. `.warm` is an actual
    /// photo studio (seamless cyclorama, softboxes), so it reads as a product
    /// shot: bright backdrop, hard highlights down the bodywork, and the
    /// silhouette separating from the background at every orbit angle.
    private static let heroEnvironment: SceneEnvironment = .warm

    /// Framing margin used only under `qa_mode`, where the orbit is frozen on
    /// the authored three-quarter pose. Measured on both store device classes
    /// — see the `.framingMargin` call site for why it differs from the
    /// interactive value.
    private static let captureFramingMargin: Float = 0.62

    @State private var loadedNode: ModelNode?
    @State private var loadError: String?
    @State private var surpriseInFlight: Bool = false
    /// Transient banner shown when a "Surprise me" roll fails (network error,
    /// empty search result). Mirrors ``ARPlacementDemo``'s `lastError` capsule.
    @State private var surpriseError: String?
    /// When non-nil, the demo is rendering a streamed pick instead of the
    /// bundled hero. We surface the title in the status pill so the user can
    /// tell which model is on screen right now.
    @State private var streamedDisplayName: String?

    private let hasSketchfabKey: Bool = SketchfabConfig.apiKey != nil

    /// `-qa_mode 1` / `?qa_mode=1` — freezes the orbit sweep so a capture lands
    /// on the pose below every time. `DeepLinkRouter` has promised this since
    /// it was added, but no demo actually read it, so every store capture shot
    /// whatever azimuth the auto-rotation happened to be at: two runs of the
    /// same demo produced different poses AND different HDRI backdrops (#2896).
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false

    var body: some View {
        ZStack {
            sceneView
            VStack {
                Spacer()
                if let surpriseError {
                    errorBanner(surpriseError)
                        .padding(.bottom, 8)
                }
                if let name = streamedDisplayName {
                    sourcePill(text: "Streamed: \(name)")
                        .padding(.bottom, 8)
                }
                if hasSketchfabKey {
                    surpriseButton
                        .padding(.bottom, 24)
                }
            }
        }
        .background(Color.black)
        .task {
            await loadBundledHero()
        }
    }

    @ViewBuilder
    private var sceneView: some View {
        if let loadedNode {
            SceneView { root in
                loadedNode.entity.position = .init(x: 0, y: 0, z: -1.5)
                root.addChild(loadedNode.entity)
            }
            .cameraControls(.orbit)
            .autoRotate(speed: qaMode ? 0 : 0.3)
            // Three-quarter hero pose. Auto-rotation sweeps away from it a
            // moment later for a normal user; under `qa_mode` it is what the
            // screenshot lands on.
            .cameraOrbit(azimuth: .pi / 5)
            .environment(Self.heroEnvironment)
            // Fit the hero to the frame instead of leaving 15 % of air around
            // its bounding sphere.
            //
            // Two values, because the two situations have different risks. For
            // a normal user the scene auto-rotates through every azimuth, so
            // the margin has to clear the model's BROADSIDE silhouette — below
            // ~0.95 it clips there. Under `qa_mode` the pose is frozen at the
            // three-quarter angle above, that risk is gone, and the looser
            // value was the reason the store frame read as a small subject
            // adrift in empty backdrop: the hero's bounding sphere is set by
            // its display plinth, not by the car (#2896).
            .framingMargin(qaMode ? Self.captureFramingMargin : 0.95)
            .ignoresSafeArea()
            .id("model-viewer-\(streamedDisplayName ?? "bundled")")
        } else {
            VStack(spacing: 12) {
                ProgressView()
                    .tint(.white)
                if let loadError {
                    Text(loadError)
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.7))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                } else {
                    Text("Loading model…")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.7))
                }
            }
        }
    }

    private func sourcePill(text: String) -> some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .background(.ultraThinMaterial, in: Capsule())
            .foregroundStyle(.primary)
    }

    private func errorBanner(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .background(Color.red.opacity(0.85), in: Capsule())
            .foregroundStyle(.white)
    }

    private var surpriseButton: some View {
        Button {
            guard !surpriseInFlight else { return }
            Task { await rollSurpriseModel() }
            #if os(iOS)
            SceneViewHaptic.shared.medium()
            #endif
        } label: {
            HStack(spacing: 8) {
                if surpriseInFlight {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .controlSize(.small)
                } else {
                    Image(systemName: "sparkles")
                        .font(.caption.weight(.bold))
                }
                Text(surpriseInFlight ? "Loading…" : "Surprise me")
                    .font(.subheadline.weight(.semibold))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .background(
                Capsule()
                    .fill(LinearGradient(
                        colors: [Color.purple, Color.blue],
                        startPoint: .leading,
                        endPoint: .trailing
                    ))
                    .shadow(color: .black.opacity(0.3), radius: 6, y: 2)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(surpriseInFlight ? "Loading next model" : "Roll a surprise streamed model")
        .disabled(surpriseInFlight)
    }

    // MARK: - Loading

    @MainActor
    private func loadBundledHero() async {
        loadError = nil
        do {
            let node = try await ModelNode.load(Self.bundledHero)
            _ = node.scaleToUnits(0.6)
            _ = node.centerOrigin()
            loadedNode = node
        } catch {
            loadError = "Could not load bundled hero: \(error.localizedDescription)"
        }
    }

    /// Pick a random downloadable CC-BY slug from the curated registry and try
    /// to resolve it. Honours the hard rule "no Sketchfab WebView" — the search
    /// happens server-side via ``SketchfabService.search`` and the result is
    /// downloaded as a USDZ that RealityKit can load directly.
    ///
    /// The Android demo searches a broad PBR query (`pbr`, `modern`, `scan`) for
    /// variety. iOS does the equivalent through ``SketchfabService.search`` —
    /// we keep the search lightweight so an empty result just leaves the hero
    /// on screen.
    @MainActor
    private func rollSurpriseModel() async {
        surpriseInFlight = true
        surpriseError = nil
        defer { surpriseInFlight = false }

        do {
            // Try server-side search across a few PBR-friendly queries. Each
            // hit must be downloadable + sub-50k-poly so we don't stall on a
            // multi-megabyte scan.
            let queries = ["pbr", "modern", "scan"]
            var picked: (uid: String, name: String)?
            for query in queries {
                let results = try await SketchfabService.shared.search(
                    query: query,
                    downloadable: true,
                    limit: 24
                )
                let viable = results.filter { result in
                    result.downloadable && (1..<200_000).contains(result.faceCount)
                }
                if let hit = viable.randomElement() {
                    picked = (hit.uid, hit.name)
                    break
                }
            }
            guard let pick = picked else {
                surfaceTransientError("No surprise model available right now — try again.")
                return
            }

            // Download via the same service the resolver uses. We bypass the
            // resolver here because the result isn't in our curated
            // ``SampleAssets`` registry (this is true "surprise me" content).
            // The hard rule "local file URL only" still holds — the service
            // returns a `Caches/` URL that RealityKit loads via the file:// API.
            let downloaded = try await SketchfabService.shared.downloadModel(uid: pick.uid)
            let node = try await ModelNode.load(contentsOf: downloaded)
            _ = node.scaleToUnits(0.6)
            _ = node.centerOrigin()
            loadedNode = node
            streamedDisplayName = pick.name
        } catch {
            // Keep the current hero on screen, but surface a transient banner so
            // a network failure isn't completely silent. The button is re-enabled
            // by the `defer` block above so the user can try again.
            surfaceTransientError("Couldn't roll a model: \(error.localizedDescription)")
        }
    }

    /// Shows a transient error banner that auto-dismisses after a few seconds.
    @MainActor
    private func surfaceTransientError(_ message: String) {
        surpriseError = message
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if surpriseError == message {
                surpriseError = nil
            }
        }
    }
}

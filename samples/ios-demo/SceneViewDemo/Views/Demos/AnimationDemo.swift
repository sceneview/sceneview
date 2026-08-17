import SwiftUI
import RealityKit
import SceneViewSwift

/// Animation showcase — carousel of 5 animated 3D models with playback controls.
///
/// Mirrors the Android `AnimationDemo` (`samples/android-demo/.../AnimationDemo.kt`)
/// scope — same five subjects (Soldier + four streamed entries from the `animation`
/// category of ``SampleAssets``), same play/pause + speed + loop chips. The Android
/// version layers four "cinematic" camera shots (Hero / Reveal / Vertigo / Tracking)
/// on top — those would require imperative camera control which is not yet exposed
/// on `SceneView` iOS (#1034 first-person + pan modes shipped but not the full
/// keyframed camera scripting). The iOS port honours the strict-subset rule from
/// `feedback_ios_mirror_android.md`: ship the controls that map cleanly to RealityKit
/// APIs available today (orbit camera + `auto-rotate`), surface the rest as
/// "Coming soon" inside the controls sheet so the user can see the roadmap.
///
/// ### Streaming pipeline (Stage 2, issue #1152)
///
/// Slot 0 ships the bundled `cyberpunk_character.usdz` as the historical hero — the
/// same role the threejs soldier plays on Android. Slots 1–4 stream the four
/// `animation` slugs from ``SampleAssets`` via ``SketchfabAssetResolver``. Empty
/// API key (App Store builds) → the resolver returns the registered bundled
/// fallback so the carousel always renders five subjects, no broken slots.
///
/// ### Honest-subset notes
///
/// - **Cinematic shots** (Hero / Reveal / Vertigo / Tracking) — not ported. The
///   Android version drives spherical camera coordinates from Compose
///   `Animatable` values; iOS would need a separate `RealityViewCameraControls`
///   surface (#1034 only ships the user-gesture modes). Tracked separately.
/// - **IBL slider** — not ported. iOS `SceneView` does not expose the IBL
///   intensity dial yet (RealityKit `EnvironmentResource` is a singleton input).
///   Auto-rotate is on so the lighting reads the same way on every frame.
struct AnimationDemo: View {
    /// Mirror of Android's `AnimationModel` private data class — exactly one of
    /// `bundledAsset` / `streamedSlug` is non-nil, with a scale hint chosen so all
    /// five carousel subjects read at similar on-screen size.
    private struct AnimationSubject {
        let displayName: String
        let streamedSlug: SketchfabSlug?
        let bundledAsset: String?
        let scale: Float

        init(
            displayName: String,
            streamedSlug: SketchfabSlug? = nil,
            bundledAsset: String? = nil,
            scale: Float
        ) {
            precondition(
                (streamedSlug == nil) != (bundledAsset == nil),
                "AnimationSubject must define exactly one of streamedSlug or bundledAsset."
            )
            self.displayName = displayName
            self.streamedSlug = streamedSlug
            self.bundledAsset = bundledAsset
            self.scale = scale
        }
    }

    private static let subjects: [AnimationSubject] = {
        let slugs = SampleAssets.byCategory["animation"] ?? []
        var items: [AnimationSubject] = [
            // Slot 0 — bundled cyberpunk_character.usdz (iOS analogue of the Android
            // `threejs_soldier.glb`). Keeps the carousel deterministic for store
            // screenshots with no Sketchfab key.
            AnimationSubject(
                displayName: "Soldier",
                bundledAsset: "cyberpunk_character",
                scale: 1.0
            ),
        ]
        for slug in slugs {
            items.append(
                AnimationSubject(
                    displayName: slug.displayName,
                    streamedSlug: slug,
                    scale: slug.scaleToUnits
                )
            )
        }
        return items
    }()

    @State private var selectedIndex: Int = 0
    @State private var isPlaying: Bool = true
    @State private var loop: Bool = true
    @State private var speed: Float = 1.0
    @State private var loadedNode: ModelNode?
    @State private var loadError: String?
    /// What the resolver handed back for a *streamed* subject (#2960).
    @State private var resolvedURL: URL?

    private let hasSketchfabKey: Bool = SketchfabConfig.apiKey != nil

    private var selectedSubject: AnimationSubject {
        Self.subjects[selectedIndex]
    }

    /// `nil` for slot 0: "Soldier" is loaded straight from the app bundle and
    /// labelled as itself, so it has no origin question to answer — showing a
    /// pill there would invent a substitution that never happened.
    private var assetSource: AssetSourceState? {
        guard selectedSubject.streamedSlug != nil else { return nil }
        return AssetSourceProbe.of(
            resolvedURL: resolvedURL,
            hasAPIKey: hasSketchfabKey,
            loaded: loadedNode != nil
        )
    }

    var body: some View {
        sceneContent
            .assetSourcePill(assetSource)
            .demoSettingsSheet {
                controlsSheet
            }
            .task {
                _ = await SketchfabAssetResolver.shared.prefetchAll(category: "animation")
            }
            .task(id: selectedIndex) {
                await loadSelectedSubject()
            }
            .task(id: PlaybackKey(isPlaying: isPlaying, loop: loop, speed: speed, index: selectedIndex)) {
                applyPlaybackState()
            }
    }

    @ViewBuilder
    private var sceneContent: some View {
        ZStack {
            // The scene stays mounted for the whole demo — it is never wrapped
            // in `if let loadedNode` and never re-keyed with SwiftUI's `.id(_:)`.
            // Both of those discard the `RealityView` and build a new one, and a
            // re-created `RealityView` on iOS 26 Simulator intermittently comes
            // back rendering nothing at all — no model, and no skybox either —
            // permanently (#3008). `.contentID(_:)` swaps the model inside the
            // scene that is already rendering instead.
            //
            // The key is an `Optional` on purpose: it must also change when the
            // model finishes loading, not only when the subject chip changes.
            // Keyed on the subject alone it would already sit at its final value
            // while `loadedNode` is still `nil`, and the scene would stay empty.
            SceneView { root in
                guard let loadedNode else { return }
                loadedNode.entity.position = .init(x: 0, y: 0, z: -2)
                root.addChild(loadedNode.entity)
            }
            .cameraControls(.orbit)
            .autoRotate(speed: 0.3)
            // Every subject here is a curated PBR model — the bundled
            // cyberpunk_character.usdz or a streamed Sketchfab character —
            // and a PBR surface is defined by what it reflects. With no
            // IBL there is nothing to reflect and the carousel undersells
            // its own subjects. Same `.studio` preset as ModelViewerDemo
            // (#2114).
            .environment(.studio)
            .contentID(loadedContentKey)
            .ignoresSafeArea()

            if loadedNode == nil {
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
                        Text("Loading \(selectedSubject.displayName)…")
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.7))
                    }
                }
            }
        }
        .background(Color.black)
    }

    /// What the scene's content closure currently builds: `nil` while the
    /// subject is loading, the subject's identifier once its model is in hand.
    /// Feeds ``SceneView/contentID(_:)`` — see the comment in `sceneContent`.
    private var loadedContentKey: String? {
        guard loadedNode != nil else { return nil }
        return selectedSubject.streamedSlug?.uid ?? selectedSubject.bundledAsset ?? "none"
    }

    @ViewBuilder
    private var controlsSheet: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Subject carousel — chips matching Android's "Subject" row.
            VStack(alignment: .leading, spacing: 8) {
                Text("Subject")
                    .font(.subheadline.weight(.semibold))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(Self.subjects.enumerated()), id: \.offset) { index, subject in
                            Button {
                                selectedIndex = index
                                #if os(iOS)
                                SceneViewHaptic.shared.selection()
                                #endif
                            } label: {
                                Text(subject.displayName)
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(index == selectedIndex ? Color.black : Color.primary)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(
                                        Capsule()
                                            .fill(index == selectedIndex ? AnyShapeStyle(.white) : AnyShapeStyle(.gray.opacity(0.18)))
                                    )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }

            // Playback row — pause / play icon + speed + loop chips.
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("Playback")
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                    Button {
                        isPlaying.toggle()
                        #if os(iOS)
                        SceneViewHaptic.shared.light()
                        #endif
                    } label: {
                        Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                            .font(.title3)
                            .padding(10)
                            .background(.gray.opacity(0.15), in: Circle())
                    }
                    .accessibilityLabel(isPlaying ? "Pause" : "Play")
                    .buttonStyle(.plain)
                }

                LabeledSlider(
                    label: "Speed",
                    value: $speed,
                    range: 0.25...3.0,
                    valueText: String(format: "%.1fx", speed)
                )

                HStack(spacing: 8) {
                    Button {
                        loop = true
                        #if os(iOS)
                        SceneViewHaptic.shared.selection()
                        #endif
                    } label: {
                        Text("Loop")
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(loop ? AnyShapeStyle(.blue) : AnyShapeStyle(.gray.opacity(0.15)), in: Capsule())
                            .foregroundStyle(loop ? .white : .primary)
                    }
                    .buttonStyle(.plain)

                    Button {
                        loop = false
                        #if os(iOS)
                        SceneViewHaptic.shared.selection()
                        #endif
                    } label: {
                        Text("Once")
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(!loop ? AnyShapeStyle(.blue) : AnyShapeStyle(.gray.opacity(0.15)), in: Capsule())
                            .foregroundStyle(!loop ? .white : .primary)
                    }
                    .buttonStyle(.plain)
                }
            }

            // Honest "Coming soon" surface — keep the iOS sheet honest about the
            // Hero / Reveal / Vertigo / Tracking shots that haven't been ported.
            Text("Cinematic camera shots (Hero / Reveal / Vertigo / Tracking) and the IBL intensity slider are Android-only in this release — coming to iOS in a future version.")
                .font(.caption2)
                .foregroundStyle(.secondary)

            if let slug = selectedSubject.streamedSlug {
                Text("by \(slug.author) · CC-BY 4.0")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Load + playback state

    /// Key bundling everything that should cause the playback effect to re-run.
    /// Hashable so `.task(id:)` can use it.
    private struct PlaybackKey: Hashable {
        let isPlaying: Bool
        let loop: Bool
        let speed: Float
        let index: Int
    }

    @MainActor
    private func loadSelectedSubject() async {
        let subject = selectedSubject
        loadedNode = nil
        loadError = nil
        resolvedURL = nil
        do {
            let node: ModelNode
            if let slug = subject.streamedSlug {
                let url = try await SketchfabAssetResolver.shared.resolve(slug)
                resolvedURL = url
                node = try await ModelNode.load(contentsOf: url)
            } else if let bundled = subject.bundledAsset {
                node = try await ModelNode.load(bundled)
            } else {
                return
            }
            _ = node.scaleToUnits(subject.scale)
            _ = node.centerOrigin()
            loadedNode = node
            applyPlaybackState()
        } catch {
            loadError = error.localizedDescription
        }
    }

    @MainActor
    private func applyPlaybackState() {
        guard let loadedNode else { return }
        // Stop everything first so the new (loop, speed) combo wins. The
        // `playAllAnimations` API drops every previously-tracked controller
        // implicitly via `entity.playAnimation`, but `stopAllAnimations` is
        // the safer reset.
        loadedNode.stopAllAnimations()
        guard isPlaying && loadedNode.animationCount > 0 else { return }
        loadedNode.playAllAnimations(loop: loop, speed: speed)
    }
}

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

    private var selectedSubject: AnimationSubject {
        Self.subjects[selectedIndex]
    }

    var body: some View {
        sceneContent
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
            if let loadedNode {
                SceneView { root in
                    loadedNode.entity.position = .init(x: 0, y: 0, z: -2)
                    root.addChild(loadedNode.entity)
                }
                .cameraControls(.orbit)
                .autoRotate(speed: 0.3)
                .ignoresSafeArea()
                .id("animation-\(selectedSubject.streamedSlug?.uid ?? selectedSubject.bundledAsset ?? "none")")
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
                        Text("Loading \(selectedSubject.displayName)…")
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.7))
                    }
                }
            }
        }
        .background(Color.black)
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

                HStack {
                    Text("Speed")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Slider(value: $speed, in: 0.25...3.0)
                        .tint(.blue)
                    Text(String(format: "%.1fx", speed))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .frame(width: 44, alignment: .trailing)
                }

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
        do {
            let node: ModelNode
            if let slug = subject.streamedSlug {
                let url = try await SketchfabAssetResolver.shared.resolve(slug)
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

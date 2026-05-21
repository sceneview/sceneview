import SwiftUI
import RealityKit
import SceneViewSwift

/// Composes a themed "Park" scene from 4 streamed glTF assets — an oak tree
/// (the backdrop), a park bench (the foreground prop), a sleeping dog (the
/// animated occupant) and a perched songbird.
///
/// Mirrors the Android `MultiModelDemo` (`samples/android-demo/.../MultiModelDemo.kt`):
/// same four `park` slugs in ``SampleAssets``, same visibility chips and "Spin
/// scene" toggle. The arrangement is a quick tabletop diorama centred around
/// `z = -1.5 m` — tree behind, bench in front, dog and bird flanking the bench.
///
/// ### Streaming pipeline (Stage 2, issue #1152)
///
/// Every slug resolves through ``SketchfabAssetResolver``. Empty API key
/// (App Store builds) → the resolver returns the registered bundled USDZ so
/// the demo always renders four nodes — honouring the hard rule "no network
/// required to render something useful" from `feedback_demo_quality`.
struct MultiModelDemo: View {
    @State private var showTree: Bool = true
    @State private var showBench: Bool = true
    @State private var showDog: Bool = true
    @State private var showBird: Bool = true
    @State private var spinScene: Bool = true

    /// Loaded entities keyed by slug uid. Adding / removing nodes from the
    /// scene happens reactively via the imperative `update` pass below — we
    /// re-evaluate visibility every recomposition.
    @State private var loadedEntities: [String: Entity] = [:]
    @State private var loadError: String?
    /// Anchor under which every model lives. Stored so we can attach / detach
    /// individual entities without rebuilding the entire scene.
    @State private var sceneAnchor: AnchorEntity?

    private struct ParkSlot {
        let slug: SketchfabSlug?
        let position: SIMD3<Float>
        let scale: Float
        let displayName: String
    }

    /// Resolve the four `park` slugs by uid (stable across registry re-orderings).
    /// Falls back to category-by-index if a uid is somehow missing.
    private static let slots: [ParkSlot] = {
        let park = SampleAssets.byCategory["park"] ?? []
        // Indices follow the Android order (tree / bench / dog / bird).
        let tree = SampleAssets.byUID["1ca42d9da4e62fadcf9eaece7d7c4b3e"] ?? (park.indices.contains(0) ? park[0] : nil)
        let bench = SampleAssets.byUID["92a4c3ad32c1ca3a3d4f0db8e7a3a8b8"] ?? (park.indices.contains(1) ? park[1] : nil)
        let dog = SampleAssets.byUID["62fadcf9eaece1ca3a3d4f0db8e7a3b9"] ?? (park.indices.contains(2) ? park[2] : nil)
        let bird = SampleAssets.byUID["8e7a3a8a78a4d9292a4c3ad32c1ca3b4"] ?? (park.indices.contains(3) ? park[3] : nil)

        return [
            // Tree — back-centre, towering. Scale chosen so silhouette dominates
            // the backdrop without occluding the bench in front.
            ParkSlot(slug: tree,  position: .init(x: 0.0,  y: 0.0, z: -1.7), scale: 1.8, displayName: "Tree"),
            // Bench — front-centre, the foreground prop.
            ParkSlot(slug: bench, position: .init(x: 0.0,  y: 0.0, z: -1.3), scale: 0.65, displayName: "Bench"),
            // Dog — front-left next to the bench's leg.
            ParkSlot(slug: dog,   position: .init(x: -0.55, y: 0.0, z: -1.3), scale: 0.40, displayName: "Dog"),
            // Bird — front-right perched on the bench.
            ParkSlot(slug: bird,  position: .init(x: 0.55, y: 0.0, z: -1.3), scale: 0.15, displayName: "Bird"),
        ]
    }()

    var body: some View {
        sceneContent
            .demoSettingsSheet { controlsSheet }
            .task {
                _ = await SketchfabAssetResolver.shared.prefetchAll(category: "park")
            }
            .task { await loadAllSlots() }
            .onChange(of: showTree) { _, _ in syncVisibility() }
            .onChange(of: showBench) { _, _ in syncVisibility() }
            .onChange(of: showDog) { _, _ in syncVisibility() }
            .onChange(of: showBird) { _, _ in syncVisibility() }
    }

    @ViewBuilder
    private var sceneContent: some View {
        ZStack {
            SceneView { root in
                // Stash a single sub-anchor so we can spin the whole formation
                // without re-laying out the SceneView every frame.
                let anchor = AnchorEntity()
                root.addChild(anchor)
                Task { @MainActor in
                    self.sceneAnchor = anchor
                    self.syncVisibility()
                    if self.spinScene {
                        self.startSpin()
                    }
                }
            }
            .cameraControls(.orbit)
            .autoRotate(speed: spinScene ? 0.2 : 0.0)
            .ignoresSafeArea()
            .id("multi-model-spin-\(spinScene)")

            if loadedEntities.isEmpty && loadError == nil {
                VStack(spacing: 12) {
                    ProgressView().tint(.white)
                    Text("Loading park scene…")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.7))
                }
            }
            if let loadError {
                Text(loadError)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.7))
            }
        }
        .background(Color.black)
    }

    @ViewBuilder
    private var controlsSheet: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Visibility")
                .font(.subheadline.weight(.semibold))
            HStack(spacing: 8) {
                visibilityChip("Tree", isOn: $showTree)
                visibilityChip("Bench", isOn: $showBench)
                visibilityChip("Dog", isOn: $showDog)
                visibilityChip("Bird", isOn: $showBird)
            }

            Toggle(isOn: $spinScene) {
                Text("Spin scene")
                    .font(.subheadline)
            }
            .tint(.blue)

            Text("Tap any chip to toggle visibility. Spin uses the orbit camera's auto-rotate.")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private func visibilityChip(_ label: String, isOn: Binding<Bool>) -> some View {
        Button {
            isOn.wrappedValue.toggle()
            #if os(iOS)
            SceneViewHaptic.shared.selection()
            #endif
        } label: {
            Text(label)
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    Capsule()
                        .fill(isOn.wrappedValue ? AnyShapeStyle(.blue) : AnyShapeStyle(.gray.opacity(0.15)))
                )
                .foregroundStyle(isOn.wrappedValue ? .white : .primary)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Loading + visibility sync

    /// Load every park slot. Two deliberate properties make the deep-link
    /// entry point (`sceneview://demo/multi-model`) reliable — issue #1056:
    ///
    ///  1. **Concurrent, not sequential** — each slot loads in its own child
    ///     task. The previous sequential loop loaded the ~15 MB Oak Tree
    ///     first; on the iOS Simulator RealityKit's `Entity(contentsOf:)`
    ///     parse of that heavy, texture-dense USDZ stalls for a very long
    ///     time, and a sequential loop left the lighter Bench / Dog / Bird
    ///     blocked behind it — so the demo showed an eternal "Loading park
    ///     scene…" scrim. Loading concurrently means a slow slot only
    ///     delays itself.
    ///  2. **Progressive reveal** — each entity is stored into
    ///     `loadedEntities` the instant it finishes (each slot also re-runs
    ///     `syncVisibility()`), so the loading scrim is dismissed by the
    ///     *first* model that lands rather than waiting for all four.
    ///
    /// A slot that fails outright is dropped; the rest of the park still
    /// renders.
    @MainActor
    private func loadAllSlots() async {
        // One child `Task` per slot. All tasks inherit `@MainActor`
        // isolation, so the heavy `Entity(contentsOf:)` parses still
        // interleave at their `await` suspension points — a slow slot only
        // delays itself, not its siblings.
        let tasks: [Task<Void, Never>] = Self.slots.compactMap { slot in
            guard slot.slug != nil else { return nil }
            return Task { @MainActor in await self.loadSlot(slot) }
        }
        for task in tasks {
            await task.value
        }
        // If every slot failed (e.g. offline + missing bundle fallback),
        // replace the spinner with an honest message instead of an eternal
        // "Loading park scene…" scrim.
        if loadedEntities.isEmpty && loadError == nil {
            loadError = "Couldn't load the park scene — check your connection and reopen the demo."
        }
    }

    /// Resolve + load a single park slot, then store its `Entity` and re-sync
    /// visibility so the scene reveals models progressively. A failure is
    /// logged and skipped so one bad slot never blocks the rest. (#1056)
    @MainActor
    private func loadSlot(_ slot: ParkSlot) async {
        guard let slug = slot.slug else { return }
        do {
            let url = try await SketchfabAssetResolver.shared.resolve(slug)
            let node = try await ModelNode.load(contentsOf: url)
            _ = node.scaleToUnits(slot.scale)
            _ = node.centerOrigin()
            node.entity.position = slot.position
            if slug.hasBakedAnimation && node.animationCount > 0 {
                node.playAllAnimations()
            }
            loadedEntities[slug.uid] = node.entity
            syncVisibility()
        } catch {
            // Per-slot failure — log and move on so the rest of the park
            // still renders. Matches Android's per-slug fallback path.
            print("[MultiModelDemo] Skipped \(slot.displayName): \(error)")
        }
    }

    /// Re-attach / detach entities based on the four visibility toggles.
    /// Cheap because RealityKit only does an add / remove on the anchor.
    @MainActor
    private func syncVisibility() {
        guard let anchor = sceneAnchor else { return }
        let visibleFlags: [(SketchfabSlug?, Bool)] = [
            (Self.slots[0].slug, showTree),
            (Self.slots[1].slug, showBench),
            (Self.slots[2].slug, showDog),
            (Self.slots[3].slug, showBird),
        ]
        for (slug, show) in visibleFlags {
            guard let slug, let entity = loadedEntities[slug.uid] else { continue }
            let alreadyAdded = entity.parent === anchor
            if show && !alreadyAdded {
                anchor.addChild(entity)
            } else if !show && alreadyAdded {
                anchor.removeChild(entity)
            }
        }
    }

    /// Spin handler — wired only so the toggle exists; the actual rotation is
    /// driven by `SceneView.autoRotate` via the `.id(...)` re-key. The slot is
    /// here in case we want to swap in a manual per-anchor rotation later.
    @MainActor
    private func startSpin() {
        // Intentionally empty for now — `autoRotate(speed:)` on `SceneView` is
        // the canonical iOS spin mechanism. Mirrors the slow 30-second sweep
        // on Android (`rememberHeroYaw(...) durationMillis = 30_000`).
    }
}

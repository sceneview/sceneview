import SwiftUI
import RealityKit
import SceneViewSwift

/// Composes a themed "Park" scene from the 4 glTF assets in ``SampleAssets``'
/// `park` category: one hero at the back of the formation and three smaller
/// ones in a front row.
///
/// Mirrors the Android Multi-Model section (`samples/android-demo/.../ModelViewerDemo.kt`):
/// same four `park` slugs, same visibility chips and "Spin scene" toggle. The
/// arrangement is a quick tabletop diorama centred around `z = -1.5 m`.
///
/// The layout is positional and fixed; WHICH model stands in each slot is the
/// registry's call. Nothing here names a species: each chip reads its label off
/// the resolved ``SketchfabSlug/displayName`` — the same curated-English source
/// the Gallery chips use — falling back to a positional "Model N" only while a
/// slot has no slug. The labels used to be hardcoded "Tree" / "Bench" / "Dog" /
/// "Bird" from a composition the registry stopped holding: four oaks named after
/// a bench and a dog, with no bench and no dog on screen (#2933).
///
/// ### Streaming pipeline (Stage 2, issue #1152)
///
/// Every slug resolves through ``SketchfabAssetResolver``. Empty API key
/// (App Store builds) → the resolver returns the registered bundled USDZ so
/// the demo always renders four nodes — honouring the hard rule "no network
/// required to render something useful" from `feedback_demo_quality`.
///
/// > Important: the chip names the CATALOGUE ENTRY, not the geometry. On a
/// > keyless build a slot still reads "Oak Trees" over its bundled stand-in.
/// > Android surfaces that with the scaffold's asset-source pill; iOS has no
/// > equivalent chrome yet, so a keyless build here is silent about the swap.
struct MultiModelDemo: View {
    /// One flag per SLOT, not per species — index `i` pairs with `Self.slots[i]`.
    @State private var visible: [Bool] = Array(repeating: true, count: MultiModelDemo.slots.count)
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
        /// Zero-based place in the formation, used for the positional fallback label.
        let index: Int

        /// Chip label, and the name used when logging a failed slot.
        ///
        /// Read off the resolved slug so it always names the registry entry the
        /// slot actually loaded. The positional fallback only fires when the
        /// registry has no slug for this slot — a chip with no model behind it
        /// still needs a stable, non-lying handle.
        var displayName: String { slug?.displayName ?? "Model \(index + 1)" }
    }

    /// The four `park` slots, back row first. Order matches the visibility chips.
    ///
    /// Each slot carries the uid it loads, so the layout, the loader and the chip
    /// label are all indexed by one thing — a slot can never end up labelled with
    /// another slot's model. Slugs are resolved by uid (stable across registry
    /// re-orderings), falling back to category-by-index if a uid is somehow missing.
    private static let slots: [ParkSlot] = {
        let park = SampleAssets.byCategory["park"] ?? []
        // Layout only — where a model stands and how big it is drawn. What stands
        // there is whatever `uid` resolves to in the registry.
        let layout: [(uid: String, position: SIMD3<Float>, scale: Float)] = [
            // Back-centre, towering. Scale chosen so the hero's silhouette dominates
            // the backdrop without occluding the front row.
            ("d841c3bcc5324daebee50f45619e05fc", .init(x: 0.0,  y: 0.0, z: -1.7), 1.8),
            // Front-centre.
            ("6d1aeea748f147789004bc03e1930d32", .init(x: 0.0,  y: 0.0, z: -1.3), 0.65),
            // Front-left.
            ("4f6ab5594a8a415aba3f958682b9ced5", .init(x: -0.55, y: 0.0, z: -1.3), 0.40),
            // Front-right.
            ("fd582b0d4a8c4af1a1b5c4f21a481c93", .init(x: 0.55, y: 0.0, z: -1.3), 0.15),
        ]
        return layout.enumerated().map { index, entry in
            ParkSlot(
                slug: SampleAssets.byUID[entry.uid] ?? (park.indices.contains(index) ? park[index] : nil),
                position: entry.position,
                scale: entry.scale,
                index: index
            )
        }
    }()

    var body: some View {
        sceneContent
            .demoSettingsSheet { controlsSheet }
            .task {
                _ = await SketchfabAssetResolver.shared.prefetchAll(category: "park")
            }
            .task { await loadAllSlots() }
            .onChange(of: visible) { _, _ in syncVisibility() }
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
            // The formation is built from curated PBR models; without an IBL
            // their metallic/rough response has nothing to reflect and the whole
            // scene reads as flat silhouettes. Same preset as ModelViewerDemo
            // (#2114) so a model looks identical whether it is shown alone or
            // as part of this multi-model scene.
            .environment(.studio)
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
            // Horizontally scrolling for the same reason the Gallery chips are:
            // catalogue names run long ("Skovfogedegen Oak") and four of them do
            // not fit an iPhone's sheet width without truncating.
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(Self.slots.enumerated()), id: \.offset) { index, slot in
                        visibilityChip(slot.displayName, isOn: $visible[index])
                    }
                }
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
    ///     task. The previous sequential loop loaded the ~15 MB hero asset
    ///     first; on the iOS Simulator RealityKit's `Entity(contentsOf:)`
    ///     parse of that heavy, texture-dense USDZ stalls for a very long
    ///     time, and a sequential loop left the three lighter slots blocked
    ///     behind it — so the demo showed an eternal "Loading park
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
        for slot in Self.slots {
            guard let slug = slot.slug, let entity = loadedEntities[slug.uid] else { continue }
            // `visible` is sized from `slots` at init and never resized, so this guard is
            // unreachable today. It stays because a Swift out-of-bounds subscript TRAPS:
            // "fail loudly" here would mean crashing a shipped App Store demo, which is a
            // worse outcome than a chip that quietly stops toggling. Android's
            // `instances[index]` does throw instead — the asymmetry is a deliberate call
            // about who pays for a future desync, not an oversight (#2933).
            let show = visible.indices.contains(slot.index) ? visible[slot.index] : true
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

#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import SceneViewSwift

/// Interactive AR tap-to-place demo with a "Pick what to place" picker.
///
/// Mirrors the Android `ARPlacementDemo` (`samples/android-demo/.../ARPlacementDemo.kt`):
/// detect a horizontal plane, tap it to drop a model on the surface. A streamed
/// chip carousel (`ar_placement` category in ``SampleAssets``) lets the user
/// choose what gets placed; the default "Bundled cycle" chip rotates through
/// five bundled USDZ models for deterministic offline behaviour.
///
/// ### Streaming pipeline (Stage 2, issue #1152)
///
/// - **Picker chips** → the `ar_placement` slugs (coffee mug / houseplant /
///   crate / side table / floor lamp / picture frame). Tap a chip to arm it
///   as the next tap's payload. When no Sketchfab key is configured the
///   resolver falls back to bundled USDZ assets so the picker still works.
/// - **Bundled cycle** → preserves the v4.3.x default behaviour: each tap drops
///   the next of five bundled models in rotation. Deterministic, no network.
///
/// ### Honest-subset notes vs Android
///
/// - ARCore exposes per-Anchor pose updates as the session learns more about
///   the plane; ARKit's `AnchorEntity(world:)` snapshots the pose at creation.
///   The placed model therefore rides RealityKit's anchor coords without an
///   explicit refinement step — close enough for the demo's "drop and move on"
///   UX. A future iOS-only refinement pass could swap in
///   `AnchorEntity(.plane(...)` to match ARCore's running pose updates.
/// - The Android version's pinch-to-scale / drag / twist editing on placed
///   models is wired via `ModelNode.isEditable` on Filament. iOS RealityKit
///   has `EntityGestures` but `SceneViewSwift` doesn't yet expose them at
///   the iOS demo level — placed models are static once dropped. Tracked
///   separately if requested.
/// Tracks the in-flight placement loads of an AR placement screen.
///
/// A tap starts an async model load; "Clear all", a reset or leaving the
/// screen has to make sure the model that load is still fetching never lands
/// on the plane afterwards. Cancelling is not enough on its own — a load can
/// already be past its last suspension point — so every placement also carries
/// the generation it was started in, and attaches only if that generation is
/// still current.
@MainActor
final class PlacementTaskTracker {
    private(set) var generation = 0
    private var tasks: [UUID: Task<Void, Never>] = [:]

    /// Runs `body` with the generation current at the time of the call, and
    /// keeps a handle on it until it finishes.
    func run(_ body: @escaping (Int) async -> Void) {
        let id = UUID()
        let startedAt = generation
        tasks[id] = Task { @MainActor [weak self] in
            await body(startedAt)
            self?.tasks[id] = nil
        }
    }

    /// True while `startedAt` is still the generation the screen is showing.
    func isCurrent(_ startedAt: Int) -> Bool {
        startedAt == generation && !Task.isCancelled
    }

    /// Cancels every in-flight placement and retires their generation.
    func invalidate() {
        generation += 1
        for task in tasks.values { task.cancel() }
        tasks.removeAll()
    }
}

struct ARPlacementDemo: View {
    /// Bundle name (without `.usdz`) of the model every tap places when set —
    /// the Model Viewer's "View in AR" handoff, mirroring Android's
    /// `demo/ar-placement?model=` route. `nil` keeps the bundled cycle.
    var initialModel: String? = nil

    /// A file URL to place instead of a bundled asset — the "Open with" handoff from
    /// ``OpenedFileViewer``.
    ///
    /// Placed at its **real-world size**, not normalised to the bundled cycle's 0.3 m.
    /// That is the entire point of opening a print or a scan in AR: a 21 cm part has to
    /// stand 21 cm tall on the floor, or the answer to "will it fit?" is a guess.
    var initialModelURL: URL? = nil

    /// The unit the user picked in the viewer for a format that carries none (STL, OBJ,
    /// PLY). `nil` uses the format's default.
    var initialModelUnit: ModelUnit? = nil

    /// Bundled cycle preserved from the previous iOS AR demos — gives a
    /// deterministic 5-model rotation when no Sketchfab key is configured.
    /// Each entry is the bundle name without `.usdz`.
    private static let bundledCycle: [(name: String, displayName: String)] = [
        ("cyberpunk_hovercar", "Cyberpunk Hovercar"),
        ("phoenix_bird", "Phoenix Bird"),
        ("retro_piano", "Retro Piano"),
        ("game_boy_classic", "Game Boy"),
        ("animated_butterfly", "Butterfly"),
    ]

    @State private var cycleIndex: Int = 0
    /// `nil` → bundled cycle mode. Non-nil → the user picked a streamed slug.
    @State private var selectedSlug: SketchfabSlug?
    /// Resolved file URL for the currently-armed streamed slug. `nil` while
    /// the resolver is still downloading / staging the bundled fallback.
    @State private var armedURL: URL?
    /// Lost-anchor / placement errors surfaced as a transient banner.
    @State private var lastError: String?
    /// Anchors placed by tapping. Retained so "Clear all placed models" can
    /// tear them down — `placedCount` is always derived from this collection
    /// so the two never drift apart.
    @State private var placedAnchors: [AnchorEntity] = []
    /// Weak handle on the live `ARView`, captured from the tap callback, so the
    /// clear-all control can remove anchors from `arView.scene`.
    @State private var arViewRef: ARViewBox = ARViewBox()
    @State private var placements = PlacementTaskTracker()

    /// Reference box for the non-`Sendable`/non-`Equatable` `ARView` so it can
    /// live in SwiftUI `@State` without triggering view-identity churn.
    private final class ARViewBox {
        weak var value: ARView?
    }

    private var placedCount: Int { placedAnchors.count }

    private let placementSlugs: [SketchfabSlug] = SampleAssets.byCategory["ar_placement"] ?? []

    private let hasSketchfabKey: Bool = SketchfabConfig.apiKey != nil

    /// `nil` in bundled-cycle mode: those five models load by name and the
    /// caption names each one for what it is, so nothing was substituted.
    /// With a slug armed, the caption claims "Next tap places: <slug>" — the
    /// pill is what keeps that claim honest when the resolver quietly handed
    /// back the offline stand-in (#2960).
    private var assetSource: AssetSourceState? {
        guard selectedSlug != nil else { return nil }
        return AssetSourceProbe.of(
            resolvedURL: armedURL,
            hasAPIKey: hasSketchfabKey,
            // "Loaded" is "the file arrived": a tap places whatever has resolved.
            loaded: armedURL != nil
        )
    }

    var body: some View {
        ZStack {
            #if !targetEnvironment(simulator)
            arScene
                .ignoresSafeArea()
            #else
            simulatorPlaceholder
            #endif

            VStack(spacing: 8) {
                statusPill
                // Stacked under the status pill rather than pinned to the
                // corner: both sit at the top and a trailing overlay collides
                // with the centred "N models placed" text on a narrow device.
                if let assetSource {
                    HStack {
                        Spacer()
                        AssetSourcePill(state: assetSource,
                                        isPlaceholder: selectedSlug?.fallbackRole == .placeholder)
                    }
                    .padding(.horizontal, 16)
                }
                Spacer()
                if let lastError {
                    errorBanner(lastError)
                        .padding(.bottom, 8)
                }
            }
        }
        // `.ar`: the stage is the camera feed, so the chrome grounds itself
        // per control instead of dimming the frame with scrim bands.
        .demoChrome(chromeMode: .ar) { controlsSheet }
        .task {
            _ = await SketchfabAssetResolver.shared.prefetchAll(category: "ar_placement")
        }
        .task(id: selectedSlug?.uid) {
            await resolveSelectedSlug()
        }
        .onDisappear {
            // Leaving the screen retires every in-flight load: without this a
            // model can still be attached to a scene the user has left.
            placements.invalidate()
        }
    }

    // MARK: - AR scene

    #if !targetEnvironment(simulator)
    private var arScene: some View {
        ARSceneView(
            planeDetection: .horizontal,
            showPlaneOverlay: true,
            showCoachingOverlay: true,
            onTapOnPlane: { worldPosition, arView in
                placements.run { generation in
                    await placeModel(at: worldPosition, in: arView, generation: generation)
                }
            }
        )
    }
    #endif

    // MARK: - Placement

    @MainActor
    private func placeModel(at worldPosition: SIMD3<Float>, in arView: ARView, generation: Int) async {
        do {
            let url: URL
            let displayName: String
            let scaleToUnits: Float
            if let slug = selectedSlug, let resolved = armedURL {
                url = resolved
                displayName = slug.displayName
                scaleToUnits = slug.scaleToUnits
            } else if let opened = initialModelURL {
                // An opened file stands at the size its unit says. No `scaleToUnits`:
                // shrinking it to a tidy 0.3 m would answer a different question than
                // the one the user opened it to ask.
                let node = try await ModelNode.load(contentsOf: opened, unit: initialModelUnit)
                guard placements.isCurrent(generation) else { return }
                // Bottom-aligned so the model sits ON the detected plane rather than
                // straddling it — `-1` on Y selects the bounding box's floor.
                _ = node.centerOrigin(normalized: SIMD3<Float>(0, -1, 0))
                _ = node.withGroundingShadow()
                let anchor = AnchorNode.world(position: worldPosition)
                anchor.add(node.entity)
                arView.scene.addAnchor(anchor.entity)
                arViewRef.value = arView
                placedAnchors.append(anchor.entity)
                #if os(iOS)
                SceneViewHaptic.shared.light()
                #endif
                return
            } else {
                // The handed-off viewer model, else the bundled round-robin cycle.
                let assetName: String
                if let initialModel {
                    assetName = initialModel
                } else {
                    assetName = Self.bundledCycle[cycleIndex % Self.bundledCycle.count].name
                    cycleIndex += 1
                }
                let node = try await ModelNode.load(assetName)
                guard placements.isCurrent(generation) else { return }
                _ = node.scaleToUnits(0.3)
                // Bottom-aligned AFTER scaling and BEFORE anchoring: the anchor
                // sits ON the detected surface, so a centred origin buries the
                // lower half of the model under the floor. `-1` on Y selects the
                // bounding box's floor.
                _ = node.centerOrigin(normalized: SIMD3<Float>(0, -1, 0))
                // Applied where the model is actually attached: the SDK's own
                // `groundingShadows` pass only covers anchors added
                // synchronously inside `onTapOnPlane`, and this load is async.
                _ = node.withGroundingShadow()
                let anchor = AnchorNode.world(position: worldPosition)
                anchor.add(node.entity)
                arView.scene.addAnchor(anchor.entity)
                arViewRef.value = arView
                placedAnchors.append(anchor.entity)
                #if os(iOS)
                SceneViewHaptic.shared.light()
                #endif
                return
            }
            let node = try await ModelNode.load(contentsOf: url)
            guard placements.isCurrent(generation) else { return }
            // The slug's real-world size hint is the point of the picker: a
            // coffee mug at 0.10 m and a floor lamp at 1.55 m, not both at the
            // bundled cycle's 0.3 m (#2966). Applies to the fallback too — it
            // stands in at the size the label claims.
            _ = node.scaleToUnits(scaleToUnits)
            // Bottom-aligned after scaling, before anchoring, as above.
            _ = node.centerOrigin(normalized: SIMD3<Float>(0, -1, 0))
            _ = node.withGroundingShadow()
            let anchor = AnchorNode.world(position: worldPosition)
            anchor.add(node.entity)
            arView.scene.addAnchor(anchor.entity)
            arViewRef.value = arView
            placedAnchors.append(anchor.entity)
            #if os(iOS)
            SceneViewHaptic.shared.light()
            #endif
            _ = displayName
        } catch is CancellationError {
            // The screen moved on — nothing to report.
        } catch {
            guard placements.isCurrent(generation) else { return }
            lastError = "Could not place model: \(error.localizedDescription)"
        }
    }

    /// Removes every placed anchor from the AR scene and resets the count.
    /// Safe to call when nothing is placed — the loop simply does nothing.
    @MainActor
    private func clearAllPlacedModels() {
        // Before removing anything: a load still in flight would otherwise
        // attach its model a second after the user cleared the scene.
        placements.invalidate()
        if let arView = arViewRef.value {
            for anchor in placedAnchors {
                arView.scene.removeAnchor(anchor)
            }
        }
        placedAnchors.removeAll()
        #if os(iOS)
        SceneViewHaptic.shared.medium()
        #endif
    }

    // MARK: - Controls sheet

    @ViewBuilder
    private var controlsSheet: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Tap a detected plane to drop a model. Each tap places a new instance.")
                .font(.caption)
                .foregroundStyle(.secondary)

            Text("Pick what to place")
                .font(.subheadline.weight(.semibold))

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    // Bundled cycle chip
                    pickerChip(
                        label: "Bundled cycle",
                        isSelected: selectedSlug == nil,
                        action: { selectedSlug = nil }
                    )
                    ForEach(placementSlugs, id: \.uid) { slug in
                        pickerChip(
                            label: slug.displayName,
                            isSelected: selectedSlug?.uid == slug.uid,
                            action: { selectedSlug = slug }
                        )
                    }
                }
            }

            if let slug = selectedSlug {
                if armedURL == nil {
                    Text("Streaming \(slug.displayName)…")
                        .font(.caption2)
                        .foregroundStyle(.orange)
                } else {
                    Text("Next tap places: \(slug.displayName)")
                        .font(.caption2)
                        .foregroundStyle(.green)
                }
                // Credits whatever is actually on screen — the fallback's own
                // author and licence in keyless mode, never the streamed one
                // next to a bundled stand-in (#2966).
                AssetCreditLine(slug: slug, source: assetSource ?? .streaming)
            } else {
                Text("Next tap places: \(Self.bundledCycle[cycleIndex % Self.bundledCycle.count].displayName)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Button(role: .destructive) {
                clearAllPlacedModels()
            } label: {
                Label("Clear all placed models", systemImage: "trash")
                    .font(.subheadline.weight(.semibold))
            }
            .disabled(placedCount == 0)
        }
    }

    private func pickerChip(label: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button {
            action()
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
                        .fill(isSelected ? AnyShapeStyle(.blue) : AnyShapeStyle(.gray.opacity(0.18)))
                )
                .foregroundStyle(isSelected ? .white : .primary)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Status overlays

    private var statusPill: some View {
        Text(placedCount == 1 ? "1 model placed" : "\(placedCount) models placed")
            .font(.caption.weight(.medium))
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .background(.ultraThinMaterial, in: Capsule())
            .padding(.top, 8)
    }

    private func errorBanner(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .background(Color.red.opacity(0.85), in: Capsule())
            .foregroundStyle(.white)
    }

    // MARK: - Simulator placeholder

    private var simulatorPlaceholder: some View {
        ARUnavailableStage(icon: "viewfinder", message: "Run on iPhone or iPad to scan a plane and tap to place models.")
    }

    // MARK: - Slug resolve

    @MainActor
    private func resolveSelectedSlug() async {
        guard let slug = selectedSlug else {
            armedURL = nil
            return
        }
        armedURL = nil
        do {
            let resolved = try await SketchfabAssetResolver.shared.resolve(slug)
            // The selection may have moved on while this was resolving; arming
            // then would place the model the user just deselected.
            guard !Task.isCancelled, selectedSlug?.uid == slug.uid else { return }
            armedURL = resolved
        } catch is CancellationError {
            // Superseded by a newer selection — leave that one's state alone.
        } catch {
            // Silently fall back to bundled cycle for the next tap. The chip
            // stays selected; the user can re-tap to retry once the network is
            // available.
            guard selectedSlug?.uid == slug.uid else { return }
            armedURL = nil
        }
    }
}
#endif

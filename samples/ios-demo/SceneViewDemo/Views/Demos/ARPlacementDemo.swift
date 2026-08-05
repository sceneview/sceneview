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
struct ARPlacementDemo: View {
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
                        AssetSourcePill(state: assetSource)
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
        .demoSettingsSheet { controlsSheet }
        .task {
            _ = await SketchfabAssetResolver.shared.prefetchAll(category: "ar_placement")
        }
        .task(id: selectedSlug?.uid) {
            await resolveSelectedSlug()
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
                Task { @MainActor in
                    await placeModel(at: worldPosition, in: arView)
                }
            }
        )
    }
    #endif

    // MARK: - Placement

    @MainActor
    private func placeModel(at worldPosition: SIMD3<Float>, in arView: ARView) async {
        do {
            let url: URL
            let displayName: String
            if let slug = selectedSlug, let resolved = armedURL {
                url = resolved
                displayName = slug.displayName
            } else {
                // Bundled cycle — round-robin through the five bundled entries.
                let entry = Self.bundledCycle[cycleIndex % Self.bundledCycle.count]
                cycleIndex += 1
                let node = try await ModelNode.load(entry.name)
                _ = node.scaleToUnits(0.3)
                _ = node.centerOrigin()
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
            _ = node.scaleToUnits(0.3)
            _ = node.centerOrigin()
            let anchor = AnchorNode.world(position: worldPosition)
            anchor.add(node.entity)
            arView.scene.addAnchor(anchor.entity)
            arViewRef.value = arView
            placedAnchors.append(anchor.entity)
            #if os(iOS)
            SceneViewHaptic.shared.light()
            #endif
            _ = displayName
        } catch {
            lastError = "Could not place model: \(error.localizedDescription)"
        }
    }

    /// Removes every placed anchor from the AR scene and resets the count.
    /// Safe to call when nothing is placed — the loop simply does nothing.
    @MainActor
    private func clearAllPlacedModels() {
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
                Text("by \(slug.author) · CC-BY 4.0")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
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
        VStack(spacing: 16) {
            Image(systemName: "viewfinder")
                .font(.system(size: 60))
                .foregroundStyle(.secondary)
            Text("AR requires a physical device")
                .font(.headline)
            Text("Run on iPhone or iPad to scan a plane and tap to place models.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
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
            armedURL = try await SketchfabAssetResolver.shared.resolve(slug)
        } catch {
            // Silently fall back to bundled cycle for the next tap. The chip
            // stays selected; the user can re-tap to retry once the network is
            // available.
            armedURL = nil
        }
    }
}
#endif

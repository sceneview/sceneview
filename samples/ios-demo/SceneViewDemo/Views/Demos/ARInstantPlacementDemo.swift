#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import SceneViewSwift

/// Instant Placement demo — tap to place a model before plane detection has fully
/// converged.
///
/// Mirrors the Android `ARInstantPlacementDemo` (`samples/android-demo/.../ARInstantPlacementDemo.kt`)
/// which leverages ARCore's `Config.InstantPlacementMode.LOCAL_Y_UP`. On iOS,
/// ARKit doesn't ship a 1:1 "instant placement" API — the closest equivalent is
/// `ARView.raycast(...)` against `.estimatedPlane` alignment, which returns hits
/// before plane geometry has fully converged.
///
/// ### Honest-subset note — what the toggle actually does
///
/// `ARSceneView`'s tap raycast (`ARSceneView.swift` `handleTap`) is hardcoded to
/// `allowing: .estimatedPlane, alignment: .any`. It has no per-call hook to switch
/// raycast alignment, so **both toggle positions place models through the same
/// `.estimatedPlane` raycast** — taps always land before plane geometry has fully
/// converged.
///
/// The toggle is therefore a *coaching/overlay* switch, not a raycast-mode switch:
///
/// - **Instant Placement ON** — no plane overlay, no coaching overlay. The UI
///   encourages tapping straight away on the estimated-plane raycast.
/// - **Instant Placement OFF** — plane overlay + coaching overlay are shown, so
///   the user can wait for a converged plane before tapping. The raycast itself
///   is identical; only the visual guidance differs.
///
/// A true per-mode `existingPlane` vs `estimatedPlane` switch would require an
/// alignment parameter on `ARSceneView`'s tap raycast — tracked as a future API
/// addition rather than faked at the demo layer.
///
/// ### Streaming pipeline (Stage 2, issue #1152)
///
/// Same `ar_placement` slugs as ``ARPlacementDemo`` — picker, bundled cycle,
/// offline fallback are identical. Streaming + cycle helpers come from the
/// curated registry in ``SampleAssets``.
struct ARInstantPlacementDemo: View {
    private static let bundledCycle: [(name: String, displayName: String)] = [
        ("cyberpunk_hovercar", "Cyberpunk Hovercar"),
        ("phoenix_bird", "Phoenix Bird"),
        ("retro_piano", "Retro Piano"),
        ("game_boy_classic", "Game Boy"),
        ("animated_butterfly", "Butterfly"),
    ]

    @State private var instantEnabled: Bool = true
    @State private var cycleIndex: Int = 0
    @State private var selectedSlug: SketchfabSlug?
    @State private var armedURL: URL?
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

    /// Same rule as `ARPlacementDemo`: `nil` in bundled-cycle mode (nothing was
    /// substituted), measured from the resolved file otherwise (#2960).
    private var assetSource: AssetSourceState? {
        guard selectedSlug != nil else { return nil }
        return AssetSourceProbe.of(
            resolvedURL: armedURL,
            hasAPIKey: hasSketchfabKey,
            loaded: armedURL != nil
        )
    }

    var body: some View {
        ZStack {
            #if !targetEnvironment(simulator)
            // Rebuild the ARSceneView when the toggle flips so the new raycast
            // alignment takes effect. Mirrors Android's `key(instantEnabled)`
            // rebuild.
            ARSceneView(
                planeDetection: .horizontal,
                showPlaneOverlay: !instantEnabled,
                showCoachingOverlay: !instantEnabled,
                onTapOnPlane: { worldPosition, arView in
                    Task { @MainActor in
                        await placeModel(at: worldPosition, in: arView)
                    }
                }
            )
            .id("instant-placement-\(instantEnabled)")
            .ignoresSafeArea()
            #else
            simulatorPlaceholder
            #endif

            VStack(spacing: 8) {
                statusPill
                // Under the status pill, not in the corner — see ARPlacementDemo.
                if let assetSource {
                    HStack {
                        Spacer()
                        AssetSourcePill(state: assetSource)
                    }
                    .padding(.horizontal, 16)
                }
                Spacer()
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

    // MARK: - Placement

    @MainActor
    private func placeModel(at worldPosition: SIMD3<Float>, in arView: ARView) async {
        do {
            let node: ModelNode
            if let slug = selectedSlug, let url = armedURL {
                node = try await ModelNode.load(contentsOf: url)
                // Honour the slug's real-world size hint, as ARPlacementDemo
                // does — the bundled cycle alone is normalised to 0.3 m (#2966).
                _ = node.scaleToUnits(slug.scaleToUnits)
                _ = node.centerOrigin()
            } else {
                let entry = Self.bundledCycle[cycleIndex % Self.bundledCycle.count]
                cycleIndex += 1
                node = try await ModelNode.load(entry.name)
                _ = node.scaleToUnits(0.3)
                _ = node.centerOrigin()
            }
            let anchor = AnchorNode.world(position: worldPosition)
            anchor.add(node.entity)
            arView.scene.addAnchor(anchor.entity)
            arViewRef.value = arView
            placedAnchors.append(anchor.entity)
            #if os(iOS)
            SceneViewHaptic.shared.light()
            #endif
        } catch {
            // Silently keep the user in tap-to-retry mode (Android parity).
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
            Toggle(isOn: $instantEnabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(instantEnabled ? "Instant Placement ON" : "Instant Placement OFF")
                        .font(.subheadline.weight(.semibold))
                    Text(instantEnabled
                         ? "Overlays hidden — tap anywhere, ARKit approximates a pose immediately."
                         : "Plane + coaching overlays shown — wait for a plane, then tap inside it.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .tint(.blue)

            Text("Pick what to place")
                .font(.subheadline.weight(.semibold))

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    pickerChip(label: "Bundled cycle", isSelected: selectedSlug == nil) {
                        selectedSlug = nil
                    }
                    ForEach(placementSlugs, id: \.uid) { slug in
                        pickerChip(label: slug.displayName, isSelected: selectedSlug?.uid == slug.uid) {
                            selectedSlug = slug
                        }
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
                // Same rule as ARPlacementDemo: credit what is on screen (#2966).
                AssetCreditLine(slug: slug, source: assetSource ?? .streaming)
            }

            Button(role: .destructive) {
                clearAllPlacedModels()
            } label: {
                Label("Clear all placed models", systemImage: "trash")
                    .font(.subheadline.weight(.semibold))
            }
            .disabled(placedCount == 0)

            Text("iOS port note: ARKit doesn't expose ARCore's `InstantPlacementMode.LOCAL_Y_UP` directly. Taps always use an `.estimatedPlane` raycast so they land before planes fully converge. The toggle here only shows/hides the plane + coaching overlays — it does not change the raycast alignment.")
                .font(.caption2)
                .foregroundStyle(.secondary)
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

    private var statusPill: some View {
        Text(placedCount == 1 ? "1 model placed" : "\(placedCount) models placed")
            .font(.caption.weight(.medium))
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .background(.ultraThinMaterial, in: Capsule())
            .padding(.top, 8)
    }

    private var simulatorPlaceholder: some View {
        VStack(spacing: 16) {
            Image(systemName: "bolt.fill")
                .font(.system(size: 60))
                .foregroundStyle(.secondary)
            Text("AR requires a physical device")
                .font(.headline)
            Text("Run on iPhone or iPad to test Instant Placement.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
    }

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
            armedURL = nil
        }
    }
}
#endif

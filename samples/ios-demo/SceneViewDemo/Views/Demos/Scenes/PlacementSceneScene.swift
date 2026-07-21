// @sceneId     placement-scene
// @title       Placement Scene
// @subtitle    One-line tap-to-place AR (Sceneform ArFragment parity)
// @category    ar
// @available   true
// @icon        mappin.and.ellipse
// @iosOnly     true
// @status      working
import SwiftUI

/// One-line tap-to-place AR demo — the RealityKit counterpart of Android's
/// `PlacementScene` composable (`arsceneview/src/main/java/io/github/sceneview/ar/PlacementScene.kt`)
/// and its showcase, `PlacementSceneDemo`
/// (`samples/android-demo/.../demos/PlacementSceneDemo.kt`, issue #2839).
///
/// Distinct from the already-working `ar-placement` id (`ArPlacementScene` →
/// `ARPlacementDemo`), which hand-rolls its own plane-detection + tap-to-place
/// loop with a Sketchfab picker to showcase the AR primitives at the *low*
/// level. This demo showcases the *high-level*, one-call bundle instead — no
/// picker, a single bundled model, every "batteries-included" flag turned on
/// at once — mirroring Android's `PlacementScene { anchor -> ... }`
/// one-liner. The two ids are intentionally NOT aliased to one another.
///
/// ### What maps onto what
///
/// | Android `PlacementScene` param (default used by the demo) | iOS `ARSceneView` param |
/// |---|---|
/// | `planeFindingMode = HORIZONTAL_AND_VERTICAL` (default) | `planeDetection: .both` |
/// | `planeRenderer = true` (default) | `showPlaneOverlay: true` |
/// | `showReticle = true` (default) | `showPlacementReticle: true` |
/// | `coaching = true` (demo opts in) | `showCoachingOverlay: true` |
/// | `groundShadows = true` (demo opts in) | `groundingShadows: true` **+** an explicit `withGroundingShadow()` on the loaded model — see note below |
/// | `instantPlacement = true` (default) | always on — `ARSceneView`'s tap raycast is hardcoded to `.estimatedPlane`, ARKit's closest equivalent (see `ARInstantPlacementDemo`'s doc comment for the full nuance) |
///
/// ### Honest-subset notes vs Android
///
/// - **The plane grid never fades.** Android's `PlacementScene` hides the
///   plane-detection grid the instant the first model is placed
///   (`fadePlaneOnFirstPlacement = true`, the default the demo doesn't
///   override) — a discovery aid, not a permanent floor decoration.
///   `ARSceneView.showPlaneOverlay` is read once in `makeUIView` and never
///   re-applied in `updateUIView`, so it cannot be toggled reactively without
///   tearing down and rebuilding the whole AR session (re-tracking from
///   scratch is a worse user experience than just leaving the grid on) — the
///   grid stays visible for the entire session on iOS today. Tracked as a
///   gap rather than faked.
/// - **The reticle's signal is coarser.** Android's ring reticle *dims while
///   searching* and *brightens with a centre dot once ready*. iOS's
///   `showPlacementReticle` disc is binary — hidden while the centre-screen
///   ray misses, shown once it hits — the same searching↔ready signal with
///   one fewer visual state in between.
/// - **The coaching UI is platform-native, not a recreation.**
///   `showCoachingOverlay` uses Apple's own `ARCoachingOverlayView`, not a
///   copy of Android's animated hand-hint `PlaneDiscoveryGuide`. Same
///   purpose (help the user find a surface on first run), different chrome —
///   intentional, and consistent with every other AR demo in this app.
/// - **The grounding shadow is applied explicitly, not via the automatic
///   flag.** `ARSceneView`'s own doc comment notes `groundingShadows` only
///   shadows anchors added *synchronously* inside `onTapOnPlane`; this demo
///   loads its model with `await ModelNode.load(...)`, so the anchor is
///   always added *asynchronously* — the automatic path would silently never
///   fire (the same latent gap exists in `ARPlacementDemo`). The model calls
///   `withGroundingShadow()` on itself instead, so the contact shadow always
///   lands; `groundingShadows: true` is left set too, both to document intent
///   and to cover any future synchronous caller.
///
/// ### Simulator caveat
///
/// ARKit world tracking does not run in the iOS Simulator — `arScene` is
/// compiled out under `#if !targetEnvironment(simulator)` and replaced with
/// `simulatorPlaceholder`, matching every other AR demo in this app. A
/// Simulator build proves this file compiles and the demo launches; it does
/// NOT prove the plane-detection + tap-to-place loop works. That needs a
/// physical device.
enum PlacementSceneScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(PlacementSceneDemoView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}

#if os(iOS)
import RealityKit
import ARKit
import SceneViewSwift

/// The demo view itself. Kept in this one file — rather than split into a
/// sibling `Views/Demos/PlacementSceneDemo.swift`, the convention
/// `ARPlacementDemo` / `ARInstantPlacementDemo` follow — because this port's
/// task scope (#2839) is a single Scene file.
private struct PlacementSceneDemoView: View {
    /// The one model every tap places. Android's `PlacementSceneDemo` places
    /// exactly one model type (`models/khronos_damaged_helmet.glb`) — no
    /// picker, unlike the lower-level `ar-placement` demo.
    /// `khronos_damaged_helmet.usdz` is the same Khronos sample asset already
    /// bundled for `ModelViewerDemo` (#2831 GLB→USDZ pipeline).
    private static let modelName = "khronos_damaged_helmet"

    /// Anchors placed by tapping. Retained so "Clear All" can tear them down
    /// — `placedCount` is always derived from this collection so the two
    /// never drift apart. Mirrors Android's `PlacementController.anchors`.
    @State private var placedAnchors: [AnchorEntity] = []

    /// Weak handle on the live `ARView`, captured from the tap callback, so
    /// "Clear All" can remove anchors from `arView.scene`.
    @State private var arViewRef: ARViewBox = ARViewBox()

    /// Reference box for the non-`Sendable`/non-`Equatable` `ARView` so it can
    /// live in SwiftUI `@State` without triggering view-identity churn.
    private final class ARViewBox {
        weak var value: ARView?
    }

    private var placedCount: Int { placedAnchors.count }

    var body: some View {
        ZStack {
            #if !targetEnvironment(simulator)
            arScene
                .ignoresSafeArea()
            #else
            simulatorPlaceholder
            #endif

            VStack {
                statusPill
                Spacer()
            }
        }
        .demoSettingsSheet { controlsSheet }
    }

    // MARK: - AR scene

    #if !targetEnvironment(simulator)
    private var arScene: some View {
        ARSceneView(
            // Android's `PlacementScene` default is HORIZONTAL_AND_VERTICAL —
            // the demo never overrides `planeFindingMode`.
            planeDetection: .both,
            showPlaneOverlay: true,
            showCoachingOverlay: true,
            showPlacementReticle: true,
            groundingShadows: true,
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
            let node = try await ModelNode.load(Self.modelName)
            _ = node.scaleToUnits(0.3)
            _ = node.centerOrigin()
            // Explicit — not reliant on ARSceneView's automatic
            // `groundingShadows` application, which only fires for anchors
            // added synchronously inside `onTapOnPlane` (see the
            // "Honest-subset notes" doc comment on `PlacementSceneScene`).
            _ = node.withGroundingShadow()
            let anchor = AnchorNode.world(position: worldPosition)
            anchor.add(node.entity)
            arView.scene.addAnchor(anchor.entity)
            arViewRef.value = arView
            placedAnchors.append(anchor.entity)
            #if os(iOS)
            SceneViewHaptic.shared.light()
            #endif
        } catch {
            // A bundled local asset should never fail to load. Keep the user
            // in tap-to-retry mode rather than surfacing an error banner —
            // Android's single-model demo has no error UI either.
        }
    }

    /// Removes every placed anchor from the AR scene and resets the count.
    /// Safe to call when nothing is placed. Mirrors Android's
    /// `PlacementController.clear()`.
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
            Text(
                "This scene is a single `ARSceneView(...)` call with coaching, " +
                "a placement reticle, and grounding shadows all turned on. It " +
                "bundles ARKit's coaching overlay, a placement reticle that " +
                "appears once a surface is ready, tap-to-place, and a contact " +
                "shadow under each model. Aim the reticle at a surface and tap " +
                "to drop a model."
            )
            .font(.caption)
            .foregroundStyle(.secondary)

            Button(role: .destructive) {
                clearAllPlacedModels()
            } label: {
                Label("Clear All", systemImage: "trash")
                    .font(.subheadline.weight(.semibold))
            }
            .disabled(placedCount == 0)

            Text(
                "iOS port note: the plane-detection grid does not fade after " +
                "your first placement the way Android's `PlacementScene` does " +
                "— `ARSceneView`'s plane overlay is fixed for the life of the " +
                "session today. Coaching, the reticle, tap-to-place, and the " +
                "contact shadow all match."
            )
            .font(.caption2)
            .foregroundStyle(.secondary)
        }
    }

    // MARK: - Status overlay

    private var statusPill: some View {
        Text(placedCount == 1 ? "1 model placed" : "\(placedCount) models placed")
            .font(.caption.weight(.medium))
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .background(.ultraThinMaterial, in: Capsule())
            .padding(.top, 8)
    }

    // MARK: - Simulator placeholder

    private var simulatorPlaceholder: some View {
        VStack(spacing: 16) {
            Image(systemName: "mappin.and.ellipse")
                .font(.system(size: 60))
                .foregroundStyle(.secondary)
            Text("AR requires a physical device")
                .font(.headline)
            Text("Run on iPhone or iPad to scan a surface and tap to place a model.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
    }
}
#endif

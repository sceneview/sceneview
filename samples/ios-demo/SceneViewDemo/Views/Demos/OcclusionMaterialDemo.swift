import SwiftUI
import RealityKit
import SceneViewSwift

/// iOS equivalent of Android's `OcclusionMaterialDemo`.
///
/// Demonstrates RealityKit's built-in `OcclusionMaterial` — an invisible,
/// depth-writing material that hides objects behind it while remaining
/// imperceptible itself.  Paired with a reference model (a metallic sphere),
/// a flat plane in front of the sphere's lower half is given the
/// `OcclusionMaterial`: the result looks like the sphere is partially buried /
/// clipped by invisible geometry.
///
/// The "Show occluder plane" toggle replaces the occlusion material with a
/// semi-transparent grey slab so you can see *where* the occluder is — the
/// ground-truth reveal that explains the illusion.
///
/// Coverage: `sceneview://demo/occlusion-material`
struct OcclusionMaterialDemo: View {

    @State private var showOccluder: Bool = false

    /// The live occluder plane. The `SceneView` content closure runs once (it
    /// is RealityView's `make:`), so the toggle hot-swaps the material on this
    /// reference instead of rebuilding the scene — same pattern as
    /// `MultiModelDemo`.
    @State private var occluderEntity: ModelEntity?

    var body: some View {
        ZStack {
            sceneView
            VStack {
                Spacer()
                controlsOverlay
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                    .padding()
            }
        }
        .onChange(of: showOccluder) { _, _ in applyOccluderMaterial() }
        .navigationTitle("Occlusion Material")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    // MARK: — Scene

    @ViewBuilder
    private var sceneView: some View {
        SceneView { root in
            // ── Reference model (sphere) ──────────────────────────────────
            let sphereMesh   = MeshResource.generateSphere(radius: 0.25)
            let sphereEntity = ModelEntity(mesh: sphereMesh, materials: [Self.referenceMaterial()])
            sphereEntity.name = "sphere"
            sphereEntity.position = [0, 0, -0.7]

            // ── Occluder plane ─────────────────────────────────────────────
            // A thin flat plane placed in front of the sphere's lower half.
            // Default material = OcclusionMaterial (invisible, depth-writing).
            let planeSize: Float = 0.5
            let planeMesh   = MeshResource.generatePlane(width: planeSize, height: planeSize / 2)
            let planeEntity = ModelEntity(
                mesh: planeMesh,
                materials: [Self.occluderMaterial(revealed: showOccluder)]
            )
            planeEntity.name = "occluder"
            // Position the occluder plane in front of the sphere's lower half.
            planeEntity.position = [0, -0.05, -0.58]

            root.addChild(sphereEntity)
            root.addChild(planeEntity)
            // Re-apply once the reference is published: a toggle that fired
            // between scene setup and this hop would otherwise be dropped by
            // `applyOccluderMaterial()`'s nil guard. Mirrors `MultiModelDemo`,
            // which calls `syncVisibility()` from the same hop.
            Task { @MainActor in
                self.occluderEntity = planeEntity
                self.applyOccluderMaterial()
            }
        }
        // The reference sphere is `metallic 0.8`: the illusion only reads if it
        // actually looks like metal, and a metallic surface with no image-based
        // light has nothing to reflect and renders near-black. The scene used to
        // be built on a raw `RealityView`, and `.environment()` is defined on
        // `SceneView` — it could not reach it. Building the entities inside the
        // wrapper's own content closure is what puts them on the IBL path
        // (#2842). Same `.studio` preset as `ModelViewerDemo` (#2114).
        .environment(.studio)
        .ignoresSafeArea()
    }

    // MARK: — Materials

    private static func referenceMaterial() -> PhysicallyBasedMaterial {
        var material = PhysicallyBasedMaterial()
        material.baseColor = .init(tint: .init(red: 0.2, green: 0.6, blue: 0.9, alpha: 1))
        material.roughness = .init(floatLiteral: 0.3)
        material.metallic  = .init(floatLiteral: 0.8)
        return material
    }

    /// `revealed` swaps the invisible depth-writing material for a
    /// semi-transparent slab — the ground-truth reveal behind the toggle.
    private static func occluderMaterial(revealed: Bool) -> any RealityKit.Material {
        revealed
            ? SimpleMaterial(color: .init(red: 0.4, green: 0.4, blue: 0.45, alpha: 0.6), isMetallic: false)
            : OcclusionMaterial()
    }

    private func applyOccluderMaterial() {
        guard let occluderEntity,
              var model = occluderEntity.components[ModelComponent.self] else { return }
        model.materials = [Self.occluderMaterial(revealed: showOccluder)]
        occluderEntity.components.set(model)
    }

    // MARK: — Controls

    @ViewBuilder
    private var controlsOverlay: some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Show occluder plane")
                    .font(.subheadline)
                Text(showOccluder
                    ? "Semi-transparent slab — see where it is"
                    : "OcclusionMaterial — invisible, cuts the sphere")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Toggle("", isOn: $showOccluder)
                .labelsHidden()
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
    }
}

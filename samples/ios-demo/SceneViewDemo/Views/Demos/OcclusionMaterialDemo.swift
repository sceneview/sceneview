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
        .navigationTitle("Occlusion Material")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: — Scene

    @ViewBuilder
    private var sceneView: some View {
        RealityView { content in
            // ── Reference model (sphere) ──────────────────────────────────
            var helmetMaterial = PhysicallyBasedMaterial()
            helmetMaterial.baseColor = .init(tint: .init(red: 0.2, green: 0.6, blue: 0.9, alpha: 1))
            helmetMaterial.roughness = .init(floatLiteral: 0.3)
            helmetMaterial.metallic  = .init(floatLiteral: 0.8)
            let sphereMesh   = MeshResource.generateSphere(radius: 0.25)
            let sphereEntity = ModelEntity(mesh: sphereMesh, materials: [helmetMaterial])
            sphereEntity.name = "sphere"
            sphereEntity.position = [0, 0, -0.7]

            // ── Occluder plane ─────────────────────────────────────────────
            // A thin flat plane placed in front of the sphere's lower half.
            // Default material = OcclusionMaterial (invisible, depth-writing).
            let planeSize: Float = 0.5
            let planeMesh   = MeshResource.generatePlane(width: planeSize, height: planeSize / 2)
            let occluderMat: any RealityKit.Material = showOccluder
                ? SimpleMaterial(color: .init(red: 0.4, green: 0.4, blue: 0.45, alpha: 0.6), isMetallic: false)
                : OcclusionMaterial()
            let planeEntity = ModelEntity(mesh: planeMesh, materials: [occluderMat])
            planeEntity.name = "occluder"
            // Position the occluder plane in front of the sphere's lower half.
            planeEntity.position = [0, -0.05, -0.58]

            content.add(sphereEntity)
            content.add(planeEntity)
        } update: { content in
            // Hot-swap the occluder material when the toggle changes.
            guard let occluder = content.entities.first(where: { $0.name == "occluder" }),
                  var model = occluder.components[ModelComponent.self] else { return }
            let newMat: any RealityKit.Material = showOccluder
                ? SimpleMaterial(color: .init(red: 0.4, green: 0.4, blue: 0.45, alpha: 0.6), isMetallic: false)
                : OcclusionMaterial()
            model.materials = [newMat]
            occluder.components.set(model)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
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

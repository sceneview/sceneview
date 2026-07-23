import SwiftUI
import RealityKit
import SceneViewSwift

/// iOS equivalent of Android's `TextureStreamingDemo` — showcases how
/// material / texture data can be swapped on an already-loaded model
/// without rebuilding geometry.
///
/// On Android / Filament the demo constructs a fresh `MaterialInstance`
/// per variant and reassigns it to a `SphereNode`; on iOS the same swap
/// is a `PhysicallyBasedMaterial` property update on an already-placed
/// `ModelEntity`.  Both teach the same concept: real-time material
/// parameter streaming is instantaneous (no geometry rebuild, no GPU
/// stall).
///
/// Coverage: `sceneview://demo/texture-streaming`
struct TextureStreamingDemo: View {

    // MARK: — Material variants (mirrors Android's MaterialVariant list)

    private struct MaterialPreset: Identifiable {
        let id: String
        let label: String
        let baseColor: UIColor
        let roughness: Float        // 0 = mirror-smooth  1 = matte
        let metallic: Float         // 0 = dielectric     1 = metallic
    }

    private static let presets: [MaterialPreset] = [
        MaterialPreset(id: "gold",       label: "Gold",      baseColor: UIColor(red: 1.00, green: 0.76, blue: 0.34, alpha: 1), roughness: 0.15, metallic: 1.0),
        MaterialPreset(id: "silver",     label: "Silver",    baseColor: UIColor(red: 0.75, green: 0.75, blue: 0.78, alpha: 1), roughness: 0.10, metallic: 1.0),
        MaterialPreset(id: "copper",     label: "Copper",    baseColor: UIColor(red: 0.72, green: 0.45, blue: 0.20, alpha: 1), roughness: 0.20, metallic: 1.0),
        MaterialPreset(id: "ceramic",    label: "Ceramic",   baseColor: UIColor(red: 0.93, green: 0.90, blue: 0.85, alpha: 1), roughness: 0.60, metallic: 0.0),
        MaterialPreset(id: "plastic",    label: "Plastic",   baseColor: UIColor(red: 0.20, green: 0.50, blue: 0.80, alpha: 1), roughness: 0.40, metallic: 0.0),
        MaterialPreset(id: "rubber",     label: "Rubber",    baseColor: UIColor(red: 0.10, green: 0.10, blue: 0.10, alpha: 1), roughness: 0.90, metallic: 0.0),
    ]

    // MARK: — State

    @State private var selectedIndex: Int = 0

    /// The live sphere. The `SceneView` content closure runs once (it is
    /// RealityView's `make:`), so a preset change mutates this reference
    /// directly rather than rebuilding the scene — same pattern as
    /// `MultiModelDemo` / `MovableLightDemo`, and the same thing the demo is
    /// teaching: swap the material, keep the geometry.
    @State private var sphereEntity: ModelEntity?

    // MARK: — Body

    var body: some View {
        ZStack {
            SceneView { root in
                let entity = makeSphereEntity(preset: Self.presets[selectedIndex])
                entity.name = "sphere"
                root.addChild(entity)
                // Re-apply once the reference is published: a preset picked
                // between scene setup and this hop would otherwise be dropped
                // by `applySelectedPreset()`'s nil guard. Mirrors
                // `MultiModelDemo`, which calls `syncVisibility()` from the
                // same hop.
                Task { @MainActor in
                    self.sphereEntity = entity
                    self.applySelectedPreset()
                }
            }
            // Route through the wrapper's IBL path for iOS-catalog consistency
            // and Android parity — NOT to fix an unlit render. The sphere used
            // to live in a raw `RealityView` overlay stacked on an empty
            // `SceneView`; that raw path already lit the presets via RealityKit's
            // default environment lighting (verified on the simulator 2026-07-23
            // — gold / silver / copper read as distinct metals). But
            // `.environment()` is defined on `SceneView`, so the raw path could
            // never adopt the catalog's studio HDRI. Android hosts these material
            // variants in `MaterialsDemo` with `studio_2k.hdr` + skybox; `.studio`
            // here is the same studio environment, aligning this demo with the
            // rest of the iOS catalog (ModelViewerDemo / MaterialsDemo, #2114) and
            // with Android. Follow-up to the L1.1 IBL sweep (#2805).
            .environment(.studio)
            .ignoresSafeArea()

            // Controls overlay at the bottom.
            VStack {
                Spacer()
                controlsOverlay
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                    .padding()
            }
        }
        .onChange(of: selectedIndex) { _, _ in applySelectedPreset() }
        .navigationTitle("Texture Streaming")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    // MARK: — Controls

    @ViewBuilder
    private var controlsOverlay: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Material")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 16)
                .padding(.top, 12)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Self.presets.indices, id: \.self) { index in
                        let preset = Self.presets[index]
                        Button {
                            withAnimation(.easeInOut(duration: 0.2)) {
                                selectedIndex = index
                            }
                        } label: {
                            HStack(spacing: 6) {
                                Circle()
                                    .fill(Color(preset.baseColor))
                                    .frame(width: 12, height: 12)
                                Text(preset.label)
                                    .font(.subheadline)
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                        }
                        .buttonStyle(.bordered)
                        .tint(selectedIndex == index ? .accentColor : .secondary)
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(selectedIndex == index ? Color.accentColor : Color.clear, lineWidth: 2)
                        )
                    }
                }
                .padding(.horizontal, 16)
            }

            let preset = Self.presets[selectedIndex]
            Text(String(format: "Metallic: %.2f   Roughness: %.2f", preset.metallic, preset.roughness))
                .font(.caption2)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
        }
    }

    // MARK: — Helpers

    private func makeSphereEntity(preset: MaterialPreset) -> ModelEntity {
        let mesh = MeshResource.generateSphere(radius: 0.3)
        let entity = ModelEntity(mesh: mesh, materials: [Self.makeMaterial(preset: preset)])
        entity.position = [0, 0, -0.8]
        return entity
    }

    private static func makeMaterial(preset: MaterialPreset) -> PhysicallyBasedMaterial {
        var pbr = PhysicallyBasedMaterial()
        pbr.baseColor = .init(tint: preset.baseColor)
        pbr.roughness = .init(floatLiteral: preset.roughness)
        pbr.metallic  = .init(floatLiteral: preset.metallic)
        return pbr
    }

    /// Streams the selected preset onto the already-placed sphere — the point
    /// of the demo: a material swap with no geometry rebuild.
    private func applySelectedPreset() {
        guard let sphereEntity,
              var model = sphereEntity.components[ModelComponent.self] else { return }
        model.materials = [Self.makeMaterial(preset: Self.presets[selectedIndex])]
        sphereEntity.components.set(model)
    }
}

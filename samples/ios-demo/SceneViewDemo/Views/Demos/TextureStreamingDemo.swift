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

    // MARK: — Body

    var body: some View {
        ZStack {
            SceneView { scene in
                // Place a sphere entity; material applied / updated below.
            }
            .overlay(sphereOverlay)
            .ignoresSafeArea()

            // Controls overlay at the bottom.
            VStack {
                Spacer()
                controlsOverlay
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                    .padding()
            }
        }
        .navigationTitle("Texture Streaming")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: — RealityView overlay

    @ViewBuilder
    private var sphereOverlay: some View {
        RealityView { content in
            let entity = makeSphereEntity(preset: Self.presets[selectedIndex])
            entity.name = "sphere"
            content.add(entity)
        } update: { content in
            guard let entity = content.entities.first(where: { $0.name == "sphere" }),
                  var model = entity.components[ModelComponent.self] else { return }
            let preset = Self.presets[selectedIndex]
            var pbr = PhysicallyBasedMaterial()
            pbr.baseColor = .init(tint: preset.baseColor)
            pbr.roughness = .init(floatLiteral: preset.roughness)
            pbr.metallic  = .init(floatLiteral: preset.metallic)
            model.materials = [pbr]
            entity.components.set(model)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
        var pbr = PhysicallyBasedMaterial()
        pbr.baseColor = .init(tint: preset.baseColor)
        pbr.roughness = .init(floatLiteral: preset.roughness)
        pbr.metallic  = .init(floatLiteral: preset.metallic)
        let mesh = MeshResource.generateSphere(radius: 0.3)
        let entity = ModelEntity(mesh: mesh, materials: [pbr])
        entity.position = [0, 0, -0.8]
        return entity
    }
}

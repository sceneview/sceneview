import RealityKit
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// Material values a bundled USDZ lost on its way from the glTF, put back at
/// load so the model reads as it does on Android, which renders the GLB.
///
/// **Toy Car.** The car sits on a velvet cloth. Its GLB (`khronos_toy_car.glb`,
/// material `Fabric`) sets `baseColorFactor` 0.15 over the fabric texture and
/// `KHR_materials_sheen` with `sheenColorFactor` (1, 0, 0) and
/// `sheenRoughnessFactor` 0.5: a dark cloth with a red rim where it turns away
/// from the light, which Filament renders as the orange-red velvet. Blender's
/// USD exporter (`tools/convert-usdz.sh`) writes a `UsdPreviewSurface`, which
/// has no sheen input, and wires the texture straight to `diffuseColor`, which
/// drops the factor, so the USDZ gave a dull pink cloth. Converting
/// again would lose both again, so the repair lives here, keyed on the mesh
/// the exporter names after the material.
///
/// RealityKit's sheen has a colour and no roughness of its own: the lobe takes
/// the material's roughness. At the cloth's roughness of 1 the sheen spreads
/// over every face and the cloth reads as flat paint; at the sheen's 0.5 it
/// gathers on the folds and the rims as on Android. So the cloth takes the
/// sheen's 0.5: its base lobe (a 0.15 factor over an already dark red
/// texture) is too dark for the extra gloss to show.
///
/// The colour is Android's screen, not the GLB's number. Through SceneView's
/// Filament pipeline (Filmic tone mapper) the red lobe comes out copper;
/// RealityKit keeps it a saturated red, which reads as another cloth. The
/// tint below is the one that shows Android's copper under RealityKit,
/// matched on offline RealityKit renders next to the Android capture.
enum ViewerMaterialRepairs {
    static let toyCarAsset = "khronos_toy_car"
    static let toyCarFabric = "Fabric"
    /// `baseColorFactor` 0.15 is linear; as an sRGB colour component, 0.4236.
    static let toyCarFabricTint: Float = 0.15
    /// The GLB's `sheenColorFactor`, for the record.
    static let toyCarGLBSheen: SIMD3<Float> = [1, 0, 0]
    /// The linear tint that renders as Android's copper (see above).
    static let toyCarSheen: SIMD3<Float> = [1, 0.3, 0.08]
    /// `sheenRoughnessFactor`, which RealityKit can only set on the material.
    static let toyCarSheenRoughness: Float = 0.5

    /// Applies the repairs `asset` needs to its loaded `entity`. A no-op for
    /// every other asset.
    static func apply(to entity: Entity, asset: String) {
        guard asset == toyCarAsset else { return }
        forEachModel(named: toyCarFabric, under: entity) { model in
            model.materials = model.materials.map { material in
                guard var pbr = material as? PhysicallyBasedMaterial else { return material }
                pbr.baseColor.tint = color(linear: SIMD3(repeating: toyCarFabricTint))
                pbr.sheen = .init(tint: color(linear: toyCarSheen))
                pbr.roughness = .init(floatLiteral: toyCarSheenRoughness)
                return pbr
            }
        }
    }

    private static func forEachModel(named name: String, under root: Entity,
                                     _ body: (inout ModelComponent) -> Void) {
        var stack = [root]
        while let entity = stack.popLast() {
            if entity.name == name, var model = entity.components[ModelComponent.self] {
                body(&model)
                entity.components.set(model)
            }
            stack.append(contentsOf: entity.children)
        }
    }

    /// A linear glTF factor as the sRGB colour RealityKit's tints take.
    static func color(linear c: SIMD3<Float>) -> PhysicallyBasedMaterial.Color {
        func encode(_ v: Float) -> CGFloat {
            let x = min(max(v, 0), 1)
            return CGFloat(x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 1 / 2.4) - 0.055)
        }
        return PhysicallyBasedMaterial.Color(red: encode(c.x), green: encode(c.y), blue: encode(c.z), alpha: 1)
    }
}

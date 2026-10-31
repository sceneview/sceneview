import Foundation

// The subset of the glTF 2.0 JSON schema SceneViewSwift reads.
//
// Plain `Decodable` structs, named after the spec's own objects
// (https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html). Every property the
// spec marks optional is optional here, and unknown keys are ignored by `Decodable`,
// so an exporter's private `extras`/`extensions` never fail a load. The extensions
// the importer understands are decoded explicitly; the rest are judged by
// `extensionsRequired` alone (see ``GLTFDocument/supportedRequiredExtensions``).

struct GLTFRoot: Decodable {
    var asset: GLTFAssetInfo
    var scene: Int?
    var scenes: [GLTFSceneDef]?
    var nodes: [GLTFNodeDef]?
    var meshes: [GLTFMeshDef]?
    var accessors: [GLTFAccessorDef]?
    var bufferViews: [GLTFBufferViewDef]?
    var buffers: [GLTFBufferDef]?
    var materials: [GLTFMaterialDef]?
    var textures: [GLTFTextureDef]?
    var images: [GLTFImageDef]?
    var samplers: [GLTFSamplerDef]?
    var skins: [GLTFSkinDef]?
    var animations: [GLTFAnimationDef]?
    var extensionsUsed: [String]?
    var extensionsRequired: [String]?
}

struct GLTFAssetInfo: Decodable {
    var version: String
    var minVersion: String?
    var generator: String?
}

struct GLTFSceneDef: Decodable {
    var name: String?
    var nodes: [Int]?
}

struct GLTFNodeDef: Decodable {
    var name: String?
    var children: [Int]?
    var mesh: Int?
    var skin: Int?
    var matrix: [Float]?
    var translation: [Float]?
    var rotation: [Float]?
    var scale: [Float]?
}

struct GLTFMeshDef: Decodable {
    var name: String?
    var primitives: [GLTFPrimitiveDef]
}

struct GLTFPrimitiveDef: Decodable {
    var attributes: [String: Int]
    var indices: Int?
    var material: Int?
    /// 0 points, 1 lines, 2 line loop, 3 line strip, 4 triangles (default), 5 strip, 6 fan.
    var mode: Int?
    var targets: [[String: Int]]?
}

struct GLTFAccessorDef: Decodable {
    var bufferView: Int?
    var byteOffset: Int?
    var componentType: Int
    var normalized: Bool?
    var count: Int
    var type: String
    var sparse: GLTFSparseDef?
}

struct GLTFSparseDef: Decodable {
    struct Indices: Decodable {
        var bufferView: Int
        var byteOffset: Int?
        var componentType: Int
    }
    struct Values: Decodable {
        var bufferView: Int
        var byteOffset: Int?
    }
    var count: Int
    var indices: Indices
    var values: Values
}

struct GLTFBufferViewDef: Decodable {
    var buffer: Int
    var byteOffset: Int?
    var byteLength: Int
    var byteStride: Int?
}

struct GLTFBufferDef: Decodable {
    var uri: String?
    var byteLength: Int
}

struct GLTFTextureInfoDef: Decodable {
    struct Extensions: Decodable {
        var textureTransform: GLTFTextureTransformDef?
        enum CodingKeys: String, CodingKey {
            case textureTransform = "KHR_texture_transform"
        }
    }
    var index: Int
    var texCoord: Int?
    /// `normalTexture.scale` — decoded for completeness, not applied (RealityKit has no
    /// normal-map strength).
    var scale: Float?
    /// `occlusionTexture.strength` — likewise.
    var strength: Float?
    var extensions: Extensions?
}

struct GLTFTextureTransformDef: Decodable, Equatable {
    var offset: [Float]?
    var rotation: Float?
    var scale: [Float]?
    var texCoord: Int?
}

struct GLTFMaterialDef: Decodable {
    struct PBR: Decodable {
        var baseColorFactor: [Float]?
        var baseColorTexture: GLTFTextureInfoDef?
        var metallicFactor: Float?
        var roughnessFactor: Float?
        var metallicRoughnessTexture: GLTFTextureInfoDef?
    }
    struct Extensions: Decodable {
        struct EmissiveStrength: Decodable { var emissiveStrength: Float? }
        struct Unlit: Decodable {}
        var emissiveStrength: EmissiveStrength?
        var unlit: Unlit?
        enum CodingKeys: String, CodingKey {
            case emissiveStrength = "KHR_materials_emissive_strength"
            case unlit = "KHR_materials_unlit"
        }
    }
    var name: String?
    var pbrMetallicRoughness: PBR?
    var normalTexture: GLTFTextureInfoDef?
    var occlusionTexture: GLTFTextureInfoDef?
    var emissiveTexture: GLTFTextureInfoDef?
    var emissiveFactor: [Float]?
    var alphaMode: String?
    var alphaCutoff: Float?
    var doubleSided: Bool?
    var extensions: Extensions?
}

struct GLTFTextureDef: Decodable {
    struct Extensions: Decodable {
        struct Source: Decodable { var source: Int? }
        var webp: Source?
        enum CodingKeys: String, CodingKey {
            case webp = "EXT_texture_webp"
        }
    }
    var sampler: Int?
    var source: Int?
    var extensions: Extensions?

    /// The image this texture samples: the core `source`, or the WebP one when the
    /// texture only declares it through `EXT_texture_webp` (ImageIO decodes WebP).
    var imageIndex: Int? { source ?? extensions?.webp?.source }
}

struct GLTFImageDef: Decodable {
    var uri: String?
    var mimeType: String?
    var bufferView: Int?
    var name: String?
}

struct GLTFSamplerDef: Decodable {
    var magFilter: Int?
    var minFilter: Int?
    var wrapS: Int?
    var wrapT: Int?
}

struct GLTFSkinDef: Decodable {
    var name: String?
    var inverseBindMatrices: Int?
    var skeleton: Int?
    var joints: [Int]
}

struct GLTFAnimationDef: Decodable {
    struct Channel: Decodable {
        struct Target: Decodable {
            var node: Int?
            var path: String
        }
        var sampler: Int
        var target: Target
    }
    struct Sampler: Decodable {
        var input: Int
        var output: Int
        var interpolation: String?
    }
    var name: String?
    var channels: [Channel]
    var samplers: [Sampler]
}

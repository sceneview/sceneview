package io.github.erkko68.filament.gltfio

/**
 * MaterialKey encodes glTF material properties for material selection and creation.
 *
 * MaterialKey holds a set of boolean flags and texture coordinates that describe the
 * properties of a glTF material. MaterialProvider uses this to determine which material
 * to create or select. Key properties include texture presence, shading model, alpha mode,
 * and advanced features like clearcoat and transmission.
 *
 * @see MaterialProvider
 */
expect class MaterialKey {
    /**
     * Create a new MaterialKey with default values.
     */
    constructor()

    /** Renders both faces of each triangle (glTF `doubleSided`). */
    var doubleSided: Boolean
    /** Uses the unlit shading model (`KHR_materials_unlit`). */
    var unlit: Boolean
    /** The mesh provides per-vertex COLOR_0 data to be multiplied into base color. */
    var hasVertexColors: Boolean
    /** A base color texture is bound. */
    var hasBaseColorTexture: Boolean
    /** A tangent-space normal map is bound. */
    var hasNormalTexture: Boolean
    /** An ambient-occlusion texture is bound. */
    var hasOcclusionTexture: Boolean
    /** An emissive texture is bound. */
    var hasEmissiveTexture: Boolean
    /** Uses the legacy specular-glossiness workflow (`KHR_materials_pbrSpecularGlossiness`). */
    var useSpecularGlossiness: Boolean
    /** Alpha mode: 0 = OPAQUE, 1 = MASK (alpha cutoff), 2 = BLEND. */
    var alphaMode: Int
    /** Enables shader diagnostics (visualizes the material as a debug aid). */
    var enableDiagnostics: Boolean
    /** A metallic-roughness texture is bound (specular-glossiness texture when [useSpecularGlossiness]). */
    var hasMetallicRoughnessTexture: Boolean
    /** glTF texcoord set index for the metallic-roughness texture. */
    var metallicRoughnessUV: Int
    /** glTF texcoord set index for the base color texture. */
    var baseColorUV: Int
    /** A clearcoat intensity texture is bound. */
    var hasClearCoatTexture: Boolean
    /** glTF texcoord set index for the clearcoat texture. */
    var clearCoatUV: Int
    /** A clearcoat roughness texture is bound. */
    var hasClearCoatRoughnessTexture: Boolean
    /** glTF texcoord set index for the clearcoat roughness texture. */
    var clearCoatRoughnessUV: Int
    /** A clearcoat normal map is bound. */
    var hasClearCoatNormalTexture: Boolean
    /** glTF texcoord set index for the clearcoat normal map. */
    var clearCoatNormalUV: Int
    /** The clearcoat layer is enabled (`KHR_materials_clearcoat`). */
    var hasClearCoat: Boolean
    /** Transmission is enabled (`KHR_materials_transmission`). */
    var hasTransmission: Boolean
    /** One or more textures use `KHR_texture_transform`. */
    var hasTextureTransforms: Boolean
    /** glTF texcoord set index for the emissive texture. */
    var emissiveUV: Int
    /** glTF texcoord set index for the ambient-occlusion texture. */
    var aoUV: Int
    /** glTF texcoord set index for the normal map. */
    var normalUV: Int
    /** A transmission texture is bound. */
    var hasTransmissionTexture: Boolean
    /** glTF texcoord set index for the transmission texture. */
    var transmissionUV: Int
    /** A sheen color texture is bound. */
    var hasSheenColorTexture: Boolean
    /** glTF texcoord set index for the sheen color texture. */
    var sheenColorUV: Int
    /** A sheen roughness texture is bound. */
    var hasSheenRoughnessTexture: Boolean
    /** glTF texcoord set index for the sheen roughness texture. */
    var sheenRoughnessUV: Int
    /** A volume thickness texture is bound (`KHR_materials_volume`). */
    var hasVolumeThicknessTexture: Boolean
    /** glTF texcoord set index for the volume thickness texture. */
    var volumeThicknessUV: Int
    /** The sheen layer is enabled (`KHR_materials_sheen`). */
    var hasSheen: Boolean
    /** A custom index of refraction is set (`KHR_materials_ior`). */
    var hasIOR: Boolean

    /**
     * Mutates this key to trim requested features down to what the provider supports, and fills
     * [uvmap] with the resulting glTF-texcoord → Filament-UV-set mapping. Called by providers
     * before material creation.
     */
    fun constrainMaterial(uvmap: IntArray)
}

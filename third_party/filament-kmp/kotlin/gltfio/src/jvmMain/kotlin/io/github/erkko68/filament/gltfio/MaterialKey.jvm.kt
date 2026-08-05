package io.github.erkko68.filament.gltfio

import io.github.erkko68.filament.confined
import io.github.erkko68.filament.ffm.FilaMaterialKey
import io.github.erkko68.filament.ffm.FilaMaterialKeyFields
import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.MemorySegment
import java.lang.foreign.SegmentAllocator
import java.lang.foreign.ValueLayout

actual class MaterialKey actual constructor() {
    actual var doubleSided: Boolean = false
    actual var unlit: Boolean = false
    actual var hasVertexColors: Boolean = false
    actual var hasBaseColorTexture: Boolean = false
    actual var hasNormalTexture: Boolean = false
    actual var hasOcclusionTexture: Boolean = false
    actual var hasEmissiveTexture: Boolean = false
    actual var useSpecularGlossiness: Boolean = false
    actual var alphaMode: Int = 0
    actual var enableDiagnostics: Boolean = false
    actual var hasMetallicRoughnessTexture: Boolean = false
    actual var metallicRoughnessUV: Int = 0
    actual var baseColorUV: Int = 0
    actual var hasClearCoatTexture: Boolean = false
    actual var clearCoatUV: Int = 0
    actual var hasClearCoatRoughnessTexture: Boolean = false
    actual var clearCoatRoughnessUV: Int = 0
    actual var hasClearCoatNormalTexture: Boolean = false
    actual var clearCoatNormalUV: Int = 0
    actual var hasClearCoat: Boolean = false
    actual var hasTransmission: Boolean = false
    actual var hasTextureTransforms: Boolean = false
    actual var emissiveUV: Int = 0
    actual var aoUV: Int = 0
    actual var normalUV: Int = 0
    actual var hasTransmissionTexture: Boolean = false
    actual var transmissionUV: Int = 0
    actual var hasSheenColorTexture: Boolean = false
    actual var sheenColorUV: Int = 0
    actual var hasSheenRoughnessTexture: Boolean = false
    actual var sheenRoughnessUV: Int = 0
    actual var hasVolumeThicknessTexture: Boolean = false
    actual var volumeThicknessUV: Int = 0
    actual var hasSheen: Boolean = false
    actual var hasIOR: Boolean = false

    /** Allocates a packed FilaMaterialKey (5 uint32 words) in [a] from these fields. */
    internal fun toNative(a: SegmentAllocator): MemorySegment {
        val fields = FilaMaterialKeyFields.allocate(a)
        val key = FilaMaterialKey.allocate(a)
        FilaMaterialKeyFields.doubleSided(fields, doubleSided)
        FilaMaterialKeyFields.unlit(fields, unlit)
        FilaMaterialKeyFields.hasVertexColors(fields, hasVertexColors)
        FilaMaterialKeyFields.hasBaseColorTexture(fields, hasBaseColorTexture)
        FilaMaterialKeyFields.hasNormalTexture(fields, hasNormalTexture)
        FilaMaterialKeyFields.hasOcclusionTexture(fields, hasOcclusionTexture)
        FilaMaterialKeyFields.hasEmissiveTexture(fields, hasEmissiveTexture)
        FilaMaterialKeyFields.useSpecularGlossiness(fields, useSpecularGlossiness)
        FilaMaterialKeyFields.alphaMode(fields, alphaMode.toByte())
        FilaMaterialKeyFields.enableDiagnostics(fields, if (enableDiagnostics) 1 else 0)
        FilaMaterialKeyFields.hasMetallicRoughnessTexture(fields, hasMetallicRoughnessTexture)
        FilaMaterialKeyFields.metallicRoughnessUV(fields, metallicRoughnessUV.toByte())
        FilaMaterialKeyFields.baseColorUV(fields, baseColorUV.toByte())
        FilaMaterialKeyFields.hasClearCoatTexture(fields, hasClearCoatTexture)
        FilaMaterialKeyFields.clearCoatUV(fields, clearCoatUV.toByte())
        FilaMaterialKeyFields.hasClearCoatRoughnessTexture(fields, hasClearCoatRoughnessTexture)
        FilaMaterialKeyFields.clearCoatRoughnessUV(fields, clearCoatRoughnessUV.toByte())
        FilaMaterialKeyFields.hasClearCoatNormalTexture(fields, hasClearCoatNormalTexture)
        FilaMaterialKeyFields.clearCoatNormalUV(fields, clearCoatNormalUV.toByte())
        FilaMaterialKeyFields.hasClearCoat(fields, hasClearCoat)
        FilaMaterialKeyFields.hasTransmission(fields, hasTransmission)
        FilaMaterialKeyFields.hasTextureTransforms(fields, if (hasTextureTransforms) 1 else 0)
        FilaMaterialKeyFields.emissiveUV(fields, emissiveUV.toByte())
        FilaMaterialKeyFields.aoUV(fields, aoUV.toByte())
        FilaMaterialKeyFields.normalUV(fields, normalUV.toByte())
        FilaMaterialKeyFields.hasTransmissionTexture(fields, hasTransmissionTexture)
        FilaMaterialKeyFields.transmissionUV(fields, transmissionUV.toByte())
        FilaMaterialKeyFields.hasSheenColorTexture(fields, hasSheenColorTexture)
        FilaMaterialKeyFields.sheenColorUV(fields, sheenColorUV.toByte())
        FilaMaterialKeyFields.hasSheenRoughnessTexture(fields, hasSheenRoughnessTexture)
        FilaMaterialKeyFields.sheenRoughnessUV(fields, sheenRoughnessUV.toByte())
        FilaMaterialKeyFields.hasVolumeThicknessTexture(fields, hasVolumeThicknessTexture)
        FilaMaterialKeyFields.volumeThicknessUV(fields, volumeThicknessUV.toByte())
        FilaMaterialKeyFields.hasSheen(fields, hasSheen)
        FilaMaterialKeyFields.hasIOR(fields, hasIOR)
        FilamentC.FilaMaterialKey_pack(fields, key)
        return key
    }

    private fun fromFields(fields: MemorySegment) {
        doubleSided = FilaMaterialKeyFields.doubleSided(fields)
        unlit = FilaMaterialKeyFields.unlit(fields)
        hasVertexColors = FilaMaterialKeyFields.hasVertexColors(fields)
        hasBaseColorTexture = FilaMaterialKeyFields.hasBaseColorTexture(fields)
        hasNormalTexture = FilaMaterialKeyFields.hasNormalTexture(fields)
        hasOcclusionTexture = FilaMaterialKeyFields.hasOcclusionTexture(fields)
        hasEmissiveTexture = FilaMaterialKeyFields.hasEmissiveTexture(fields)
        useSpecularGlossiness = FilaMaterialKeyFields.useSpecularGlossiness(fields)
        alphaMode = FilaMaterialKeyFields.alphaMode(fields).toInt()
        enableDiagnostics = FilaMaterialKeyFields.enableDiagnostics(fields).toInt() != 0
        hasMetallicRoughnessTexture = FilaMaterialKeyFields.hasMetallicRoughnessTexture(fields)
        metallicRoughnessUV = FilaMaterialKeyFields.metallicRoughnessUV(fields).toInt()
        baseColorUV = FilaMaterialKeyFields.baseColorUV(fields).toInt()
        hasClearCoatTexture = FilaMaterialKeyFields.hasClearCoatTexture(fields)
        clearCoatUV = FilaMaterialKeyFields.clearCoatUV(fields).toInt()
        hasClearCoatRoughnessTexture = FilaMaterialKeyFields.hasClearCoatRoughnessTexture(fields)
        clearCoatRoughnessUV = FilaMaterialKeyFields.clearCoatRoughnessUV(fields).toInt()
        hasClearCoatNormalTexture = FilaMaterialKeyFields.hasClearCoatNormalTexture(fields)
        clearCoatNormalUV = FilaMaterialKeyFields.clearCoatNormalUV(fields).toInt()
        hasClearCoat = FilaMaterialKeyFields.hasClearCoat(fields)
        hasTransmission = FilaMaterialKeyFields.hasTransmission(fields)
        hasTextureTransforms = FilaMaterialKeyFields.hasTextureTransforms(fields).toInt() != 0
        emissiveUV = FilaMaterialKeyFields.emissiveUV(fields).toInt()
        aoUV = FilaMaterialKeyFields.aoUV(fields).toInt()
        normalUV = FilaMaterialKeyFields.normalUV(fields).toInt()
        hasTransmissionTexture = FilaMaterialKeyFields.hasTransmissionTexture(fields)
        transmissionUV = FilaMaterialKeyFields.transmissionUV(fields).toInt()
        hasSheenColorTexture = FilaMaterialKeyFields.hasSheenColorTexture(fields)
        sheenColorUV = FilaMaterialKeyFields.sheenColorUV(fields).toInt()
        hasSheenRoughnessTexture = FilaMaterialKeyFields.hasSheenRoughnessTexture(fields)
        sheenRoughnessUV = FilaMaterialKeyFields.sheenRoughnessUV(fields).toInt()
        hasVolumeThicknessTexture = FilaMaterialKeyFields.hasVolumeThicknessTexture(fields)
        volumeThicknessUV = FilaMaterialKeyFields.volumeThicknessUV(fields).toInt()
        hasSheen = FilaMaterialKeyFields.hasSheen(fields)
        hasIOR = FilaMaterialKeyFields.hasIOR(fields)
    }

    actual fun constrainMaterial(uvmap: IntArray): Unit = confined { a ->
        val key = toNative(a)
        val uv = a.allocate(ValueLayout.JAVA_BYTE, 8)
        for (i in 0 until 8) uv.set(ValueLayout.JAVA_BYTE, i.toLong(), uvmap.getOrElse(i) { 0 }.toByte())
        FilamentC.FilaMaterialKey_constrainMaterial(key, uv)
        for (i in 0 until minOf(8, uvmap.size)) uvmap[i] = uv.get(ValueLayout.JAVA_BYTE, i.toLong()).toInt()
        // Sync back any key changes constrainMaterial made.
        val fields = FilaMaterialKeyFields.allocate(a)
        FilamentC.FilaMaterialKey_unpack(key, fields)
        fromFields(fields)
    }
}

package io.github.sceneview.demo.demos.internal

import com.google.android.filament.MaterialInstance
import io.github.sceneview.demo.demos.internal.feel.FeelMaterial
import io.github.sceneview.demo.demos.internal.feel.FeelSimulation
import io.github.sceneview.demo.demos.internal.feel.MatterMeshes
import io.github.sceneview.demo.demos.internal.feel.MatterSurfaces
import io.github.sceneview.loaders.MaterialLoader
import kotlin.math.exp
import kotlin.math.sin

/** Filament material instances, created/updated/destroyed on the composition's main thread. */
internal class MatterSurfaceMaterials(private val loader: MaterialLoader) {
    private val opaque = loader.createMaterial("materials/matter_surface.filamat")
    private val gel = loader.createMaterial("materials/matter_gel.filamat")
    val samples = FeelMaterial.entries.map { kind ->
        val finish = MatterSurfaces[kind]
        loader.createInstance(if (kind == FeelMaterial.Gel) gel else opaque).apply {
            setParameter("finger", 0f, 0f)
            setParameter("depth", 0f)
            setParameter("bounce", 0f)
            setParameter("radius", kind.radius)
            setParameter("profile", finish.falloff, finish.bulge, finish.wrinkles, finish.foldCount)
            setParameter("dimensions", FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_THICKNESS,
                FeelSimulation.PAD_HALF_LENGTH, MatterMeshes.BEVEL)
            setParameter("albedo", finish.red, finish.green, finish.blue)
            setParameter("roughness", finish.roughness)
            if (kind == FeelMaterial.Gel) {
                setParameter("ior", MatterSurfaces.GEL_IOR)
                setParameter("transmission", MatterSurfaces.GEL_TRANSMISSION)
                setParameter("absorption", MatterSurfaces.GEL_ABSORPTION_RED,
                    MatterSurfaces.GEL_ABSORPTION_GREEN, MatterSurfaces.GEL_ABSORPTION_BLUE)
            } else {
                setParameter("surface", kind.ordinal.toFloat())
                setParameter("metallic", finish.metallic)
                setParameter("coat", finish.coat)
                setParameter("coatRoughness", finish.coatRoughness)
                setParameter("sheen", finish.sheen)
                setParameter("sheenRoughness", finish.sheenRoughness)
                setParameter("anisotropy", finish.anisotropy)
                setParameter("ripple", 0f, 0f, 0f, 0f)
                setParameter("rippleColor", 0f, 0f, 0f)
                setParameter("rippleWidth", 0.01f)
            }
        }
    }
    val mark: MaterialInstance = loader.createInstance(loader.createMaterial("materials/studio_pbr.filamat")).apply {
        val finish = MatterSurfaces.ceramic
        setParameter("color", finish.red, finish.green, finish.blue, 1f)
        setParameter("metallic", finish.metallic)
        setParameter("roughness", finish.roughness)
        setParameter("reflectance", 0.5f)
        setParameter("clearCoat", finish.coat)
        setParameter("clearCoatRoughness", finish.coatRoughness)
        setParameter("sheenColor", 0f, 0f, 0f)
        setParameter("sheenRoughness", 1f)
        setParameter("emissive", 0f, 0f, 0f)
    }

    fun update(kind: FeelMaterial, simulation: FeelSimulation, now: Long, motion: Boolean, ringWidth: Float) {
        val i = kind.ordinal
        samples[i].apply {
            setParameter("finger", simulation.fingerX[i], simulation.fingerZ[i])
            setParameter("depth", MatterSurfaces.visualDepth(simulation.pads[i].depthMm, kind))
            val age = if (simulation.contactMillis[i] == Long.MIN_VALUE) Long.MAX_VALUE else now - simulation.contactMillis[i]
            val ripple = kind.rigid && motion && age in 0L until MatterMeshes.RIPPLE_MILLIS
            // Upwards only, under 0.72 illustrative mm: the tile never enters the floor.
            val bounce = if (ripple) {
                val wave = sin(age / 55f)
                0.018f * exp(-age / 110f) * wave * wave
            } else 0f
            setParameter("bounce", bounce)
            if (kind != FeelMaterial.Gel) {
                val progress = if (ripple) age.toFloat() / MatterMeshes.RIPPLE_MILLIS else 1f
                setParameter("ripple", simulation.contactX[i], simulation.contactZ[i],
                    .08f + .16f * progress, if (ripple) 1f - progress else 0f)
                setParameter("rippleWidth", ringWidth)
            }
        }
    }

    fun destroy() {
        samples.forEach(loader::destroyMaterialInstance)
        loader.destroyMaterialInstance(mark)
        // MaterialLoader owns the three parent material packages.
    }
}

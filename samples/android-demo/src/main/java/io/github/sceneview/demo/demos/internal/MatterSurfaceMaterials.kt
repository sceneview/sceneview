package io.github.sceneview.demo.demos.internal

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import io.github.sceneview.demo.demos.internal.feel.FeelMaterial
import io.github.sceneview.demo.demos.internal.feel.FeelSimulation
import io.github.sceneview.demo.demos.internal.feel.MatterMeshes
import io.github.sceneview.demo.demos.internal.feel.MatterDetailAtlas
import io.github.sceneview.demo.demos.internal.feel.MatterTransition
import io.github.sceneview.demo.demos.internal.feel.MatterSurfaces
import io.github.sceneview.loaders.MaterialLoader
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.sin

/** Filament material instances, created/updated/destroyed on the composition's main thread. */
internal class MatterSurfaceMaterials(private val engine: Engine, private val loader: MaterialLoader) {
    private val textures = mutableMapOf<FeelMaterial, Texture>()
    var detailVersion = 0L
        private set
    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    private val fallback = Texture.Builder().width(1).height(1).levels(1)
        .format(Texture.InternalFormat.RG8).build(engine).apply {
            setImage(engine, 0, Texture.PixelBufferDescriptor(
                ByteBuffer.allocateDirect(2).put(byteArrayOf(128.toByte(), 128.toByte())).flip(),
                Texture.Format.RG, Texture.Type.UBYTE, 1))
        }
    private val packages = listOf("soft", "gel", "velvet", "leather", "marble", "steel")
        .map { loader.createMaterial("materials/matter_$it.filamat") }
    val samples = FeelMaterial.entries.map { kind ->
        val finish = MatterSurfaces[kind]
        loader.createInstance(packages[kind.ordinal]).apply {
            setParameter("finger", 0f, 0f)
            setParameter("depth", 0f)
            setParameter("bounce", 0f)
            setParameter("squash", 0f)
            setParameter("radius", kind.radius)
            setParameter("profile", finish.falloff, finish.bulge, finish.wrinkles, finish.foldCount)
            setParameter("rimShape", finish.rimRadius, finish.rimWidth)
            setParameter("trailMode", 0f)
            setParameter("trail", MaterialInstance.FloatElement.FLOAT4,
                FloatArray(FeelSimulation.TRAIL_POINTS * 4), 0, FeelSimulation.TRAIL_POINTS)
            setParameter("releaseWave", 0f, 0f, 0f, 0f)
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
                setParameter("detail", fallback, sampler)
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

    fun hasDetail(kind: FeelMaterial) = kind == FeelMaterial.Gel || kind in textures

    /** Called only from the main-thread LaunchedEffect, after the cancellable CPU bake. */
    fun uploadDetail(kind: FeelMaterial, pixels: ByteArray) {
        check(!hasDetail(kind))
        require(pixels.size == MatterDetailAtlas.WIDTH * MatterDetailAtlas.HEIGHT * 2)
        val texture = Texture.Builder().width(MatterDetailAtlas.WIDTH).height(MatterDetailAtlas.HEIGHT)
            .levels(0xff).format(Texture.InternalFormat.RG8)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE).build(engine)
        textures[kind] = texture
        val buffer = ByteBuffer.allocateDirect(pixels.size).put(pixels).apply { flip() }
        texture.setImage(engine, 0, Texture.PixelBufferDescriptor(buffer, Texture.Format.RG, Texture.Type.UBYTE, 1))
        texture.generateMipmaps(engine)
        samples[kind.ordinal].setParameter("detail", texture, sampler)
        detailVersion++
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

    fun update(kind: FeelMaterial, simulation: FeelSimulation, now: Long, motion: Boolean, ringWidth: Float,
               landingAge: Float = -1f) {
        val i = kind.ordinal
        samples[i].apply {
            setParameter("finger", simulation.dentX[i], simulation.dentZ[i])
            val settling = if (motion && simulation.active != kind) MatterTransition.settleDepth(kind, landingAge) else 0f
            setParameter("radius", simulation.radius(kind))
            setParameter("depth", MatterSurfaces.visualDepth(simulation.pads[i].depthMm, kind) + settling)
            setParameter("squash", if (kind == FeelMaterial.MemoryFoam) simulation.opening.squash else 0f)
            if (kind == FeelMaterial.MemoryFoam || kind == FeelMaterial.Velvet) {
                val trail = simulation.trails[i]
                val hasTrail = (0 until FeelSimulation.TRAIL_POINTS).any { trail[it * 4 + 2] > 0f }
                setParameter("trailMode", if (!hasTrail) 0f else if (kind == FeelMaterial.MemoryFoam) 1f else 2f)
                setParameter("trail", MaterialInstance.FloatElement.FLOAT4, trail, 0, FeelSimulation.TRAIL_POINTS)
            }
            if (kind == FeelMaterial.Gel) {
                val age = if (simulation.gelReleasedAt == Long.MIN_VALUE) -1f else (now - simulation.gelReleasedAt) / 1000f
                val tail = ((FeelSimulation.GEL_WAVE_SECONDS - age) / .6f).coerceIn(0f, 1f)
                val amplitude = if (motion && age in 0f..FeelSimulation.GEL_WAVE_SECONDS) {
                    simulation.gelReleaseDepth * .12f * exp(-1.4f * age) *
                        (age / .15f).coerceIn(0f, 1f) * tail * tail * (3f - 2f * tail)
                } else 0f
                setParameter("releaseWave", simulation.gelReleaseX, simulation.gelReleaseZ, age, amplitude)
            }
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
        textures.values.forEach(engine::destroyTexture)
        engine.destroyTexture(fallback)
        // MaterialLoader owns the parent material packages.
    }
}

package io.github.sceneview.demo.demos.internal

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import com.google.android.filament.Material
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
internal class MatterSurfaceMaterials(
    private val engine: Engine,
    private val loader: MaterialLoader,
    foamPayload: ByteBuffer,
    markPayload: ByteBuffer,
    floorPayload: ByteBuffer,
) {
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
    companion object {
        fun asset(kind: FeelMaterial) = "materials/matter_${listOf("soft", "gel", "velvet", "leather", "marble", "steel")[kind.ordinal]}.filamat"
    }
    val floor = loader.createInstance(loader.createMaterial(floorPayload)).apply {
        setParameter("contact", 0f, 0f, 1f)
        setParameter("halfSize", FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_HALF_LENGTH)
    }
    val samples = mutableStateMapOf<FeelMaterial, MaterialInstance>()
    private val instances = mutableMapOf<FeelMaterial, MaterialInstance>()
    private val detailUploadedAt = mutableMapOf<FeelMaterial, Long>()
    private var compiling = false
    private var destroyed = false
    val needsWarmup get() = instances.size < FeelMaterial.entries.size || compiling
    fun hasPrepared(kind: FeelMaterial) = kind in instances

    init {
        val kind = FeelMaterial.MemoryFoam
        samples[kind] = create(kind, loader.createMaterial(foamPayload))
    }

    private fun create(kind: FeelMaterial, material: Material): MaterialInstance {
        val finish = MatterSurfaces[kind]
        return loader.createInstance(material).apply {
            if (kind == FeelMaterial.MemoryFoam) setParameter("imprint", 0f, 0f, 0f)
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
                setParameter("detail", textures[kind] ?: fallback, sampler)
                setParameter("detailBlend", if (kind in textures) 1f else 0f)
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
        }.also { instances[kind] = it }
    }

    /** One family per idle frame, and only one backend compilation in flight. */
    fun warmOne(kind: FeelMaterial, payload: ByteBuffer, onReady: () -> Unit): Boolean {
        if (compiling || hasPrepared(kind)) return false
        val material = loader.createMaterial(payload)
        val instance = create(kind, material)
        compiling = true
        // Warm the studio key, local fill/rim and soft-shadow receiver variants.
        material.compile(Material.CompilerPriorityQueue.LOW,
            Material.UserVariantFilterBit.DIRECTIONAL_LIGHTING or Material.UserVariantFilterBit.SHADOW_RECEIVER or
                Material.UserVariantFilterBit.DYNAMIC_LIGHTING,
            Handler(Looper.getMainLooper()), Runnable {
                if (!destroyed) {
                    compiling = false
                    samples[kind] = instance
                    onReady()
                }
            })
        return true
    }

    /** Blend the field before shading, so tint, roughness and relief arrive together. */
    fun advanceDetail(now: Long): Boolean {
        var fading = false
        for ((kind, uploadedAt) in detailUploadedAt) {
            val blend = ((now - uploadedAt) / 300f).coerceIn(0f, 1f)
            val previous = detailBlend[kind] ?: -1f
            if (previous != blend) {
                instances[kind]?.setParameter("detailBlend", blend)
                detailBlend[kind] = blend
                detailVersion++
            }
            if (blend < 1f) fading = true
        }
        return fading
    }
    private val detailBlend = mutableMapOf<FeelMaterial, Float>()

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
        instances[kind]?.setParameter("detail", texture, sampler)
        instances[kind]?.setParameter("detailBlend", 0f)
        detailUploadedAt[kind] = SystemClock.uptimeMillis()
        detailBlend[kind] = 0f
        detailVersion++
    }
    private val markMaterial = loader.createMaterial(markPayload)
    val mark: List<MaterialInstance> = List(3) {
        loader.createInstance(markMaterial).apply { setParameter("color", 1f, 1f, 1f, 1f) }
    }

    fun update(kind: FeelMaterial, simulation: FeelSimulation, now: Long, motion: Boolean, ringWidth: Float,
               landingAge: Float = -1f) {
        val i = kind.ordinal
        (samples[kind] ?: return).apply {
            if (kind == FeelMaterial.MemoryFoam) {
                setParameter("imprint", simulation.imprintX, simulation.imprintZ, simulation.imprintStrength)
            }
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
        destroyed = true
        instances.values.forEach(loader::destroyMaterialInstance)
        mark.forEach(loader::destroyMaterialInstance)
        loader.destroyMaterialInstance(floor)
        textures.values.forEach(engine::destroyTexture)
        engine.destroyTexture(fallback)
        // MaterialLoader owns the parent material packages.
    }
}

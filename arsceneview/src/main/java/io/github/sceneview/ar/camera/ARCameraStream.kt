package io.github.sceneview.ar.camera

import android.util.Log
import androidx.compose.ui.graphics.Color
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndexBuffer.Builder.IndexType
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.Texture.PixelBufferDescriptor
import com.google.android.filament.VertexBuffer
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.arcore.semanticImage
import io.github.sceneview.ar.node.ARFogConfig
import io.github.sceneview.components.RenderableComponent
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.managers.safeDestroy
import io.github.sceneview.material.setExternalTexture
import io.github.sceneview.material.setParameter
import io.github.sceneview.material.setTexture
import io.github.sceneview.math.Transform
import io.github.sceneview.safeDestroyTexture
import io.github.sceneview.safeDestroyVertexBuffer
import io.github.sceneview.utils.OpenGL
import io.github.sceneview.utils.clone
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

const val kUVTransformParameter = "uvTransform"
const val kCameraTextureParameter = "cameraTexture"
const val kDepthTextureParameter = "depthTexture"

// People occlusion (issue #1761). The `personTexture` sampler holds the binary PERSON mask
// derived from ARCore Scene Semantics — see `camera_stream_person_occlusion.mat`.
const val kPersonTextureParameter = "personTexture"

// Environment-aware AR fog (issue #1717). Parameter names match the
// `camera_stream_depth.mat` declarations 1:1 so the Filament binding is a
// straight pass-through. Defaults mirror `FogNode`'s virtual-fog defaults so
// real + virtual fog visually align out of the box.
const val kFogEnabledParameter = "fogEnabled"
const val kFogColorParameter = "fogColor"
const val kFogDensityParameter = "fogDensity"
const val kFogStartParameter = "fogStart"
const val kFogEndParameter = "fogEnd"

/**
 * Renders the live AR camera feed as the scene background using Filament.
 *
 * The camera stream owns OpenGL external textures that receive frames from ARCore, and a
 * full-screen Filament renderable that draws the active texture behind all scene content.
 * It also supports **depth occlusion** — when enabled, virtual objects are correctly hidden
 * behind real-world surfaces using the ARCore depth image — and **people occlusion**
 * ([isPersonOcclusionEnabled], #1761) — virtual content is hidden behind real people using
 * the ARCore Scene Semantics `PERSON`-class segmentation mask.
 *
 * Typically created via [rememberARCameraStream] and passed to `ARSceneView(cameraStream = ...)`.
 *
 * @param materialLoader               [MaterialLoader] for creating the camera background materials.
 * @param standardMaterialFile         Asset path for the flat (non-depth) camera material.
 * @param depthOcclusionMaterialFile   Asset path for the depth-aware occlusion material.
 * @param personOcclusionMaterialFile  Asset path for the Scene Semantics people-occlusion material.
 */
open class ARCameraStream(
    private val materialLoader: MaterialLoader,
    standardMaterialFile: String = "materials/camera_stream_flat.filamat",
    depthOcclusionMaterialFile: String = "materials/camera_stream_depth.filamat",
    personOcclusionMaterialFile: String = "materials/camera_stream_person_occlusion.filamat",
) : RenderableComponent {

    final override val engine: Engine = materialLoader.engine

    final override val entity = EntityManager.get().create()

    /**
     * Passing multiple textures allows for a multithreaded rendering pipeline
     */
    val cameraTextureIds = IntArray(6) { OpenGL.createExternalTextureId() }

    /**
     * Textures buffer
     */
    var cameraTextures: Map<Int, Texture> = cameraTextureIds.associateWith { cameraTextureId ->
        Texture.Builder().sampler(Texture.Sampler.SAMPLER_EXTERNAL)
            .format(Texture.InternalFormat.RGB16F)
            .importTexture(cameraTextureId.toLong())
            .build(engine)
    }

    /**
     * We apply the multithreaded actual rendering (Frame) texture
     */
    var cameraTexture: Texture = checkNotNull(cameraTextures[cameraTextureIds[0]]) {
            "Camera texture not found for ID ${cameraTextureIds[0]}"
        }
        set(value) {
            if (field != value) {
                field = value
                materialInstance.setExternalTexture(kCameraTextureParameter, value)
            }
        }

    /**
     * Extracted texture from the session depth image
     */
    val depthTexture =
        Texture.Builder().sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RG8)
            .levels(1).build(engine)

    /**
     * ### Flat camera material
     */
    val standardMaterial = materialLoader.createMaterial(standardMaterialFile).apply {
        defaultInstance.apply {
            setParameter(kUVTransformParameter, Transform())
            setExternalTexture(kCameraTextureParameter, cameraTexture)
        }
    }

    /**
     * ### Depth occlusion material
     *
     * Also carries the environment-aware AR fog parameters (issue #1717).
     * Fog is initialised disabled with the same defaults as
     * [io.github.sceneview.node.FogNode] so the material is a strict superset
     * of the previous depth-only behaviour. Drive the fog from the public
     * [io.github.sceneview.ar.node.ARFogNode] composable, or set
     * [arFog] imperatively.
     */
    var depthOcclusionMaterial = materialLoader.createMaterial(depthOcclusionMaterialFile).apply {
        defaultInstance.apply {
            setParameter(kUVTransformParameter, Transform())
            setExternalTexture(kCameraTextureParameter, cameraTexture)
            setTexture(kDepthTextureParameter, depthTexture)
            // Fog defaults — match `FogNode`'s defaults for parameter parity.
            setParameter(kFogEnabledParameter, 0f)
            setParameter(kFogColorParameter, Float3(0.8f, 0.866f, 1.0f))
            setParameter(kFogDensityParameter, 0.05f)
            setParameter(kFogStartParameter, 0f)
            setParameter(kFogEndParameter, 30f)
        }
    }

    /**
     * Single-channel `R8` texture holding the binary PERSON occlusion mask (issue #1761).
     *
     * Re-uploaded every frame from [PersonMask.build] while [isPersonOcclusionEnabled] is
     * `true`: `255` where ARCore Scene Semantics classified the pixel as `PERSON`, `0`
     * elsewhere.
     *
     * Sized lazily — ARCore's semantic image resolution (typically 256×144) is not known
     * until the first frame, and a Filament [Texture] is immutable-size. The texture is
     * (re)built by [updatePersonMask] the first time a semantic image arrives, and again if
     * the resolution ever changes (display rotation, camera-config switch). Starts as a
     * 1×1 placeholder so the material always has a valid sampler bound — a 1×1 R8 texel of
     * value 0 reads as "no person", so people occlusion is a clean no-op until the first
     * real mask lands.
     */
    var personMaskTexture: Texture =
        Texture.Builder().width(1).height(1)
            .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.R8)
            .levels(1).build(engine)
        private set

    // Current dimensions of [personMaskTexture]. (0, 0) means "still the 1×1 placeholder,
    // never had a real semantic image yet". A resolution change rebuilds the texture.
    private var personMaskWidth = 0
    private var personMaskHeight = 0

    /**
     * ### People occlusion material (issue #1761)
     *
     * Strict superset of [depthOcclusionMaterial]: keeps the ARCore depth-occlusion path and
     * the environment-aware fog verbatim, and additionally samples [personMaskTexture] — the
     * Scene Semantics `PERSON`-class mask — to push the camera fragment to the near plane
     * wherever a real person is detected, so virtual geometry behind the person is
     * depth-tested away.
     *
     * Selected over [depthOcclusionMaterial] when [isPersonOcclusionEnabled] is `true`.
     */
    var personOcclusionMaterial =
        materialLoader.createMaterial(personOcclusionMaterialFile).apply {
            defaultInstance.apply {
                setParameter(kUVTransformParameter, Transform())
                setExternalTexture(kCameraTextureParameter, cameraTexture)
                setTexture(kDepthTextureParameter, depthTexture)
                setTexture(kPersonTextureParameter, personMaskTexture)
                // Fog defaults — match `FogNode`'s defaults for parameter parity.
                setParameter(kFogEnabledParameter, 0f)
                setParameter(kFogColorParameter, Float3(0.8f, 0.866f, 1.0f))
                setParameter(kFogDensityParameter, 0.05f)
                setParameter(kFogStartParameter, 0f)
                setParameter(kFogEndParameter, 30f)
            }
        }

    /**
     * Environment-aware AR fog config (issue #1717).
     *
     * Set to a non-null value to fade distant real-world geometry into a
     * coloured haze using the ARCore depth image. Mirrors the parameter set of
     * [io.github.sceneview.node.FogNode] (`color`, `density`, `start`, `end`)
     * so the same numbers fog both the real world and virtual geometry
     * consistently.
     *
     * Requires [isDepthOcclusionEnabled] to be `true` — without depth pixels
     * we have nothing to drive the per-pixel fog factor. The setter does NOT
     * force depth occlusion on, since some callers may want to gate both
     * flags on the same user toggle; ensure both are set together.
     *
     * Default `null` keeps fog disabled at zero shader cost (the fog mix
     * collapses to a no-op via `fogEnabled = 0`).
     */
    var arFog: ARFogConfig? = null
        set(value) {
            field = value
            applyARFog(value)
        }

    private fun applyARFog(config: ARFogConfig?) {
        // Both the depth and the people-occlusion materials carry the identical fog
        // parameter set (`camera_stream_person_occlusion.mat` is a strict superset of
        // `camera_stream_depth.mat`). Drive both so fog stays consistent regardless of
        // which occlusion material is currently bound.
        for (instance in listOf(
            depthOcclusionMaterial.defaultInstance,
            personOcclusionMaterial.defaultInstance
        )) {
            instance.apply {
                if (config == null || !config.enabled) {
                    setParameter(kFogEnabledParameter, 0f)
                    return@apply
                }
                setParameter(kFogEnabledParameter, 1f)
                // Compose Color is sRGB; the shader does its haze blend in the
                // linear-ish space Filament hands us via `inverseTonemapSRGB`.
                // Use the linear components straight (`red`/`green`/`blue` on
                // Compose Color are already in the colour-space's component
                // domain) — matches how `FogNode` writes to `fogOptions.color`.
                setParameter(
                    kFogColorParameter,
                    Float3(config.color.red, config.color.green, config.color.blue)
                )
                setParameter(kFogDensityParameter, config.density.coerceIn(0f, 1f))
                setParameter(kFogStartParameter, config.start.coerceAtLeast(0f))
                setParameter(kFogEndParameter, config.end.coerceAtLeast(config.start + 0.001f))
            }
        }
    }

    /**
     * Depending on [isDepthOcclusionEnabled] and device Depth compatibility
     */
    override fun setMaterialInstances(materialInstance: MaterialInstance) {
        materialInstance.setExternalTexture(kCameraTextureParameter, cameraTexture)
        super.setMaterialInstances(materialInstance)
    }

    /**
     * ### Enable the depth occlusion material
     *
     * This will process the incoming DepthImage to occlude virtual objects behind real world
     * objects.
     *
     * If the [Session] is not configured properly the standard camera material is used.
     * Valid [Session] configuration for the DepthMode are [Config.DepthMode.AUTOMATIC] and
     * [Config.DepthMode.RAW_DEPTH_ONLY]
     *
     * Disable this value to apply the standard camera material to the CameraStream.
     *
     * ### Depth ByteBuffer lifecycle (#1757)
     *
     * The depth [ByteBuffer] handed to Filament via [PixelBufferDescriptor] is borrowed
     * from the ARCore [com.google.ar.core.Image] — **not cloned**. Closure of the ARCore
     * image is serialised to the descriptor's upload-completed callback, so a stale
     * read after `Image.close()` is structurally impossible:
     *
     *  - Filament keeps the [PixelBufferDescriptor] alive until the GPU transfer
     *    finishes; only then does the callback fire and call [Image.close].
     *  - Toggling this property mid-upload is safe: the setter only swaps material
     *    instances, never the in-flight buffer. The upload-completed callback still
     *    fires and closes its own image. See the long-form comment in [update].
     *  - On [destroy], Filament cancels in-flight uploads BEFORE freeing
     *    [depthTexture], so the callback still fires and the image is closed exactly
     *    once.
     */
    var isDepthOcclusionEnabled = false
        set(value) {
            if (field != value) {
                field = value
                applyOcclusionMaterial()
            }
        }

    /**
     * ### Enable people occlusion (issue #1761)
     *
     * Occludes virtual objects behind **real people** using ARCore's Scene Semantics
     * `PERSON`-class segmentation mask. When a real person walks in front of a placed virtual
     * object, the object is correctly hidden — the flagship cross-ecosystem AR effect (ARKit
     * `ARFrame.segmentationBuffer`, AR Foundation `AROcclusionManager`, Niantic Lightship).
     *
     * Enabling this selects [personOcclusionMaterial], which is a strict superset of
     * [depthOcclusionMaterial]: it keeps the depth-occlusion path (static real-world geometry
     * still occludes correctly) and additionally pushes the camera fragment to the near
     * plane wherever the PERSON mask is set. People occlusion therefore **implies depth
     * occlusion** — when this is `true` the depth material is never selected even if
     * [isDepthOcclusionEnabled] is also `true`.
     *
     * ## Requirements
     *
     * Requires the ARCore [Session] to be configured with
     * [Config.SemanticMode.ENABLED] — without it [com.google.ar.core.Frame.acquireSemanticImage]
     * never produces a mask and the camera feed shows through un-occluded. SceneView does NOT
     * auto-enable `Config.SemanticMode`; set it in your `sessionConfiguration` block (or via
     * `ARSceneView(semanticMode = …)`).
     *
     * ## Caveats (be honest with users)
     *
     * Scene Semantics is ARCore's only segmentation surface and it carries real limits:
     *
     *  - **Outdoor scenes only.** The on-device ML model has no indoor training data — indoor
     *    people may not be classified at all. Devices without the model silently downgrade
     *    `SemanticMode` to `DISABLED` and this path is a no-op.
     *  - **Coarse resolution.** The semantic raster is typically 256×144 — the person cutout
     *    edge is blocky compared with ARKit's dedicated person-segmentation model.
     *  - **Latency.** The mask trails fast motion by a frame or two.
     *
     * For a hard cutout against authored geometry (window frames, virtual aquarium walls)
     * use [io.github.sceneview.loaders.MaterialLoader.createOcclusionInstance] instead.
     *
     * Disable this value (and [isDepthOcclusionEnabled]) to apply the standard flat camera
     * material.
     */
    var isPersonOcclusionEnabled = false
        set(value) {
            if (field != value) {
                field = value
                applyOcclusionMaterial()
            }
        }

    /**
     * Selects the camera-background material from the current [isPersonOcclusionEnabled] /
     * [isDepthOcclusionEnabled] flags. People occlusion wins because its material already
     * subsumes the depth-occlusion behaviour.
     */
    private fun applyOcclusionMaterial() {
        setMaterialInstances(
            when {
                isPersonOcclusionEnabled -> personOcclusionMaterial.defaultInstance
                isDepthOcclusionEnabled -> depthOcclusionMaterial.defaultInstance
                else -> standardMaterial.defaultInstance
            }
        )
    }

    private val vertexBuffer: VertexBuffer =
        VertexBuffer.Builder().vertexCount(VERTEX_COUNT).bufferCount(2).attribute(
            VertexBuffer.VertexAttribute.POSITION,
            POSITION_BUFFER_INDEX,
            VertexBuffer.AttributeType.FLOAT3,
            0,
            CAMERA_VERTICES.size / VERTEX_COUNT * FLOAT_SIZE_IN_BYTES
        ).attribute(
            VertexBuffer.VertexAttribute.UV0,
            UV_BUFFER_INDEX,
            VertexBuffer.AttributeType.FLOAT2,
            0,
            CAMERA_UVS.size / VERTEX_COUNT * FLOAT_SIZE_IN_BYTES
        ).build(engine).apply {
            setBufferAt(engine, POSITION_BUFFER_INDEX, FloatBuffer.wrap(CAMERA_VERTICES))
        }

    private val uvCoordinates: FloatBuffer =
        ByteBuffer.allocateDirect(CAMERA_UVS.size * FLOAT_SIZE_IN_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(CAMERA_UVS)
                rewind()
            }

    // Note: ARCore expects the UV buffers to be direct or will assert in transformDisplayUvCoords
    private var transformedUvCoordinates: FloatBuffer? = null

    init {
        RenderableManager.Builder(1)
            .castShadows(false)
            .receiveShadows(false)
            // Always draw the camera feed last to avoid overdraw
            .culling(false)
            .priority(7)
            .geometry(0,
                RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer,
                IndexBuffer.Builder()
                    .indexCount(INDICES.size)
                    .bufferType(IndexType.USHORT)
                    .build(engine)
                    .apply {
                        // Create screen quad geometry to camera stream to
                        setBuffer(engine, ShortBuffer.wrap(INDICES))
                    })
            .material(0, standardMaterial.defaultInstance)
            .build(engine, entity)
    }

    fun update(session: Session, frame: Frame) {
        cameraTextures[frame.cameraTextureName]?.let {
            cameraTexture = it
        }

        // Recalculate camera Uvs if necessary.
        if (transformedUvCoordinates == null || frame.hasDisplayGeometryChanged()) {
            val transformedUvCoordinates = transformedUvCoordinates ?: uvCoordinates.clone().also {
                transformedUvCoordinates = it
            }

            // If display rotation changed (also includes view size change), we need to re-query the UV
            // coordinates for the screen rect, as they may have changed as well.
            frame.transformCoordinates2d(
                Coordinates2d.VIEW_NORMALIZED,
                uvCoordinates,
                Coordinates2d.TEXTURE_NORMALIZED,
                transformedUvCoordinates
            )
            // Adjust Camera Uvs for OpenGL
            for (i in 1 until (VERTEX_COUNT * 2) step 2) {
                // Correct for vertical coordinates to match OpenGL
                transformedUvCoordinates.put(i, 1.0f - transformedUvCoordinates[i])
            }
            vertexBuffer.setBufferAt(engine, UV_BUFFER_INDEX, transformedUvCoordinates)
        }

        // The depth texture feeds both the depth-occlusion material AND the people-occlusion
        // material (the latter keeps the depth path so static geometry still occludes). Upload
        // depth whenever either path is active.
        if (isDepthOcclusionEnabled || isPersonOcclusionEnabled) {
            when (session.config.depthMode) {
                Config.DepthMode.AUTOMATIC -> {
                    runCatching {
                        frame.acquireDepthImage16Bits()
                    }.getOrNull()
                }

                Config.DepthMode.RAW_DEPTH_ONLY -> {
                    runCatching {
                        frame.acquireRawDepthImage16Bits()
                    }.getOrNull()
                }

                else -> null
            }?.let { depthImage ->
                // Depth ByteBuffer lifecycle invariant (#1757).
                //
                // We pass `depthImage.planes[0].buffer` directly to Filament's
                // [PixelBufferDescriptor] WITHOUT cloning it. The earlier comment
                // claimed the buffer was cloned (followed by a `//.clone()` strike-through
                // in the source) — that was misleading: the code never has cloned
                // and we have intentionally kept it that way.
                //
                // The correct invariant is: Filament uploads asynchronously, holding a
                // strong reference to the [PixelBufferDescriptor] (and through it, our
                // ARCore-owned buffer) until the GPU transfer completes. The completion
                // callback below is THE serialization point that makes
                // [depthImage.close] safe: it fires AFTER Filament releases its hold,
                // so the ARCore native handle is never released while a transfer is
                // still draining.
                //
                // Why not clone defensively?
                //  - The buffer is sized W×H×2 bytes (e.g. 160×120×2 = 38.4 kB on a
                //    Pixel-class device for depth16 AUTOMATIC mode). Cloning at 30 Hz
                //    would churn ~1.1 MB/s of fresh DirectByteBuffer allocation +
                //    cleaner work, for zero correctness benefit.
                //  - The current path is structurally race-free: the upload-completed
                //    callback owns the close, no other code path closes the image.
                //
                // What about toggling [isDepthOcclusionEnabled] off mid-upload?
                //  - The toggle setter only swaps the material instance — it does NOT
                //    touch any in-flight [depthImage] or its [PixelBufferDescriptor].
                //  - Subsequent frames skip this whole block (we're inside
                //    `if (isDepthOcclusionEnabled)`), so no NEW depth acquire happens.
                //  - The previously-uploaded depth image still completes via its
                //    callback, closing itself cleanly. No use-after-close.
                //
                // What about destruction (`destroy()`) while an upload is in flight?
                //  - `engine.safeDestroyTexture(depthTexture)` runs in `destroy()`.
                //    Filament guarantees the descriptor callback fires (with the
                //    upload either completed or cancelled) before the texture is
                //    freed, so `depthImage.close` is still invoked exactly once.
                //
                // `buffer.clear()` inside the callback only resets ByteBuffer
                // position/limit metadata — it does NOT free native memory. The actual
                // memory is owned by the ARCore [com.google.ar.core.Image] and freed
                // by [depthImage.close].
                val buffer = depthImage.planes[0].buffer
                depthTexture.setImage(engine, 0, PixelBufferDescriptor(
                    buffer, Texture.Format.RG, Texture.Type.UBYTE, 1, 0, 0, 0, null
                ) {
                    // Close the ARCore image only after Filament has finished draining
                    // the buffer to the GPU. This callback is the load-bearing
                    // synchronisation point — see the long-form comment above.
                    depthImage.close()
                    buffer.clear()
                })
            }
        }

        if (isPersonOcclusionEnabled) {
            updatePersonMask(frame)
        }
    }

    /**
     * Acquires the ARCore Scene Semantics raster for [frame], bakes it into a binary PERSON
     * mask via [PersonMask.build], and uploads it to [personMaskTexture] (issue #1761).
     *
     * Lifecycle: the semantic [com.google.ar.core.Image] is **caller-owned** — unlike the
     * depth path it is closed immediately after the mask is built, because [PersonMask.build]
     * fully copies the data into a freshly-allocated packed buffer (it must, to drop the row
     * stride and binarise). The packed buffer — not the ARCore image — is what Filament holds
     * until the upload completes, so there is no use-after-close hazard and the ARCore
     * semantic-image pool (only 2–3 slots deep) is freed promptly.
     *
     * Returns silently when semantics are not yet available (first frame after resume, or
     * `Config.SemanticMode.DISABLED`) — the camera feed simply shows through un-occluded.
     */
    private fun updatePersonMask(frame: Frame) {
        val image = frame.semanticImage() ?: return
        val mask: ByteBuffer
        val width: Int
        val height: Int
        try {
            width = image.width
            height = image.height
            if (width <= 0 || height <= 0) return
            val plane = image.planes[0]
            mask = PersonMask.build(
                source = plane.buffer,
                width = width,
                height = height,
                rowStrideBytes = plane.rowStride
            )
        } finally {
            // The ARCore semantic image is fully consumed by `PersonMask.build` above —
            // close it now so the shallow semantic-image pool does not exhaust.
            image.close()
        }

        // (Re)build the texture when the semantic resolution first becomes known or changes
        // (display rotation, camera-config switch). A Filament Texture is immutable-size, so
        // a size change means a destroy + rebuild + rebind on the material instance. This
        // happens at most a handful of times in a session, never per-frame in steady state.
        if (personMaskWidth != width || personMaskHeight != height) {
            engine.safeDestroyTexture(personMaskTexture)
            personMaskTexture = Texture.Builder().width(width).height(height)
                .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.R8)
                .levels(1).build(engine)
            personMaskWidth = width
            personMaskHeight = height
            personOcclusionMaterial.defaultInstance
                .setTexture(kPersonTextureParameter, personMaskTexture)
        }

        // `mask` is a fresh DirectByteBuffer owned by us — Filament holds it until the
        // upload completes; nothing else references it, so no close/clear coordination is
        // needed beyond letting it go out of scope after the GPU drains it.
        personMaskTexture.setImage(
            engine, 0,
            PixelBufferDescriptor(mask, Texture.Format.R, Texture.Type.UBYTE)
        )
    }

    fun destroy() {
        // Destruction order is constrained by two separate Filament preconditions:
        //
        //   1. "MaterialInstance still in use by Renderable" — destroy the renderable first.
        //   2. "Invalid texture still bound to MaterialInstance" — the texture must be
        //      destroyed AFTER the MaterialInstance that samples it. `Engine.destroyMaterial`
        //      cascades to the material's defaultInstance, which unbinds all its textures,
        //      so the correct order is: renderable → material → textures.
        //
        // Go through `materialLoader.destroyMaterial` (guarded) rather than
        // `engine.safeDestroyMaterial` directly so the Material is also removed from
        // MaterialLoader's tracking list. Otherwise the outer MaterialLoader.destroy()
        // would walk a stale list and call Engine.destroyMaterial on an already-reclaimed
        // native object.
        //
        // See: https://github.com/sceneview/sceneview/issues/773
        renderableManager.safeDestroy(entity)
        engine.safeDestroyVertexBuffer(vertexBuffer)
        materialLoader.destroyMaterial(standardMaterial)
        materialLoader.destroyMaterial(depthOcclusionMaterial)
        materialLoader.destroyMaterial(personOcclusionMaterial)
        cameraTextures.values.forEach { engine.safeDestroyTexture(it) }
        engine.safeDestroyTexture(depthTexture)
        engine.safeDestroyTexture(personMaskTexture)
        uvCoordinates.clear()
        transformedUvCoordinates?.clear()
        Log.d("Sceneview", "CameraStream destroyed")
    }

    companion object {
        private const val VERTEX_COUNT = 3
        private const val POSITION_BUFFER_INDEX = 0
        private val CAMERA_VERTICES = floatArrayOf(
            -1.0f, 1.0f, 1.0f, -1.0f, -3.0f, 1.0f, 3.0f, 1.0f, 1.0f
        )
        private const val UV_BUFFER_INDEX = 1
        private val CAMERA_UVS = floatArrayOf(
            0.0f, 0.0f, 0.0f, 2.0f, 2.0f, 0.0f
        )

        private val INDICES = shortArrayOf(0, 1, 2)
        private const val FLOAT_SIZE_IN_BYTES = java.lang.Float.SIZE / 8
    }
}
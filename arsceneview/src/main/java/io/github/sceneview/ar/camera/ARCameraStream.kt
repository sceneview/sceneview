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
 * behind real-world surfaces using the ARCore depth image.
 *
 * Typically created via [rememberARCameraStream] and passed to `ARSceneView(cameraStream = ...)`.
 *
 * @param materialLoader             [MaterialLoader] for creating the camera background materials.
 * @param standardMaterialFile       Asset path for the flat (non-depth) camera material.
 * @param depthOcclusionMaterialFile Asset path for the depth-aware occlusion material.
 */
open class ARCameraStream(
    private val materialLoader: MaterialLoader,
    standardMaterialFile: String = "materials/camera_stream_flat.filamat",
    depthOcclusionMaterialFile: String = "materials/camera_stream_depth.filamat",
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
        depthOcclusionMaterial.defaultInstance.apply {
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
                setMaterialInstances(
                    if (value) {
                        depthOcclusionMaterial.defaultInstance
                    } else {
                        standardMaterial.defaultInstance
                    }
                )
                applyCameraStreamPriority(value)
            }
        }

    /**
     * Coarse-level draw ordering for the camera-stream renderable (issue #1617).
     *
     * The two camera materials require **opposite** draw orders:
     *
     *  - **Flat** (`camera_stream_flat.mat`): an opaque background that does NOT
     *    write `gl_FragDepth`. Drawing it **last** ([CAMERA_PRIORITY_BACKGROUND])
     *    lets the early-Z reject every texel already covered by virtual geometry,
     *    so the camera feed only fills the uncovered pixels — minimal overdraw.
     *
     *  - **Depth occlusion** (`camera_stream_depth.mat`): writes the real-world
     *    per-pixel depth into the z-buffer via `gl_FragDepth`. For virtual
     *    geometry to be *occluded* by real surfaces, that real-world depth MUST
     *    already be in the buffer when the virtual objects are depth-tested —
     *    i.e. the camera quad has to draw **first**
     *    ([CAMERA_PRIORITY_DEPTH_PRIME]). Drawing it last (the old fixed
     *    `priority(7)`) wrote the real-world depth *after* every virtual object
     *    had already passed its depth test against an empty buffer, so nothing
     *    was ever occluded — the user-visible symptom in #1617.
     *
     * Filament's `RenderableManager` priority runs 0 (drawn first) … 7 (drawn
     * last); this is a JNI call so it must run on the main thread, which is
     * guaranteed because [isDepthOcclusionEnabled] is only ever set from the
     * composable / main-thread setup path.
     */
    private fun applyCameraStreamPriority(depthOcclusionEnabled: Boolean) {
        setPriority(
            if (depthOcclusionEnabled) {
                CAMERA_PRIORITY_DEPTH_PRIME
            } else {
                CAMERA_PRIORITY_BACKGROUND
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
            .culling(false)
            // Initial priority matches the flat (non-depth) material — drawn
            // last to minimise overdraw. `isDepthOcclusionEnabled`'s setter
            // re-prioritises to `CAMERA_PRIORITY_DEPTH_PRIME` when occlusion is
            // turned on so the depth material draws first and primes the
            // z-buffer (issue #1617).
            .priority(CAMERA_PRIORITY_BACKGROUND)
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

        if (isDepthOcclusionEnabled) {
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
        cameraTextures.values.forEach { engine.safeDestroyTexture(it) }
        engine.safeDestroyTexture(depthTexture)
        uvCoordinates.clear()
        transformedUvCoordinates?.clear()
        Log.d("Sceneview", "CameraStream destroyed")
    }

    companion object {
        /**
         * Camera-stream draw priority while depth occlusion is OFF (issue #1617).
         *
         * Filament priority 7 = drawn **last**. The flat camera material is an
         * opaque background, so drawing it last lets early-Z skip every texel
         * already covered by virtual geometry — minimal overdraw.
         */
        const val CAMERA_PRIORITY_BACKGROUND = 7

        /**
         * Camera-stream draw priority while depth occlusion is ON (issue #1617).
         *
         * Filament priority 0 = drawn **first**. The depth camera material writes
         * the real-world per-pixel depth via `gl_FragDepth`; it must run before
         * any virtual geometry so those objects are depth-tested against the
         * real world and correctly occluded.
         */
        const val CAMERA_PRIORITY_DEPTH_PRIME = 0

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
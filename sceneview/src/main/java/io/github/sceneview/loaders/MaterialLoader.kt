package io.github.sceneview.loaders

import android.content.Context
import androidx.annotation.MainThread
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.gltfio.MaterialProvider.MaterialKey
import com.google.android.filament.gltfio.UbershaderProvider
import io.github.sceneview.material.kMaterialDefaultMetallic
import io.github.sceneview.material.kMaterialDefaultReflectance
import io.github.sceneview.material.kMaterialDefaultRoughness
import io.github.sceneview.material.setColor
import io.github.sceneview.material.setExternalTexture
import io.github.sceneview.material.setInvertFrontFaceWinding
import io.github.sceneview.material.setMetallic
import io.github.sceneview.material.setParameter
import io.github.sceneview.material.setReflectance
import io.github.sceneview.material.setRoughness
import io.github.sceneview.material.setSemanticsOpacity
import io.github.sceneview.material.setSemanticsTexture
import io.github.sceneview.material.setTexture
import io.github.sceneview.math.Color
import io.github.sceneview.math.colorOf
import io.github.sceneview.safeDestroyMaterial
import io.github.sceneview.safeDestroyMaterialInstance
import io.github.sceneview.texture.TextureSampler2D
import io.github.sceneview.utils.loadFileBuffer
import io.github.sceneview.utils.readBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.Buffer

private const val kMaterialsAssetFolder = "materials"

/**
 * A Filament Material defines the visual appearance of an object.
 *
 * Materials function as a templates from which [MaterialInstance]s can be spawned.
 */
class MaterialLoader(
    val engine: Engine,
    val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    data class UvCoordinate(val x: Int, val y: Int)

    val assets get() = context.assets

    val ubershaderProvider = UbershaderProvider(engine)

    private val opaqueColoredMaterial by lazy {
        createMaterial("$kMaterialsAssetFolder/opaque_colored.filamat")
    }
    private val transparentColoredMaterial by lazy {
        createMaterial("$kMaterialsAssetFolder/transparent_colored.filamat")
    }
    private val opaqueUnlitColoredMaterial by lazy {
        createMaterial("$kMaterialsAssetFolder/opaque_unlit_colored.filamat")
    }
    private val transparentUnlitColoredMaterial by lazy {
        createMaterial("$kMaterialsAssetFolder/transparent_unlit_colored.filamat")
    }
    private val opaqueTexturedMaterial by lazy {
        createMaterial("$kMaterialsAssetFolder/opaque_textured.filamat")
    }
    private val transparentTexturedMaterial by lazy {
        createMaterial("$kMaterialsAssetFolder/transparent_textured.filamat")
    }
    private val imageTextureMaterial by lazy {
        createMaterial("$kMaterialsAssetFolder/image_texture.filamat")
    }
    private val videoTextureMaterial by lazy {
        createMaterial("$kMaterialsAssetFolder/video_texture.filamat")
    }
    private val videoTextureChromaKeyMaterial by lazy {
        createMaterial("$kMaterialsAssetFolder/video_texture_chroma_key.filamat")
    }

    private val viewTextureLitMaterial by lazy {
        createMaterial("$kMaterialsAssetFolder/view_texture_lit.filamat")
    }
    private val viewTextureUnlitMaterial by lazy {
        createMaterial("$kMaterialsAssetFolder/view_texture_unlit.filamat")
    }
    private val occlusionMaterial by lazy {
        createMaterial("$kMaterialsAssetFolder/occlusion.filamat")
    }
    private val semanticsOverlayMaterial by lazy {
        createMaterial("$kMaterialsAssetFolder/semantics_overlay.filamat")
    }
    private val splatMaterial by lazy {
        createMaterial("$kMaterialsAssetFolder/splat.filamat")
    }

    private val materials = java.util.Collections.synchronizedList(mutableListOf<Material>())
    private val materialInstances = java.util.Collections.synchronizedList(mutableListOf<MaterialInstance>())

    /**
     * Creates and returns a [Material] object.
     *
     * A Filament Material defines the visual appearance of an object. Materials function as a
     * templates from which [MaterialInstance]s can be spawned.
     *
     * Documentation: [Filament Materials Guide](https://google.github.io/filament/Materials.html)
     *
     * @param payload Specifies the material data. The material data is a binary blob produced by
     * libfilamat or by matc.
     *
     * @see MaterialLoader.loadMaterial
     */
    @MainThread
    fun createMaterial(payload: Buffer): Material =
        Material.Builder()
            .payload(payload, payload.remaining())
            .build(engine)
            .also {
                materials += it
            }

    @MainThread
    fun getUbershaderMaterial(
        config: MaterialKey,
        uvMap: List<UvCoordinate> = listOf(
            // uv00
            UvCoordinate(0, 0),
            // uv01
            UvCoordinate(0, 1),
            // uv11
            UvCoordinate(1, 1),
            // uv10
            UvCoordinate(1, 0)
        ),
        label: String? = null
    ): Material? = ubershaderProvider.getMaterial(
        config,
        uvMap.flatMap { listOf(it.x, it.y) }.toIntArray(),
        label
    )?.also {
        materials += it
    }

    @MainThread
    fun createUbershaderInstance(
        config: MaterialKey,
        uvMap: List<UvCoordinate> = listOf(
            // uv00
            UvCoordinate(0, 0),
            // uv01
            UvCoordinate(0, 1),
            // uv11
            UvCoordinate(1, 1),
            // uv10
            UvCoordinate(1, 0)
        ),
        label: String? = null,
        extras: String? = null
    ): MaterialInstance? = ubershaderProvider.createMaterialInstance(
        config,
        uvMap.flatMap { listOf(it.x, it.y) }.toIntArray(),
        label,
        extras
    )?.also {
        materialInstances += it
    }

    /**
     * Creates and returns a [Material] object from Filamat asset file.
     *
     * @param assetFileLocation the .filamat asset file location *materials/mymaterial.filamat*
     *
     * @see createMaterial
     */
    @MainThread
    fun createMaterial(assetFileLocation: String): Material =
        createMaterial(assets.readBuffer(assetFileLocation))

    /**
     * Loads a [Material] from the contents of a Filamat file.
     *
     * The material data is a binary blob produced by libfilamat or by matc.
     *
     * @param fileLocation the .filamat file location:
     * - A relative asset file location *materials/mymaterial.filamat*
     * - An Android resource from the res folder *context.getResourceUri(R.raw.mymaterial)*
     * - A File path *Uri.fromFile(myMaterialFile).path*
     * - An http or https url *https://mydomain.com/mymaterial.filamat*
     */
    suspend fun loadMaterial(fileLocation: String): Material? =
        context.loadFileBuffer(fileLocation)?.let { buffer ->
            withContext(Dispatchers.Main) {
                createMaterial(buffer)
            }
        }

    /**
     * Loads a [Material] from the contents of a Filamat file within a created coroutine scope.
     *
     * The [onResult] callback is **always invoked on the main thread**, mirroring
     * [ModelLoader.loadModelAsync]. This guarantees Filament JNI calls inside the
     * callback (e.g. `materialLoader.createColorInstance(material)`,
     * `renderableManager.setMaterialInstanceAt(...)`) run on the correct thread —
     * Filament asserts on JNI thread mismatch.
     *
     * @param fileLocation the .filamat file location:
     * - A relative asset file location *materials/mymaterial.filamat*
     * - An Android resource from the res folder *context.getResourceUri(R.raw.mymaterial)*
     * - A File path *Uri.fromFile(myMaterialFile).path*
     * - An http or https url *https://mydomain.com/mymaterial.filamat*
     *
     * @see loadMaterial
     */
    fun loadMaterialAsync(fileLocation: String, onResult: (Material?) -> Unit) =
        coroutineScope.launch {
            val material = loadMaterial(fileLocation)
            withContext(Dispatchers.Main) {
                onResult(material)
            }
        }

    fun createInstance(material: Material) = material.createInstance().also {
        materialInstances += it
    }

    /**
     * Creates an opaque or transparent [Material] depending on the color alpha with the [Color]
     * passed in.
     *
     * The [Color] can be modified by calling [MaterialInstance.setColor].
     * The metallicness, roughness, and reflectance can be modified using
     * [MaterialInstance.setMetallic], [MaterialInstance.setRoughness],
     * [MaterialInstance.setReflectance].
     *
     * For a flat color that ignores scene lighting (no PBR shading), use
     * [createUnlitColorInstance] instead.
     */
    @MainThread
    fun createColorInstance(
        color: androidx.compose.ui.graphics.Color,
        metallic: Float = kMaterialDefaultMetallic,
        roughness: Float = kMaterialDefaultRoughness,
        reflectance: Float = kMaterialDefaultReflectance
    ) = createColorInstance(colorOf(color), metallic, roughness, reflectance)

    /**
     * Creates an opaque or transparent [Material] depending on the color alpha with the [Color]
     * passed in.
     *
     * The [Color] can be modified by calling [MaterialInstance.setColor].
     * The metallicness, roughness, and reflectance can be modified using
     * [MaterialInstance.setMetallic], [MaterialInstance.setRoughness],
     * [MaterialInstance.setReflectance].
     *
     * For a flat color that ignores scene lighting (no PBR shading), use
     * [createUnlitColorInstance] instead.
     */
    @MainThread
    fun createColorInstance(
        color: Int,
        metallic: Float = kMaterialDefaultMetallic,
        roughness: Float = kMaterialDefaultRoughness,
        reflectance: Float = kMaterialDefaultReflectance
    ) = createColorInstance(colorOf(color), metallic, roughness, reflectance)

    /**
     * Creates an opaque or transparent [Material] depending on the color alpha with the [Color]
     * passed in.
     *
     * The [Color] can be modified by calling [MaterialInstance.setColor].
     * The metallicness, roughness, and reflectance can be modified using
     * [MaterialInstance.setMetallic], [MaterialInstance.setRoughness],
     * [MaterialInstance.setReflectance].
     *
     * For a flat color that ignores scene lighting (no PBR shading), use
     * [createUnlitColorInstance] instead.
     */
    @MainThread
    fun createColorInstance(
        color: Color,
        metallic: Float = kMaterialDefaultMetallic,
        roughness: Float = kMaterialDefaultRoughness,
        reflectance: Float = kMaterialDefaultReflectance
    ): MaterialInstance =
        createInstance(if (color.a == 1.0f) opaqueColoredMaterial else transparentColoredMaterial)
            .apply {
                setColor(color)
                setMetallic(metallic)
                setRoughness(roughness)
                setReflectance(reflectance)
            }

    /**
     * Creates an opaque or transparent unlit [Material] depending on the color alpha with the
     * [Color] passed in.
     *
     * The unlit shading model is independent of light: surfaces appear as flat colors and ignore
     * the scene lighting. Use this for HUD-like overlays, debug visualizations, billboards, or
     * stylized rendering where physically-based shading is not desired.
     *
     * The [Color] can be modified by calling [MaterialInstance.setColor].
     *
     * For physically-based shading with metallic/roughness/reflectance, use
     * [createColorInstance] instead.
     */
    @MainThread
    fun createUnlitColorInstance(color: androidx.compose.ui.graphics.Color) =
        createUnlitColorInstance(colorOf(color))

    /**
     * Creates an opaque or transparent unlit [Material] depending on the color alpha with the
     * [Color] passed in.
     *
     * The unlit shading model is independent of light: surfaces appear as flat colors and ignore
     * the scene lighting. Use this for HUD-like overlays, debug visualizations, billboards, or
     * stylized rendering where physically-based shading is not desired.
     *
     * The [Color] can be modified by calling [MaterialInstance.setColor].
     *
     * For physically-based shading with metallic/roughness/reflectance, use
     * [createColorInstance] instead.
     */
    @MainThread
    fun createUnlitColorInstance(color: Int) = createUnlitColorInstance(colorOf(color))

    /**
     * Creates an opaque or transparent unlit [Material] depending on the color alpha with the
     * [Color] passed in.
     *
     * The unlit shading model is independent of light: surfaces appear as flat colors and ignore
     * the scene lighting. Use this for HUD-like overlays, debug visualizations, billboards, or
     * stylized rendering where physically-based shading is not desired.
     *
     * The [Color] can be modified by calling [MaterialInstance.setColor].
     *
     * For physically-based shading with metallic/roughness/reflectance, use
     * [createColorInstance] instead.
     */
    @MainThread
    fun createUnlitColorInstance(color: Color): MaterialInstance =
        createInstance(
            if (color.a == 1.0f) opaqueUnlitColoredMaterial else transparentUnlitColoredMaterial
        ).apply {
            setColor(color)
        }

    /**
     * Creates an an opaque or transparent [Material] with the [Texture] passed in.
     *
     * The [Texture] can be modified by calling [MaterialInstance.setTexture].
     * The metallicness, roughness, and reflectance can be modified using
     * [MaterialInstance.setMetallic], [MaterialInstance.setRoughness],
     * [MaterialInstance.setReflectance].
     */
    @MainThread
    fun createTextureInstance(
        texture: Texture,
        isOpaque: Boolean = true,
        metallic: Float = kMaterialDefaultMetallic,
        roughness: Float = kMaterialDefaultRoughness,
        reflectance: Float = kMaterialDefaultReflectance
    ): MaterialInstance =
        createInstance(if (isOpaque) opaqueTexturedMaterial else transparentTexturedMaterial)
            .apply {
                setTexture(texture)
                setMetallic(metallic)
                setRoughness(roughness)
                setReflectance(reflectance)
            }

    @MainThread
    fun createImageInstance(imageTexture: Texture, sampler: TextureSampler = TextureSampler2D()) =
        createInstance(imageTextureMaterial).apply {
            setTexture(imageTexture, sampler)
        }

    @MainThread
    fun createVideoInstance(videoTexture: Texture, chromaKeyColor: Int? = null) =
        if (chromaKeyColor == null) {
            createInstance(videoTextureMaterial)
        } else {
            createInstance(videoTextureChromaKeyMaterial).apply {
                setParameter("chromaKeyColor", colorOf(chromaKeyColor))
            }
        }.apply {
            setExternalTexture(videoTexture)
        }

    @MainThread
    fun createViewInstance(
        viewTexture: Texture,
        unlit: Boolean = false,
        invertFrontFaceWinding: Boolean = false
    ) = createInstance(if (unlit) viewTextureUnlitMaterial else viewTextureLitMaterial).apply {
        setExternalTexture(viewTexture)
        setInvertFrontFaceWinding(invertFrontFaceWinding)
    }

    /**
     * Creates a [MaterialInstance] of the **occlusion material** — invisible, depth-writing.
     *
     * Geometry rendered with this material punches the depth buffer but emits no colour, so
     * any virtual node behind it is hidden by the depth test while the surface itself paints
     * zero pixels. This is the SceneView equivalent of:
     *
     * - RealityKit's [`OcclusionMaterial`](https://developer.apple.com/documentation/realitykit/occlusionmaterial)
     * - Sceneform legacy's `MaterialFactory.makeOcclusionMaterial(...)`
     *
     * **Use cases**
     *   * Virtual aquarium / shop-window walls — author the wall as a quad, then put fish
     *     behind it and they vanish at the wall plane like real depth occlusion.
     *   * Door / window cutouts on photogrammetry assets — paint the opening with the
     *     occlusion material to suppress everything visible through it.
     *   * "Real-world geometry stand-in" stubs in non-AR 3D scenes (proxy walls / props).
     *
     * For AR scenes that need to occlude virtual content against the **live depth camera**,
     * use `ARSceneView`'s built-in depth-aware camera stream
     * ([`ARCameraStream.isDepthOcclusionEnabled`][io.github.sceneview.ar.camera.ARCameraStream])
     * — that path samples ARCore's per-pixel depth image, not a static occluder mesh.
     *
     * **Caveats**
     *   * No colour parameters — the material has nothing to tint. Apply your scene's
     *     reflectance/highlight via lit nodes positioned in front of the occluder if you
     *     need the occluder surface to look like glass / a screen.
     *   * Draws as **opaque** so the renderable lands in the opaque pass — virtual
     *     transparents behind it skip their fragment shader entirely, which is usually the
     *     intent (an "invisible wall" shouldn't paint translucents through itself).
     *   * Double-sided — back-face culling is disabled so single-sided plane occluders work
     *     in either orientation. If you author a closed convex mesh as an occluder, that's
     *     still correct (the depth buffer only takes the nearest fragment per pixel).
     */
    @MainThread
    fun createOcclusionInstance(): MaterialInstance = createInstance(occlusionMaterial)

    /**
     * Creates a [MaterialInstance] of the **Scene Semantics overlay material** — a transparent,
     * unlit material that color-codes ARCore's per-pixel semantic segmentation (#1730 / #1868).
     *
     * The material samples a single-channel `R8` texture whose red channel is a
     * [`SemanticLabel`](https://developers.google.com/ar/reference/java/com/google/ar/core/SemanticLabel)
     * ordinal (`0..11`, scaled to `[0, 1]` — i.e. `ordinal / 255f` on upload). Each pixel is
     * mapped to one of the 12 fixed outdoor-class colours baked into the shader:
     *
     * `SKY` · `BUILDING` · `TREE` · `ROAD` · `SIDEWALK` · `TERRAIN` · `STRUCTURE` · `OBJECT` ·
     * `VEHICLE` · `PERSON` · `WATER` · `UNLABELED`.
     *
     * `UNLABELED` pixels stay fully transparent so the live camera feed shows through any
     * un-classified region — only confident classes are tinted.
     *
     * **Usage** — upload `Frame.semanticImage()` (an `R8` raster) into a Filament [Texture],
     * then drive a full-screen quad with this instance. The [opacity] parameter blends the
     * overlay against the camera feed (`0f` = camera only, `1f` = full overlay):
     *
     * ```kotlin
     * val overlay = materialLoader.createSemanticsOverlayInstance(semanticTexture, opacity = 0.6f)
     * // each frame: re-upload the semantic raster into `semanticTexture`, then
     * overlay.setSemanticsOpacity(blendSlider)
     * ```
     *
     * Android / ARCore only — the on-device Scene Semantics ML model has no iOS / Web
     * equivalent. Outdoor scenes only (the model has no indoor training data). See the
     * `ARSceneSemanticsDemo` sample for the full per-frame upload + blend wiring.
     *
     * @param semanticTexture single-channel `R8` texture holding the per-pixel label ordinal.
     * @param opacity global overlay alpha in `[0, 1]`; clamped in-shader.
     *
     * @see io.github.sceneview.material.setSemanticsTexture
     * @see io.github.sceneview.material.setSemanticsOpacity
     */
    @MainThread
    fun createSemanticsOverlayInstance(
        semanticTexture: Texture,
        opacity: Float = 1.0f
    ): MaterialInstance = createInstance(semanticsOverlayMaterial).apply {
        setSemanticsTexture(semanticTexture)
        setSemanticsOpacity(opacity)
    }

    /**
     * Creates a [MaterialInstance] of the **Gaussian Splatting material** (`splat.filamat`) —
     * one per instanced draw batch of an [io.github.sceneview.node.SplatNode] (#2646).
     *
     * The material draws hardware-instanced camera-facing quads whose per-splat centre /
     * half-extent / colour / opacity are fetched **in the vertex shader** from the two square
     * `RGBA16F` data textures, indexed by `getInstanceIndex() + instanceOffset`. See
     * [io.github.sceneview.splat.SplatBuffers] for the exact texel layout contract.
     *
     * Both textures are sampled with `NEAREST` filtering and `CLAMP_TO_EDGE` wrapping: texels
     * are addressed exactly at their centres, and any interpolation would blend adjacent
     * splats' attributes into garbage.
     *
     * @param positionScaleTexture per-splat `xyz = centre (model space), w = billboard half-extent`.
     * @param colorOpacityTexture  per-splat `rgb = linear colour, a = straight opacity`.
     * @param textureSize          side of the two square data textures (the shader's `texWidth`).
     * @param instanceOffset       global splat index of this batch's instance 0 — batches above
     *                             the 65535 instances/draw cap share the textures and shift
     *                             their indexing by this offset.
     */
    @MainThread
    fun createSplatInstance(
        positionScaleTexture: Texture,
        colorOpacityTexture: Texture,
        textureSize: Int,
        instanceOffset: Int = 0
    ): MaterialInstance = createInstance(splatMaterial).apply {
        val dataSampler = TextureSampler(
            TextureSampler.MinFilter.NEAREST,
            TextureSampler.MagFilter.NEAREST,
            TextureSampler.WrapMode.CLAMP_TO_EDGE
        )
        setTexture("splatPositionScale", positionScaleTexture, dataSampler)
        setTexture("splatColorOpacity", colorOpacityTexture, dataSampler)
        setParameter("texWidth", textureSize)
        setParameter("instanceOffset", instanceOffset)
    }

    fun destroyMaterial(material: Material) {
        if (material in materials) {
            // Filament's `Engine.destroyMaterial` implicitly destroys the material's own
            // `defaultInstance`. Destroying it explicitly here would be a double-destroy; the
            // previous `safeDestroyMaterialInstance` call was a runCatching-silenced hotfix that
            // hid the double-free. Rely on Engine.destroyMaterial to do the right thing.
            //
            // A `runCatching` wrapper remains around the native call because a Material can
            // still be reclaimed out of band by the Engine itself (e.g. in the AR teardown
            // path where `engine.safeDestroy()` runs before the parent MaterialLoader's
            // DisposableEffect fires). In that case the Kotlin wrapper's `nativeObject` is 0
            // and `Engine.destroyMaterial` throws `IllegalStateException: Calling method on
            // destroyed Material`. Swallow — we already lost native ownership, so there is
            // nothing left for us to reclaim.
            runCatching { engine.safeDestroyMaterial(material) }
            materials -= material
        }
    }

    fun destroyMaterialInstance(materialInstance: MaterialInstance) {
        // `List.remove` on the `synchronizedList` is atomic: the contains-check and the removal
        // happen under a single lock, so only ONE caller ever observes `true`. A non-atomic
        // `if (mi in materialInstances) { ...; materialInstances -= mi }` would let two threads
        // (e.g. a VideoNode `materialInstance` setter on the main thread + a `destroy()` sweep)
        // both pass the guard and call `Engine.destroyMaterialInstance` twice → native abort.
        if (materialInstances.remove(materialInstance)) {
            // Same tolerance as destroyMaterial — a MaterialInstance can be orphaned by a
            // prior Material.destroy (which cascades to its defaultInstance and, during
            // Engine teardown, effectively to all instances tied to destroyed materials).
            runCatching { engine.safeDestroyMaterialInstance(materialInstance) }
        }
    }

    /**
     * Destroys this loader and cancels its coroutine scope.
     *
     * Cancelling the scope stops any in-flight [loadMaterialAsync] job so it cannot touch a
     * destroyed [Engine] after disposal (#933). Wired to `rememberMaterialLoader`'s
     * `DisposableEffect.onDispose`, so launched jobs never outlive the owning composition.
     */
    fun destroy() {
        coroutineScope.cancel()

        materialInstances.toList().forEach { destroyMaterialInstance(it) }
        materialInstances.clear()
        materials.toList().forEach { destroyMaterial(it) }
        materials.clear()

        ubershaderProvider.destroyMaterials()
        ubershaderProvider.destroy()
    }
}

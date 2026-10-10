package io.github.sceneview.loaders

import android.content.Context
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

    private val materials = mutableListOf<Material>()
    private val materialInstances = mutableListOf<MaterialInstance>()

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
    fun createMaterial(payload: Buffer): Material =
        Material.Builder()
            .payload(payload, payload.remaining())
            .build(engine)
            .also {
                materials += it
            }

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
            loadMaterial(fileLocation).also(onResult)
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
     */
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
     */
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
     */
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
     * Creates an an opaque or transparent [Material] with the [Texture] passed in.
     *
     * The [Texture] can be modified by calling [MaterialInstance.setTexture].
     * The metallicness, roughness, and reflectance can be modified using
     * [MaterialInstance.setMetallic], [MaterialInstance.setRoughness],
     * [MaterialInstance.setReflectance].
     */
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

    fun createImageInstance(imageTexture: Texture, sampler: TextureSampler = TextureSampler2D()) =
        createInstance(imageTextureMaterial).apply {
            setTexture(imageTexture, sampler)
        }

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

    fun createViewInstance(
        viewTexture: Texture,
        unlit: Boolean = false,
        invertFrontFaceWinding: Boolean = false
    ) = createInstance(if (unlit) viewTextureUnlitMaterial else viewTextureLitMaterial).apply {
        setExternalTexture(viewTexture)
        setInvertFrontFaceWinding(invertFrontFaceWinding)
    }

    fun destroyMaterial(material: Material) {
        if (material in materials) {
            engine.safeDestroyMaterialInstance(material.defaultInstance)
            engine.safeDestroyMaterial(material)
            materials -= material
        }
    }

    fun destroyMaterialInstance(materialInstance: MaterialInstance) {
        if (materialInstance in materialInstances) {
            engine.safeDestroyMaterialInstance(materialInstance)
            materialInstances -= materialInstance
        }
    }

    /**
     * Stops the loads in flight, the first thing [destroy] does, without touching the engine: for
     * a view whose engine is destroyed later than the view itself.
     */
    internal fun cancelLoads() {
        runCatching { coroutineScope.cancel() }
    }

    fun destroy() {
        coroutineScope.cancel()

        materialInstances.toList().forEach { destroyMaterialInstance(it) }
        materialInstances.clear()
        materials.toList().forEach { destroyMaterial(it) }
        materials.clear()
    }
}

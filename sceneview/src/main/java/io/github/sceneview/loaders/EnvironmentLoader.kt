package io.github.sceneview.loaders

import android.content.Context
import androidx.annotation.RawRes
import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import com.google.android.filament.utils.HDRLoader
import com.google.android.filament.utils.KTX1Loader
import io.github.sceneview.environment.Environment
import io.github.sceneview.environment.IBLPrefilter
import io.github.sceneview.safeDestroyIndirectLight
import io.github.sceneview.safeDestroySkybox
import io.github.sceneview.safeDestroyTexture
import io.github.sceneview.texture.use
import io.github.sceneview.utils.loadFileBuffer
import io.github.sceneview.utils.readBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.Buffer

/**
 * Utility for decoding an HDR file or consuming KTX1 files and producing Filament textures, IBLs,
 * and sky boxes.
 *
 * KTX is a simple container format that makes it easy to bundle miplevels and cubemap faces into a
 * single file.
 *
 * Consuming the content of an HDR file produces a [Texture] object and generates a prefiltered
 * indirect light cubemap. The specular filter is a GPU-based implementation of the specular
 * probe pre-integration filter.
 * ** Launch the heavier computation. Expect 100-200ms on the GPU.**
 */
class EnvironmentLoader(
    val engine: Engine,
    internal val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    val iblPrefilter = IBLPrefilter(engine)

    private val environments = mutableListOf<Environment>()

    fun createEnvironment(
        indirectLight: IndirectLight? = null,
        skybox: Skybox? = null,
        sphericalHarmonics: FloatArray? = null
    ) = Environment(
        indirectLight = indirectLight,
        skybox = skybox,
        sphericalHarmonics = sphericalHarmonics?.toList()
    ).also {
        environments += it
    }

    /**
     * Utility for decoding and producing environment resources from an HDR file.
     *
     * Consumes the content of an HDR file and produces an [IndirectLight] and a [Skybox].
     *
     * @param buffer The content of the HDR File.
     * @param indirectLightSpecularFilter Generates a prefiltered indirect light cubemap.
     * SpecularFilter is a GPU based implementation of the specular probe pre-integration filter.
     * ** Launch the heavier computation. Expect 100-200ms on the GPU.**
     * @param indirectLightApply Builder hook applied AFTER the v4.1.0-balanced 10k default
     * (see #1075). Use it to override the IBL intensity (e.g. bright outdoor HDRIs may want
     * 30k+) or rotation without copying the buffer-loading boilerplate.
     * @param textureOptions texture loader options
     * @param createSkybox Disable the skybox creation if you don't need it.
     *
     * @return the generated environment indirect light and skybox from the hdr.
     *
     * @see HDRLoader.createTexture
     */
    fun createHDREnvironment(
        buffer: Buffer,
        indirectLightSpecularFilter: Boolean = true,
        indirectLightApply: IndirectLight.Builder.() -> Unit = {},
        textureOptions: HDRLoader.Options = HDRLoader.Options(),
        createSkybox: Boolean = true
    ): Environment? {
        // Since we directly destroy the texture we call the `use` function and so don't pass lifecycle
        // to createTexture because it can be destroyed immediately.
        val textureCubemap = HDRLoader.createTexture(
            engine = engine,
            buffer = buffer,
            options = textureOptions
        )?.use(engine) { hdrTexture ->
            iblPrefilter.equirectangularToCubemap(equirect = hdrTexture)
        } ?: return null

        val reflections = if (indirectLightSpecularFilter) {
            iblPrefilter.specularFilter(textureCubemap).also {
                if (!createSkybox) {
                    engine.safeDestroyTexture(textureCubemap)
                }
            }
        } else {
            textureCubemap
        }
        val indirectLight = IndirectLight.Builder()
            .reflections(reflections)
            // Sensible default — Filament's hardcoded 30k drowns the v4.1.0 main+fill
            // (10k+3k) in ambient. Set BEFORE `apply` so callers can override (#1075).
            .intensity(io.github.sceneview.DEFAULT_IBL_INTENSITY)
            .apply(indirectLightApply)
            .build(engine)

        val skybox = textureCubemap.takeIf { createSkybox }?.let {
            Skybox.Builder()
                .environment(it)
                .build(engine)
        }
        return createEnvironment(indirectLight = indirectLight, skybox = skybox)
    }

    /**
     * Utility for decoding and producing environment resources from an HDR file.
     *
     * Consumes the content of an HDR file and produces an [IndirectLight] and a [Skybox].
     *
     * @param assetFileLocation The HDR asset file location.
     * @param indirectLightSpecularFilter Generates a prefiltered indirect light cubemap.
     * SpecularFilter is a GPU based implementation of the specular probe pre-integration filter.
     * ** Launch the heavier computation. Expect 100-200ms on the GPU.**
     * @param indirectLightApply Builder hook applied AFTER the v4.1.0-balanced 10k default
     * (see #1075). Use it to override the IBL intensity (e.g. bright outdoor HDRIs may want
     * 30k+) or rotation without copying the buffer-loading boilerplate.
     * @param textureOptions texture loader options
     * @param createSkybox Disable the skybox creation if you don't need it.
     *
     * @return the generated environment indirect light and skybox from the hdr.
     *
     * @see HDRLoader.createTexture
     */
    fun createHDREnvironment(
        assetFileLocation: String,
        indirectLightSpecularFilter: Boolean = true,
        indirectLightApply: IndirectLight.Builder.() -> Unit = {},
        textureOptions: HDRLoader.Options = HDRLoader.Options(),
        createSkybox: Boolean = true,
    ): Environment? = createHDREnvironment(
        buffer = context.assets.readBuffer(assetFileLocation),
        indirectLightSpecularFilter = indirectLightSpecularFilter,
        indirectLightApply = indirectLightApply,
        textureOptions = textureOptions,
        createSkybox = createSkybox
    )

    /**
     * Utility for decoding and producing environment resources from an HDR file.
     *
     * Consumes the content of an HDR file and produces an [IndirectLight] and a [Skybox].
     *
     * @param rawResId The HDR File raw resource id.
     * @param indirectLightSpecularFilter Generates a prefiltered indirect light cubemap.
     * SpecularFilter is a GPU based implementation of the specular probe pre-integration filter.
     * ** Launch the heavier computation. Expect 100-200ms on the GPU.**
     * @param indirectLightApply Builder hook applied AFTER the v4.1.0-balanced 10k default
     * (see #1075). Use it to override the IBL intensity (e.g. bright outdoor HDRIs may want
     * 30k+) or rotation without copying the buffer-loading boilerplate.
     * @param textureOptions texture loader options
     * @param createSkybox Disable the skybox creation if you don't need it.
     *
     * @return the generated environment indirect light and skybox from the hdr.
     *
     * @see HDRLoader.createTexture
     */
    fun createHDREnvironment(
        @RawRes rawResId: Int,
        indirectLightSpecularFilter: Boolean = true,
        indirectLightApply: IndirectLight.Builder.() -> Unit = {},
        textureOptions: HDRLoader.Options = HDRLoader.Options(),
        createSkybox: Boolean = true,
    ): Environment? = createHDREnvironment(
        buffer = context.resources.readBuffer(rawResId),
        indirectLightSpecularFilter = indirectLightSpecularFilter,
        indirectLightApply = indirectLightApply,
        textureOptions = textureOptions,
        createSkybox = createSkybox
    )

    /**
     * Utility for decoding and producing environment resources from an HDR file.
     *
     * Consumes the content of an HDR file and produces an [IndirectLight] and a [Skybox].
     *
     * @param file The HDR File.
     * @param indirectLightSpecularFilter Generates a prefiltered indirect light cubemap.
     * SpecularFilter is a GPU based implementation of the specular probe pre-integration filter.
     * ** Launch the heavier computation. Expect 100-200ms on the GPU.**
     * @param indirectLightApply Builder hook applied AFTER the v4.1.0-balanced 10k default
     * (see #1075). Use it to override the IBL intensity (e.g. bright outdoor HDRIs may want
     * 30k+) or rotation without copying the buffer-loading boilerplate.
     * @param textureOptions texture loader options
     * @param createSkybox Disable the skybox creation if you don't need it.
     *
     * @return the generated environment indirect light and skybox from the hdr.
     *
     * @see HDRLoader.createTexture
     */
    fun createHDREnvironment(
        file: File,
        indirectLightSpecularFilter: Boolean = true,
        indirectLightApply: IndirectLight.Builder.() -> Unit = {},
        textureOptions: HDRLoader.Options = HDRLoader.Options(),
        createSkybox: Boolean = true,
    ): Environment? = createHDREnvironment(
        buffer = file.readBuffer(),
        indirectLightSpecularFilter = indirectLightSpecularFilter,
        indirectLightApply = indirectLightApply,
        textureOptions = textureOptions,
        createSkybox = createSkybox
    )

    /**
     * Utility for decoding and producing environment resources from an HDR file.
     *
     * Consumes the content of an HDR file and produces an [IndirectLight] and a [Skybox].
     *
     * @param url The HDR File url.
     * @param indirectLightSpecularFilter Generates a prefiltered indirect light cubemap.
     * SpecularFilter is a GPU based implementation of the specular probe pre-integration filter.
     * ** Launch the heavier computation. Expect 100-200ms on the GPU.**
     * @param indirectLightApply Builder hook applied AFTER the v4.1.0-balanced 10k default
     * (see #1075). Use it to override the IBL intensity (e.g. bright outdoor HDRIs may want
     * 30k+) or rotation without copying the buffer-loading boilerplate.
     * @param textureOptions texture loader options
     * @param createSkybox Disable the skybox creation if you don't need it.
     *
     * @return the generated environment indirect light and skybox from the hdr.
     *
     * @see HDRLoader.createTexture
     */
    suspend fun loadHDREnvironment(
        url: String,
        indirectLightSpecularFilter: Boolean = true,
        indirectLightApply: IndirectLight.Builder.() -> Unit = {},
        textureOptions: HDRLoader.Options = HDRLoader.Options(),
        createSkybox: Boolean = true,
    ): Environment? = context.loadFileBuffer(url)?.let { buffer ->
        // Filament asserts on JNI thread mismatch — build on Main, mirroring
        // MaterialLoader.loadMaterial / ModelLoader.loadModel.
        withContext(Dispatchers.Main) {
            createHDREnvironment(
                buffer = buffer,
                indirectLightSpecularFilter = indirectLightSpecularFilter,
                indirectLightApply = indirectLightApply,
                textureOptions = textureOptions,
                createSkybox = createSkybox
            )
        }
    }

    /**
     * Utility for producing environment resources from precompiled cmgen generated KTX files.
     *
     * Consumes the content of KTX files and produces an [IndirectLight], SphericalHarmonics and a
     * [Skybox]
     *
     * You can generate ktx ibl and skybox files using:
     *
     * `cmgen --deploy ./output --format=ktx --size=256 --extract-blur=0.1 environment.hdr`
     *
     * Documentation: [Filament - Bake environment map](https://github.com/google/filament/blob/main/web/docs/tutorial_redball.md#bake-environment-map)
     *
     * @param iblBuffer The content of the ibl KTX File.
     * @param skyboxBuffer The content of the skybox KTX File.
     *
     * @return the generated environment indirect light, sphericalHarmonics and skybox from the ktxs.
     */
    fun createKTX1Environment(
        iblBuffer: Buffer? = null,
        skyboxBuffer: Buffer? = null
    ) = createEnvironment(
        // Apply the v4.1.0-balanced IBL intensity (#1075) so callers don't need to
        // remember it; the KTX1Loader-built IBL otherwise inherits Filament's 30k
        // default which dominates the 10k main + 3k fill light setup.
        indirectLight = iblBuffer?.let {
            KTX1Loader.createIndirectLight(engine, it).indirectLight
                ?.also { ibl -> ibl.intensity = io.github.sceneview.DEFAULT_IBL_INTENSITY }
        },
        skybox = skyboxBuffer?.let { KTX1Loader.createSkybox(engine, it).skybox },
        sphericalHarmonics = iblBuffer?.rewind()?.let { KTX1Loader.getSphericalHarmonics(it) }
    )

    /**
     * Utility for producing environment resources from precompiled cmgen generated KTX files.
     *
     * Consumes the content of KTX files and produces an [IndirectLight], SphericalHarmonics and a
     * [Skybox]
     *
     * You can generate ktx ibl and skybox files using:
     *
     * `cmgen --deploy ./output --format=ktx --size=256 --extract-blur=0.1 environment.hdr`
     *
     * Documentation: [Filament - Bake environment map](https://github.com/google/filament/blob/main/web/docs/tutorial_redball.md#bake-environment-map)
     *
     * @param iblAssetFile The ibl KTX asset file location.
     * @param skyboxAssetFile The skybox KTX asset file location.
     *
     * @return the generated environment indirect light, sphericalHarmonics and skybox from the ktxs.
     */
    fun createKTX1Environment(
        iblAssetFile: String? = null,
        skyboxAssetFile: String? = null
    ): Environment = createKTX1Environment(
        iblBuffer = iblAssetFile?.let { context.assets.readBuffer(it) },
        skyboxBuffer = skyboxAssetFile?.let { context.assets.readBuffer(it) },
    )

    /**
     * Utility for producing environment resources from precompiled cmgen generated KTX files.
     *
     * Consumes the content of KTX files and produces an [IndirectLight], SphericalHarmonics and a
     * [Skybox]
     *
     * You can generate ktx ibl and skybox files using:
     *
     * `cmgen --deploy ./output --format=ktx --size=256 --extract-blur=0.1 environment.hdr`
     *
     * Documentation: [Filament - Bake environment map](https://github.com/google/filament/blob/main/web/docs/tutorial_redball.md#bake-environment-map)
     *
     * @param iblRawResId The ibl KTX file raw resource id.
     * @param skyboxRawResId The skybox KTX file raw resource id.
     *
     * @return the generated environment indirect light, sphericalHarmonics and skybox from the ktxs.
     */
    fun createKTX1Environment(
        iblRawResId: Int? = null,
        skyboxRawResId: Int? = null
    ): Environment = createKTX1Environment(
        iblBuffer = iblRawResId?.let { context.resources.readBuffer(it) },
        skyboxBuffer = skyboxRawResId?.let { context.resources.readBuffer(it) },
    )

    /**
     * Utility for producing environment resources from precompiled cmgen generated KTX files.
     *
     * Consumes the content of KTX files and produces an [IndirectLight], SphericalHarmonics and a
     * [Skybox]
     *
     * You can generate ktx ibl and skybox files using:
     *
     * `cmgen --deploy ./output --format=ktx --size=256 --extract-blur=0.1 environment.hdr`
     *
     * Documentation: [Filament - Bake environment map](https://github.com/google/filament/blob/main/web/docs/tutorial_redball.md#bake-environment-map)
     *
     * @param iblFile The ibl KTX File.
     * @param skyboxFile The skybox KTX File.
     *
     * @return the generated environment indirect light, sphericalHarmonics and skybox from the ktxs.
     */
    fun createKTX1Environment(
        iblFile: File? = null,
        skyboxFile: File? = null
    ): Environment = createKTX1Environment(
        iblBuffer = iblFile?.let { it.readBuffer() },
        skyboxBuffer = skyboxFile?.let { it.readBuffer() },
    )

    /**
     * Utility for producing environment resources from precompiled cmgen generated KTX files.
     *
     * Consumes the content of KTX files and produces an [IndirectLight], SphericalHarmonics and a
     * [Skybox]
     *
     * You can generate ktx ibl and skybox files using:
     *
     * `cmgen --deploy ./output --format=ktx --size=256 --extract-blur=0.1 environment.hdr`
     *
     * Documentation: [Filament - Bake environment map](https://github.com/google/filament/blob/main/web/docs/tutorial_redball.md#bake-environment-map)
     *
     * @param iblUrl The ibl KTX file url.
     * @param skyboxUrl The skybox KTX file url.
     *
     * @return the generated environment indirect light, sphericalHarmonics and skybox from the ktxs.
     */
    suspend fun loadKTX1Environment(
        iblUrl: String? = null,
        skyboxUrl: String? = null
    ): Environment {
        // Load both buffers on the caller's (IO) dispatcher first, then build on Main —
        // Filament asserts on JNI thread mismatch (same pattern as MaterialLoader.loadMaterial).
        val iblBuffer = iblUrl?.let { context.loadFileBuffer(iblUrl) }
        val skyboxBuffer = skyboxUrl?.let { context.loadFileBuffer(skyboxUrl) }
        return withContext(Dispatchers.Main) {
            createKTX1Environment(
                iblBuffer = iblBuffer,
                skyboxBuffer = skyboxBuffer
            )
        }
    }

    /**
     * Utility for decoding and producing environment resources from an HDR file.
     *
     * Consumes the content of an HDR file and produces an [IndirectLight] and a [Skybox].
     *
     * @param url The HDR File url.
     * @param indirectLightSpecularFilter Generates a prefiltered indirect light cubemap.
     * SpecularFilter is a GPU based implementation of the specular probe pre-integration filter.
     * ** Launch the heavier computation. Expect 100-200ms on the GPU.**
     * @param textureOptions texture loader options
     * @param createSkybox Disable the skybox creation if you don't need it.
     *
     * @return the generated environment indirect light and skybox from the hdr.
     *
     * @see HDRLoader.createTexture
     */
    suspend fun loadKTX1Environment(
        url: String,
        indirectLightSpecularFilter: Boolean = true,
        indirectLightApply: IndirectLight.Builder.() -> Unit = {},
        textureOptions: HDRLoader.Options = HDRLoader.Options(),
        createSkybox: Boolean = true,
    ): Environment? = context.loadFileBuffer(url)?.let { buffer ->
        // Filament asserts on JNI thread mismatch — build on Main, mirroring
        // MaterialLoader.loadMaterial / ModelLoader.loadModel.
        withContext(Dispatchers.Main) {
            createHDREnvironment(
                buffer = buffer,
                indirectLightSpecularFilter = indirectLightSpecularFilter,
                indirectLightApply = indirectLightApply,
                textureOptions = textureOptions,
                createSkybox = createSkybox
            )
        }
    }

    fun destroyEnvironment(environment: Environment) {
        environment.indirectLight?.let { engine.safeDestroyIndirectLight(it) }
        environment.skybox?.let { engine.safeDestroySkybox(it) }
        environments -= environment
    }

    /**
     * Releases every [Environment] produced by this loader.
     *
     * Does **not** cancel the loader's coroutine scope — the loader stays usable and a
     * subsequent `loadHDREnvironment`/`loadKTX1Environment` still launches correctly.
     * Scope cancellation happens only in [destroy] (#933), so calling `clear()` to drop
     * environments mid-life never leaves the loader with a dead scope.
     */
    fun clear() {
        environments.toList().forEach { destroyEnvironment(it) }
        environments.clear()
    }

    /**
     * Destroys this loader and cancels its coroutine scope.
     *
     * Cancelling the scope stops any in-flight `loadHDREnvironment`/`loadKTX1Environment`
     * job so it cannot touch a destroyed [Engine] after disposal (#933). Wired to
     * `rememberEnvironmentLoader`'s `DisposableEffect.onDispose`, so the scope never
     * outlives the owning composition.
     */
    fun destroy() {
        runCatching { coroutineScope.cancel() }
        clear()

        iblPrefilter.destroy()
    }
}
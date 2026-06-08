package io.github.sceneview.environment

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.rememberEnvironment

/**
 * Pre-defined HDR environment asset locations bundled with SceneView.
 *
 * Use with [rememberHDREnvironment] for easy environment loading:
 * ```kotlin
 * val env = rememberHDREnvironment(
 *     environmentLoader = environmentLoader,
 *     assetFileLocation = "environments/sky_2k.hdr"
 * )
 * SceneView(environment = env ?: rememberEnvironment(environmentLoader)) { ... }
 * ```
 *
 * If you have custom HDR files, place them in `src/main/assets/environments/` and pass the path
 * directly.
 */
@ExperimentalSceneViewApi
object EnvironmentPresets {
    /**
     * The default neutral IBL shipped with SceneView.
     *
     * This is a KTX1 pre-filtered environment — lightweight and always available.
     * Used internally by [io.github.sceneview.createEnvironment].
     */
    const val NEUTRAL = "environments/neutral/neutral_ibl.ktx"
}

/**
 * Asynchronously loads an HDR environment from an asset file and remembers the result.
 *
 * Returns `null` while loading. Once the environment is ready it is cached and reused across
 * recompositions. Falls back to `null` (not a neutral environment) so the caller can decide
 * what to display while loading.
 *
 * ```kotlin
 * val env = rememberHDREnvironment(environmentLoader, "environments/sky_2k.hdr")
 * SceneView(environment = env ?: rememberEnvironment(environmentLoader)) { ... }
 * ```
 *
 * @param environmentLoader The [EnvironmentLoader] to use for decoding.
 * @param assetFileLocation Path to the HDR file relative to the `assets` folder.
 * @param createSkybox      Whether to also create a skybox from the HDR (default true).
 * @return The loaded [Environment], or `null` while loading or on failure.
 */
@ExperimentalSceneViewApi
@Composable
fun rememberHDREnvironment(
    environmentLoader: EnvironmentLoader,
    assetFileLocation: String,
    createSkybox: Boolean = true
): Environment? {
    val environment = produceState<Environment?>(
        initialValue = null,
        key1 = environmentLoader,
        key2 = assetFileLocation
    ) {
        // The HDR decode + IBL prefilter runs on the GPU (main thread) through Filament.
        // createHDREnvironment handles this internally.
        value = runCatching {
            environmentLoader.createHDREnvironment(
                assetFileLocation = assetFileLocation,
                createSkybox = createSkybox
            )
        }.getOrNull()
    }.value
    // `produceState` only cancels the producer coroutine on a key change — it never destroys the
    // previously produced [Environment]. Keying a [DisposableEffect] on the produced value fires
    // `onDispose` for the *previous* environment whenever a new one is produced (key swap) and on
    // leave-composition, matching the sibling `rememberEnvironment(key = …)`. `onDispose` runs on
    // the composition (main) thread, satisfying the Filament JNI threading contract.
    DisposableEffect(environment) {
        onDispose { environment?.let { environmentLoader.destroyEnvironment(it) } }
    }
    return environment
}

/**
 * Asynchronously loads a KTX1 environment and remembers the result.
 *
 * KTX1 environments are pre-filtered and load faster than HDR files. Generate them with:
 * ```
 * cmgen --deploy ./output --format=ktx --size=256 --extract-blur=0.1 environment.hdr
 * ```
 *
 * @param environmentLoader The [EnvironmentLoader] to use.
 * @param iblAssetFile      Path to the IBL KTX file in assets (null to skip IBL).
 * @param skyboxAssetFile   Path to the skybox KTX file in assets (null to skip skybox).
 * @return The loaded [Environment], or `null` while loading.
 */
@ExperimentalSceneViewApi
@Composable
fun rememberKTXEnvironment(
    environmentLoader: EnvironmentLoader,
    iblAssetFile: String? = null,
    skyboxAssetFile: String? = null
): Environment? {
    val environment = produceState<Environment?>(
        initialValue = null,
        key1 = environmentLoader,
        key2 = iblAssetFile,
        key3 = skyboxAssetFile
    ) {
        value = runCatching {
            environmentLoader.createKTX1Environment(
                iblAssetFile = iblAssetFile,
                skyboxAssetFile = skyboxAssetFile
            )
        }.getOrNull()
    }.value
    // See [rememberHDREnvironment]: `produceState` skips per-key disposal, so destroy the previous
    // [Environment] when the produced value changes (key swap) and on leave-composition. `onDispose`
    // runs on the composition (main) thread, satisfying the Filament JNI threading contract.
    DisposableEffect(environment) {
        onDispose { environment?.let { environmentLoader.destroyEnvironment(it) } }
    }
    return environment
}

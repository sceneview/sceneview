package io.github.sceneview.environment

import android.util.Log
import androidx.compose.runtime.Composable
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.rememberEnvironment
import kotlinx.coroutines.CancellationException

private const val TAG = "EnvironmentPresets"

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
 * Returns `null` during the first load. When [assetFileLocation] or [createSkybox] changes, the
 * current environment remains visible until the replacement has finished decoding, uploading,
 * and prefiltering. The latest request wins: superseded results are destroyed instead of being
 * published.
 *
 * ```kotlin
 * val env = rememberHDREnvironment(environmentLoader, "environments/sky_2k.hdr")
 * SceneView(environment = env ?: rememberEnvironment(environmentLoader)) { ... }
 * ```
 *
 * @param environmentLoader The [EnvironmentLoader] to use for decoding.
 * @param assetFileLocation Path to the HDR file relative to the `assets` folder.
 * @param createSkybox      Whether to also create a skybox from the HDR (default true).
 * @return The loaded [Environment], or `null` while the first environment loads or on initial
 * failure. A later failure keeps the last successfully loaded environment.
 */
@ExperimentalSceneViewApi
@Composable
fun rememberHDREnvironment(
    environmentLoader: EnvironmentLoader,
    assetFileLocation: String,
    createSkybox: Boolean = true
): Environment? {
    return rememberRetainedResource(
        owner = environmentLoader,
        assetFileLocation,
        createSkybox,
        release = environmentLoader::destroyEnvironment,
    ) {
        // loadHDREnvironment reads and decodes the HDR off the main thread, then runs only the
        // Filament upload + IBL prefilter on Main — a 2k equirect no longer stalls composition.
        try {
            environmentLoader.loadHDREnvironment(
                url = assetFileLocation,
                createSkybox = createSkybox
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(TAG, "Failed to load HDR environment $assetFileLocation", error)
            null
        }
    }
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
 * @return The loaded [Environment], or `null` during the first load. Path changes retain the
 * current environment until the replacement is ready; a failed replacement keeps it.
 */
@ExperimentalSceneViewApi
@Composable
fun rememberKTXEnvironment(
    environmentLoader: EnvironmentLoader,
    iblAssetFile: String? = null,
    skyboxAssetFile: String? = null
): Environment? {
    return rememberRetainedResource(
        owner = environmentLoader,
        iblAssetFile,
        skyboxAssetFile,
        release = environmentLoader::destroyEnvironment,
    ) {
        try {
            environmentLoader.loadKTX1Environment(
                iblUrl = iblAssetFile,
                skyboxUrl = skyboxAssetFile,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(TAG, "Failed to load KTX environment", error)
            null
        }
    }
}

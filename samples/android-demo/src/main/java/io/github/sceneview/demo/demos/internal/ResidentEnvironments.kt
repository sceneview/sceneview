package io.github.sceneview.demo.demos.internal

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.EnvironmentLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** What is on screen: a resource and the asset file it was loaded from, published together. */
internal class Presented<T : Any>(val file: String, val resource: T)

/**
 * Main-thread-confined bookkeeping for resources a screen keeps loaded by asset file: the one
 * it wants on screen, plus a set it keeps *warm* behind it so a later swap is instant.
 *
 * It is the keyed sibling of the SDK's retained environment: a request never clears
 * [presented], which moves only once the wanted file is loaded. Pure — it holds no Filament
 * handle of its own and reaches the engine only through [release].
 *
 * The Lighting demo's Sun clock is why it exists. A day lasts ~34 s and crosses four skies,
 * the shortest for 3.3 s; loading each one on demand costs a decode and an IBL prefilter per
 * slice, and on a slow device a slice ends before its sky is ready, so it is never seen.
 */
internal class ResidentResources<T : Any>(private val release: (T) -> Unit) {
    private val loaded = mutableStateMapOf<String, T>()
    private val failed = mutableStateMapOf<String, Unit>()
    private var wanted by mutableStateOf<String?>(null)
    private var warm by mutableStateOf<List<String>>(emptyList())

    /** The wanted file once it is loaded, the previous one until then. */
    var presented: Presented<T>? by mutableStateOf(null)
        private set

    /** Asks for [file] on screen, and for [warm] to be loaded and kept behind it. */
    fun request(file: String, warm: List<String> = emptyList()) {
        wanted = file
        this.warm = warm
        present()
    }

    /** The next file worth loading: the wanted one first, then the warm ones in order. */
    fun nextToLoad(): String? =
        (listOfNotNull(wanted) + warm).firstOrNull { it !in loaded && it !in failed }

    /**
     * Hands over the result of loading [file]; `null` records a failure, and the file is not
     * tried again. A result nobody wants any more is released here: it never reached the screen.
     */
    fun onLoaded(file: String, resource: T?) {
        when {
            resource == null -> failed[file] = Unit
            file != wanted && file !in warm -> release(resource)
            else -> {
                loaded.put(file, resource)?.let(release)
                present()
            }
        }
    }

    /**
     * Releases whatever is neither on screen, wanted, nor kept warm. Call it once the last swap
     * has been presented — [presented] is excluded, the resource it replaced is not.
     */
    fun releaseUnused() {
        val keep = warm.toSet() + listOfNotNull(wanted, presented?.file)
        loaded.keys.filter { it !in keep }.forEach { file -> loaded.remove(file)?.let(release) }
    }

    /** Releases everything, each resource once. */
    fun clear() {
        presented = null
        loaded.values.toList().also { loaded.clear() }.forEach(release)
        failed.clear()
    }

    private fun present() {
        val file = wanted ?: return
        val resource = loaded[file] ?: return
        if (presented?.resource !== resource) presented = Presented(file, resource)
    }
}

/**
 * The HDR environment loaded from [file], with [warm] kept loaded behind it.
 *
 * Returns `null` only until the first load lands. After that the presented pair stays on screen
 * while a later [file] loads, and swapping to a warm file takes no load at all. Files are loaded
 * one at a time, the wanted one first, and a load that has started is never cancelled by a later
 * request: its result goes to the cache if still wanted there, and is destroyed otherwise.
 * An environment that stops being wanted is destroyed two frames after its replacement is on
 * screen; everything is destroyed when the composable leaves.
 *
 * A file that fails to load is not tried again and stays out of the result: [onLoadFailed] is
 * told which one, on the main thread.
 */
@Composable
internal fun rememberResidentEnvironment(
    environmentLoader: EnvironmentLoader,
    file: String,
    warm: List<String> = emptyList(),
    onLoadFailed: (file: String) -> Unit = {},
): Presented<Environment>? {
    val reportLoadFailed by rememberUpdatedState(onLoadFailed)
    val resident = remember(environmentLoader) {
        ResidentResources<Environment>(environmentLoader::destroyEnvironment)
    }
    SideEffect { resident.request(file, warm) }
    LaunchedEffect(resident) {
        while (true) {
            val next = snapshotFlow { resident.nextToLoad() }.filterNotNull().first()
            val started = SystemClock.elapsedRealtime()
            // The decode runs off the main thread; only the upload and the IBL prefilter are on it.
            val environment = try {
                environmentLoader.loadHDREnvironment(url = next)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.w(TAG, "Failed to load HDR environment $next", error)
                null
            }
            resident.onLoaded(next, environment)
            // Not retried, so said: a caller holding "Scene ready" for this file stops waiting.
            if (environment == null) reportLoadFailed(next)
            Log.d(TAG, "Loaded $next in ${SystemClock.elapsedRealtime() - started} ms")
            // A frame between loads: each ends on a main-thread prefilter, so they never stack.
            withFrameNanos { }
        }
    }
    val presented = resident.presented
    LaunchedEffect(resident, presented, file, warm) {
        // The replaced environment stays alive until SceneView has observed the new one and
        // presented it: destroying it in the frame that schedules the swap draws a dead skybox.
        repeat(2) { withFrameNanos { } }
        resident.releaseUnused()
    }
    DisposableEffect(resident) {
        onDispose(resident::clear)
    }
    return presented
}

private const val TAG = "ResidentEnvironments"

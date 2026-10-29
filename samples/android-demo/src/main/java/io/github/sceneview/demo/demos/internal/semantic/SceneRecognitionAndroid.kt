package io.github.sceneview.demo.demos.internal.semantic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import io.github.sceneview.demo.R
import io.github.sceneview.demo.hdpack.isOnMeteredNetwork
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.coroutines.cancellation.CancellationException

/*
 * The Android side of scene recognition: the detector downloaded on first use, the replay's photos
 * as detector images, and the labels' names in the app's language. Everything else is plain Kotlin
 * the JVM tests run.
 */

private const val TAG = "SceneRecognition"

/** The side a keyframe is decoded down to before the detector letterboxes it into 416 pixels. */
const val SCENE_IMAGE_MAX_SIDE = 640

/** The detector's folder in app storage: kept across launches, never cleared as a cache is. */
private const val SCENE_MODEL_DIR = "scene-detector"

/**
 * The furniture detector, `null` when it cannot be had — no network for its first download
 * ([SceneModelState.Offline]), or a file that fails its check, that LiteRT will not load, or a
 * device LiteRT has no library for ([SceneModelState.Unusable]): the replay then runs without it. [onState] hears where it is, on
 * the caller's dispatcher: [SceneModelState.Metered] while it waits for the user's go, the progress
 * of its download — another replay's too, when one is already downloading it —, then
 * [SceneModelState.Ready] once its interpreter is built, or why there is none.
 *
 * The first call downloads the model ([SceneModelStore]) — or finds it in app storage —, maps it
 * and builds its interpreter, all off the main thread; every later one, each replay opened again,
 * shares that interpreter ([SceneDetectorShare]). Closing what it returns lets go of the share,
 * never the shared interpreter.
 */
suspend fun loadSceneDetector(context: Context, onState: (SceneModelState) -> Unit = {}): SceneDetector? =
    ProcessDetector.of(context).share(onState)

/**
 * The user's go for the detector's download on a metered network — the replay's "Download" tap: a
 * replay waiting at [SceneModelState.Metered] starts it. It holds until the app restarts, except
 * once LiteRT has refused a copy: fetching another one on mobile data asks again.
 */
fun allowSceneDetectorDownload(context: Context) = ProcessDetector.of(context).allowMetered()

/** The process's one [SceneDetectorShare], on the app's storage and network. */
private object ProcessDetector {
    @Volatile
    private var share: SceneDetectorShare? = null

    fun of(context: Context): SceneDetectorShare = share ?: synchronized(this) {
        share ?: context.applicationContext.let { app ->
            SceneDetectorShare(
                storeOf = {
                    withContext(Dispatchers.IO) {
                        SceneModelStore(File(app.filesDir, SCENE_MODEL_DIR)) { failure ->
                            Log.w(TAG, "The scene detector could not be fetched: ${failure.message}")
                        }
                    }
                },
                metered = { isOnMeteredNetwork(app) },
                open = ::openSceneDetector,
                abis = Build.SUPPORTED_ABIS.toList(),
            )
        }.also { share = it }
    }
}

/**
 * The detector, built once and shared by every replay. The first replay that needs it fetches it
 * from [storeOf]'s store and [open]s it; the others wait for it, and meanwhile hear that download's
 * progress. On a [metered] network nothing is fetched until [allowMetered] — the HD pack's rule —
 * unless the file is already on the device. A download already under way goes on: a replay opened
 * meanwhile follows it instead of asking, and asks only if it stops short.
 *
 * A download that found no network is tried again by the next replay, and resumes where it
 * stopped; so does one the replay left. A file that failed its check is not tried again before the
 * app restarts. One that LiteRT will not load is deleted and fetched once more — after a new go on
 * a metered network —, and given up on if the new copy fails too. That second copy is kept and
 * marked ([SceneModelStore.markRefetched]): a later launch tries it again, never a third download.
 * A copy that loads clears the mark ([SceneModelStore.clearRefetched]), so a file refused after
 * that is fetched once more.
 *
 * [abis] are the device's ABIs as `Build.SUPPORTED_ABIS` lists them, the one the app runs as first.
 * When LiteRT ships no library for that one (32-bit x86, even on a device that also translates ARM
 * code), nothing is downloaded and no tap asked for: every replay runs without a detector
 * ([SceneModelState.Unusable]). Should LiteRT still fail to load on another device, the file is not
 * at fault: its copy is kept, no other is fetched, and each launch runs without a detector.
 *
 * The interpreter lives as long as the process, whose end frees it; the shares handed out close
 * nothing ([Share]), so a replay that closes its detector cannot pull the interpreter from under
 * another one.
 */
internal class SceneDetectorShare(
    private val storeOf: suspend () -> SceneModelStore,
    private val metered: () -> Boolean,
    private val open: suspend (File) -> SceneDetector?,
    private val abis: List<String>,
) {
    private val lock = Mutex()
    private val storeLock = Mutex()
    private var store: SceneModelStore? = null

    @Volatile
    private var detector: SceneDetector? = null

    @Volatile
    private var unusable = false

    /** The download under way, for the replays waiting for it. */
    private val progress = MutableStateFlow<SceneModelState.Downloading?>(null)
    private val meteredAllowed = MutableStateFlow(false)

    /**
     * The user's go for the download on a metered network: every replay waiting for it starts. It
     * is for one copy: a refused one fetched again asks again ([fetchAndOpen]).
     */
    fun allowMetered() {
        meteredAllowed.value = true
    }

    suspend fun share(onState: (SceneModelState) -> Unit = {}): SceneDetector? {
        if (!unusable && !liteRtRunsOn(abis)) {
            Log.i(TAG, "No LiteRT for ${abis.firstOrNull()}: no scene detector downloaded")
            unusable = true
        }
        shared(onState)?.let { return it }
        if (unusable) return null
        val models = models()
        awaitGo(models, onState)
        // The lock is held for the whole download: while it waits, a replay shows its progress.
        return coroutineScope {
            val relay = launch(start = CoroutineStart.UNDISPATCHED) {
                progress.collect { it?.let(onState) }
            }
            try {
                lock.withLock {
                    relay.cancelAndJoin()
                    shared(onState) ?: if (unusable) null else fetchAndOpen(models, onState)
                }
            } finally {
                relay.cancel()
            }
        }
    }

    /** The detector already built ([SceneModelState.Ready]), else `null` — said when it never will be. */
    private fun shared(onState: (SceneModelState) -> Unit): SceneDetector? {
        detector?.let {
            onState(SceneModelState.Ready)
            return Share(it)
        }
        if (unusable) onState(SceneModelState.Unusable)
        return null
    }

    private suspend fun models(): SceneModelStore = storeLock.withLock {
        store ?: storeOf().also { store = it }
    }

    /** Returns once the download may start: not metered, the user said yes, or nothing to download. */
    private suspend fun awaitGo(models: SceneModelStore, onState: (SceneModelState) -> Unit) {
        if (!mustAsk(models)) return
        onState(SceneModelState.Metered)
        // A switch to Wi-Fi is a go too: looked at again every few seconds. Another replay's
        // download starting is one at once.
        while (mustAsk(models)) {
            withTimeoutOrNull(NETWORK_RECHECK_MILLIS) {
                combine(meteredAllowed, progress) { allowed, under -> allowed || under != null }.first { it }
            }
        }
    }

    /** Metered, not allowed, not on the device, and no download under way: that one goes on. */
    private fun mustAsk(models: SceneModelStore) =
        !meteredAllowed.value && progress.value == null && models.cached() == null && metered()

    private suspend fun fetchAndOpen(models: SceneModelStore, onState: (SceneModelState) -> Unit): SceneDetector? {
        for (attempt in 0 until OPEN_ATTEMPTS) {
            // Asked again under the lock: the download a replay followed here may have stopped
            // short, on a network that has turned metered since.
            awaitGo(models, onState)
            val file = fetch(models, onState) ?: return null
            if (attempt > 0) models.markRefetched()
            val opened = try {
                open(file)
            } catch (missing: UnsatisfiedLinkError) {
                Log.w(TAG, "No LiteRT for ${abis.firstOrNull()}: the scan is recognised without a detector", missing)
                break
            }
            opened?.let { built ->
                models.clearRefetched()
                detector = built
                onState(SceneModelState.Ready)
                return Share(built)
            }
            Log.w(TAG, "LiteRT refused the scene detector (try ${attempt + 1} of $OPEN_ATTEMPTS)")
            // A copy fetched again after a refusal — at this launch or an earlier one — was checked
            // as it arrived: the fault is the runtime's, keep it and fetch no other.
            if (attempt == OPEN_ATTEMPTS - 1 || models.isRefetched()) break
            models.discard()
            // The go was for the copy just refused: another 10 MB on mobile data is asked for again.
            meteredAllowed.value = false
        }
        unusable = true
        onState(SceneModelState.Unusable)
        return null
    }

    private suspend fun fetch(models: SceneModelStore, onState: (SceneModelState) -> Unit): File? {
        var ended: SceneModelState? = null
        val file = try {
            models.fetch { state ->
                ended = state
                if (state is SceneModelState.Downloading) progress.value = state
                // Ready is said once the interpreter is built, not when the file is merely on disk.
                if (state != SceneModelState.Ready) onState(state)
            }
        } finally {
            progress.value = null
        }
        if (file == null) {
            unusable = ended == SceneModelState.Unusable
            Log.i(TAG, "No scene detector (${ended ?: "not fetched"}): the scan is recognised without it")
        }
        return file
    }

    private companion object {
        /** The first download, and one more after LiteRT refused it: never a loop. */
        const val OPEN_ATTEMPTS = 2
        const val NETWORK_RECHECK_MILLIS = 5_000L

        /** The ABIs LiteRT ships its native library for: no 32-bit x86. */
        val LITERT_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

        /**
         * Whether LiteRT can load on a device with these [abis]. The app runs as the first one, whose
         * libraries the installer picked: a second ABI the device translates does not count.
         */
        fun liteRtRunsOn(abis: List<String>) = abis.firstOrNull() in LITERT_ABIS
    }
}

/**
 * [file] mapped in place — LiteRT reads a direct buffer without copying it onto the heap — and its
 * interpreter built: the mapping on [Dispatchers.IO], the interpreter on [Dispatchers.Default].
 * `null` when LiteRT refuses the file. On a device LiteRT has no native library for (32-bit x86)
 * the [UnsatisfiedLinkError] goes through: the file is not at fault, and [SceneDetectorShare]
 * keeps it rather than fetch another.
 */
@VisibleForTesting
internal suspend fun openSceneDetector(file: File): SceneDetector? = try {
    val mapped = withContext(Dispatchers.IO) {
        FileInputStream(file).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.channel.size()) }
    }
    withContext(Dispatchers.Default) { LiteRtSceneDetector(mapped, name = SceneModel.NAME) }
} catch (e: CancellationException) {
    throw e
} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
    Log.w(TAG, "The scene detector could not be loaded: ${failure.message}")
    null
}

/** A share of the process's detector: it detects with it, and closing it leaves it open. */
private class Share(detector: SceneDetector) : SceneDetector by detector {
    override fun close() = Unit
}

/**
 * An encoded photo, decoded down by powers of two until its longer side is under twice
 * [maxSide] pixels — the detector letterboxes it smaller still — as a detector reads it.
 * [decode] decodes it with the options it is given (`null` when it cannot), as
 * `RerunReplayMedia.decode` does.
 */
fun sceneImageOf(maxSide: Int = SCENE_IMAGE_MAX_SIDE, decode: (BitmapFactory.Options) -> Bitmap?): SceneImage? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val bitmap = decode(BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        SceneImage(bitmap.width, bitmap.height, pixels)
    } finally {
        bitmap.recycle()
    }
}

/** The label's name as the replay writes it over a box. */
@StringRes
fun SceneLabel.nameRes(): Int = when (this) {
    SceneLabel.Bed -> R.string.demo_ar_rerun_recognized_bed
    SceneLabel.Sofa -> R.string.demo_ar_rerun_recognized_sofa
    SceneLabel.Chair -> R.string.demo_ar_rerun_recognized_chair
    SceneLabel.Table -> R.string.demo_ar_rerun_recognized_table
    SceneLabel.Bench -> R.string.demo_ar_rerun_recognized_bench
    SceneLabel.Toilet -> R.string.demo_ar_rerun_recognized_toilet
    SceneLabel.Sink -> R.string.demo_ar_rerun_recognized_sink
    SceneLabel.Refrigerator -> R.string.demo_ar_rerun_recognized_refrigerator
    SceneLabel.Oven -> R.string.demo_ar_rerun_recognized_oven
    SceneLabel.Microwave -> R.string.demo_ar_rerun_recognized_microwave
    SceneLabel.Television -> R.string.demo_ar_rerun_recognized_television
    SceneLabel.Plant -> R.string.demo_ar_rerun_recognized_plant
    SceneLabel.Door -> R.string.demo_ar_rerun_recognized_door
    SceneLabel.Window -> R.string.demo_ar_rerun_recognized_window
}

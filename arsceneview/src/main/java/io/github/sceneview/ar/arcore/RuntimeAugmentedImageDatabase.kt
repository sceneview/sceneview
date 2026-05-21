package io.github.sceneview.ar.arcore

import android.graphics.Bitmap
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.ImageInsufficientQualityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages an [AugmentedImageDatabase] that grows **at runtime** — letting the user register a
 * brand-new reference image on-device (e.g. from a photo they just took) without a pre-bundled
 * `arcoreimg` database or a trip to a desktop tool (#1553, follow-up of #1437).
 *
 * The whole point of this helper is to make the "capture a photo → start tracking it" flow a
 * couple of lines. Pair it with [Frame.captureCameraBitmap] to grab the live AR camera frame
 * and with the `ARSceneView` `onSessionUpdated` callback to surface detected images:
 *
 * ```kotlin
 * val runtimeDb = rememberRuntimeAugmentedImageDatabase()
 *
 * ARSceneView(
 *     // Apply whatever the runtime DB currently holds on every (re)configure.
 *     sessionConfiguration = { _, config -> runtimeDb.applyTo(config) },
 *     onSessionCreated = { session -> runtimeDb.bind(session) },
 *     onSessionUpdated = { _, frame ->
 *         frame.getUpdatedAugmentedImages().forEach { /* place AugmentedImageNode */ }
 *     }
 * ) { /* ... */ }
 *
 * // Later, from a "Capture" button:
 * scope.launch {
 *     val photo = withContext(Dispatchers.Default) { frame.captureCameraBitmap() }
 *     when (val result = runtimeDb.addImage("snapshot-1", photo!!)) {
 *         is AddImageResult.Added       -> { /* now tracked */ }
 *         is AddImageResult.LowQuality  -> showRetryHint()
 *         is AddImageResult.Error       -> showError(result.cause)
 *     }
 * }
 * ```
 *
 * ### Threading
 *
 * - [addImage] does the heavy ARCore feature-extraction off the main thread (it suspends on
 *   [Dispatchers.Default]); calling it directly on the main thread would jank the renderer.
 * - The resulting **session reconfigure runs on the main thread** — ARCore's
 *   `Session.configure()` is not safe to call from a background coroutine. [addImage] handles
 *   this internally (`withContext(Dispatchers.Main)`); callers must still call [addImage] from
 *   a coroutine, which they will be doing anyway for the suspend signature.
 *
 * This class is **not** thread-safe for concurrent [addImage] calls — serialise them (a single
 * UI "Capture" button naturally does). It is safe to read [imageNames] / [size] from any
 * thread once a call has returned.
 */
class RuntimeAugmentedImageDatabase {

    private data class Entry(val name: String, val bitmap: Bitmap, val widthInMeters: Float?)

    private val entries = mutableListOf<Entry>()

    private var session: Session? = null

    /** Number of images currently registered in the runtime database. */
    val size: Int get() = entries.size

    /**
     * Names of every image currently registered, in insertion order. Names are **not**
     * guaranteed unique — ARCore allows duplicates — but callers are encouraged to keep them
     * unique so [com.google.ar.core.AugmentedImage.getName] stays meaningful.
     */
    val imageNames: List<String> get() = entries.map { it.name }

    /**
     * Binds the live ARCore [Session] so [addImage] can rebuild and apply the database itself.
     *
     * Call this from `ARSceneView`'s `onSessionCreated`. Until a session is bound, [addImage]
     * still validates and stores the image but cannot apply it live — it returns
     * [AddImageResult.Added] and the image takes effect on the next [applyTo] (e.g. the next
     * `sessionConfiguration` pass).
     */
    fun bind(session: Session) {
        this.session = session
    }

    /** Drops the bound session reference — call from `onSessionPaused` / on teardown. */
    fun unbind() {
        this.session = null
    }

    /**
     * Builds a fresh [AugmentedImageDatabase] for [session] from every registered entry and
     * assigns it to [config] — the single place that knows how to translate the runtime
     * entry list into ARCore's config.
     *
     * Call this from the `ARSceneView` `sessionConfiguration` callback so the database is
     * re-applied whenever the session is (re)configured. Safe to call with an empty database
     * (it assigns an empty [AugmentedImageDatabase], which simply tracks nothing).
     *
     * Must be called on the main thread — it mutates the [Config] that ARCore will hand to
     * `Session.configure()`.
     */
    @MainThread
    fun applyTo(config: Config, session: Session) {
        config.augmentedImageDatabase = buildDatabase(session)
    }

    /**
     * Validates [bitmap], registers it under [name], and — if a [Session] is bound — rebuilds
     * the [AugmentedImageDatabase] and applies it live so ARCore starts tracking the new image
     * immediately.
     *
     * The expensive ARCore feature extraction (20–30 ms per image, more for large bitmaps)
     * runs on [Dispatchers.Default]; the session reconfigure that follows runs on
     * [Dispatchers.Main]. This is a `suspend` function — call it from a coroutine.
     *
     * @param name Name metadata stored on the resulting [com.google.ar.core.AugmentedImage].
     *   Does not have to be unique, but unique names keep detection callbacks unambiguous.
     * @param bitmap The reference image. Must be `ARGB_8888` — see [Image.toArgbBitmap] /
     *   [Frame.captureCameraBitmap] which already produce that format. The bitmap is retained
     *   by this helper so it can rebuild the database after later [addImage] calls; do not
     *   recycle it while it is registered.
     * @param widthInMeters Optional known physical width of the printed/displayed image. When
     *   provided ARCore estimates the pose immediately on detection (no need to view the image
     *   from several angles); `null` trades a slower first detection for not having to measure.
     * @return [AddImageResult.Added] on success, [AddImageResult.LowQuality] when ARCore
     *   rejects the bitmap for lacking trackable features, or [AddImageResult.Error] for any
     *   other failure (e.g. a non-`ARGB_8888` bitmap).
     */
    suspend fun addImage(
        name: String,
        bitmap: Bitmap,
        widthInMeters: Float? = null
    ): AddImageResult {
        val invalid = validateRuntimeImage(name, bitmap, widthInMeters)
        if (invalid != null) {
            return AddImageResult.Error(IllegalArgumentException(invalid))
        }

        val boundSession = session
        // Validate the image against ARCore's feature extractor off the main thread. We build a
        // throwaway single-image database purely as a quality probe — `addImage` is the only
        // ARCore API that surfaces ImageInsufficientQualityException.
        val probeResult = withContext(Dispatchers.Default) {
            if (boundSession == null) {
                // No session yet — we cannot probe quality. Defer the real check to the next
                // applyTo()/configure() and optimistically accept.
                AddImageResult.Added
            } else {
                runCatching {
                    AugmentedImageDatabase(boundSession).also { probe ->
                        if (widthInMeters != null) {
                            probe.addImage(name, bitmap, widthInMeters)
                        } else {
                            probe.addImage(name, bitmap)
                        }
                    }
                }.fold(
                    onSuccess = { AddImageResult.Added },
                    onFailure = { error ->
                        when (error) {
                            is ImageInsufficientQualityException -> AddImageResult.LowQuality
                            else -> AddImageResult.Error(error)
                        }
                    }
                )
            }
        }

        if (probeResult !is AddImageResult.Added) {
            return probeResult
        }

        entries += Entry(name, bitmap, widthInMeters)

        // Apply the rebuilt database on the main thread — Session.configure() is main-thread only.
        boundSession?.let { live ->
            withContext(Dispatchers.Main) {
                live.configure { config -> applyTo(config, live) }
            }
        }
        return AddImageResult.Added
    }

    /**
     * Removes every registered image and — if a session is bound — applies the now-empty
     * database on the main thread so ARCore stops tracking. Does not recycle the bitmaps; the
     * caller still owns them.
     */
    suspend fun clear() {
        entries.clear()
        session?.let { live ->
            withContext(Dispatchers.Main) {
                live.configure { config -> applyTo(config, live) }
            }
        }
    }

    /**
     * Rebuilds an [AugmentedImageDatabase] for [session] containing every registered entry.
     *
     * Synchronous — only call from a background thread or when the entry list is small.
     * [applyTo] / [addImage] call this internally on the appropriate thread.
     */
    private fun buildDatabase(session: Session): AugmentedImageDatabase =
        AugmentedImageDatabase(session).also { database ->
            entries.forEach { entry ->
                if (entry.widthInMeters != null) {
                    database.addImage(entry.name, entry.bitmap, entry.widthInMeters)
                } else {
                    database.addImage(entry.name, entry.bitmap)
                }
            }
        }
}

/**
 * Creates and remembers a [RuntimeAugmentedImageDatabase] scoped to the composable lifecycle.
 *
 * The helper is `remember`ed across recompositions so registered images survive a recompose,
 * and its bound [Session] reference is dropped automatically when the composable leaves the
 * tree so no stale `Session` is retained.
 *
 * ```kotlin
 * val runtimeDb = rememberRuntimeAugmentedImageDatabase()
 * ARSceneView(
 *     sessionConfiguration = { session, config -> runtimeDb.applyTo(config, session) },
 *     onSessionCreated = { runtimeDb.bind(it) }
 * ) { /* ... */ }
 * ```
 *
 * @see RuntimeAugmentedImageDatabase
 */
@Composable
fun rememberRuntimeAugmentedImageDatabase(): RuntimeAugmentedImageDatabase {
    val database = remember { RuntimeAugmentedImageDatabase() }
    DisposableEffect(database) {
        onDispose { database.unbind() }
    }
    return database
}

/**
 * Outcome of [RuntimeAugmentedImageDatabase.addImage].
 *
 * Modelled as a sealed hierarchy so callers can `when`-match exhaustively and give the user a
 * specific message — the most common failure ([LowQuality]) is recoverable by retaking the
 * photo, so it must be distinguishable from a hard error.
 */
sealed class AddImageResult {
    /** The image was registered (and applied live if a session was bound). */
    object Added : AddImageResult()

    /**
     * ARCore rejected the image because it has too few trackable features — typically a
     * low-contrast, blurry, glare-covered or near-uniform photo. The user should retake it
     * pointing at a more textured, well-lit, high-contrast subject.
     */
    object LowQuality : AddImageResult()

    /**
     * The image could not be added for a reason other than insufficient quality — e.g. a
     * bitmap that is not `ARGB_8888`, a non-positive [widthInMeters], or an empty name.
     */
    data class Error(val cause: Throwable) : AddImageResult()
}

/**
 * Pure-logic pre-flight validation for [RuntimeAugmentedImageDatabase.addImage] — checks the
 * arguments ARCore would otherwise reject with a generic `IllegalArgumentException` deep inside
 * a JNI call.
 *
 * Extracted as `internal` so the contract can be unit-tested without a live ARCore [Session]
 * (#1553). Mirrors the constraints documented on [com.google.ar.core.AugmentedImageDatabase.addImage].
 *
 * @return `null` when the arguments are valid, or a human-readable reason string when they are
 *   not (empty name, non-`ARGB_8888` bitmap, zero-sized bitmap, or non-positive width).
 */
internal fun validateRuntimeImage(
    name: String,
    bitmap: Bitmap,
    widthInMeters: Float?
): String? = when {
    name.isBlank() ->
        "Augmented image name must not be blank."
    bitmap.config != Bitmap.Config.ARGB_8888 ->
        "Augmented image bitmap must be ARGB_8888 (was ${bitmap.config}). " +
            "Use Frame.captureCameraBitmap() or Image.toArgbBitmap() which already produce it."
    bitmap.width <= 0 || bitmap.height <= 0 ->
        "Augmented image bitmap must have a non-zero size (was ${bitmap.width}x${bitmap.height})."
    widthInMeters != null && widthInMeters <= 0f ->
        "widthInMeters must be strictly greater than zero (was $widthInMeters), or null."
    else -> null
}

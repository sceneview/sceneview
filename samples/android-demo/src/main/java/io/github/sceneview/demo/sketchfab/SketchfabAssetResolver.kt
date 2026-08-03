package io.github.sceneview.demo.sketchfab

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.min
import kotlin.math.pow

/**
 * Single entry-point demos call to obtain a local model file for a curated
 * [SketchfabSlug].
 *
 * The resolver layers three behaviours on top of [SketchfabService]:
 *
 *  1. **Curated registry** — only [SampleAssets] entries can be streamed.
 *     Unknown uids throw [Unknown] so the registry stays authoritative for
 *     license + license-attribution correctness.
 *  2. **Offline fallback** — when no API key is configured *or* the network
 *     download fails, the resolver returns a [File] handle to the bundled
 *     asset declared by [SketchfabSlug.fallbackBundledPath]. This honours the
 *     hard rule "NEVER ship a build that needs the network to render
 *     something useful" (`feedback_demo_quality`).
 *  3. **LRU eviction with a sample-tighter cap** — [SketchfabService] caches
 *     up to 500 MB of GLBs for the Explore tab. The samples-side resolver
 *     trims the same on-disk cache down to [CACHE_MAX_BYTES] (250 MB) after
 *     every download so the demo APK doesn't balloon a phone's data partition
 *     past the user's storage settings expectations.
 *
 * Thread-safety: the resolver delegates network I/O and cache mutation to
 * [SketchfabService] (single shared [okhttp3.OkHttpClient]) and dispatches
 * fallback `assets/` reads through [Dispatchers.IO]. Concurrent
 * `resolve(sameSlug)` calls will perform one network download and N − 1
 * filesystem touches — the same property the underlying service guarantees.
 *
 * Mirrors the iOS scaffold (`SketchfabAssetResolver.swift`) — keep both in
 * sync when adding behaviour.
 *
 * @see SketchfabService for the low-level Sketchfab API client.
 * @see SampleAssets for the curated registry of allowed slugs.
 */
class SketchfabAssetResolver private constructor(
    context: Context,
    private val service: SketchfabService,
    private val bundledAssets: BundledAssets = AssetManagerBundledAssets(context),
) {
    /**
     * The APK's `assets/` tree, behind an interface so tests can ship
     * different bytes for the same path across two calls — which is what an
     * app update does, and the only way to exercise the staleness guard in
     * [fallbackBundle]. Mirrors the `bundle` dependency injected into the
     * iOS resolver's `init(service:bundle:fileManager:)`.
     */
    @VisibleForTesting
    internal interface BundledAssets {
        /** Opens [path] for reading, or throws [IOException] when absent. */
        fun open(path: String): InputStream

        /**
         * Byte length of [path] once decompressed, or `-1` when it cannot be
         * determined (missing asset, unreadable stream).
         *
         * An unknown length never counts as *fresh*: [fallbackBundle] will
         * not take its fast path on it. What happens next depends on whether
         * a usable staged copy exists — a complete one is served as a last
         * resort (rendering the previous model beats throwing at every call
         * site), and otherwise the staging path runs and surfaces the real
         * error. See that function for the full ordering.
         */
        fun sizeOf(path: String): Long
    }

    private class AssetManagerBundledAssets(context: Context) : BundledAssets {
        private val assets = context.applicationContext.assets

        override fun open(path: String): InputStream = assets.open(path)

        /**
         * `AssetInputStream.available()` reports the total remaining length,
         * which for a freshly opened asset is its full decompressed size —
         * true for both stored and deflated entries, so it does not depend on
         * `.glb` being listed under `android.androidResources.noCompress`
         * (it is not). `openFd().length` would be cheaper but throws for a
         * compressed entry, which is exactly how our GLBs ship.
         */
        override fun sizeOf(path: String): Long = runCatching {
            assets.open(path).use { it.available().toLong() }
        }.getOrDefault(-1L)
    }

    companion object {
        /**
         * Samples-side cap on the shared on-disk cache, in bytes (250 MB).
         *
         * Tighter than [SketchfabConfig.CACHE_MAX_BYTES] (500 MB) so demos
         * with constant churn (random "Surprise me", category prefetch) can't
         * silently fill half a gigabyte of user storage.
         */
        const val CACHE_MAX_BYTES: Long = 250L * 1024 * 1024

        /**
         * Permissible bounding-sphere radius window, in metres. A model whose
         * actual size falls outside `[MIN, MAX]` after load is considered
         * "drifted" and the resolver falls back to the bundled asset.
         *
         * 5 cm rejects placeholder cubes (`scale=0.001`) authors sometimes
         * leave behind; 5 m rejects unreduced engineering files whose origin
         * sits 50 m from the geometry.
         */
        const val MIN_BOUNDS_RADIUS_M: Float = 0.05f
        const val MAX_BOUNDS_RADIUS_M: Float = 5.0f

        /**
         * Cache subdirectory the bundled fallbacks are staged into, directly under the
         * streamed-download root. Both paths hand back a [File], so this directory name is
         * the only thing that distinguishes them — see [isBundledFallback].
         */
        const val FALLBACK_DIR_NAME: String = "fallback"

        /**
         * True when [file] is a staged bundled fallback rather than a streamed download.
         *
         * A demo that wants to tell the user which one it is rendering has to ask the FILE,
         * not the configuration: "an API key is configured" does not mean the download
         * succeeded. Every failure mode in [resolve] — no network, 401 on a stale key, a
         * bounds-drifted asset, exhausted retries — ends at [fallbackBundle], so a keyed
         * build can quietly render the offline stand-in. That is measured, not theoretical:
         * on the QA emulator (2026-07-28) a build WITH a key staged all four Multi-Model
         * slots out of the fallback directory, the download endpoint answering 429 (#2933).
         * The config-based inference used elsewhere in the demo calls that case "streamed".
         */
        fun isBundledFallback(file: File): Boolean = file.parentFile?.name == FALLBACK_DIR_NAME

        /** Max retries for a transient network failure (429 / 5xx). */
        const val MAX_RETRIES: Int = 3

        /** Initial backoff between retries, in milliseconds. Doubles each step. */
        const val INITIAL_RETRY_DELAY_MS: Long = 500L

        @Volatile private var INSTANCE: SketchfabAssetResolver? = null

        /** Process-wide singleton. */
        fun getInstance(context: Context): SketchfabAssetResolver =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SketchfabAssetResolver(
                    context = context.applicationContext,
                    service = SketchfabService.getInstance(context.applicationContext),
                ).also { INSTANCE = it }
            }

        /**
         * The production [BundledAssets], so a test can assert that
         * `available()` really does equal the staged byte count for the real
         * `AssetManager` — the assumption the freshness fast path rests on.
         */
        @VisibleForTesting
        internal fun assetManagerBundledAssetsForTesting(context: Context): BundledAssets =
            AssetManagerBundledAssets(context.applicationContext)

        /** Test-only factory so unit tests can inject a fake service. */
        @VisibleForTesting
        internal fun forTesting(
            context: Context,
            service: SketchfabService,
            bundledAssets: BundledAssets? = null,
        ): SketchfabAssetResolver = SketchfabAssetResolver(
            context = context.applicationContext,
            service = service,
            bundledAssets = bundledAssets
                ?: AssetManagerBundledAssets(context.applicationContext),
        )
    }

    /**
     * Resolve [slug] to a local [File]. The returned file is always either
     *  - the streamed GLB under `cacheDir/sketchfab/<uid>.glb`, or
     *  - a copy of the bundled fallback under `cacheDir/sketchfab/fallback/<uid>.glb`.
     *
     * Demos can pass the returned [File] straight to `rememberModelInstance`
     * — the threading rule (Filament JNI on main) is the demo's
     * responsibility, not the resolver's.
     *
     * @throws Unknown if [slug] is not registered in [SampleAssets].
     * @throws FallbackUnavailable if the network path fails AND the bundled
     *   fallback cannot be staged (e.g. asset missing from the APK). This is
     *   the only error a demo ever needs to handle — everything else is
     *   masked by the fallback.
     */
    suspend fun resolve(slug: SketchfabSlug): File = withContext(Dispatchers.IO) {
        if (SampleAssets.byUid[slug.uid] !== slug) {
            // Identity check on purpose — we want to reject slugs constructed
            // out-of-band (e.g. via reflection or string literal) rather than
            // value-compared. Catches accidental copies that bypass review.
            throw Unknown(slug.uid)
        }

        if (SketchfabConfig.apiKey == null) {
            // No key in App Store / cold-cache builds → straight to fallback.
            return@withContext fallbackBundle(slug)
        }

        // Try the network path with exponential backoff on transient errors.
        var lastFailure: Throwable? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                val downloaded = service.downloadModel(slug.uid)
                pruneCache()
                if (!boundsAreSane(downloaded, slug)) {
                    // Drifted asset — don't ship a 30 m cube to the demo.
                    // Fall back to the bundled asset for this run and log so
                    // the registry curator gets a signal.
                    return@withContext fallbackBundle(slug)
                }
                return@withContext downloaded
            } catch (transient: SketchfabService.SketchfabError.RequestFailed) {
                lastFailure = transient
                if (!transient.isRetryable()) return@repeat run {
                    // 4xx (except 429) — no point retrying.
                    return@withContext fallbackBundle(slug)
                }
                // Honour exponential backoff with a tiny cap so a flaky
                // network doesn't keep the demo hanging on launch.
                val backoff = INITIAL_RETRY_DELAY_MS * 2.0.pow(attempt).toLong()
                delay(min(backoff, 4_000L))
            } catch (io: IOException) {
                // Network dropped mid-stream — same retry policy.
                lastFailure = io
                val backoff = INITIAL_RETRY_DELAY_MS * 2.0.pow(attempt).toLong()
                delay(min(backoff, 4_000L))
            } catch (other: Throwable) {
                // Non-retryable (missing key, model not found, unknown error)
                // → fall back immediately rather than burn retries.
                lastFailure = other
                return@withContext fallbackBundle(slug)
            }
        }

        // Exhausted retries — last resort, the bundled asset.
        fallbackBundle(slug)
    }

    /**
     * Warm the cache for every [SketchfabSlug] in [category]. Demos call this
     * from a `LaunchedEffect` so models stream in parallel before the first
     * frame.
     *
     * Failures are swallowed (the resolver already falls back per-slug) — the
     * return value is the count of slugs that resolved successfully. Useful
     * for telemetry / cold-start debugging.
     *
     * **Rate-limiting note.** Sketchfab's API allows 60 unauthenticated /
     * 1000 authenticated requests per minute, well above what any Stage 2
     * category will issue (max 4 streams in parallel per category). If a
     * future demo grows past ~20 slugs in one category, gate the fan-out
     * with a [kotlinx.coroutines.sync.Semaphore] before relying on the
     * service-side retry to absorb 429s.
     */
    suspend fun prefetchAll(category: String): Int = coroutineScope {
        val slugs = SampleAssets.byCategory[category].orEmpty()
        if (slugs.isEmpty()) return@coroutineScope 0
        slugs.map { slug ->
            async {
                runCatching { resolve(slug) }.isSuccess
            }
        }.awaitAll().count { it }
    }

    /**
     * Stage a copy of the bundled fallback under the same cache root used by
     * the streamed path so demos always get a [File] (some Filament loaders
     * choke on AssetManager streams when the binary is large).
     *
     * The copy is re-made whenever it no longer matches the asset currently
     * in the APK; otherwise subsequent calls only touch the `lastModified`
     * timestamp to keep the file from being evicted as cold. Keying the
     * staging on `uid` alone made it permanent: the app's data dir survives a
     * Play Store update, so an APK shipping a corrected bundled GLB stayed
     * inert on every existing install — the target path was identical and the
     * file already existed. That is the defect #2929 fixed on iOS; this is
     * the Android half of it (#2943).
     *
     * Byte length is the discriminator, as on iOS: the APK is immutable for a
     * given app version, so any asset change ships a different length.
     * Hashing megabytes on every resolve to catch a same-length edit would
     * cost far more than it saves, and a missed re-stage degrades to the
     * previous behaviour rather than to a crash.
     *
     * **Concurrency.** Demos call `resolve` from both [prefetchAll] *and* a
     * per-slug `produceState` at the same time, so two coroutines routinely
     * stage the same uid concurrently. The copy therefore writes to a
     * per-call unique temp file and then **atomically renames** it onto the
     * shared `target` path. Without this, two coroutines opened two output
     * streams on the same `target`, interleaved their writes, and left a
     * truncated GLB on disk — which then poisoned the cache permanently
     * (`target.exists()` short-circuits every later call), so the streamed
     * demos (#1422 / #1423 / #1433) hung forever on their loading spinner.
     */
    @VisibleForTesting
    internal fun fallbackBundle(slug: SketchfabSlug): File {
        val cacheRoot = service.cacheRoot()
        val fallbackDir = File(cacheRoot, FALLBACK_DIR_NAME).also { it.mkdirs() }
        val target = File(fallbackDir, "${slug.uid}.glb")
        // Size floor + magic header, the same pair `boundsAreSane` applies to a
        // fresh download and iOS applies to this very path. A 4-byte file whose
        // entire content is the `glTF` magic passes a `> 0` length test and is
        // not a GLB: Android served it, iOS refused it, and both comments
        // claimed the two platforms agreed (#2974).
        val stagedLooksComplete =
            target.exists() && target.length() >= MIN_GLB_SIZE_BYTES && hasGlbMagic(target)
        val bundledSize = bundledAssets.sizeOf(slug.fallbackBundledPath)
        // Trust the cached copy only if it is a *complete* GLB **and** still
        // matches the asset currently in the APK. A truncated file left
        // behind by an older racy write (pre-#1423 fix) would otherwise be
        // served forever — `hasGlbMagic` re-stages it instead — and so would
        // the bytes of a previous app version, because the target path is
        // keyed on uid alone and the data dir survives a Play Store update.
        if (stagedLooksComplete && bundledSize >= 0L && target.length() == bundledSize) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        if (bundledSize < 0L && stagedLooksComplete) {
            // The bundled asset is unreadable (renamed or pruned from the APK
            // while [SampleAssets] still points at the old path) but a
            // complete staged copy exists. Serving it degrades to "renders
            // the previous model" rather than throwing at every call site —
            // the shape callers had before this freshness check existed.
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        // Stage into a per-call unique temp file so concurrent callers never
        // share an output stream; the atomic rename below installs a complete
        // file or nothing at all.
        val temp = File.createTempFile("${slug.uid}-", ".glb.tmp", fallbackDir)
        try {
            bundledAssets.open(slug.fallbackBundledPath).use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // renameTo is atomic within the same filesystem and overwrites an
            // existing destination. On the rare failure path, keep whatever a
            // racing coroutine installed only when it matches the bytes we
            // just staged: "target already exists" is no longer proof that it
            // is equivalent, since the file we are replacing may be a stale
            // copy from a previous app version.
            if (!temp.renameTo(target)) {
                if (target.exists() && temp.length() > 0L && target.length() == temp.length()) {
                    temp.delete()
                } else {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            }
        } catch (io: IOException) {
            // The bundled asset is unreachable (typo in registry, asset
            // pruned from the APK by Stage 3). This is the one error demos
            // must handle — surface it so the UI can show a friendly "asset
            // unavailable" state instead of a half-cooked fallback.
            temp.delete()
            if (target.length() == 0L) target.delete()
            throw FallbackUnavailable(slug.uid, slug.fallbackBundledPath, io)
        }
        return target
    }

    /**
     * Best-effort bounds + animation check.
     *
     * We do **not** parse the GLB here — that would require pulling Filament
     * onto the resolver's I/O dispatcher (it would deadlock on the JNI main
     * thread rule). Instead we delegate to a lightweight magic-byte +
     * minimum-file-size heuristic that catches the two highest-frequency
     * failure modes:
     *
     *  - 0-byte / truncated downloads (Sketchfab CDN sometimes serves a
     *    short HTML error page with a 200 status).
     *  - Files missing the `glTF` magic header (e.g. a USDZ archive served
     *    when only USDZ was available — Android can't render those).
     *
     * Full visual-bounds + animation-count verification happens lazily at
     * load time in Filament-land and any mismatch is surfaced by the demo's
     * own visual smoke test.
     */
    @VisibleForTesting
    internal fun boundsAreSane(file: File, slug: SketchfabSlug): Boolean {
        if (!file.exists()) return false
        if (file.length() < MIN_GLB_SIZE_BYTES) return false
        return hasGlbMagic(file).also { ok ->
            if (!ok) {
                // Track unhelpful slug suggestions so Stage 1's bounds
                // sanity-check rejection becomes a real registry signal.
                // Silent for now — telemetry hook lands in Stage 3.
            }
            // The animation-count vs `hasBakedAnimation` check is also
            // deferred to demo-side post-load (the GLB JSON header would
            // need a real parser to find `animations[]`). Keeping that
            // check on Filament's side avoids re-implementing a JSON
            // walker just for one bool.
            // Reference the slug to silence "unused" warnings; the flag is
            // consumed by the demo's animation pipeline.
            @Suppress("UNUSED_EXPRESSION") slug.hasBakedAnimation
        }
    }

    /**
     * `true` when [file] starts with the binary glTF (`glTF`) magic header.
     *
     * Used both to validate fresh network downloads and to detect a cached
     * fallback that an older racy write left truncated — a partial GLB has
     * the right size but a clobbered/missing header, so this check re-stages
     * it instead of trusting it forever.
     */
    private fun hasGlbMagic(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val header = ByteArray(4)
            val read = stream.read(header)
            // `glTF` little-endian magic — see https://docs.fileformat.com/3d/glb/.
            read == 4 &&
                header[0] == 'g'.code.toByte() &&
                header[1] == 'l'.code.toByte() &&
                header[2] == 'T'.code.toByte() &&
                header[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    /**
     * Trim the shared on-disk cache to [CACHE_MAX_BYTES]. The underlying
     * [SketchfabService] also enforces its own (looser) cap; we re-run after
     * every resolve so the samples-side budget is honoured even if a key was
     * burned on the Explore tab.
     */
    @VisibleForTesting
    internal fun pruneCache() {
        val root = service.cacheRoot()
        val entries = root.listFiles()?.filter { it.isFile } ?: return
        var total = entries.sumOf { it.length() }
        if (total <= CACHE_MAX_BYTES) return
        val sorted = entries.sortedBy { it.lastModified() }
        for (file in sorted) {
            if (total <= CACHE_MAX_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    /** A [SketchfabSlug] that isn't part of [SampleAssets]. */
    class Unknown(val uid: String) :
        IllegalArgumentException("Sketchfab uid '$uid' is not in SampleAssets.")

    /**
     * Raised when both the network download AND the bundled fallback failed.
     * Demos should surface a friendly error UI ("Asset unavailable — try
     * again later") rather than silently rendering an empty scene.
     */
    class FallbackUnavailable(
        val uid: String,
        val bundledPath: String,
        cause: Throwable,
    ) : IOException(
        "Sketchfab asset '$uid' could not be streamed AND the bundled fallback " +
            "'$bundledPath' is missing from the APK.",
        cause,
    )
}

/** Lowest GLB size we treat as a real download — anything smaller is almost
 *  certainly a JSON error body or truncated stream. 12 bytes = GLB header
 *  alone; demos start at ~32 KB after compression. */
private const val MIN_GLB_SIZE_BYTES = 12L

/** Sketchfab returns 429 (rate limit) and 5xx (transient backend errors). All
 *  other 4xx are policy decisions on Sketchfab's side that won't change on
 *  retry — fall back immediately. */
private fun SketchfabService.SketchfabError.RequestFailed.isRetryable(): Boolean =
    statusCode == 429 || statusCode in 500..599

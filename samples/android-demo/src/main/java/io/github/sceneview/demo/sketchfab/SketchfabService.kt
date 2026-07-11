package io.github.sceneview.demo.sketchfab

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Thread-safe client for the Sketchfab Data API v3.
 *
 * Responsibilities:
 *  - Build authenticated requests against [SketchfabConfig.BASE_URL].
 *  - Decode JSON via the data classes in `SketchfabModels.kt`.
 *  - Stream binary downloads to an on-disk LRU cache under
 *    `Context.cacheDir/sketchfab/`.
 *
 * Mirrors the iOS scaffold (`SketchfabService.swift`) — keep both in sync when
 * adding endpoints.
 *
 * All public methods are `suspend`, dispatch work to [Dispatchers.IO], and are
 * **cooperatively cancellable**: every network round-trip goes through OkHttp's
 * [executeAsync], so cancelling the calling coroutine aborts the underlying
 * call (socket included). That property is what keeps the Explore tab's
 * debounced live search from stacking zombie requests — with the old blocking
 * `execute()` each re-keyed Compose effect abandoned a request that kept
 * running to completion, and a fast-typing burst of those parallel calls is
 * exactly the bot signature CloudFront's WAF throttles (#2644, #2191).
 *
 * @param baseUrl API root; overridable so tests can point at a local
 *   MockWebServer. Production code always uses [SketchfabConfig.BASE_URL].
 * @param apiKeyProvider resolves the API token per request; overridable so
 *   tests can inject a fake without mutating global [SketchfabConfig] state.
 */
class SketchfabService @VisibleForTesting internal constructor(
    private val context: Context,
    private val baseUrl: String = SketchfabConfig.BASE_URL,
    private val apiKeyProvider: () -> String? = { SketchfabConfig.apiKey },
) {
    companion object {
        @Volatile private var INSTANCE: SketchfabService? = null

        /** Obtain the process-wide singleton. Pass any [Context] — the application
         *  context is held internally so this is safe to call from activities. */
        fun getInstance(context: Context): SketchfabService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SketchfabService(context.applicationContext).also { INSTANCE = it }
            }

        /**
         * Hard ceiling for one API call, end to end (DNS + connect + headers +
         * body). OkHttp ships per-phase 10 s defaults but NO overall cap, so on
         * a degraded mobile link a single call could hold its IO thread far
         * longer than any UI that still wants the answer. 20 s is generous for
         * a JSON feed page yet short enough that stacked-up work drains (#2644).
         * Binary GLB downloads stream well past 20 s legitimately, so the
         * download path lifts the ceiling — see [downloadBinary].
         */
        private const val CALL_TIMEOUT_SECONDS = 20L

        /**
         * Statuses worth exactly one retry. Live probing (2026-07-10, #2644)
         * showed Sketchfab's search backend answering in ~1.5–2.2 s against a
         * ~2.2 s origin deadline — under load a majority of search calls die
         * as HTTP 408 (observed: 7 of 12 in a burst), and an identical retry
         * moments later flips to 200. That server-side degradation is what
         * users experienced as "the search lost the connection". 429/5xx are
         * standard transient-retry citizens; 4xx auth/shape errors are not.
         */
        private val TRANSIENT_HTTP_CODES = setOf(408, 429, 500, 502, 503, 504)

        /**
         * Pause before the single transient retry. CloudFront in front of the
         * API caches error responses briefly (observed `Cache-Control:
         * max-age=300` on 408s, loosely enforced per edge), so an *immediate*
         * replay risks re-reading the cached failure; ~750 ms is enough to
         * usually skip past it while staying imperceptible next to the 350 ms
         * search debounce. Uses [kotlinx.coroutines.delay], so a superseded
         * search cancels its pending retry along with everything else.
         */
        private const val TRANSIENT_RETRY_DELAY_MS = 750L
    }

    /**
     * A modest on-disk HTTP cache for the JSON metadata responses (search,
     * staff-picks, featured, recently-added, model detail). The Sketchfab API
     * sends `Cache-Control` headers OkHttp honours, so an identical feed/search
     * request inside the cache window is served from disk instead of hitting
     * the network — which directly cuts the rate-limit (HTTP 429) pressure a
     * "basic"-plan Sketchfab key suffers from when the Explore tab reloads its
     * three feeds on every recomposition / pull-to-refresh (#2095).
     *
     * Lives under `cacheDir/sketchfab-http/`, separate from the GLB binary
     * cache (`cacheDir/sketchfab/`) so the two LRU policies don't fight. Sized
     * small (10 MB) — JSON metadata is tiny; the binaries are the bulk and have
     * their own [pruneCacheIfNeeded].
     */
    private val httpCache: Cache = Cache(
        directory = File(context.cacheDir, SketchfabConfig.HTTP_CACHE_DIR_NAME),
        maxSize = SketchfabConfig.HTTP_CACHE_MAX_BYTES,
    )

    @VisibleForTesting
    internal val client: OkHttpClient = OkHttpClient.Builder()
        .cache(httpCache)
        // Force HTTP/1.1 — the AWS WAF in front of `api.sketchfab.com`
        // (CloudFront) flags OkHttp's HTTP/2 + default TLS fingerprint as
        // bot traffic and returns HTTP 202 + empty body + `x-amzn-waf-action:
        // challenge` (a JS challenge the OkHttp client can't solve), which
        // would otherwise leave the Explore-tab carousels silently empty
        // (#2191). HTTP/1.1 + the explicit `User-Agent` header set on every
        // request side-step that particular WAF rule. The remaining WAF
        // failure paths (IP-reputation throttles, sustained traffic from one
        // egress) are caught explicitly as [SketchfabError.WafChallenge] so
        // the UI shows the "Sketchfab unavailable" banner instead of three
        // self-hiding feeds.
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        // Overall per-call ceiling for the JSON endpoints (#2644) — see
        // [CALL_TIMEOUT_SECONDS]. The GLB download path derives a no-ceiling
        // client from this one in [downloadBinary].
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @VisibleForTesting
    internal val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /**
     * Search models by free-text query.
     *
     * @param query Search text forwarded to `q=`.
     * @param categories Optional Sketchfab category slugs (e.g. `cars-vehicles`).
     * @param downloadable Restrict to models the API can serve as GLB/glTF.
     * @param limit Page size — Sketchfab caps this at 24.
     */
    suspend fun search(
        query: String,
        categories: List<String>? = null,
        downloadable: Boolean = true,
        limit: Int = 24,
    ): List<SketchfabModel> = withContext(Dispatchers.IO) {
        val url = buildUrl("search") {
            addQueryParameter("type", "models")
            addQueryParameter("q", query)
            addQueryParameter("downloadable", downloadable.toString())
            addQueryParameter("count", limit.toString())
            if (!categories.isNullOrEmpty()) {
                addQueryParameter("categories", categories.joinToString(","))
            }
        }
        val body = authenticatedGet(url)
        json.decodeFromString(SketchfabSearchResponse.serializer(), body).results
    }

    /**
     * Featured / "most liked" models, optionally filtered by category.
     *
     * Uses `sort_by=-likeCount` since Sketchfab does not expose a dedicated
     * "featured" endpoint.
     */
    suspend fun featured(
        category: String? = null,
        animated: Boolean? = null,
        limit: Int = 10,
    ): List<SketchfabModel> = list(
        sortBy = "-likeCount",
        animated = animated,
        category = category,
        limit = limit,
    )

    /**
     * "Staff Picks" — hand-curated by Sketchfab's editorial team. Mirrors the
     * iOS [`staffPicks`](SketchfabService.swift) helper so both demos hit the
     * same wire format.
     */
    suspend fun staffPicks(
        category: String? = null,
        animated: Boolean? = null,
        limit: Int = 10,
    ): List<SketchfabModel> = list(
        sortBy = "-staffPickedAt",
        staffPicked = true,
        animated = animated,
        category = category,
        limit = limit,
    )

    /** Trending right now (sorted by `-viewCount`). */
    suspend fun mostPopular(
        category: String? = null,
        animated: Boolean? = null,
        limit: Int = 10,
    ): List<SketchfabModel> = list(
        sortBy = "-viewCount",
        animated = animated,
        category = category,
        limit = limit,
    )

    /** Recently published downloadable models. */
    suspend fun recentlyAdded(
        category: String? = null,
        animated: Boolean? = null,
        limit: Int = 10,
    ): List<SketchfabModel> = list(
        sortBy = "-publishedAt",
        animated = animated,
        category = category,
        limit = limit,
    )

    /** Internal helper used by the curated-feed methods. */
    private suspend fun list(
        sortBy: String,
        staffPicked: Boolean = false,
        animated: Boolean? = null,
        category: String? = null,
        limit: Int,
    ): List<SketchfabModel> = withContext(Dispatchers.IO) {
        val url = buildUrl("models") {
            addQueryParameter("type", "models")
            addQueryParameter("sort_by", sortBy)
            addQueryParameter("downloadable", "true")
            addQueryParameter("count", limit.toString())
            if (staffPicked) addQueryParameter("staffpicked", "true")
            if (animated != null) addQueryParameter("animated", animated.toString())
            if (!category.isNullOrBlank()) addQueryParameter("categories", category)
        }
        val body = authenticatedGet(url)
        json.decodeFromString(SketchfabSearchResponse.serializer(), body).results
    }

    /**
     * Resolve the signed CDN URL for a model's preferred format (GLB > glTF > USDZ).
     *
     * The returned URL is short-lived (see [SketchfabDownloadUrl.expires]) and
     * must be fetched WITHOUT the Sketchfab `Authorization` header — the CDN
     * rejects authenticated requests with HTTP 403.
     */
    suspend fun downloadUrl(uid: String): String = withContext(Dispatchers.IO) {
        val url = buildUrl("models/$uid/download") {}
        val body = authenticatedGet(url)
        val response = json.decodeFromString(SketchfabDownloadResponse.serializer(), body)
        response.preferred?.url ?: throw SketchfabError.ModelNotFound
    }

    /**
     * Download a model to the on-disk cache and return the local file.
     *
     * If the file is already cached its modification date is touched (LRU
     * marker) and the cached path is returned without hitting the network.
     *
     * @param uid Sketchfab model uid.
     * @param onProgress Optional callback fired while the binary is streaming.
     *   Receives `(bytesRead, totalBytes)` — `totalBytes` is `-1` when the
     *   server omits a `Content-Length` header. Called from [Dispatchers.IO];
     *   update Compose state via a [kotlinx.coroutines.channels.Channel].
     */
    suspend fun downloadModel(
        uid: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val cacheFile = cacheFileFor(uid)
        if (cacheFile.exists()) {
            cacheFile.setLastModified(System.currentTimeMillis())
            return@withContext cacheFile
        }

        val remoteUrl = downloadUrl(uid)
        downloadBinary(remoteUrl, cacheFile, onProgress)
        pruneCacheIfNeeded()
        cacheFile
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * Build the absolute URL for a given API path. Exposed `internal` so unit
     * tests can verify path construction without making network calls.
     */
    @VisibleForTesting
    internal fun buildUrl(
        path: String,
        block: HttpUrl.Builder.() -> Unit,
    ): HttpUrl {
        val base = baseUrl.toHttpUrl()
        return base.newBuilder()
            .addPathSegments(path)
            .apply(block)
            .build()
    }

    private suspend fun authenticatedGet(url: HttpUrl): String {
        val apiKey = apiKeyProvider() ?: throw SketchfabError.MissingApiKey
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $apiKey")
            .header("Accept", "application/json")
            .header("Accept-Charset", "utf-8")
            .header("User-Agent", SketchfabConfig.USER_AGENT)
            .get()
            .build()
        return try {
            fetchOnce(request)
        } catch (e: SketchfabError.RequestFailed) {
            // Exactly one retry for transient statuses (#2644): the degraded
            // Sketchfab search backend 408s a majority of burst queries, and
            // the same query re-sent moments later flips to 200 — see
            // [TRANSIENT_HTTP_CODES]. Piling more retries onto an unhealthy
            // origin would make things worse, so one is the cap. delay() is
            // cancellable: a superseded search abandons its pending retry the
            // same way it abandons the in-flight call.
            if (e.statusCode !in TRANSIENT_HTTP_CODES) throw e
            delay(TRANSIENT_RETRY_DELAY_MS)
            fetchOnce(request)
        }
    }

    /**
     * Run [block] with [call]'s lifecycle tied to the calling coroutine for
     * its WHOLE duration. [executeAsync] alone only binds cancellation to the
     * response-await phase; once the headers are in, reading the body is a
     * blocking Okio read that would run to completion regardless — the exact
     * zombie-request gap this fix exists to close (#2644). The watcher extends
     * the tie: cancellation at ANY phase invokes [okhttp3.Call.cancel], which
     * closes the socket and aborts an in-flight read with an IOException.
     *
     * Two deliberate properties:
     *  - On normal completion the watcher is cancelled; its `finally` still
     *    runs [okhttp3.Call.cancel], which is a documented no-op on a
     *    finished call.
     *  - When the caller IS cancelled, the function completes with the
     *    caller's CancellationException — the socket-abort IOException can
     *    never masquerade as a network failure to `catch` blocks upstream.
     *    [coroutineScope] alone does NOT guarantee this: the socket close the
     *    watcher triggers aborts an in-flight blocking body read with an
     *    IOException (`SocketException: Socket closed`), and when the block
     *    completes with that non-cancellation exception `coroutineScope`
     *    surfaces IT, not the cancellation (kotlinx prefers the first
     *    non-cancellation exception when finalising a cancelling scope). The
     *    [ensureActive] below closes that race: an IOException thrown while the
     *    scope is already cancelled is re-canonised into the caller's
     *    CancellationException; a genuine network IOException on a still-active
     *    scope is rethrown unchanged. Without it the Explore search's
     *    `catch (CancellationException)` fast-path is bypassed under load and a
     *    superseded query flashes its error/empty state (#2665 CI flake, #2644).
     */
    private suspend fun <T> executeCancellably(
        call: okhttp3.Call,
        block: suspend () -> T,
    ): T = coroutineScope {
        val tie = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            block()
        } catch (e: IOException) {
            // If the caller cancelled us, the closed-socket IOException is just
            // the shape the abort took — surface the cancellation instead.
            // ensureActive() throws the scope's CancellationException when the
            // scope is cancelling; otherwise it is a no-op and we rethrow the
            // real network failure below.
            coroutineContext.ensureActive()
            throw e
        } finally {
            tie.cancel()
        }
    }

    @Suppress("ThrowsCount") // fine-grained error types (WAF, key-rejected, request-failed) need multiple throws
    private suspend fun fetchOnce(request: Request): String {
        // executeAsync (not the blocking execute()): cancelling the calling
        // coroutine cancels the OkHttp call and closes its socket — see
        // [executeCancellably] for why the tie must outlive the await phase.
        // This is the load-bearing half of the #2644 fix: when the Explore
        // search effect re-keys on the next debounced query, the superseded
        // request dies instead of completing in the background and stacking
        // WAF pressure.
        val call = client.newCall(request)
        return executeCancellably(call) {
            call.executeAsync().use { response ->
                // AWS WAF in front of Sketchfab returns HTTP 202 + empty body +
                // `x-amzn-waf-action: challenge` (or `block`) when it wants the
                // client to solve a JS / CAPTCHA challenge. The body is empty, so
                // the JSON decoder would otherwise throw `Expected start of the
                // object '{', but had 'EOF' instead`. Surface it as a typed error
                // so the UI shows the SketchfabDisabledBanner instead of three
                // silent self-hiding feeds (#2191). The companion fix is the
                // app-identifying User-Agent injected above — sending
                // `okhttp/<version>` is the bot signature that triggered the
                // challenge in the first place.
                val wafAction = response.header("x-amzn-waf-action")
                if (wafAction != null) {
                    throw SketchfabError.WafChallenge(wafAction)
                }
                if (!response.isSuccessful) {
                    // 401 Unauthorized / 403 Forbidden mean the API key itself was
                    // rejected (missing scope, revoked, or a typo'd secret) — a
                    // distinct failure from a transient 429 / 5xx. Surface it as
                    // its own error so the Explore feed can show the
                    // "Sketchfab unavailable" banner instead of silently
                    // collapsing into an empty feed (#2095).
                    if (response.code == 401 || response.code == 403) {
                        throw SketchfabError.KeyRejected(response.code)
                    }
                    throw SketchfabError.RequestFailed(response.code)
                }
                // Force UTF-8 decoding regardless of the Content-Type charset.
                // `body.string()` honours the response charset and falls back to
                // ISO-8859-1 when the header lacks a `charset=` parameter — which
                // can happen with edge-cache rewrites — corrupting any non-ASCII
                // character in model names like `Myślinice` (Polish ś → U+FFFD).
                // The Sketchfab API always returns UTF-8 bytes, so decoding them
                // as UTF-8 explicitly is both correct and defensive (#1181).
                response.body.source().readString(Charsets.UTF_8)
            }
        }
    }

    /**
     * Derived client for GLB binary streaming: same pool/cache/dispatcher as
     * [client], but with the overall call ceiling lifted — a large model on a
     * slow link legitimately streams for minutes, and the per-phase read
     * timeout (OkHttp's 10 s default between socket reads) already catches a
     * genuinely dead transfer. `newBuilder()` shares resources, so this is a
     * view over the same engine, not a second connection pool.
     */
    private val downloadClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    @Suppress("NestedBlockDepth") // OkHttp use{} + temp-file try/catch + streaming loop is inherently nested
    private suspend fun downloadBinary(
        remoteUrl: String,
        destination: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        // The signed CDN URL must NOT carry the Sketchfab auth header.
        val request = Request.Builder().url(remoteUrl).get().build()
        // Tied to the caller for the WHOLE stream (#2644, see
        // [executeCancellably]): cancelling mid-download cancels the call, the
        // closed socket aborts the read loop, the finally below reclaims the
        // temp file, and coroutineScope surfaces the caller's
        // CancellationException — never a bogus DownloadFailed.
        val call = downloadClient.newCall(request)
        executeCancellably(call) {
            call.executeAsync().use { response ->
                if (!response.isSuccessful) {
                    throw SketchfabError.DownloadFailed("HTTP ${response.code}")
                }
                val body = response.body
                val totalBytes = body.contentLength() // -1 when unknown
                destination.parentFile?.mkdirs()
                // Stream into a per-call unique temp file, then atomically rename
                // onto `destination`. Concurrent downloads of the same uid (the
                // prefetch + per-slug resolve fan-out) therefore never share an
                // output stream and can't leave a truncated GLB in the cache.
                val temp = File.createTempFile(
                    "${destination.nameWithoutExtension}-",
                    ".glb.tmp",
                    destination.parentFile,
                )
                try {
                    var bytesRead = 0L
                    // Throttle callbacks: fire at most every 256 KB or 1 % of total,
                    // whichever is larger — keeps UI smooth without saturating the
                    // channel on fast connections.
                    val throttle = if (totalBytes > 0L) maxOf(totalBytes / 100L, 256 * 1024L)
                                   else 256 * 1024L
                    var nextReport = throttle
                    val buffer = ByteArray(8 * 1024)
                    temp.outputStream().use { out ->
                        body.byteStream().use { input ->
                            var n: Int
                            while (input.read(buffer).also { n = it } != -1) {
                                // Belt-and-braces next to the watcher: bail out
                                // of the CPU side of the loop as soon as the
                                // caller is cancelled, before touching the
                                // socket again. Throws CancellationException;
                                // the finally below reclaims the temp file.
                                coroutineContext.ensureActive()
                                out.write(buffer, 0, n)
                                bytesRead += n
                                if (onProgress != null && bytesRead >= nextReport) {
                                    onProgress(bytesRead, totalBytes)
                                    nextReport = bytesRead + throttle
                                }
                            }
                            // Final notification at 100 %.
                            onProgress?.invoke(bytesRead, totalBytes)
                        }
                    }
                    if (!temp.renameTo(destination)) {
                        temp.copyTo(destination, overwrite = true)
                    }
                } catch (io: IOException) {
                    throw SketchfabError.DownloadFailed(io.message ?: "io error", cause = io)
                } finally {
                    // Success renames temp away; every failure path — IOException,
                    // cancellation mid-stream, copyTo fallback — leaves it behind.
                    // One cleanup covers them all: no truncated GLB, no orphaned
                    // .tmp accumulating in the cache dir.
                    if (temp.exists()) temp.delete()
                }
            }
        }
    }

    // ── Cache management ──────────────────────────────────────────────────

    @VisibleForTesting
    internal fun cacheRoot(): File {
        val root = File(context.cacheDir, SketchfabConfig.CACHE_DIR_NAME)
        if (!root.exists()) root.mkdirs()
        return root
    }

    private fun cacheFileFor(uid: String): File = File(cacheRoot(), "$uid.glb")

    /** Evict oldest files when total size exceeds [SketchfabConfig.CACHE_MAX_BYTES]. */
    @VisibleForTesting
    internal fun pruneCacheIfNeeded() {
        val root = cacheRoot()
        val entries = root.listFiles()?.filter { it.isFile } ?: return
        var total = entries.sumOf { it.length() }
        if (total <= SketchfabConfig.CACHE_MAX_BYTES) return

        // Oldest first.
        val sorted = entries.sortedBy { it.lastModified() }
        for (file in sorted) {
            if (total <= SketchfabConfig.CACHE_MAX_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    /** Errors surfaced by [SketchfabService]. */
    sealed class SketchfabError : Exception() {
        /** [SketchfabConfig.apiKey] returned `null` — no key was injected. */
        object MissingApiKey : SketchfabError() {
            private fun readResolve(): Any = MissingApiKey
            override val message: String get() = "SKETCHFAB_API_KEY is not configured."
        }

        /** The Sketchfab API returned a non-2xx status. */
        class RequestFailed(val statusCode: Int) : SketchfabError() {
            override val message: String get() = "Sketchfab request failed with HTTP $statusCode."
        }

        /**
         * The Sketchfab API key was rejected — HTTP 401 (Unauthorized) or 403
         * (Forbidden). The key is present but invalid, revoked, or lacks the
         * required scope. Distinct from a transient [RequestFailed] (429 / 5xx)
         * so the UI can tell the user "Sketchfab unavailable" rather than
         * silently showing an empty feed (#2095).
         */
        class KeyRejected(val statusCode: Int) : SketchfabError() {
            override val message: String
                get() = "Sketchfab API key was rejected (HTTP $statusCode)."
        }

        /** Failure while streaming the GLB from the signed CDN URL. */
        class DownloadFailed(val reason: String, cause: Throwable? = null) : SketchfabError() {
            init { cause?.let { initCause(it) } }
            override val message: String get() = "Sketchfab download failed: $reason."
        }

        /** `GET /v3/models/<uid>/download` returned no supported format. */
        object ModelNotFound : SketchfabError() {
            private fun readResolve(): Any = ModelNotFound
            override val message: String get() = "No downloadable format available for the requested model."
        }

        /**
         * AWS CloudFront's WAF (in front of `api.sketchfab.com`) returned a
         * challenge response (`x-amzn-waf-action: challenge` / `block`) with
         * an empty body. Typically caused by a `User-Agent: okhttp/<version>`
         * that the WAF treats as bot traffic, or by sustained high request
         * volume from a single source IP. The UI surfaces this via the
         * "Sketchfab unavailable" banner so the user sees an explanation
         * instead of three silently self-hiding carousels (#2191).
         */
        class WafChallenge(val action: String) : SketchfabError() {
            override val message: String
                get() = "Sketchfab CloudFront WAF returned action='$action' — request blocked or challenged."
        }
    }
}

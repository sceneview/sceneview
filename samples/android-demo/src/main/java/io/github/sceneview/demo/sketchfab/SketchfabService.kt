package io.github.sceneview.demo.sketchfab

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

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
 * All public methods are `suspend` and dispatch work to [Dispatchers.IO].
 */
class SketchfabService private constructor(
    private val context: Context,
) {
    companion object {
        @Volatile private var INSTANCE: SketchfabService? = null

        /** Obtain the process-wide singleton. Pass any [Context] — the application
         *  context is held internally so this is safe to call from activities. */
        fun getInstance(context: Context): SketchfabService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SketchfabService(context.applicationContext).also { INSTANCE = it }
            }
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
     * @param onProgress Optional progress callback fired periodically during
     *   the streaming download. `bytesRead` accumulates from `0L` to the
     *   final body size; `totalBytes` is the `Content-Length` reported by the
     *   server (or `-1L` if the server omitted it — rare but possible for
     *   chunked-transfer responses). The callback is invoked on the IO
     *   dispatcher; if the UI needs to update from it, hop to the main
     *   thread inside the callback. Fired once with `(size, size)` at the
     *   end so the UI can settle on 100 % even when the body Content-Length
     *   was unknown (#2232).
     */
    suspend fun downloadModel(
        uid: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val cacheFile = cacheFileFor(uid)
        if (cacheFile.exists()) {
            cacheFile.setLastModified(System.currentTimeMillis())
            // Cache hit — fire a single 100 % progress event so the UI exits
            // its loading state immediately instead of waiting for the model
            // parser to start. `length()` is used as both `read` and `total`
            // so the bar settles at 1.0 rather than flashing 0 %.
            onProgress?.invoke(cacheFile.length(), cacheFile.length())
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
        val base = SketchfabConfig.BASE_URL.toHttpUrl()
        return base.newBuilder()
            .addPathSegments(path)
            .apply(block)
            .build()
    }

    private fun authenticatedGet(url: HttpUrl): String {
        val apiKey = SketchfabConfig.apiKey ?: throw SketchfabError.MissingApiKey
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $apiKey")
            .header("Accept", "application/json")
            .header("Accept-Charset", "utf-8")
            .header("User-Agent", SketchfabConfig.USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
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
            return response.body.source().readString(Charsets.UTF_8)
        }
    }

    private fun downloadBinary(
        remoteUrl: String,
        destination: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        // The signed CDN URL must NOT carry the Sketchfab auth header.
        val request = Request.Builder().url(remoteUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SketchfabError.DownloadFailed("HTTP ${response.code}")
            }
            val body = response.body
            // contentLength() returns -1L for chunked-transfer responses
            // (rare but possible) — surfaced as-is so the UI can render a
            // determinate bar when known and fall back to indeterminate.
            val total = body.contentLength()
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
                temp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        // Manual chunked copy with progress (#2232). 16 KB
                        // buffer matches OkHttp's internal default and keeps
                        // the callback frequency reasonable (~60 Hz on 4G,
                        // ~600 Hz on 5G — still well under any UI debounce
                        // budget). Coalescing on the UI side handles bursty
                        // emissions.
                        val buffer = ByteArray(16 * 1024)
                        var totalRead = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            totalRead += read
                            onProgress?.invoke(totalRead, total)
                        }
                        // Always fire a final 100 % event so the UI can
                        // settle on "done" even when contentLength was -1L
                        // (chunked transfer — totalRead becomes the de-facto
                        // total once the stream ends).
                        onProgress?.invoke(totalRead, totalRead)
                    }
                }
                if (!temp.renameTo(destination)) {
                    temp.copyTo(destination, overwrite = true)
                    temp.delete()
                }
            } catch (io: IOException) {
                temp.delete()
                throw SketchfabError.DownloadFailed(io.message ?: "io error")
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
        class DownloadFailed(val reason: String) : SketchfabError() {
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

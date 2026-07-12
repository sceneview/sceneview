package io.github.sceneview.demo.sources

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.github.sceneview.demo.sketchfab.SketchfabConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [ModelSource] for **Poly Haven** — CC0 assets with pristine PBR
 * (`api.polyhaven.com`), no auth (#2645).
 *
 * Poly Haven has no editorial "staff picks" and no server-side keyword search,
 * so this source honestly exposes only Trending (by download count) and
 * Recently added (by publish date), and searches client-side over the models
 * index. Its models ship as multi-file glTF, so [download] fetches the root
 * `.gltf` plus its textures/buffers into one directory — SceneView's
 * `ModelLoader` resolves the external URIs from there.
 *
 * The iOS demo's `ExploreTab` will mirror this same `ModelSource` shape (spec
 * item 4 of #2645); that Swift port is a follow-up — keep the two in sync when
 * it lands.
 */
class PolyHavenService @VisibleForTesting internal constructor(
    context: Context,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val cdnBaseUrl: String = DEFAULT_CDN_BASE_URL,
    private val client: OkHttpClient = defaultClient,
    private val downloader: NetworkModelDownloader = NetworkModelDownloader(context),
) : ModelSource {

    constructor(context: Context) : this(context, DEFAULT_BASE_URL)

    override val id: ModelSourceId = ModelSourceId.POLY_HAVEN

    /** CC0, no key — always available. */
    override val isAvailable: Boolean = true

    // Poly Haven has no editorial curation and no "animated" concept.
    override val feedKinds: List<FeedKind> = listOf(FeedKind.TRENDING, FeedKind.RECENTLY_ADDED)

    @VisibleForTesting
    internal val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    // The models index is one call for the whole catalog; cache it briefly so a
    // feed refresh + search burst doesn't re-download it three times.
    @Volatile private var cachedIndex: Map<String, PolyHavenAsset>? = null
    @Volatile private var cachedAtMs: Long = 0L

    override suspend fun feed(kind: FeedKind, animatedOnly: Boolean, limit: Int): List<GalleryModel> =
        withContext(Dispatchers.IO) {
            val index = modelsIndex()
            val sorted = when (kind) {
                FeedKind.TRENDING -> index.entries.sortedByDescending { it.value.downloadCount }
                FeedKind.RECENTLY_ADDED -> index.entries.sortedByDescending { it.value.datePublished }
                FeedKind.STAFF_PICKS -> index.entries // unreachable: not in feedKinds
            }
            sorted.take(limit).map { it.value.toGalleryModel(it.key, cdnBaseUrl) }
        }

    override suspend fun search(query: String, limit: Int): List<GalleryModel> =
        withContext(Dispatchers.IO) {
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) return@withContext emptyList()
            modelsIndex().entries
                .filter { (slug, asset) -> asset.matches(slug, needle) }
                .sortedByDescending { it.value.downloadCount }
                .take(limit)
                .map { it.value.toGalleryModel(it.key, cdnBaseUrl) }
        }

    override suspend fun download(
        model: GalleryModel,
        onProgress: ((Long, Long) -> Unit)?,
    ): File = withContext(Dispatchers.IO) {
        val files = json.parseToJsonElement(getBody(buildUrl("files/${model.id}") {})).jsonObject
        val gltf = files["gltf"]?.jsonObject
            ?: throw IOException("Poly Haven asset ${model.id} has no glTF files")
        // Prefer the lightest resolution for a snappy demo download.
        val resolution = gltf.keys.firstOrNull { it.equals("1k", ignoreCase = true) } ?: gltf.keys.firstOrNull()
            ?: throw IOException("Poly Haven asset ${model.id} exposes no glTF resolution")
        val resObj = gltf.getValue(resolution).jsonObject
        // Inside a resolution the file object is keyed by format name (e.g. "gltf");
        // take the first entry rather than assuming the key.
        val fileObj = resObj.values.firstOrNull()?.jsonObject
            ?: throw IOException("Poly Haven asset ${model.id} glTF entry is empty")
        val rootUrl = fileObj["url"]?.jsonPrimitive?.content
            ?: throw IOException("Poly Haven asset ${model.id} glTF has no url")
        val rootRelative = rootUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "model.gltf" }
        val resources = fileObj["include"]?.jsonObject.orEmptyObject().mapNotNull { (rel, node) ->
            val url = node.jsonObject["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            rel to url
        }.toMap()
        downloader.downloadBundle(id, model.id, rootRelative, rootUrl, resources, onProgress)
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private suspend fun modelsIndex(): Map<String, PolyHavenAsset> {
        val cached = cachedIndex
        if (cached != null && System.currentTimeMillis() - cachedAtMs < INDEX_TTL_MS) return cached
        val url = buildUrl("assets") { addQueryParameter("t", "models") }
        val index = json.decodeFromString<Map<String, PolyHavenAsset>>(getBody(url))
        cachedIndex = index
        cachedAtMs = System.currentTimeMillis()
        return index
    }

    @VisibleForTesting
    internal fun buildUrl(path: String, block: HttpUrl.Builder.() -> Unit): HttpUrl =
        baseUrl.toHttpUrl().newBuilder().addPathSegments(path).apply(block).build()

    private suspend fun getBody(url: HttpUrl): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", SketchfabConfig.USER_AGENT)
            .get()
            .build()
        client.newCall(request).executeAsync().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Poly Haven request failed with HTTP ${response.code}")
            }
            return response.body.source().readString(Charsets.UTF_8)
        }
    }

    private fun JsonObject?.orEmptyObject(): Map<String, kotlinx.serialization.json.JsonElement> =
        this ?: emptyMap()

    companion object {
        /** Poly Haven public API root. Always ends with a trailing slash. */
        const val DEFAULT_BASE_URL: String = "https://api.polyhaven.com/"

        /** CDN root serving the `asset_img/thumbs/<slug>.png` previews. */
        const val DEFAULT_CDN_BASE_URL: String = "https://cdn.polyhaven.com/"

        /** In-memory TTL for the models index (5 minutes). */
        private const val INDEX_TTL_MS = 5L * 60 * 1000

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

// ── Wire model (only the fields the demo needs) ─────────────────────────────

@Serializable
internal data class PolyHavenAsset(
    val name: String? = null,
    @kotlinx.serialization.SerialName("date_published") val datePublished: Long = 0L,
    @kotlinx.serialization.SerialName("download_count") val downloadCount: Long = 0L,
    val authors: Map<String, String> = emptyMap(),
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    fun matches(slug: String, needle: String): Boolean =
        (name?.lowercase()?.contains(needle) == true) ||
            slug.lowercase().contains(needle) ||
            tags.any { it.lowercase().contains(needle) } ||
            categories.any { it.lowercase().contains(needle) }

    fun toGalleryModel(slug: String, cdnBaseUrl: String): GalleryModel {
        val thumbBase = "${cdnBaseUrl}asset_img/thumbs/$slug.png"
        return GalleryModel(
            sourceId = ModelSourceId.POLY_HAVEN,
            id = slug,
            name = name ?: slug.replace('_', ' ').replaceFirstChar { it.uppercase() },
            thumbnails = listOf(
                GalleryThumbnail("$thumbBase?height=360", width = 640, height = 360),
                GalleryThumbnail("$thumbBase?height=720", width = 1280, height = 720),
            ),
            attribution = GalleryAttribution(
                authorName = authors.keys.firstOrNull(),
                // Poly Haven is uniformly CC0.
                license = "CC0",
                sourceUrl = "https://polyhaven.com/a/$slug",
            ),
            tags = (categories + tags).distinct(),
        )
    }
}

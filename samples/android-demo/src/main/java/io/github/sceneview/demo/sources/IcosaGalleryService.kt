package io.github.sceneview.demo.sources

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.github.sceneview.demo.sketchfab.SketchfabConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [ModelSource] for **Icosa Gallery** — the open-source Google Poly successor
 * (`api.icosa.gallery/v1`). Creative-Commons, glTF-native, no auth or free key
 * (#2645). The best drop-in Sketchfab-shaped replacement: it exposes trending /
 * curated / newest feeds and free-text search, all as plain JSON.
 *
 * The iOS demo's `ExploreTab` will mirror this same `ModelSource` shape (spec
 * item 4 of #2645); that Swift port is a follow-up — keep the two in sync when
 * it lands.
 *
 * @param baseUrl API root; overridable so unit tests can point at a local
 *   MockWebServer. Production always uses [DEFAULT_BASE_URL].
 */
class IcosaGalleryService @VisibleForTesting internal constructor(
    context: Context,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient,
    private val downloader: NetworkModelDownloader = NetworkModelDownloader(context),
) : ModelSource {

    constructor(context: Context) : this(context, DEFAULT_BASE_URL)

    override val id: ModelSourceId = ModelSourceId.ICOSA

    /** No key required — always available. */
    override val isAvailable: Boolean = true

    override val feedKinds: List<FeedKind> =
        listOf(FeedKind.TRENDING, FeedKind.STAFF_PICKS, FeedKind.RECENTLY_ADDED)

    @VisibleForTesting
    internal val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    override suspend fun feed(kind: FeedKind, animatedOnly: Boolean, limit: Int): List<GalleryModel> =
        withContext(Dispatchers.IO) {
            val url = buildUrl("assets") {
                addQueryParameter("format", "GLTF2")
                addQueryParameter("pageSize", limit.toString())
                when (kind) {
                    FeedKind.TRENDING -> addQueryParameter("orderBy", "BEST")
                    FeedKind.STAFF_PICKS -> {
                        addQueryParameter("orderBy", "BEST")
                        addQueryParameter("curated", "true")
                    }
                    FeedKind.RECENTLY_ADDED -> addQueryParameter("orderBy", "NEWEST")
                }
            }
            getAssets(url)
        }

    override suspend fun search(query: String, limit: Int): List<GalleryModel> =
        withContext(Dispatchers.IO) {
            val url = buildUrl("assets") {
                addQueryParameter("format", "GLTF2")
                addQueryParameter("keywords", query)
                addQueryParameter("pageSize", limit.toString())
            }
            getAssets(url)
        }

    override suspend fun download(
        model: GalleryModel,
        onProgress: ((Long, Long) -> Unit)?,
    ): File = withContext(Dispatchers.IO) {
        // Re-resolve the asset so the download plan (formats + resources) is
        // never stale — the feed/search response only carried display metadata.
        val detailUrl = buildUrl("assets/${model.id}") {}
        val asset = json.decodeFromString(IcosaAsset.serializer(), getBody(detailUrl))
        val format = asset.preferredFormat() ?: throw IOException("Icosa asset ${model.id} has no glTF format")
        val rootUrl = format.root?.url ?: throw IOException("Icosa format for ${model.id} has no root URL")

        if (rootUrl.substringBefore('?').endsWith(".glb", ignoreCase = true)) {
            // Self-contained GLB — single-file fetch. Sanitise the (untrusted,
            // server-supplied) id into a single safe path segment before it
            // becomes a cache filename — the multi-file branch already routes
            // through NetworkModelDownloader.sanitize (#2645 review).
            downloader.downloadSingle(id, "${NetworkModelDownloader.sanitize(model.id)}.glb", rootUrl, onProgress)
        } else {
            // Multi-file glTF — fetch the root plus its resources side by side.
            val rootRelative = format.root.relativePath?.takeIf { it.isNotBlank() }
                ?: rootUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "model.gltf" }
            val resources = format.resources.orEmpty().mapNotNull { res ->
                val rel = res.relativePath?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = res.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                rel to url
            }.toMap()
            downloader.downloadBundle(id, model.id, rootRelative, rootUrl, resources, onProgress)
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    @VisibleForTesting
    internal fun buildUrl(path: String, block: HttpUrl.Builder.() -> Unit): HttpUrl =
        baseUrl.toHttpUrl().newBuilder().addPathSegments(path).apply(block).build()

    private suspend fun getAssets(url: HttpUrl): List<GalleryModel> {
        val response = json.decodeFromString(IcosaListResponse.serializer(), getBody(url))
        return response.assets.mapNotNull { it.toGalleryModel() }
    }

    private suspend fun getBody(url: HttpUrl): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", SketchfabConfig.USER_AGENT)
            .get()
            .build()
        client.newCall(request).executeAsync().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Icosa request failed with HTTP ${response.code}")
            }
            return response.readBoundedBody()
        }
    }

    companion object {
        /** Icosa Gallery Data API v1 root. Always ends with a trailing slash. */
        const val DEFAULT_BASE_URL: String = "https://api.icosa.gallery/v1/"

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}

// ── Wire model (only the fields the demo needs) ─────────────────────────────

@Serializable
internal data class IcosaListResponse(
    val assets: List<IcosaAsset> = emptyList(),
)

@Serializable
internal data class IcosaAsset(
    val assetId: String? = null,
    val id: String? = null,
    val name: String? = null,
    val displayName: String? = null,
    val authorName: String? = null,
    val license: String? = null,
    val triangleCount: Int = 0,
    val tags: List<String> = emptyList(),
    val thumbnail: IcosaFile? = null,
    val formats: List<IcosaFormat> = emptyList(),
) {
    /** Resolve the id across the field names Icosa has used across API revisions. */
    fun resolvedId(): String? = assetId ?: id ?: name

    /** Prefer a self-contained GLB, then any GLTF2, then whatever is available. */
    fun preferredFormat(): IcosaFormat? =
        formats.firstOrNull { it.root?.url?.substringBefore('?')?.endsWith(".glb", ignoreCase = true) == true }
            ?: formats.firstOrNull { it.formatType.equals("GLTF2", ignoreCase = true) }
            ?: formats.firstOrNull { it.root?.url != null }

    fun toGalleryModel(): GalleryModel? {
        val resolvedId = resolvedId() ?: return null
        if (preferredFormat() == null) return null
        return GalleryModel(
            sourceId = ModelSourceId.ICOSA,
            id = resolvedId,
            name = displayName ?: name ?: "Untitled",
            thumbnails = thumbnail?.url?.let {
                listOf(GalleryThumbnail(it, thumbnail.width.takeIf { w -> w > 0 } ?: 1024, thumbnail.height.takeIf { h -> h > 0 } ?: 1024))
            } ?: emptyList(),
            attribution = GalleryAttribution(
                authorName = authorName,
                license = licenseDisplayName(license),
                sourceUrl = "https://icosa.gallery/view/$resolvedId",
            ),
            tags = tags,
            faceCount = triangleCount,
        )
    }
}

@Serializable
internal data class IcosaFormat(
    val formatType: String? = null,
    val root: IcosaFile? = null,
    val resources: List<IcosaFile>? = null,
)

@Serializable
internal data class IcosaFile(
    val url: String? = null,
    val relativePath: String? = null,
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * Map an Icosa / Poly-style license enum onto a short human-readable label.
 * Shared with [PolyHavenService] which uses the same CC vocabulary.
 */
internal fun licenseDisplayName(@Suppress("ReturnCount") raw: String?): String? = when (raw?.uppercase()) {
    null, "" -> null
    "CREATIVE_COMMONS_BY", "CC-BY", "CC_BY" -> "CC BY 4.0"
    "CREATIVE_COMMONS_BY_ND", "CC-BY-ND" -> "CC BY-ND 4.0"
    "CREATIVE_COMMONS_BY_SA", "CC-BY-SA" -> "CC BY-SA 4.0"
    "CREATIVE_COMMONS_0", "CC0", "CC_0" -> "CC0"
    "ALL_RIGHTS_RESERVED", "RESERVED" -> "All rights reserved"
    else -> raw.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

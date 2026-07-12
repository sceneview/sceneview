package io.github.sceneview.demo.sources

import android.content.Context
import io.github.sceneview.demo.sketchfab.SketchfabConfig
import io.github.sceneview.demo.sketchfab.SketchfabModel
import io.github.sceneview.demo.sketchfab.SketchfabService
import java.io.File

/**
 * [ModelSource] backed by the Sketchfab Data API v3 — the original catalog,
 * now one implementation among several (#2645).
 *
 * This adapter is deliberately thin: it maps [SketchfabModel] onto the
 * source-agnostic [GalleryModel] and delegates every network round-trip to the
 * existing [SketchfabService] (which owns the WAF-hardening, cancellation, and
 * transient-retry logic), so the multi-source refactor never touched the
 * proven Sketchfab client.
 */
class SketchfabSource(private val service: SketchfabService) : ModelSource {

    override val id: ModelSourceId = ModelSourceId.SKETCHFAB

    /** Sketchfab needs an API key; without one the chip is hidden from the picker. */
    override val isAvailable: Boolean get() = SketchfabConfig.apiKey != null

    override val feedKinds: List<FeedKind> =
        listOf(FeedKind.TRENDING, FeedKind.STAFF_PICKS, FeedKind.RECENTLY_ADDED)

    /** Only Sketchfab's feed endpoints accept the `animated` flag. */
    override val supportsAnimatedFilter: Boolean = true

    override suspend fun feed(kind: FeedKind, animatedOnly: Boolean, limit: Int): List<GalleryModel> {
        val animated: Boolean? = if (animatedOnly) true else null
        val models = when (kind) {
            FeedKind.TRENDING -> service.featured(animated = animated, limit = limit)
            FeedKind.STAFF_PICKS -> service.staffPicks(animated = animated, limit = limit)
            FeedKind.RECENTLY_ADDED -> service.recentlyAdded(animated = animated, limit = limit)
        }
        return models.map { it.toGalleryModel() }
    }

    override suspend fun search(query: String, limit: Int): List<GalleryModel> =
        service.search(query = query, limit = limit).map { it.toGalleryModel() }

    override suspend fun download(
        model: GalleryModel,
        onProgress: ((Long, Long) -> Unit)?,
    ): File = service.downloadModel(model.id, onProgress)

    companion object {
        /** Process-wide instance over the shared [SketchfabService] singleton. */
        fun getInstance(context: Context): SketchfabSource =
            SketchfabSource(SketchfabService.getInstance(context))
    }
}

/**
 * Map a Sketchfab wire model onto the source-agnostic [GalleryModel].
 *
 * Author + license are left `null`: the demo's [SketchfabModel] deliberately
 * does not decode Sketchfab's per-model `user` / `license` blocks (keeping it
 * in lock-step with the iOS scaffold), and the download flow re-resolves the
 * signed CDN URL from the uid, so no download reference is carried here.
 */
fun SketchfabModel.toGalleryModel(): GalleryModel = GalleryModel(
    sourceId = ModelSourceId.SKETCHFAB,
    id = uid,
    name = name,
    thumbnails = thumbnails.images.map { GalleryThumbnail(it.url, it.width, it.height) },
    attribution = GalleryAttribution(sourceUrl = viewerUrl),
    tags = tags?.map { it.name } ?: emptyList(),
    faceCount = faceCount,
    animationCount = animationCount,
    downloadable = downloadable,
)

package io.github.sceneview.demo.sources

import java.util.Locale

/**
 * The 3D-model catalogs the Explore tab can browse (#2645).
 *
 * Sketchfab (Epic Games / Fab) has a measurably degraded search backend and a
 * bounded contractual lifetime; Icosa Gallery and Poly Haven are verified-alive
 * free alternatives with real JSON APIs and no auth. The Explore tab is
 * source-agnostic: it renders [GalleryModel]s produced by whichever source the
 * user picks, and one degraded source never blanks the tab.
 *
 * @param slug        stable identifier persisted in SharedPreferences.
 * @param displayName label shown on the source-picker chip.
 */
enum class ModelSourceId(val slug: String, val displayName: String) {
    SKETCHFAB("sketchfab", "Sketchfab"),
    ICOSA("icosa", "Icosa Gallery"),
    POLY_HAVEN("polyhaven", "Poly Haven");

    companion object {
        /** Resolve a persisted [slug] back to its enum, or `null` when unknown. */
        fun fromSlug(slug: String?): ModelSourceId? = entries.firstOrNull { it.slug == slug }
    }
}

/** One thumbnail candidate at a specific pixel size. */
data class GalleryThumbnail(
    val url: String,
    val width: Int,
    val height: Int,
)

/**
 * Attribution + license metadata for a [GalleryModel].
 *
 * Every source in scope serves Creative-Commons / CC0 content, so surfacing the
 * author and license is both a courtesy and, for CC-BY assets, a requirement —
 * the abstraction carries it so the viewer can credit the creator regardless of
 * which catalog the model came from.
 *
 * @param authorName human-readable creator, or `null` when the API omits it.
 * @param license    short human-readable license, e.g. `"CC BY 4.0"` / `"CC0"`.
 * @param sourceUrl  the model's page on the origin catalog. Recorded for credit
 *   only — the demo never opens an external viewer (it always renders through
 *   SceneView), so this is metadata, not a navigation target.
 */
data class GalleryAttribution(
    val authorName: String? = null,
    val license: String? = null,
    val sourceUrl: String? = null,
)

/**
 * A source-agnostic model entry rendered by the Explore tab.
 *
 * This is the single shape the carousels, the search results, the card
 * ([io.github.sceneview.demo.ui.explore.components.FeaturedModelCard]) and the
 * viewer ([io.github.sceneview.demo.ui.explore.GalleryModelViewerScreen]) speak,
 * so adding a new [ModelSource] never touches the UI. Each source maps its own
 * wire format onto this type (see `SketchfabModel.toGalleryModel()`,
 * `IcosaGalleryService`, `PolyHavenService`).
 *
 * @param id source-specific identifier (Sketchfab uid, Icosa assetId, Poly
 *   Haven slug) — unique only *within* [sourceId]. Use [cardKey] for Compose
 *   list keys, which are unique across sources.
 * @param downloadable `false` when the origin catalog exposes the model but not
 *   a downloadable format the demo can render (e.g. a Sketchfab model outside
 *   the free tier). The viewer disables the "Open in SceneView" CTA.
 */
data class GalleryModel(
    val sourceId: ModelSourceId,
    val id: String,
    val name: String,
    val thumbnails: List<GalleryThumbnail> = emptyList(),
    val attribution: GalleryAttribution = GalleryAttribution(),
    val tags: List<String> = emptyList(),
    val faceCount: Int = 0,
    val animationCount: Int = 0,
    val downloadable: Boolean = true,
) {
    /** True when the model carries one or more skeletal animations. */
    val isAnimated: Boolean get() = animationCount > 0

    /**
     * Stable, cross-source-unique key for Compose `LazyRow(items, key = …)`.
     *
     * A raw [id] is only unique within one source, and the same model can even
     * appear in two feeds of the same source — the `sourceId` prefix guarantees
     * no key collision when the picker switches catalogs or a model surfaces in
     * both "Trending" and "Recently added".
     */
    val cardKey: String get() = "${sourceId.slug}:$id"
}

/**
 * Pick a thumbnail close to the card's render size, falling back to the largest
 * available (then the first) — mirrors the former Sketchfab-only helper so the
 * card/viewer downscale a reasonable image instead of a 2k original.
 */
fun GalleryModel.preferredThumbnailUrl(minWidth: Int = 320, maxWidth: Int = 640): String? {
    val sweetSpot = thumbnails.firstOrNull { it.width in minWidth..maxWidth }
    return (sweetSpot ?: thumbnails.maxByOrNull { it.width } ?: thumbnails.firstOrNull())?.url
}

/** First tag in Title Case, or a generic fallback. */
fun GalleryModel.primaryTagDisplay(): String =
    tags.firstOrNull()?.replaceFirstChar { it.titlecase() } ?: "3D Model"

/** Compact human-readable face count: `1.2k`, `3.4M`, or the raw number. */
fun GalleryModel.formattedFaceCount(): String = when {
    faceCount >= 1_000_000 -> String.format(Locale.US, "%.1fM", faceCount / 1_000_000.0)
    faceCount >= 1_000 -> String.format(Locale.US, "%.1fk", faceCount / 1_000.0)
    else -> faceCount.toString()
}

/** `"by Ada · CC BY 4.0 · via Icosa Gallery"`, omitting the parts a source can't fill. */
fun GalleryModel.attributionLine(): String {
    val parts = buildList {
        attribution.authorName?.takeIf { it.isNotBlank() }?.let { add("by $it") }
        attribution.license?.takeIf { it.isNotBlank() }?.let { add(it) }
        add("via ${sourceId.displayName}")
    }
    return parts.joinToString(" · ")
}

package io.github.sceneview.demo.sketchfab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A Sketchfab model entry returned by `/v3/search` and `/v3/models`.
 *
 * Fields mirror the iOS scaffold (`SketchfabModels.swift`) so both demo apps
 * speak the same wire format — keep them in sync when adding properties.
 */
@Serializable
data class SketchfabModel(
    val uid: String,
    val name: String,
    val description: String? = null,
    val thumbnails: SketchfabThumbnails,
    @SerialName("viewerUrl") val viewerUrl: String,
    /**
     * Sketchfab returns this field as `isDownloadable` (camelCase, leading `is`) in
     * `/v3/models` and `/v3/search` responses. Without the explicit `@SerialName`
     * the value silently decodes to `false` — discovered while wiring the iOS demo.
     */
    @SerialName("isDownloadable") val downloadable: Boolean = false,
    val tags: List<SketchfabTag>? = null,
    /** Number of GPU triangles. Surfaces the "12k polys" badge on cards. */
    val faceCount: Int = 0,
    /** Number of skeletal animations. `> 0` means we show an "Animated" pill. */
    val animationCount: Int = 0,
    /** Likes on the Sketchfab page (mirrors `-likeCount` sort key). */
    val likeCount: Int = 0,
    /** Views on the Sketchfab page (mirrors `-viewCount` sort key). */
    val viewCount: Int = 0,
) {
    /** True when the Sketchfab model carries one or more skeletal animations. */
    val isAnimated: Boolean get() = animationCount > 0

    /**
     * The smallest thumbnail at least [minWidth] px wide, else the largest one, else `null`.
     * Sketchfab serves the same render at several sizes up to 1920 px; a picker tile needs
     * the small one, and decoding the original for an 80 dp tile is wasted memory.
     */
    fun thumbnailUrl(minWidth: Int): String? =
        (
            thumbnails.images.filter { it.width >= minWidth }.minByOrNull { it.width }
                ?: thumbnails.images.maxByOrNull { it.width }
            )?.url
}

/** Wrapper around the `images` array returned by Sketchfab for each model. */
@Serializable
data class SketchfabThumbnails(
    val images: List<SketchfabThumbnail>,
)

/** A single thumbnail at a specific resolution. */
@Serializable
data class SketchfabThumbnail(
    val url: String,
    val width: Int,
    val height: Int,
)

/**
 * A tag attached to a model. Sketchfab returns more fields (slug, uri, …) but
 * only [name] is needed for filtering/display in the demo app.
 */
@Serializable
data class SketchfabTag(
    val name: String,
)

/** Paginated search/list response. */
@Serializable
data class SketchfabSearchResponse(
    val results: List<SketchfabModel>,
    val next: String? = null,
    val previous: String? = null,
)

/**
 * Response of `GET /v3/models/{uid}/download`.
 *
 * Sketchfab returns up to three format entries (`gltf`, `glb`, `usdz`); all
 * are optional because availability depends on the model. The demo prefers
 * `glb` (single binary, no companion files) and falls back to `gltf`, then
 * `usdz` as a last resort (which SceneView can't load on Android — useful
 * only for cross-platform parity reporting).
 */
@Serializable
data class SketchfabDownloadResponse(
    val gltf: SketchfabDownloadUrl? = null,
    val glb: SketchfabDownloadUrl? = null,
    val usdz: SketchfabDownloadUrl? = null,
) {
    /** Best format for SceneView consumption. */
    val preferred: SketchfabDownloadUrl?
        get() = glb ?: gltf ?: usdz
}

/** A signed download URL with its size and expiration timestamp (epoch seconds). */
@Serializable
data class SketchfabDownloadUrl(
    val url: String,
    val size: Long,
    val expires: Long,
)

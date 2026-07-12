package io.github.sceneview.demo.sources

import java.io.File

/**
 * One curated feed of a [ModelSource], in browse-layout display order.
 *
 * Not every catalog exposes all three taxonomies — Poly Haven has no
 * editorial "staff picks", for instance — so each source declares the subset
 * it supports via [ModelSource.feedKinds]. The Explore tab renders exactly the
 * feeds the selected source advertises.
 */
enum class FeedKind {
    /** Most-popular / trending right now. */
    TRENDING,

    /** Editorially curated (Sketchfab Staff Picks, Icosa curated flag). */
    STAFF_PICKS,

    /** Most recently published. */
    RECENTLY_ADDED,
}

/**
 * A 3D-model catalog the Explore tab can browse, search, and stream from (#2645).
 *
 * Implementations wrap a network client and map the catalog's wire format onto
 * the source-agnostic [GalleryModel]. The tab never references a concrete
 * source: it iterates [feedKinds], calls [feed] / [search], renders the
 * resulting [GalleryModel]s, and streams the chosen one through [download].
 *
 * **Resilience contract.** A single degraded source must never blank the tab.
 * [feed] and [search] may throw (network blip, rate limit, decode error); the
 * caller catches per-feed so surviving feeds — and the always-present curated
 * samples + the source picker — keep the tab usable and let the user switch
 * catalogs.
 */
interface ModelSource {
    /** Which catalog this source represents (drives the picker chip + persistence). */
    val id: ModelSourceId

    /**
     * `true` when the source can be used in the current build. Sketchfab needs
     * an API key; the CC0 / CC-BY sources are always available. Unavailable
     * sources are hidden from the picker.
     */
    val isAvailable: Boolean

    /** Feeds this source exposes, in display order. */
    val feedKinds: List<FeedKind>

    /**
     * `true` when the "Animated" filter chip is meaningful for this source. Only
     * Sketchfab's feed endpoints accept an `animated` flag; the CC sources
     * ignore it, so the chip is hidden rather than presenting a dead toggle.
     */
    val supportsAnimatedFilter: Boolean get() = false

    /**
     * Load one curated [kind] feed.
     *
     * @param animatedOnly restrict to skeletal-rig models (honoured only when
     *   [supportsAnimatedFilter]).
     * @throws Exception on any network / decode failure — the caller degrades
     *   gracefully (empty feed self-hides).
     */
    suspend fun feed(kind: FeedKind, animatedOnly: Boolean = false, limit: Int = 10): List<GalleryModel>

    /** Free-text search over the catalog. Same failure contract as [feed]. */
    suspend fun search(query: String, limit: Int = 24): List<GalleryModel>

    /**
     * Stream [model]'s preferred format to the on-disk cache and return the
     * local file, ready to hand to `rememberModelInstance`.
     *
     * For multi-file glTF sources the returned `.gltf` sits alongside its
     * resources in the same directory, which SceneView's `ModelLoader` resolves
     * relative to the file. Cooperatively cancellable: cancelling the caller
     * aborts the in-flight download.
     *
     * @param onProgress optional `(bytesRead, totalBytes)` callback; `totalBytes`
     *   is `-1` when the server omits `Content-Length`.
     */
    suspend fun download(
        model: GalleryModel,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): File
}

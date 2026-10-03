package io.github.sceneview.demo.ui.home

/**
 * The demo ids each group above the catalogue draws, every demo at most once.
 *
 * The top of the Home has three places that push a demo: the header's featured pager, the
 * "Featured" banners and the "What's new" row's picture. Each was fed from its own list,
 * and the lists overlap by design — the flagship samples are featured *and* recently
 * updated — so the same demo led all three (the header page, a banner, the What's new
 * picture) before the catalogue had shown anything else.
 *
 * [pager] ids first, then [featured], then [whatsNew]: a group only keeps what no group
 * before it shows. A group left empty is not drawn at all — see `HomeScreen`.
 */
data class HomeTopSections(
    /** The header pager's pages, in page order. */
    val pager: List<String>,
    /** The "Featured" banners left once the pager's demos are taken out. */
    val featured: List<String>,
    /** The fresh demos neither the pager nor the banners show — the What's new row leads with one. */
    val whatsNew: List<String>,
)

/**
 * Cuts the three lists into [HomeTopSections], in priority order. Pure, so the rule is
 * tested on the JVM with plain ids — it is a rule about the order, not about one demo.
 *
 * Each input keeps its own order; an id repeated inside one list is kept once.
 */
fun homeTopSections(
    pager: List<String>,
    featured: List<String>,
    fresh: List<String>,
): HomeTopSections {
    val shown = mutableSetOf<String>()
    // `add` is false for an id an earlier group — or an earlier row of this one — took.
    fun List<String>.notShownYet(): List<String> = filter { shown.add(it) }
    return HomeTopSections(
        pager = pager.notShownYet(),
        featured = featured.notShownYet(),
        whatsNew = fresh.notShownYet(),
    )
}

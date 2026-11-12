package io.github.sceneview.demo.ui.home

import androidx.annotation.StringRes
import io.github.sceneview.demo.R

/** A one-tap discovery action, resolved against the same registry as the catalogue. */
internal data class HomeOutcome(
    val demoId: String,
    @StringRes val labelRes: Int,
    @StringRes val supportingRes: Int,
)

internal val HOME_OUTCOMES = listOf(
    HomeOutcome(HERO_DEMO_ID, R.string.home_view_model, R.string.home_view_model_supporting),
    HomeOutcome("ar-placement", R.string.home_place_in_room, R.string.home_place_in_room_supporting),
    HomeOutcome("ar-rerun", R.string.home_explore_scan, R.string.home_explore_scan_supporting),
)

/** Try next shortcuts. Feel joins here when its demo is registered. */
internal val FEATURED_IDS = listOf("materials", "cosmos", "rolling-balls")

/** Discovery shortcuts and release highlights; none owns a demo's catalogue membership. */
data class HomeTopSections(
    val outcomes: List<String>,
    val featured: List<String>,
    val whatsNew: List<String>,
) {
    /** Every match keeps its permanent category home, including featured demos. */
    fun catalogue(ids: List<String>): List<String> = ids
}

/** Keep each discovery shortcut once, preserving priority and input order. */
fun homeTopSections(
    outcomes: List<String>,
    featured: List<String>,
    fresh: List<String>,
): HomeTopSections {
    val shown = mutableSetOf<String>()
    fun List<String>.notShownYet(): List<String> = filter { shown.add(it) }
    return HomeTopSections(
        outcomes = outcomes.notShownYet(),
        featured = featured.notShownYet(),
        whatsNew = fresh.notShownYet(),
    )
}

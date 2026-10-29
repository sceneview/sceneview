package io.github.sceneview.demo.ui.home

/**
 * One searchable row of the home grid — a [io.github.sceneview.demo.DemoEntry]
 * with its string resources already resolved, so [filterDemos] stays pure and
 * unit-testable on the JVM with no resources, no Compose and no Robolectric.
 *
 * Build one per demo at the call site with `stringResource(...)`; see
 * `HomeScreen.kt`.
 */
data class HomeSearchEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    /** Stable category key (`DemoCategory.*`). */
    val category: String,
    /** The category's display label, so "lighting" finds the Lighting demos. */
    val categoryLabel: String,
    val tags: Set<String>,
    val order: Int,
)

/**
 * Pure filter behind the home screen's search field.
 *
 * - [query] is trimmed and split on whitespace; every word must match
 *   (case-insensitively) somewhere in title, subtitle, category label or tags.
 *   A blank query matches everything.
 * - The result keeps the input's editorial [HomeSearchEntry.order].
 *
 * The category chips do not filter: they scroll to their section
 * ([homeSectionStarts]), so nothing but a query ever cuts the catalogue down.
 */
fun filterDemos(
    entries: List<HomeSearchEntry>,
    query: String,
): List<HomeSearchEntry> {
    val words = query.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
    return entries
        .filter { entry -> words.all { word -> entry.matches(word) } }
        .sortedBy { it.order }
}

private val WHITESPACE = Regex("\\s+")

private fun HomeSearchEntry.matches(word: String): Boolean =
    title.lowercase().contains(word) ||
        subtitle.lowercase().contains(word) ||
        categoryLabel.lowercase().contains(word) ||
        category.lowercase().contains(word) ||
        tags.any { it.lowercase().contains(word) }

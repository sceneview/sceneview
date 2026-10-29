package io.github.sceneview.demo.ui.home

/**
 * Where each catalogue section starts in the home grid, for the section chips.
 *
 * The chips used to filter the catalogue down to one category. Measured on the 43 demos of
 * 4.x: five sections, the largest 17 rows long, and the filter hid everything else — the
 * "this app only has these samples" reading #4304 had already fought — while search and the
 * "What's new" row cover the other ways in. A chip now scrolls to its section: the whole
 * catalogue stays on the page and a section is one tap away, which is what the chips were
 * reached for.
 *
 * Pure so it is unit-tested on the JVM (`HomeSectionJumpTest`): given how many grid items
 * come before the catalogue ([headItemCount]) and the category of every catalogue row in
 * grid order ([rowCategories]), returns the grid index of the item that opens each
 * category — its section header when [showSections], else its first row. A category with
 * no row is absent from the map.
 */
internal fun homeSectionStarts(
    headItemCount: Int,
    rowCategories: List<String>,
    showSections: Boolean,
): Map<String, Int> {
    val starts = LinkedHashMap<String, Int>()
    var index = headItemCount
    var previous: String? = null
    rowCategories.forEach { category ->
        if (category != previous) {
            starts.putIfAbsent(category, index)
            if (showSections) index++
            previous = category
        }
        index++
    }
    return starts
}

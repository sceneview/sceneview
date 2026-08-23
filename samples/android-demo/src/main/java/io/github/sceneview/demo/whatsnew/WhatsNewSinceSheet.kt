@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
)

package io.github.sceneview.demo.whatsnew

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewDemoTheme

/**
 * The "What's new since you last tested" sheet.
 *
 * **Scrolling list, not a page.** The content is cumulative — skipping three
 * releases shows all three — so its length is unbounded by design. It is a
 * [LazyColumn] with a sticky version header per section, which keeps "which
 * release am I reading" answered while scrolling through a long backlog, and a
 * bottom action bar that stays pinned so the acknowledge button is reachable
 * without scrolling to the end.
 *
 * **Dismissing is not acknowledging.** [onDismiss] leaves the marker alone;
 * only [onMarkSeen] writes it. That asymmetry is the whole feature: this list
 * is a test plan, and losing it to a stray swipe would make it untrustworthy.
 *
 * All colours come from `MaterialTheme.colorScheme`, so both schemes follow the
 * theme (DESIGN.md: never hardcode a colour).
 */
@Composable
fun WhatsNewSinceSheet(
    sections: List<WhatsNewSection>,
    seenVersion: String?,
    onDemoClick: (String) -> Unit,
    onMarkSeen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entryCount = remember(sections) { sections.sumOf { it.entries.size } }
    val demoTitles = rememberDemoTitles()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.whats_new_since_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (seenVersion != null) {
                        pluralStringResource(
                            R.plurals.whats_new_since_subtitle,
                            entryCount,
                            entryCount,
                            seenVersion,
                        )
                    } else {
                        stringResource(R.string.whats_new_since_subtitle_unknown)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(
                // `fill = false` so a one-entry list keeps the sheet short
                // instead of stretching it to full height.
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                sections.forEach { section ->
                    stickyHeader(key = "header-${section.version ?: "unreleased"}") {
                        SectionHeader(section)
                    }
                    section.entries.groupBy { it.category }.forEach { (category, entries) ->
                        item(key = "cat-${section.version}-$category") {
                            CategoryLabel(category)
                        }
                        items(entries, key = { "entry-${it.id}" }) { entry ->
                            EntryRow(
                                entry = entry,
                                category = category,
                                onDemoClick = onDemoClick,
                                demoTitles = demoTitles,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(modifier = Modifier.padding(20.dp)) {
                Button(
                    onClick = onMarkSeen,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.whats_new_since_mark_seen),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = stringResource(R.string.whats_new_since_mark_seen_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                )
            }
        }
    }
}

/** Sticky per-version heading. Opaque — it scrolls over list content. */
@Composable
private fun SectionHeader(section: WhatsNewSection) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = section.version
                    ?.let { stringResource(R.string.whats_new_release_version, it) }
                    ?: stringResource(R.string.whats_new_since_unreleased),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            section.date?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CategoryLabel(category: WhatsNewCategory) {
    Text(
        text = stringResource(categoryLabel(category)),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = categoryAccentColor(category),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun EntryRow(
    entry: WhatsNewEntry,
    category: WhatsNewCategory,
    onDemoClick: (String) -> Unit,
    demoTitles: List<Pair<DemoEntry, String>> = emptyList(),
) {
    val accent = categoryAccentColor(category)
    val demos = remember(entry.id, demoTitles) { mentionedDemos(entry, demoTitles) }
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .padding(top = 7.dp)
                    .size(6.dp)
                    .background(color = accent, shape = CircleShape),
            )
            Text(
                text = rememberMarkdownLite(entry.text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 20.sp,
                modifier = Modifier.weight(1f),
            )
        }
        if (demos.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                demos.forEach { demo ->
                    DemoChip(demo = demo, onClick = { onDemoClick(demo.id) })
                }
            }
        }
    }
}

/** Tap-through to a demo the entry names. See [rememberMentionedDemos]. */
@Composable
private fun DemoChip(demo: DemoEntry, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = demo.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.whats_new_since_open_demo, stringResource(demo.titleRes)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Demos this entry names, by exact title match against the registry.
 *
 * Deliberately literal: a changelog entry that writes out "Point Cloud" gets a
 * chip, anything vaguer gets none. Fuzzy matching here would produce chips that
 * open the wrong demo, which is worse than no chip at all — and titles shorter
 * than [MIN_TITLE_MATCH] characters are skipped for the same reason, since a
 * short generic title would match half the changelog.
 *
 * Pure, and fed a title list resolved once per sheet: resolving ~40 string
 * resources per row would otherwise run on every row of a scrolling list.
 */
private fun mentionedDemos(
    entry: WhatsNewEntry,
    titles: List<Pair<DemoEntry, String>>,
): List<DemoEntry> {
    val haystack = entry.plainText.lowercase()
    return titles
        .filter { (_, title) -> title.length >= MIN_TITLE_MATCH && haystack.contains(title.lowercase()) }
        .map { it.first }
        .take(MAX_CHIPS)
}

/** Registry titles resolved once, for [mentionedDemos]. */
@Composable
private fun rememberDemoTitles(): List<Pair<DemoEntry, String>> =
    ALL_DEMOS.map { it to stringResource(it.titleRes) }

private const val MIN_TITLE_MATCH = 6
private const val MAX_CHIPS = 2

/**
 * Renders the only inline markdown a changelog headline actually varies on:
 * `` `code` `` spans. Bold is already resolved by the parser (the whole
 * headline is bold by convention), and nothing else appears often enough in a
 * headline to be worth a markdown engine in a demo app.
 */
@Composable
private fun rememberMarkdownLite(text: String): AnnotatedString {
    val codeColor = MaterialTheme.colorScheme.primary
    return remember(text, codeColor) {
        buildAnnotatedString {
            var index = 0
            while (index < text.length) {
                val open = text.indexOf('`', index)
                if (open < 0) {
                    append(text.substring(index))
                    break
                }
                val close = text.indexOf('`', open + 1)
                if (close < 0) {
                    append(text.substring(index))
                    break
                }
                append(text.substring(index, open))
                withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor, fontSize = 13.sp)
                ) {
                    append(text.substring(open + 1, close))
                }
                index = close + 1
            }
        }
    }
}

@Composable
private fun categoryAccentColor(category: WhatsNewCategory) = when (category) {
    WhatsNewCategory.Added -> MaterialTheme.colorScheme.primary
    WhatsNewCategory.Fixed -> MaterialTheme.colorScheme.tertiary
    WhatsNewCategory.Changed -> MaterialTheme.colorScheme.secondary
    WhatsNewCategory.Performance -> MaterialTheme.colorScheme.secondary
    WhatsNewCategory.Removed -> MaterialTheme.colorScheme.error
}

private fun categoryLabel(category: WhatsNewCategory) = when (category) {
    WhatsNewCategory.Added -> R.string.whats_new_category_added
    WhatsNewCategory.Fixed -> R.string.whats_new_category_fixed
    WhatsNewCategory.Changed -> R.string.whats_new_category_changed
    WhatsNewCategory.Performance -> R.string.whats_new_category_performance
    WhatsNewCategory.Removed -> R.string.whats_new_category_removed
}

// ── Previews ─────────────────────────────────────────────────────────────

private val previewSections = listOf(
    WhatsNewSection(
        version = null,
        date = null,
        title = null,
        entries = listOf(
            WhatsNewEntry("a", WhatsNewCategory.Fixed, "Point & Ask describes whatever you tap", 3187),
            WhatsNewEntry("b", WhatsNewCategory.Added, "`SplatNode` lands on Android", null),
        ),
    ),
    WhatsNewSection(
        version = "4.31.0",
        date = "2026-08-17",
        title = "The demo apps stop overlapping themselves",
        entries = listOf(
            WhatsNewEntry("c", WhatsNewCategory.Fixed, "A resized node picks at the size it renders at", 3194),
        ),
    ),
)

@Preview(showBackground = true, name = "WhatsNewSince - Light")
@Composable
private fun WhatsNewSincePreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface {
            Column {
                SectionHeader(previewSections[0])
                CategoryLabel(WhatsNewCategory.Fixed)
                EntryRow(previewSections[0].entries[0], WhatsNewCategory.Fixed, {})
                CategoryLabel(WhatsNewCategory.Added)
                EntryRow(previewSections[0].entries[1], WhatsNewCategory.Added, {})
                SectionHeader(previewSections[1])
            }
        }
    }
}

@Preview(
    showBackground = true,
    name = "WhatsNewSince - Dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun WhatsNewSinceDarkPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface {
            Column {
                SectionHeader(previewSections[0])
                CategoryLabel(WhatsNewCategory.Fixed)
                EntryRow(previewSections[0].entries[0], WhatsNewCategory.Fixed, {})
                SectionHeader(previewSections[1])
            }
        }
    }
}

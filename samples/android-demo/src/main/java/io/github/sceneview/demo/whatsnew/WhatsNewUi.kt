@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.whatsnew

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.IN_REVIEW_BADGE_VISIBLE
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.sample.ui.demoCategoryAccent

/**
 * The "What's new" surface — a card at the head of the Samples grid plus the
 * modal sheet it opens. Everything rendered here is *derived*: releases come
 * from the bundled `CHANGELOG.md` (see [loadWhatsNew]) and the "try these"
 * demos are whatever the registry currently flags [io.github.sceneview.demo.DemoStatus.InReview].
 * Nothing on this screen is hand-maintained.
 *
 * Layout contract: the card is an ordinary item *inside* the Samples
 * `LazyVerticalGrid` — a sibling of the demo cards that scrolls away with
 * them. Nothing here floats over the list (that class of bug — a chip masking
 * grid content at rest — is what #2194/#2358 spent two rounds fixing). The
 * detail view is a [ModalBottomSheet], the same pattern as `CreditsSheet`.
 *
 * All colors are `MaterialTheme.colorScheme` tokens, so light/dark both come
 * from the theme (DESIGN.md: never hardcode colors).
 */
@Composable
fun WhatsNewCard(
    latest: WhatsNewRelease,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        // Same hairline the demo cards carry — in dark mode `surfaceContainer`
        // bleeds into the particle backdrop without it (#1443).
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.whats_new_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = latest.title
                        ?: stringResource(R.string.whats_new_card_subtitle_fallback),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    lineHeight = 16.sp,
                )
            }
            VersionPill(version = latest.version)
        }
    }
}

@Composable
private fun VersionPill(version: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = stringResource(R.string.whats_new_release_version, version),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * Modal sheet listing the latest releases' highlights, newest first, plus a
 * tappable "new in this build" row for every demo the registry flags as
 * [io.github.sceneview.demo.DemoStatus.InReview].
 */
@Composable
fun WhatsNewSheet(
    releases: List<WhatsNewRelease>,
    inReviewDemos: List<DemoEntry>,
    onDemoClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.whats_new_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.whats_new_sheet_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (inReviewDemos.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.whats_new_in_review_header),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                inReviewDemos.forEach { demo ->
                    InReviewDemoRow(demo = demo, onClick = { onDemoClick(demo.id) })
                }
            }

            releases.forEach { release ->
                ReleaseSection(release = release)
            }
        }
    }
}

@Composable
private fun InReviewDemoRow(demo: DemoEntry, onClick: () -> Unit) {
    val accent = demoCategoryAccent(demo.category)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = demo.icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(demo.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(demo.subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // Debug-only: the row itself is a user-facing "try this" entry, the
            // chip on it is internal review vocabulary. See IN_REVIEW_BADGE_VISIBLE.
            if (IN_REVIEW_BADGE_VISIBLE) {
                Text(
                    text = stringResource(R.string.samples_chip_in_review),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReleaseSection(release: WhatsNewRelease) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.whats_new_release_version, release.version),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            release.date?.let { date ->
                Text(
                    text = date,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        release.title?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        WhatsNewCategory.entries.forEach { category ->
            val group = release.highlights.filter { it.category == category }
            if (group.isNotEmpty()) {
                CategoryGroup(category = category, highlights = group)
            }
        }
    }
}

@Composable
private fun CategoryGroup(category: WhatsNewCategory, highlights: List<WhatsNewHighlight>) {
    val accent = categoryAccent(category)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(categoryLabelRes(category)),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            modifier = Modifier.padding(top = 4.dp),
        )
        highlights.forEach { highlight ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(6.dp)
                        .background(color = accent, shape = CircleShape),
                )
                Text(
                    text = highlight.headline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 18.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun categoryAccent(category: WhatsNewCategory) = when (category) {
    WhatsNewCategory.Added -> MaterialTheme.colorScheme.primary
    WhatsNewCategory.Fixed -> MaterialTheme.colorScheme.tertiary
    WhatsNewCategory.Changed -> MaterialTheme.colorScheme.secondary
    WhatsNewCategory.Performance -> MaterialTheme.colorScheme.secondary
    WhatsNewCategory.Removed -> MaterialTheme.colorScheme.error
}

private fun categoryLabelRes(category: WhatsNewCategory) = when (category) {
    WhatsNewCategory.Added -> R.string.whats_new_category_added
    WhatsNewCategory.Fixed -> R.string.whats_new_category_fixed
    WhatsNewCategory.Changed -> R.string.whats_new_category_changed
    WhatsNewCategory.Performance -> R.string.whats_new_category_performance
    WhatsNewCategory.Removed -> R.string.whats_new_category_removed
}

// ── Previews — fixed sample data, light and dark ─────────────────────────

private val previewRelease = WhatsNewRelease(
    version = "4.30.0",
    date = "2026-08-12",
    title = "Cached Filament handles that stop lying, and AR depth occlusion that occludes",
    highlights = listOf(
        WhatsNewHighlight(WhatsNewCategory.Added, "Engine.destroyLight / Engine.safeDestroyLight"),
        WhatsNewHighlight(WhatsNewCategory.Fixed, "Depth occlusion had no visible effect at all", 1617),
        WhatsNewHighlight(WhatsNewCategory.Fixed, "A resized node now picks at the size it renders at", 3194),
    ),
)

@Preview(showBackground = true, name = "WhatsNewCard - Light")
@Composable
private fun WhatsNewCardPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { WhatsNewCard(latest = previewRelease, onClick = {}) }
    }
}

@Preview(
    showBackground = true,
    name = "WhatsNewCard - Dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun WhatsNewCardDarkPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { WhatsNewCard(latest = previewRelease, onClick = {}) }
    }
}

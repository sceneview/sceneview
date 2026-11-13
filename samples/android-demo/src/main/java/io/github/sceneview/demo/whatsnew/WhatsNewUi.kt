@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sceneview.demo.ui.ExpandableColumn
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.IN_REVIEW_BADGE_VISIBLE
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoModalBottomSheet
import io.github.sceneview.sample.ui.demoCategoryAccent

/** Release notes, reached from the single entry in About. */
@Composable
fun WhatsNewSheet(
    releases: List<WhatsNewRelease>,
    inReviewDemos: List<DemoEntry>,
    onDemoClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    DemoModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                // #3716: the container now reaches the true bottom edge, so the
                // content itself must clear the navigation bar in both nav modes.
                .navigationBarsPadding(),
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
                    // #3800: the full string never fit one line next to the "In
                    // review" badge and was hard-clipped mid-sentence. Two lines
                    // covers every current subtitle; an ellipsis is only a
                    // safety net if a future one is longer still.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp,
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
                CategoryGroup(
                    category = category,
                    highlights = group,
                    stateKey = "${release.version}-$category",
                )
            }
        }
    }
}

@Composable
private fun CategoryGroup(
    category: WhatsNewCategory,
    highlights: List<WhatsNewHighlight>,
    stateKey: String,
) {
    val accent = categoryAccent(category)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(categoryLabelRes(category)),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            modifier = Modifier.padding(top = 4.dp),
        )
        // A release can list a dozen fixes: the first few say what kind of release it
        // was, the rest unfold on demand, so the sheet reads as several releases rather
        // than one long changelog.
        ExpandableColumn(
            items = highlights,
            collapsedCount = WHATS_NEW_COLLAPSED_HIGHLIGHTS,
            stateKey = stateKey,
            spacing = 6.dp,
        ) { highlight ->
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

/** Highlights a category shows before its "Show more". */
private const val WHATS_NEW_COLLAPSED_HIGHLIGHTS = 3

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

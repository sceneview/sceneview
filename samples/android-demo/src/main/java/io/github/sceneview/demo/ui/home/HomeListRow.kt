package io.github.sceneview.demo.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoFreshness
import io.github.sceneview.demo.DemoPreviews
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens

/** The 4.68 picture-led row, with a 72 dp thumbnail and no dissolve or image/caption overlap. */
@Composable
fun DemoListRow(
    demo: DemoEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") style: HomeRowStyle = HomeRowStyle.Fused,
    freshness: DemoFreshness = DemoFreshness.None,
    testTag: String = HomeTestTags.row(demo.id),
) {
    val home = SceneViewTokens.Home
    HomeCaptionRow(
        title = stringResource(demo.titleRes),
        subtitle = stringResource(demo.subtitleRes),
        onClick = onClick,
        modifier = modifier.testTag(testTag),
        leading = {
            Image(
                painterResource(homeDemoImage(demo)),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = FEATURED_MEDIA_ALIGNMENT[demo.id] ?: Alignment.Center,
                modifier = Modifier.size(home.thumbnailSize).clip(RoundedCornerShape(home.cardRadius)),
            )
        },
        badges = {
            if (freshness != DemoFreshness.None) {
                FreshnessChip(freshness, MaterialTheme.colorScheme.primary)
            }
            if (demo.status != DemoStatus.Working) StatusChip(demo.status)
        },
    )
}

/** Prefer this demo's capture; uncaptured demos temporarily use media from their category. */
private fun homeDemoImage(demo: DemoEntry): Int = when (demo.id) {
    HERO_DEMO_ID, "ar-placement", "ar-rerun" -> homeOutcomeImage(demo.id)
    else -> DemoPreviews.resourceFor(demo.id, dark = true) ?: when (demo.category) {
        DemoCategory.CREATE -> R.drawable.preview_materials_dark
        DemoCategory.PLAY -> R.drawable.preview_rolling_balls_dark
        DemoCategory.PLACE_AR -> R.drawable.preview_wall_placement_dark
        DemoCategory.UNDERSTAND -> R.drawable.preview_ar_rerun_dark
        DemoCategory.DEV_TOOLS -> R.drawable.preview_debug_overlay_dark
        else -> R.drawable.home_hero_still
    }
}

@Composable
fun BrowseOnlineRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    UtilityRow(
        title = stringResource(R.string.home_browse_title),
        subtitle = stringResource(R.string.home_browse_subtitle),
        icon = Icons.Filled.Language,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
fun WhatsNewRow(
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadDemoId: String? = null,
) {
    val resource = leadDemoId?.let { DemoPreviews.resourceFor(it, dark = true) }
    HomeCaptionRow(
        title = stringResource(R.string.home_whats_new_title),
        subtitle = subtitle,
        onClick = onClick,
        modifier = modifier,
        leading = {
            if (resource == null) Icon(Icons.Filled.AutoAwesome, contentDescription = null)
            else Image(
                painterResource(resource), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(SceneViewTokens.Home.thumbnailSize)
                    .clip(RoundedCornerShape(SceneViewTokens.Home.cardRadius)),
            )
        },
    )
}

@Composable
private fun UtilityRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    HomeCaptionRow(title, subtitle, onClick, modifier, leading = {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    })
}

@Composable
private fun HomeCaptionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier,
    leading: @Composable () -> Unit,
    badges: @Composable RowScope.() -> Unit = {},
) {
    val home = SceneViewTokens.Home
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(home.rowRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(home.cardOutlineWidth, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Row(
            modifier = Modifier.padding(SceneViewTokens.Space.sm),
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(home.rowTextGap)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm), content = badges)
            }
        }
    }
}

/** Width arithmetic also used by catalogue previews outside the capped Home sheet. */
internal fun homeListColumns(widthDp: Int): Int {
    val home = SceneViewTokens.Home
    val available = widthDp - 2 * home.contentPadding.value
    return ((available + home.rowGap.value) / (home.rowMinWidth.value + home.rowGap.value)).toInt().coerceAtLeast(1)
}

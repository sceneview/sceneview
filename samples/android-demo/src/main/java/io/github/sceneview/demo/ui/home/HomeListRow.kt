package io.github.sceneview.demo.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoFreshness
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.previewPainter
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.sample.ui.DemoCategoryAccent

/**
 * One demo on the home list (`home-row` in `DESIGN.md`) — the standard two-line list item
 * of a Material 3 app, not a showcase card.
 *
 * The 3D header above is the only showpiece on the screen; everything under it reads like
 * the settings or the library of any well-made app, which is the point the Home makes: the
 * scene drops into an ordinary app. Anatomy: a `home-row-media` picture of the demo's own
 * capture, 5:4 like the capture itself so the generated scene reads instead of a 56 dp crop
 * (the icon tile while none exists), title in `type-body` semibold, subtitle in
 * `type-caption` regular, the freshness / status chips on the title line so the subtitle
 * keeps the row's full width. The row sits on the `home-row-bg` tile and takes its corners
 * from its place in the group ([rowShape]), so a section reads as one grey block split by
 * `home-row-gap` hairlines of page.
 */
@Composable
fun DemoListRow(
    demo: DemoEntry,
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    freshness: DemoFreshness = DemoFreshness.None,
) {
    val dark = isSystemInDarkTheme()
    val accent = DemoCategoryAccent[demo.category, dark]
    val preview = demo.previewPainter()
    val thumb: Modifier = Modifier.media()
    ListRow(
        title = stringResource(demo.titleRes),
        subtitle = stringResource(demo.subtitleRes),
        shape = shape,
        onClick = onClick,
        modifier = modifier.testTag(HomeTestTags.row(demo.id)),
        leading = {
            if (preview != null) {
                Image(
                    painter = preview,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = FEATURED_MEDIA_ALIGNMENT[demo.id] ?: Alignment.Center,
                    modifier = thumb,
                )
            } else {
                GlyphThumb(icon = demo.icon, tint = accent, modifier = thumb)
            }
        },
        badges = {
            if (freshness != DemoFreshness.None) FreshnessChip(freshness = freshness, accent = accent)
            if (demo.status != DemoStatus.Working) StatusChip(status = demo.status)
        },
    )
}

/**
 * The row that opens the online model gallery (`ExploreTabScreen`), drawn as one more list
 * item — a globe on the thumb square — so it sits in the list's rhythm instead of being a
 * banner of its own.
 */
@Composable
fun BrowseOnlineRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListRow(
        title = stringResource(R.string.home_browse_title),
        subtitle = stringResource(R.string.home_browse_subtitle),
        shape = RoundedCornerShape(SceneViewTokens.Home.rowRadiusOuter),
        onClick = onClick,
        modifier = modifier,
        leading = { GlyphThumb(icon = Icons.Filled.Language, tint = MaterialTheme.colorScheme.primary) },
    )
}

@Composable
private fun ListRow(
    title: String,
    subtitle: String,
    shape: Shape,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Chips drawn after the title, on its line. */
    badges: @Composable RowScope.() -> Unit = {},
) {
    val home = SceneViewTokens.Home
    Surface(
        onClick = onClick,
        shape = shape,
        color = homeRowBackground(),
        modifier = modifier
            .fillMaxWidth()
            // Title, subtitle and chips announce as one node.
            .semantics(mergeDescendants = true) {},
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = home.rowMinHeight)
                .padding(horizontal = home.rowPaddingHorizontal, vertical = home.rowPaddingVertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(home.rowPaddingHorizontal),
        ) {
            leading()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(home.rowTextGap),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                ) {
                    Text(
                        text = title,
                        style = SceneViewTokens.Type.body,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    badges()
                }
                Text(
                    text = subtitle,
                    style = SceneViewTokens.Type.caption,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The thumb square: `home-row-thumb`, `radius-sm`. */
private fun Modifier.thumb(): Modifier =
    size(SceneViewTokens.Home.rowThumb).clip(RoundedCornerShape(SceneViewTokens.Radius.sm))

/** The leading picture of a demo row: `home-row-media`, 5:4 like the captures, `radius-sm`. */
private fun Modifier.media(): Modifier = this
    .width(SceneViewTokens.Home.rowMediaWidth)
    .aspectRatio(SceneViewTokens.Home.rowMediaAspect)
    .clip(RoundedCornerShape(SceneViewTokens.Radius.sm))

/** A glyph on the thumb square, one step up the surface ramp from the row. */
@Composable
private fun GlyphThumb(icon: ImageVector, tint: Color, modifier: Modifier = Modifier.thumb()) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(SceneViewTokens.Home.rowThumbGlyph),
        )
    }
}

/** `home-row-bg`: `surface-container-high` — the neutral grey tile of a list row. */
@Composable
internal fun homeRowBackground(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

/**
 * Which corners of a row are its group's outer corners, for the row at [index] of a group
 * of [count] rows laid out [columns] across. A group is one block: its four outer corners
 * take `home-row-radius-outer`, every corner shared with a neighbour takes
 * `home-row-radius-inner`. With a short last line the block ends in a step, and the corner
 * over the step is outer too.
 */
internal data class RowCorners(
    val topStart: Boolean,
    val topEnd: Boolean,
    val bottomEnd: Boolean,
    val bottomStart: Boolean,
)

internal fun rowCorners(index: Int, count: Int, columns: Int): RowCorners {
    val cols = columns.coerceAtLeast(1)
    val row = index / cols
    val col = index % cols
    val lastRow = (count - 1) / cols
    val lastInLine = col == cols - 1 || index == count - 1
    val nothingBelow = index + cols > count - 1
    return RowCorners(
        topStart = row == 0 && col == 0,
        topEnd = row == 0 && lastInLine,
        bottomEnd = nothingBelow && lastInLine,
        bottomStart = row == lastRow && col == 0,
    )
}

/** The shape [rowCorners] describes. */
internal fun rowShape(index: Int, count: Int, columns: Int): Shape {
    val home = SceneViewTokens.Home
    val corners = rowCorners(index, count, columns)
    fun radius(outer: Boolean): Dp = if (outer) home.rowRadiusOuter else home.rowRadiusInner
    return RoundedCornerShape(
        topStart = radius(corners.topStart),
        topEnd = radius(corners.topEnd),
        bottomEnd = radius(corners.bottomEnd),
        bottomStart = radius(corners.bottomStart),
    )
}

/**
 * Columns of the home list for a window [widthDp] wide: one on a phone, then as many
 * `home-row-min-width` columns as fit between the side insets.
 */
internal fun homeListColumns(widthDp: Int): Int {
    val home = SceneViewTokens.Home
    val available = widthDp - 2 * home.contentPadding.value
    val columns = ((available + home.rowGap.value) / (home.rowMinWidth.value + home.rowGap.value)).toInt()
    return columns.coerceAtLeast(1)
}

package io.github.sceneview.demo.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoFreshness
import io.github.sceneview.demo.DemoPreviews
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.sample.ui.DemoCategoryAccent
import kotlin.math.roundToInt

/** Home catalogue media and captions on flat Material surfaces with hard picture edges. */
@Composable
fun DemoListRow(
    demo: DemoEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: HomeRowStyle = HomeRowStyle.Fused,
    freshness: DemoFreshness = DemoFreshness.None,
) {
    val dark = isSystemInDarkTheme()
    val accent = DemoCategoryAccent[demo.category, dark]
    val resources = LocalContext.current.resources
    val res = DemoPreviews.resourceFor(demo.id, dark)
    val tint = MaterialTheme.colorScheme.surfaceContainerLow
    val alignment = FEATURED_MEDIA_ALIGNMENT[demo.id] ?: Alignment.Center
    val media: @Composable (Modifier) -> Unit = { mediaModifier ->
        if (res != null) {
            RowPicture(painterResource(res), alignment, mediaModifier)
        } else {
            GlyphPanel(icon = demo.icon, accent = accent, tint = tint, modifier = mediaModifier)
        }
    }
    val badges: @Composable RowScope.() -> Unit = {
        if (freshness != DemoFreshness.None) FreshnessChip(freshness = freshness, accent = accent)
        if (demo.status != DemoStatus.Working) StatusChip(status = demo.status)
    }
    val rowModifier = modifier.testTag(HomeTestTags.row(demo.id))
    when (style) {
        HomeRowStyle.Fused -> FusedRow(
            title = stringResource(demo.titleRes),
            subtitle = stringResource(demo.subtitleRes),
            tint = tint,
            onClick = onClick,
            media = media,
            badges = badges,
            modifier = rowModifier,
        )
        HomeRowStyle.Banner -> BannerRow(
            title = stringResource(demo.titleRes),
            subtitle = stringResource(demo.subtitleRes),
            tint = tint,
            onClick = onClick,
            media = media,
            badges = badges,
            modifier = rowModifier,
        )
    }
}

/**
 * The row that opens the online model gallery (`ExploreTabScreen`), drawn as one more
 * [HomeRowStyle.Fused] row — a globe on a `primary`-tinted panel — so it sits in the
 * list's rhythm instead of being a banner of its own.
 */
@Composable
fun BrowseOnlineRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val tint = glyphTint(accent, isSystemInDarkTheme())
    FusedRow(
        title = stringResource(R.string.home_browse_title),
        subtitle = stringResource(R.string.home_browse_subtitle),
        tint = tint,
        onClick = onClick,
        media = { GlyphPanel(icon = Icons.Filled.Language, accent = accent, tint = tint, modifier = it) },
        modifier = modifier,
    )
}

/**
 * The row under the hero that opens the "What's new" sheet, led by the picture of the
 * freshest demo nothing above it already shows ([leadDemoId]) so the entry point shows what
 * is new rather than a symbol for it, and not the hero's picture a second time.
 *
 * That picture is shown whole ([WholePictureRow]): it stands for a demo the row does not
 * name, so it has to be recognised from the card alone, and a card cropped to the leading
 * half of a row — a strip five times wider than tall on a tablet — kept three of
 * Geometry's seven shapes (#4351). A demo without a picture falls back to the badges' own
 * sparkle on a `primary`-tinted panel, in a plain [FusedRow].
 */
@Composable
fun WhatsNewRow(
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadDemoId: String? = null,
) {
    val dark = isSystemInDarkTheme()
    val resources = LocalContext.current.resources
    val res = leadDemoId?.let { DemoPreviews.resourceFor(it, dark) }
    val title = stringResource(R.string.home_whats_new_title)
    if (res != null) {
        // The picture's trailing edge is its left one when the row is mirrored.
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        WholePictureRow(
            title = title,
            subtitle = subtitle,
            tint = remember(res, dark) { HomeAmbient.tint(resources, res, dark) },
            picture = painterResource(res),
            pictureEdge = remember(res, rtl) { HomeAmbient.edge(resources, res, leftEdge = rtl) },
            alignment = FEATURED_MEDIA_ALIGNMENT[leadDemoId] ?: Alignment.Center,
            onClick = onClick,
            modifier = modifier,
        )
    } else {
        val accent = MaterialTheme.colorScheme.primary
        val tint = glyphTint(accent, dark)
        FusedRow(
            title = title,
            subtitle = subtitle,
            tint = tint,
            onClick = onClick,
            media = { GlyphPanel(icon = Icons.Filled.AutoAwesome, accent = accent, tint = tint, modifier = it) },
            modifier = modifier,
        )
    }
}

/** A whole picture beside its caption, separated by the normal token spacing. */
@Composable
private fun WholePictureRow(
    title: String,
    subtitle: String,
    tint: Color,
    picture: Painter,
    pictureEdge: Color,
    alignment: Alignment,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val home = SceneViewTokens.Home
    val aspect = picture.intrinsicSize.let { size ->
        if (size.isSpecified && size.height > 0f) size.width / size.height else 1f
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(home.rowRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        Layout(
            content = {
                // The spacer between the whole picture and its caption uses the card surface.
                Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow))
                RowPicture(
                    painter = picture,
                    alignment = alignment,
                    modifier = Modifier,
                )
                RowCaption(
                    title = title,
                    subtitle = subtitle,
                    badges = {},
                    modifier = Modifier
                        .padding(end = home.rowTextPaddingEnd)
                        .padding(vertical = home.rowTextPaddingVertical),
                )
            },
        ) { (meltMeasurable, pictureMeasurable, captionMeasurable), constraints ->
            val width = constraints.maxWidth
            val minHeight = home.rowHeight.roundToPx()
            val gap = home.rowWholeMelt.roundToPx()
            val widest = (width * home.rowMediaFraction).roundToInt()
            fun pictureWidth(height: Int) = (height * aspect).roundToInt().coerceAtMost(widest)
            // The picture's width follows the row's height and the text's height follows the
            // width the picture leaves: settle it once on the text's intrinsic height.
            val textHeight = captionMeasurable.minIntrinsicHeight(
                (width - pictureWidth(minHeight) - gap).coerceAtLeast(0),
            )
            val pictureWidth = pictureWidth(maxOf(minHeight, textHeight))
            val caption = captionMeasurable.measure(
                Constraints(maxWidth = (width - pictureWidth - gap).coerceAtLeast(0)),
            )
            val height = maxOf(minHeight, caption.height)
            val pictureBox = pictureMeasurable.measure(Constraints.fixed(pictureWidth, height))
            val meltStart = (pictureWidth * home.rowWholeDissolveStart).roundToInt()
            val melt = meltMeasurable.measure(
                Constraints.fixed(pictureWidth - meltStart + gap, height),
            )
            layout(width, height) {
                melt.placeRelative(meltStart, 0)
                pictureBox.placeRelative(0, 0)
                caption.placeRelative(pictureWidth + gap, (height - caption.height) / 2)
            }
        }
    }
}

/** Leading media beside an opaque, themed caption. */
@Composable
private fun FusedRow(
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit,
    media: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    badges: @Composable RowScope.() -> Unit = {},
) {
    val home = SceneViewTokens.Home
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(home.rowRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            // Title, subtitle and chips announce as one node.
            .semantics(mergeDescendants = true) {},
    ) {
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                media(Modifier)
                RowCaption(
                    title = title,
                    subtitle = subtitle,
                    badges = badges,
                    modifier = Modifier
                        .padding(start = SceneViewTokens.Space.sm, end = home.rowTextPaddingEnd)
                        .padding(vertical = home.rowTextPaddingVertical),
                )
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val mediaWidth = (width * home.rowTextStartFraction).roundToInt()
            val caption = measurables[1].measure(Constraints(maxWidth = width - mediaWidth))
            val height = maxOf(home.rowHeight.roundToPx(), caption.height)
            val picture = measurables[0].measure(Constraints.fixed(mediaWidth, height))
            layout(width, height) {
                picture.placeRelative(0, 0)
                caption.placeRelative(mediaWidth, (height - caption.height) / 2)
            }
        }
    }
}

/** Full-width media followed by a caption on the card surface. */
@Composable
private fun BannerRow(
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit,
    media: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    badges: @Composable RowScope.() -> Unit = {},
) {
    val home = SceneViewTokens.Home
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(home.rowRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            media(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(home.bannerAspect),
            )
            RowCaption(
                title = title,
                subtitle = subtitle,
                badges = badges,
                modifier = Modifier
                    .padding(top = home.rowTextPaddingVertical)
                    .padding(horizontal = home.bannerTextPaddingHorizontal)
                    .padding(bottom = home.rowTextPaddingVertical),
            )
        }
    }
}

/**
 * The chips on their own line above the title, the title in `type-card`, the subtitle in
 * `type-caption` regular. The chips used to share the title's line, where a two-chip row
 * squeezed a long title into three lines and a pill sat in the middle of a wrapped one.
 */
@Composable
private fun RowCaption(
    title: String,
    subtitle: String,
    badges: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        BadgeLine(badges)
        Text(
            text = title,
            style = SceneViewTokens.Type.card,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(SceneViewTokens.Home.rowTextGap))
        Text(
            text = subtitle,
            style = SceneViewTokens.Type.caption,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The row's chips, then `home-row-text-gap` down to the title — or nothing at all when no
 * chip draws, so a caption without chips keeps the exact height it had before (a
 * `spacedBy` column would still spend the gap on an empty line).
 */
@Composable
private fun BadgeLine(badges: @Composable RowScope.() -> Unit) {
    val gap = SceneViewTokens.Home.rowTextGap
    Layout(
        content = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                content = badges,
            )
        },
    ) { measurables, constraints ->
        val row = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        if (row.width == 0 || row.height == 0) {
            layout(0, 0) {}
        } else {
            layout(row.width, row.height + gap.roundToPx()) { row.place(0, 0) }
        }
    }
}

/** A demo's capture, cropped to fill whatever box the row gives it. */
@Composable
private fun RowPicture(painter: Painter, alignment: Alignment, modifier: Modifier) {
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = alignment,
        modifier = modifier,
    )
}

/** A demo with no capture yet (or a utility row): its glyph on an [accent] wash. */
@Composable
private fun GlyphPanel(icon: ImageVector, accent: Color, tint: Color, modifier: Modifier) {
    Box(
        modifier = modifier.background(accent.copy(alpha = GLYPH_WASH_ALPHA).compositeOver(tint)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(SceneViewTokens.Home.rowGlyph),
        )
    }
}

/** The row colour of a glyph row: the ambient tint of its accent, as if it were a picture. */
private fun glyphTint(accent: Color, dark: Boolean): Color = ambientTint(accent, dark)

/** Opacity of the accent wash behind a [GlyphPanel]'s glyph. */
private const val GLYPH_WASH_ALPHA = 0.18f

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

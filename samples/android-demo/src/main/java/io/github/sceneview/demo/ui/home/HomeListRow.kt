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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * One demo on the Home list (`home-row` / `home-banner` in `DESIGN.md`).
 *
 * The picture is the row. It runs to the row's own edges — no inset, no frame, no radius
 * of its own — and dissolves into the row's colour, which is the picture's colour
 * ([HomeAmbient]): a night capture carries on as a deep navy under the text, the fox as a
 * warm brown. Nothing reads as a thumbnail pasted on a grey tile.
 *
 * [HomeRowStyle.Fused] puts the picture on the leading half, full height, dissolving
 * sideways under the start of the text: the catalogue stays a list you scan by title.
 * [HomeRowStyle.Banner] gives the picture the full width and lets it dissolve down into
 * its caption — the Featured group, where the pictures are the point.
 */
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
    val tint = if (res != null) {
        remember(res, dark) { HomeAmbient.tint(resources, res, dark) }
    } else {
        glyphTint(accent, dark)
    }
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

/**
 * `home-row-whole`: a row whose [picture] is shown whole instead of filling half the row.
 * The picture keeps its own shape at the row's height, against the leading edge, solid
 * across the band a card holds its subject in. The melt into [tint] takes most of its
 * room after the picture, not from it: from `home-row-whole-dissolve` on the picture
 * gives way to [pictureEdge], the colour it ends on, which carries on past the picture
 * over `home-row-whole-melt` and fades into the row on one ease from the first point to
 * the last. A dark card on a pale row therefore ends like an illustration, not on a
 * line. The text starts after that strip. The row's width never decides what is left of
 * the picture: a phone and a tablet show the same card.
 *
 * A row its text makes taller widens the picture with it, up to the
 * [SceneViewTokens.Home.rowMediaFraction] a catalogue row gives its own; only past that
 * (a large font on a narrow window) is the picture cropped, from its sides, around
 * [alignment].
 */
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
        color = tint,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        Layout(
            content = {
                // Under the picture's dissolving margin and on past it: one melt, one ease.
                Box(Modifier.dissolve(DissolveEdge.End, start = 0f).background(pictureEdge))
                RowPicture(
                    painter = picture,
                    alignment = alignment,
                    modifier = Modifier.dissolve(DissolveEdge.End, home.rowWholeDissolveStart),
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

/**
 * `home-row`: the picture fills the leading [SceneViewTokens.Home.rowMediaFraction] of the
 * row, top to bottom, and dissolves into [tint] from `home-row-dissolve` on; the text
 * starts where the picture has all but gone.
 */
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
        color = tint,
        modifier = modifier
            .fillMaxWidth()
            // Title, subtitle and chips announce as one node.
            .semantics(mergeDescendants = true) {},
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = home.rowHeight)) {
            Box(modifier = Modifier.matchParentSize()) {
                media(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(home.rowMediaFraction)
                        .dissolve(DissolveEdge.End, home.rowDissolveStart),
                )
            }
            Row(modifier = Modifier.fillMaxWidth().align(Alignment.CenterStart)) {
                Spacer(Modifier.weight(home.rowTextStartFraction))
                RowCaption(
                    title = title,
                    subtitle = subtitle,
                    badges = badges,
                    modifier = Modifier
                        .weight(1f - home.rowTextStartFraction)
                        .padding(end = home.rowTextPaddingEnd)
                        .padding(vertical = home.rowTextPaddingVertical),
                )
            }
        }
    }
}

/**
 * `home-banner`: the picture across the row at `home-banner-aspect`, dissolving from
 * `home-banner-dissolve` down into [tint]; the caption is pulled up into the dissolve so
 * the title sits where the picture ends, not under an edge.
 */
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
        color = tint,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            media(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(home.bannerAspect)
                    .dissolve(DissolveEdge.Bottom, home.bannerDissolveStart),
            )
            RowCaption(
                title = title,
                subtitle = subtitle,
                badges = badges,
                modifier = Modifier
                    .pullUp(home.bannerCaptionOverlap)
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

private enum class DissolveEdge { End, Bottom }

/**
 * Fades the content out towards [edge], fully opaque up to [start] (a fraction of the
 * size along that axis) and gone at the edge, on a cosine ease so the fade has no band
 * where it starts or ends. Drawn in an offscreen layer and masked with `DstIn`, so what
 * shows through is the row's own tint.
 */
private fun Modifier.dissolve(edge: DissolveEdge, start: Float): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val stops = dissolveStops(start)
        val brush = when (edge) {
            DissolveEdge.Bottom -> Brush.verticalGradient(*stops)
            DissolveEdge.End -> if (layoutDirection == LayoutDirection.Rtl) {
                Brush.horizontalGradient(*stops, startX = size.width, endX = 0f)
            } else {
                Brush.horizontalGradient(*stops)
            }
        }
        drawRect(brush, blendMode = BlendMode.DstIn)
    }

/** Opaque to [start], then a cosine ease to transparent at 1. */
private fun dissolveStops(start: Float): Array<Pair<Float, Color>> {
    val steps = 8
    return Array(steps + 2) { i ->
        if (i == 0) {
            0f to Color.Black
        } else {
            val u = (i - 1) / steps.toFloat()
            val alpha = (0.5 * (1 + cos(PI * u))).toFloat()
            (start + (1f - start) * u) to Color.Black.copy(alpha = alpha)
        }
    }
}

/** Lays the content out [by] higher than it would sit, and takes that much off its height. */
private fun Modifier.pullUp(by: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = by.roundToPx()
    layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) {
        placeable.place(0, -shift)
    }
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

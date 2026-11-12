package io.github.sceneview.demo.ui.home

import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoFreshness
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.IN_REVIEW_BADGE_VISIBLE
import io.github.sceneview.demo.R
import io.github.sceneview.demo.previewPainter
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.sample.ui.DemoCategoryAccent

/** A demo preview above an opaque themed caption; badges remain on the media. */
@Composable
fun DemoMediaCard(
    demo: DemoEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    freshness: DemoFreshness = DemoFreshness.None,
    /** Where the picture is anchored when it is cropped — see [FEATURED_MEDIA_ALIGNMENT]. */
    mediaAlignment: Alignment = Alignment.Center,
    /**
     * The demos laid out beside this one — its grid row. Their
     * captions set this card's caption floor, so cards side by side end level. Read at
     * layout time.
     */
    rowPeers: () -> List<DemoEntry> = { emptyList() },
) {
    val dark = isSystemInDarkTheme()
    val resources = LocalContext.current.resources
    MediaCard(
        title = stringResource(demo.titleRes),
        subtitle = stringResource(demo.subtitleRes),
        preview = demo.previewPainter(),
        icon = demo.icon,
        accent = DemoCategoryAccent[demo.category, dark],
        status = demo.status,
        onClick = onClick,
        modifier = modifier,
        freshness = freshness,
        mediaAlignment = mediaAlignment,
        captionPeers = {
            rowPeers().map { resources.getString(it.titleRes) to resources.getString(it.subtitleRes) }
        },
    )
}

@Composable
private fun MediaCard(
    title: String,
    subtitle: String,
    preview: Painter?,
    icon: ImageVector,
    accent: Color,
    status: DemoStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    freshness: DemoFreshness = DemoFreshness.None,
    /** Where the picture is anchored when it is cropped to the card. */
    mediaAlignment: Alignment = Alignment.Center,
    /** Title and subtitle of the cards beside this one; see [DemoMediaCard]'s `rowPeers`. */
    captionPeers: () -> List<Pair<String, String>> = { emptyList() },
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) SceneViewTokens.Spring.pressScale else 1f,
        animationSpec = spring(
            dampingRatio = SceneViewTokens.Spring.dampingRatio,
            stiffness = SceneViewTokens.Spring.stiffness,
        ),
        label = "cardPress",
    )
    val home = SceneViewTokens.Home
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(home.cardRadius)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            // Title + subtitle + chip announce as one node.
            .semantics(mergeDescendants = true) {}
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        // Light lifts the card off the page with `shadow-sm`; dark keeps the 1 dp
        // `outline-subtle` a shadow cannot draw on a dark page.
        shadowElevation = if (dark) 0.dp else SceneViewTokens.Elevation.sm,
        border = if (dark) BorderStroke(home.cardOutlineWidth, outlineSubtle()) else null,
    ) {
        val aspect = home.cardMediaAspect
        val captionInset = home.cardTextPaddingHorizontal
        val titleStyle = SceneViewTokens.Type.card
        val subtitleStyle = SceneViewTokens.Type.caption.copy(fontWeight = FontWeight.Normal)
        val measurer = rememberTextMeasurer()
        val textGap = SceneViewTokens.Space.xs
        Box(modifier = Modifier.fillMaxWidth()) {
            // 1. The picture, sharp, at the top of the card.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightFromWidth(aspect)
                    .clipToBounds(),
            ) {
                if (preview != null) {
                    MediaImage(preview, mediaAlignment)
                } else {
                    IconTile(icon = icon, accent = accent)
                }
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.fillMaxWidth().heightFromWidth(aspect))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(
                            top = SceneViewTokens.Space.md,
                            start = captionInset,
                            end = captionInset,
                            bottom = home.cardTextPaddingBottom,
                        )
                        // Cards side by side share one caption height, so a row lines up
                        // without cutting a word: the tallest peer's text sets the floor.
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val floor = captionPeers().maxOfOrNull { (peerTitle, peerSubtitle) ->
                                captionHeight(
                                    CaptionKey(
                                        title = peerTitle,
                                        subtitle = peerSubtitle,
                                        titleStyle = titleStyle,
                                        subtitleStyle = subtitleStyle,
                                        width = constraints.maxWidth,
                                        gap = textGap.roundToPx(),
                                        density = density,
                                        fontScale = fontScale,
                                    ),
                                    measurer,
                                )
                            } ?: 0
                            val height = maxOf(placeable.height, floor)
                                .coerceIn(constraints.minHeight, constraints.maxHeight)
                            layout(placeable.width, height) { placeable.place(0, 0) }
                        },
                    verticalArrangement = Arrangement.spacedBy(textGap),
                ) {
                    Text(text = title, style = titleStyle, color = MaterialTheme.colorScheme.onSurface)
                    Text(text = subtitle, style = subtitleStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (status != DemoStatus.Working) {
                StatusChip(
                    status = status,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(SceneViewTokens.Space.sm),
                )
            }
            if (freshness != DemoFreshness.None) {
                FreshnessChip(
                    freshness = freshness,
                    accent = accent,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(SceneViewTokens.Space.sm),
                )
            }
        }
    }
}

/** Everything the height of a caption depends on: its two texts, their styles, and the room it has. */
private data class CaptionKey(
    val title: String,
    val subtitle: String,
    val titleStyle: TextStyle,
    val subtitleStyle: TextStyle,
    val width: Int,
    val gap: Int,
    val density: Float,
    val fontScale: Float,
)

/**
 * Caption heights already measured (#4461). Every card of a row asks for the height of every
 * caption of that row, and a list is rebuilt from nothing each time a demo is closed: without
 * this, a row of two lays out eight paragraphs to learn two numbers, on the frame that starts
 * the exit transition. A height is a pure function of its [CaptionKey], so it is computed once.
 */
private val captionHeights = LruCache<CaptionKey, Int>(CAPTION_HEIGHTS_KEPT)

private const val CAPTION_HEIGHTS_KEPT = 256

/** Height of the caption [key] describes: title, gap, subtitle, each wrapped to the key's width. */
private fun captionHeight(key: CaptionKey, measurer: TextMeasurer): Int =
    captionHeights[key] ?: run {
        val width = Constraints(maxWidth = key.width)
        val title = measurer.measure(key.title, key.titleStyle, constraints = width)
        val subtitle = measurer.measure(key.subtitle, key.subtitleStyle, constraints = width)
        (title.size.height + key.gap + subtitle.size.height).also { captionHeights.put(key, it) }
    }

/** Exact height from the incoming width: `width / aspect`, less [minus]. */
private fun Modifier.heightFromWidth(aspect: Float, minus: Dp = 0.dp): Modifier =
    layout { measurable, constraints ->
        val height = (constraints.maxWidth / aspect - minus.toPx()).roundToInt()
            .coerceIn(0, constraints.maxHeight)
        val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/** The card's picture, cropped to fill. */
@Composable
private fun MediaImage(painter: Painter, alignment: Alignment) {
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = alignment,
        modifier = Modifier.fillMaxSize(),
    )
}

/** Fallback media while no preview capture exists: the demo icon on `surface-dim`. */
@Composable
private fun IconTile(icon: ImageVector, accent: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(chipBackground()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(SceneViewTokens.Home.iconTileGlyph),
        )
    }
}

/**
 * "New" / "Updated" on the media, top-left.
 *
 * Same pill geometry as [StatusChip] so the two read as one family, but tinted
 * with the demo's category accent rather than `onSurfaceVariant`: freshness is
 * an invitation, status is a caveat, and they must not look alike at a glance.
 */
@Composable
internal fun FreshnessChip(
    freshness: DemoFreshness,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val label = when (freshness) {
        DemoFreshness.New -> stringResource(R.string.samples_chip_new)
        DemoFreshness.Updated -> stringResource(R.string.samples_chip_updated)
        DemoFreshness.None -> return
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(SceneViewTokens.Radius.full),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        border = BorderStroke(SceneViewTokens.Home.cardOutlineWidth, outlineSubtle()),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = SceneViewTokens.Space.sm,
                vertical = SceneViewTokens.Home.badgePaddingVertical,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(SceneViewTokens.Home.badgeIcon),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                maxLines = Int.MAX_VALUE,
            )
        }
    }
}

@Composable
internal fun StatusChip(status: DemoStatus, modifier: Modifier = Modifier) {
    val label = when (status) {
        DemoStatus.KnownIssue -> stringResource(R.string.samples_chip_preview)
        DemoStatus.ComingSoon -> stringResource(R.string.samples_chip_soon)
        // Release builds draw no chip at all for InReview — see IN_REVIEW_BADGE_VISIBLE.
        DemoStatus.InReview ->
            if (IN_REVIEW_BADGE_VISIBLE) stringResource(R.string.samples_chip_in_review) else return
        DemoStatus.Working -> return
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(SceneViewTokens.Radius.full),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        border = BorderStroke(SceneViewTokens.Home.cardOutlineWidth, outlineSubtle()),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = SceneViewTokens.Space.sm,
                vertical = SceneViewTokens.Home.badgePaddingVertical,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SceneViewTokens.Home.badgeIcon),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                maxLines = Int.MAX_VALUE,
            )
        }
    }
}

/** `outline-subtle` for the current scheme (`DESIGN.md` Borders). */
@Composable
internal fun outlineSubtle(): Color =
    if (isSystemInDarkTheme()) SceneViewTokens.HomeColor.outlineSubtleDark
    else SceneViewTokens.HomeColor.outlineSubtleLight

/** `surface-container-high` as `DESIGN.md` defines it — chip + icon-tile fill. */
@Composable
internal fun chipBackground(): Color =
    if (isSystemInDarkTheme()) SceneViewTokens.HomeColor.chipBackgroundDark
    else SceneViewTokens.HomeColor.chipBackgroundLight

/**
 * The stage field of a stage that is **embedded in a card** — the home hero, a card's
 * preview art.
 *
 * `stage-background` (#0B0F16) is the viewer's clear colour, and a full-screen stage
 * keeps it. Painted inside a card it is a different job: measured on iOS first, the home
 * hero came out at **1.014:1** against the dark page, because the stage colour is drawn
 * *over* the card fill and no surface token underneath can rescue it. The card is then
 * the same colour as the page, whatever role it was given.
 *
 * So an embedded stage takes the elevated container tone in dark (1.32:1 against the
 * page) and keeps #0B0F16 in light, where the near-black field against a white page was
 * never the problem. Full-screen stages are untouched.
 */
@Composable
internal fun heroField(): Color =
    if (isSystemInDarkTheme()) SceneViewTokens.HomeColor.heroFieldEmbeddedDark
    else SceneViewTokens.HomeColor.heroField

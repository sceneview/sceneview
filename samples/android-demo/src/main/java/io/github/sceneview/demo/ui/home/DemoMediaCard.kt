package io.github.sceneview.demo.ui.home

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.material.icons.filled.Language
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

/**
 * One demo on the home grid (design spec §2, "Card").
 *
 * Anatomy, top to bottom: a square picture ([SceneViewTokens.Home.cardMediaAspect])
 * showing the captured preview when the image pipeline has produced one
 * ([DemoEntry.previewPainter]) and the category-tinted [DemoEntry.icon] tile
 * otherwise; then title (`type-card`) and subtitle (`type-caption`, weight 400), never
 * truncated. There is no white box under the picture any more: the caption sits on
 * `card-glass` — a blurred copy of the card's own picture under `surface-container` at
 * 72 % / 85 % — and the sharp picture dissolves into it over `card-glass-melt`, so each
 * card is tinted by what it shows. 20 dp radius; light lifts it with `shadow-sm`, dark
 * keeps the 1 dp `outline-subtle`.
 *
 * [featured] is the "Featured" shelf's variant ([FeaturedShelf]): a 4:5 portrait card,
 * picture full-bleed, the same frosted caption floating on its lower part, `type-title`,
 * `radius-xl`, `shadow-md`, and the picture drifting by [mediaShift] as the shelf moves.
 *
 * A status chip sits on the media only for [DemoStatus.ComingSoon] /
 * [DemoStatus.KnownIssue], and [DemoStatus.InReview] behind
 * [IN_REVIEW_BADGE_VISIBLE]. Press scales the card to 0.98 on the one app spring.
 *
 * A **freshness** chip ("New" / "Updated", #3566) sits opposite it, top-left, so
 * the two can coexist on one card. Unlike the status chip it is drawn in release
 * builds — it is addressed to users, not to whoever runs the sign-off pass — and
 * it expires on its own as the version moves. See [DemoFreshness].
 */
@Composable
fun DemoMediaCard(
    demo: DemoEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    freshness: DemoFreshness = DemoFreshness.None,
    featured: Boolean = false,
    mediaShift: () -> Float = { 0f },
) {
    val dark = isSystemInDarkTheme()
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
        featured = featured,
        mediaShift = mediaShift,
    )
}

/**
 * The row under the home grid that opens the online model gallery
 * (`ExploreTabScreen`): a globe glyph, the title and a one-line subtitle on
 * `surface-container-high`.
 */
@Composable
fun BrowseOnlineModelsCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(SceneViewTokens.Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(SceneViewTokens.Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
        ) {
            Icon(Icons.Filled.Language, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_browse_title), style = SceneViewTokens.Type.card)
                Text(stringResource(R.string.home_browse_subtitle), style = SceneViewTokens.Type.body)
            }
        }
    }
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
    /** A "Featured" card: portrait, picture full-bleed, caption floating on it. */
    featured: Boolean = false,
    /** Horizontal lag of the picture inside the card, in px — the shelf's parallax. */
    mediaShift: () -> Float = { 0f },
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
    val shape = RoundedCornerShape(if (featured) SceneViewTokens.Radius.xl else home.cardRadius)
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
        // Light lifts the card off the page with `shadow-sm` (`shadow-md` for a featured
        // one); dark keeps the 1 dp `outline-subtle` a shadow cannot draw on a dark page.
        shadowElevation = when {
            dark -> 0.dp
            featured -> SceneViewTokens.Elevation.md
            else -> SceneViewTokens.Elevation.sm
        },
        border = if (dark) BorderStroke(home.cardOutlineWidth, outlineSubtle()) else null,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val mediaHeight = maxWidth / if (featured) home.featuredMediaAspect else home.cardMediaAspect
            // Where the caption starts, read at draw time only: the glass is drawn from there
            // down, so a caption that grows (a long subtitle, a 1.5 font scale) takes its
            // glass with it and nothing re-lays out.
            var captionTop by remember { mutableFloatStateOf(Float.NaN) }
            val melt = with(LocalDensity.current) { home.cardGlassMelt.toPx() }
            val glass = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardGlassAlpha())
            val captionInset = if (featured) {
                home.heroPadding - SceneViewTokens.Space.xs
            } else {
                home.cardTextPaddingHorizontal
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                // 1. The picture, sharp — full-bleed on a featured card, the top square otherwise.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (featured) Modifier.matchParentSize() else Modifier.height(mediaHeight))
                        .clipToBounds(),
                ) {
                    if (preview != null) {
                        MediaImage(preview, mediaShift, featured)
                    } else {
                        IconTile(icon = icon, accent = accent)
                    }
                }
                // 2. The same picture, blurred, under the caption: frosted glass tinted by the
                //    image it describes. It fades in over `card-glass-melt`, so the sharp picture
                //    dissolves into the glass instead of stopping at an edge.
                if (preview != null && FROSTED_BLUR_SUPPORTED) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(meltBrush(captionTop, melt, Color.Black), blendMode = BlendMode.DstIn)
                            }
                            .blur(home.cardGlassBlur, BlurredEdgeTreatment.Rectangle),
                    ) {
                        MediaImage(preview, mediaShift, featured)
                    }
                }
                // 3. The glass tint — what the caption's contrast is measured against.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawBehind { drawRect(meltBrush(captionTop, melt, glass)) },
                )
                // Sizing column: the picture's height, then the caption — pulled up over the
                // picture's melt band on a grid card, pinned to the bottom of a featured one.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (featured) Modifier.heightIn(min = mediaHeight) else Modifier),
                ) {
                    Spacer(
                        if (featured) {
                            Modifier.weight(1f)
                        } else {
                            Modifier.height(mediaHeight - home.cardGlassMelt)
                        },
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPlaced { captionTop = it.positionInParent().y }
                            .padding(
                                top = home.cardGlassMelt,
                                start = captionInset,
                                end = captionInset,
                                bottom = if (featured) captionInset else home.cardTextPaddingBottom,
                            ),
                        verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
                    ) {
                        Text(
                            text = title,
                            style = if (featured) SceneViewTokens.Type.title else SceneViewTokens.Type.card,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = subtitle,
                            style = if (featured) SceneViewTokens.Type.body else SceneViewTokens.Type.caption,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (status != DemoStatus.Working) {
                    StatusChip(
                        status = status,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(if (featured) SceneViewTokens.Space.md else SceneViewTokens.Space.sm),
                    )
                }
                if (freshness != DemoFreshness.None) {
                    FreshnessChip(
                        freshness = freshness,
                        accent = accent,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(if (featured) SceneViewTokens.Space.md else SceneViewTokens.Space.sm),
                    )
                }
            }
        }
    }
}

/**
 * The card's picture, cropped to fill. On a featured card it is drawn a little larger than
 * the card and slides by `shift` as the shelf is swiped — parallax, read at draw time.
 */
@Composable
private fun MediaImage(painter: Painter, shift: () -> Float, featured: Boolean) {
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (featured) {
                    Modifier.graphicsLayer {
                        scaleX = FEATURED_MEDIA_OVERSCAN
                        scaleY = FEATURED_MEDIA_OVERSCAN
                        val slack = size.width * (FEATURED_MEDIA_OVERSCAN - 1f) / 2f
                        translationX = shift().coerceIn(-slack, slack)
                    }
                } else {
                    Modifier
                },
            ),
    )
}

/** How much larger than its card a featured picture is drawn, so the parallax never shows an edge. */
private const val FEATURED_MEDIA_OVERSCAN = 1.06f

/**
 * `RenderEffect` blur exists from API 31. Below it `Modifier.blur` is a no-op and a sharp
 * copy of the picture would sit under the text, so older devices draw no copy at all and
 * the caption takes the more opaque `glass-sheet` fill instead (see [cardGlassAlpha]).
 */
private val FROSTED_BLUR_SUPPORTED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** `card-glass` for the current scheme, or the `glass-sheet` value where there is no blur. */
@Composable
private fun cardGlassAlpha(): Float {
    val dark = isSystemInDarkTheme()
    return when {
        !FROSTED_BLUR_SUPPORTED && dark -> SceneViewTokens.Glass.sheetAlphaDark
        !FROSTED_BLUR_SUPPORTED -> SceneViewTokens.Glass.sheetAlphaLight
        dark -> SceneViewTokens.HomeColor.cardGlassAlphaDark
        else -> SceneViewTokens.HomeColor.cardGlassAlphaLight
    }
}

/**
 * [color] from the caption's top edge down, fading in over the [melt] band above it — the
 * mask of the blurred copy and the glass tint share it, so they melt in together. Draws
 * nothing before the caption has been placed.
 */
private fun DrawScope.meltBrush(top: Float, melt: Float, color: Color): Brush {
    if (top.isNaN() || size.height <= 0f) return SolidColor(Color.Transparent)
    val end = (top + melt).coerceIn(0f, size.height)
    val start = (top - melt).coerceIn(0f, end)
    return Brush.verticalGradient(
        0f to Color.Transparent,
        start / size.height to Color.Transparent,
        end / size.height to color,
        1f to color,
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
private fun FreshnessChip(
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
            modifier = Modifier.padding(horizontal = SceneViewTokens.Space.sm, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(12.dp),
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
private fun StatusChip(status: DemoStatus, modifier: Modifier = Modifier) {
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
            modifier = Modifier.padding(horizontal = SceneViewTokens.Space.sm, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
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

package io.github.sceneview.demo.ui.home

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.IN_REVIEW_BADGE_VISIBLE
import io.github.sceneview.demo.R
import io.github.sceneview.demo.previewPainter
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.sample.ui.DemoCategoryAccent

/**
 * One demo on the home grid (design spec §2, "Card").
 *
 * Anatomy, top to bottom: a 5:4 media slot ([SceneViewTokens.Layout.mediaAspect])
 * showing the captured preview when the image pipeline has produced one
 * ([DemoEntry.previewPainter]) and the category-tinted [DemoEntry.icon] tile
 * otherwise; then title (`type-card`, one line) and subtitle (`type-caption`,
 * weight 400, one line). `surface` fill, 1 dp `outline-subtle` hairline, 20 dp
 * radius, no shadow, no scrim, no overlay — the media is the card.
 *
 * A status chip sits on the media only for [DemoStatus.ComingSoon] /
 * [DemoStatus.KnownIssue], and [DemoStatus.InReview] behind
 * [IN_REVIEW_BADGE_VISIBLE]. Press scales the card to 0.98 on the one app spring.
 */
@Composable
fun DemoMediaCard(
    demo: DemoEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
    )
}

/**
 * The closing grid item — same anatomy as a demo card, static icon media —
 * that opens the online model gallery (`ExploreTabScreen`).
 */
@Composable
fun BrowseOnlineModelsCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MediaCard(
        title = stringResource(R.string.home_browse_title),
        subtitle = stringResource(R.string.home_browse_subtitle),
        preview = null,
        icon = Icons.Filled.Language,
        accent = MaterialTheme.colorScheme.primary,
        status = DemoStatus.Working,
        onClick = onClick,
        modifier = modifier,
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
        shape = RoundedCornerShape(home.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(home.cardOutlineWidth, outlineSubtle()),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(SceneViewTokens.Layout.mediaAspect),
            ) {
                if (preview != null) {
                    Image(
                        painter = preview,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    IconTile(icon = icon, accent = accent)
                }
                if (status != DemoStatus.Working) {
                    StatusChip(
                        status = status,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(SceneViewTokens.Space.sm),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = home.cardTextPaddingTop,
                        start = home.cardTextPaddingHorizontal,
                        end = home.cardTextPaddingHorizontal,
                        bottom = home.cardTextPaddingBottom,
                    ),
                verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
            ) {
                Text(
                    text = title,
                    style = SceneViewTokens.Type.card,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = SceneViewTokens.Type.caption,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
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
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** `outline-subtle` for the current scheme (`DESIGN.md` Borders). */
@Composable
internal fun outlineSubtle(): Color =
    if (isSystemInDarkTheme()) SceneViewTokens.HomeColor.outlineSubtleDark
    else SceneViewTokens.HomeColor.outlineSubtleLight

/** `surface-dim` as `DESIGN.md` defines it — chip + icon-tile fill. */
@Composable
internal fun chipBackground(): Color =
    if (isSystemInDarkTheme()) SceneViewTokens.HomeColor.chipBackgroundDark
    else SceneViewTokens.HomeColor.chipBackgroundLight

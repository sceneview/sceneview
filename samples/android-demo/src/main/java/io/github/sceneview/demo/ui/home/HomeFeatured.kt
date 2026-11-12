package io.github.sceneview.demo.ui.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.previewPainter
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.pressScale

/**
 * One page of the Showcase's featured pager.
 *
 * [WhatsNew] is not a demo: it is the "What's new in 4.x" entry the maintainer
 * asked for at the top of the Showcase (#3566). Modelling it as a page rather
 * than as one more full-span card in the grid keeps the promise the grid makes —
 * the grid is demos — and puts the answer to "which feature can I test?" in the
 * one slot the eye already lands on.
 */
sealed interface FeaturedPage {
    /** Stable pager key. */
    val key: String

    /**
     * A demo, opened by tapping the page.
     *
     * [heroArt] overrides the demo's grid capture for pages that have bespoke
     * editorial artwork. The Model Viewer page no longer passes one: it is the window
     * onto the live stage (#3948). `preview_hero_model_viewer` stays bundled unused —
     * assets are never deleted — framed for a full-span card should a page want it.
     *
     * [titleRes] overrides the demo's catalogue title on its page. The flagship passes the
     * screen it opens, "Model Viewer" (#4622): titled "Models", its page read as a section
     * and the Model Viewer was never seen on the home.
     */
    data class Demo(
        val entry: DemoEntry,
        @DrawableRes val heroArt: Int? = null,
        @StringRes val titleRes: Int? = null,
    ) : FeaturedPage {
        override val key: String get() = keyFor(entry.id)

        companion object {
            /** The pager key a demo page carries — the handle the live hero is matched on. */
            fun keyFor(demoId: String): String = "demo-$demoId"
        }
    }

    /**
     * "New in [version] — [count] samples". Opens the What's new sheet.
     * Only ever built when [count] is greater than zero — an empty
     * "nothing changed" page is worse than no page.
     */
    data class WhatsNew(val version: String, val count: Int) : FeaturedPage {
        override val key: String get() = "whats-new"
    }
}

/** Test tags for the featured pager. */
object FeaturedTestTags {
    const val PAGER = "home-featured-pager"
    const val INDICATOR = "home-featured-indicator"
}

/** Featured pages with opaque themed captions and a standard pager indicator. */
@Composable
fun HomeFeaturedPager(
    pages: List<FeaturedPage>,
    height: Dp,
    onDemoClick: (String) -> Unit,
    onWhatsNewClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Hoisted by [HomeScreen] (#3949): this pager lives in a lazy item, and a state
     * remembered here left with the item — scroll past the band, come back, page one
     * again. Owned by the screen it keeps the page the user chose.
     */
    pagerState: PagerState = rememberPagerState(pageCount = { pages.size }),
) {
    if (pages.isEmpty()) return
    val windowKey = FeaturedPage.Demo.keyFor(HERO_DEMO_ID)
    Box(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = SceneViewTokens.Space.md,
            key = { pages[it].key },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(FeaturedTestTags.PAGER),
        ) { index ->
            when (val page = pages[index]) {
                is FeaturedPage.Demo -> FeaturedCard(
                    height = height,
                    title = stringResource(page.titleRes ?: page.entry.titleRes),
                    subtitle = stringResource(page.entry.subtitleRes),
                    actionLabel = stringResource(R.string.home_hero_open),
                    media = if (page.key == windowKey) {
                        null
                    } else {
                        // The band is the dark stage in both themes, so its pages take the
                        // dark capture: a light-theme still put the white copy on pale wood
                        // (Rolling Balls, #4622).
                        page.heroArt?.let { painterResource(it) } ?: page.entry.previewPainter(dark = true)
                    },
                    onClick = { onDemoClick(page.entry.id) },
                    window = page.key == windowKey,
                )
                is FeaturedPage.WhatsNew -> FeaturedCard(
                    height = height,
                    title = stringResource(R.string.home_featured_whats_new_title, page.version),
                    subtitle = pluralStringResource(
                        R.plurals.home_featured_whats_new_subtitle,
                        page.count,
                        page.count,
                    ),
                    actionLabel = stringResource(R.string.home_featured_whats_new_action),
                    media = null,
                    glyph = true,
                    onClick = onWhatsNewClick,
                )
            }
        }
        if (pages.size > 1) {
            PageIndicator(
                count = pages.size,
                selected = pagerState.currentPage,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(SceneViewTokens.Home.heroPadding)
                    .testTag(FeaturedTestTags.INDICATOR),
            )
        }
    }
}

/** A preview with a flat surface behind its title and supporting copy. */
@Composable
private fun FeaturedCard(
    height: Dp,
    title: String,
    subtitle: String,
    actionLabel: String,
    media: Painter?,
    onClick: () -> Unit,
    glyph: Boolean = false,
    window: Boolean = false,
) {
    val dark = isSystemInDarkTheme()
    val colors = SceneViewTokens.HomeColor
    val home = SceneViewTokens.Home
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .semantics(mergeDescendants = true) {}
            .then(if (window) Modifier else Modifier.pressScale(interaction))
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
            ),
        shape = if (window) RectangleShape else MaterialTheme.shapes.medium,
        color = if (window) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 0.dp,
        border = if (dark && !window) BorderStroke(home.cardOutlineWidth, outlineSubtle()) else null,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (media != null) {
                Image(
                    painter = media,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (glyph) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = colors.heroSubtitle,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(home.heroPadding)
                        .size(featuredGlyphSize),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(home.heroPadding),
                verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                Text(
                    text = title,
                    style = SceneViewTokens.Type.display,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = SceneViewTokens.Type.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.widthIn(max = home.heroSubtitleMaxWidth),
                )
                Surface(
                    modifier = Modifier
                        .padding(top = SceneViewTokens.Space.sm)
                        .height(home.heroPillHeight),
                    shape = RoundedCornerShape(SceneViewTokens.Radius.full),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    // Label then arrow (#4622): a bare "Open" over the live stage read as a
                    // caption. The arrow is decorative — the page is one merged button node.
                    Row(
                        modifier = Modifier.padding(horizontal = home.heroPillPaddingHorizontal),
                        horizontalArrangement = Arrangement.spacedBy(home.heroPillIconGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = actionLabel,
                            style = SceneViewTokens.Type.body,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(home.heroPillIconSize),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Where a demo's picture is anchored when the 5:4 preview is cropped narrower — to a
 * home row's square thumb ([DemoListRow]). Centred by default; a preview listed here
 * carries something at one edge that must stay out of frame.
 *
 * `ar-rerun`: the preview is a capture of the demo, whose top-right corner holds the
 * demo's own "Camera" picture-in-picture (source x ≥ 525 of 800). Centred, the crop keeps
 * half of it and it reads as a second picture stuck on the first. Anchored left, the
 * crop keeps the camera path and the rebuilt room, no inset.
 */
internal val FEATURED_MEDIA_ALIGNMENT: Map<String, Alignment> = mapOf(
    "ar-rerun" to Alignment.CenterStart,
)

/** 28 dp — the What's new page's corner glyph, matched to `type-display`'s cap height. */
private val featuredGlyphSize = 28.dp

/** Dot diameter and gap for [PageIndicator] — the carousel convention, unscaled. */
private val indicatorDot = 6.dp

/**
 * Dots over the scrim, bottom-right, opposite the copy. Drawn inside the card
 * rather than under it so the pager stays exactly one grid item tall and the
 * Showcase's vertical rhythm is unchanged.
 *
 * Colours are the fixed hero tokens for the same reason the copy uses them: the
 * card is dark in both themes, so a `colorScheme` role would vanish in light
 * mode.
 */
@Composable
private fun PageIndicator(count: Int, selected: Int, modifier: Modifier = Modifier) {
    val colors = SceneViewTokens.HomeColor
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .size(indicatorDot)
                    .background(
                        color = if (index == selected) colors.heroTitle else colors.heroSubtitle.copy(alpha = 0.4f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

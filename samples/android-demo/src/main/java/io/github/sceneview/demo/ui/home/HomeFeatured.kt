package io.github.sceneview.demo.ui.home

import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloatAsState
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.previewPainter
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.motionTween
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
     * editorial artwork — the Model Viewer page keeps `preview_hero_model_viewer`,
     * which is framed for a full-span card rather than for a 5:4 grid cell.
     */
    data class Demo(
        val entry: DemoEntry,
        @DrawableRes val heroArt: Int? = null,
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

/**
 * The Showcase's featured banner — now a **horizontal pager** (#3567).
 *
 * It has always looked like a carousel: full-bleed image, scrim, display title,
 * "Open" pill — the exact shape every store app uses for a swipeable featured
 * row. It had no horizontal gesture, no second page and no indicator, so the
 * affordance the layout promised was not there and one slot out of 51 demos was
 * permanently spent on the same card.
 *
 * Each page keeps the hero's visual contract unchanged (design spec §2):
 * `radius-xl` clip, media cropped to fill, a vertical scrim from transparent at
 * 50 % to `stage-scrim-end`, bottom-left copy — `type-display` title,
 * `type-body` subtitle at 80 % white, one 44 dp pill. Light: `shadow-md`; dark:
 * 1 dp `outline-subtle`. Pages stay dark in both themes by design, which is why
 * their text colours are fixed [SceneViewTokens.HomeColor] tokens rather than
 * `colorScheme` roles.
 *
 * The whole pager is one grid item, so the Showcase's vertical scroll is
 * untouched; each page is one merged semantics node, so a screen reader
 * announces a page rather than four fragments.
 */
@Composable
fun HomeFeaturedPager(
    pages: List<FeaturedPage>,
    height: Dp,
    onDemoClick: (String) -> Unit,
    onWhatsNewClick: () -> Unit,
    modifier: Modifier = Modifier,
    collapseFraction: () -> Float = { 0f },
    heroRendering: Boolean = true,
) {
    if (pages.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { pages.size })
    // The live subject belongs to the page the app's flagship demo owns, and to no
    // other: a carousel where every page runs its own Filament engine is three engines
    // on the home screen. Inspection mode (Android Studio `@Preview`, the Roborazzi
    // home goldens) never composes it — LayoutLib has no Filament `.so` to load, and
    // a golden has to pin the bundled still, which is exactly what the fallback is.
    val liveHeroKey = if (LocalInspectionMode.current) null else FeaturedPage.Demo.keyFor(HERO_DEMO_ID)
    var heroSceneReady by remember { mutableStateOf(false) }
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
                    title = stringResource(page.entry.titleRes),
                    subtitle = stringResource(page.entry.subtitleRes),
                    actionLabel = stringResource(R.string.home_hero_open),
                    media = page.heroArt?.let { painterResource(it) }
                        ?: page.entry.previewPainter(),
                    // Once the subject is live the bundled still is redundant, so it
                    // crossfades away under it — the one "loading → content" fade the
                    // screen has, and the reason the still is still drawn at all.
                    mediaAlpha = if (page.key == liveHeroKey && heroSceneReady) 0f else 1f,
                    onClick = { onDemoClick(page.entry.id) },
                    live = if (page.key != liveHeroKey) {
                        null
                    } else {
                        {
                            HomeHeroScene(
                                collapseFraction = collapseFraction,
                                rendering = heroRendering,
                                onVisibilityChange = { heroSceneReady = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    },
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

/**
 * One featured page. [media] is the demo's own capture — the same
 * `preview_<id>_{light,dark}.webp` pair the grid cards use, so adding a featured
 * entry needs no new artwork. When it is absent the page falls back to the flat
 * `hero-field` stage colour, optionally carrying [glyph] — which is what the
 * What's new page uses, deliberately, so it does not masquerade as a demo.
 */
@Composable
private fun FeaturedCard(
    height: Dp,
    title: String,
    subtitle: String,
    actionLabel: String,
    media: Painter?,
    onClick: () -> Unit,
    glyph: Boolean = false,
    mediaAlpha: Float = 1f,
    live: (@Composable BoxScope.() -> Unit)? = null,
) {
    val dark = isSystemInDarkTheme()
    val colors = SceneViewTokens.HomeColor
    val home = SceneViewTokens.Home
    val interaction = remember { MutableInteractionSource() }
    val stillAlpha by animateFloatAsState(
        targetValue = mediaAlpha,
        animationSpec = motionTween(SceneViewTokens.Duration.longMillis),
        label = "featured-still",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .semantics(mergeDescendants = true) {}
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(SceneViewTokens.Radius.xl),
        color = heroField(),
        shadowElevation = if (dark) 0.dp else SceneViewTokens.Elevation.md,
        border = if (dark) BorderStroke(home.cardOutlineWidth, outlineSubtle()) else null,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (media != null && stillAlpha > 0f) {
                Image(
                    painter = media,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(stillAlpha),
                )
            }
            // Drawn over the still and under the scrim, so the copy and the pill keep
            // the exact contrast they have on a page that is only a photograph.
            live?.invoke(this)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            home.heroScrimStart to SceneViewTokens.SpatialGalleryColor.stageScrimStart,
                            1f to SceneViewTokens.SpatialGalleryColor.stageScrimEnd,
                        ),
                    ),
            )
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
                    .padding(home.heroPadding),
                verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                Text(
                    text = title,
                    style = SceneViewTokens.Type.display,
                    color = colors.heroTitle,
                )
                Text(
                    text = subtitle,
                    style = SceneViewTokens.Type.body,
                    color = colors.heroSubtitle,
                    maxLines = 2,
                    modifier = Modifier.widthIn(max = home.heroSubtitleMaxWidth),
                )
                Surface(
                    modifier = Modifier
                        .padding(top = SceneViewTokens.Space.sm)
                        .height(home.heroPillHeight),
                    shape = RoundedCornerShape(SceneViewTokens.Radius.full),
                    color = colors.heroPillBackground,
                    contentColor = colors.heroPillText,
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = home.heroPillPaddingHorizontal),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = actionLabel,
                            style = SceneViewTokens.Type.body,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.heroPillText,
                        )
                    }
                }
            }
        }
    }
}

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

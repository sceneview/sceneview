@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoFreshness
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.RequestLightStatusBarIcons
import io.github.sceneview.demo.categoryDisplayNameRes
import io.github.sceneview.demo.freshDemos
import io.github.sceneview.demo.freshness
import io.github.sceneview.demo.freshnessHeadlineVersion
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.motionFade
import io.github.sceneview.demo.whatsnew.WhatsNewRelease
import io.github.sceneview.demo.whatsnew.WhatsNewSheet
import io.github.sceneview.demo.whatsnew.loadWhatsNew
import io.github.sceneview.demo.ui.cascadeIn
import io.github.sceneview.demo.ui.pressScale
import io.github.sceneview.demo.ui.rememberCascade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test tags for the home screen. */
object HomeTestTags {
    const val GRID = "home-grid"
    const val SEARCH_FIELD = "home-search-field"
    const val HERO = "home-hero"
    const val SEARCH_CLOSE = "home-search-close"

    /** Test tag of the full-span header drawn above [category]'s first card (#2239). */
    fun sectionHeader(category: String): String =
        "home-section-" + category.lowercase().replace(Regex("[^a-z0-9]+"), "-")

    /** Test tag of the "Featured" shelf header, right under the hero. */
    const val FEATURED_SECTION = "home-section-featured"
}

/**
 * The Showcase tab (design spec §2): one `LazyVerticalGrid`, no nested
 * scroll. Full-span header spacer, hero, the "Featured" shelf
 * ([FEATURED_SECTION_IDS], priority order), a [BrowseOnlineModelsCard] that opens
 * the online gallery and the chip row, then every demo as a [DemoMediaCard] in flat
 * editorial [DemoEntry.order], one section per category.
 *
 * Under the grid, and not part of it, sits the live stage (#3948): the dusk flight of
 * [HomeHeroScene] over a sky this screen paints, from the top edge of the display to
 * a little past the featured band. It is composed once per screen and never by a lazy
 * item, which is the whole fix for #3949: scrolling away and back cannot dispose the
 * engine, the model or the clock, so the flight is simply where it was. The band's
 * first page is a transparent window onto it — copy, scrim and pill, no still.
 *
 * The header is a pinned overlay drawn over the grid: transparent with white type
 * while the stage is under it, `surface` at 100 % plus a bottom hairline once the
 * first item has scrolled away. Its search action swaps the wordmark row for a 48 dp
 * field; the category chips and the query are hoisted to `RootScreen` so they
 * survive tab switches and process death.
 *
 * The "What's new" sheet stays reachable from a small header action instead of
 * a full-span card — the grid is for demos only. Loading is skipped in
 * inspection mode so the snapshot goldens do not churn on every release.
 */
@Composable
fun HomeScreen(
    demos: List<DemoEntry>,
    selectedCategory: String?,
    onCategoryChange: (String?) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onDemoClick: (String) -> Unit,
    onBrowseOnlineClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether the "since you last tested" list has pending entries. Owned by
     * [io.github.sceneview.demo.ui.RootScreen] — this screen must not derive it
     * again, or acknowledging in the sheet would leave a second stale copy of
     * the marker driving the badge. Defaulted so previews and snapshot tests
     * render the unbadged header they already pinned.
     */
    hasUnseenWhatsNew: Boolean = false,
    onWhatsNewSinceClick: () -> Unit = {},
    /**
     * The version the freshness markers are measured against — `VERSION_NAME`
     * in the app, a pinned value in the snapshot tests.
     *
     * Read as a parameter rather than straight off `BuildConfig` because that
     * read is what coupled the home goldens to `gradle.properties` (#3666).
     * Freshness is a *relative* verdict: a demo declaring `updatedIn = "4.35.0"`
     * is inside the window at build 4.36 and outside it at 4.37, so the release
     * commit's own version bump silently repaints the grid. The goldens then
     * failed on the release PR — the one PR where a red check is most expensive
     * and least informative — and were re-recorded under time pressure at 4.35.0
     * and again at 4.37.0, which is not review, it is ratification. Hoisting the
     * version makes the badge set a function of what the demos declare, and of
     * nothing else.
     */
    buildVersion: String = BuildConfig.VERSION_NAME,
) {
    val home = SceneViewTokens.Home
    val gridState = rememberLazyGridState()
    val expanded = LocalConfiguration.current.screenWidthDp >= home.expandedWidthDp
    val inspectionMode = LocalInspectionMode.current

    // Resolve every demo's strings once per composition so the filter below
    // stays a pure function (see HomeFilter.kt / HomeFilterTest).
    val searchEntries = demos.map { demo ->
        HomeSearchEntry(
            id = demo.id,
            title = stringResource(demo.titleRes),
            subtitle = stringResource(demo.subtitleRes),
            category = demo.category,
            categoryLabel = stringResource(categoryDisplayNameRes(demo.category)),
            tags = demo.tags,
            order = demo.order,
        )
    }
    val byId = remember(demos) { demos.associateBy { it.id } }
    val visible = remember(searchEntries, selectedCategory, query) {
        filterDemos(searchEntries, selectedCategory, query).mapNotNull { byId[it.id] }
    }
    val searching = query.isNotBlank()
    // A header earns its row only when it separates something. With one category
    // selected the chip already names it, and a lone header above a filtered grid
    // is chrome repeating what the user just tapped.
    val showSections = remember(visible) { visible.map { it.category }.distinct().size > 1 }
    // The run of cards each demo is laid out in, and its place there: a run restarts at
    // every section header, a full-span item. Cards read their grid row out of it at
    // layout time, once [gridCells] knows the column count, to end level (#4144).
    val cardRuns = remember(visible, showSections) { cardRuns(visible, showSections) }

    // Freshness — "New" / "Updated" per card, and the "What's new in 4.x"
    // featured page they feed (#3566). Derived from the demo's own declared
    // `sinceVersion` / `updatedIn` against `buildVersion`, so it expires on
    // its own and nothing here is hand-maintained. See `DemoFreshness.kt`.
    // `buildVersion` is a parameter, defaulting to `BuildConfig.VERSION_NAME`:
    // see its KDoc for why the snapshot tests must be able to pin it (#3666).
    val freshnessById = remember(demos, buildVersion) {
        demos.associate { it.id to it.freshness(buildVersion) }
    }
    val fresh = remember(demos, buildVersion) { freshDemos(demos, buildVersion) }
    val freshVersion = remember(demos, buildVersion) {
        freshnessHeadlineVersion(demos, buildVersion)
    }

    // The featured pager's pages (#3567). The "What's new" page leads when there
    // is anything to say and is simply absent otherwise — an empty "nothing
    // changed this release" page is worse than no page.
    val featuredPages = remember(demos, fresh, freshVersion) {
        buildList {
            FEATURED_DEMO_IDS.mapNotNull { id -> demos.firstOrNull { it.id == id } }
                .forEach { entry ->
                    add(
                        FeaturedPage.Demo(
                            entry = entry,
                            // The flagship page is a window onto the live stage, not a
                            // still (#3948). `preview_hero_model_viewer` stays bundled;
                            // no page draws it.
                            heroArt = null,
                        ),
                    )
                }
        }
    }

    // The "Featured" shelf under the hero: the demos we push, in priority order.
    val featuredShelf = remember(byId) { FEATURED_SECTION_IDS.mapNotNull { byId[it] } }

    // "What's new" — derived from the bundled CHANGELOG.md, never hand-maintained.
    val context = LocalContext.current
    val whatsNew by produceState(initialValue = emptyList<WhatsNewRelease>()) {
        if (!inspectionMode) {
            value = withContext(Dispatchers.IO) { loadWhatsNew(context.assets) }
        }
    }
    // The sheet's "try these" list is the freshness list first — that is what the
    // marker on the cards points at — with any still-InReview demo appended, so
    // the process state keeps its own reason to exist without duplicating a row.
    val inReviewDemos = remember(demos, fresh) {
        fresh + demos.filter { it.status == DemoStatus.InReview && it !in fresh }
    }
    var showWhatsNew by rememberSaveable { mutableStateOf(false) }
    if (showWhatsNew) {
        WhatsNewSheet(
            releases = whatsNew,
            inReviewDemos = inReviewDemos,
            onDemoClick = { id ->
                showWhatsNew = false
                onDemoClick(id)
            },
            onDismiss = { showWhatsNew = false },
        )
    }

    val scrolled by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 }
    }

    // The featured pager's page, owned here so a scroll past the band does not reset
    // it with the item (#3949); the flight is only wanted while its window shows.
    val featuredPagerState = rememberPagerState(pageCount = { featuredPages.size })
    val windowPage = remember(featuredPages) {
        featuredPages.indexOfFirst { it.key == FeaturedPage.Demo.keyFor(HERO_DEMO_ID) }
    }
    val windowShowing = featuredPagerState.currentPage == windowPage ||
        featuredPagerState.isScrollInProgress

    // The live stage renders while any of the band is in the viewport and its window is
    // the page on show. Off screen — or behind another page's opaque card — it parks on
    // its last frame; the drag itself keeps rendering, so the flight scrolls as a scene
    // and not as a photograph of one.
    val heroOnScreen by remember(gridState) {
        derivedStateOf {
            gridState.layoutInfo.visibleItemsInfo.any { it.key == HERO_ITEM_KEY }
        }
    }
    val heroActive = heroOnScreen && !searching && windowShowing

    // The catalogue's one-shot entrance, played on arrival and never again under a thumb.
    val cascade = rememberCascade()
    var cascadeIndex = 0

    val heroHeight = if (expanded) home.heroHeightExpanded else home.heroHeight
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val gridCells = remember(expanded) {
        CountingAdaptiveCells(if (expanded) home.gridMinCellExpanded else home.gridMinCell)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // The stage: composed once, under the grid, alive as long as this screen is
        // (#3948, #3949). It starts above the Scaffold's status-bar inset so the sky
        // runs to the top edge, covers the header band and the featured band, and
        // bleeds a little further before fading into the page. Its vertical travel
        // follows the band's grid item, read at draw time — no recomposition per
        // scrolled pixel — with the sky and the flight lagging a touch behind for depth.
        if (!searching) {
            HomeHeroStage(
                gridState = gridState,
                active = heroActive,
                height = statusBarTop + home.headerHeight + home.heroTopGap + heroHeight +
                    home.heroStageBleed,
                topInset = statusBarTop,
                restTop = home.headerHeight + home.heroTopGap,
                inspectionMode = inspectionMode,
            )
        }

        LazyVerticalGrid(
            state = gridState,
            columns = gridCells,
            contentPadding = PaddingValues(
                start = home.contentPadding,
                end = home.contentPadding,
                bottom = home.gridBottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(home.gridGutter),
            horizontalArrangement = Arrangement.spacedBy(home.gridGutter),
            modifier = Modifier
                .fillMaxSize()
                .testTag(HomeTestTags.GRID),
        ) {
            // The pinned header overlay covers this band; the spacer keeps the
            // hero from starting underneath it.
            item(key = "header-spacer", span = { GridItemSpan(maxLineSpan) }) {
                Spacer(Modifier.height(home.headerHeight + home.heroTopGap - home.gridGutter))
            }
            // While a query is typed the featured pager gives way so the results
            // start under the header and stay visible above the keyboard (#3308).
            if (!searching) item(key = HERO_ITEM_KEY, span = { GridItemSpan(maxLineSpan) }) {
                HomeFeaturedPager(
                    pages = featuredPages,
                    height = heroHeight,
                    onDemoClick = onDemoClick,
                    onWhatsNewClick = { showWhatsNew = true },
                    pagerState = featuredPagerState,
                    modifier = Modifier.testTag(HomeTestTags.HERO),
                )
            }
            // The "Featured" shelf: what we want seen first, right under the hero and
            // above the catalogue, so the flagship samples never wait for a scroll to
            // the section they are filed in. Its cards repeat in their own sections
            // below — the catalogue stays complete — under a distinct item key.
            if (!searching && featuredShelf.isNotEmpty()) {
                item(key = "section-featured", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(
                        title = stringResource(R.string.home_section_featured),
                        testTag = HomeTestTags.FEATURED_SECTION,
                        modifier = Modifier
                            .animateItem()
                            .cascadeIn(cascade.delayFor(cascadeIndex++)),
                    )
                }
                val shelfDelay = cascade.delayFor(cascadeIndex++)
                item(key = "featured-shelf", span = { GridItemSpan(maxLineSpan) }) {
                    FeaturedShelf(
                        demos = featuredShelf,
                        freshness = { freshnessById[it.id] ?: DemoFreshness.None },
                        onDemoClick = onDemoClick,
                        expanded = expanded,
                        modifier = Modifier
                            .animateItem()
                            .bleedHorizontal(home.contentPadding)
                            .cascadeIn(shelfDelay),
                    )
                }
            }
            if (!searching) {
                item(key = "browse-online", span = { GridItemSpan(maxLineSpan) }) {
                    BrowseOnlineModelsCard(
                        onClick = onBrowseOnlineClick,
                        modifier = Modifier
                            .animateItem()
                            .cascadeIn(cascade.delayFor(cascadeIndex++)),
                    )
                }
            }
            item(key = "chips", span = { GridItemSpan(maxLineSpan) }) {
                CategoryChipRow(
                    selected = selectedCategory,
                    onSelect = onCategoryChange,
                    modifier = Modifier.padding(
                        top = home.chipRowTopGap - home.gridGutter,
                        bottom = home.gridTopGap - home.gridGutter,
                    ),
                )
            }
            if (visible.isEmpty() && searching) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptySearchState(query = query, onClear = { onQueryChange("") })
                }
            }
            // Sections. `visible` is already in editorial order, and the registry
            // keeps a category's demos contiguous within it (asserted by
            // DemoRegistryIntegrityTest), so a section boundary is simply "the
            // category changed" — no grouping pass, no re-sort, and the cards keep
            // the exact order the collator emitted.
            var previousCategory: String? = null
            visible.forEach { demo ->
                if (showSections && demo.category != previousCategory) {
                    item(
                        key = "section-${demo.category}",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        SectionHeader(
                            title = stringResource(categoryDisplayNameRes(demo.category)),
                            testTag = HomeTestTags.sectionHeader(demo.category),
                            modifier = Modifier
                                .animateItem()
                                .cascadeIn(cascade.delayFor(cascadeIndex++)),
                        )
                    }
                }
                previousCategory = demo.category
                val cardDelay = cascade.delayFor(cascadeIndex++)
                item(key = "demo-${demo.id}") {
                    DemoMediaCard(
                        demo = demo,
                        onClick = { onDemoClick(demo.id) },
                        freshness = freshnessById[demo.id] ?: DemoFreshness.None,
                        rowPeers = { cardRuns[demo.id]?.rowOf(gridCells.columns).orEmpty() },
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = tween(SceneViewTokens.Duration.fadeMillis),
                                placementSpec = spring(
                                    dampingRatio = SceneViewTokens.Spring.dampingRatio,
                                    stiffness = SceneViewTokens.Spring.stiffness,
                                ),
                            )
                            .cascadeIn(cardDelay),
                    )
                }
            }

        }

        HomeHeader(
            scrolled = scrolled,
            overStage = !scrolled && !searching,
            query = query,
            onQueryChange = onQueryChange,
            // The discreet re-proposal: a "since" list dismissed without
            // acknowledging retreats to a dot on this action and takes the
            // tap until it is marked seen; afterwards the action falls back
            // to the release-notes sheet.
            showWhatsNew = hasUnseenWhatsNew || whatsNew.isNotEmpty(),
            whatsNewBadged = hasUnseenWhatsNew,
            onWhatsNewClick = {
                if (hasUnseenWhatsNew) onWhatsNewSinceClick() else showWhatsNew = true
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * A full-span catalogue section header (#2239).
 *
 * The catalogue shipped as one flat run of cards, which is what made 53 demos
 * unnavigable: nothing told a scrolling thumb where one subject ended and the
 * next began, so "features that belong together" read as scattered even when
 * they were adjacent. This is the landmark — the section's display name, at
 * title weight, spanning the grid.
 *
 * Type and spacing come from [SceneViewTokens.Home]; nothing here hardcodes a
 * colour (see DESIGN.md).
 */
@Composable
private fun SectionHeader(title: String, testTag: String, modifier: Modifier = Modifier) {
    val home = SceneViewTokens.Home
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                top = home.sectionHeaderTopGap - home.gridGutter,
                bottom = home.sectionHeaderBottomGap - home.gridGutter,
            )
            .testTag(testTag),
    )
}

/**
 * Grid key of the featured band. Named because three things agree on it: the item
 * itself, the stage under the grid that follows its travel, and the render gate that
 * parks Filament once it has left.
 */
private const val HERO_ITEM_KEY = "hero"

/**
 * The layer under the grid that carries the sky and the live flight (#3948).
 *
 * Geometry: [height] tall from `-topInset` — the Scaffold pads the status bar and this
 * undoes it, so the sky reaches the top of the display and the status bar rides on it
 * ([HomeHeader] flips its icons to light meanwhile). At rest the featured band's grid
 * item sits at [restTop] below the content's top edge; as the grid scrolls, the stage
 * follows that item one-for-one, read in `graphicsLayer` at draw time so the scroll
 * costs no recomposition. Inside, the sky and the flight lag the band by a fraction
 * ([HERO_PARALLAX]), which reads as depth; the stage clips its bounds so the lag never
 * leaks below the band, where the grid is transparent over the page. When the item has
 * left the viewport the layer is fully transparent and [active] is false, so the loop
 * is parked on its last frame — the frame that shows again, unchanged, on the way back.
 *
 * In inspection mode (goldens) the sky is painted and the flight is not: Roborazzi has
 * no GPU, and a still of a rendered frame would tie the goldens to a driver.
 */
@Composable
private fun HomeHeroStage(
    gridState: LazyGridState,
    active: Boolean,
    height: Dp,
    topInset: Dp,
    restTop: Dp,
    inspectionMode: Boolean,
) {
    val home = SceneViewTokens.Home
    val colors = SceneViewTokens.HomeColor
    val density = LocalDensity.current
    val restTopPx = with(density) { restTop.toPx() }
    val heroTravel: () -> Float? = {
        gridState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == HERO_ITEM_KEY }
            ?.let { (it.offset.y - restTopPx).coerceAtMost(0f) }
    }
    val surface = MaterialTheme.colorScheme.surface
    val bandTop = topInset + restTop
    val bandHeight = height - bandTop - home.heroStageBleed
    val bandFraction = bandHeight.value / (bandHeight + home.heroStageBleed).value
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .offset(y = -topInset)
            .graphicsLayer {
                val travel = heroTravel()
                translationY = travel ?: 0f
                alpha = if (travel == null) 0f else 1f
            }
            .clipToBounds()
            .clearAndSetSemantics { },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = -(heroTravel() ?: 0f) * HERO_PARALLAX
                }
                .background(
                    Brush.verticalGradient(
                        0f to colors.heroSkyTop,
                        home.heroSkyHorizon * 0.45f to colors.heroSkyDusk,
                        home.heroSkyHorizon to colors.heroSkyHorizon,
                        home.heroSkyHorizon + 0.1f to colors.heroSkyGround,
                        1f to colors.heroSkyTop,
                    ),
                )
                // The sun's glow, where the disc sits in the flight: the sky is warmest
                // around it, as a sky is.
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            0f to colors.heroSkyHorizon.copy(alpha = 0.7f),
                            0.45f to colors.heroSkyHorizon.copy(alpha = 0.25f),
                            1f to Color.Transparent,
                            center = Offset(size.width * HERO_SUN_X, size.height * home.heroSkyHorizon * 0.82f),
                            radius = size.width * 0.6f,
                        ),
                    )
                },
        ) {
            if (!inspectionMode) {
                HomeHeroScene(active = active, modifier = Modifier.fillMaxSize())
            }
        }
        // The legibility scrim under the band's copy — the hero's usual gradient, painted
        // here full-bleed rather than by the page, so no card edge cuts the landscape —
        // running on into `surface`: the stage ends on the page, not on an edge.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = bandTop)
                .height(bandHeight + home.heroStageBleed)
                .background(
                    Brush.verticalGradient(
                        home.heroScrimStart * bandFraction to
                            SceneViewTokens.SpatialGalleryColor.stageScrimStart,
                        bandFraction to SceneViewTokens.SpatialGalleryColor.stageScrimEnd,
                        1f to SceneViewTokens.SpatialGalleryColor.stageScrimEnd,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(home.heroStageBleed + home.gridGutter)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(listOf(surface.copy(alpha = 0f), surface)),
                ),
        )
    }
}

/** Where the sun sits across the stage, as a fraction of its width — see `DuskFlight`. */
private const val HERO_SUN_X = 0.31f

/** Fraction of the band's scroll travel the stage's content lags behind. */
private const val HERO_PARALLAX = 0.35f

/** The demo the first featured page opens. */
const val HERO_DEMO_ID = "model-viewer"

/**
 * Editorial order of the featured pager's demo pages (#3567).
 *
 * Short on purpose: a carousel nobody reaches the end of is a list, and the
 * grid below is already the list. [HERO_DEMO_ID] stays first — it is the demo
 * the store listing, the deep link and the app icon all point at — and keeps its
 * bespoke full-span artwork; the rest reuse their own grid captures.
 */
private val FEATURED_DEMO_IDS = listOf(HERO_DEMO_ID, "ar-rerun", "materials", "lighting")

/**
 * The "Featured" shelf right under the hero: the samples we push, in priority
 * order — the flagship replay, then the newest and most recently reworked demos.
 * [HERO_DEMO_ID] is not repeated here; it is the hero itself. Older samples built
 * on earlier models stay in their sections, which are themselves in priority order
 * (see [io.github.sceneview.demo.DEMO_CATEGORIES]).
 */
/**
 * [GridCells.Adaptive] that also remembers how many columns its last measure produced, so
 * a card can find its grid row while it is being laid out. The grid computes the cells
 * before it measures any item in the same pass, so the count is current when read.
 */
internal class CountingAdaptiveCells(private val minSize: Dp) : GridCells {
    private val adaptive = GridCells.Adaptive(minSize)

    /** Columns of the last measure; 1 before the first one. */
    var columns: Int = 1
        private set

    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> =
        with(adaptive) { calculateCrossAxisCellSizes(availableSize, spacing) }.also { columns = it.size }

    override fun equals(other: Any?): Boolean = other is CountingAdaptiveCells && other.minSize == minSize

    override fun hashCode(): Int = minSize.hashCode()
}

/** One demo's run of consecutive cards (between two full-span items) and its index in it. */
internal class CardRun(private val run: List<DemoEntry>, private val index: Int) {
    /** The cards sharing this demo's grid row when the grid has [columns] columns. */
    fun rowOf(columns: Int): List<DemoEntry> {
        val first = index / columns.coerceAtLeast(1) * columns.coerceAtLeast(1)
        return run.subList(first, minOf(run.size, first + columns.coerceAtLeast(1)))
    }
}

/** Splits [visible] into runs at each section header, as the grid lays them out. */
internal fun cardRuns(visible: List<DemoEntry>, showSections: Boolean): Map<String, CardRun> {
    val runs = mutableListOf<MutableList<DemoEntry>>()
    var category: String? = null
    visible.forEach { demo ->
        if (runs.isEmpty() || (showSections && demo.category != category)) runs += mutableListOf<DemoEntry>()
        runs.last() += demo
        category = demo.category
    }
    return runs.flatMap { run -> run.mapIndexed { index, demo -> demo.id to CardRun(run, index) } }.toMap()
}

internal val FEATURED_SECTION_IDS = listOf(
    "ar-rerun", // Rerun AR replay — the flagship, reworked in 4.46
    // Record your room there, then stand it on your table here.
    "ar-splat-room", // "Your room, as a dollhouse" — your own Rerun recording in AR, 4.46
    "splat-preview", // Gaussian-splat viewer — oriented, camera-sorted splats in 4.45
    "animation-physics", // reworked so every control shows its effect, 4.41
    "ar-placement", // tap-to-place, picker shows each model's own thumbnail, 4.39
    "ar-record-playback", // records and replays in place (#3914)
)

@Composable
private fun HomeHeader(
    scrolled: Boolean,
    /** The stage's sky is behind the row: white type, light status-bar icons. */
    overStage: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    showWhatsNew: Boolean,
    whatsNewBadged: Boolean,
    onWhatsNewClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val home = SceneViewTokens.Home
    var searchOpen by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    val motionEnabled = LocalMotionEnabled.current
    val headerSwapSpec = remember(motionEnabled) {
        if (motionEnabled) tween<Float>(SceneViewTokens.Duration.shortMillis) else snap()
    }
    val keyboard = LocalSoftwareKeyboardController.current
    // Status-bar icons follow the row's type: light over the sky, the theme's own
    // otherwise — requested, not written, so leaving the screen or scrolling the sky
    // away cannot race a demo's own request (#3984).
    RequestLightStatusBarIcons(active = overStage)
    val overlay by animateColorAsState(
        targetValue = if (scrolled) {
            MaterialTheme.colorScheme.surface.copy(alpha = SceneViewTokens.HomeColor.headerOverlayAlpha)
        } else {
            Color.Transparent
        },
        animationSpec = tween(SceneViewTokens.Duration.shortMillis),
        label = "headerOverlay",
    )
    // The stage runs up under the status bar (see `HomeHeroStage`), so once the page has
    // scrolled the overlay has to cover that strip too: painted only behind the row, it left
    // the strip showing the stage — the top of the sun disc peeking above a white bar (#4066).
    val statusBarPx = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    // The wordmark row and the search row are not the same height, so the swap used to
    // step the grid underneath it. `animateContentSize` makes the header carry that
    // difference itself, on the same `motion-fade` the content crossfade uses.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    color = overlay,
                    topLeft = Offset(0f, -statusBarPx),
                    size = Size(size.width, size.height + statusBarPx),
                )
            }
            .animateContentSize(animationSpec = motionFade()),
    ) {
        AnimatedContent(
            targetState = searchOpen,
            transitionSpec = {
                fadeIn(headerSwapSpec) togetherWith fadeOut(headerSwapSpec)
            },
            label = "headerContent",
        ) { open ->
            if (open) {
                SearchRow(
                    query = query,
                    onQueryChange = onQueryChange,
                    // One tap closes search: query cleared, field collapsed, keyboard
                    // hidden (#3308). Clearing the text alone is the field's own
                    // trailing icon.
                    onClose = {
                        keyboard?.hide()
                        onQueryChange("")
                        searchOpen = false
                    },
                )
            } else {
                TitleRow(
                    showWhatsNew = showWhatsNew,
                    whatsNewBadged = whatsNewBadged,
                    overStage = overStage,
                    onWhatsNewClick = onWhatsNewClick,
                    onSearchClick = { searchOpen = true },
                )
            }
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(home.cardOutlineWidth)
                .alpha(if (scrolled) 1f else 0f)
                .background(outlineSubtle()),
        )
    }
}

@Composable
private fun TitleRow(
    showWhatsNew: Boolean,
    whatsNewBadged: Boolean,
    overStage: Boolean,
    onWhatsNewClick: () -> Unit,
    onSearchClick: () -> Unit,
) {
    val home = SceneViewTokens.Home
    // Over the stage the row uses the hero's own fixed whites (DESIGN.md: the hero
    // stays dark in both themes); on the page it uses the scheme's roles.
    val titleColor by animateColorAsState(
        targetValue = if (overStage) SceneViewTokens.HomeColor.heroTitle else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(SceneViewTokens.Duration.shortMillis),
        label = "headerTitle",
    )
    val iconTint by animateColorAsState(
        targetValue = if (overStage) {
            SceneViewTokens.HomeColor.heroSubtitle
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(SceneViewTokens.Duration.shortMillis),
        label = "headerIcons",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(home.headerHeight)
            .padding(horizontal = home.contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_sceneview_mark),
            contentDescription = null,
            modifier = Modifier.size(home.markSize),
        )
        Spacer(Modifier.width(home.markGap))
        Text(
            text = stringResource(R.string.app_name),
            style = SceneViewTokens.Type.title,
            color = titleColor,
        )
        Spacer(Modifier.weight(1f))
        if (showWhatsNew) {
            IconButton(onClick = onWhatsNewClick) {
                val icon = @Composable {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = stringResource(
                            if (whatsNewBadged) R.string.whats_new_since_action else R.string.home_whats_new,
                        ),
                        tint = iconTint,
                    )
                }
                if (whatsNewBadged) BadgedBox(badge = { Badge() }) { icon() } else icon()
            }
        }
        IconButton(onClick = onSearchClick, modifier = Modifier.offset(x = 12.dp)) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = stringResource(R.string.home_search),
                tint = iconTint,
            )
        }
    }
}

@Composable
private fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val home = SceneViewTokens.Home
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(home.headerHeight)
            .padding(start = home.contentPadding, end = home.contentPadding - 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .height(home.searchFieldHeight)
                .focusRequester(focus)
                .testTag(HomeTestTags.SEARCH_FIELD),
            placeholder = {
                Text(
                    stringResource(R.string.home_search_placeholder),
                    style = SceneViewTokens.Type.body,
                )
            },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isEmpty()) null else {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Filled.Cancel,
                            contentDescription = stringResource(R.string.home_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            singleLine = true,
            textStyle = SceneViewTokens.Type.body,
            shape = RoundedCornerShape(SceneViewTokens.Radius.full),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedBorderColor = outlineSubtle(),
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        )
        IconButton(onClick = onClose, modifier = Modifier.testTag(HomeTestTags.SEARCH_CLOSE)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.home_search_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** `null` = All. Order is the chip order. */
/**
 * The filter chips, in [io.github.sceneview.demo.DEMO_CATEGORIES] order after the
 * leading "All". Short labels, because the chip row is one horizontal scroll and
 * the long form is already carried by the section header the chip filters down to.
 * [io.github.sceneview.demo.DemoRegistryIntegrityTest] asserts this list covers
 * every registered category, so a new category can never ship without a chip.
 */
private val CHIP_CATEGORIES: List<Pair<String?, Int>> = listOf(
    null to R.string.category_short_all,
    DemoCategory.VIEW_3D to R.string.category_short_view_3d,
    DemoCategory.PLACE_AR to R.string.category_short_place_ar,
    DemoCategory.DEV_TOOLS to R.string.category_short_dev_tools,
    DemoCategory.CREATE to R.string.category_short_create,
    DemoCategory.UNDERSTAND to R.string.category_short_understand,
)

/** The categories [CHIP_CATEGORIES] offers, minus the leading "All". */
internal val CHIP_CATEGORY_KEYS: List<String> = CHIP_CATEGORIES.mapNotNull { it.first }

@Composable
private fun CategoryChipRow(
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val home = SceneViewTokens.Home
    // The row bleeds out of the grid's side inset and carries it as content
    // padding instead, so chips scroll to the screen edge and the last one keeps
    // the same gap on the right as the first one on the left (#3308).
    LazyRow(
        modifier = modifier.bleedHorizontal(home.contentPadding),
        contentPadding = PaddingValues(horizontal = home.contentPadding),
        horizontalArrangement = Arrangement.spacedBy(home.chipGap),
    ) {
        rowItems(CHIP_CATEGORIES, key = { it.first ?: "all" }) { (category, labelRes) ->
            CategoryChip(
                label = stringResource(labelRes),
                selected = category == selected,
                onClick = { onSelect(category) },
            )
        }
    }
}

/**
 * Widens the node by [inset] on each side and shifts it so it lines up with
 * the parent's outer edge — an edge-to-edge row inside a padded column.
 */
internal fun Modifier.bleedHorizontal(inset: Dp): Modifier = layout { measurable, constraints ->
    val px = inset.roundToPx()
    val width = constraints.maxWidth + 2 * px
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-px, 0) }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val dark = isSystemInDarkTheme()
    val colors = SceneViewTokens.HomeColor
    val home = SceneViewTokens.Home
    val background = when {
        selected && dark -> colors.chipSelectedBackgroundDark
        selected -> colors.chipSelectedBackgroundLight
        dark -> colors.chipBackgroundDark
        else -> colors.chipBackgroundLight
    }
    val content = when {
        selected && dark -> colors.chipSelectedTextDark
        selected -> colors.chipSelectedTextLight
        dark -> colors.chipTextDark
        else -> colors.chipTextLight
    }
    Surface(
        modifier = Modifier
            .height(home.chipRowHeight)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Tab,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(SceneViewTokens.Radius.full),
        color = background,
        contentColor = content,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = home.chipPaddingHorizontal),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = SceneViewTokens.Type.body,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = content,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EmptySearchState(query: String, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SceneViewTokens.Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
    ) {
        Text(
            text = stringResource(R.string.home_empty_query, query),
            style = SceneViewTokens.Type.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClear) {
            Text(stringResource(R.string.home_clear))
        }
    }
}

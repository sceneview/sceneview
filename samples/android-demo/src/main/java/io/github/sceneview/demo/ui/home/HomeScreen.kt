@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.ui.home

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
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
import androidx.compose.ui.res.pluralStringResource
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
import io.github.sceneview.demo.freshnessWindowStart
import io.github.sceneview.demo.telemetry.SearchTerm
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.motionFade
import io.github.sceneview.demo.whatsnew.WhatsNewRelease
import io.github.sceneview.demo.whatsnew.WhatsNewSheet
import io.github.sceneview.demo.whatsnew.rememberReturningSheetState
import io.github.sceneview.demo.whatsnew.loadWhatsNew
import io.github.sceneview.demo.ui.ContainerRole
import io.github.sceneview.demo.ui.DemoContainerTransform
import io.github.sceneview.demo.ui.demoContainerTransform
import io.github.sceneview.demo.ui.cascadeIn
import io.github.sceneview.demo.ui.pressScale
import io.github.sceneview.demo.ui.rememberCascade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /** Test tag of the release highlights below discovery. */
    const val WHATS_NEW_ROW = "home-whats-new-row"

    /** Test tag of the Try next group header. */
    const val FEATURED_SECTION = "home-section-featured"
    const val BROWSE_SECTION = "home-section-browse"
    const val CATEGORY_CHIPS = "home-category-chips"

    fun outcome(demoId: String): String = "home-outcome-$demoId"
    fun featured(demoId: String): String = "home-featured-$demoId"

    /** Test tag of the section chip that scrolls to [category]. */
    fun sectionChip(category: String): String = "home-chip-" + category.lowercase().replace(Regex("[^a-z0-9]+"), "-")

    /** Test tag of one demo's row in the home list. */
    fun row(demoId: String): String = "home-row-$demoId"
}

/**
 * Compact live scene, three outcome shortcuts, Try next, then the complete catalogue.
 * Section chips scroll to permanent category homes; search returns every matching demo.
 * Release highlights and online browsing follow discovery.
 *
 * The live stage stays outside the lazy grid so scrolling cannot recreate its renderer.
 * It only renders while visible. Text and actions sit below its fixed-height window,
 * in wrapping Material list items. Query and scroll state survive returning from a demo.
 */
@Composable
fun HomeScreen(
    demos: List<DemoEntry>,
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
     * A non-blank [query] the user settled on, with how many samples it shows — fired once the
     * field has been still for [SearchTerm.SETTLE_MS], or straight away when a sample is tapped
     * before that. Feeds the `search` event; defaulted so previews and snapshots ignore it.
     */
    onSearchSettled: (query: String, resultCount: Int) -> Unit = { _, _ -> },
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
    val visible = remember(searchEntries, query) {
        filterDemos(searchEntries, query).mapNotNull { byId[it.id] }
    }
    val searching = query.isNotBlank()
    val currentOnSearchSettled by rememberUpdatedState(onSearchSettled)
    val currentVisibleCount by rememberUpdatedState(visible.size)
    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        delay(SearchTerm.SETTLE_MS)
        currentOnSearchSettled(query, currentVisibleCount)
    }
    // A sample tapped from a search counts that search even if the field never sat still.
    val openDemo: (String) -> Unit = { id ->
        if (searching) currentOnSearchSettled(query, visible.size)
        onDemoClick(id)
    }
    // A catalogue card grows into its demo (container transform, `motion-container`): the
    // tap arms the morph for that one id before navigating, and the card carries the
    // shared bounds the demo screen is matched against.
    val openDemoFromCard: (String) -> Unit = { id ->
        DemoContainerTransform.armedId = id
        openDemo(id)
    }
    // A header earns its row only when it separates something: a search whose results
    // all sit in one category does not get a lone header above them.
    val showSections = remember(visible) { visible.map { it.category }.distinct().size > 1 }

    // Freshness — "New" / "Updated" per card and the release highlights below discovery.
    // Derived from the demo's own declared
    // `addedIn` / `updatedIn` against `buildVersion`, so it expires on
    // its own and nothing here is hand-maintained. See `DemoFreshness.kt`.
    // `buildVersion` is a parameter, defaulting to `BuildConfig.VERSION_NAME`:
    // see its KDoc for why the snapshot tests must be able to pin it (#3666).
    val freshnessById = remember(demos, buildVersion) {
        demos.associate { it.id to it.freshness(buildVersion) }
    }
    val fresh = remember(demos, buildVersion) { freshDemos(demos, buildVersion) }

    val top = remember(byId, fresh) {
        homeTopSections(
            outcomes = HOME_OUTCOMES.map { it.demoId }.filter { it in byId },
            featured = FEATURED_IDS.filter { it in byId },
            fresh = fresh.map { it.id },
        )
    }
    val outcomes = remember(top) { HOME_OUTCOMES.filter { it.demoId in top.outcomes } }
    val featuredShelf = remember(top, byId) { top.featured.mapNotNull { byId[it] } }
    // Shortcuts never remove the same demo from its permanent catalogue category.
    val rows = remember(visible, top) {
        val listed = top.catalogue(visible.map { it.id }).toSet()
        visible.filter { it.id in listed }
    }
    // The fresh demos nothing above shows: the "What's new" row leads with one of them,
    // a new demo over an updated one.
    val whatsNewLead = remember(top, freshnessById) {
        top.whatsNew.firstOrNull { freshnessById[it] == DemoFreshness.New }
            ?: top.whatsNew.firstOrNull()
    }

    // "What's new" — derived from the bundled CHANGELOG.md, never hand-maintained.
    val context = LocalContext.current
    // Null until read: the sheet reopening after a sample waits for it rather than
    // opening blank and filling a frame later.
    val whatsNew by produceState<List<WhatsNewRelease>?>(initialValue = null) {
        value = if (inspectionMode) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { loadWhatsNew(context.assets) }
        }
    }
    // The sheet's "try these" list is the freshness list first — that is what the
    // marker on the cards points at — with any still-InReview demo appended, so
    // the process state keeps its own reason to exist without duplicating a row.
    val inReviewDemos = remember(demos, fresh) {
        fresh + demos.filter { it.status == DemoStatus.InReview && it !in fresh }
    }
    // Back from a sample opened in the sheet lands on the sheet again, not on a bare Home.
    val whatsNewSheet = rememberReturningSheetState(contentReady = whatsNew != null)
    if (whatsNewSheet.isShown) {
        WhatsNewSheet(
            releases = whatsNew.orEmpty(),
            inReviewDemos = inReviewDemos,
            onDemoClick = { id ->
                whatsNewSheet.leaveForSample()
                onDemoClick(id)
            },
            onDismiss = { whatsNewSheet.dismiss() },
        )
    }

    val scrolled by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 }
    }

    // Park the persistent stage when its compact window leaves the viewport.
    val heroOnScreen by remember(gridState) {
        derivedStateOf {
            gridState.layoutInfo.visibleItemsInfo.any { it.key == HERO_ITEM_KEY }
        }
    }
    val heroActive = heroOnScreen && !searching

    // The catalogue's one-shot entrance, played on arrival and never again under a thumb.
    val cascade = rememberCascade()
    var cascadeIndex = 0

    val heroHeight = home.discoverySceneHeight
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val columns = homeListColumns(LocalConfiguration.current.screenWidthDp)

    // Where each section chip scrolls to. The grid items above the catalogue are counted
    // here, in the order the grid below declares them — a full-span item added above the
    // chips must be counted here too, or every chip lands one item off.
    val chipsShown = !searching && rows.map { it.category }.distinct().size > 1
    val headItemCount = 1 + // header spacer
        (if (searching) 0 else 1) + // hero
        (if (!searching && outcomes.isNotEmpty()) 1 else 0) +
        (if (!searching && featuredShelf.isNotEmpty()) 1 + featuredShelf.size else 0) +
        (if (chipsShown) 1 else 0)
    val sectionStarts = remember(rows, headItemCount, showSections) {
        homeSectionStarts(headItemCount, rows.map { it.category }, showSections)
    }
    val scope = rememberCoroutineScope()
    // The pinned header covers the grid's top band: a section lands just under it.
    val headerHeightPx = with(LocalDensity.current) { home.headerHeight.roundToPx() }

    // The header's glass: the page under it, recorded once per frame and drawn again,
    // blurred, behind the header (API 31+, where `RenderEffect` exists).
    val backdrop = rememberGraphicsLayer()
    val glass = HEADER_GLASS_SUPPORTED

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (glass) {
                        Modifier.drawWithContent {
                            backdrop.record { this@drawWithContent.drawContent() }
                            drawLayer(backdrop)
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
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
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(
                    start = home.contentPadding,
                    end = home.contentPadding,
                    bottom = home.gridBottomInset,
                ),
                // Rows of one group sit `home-row-gap` apart, so a group reads as one grey
                // block; every full-span item below carries its own spacing minus this gap.
                verticalArrangement = Arrangement.spacedBy(home.rowGap),
                horizontalArrangement = Arrangement.spacedBy(home.rowGap),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(HomeTestTags.GRID),
            ) {
                // The pinned header overlay covers this band; the spacer keeps the
                // hero from starting underneath it.
                item(key = "header-spacer", span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(Modifier.height(home.headerHeight + home.heroTopGap - home.rowGap))
                }
                if (!searching) item(key = HERO_ITEM_KEY, span = { GridItemSpan(maxLineSpan) }) {
                    // Only the live scene is fixed-height. Every action below can grow.
                    Spacer(Modifier.height(heroHeight).testTag(HomeTestTags.HERO))
                }
                if (!searching && outcomes.isNotEmpty()) {
                    item(key = "outcomes", span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            outcomes.forEach { outcome ->
                                DiscoveryRow(
                                    label = stringResource(outcome.labelRes),
                                    demo = byId.getValue(outcome.demoId),
                                    onClick = { openDemo(outcome.demoId) },
                                    modifier = Modifier.testTag(HomeTestTags.outcome(outcome.demoId)),
                                )
                            }
                        }
                    }
                }
                if (!searching && featuredShelf.isNotEmpty()) {
                    item(key = "section-featured", span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader(
                            title = stringResource(R.string.home_section_featured),
                            testTag = HomeTestTags.FEATURED_SECTION,
                            topGap = SceneViewTokens.Space.sm,
                        )
                    }
                    featuredShelf.forEach { demo ->
                        item(key = "featured-${demo.id}", span = { GridItemSpan(maxLineSpan) }) {
                            DiscoveryRow(
                                label = stringResource(demo.titleRes),
                                demo = demo,
                                onClick = { openDemo(demo.id) },
                                modifier = Modifier.testTag(HomeTestTags.featured(demo.id)),
                            )
                        }
                    }
                }
                // The section chips: a table of contents, not a filter. A tap scrolls the
                // catalogue so that section's header sits under the pinned header; the
                // whole catalogue stays listed. Gone while searching — results are short,
                // and the query already narrows them.
                if (chipsShown) {
                    item(key = "chips", span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SectionHeader(
                                title = stringResource(R.string.home_browse_demos),
                                testTag = HomeTestTags.BROWSE_SECTION,
                            )
                            SectionChipRow(
                                categories = sectionStarts.keys.toList(),
                                onSelect = { category ->
                                    val index = sectionStarts[category] ?: return@SectionChipRow
                                    scope.launch {
                                        gridState.animateScrollToItem(index, scrollOffset = -headerHeightPx)
                                    }
                                },
                                modifier = Modifier.padding(bottom = home.gridTopGap - home.rowGap),
                            )
                        }
                    }
                }
                if (visible.isEmpty() && searching) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        EmptySearchState(query = query, onClear = { onQueryChange("") })
                    }
                }
                // Sections. `rows` is already in editorial order, and the registry
                // keeps a category's demos contiguous within it (asserted by
                // DemoRegistryIntegrityTest), so a section boundary is simply "the
                // category changed" — no grouping pass, no re-sort, and the cards keep
                // the exact order the collator emitted.
                var previousCategory: String? = null
                rows.forEach { demo ->
                    if (showSections && demo.category != previousCategory) {
                        // The first header sits right under the chip row, which already
                        // carries its own gap.
                        val first = previousCategory == null
                        item(
                            key = "section-${demo.category}",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            SectionHeader(
                                title = stringResource(categoryDisplayNameRes(demo.category)),
                                testTag = HomeTestTags.sectionHeader(demo.category),
                                topGap = if (first) SceneViewTokens.Space.sm else home.sectionHeaderTopGap,
                                modifier = Modifier
                                    .animateItem()
                                    .cascadeIn(cascade.delayFor(cascadeIndex++)),
                            )
                        }
                    }
                    previousCategory = demo.category
                    val cardDelay = cascade.delayFor(cascadeIndex++)
                    item(key = "demo-${demo.id}") {
                        DemoListRow(
                            demo = demo,
                            onClick = { openDemoFromCard(demo.id) },
                            style = HomeRowStyle.Fused,
                            freshness = freshnessById[demo.id] ?: DemoFreshness.None,
                            modifier = Modifier
                                .animateItem(
                                    fadeInSpec = tween(SceneViewTokens.Duration.fadeMillis),
                                    placementSpec = spring(
                                        dampingRatio = SceneViewTokens.Spring.dampingRatio,
                                        stiffness = SceneViewTokens.Spring.stiffness,
                                    ),
                                )
                                .cascadeIn(cardDelay)
                                .demoContainerTransform(demo.id, ContainerRole.Card),
                        )
                    }
                }
                if (!searching) {
                    item(key = "browse-online", span = { GridItemSpan(maxLineSpan) }) {
                        BrowseOnlineRow(
                            onClick = onBrowseOnlineClick,
                            modifier = Modifier.padding(top = home.groupGap - home.rowGap),
                        )
                    }
                    if (whatsNewLead != null) {
                        item(key = WHATS_NEW_ITEM_KEY, span = { GridItemSpan(maxLineSpan) }) {
                            WhatsNewRow(
                                subtitle = pluralStringResource(
                                    R.plurals.home_whats_new_subtitle,
                                    fresh.size,
                                    fresh.size,
                                    freshnessWindowStart(buildVersion),
                                ),
                                leadDemoId = whatsNewLead,
                                onClick = { whatsNewSheet.open() },
                                modifier = Modifier
                                    .testTag(HomeTestTags.WHATS_NEW_ROW)
                                    .padding(top = home.groupGap - home.rowGap),
                            )
                        }
                    } else if (hasUnseenWhatsNew || !whatsNew.isNullOrEmpty()) {
                        item(key = WHATS_NEW_ITEM_KEY, span = { GridItemSpan(maxLineSpan) }) {
                            TextButton(
                                onClick = { whatsNewSheet.open() },
                                modifier = Modifier.fillMaxWidth().testTag(HomeTestTags.WHATS_NEW_ROW),
                            ) {
                                Text(stringResource(R.string.home_whats_new_title))
                            }
                        }
                    }
                }
            }
        }

        if (glass) {
            HeaderGlass(
                backdrop = backdrop,
                visible = scrolled,
                height = statusBarTop + home.headerHeight + home.cardOutlineWidth,
                topInset = statusBarTop,
            )
        }

        HomeHeader(
            glass = glass,
            scrolled = scrolled,
            overStage = !scrolled && !searching,
            query = query,
            onQueryChange = onQueryChange,
            // The discreet re-proposal: a "since" list dismissed without
            // acknowledging retreats to a dot on this action and takes the
            // tap until it is marked seen; afterwards the action falls back
            // to the release-notes sheet.
            showWhatsNew = hasUnseenWhatsNew || !whatsNew.isNullOrEmpty(),
            whatsNewBadged = hasUnseenWhatsNew,
            onWhatsNewClick = {
                if (hasUnseenWhatsNew) onWhatsNewSinceClick() else whatsNewSheet.open()
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * A discovery shortcut: icon, then a label that wraps instead of clipping at large font
 * scales. A plain row rather than `ListItem`, whose headline slot clipped at 130 % text.
 */
@Composable
private fun DiscoveryRow(
    label: String,
    demo: DemoEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .padding(horizontal = SceneViewTokens.Space.md, vertical = SceneViewTokens.Space.sm),
    ) {
        Icon(demo.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
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
private fun SectionHeader(
    title: String,
    testTag: String,
    modifier: Modifier = Modifier,
    topGap: Dp = SceneViewTokens.Home.sectionHeaderTopGap,
) {
    val home = SceneViewTokens.Home
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                // The first header's gap (`space-sm`) is under a row gap: never negative.
                top = (topGap - home.rowGap).coerceAtLeast(0.dp),
                bottom = (home.sectionHeaderBottomGap - home.rowGap).coerceAtLeast(0.dp),
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

/** Grid key of the release highlights below discovery. */
private const val WHATS_NEW_ITEM_KEY = "whats-new"

/**
 * The layer under the grid that carries the sky and the night circuit (#3948).
 *
 * Geometry: [height] tall from `-topInset` — the Scaffold pads the status bar and this
 * undoes it, so the sky reaches the top of the display and the status bar rides on it
 * ([HomeHeader] flips its icons to light meanwhile). At rest the featured band's grid
 * item sits at [restTop] below the content's top edge; as the grid scrolls, the stage
 * follows that item one-for-one, read in `graphicsLayer` at draw time so the scroll
 * costs no recomposition. Inside, the sky and the circuit lag the band by a fraction
 * ([HERO_PARALLAX]), which reads as depth; the stage clips its bounds so the lag never
 * leaks below the band, where the grid is transparent over the page. When the item has
 * left the viewport the layer is fully transparent and [active] is false, so the loop
 * is parked on its last frame — the frame that shows again, unchanged, on the way back.
 *
 * In inspection mode the sky is painted and the circuit is not: `@Preview` and Robolectric
 * have no GPU to render it with.
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
    val bandHeightPx = with(density) { bandHeight.toPx() }.coerceAtLeast(1f)
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
                ),
        ) {
            if (!inspectionMode) {
                HomeHeroScene(
                    active = active,
                    modifier = Modifier.fillMaxSize(),
                    // The scroll is the camera's: as the band leaves, the camera turns
                    // further round the car and pushes in.
                    scroll = {
                        val travel = -(heroTravel() ?: 0f)
                        (travel / bandHeightPx).coerceIn(0f, 1f)
                    },
                    // Actions are below the stage; reserve only its header and bleed.
                    contentPadding = PaddingValues(
                        top = bandTop,
                        bottom = home.heroStageBleed,
                    ),
                )
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

/** Fraction of the band's scroll travel the stage's content lags behind. */
private const val HERO_PARALLAX = 0.35f

/** Destination of the first discovery action beneath the compact live scene. */
const val HERO_DEMO_ID = "model-viewer"

@Composable
private fun HomeHeader(
    /** A blurred copy of the page is drawn under the header: its fill is `header-glass`. */
    glass: Boolean,
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
            val alpha = if (glass) headerGlassAlpha() else SceneViewTokens.HomeColor.headerOverlayAlpha
            MaterialTheme.colorScheme.surface.copy(alpha = alpha)
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

/**
 * The section chips' short labels, one per category. Short, because the chip row is one
 * horizontal scroll and the long form is the section header the chip scrolls to.
 * [io.github.sceneview.demo.DemoRegistryIntegrityTest] asserts this map covers every
 * registered category, so a new category can never ship without a chip.
 */
private val CHIP_LABELS: Map<String, Int> = mapOf(
    DemoCategory.VIEW_3D to R.string.category_short_view_3d,
    DemoCategory.CREATE to R.string.category_short_create,
    DemoCategory.PLAY to R.string.category_short_play,
    DemoCategory.PLACE_AR to R.string.category_short_place_ar,
    DemoCategory.UNDERSTAND to R.string.category_short_understand,
    DemoCategory.DEV_TOOLS to R.string.category_short_dev_tools,
)

/** The categories a section chip exists for. */
internal val CHIP_CATEGORY_KEYS: List<String> = CHIP_LABELS.keys.toList()

/**
 * One chip per catalogue section, in the order the sections appear. Nothing is ever
 * selected: a chip is a shortcut to a place in the list, and the list stays whole.
 */
@Composable
private fun SectionChipRow(
    categories: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = categories.mapNotNull { category -> CHIP_LABELS[category]?.let { category to it } }
    val home = SceneViewTokens.Home
    // The row bleeds out of the grid's side inset and carries it as content
    // padding instead, so chips scroll to the screen edge and the last one keeps
    // the same gap on the right as the first one on the left (#3308).
    LazyRow(
        modifier = modifier.bleedHorizontal(home.contentPadding).testTag(HomeTestTags.CATEGORY_CHIPS),
        contentPadding = PaddingValues(horizontal = home.contentPadding),
        horizontalArrangement = Arrangement.spacedBy(home.chipGap),
    ) {
        rowItems(chips, key = { it.first }) { (category, labelRes) ->
            SectionChip(
                label = stringResource(labelRes),
                onClick = { onSelect(category) },
                modifier = Modifier.testTag(HomeTestTags.sectionChip(category)),
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
private fun SectionChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val dark = isSystemInDarkTheme()
    val colors = SceneViewTokens.HomeColor
    val home = SceneViewTokens.Home
    val background = if (dark) colors.chipBackgroundDark else colors.chipBackgroundLight
    val content = if (dark) colors.chipTextDark else colors.chipTextLight
    Surface(
        modifier = modifier
            .height(home.chipRowHeight)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
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

/**
 * The header's backdrop: the page under the header — list, and the stage while it is
 * still there — drawn again from [backdrop] and blurred `header-glass-blur`, from the top
 * of the display to the header's hairline. The header's own `header-glass` fill sits on it.
 */
@Composable
private fun HeaderGlass(
    backdrop: GraphicsLayer,
    visible: Boolean,
    height: Dp,
    topInset: Dp,
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(SceneViewTokens.Duration.shortMillis),
        label = "headerGlass",
    )
    val density = LocalDensity.current
    val blurPx = with(density) { SceneViewTokens.Home.headerGlassBlur.toPx() }
    val topInsetPx = with(density) { topInset.toPx() }
    // The grid is transparent over the page, so the recorded copy is too: the page goes
    // under it first, or the sharp list would show through the blurred one's thin spots.
    val page = MaterialTheme.colorScheme.surface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .offset(y = -topInset)
            .graphicsLayer {
                this.alpha = alpha
                clip = true
                renderEffect = if (alpha > 0f) BlurEffect(blurPx, blurPx, TileMode.Clamp) else null
            }
            .drawBehind {
                if (alpha > 0f) {
                    drawRect(page)
                    translate(top = topInsetPx) { drawLayer(backdrop) }
                }
            }
            .clearAndSetSemantics { },
    )
}

/** `header-glass` for the current scheme. */
@Composable
private fun headerGlassAlpha(): Float =
    if (isSystemInDarkTheme()) SceneViewTokens.HomeColor.headerGlassAlphaDark
    else SceneViewTokens.HomeColor.headerGlassAlphaLight

/** `RenderEffect` blur exists from API 31; below it the header stays opaque `surface`. */
private val HEADER_GLASS_SUPPORTED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

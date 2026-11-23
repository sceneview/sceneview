@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.layout.layout
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
import io.github.sceneview.demo.whatsnew.WhatsNewRelease
import io.github.sceneview.demo.whatsnew.WhatsNewSheet
import io.github.sceneview.demo.whatsnew.rememberReturningSheetState
import io.github.sceneview.demo.whatsnew.loadWhatsNew
import io.github.sceneview.demo.ui.ContainerRole
import io.github.sceneview.demo.ui.DemoContainerTransform
import io.github.sceneview.demo.ui.demoContainerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tags for the home screen. */
object HomeTestTags {
    const val ROOT = "home-root"
    const val GRID = "home-grid"
    const val SEARCH_ENTRY = "home-search-entry"
    const val OUTCOMES = "home-outcomes"
    const val SHEET_HANDLE = "home-sheet-handle"
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

/** Full-window night circuit under a persistent Material sheet and the catalogue. */
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
    bottomBarHeight: Dp = 0.dp,
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
    val openDemoFromCard: (String, String) -> Unit = { id, source ->
        DemoContainerTransform.armedSource = source
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

    var searchOpen by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    val sheetState = rememberStandardBottomSheetState(skipHiddenState = true)
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val scope = rememberCoroutineScope()
    val motionEnabled = LocalMotionEnabled.current
    var entrancePlayed by rememberSaveable { mutableStateOf(false) }
    val entrance = remember { Animatable(if (entrancePlayed || !motionEnabled) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!entrancePlayed) {
            entrancePlayed = true
            if (motionEnabled) entrance.animateTo(1f, tween(home.sheetEntranceMillis))
            else entrance.snapTo(1f)
        }
    }
    val openSearch: () -> Unit = {
        searchOpen = true
        scope.launch { sheetState.expand() }
    }
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            sheetState.expand()
            gridState.scrollToItem(0)
        }
    }
    BackHandler(enabled = searchOpen) {
        searchOpen = false
        onQueryChange("")
    }
    val chipsShown = !searchOpen && rows.map { it.category }.distinct().size > 1
    val headItemCount = (if (searchOpen) 0 else 1) + // resting content
        (if (!searchOpen && featuredShelf.isNotEmpty()) 1 + featuredShelf.size else 0) +
        (if (chipsShown) 1 else 0)
    val sectionStarts = remember(rows, headItemCount, showSections) {
        homeSectionStarts(headItemCount, rows.map { it.category }, showSections)
    }

    BoxWithConstraints(modifier.fillMaxSize().testTag(HomeTestTags.ROOT)) {
        val density = LocalDensity.current
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val headerBand = topInset + home.headerHeight
        val peekHeight = homeSheetPeekHeight(maxHeight.value, bottomBarHeight.value).dp
        val restOffsetPx = with(density) {
            (maxHeight - bottomBarHeight - headerBand - peekHeight).toPx()
        }
        // Read offset in the frame loop. Only the covered/uncovered boundary recomposes Home.
        val cameraProgress: () -> Float = {
            homeSheetCameraProgress(
                runCatching { sheetState.requireOffset() }.getOrDefault(restOffsetPx),
                restOffsetPx,
            )
        }
        val covered by remember(sheetState, restOffsetPx) {
            derivedStateOf { cameraProgress() >= 0.999f }
        }
        val heroActive = !covered && !searchOpen && !searching && !whatsNewSheet.isShown
        HomeHeroStage(
            active = heroActive,
            scroll = cameraProgress,
            contentPadding = PaddingValues(
                top = headerBand,
                bottom = maxHeight * (1f - home.sheetRestFraction),
            ),
            inspectionMode = inspectionMode,
        )
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = peekHeight,
            sheetMaxWidth = home.sheetMaxWidth,
            sheetDragHandle = { BottomSheetDefaults.DragHandle(Modifier.testTag(HomeTestTags.SHEET_HANDLE)) },
            sheetShape = RoundedCornerShape(topStart = home.sheetCorner, topEnd = home.sheetCorner),
            sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            sheetContentColor = MaterialTheme.colorScheme.onSurface,
            containerColor = Color.Transparent,
            // The one-shot lift belongs to the sheet, never to the live stage.
            modifier = Modifier
                .fillMaxSize()
                .padding(top = headerBand, bottom = bottomBarHeight)
                .graphicsLayer { translationY = (1f - entrance.value) * peekHeight.toPx() },
            sheetContent = {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(1),
                    contentPadding = PaddingValues(
                        start = home.contentPadding,
                        end = home.contentPadding,
                        bottom = home.gridBottomInset,
                    ),
                    verticalArrangement = Arrangement.spacedBy(home.rowGap),
                    modifier = Modifier.fillMaxSize().testTag(HomeTestTags.GRID),
                ) {
                    if (!searchOpen) {
                        item(key = "discovery", span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.home_hero_title), style = MaterialTheme.typography.titleLarge)
                                        Text(
                                            stringResource(R.string.home_hero_live),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    FilledTonalButton(onClick = { openDemo(HERO_DEMO_ID) }) {
                                        Text(stringResource(R.string.home_hero_open))
                                    }
                                }
                                HomeOutcomeCarousel(
                                    outcomes = outcomes,
                                    entranceComplete = entrance.value == 1f,
                                    onDemoClick = { openDemoFromCard(it, "outcome") },
                                )
                                SearchBar(
                                    inputField = {
                                        SearchBarDefaults.InputField(
                                            query = "",
                                            onQueryChange = {},
                                            onSearch = { openSearch() },
                                            expanded = false,
                                            onExpandedChange = { if (it) openSearch() },
                                            placeholder = { Text(stringResource(R.string.home_search_placeholder)) },
                                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                        )
                                    },
                                    expanded = false,
                                    onExpandedChange = { if (it) openSearch() },
                                    windowInsets = WindowInsets(0, 0, 0, 0),
                                    modifier = Modifier.fillMaxWidth().testTag(HomeTestTags.SEARCH_ENTRY),
                                ) { }
                            }
                        }
                    }
                    if (!searchOpen && featuredShelf.isNotEmpty()) {
                        item(key = "section-featured", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                title = stringResource(R.string.home_section_featured),
                                testTag = HomeTestTags.FEATURED_SECTION,
                                topGap = SceneViewTokens.Space.sm,
                            )
                        }
                        featuredShelf.forEach { demo ->
                            item(key = "featured-${demo.id}", span = { GridItemSpan(maxLineSpan) }) {
                                DemoListRow(
                                    demo = demo,
                                    onClick = { openDemoFromCard(demo.id, "featured") },
                                    testTag = HomeTestTags.featured(demo.id),
                                    modifier = Modifier
                                        .demoContainerTransform(demo.id, ContainerRole.Card, source = "featured"),
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
                                            sheetState.expand()
                                            gridState.animateScrollToItem(index)
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
                                        .animateItem(),
                                )
                            }
                        }
                        previousCategory = demo.category
                        item(key = "demo-${demo.id}") {
                            DemoListRow(
                                demo = demo,
                                onClick = { openDemoFromCard(demo.id, "catalogue") },
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
                                    .demoContainerTransform(demo.id, ContainerRole.Card, source = "catalogue"),
                            )
                        }
                    }
                    if (!searchOpen) {
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
            },
        ) { /* The single persistent SceneView is the sibling underneath this scaffold. */ }
        HomeHeader(
            overStage = !covered && !searchOpen,
            searchOpen = searchOpen,
            query = query,
            onQueryChange = onQueryChange,
            onSearchClick = openSearch,
            onSearchClose = { searchOpen = false; onQueryChange("") },
            showWhatsNew = hasUnseenWhatsNew || !whatsNew.isNullOrEmpty(),
            whatsNewBadged = hasUnseenWhatsNew,
            onWhatsNewClick = {
                if (hasUnseenWhatsNew) onWhatsNewSinceClick() else whatsNewSheet.open()
            },
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

private const val WHATS_NEW_ITEM_KEY = "whats-new"

/** A single, full-window layer: no card, clipping, painted sky or text over the circuit. */
@Composable
private fun HomeHeroStage(
    active: Boolean,
    scroll: () -> Float,
    contentPadding: PaddingValues,
    inspectionMode: Boolean,
) {
    Box(Modifier.fillMaxSize().testTag(HomeTestTags.HERO)) {
        if (inspectionMode) HomeHeroStill(Modifier.fillMaxSize())
        else HomeHeroScene(
            active = active,
            scroll = scroll,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

const val HERO_DEMO_ID = "model-viewer"

@Composable
private fun HomeHeader(
    overStage: Boolean,
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchClick: () -> Unit,
    onSearchClose: () -> Unit,
    showWhatsNew: Boolean,
    whatsNewBadged: Boolean,
    onWhatsNewClick: () -> Unit,
) {
    RequestLightStatusBarIcons(active = overStage)
    val keyboard = LocalSoftwareKeyboardController.current
    val titleColor = if (overStage) SceneViewTokens.HomeColor.heroTitle else MaterialTheme.colorScheme.onSurface
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .background(if (overStage) Color.Transparent else MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        if (searchOpen) {
            SearchRow(query, onQueryChange, onClose = { keyboard?.hide(); onSearchClose() })
        } else {
            Row(
                Modifier.fillMaxWidth().height(SceneViewTokens.Home.headerHeight)
                    .padding(horizontal = SceneViewTokens.Home.contentPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                Icon(
                    painterResource(R.drawable.ic_sceneview_mark), contentDescription = null,
                    tint = titleColor, modifier = Modifier.size(SceneViewTokens.Home.markSize),
                )
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.titleLarge, color = titleColor)
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = onSearchClick) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.home_search))
                }
                Box {
                    FilledTonalIconButton(onClick = { menuOpen = true }) {
                        if (whatsNewBadged) {
                            BadgedBox(badge = { Badge() }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_menu))
                            }
                        } else Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_menu))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.home_whats_new)) },
                            enabled = showWhatsNew,
                            onClick = { menuOpen = false; onWhatsNewClick() },
                        )
                    }
                }
            }
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
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
    )
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

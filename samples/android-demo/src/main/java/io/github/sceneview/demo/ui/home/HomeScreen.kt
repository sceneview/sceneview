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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoFreshness
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
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

    /** The permanent 80 dp band the hero docks into, and the grid's scroll proxy over it. */
    const val HERO_DOCK = "home-hero-dock"
    const val SEARCH_CLOSE = "home-search-close"

    /** Test tag of the full-span header drawn above [category]'s first card (#2239). */
    fun sectionHeader(category: String): String =
        "home-section-" + category.lowercase().replace(Regex("[^a-z0-9]+"), "-")
}

/**
 * The Showcase tab (design spec §2): one `LazyVerticalGrid`, no nested
 * scroll, no background scene. Full-span header spacer, hero and chip row,
 * then every demo as a [DemoMediaCard] in flat editorial [DemoEntry.order],
 * closed by a [BrowseOnlineModelsCard] that opens the online gallery.
 *
 * The header is a pinned overlay drawn over the grid: transparent while the
 * hero is on screen, `surface` at 94 % plus a bottom hairline once the first
 * item has scrolled away. Its search action swaps the wordmark row for a 48 dp
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
    /**
     * The catalogue's scroll state, hoisted.
     *
     * The hero's whole choreography is a function of this one number, so a test that cannot set it
     * exactly can only assert the screen through a swipe and a touch slop. `HomeHeroDockTest`
     * drives it directly and captures the stage at an exact `p`.
     */
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val home = SceneViewTokens.Home
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
                            heroArt = if (entry.id == HERO_DEMO_ID) {
                                R.drawable.preview_hero_model_viewer
                            } else {
                                null
                            },
                        ),
                    )
                }
        }
    }

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

    // ── The hero, as one number ───────────────────────────────────────────────────────────────
    //
    // `p` runs 0 → 1 over `HERO_DOLLY` dp of scroll and is the screen's *only* input to the 3D
    // camera (`HomeHeroPose`). Everything the hero does — the dolly, the arc, the framing, the
    // dock — is a pure function of it, so there is one writer, no memory, and nothing to
    // interrupt. Read at *frame* time, hence a lambda: a `Float` here would recompose the whole
    // home grid once per scrolled frame to move a camera the composition never reads.
    val density = LocalDensity.current
    val motionEnabled = LocalMotionEnabled.current
    val dollyPx = with(density) { HERO_DOLLY.toPx() }
    val heroProgress: () -> Float = remember(gridState, dollyPx) {
        {
            val item = gridState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == HERO_ITEM_KEY }
            if (item == null) 1f else ((-item.offset.y).toFloat() / dollyPx).coerceIn(0f, 1f)
        }
    }
    // The stage spans the grid's own margins, so the hero and the first row of cards share one
    // left edge. This is `W` in the pose table.
    val stageWidthDp = LocalConfiguration.current.screenWidthDp - 2f * home.contentPadding.value
    val pagerState = rememberPagerState(pageCount = { featuredPages.size })
    val heroPose: (Float) -> HeroPose = remember(heroProgress, motionEnabled, stageWidthDp, pagerState) {
        { idleYaw ->
            val raw = heroProgress()
            // "Remove animations": two poses, no trajectory. Thomas' decision — and note that the
            // architecture is untouched, so the gesture fix and the permanent band survive it.
            val p = if (motionEnabled) raw else if (raw < 0.5f) 0f else 1f
            HomeHeroPose.pose(
                progress = p,
                pagerOffset = if (motionEnabled) {
                    pagerState.currentPage + pagerState.currentPageOffsetFraction
                } else {
                    0f
                },
                idleYaw = idleYaw,
                widthDp = stageWidthDp,
                subjectUnits = HERO_SUBJECT_UNITS,
            )
        }
    }
    // The stage never leaves the screen — that is what a permanent band means — so "is it on
    // screen" is no longer the question. What is left is the hard stop: a search that has taken
    // the band's place. Everything else is settled by the pose itself, in `HomeHeroScene`: a pose
    // that has not moved writes nothing, nothing pushes the next frame, and the loop parks.
    val heroRendering = !searching

    // The catalogue's one-shot entrance, played on arrival and never again under a thumb.
    val cascade = rememberCascade()
    var cascadeIndex = 0

    val stageTop = home.headerHeight + home.heroTopGap
    val stageClip = remember { Path() }

    Box(modifier = modifier.fillMaxSize()) {
        // ── Layer 1: the stage ────────────────────────────────────────────────────────────────
        // A viewport of a fixed size, pinned for the life of this screen, that receives no input
        // whatsoever. It is drawn *under* the grid so the pager's copy can sit on it, and it is
        // composed outside the grid so that scrolling the hero away cannot destroy the Filament
        // engine and the EGL context with the lazy item that used to own it.
        val stageModifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .padding(top = stageTop, start = home.contentPadding, end = home.contentPadding)
            .height(HERO_STAGE_HEIGHT)
            // A query takes the band's place, and the results scroll where the stage is. Hidden
            // rather than removed: dropping the composable would tear down the Filament engine and
            // the EGL context on every search, and build them again. `rendering = false` already
            // means it draws nothing while it is hidden.
            .alpha(if (searching) 0f else 1f)
            .drawWithContent {
                // The one clip in the design, and it is a *draw* clip: the viewport itself never
                // resizes, so the camera's projection never changes and there is no resize jump to
                // chase. `visH` is the only height anything on screen reads.
                val visible = size.height - heroProgress() * HERO_DOLLY.toPx()
                val radius = HERO_STAGE_RADIUS.toPx()
                stageClip.rewind()
                stageClip.addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = size.width,
                        bottom = visible,
                        cornerRadius = CornerRadius(radius, radius),
                    ),
                )
                clipPath(stageClip) { this@drawWithContent.drawContent() }
            }
        if (inspectionMode) {
            // LayoutLib has no Filament `.so` to load, so a `@Preview` and the Roborazzi goldens
            // get the bundled still of the same model in the same place. The images then pin the
            // screen's geometry — the band, the clip, the copy over the stage — instead of a hole
            // where the viewport would be.
            Image(
                painter = painterResource(R.drawable.preview_hero_model_viewer),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = stageModifier,
            )
        } else {
            HomeHeroScene(
                pose = heroPose,
                rendering = heroRendering,
                modifier = stageModifier,
            )
        }

        // ── Layer 2: the catalogue ────────────────────────────────────────────────────────────
        // Offset by the dock's height rather than by a spacer item, so no amount of scrolling can
        // ever bring a card over the band. That offset *is* the permanence of the band.
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(if (expanded) home.gridMinCellExpanded else home.gridMinCell),
            contentPadding = PaddingValues(
                start = home.contentPadding,
                end = home.contentPadding,
                bottom = home.gridBottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(home.gridGutter),
            horizontalArrangement = Arrangement.spacedBy(home.gridGutter),
            modifier = Modifier
                .fillMaxSize()
                .padding(top = if (searching) home.headerHeight + home.heroTopGap else stageTop + HERO_DOCK_HEIGHT)
                .clipToBounds()
                .testTag(HomeTestTags.GRID),
        ) {
            // While a query is typed the featured pager gives way so the results
            // start under the header and stay visible above the keyboard (#3308).
            if (!searching) item(key = HERO_ITEM_KEY, span = { GridItemSpan(maxLineSpan) }) {
                // `HERO_DOLLY`, not the stage height: the item is the scroll *extent* of the
                // choreography, and the last 80 dp of the stage are the band, which does not
                // scroll. The item's remaining height is therefore always `visH - 80`, so the
                // first card meets the clipped edge of the stage exactly, at every `p`.
                HomeFeaturedPager(
                    pages = featuredPages,
                    height = HERO_DOLLY,
                    onDemoClick = onDemoClick,
                    onWhatsNewClick = { showWhatsNew = true },
                    pagerState = pagerState,
                    stageBacked = true,
                    modifier = Modifier
                        // The copy is gone by mid-course — well before the clip could cut a title
                        // through mid-letter, and before it would read as a ghost over the model.
                        // The stage keeps the frame; the copy is the part that leaves.
                        .graphicsLayer {
                            alpha = ((0.42f - heroProgress()) / 0.30f).coerceIn(0f, 1f)
                        }
                        .testTag(HomeTestTags.HERO),
                )
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
                            category = demo.category,
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

        // ── Layer 3: the chrome ───────────────────────────────────────────────────────────────
        // Draw-only, and that is load-bearing: a modifier that only draws is not hit-testable, so
        // this covers the whole screen without taking a single touch away from the grid.
        if (!searching) {
            val stageOutline = outlineSubtle()
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val left = home.contentPadding.toPx()
                        val top = stageTop.toPx()
                        val width = size.width - 2 * left
                        val visible = HERO_STAGE_HEIGHT.toPx() -
                            heroProgress() * HERO_DOLLY.toPx()
                        val radius = CornerRadius(HERO_STAGE_RADIUS.toPx(), HERO_STAGE_RADIUS.toPx())
                        // Nothing on this screen rests on its fill alone (Thomas, on dark mode):
                        // every container is closed by a 1 dp outline against the page, drawn last.
                        drawRoundRect(
                            color = stageOutline,
                            topLeft = Offset(left, top),
                            size = Size(width, visible),
                            cornerRadius = radius,
                            style = Stroke(width = home.cardOutlineWidth.toPx()),
                        )
                    },
            )
        }

        // ── Layer 3b: the dock's own affordance ───────────────────────────────────────────────
        // The band is permanent, so it needs a tap target — and it must not become the one place
        // on the screen where the catalogue stops scrolling. `scrollable` hands the grid the
        // vertical drags this 80 dp strip receives, fling included.
        if (!searching) {
            val dockLabel = stringResource(R.string.home_hero_title)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(top = stageTop, start = home.contentPadding, end = home.contentPadding)
                    .height(HERO_DOCK_HEIGHT)
                    .scrollable(
                        state = gridState,
                        orientation = Orientation.Vertical,
                        reverseDirection = true,
                    )
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.home_hero_open),
                    ) { onDemoClick(HERO_DEMO_ID) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = dockLabel
                    }
                    .testTag(HomeTestTags.HERO_DOCK),
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = SceneViewTokens.Space.md)
                        // Draw-time, like everything else the hero drives: the label fades in over
                        // the last quarter of the dolly without a single recomposition, and without
                        // the screen holding a Float it would have to invalidate.
                        .graphicsLayer {
                            alpha = ((heroProgress() - 0.75f) / 0.25f).coerceIn(0f, 1f)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
                ) {
                    Text(
                        text = stringResource(R.string.home_hero_open),
                        style = SceneViewTokens.Type.body,
                        fontWeight = FontWeight.SemiBold,
                        color = SceneViewTokens.HomeColor.heroTitle,
                    )
                    Surface(
                        modifier = Modifier.size(home.heroPillHeight),
                        shape = RoundedCornerShape(SceneViewTokens.Radius.full),
                        color = SceneViewTokens.HomeColor.heroPillBackground,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = SceneViewTokens.HomeColor.heroPillText,
                                modifier = Modifier.size(dockPillIconSize),
                            )
                        }
                    }
                }
            }
        }

        HomeHeader(
            scrolled = scrolled,
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
private fun SectionHeader(category: String, modifier: Modifier = Modifier) {
    val home = SceneViewTokens.Home
    Text(
        text = stringResource(categoryDisplayNameRes(category)),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                top = home.sectionHeaderTopGap - home.gridGutter,
                bottom = home.sectionHeaderBottomGap - home.gridGutter,
            )
            .testTag(HomeTestTags.sectionHeader(category)),
    )
}

/**
 * Grid key of the featured band. Named because three things now agree on it: the
 * item itself, the collapse fraction that measures its travel, and the render gate
 * that parks Filament once it has left.
 */
private const val HERO_ITEM_KEY = "hero"

/** The pinned viewport's size. Fixed for the life of the screen — see [HomeHeroPose]. */
private val HERO_STAGE_HEIGHT = HomeHeroPose.STAGE_HEIGHT_DP.dp

/** The band the hero never shrinks below. Thomas' decision: it is permanent, not a transient. */
private val HERO_DOCK_HEIGHT = HomeHeroPose.DOCK_HEIGHT_DP.dp

/** Scroll distance over which the choreography runs, and therefore the hero item's height. */
private val HERO_DOLLY = HomeHeroPose.DOLLY_DISTANCE_DP.dp

/** The stage's corner radius — the catalogue card's, so hero and cards share one language. */
private val HERO_STAGE_RADIUS = SceneViewTokens.Radius.xl

/** Arrow inside the docked pill; the pill is a 44 dp touch target, the glyph is not. */
private val dockPillIconSize = 20.dp

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
private val FEATURED_DEMO_IDS = listOf(HERO_DEMO_ID, "materials", "lighting")

@Composable
private fun HomeHeader(
    scrolled: Boolean,
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
    val overlay by animateColorAsState(
        targetValue = if (scrolled) {
            MaterialTheme.colorScheme.surface.copy(alpha = SceneViewTokens.HomeColor.headerOverlayAlpha)
        } else {
            Color.Transparent
        },
        animationSpec = tween(SceneViewTokens.Duration.shortMillis),
        label = "headerOverlay",
    )
    // The wordmark row and the search row are not the same height, so the swap used to
    // step the grid underneath it. `animateContentSize` makes the header carry that
    // difference itself, on the same `motion-fade` the content crossfade uses.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(overlay)
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
    onWhatsNewClick: () -> Unit,
    onSearchClick: () -> Unit,
) {
    val home = SceneViewTokens.Home
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
        Spacer(Modifier.width(SceneViewTokens.Space.sm + 2.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = SceneViewTokens.Type.title,
            color = MaterialTheme.colorScheme.onSurface,
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (whatsNewBadged) BadgedBox(badge = { Badge() }) { icon() } else icon()
            }
        }
        IconButton(onClick = onSearchClick, modifier = Modifier.offset(x = 12.dp)) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = stringResource(R.string.home_search),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
    DemoCategory.VIEWER to R.string.category_short_viewer,
    DemoCategory.GEOMETRY_MATERIALS to R.string.category_short_geometry_materials,
    DemoCategory.RENDERING to R.string.category_short_rendering,
    DemoCategory.INTERACTION to R.string.category_short_interaction,
    DemoCategory.AR_PLACEMENT to R.string.category_short_ar_placement,
    DemoCategory.AR_TRACKING to R.string.category_short_ar_tracking,
    DemoCategory.AR_UNDERSTANDING to R.string.category_short_ar_understanding,
    DemoCategory.AR_ANCHORS to R.string.category_short_ar_anchors,
    DemoCategory.PLATFORM to R.string.category_short_platform,
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
private fun Modifier.bleedHorizontal(inset: Dp): Modifier = layout { measurable, constraints ->
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

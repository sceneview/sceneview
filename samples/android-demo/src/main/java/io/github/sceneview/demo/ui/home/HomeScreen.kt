@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.telemetry.SearchTerm
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.ContainerRole
import io.github.sceneview.demo.ui.DemoContainerTransform
import io.github.sceneview.demo.ui.demoContainerTransform
import kotlinx.coroutines.delay

object HomeTestTags {
    const val LIST = "home-list"
    const val SEARCH_FIELD = "home-search-field"
    const val HERO = "home-hero"
    const val SEARCH_CLOSE = "home-search-close"
    fun section(section: HomeSection) = "home-section-${section.name}"
    fun row(demoId: String) = "home-row-$demoId"
}

/** A persistent live hero, six Material tabs, and one list of demos. Search spans all sections. */
@Composable
fun HomeScreen(
    demos: List<DemoEntry>,
    query: String,
    onQueryChange: (String) -> Unit,
    onDemoClick: (String) -> Unit,
    selectedSection: HomeSection,
    onSectionSelected: (HomeSection) -> Unit,
    onAboutClick: () -> Unit,
    searchOpen: Boolean,
    onSearchOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hasUnseenWhatsNew: Boolean = false,
    onSearchSettled: (String, Int) -> Unit = { _, _ -> },
) {
    val home = SceneViewTokens.Home
    val heroDescription = stringResource(R.string.demo_model_viewer_screen_title)
    val listState = rememberLazyListState()
    val searching = searchOpen || query.isNotBlank()
    val entries = HomeSection.entries.flatMap { section ->
        section.demos(demos).map { demo ->
            HomeSearchEntry(demo.id, stringResource(demo.titleRes), stringResource(demo.subtitleRes),
                section.name, stringResource(section.titleRes), demo.tags, demo.order)
        }
    }
    val byId = remember(demos) { demos.associateBy { it.id } }
    val rows = if (searching) filterDemos(entries, query).mapNotNull { byId[it.id] }
        else selectedSection.demos(demos)
    val currentOnSearchSettled by rememberUpdatedState(onSearchSettled)
    val resultCount by rememberUpdatedState(rows.size)
    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            delay(SearchTerm.SETTLE_MS)
            currentOnSearchSettled(query, resultCount)
        }
    }
    val closeSearch = { onQueryChange(""); onSearchOpenChange(false) }
    BackHandler(enabled = searching, onBack = closeSearch)
    val heroHeight = if (LocalConfiguration.current.screenWidthDp >= home.expandedWidthDp)
        home.heroHeightExpanded else home.heroHeight
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val heroVisible by remember { derivedStateOf {
        listState.layoutInfo.visibleItemsInfo.any { it.key == HomeTestTags.HERO }
    } }
    var previousSection by rememberSaveable { mutableStateOf(selectedSection) }
    LaunchedEffect(selectedSection) {
        if (previousSection != selectedSection && !heroVisible && !searching) {
            // The two items above the tabs are the header gap and the hero.
            listState.scrollToItem(2)
        }
        previousSection = selectedSection
    }
    Box(modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.surface)) {
        // Outside the lazy list: scrolling or changing section never recreates the engine.
        HomeHeroStage(
            listState = listState,
            active = heroVisible && !searching,
            height = statusBarTop + home.headerHeight + home.heroTopGap + heroHeight + home.heroStageBleed,
            topInset = statusBarTop,
            restTop = home.headerHeight + home.heroTopGap,
            inspectionMode = LocalInspectionMode.current,
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(top = home.headerHeight).testTag(HomeTestTags.LIST)) {
            item(key = "header-space") { Spacer(Modifier.height(home.heroTopGap)) }
            if (!searching) {
                item(key = HomeTestTags.HERO) {
                    Spacer(
                        Modifier.fillMaxWidth().height(heroHeight).testTag(HomeTestTags.HERO)
                            .semantics { contentDescription = heroDescription }
                            .clickable(role = Role.Button) { onDemoClick("model-viewer") },
                    )
                }
                stickyHeader(key = "sections") {
                    // The header is pinned above the tabs when the hero scrolls away.
                    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                        SecondaryScrollableTabRow(
                            selectedTabIndex = selectedSection.ordinal,
                            edgePadding = SceneViewTokens.Space.sm,
                        ) {
                            HomeSection.entries.forEach { section ->
                                Tab(
                                    selected = selectedSection == section,
                                    onClick = { onSectionSelected(section) },
                                    text = { Text(stringResource(section.titleRes)) },
                                    modifier = Modifier.testTag(HomeTestTags.section(section)),
                                )
                            }
                        }
                    }
                }
            }
            items(rows, key = { it.id }) { demo ->
                DemoListRow(
                    demo = demo,
                    onClick = {
                        if (query.isNotBlank()) currentOnSearchSettled(query, rows.size)
                        DemoContainerTransform.armedId = demo.id
                        onDemoClick(demo.id)
                    },
                    modifier = Modifier.demoContainerTransform(demo.id, ContainerRole.Card),
                )
            }
            if (rows.isEmpty() && searching) item {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .padding(SceneViewTokens.Space.md)) {
                    Text(stringResource(R.string.home_empty_query, query))
                    TextButton(onClick = { onQueryChange("") }) { Text(stringResource(R.string.home_clear)) }
                }
            }
            item { Spacer(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .height(home.gridBottomInset)) }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            if (searching) SearchRow(query, onQueryChange, closeSearch)
            else TopAppBar(
                title = { Text(stringResource(R.string.about_sceneview)) },
                expandedHeight = home.headerHeight,
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    IconButton(onClick = { onSearchOpenChange(true) }) {
                        Icon(Icons.Outlined.Search, stringResource(R.string.home_search_placeholder))
                    }
                    IconButton(onClick = onAboutClick) {
                        BadgedBox(badge = { if (hasUnseenWhatsNew) Badge() }) {
                            Icon(Icons.Outlined.Info, stringResource(R.string.about_title))
                        }
                    }
                },
            )
        }
    }
}

/** Same stage dimensions, camera padding, scroll response and parallax as the original hero. */
@Composable
private fun HomeHeroStage(
    listState: LazyListState, active: Boolean, height: Dp, topInset: Dp,
    restTop: Dp, inspectionMode: Boolean,
) {
    val home = SceneViewTokens.Home
    val restTopPx = with(LocalDensity.current) { (restTop - home.headerHeight).toPx() }
    val heroTravel: () -> Float? = {
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == HomeTestTags.HERO }
            ?.let { (it.offset - restTopPx).coerceAtMost(0f) }
    }
    val bandTop = topInset + restTop
    val bandHeight = height - bandTop - home.heroStageBleed
    val bandHeightPx = with(LocalDensity.current) { bandHeight.toPx() }.coerceAtLeast(1f)
    Box(Modifier.fillMaxWidth().height(height).offset(y = -topInset)
        .graphicsLayer {
            val travel = heroTravel()
            translationY = travel ?: 0f
            alpha = if (travel == null) 0f else 1f
        }.clipToBounds().clearAndSetSemantics { }) {
        if (!inspectionMode) HomeHeroScene(
            active = active,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationY = -(heroTravel() ?: 0f) * HERO_PARALLAX
            },
            scroll = { (-(heroTravel() ?: 0f) / bandHeightPx).coerceIn(0f, 1f) },
            contentPadding = PaddingValues(
                top = bandTop,
                bottom = home.heroStageBleed + bandHeight * HERO_BOTTOM_FRAMING,
            ),
        )
    }
}

private const val HERO_PARALLAX = 0.35f
private const val HERO_BOTTOM_FRAMING = 0.4f

@Composable
private fun SearchRow(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(horizontal = SceneViewTokens.Space.sm)) {
        OutlinedTextField(
            value = query, onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).focusRequester(focus).testTag(HomeTestTags.SEARCH_FIELD),
            placeholder = { Text(stringResource(R.string.home_search_placeholder)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        )
        IconButton(onClick = { keyboard?.hide(); onClose() },
            modifier = Modifier.testTag(HomeTestTags.SEARCH_CLOSE)) {
            Icon(Icons.Outlined.Close, stringResource(R.string.home_search_close))
        }
    }
}

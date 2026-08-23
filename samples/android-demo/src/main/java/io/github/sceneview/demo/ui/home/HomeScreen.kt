@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.categoryDisplayNameRes
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.whatsnew.WhatsNewRelease
import io.github.sceneview.demo.whatsnew.WhatsNewSheet
import io.github.sceneview.demo.whatsnew.loadWhatsNew
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test tags for the home screen. */
object HomeTestTags {
    const val GRID = "home-grid"
    const val SEARCH_FIELD = "home-search-field"
    const val HERO = "home-hero"
    const val SEARCH_CLOSE = "home-search-close"
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

    // "What's new" — derived from the bundled CHANGELOG.md, never hand-maintained.
    val context = LocalContext.current
    val whatsNew by produceState(initialValue = emptyList<WhatsNewRelease>()) {
        if (!inspectionMode) {
            value = withContext(Dispatchers.IO) { loadWhatsNew(context.assets) }
        }
    }
    val inReviewDemos = remember(demos) { demos.filter { it.status == DemoStatus.InReview } }
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

    Box(modifier = modifier.fillMaxSize()) {
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
                .testTag(HomeTestTags.GRID),
        ) {
            // The pinned header overlay covers this band; the spacer keeps the
            // hero from starting underneath it.
            item(key = "header-spacer", span = { GridItemSpan(maxLineSpan) }) {
                Spacer(Modifier.height(home.headerHeight + home.heroTopGap - home.gridGutter))
            }
            // While a query is typed the hero gives way so the results start under
            // the header and stay visible above the keyboard (#3308).
            if (!searching) item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                HomeHero(
                    height = if (expanded) home.heroHeightExpanded else home.heroHeight,
                    onClick = { onDemoClick(HERO_DEMO_ID) },
                    modifier = Modifier.testTag(HomeTestTags.HERO),
                )
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
            items(visible, key = { "demo-${it.id}" }) { demo ->
                DemoMediaCard(
                    demo = demo,
                    onClick = { onDemoClick(demo.id) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(SceneViewTokens.Duration.fadeMillis),
                        placementSpec = spring(
                            dampingRatio = SceneViewTokens.Spring.dampingRatio,
                            stiffness = SceneViewTokens.Spring.stiffness,
                        ),
                    ),
                )
            }
            if (!searching) {
                item(key = "browse-online") {
                    BrowseOnlineModelsCard(
                        onClick = onBrowseOnlineClick,
                        modifier = Modifier.animateItem(),
                    )
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

/** The demo the hero opens. */
const val HERO_DEMO_ID = "model-viewer"

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
    Column(modifier = modifier.fillMaxWidth().background(overlay)) {
        AnimatedContent(
            targetState = searchOpen,
            transitionSpec = {
                fadeIn(tween(SceneViewTokens.Duration.shortMillis)) togetherWith
                    fadeOut(tween(SceneViewTokens.Duration.shortMillis))
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
private val CHIP_CATEGORIES: List<Pair<String?, Int>> = listOf(
    null to R.string.category_short_all,
    DemoCategory.BASICS_3D to R.string.category_short_3d,
    DemoCategory.LIGHTING_ENVIRONMENT to R.string.category_short_lighting,
    DemoCategory.CONTENT to R.string.category_short_content,
    DemoCategory.INTERACTION to R.string.category_short_interaction,
    DemoCategory.ADVANCED to R.string.category_short_advanced,
    DemoCategory.AUGMENTED_REALITY to R.string.category_short_ar,
)

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
            .clickable(role = Role.Tab, onClick = onClick),
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

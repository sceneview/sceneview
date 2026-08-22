@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import io.github.sceneview.demo.ui.LIST_BOTTOM_GUTTER
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.sketchfab.SketchfabService
import io.github.sceneview.demo.sources.FeedKind
import io.github.sceneview.demo.sources.GalleryModel
import io.github.sceneview.demo.sources.ModelSource
import io.github.sceneview.demo.sources.ModelSourceId
import io.github.sceneview.demo.sources.rememberModelSources
import io.github.sceneview.demo.ui.explore.components.FeaturedModelCard
import io.github.sceneview.demo.ui.explore.components.SpatialHero
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.sample.ui.demoCategoryAccent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

/**
 * Curated 3D-model discovery feed — the demo app's Spatial Gallery home: a
 * static featured stage, expandable catalog search, media-first trending rail,
 * compact source filters, and sample showcase.
 *
 * The iOS `ExploreTab` still shows the previous layout; it catches up in the
 * iOS leg of the demo-app redesign plan.
 */
@Composable
fun ExploreTabScreen(
    curatedSamples: List<DemoEntry>,
    onSampleClick: (DemoEntry) -> Unit,
) {
    val scroll = rememberScrollState()
    val recentSearches = rememberRecentSearches()

    // Multi-source resilience (#2645): the tab browses whichever catalog the
    // user picks (Sketchfab | Icosa | Poly Haven), remembering the last choice.
    // A single degraded source never blanks the tab — the samples row + the
    // picker + the other sources stay usable.
    val sources = rememberModelSources()
    val selectedSource = sources.selected

    var searchQuery by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var animatedOnly by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf<GalleryModel?>(null) }

    // Search execution state (#1239). Until v4.3.5 the SearchField persisted
    // queries to recent searches but never executed them — `searchQuery` was
    // a UI-only dead-end. `activeSearchQuery` (set on submit) drives the
    // search LaunchedEffect below.
    //   - activeSearchQuery == ""  → no search yet, default carousels show
    //   - searchResults == null    → query is loading
    //   - searchResults.isEmpty()  → query ran and returned 0 hits
    //   - searchResults non-empty  → render results section, hide trending/staff/recent
    var activeSearchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GalleryModel>?>(null) }

    // Feeds keyed by the source's advertised [FeedKind]s (each source exposes
    // its own subset — Poly Haven has no "staff picks", for instance).
    var feedsByKind by remember { mutableStateOf<Map<FeedKind, List<GalleryModel>>>(emptyMap()) }
    var loadingFeeds by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    // `true` once a feed/search request fails with HTTP 401/403 — the API key
    // is present but rejected (revoked, wrong scope, typo'd secret). Drives the
    // SketchfabDisabledBanner so the user sees "Sketchfab unavailable" instead
    // of a silently empty feed (#2095). Reset to `false` at the start of every
    // (re)load so a recovered key clears the banner.
    var keyRejected by remember { mutableStateOf(false) }
    // Bumped on each pull-to-refresh so the LaunchedEffect below re-keys and
    // cancels its previous run. This avoids the race where (a) the user pulls
    // to refresh, then (b) toggles the "Animated" filter chip before the
    // request returns: both would otherwise race writes into the same three
    // feed lists. Keying the LaunchedEffect on both `animatedOnly` and
    // `refreshTick` gives us a single cancel-then-restart pipeline.
    var refreshTick by remember { mutableStateOf(0) }

    // Switching source resets the browse + search state so the previous
    // catalog's cards never flash under the new source's headers; the feeds
    // effect below then reloads for the newly-selected source.
    val onSelectSource: (ModelSource) -> Unit = { source ->
        if (source.id != sources.selected.id) {
            sources.select(source)
            searchQuery = ""
            activeSearchQuery = ""
            searchResults = null
            feedsByKind = emptyMap()
            keyRejected = false
        }
    }

    /** (Re)load the selected source's feeds on source switch, animated toggle, first composition, or pull-to-refresh. */
    LaunchedEffect(selectedSource.id, animatedOnly, refreshTick) {
        loadingFeeds = true
        // Reset the Sketchfab "key rejected" banner before every (re)load so a
        // transient 401/403 that clears drops the banner instead of latching it.
        keyRejected = false
        val source = selectedSource
        // supervisorScope so a single feed failure (transient 429, network blip,
        // decode error) doesn't cancel the sibling feeds — surviving feeds still
        // render (#980, #2645). Each `catchingFeed` re-throws CancellationException
        // so the parent LaunchedEffect cancellation (source switch, toggle,
        // pull-to-refresh, navigation away) tears down in-flight requests cleanly.
        // A Sketchfab 401/403 flips `keyRejected` so the banner shows instead of
        // the feed silently self-hiding (#2095).
        try {
            supervisorScope {
                val onRejected = { keyRejected = true }
                val deferred = source.feedKinds.map { kind ->
                    kind to async {
                        catchingFeed(onRejected) {
                            source.feed(
                                kind = kind,
                                animatedOnly = animatedOnly && source.supportsAnimatedFilter,
                                limit = 10,
                            )
                        }
                    }
                }
                feedsByKind = deferred.associate { (kind, d) -> kind to d.await() }
            }
        } finally {
            // try/finally so loadingFeeds + isRefreshing always reset even if the
            // coroutine was cancelled mid-flight (otherwise the skeleton spinners
            // would stay forever after navigating away mid-refresh).
            loadingFeeds = false
            isRefreshing = false
        }
    }

    // Debounced live search (#2228). Each keystroke restarts this effect; the
    // 350 ms delay is structurally cancelled by the next change, so we only
    // actually fire `activeSearchQuery = ...` once the user has paused.
    //
    // Only the explicit Enter press (`onSearchSubmit`, below) records the
    // query into `recentSearches` — live keystroke fragments would spam the
    // history with "m", "ma", "mar", … and overwhelm the dropdown.
    //
    // The 2-character minimum filters out single-letter noise; an empty
    // field is handled by `onSearchQueryChange` which immediately resets
    // both `activeSearchQuery` and `searchResults`. Trimming the comparison
    // avoids re-running on trailing-space whitespace changes.
    LaunchedEffect(searchQuery) {
        val trimmed = searchQuery.trim()
        if (trimmed.length < 2) return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        if (trimmed != activeSearchQuery) {
            activeSearchQuery = trimmed
        }
    }

    // Execute search when activeSearchQuery changes (#1239). `null` results
    // means "in flight"; an empty list means "0 hits"; non-empty means we
    // have results to render. CancellationException is re-thrown so the
    // structured concurrency teardown still applies on navigation away.
    //
    // Note: source search does NOT accept an `animated` filter (Sketchfab only
    // exposes it on the feed endpoints; the CC sources have no such concept), so
    // the animated chip has no effect on search results. Re-keys on the source
    // id so switching catalogs re-runs the query against the new one.
    LaunchedEffect(activeSearchQuery, selectedSource.id) {
        if (activeSearchQuery.isBlank()) return@LaunchedEffect
        searchResults = null  // signal loading
        searchResults = try {
            selectedSource.search(
                query = activeSearchQuery,
                limit = 24,
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Only a rejected key (401/403 — dead, revoked, or mis-scoped
            // token) latches the permanent "Sketchfab unavailable" banner.
            // A WAF challenge is deliberately NOT latched here (#2644): it is
            // transient bot mitigation, historically tripped by fast typing
            // stacking un-cancellable search calls — and `keyRejected` is only
            // reset by the feeds effect (pull-to-refresh / animated toggle),
            // so latching it made a brief throttle masquerade as a dead key.
            // Now that SketchfabService cancels superseded calls the burst is
            // gone at the source; a residual WAF blip / 429 / network error
            // surfaces the search empty state and clears on the next query.
            if (t is SketchfabService.SketchfabError.KeyRejected) {
                keyRejected = true
            }
            emptyList()
        }
    }

    val body = @Composable {
        ExploreBody(
            scroll = scroll,
            sources = sources.sources,
            selectedSource = selectedSource,
            onSelectSource = onSelectSource,
            keyRejected = keyRejected,
            searchQuery = searchQuery,
            onSearchQueryChange = { newValue ->
                searchQuery = newValue
                // Clearing the field cancels the active search and restores
                // the default carousels (#1239).
                if (newValue.isBlank()) {
                    activeSearchQuery = ""
                    searchResults = null
                }
            },
            onSearchSubmit = { q ->
                if (q.isNotBlank()) {
                    recentSearches.push(q)
                    activeSearchQuery = q  // triggers the search LaunchedEffect (#1239)
                }
            },
            activeSearchQuery = activeSearchQuery,
            searchResults = searchResults,
            searchExpanded = searchExpanded,
            onSearchExpandedChange = { searchExpanded = it },
            recentSearches = recentSearches,
            animatedOnly = animatedOnly,
            onToggleAnimated = { animatedOnly = !animatedOnly },
            curatedSamples = curatedSamples,
            onSampleClick = onSampleClick,
            feedsByKind = feedsByKind,
            loadingFeeds = loadingFeeds,
            onModelClick = { selectedModel = it },
        )
    }

    // Flat `surface` page — the particle backdrop this gallery used to share
    // with the old Samples tab was removed with the home redesign (design
    // spec §2: no background scene, the render is the protagonist).
    Box(modifier = Modifier.fillMaxSize()) {
        // Every available source has live feeds to refresh (Icosa / Poly Haven
        // need no key), so pull-to-refresh is always wired. onRefresh just bumps
        // `refreshTick`; the LaunchedEffect above owns the single
        // cancel-then-restart pipeline.
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                if (!isRefreshing) {
                    isRefreshing = true
                    refreshTick++
                }
            },
        ) {
            body()
        }
    }

    selectedModel?.let { model ->
        GalleryModelViewerScreen(
            model = model,
            source = selectedSource,
            onDismiss = { selectedModel = null },
        )
    }
}

@Composable
private fun ExploreBody(
    scroll: androidx.compose.foundation.ScrollState,
    sources: List<ModelSource>,
    selectedSource: ModelSource,
    onSelectSource: (ModelSource) -> Unit,
    keyRejected: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: (String) -> Unit,
    activeSearchQuery: String,
    searchResults: List<GalleryModel>?,
    searchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    recentSearches: RecentSearchesState,
    animatedOnly: Boolean,
    onToggleAnimated: () -> Unit,
    curatedSamples: List<DemoEntry>,
    onSampleClick: (DemoEntry) -> Unit,
    feedsByKind: Map<FeedKind, List<GalleryModel>>,
    loadingFeeds: Boolean,
    onModelClick: (GalleryModel) -> Unit,
) {
    val isSearching = activeSearchQuery.isNotBlank()
    // Only Sketchfab's "key rejected" (401/403) surfaces the unavailable banner;
    // the CC sources never reach it, and a missing Sketchfab key simply drops the
    // chip from the picker rather than showing dead UI (#1909/#2095, #2645).
    val showSketchfabBanner = selectedSource.id == ModelSourceId.SKETCHFAB && keyRejected
    // The featured stage fronts the first available model; the trending rail
    // below filters it out so the same card never appears twice on screen.
    val hero = feedsByKind[FeedKind.TRENDING]?.firstOrNull()
        ?: selectedSource.feedKinds.asSequence().flatMap { feedsByKind[it].orEmpty().asSequence() }.firstOrNull()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(
                start = SceneViewTokens.Layout.containerPaddingMobile,
                end = SceneViewTokens.Layout.containerPaddingMobile,
                top = SceneViewTokens.Space.sm,
                bottom = LIST_BOTTOM_GUTTER,
            ),
        verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.lg),
    ) {
        Spacer(Modifier.height(SceneViewTokens.Space.xs))

        if (searchExpanded || isSearching) {
            SearchField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                onSubmit = onSearchSubmit,
                sourceName = selectedSource.id.displayName,
                onCollapse = {
                    if (searchQuery.isBlank()) onSearchExpandedChange(false)
                },
            )
            if (searchQuery.isBlank() && recentSearches.items.isNotEmpty()) {
                RecentSearchesSection(
                    items = recentSearches.items,
                    onClear = recentSearches::clear,
                    onClick = { query ->
                        onSearchQueryChange(query)
                        onSearchSubmit(query)
                    },
                    onRemove = recentSearches::remove,
                )
            }
            CompactBrowseRail(
                sources = sources,
                selectedSource = selectedSource,
                onSelectSource = onSelectSource,
                showAnimated = selectedSource.supportsAnimatedFilter && !isSearching,
                animatedOnly = animatedOnly,
                onToggleAnimated = onToggleAnimated,
            )
        } else {
            // The pill is anchored to the hero card, not the display: this Box lives
            // inside the scrolled column, whose host applies the window insets.
            Box(modifier = Modifier.fillMaxWidth()) {
                if (hero != null) {
                    SpatialHero(model = hero, onViewIn3D = { onModelClick(hero) })
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SceneViewTokens.Layout.heroStageHeight)
                            .clip(RoundedCornerShape(SceneViewTokens.Radius.xl))
                            .background(MaterialTheme.colorScheme.surfaceDim),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (loadingFeeds) CircularProgressIndicator()
                    }
                }
                FloatingSearchPill(
                    sourceName = selectedSource.id.displayName,
                    onClick = { onSearchExpandedChange(true) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(SceneViewTokens.Space.md),
                )
            }
        }

        // "Sketchfab unavailable" banner (#2095) — only when the Sketchfab key was
        // rejected at runtime. Intentionally outlined (not error-colored): the tab
        // is degraded but functional (samples + other sources still work).
        if (showSketchfabBanner) {
            SketchfabDisabledBanner(keyRejected = true)
        }

        if (isSearching) {
            // Active search (#1239): render the results section, hide the feeds.
            // searchResults == null ⇒ still loading; empty ⇒ ran but 0 hits;
            // non-empty ⇒ render carousel.
            when {
                searchResults == null -> FeedSection(
                    title = stringResource(R.string.explore_search_results),
                    models = emptyList(),
                    loading = true,
                    onModelClick = onModelClick,
                )
                searchResults.isEmpty() -> Text(
                    text = stringResource(R.string.explore_search_no_results, activeSearchQuery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> FeedSection(
                    title = stringResource(R.string.explore_search_results),
                    models = searchResults,
                    loading = false,
                    onModelClick = onModelClick,
                )
            }
        } else if (!searchExpanded) {
            val trending = (
                feedsByKind[FeedKind.TRENDING]
                    ?: selectedSource.feedKinds.firstNotNullOfOrNull { kind ->
                        feedsByKind[kind]?.takeIf { models -> models.isNotEmpty() }
                    }
                    ?: emptyList()
                ).filterNot { it.cardKey == hero?.cardKey }
            TrendingRail(models = trending, loading = loadingFeeds, onModelClick = onModelClick)

            CompactBrowseRail(
                sources = sources,
                selectedSource = selectedSource,
                onSelectSource = onSelectSource,
                showAnimated = selectedSource.supportsAnimatedFilter,
                animatedOnly = animatedOnly,
                onToggleAnimated = onToggleAnimated,
            )

            if (curatedSamples.isNotEmpty()) {
                CarouselSection(title = stringResource(R.string.explore_built_with_sceneview)) {
                    val state = rememberLazyListState()
                    LazyRow(
                        state = state,
                        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                        contentPadding = PaddingValues(horizontal = SceneViewTokens.Space.xs),
                        flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
                    ) {
                        items(curatedSamples) { sample ->
                            SampleCard(sample = sample, onClick = { onSampleClick(sample) })
                        }
                    }
                }
            }
        }

        // Categories section removed (#2237) — Thomas QA 2026-05-26 confirmed it
        // is never used as a section. Will return as filter chips under the
        // search bar in a focused redesign (no issue filed yet).

        // Recent searches section removed (#2237) — same QA pass. The
        // RecentSearchesState data layer is preserved for a future
        // search-focus dropdown (Google Search style).

        Spacer(Modifier.height(SceneViewTokens.Space.md))
    }
}

// ── Sub-components ────────────────────────────────────────────────────────

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    sourceName: String,
    onCollapse: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    // Focus (and IME) only on first appearance — recompositions after a
    // submitted search must not re-open the keyboard over the results.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            placeholder = { Text(stringResource(R.string.explore_search_placeholder, sourceName)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(SceneViewTokens.Radius.full),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit(value) }),
            trailingIcon = {
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.explore_clear_search))
                    }
                } else {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.explore_close_search))
                    }
                }
            },
        )
    }
}

@Composable
private fun FloatingSearchPill(sourceName: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SceneViewTokens.SpatialGalleryColor
    val dark = isSystemInDarkTheme()
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(SceneViewTokens.Radius.full),
        color = if (dark) colors.glassSurfaceDark else colors.glassSurfaceLight,
        border = BorderStroke(
            colors.glassBorderWidth,
            if (dark) colors.glassBorderDark else colors.glassBorderLight,
        ),
    ) {
        Row(
            modifier = Modifier.padding(SceneViewTokens.Space.md),
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Text(
                text = stringResource(R.string.explore_search_placeholder, sourceName),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrendingRail(
    models: List<GalleryModel>,
    loading: Boolean,
    onModelClick: (GalleryModel) -> Unit,
) {
    if (models.isEmpty() && !loading) return
    CarouselSection(title = stringResource(R.string.explore_trending_in_3d)) {
        if (models.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.size(SceneViewTokens.Space.lg))
        } else {
            val state = rememberLazyListState()
            LazyRow(
                state = state,
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                contentPadding = PaddingValues(horizontal = SceneViewTokens.Space.xs),
                flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
            ) {
                items(models, key = { it.cardKey }) { model ->
                    val width = if (model == models.first()) {
                        SceneViewTokens.Layout.heroStageHeight
                    } else {
                        SceneViewTokens.Layout.heroStageHeight - SceneViewTokens.Space.x3l
                    }
                    FeaturedModelCard(
                        model = model,
                        onClick = { onModelClick(model) },
                        modifier = Modifier.width(width),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactBrowseRail(
    sources: List<ModelSource>,
    selectedSource: ModelSource,
    onSelectSource: (ModelSource) -> Unit,
    showAnimated: Boolean,
    animatedOnly: Boolean,
    onToggleAnimated: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
        Text(
            text = stringResource(R.string.explore_browse_by_source),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
        ) {
            sources.forEach { source ->
                FilterChip(
                    selected = source.id == selectedSource.id,
                    onClick = { onSelectSource(source) },
                    label = { Text(source.id.displayName) },
                )
            }
            if (showAnimated) {
                FilterChip(
                    selected = animatedOnly,
                    onClick = onToggleAnimated,
                    label = { Text(stringResource(R.string.explore_filter_animated)) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
    }
}

/**
 * Defensive banner shown in the Explore tab when Sketchfab is unavailable.
 *
 * Two cases, distinguished by [keyRejected]:
 *  - [keyRejected] == `false` — [SketchfabConfig.apiKey] is `null`: the build
 *    shipped without a `SKETCHFAB_API_KEY` secret (#1909).
 *  - [keyRejected] == `true`  — the key is present but the API rejected it with
 *    HTTP 401/403 (revoked, wrong scope, typo, or a rate-limit burst) (#2095).
 *
 * The banner is intentionally outlined (neutral container) rather than error-
 * colored — the app is degraded but functional: the curated samples + category
 * grid + recent searches all still work. Tapping the banner surfaces a short
 * dialog explaining the situation + the `local.properties` workaround for
 * contributors building from source.
 */
@Composable
private fun SketchfabDisabledBanner(keyRejected: Boolean = false) {
    var showDialog by remember { mutableStateOf(false) }
    val titleRes = if (keyRejected) {
        R.string.explore_sketchfab_rejected_title
    } else {
        R.string.explore_sketchfab_disabled_title
    }
    val subtitleRes = if (keyRejected) {
        R.string.explore_sketchfab_rejected_subtitle
    } else {
        R.string.explore_sketchfab_disabled_subtitle
    }
    val dialogBodyRes = if (keyRejected) {
        R.string.explore_sketchfab_rejected_dialog_body
    } else {
        R.string.explore_sketchfab_disabled_dialog_body
    }
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true },
        shape = RoundedCornerShape(SceneViewTokens.Radius.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SceneViewTokens.Space.md, vertical = SceneViewTokens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                )
            },
            title = { Text(stringResource(R.string.explore_sketchfab_disabled_dialog_title)) },
            text = {
                // Strip the `<b>` markers (kept in the string for future
                // HTML-rendering upgrade) and render as plain bodyMedium for
                // now — keeps the dialog accessible without an extra
                // dependency on HtmlCompat.fromHtml.
                Text(
                    text = stringResource(dialogBodyRes)
                        .replace("<b>", "")
                        .replace("</b>", ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.explore_sketchfab_disabled_dismiss))
                }
            },
        )
    }
}

@Composable
private fun CarouselSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        content()
    }
}

/**
 * Run [block] and return its result, or `emptyList()` if it throws — but
 * re-throw `CancellationException` so structured concurrency stays intact
 * (the parent `LaunchedEffect` cancellation must propagate through the
 * `supervisorScope` `await` calls).
 *
 * A [SketchfabService.SketchfabError.KeyRejected] (HTTP 401/403) additionally
 * fires [onKeyRejected] so the caller can surface the "Sketchfab unavailable"
 * banner instead of letting the feed silently collapse to an empty list
 * (#2095). All other failures (429 / network blip / decode error) just yield
 * an empty list as before.
 */
private suspend inline fun <T> catchingFeed(
    onKeyRejected: () -> Unit = {},
    crossinline block: suspend () -> List<T>,
): List<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    // Both KeyRejected (401/403) and WafChallenge (202 + empty body from
    // the AWS CloudFront WAF in front of Sketchfab) surface the
    // "Sketchfab unavailable" banner so the user sees an explanation
    // rather than three silently self-hiding feed sections (#2095, #2191).
    if (t is SketchfabService.SketchfabError.KeyRejected ||
        t is SketchfabService.SketchfabError.WafChallenge
    ) {
        onKeyRejected()
    }
    emptyList()
}

@Composable
private fun FeedSection(
    title: String,
    models: List<GalleryModel>,
    loading: Boolean,
    onModelClick: (GalleryModel) -> Unit,
) {
    // Hide the entire section when the feed is empty and we're not still
    // loading — better than telling users "Nothing here yet" when the
    // Sketchfab request failed silently or is rate-limited. Avoids three
    // ghost sections stacking under each other in the offline path.
    if (models.isEmpty() && !loading) return

    Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(SceneViewTokens.Space.lg),
                    strokeWidth = SceneViewTokens.Space.xs,
                )
            }
        }
        val state = rememberLazyListState()
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
            // Edge padding so the first/last card has breathing room and never
            // sits half-cropped at the viewport edge (#1182).
            contentPadding = PaddingValues(horizontal = SceneViewTokens.Space.xs),
            // Snap to card boundaries on fling so the user never releases
            // scroll on a half-card mid-name.
            flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
        ) {
            items(models, key = { it.cardKey }) { m ->
                FeaturedModelCard(
                    model = m,
                    onClick = { onModelClick(m) },
                    modifier = Modifier.width(
                        SceneViewTokens.Layout.heroStageHeight - SceneViewTokens.Space.x3l,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SampleCard(sample: DemoEntry, onClick: () -> Unit) {
    val accent = demoCategoryAccent(sample.category)
    Box(
        modifier = Modifier
            .width(SceneViewTokens.Layout.heroStageHeight - SceneViewTokens.Space.x3l * 3)
            .aspectRatio(SceneViewTokens.Layout.mediaAspect)
            .clip(RoundedCornerShape(SceneViewTokens.Radius.lg))
            .background(
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(
                        accent.copy(alpha = SceneViewTokens.SpatialGalleryColor.glassSurfaceLight.alpha),
                        MaterialTheme.colorScheme.surfaceDim,
                    ),
                ),
            )
            .clickable(onClick = onClick),
    ) {
        // Full-card scrim, same treatment as FeaturedModelCard: the white title
        // must stay readable whatever the accent gradient behind it resolves to.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(
                            SceneViewTokens.SpatialGalleryColor.stageScrimStart,
                            SceneViewTokens.SpatialGalleryColor.stageScrimEnd,
                        ),
                    ),
                ),
        )
        Icon(
            imageVector = sample.icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier
                .align(Alignment.Center)
                .size(SceneViewTokens.Space.x2l),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(SceneViewTokens.Space.sm),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        ) {
            Text(
                text = stringResource(sample.titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = androidx.compose.ui.graphics.Color.White,
                maxLines = 1,
            )
            Text(
                text = stringResource(sample.subtitleRes),
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Preserved for a future redesign — see #2237. Currently unwired from
// ExploreBody; do not delete the definition.
@Suppress("unused", "UnusedPrivateMember", "UnusedPrivateFunction")
@Composable
private fun CategoriesSection(onCategoryClick: (SketchfabCategory) -> Unit) {
    val scrollState = rememberScrollState()
    Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
        Text(
            text = stringResource(R.string.explore_categories),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Two-row flow: split the 18 categories across two horizontally-
        // scrolling rows so all chips remain visible without a grid layout
        // (which would need LazyVerticalGrid → conflicts with the parent
        // verticalScroll). Same compromise as the iOS demo.
        val all = SketchfabCategory.entries
        val rowSize = (all.size + 1) / 2
        Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
            Row(
                modifier = Modifier.horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                all.take(rowSize).forEach { CategoryChip(it, onCategoryClick) }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                all.drop(rowSize).forEach { CategoryChip(it, onCategoryClick) }
            }
        }
    }
}

@Composable
private fun CategoryChip(category: SketchfabCategory, onClick: (SketchfabCategory) -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(SceneViewTokens.Radius.full))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .clickable { onClick(category) }
            .padding(horizontal = SceneViewTokens.Space.md, vertical = SceneViewTokens.Space.sm),
    ) {
        Text(
            text = category.displayName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontWeight = FontWeight.Medium,
        )
    }
}

// Preserved for a future redesign — see #2237. Currently unwired from
// ExploreBody; do not delete the definition.
@Suppress("unused", "UnusedPrivateMember", "UnusedPrivateFunction")
@Composable
private fun RecentSearchesSection(
    items: List<String>,
    onClear: () -> Unit,
    onClick: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.explore_recent_searches),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text(stringResource(R.string.explore_clear)) }
        }
        items.forEach { query ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SceneViewTokens.Radius.md))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onClick(query) }
                    .padding(horizontal = SceneViewTokens.Space.md, vertical = SceneViewTokens.Space.sm),
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(SceneViewTokens.Space.sm))
                Text(
                    text = query,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemove(query) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.explore_remove_query, query))
                }
            }
        }
    }
}

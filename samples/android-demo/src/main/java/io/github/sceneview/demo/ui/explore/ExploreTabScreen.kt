@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.feedback.FEEDBACK_FAB_RESERVED_SPACE
import io.github.sceneview.demo.sketchfab.SketchfabConfig
import io.github.sceneview.demo.sketchfab.SketchfabModel
import io.github.sceneview.demo.sketchfab.SketchfabService
import io.github.sceneview.demo.ui.explore.components.FeaturedSketchfabCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

/**
 * Curated 3D-model discovery feed. Mirrors the iOS `ExploreTab` so QA can
 * compare both apps side-by-side.
 *
 * Top-down layout:
 *   1. Search bar (Sketchfab queries)
 *   2. Filter chips — currently "Animated" (toggle reloads the three feeds)
 *   3. "Try a sample" curated row — links straight into existing demos
 *   4. "Staff Picks"     carousel (Sketchfab)
 *   5. "Most Liked"      carousel (Sketchfab)
 *   6. "Recently Added"  carousel (Sketchfab)
 *   7. Categories grid (18 official Sketchfab slugs)
 *   8. Recent searches (SharedPreferences-backed)
 *
 * When `BuildConfig.SKETCHFAB_API_KEY` is empty the three Sketchfab carousels
 * are hidden and only the curated sample row + categories are shown — keeps
 * the public Play Store build useful even without the proprietary key.
 */
@Composable
fun ExploreTabScreen(
    curatedSamples: List<DemoEntry>,
    onSampleClick: (DemoEntry) -> Unit,
    onCategoryClick: (SketchfabCategory) -> Unit = {},
) {
    val scroll = rememberScrollState()
    val recentSearches = rememberRecentSearches()

    var searchQuery by remember { mutableStateOf("") }
    var animatedOnly by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf<SketchfabModel?>(null) }

    // Search execution state (#1239). Until v4.3.5 the SearchField persisted
    // queries to recent searches but never executed them — `searchQuery` was
    // a UI-only dead-end. `activeSearchQuery` (set on submit) drives the
    // search LaunchedEffect below.
    //   - activeSearchQuery == ""  → no search yet, default carousels show
    //   - searchResults == null    → query is loading
    //   - searchResults.isEmpty()  → query ran and returned 0 hits
    //   - searchResults non-empty  → render results section, hide trending/staff/recent
    var activeSearchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SketchfabModel>?>(null) }

    var staffPicks by remember { mutableStateOf<List<SketchfabModel>>(emptyList()) }
    var mostLiked by remember { mutableStateOf<List<SketchfabModel>>(emptyList()) }
    var recent by remember { mutableStateOf<List<SketchfabModel>>(emptyList()) }
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

    val sketchfabService = SketchfabService.getInstance(LocalContext.current)

    /** (re)load the three feeds when the animated toggle flips, on first composition, or on pull-to-refresh. */
    LaunchedEffect(animatedOnly, refreshTick) {
        if (SketchfabConfig.apiKey == null) return@LaunchedEffect
        loadingFeeds = true
        // Reset before every (re)load so a recovered key (e.g. a corrected
        // secret on the next build, or a transient 403 that clears) drops the
        // "Sketchfab unavailable" banner instead of latching it forever.
        keyRejected = false
        val animatedParam: Boolean? = if (animatedOnly) true else null
        // supervisorScope so a single feed failure (transient 429, network
        // blip) doesn't cancel the other two — surviving feeds still render
        // (#980). Each `catchingFeed` re-throws CancellationException so the
        // parent LaunchedEffect cancellation (toggle re-keys, pull-to-refresh
        // re-keys, navigation away) still tears down the in-flight requests
        // cleanly. A 401/403 from any feed flips `keyRejected` so the banner
        // shows rather than the feed silently self-hiding (#2095).
        try {
            supervisorScope {
                val onRejected = { keyRejected = true }
                val a = async { catchingFeed(onRejected) { sketchfabService.staffPicks(animated = animatedParam, limit = 10) } }
                val b = async { catchingFeed(onRejected) { sketchfabService.featured(animated = animatedParam, limit = 10) } }
                val c = async { catchingFeed(onRejected) { sketchfabService.recentlyAdded(animated = animatedParam, limit = 10) } }
                staffPicks = a.await()
                mostLiked = b.await()
                recent = c.await()
            }
        } finally {
            // try/finally so loadingFeeds + isRefreshing always reset even if
            // the coroutine was cancelled mid-flight (otherwise the skeleton
            // spinners would stay forever after navigating away mid-refresh).
            loadingFeeds = false
            isRefreshing = false
        }
    }

    // Execute search when activeSearchQuery changes (#1239). `null` results
    // means "in flight"; an empty list means "0 hits"; non-empty means we
    // have results to render. CancellationException is re-thrown so the
    // structured concurrency teardown still applies on navigation away.
    //
    // Note: `SketchfabService.search` does NOT accept an `animated` filter
    // (that's only on the staffPicks/featured/recentlyAdded endpoints). The
    // animated chip therefore has no effect on the search results — that's a
    // Sketchfab API limitation, not a wiring gap.
    LaunchedEffect(activeSearchQuery) {
        if (activeSearchQuery.isBlank() || SketchfabConfig.apiKey == null) return@LaunchedEffect
        searchResults = null  // signal loading
        searchResults = try {
            sketchfabService.search(
                query = activeSearchQuery,
                limit = 24,
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // A rejected key (401/403) or a WAF challenge (CloudFront's
            // bot mitigation in front of Sketchfab — see SketchfabService
            // KDoc) flips `keyRejected` so the banner shows; a transient
            // 429 / network blip just surfaces an empty result set rather
            // than a crash. The empty-state UI explains.
            if (t is SketchfabService.SketchfabError.KeyRejected ||
                t is SketchfabService.SketchfabError.WafChallenge
            ) {
                keyRejected = true
            }
            emptyList()
        }
    }

    // Capture the apiKey resolution exactly once per composition so the body +
    // banner + search-field hint stay coherent (the property reads BuildConfig
    // + System.getenv, so re-reading on every recomposition is harmless but
    // wasted work). Used to drive the #1909 defensive banner: when null the
    // build was shipped without a Sketchfab token and the carousels/search
    // would otherwise be silent dead UI.
    val apiKeyAvailable = SketchfabConfig.apiKey != null

    val body = @Composable {
        ExploreBody(
            scroll = scroll,
            apiKeyAvailable = apiKeyAvailable,
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
            animatedOnly = animatedOnly,
            onToggleAnimated = { animatedOnly = !animatedOnly },
            curatedSamples = curatedSamples,
            onSampleClick = onSampleClick,
            mostLiked = mostLiked,
            staffPicks = staffPicks,
            recent = recent,
            loadingFeeds = loadingFeeds,
            onModelClick = { selectedModel = it },
            onCategoryClick = onCategoryClick,
            recentSearches = recentSearches,
        )
    }

    // Pull-to-refresh is only wired when the Sketchfab carousels are visible —
    // without an API key there's nothing dynamic to refresh and pulling would
    // spin a spinner that immediately settles, which is worse than no affordance
    // at all. onRefresh just bumps `refreshTick`; the LaunchedEffect above
    // owns the single cancel-then-restart pipeline.
    if (apiKeyAvailable) {
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
    } else {
        body()
    }

    selectedModel?.let { model ->
        SketchfabModelViewerScreen(model = model, onDismiss = { selectedModel = null })
    }
}

@Composable
private fun ExploreBody(
    scroll: androidx.compose.foundation.ScrollState,
    apiKeyAvailable: Boolean,
    keyRejected: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: (String) -> Unit,
    activeSearchQuery: String,
    searchResults: List<SketchfabModel>?,
    animatedOnly: Boolean,
    onToggleAnimated: () -> Unit,
    curatedSamples: List<DemoEntry>,
    onSampleClick: (DemoEntry) -> Unit,
    mostLiked: List<SketchfabModel>,
    staffPicks: List<SketchfabModel>,
    recent: List<SketchfabModel>,
    loadingFeeds: Boolean,
    onModelClick: (SketchfabModel) -> Unit,
    onCategoryClick: (SketchfabCategory) -> Unit,
    recentSearches: RecentSearchesState,
) {
    val isSearching = activeSearchQuery.isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            // Bottom padding reserves a gutter for the floating feedback FAB
            // so the chip floats over empty space instead of masking the
            // "Trending models" / "Staff picks" carousel titles when those
            // sections render below the search field (#2194).
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = FEEDBACK_FAB_RESERVED_SPACE,
            ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.height(0.dp))
        // Hide the page title when the user is actively searching (#2229) —
        // it's noise that pushes the results below the fold. The SearchField
        // below carries the "what is this page" affordance on its own.
        if (!isSearching) {
            Text(
                text = stringResource(R.string.explore_heading),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        SearchField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            onSubmit = onSearchSubmit,
            apiKeyAvailable = apiKeyAvailable,
        )

        // Defensive banner (#1909, #2095) — Sketchfab-backed surfaces below
        // (carousels + search) become silent dead UI in two cases:
        //   • the release build shipped without a SKETCHFAB_API_KEY secret
        //     (apiKey == null), or
        //   • the key is present but the API rejected it with 401/403
        //     (`keyRejected`) — revoked, wrong scope, or a typo.
        // Either way, surface it explicitly so QA + curious users see WHY the
        // page looks empty instead of assuming "the app is broken". The banner
        // is intentionally outlined (not error-colored) — unavailable is not
        // the same as crashed.
        if (!apiKeyAvailable || keyRejected) {
            SketchfabDisabledBanner(keyRejected = keyRejected)
        }

        // FiltersBar (Animated chip) and the "Try a sample" carousel both
        // belong to the browse experience — when searching, the screen is
        // dedicated to results so they get hidden too (#2229). The full
        // browse layout returns the moment the query clears.
        if (apiKeyAvailable && !isSearching) {
            FiltersBar(animatedOnly = animatedOnly, onToggle = onToggleAnimated)
        }

        if (curatedSamples.isNotEmpty() && !isSearching) {
            CarouselSection(title = stringResource(R.string.explore_try_a_sample)) {
                val state = rememberLazyListState()
                LazyRow(
                    state = state,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    // Edge padding so the first/last card has breathing room
                    // and never sits half-cropped at the viewport edge — the
                    // Pixel 9 audit (#1182) caught a screenshot where a card
                    // appeared truncated mid-name purely because of zero-pad.
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    // Snap to card boundaries on fling so the user never
                    // releases scroll on a half-card.
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
                ) {
                    items(curatedSamples) { sample ->
                        SampleCard(sample = sample, onClick = { onSampleClick(sample) })
                    }
                }
            }
        }

        // The Sketchfab carousels are only rendered when the API key is wired
        // in via gradle.properties / CI secret. On end-user Play Store builds
        // the key is always present (see release.yml). Builds without the key
        // silently fall back to the "Try a sample" carousel + curated category
        // grid below — the SketchfabDisabledBanner above already tells the
        // user WHY the dynamic carousels are missing (#1909).
        if (apiKeyAvailable) {
            if (isSearching) {
                // Active search (#1239): render results section, hide the three
                // default feeds. searchResults == null ⇒ still loading;
                // empty ⇒ ran but 0 hits; non-empty ⇒ render carousel.
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
            } else {
                // No `feedsError` Text — when the API is unreachable each empty
                // FeedSection self-hides and the page falls back to the "Try a
                // sample" carousel + Categories grid silently. A red dev-style
                // error banner here was the v4.1.0 "horrible UI" complaint.
                FeedSection(
                    title = stringResource(R.string.explore_trending_models),
                    models = mostLiked,
                    loading = loadingFeeds && mostLiked.isEmpty(),
                    onModelClick = onModelClick,
                )
                FeedSection(
                    title = stringResource(R.string.explore_staff_picks),
                    models = staffPicks,
                    loading = loadingFeeds && staffPicks.isEmpty(),
                    onModelClick = onModelClick,
                )
                FeedSection(
                    title = stringResource(R.string.explore_recently_added),
                    models = recent,
                    loading = loadingFeeds && recent.isEmpty(),
                    onModelClick = onModelClick,
                )
            }
        }

        // Categories section removed (#2237) — Thomas QA 2026-05-26 confirmed it
        // is never used as a section. Will return as filter chips under the
        // search bar in a focused redesign (no issue filed yet).

        // Recent searches section removed (#2237) — same QA pass. The
        // RecentSearchesState data layer is preserved for a future
        // search-focus dropdown (Google Search style).

        Spacer(Modifier.height(16.dp))
    }
}

// ── Sub-components ────────────────────────────────────────────────────────

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    apiKeyAvailable: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.explore_search_placeholder)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            // When the API key is missing the search bar is purely cosmetic —
            // the LaunchedEffect short-circuits in ExploreTabScreen.kt:165, so
            // disabling the input is honest UX rather than letting users type
            // queries that go into a black hole (#1909).
            enabled = apiKeyAvailable,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit(value) }),
            trailingIcon = if (value.isNotEmpty()) {
                {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.explore_clear_search))
                    }
                }
            } else null,
        )
        if (!apiKeyAvailable) {
            Text(
                text = stringResource(R.string.explore_sketchfab_search_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
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
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
private fun FiltersBar(animatedOnly: Boolean, onToggle: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = animatedOnly,
            onClick = onToggle,
            label = { Text(stringResource(R.string.explore_filter_animated)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
        )
    }
}

@Composable
private fun CarouselSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    models: List<SketchfabModel>,
    loading: Boolean,
    onModelClick: (SketchfabModel) -> Unit,
) {
    // Hide the entire section when the feed is empty and we're not still
    // loading — better than telling users "Nothing here yet" when the
    // Sketchfab request failed silently or is rate-limited. Avoids three
    // ghost sections stacking under each other in the offline path.
    if (models.isEmpty() && !loading) return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        val state = rememberLazyListState()
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            // Edge padding so the first/last card has breathing room and never
            // sits half-cropped at the viewport edge (#1182).
            contentPadding = PaddingValues(horizontal = 4.dp),
            // Snap to card boundaries on fling so the user never releases
            // scroll on a half-card mid-name.
            flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
        ) {
            items(models, key = { it.uid }) { m ->
                FeaturedSketchfabCard(model = m, onClick = { onModelClick(m) })
            }
        }
    }
}

@Composable
private fun SampleCard(sample: DemoEntry, onClick: () -> Unit) {
    val accent = remember(sample.category) { sampleAccent(sample.category) }
    androidx.compose.material3.Surface(
        modifier = Modifier
            .width(168.dp)
            .height(168.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.32f),
                                accent.copy(alpha = 0.14f),
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = sample.icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(36.dp),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(sample.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(sample.subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

private fun sampleAccent(category: String): androidx.compose.ui.graphics.Color =
    when (category) {
        "3D Basics" -> androidx.compose.ui.graphics.Color(0xFF6446CD)
        "Lighting & Environment" -> androidx.compose.ui.graphics.Color(0xFFE6A23C)
        "Content" -> androidx.compose.ui.graphics.Color(0xFF42A5F5)
        "Interaction" -> androidx.compose.ui.graphics.Color(0xFFEC407A)
        "Advanced" -> androidx.compose.ui.graphics.Color(0xFF26A69A)
        "Augmented Reality" -> androidx.compose.ui.graphics.Color(0xFF66BB6A)
        else -> androidx.compose.ui.graphics.Color(0xFF6446CD)
    }

// Preserved for a future redesign — see #2237. Currently unwired from
// ExploreBody; do not delete the definition.
@Suppress("unused", "UnusedPrivateMember", "UnusedPrivateFunction")
@Composable
private fun CategoriesSection(onCategoryClick: (SketchfabCategory) -> Unit) {
    val scrollState = rememberScrollState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                all.take(rowSize).forEach { CategoryChip(it, onCategoryClick) }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .clickable { onClick(category) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onClick(query) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
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

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.sceneview.demo.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.feedback.CurrentRootScreen
import io.github.sceneview.demo.feedback.FeedbackOpenRequest
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.explore.ExploreTabScreen
import io.github.sceneview.demo.ui.home.HomeScreen
import io.github.sceneview.demo.whatsnew.WhatsNewSinceSheet
import io.github.sceneview.demo.whatsnew.rememberWhatsNewSince

/**
 * Top-level UI scaffold. Hosts the three primary tabs (Showcase, AR View,
 * About) under a single M3 [NavigationBar]. The Showcase tab — the home grid
 * in [HomeScreen] — is the landing experience; the online model gallery
 * ([ExploreTabScreen]) is reached from its closing "Browse online models"
 * card rather than from a tab of its own.
 *
 * Home filter state (selected category + search query) and the gallery
 * open/closed flag are hoisted here and `rememberSaveable`d so a tab switch,
 * rotation or process death lands the user back where they were.
 *
 * Routing back into the existing per-demo screens is delegated to the
 * caller via [onDemoClick] — this composable does not own the NavHost so
 * deep-link replay (`sceneview://demo/<id>`) keeps working unchanged.
 */
@Composable
fun RootScreen(onDemoClick: (String) -> Unit) {
    var selectedTab by rememberSaveable { mutableStateOf(RootTab.Showcase) }
    // Tracks whether the AR View tab is in a live camera session. When `true`
    // the bottom NavigationBar is hidden so the AR camera goes truly
    // fullscreen — pre-#2238 the nav bar always stayed visible and ate ~90 px
    // of the live camera viewport. ArViewTabContent invokes the setter
    // whenever its internal `sessionStarted` flag flips (start / exit / back
    // gesture). The flag is intentionally NOT rememberSaveable: a config
    // change or process death should land the user back on the launcher
    // screen with the nav bar visible, not on an orphaned immersive shell.
    var arSessionActive by remember { mutableStateOf(false) }
    // "" stands for "All" — a nullable String is not a Bundle-safe saveable.
    var selectedCategory by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var galleryOpen by rememberSaveable { mutableStateOf(false) }

    // Publish the visible screen for the bug reporter (#3390). The tab
    // selection lives here as local state, while the NavController the reporter
    // can see has a single "list" destination covering all three tabs — so
    // without this every report filed from the tab host was screen-less.
    // The two sub-states matter as much as the tab: "Explore gallery" and a
    // live AR session are the screens a bug is actually filed against.
    val reportScreenLabel = when {
        selectedTab == RootTab.Showcase && galleryOpen -> "Explore gallery"
        selectedTab == RootTab.ArView && arSessionActive -> "AR View tab · session active"
        else -> selectedTab.reportLabel
    }
    DisposableEffect(reportScreenLabel) {
        CurrentRootScreen.label = reportScreenLabel
        // A demo on top removes the tab host from composition; it names itself.
        onDispose { CurrentRootScreen.label = null }
    }

    // Updates are available from the home action without interrupting app launch.
    val whatsNewSince = rememberWhatsNewSince()
    var showWhatsNewSince by rememberSaveable { mutableStateOf(false) }
    if (showWhatsNewSince) {
        WhatsNewSinceSheet(
            sections = whatsNewSince.unseen,
            seenVersion = whatsNewSince.seenVersion,
            onDemoClick = { id ->
                showWhatsNewSince = false
                onDemoClick(id)
            },
            onMarkSeen = {
                whatsNewSince.markSeen()
                showWhatsNewSince = false
            },
            onDismiss = { showWhatsNewSince = false },
        )
    }

    Scaffold(
        bottomBar = {
            // Conditional rendering rather than just `visible = !arSessionActive`
            // because the bottomBar slot reserves layout space when present —
            // hiding it via Modifier.alpha or visibility would still steal ~90 px
            // from the live AR camera viewport (#2238).
            if (!arSessionActive) {
                NavigationBar {
                    RootTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            // The badge rides the Showcase tab so a list left
                            // pending stays visible from every tab, not only
                            // from the one that hosts its entry point.
                            icon = {
                                if (tab == RootTab.Showcase && whatsNewSince.hasUnseen) {
                                    BadgedBox(badge = { Badge() }) {
                                        Icon(tab.icon, contentDescription = null)
                                    }
                                } else {
                                    Icon(tab.icon, contentDescription = null)
                                }
                            },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (selectedTab) {
                RootTab.Showcase -> if (galleryOpen) {
                    BackHandler { galleryOpen = false }
                    ExploreTabScreen(
                        onBack = { galleryOpen = false },
                        curatedSamples = curatedSamplesForExplore(),
                        onSampleClick = { sample -> onDemoClick(sample.id) },
                    )
                } else {
                    HomeScreen(
                        demos = ALL_DEMOS,
                        selectedCategory = selectedCategory.ifEmpty { null },
                        onCategoryChange = { selectedCategory = it.orEmpty() },
                        query = query,
                        onQueryChange = { query = it },
                        onDemoClick = onDemoClick,
                        onBrowseOnlineClick = { galleryOpen = true },
                        hasUnseenWhatsNew = whatsNewSince.hasUnseen,
                        onWhatsNewSinceClick = { showWhatsNewSince = true },
                    )
                }
                RootTab.ArView -> ArViewTabContent(
                    onDemoClick = onDemoClick,
                    onSessionActiveChange = { arSessionActive = it },
                )
                RootTab.About -> AboutTabContent()
            }
        }
    }
}

/**
 * @param labelRes localized label shown in the bottom navigation bar.
 * @param reportLabel English name of the tab as it appears in a bug report
 *   (#3390). Deliberately not [labelRes]: issues are read in English whatever
 *   the reporter's device locale, so a localized label would land untranslated
 *   in the tracker.
 */
enum class RootTab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val reportLabel: String,
) {
    Showcase(R.string.tab_showcase, Icons.Filled.GridView, "Showcase tab"),
    ArView(R.string.tab_ar_view, Icons.Filled.ViewInAr, "AR View tab"),
    About(R.string.tab_about, Icons.Filled.Info, "About tab"),
}

/**
 * Curated subset of [ALL_DEMOS] surfaced in the gallery's "Try a sample" carousel.
 * Hand-picked across categories so the carousel feels diverse on first
 * launch — same intent as the iOS `featuredModels` list.
 */
private fun curatedSamplesForExplore(): List<DemoEntry> {
    val ids = listOf(
        "model-viewer",
        "geometry",
        "lighting",
        "ar-placement",
        // #2239 Batch 5 — `multi-model` consolidated into `model-viewer` (already
        // first in this list). Repointed to the live `materials` umbrella so the
        // carousel keeps its 6-card diversity rather than silently shrinking when
        // a retired id is dropped by `mapNotNull` (the Batch 3 footgun).
        "materials",
        // #2239 Batch 3 — `animation` consolidated into `animation-physics`.
        "animation-physics",
    )
    return ids.mapNotNull { id -> ALL_DEMOS.firstOrNull { it.id == id } }
}

/**
 * About tab.
 *
 * Rebuilt from scratch for #3564. What it replaced: eight identical cards in a
 * column — a hero slab plus seven `AboutInfoCard` rows that gave a statement of
 * fact ("Open Source", not even tappable) exactly the weight of the one thing the
 * screen is for. Everything was emphasised, so nothing was.
 *
 * The shape now, taken from the About/Settings screens of Sketchfab, Polycam and
 * Reality Composer: a quiet identity block on the page background, then **one**
 * emphasised surface, then titled groups of plain rows. The emphasised surface is
 * the support card (#3565), placed directly under the identity so it is read
 * without scrolling — and nowhere else in the app, because "non-intrusive" means
 * one card seen once, never a dialog, a launch nag or a badge.
 */
@Composable
private fun AboutTabContent() {
    val context = LocalContext.current
    val noBrowserMessage = stringResource(R.string.about_no_browser)
    val openLink: (String) -> Unit = { url ->
        // Devices without a browser (Android Go, stripped AOSP, uninstalled Chrome)
        // throw ActivityNotFoundException → the app crashes. #1208
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            android.widget.Toast.makeText(
                context,
                noBrowserMessage.format(url),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
    // #1152 Stage 3 — CC-BY attribution for every streamed Sketchfab model.
    var showCreditsSheet by rememberSaveable { mutableStateOf(false) }
    if (showCreditsSheet) {
        CreditsSheet(onDismiss = { showCreditsSheet = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = SceneViewTokens.Space.md,
                end = SceneViewTokens.Space.md,
                top = SceneViewTokens.Space.lg,
                bottom = LIST_BOTTOM_GUTTER,
            ),
        // `space-xl` between blocks, `space-sm` between a group label and its card:
        // the label has to belong to the card under it, not float between two of them.
        verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xl),
    ) {
        AboutIdentity()
        AboutSupportCard(openLink = openLink)
        AboutGroup(title = stringResource(R.string.about_group_learn)) {
            AboutActionRow(
                icon = Icons.Outlined.MenuBook,
                title = stringResource(R.string.about_card_docs_title),
                onClick = { openLink("https://sceneview.github.io") },
            )
            AboutRowDivider()
            AboutActionRow(
                icon = Icons.Outlined.PlayCircleOutline,
                title = stringResource(R.string.about_card_playground_title),
                onClick = { openLink("https://sceneview.github.io/playground.html") },
            )
            AboutRowDivider()
            AboutActionRow(
                icon = Icons.Outlined.Code,
                title = stringResource(R.string.about_source_title),
                onClick = { openLink("https://github.com/sceneview/sceneview") },
            )
        }
        AboutGroup(title = stringResource(R.string.about_group_app)) {
            AboutActionRow(
                icon = Icons.Outlined.BugReport,
                title = stringResource(R.string.about_card_feedback_title),
                external = false,
                onClick = { FeedbackOpenRequest.request() },
            )
            AboutRowDivider()
            AboutActionRow(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.about_release_notes),
                onClick = { openLink("https://github.com/sceneview/sceneview/releases") },
            )
        }
        AboutGroup(title = stringResource(R.string.about_group_legal)) {
            AboutActionRow(
                icon = Icons.Outlined.Description,
                title = stringResource(R.string.about_license_title),
                onClick = { openLink("https://github.com/sceneview/sceneview/blob/main/LICENSE") },
            )
            AboutRowDivider()
            AboutActionRow(
                icon = Icons.Outlined.Groups,
                title = stringResource(R.string.about_credits_attribution),
                external = false,
                onClick = { showCreditsSheet = true },
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        ) {
            Text(
                text = stringResource(R.string.about_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.about_built_with),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Identity block — the mark, the name, the installed version, one sentence.
 *
 * Deliberately **not** a card. A slab here would be a second emphasised surface
 * competing with the support card directly below it, which is the mistake the old
 * screen made; on the page background the block reads as a masthead instead.
 */
@Composable
private fun AboutIdentity() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
    ) {
        Image(
            // The launcher icon itself (#3563), not a Material glyph on a gradient —
            // and not `Icons.Filled.ViewInAr`, which is already the AR View tab's icon.
            painter = painterResource(R.drawable.ic_sceneview_hero),
            contentDescription = null,
            modifier = Modifier
                .size(SceneViewTokens.About.markSize)
                .clip(RoundedCornerShape(SceneViewTokens.Radius.xl)),
        )
        Text(
            text = stringResource(R.string.about_sceneview),
            style = SceneViewTokens.Type.title,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            // Version only. The licence has its own row in the Legal group; naming it
            // twice on one screen is the redundancy this redesign removes.
            text = stringResource(R.string.about_identity_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.about_identity_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The one emphasised surface on the screen (#3565).
 *
 * Open Collective takes the filled button because it is the channel that has
 * actually received contributions; GitHub Sponsors is the quiet second. No amount,
 * no tier, no urgency copy, and nothing about revenue — the card states what
 * funding buys and stops.
 */
@Composable
private fun AboutSupportCard(openLink: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(SceneViewTokens.Radius.lg),
        // `secondaryContainer`, not `primaryContainer`: the primary container is
        // #00448D in the dark scheme — a saturated blue slab that reads as a banner
        // ad, which is the opposite of "non-intrusive" (#3565). The secondary
        // container is calm in both themes and still the only tinted surface here,
        // so the card stays the one thing the eye lands on after the mark.
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(SceneViewTokens.Space.md),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                Icon(
                    imageVector = Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(SceneViewTokens.About.rowIcon),
                )
                Text(
                    text = stringResource(R.string.about_support_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = stringResource(R.string.about_support_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { openLink("https://opencollective.com/sceneview") },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = SceneViewTokens.Layout.touchTarget),
                    shape = RoundedCornerShape(SceneViewTokens.Radius.md),
                    contentPadding = PaddingValues(horizontal = SceneViewTokens.Space.sm),
                ) {
                    Text(
                        text = stringResource(R.string.about_support_open_collective),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
                TextButton(
                    onClick = { openLink("https://github.com/sponsors/sceneview") },
                    modifier = Modifier.heightIn(min = SceneViewTokens.Layout.touchTarget),
                    shape = RoundedCornerShape(SceneViewTokens.Radius.md),
                    contentPadding = PaddingValues(horizontal = SceneViewTokens.Space.sm),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.about_support_github_sponsors),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * A titled group of rows, drawn as one `surfaceContainer` card with inset hairlines
 * between its children — so the rows read as a list with a shape, and the label
 * belongs to the card under it rather than floating between two of them.
 */
@Composable
private fun AboutGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            modifier = Modifier
                .padding(
                    start = SceneViewTokens.Space.md,
                    bottom = SceneViewTokens.Space.sm,
                )
                .semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(SceneViewTokens.Radius.md),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(content = content)
        }
    }
}

/** Hairline between two rows of an [AboutGroup], inset past the leading glyph. */
@Composable
private fun AboutRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = SceneViewTokens.About.dividerInset),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * One tappable row. [external] picks the affordance, which is the row's only
 * promise about what a tap does: open-in-new leaves the app for a browser, a
 * chevron stays inside it (the feedback flow, the credits sheet).
 */
@Composable
private fun AboutActionRow(
    icon: ImageVector,
    title: String,
    external: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .padding(
                horizontal = SceneViewTokens.Space.md,
                vertical = SceneViewTokens.Space.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
    ) {
        Icon(
            imageVector = icon,
            // Decorative: the row merges its children, so the title below is already
            // the accessible name. A description here would read it twice.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(SceneViewTokens.About.rowIcon),
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            imageVector = if (external) {
                Icons.AutoMirrored.Outlined.OpenInNew
            } else {
                Icons.AutoMirrored.Outlined.KeyboardArrowRight
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            // The chevron is a thinner glyph than open-in-new, so it is drawn a step
            // larger: matched boxes would read as two different icon sets in one column.
            modifier = Modifier.size(
                if (external) SceneViewTokens.About.rowAffordance else SceneViewTokens.About.rowIcon,
            ),
        )
    }
}

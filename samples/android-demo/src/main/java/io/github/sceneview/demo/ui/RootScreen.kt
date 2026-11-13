@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.DeepLinkRouter
import io.github.sceneview.demo.feedback.CurrentRootScreen
import io.github.sceneview.demo.freshDemos
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.support.SupportPrompt
import io.github.sceneview.demo.support.SupportSheet
import io.github.sceneview.demo.support.SupportSheetMode
import io.github.sceneview.demo.telemetry.*
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.home.HomeScreen
import io.github.sceneview.demo.ui.home.HomeSection
import io.github.sceneview.demo.update.UpdatePromptHost
import io.github.sceneview.demo.whatsnew.*
import io.github.sceneview.sample.common.update.UpdatePromptController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** One home, with About and release notes as modal sheets. Demo routes belong to the activity. */
@Composable
internal fun RootScreen(
    onDemoClick: (String) -> Unit,
    updatePrompt: UpdatePromptController? = null,
    destination: DeepLinkRouter.HomeDestination? = null,
    onDestinationConsumed: () -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf(HomeSection.Models) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val whatsNewSince = rememberWhatsNewSince()
    val context = LocalContext.current
    val releases by produceState<List<WhatsNewRelease>?>(null) {
        value = withContext(Dispatchers.IO) { loadWhatsNew(context.assets) }
    }
    val releaseSheet = rememberReturningSheetState(contentReady = releases != null)
    val sinceSheet = rememberReturningSheetState(contentReady = whatsNewSince.isLoaded)

    fun selectSection(next: HomeSection) {
        if (section != next) {
            section = next
            Telemetry.analytics.log(AnalyticsEvent.HomeSectionSelected(next.name))
        }
    }
    LaunchedEffect(destination) {
        when (destination) {
            DeepLinkRouter.HomeDestination.AR -> {
                aboutOpen = false
                releaseSheet.dismiss()
                sinceSheet.dismiss()
                query = ""
                searchOpen = false
                Telemetry.search.cleared(SearchSurface.Samples)
                selectSection(HomeSection.AR)
            }
            DeepLinkRouter.HomeDestination.About -> {
                releaseSheet.dismiss()
                sinceSheet.dismiss()
                aboutOpen = true
            }
            null -> return@LaunchedEffect
        }
        onDestinationConsumed()
    }
    val screen = if (aboutOpen) "about" else "home"
    LaunchedEffect(screen) {
        Telemetry.analytics.log(AnalyticsEvent.ScreenView(screen, "Home"))
    }
    DisposableEffect(screen) {
        CurrentRootScreen.label = if (aboutOpen) "About sheet" else "Home"
        onDispose { CurrentRootScreen.label = null }
    }
    val canAskForSupport = !aboutOpen && !releaseSheet.isShown && !sinceSheet.isShown
    LaunchedEffect(canAskForSupport, SupportPrompt.valueMoment, SupportPrompt.isShown) {
        if (!canAskForSupport || SupportPrompt.isShown || !SupportPrompt.valueMoment) return@LaunchedEffect
        delay(SUPPORT_PROMPT_SETTLE_MILLIS)
        SupportPrompt.maybeShow(canShow = true)
    }
    if (SupportPrompt.isShown && canAskForSupport) {
        SupportSheet(SupportSheetMode.Prompt, SupportPrompt::dismiss, SupportPrompt::optOut)
    }
    Scaffold(
        snackbarHost = {
            updatePrompt?.let {
                UpdatePromptHost(it, modifier = Modifier.padding(horizontal = SceneViewTokens.Space.md))
            }
        },
    ) { padding ->
        HomeScreen(
            demos = ALL_DEMOS,
            query = query,
            onQueryChange = {
                if (it.isBlank()) Telemetry.search.cleared(SearchSurface.Samples)
                query = it
            },
            selectedSection = section,
            onSectionSelected = ::selectSection,
            onDemoClick = {
                Telemetry.nextOpenSource = if (query.isBlank()) OpenSource.Home else OpenSource.Search
                onDemoClick(it)
            },
            onSearchSettled = { q, count -> Telemetry.search.settled(SearchSurface.Samples, q, count) },
            hasUnseenWhatsNew = whatsNewSince.hasUnseen,
            onAboutClick = { aboutOpen = true },
            searchOpen = searchOpen,
            onSearchOpenChange = { searchOpen = it },
            modifier = Modifier.padding(padding),
        )
    }
    if (aboutOpen && releaseSheet.phase == ReturningSheetState.Phase.Closed &&
        sinceSheet.phase == ReturningSheetState.Phase.Closed) {
        AboutSheet(
            onDismiss = { aboutOpen = false },
            onWhatsNew = { if (whatsNewSince.hasUnseen) sinceSheet.open() else releaseSheet.open() },
        )
    }
    if (sinceSheet.isShown) {
        WhatsNewSinceSheet(
            sections = whatsNewSince.unseen,
            seenVersion = whatsNewSince.seenVersion,
            onDemoClick = {
                sinceSheet.leaveForSample()
                Telemetry.nextOpenSource = OpenSource.Other
                onDemoClick(it)
            },
            onMarkSeen = { whatsNewSince.markSeen(); sinceSheet.dismiss() },
            onDismiss = sinceSheet::dismiss,
        )
    }
    if (releaseSheet.isShown) {
        WhatsNewSheet(
            releases = releases.orEmpty(),
            inReviewDemos = freshDemos(ALL_DEMOS, BuildConfig.VERSION_NAME),
            onDemoClick = {
                releaseSheet.leaveForSample()
                Telemetry.nextOpenSource = OpenSource.Other
                onDemoClick(it)
            },
            onDismiss = releaseSheet::dismiss,
        )
    }
}

private const val SUPPORT_PROMPT_SETTLE_MILLIS = 900L

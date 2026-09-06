package io.github.sceneview.demo.whatsnew

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether the "What's new since you last tested" sheet has already opened
 * itself in THIS process.
 *
 * Process-scoped on purpose. The requirement is "on app open, once": a
 * `rememberSaveable` flag would survive rotation but not a tab switch that
 * takes the Samples screen out of composition, and the sheet re-opening on
 * return from a demo is exactly the modal-on-every-resume behaviour the badge
 * exists to avoid. A process-lifetime flag says once per app launch and nothing
 * else — it is deliberately not persisted, since a new launch after a genuine
 * app restart *should* present the list again until it is acknowledged.
 */
private object WhatsNewAutoOpen {
    var shownThisProcess = false
}

/** UI state for the "since you last tested" surface. */
@Stable
class WhatsNewSinceState internal constructor(
    /** Cumulative unseen sections, newest first. Empty when nothing is pending. */
    val unseen: List<WhatsNewSection>,
    /** Base version of the last acknowledged build, for the subtitle. */
    val seenVersion: String?,
    private val onMarkSeen: () -> Unit,
) {
    /** Drives the top-bar badge. */
    val hasUnseen: Boolean get() = unseen.isNotEmpty()

    /**
     * Should the sheet open by itself right now? True at most once per process,
     * and only when there is something to show.
     *
     * Never in QA mode (#3444). A `--ez qa_mode true` launch is a device-QA run
     * deep-linking into one demo; a modal that opens itself over that demo eats
     * the flow's gestures and can end up in the screenshot. It stays silent
     * rather than being acknowledged, so the badge still has its list waiting
     * for the human who opens the app next. CI installs a fresh app and adopts
     * the running build as its baseline, so nothing is pending there — this is
     * the local loop, where the app is updated in place and every QA launch
     * would otherwise land on the sheet.
     */
    fun consumeAutoOpen(): Boolean {
        if (!shouldAutoOpenWhatsNew(
                qaMode = DemoSettings.qaMode,
                hasUnseen = hasUnseen,
                alreadyShownThisProcess = WhatsNewAutoOpen.shownThisProcess,
            )
        ) {
            return false
        }
        WhatsNewAutoOpen.shownThisProcess = true
        return true
    }

    /** Writes the marker. The only thing that does. */
    fun markSeen() = onMarkSeen()
}

/**
 * The auto-open decision, split out from [WhatsNewSinceState.consumeAutoOpen] so it can be
 * stated once and tested without a process-lifetime flag standing in the way.
 *
 * [qaMode] is the one that is not obvious: a `--ez qa_mode true` launch is a device-QA run
 * deep-linking into a single demo, and a modal that opens itself over that demo eats the
 * flow's gestures and can end up in the screenshot (#3444). Staying silent — rather than
 * acknowledging — leaves the badge and its list waiting for the human who opens the app next.
 */
internal fun shouldAutoOpenWhatsNew(
    qaMode: Boolean,
    hasUnseen: Boolean,
    alreadyShownThisProcess: Boolean,
): Boolean = !qaMode && hasUnseen && !alreadyShownThisProcess

/**
 * Loads the bundled changelog, establishes the fresh-install baseline, and
 * derives what this install has not been shown yet.
 *
 * Asset I/O and preference writes both run off the main thread. Inspection mode
 * (previews, Roborazzi snapshots) short-circuits to empty for the same reason
 * the What's new card does: the goldens must not churn on every release, and a
 * snapshot environment should not depend on asset I/O.
 */
@Composable
fun rememberWhatsNewSince(): WhatsNewSinceState {
    val context = LocalContext.current
    val inspectionMode = LocalInspectionMode.current
    var sections by remember { mutableStateOf(emptyList<WhatsNewSection>()) }
    var seen by remember { mutableStateOf<WhatsNewSeen?>(null) }

    LaunchedEffect(inspectionMode) {
        if (inspectionMode) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { loadWhatsNewSections(context.assets) }
        val marker = withContext(Dispatchers.IO) {
            // First launch after an install: silently adopt the running build,
            // pending entries included, so the surface arms from the NEXT build
            // rather than dumping the whole project history on day one.
            WhatsNewSeenStore.initialise(
                context = context,
                versionCode = BuildConfig.VERSION_CODE,
                versionName = BuildConfig.VERSION_NAME,
                entryIds = unreleasedEntryIds(loaded),
            )
        }
        sections = loaded
        seen = marker
    }

    val unseen = remember(sections, seen) { unseenSections(sections, seen) }
    return remember(unseen, seen) {
        WhatsNewSinceState(
            unseen = unseen,
            seenVersion = seen?.baseVersion,
            onMarkSeen = {
                val acknowledged = WhatsNewSeen(
                    versionCode = BuildConfig.VERSION_CODE,
                    versionName = BuildConfig.VERSION_NAME,
                    entryIds = unreleasedEntryIds(sections),
                )
                WhatsNewSeenStore.markSeen(
                    context = context,
                    versionCode = acknowledged.versionCode,
                    versionName = acknowledged.versionName,
                    entryIds = acknowledged.entryIds,
                )
                seen = acknowledged
            },
        )
    }
}

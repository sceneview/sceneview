package io.github.sceneview.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.sceneview.demo.fragments.GeneratedDemos
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.ui.RootScreen
import io.github.sceneview.sample.common.update.InAppUpdateManager
import io.github.sceneview.sample.common.update.UpdateBanner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.platform.LocalContext
import io.github.sceneview.demo.feedback.FeedbackButton
import io.github.sceneview.demo.feedback.FeedbackFlow
import io.github.sceneview.demo.feedback.FeedbackRecorder
import io.github.sceneview.demo.feedback.FeedbackRecordingService
import io.github.sceneview.demo.feedback.RecordingState
import io.github.sceneview.demo.feedback.RecordingStopPill
import io.github.sceneview.demo.feedback.rememberFeedbackRecordingLauncher
import io.github.sceneview.demo.feedback.sweepStaleFeedbackMedia

class MainActivity : ComponentActivity() {

    // Exposed (internal) so SceneViewDemoApp can hand the manager to UpdateBanner —
    // the composable only renders the downloaded-and-ready chrome.
    internal lateinit var updateManager: InAppUpdateManager

    /**
     * Latest demo id parsed from a deep-link intent (`sceneview://demo/<id>`
     * today, `https://sceneview.github.io/open?demo=<id>` once App-Links
     * verification ships). Updated from both `onCreate` (cold start) and
     * [onNewIntent] (warm start with the activity already in the
     * background). The Compose UI observes this and navigates when it
     * sees a non-null value, then resets it via [consumePendingDemo] so
     * a configuration change doesn't replay the same navigation.
     */
    private val pendingDemoId = MutableStateFlow<String?>(null)
    val pendingDemoIdFlow: StateFlow<String?> get() = pendingDemoId.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Clean up any feedback recording stranded in the cache by a prior run.
        sweepStaleFeedbackMedia(this)
        updateManager = InAppUpdateManager(this)
        // Two ingress channels: (1) `--es demo <id>` from `adb shell am` for QA / instrumented
        // tests, (2) URL deep-links via the public sceneview://demo/<id> scheme parsed by
        // DeepLinkRouter. The QA channel takes precedence so a tester running adb against a
        // running app can deterministically navigate without competing with a stale URL intent.
        // Same allow-list (ALL_DEMOS) gates both ingress channels — without it
        // any app could call `am start ... --es demo whatever` and steer
        // navigation through PlaceholderDemo. See #958.
        pendingDemoId.value = DeepLinkRouter.validate(intent?.getStringExtra("demo"))
            ?: DeepLinkRouter.parse(intent?.data)
        // QA mode ingress: `--ez qa_mode true` freezes auto-rotation / orbit / animations
        // so screenshot tests get a deterministic frame. Same setting reachable via the
        // long-press gesture on the demo title bar (see DemoScaffold). Off by default.
        // QA-mode tracks the latest intent, not "stickily true forever" — otherwise any
        // app on the device could flip it on once via `--ez qa_mode true` and leave the
        // showcase frozen until process death.
        DemoSettings.qaMode = intent?.getBooleanExtra("qa_mode", false) ?: false
        // Optional path to an ARCore playback fixture (.mp4). Confined to the app's own
        // external-files dir so a malicious deep link can't probe arbitrary device paths
        // (`/data/data/...`, photos, configs). The path is consumed once by
        // `ARRecordPlaybackDemo` then nulled.
        DemoSettings.arPendingPlaybackFile = intent?.getStringExtra("ar_playback_file")
            ?.takeIf { isWithinAppFilesDir(it) }
        // Optional camera-to-model distance (zoom level). Maestro has no pinch gesture, so
        // the device-QA flows drive 3D zoom by deep link instead (#1571). Same dual-ingress
        // policy: the `--ef camera_distance <f>` QA extra wins over the URL query parameter.
        // Both go through DeepLinkRouter.validateCameraDistance, so a non-finite or
        // out-of-range value is dropped to null (default framing) rather than crashing.
        DemoSettings.cameraDistance = resolveCameraDistance(intent)
        setContent {
            SceneViewDemoTheme {
                SceneViewDemoApp(activity = this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Replace the activity intent so subsequent re-creations see the new
        // deep link, not the original launcher intent.
        setIntent(intent)
        // Same dual-ingress policy as onCreate — `--es demo` first, URL second.
        // Both go through DeepLinkRouter.validate / .parse so unknown ids are
        // dropped rather than routed to PlaceholderDemo. See #958.
        pendingDemoId.value = DeepLinkRouter.validate(intent.getStringExtra("demo"))
            ?: DeepLinkRouter.parse(intent.data)
        DemoSettings.qaMode = intent.getBooleanExtra("qa_mode", false)
        DemoSettings.arPendingPlaybackFile = intent.getStringExtra("ar_playback_file")
            ?.takeIf { isWithinAppFilesDir(it) }
        DemoSettings.cameraDistance = resolveCameraDistance(intent)
    }

    /**
     * Resolves the optional camera-to-model distance (zoom level) from an incoming intent.
     *
     * Two ingress channels, mirroring the `demo` extra's dual-channel policy:
     *  1. `--ef camera_distance <f>` — the QA extra, used by the Maestro device-QA flows
     *     and `adb shell am`. Takes precedence.
     *  2. `?cameraDistance=<f>` query parameter on a `sceneview://demo/<id>` URL deep link.
     *
     * Both are clamped by [DeepLinkRouter.validateCameraDistance] / [parseCameraDistance] to
     * a finite, in-range value; anything absent, unparseable, or out of range resolves to
     * `null` so the launched demo keeps its own auto-fit framing and never crashes.
     *
     * `Float.NaN` is the sentinel for "extra absent" — `getFloatExtra` has no nullable
     * overload — and `validateCameraDistance` rejects NaN, so an absent extra correctly
     * falls through to the URL channel.
     */
    private fun resolveCameraDistance(intent: Intent?): Float? {
        if (intent == null) return null
        val fromExtra = DeepLinkRouter.validateCameraDistance(
            intent.getFloatExtra("camera_distance", Float.NaN),
        )
        return fromExtra ?: DeepLinkRouter.parseCameraDistance(intent.data)
    }

    fun consumePendingDemo() {
        pendingDemoId.value = null
    }

    /**
     * Returns `true` if the given path is inside this app's external-files directory
     * (the only location where AR fixtures legitimately live). Anything else — system
     * paths, other apps' data, photos, downloads — gets rejected. Without this guard,
     * any app on the device could craft a deep link with `--es ar_playback_file <path>`
     * and trick the demo into opening arbitrary files (Logcat would log the path,
     * leaking it). MP4 parsing itself is safe (ARCore rejects non-datasets), but
     * defence-in-depth.
     */
    private fun isWithinAppFilesDir(path: String): Boolean {
        val base = getExternalFilesDir(null)?.absolutePath ?: return false
        val canonical = runCatching { java.io.File(path).canonicalPath }.getOrNull() ?: return false
        return canonical.startsWith(base)
    }

    override fun onResume() {
        super.onResume()
        // Two phases (#890): handle a partially-downloaded update from a prior session,
        // then proactively check the Play Console for a newer release. Without
        // checkForUpdate() the flexible-update flow never starts, so the UpdateBanner
        // composable below also never lights up — making the entire in-app-update
        // pipeline a phantom on production builds.
        updateManager.checkForStalledUpdate()
        updateManager.checkForUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        updateManager.destroy()
    }
}

/**
 * Bottom inset for the feedback FAB on the tab screens — clears the 80 dp M3
 * `NavigationBar` plus a 16 dp gap.
 */
private val FEEDBACK_FAB_BOTTOM_INSET = 96.dp

@Composable
fun SceneViewDemoApp(activity: MainActivity? = null) {
    val navController = rememberNavController()

    // Watch for deep-link intents. On a non-null id we either navigate
    // directly (the demo list is the start destination, so navigate adds
    // the demo screen on top — back returns to the list) and immediately
    // consume the pending id so a config change doesn't replay it.
    val pendingId by (activity?.pendingDemoIdFlow?.collectAsState()
        ?: remember { MutableStateFlow<String?>(null) }.collectAsState())
    // Capture the launch-time deep-link id ONCE, so NavHost picks the right
    // start destination on first composition. The LaunchedEffect below still
    // handles subsequent intents (onNewIntent → pendingDemoIdFlow updates).
    val initialDemo = remember { activity?.pendingDemoIdFlow?.value }
    LaunchedEffect(pendingId) {
        val id = pendingId ?: return@LaunchedEffect
        // If the cold-start `initialDemo` already matches `pendingId`, NavHost picked the
        // demo as its start destination — navigating here would push a SECOND instance,
        // destroying the first one's remember{} state (and any one-shot flags like
        // `DemoSettings.arPendingPlaybackFile` that were already consumed). Just clear
        // the pending id so config changes don't replay it.
        if (id != initialDemo) {
            navController.navigate("demo/$id")
        }
        activity?.consumePendingDemo()
    }

    // Wrap the NavHost and the in-app update banner in an explicit Box so the
    // banner is z-ordered ON TOP of every screen (#1425). Previously the banner
    // and the NavHost were sibling root composables — the demo screens' own
    // TopAppBar then drew over the banner, clipping the "Update ready / Restart"
    // chrome. Drawing the banner last in the Box guarantees it stays visible.
    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = if (initialDemo != null) "demo/$initialDemo" else "list",
            enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
            exitTransition = { slideOutHorizontally(targetOffsetX = { -it / 4 }) + fadeOut() },
            popEnterTransition = { slideInHorizontally(initialOffsetX = { -it / 4 }) + fadeIn() },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) + fadeOut() }
        ) {
            composable("list") {
                // New 4-tab root (Explore / AR View / Samples / About). The legacy
                // category-grouped DemoListScreen lives untouched inside the
                // "Samples" tab so existing deep-link flows (`adb am start ... --es
                // demo <id>`) and the in-app update banner remain wired up.
                RootScreen(onDemoClick = { id -> navController.navigate("demo/$id") })
            }
            composable("demo/{id}") { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id") ?: return@composable
                val onBack: () -> Unit = { navController.popBackStack() }
                DemoRouter(id = id, onBack = onBack)
            }
            // Note: the legacy `composable("about") { AboutScreen(...) }` route was
            // removed (alongside `AboutScreen.kt`) because RootScreen's bottom-tab
            // `RootTab.About → AboutTabContent()` is the only About surface — no
            // caller ever navigated to "about". Deletion verified via grep:
            // 0 `navigate("about")` calls anywhere. The @Ignore'd ScreenshotTest
            // for AboutScreen stays disabled per its own comment.
        }

        // The update banner is a no-op when state is IDLE / CHECKING /
        // UP_TO_DATE — it only renders during DOWNLOADING / READY_TO_INSTALL so
        // it doesn't take screen real estate from demos (#890). The status-bar
        // inset keeps it clear of the system bar so the banner is never clipped
        // behind the status bar or a demo's top app bar (#1425).
        activity?.updateManager?.let { mgr ->
            UpdateBanner(
                updateManager = mgr,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars),
            )
        }

        // Feedback entry point — shown only on the four tab screens. Demo
        // screens place their own controls in every corner (movement pads, AR
        // action buttons), so a floating FAB there would collide; an in-demo
        // feedback entry point is a separate follow-up (#1932).
        val context = LocalContext.current
        val currentEntry by navController.currentBackStackEntryAsState()
        val onListScreen = currentEntry?.destination?.route == "list"
        var feedbackOpen by rememberSaveable { mutableStateOf(false) }
        val recordingState by FeedbackRecorder.state.collectAsState()
        val isRecording = recordingState is RecordingState.Recording
        val startRecording = rememberFeedbackRecordingLauncher()

        // A finished or failed recording re-opens the flow at its review step.
        LaunchedEffect(recordingState) {
            if (recordingState is RecordingState.Done ||
                recordingState is RecordingState.Failed
            ) {
                feedbackOpen = true
            }
        }

        if (onListScreen && !isRecording) {
            FeedbackButton(
                onClick = { feedbackOpen = true },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 16.dp, bottom = FEEDBACK_FAB_BOTTOM_INSET),
            )
        }

        // While recording, the dialog is hidden so the user can demonstrate the
        // bug; a floating Stop pill stays on top of every screen.
        if (isRecording) {
            RecordingStopPill(
                onStop = { FeedbackRecordingService.stop(context) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 24.dp),
            )
        }

        if (feedbackOpen && !isRecording) {
            FeedbackFlow(
                onDismiss = {
                    feedbackOpen = false
                    FeedbackRecorder.reset()
                },
                onStartRecording = startRecording,
            )
        }
    }
}

/**
 * Routes a demo [id] to the corresponding composable.
 *
 * Both ingress channels ([MainActivity.onCreate] deep-links and the `--es demo`
 * QA extra) validate against [ALL_DEMOS] via [DeepLinkRouter] before an id ever
 * reaches here — so a missing branch is unreachable in a correct build. The
 * fallback to [PlaceholderDemo] is kept as a registry/router drift guard: a
 * debug build crashes loudly if [ALL_DEMOS] ships an id that has no matching
 * fragment, while a release build degrades gracefully rather than crashing a
 * shipped app.
 *
 * The actual routing table is generated by the
 * `samples/android-demo/scripts/collate-demos.sh` collator from the per-demo
 * fragments under `io.github.sceneview.demo.fragments` (#1797). Adding a demo
 * does NOT require touching this file.
 */
@Composable
fun DemoRouter(id: String, onBack: () -> Unit) {
    val matched = GeneratedDemos.Screen(id = id, onBack = onBack)
    if (!matched) {
        check(!BuildConfig.DEBUG) {
            "DemoRouter has no fragment for demo id '$id'. Every ALL_DEMOS entry " +
                "must be backed by a *Fragment.kt under io.github.sceneview.demo.fragments — " +
                "add one and run samples/android-demo/scripts/collate-demos.sh."
        }
        PlaceholderDemo(id = id, onBack = onBack)
    }
}

@Composable
fun PlaceholderDemo(id: String, onBack: () -> Unit) {
    val entry = ALL_DEMOS.find { it.id == id }
    DemoScaffold(
        title = entry?.titleRes?.let { stringResource(it) } ?: id,
        onBack = onBack
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.demo_coming_soon),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

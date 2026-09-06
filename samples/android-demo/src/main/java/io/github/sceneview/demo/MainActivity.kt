package io.github.sceneview.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
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
import androidx.navigation.NavType
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.sceneview.demo.fragments.GeneratedDemos
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.RootScreen
import io.github.sceneview.sample.common.update.InAppUpdateManager
import io.github.sceneview.sample.common.update.UpdateBanner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.platform.LocalContext
import io.github.sceneview.demo.feedback.BugReportSheet
import io.github.sceneview.demo.feedback.CurrentRootScreen
import io.github.sceneview.demo.feedback.FeedbackOpenRequest
import io.github.sceneview.demo.feedback.PendingBugReport
import io.github.sceneview.demo.feedback.ReportScreen
import io.github.sceneview.demo.feedback.captureBugReportInfo
import io.github.sceneview.demo.feedback.captureBugReportScreenshot
import io.github.sceneview.demo.feedback.sweepStaleFeedbackMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    // Exposed (internal) so SceneViewDemoApp can hand the manager to UpdateBanner —
    // the composable renders the demo-native update-available / downloading /
    // ready-to-restart chrome.
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

    /**
     * A model file another app asked this one to open through `ACTION_VIEW`, or an `ACTION_SEND`
     * from a share sheet (#3482).
     *
     * Staging copies the bytes into the cache off the main thread, so this arrives *after* the
     * first composition; the UI observes it and navigates to the Model Viewer when it lands.
     * Nulled by [consumePendingOpenedModel] once navigated, so a configuration change does not
     * re-open the same file.
     */
    private val pendingOpenedModel = MutableStateFlow<OpenedModel?>(null)
    val pendingOpenedModelFlow: StateFlow<OpenedModel?> get() = pendingOpenedModel.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Clean up any feedback recording stranded in the cache by a prior run.
        sweepStaleFeedbackMedia(this)
        updateManager = InAppUpdateManager(this)
        // Register the activity-result launcher for Google's FLEXIBLE consent
        // modal BEFORE the activity reaches STARTED. Cancelling that modal is
        // delivered here (RESULT_CANCELED) — without it a cancel would strand
        // the in-app Update button as a permanent no-op (#1942 review).
        updateManager.registerForResult(this)
        // Two ingress channels: (1) `--es demo <id>` from `adb shell am` for QA / instrumented
        // tests, (2) URL deep-links via the public sceneview://demo/<id> scheme parsed by
        // DeepLinkRouter. The QA channel takes precedence so a tester running adb against a
        // running app can deterministically navigate without competing with a stale URL intent.
        // Same allow-list (ALL_DEMOS) gates both ingress channels — without it
        // any app could call `am start ... --es demo whatever` and steer
        // navigation through PlaceholderDemo. See #958.
        pendingDemoId.value = DeepLinkRouter.validate(intent?.getStringExtra("demo"))
            ?: DeepLinkRouter.parse(intent?.data)
        // "Open with SceneView": a supported model file handed over by another app (#3482).
        stageOpenedModel(intent)
        // QA mode ingress: `--ez qa_mode true` freezes auto-rotation / orbit / animations
        // so screenshot tests get a deterministic frame. Same setting reachable via the
        // long-press gesture on the demo title bar (see DemoScaffold). Off by default.
        // QA-mode tracks the latest intent, not "stickily true forever" — otherwise any
        // app on the device could flip it on once via `--ez qa_mode true` and leave the
        // showcase frozen until process death.
        DemoSettings.qaMode = intent?.getBooleanExtra("qa_mode", false) ?: false
        DemoSettings.qaBackdrop = resolveQaBackdrop(intent)
        // Optional id of a visual state a demo should pin itself to (#3421, #3455). One extra
        // for every demo's vocabulary (Cloud Anchors, Point & Ask). Only honoured in QA mode
        // — a forced state makes a demo show something that never happened.
        DemoSettings.qaDemoState = resolveQaDemoState(intent)
        // Optional path to an ARCore playback fixture (.mp4). Confined to the app's own
        // external-files dir so a malicious deep link can't probe arbitrary device paths
        // (`/data/data/...`, photos, configs). The path is consumed once by
        // `ARRecordPlaybackDemo` then nulled.
        DemoSettings.arPendingPlaybackFile = intent?.getStringExtra("ar_playback_file")
            ?.takeIf { isWithinAppFilesDir(it) }
        // Optional camera-to-model distance (zoom level). Maestro has no pinch gesture, so
        // the device-QA flows drive 3D zoom by deep link instead (#1571). Same dual-ingress
        // policy: the `camera_distance` QA extra (any Bundle type — Float from `adb --ef`,
        // String from Maestro launchApp arguments, #2652) wins over the URL query parameter.
        // Both go through the DeepLinkRouter clamp, so a non-finite, unparseable, or
        // out-of-range value is dropped to null (default framing) rather than crashing.
        DemoSettings.cameraDistance = resolveCameraDistance(intent)
        // Optional initial tab for a consolidated (segmented-button) demo. Set from the alias
        // that opened it (`--es demo shape` → Shape tab) or an explicit `--es tab <i>` extra /
        // `?tab=<id|index>` query. Absent / unparseable → null (demo keeps its default tab).
        DemoSettings.initialTab = resolveInitialTab(intent)
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
        stageOpenedModel(intent)
        DemoSettings.qaMode = intent.getBooleanExtra("qa_mode", false)
        DemoSettings.qaBackdrop = resolveQaBackdrop(intent)
        DemoSettings.qaDemoState = resolveQaDemoState(intent)
        DemoSettings.arPendingPlaybackFile = intent.getStringExtra("ar_playback_file")
            ?.takeIf { isWithinAppFilesDir(it) }
        DemoSettings.cameraDistance = resolveCameraDistance(intent)
        DemoSettings.initialTab = resolveInitialTab(intent)
    }

    /**
     * `--ez qa_backdrop true|false` forces the QA camera backdrop on or off (#3308); absent,
     * it follows `qa_mode`. Tracks the latest intent like `qa_mode` does.
     */
    private fun resolveQaBackdrop(intent: Intent?): Boolean? =
        intent?.takeIf { it.hasExtra("qa_backdrop") }?.getBooleanExtra("qa_backdrop", false)

    /**
     * `--es qa_state <id>` pins a demo to one named visual state (#3421, #3455) — the one
     * seam the emulator smoke suite uses to capture states that need hardware or services
     * `emulator-5554` does not have (#2754): Cloud Anchor host / resolve states (a live
     * cloud project and a second device), Point & Ask cards (AICore). Each demo owns its
     * own ids; see [DemoSettings.qaDemoState].
     *
     * Gated on `qa_mode` by [DeepLinkRouter.resolveQaState], deliberately: unlike the camera
     * backdrop, which only changes what is *behind* the scene, this changes what the demo
     * *claims*, so it must never be reachable from an ordinary launch. Reads
     * [DemoSettings.qaMode] rather than the extra again so the gate and the flag it guards
     * cannot disagree. Tracks the latest intent like the other QA extras.
     */
    private fun resolveQaDemoState(intent: Intent?): String? =
        DeepLinkRouter.resolveQaState(DemoSettings.qaMode, intent?.getStringExtra("qa_state"))

    /**
     * Resolves the optional initial tab a consolidated demo should pre-select from an
     * incoming intent (#2315). Mirrors the `demo` / `camera_distance` dual-ingress policy:
     * the `--es tab <v>` QA extra wins over the `?tab=<v>` URL query, and both fall back to
     * the alias that launched the demo (`--es demo shape` → the Shape tab of
     * `custom-geometry`). [DeepLinkRouter.resolveInitialTab] owns the precedence + parsing;
     * an absent / unparseable value resolves to `null` so the demo keeps its default first
     * tab and never crashes on a bad index.
     */
    private fun resolveInitialTab(intent: Intent?): Int? {
        if (intent == null) return null
        val rawId = intent.getStringExtra("demo")
            ?: intent.data?.let(DeepLinkRouter::extractCandidate)
        val tabParam = intent.getStringExtra(DeepLinkRouter.QUERY_PARAM_TAB)
            ?: DeepLinkRouter.parseTabParam(intent.data)
        return DeepLinkRouter.resolveInitialTab(rawId, tabParam)
    }

    /**
     * Resolves the optional camera-to-model distance (zoom level) from an incoming intent.
     *
     * Two ingress channels, mirroring the `demo` extra's dual-channel policy:
     *  1. The `camera_distance` intent extra — used by the Maestro device-QA flows and
     *     `adb shell am`. Takes precedence. The extra's Bundle type depends on the
     *     sender — `adb --ef` delivers a `Float`, but Maestro's `launchApp` delivers
     *     env-interpolated values as `String` extras (and could deliver a bare YAML
     *     number as `Integer`/`Double`) — so the raw value is read type-agnostically and
     *     coerced by [DeepLinkRouter.coerceCameraDistanceExtra]. Reading only
     *     `getFloatExtra` here silently ignored Maestro's zoom parameter (#2652).
     *  2. `?cameraDistance=<f>` query parameter on a `sceneview://demo/<id>` URL deep link.
     *
     * Both are clamped by [DeepLinkRouter.coerceCameraDistanceExtra] /
     * [DeepLinkRouter.parseCameraDistance] to a finite, in-range value; anything absent,
     * unparseable, of an unsupported type, or out of range resolves to `null` so the
     * launched demo keeps its own auto-fit framing and never crashes.
     */
    private fun resolveCameraDistance(intent: Intent?): Float? {
        if (intent == null) return null
        // Bundle.get is deprecated in favour of the typed getters, but "which type did
        // the sender use?" is exactly the unknown being resolved here — the typed
        // getters would each silently drop the other senders' encodings.
        @Suppress("DEPRECATION")
        val fromExtra = DeepLinkRouter.coerceCameraDistanceExtra(
            intent.extras?.get("camera_distance"),
        )
        return fromExtra ?: DeepLinkRouter.parseCameraDistance(intent.data)
    }

    /**
     * Copies a model handed over by another app into the cache, then publishes it for the UI.
     *
     * Off the main thread, because the file is read and rewritten byte for byte and a shared print
     * can be tens of megabytes — doing it in `onCreate` would drop frames before the first one is
     * ever drawn. The intent is inspected synchronously (cheap, and the read grant is live now);
     * only the copy is deferred. An intent that carries no model, or a file that turns out not to
     * be one, leaves the app on its normal start destination.
     */
    private fun stageOpenedModel(intent: Intent?) {
        val uri = OpenedModelIntent.modelUri(intent) ?: return
        val mimeType = intent?.type
        lifecycleScope.launch {
            val opened = withContext(Dispatchers.IO) {
                OpenedModelIntent.stage(this@MainActivity, uri, mimeType)
            }
            if (opened == null) {
                // Silence would read as "the app is broken": the user tapped a file and landed on
                // the demo list with no explanation. Say what did not happen, once.
                android.widget.Toast.makeText(
                    this@MainActivity,
                    getString(R.string.open_model_failed),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            withContext(Dispatchers.IO) {
                OpenedModelIntent.sweep(this@MainActivity, opened.file())
            }
            DemoSettings.openedModelSizeMeters = null
            pendingOpenedModel.value = opened
        }
    }

    fun consumePendingOpenedModel() {
        pendingOpenedModel.value = null
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
        // then proactively check the Play Console for a newer release. Called from
        // onResume (not onCreate) so a backgrounded-then-resumed app re-checks.
        // checkForUpdate() only surfaces the AVAILABLE state — it never pops the
        // Google consent modal; that happens solely on the user's deliberate tap of
        // the in-app "Update" button rendered by UpdateBanner below (#1941).
        updateManager.checkForStalledUpdate()
        updateManager.checkForUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        updateManager.destroy()
    }
}

@Composable
fun SceneViewDemoApp(activity: MainActivity? = null) {
    val navController = rememberNavController()
    val requestedRoute = DemoSettings.requestedRoute
    LaunchedEffect(requestedRoute) {
        requestedRoute?.let {
            DemoSettings.requestedRoute = null
            navController.navigate(it)
        }
    }

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
    // "Open with SceneView" (#3482). Staging is async, so this always arrives after the first
    // composition — never as a NavHost start destination. The Model Viewer is the target because
    // it is the app's flagship viewer: framing, lighting, animation and the View-in-AR handoff are
    // all already there, and an opened file deserves the same screen a bundled one gets.
    val openedModel by (activity?.pendingOpenedModelFlow?.collectAsState()
        ?: remember { MutableStateFlow<OpenedModel?>(null) }.collectAsState())
    LaunchedEffect(openedModel) {
        val opened = openedModel ?: return@LaunchedEffect
        DemoSettings.openedModel = opened
        navController.navigate("demo/model-viewer") {
            // One viewer on the stack however many files are opened in a row.
            popUpTo("demo/model-viewer") { inclusive = true }
        }
        activity?.consumePendingOpenedModel()
    }

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
            // Material shared-axis X (#3406): both screens move a short distance in the
            // same direction while they cross-fade, on `duration-medium` /
            // `ease-expressive` from DESIGN.md. The full-window slide this replaced was
            // an iOS-style push — the incoming screen travelled a whole viewport on
            // Compose's default spring, which on a demo that then has to load a model
            // read as two separate waits stacked on each other.
            enterTransition = { slideInHorizontally(navMotion()) { it / NAV_SLIDE_FRACTION } + fadeIn(navMotion()) },
            exitTransition = { slideOutHorizontally(navMotion()) { -it / NAV_SLIDE_FRACTION } + fadeOut(navMotion()) },
            popEnterTransition = { slideInHorizontally(navMotion()) { -it / NAV_SLIDE_FRACTION } + fadeIn(navMotion()) },
            popExitTransition = { slideOutHorizontally(navMotion()) { it / NAV_SLIDE_FRACTION } + fadeOut(navMotion()) }
        ) {
            composable("list") {
                // Three-tab root (Showcase / AR View / About). Demo deep links
                // (`adb am start ... --es demo <id>`) never land here — they
                // navigate straight to "demo/<id>" (see the pending-demo
                // handling above) — and the in-app update banner floats over
                // this whole Box, so both stay wired up whatever tab is active.
                RootScreen(onDemoClick = { id -> navController.navigate("demo/$id") })
            }
            composable(
                route = "demo/{id}?model={model}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("model") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id") ?: return@composable
                DemoSettings.requestedModel = backStackEntry.arguments?.getString("model")
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
        // UP_TO_DATE — it renders the demo-native AVAILABLE / DOWNLOADING /
        // READY_TO_INSTALL chrome so the whole flexible-update flow stays inside
        // the app's own UI (#890, #1941).
        //
        // `safeDrawing`, not `statusBars` — this banner floats over the entire
        // app, above every screen, so it meets a display cutout that `statusBars`
        // does not describe: in landscape the notch is a *side* inset, and the
        // banner ran straight under it. Top + Horizontal is the same frame
        // DemoScaffold now applies to its own top overlays, which is the point —
        // this used to be the third distinct inset spelling in the app (#3237).
        activity?.updateManager?.let { mgr ->
            UpdateBanner(
                updateManager = mgr,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                        )
                    ),
            )
        }

        // Bug-report entry points (#1930, rebuilt permission-free in #2188's
        // successor):
        //  - the tab host ("list" — RootScreen, every tab) gets the
        //    extended FAB rendered below;
        //  - every demo screen gets a top-app-bar feedback action in
        //    DemoScaffold, which raises FeedbackOpenRequest (observed here).
        // A demo screen places its own controls in every corner (movement
        // pads, AR action buttons, the Tune FAB), so a floating FAB there
        // would collide — the top-bar action is the right slot.
        val context = LocalContext.current
        val currentEntry by navController.currentBackStackEntryAsState()
        val onListScreen = currentEntry?.destination?.route == "list"

        // The pending report (metadata + logcat + screenshot), captured the
        // moment the user asks to report — BEFORE the sheet is composed, so
        // the sheet itself is never in the screenshot. Not rememberSaveable:
        // a Bitmap can't ride a config change; the sheet simply closes, and
        // re-opening re-captures in <100 ms.
        var bugReport by remember { mutableStateOf<PendingBugReport?>(null) }
        val reportScope = rememberCoroutineScope()

        fun openBugReport() {
            if (bugReport != null) return
            // Capture the current screen NOW (the sheet host is the only place
            // that can see the NavController), so the report names where the
            // bug is (#1934, #3390).
            //
            // The demo id is read straight off the arguments rather than by
            // comparing the route to a literal: the literal was "demo/{id}"
            // while the declared route had grown a "?model={model}" argument,
            // so the id silently came back null and every report shipped
            // without a screen (#3390). Only the demo destination declares an
            // `id`, so this is null on the tab host by construction.
            val entry = currentEntry
            val screen = ReportScreen(
                demoId = entry?.arguments?.getString("id"),
                rootScreen = CurrentRootScreen.label,
                route = entry?.destination?.route,
            )
            reportScope.launch {
                val screenshot = activity?.let { captureBugReportScreenshot(it) }
                bugReport = PendingBugReport(
                    info = captureBugReportInfo(context, screen),
                    screenshot = screenshot,
                )
            }
        }

        // A demo screen's top-app-bar feedback action raises this flag — open
        // the shared sheet and consume the request (#1930).
        val feedbackRequested by FeedbackOpenRequest.requested.collectAsState()
        LaunchedEffect(feedbackRequested) {
            if (feedbackRequested) {
                FeedbackOpenRequest.consume()
                openBugReport()
            }
        }

        bugReport?.let { report ->
            BugReportSheet(
                report = report,
                // The screenshot PNG is deliberately NOT deleted here: a share
                // target (e.g. Gmail) may read the FileProvider URI lazily,
                // after the sheet is gone. The app-start sweep
                // (sweepStaleFeedbackMedia) reclaims the few-hundred-KB file
                // on the next launch instead.
                // No "sent" confirmation on return to the app (#3398): the
                // hand-off itself is the acknowledgement — the browser opens
                // on the pre-filled GitHub issue, or the system share sheet
                // takes over. The snackbar that used to greet the user back
                // (#3263, positioned in #3325) was redundant with it, and
                // could not be truthful anyway — with no GitHub API call on
                // device, "sent" only ever meant "intent launched".
                onDismiss = { bugReport = null },
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

/**
 * Fraction of the viewport a screen travels during a navigation transition — a
 * sixth, the short shared-axis distance, not the full-window push (#3406).
 */
private const val NAV_SLIDE_FRACTION = 6

/** The one spec every navigation slide and fade shares: `duration-medium`, `ease-expressive`. */
private fun <T> navMotion(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
    androidx.compose.animation.core.tween(
        durationMillis = SceneViewTokens.Duration.mediumMillis,
        easing = SceneViewTokens.Ease.expressive,
    )

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

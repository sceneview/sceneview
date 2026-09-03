package io.github.sceneview.demo.demos

import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.ResolveCloudAnchorFuture
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARCoreAvailabilityOverlay
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.CloudAnchorNode as CloudAnchorNodeImpl
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.CloudAnchorFlowCard
import io.github.sceneview.demo.common.CloudServiceStatus
import io.github.sceneview.demo.common.CloudServiceStatusBanner
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.ForceCloudAnchorScenarioMenu
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedCloudAnchorScenario
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.QaCameraBackdrop
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
import io.github.sceneview.demo.common.clipboardText
import io.github.sceneview.demo.common.copyToClipboard
import io.github.sceneview.demo.common.qaCameraBackdropEnabled
import io.github.sceneview.demo.common.qaCameraBackdropSurfaceType
import io.github.sceneview.demo.common.qaStateOverridesAllowed
import io.github.sceneview.demo.common.rememberHasArcoreApiKey
import io.github.sceneview.demo.common.rememberIsNetworkAvailable
import io.github.sceneview.demo.common.rememberQaCameraBackdropActive
import io.github.sceneview.demo.common.shareText
import io.github.sceneview.demo.common.toCloudServiceStatus
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.demos.internal.CLOUD_ANCHOR_TTL_DAYS
import io.github.sceneview.demo.demos.internal.CloudAnchorAction
import io.github.sceneview.demo.demos.internal.CloudAnchorBlocker
import io.github.sceneview.demo.demos.internal.CloudAnchorFlowState
import io.github.sceneview.demo.demos.internal.CloudAnchorStatusIcon
import io.github.sceneview.demo.demos.internal.CloudAnchorStep
import io.github.sceneview.demo.demos.internal.CloudAnchorTask
import io.github.sceneview.demo.demos.internal.RoomQuality
import io.github.sceneview.demo.demos.internal.actionBar
import io.github.sceneview.demo.demos.internal.allows
import io.github.sceneview.demo.demos.internal.card
import io.github.sceneview.demo.demos.internal.cloudAnchorFailureOf
import io.github.sceneview.demo.demos.internal.cloudAnchorScenarioOf
import io.github.sceneview.demo.demos.internal.cloudAnchorShareText
import io.github.sceneview.demo.demos.internal.needsExplanationCard
import io.github.sceneview.demo.demos.internal.state
import io.github.sceneview.demo.demos.internal.status
import io.github.sceneview.demo.demos.internal.trimmedCode
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import kotlinx.coroutines.delay

/**
 * Cloud anchor persistence demo — a deliberate two-step flow (#3421).
 *
 * **Host**: place an anchor on a surface, walk around it until ARCore says the room is
 * mapped well enough, upload it, and hand the resulting code to another device via the
 * clipboard or the system share sheet. **Resolve**: paste a code someone shared, resolve
 * it, and see the same content anchored in the same physical spot.
 *
 * ## What #3421 changed, and why
 *
 * The screen this replaces presented Host and Resolve as two peer buttons over a single
 * anchor slot, with a free-form `String` for status that the on-screen banner mostly
 * ignored. The consequences were not cosmetic:
 *
 *  - Tapping **Host** changed nothing on screen. `"Hosting anchor…"` was written into
 *    `statusMessage`, but the banner's `when` chain answered `"Anchor placed — tap Host
 *    to share it to the cloud"` first and unconditionally. Resolve had the same bug.
 *  - The banner's severity came from `statusMessage.contains("failed")` — the tone of a
 *    state decided by substring-matching an English sentence.
 *  - Failures printed the raw ARCore constant (`ERROR_HOSTING_DATASET_PROCESSING_FAILED`)
 *    into the coaching pill.
 *  - The sheet told the user to "copy" the hosted id; nothing on the screen could copy it,
 *    and the id itself was rendered as wrapping 13 sp body text.
 *  - **Host** stayed enabled with no anchor placed, with a completely unmapped room (the
 *    dominant real cause of a rejected upload), and again after a successful *resolve* —
 *    offering to host an anchor that was already hosted.
 *  - The resolve field was on screen while the user was placing an anchor to host, and
 *    vanished the moment a resolve succeeded. Once an anchor was placed there was no way
 *    to start over: `onReset` was never passed to the scaffold.
 *  - `ResolveCloudAnchorFuture` was dropped on the floor, against its own KDoc — every
 *    abandoned resolve kept accruing a billing event.
 *
 * All of the decision-making now lives in `demos/internal/CloudAnchorFlow.kt` as pure
 * functions of one [CloudAnchorFlowState], pinned by `CloudAnchorFlowTest`. This file
 * only wires ARCore signals in and renders what the flow says. That split is what makes
 * the screen testable at all: `emulator-5554` cannot run ARCore (#2754), so the state
 * machine would otherwise be validated nowhere but a physical device.
 *
 * Requires the ARCore Cloud Anchor API to be enabled and an API key wired in — see
 * `samples/android-demo/ARCORE_CLOUD_SETUP.md`. When it is not, the screen says so in a
 * card instead of offering controls that cannot work (the #3374 pattern).
 */
@Composable
fun ARCloudAnchorDemo(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cameraStream = rememberARCameraStream(materialLoader)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Detect at runtime whether the build wired an ARCore Cloud API key into the manifest.
    // Absent (a fork without the GitHub secret, or no ARCORE_API_KEY in local.properties),
    // host()/resolve() come back with ERROR_NOT_AUTHORIZED and nothing explains why.
    val hasArcoreApiKey = rememberHasArcoreApiKey()
    // Preemptive network check (#3262): a Cloud call with no network otherwise looks
    // identical to one ARCore silently swallowed.
    val isNetworkAvailable = rememberIsNetworkAvailable()

    // ── Raw ARCore signals ──────────────────────────────────────────────────
    var localAnchor by remember { mutableStateOf<Anchor?>(null) }
    var placedAnchorId by remember { mutableStateOf<String?>(null) }
    var isTracking by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    var arSession by remember { mutableStateOf<Session?>(null) }
    var cloudNode by remember { mutableStateOf<CloudAnchorNodeImpl?>(null) }
    var resolveFuture by remember { mutableStateOf<ResolveCloudAnchorFuture?>(null) }
    val qaBackdrop = rememberQaCameraBackdropActive(cameraReady)

    // ── Flow state ──────────────────────────────────────────────────────────
    // `rememberSaveable` on the two pieces a rotation must not destroy: a hosted code the
    // user is about to share, and a code they have just pasted. Both were plain
    // `remember` before, so turning the phone threw away the whole point of the screen.
    var step by rememberSaveable { mutableStateOf(CloudAnchorStep.Host) }
    var codeInput by rememberSaveable { mutableStateOf("") }
    var hostedCode by rememberSaveable { mutableStateOf<String?>(null) }
    var hostTask by remember { mutableStateOf<CloudAnchorTask>(CloudAnchorTask.Idle) }
    var resolveTask by remember { mutableStateOf<CloudAnchorTask>(CloudAnchorTask.Idle) }
    var roomQuality by remember { mutableStateOf(RoomQuality.Insufficient) }
    var justCopied by remember { mutableStateOf(false) }
    // Set from a host()/resolve() result when ARCore reports one of the shared
    // Cloud-service failure reasons (#3262); cleared whenever a new attempt starts.
    var operationCloudStatus by remember { mutableStateOf<CloudServiceStatus?>(null) }

    // The shared cross-demo verdict on whether a Cloud call can be attempted at all
    // (#3262) — identical wording in all five Cloud demos.
    val cloudStatus: CloudServiceStatus = when {
        !hasArcoreApiKey -> CloudServiceStatus.ApiKeyMissing
        !isNetworkAvailable -> CloudServiceStatus.NoNetwork
        else -> operationCloudStatus ?: CloudServiceStatus.Available
    }

    // ARCore's own reason for not tracking, when it has one — "It's too dark", "Move the
    // device more slowly" — worded once in `common/TrackingFailureMessages.kt` for all 28
    // AR demos. `ForcedTrackingFailure` (#1881) lets QA emit each without staging it.
    val effectiveTrackingFailure = ForcedTrackingFailure.override ?: trackingFailureReason
    val trackingHint = trackingFailureMessage(effectiveTrackingFailure)

    val liveState = CloudAnchorFlowState(
        step = step,
        blocker = when {
            // A session that will never start outranks every Cloud reason: ARSceneView
            // already draws the SDK's own, better-informed card over the viewport (#3374).
            arCoreAvailability != null -> CloudAnchorBlocker.ArUnavailable
            cloudStatus is CloudServiceStatus.ApiKeyMissing -> CloudAnchorBlocker.ApiKeyMissing
            cloudStatus is CloudServiceStatus.ApiKeyRejected -> CloudAnchorBlocker.ApiKeyRejected
            cloudStatus is CloudServiceStatus.QuotaExhausted -> CloudAnchorBlocker.QuotaExhausted
            cloudStatus is CloudServiceStatus.NoNetwork -> CloudAnchorBlocker.NoNetwork
            else -> null
        },
        // A tracking failure — real or forced (#1881) — must read as "not tracking" here
        // too, or the screen would offer to place an anchor while the pill says the room
        // is too dark to see.
        tracking = isTracking && effectiveTrackingFailure == null,
        anchorPlaced = localAnchor != null,
        roomQuality = roomQuality,
        host = hostTask,
        resolve = resolveTask,
        codeInput = codeInput,
        trackingHint = trackingHint,
    )

    // QA-only state pin (#3421): the settings-sheet menu, or `--es qa_state <name>` for
    // the emulator smoke suite. Both gated on QA mode — a forced state makes the screen
    // claim something that never happened. `?:` means the absence of an override is the
    // normal path, so live behaviour is untouched by construction.
    LaunchedEffect(Unit) {
        if (qaStateOverridesAllowed()) {
            cloudAnchorScenarioOf(DemoSettings.qaDemoState)?.let {
                ForcedCloudAnchorScenario.override = it
            }
        }
    }
    val forcedScenario = ForcedCloudAnchorScenario.override?.takeIf { qaStateOverridesAllowed() }
    val flow = forcedScenario?.state() ?: liveState

    val status = flow.status()
    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_lantern.glb")

    // Clear the "Copied" confirmation after a beat. Android only shows its own clipboard
    // toast from API 33 and this app's minSdk is 28, so the card confirms it itself.
    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(COPY_CONFIRMATION_MILLIS)
            justCopied = false
        }
    }

    // A resolve accrues a billing event whether anyone is still listening or not, so its
    // future is cancelled when the screen leaves — required by CloudAnchorNode.resolve's
    // own KDoc, and simply not done before #3421.
    DisposableEffect(Unit) {
        onDispose {
            resolveFuture?.cancel()
            cloudNode?.cancelHost()
            // The QA override is a global singleton; leaving the screen must not strand
            // the next visit in a fake state. Re-entering re-reads the intent extra.
            ForcedCloudAnchorScenario.override = null
        }
    }

    val restart = {
        resolveFuture?.cancel()
        resolveFuture = null
        cloudNode?.cancelHost()
        localAnchor?.detach()
        localAnchor = null
        placedAnchorId = null
        cloudNode = null
        hostTask = CloudAnchorTask.Idle
        resolveTask = CloudAnchorTask.Idle
        hostedCode = null
        roomQuality = RoomQuality.Insufficient
        codeInput = ""
        operationCloudStatus = null
        justCopied = false
    }

    val onHost = onHost@{
        val node = cloudNode ?: return@onHost
        val session = arSession ?: return@onHost
        operationCloudStatus = null
        hostTask = CloudAnchorTask.Running
        node.host(session, ttlDays = CLOUD_ANCHOR_TTL_DAYS) { id, state ->
            if (state == Anchor.CloudAnchorState.SUCCESS && id != null) {
                hostedCode = id
                placedAnchorId = id
                hostTask = CloudAnchorTask.Succeeded(id)
            } else {
                // The shared banner still owns the two reasons every Cloud demo words
                // identically (rejected key, spent quota); everything else is a Cloud
                // Anchor failure this screen explains in its own words.
                operationCloudStatus = state.toCloudServiceStatus("Hosting")
                hostTask = CloudAnchorTask.Failed(cloudAnchorFailureOf(state.name))
            }
        }
        Unit
    }

    val onResolve = onResolve@{
        val session = arSession ?: return@onResolve
        val code = flow.trimmedCode
        operationCloudStatus = null
        resolveTask = CloudAnchorTask.Running
        resolveFuture = CloudAnchorNodeImpl.resolve(engine, session, code) { state, node ->
            if (state == Anchor.CloudAnchorState.SUCCESS && node != null) {
                localAnchor = node.anchor
                placedAnchorId = code
                resolveTask = CloudAnchorTask.Succeeded(code)
            } else {
                operationCloudStatus = state.toCloudServiceStatus("Resolve")
                resolveTask = CloudAnchorTask.Failed(cloudAnchorFailureOf(state.name))
            }
            resolveFuture = null
        }
        Unit
    }

    val runAction: (CloudAnchorAction) -> Unit = { action ->
        when (action) {
            CloudAnchorAction.Host -> onHost()
            CloudAnchorAction.Resolve -> onResolve()
            CloudAnchorAction.Restart -> restart()
            CloudAnchorAction.CopyCode -> hostedCode?.let {
                justCopied = copyToClipboard(context, CODE_CLIP_LABEL, it)
            }
            CloudAnchorAction.ShareCode -> hostedCode?.let { code ->
                val shared = shareText(
                    context = context,
                    text = cloudAnchorShareText(code),
                    chooserTitle = "Share anchor code",
                    subject = "SceneView cloud anchor",
                )
                // Nothing on the device can handle a text share (#3263). Fall back to the
                // clipboard rather than appearing to have done nothing — the user still
                // ends up holding the code, which is the whole point of the button.
                if (!shared) justCopied = copyToClipboard(context, CODE_CLIP_LABEL, code)
            }
            CloudAnchorAction.PasteCode -> clipboardText(context)?.let { codeInput = it.trim() }
            // Not action-bar actions: placing is a scene tap, switching steps is the dock.
            CloudAnchorAction.PlaceAnchor, CloudAnchorAction.SwitchStep -> {}
        }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_cloud_anchor_title),
        onBack = onBack,
        // The way out of any state, including a failed upload. The old screen had none:
        // once an anchor was placed the demo was stuck for the rest of the session.
        onReset = restart,
        // Two steps, two dock items — the M3 navigation idiom, captioned per #3402. The
        // dock navigates; the action bar acts. Both are locked while a request is in
        // flight, so a step switch cannot abandon a call that is already billing.
        dock = listOf(
            DockItem(
                icon = Icons.Rounded.CloudUpload,
                label = "Host an anchor",
                caption = "Host",
                onClick = { step = CloudAnchorStep.Host },
                enabled = flow.allows(CloudAnchorAction.SwitchStep),
                selected = flow.step == CloudAnchorStep.Host,
            ),
            DockItem(
                icon = Icons.Rounded.CloudDownload,
                label = "Resolve a shared code",
                caption = "Resolve",
                onClick = { step = CloudAnchorStep.Resolve },
                enabled = flow.allows(CloudAnchorAction.SwitchStep),
                selected = flow.step == CloudAnchorStep.Resolve,
            ),
        ),
        // The sheet keeps only what is genuinely secondary: the one paragraph explaining
        // what a cloud anchor is, and the QA menus. Everything the flow needs — the code,
        // the field, Copy, Share, Paste — is on screen, because the status line tells the
        // user to use it (#1964 / #2486).
        controls = {
            Text(
                text = stringResource(R.string.demo_ar_cloud_anchor_about),
                style = MaterialTheme.typography.bodyMedium,
            )
            ForceCloudAnchorScenarioMenu()
            ForceTrackingFailureMenu()
        },
        bottomOverlay = {
            // Exactly one pill, never two. The shared Cloud-service banner (#3262) owns
            // the sentence for the one blocker that clears on its own — no network — so
            // all five Cloud demos still word that identically. Every other sentence on
            // this screen is flow state and comes from `status()`. The three
            // configuration blockers get no pill at all: the card below explains them,
            // and a pill repeating the card is the kind of double-voiced chrome #3421
            // was filed about.
            when {
                // `CloudServiceStatus.NoNetwork` rather than `cloudStatus`: they are the
                // same value on a real device, but a QA-forced no-network state has to
                // render the banner too, and the live `cloudStatus` would say Available.
                flow.blocker == CloudAnchorBlocker.NoNetwork ->
                    CloudServiceStatusBanner(CloudServiceStatus.NoNetwork)
                flow.blocker?.needsExplanationCard == true -> Unit
                else -> DemoStatusBanner(
                    text = status.text,
                    tone = status.tone,
                    // A completed step keeps the Guidance tone — there is still something
                    // to do next — but must not wear the tone's move-your-device glyph.
                    icon = when (status.icon) {
                        CloudAnchorStatusIcon.Success -> Icons.Rounded.CheckCircle
                        CloudAnchorStatusIcon.Default -> null
                    },
                )
            }

            CloudAnchorFlowCard(
                card = flow.card(),
                onCodeChange = { codeInput = it },
                copied = justCopied,
            )

            val buttons = flow.actionBar()
            if (buttons.isNotEmpty()) {
                SceneActionBar(
                    *buttons.map { button ->
                        SceneAction(
                            label = button.label,
                            onClick = { runAction(button.action) },
                            enabled = button.enabled,
                        )
                    }.toTypedArray()
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // QA-only synthetic room behind a translucent AR surface (#3308) — the arm64
            // emulator has no camera HAL, so without this every AR capture is black.
            if (qaBackdrop) QaCameraBackdrop(seed = "ar-cloud-anchor")

            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                isOpaque = !qaCameraBackdropEnabled(),
                surfaceType = qaCameraBackdropSurfaceType(),
                cameraStream = if (qaBackdrop) null else cameraStream,
                playbackDataset = arPlaybackDataset,
                planeRenderer = true,
                cloudAnchorMode = Config.CloudAnchorMode.ENABLED,
                // The SDK's "ARCore can't start" card (#3374) is suppressed only while QA
                // has pinned the screen to a fictional state. `emulator-5554` cannot run
                // ARCore at all (#2754), so it always publishes a verdict there and the
                // centred card would sit on top of the very chrome the capture exists to
                // review — with `api_key_missing` producing two stacked dark cards saying
                // different things. Nothing is lost: that card has its own previews and
                // `ARCoreAvailabilityTest`, and this branch is unreachable outside QA
                // mode, so a real user on a real device always gets it.
                arCoreAvailabilityOverlay = if (forcedScenario != null) {
                    null
                } else {
                    { ARCoreAvailabilityOverlay(it) }
                },
                onSessionCreated = { session -> arSession = session },
                onARCoreAvailability = { arCoreAvailability = it },
                onSessionUpdated = { session: Session, frame: Frame ->
                    cameraReady = true
                    latestFrame = frame
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    // Room-mapping feedback, the signal the old screen ignored entirely.
                    // Only meaningful once there is an anchor to map *around*, and only
                    // while tracking — ARCore throws otherwise, hence the runCatching.
                    if (localAnchor != null && hostTask == CloudAnchorTask.Idle) {
                        roomQuality = runCatching {
                            session.estimateFeatureMapQualityForHosting(frame.camera.pose)
                                .toRoomQuality()
                        }.getOrDefault(roomQuality)
                    }
                },
                onTrackingFailureChanged = { reason -> trackingFailureReason = reason },
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { event: MotionEvent, _ ->
                        // One gate, asked of the flow — the same rule the Host button and
                        // the status line read, so a tap can never place an anchor the
                        // screen has just said cannot be hosted.
                        if (flow.allows(CloudAnchorAction.PlaceAnchor)) {
                            val hit = latestFrame?.hitTest(event)?.firstOrNull { result ->
                                val trackable = result.trackable
                                trackable is Plane &&
                                    trackable.isPoseInPolygon(result.hitPose) &&
                                    result.distance <= MAX_PLACEMENT_DISTANCE_METRES
                            }
                            if (hit != null) localAnchor = hit.createAnchor()
                        }
                    }
                )
            ) {
                localAnchor?.let { anchor ->
                    CloudAnchorNode(
                        anchor = anchor,
                        cloudAnchorId = placedAnchorId,
                        apply = { cloudNode = this },
                    ) {
                        modelInstance?.let { instance ->
                            ModelNode(
                                modelInstance = instance,
                                scaleToUnits = ANCHOR_MODEL_SIZE_METRES,
                            )
                        }
                    }
                }
            }

            // Cover the still-black AR viewport until the first camera frame (#2484);
            // reads the ARCore verdict so it never covers the SDK's own card (#3341).
            ARCameraInitScrim(
                initializing = !cameraReady,
                arCoreAvailability = arCoreAvailability,
            )
        }
    }
}

/** Clipboard label for a hosted code — what a clipboard manager shows as its origin. */
private const val CODE_CLIP_LABEL = "SceneView cloud anchor code"

/** How long the hosted-code card confirms a copy before going back to the expiry line. */
private const val COPY_CONFIRMATION_MILLIS = 2_000L

/** Furthest plane hit that may take an anchor. Beyond this ARCore's depth is unreliable. */
private const val MAX_PLACEMENT_DISTANCE_METRES = 5.0f

/** Size the lantern is scaled to at the anchor. */
private const val ANCHOR_MODEL_SIZE_METRES = 0.3f

/**
 * Maps ARCore's `FeatureMapQuality` to the flow's own enum.
 *
 * The one place the two vocabularies meet — the flow stays free of ARCore types so it can
 * be unit-tested on the JVM, which matters because `emulator-5554` cannot run ARCore at
 * all (#2754).
 */
private fun Session.FeatureMapQuality.toRoomQuality(): RoomQuality = when (this) {
    Session.FeatureMapQuality.INSUFFICIENT -> RoomQuality.Insufficient
    Session.FeatureMapQuality.SUFFICIENT -> RoomQuality.Sufficient
    Session.FeatureMapQuality.GOOD -> RoomQuality.Good
}

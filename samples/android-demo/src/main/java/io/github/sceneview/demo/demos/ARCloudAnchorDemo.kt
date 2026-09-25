package io.github.sceneview.demo.demos

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
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
import com.google.ar.core.HostCloudAnchorFuture
import com.google.ar.core.ResolveCloudAnchorFuture
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARCoreAvailabilityOverlay
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.SceneView
import io.github.sceneview.ar.PlacementPhase
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.model
import io.github.sceneview.haptic.rememberHapticFeedback
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.common.placement.PlacementActionCard
import io.github.sceneview.demo.common.placement.PlacementCard
import io.github.sceneview.ar.AutoPlacementResult
import io.github.sceneview.ar.AutoPlacementModel
import io.github.sceneview.ar.rememberAutoPlacementState
import io.github.sceneview.demo.common.placement.FeaturePlacementScene
import io.github.sceneview.demo.demos.internal.CloudRequestGeneration
import io.github.sceneview.math.Position
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.CloudAnchorFlowCard
import io.github.sceneview.demo.common.CloudServiceStatus
import io.github.sceneview.demo.common.DemoModalBottomSheet
import io.github.sceneview.demo.common.CloudServiceStatusBanner
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.ForceCloudAnchorScenarioMenu
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedCloudAnchorScenario
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
import io.github.sceneview.demo.common.clipboardText
import io.github.sceneview.demo.common.copyToClipboard
import io.github.sceneview.demo.common.qaStateOverridesAllowed
import io.github.sceneview.demo.common.rememberHasArcoreApiKey
import io.github.sceneview.demo.common.rememberIsNetworkAvailable
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
import kotlinx.coroutines.CancellationException
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
 * Cloud control decision-making lives in `demos/internal/CloudAnchorFlow.kt` as pure
 * functions of one [CloudAnchorFlowState], pinned by `CloudAnchorFlowTest`. This file
 * only wires ARCore signals in and renders what the flow says. That split is what makes
 * the screen testable at all: `emulator-5554` cannot run ARCore (#2754), so the state
 * machine would otherwise be validated nowhere but a physical device.
 *
 * Requires the ARCore Cloud Anchor API to be enabled and an API key wired in — see
 * `samples/android-demo/ARCORE_CLOUD_SETUP.md`. When it is not, the screen says so in a
 * card instead of offering controls that cannot work (the #3374 pattern).
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val placementState = rememberAutoPlacementState()
    val haptic = rememberHapticFeedback()
    var show3D by remember { mutableStateOf(false) }
    var invalidMove by remember { mutableStateOf(false) }
    var localPlacement by remember { mutableStateOf<AutoPlacementResult?>(null) }
    var resolvedAnchor by remember { mutableStateOf<Anchor?>(null) }
    val requestGeneration = remember { CloudRequestGeneration() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var hostFuture by remember { mutableStateOf<HostCloudAnchorFuture?>(null) }
    var hostAnchor by remember { mutableStateOf<Anchor?>(null) }
    var isTracking by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var arSession by remember { mutableStateOf<Session?>(null) }
    var resolveFuture by remember { mutableStateOf<ResolveCloudAnchorFuture?>(null) }

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
        anchorPlaced = localPlacement != null || resolvedAnchor != null,
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
    var modelInstance by remember { mutableStateOf<ModelInstance?>(null) }
    var modelFailed by remember { mutableStateOf(false) }
    var modelRetry by remember { mutableStateOf(0) }
    LaunchedEffect(modelRetry) {
        val ticket = placementState.selectModel()
        modelFailed = false
        val loaded = try { modelLoader.loadModelInstance("models/khronos_lantern.glb") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (placementState.acceptsAsset(ticket)) {
            modelInstance = loaded
            modelFailed = loaded == null
        } else loaded?.let { modelLoader.destroyModel(it.model) }
    }
    DisposableEffect(modelInstance) {
        val owned = modelInstance
        onDispose { owned?.let { modelLoader.destroyModel(it.model) } }
    }
    LaunchedEffect(placementState.phase) {
        if (placementState.phase == PlacementPhase.TRACKING_LOST) haptic.warning()
        if (placementState.phase != PlacementPhase.ADJUSTING) invalidMove = false
    }

    // Clear the "Copied" confirmation after a beat. Android only shows its own clipboard
    // toast from API 33 and this app's minSdk is 28, so the card confirms it itself.
    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(COPY_CONFIRMATION_MILLIS)
            justCopied = false
        }
    }

    // Cancel native requests and reject callbacks delivered after reset or dismissal.
    DisposableEffect(Unit) {
        onDispose {
            requestGeneration.invalidate()
            resolveFuture?.cancel()
            hostFuture?.cancel()
            resolvedAnchor?.detach()
            // The QA override is a global singleton; leaving the screen must not strand
            // the next visit in a fake state. Re-entering re-reads the intent extra.
            ForcedCloudAnchorScenario.override = null
        }
    }

    val clearHostedPlacement = {
        requestGeneration.invalidate()
        hostFuture?.cancel()
        hostFuture = null
        hostAnchor = null
        hostTask = CloudAnchorTask.Idle
        hostedCode = null
        roomQuality = RoomQuality.Insufficient
        justCopied = false
        operationCloudStatus = null
    }

    val restart = {
        clearHostedPlacement()
        resolveFuture?.cancel()
        resolveFuture = null
        // The placement adapter owns its local anchor; the resolved anchor is ours.
        placementState.resetPlacement(SystemClock.uptimeMillis())
        localPlacement = null
        invalidMove = false
        resolvedAnchor?.detach()
        resolvedAnchor = null
        resolveTask = CloudAnchorTask.Idle
        codeInput = ""
    }

    val onHost = onHost@{
        if (!liveState.allows(CloudAnchorAction.Host)) return@onHost
        val anchor = localPlacement?.anchor ?: return@onHost
        val session = arSession ?: return@onHost
        if (anchor.trackingState != TrackingState.TRACKING) return@onHost
        val generation = requestGeneration.current
        hostAnchor = anchor
        operationCloudStatus = null
        hostTask = CloudAnchorTask.Running
        try {
            hostFuture = session.hostCloudAnchorAsync(anchor, CLOUD_ANCHOR_TTL_DAYS) { id, result ->
                // ARCore completion may arrive on its render thread. Serialize it with
                // Compose actions/disposal before reading generations or mutating UI state.
                mainHandler.post {
                    if (requestGeneration.accepts(generation) && localPlacement?.anchor !== anchor) {
                        clearHostedPlacement()
                    } else if (requestGeneration.accepts(generation)) {
                        hostFuture = null
                        if (result == Anchor.CloudAnchorState.SUCCESS && id != null) {
                            hostedCode = id
                            hostTask = CloudAnchorTask.Succeeded(id)
                        } else {
                            operationCloudStatus = result.toCloudServiceStatus("Hosting")
                            hostTask = CloudAnchorTask.Failed(cloudAnchorFailureOf(result.name))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            hostTask = CloudAnchorTask.Failed(cloudAnchorFailureOf("ERROR_INTERNAL"))
        }
        Unit
    }

    val onResolve = onResolve@{
        if (!liveState.allows(CloudAnchorAction.Resolve)) return@onResolve
        val session = arSession ?: return@onResolve
        val code = liveState.trimmedCode
        val generation = requestGeneration.current
        operationCloudStatus = null
        resolveTask = CloudAnchorTask.Running
        try {
            resolveFuture = session.resolveCloudAnchorAsync(code) { anchor, result ->
                // Always deliver cleanup, even after composition has been disposed: a
                // cancelled Compose coroutine would strand a late native anchor result.
                mainHandler.post {
                    if (!requestGeneration.accepts(generation)) {
                        anchor?.detach()
                    } else {
                        resolveFuture = null
                        if (result == Anchor.CloudAnchorState.SUCCESS && anchor != null) {
                            resolvedAnchor?.detach()
                            resolvedAnchor = anchor
                            resolveTask = CloudAnchorTask.Succeeded(code)
                        } else {
                            anchor?.detach()
                            operationCloudStatus = result.toCloudServiceStatus("Resolve")
                            resolveTask = CloudAnchorTask.Failed(cloudAnchorFailureOf(result.name))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            resolveTask = CloudAnchorTask.Failed(cloudAnchorFailureOf("ERROR_INTERNAL"))
        }
        Unit
    }

    val runAction: (CloudAnchorAction) -> Unit = { action ->
        // Protect Copy/Share even if a gesture ended before the next camera callback.
        if (hostAnchor != null && localPlacement?.anchor !== hostAnchor) clearHostedPlacement()
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
            // Placement is automatic; switching steps belongs to the dock.
            CloudAnchorAction.PlaceAnchor, CloudAnchorAction.SwitchStep -> {}
        }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_cloud_anchor_title),
        chromeToggleOnTap = false,
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
                onClick = {
                    if (step != CloudAnchorStep.Host) { restart(); step = CloudAnchorStep.Host }
                },
                enabled = flow.allows(CloudAnchorAction.SwitchStep),
                selected = flow.step == CloudAnchorStep.Host,
            ),
            DockItem(
                icon = Icons.Rounded.CloudDownload,
                label = "Resolve a shared code",
                caption = "Resolve",
                onClick = {
                    if (step != CloudAnchorStep.Resolve) { restart(); step = CloudAnchorStep.Resolve }
                },
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
            Text(stringResource(R.string.ar_scale_preview_size, (placementState.scaleFactor * 100).toInt()))
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
            val hostingUnblocked = flow.step == CloudAnchorStep.Host && flow.blocker == null
            val placementCard = if (forcedScenario == null && hostingUnblocked) {
                when (placementState.phase) {
                    PlacementPhase.NO_SURFACE -> PlacementCard.NO_SURFACE
                    PlacementPhase.RECOVERY_FAILED -> PlacementCard.RECOVERY_FAILED
                    else -> null
                }
            } else null
            val placementMessage = if (forcedScenario == null && hostingUnblocked &&
                hostTask != CloudAnchorTask.Running
            ) {
                when {
                    modelFailed -> stringResource(R.string.ar_place_model_failed)
                    modelInstance == null -> stringResource(R.string.ar_place_loading_model)
                    placementCard != null -> null
                    invalidMove -> stringResource(R.string.ar_place_keep_on_surface)
                    placementState.phase == PlacementPhase.TRACKING_LOST ->
                        stringResource(R.string.ar_place_tracking_paused)
                    placementState.phase == PlacementPhase.RECOVERING ->
                        stringResource(R.string.ar_place_finding_placement)
                    placementState.phase == PlacementPhase.ADJUSTING ->
                        stringResource(
                            R.string.ar_scale_preview_size,
                            (placementState.scaleFactor * 100).toInt(),
                        )
                    else -> null
                }
            } else null
            when {
                // `CloudServiceStatus.NoNetwork` rather than `cloudStatus`: they are the
                // same value on a real device, but a QA-forced no-network state has to
                // render the banner too, and the live `cloudStatus` would say Available.
                flow.blocker == CloudAnchorBlocker.NoNetwork ->
                    CloudServiceStatusBanner(CloudServiceStatus.NoNetwork)
                flow.blocker?.needsExplanationCard == true || placementCard != null -> Unit
                else -> DemoStatusBanner(
                    text = placementMessage ?: status.text,
                    tone = status.tone,
                    // A completed step keeps the Guidance tone — there is still something
                    // to do next — but must not wear the tone's move-your-device glyph.
                    icon = when (status.icon) {
                        CloudAnchorStatusIcon.Success -> Icons.Rounded.CheckCircle
                        CloudAnchorStatusIcon.Default -> null
                    },
                )
            }

            if (modelFailed && flow.blocker == null) {
                TextButton(onClick = { modelRetry++ }) { Text(stringResource(R.string.ar_place_try_again)) }
            }
            PlacementActionCard(placementCard, { show3D = true },
                { placementState.keepScanning(SystemClock.uptimeMillis()) }, restart, restart)
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
            FeaturePlacementScene(
                assetReady = modelInstance != null && liveState.allows(CloudAnchorAction.PlaceAnchor),
                state = placementState,
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                cameraStream = cameraStream,
                playbackDataset = arPlaybackDataset,
                sessionConfiguration = { _, config ->
                    config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                },
                onPlaced = { localPlacement = it; haptic.medium() },
                onSessionCreated = { session -> arSession = session },
                onARCoreAvailability = { arCoreAvailability = it },
                arCoreAvailabilityOverlay = if (forcedScenario != null) null else { { ARCoreAvailabilityOverlay(it) } },
                onSessionUpdated = { session: Session, frame: Frame ->
                    cameraReady = true
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    // Moving to a new surface replaces the native anchor. An uploaded
                    // code (or upload in flight) belongs to the old placement, never this one.
                    if (hostAnchor != null && localPlacement?.anchor !== hostAnchor) {
                        clearHostedPlacement()
                    }
                    // Room-mapping feedback, the signal the old screen ignored entirely.
                    // Only meaningful once there is an anchor to map *around*, and only
                    // while tracking — ARCore throws otherwise, hence the runCatching.
                    if (isTracking && localPlacement?.anchor?.trackingState == TrackingState.TRACKING &&
                        hostTask == CloudAnchorTask.Idle) {
                        roomQuality = runCatching {
                            session.estimateFeatureMapQualityForHosting(frame.camera.pose)
                                .toRoomQuality()
                        }.getOrDefault(roomQuality)
                    }
                },
                onTrackingFailureChanged = { reason -> trackingFailureReason = reason },
            ) { placement ->
                modelInstance?.let { instance ->
                    if (placement != null && resolvedAnchor == null) {
                        AutoPlacementModel(placement, placementState, instance,
                            scaleToUnits = ANCHOR_MODEL_SIZE_METRES,
                            onInvalidMove = { invalidMove = it },
                            onScaleChanged = { _, _, crossed -> if (crossed) haptic.selection() })
                    }
                    resolvedAnchor?.let { anchor ->
                        AnchorNode(anchor = anchor) {
                            ModelNode(modelInstance = instance,
                                scaleToUnits = ANCHOR_MODEL_SIZE_METRES,
                                centerOrigin = Position(y = -1f),
                                isVisible = isTracking)
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
    if (show3D) {
        DemoModalBottomSheet(onDismissRequest = { show3D = false }) {
            // #3716: the container now reaches the true bottom edge — clear the
            // navigation bar explicitly instead of relying on the system inset.
            Column(Modifier.navigationBarsPadding()) {
                Text(stringResource(R.string.ar_place_preview_size), Modifier.padding(SceneViewTokens.Space.md))
                val preview = rememberModelInstance(modelLoader, "models/khronos_lantern.glb")
                SceneView(Modifier.fillMaxWidth().aspectRatio(1f), engine = engine,
                    modelLoader = modelLoader, materialLoader = materialLoader) {
                    preview?.let { ModelNode(it, scaleToUnits = ANCHOR_MODEL_SIZE_METRES) }
                }
            }
        }
    }
}

/** Clipboard label for a hosted code — what a clipboard manager shows as its origin. */
private const val CODE_CLIP_LABEL = "SceneView cloud anchor code"

/** How long the hosted-code card confirms a copy before going back to the expiry line. */
private const val COPY_CONFIRMATION_MILLIS = 2_000L

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

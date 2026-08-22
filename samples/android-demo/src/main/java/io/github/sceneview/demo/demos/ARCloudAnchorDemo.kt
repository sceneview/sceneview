package io.github.sceneview.demo.demos

import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.CloudAnchorNode as CloudAnchorNodeImpl
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ARCORE_API_KEY_MISSING_MESSAGE
import io.github.sceneview.demo.common.arcoreApiKeyRejectedMessage
import io.github.sceneview.demo.common.rememberHasArcoreApiKey
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener

/**
 * Cloud anchor persistence demo.
 *
 * Demonstrates hosting and resolving ARCore Cloud Anchors for cross-device, persistent AR.
 * Tap on a detected plane to place an anchor, then host it to the cloud. Copy the cloud anchor
 * ID and resolve it on another device to see the same 3D content at the same location.
 *
 * Requires ARCore Cloud Anchor API to be enabled in Google Cloud Console.
 */
@Composable
fun ARCloudAnchorDemo(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch - see
    // `rememberArPlaybackDataset` - so live AR is completely unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Detect at runtime whether the build wired an ARCore Cloud API key into
    // the manifest. If absent (e.g. running a fork without the GitHub secret,
    // or a developer who forgot to set ARCORE_API_KEY in local.properties),
    // host()/resolve() will silently come back with ERROR_NOT_AUTHORIZED — we
    // surface that upfront in the status banner so the user knows why.
    val hasArcoreApiKey = rememberHasArcoreApiKey()

    var localAnchor by remember { mutableStateOf<Anchor?>(null) }
    var cloudAnchorId by remember { mutableStateOf<String?>(null) }
    var resolveId by remember { mutableStateOf("") }
    var isTracking by remember { mutableStateOf(false) }
    // Cover the jet-black ARSceneView surface until ARCore delivers its first camera
    // frame, so the ~1–3 s warm-up on entry doesn't read as a frozen screen (#2484).
    var cameraReady by remember { mutableStateOf(false) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var hostedId by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember {
        mutableStateOf(
            if (hasArcoreApiKey) {
                "Tap a surface to place an anchor"
            } else {
                ARCORE_API_KEY_MISSING_MESSAGE
            }
        )
    }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    var arSession by remember { mutableStateOf<Session?>(null) }
    // Ref to the CloudAnchorNode created inside the ARSceneView content — needed so the
    // Host button can call node.host(session) to actually upload the anchor. Without this
    // the Host button just updated the status text and nothing hit the Cloud Anchor API.
    var cloudNode by remember { mutableStateOf<CloudAnchorNodeImpl?>(null) }

    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_lantern.glb")

    // Host the placed local anchor to the cloud. Hoisted so the on-screen
    // SceneActionBar can invoke it — Host is the demo's primary action (the
    // banner literally tells the user to "tap Host"), so it lives on-screen
    // rather than buried in the Settings sheet (#1964 / #1614).
    val onHost = {
        val node = cloudNode
        val session = arSession
        when {
            // Host is always tappable (#2486) so it never reads as "no button";
            // tapping before an anchor exists explains the next step on-screen.
            localAnchor == null -> {
                statusMessage = "Tap a surface to place an anchor first, then Host"
            }
            node == null || session == null -> Toast.makeText(
                context, "AR session not ready", Toast.LENGTH_SHORT
            ).show()
            else -> {
                statusMessage = "Hosting anchor…"
                node.host(session)
            }
        }
        Unit
    }
    // Resolve a cloud anchor id (typed into the on-screen ID field) back into a
    // local anchor. Also primary — on-screen alongside Host (#1964). Resolve is
    // always tappable (#2486); a blank id explains what to do rather than being a
    // dead, greyed-out button the user reads as "no button".
    val onResolve = onResolve@{
        val session = arSession
        if (resolveId.isBlank()) {
            statusMessage = "Enter a Cloud Anchor ID above, then tap Resolve"
            return@onResolve
        }
        if (session == null) {
            Toast.makeText(
                context, "AR session not ready", Toast.LENGTH_SHORT
            ).show()
            return@onResolve
        }
        statusMessage = "Resolving $resolveId…"
        CloudAnchorNodeImpl.resolve(engine, session, resolveId) { state, node ->
            if (state == Anchor.CloudAnchorState.SUCCESS && node != null) {
                localAnchor = node.anchor
                cloudAnchorId = resolveId
                statusMessage = "Resolved $resolveId"
            } else {
                statusMessage = when (state) {
                    Anchor.CloudAnchorState.ERROR_NOT_AUTHORIZED ->
                        arcoreApiKeyRejectedMessage("Resolve")
                    else -> "Resolve failed: $state"
                }
            }
        }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_cloud_anchor_title),
        onBack = onBack,
        // Host / Resolve are the demo's primary actions and live on-screen via
        // SceneActionBar (#1964 / #1614); the one-line instruction and the Cloud
        // Anchor ID input now live ON-SCREEN too (#2486) so the host\u2192resolve
        // flow is discoverable without ever opening this sheet. The sheet keeps
        // only the hosted-ID readout (to copy + share) and the QA debug menu \u2014
        // both genuinely secondary.
        controls = {
            Text(
                text = "Place an anchor and tap Host to share it; paste a shared id " +
                    "in the on-screen field and tap Resolve. The hosted id appears " +
                    "below once Host succeeds \u2014 copy it to resolve on another device.",
                style = MaterialTheme.typography.bodyMedium,
            )

            hostedId?.let {
                Text(
                    text = "Hosted ID: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            // Developer-only debug toggle — visible when QA mode is on. Lets QA
            // force-emit each TrackingFailureReason so the actionable-message
            // overlay can be validated without staging a real failure. See
            // io.github.sceneview.demo.common.ForcedTrackingFailure / #1881.
            ForceTrackingFailureMenu()
        },
        // Status line + on-screen Cloud Anchor ID input, hosted by the scaffold's
        // top slot (#2486 / #3237).
        //
        // They live at the top of the screen, not the bottom: the long
        // ERROR_NOT_AUTHORIZED message used to wrap into — and be clipped by —
        // the bottom Host / Resolve action bar.
        //
        // ForcedTrackingFailure.override shadows the real ARCore-reported
        // reason when a developer picks one in the debug menu (#1881); the
        // forced reason wins so QA can validate the tracking-failure
        // mappings even while hosting/resolving.
        topOverlay = {
            val effectiveReason = ForcedTrackingFailure.override ?: trackingFailureReason
            val forcedMessage = ForcedTrackingFailure.override?.let {
                trackingFailureMessage(effectiveReason)
            }
            val isAnchorReady = hostedId != null || cloudAnchorId != null
            // One concise, contextual line that makes the host→resolve flow
            // self-explanatory instead of a static "Tap a surface" (#2486),
            // mirroring the readiness-banner pattern in ARRooftopAnchorDemo.
            val statusText = when {
                forcedMessage != null -> forcedMessage
                !hasArcoreApiKey -> statusMessage
                !isTracking -> "Initializing camera — move slowly to find a surface"
                localAnchor == null -> "Point at a surface and tap to place an anchor"
                hostedId == null && cloudAnchorId == null ->
                    "Anchor placed — tap Host to share it to the cloud"
                else -> statusMessage
            }
            // Error-tint when the API key is missing or a host/resolve call came
            // back with a failure, so the actionable banner reads as a warning
            // rather than a normal status (#2486).
            val isError = !hasArcoreApiKey ||
                statusMessage.contains("failed", ignoreCase = true)
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .widthIn(max = 340.dp)
                            .background(
                                color = if (isError) {
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                },
                                shape = RoundedCornerShape(24.dp)
                            )
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }

                // Cloud Anchor ID input ON-SCREEN (#2486): previously the only
                // ID field lived inside the Settings sheet, so a user never saw
                // how to resolve. Surfaced here (hidden once an anchor is hosted
                // or resolved) so the Resolve button has something to act on
                // without ever opening Settings.
                if (!isAnchorReady) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .widthIn(max = 340.dp),
                    ) {
                        OutlinedTextField(
                            value = resolveId,
                            onValueChange = { resolveId = it },
                            label = { Text("Cloud Anchor ID to resolve") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                        )
                    }
                }
            }
        },
        // Primary actions on-screen (#1964 / #1614) — the banner tells the
        // user to tap Host / Resolve, so both are on-screen buttons, never
        // buried in the Settings sheet. Hosted by the scaffold's bottom slot so
        // the bar is laid out against the Settings FAB instead of blindly
        // beside it (#2779). Both stay TAPPABLE (#2486): a disabled M3 Button
        // over the camera feed reads as faint grey text ("there are no
        // buttons"), so instead of disabling on the not-yet-actionable states
        // the handlers explain the next step on-screen. Only the
        // genuinely-unavailable cases stay disabled: no Cloud API key at all,
        // or Host after a successful host.
        bottomOverlay = {
            SceneActionBar(
                SceneAction(
                    label = "Host",
                    onClick = onHost,
                    enabled = hasArcoreApiKey && hostedId == null,
                ),
                SceneAction(
                    label = "Resolve",
                    onClick = onResolve,
                    enabled = hasArcoreApiKey,
                ),
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                planeRenderer = true,
                // Typed Config.*Mode params (#1766) — replaces the previous sessionConfiguration
                // callback. planeFindingMode + lightEstimationMode were already the defaults.
                cloudAnchorMode = Config.CloudAnchorMode.ENABLED,
                onSessionCreated = { session ->
                    arSession = session
                },
                onSessionUpdated = { _, frame: Frame ->
                    cameraReady = true
                    latestFrame = frame
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                },
                onTrackingFailureChanged = { reason ->
                    trackingFailureReason = reason
                },
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { event: MotionEvent, _ ->
                        val frame = latestFrame ?: return@rememberOnGestureListener
                        if (frame.camera.trackingState != TrackingState.TRACKING) {
                            return@rememberOnGestureListener
                        }
                        if (localAnchor != null) return@rememberOnGestureListener

                        val hit = frame.hitTest(event).firstOrNull { result ->
                            val trackable = result.trackable
                            trackable is Plane &&
                                trackable.isPoseInPolygon(result.hitPose) &&
                                result.distance <= 5.0f
                        }
                        if (hit != null) {
                            localAnchor = hit.createAnchor()
                            statusMessage = "Anchor placed \u2014 tap Host to share"
                        }
                    }
                )
            ) {
                localAnchor?.let { anchor ->
                    CloudAnchorNode(
                        anchor = anchor,
                        cloudAnchorId = cloudAnchorId,
                        onHosted = { id, state ->
                            if (state == Anchor.CloudAnchorState.SUCCESS && id != null) {
                                hostedId = id
                                cloudAnchorId = id
                                statusMessage = "Hosted! ID: $id"
                            } else {
                                // Surface ERROR_NOT_AUTHORIZED with actionable guidance: the
                                // most common cause on a fresh Play Store deploy is that the
                                // App Signing key SHA-1 (post-Play-resign) isn't whitelisted on
                                // the Google Cloud API key. See samples/android-demo/ARCORE_CLOUD_SETUP.md
                                // for the runbook. Generic states still get the bare label.
                                statusMessage = when (state) {
                                    Anchor.CloudAnchorState.ERROR_NOT_AUTHORIZED ->
                                        arcoreApiKeyRejectedMessage("Hosting")
                                    else -> "Hosting failed: $state"
                                }
                            }
                        },
                        apply = { cloudNode = this },
                    ) {
                        modelInstance?.let { instance ->
                            ModelNode(
                                modelInstance = instance,
                                scaleToUnits = 0.3f,
                            )
                        }
                    }
                }
            }

            // Cover the still-black AR viewport until the first camera frame (#2484).
            ARCameraInitScrim(initializing = !cameraReady)
        }
    }
}

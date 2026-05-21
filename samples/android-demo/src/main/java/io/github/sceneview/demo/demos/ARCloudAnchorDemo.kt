package io.github.sceneview.demo.demos

import android.content.pm.PackageManager
import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.math.Position
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
    val hasArcoreApiKey = remember {
        runCatching {
            val ai = context.packageManager.getApplicationInfo(
                context.packageName, PackageManager.GET_META_DATA
            )
            !ai.metaData?.getString("com.google.android.ar.API_KEY").isNullOrBlank()
        }.getOrDefault(false)
    }

    var localAnchor by remember { mutableStateOf<Anchor?>(null) }
    var cloudAnchorId by remember { mutableStateOf<String?>(null) }
    var resolveId by remember { mutableStateOf("") }
    var isTracking by remember { mutableStateOf(false) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var hostedId by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember {
        mutableStateOf(
            if (hasArcoreApiKey) {
                "Tap a surface to place an anchor"
            } else {
                "ARCore Cloud API key not configured — Host/Resolve will return " +
                    "ERROR_NOT_AUTHORIZED. See samples/android-demo/ARCORE_CLOUD_SETUP.md"
            }
        )
    }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    var arSession by remember { mutableStateOf<Session?>(null) }
    // Ref to the CloudAnchorNode created inside the ARSceneView content — needed so the
    // Host button can call node.host(session) to actually upload the anchor. Without this
    // the Host button just updated the status text and nothing hit the Cloud Anchor API.
    var cloudNode by remember { mutableStateOf<CloudAnchorNodeImpl?>(null) }

    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // Host the placed local anchor to the cloud. Hoisted so the on-screen
    // SceneActionBar can invoke it — Host is the demo's primary action (the
    // banner literally tells the user to "tap Host"), so it lives on-screen
    // rather than buried in the Settings sheet (#1964 / #1614).
    val onHost = {
        val node = cloudNode
        val session = arSession
        when {
            localAnchor == null -> Toast.makeText(
                context, "Place an anchor first", Toast.LENGTH_SHORT
            ).show()
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
    // Resolve a cloud anchor id (typed into the sheet's text field) back into a
    // local anchor. Also primary — moved on-screen alongside Host (#1964).
    val onResolve = onResolve@{
        val session = arSession
        if (resolveId.isBlank()) return@onResolve
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
                        "Resolve failed: ERROR_NOT_AUTHORIZED. The ARCore Cloud " +
                            "API key is rejecting this APK. Check SHA-1 + billing " +
                            "+ ARCore API restrictions in ARCORE_CLOUD_SETUP.md."
                    else -> "Resolve failed: $state"
                }
            }
        }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_cloud_anchor_title),
        onBack = onBack,
        // Host / Resolve are the demo's primary actions and live on-screen via
        // SceneActionBar (#1964 / #1614). The sheet keeps only the Cloud Anchor
        // ID input (config that feeds Resolve) and the hosted-ID readout \u2014
        // both genuinely secondary.
        controls = {
            Text(
                text = "Tap a surface to place an anchor, then use the on-screen Host " +
                    "button to share it. To load a shared anchor, paste its id below " +
                    "and tap Resolve.",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = resolveId,
                onValueChange = { resolveId = it },
                label = { Text("Cloud Anchor ID") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
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
                                        "Hosting failed: ERROR_NOT_AUTHORIZED. The ARCore Cloud " +
                                            "API key is rejecting this APK. Check SHA-1 + billing " +
                                            "+ ARCore API restrictions in ARCORE_CLOUD_SETUP.md."
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
                                centerOrigin = Position(0f, 0f, 0f),
                                // The bundled DamagedHelmet GLB carries a residual +90° X
                                // root rotation that lands it face-down on the plane —
                                // correct it at placement time. See #1477.
                                rotation = DemoMath.placementRotationFor(DemoMath.HELMET_ASSET)
                            )
                        }
                    }
                }
            }

            // Status overlay
            // ForcedTrackingFailure.override shadows the real ARCore-reported reason
            // when a developer has picked one in the debug menu (#1881). Read it here
            // so flipping the override re-renders the overlay immediately. The forced
            // reason wins over the live status message so QA can validate the
            // tracking-failure mappings even while hosting/resolving.
            val effectiveReason = ForcedTrackingFailure.override ?: trackingFailureReason
            val forcedMessage = ForcedTrackingFailure.override?.let {
                trackingFailureMessage(effectiveReason)
            }
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Text(
                    text = forcedMessage ?: statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .padding(bottom = 32.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }

            // Primary actions on-screen (#1964 / #1614) — the banner tells the
            // user to "tap Host", so Host (and Resolve) must be on-screen
            // buttons, not buried in the Settings sheet. Same enabled gates as
            // the previous in-sheet buttons.
            SceneActionBar(
                SceneAction(
                    label = "Host",
                    onClick = onHost,
                    enabled = hasArcoreApiKey && localAnchor != null && hostedId == null,
                ),
                SceneAction(
                    label = "Resolve",
                    onClick = onResolve,
                    enabled = hasArcoreApiKey && resolveId.isNotBlank(),
                ),
            )
        }
    }
}

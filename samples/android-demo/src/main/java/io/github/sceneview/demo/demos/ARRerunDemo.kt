package io.github.sceneview.demo.demos

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rerun.RerunBridge
import io.github.sceneview.ar.rerun.rememberRerunBridge
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import kotlinx.coroutines.delay

/**
 * AR debug recording to Rerun.io demo.
 *
 * The bridge auto-connects to the Python sidecar at `127.0.0.1:9876` on
 * entry — for USB pair with `adb reverse tcp:9876 tcp:9876`, for Wi-Fi
 * change the sidecar's bind address. Tap "Save & Share recording" to flush
 * the captured events to a `.rrd` file you can drop onto
 * https://sceneview.github.io/rerun/.
 */
@Composable
fun ARRerunDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch - see
    // `rememberArPlaybackDataset` - so live AR is completely unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isTracking by remember { mutableStateOf(false) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var frameCount by remember { mutableStateOf(0L) }
    var eventsPerSec by remember { mutableStateOf(0f) }
    var lastCameraPose by remember { mutableStateOf<Pose?>(null) }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    val anchors = remember { mutableStateListOf<Anchor>() }

    var sharing by remember { mutableStateOf(false) }
    var shareResult by remember { mutableStateOf<RerunBridge.ShareResult?>(null) }

    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // Bridge auto-connects on first composition, auto-disconnects on
    // dispose — no Connect/Disconnect UI to confuse first-time users who
    // came in from the QR code on /rerun/.
    val bridge = rememberRerunBridge(rateHz = 10, enabled = true)
    // Read the bridge's actually-shipped count, not a local frame counter — a
    // local counter ticks even when the sidecar is unreachable, which would
    // mislead the user into thinking events are being sent.
    val eventCount = bridge.eventsSent
    val isConnected = bridge.isConnected

    // Sample events/sec once per second.
    LaunchedEffect(Unit) {
        var lastSampleCount = eventCount
        while (true) {
            delay(1000)
            val current = eventCount
            eventsPerSec = (current - lastSampleCount).toFloat()
            lastSampleCount = current
        }
    }

    // Save & Share is the demo's primary action. Hoisted so the on-screen
    // SceneActionBar can invoke it — primary actions belong on-screen, not in
    // the Settings sheet (#1964).
    val onSaveAndShare = {
        if (!sharing) {
            sharing = true
            bridge.requestSaveAndShare { result ->
                scope.launch {
                    withContext(Dispatchers.Main) {
                        sharing = false
                        shareResult = result
                    }
                }
            }
        }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_rerun_title),
        onBack = onBack,
        // "Save & Share recording" is the demo's primary action and lives
        // on-screen via SceneActionBar (#1964). The sheet keeps only the
        // stream-stats readout — purely informational.
        controls = {
            Text(
                text = "Captures camera pose, planes and point cloud as a .rrd file " +
                    "you can drop onto sceneview.github.io/rerun/ to scrub frame-by-frame. " +
                    "Tap \"Save & Share recording\" on-screen when you're done.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            // ── Stream Stats ────────────────────────────────────────────────
            StreamStatsCard(
                eventCount = eventCount,
                eventsPerSec = eventsPerSec,
                frameCount = frameCount,
                lastPose = lastCameraPose,
                isConnected = isConnected
            )

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
                sessionConfiguration = { _: Session, config: Config ->
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                },
                onSessionUpdated = { session: Session, frame: Frame ->
                    latestFrame = frame
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    // Bridge gates on its own enabled + connection state, so this
                    // is safe whether or not the sidecar is reachable.
                    bridge.logFrame(session, frame)
                    frameCount++
                    if (frame.camera.trackingState == TrackingState.TRACKING) {
                        lastCameraPose = frame.camera.pose
                    }
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
                        val hit = frame.hitTest(event).firstOrNull { result ->
                            val trackable = result.trackable
                            trackable is Plane &&
                                trackable.isPoseInPolygon(result.hitPose) &&
                                result.distance <= 5.0f
                        }
                        if (hit != null) {
                            anchors.add(hit.createAnchor())
                        }
                    }
                )
            ) {
                anchors.forEach { anchor ->
                    AnchorNode(anchor = anchor) {
                        modelInstance?.let { instance ->
                            ModelNode(
                                modelInstance = instance,
                                scaleToUnits = 0.3f,
                                centerOrigin = Position(0f, 0f, 0f)
                            )
                        }
                    }
                }
            }

            // Share result dialog
            shareResult?.let { result ->
                ShareResultDialog(
                    result = result,
                    onDismiss = { shareResult = null },
                    onCopyPath = { path ->
                        copyToClipboard(context, "Path", path)
                        Toast.makeText(context, "Path copied", Toast.LENGTH_SHORT).show()
                    },
                    onCopyUrl = { url ->
                        copyToClipboard(context, "Viewer URL", url)
                        Toast.makeText(context, "Viewer URL copied", Toast.LENGTH_SHORT).show()
                    },
                    onShare = onShare@{ url ->
                        if (url.isNullOrBlank()) return@onShare
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                            putExtra(Intent.EXTRA_SUBJECT, "AR session — SceneView")
                        }
                        context.startActivity(
                            Intent.createChooser(intent, "Share AR session")
                        )
                    },
                )
            }

            // Status overlay
            // ForcedTrackingFailure.override shadows the real ARCore-reported reason
            // when a developer has picked one in the debug menu (#1881). Read it here
            // so flipping the override re-renders the overlay immediately.
            val effectiveReason = ForcedTrackingFailure.override ?: trackingFailureReason
            AnimatedVisibility(
                visible = !isTracking || ForcedTrackingFailure.override != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Text(
                    text = trackingFailureMessage(effectiveReason)
                        ?: stringResource(R.string.ar_status_scanning),
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

            // Primary action on-screen (#1964) — "Save & Share recording" is
            // the demo's core action, so it lives bottom-start instead of in
            // the Settings sheet. The label reflects the in-flight save state.
            SceneActionBar(
                SceneAction(
                    label = if (sharing) "Saving on dev machine…"
                        else "Save & Share recording",
                    onClick = onSaveAndShare,
                    enabled = !sharing,
                ),
            )
        }
    }
}

@Composable
private fun StreamStatsCard(
    eventCount: Long,
    eventsPerSec: Float,
    frameCount: Long,
    lastPose: Pose?,
    isConnected: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Recording",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // Connection pill — surfaces the failure mode for users without
                // a Python sidecar running. Without this, "Events captured: 0"
                // looks like a bug; with it, the cause is unambiguous.
                val (label, color) = if (isConnected)
                    "Live" to MaterialTheme.colorScheme.primary
                else
                    "Sidecar offline" to MaterialTheme.colorScheme.error
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    modifier = Modifier
                        .background(
                            color = color.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            StatRow(label = "Events sent", value = eventCount.toString())
            StatRow(
                label = "Rate",
                value = if (eventsPerSec > 0f) "%.0f events/s".format(eventsPerSec) else "—"
            )
            StatRow(label = "AR frames", value = frameCount.toString())

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Last camera pose",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            if (lastPose != null) {
                Text(
                    text = "pos  x=%+.2f  y=%+.2f  z=%+.2f".format(
                        lastPose.tx(), lastPose.ty(), lastPose.tz()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "quat x=%+.2f y=%+.2f z=%+.2f w=%+.2f".format(
                        lastPose.qx(), lastPose.qy(), lastPose.qz(), lastPose.qw()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                Text(
                    text = "(waiting for tracking…)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ShareResultDialog(
    result: RerunBridge.ShareResult,
    onDismiss: () -> Unit,
    onCopyPath: (String) -> Unit,
    onCopyUrl: (String) -> Unit,
    onShare: (String?) -> Unit,
) {
    val viewerUrl = result.viewerUrl
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (result.success) "Recording saved" else "Couldn't save")
        },
        text = {
            if (result.success) {
                ShareResultBody(result, onCopyPath, onCopyUrl)
            } else {
                Text(
                    text = result.reason
                        ?: "The sidecar didn't acknowledge the save command.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            if (result.success && viewerUrl != null) {
                TextButton(onClick = { onShare(viewerUrl) }) { Text("Share link") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = if (result.success) {
            { TextButton(onClick = onDismiss) { Text("Done") } }
        } else null,
    )
}

@Composable
private fun ShareResultBody(
    result: RerunBridge.ShareResult,
    onCopyPath: (String) -> Unit,
    onCopyUrl: (String) -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "${result.events} events recorded.",
            style = MaterialTheme.typography.bodyMedium,
        )
        result.path?.let { path ->
            Text("Saved on the dev machine:", style = MaterialTheme.typography.labelMedium)
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { onCopyPath(path) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy path") }
        }
        result.viewerUrl?.let { url ->
            Text(
                "Drag-and-drop the .rrd file onto " +
                    "https://sceneview.github.io/rerun/ — the AR Session Viewer renders " +
                    "it in-place. Or re-host on a public URL (R2, GitHub release, gist) " +
                    "and open this link:",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedButton(
                onClick = { onCopyUrl(url) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy viewer URL") }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

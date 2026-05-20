package io.github.sceneview.demo.demos

import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.sceneview.demo.common.CLOUD_ANCHOR_DEFAULT_TTL_DAYS
import io.github.sceneview.demo.common.CLOUD_ANCHOR_MAX_TTL_DAYS
import io.github.sceneview.demo.common.rememberCloudAnchorPrivacyAccepted
import io.github.sceneview.demo.common.rememberCloudAnchorRegistry
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
    var anchorName by remember { mutableStateOf("") }
    var isTracking by remember { mutableStateOf(false) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var hostedId by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember {
        mutableStateOf(
            if (hasArcoreApiKey) {
                "Tap a surface to place an anchor"
            } else {
                "ARCore Cloud API key not configured — Host/Resolve will return " +
                    "ERROR_NOT_AUTHORIZED. See samples/android-demo/STREETSCAPE_SETUP.md"
            }
        )
    }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    var arSession by remember { mutableStateOf<Session?>(null) }
    // Ref to the CloudAnchorNode created inside the ARSceneView content — needed so the
    // Host button can call node.host(session) to actually upload the anchor. Without this
    // the Host button just updated the status text and nothing hit the Cloud Anchor API.
    var cloudNode by remember { mutableStateOf<CloudAnchorNodeImpl?>(null) }

    // Persistent registry of previously hosted Cloud Anchors so users can
    // re-resolve a saved anchor across app launches. Mirrors the persistent-
    // cloud-anchor flow shipped in arcore-android-sdk's
    // `persistent_cloud_anchor_java` sample. See `CloudAnchorStore.kt`.
    val registry = rememberCloudAnchorRegistry()
    val (privacyAccepted, setPrivacyAccepted) = rememberCloudAnchorPrivacyAccepted()
    // Prune entries whose TTL elapsed — they would return
    // ERROR_RESOLVING_CLOUD_ANCHOR_ID_NOT_FOUND otherwise, which is confusing.
    LaunchedEffect(Unit) { registry.pruneExpired() }
    // Privacy-disclosure gate: hosting uploads visual feature points to
    // Google's ARCore Cloud Anchor service, so we surface a one-time consent
    // dialog before the first Host call (per ARCore's terms of service).
    var pendingHost by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf(false) }
    val showPrivacyDialog = !privacyAccepted && (pendingHost || pendingSave)

    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // Actually run the host call once the user has acknowledged the privacy
    // disclosure. Persistent anchors are hosted with the maximum 365-day TTL so
    // the registry can re-resolve them weeks later. ARCore caps `ttlDays` at
    // [CLOUD_ANCHOR_MAX_TTL_DAYS] (since 1.33).
    val hostAndOptionallySave: (Boolean) -> Unit = { save ->
        val node = cloudNode
        val session = arSession
        val name = anchorName.trim()
        when {
            localAnchor == null -> Toast.makeText(
                context, "Place an anchor first", Toast.LENGTH_SHORT
            ).show()
            node == null || session == null -> Toast.makeText(
                context, "AR session not ready", Toast.LENGTH_SHORT
            ).show()
            save && name.isEmpty() -> Toast.makeText(
                context, "Enter a name to save this anchor", Toast.LENGTH_SHORT
            ).show()
            else -> {
                statusMessage = if (save) {
                    "Hosting & saving \u201c$name\u201d\u2026"
                } else {
                    "Hosting anchor\u2026"
                }
                val ttlDays = if (save) CLOUD_ANCHOR_DEFAULT_TTL_DAYS else 1
                node.host(session, ttlDays = ttlDays) { id, state ->
                    if (save && state == Anchor.CloudAnchorState.SUCCESS && id != null) {
                        registry.save(name, id, ttlDays)
                    }
                }
            }
        }
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = {
                pendingHost = false
                pendingSave = false
            },
            title = { Text("Share AR view with Google?") },
            text = {
                Text(
                    "Hosting a Cloud Anchor uploads visual feature points from " +
                        "your surroundings to Google's ARCore Cloud Anchor service " +
                        "so the same point in space can be recognised on other " +
                        "devices. Make sure no sensitive content is in view before " +
                        "you continue."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    setPrivacyAccepted(true)
                    val save = pendingSave
                    pendingHost = false
                    pendingSave = false
                    hostAndOptionallySave(save)
                }) { Text("Allow & host") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingHost = false
                    pendingSave = false
                }) { Text("Cancel") }
            }
        )
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_cloud_anchor_title),
        onBack = onBack,
        controls = {
            Text("Cloud Anchor Controls", style = MaterialTheme.typography.labelLarge)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (privacyAccepted) hostAndOptionallySave(false)
                        else pendingHost = true
                    },
                    // Without an ARCore Cloud API key the SDK returns
                    // ERROR_NOT_AUTHORIZED silently \u2014 disable the action so the
                    // status text is the single source of truth, mirroring how
                    // ARTerrainAnchorDemo / ARRooftopAnchorDemo already gate the
                    // "Drop" button on `hasArcoreApiKey`.
                    enabled = hasArcoreApiKey && localAnchor != null && hostedId == null
                ) {
                    Text("Host")
                }

                Button(
                    onClick = {
                        if (privacyAccepted) hostAndOptionallySave(true)
                        else pendingSave = true
                    },
                    // Save = host with a 365-day TTL + persist locally so we can
                    // re-resolve on next launch. Same API-key gate as Host.
                    enabled = hasArcoreApiKey && localAnchor != null && hostedId == null
                        && anchorName.isNotBlank()
                ) {
                    Text("Host & save")
                }

                Button(
                    onClick = {
                        val session = arSession
                        if (resolveId.isBlank()) return@Button
                        if (session == null) {
                            Toast.makeText(
                                context, "AR session not ready", Toast.LENGTH_SHORT
                            ).show()
                            return@Button
                        }
                        statusMessage = "Resolving $resolveId\u2026"
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
                                            "+ ARCore API restrictions in STREETSCAPE_SETUP.md."
                                    else -> "Resolve failed: $state"
                                }
                            }
                        }
                    },
                    // Same API-key gate as Host \u2014 Resolve also needs the Cloud
                    // backend.
                    enabled = hasArcoreApiKey && resolveId.isNotBlank()
                ) {
                    Text("Resolve")
                }
            }

            OutlinedTextField(
                value = anchorName,
                onValueChange = { anchorName = it },
                label = { Text("Anchor name (for saving)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = resolveId,
                onValueChange = { resolveId = it },
                label = { Text("Cloud Anchor ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            hostedId?.let {
                Text(
                    text = "Hosted ID: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Saved-anchor registry \u2014 re-resolve on next launch.
            if (registry.entries.isNotEmpty()) {
                Text(
                    text = "Saved anchors (max TTL ${CLOUD_ANCHOR_MAX_TTL_DAYS} days):",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Column {
                    registry.entries.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = {
                                resolveId = entry.cloudAnchorId
                            }) { Text("Use") }
                            TextButton(onClick = { registry.remove(entry.name) }) {
                                Text("Forget")
                            }
                        }
                    }
                }
            }
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
                    config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                },
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
                                // the Google Cloud API key. See samples/android-demo/STREETSCAPE_SETUP.md
                                // for the runbook. Generic states still get the bare label.
                                statusMessage = when (state) {
                                    Anchor.CloudAnchorState.ERROR_NOT_AUTHORIZED ->
                                        "Hosting failed: ERROR_NOT_AUTHORIZED. The ARCore Cloud " +
                                            "API key is rejecting this APK. Check SHA-1 + billing " +
                                            "+ ARCore API restrictions in STREETSCAPE_SETUP.md."
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
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Text(
                    text = statusMessage,
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
        }
    }
}

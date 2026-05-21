package io.github.sceneview.demo.demos

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.AddImageResult
import io.github.sceneview.ar.arcore.captureCameraBitmap
import io.github.sceneview.ar.arcore.rememberRuntimeAugmentedImageDatabase
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Augmented image tracking demo with **on-device runtime image registration** (#1553).
 *
 * Two ways to register an image to track are shown side by side:
 *
 *  1. **Pre-bundled** — a reference image loaded from assets is added to the
 *     [io.github.sceneview.ar.arcore.RuntimeAugmentedImageDatabase] at session creation, and an
 *     in-app "what to scan" card shows the actual target so the user knows where to point.
 *  2. **Runtime capture** — the "Capture this view" button grabs the live AR camera frame as an
 *     `ARGB_8888` bitmap ([Frame.captureCameraBitmap]) and feeds it straight into the same
 *     runtime database. ARCore starts tracking the just-captured scene without any pre-bundled
 *     `arcoreimg` database — fully on-device, no PC needed.
 *
 * Threading: [io.github.sceneview.ar.arcore.RuntimeAugmentedImageDatabase.addImage] does the
 * YUV→ARGB conversion and ARCore feature extraction off the main thread, then re-applies the
 * session config on the main thread itself — the demo only has to call it from a coroutine.
 *
 * When ARCore detects a registered image in the camera feed, an `AugmentedImageNode` is placed
 * at the image location with a 3D model attached.
 */
@Composable
fun ARImageDemo(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val scope = rememberCoroutineScope()
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch - see
    // `rememberArPlaybackDataset` - so live AR is completely unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Runtime, growable augmented-image database. Holds the pre-bundled reference image AND any
    // image the user captures on-device via the "Capture this view" button (#1553).
    val runtimeDatabase = rememberRuntimeAugmentedImageDatabase()

    val detectedImages = remember { mutableStateListOf<AugmentedImage>() }
    var isTracking by remember { mutableStateOf(false) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var imageCount by remember { mutableStateOf(0) }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    // Status of the last on-device capture attempt — surfaced as a transient banner so the
    // user gets specific feedback (added / low-quality / error) rather than a silent button.
    var captureStatus by remember { mutableStateOf<String?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    // "What to scan" card is expanded by default so a first-time user immediately sees the
    // reference image to point the camera at; it collapses to a chip once detection succeeds.
    var showScanGuide by remember { mutableStateOf(true) }

    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_toy_car.glb")

    // Decode the reference image once per composition — ARCore calls sessionConfiguration on
    // every session reconfig, so decoding inside that lambda re-opens the asset + allocates a
    // fresh Bitmap every time (the old bitmap is never recycled either).
    val referenceBitmap = remember(context) {
        context.assets.open("augmented_images/qrcode.png").use {
            BitmapFactory.decodeStream(it)
        }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_image_title),
        onBack = onBack,
        // The on-screen "what to scan" card and the status overlay already tell
        // the user exactly what to do, so the Settings sheet's lone help
        // paragraph was redundant. The only other content was the dev-only
        // ForceTrackingFailureMenu (invisible unless QA mode is on) — gate the
        // whole `controls` block on qaMode so end users get no empty-sheet FAB
        // while QA keeps the debug menu (#1620 thread 1).
        controls = if (DemoSettings.qaMode) {
            {
                // Developer-only debug toggle — lets QA force-emit each
                // TrackingFailureReason so the actionable-message overlay can be
                // validated without staging a real failure. See
                // io.github.sceneview.demo.common.ForcedTrackingFailure / #1881.
                ForceTrackingFailureMenu()
            }
        } else {
            null
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                planeRenderer = false,
                sessionConfiguration = { session: Session, config: Config ->
                    config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                    config.focusMode = Config.FocusMode.AUTO
                    // Apply whatever the runtime database currently holds. ARCore re-runs this
                    // callback on every session (re)configure, so a runtime-captured image
                    // registered via `addImage` survives a reconfigure too.
                    runtimeDatabase.applyTo(config, session)
                },
                onSessionCreated = { session: Session ->
                    // Bind the live session so RuntimeAugmentedImageDatabase.addImage can
                    // rebuild + reconfigure on its own. Seed the pre-bundled reference image.
                    runtimeDatabase.bind(session)
                    scope.launch {
                        runtimeDatabase.addImage("reference", referenceBitmap, 0.15f)
                    }
                },
                onSessionUpdated = { _: Session, frame: Frame ->
                    latestFrame = frame
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    frame.getUpdatedTrackables(AugmentedImage::class.java).forEach { image ->
                        if (image.trackingState == TrackingState.TRACKING &&
                            image.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
                        ) {
                            if (detectedImages.none { it.index == image.index }) {
                                detectedImages.add(image)
                            }
                        }
                    }
                    // Drop stopped images so stale AugmentedImageNodes don't linger in the scene.
                    detectedImages.removeAll { it.trackingState == TrackingState.STOPPED }
                    imageCount = detectedImages.size
                    // Once an image is recognised the user no longer needs the full guide —
                    // collapse it to a chip so it stops covering the AR content.
                    if (imageCount > 0) {
                        showScanGuide = false
                    }
                },
                onTrackingFailureChanged = { reason ->
                    trackingFailureReason = reason
                }
            ) {
                detectedImages.forEach { image ->
                    AugmentedImageNode(
                        augmentedImage = image,
                        applyImageScale = true
                    ) {
                        modelInstance?.let { instance ->
                            ModelNode(
                                modelInstance = instance,
                                scaleToUnits = 0.1f,
                                centerOrigin = Position(0f, 0f, 0f)
                            )
                        }
                    }
                }
            }

            // "What to scan" guide — shows the actual reference target so the user knows
            // exactly which image to point the camera at. Tap to expand/collapse.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .clickable { showScanGuide = !showScanGuide },
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 4.dp,
                shadowElevation = 4.dp
            ) {
                if (showScanGuide) {
                    Column(
                        modifier = Modifier
                            .width(220.dp)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Point your camera at this image",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Image(
                            bitmap = referenceBitmap.asImageBitmap(),
                            contentDescription = "Reference image to scan",
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .size(160.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(8.dp)
                        )
                        Text(
                            text = "Display it on another screen or print it, " +
                                "then aim the camera at it. Or capture your own image below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCode2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Show target image",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            // On-device capture controls — the #1553 "take a photo, add it to the database"
            // flow. Grabs the live AR camera frame, registers it in the runtime database, and
            // ARCore starts tracking that scene with no pre-bundled database.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Status overlay
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    // ForcedTrackingFailure.override shadows the real ARCore-reported reason
                    // when a developer has picked one in the debug menu (#1881). Read it here
                    // (not inside `onTrackingFailureChanged`) so flipping the override from
                    // the debug menu re-renders the overlay immediately, without waiting for
                    // the next ARCore tracking-failure callback. The forced reason wins over
                    // the live tracking state so QA can validate the message overlay even
                    // while a real image is being tracked.
                    val effectiveReason = ForcedTrackingFailure.override ?: trackingFailureReason
                    val trackingHint = trackingFailureMessage(effectiveReason)
                    val statusText = when {
                        captureStatus != null -> captureStatus!!
                        ForcedTrackingFailure.override != null ->
                            trackingHint ?: "Scanning for images…"
                        imageCount > 0 -> "Tracking $imageCount image(s)"
                        !isTracking -> trackingHint ?: "Scanning for images…"
                        else -> "Looking for reference image…"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }

                Button(
                    onClick = {
                        val frame = latestFrame ?: return@Button
                        isCapturing = true
                        captureStatus = "Processing capture…"
                        scope.launch {
                            // Grab + convert the camera frame off the main thread — JPEG
                            // round-trip + YUV->ARGB conversion would jank the renderer.
                            val photo = withContext(Dispatchers.Default) {
                                frame.captureCameraBitmap()
                            }
                            captureStatus = if (photo == null) {
                                "Camera image not ready yet — try again"
                            } else {
                                // addImage runs feature extraction off-thread and re-applies
                                // the session config on the main thread itself (#1553).
                                when (val result = runtimeDatabase.addImage(
                                    name = "capture-${runtimeDatabase.size}",
                                    bitmap = photo
                                )) {
                                    is AddImageResult.Added ->
                                        "Image added — point the camera back at this scene"
                                    is AddImageResult.LowQuality ->
                                        "Too few features — aim at a textured, " +
                                            "high-contrast scene and retry"
                                    is AddImageResult.Error ->
                                        "Capture failed: ${result.cause.message}"
                                }
                            }
                            isCapturing = false
                        }
                    },
                    enabled = !isCapturing && latestFrame != null
                ) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = null
                    )
                    Text(
                        text = "Capture this view",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

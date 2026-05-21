package io.github.sceneview.demo.demos

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.demo.DemoScaffold
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

/**
 * Augmented image tracking demo.
 *
 * Configures an [AugmentedImageDatabase] with a reference image loaded from assets.
 * When ARCore detects the image in the camera feed, an [AugmentedImageNode] is placed
 * at the image location with a 3D model attached.
 *
 * An in-app "what to scan" card shows the actual reference image so the user knows exactly
 * which target to point the camera at — no need to dig through the assets folder. The card
 * collapses to a chip once an image is recognised.
 */
@Composable
fun ARImageDemo(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch - see
    // `rememberArPlaybackDataset` - so live AR is completely unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()

    val detectedImages = remember { mutableStateListOf<AugmentedImage>() }
    var isTracking by remember { mutableStateOf(false) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var imageCount by remember { mutableStateOf(0) }
    // "What to scan" card is expanded by default so a first-time user immediately sees the
    // reference image to point the camera at; it collapses to a chip once detection succeeds.
    var showScanGuide by remember { mutableStateOf(true) }

    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

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
        controls = {
            Text(
                text = "Point the camera at the reference image displayed at the top of the " +
                    "screen. ARCore will attach a 3D model to it once it locks on.",
                style = MaterialTheme.typography.bodyMedium,
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
                planeRenderer = false,
                sessionConfiguration = { session: Session, config: Config ->
                    config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                    config.focusMode = Config.FocusMode.AUTO
                    // Build an augmented image database with the pre-decoded reference bitmap.
                    config.augmentedImageDatabase =
                        AugmentedImageDatabase(session).apply {
                            addImage("reference", referenceBitmap, 0.15f) // 15 cm physical width
                        }
                },
                onSessionUpdated = { _: Session, frame: Frame ->
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
                                "then aim the camera at it. Tap to hide.",
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

            // Status overlay
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
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
                    ForcedTrackingFailure.override != null ->
                        trackingHint ?: "Scanning for images\u2026"
                    imageCount > 0 -> "Tracking $imageCount image(s)"
                    !isTracking -> trackingHint ?: "Scanning for images\u2026"
                    else -> "Looking for reference image\u2026"
                }
                Text(
                    text = statusText,
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

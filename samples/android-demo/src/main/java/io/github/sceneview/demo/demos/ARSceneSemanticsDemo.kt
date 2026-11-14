package io.github.sceneview.demo.demos

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.SemanticLabel
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.semanticImage
import io.github.sceneview.ar.arcore.semanticLabelFraction
import io.github.sceneview.ar.rememberHostLifecycle
import io.github.sceneview.demo.DemoBottomOverlayScope
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.demos.internal.SemanticsOverlay
import io.github.sceneview.demo.isDemoCompactHeight
import io.github.sceneview.demo.isDemoCompactWidth
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader

/**
 * AR demo — Scene Semantics: read ARCore's per-pixel 12-class outdoor semantic model and render
 * it two ways at once — a live top-3 label HUD plus a color-coded segmentation **overlay** that
 * blends over the camera feed via a Camera ↔ Semantic slider (12 classes: SKY, BUILDING, TREE,
 * ROAD, SIDEWALK, TERRAIN, STRUCTURE, OBJECT, VEHICLE, PERSON, WATER, UNLABELED).
 *
 * Pipeline:
 *
 * 1. Configure the session with [Config.SemanticMode.ENABLED]. On devices without the on-device
 *    ML model, the session silently downgrades to `DISABLED` (`ARSession.configure` enforces
 *    the support gate via [io.github.sceneview.ar.arcore.resolveSemanticMode]) and the HUD
 *    surfaces a "not supported" banner — so the screen is never just black (#1617 principle).
 * 2. On every `onSessionUpdated`, call [Frame.semanticLabelFraction] for each of the 12 labels
 *    (a cheap GPU-backed query) to drive the HUD, **and** acquire the full per-pixel raster via
 *    [Frame.semanticImage] and colour it into an `ARGB_8888` [Bitmap] via
 *    [SemanticsOverlay.labelBufferToArgb] — rotated to the display orientation the same way
 *    `ARDepthVisualizationDemo` rotates its depth bitmap (#3184-class mismatch).
 * 3. The bitmap is composited as a Compose [Image], on top of the [ARSceneView] itself, with
 *    `alpha` driven by the Camera ↔ Semantic [Slider] (0.0 = camera only, 1.0 = full overlay) —
 *    the exact same architecture `ARDepthVisualizationDemo` uses for its depth overlay.
 *
 * This demo used to paint the raster with a custom `semantics_overlay.filamat` material on a
 * full-screen quad parented to the AR camera node instead. That never actually appeared: the
 * camera background is deliberately drawn *last* in Filament's render order (`ARCameraStream`
 * priority 7, so it can early-Z-reject pixels already covered by opaque virtual geometry,
 * #1617) — but the overlay material had to disable depth *write* (so `UNLABELED` pixels could
 * stay transparent instead of leaving an opaque hole), so it left nothing in the depth buffer
 * for the camera pass to reject against, and the camera silently overdrew the overlay every
 * frame, on every device. See [SemanticsOverlay]'s class KDoc for the full account. That is the
 * root cause of [#3396](https://github.com/sceneview/sceneview/issues/3396) and
 * [#3527](https://github.com/sceneview/sceneview/issues/3527).
 *
 * **Outdoor only.** Indoors, the ARCore model can return `UNLABELED` or misleading outdoor
 * labels. Take this demo outside (street / park / back yard).
 *
 * Closes [#1868](https://github.com/sceneview/sceneview/issues/1868) — follow-up of
 * [#1730](https://github.com/sceneview/sceneview/issues/1730) (Config wiring + Frame accessors).
 */
@Composable
fun ARSceneSemanticsDemo(onBack: () -> Unit) {
    val context = LocalContext.current
    // Resolved once: the Display *object* is stable for the lifetime of this context, while
    // `rotation` on it is live — so the per-frame read below stays cheap. Same pattern as
    // `ARDepthVisualizationDemo`. `Context.display` was added in API 30; fall back to the
    // deprecated accessor on API 28–29.
    val display = remember(context) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            }
        }.getOrNull()
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Capability gate: null while we wait for the first session.configure callback;
    // true if the device shipped the Scene Semantics ML model; false otherwise.
    // Driving the UI off this avoids the "black screen on unsupported device" trap (#1617).
    var semanticsSupported by remember { mutableStateOf<Boolean?>(null) }

    // `semanticsSupported` stays null for good when the session never configures: ARCore
    // missing or outdated (the SDK shows its install/update card) or a session failure (the
    // scaffold shows its failure card). Same wiring as `ARPointCloudDemo`, so neither card is
    // covered by the outdoor warning, which would otherwise wait on that null forever.
    var arSessionFailed by remember { mutableStateOf(false) }
    var arSessionUnavailable by remember { mutableStateOf(false) }

    // Latest top-3 (label, fraction) snapshot. Captured each `onSessionUpdated` frame; rendered
    // as a Compose overlay. We snapshot the list rather than expose per-label State because the
    // 12-element computation per frame is cheap and the entire HUD recomposes together — no
    // need to atomize updates.
    var topLabels by remember { mutableStateOf<List<LabelFraction>>(emptyList()) }

    // True once we've seen at least one frame with semantic data. Drives a "warming up"
    // overlay so the user knows the model is initialising rather than broken.
    var semanticsEverReceived by remember { mutableStateOf(false) }

    // #3274: the Scene Semantics model has no indoor training data — pointed at a
    // living-room wall it classifies almost every pixel UNLABELED, which the overlay
    // shader paints fully transparent. Without this flag the demo then shows exactly the
    // plain camera feed with zero on-screen explanation, which read to a user as "nothing
    // ...rendered" even though the pipeline was working end to end. Derived from `topLabels`
    // below via the pure [SemanticsOverlay.isOutdoorSceneUnclassified] gate.
    var sceneUnclassified by remember { mutableStateOf(false) }

    var isTracking by remember { mutableStateOf(false) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }

    // Camera ↔ Semantic overlay blend [0..1]. 0 = camera feed only; 1 = full segmentation overlay.
    var overlayBlend by remember { mutableStateOf(0.5f) }

    // ── Compose bitmap overlay (#1868, #3396, #3527) ──────────────────────────────────
    // Mutable bitmap; reallocated when the semantic-image resolution or display rotation
    // changes. Composited as a Compose Image on top of the ARSceneView — see the class KDoc
    // for why a Filament 3D quad could never actually appear over the live camera feed.
    var semanticsBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var bitmapWidth by remember { mutableStateOf(0) }
    var bitmapHeight by remember { mutableStateOf(0) }
    // Version counter incremented after every successful upload so Compose recomposes the
    // Image even though the Bitmap reference is unchanged.
    var semanticsFrameVersion by remember { mutableStateOf(0) }

    val legendShown = semanticsEverReceived && overlayBlend > LEGEND_MIN_BLEND

    DemoScaffold(
        arSessionFailed = arSessionFailed,
        arOverlaysEnabled = !arSessionUnavailable,
        title = stringResource(R.string.demo_ar_scene_semantics_title),
        onBack = onBack,
        controls = {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Semantic overlay",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Each pixel of the camera feed is color-coded by ARCore's " +
                            "12-class outdoor scene model. Slide to blend.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Camera", style = MaterialTheme.typography.bodyMedium)
                Text(text = "Semantic", style = MaterialTheme.typography.bodyMedium)
            }
            Slider(
                value = overlayBlend,
                onValueChange = { overlayBlend = SemanticsOverlay.clampUnit(it) },
                enabled = semanticsSupported != false,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Blend: ${(overlayBlend * 100).toInt()} %",
                style = MaterialTheme.typography.labelMedium
            )

            // Developer-only debug toggle — visible when QA mode is on. Lets QA force-emit each
            // TrackingFailureReason so the actionable-message overlay can be validated without
            // staging a real failure. See io.github.sceneview.demo.common.ForcedTrackingFailure.
            if (DemoSettings.qaMode) {
                ForceTrackingFailureMenu()
            }
        },
        // Top band: the label HUD, the semantic notice and any tracking failure. They are
        // siblings in the scaffold's top Column, so they stack instead of overlapping —
        // this demo used to place them by arithmetic (8 dp, 8 dp, 56 dp), which held only
        // while the pill above stayed one line at font scale 1.0 in English (#3237).
        topOverlay = {
            SceneSemanticsTopBand(legendShown = legendShown) {
                // Top-start HUD listing the 3 highest semantic-label fractions. Hidden until the
                // first non-zero update so the empty list doesn't show during startup warmup.
                if (semanticsSupported == true && semanticsEverReceived && topLabels.isNotEmpty()) {
                    SceneSemanticsLabelHud(
                        labels = topLabels,
                        modifier = Modifier.align(Alignment.Start),
                    )
                }

                // Device-unsupported banner — surfaced on-screen (not buried in a
                // Settings sheet) so a user on a device without the Scene Semantics
                // ML model immediately understands why the HUD never appears (#1620
                // thread 1).
                AnimatedVisibility(
                    visible = semanticsSupported == false,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text(
                            text = "Your device doesn't have the ARCore Scene Semantics " +
                                "model — see ARCore docs for the supported device list.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                // One banner owns the persistent outdoor warning and the current semantic state,
                // including UNLABELED frames whose transparent overlay can look broken (#3274).
                SceneSemanticsOutdoorNotice(
                    arSessionBlocked = arSessionFailed || arSessionUnavailable,
                    semanticsSupported = semanticsSupported,
                    semanticsEverReceived = semanticsEverReceived,
                    sceneUnclassified = sceneUnclassified,
                )

                // Tracking-failure overlay — same vocabulary as the other AR demos.
                // ForcedTrackingFailure.override shadows the real ARCore-reported reason
                // when a developer has picked one in the debug menu (#1881). Read it here
                // so flipping the override re-renders the overlay immediately.
                val effectiveReason = ForcedTrackingFailure.override ?: trackingFailureReason
                AnimatedVisibility(
                    visible = (!isTracking && trackingFailureReason != null) ||
                        ForcedTrackingFailure.override != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text(
                            text = when (effectiveReason) {
                                TrackingFailureReason.INSUFFICIENT_LIGHT -> "Not enough light"
                                TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast"
                                TrackingFailureReason.INSUFFICIENT_FEATURES ->
                                    "Not enough detail — point at a textured outdoor scene"
                                TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
                                TrackingFailureReason.BAD_STATE -> "AR session error"
                                else -> stringResource(R.string.ar_status_scanning)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        },
        // Color legend — maps the 12 overlay colors to their class names so the segmentation
        // is readable. Only relevant once the overlay is actually showing. Portrait keeps it in
        // the bottom slot; a phone held sideways moves it into the top band, beside the HUD
        // (see [SceneSemanticsTopBand] for why).
        bottomOverlay = {
            SceneSemanticsBottomLegend(legendShown = legendShown)
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                lifecycle = rememberHostLifecycle(),
                onSessionFailure = { arSessionFailed = true },
                onARCoreAvailability = { arSessionUnavailable = it != null },
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                sessionConfiguration = { session: Session, config: Config ->
                    // Capability probe — uses ARCore's `isSemanticModeSupported` so we set the
                    // mode only when the on-device ML model is present. `ARSession.configure`
                    // applies the same gate as a defense-in-depth fallback (resolveSemanticMode).
                    val supported = session.isSemanticModeSupported(Config.SemanticMode.ENABLED)
                    semanticsSupported = supported
                    config.semanticMode = if (supported) {
                        Config.SemanticMode.ENABLED
                    } else {
                        Config.SemanticMode.DISABLED
                    }
                },
                onSessionUpdated = { _, frame: Frame ->
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    if (semanticsSupported == true && isTracking) {
                        // Cheap per-label fractions — semanticLabelFraction is a GPU-backed
                        // query, no Image acquire/close lifecycle. Drives the HUD.
                        val snapshot = SemanticLabel.values()
                            .map { LabelFraction(it, frame.semanticLabelFraction(it)) }
                            .sortedByDescending { it.fraction }
                            .take(3)
                        topLabels = snapshot
                        if (snapshot.any { it.fraction > 0f }) {
                            semanticsEverReceived = true
                        }
                        // #3274 — a dominant UNLABELED top label means the model has
                        // classified essentially nothing this frame (typically: indoors).
                        sceneUnclassified = snapshot.firstOrNull()?.let {
                            SemanticsOverlay.isOutdoorSceneUnclassified(
                                topOrdinal = it.label.ordinal,
                                topFraction = it.fraction,
                            )
                        } ?: false

                        // Per-pixel raster overlay (#3396, #3527) — acquire the R8 semantic
                        // image, colour it into an ARGB bitmap rotated to the display, and
                        // composite it as a Compose Image above the ARSceneView. See the class
                        // KDoc for why a Filament 3D quad never actually appeared on screen.
                        val image = frame.semanticImage()
                        if (image != null) {
                            try {
                                val plane = image.planes[0]
                                val w = image.width
                                val h = image.height
                                val rotation = SemanticsOverlay.displayRotationToDegrees(
                                    display?.rotation ?: Surface.ROTATION_0
                                )
                                val pixels = SemanticsOverlay.labelBufferToArgb(
                                    labelBytes = plane.buffer,
                                    width = w,
                                    height = h,
                                    rowStrideBytes = plane.rowStride,
                                    rotationDegrees = rotation,
                                )
                                // Post-rotation dimensions — swapped on a quarter turn.
                                val outW = SemanticsOverlay.rotatedWidth(w, h, rotation)
                                val outH = SemanticsOverlay.rotatedHeight(w, h, rotation)
                                if (semanticsBitmap == null ||
                                    outW != bitmapWidth ||
                                    outH != bitmapHeight
                                ) {
                                    semanticsBitmap =
                                        Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                                    bitmapWidth = outW
                                    bitmapHeight = outH
                                }
                                semanticsBitmap?.setPixels(pixels, 0, outW, 0, 0, outW, outH)
                                semanticsFrameVersion++
                            } finally {
                                runCatching { image.close() }
                            }
                        }
                    }
                },
                onTrackingFailureChanged = { reason ->
                    trackingFailureReason = reason
                },
            )

            // Read the version so Compose recomposes when a new semantic frame arrives.
            val versionRead = semanticsFrameVersion
            val bitmap = semanticsBitmap
            if (bitmap != null && versionRead >= 0) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = overlayBlend,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Persistent outdoor warning and semantic status share one banner to avoid duplicate guidance.
 *
 * [arSessionBlocked] is true when ARCore is missing, needs an update, or the session failed:
 * [semanticsSupported] then never leaves null, and the SDK's ARCore card or the scaffold's
 * failure card owns the screen instead.
 */
@Composable
internal fun SceneSemanticsOutdoorNotice(
    arSessionBlocked: Boolean,
    semanticsSupported: Boolean?,
    semanticsEverReceived: Boolean,
    sceneUnclassified: Boolean,
) {
    if (!arSessionBlocked && semanticsSupported != false) {
        Surface(
            modifier = Modifier.testTag(OUTDOOR_NOTICE_TAG),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Trained on outdoor scenes — labels are unreliable indoors",
                    style = MaterialTheme.typography.labelLarge,
                )
                val status = when {
                    semanticsSupported != true -> null
                    !semanticsEverReceived -> "Warming up Scene Semantics…"
                    sceneUnclassified -> "Nothing classified yet — try a street, park or back yard."
                    else -> null
                }
                if (status != null) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** Below this Camera ↔ Semantic blend the overlay is too faint for its legend to mean anything. */
private const val LEGEND_MIN_BLEND = 0.05f

/** Test tag of the color legend, wherever the window puts it. */
internal const val SEMANTIC_LEGEND_TAG = "scene-semantics-legend"

/** Test tag of the outdoor warning pill — the whole Surface, not only its first line. */
internal const val OUTDOOR_NOTICE_TAG = "scene-semantics-outdoor-notice"

/**
 * Whether the legend goes beside the HUD in the top band rather than in the bottom band: only in
 * a window both short and wide, a phone held sideways (#4036). A short but narrow window — one
 * half of a split screen, 393 × 400 dp — has no room for two columns: halved, the HUD's "41 %"
 * wrapped one character per line and the warning was pushed down under the dock, so it keeps
 * the portrait layout.
 */
@Composable
@ReadOnlyComposable
private fun legendBesideHud(): Boolean = isDemoCompactHeight() && !isDemoCompactWidth()

/**
 * The demo's `topOverlay` content, with the color legend beside it on a phone held sideways.
 *
 * The scaffold stacks each overlay slot as a Column, so nothing inside the top band can meet
 * anything else inside it — but the top band (below the identity row) and the bottom band
 * (above the dock) are two independent Columns. On a phone held sideways (Pixel 4a: 393 dp of
 * window height) the HUD and the outdoor warning reached down past the top of the legend, and
 * the legend sat across the lower half of the warning, in light and dark theme alike (#4036).
 *
 * So in a short and wide window ([legendBesideHud]) the legend leaves the bottom band and
 * becomes the end column of a Row whose start column is [content]: two Row cells share no
 * horizontal pixels, so the warning and the legend cannot overlap at any font scale, and the
 * bottom band stays empty above the dock. Portrait is unchanged: [content] goes straight into
 * the slot's Column and the legend stays in the bottom band ([SceneSemanticsBottomLegend]).
 */
@Composable
internal fun ColumnScope.SceneSemanticsTopBand(
    legendShown: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (legendShown && legendBesideHud()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                content = content,
            )
            SemanticLegend(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = SceneViewTokens.Space.md)
            )
        }
    } else {
        content()
    }
}

/**
 * The demo's `bottomOverlay` content: the color legend, in portrait only — sideways it is drawn
 * by [SceneSemanticsTopBand] instead (#4036).
 *
 * Start-aligned in the scaffold's bottom slot (#2779): hand-anchored bottom-center, the wide
 * card's end edge used to run under the Settings FAB.
 */
@Composable
internal fun DemoBottomOverlayScope.SceneSemanticsBottomLegend(legendShown: Boolean) {
    if (legendShown && !legendBesideHud()) {
        SemanticLegend(
            modifier = Modifier
                .align(Alignment.Start)
                .padding(start = SceneViewTokens.Space.md, end = settingsFabReservedSpace)
        )
    }
}

/** Snapshot of one ARCore semantic-class fraction for the current frame. */
internal data class LabelFraction(val label: SemanticLabel, val fraction: Float)

/** Top-start HUD listing the highest semantic-label fractions of the current frame. */
@Composable
internal fun SceneSemanticsLabelHud(labels: List<LabelFraction>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp)),
        color = Color.Black.copy(alpha = 0.65f),
        contentColor = Color.White
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = "Scene Semantics",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(6.dp))
            labels.forEach { (label, fraction) ->
                LabelRow(label, fraction)
            }
        }
    }
}

/** Single row of the HUD — semi-mono label name + a small bar + a percentage. */
@Composable
private fun LabelRow(label: SemanticLabel, fraction: Float) {
    val clampedFraction = fraction.coerceIn(0f, 1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label.name.padEnd(10),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = Color.White
        )
        // Inline progress bar — width proportional to fraction. Drawn as a Box so we don't
        // pull in LinearProgressIndicator (which animates and would look jittery at ~30 Hz
        // semantic updates).
        Box(
            modifier = Modifier
                .height(8.dp)
                .width((80f * clampedFraction).dp)
                .background(
                    color = Color(0xFF4FC3F7),
                    shape = RoundedCornerShape(4.dp)
                )
        )
        Text(
            text = "${(clampedFraction * 100).toInt()} %",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = Color.White
        )
    }
}

/**
 * Bottom color legend — one tiny swatch + class name per ARCore semantic label, so the
 * segmentation overlay is readable. Colors come from [SemanticsOverlay.PALETTE_ARGB], which
 * mirrors the palette baked into `semantics_overlay.mat`.
 *
 * Uses [FlowRow] rather than a hand-chunked 4-items-per-row [Row]: the old fixed grouping
 * assumed every row of four labels fits the reserved band, which held for "Sky Building Tree
 * Road" and "Vehicle Person Water" but not for "Sidewalk Terrain Structure Object" — the widest
 * row. A bounded, non-wrapping [Row] doesn't overflow visibly when its children run out of
 * room; it squeezes the last child's max-width constraint down instead, so "Object" wrapped
 * one character per line on a real device (Pixel 4a device QA, #3295 follow-up) even though
 * nothing was clipped or crashed. [FlowRow] measures each swatch+label pair as one unit and
 * wraps whole units to the next line, so a label is never split mid-word regardless of screen
 * width, density or the Settings FAB's reserved band.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SemanticLegend(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.testTag(SEMANTIC_LEGEND_TAG),
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.medium
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // UNLABELED (last entry) is intentionally omitted — it renders transparent.
            for (ordinal in 0 until SemanticsOverlay.UNLABELED_ORDINAL) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(SemanticsOverlay.PALETTE_ARGB[ordinal]))
                    )
                    Text(
                        text = SemanticsOverlay.LABEL_NAMES[ordinal],
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

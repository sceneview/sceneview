package io.github.sceneview.demo.demos

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.android.filament.Texture
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.SemanticLabel
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.semanticImage
import io.github.sceneview.ar.arcore.semanticLabelFraction
import io.github.sceneview.ar.node.ARCameraNode
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.demos.internal.SemanticsOverlay
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.material.setSemanticsOpacity
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader

/**
 * Local-space distance (metres) from the AR camera at which the full-screen semantic-overlay
 * quad is parented. Any value inside the near/far clip range works — the quad is sized to the
 * camera frustum at this distance every frame, so it always fills the viewport exactly.
 */
private const val OVERLAY_QUAD_DISTANCE = 1.0f

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
 *    (a cheap GPU-backed query) to drive the HUD, **and** acquire the full per-pixel raster
 *    via [Frame.semanticImage] and upload it into a Filament `R8` [Texture].
 * 3. The overlay quad is a full-screen [PlaneNode] parented to the AR camera node, painted with
 *    the **`semantics_overlay.filamat`** material ([io.github.sceneview.loaders.MaterialLoader.createSemanticsOverlayInstance]):
 *    the shader maps each label ordinal to one of 12 fixed colors. A Compose [Slider] drives
 *    `opacity` (0.0 = camera only, 1.0 = full overlay) — same blend UX as `ARDepthVisualizationDemo`.
 *
 * The custom `.filamat` shader is the per-pixel label-overlay port of Google's
 * `samples/semantics_java` `background_semantics.frag`, deferred from #1730 / PR #1867 so the
 * matc-toolchain ABI work could be sequenced after the Config + Frame-accessor API landed.
 *
 * **Outdoor only.** The ARCore model has no indoor training data — pointing the camera at a
 * living-room wall will return mostly `UNLABELED`. Take this demo outside (street / park /
 * back yard).
 *
 * Closes [#1868](https://github.com/sceneview/sceneview/issues/1868) — follow-up of
 * [#1730](https://github.com/sceneview/sceneview/issues/1730) (Config wiring + Frame accessors).
 */
@Composable
fun ARSceneSemanticsDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val arPlaybackDataset = rememberArPlaybackDataset()

    // The AR camera node is hoisted so the semantic-overlay quad can be parented to it — the
    // quad then tracks the camera and is sized to its frustum every frame, filling the viewport.
    val cameraNode = rememberARCameraNode(engine)

    // Capability gate: null while we wait for the first session.configure callback;
    // true if the device shipped the Scene Semantics ML model; false otherwise.
    // Driving the UI off this avoids the "black screen on unsupported device" trap (#1617).
    var semanticsSupported by remember { mutableStateOf<Boolean?>(null) }

    // Latest top-3 (label, fraction) snapshot. Captured each `onSessionUpdated` frame; rendered
    // as a Compose overlay. We snapshot the list rather than expose per-label State because the
    // 12-element computation per frame is cheap and the entire HUD recomposes together — no
    // need to atomize updates.
    var topLabels by remember { mutableStateOf<List<LabelFraction>>(emptyList()) }

    // True once we've seen at least one frame with semantic data. Drives a "warming up"
    // overlay so the user knows the model is initialising rather than broken.
    var semanticsEverReceived by remember { mutableStateOf(false) }

    var isTracking by remember { mutableStateOf(false) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }

    // Camera ↔ Semantic overlay blend [0..1]. 0 = camera feed only; 1 = full segmentation overlay.
    var overlayBlend by remember { mutableStateOf(0.5f) }

    // ── Filament resources for the GPU overlay (#1868) ──────────────────────────────────────
    // The semantic raster is uploaded into this single-channel R8 texture, recreated only when
    // the ARCore image dimensions change. The PlaneNode paints it with the custom .filamat.
    // Held in `remember` holders so they survive recomposition; released in the DisposableEffect.
    val overlay = remember { SemanticsOverlayResources() }

    DisposableEffect(Unit) {
        onDispose { overlay.release(materialLoader) }
    }

    DemoScaffold(
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
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                cameraNode = cameraNode,
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

                        // Per-pixel raster overlay — acquire the R8 semantic image, upload it
                        // into the Filament texture, (re)build the camera-parented overlay quad
                        // and size it to the current frustum. All Filament JNI calls run here on
                        // the ARCore frame callback (the render/main thread) — never off-thread.
                        overlay.update(
                            frame = frame,
                            engine = engine,
                            materialLoader = materialLoader,
                            cameraNode = cameraNode,
                            opacity = overlayBlend
                        )
                    }
                },
                onTrackingFailureChanged = { reason ->
                    trackingFailureReason = reason
                },
            )

            // Top-left HUD listing the 3 highest semantic-label fractions. Hidden until the
            // first non-zero update so the empty list doesn't show during startup warmup.
            if (semanticsSupported == true && semanticsEverReceived && topLabels.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
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
                        topLabels.forEach { (label, fraction) ->
                            LabelRow(label, fraction)
                        }
                    }
                }
            }

            // Device-unsupported banner — surfaced on-screen (not buried in a
            // Settings sheet) so a user on a device without the Scene Semantics
            // ML model immediately understands why the HUD never appears (#1620
            // thread 1).
            AnimatedVisibility(
                visible = semanticsSupported == false,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
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

            // Warming-up overlay — shown when supported but no semantic data yet (first
            // ~5 frames after resume on the typical device). Crucial: never leave the user
            // staring at a black screen (see #1617) if semantics takes a moment to warm up.
            AnimatedVisibility(
                visible = semanticsSupported == true && !semanticsEverReceived,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = "Warming up Scene Semantics — point the camera at an outdoor scene",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // Bottom color legend — maps the 12 overlay colors to their class names so the
            // segmentation is readable. Only relevant once the overlay is actually showing.
            if (semanticsEverReceived && overlayBlend > 0.05f) {
                SemanticLegend(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                )
            }

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
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
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
    }
}

/**
 * Holds the Filament resources backing the GPU semantic overlay: the `R8` [Texture] that
 * receives the per-pixel raster, the camera-parented full-screen [PlaneNode], and its
 * `semantics_overlay.filamat` material instance. Recreated lazily and reused across frames so
 * the per-frame cost is one `setImage` upload plus a frustum-fit resize.
 *
 * All members are touched only from the ARCore frame callback (the render/main thread); none
 * are accessed off-thread, so no synchronization is needed.
 */
private class SemanticsOverlayResources {
    private var texture: Texture? = null
    private var textureWidth = 0
    private var textureHeight = 0
    private var quad: PlaneNode? = null

    /**
     * Acquires the semantic raster from [frame], uploads it into the (lazily (re)created)
     * `R8` texture, builds the overlay quad on first use, sizes it to the camera frustum, and
     * applies [opacity]. A no-op when semantics are not yet available.
     */
    @Suppress("LongMethod") // Filament texture upload + quad lifecycle is inherently multi-step
    fun update(
        frame: Frame,
        engine: com.google.android.filament.Engine,
        materialLoader: io.github.sceneview.loaders.MaterialLoader,
        cameraNode: ARCameraNode,
        opacity: Float
    ) {
        val image = frame.semanticImage() ?: return
        try {
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) return

            val plane = image.planes[0]
            val packed = SemanticsOverlay.stripRowStride(
                source = plane.buffer,
                width = width,
                height = height,
                rowStrideBytes = plane.rowStride
            )

            // (Re)create the R8 texture when the ARCore image resolution changes.
            if (texture == null || width != textureWidth || height != textureHeight) {
                texture?.let { runCatching { engine.destroyTexture(it) } }
                texture = Texture.Builder()
                    .width(width)
                    .height(height)
                    .levels(1)
                    .sampler(Texture.Sampler.SAMPLER_2D)
                    .format(Texture.InternalFormat.R8)
                    .build(engine)
                textureWidth = width
                textureHeight = height
                // The texture identity changed — rebuild the quad so its material instance
                // samples the fresh texture.
                disposeQuad(cameraNode, materialLoader)
            }

            val tex = texture ?: return
            tex.setImage(
                engine,
                0,
                Texture.PixelBufferDescriptor(
                    packed,
                    Texture.Format.R,
                    Texture.Type.UBYTE
                )
            )

            // Build the camera-parented full-screen quad on first use.
            if (quad == null) {
                quad = PlaneNode(
                    engine = engine,
                    size = Size(1.0f, 1.0f),
                    center = Position(0.0f, 0.0f, -OVERLAY_QUAD_DISTANCE),
                    // Plane faces the camera (-Z is the look direction; the quad's outward
                    // normal must point back toward the camera at +Z in camera-local space).
                    normal = Direction(0.0f, 0.0f, 1.0f),
                    materialInstance = materialLoader.createSemanticsOverlayInstance(
                        semanticTexture = tex,
                        opacity = opacity
                    )
                ).also { cameraNode.addChildNode(it) }
            }

            // Size the quad to exactly fill the camera frustum at OVERLAY_QUAD_DISTANCE.
            // The projection matrix is column-major: m[5] = 1/tan(vFov/2), m[0] = m[5]/aspect.
            val projection = cameraNode.projectionTransform
            val m11 = projection.y.y
            val m00 = projection.x.x
            quad?.let { node ->
                if (m11 != 0.0f && m00 != 0.0f) {
                    val visibleHeight = 2.0f * OVERLAY_QUAD_DISTANCE / m11
                    val visibleWidth = 2.0f * OVERLAY_QUAD_DISTANCE / m00
                    node.updateGeometry(
                        size = Size(visibleWidth, visibleHeight),
                        center = Position(0.0f, 0.0f, -OVERLAY_QUAD_DISTANCE),
                        normal = Direction(0.0f, 0.0f, 1.0f)
                    )
                }
                node.materialInstance.setSemanticsOpacity(opacity)
            }
        } finally {
            runCatching { image.close() }
        }
    }

    private fun disposeQuad(
        cameraNode: ARCameraNode,
        materialLoader: io.github.sceneview.loaders.MaterialLoader
    ) {
        quad?.let { node ->
            cameraNode.removeChildNode(node)
            val mi = node.materialInstance
            node.destroy()
            materialLoader.destroyMaterialInstance(mi)
        }
        quad = null
    }

    /** Releases the texture, the quad and its material instance. Call on demo teardown. */
    fun release(materialLoader: io.github.sceneview.loaders.MaterialLoader) {
        quad?.let { node ->
            val mi = runCatching { node.materialInstance }.getOrNull()
            runCatching { node.destroy() }
            mi?.let { materialLoader.destroyMaterialInstance(it) }
        }
        quad = null
        texture?.let { tex ->
            // The quad's renderable is already destroyed above, so the texture is no longer
            // bound — safe to destroy directly on the engine.
            runCatching { materialLoader.engine.destroyTexture(tex) }
        }
        texture = null
    }
}

/** Snapshot of one ARCore semantic-class fraction for the current frame. */
private data class LabelFraction(val label: SemanticLabel, val fraction: Float)

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
 */
@Composable
private fun SemanticLegend(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // UNLABELED (last entry) is intentionally omitted — it renders transparent.
            for (rowStart in 0 until SemanticsOverlay.UNLABELED_ORDINAL step 4) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (ordinal in rowStart until minOf(rowStart + 4, SemanticsOverlay.UNLABELED_ORDINAL)) {
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
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

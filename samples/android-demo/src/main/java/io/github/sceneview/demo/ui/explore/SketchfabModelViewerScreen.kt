@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)

package io.github.sceneview.demo.ui.explore

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.sceneview.demo.R
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.createEnvironment
import io.github.sceneview.fitDistanceForBounds
import io.github.sceneview.toAabb
import io.github.sceneview.verticalFovDegreesForFocalLength
import io.github.sceneview.demo.rememberHeroOrbitCameraManipulator
import io.github.sceneview.demo.sketchfab.SketchfabModel
import io.github.sceneview.demo.sketchfab.SketchfabService
import io.github.sceneview.demo.ui.explore.components.AsyncNetworkImage
import io.github.sceneview.demo.ui.explore.components.formattedFaceCount
import io.github.sceneview.demo.ui.explore.components.preferredThumbnailUrl
import io.github.sceneview.demo.ui.explore.components.primaryTagDisplay
import com.google.android.filament.Engine
import com.google.android.filament.LightManager
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.model
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Detail sheet for a Sketchfab [model]. Two-state UI:
 *
 *  1. **Preview**  : large thumbnail + stats + "Open in SceneView" CTA.
 *  2. **Rendering**: SceneView composable renders the downloaded GLB.
 *
 * Constraints from the product brief:
 *  - All models render through the **SceneView SDK** — never the Sketchfab
 *    iframe / web viewer / external app. The whole point of the demo app is
 *    to showcase the SDK; falling back to Sketchfab's player undermines it.
 *  - No "View on Sketchfab" external link.
 *
 * The bottom sheet uses M3 [ModalBottomSheet] with the `fillMaxSize` skip-partially-expanded
 * state for a near-full-height experience (we want enough room for the live
 * SceneView surface).
 */
@Composable
fun SketchfabModelViewerScreen(
    model: SketchfabModel,
    onDismiss: () -> Unit,
) {
    var stage by remember(model.uid) { mutableStateOf<Stage>(Stage.Preview) }

    // Pre-warm the Filament Engine on the first transition out of Preview.
    //
    // Engine creation is a synchronous JNI call on the main thread; on a real
    // device it freezes the UI for ~5 s (Choreographer "Skipped 349 frames!"
    // on Thomas's Pixel for #2193). Previous fix attempts:
    //   - Engine at sheet root → 5 s freeze the instant the user taps the
    //     model card. Frozen Explore tab + ANR dialog. #2193 BLOCKER.
    //   - Engine inside `RenderContent` → freeze shifts to the moment the
    //     download completes. Frozen Ken-Burns + stopped spinner = the user
    //     sees a "loaded but stuck" UI before the model appears.
    //
    // Anchoring the `rememberEngine` slot here, gated on `stage !is Preview`,
    // moves the freeze to the Preview → Downloading transition. The user
    // taps "Open in SceneView", sees the spinner / Ken-Burns appear, and the
    // freeze happens while a "loading" affordance is already on screen.
    // Compose Engine slot survives the subsequent Downloading → Rendering
    // transition (parent composition is stable), so the model renders the
    // instant `rememberModelInstance` finishes parsing the GLB — no second
    // freeze. (#2193 follow-up review.)
    val engineNeeded = stage !is Stage.Preview
    val engine: Engine? = if (engineNeeded) rememberEngine() else null
    val modelLoader: ModelLoader? = engine?.let { rememberModelLoader(it) }
    val environmentLoader: EnvironmentLoader? = engine?.let { rememberEnvironmentLoader(it) }

    // Fullscreen Dialog instead of ModalBottomSheet (#2227). The bottom-sheet
    // drag gesture conflicted with the SceneView orbit / pinch gestures inside
    // RenderContent — vertical drags closed the sheet instead of tilting the
    // camera. Dialog with `usePlatformDefaultWidth = false` + `Surface` filling
    // the screen gives the viewer the entire viewport (no gesture rival) and
    // lets the new ground-shadow plane (#2235) read against a stable backdrop.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // System back exits the viewer (Compose Dialog handles this for
            // us); the in-app X button below is the primary exit affordance.
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        BackHandler(onBack = onDismiss)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) { Box(modifier = Modifier.fillMaxSize()) {
        // SharedTransitionLayout + AnimatedContent so the hero thumbnail
        // morphs smoothly between Preview (220 dp card) → Downloading
        // (440 dp Ken-Burns) → Rendering (live SceneView surface). Each
        // stage receives a `heroModifier` slot carrying `sharedBounds` +
        // a matching `clip(RoundedCornerShape(24.dp))` so the corner
        // radius stays consistent through the morph (without the clip
        // baked into the shared modifier, Preview's rounded corners
        // would snap to square corners at frame 1 of the animation).
        // Stage.Error opts out — there is no hero so reusing the bounds
        // would animate the error card sliding out of empty space.
        val heroShape = RoundedCornerShape(24.dp)
        val heroKey = "sketchfab-hero-${model.uid}"
        SharedTransitionLayout {
            AnimatedContent(
                targetState = stage,
                transitionSpec = {
                    // Stages that share the hero (Preview / Downloading /
                    // Rendering) opt out of fade so only `sharedBounds`
                    // drives the morph — a parallel fadeIn/fadeOut would
                    // wash the hero semi-transparent halfway through. Only
                    // Stage.Error (no shared element) uses a real fade.
                    val errorInvolved = initialState is Stage.Error || targetState is Stage.Error
                    if (errorInvolved) {
                        fadeIn(animationSpec = tween(300)) togetherWith
                            fadeOut(animationSpec = tween(300))
                    } else {
                        EnterTransition.None togetherWith ExitTransition.None
                    }
                },
                label = "sketchfab-stage",
            ) { s ->
                val heroModifier: Modifier = when (s) {
                    Stage.Preview, Stage.Downloading, is Stage.Rendering ->
                        Modifier
                            .sharedBounds(
                                sharedContentState = rememberSharedContentState(key = heroKey),
                                animatedVisibilityScope = this@AnimatedContent,
                            )
                            .clip(heroShape)
                    is Stage.Error -> Modifier
                }
                when (s) {
                    Stage.Preview -> PreviewContent(
                        model = model,
                        onOpenInSceneView = { stage = Stage.Downloading },
                        heroModifier = heroModifier,
                    )
                    Stage.Downloading -> DownloadingContent(
                        model = model,
                        onReady = { file -> stage = Stage.Rendering(file) },
                        onError = { stage = Stage.Error(it) },
                        heroModifier = heroModifier,
                    )
                    is Stage.Rendering -> RenderContent(
                        file = s.file,
                        model = model,
                        // Engine + loaders are guaranteed non-null whenever
                        // `stage` is past Preview (see `engineNeeded` above).
                        engine = engine!!,
                        modelLoader = modelLoader!!,
                        environmentLoader = environmentLoader!!,
                        heroModifier = heroModifier,
                    )
                    is Stage.Error -> ErrorContent(message = s.message, onRetry = { stage = Stage.Preview })
                }
            }
        }

        // Top-end close button — the only exit affordance now that the
        // bottom-sheet drag handle is gone (#2227). System back gesture also
        // dismisses (see BackHandler / DialogProperties.dismissOnBackPress).
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.feedback_cd_close),
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        } } // close Box, Surface
    } // close Dialog
}

private sealed interface Stage {
    data object Preview : Stage
    data object Downloading : Stage
    data class Rendering(val file: File) : Stage
    data class Error(val message: String) : Stage
}

// ── Preview ───────────────────────────────────────────────────────────────

@Composable
private fun PreviewContent(
    model: SketchfabModel,
    onOpenInSceneView: () -> Unit,
    heroModifier: Modifier = Modifier,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(24.dp))
                .then(heroModifier),
        ) {
            AsyncNetworkImage(
                url = model.preferredThumbnailUrl(minWidth = 640, maxWidth = 1280),
                contentDescription = model.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (model.isAnimated) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(12.dp)
                        .background(
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(50),
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.sketchfab_animated),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = model.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = model.primaryTagDisplay(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        StatsRow(model)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onOpenInSceneView,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.sketchfab_open_in_sceneview),
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.sketchfab_rendered_locally),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun StatsRow(model: SketchfabModel) {
    val scroll = rememberScrollState()
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(scroll),
    ) {
        if (model.faceCount > 0) StatChip(label = "${model.formattedFaceCount()} polys")
        if (model.animationCount > 0) StatChip(label = "${model.animationCount} anim")
        if (model.likeCount > 0) StatChip(label = "${model.likeCount} likes")
        model.tags.orEmpty().take(3).forEach { tag -> StatChip(label = tag.name) }
    }
}

@Composable
private fun StatChip(label: String) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Downloading ───────────────────────────────────────────────────────────

/**
 * Hero state while the GLB is downloading. Shows the Sketchfab thumbnail with a
 * slow Ken-Burns zoom (1.0 → 1.18) and a soft blur, so the screen feels like a
 * premium preview instead of a spinner. Morphs into the live SceneView via
 * the parent [SharedTransitionLayout] + [AnimatedContent] when [RenderContent]
 * takes over — the 440 dp hero Box keeps its shared bounds across the swap.
 *
 * When the server sends a `Content-Length` header the card switches from an
 * indeterminate [CircularProgressIndicator] to a determinate
 * [LinearProgressIndicator] + `X.X / Y.Y MB` counter so the user knows how
 * long they are waiting (#2232).
 */
@Composable
private fun DownloadingContent(
    model: SketchfabModel,
    onReady: (File) -> Unit,
    onError: (String) -> Unit,
    heroModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Capture outside the coroutine so stringResource() is called on the composition thread.
    val downloadFailedMsg = stringResource(R.string.sketchfab_download_failed)

    // (bytesRead, totalBytes) — totalBytes == -1L when server omits Content-Length.
    var downloadProgress by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    LaunchedEffect(model.uid) {
        // Conflated channel: only the latest progress event matters for the UI.
        val progressChannel = Channel<Pair<Long, Long>>(Channel.CONFLATED)
        // Collect on the main coroutine so Compose state is written on the UI thread.
        val collector = launch {
            for (p in progressChannel) { downloadProgress = p }
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                SketchfabService.getInstance(context).downloadModel(model.uid) { read, total ->
                    progressChannel.trySend(Pair(read, total))
                }
            }
        }
        progressChannel.close()
        collector.join()
        result.fold(
            onSuccess = onReady,
            onFailure = { onError(it.message ?: downloadFailedMsg) },
        )
    }

    val infinite = rememberInfiniteTransition(label = "thumb-zoom")
    val zoom by infinite.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8_000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thumb-zoom",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(440.dp)
            .then(heroModifier),
    ) {
        AsyncNetworkImage(
            url = model.preferredThumbnailUrl(minWidth = 640, maxWidth = 1280),
            contentDescription = model.name,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = zoom; scaleY = zoom }
                .blur(8.dp),
            contentScale = ContentScale.Crop,
        )
        // Darken the thumbnail so the foreground progress card stays legible.
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(0.72f),
                ) {
                    val progress = downloadProgress
                    val fraction = progress?.let { (read, total) ->
                        if (total > 0L) read.toFloat() / total else null
                    }
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.sketchfab_loading_model, model.name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    // Show "X.X / Y.Y MB" when total is known, fallback label otherwise.
                    val progressText = progress?.let { (read, total) ->
                        if (total > 0L) {
                            val readMb = "%.1f".format(read / 1_000_000.0)
                            val totalMb = "%.1f".format(total / 1_000_000.0)
                            "$readMb / $totalMb MB"
                        } else {
                            stringResource(R.string.sketchfab_streaming_from)
                        }
                    } ?: stringResource(R.string.sketchfab_streaming_from)
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Advisory for very heavy models (≥ 500k polys).
                    if (model.faceCount >= 500_000) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.sketchfab_heavy_model_warning,
                                model.faceCount / 1_000,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ── Rendering (SceneView Compose) ─────────────────────────────────────────

/**
 * Live SceneView render of the downloaded GLB. Wow-factor polish (2026-05-11):
 *
 *  - **Premium studio HDR by default** — `studio_2k.hdr` instead of the SDK's
 *    neutral_ibl, with `createSkybox = false` so the background stays the
 *    sheet's clean surface and the IBL just flatters the PBR materials.
 *  - **Hero auto-rotate** — `rememberHeroOrbitCameraManipulator` cycles the
 *    camera around the model on a 20 s loop, so the model presents itself
 *    from every angle without the user touching the screen.
 *  - **Cinematic vignette** — radial dark gradient on the corners (multiply
 *    blend) for the "Apple Store" framing.
 */
@Composable
private fun RenderContent(
    file: File,
    model: SketchfabModel,
    engine: Engine,
    modelLoader: ModelLoader,
    environmentLoader: EnvironmentLoader,
    heroModifier: Modifier = Modifier,
) {
    // Engine + loaders are now hoisted to the sheet root and pre-warmed on
    // Preview → Downloading transition (see KDoc on `engineNeeded` in
    // [SketchfabModelViewerScreen]). By the time RenderContent enters the
    // composition, Filament is already initialised; this composable just
    // wires the (already-async) glTF parse into the live SceneView surface.
    // #2193 root-cause + follow-up review.

    // `rememberModelInstance` accepts asset paths or URIs; we pass a `file://`
    // URI so Filament reads from the local on-disk cache without re-decoding
    // through the asset pipeline.
    val instance = rememberModelInstance(
        modelLoader = modelLoader,
        fileLocation = "file://${file.absolutePath}",
    )

    // Material loader for the ground-shadow plane (#2235).
    val materialLoader = rememberMaterialLoader(engine)

    // Shadow-receiver material — `plane_renderer_shadow.filamat` is a Filament
    // material from arsceneview that writes only to the shadow buffer (no colour
    // output). It gives the model a soft ground shadow without a visible floor.
    val shadowMaterialInstance = remember(materialLoader) {
        materialLoader.createMaterial("materials/plane_renderer_shadow.filamat")
            .createInstance()
    }
    DisposableEffect(shadowMaterialInstance) {
        onDispose { materialLoader.destroyMaterialInstance(shadowMaterialInstance) }
    }

    // Premium studio HDR — much more flattering on PBR materials than the
    // neutral_ibl SDK default. `createSkybox = false` keeps the sheet's surface
    // background (no big white skybox behind the model).
    val environment: Environment = remember(environmentLoader) {
        environmentLoader.createHDREnvironment(
            assetFileLocation = "environments/studio_2k.hdr",
            createSkybox = false,
        ) ?: createEnvironment(environmentLoader)
    }
    DisposableEffect(environment) {
        onDispose { environmentLoader.destroyEnvironment(environment) }
    }

    // Live viewport aspect (width / height) of the 440 dp render Box, measured via
    // `onSizeChanged` below. Seeded to 0f ("unknown"); `computeAutoFitFraming` falls back
    // to a portrait-phone aspect until the first measured size arrives, then re-frames.
    var viewportAspect by remember(instance) { mutableStateOf(0f) }

    // Auto-fit camera framing (#2348). The previous `computeAutoFitRadius` scaled the
    // orbit to the model's *raw* glTF bounding box while the model was rendered with
    // `scaleToUnits = 1f` (normalised to a unit cube) — so the orbit radius and the
    // rendered size were computed in two different scales. A car authored in large glTF
    // units got a huge radius (tiny in a black void); a small-unit character got a tiny
    // radius (over-zoomed, legs only). This now mirrors the correctly-framed
    // `ModelViewerDemo`: render the model at its TRUE glTF size (no `scaleToUnits`),
    // recenter it on its bounding-box centre (glTF pivots are frequently off-origin —
    // the prime cause of "cut off at the legs"), and derive the orbit radius from the
    // library helper `fitDistanceForBounds` (bounding-sphere fit on BOTH axes via the
    // real viewport aspect + the stock 28 mm lens FOV), so tall and wide models both
    // frame to ~85 % of the viewport.
    val framing = remember(instance, viewportAspect) {
        instance?.let { computeAutoFitFraming(it, viewportAspect) }
    }

    // Orbit radius (fit distance) for the current model — falls back to 2.2 m while the
    // bounds are not yet measurable so the camera is never at radius 0.
    val autoFitRadius = framing?.radius ?: 2.2f

    // 20 s automatic orbit around the (recentred) model. `trigger = instance != null`
    // makes the orbit start the moment the GLB finishes loading. The default
    // `resumeAfterMillis = 3_000L` means the auto-orbit resumes 3 s after the user
    // releases a drag / pinch (#2225).
    val cameraManipulator = rememberHeroOrbitCameraManipulator(
        trigger = instance != null,
        radius = autoFitRadius,
        // Eye sits just above the look target for a gentle hero down-tilt (#2348).
        yHeight = (framing?.lookAtY ?: 0f) + autoFitRadius * 0.06f,
        // Aim the orbit pivot a little ABOVE the model's centre, not at the world origin
        // (#2348). The model is recentred so its bbox centre is at origin, but the 440 dp
        // render surface is the TOP portion of a taller camera viewport, so aiming dead at
        // origin left the model floating in the upper half. Raising the look-point to
        // `lookAtY` (a fraction of the model's half-height) drops the model to the visual
        // centre of the surface. Measured on Scifi Girl + Porsche.
        target = Position(0f, framing?.lookAtY ?: 0f, 0f),
        durationMillis = 20_000,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(520.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(440.dp)
                // Feed the live render-surface aspect into the auto-fit framing so a
                // tall model gets pushed back enough to clear the top/bottom and a wide
                // model fills the width — both on THIS viewport, not a guessed one (#2348).
                .onSizeChanged { size ->
                    if (size.height > 0) viewportAspect = size.width.toFloat() / size.height
                }
                .then(heroModifier),
        ) {
            // Defer SceneView mount until the GLB has finished loading
            // (issue #1201). Previously the SceneView was always composed and
            // a CircularProgressIndicator-over-opaque-surface placeholder was
            // layered ON TOP — but during the bottom-sheet expand transition
            // the opaque surface faded relative to the still-rendering
            // SceneView surface underneath, producing a brief "model inside a
            // circular crop / porthole" frame (the user could see the model
            // through the fading surface overlay, with the centered spinner
            // ring framing the visible area). Mounting the SceneView only
            // when `instance != null` means the underlying Surface never
            // exists during the transition and the placeholder owns the full
            // 440 dp box cleanly.
            if (instance != null) {
                SceneView(
                    modifier = Modifier.fillMaxSize(),
                    // TextureSurface so the live render respects the Compose
                    // layer alpha during the SharedTransitionLayout morph and
                    // the surrounding cinematic-vignette overlay. The default
                    // SurfaceView is a hardware overlay punched through the
                    // window and ignores Compose's fadeOut/fadeIn during the
                    // stage transition — the viewport would pop in opaque
                    // instead of morphing in. (Carried over from #1203.)
                    surfaceType = SurfaceType.TextureSurface,
                    engine = engine,
                    modelLoader = modelLoader,
                    environmentLoader = environmentLoader,
                    environment = environment,
                    cameraManipulator = cameraManipulator,
                ) {
                    // The model renders at its TRUE glTF size and is recentred so its
                    // bounding-box centre lands on the world origin the hero orbit pivots
                    // around (#2348). After recentring, the model's bottom sits at
                    // −halfExtent.y in true world units, and the shadow plane is sized to
                    // the model's footprint so the shadow reads at any model scale.
                    val he = remember(instance) { instance.model.boundingBox.halfExtent }
                    val groundY = remember(he) { -he[1] }
                    // Plane spanning ~3× the model's horizontal footprint, clamped so a
                    // near-degenerate bbox still gets a sane catcher.
                    val planeSize = remember(he) {
                        (maxOf(he[0], he[2]) * 6f).coerceAtLeast(1f)
                    }

                    // Directional light angled to cast a soft shadow under the model.
                    // Intensity 80 000 lx matches the studio_2k IBL contribution so
                    // the shadow is visible but not harsh.
                    LightNode(
                        type = LightManager.Type.DIRECTIONAL,
                        intensity = 80_000f,
                        direction = Direction(x = -0.5f, y = -1f, z = -0.4f),
                        apply = { castShadows(true) },
                    )

                    // Invisible shadow-receiver plane at the model's ground level.
                    // plane_renderer_shadow.filamat writes only to the shadow buffer —
                    // the plane itself is transparent, but the shadow cast by the
                    // model onto it is rendered (#2235).
                    //
                    // The quad MUST be an XZ (horizontal) plane — Size(x, y = 0, z) —
                    // not XY. plane_renderer_shadow's vertex shader FORCES `pos.y =
                    // 0.005` (see arsceneview/src/main/materials/plane_renderer_shadow.mat),
                    // so an XY quad (Size(x, y), z = 0) whose two triangles vary only in
                    // x and y collapses every vertex onto y = 0.005 → a zero-area line
                    // segment that catches no shadow. An XZ quad varies in x and z, so
                    // the forced-y is a harmless z-fight lift and the footprint survives.
                    // This matches ShadowReceiverPlaneNode.meshSize()'s XZ convention (#2581).
                    PlaneNode(
                        size = Size(x = planeSize, y = 0f, z = planeSize),
                        materialInstance = shadowMaterialInstance,
                        position = Position(x = 0f, y = groundY, z = 0f),
                    )

                    // No `scaleToUnits` — render at true glTF size; the orbit radius is
                    // auto-fit to that size instead (mirrors ModelViewerDemo). Recenter
                    // on the bbox centre so off-origin glTF pivots don't push the model
                    // off the top of the viewport.
                    ModelNode(
                        modelInstance = instance,
                        position = framing?.let { -it.center } ?: Position(0f, 0f, 0f),
                    )
                }
                // Cinematic vignette — costs ~0 GPU and lifts the model in
                // the centre without obscuring it. Only drawn once the
                // SceneView is mounted so it doesn't tint the loading
                // placeholder.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.0f),
                                    Color.Black.copy(alpha = 0.35f),
                                ),
                                radius = 800f,
                                center = Offset.Unspecified,
                            ),
                        ),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = model.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            text = stringResource(R.string.sketchfab_rendered_by, model.formattedFaceCount()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

// ── Error ─────────────────────────────────────────────────────────────────

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.sketchfab_could_not_open),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.sketchfab_try_again)) }
    }
}

// ── Camera framing ────────────────────────────────────────────────────────

/**
 * Auto-fit framing for a loaded model (#2348): the orbit [radius] (fit distance), the
 * bounding-box [center] to recenter the model onto the orbit pivot, and the camera
 * [lookAtY] (world-Y the orbit pivot aims at, above the recentred model's origin) that
 * vertically centres the model in the render surface.
 */
private data class ModelFraming(val radius: Float, val center: Position, val lookAtY: Float)

/**
 * Computes the auto-fit framing for [instance] using the library helper
 * [fitDistanceForBounds] — the SAME path `ModelViewerDemo` (which frames correctly) uses,
 * so the Sketchfab viewer and the bundled Models demo frame identically (#2348).
 *
 * Unlike the old `computeAutoFitRadius`, this:
 *  - fits the model's **bounding sphere** on **both** axes (so a tall model is pushed back
 *    far enough to clear the top and bottom, and a wide model fills the width), via the
 *    real viewport [aspect] (width / height) and the stock 28 mm lens FOV;
 *  - returns the bounding-box **centre** so the caller can translate the ModelNode onto the
 *    orbit pivot — off-origin glTF pivots were a prime cause of "cut off at the legs".
 *
 * @param aspect Live render-surface aspect (width / height). A non-positive value (the
 *   bounds-not-yet-measured frame) falls back to a portrait-phone `0.5`, matching
 *   `ModelViewerDemo`; the camera re-frames once the real aspect is measured.
 */
private fun computeAutoFitFraming(instance: ModelInstance, aspect: Float): ModelFraming? {
    val bounds = runCatching { instance.model.boundingBox.toAabb() }.getOrNull()
    if (bounds == null || bounds.isEmpty) return null
    val safeAspect = if (aspect.isFinite() && aspect > 0f) aspect.toDouble() else 0.5
    val radius = fitDistanceForBounds(
        bounds = bounds,
        verticalFovDegrees = verticalFovDegreesForFocalLength(28.0),
        aspect = safeAspect,
        // Slightly tighter than the SDK's 0.15 default — this hero viewer auto-orbits
        // 360°, so the bounding-SPHERE fit already guarantees the model never clips at any
        // yaw; 0.08 fills a bit more of the viewport while keeping that safety margin.
        padding = 0.08f,
    ).coerceIn(0.2f, 50f)

    // Vertical look-target (#2348). The model is recentred so its bbox centre is at the
    // world origin, but the 440 dp render surface is only the TOP slice of a taller camera
    // viewport — so aiming the orbit at origin floats the model in the upper half. Raising
    // the look-point to ~55 % of the model's half-height drops the model to the visual centre
    // of the surface (measured on Scifi Girl + Porsche).
    val he = instance.model.boundingBox.halfExtent
    val lookAtY = he[1] * 0.55f

    return ModelFraming(radius = radius, center = bounds.center, lookAtY = lookAtY)
}

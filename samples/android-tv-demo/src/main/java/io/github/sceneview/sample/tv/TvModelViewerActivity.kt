package io.github.sceneview.sample.tv

import android.os.Bundle
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.SceneView
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.math.Position
import io.github.sceneview.node.CameraNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.SceneviewTheme
import io.github.sceneview.sample.common.update.InAppUpdateManager
import io.github.sceneview.sample.common.update.UpdateBanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * One gallery entry. [credit] is shown under the title: every model below is CC BY 4.0 or CC0,
 * and CC BY asks for the author wherever the work is displayed. Source of truth for the credits:
 * `assets/CREDITS.md`.
 */
internal data class ModelEntry(
    val label: String,
    val assetPath: String,
    val credit: String,
    @param:DrawableRes val thumbnail: Int,
)

// Models bundled in src/main/assets/models (TV-only) or picked up from the phone demo's asset
// folder through `sourceSets.main.assets.srcDirs` — see build.gradle. `internal` so
// TvModelListTest can assert every assetPath resolves to a bundled file.
internal val models = listOf(
    ModelEntry("Damaged Helmet", "models/khronos_damaged_helmet.glb", "theblueturtle_ · CC BY 4.0", R.drawable.model_thumb_khronos_damaged_helmet),
    ModelEntry("Toy Car", "models/khronos_toy_car.glb", "Khronos Group · CC BY 4.0", R.drawable.model_thumb_khronos_toy_car),
    ModelEntry("Sheen Chair", "models/khronos_sheen_chair.glb", "Wayfair · CC0", R.drawable.model_thumb_khronos_sheen_chair),
    ModelEntry("Velvet Sofa", "models/khronos_glam_velvet_sofa.glb", "Wayfair · CC BY 4.0", R.drawable.model_thumb_khronos_glam_velvet_sofa),
    ModelEntry("Lantern", "models/khronos_lantern.glb", "Microsoft · CC BY 4.0", R.drawable.model_thumb_khronos_lantern),
    ModelEntry("Iridescent Dish", "models/khronos_iridescent_dish.glb", "Wayfair · CC BY 4.0", R.drawable.model_thumb_khronos_iridescent_dish),
    ModelEntry("Fox", "models/khronos_fox.glb", "PixelMannen, tomkranis · CC BY 4.0", R.drawable.model_thumb_khronos_fox),
    ModelEntry("Shiba", "models/shiba.glb", "zixisun51 · CC BY 4.0", R.drawable.model_thumb_shiba),
    ModelEntry("Soldier", "models/threejs_soldier.glb", "Tomás Laulhé · CC0", R.drawable.model_thumb_threejs_soldier),
    // No rendered thumbnail yet for this TV-only model: the SceneView cube stands in.
    ModelEntry("Air Jordan", "models/nike_air_jordan.glb", "Ar41k · CC BY 4.0", R.drawable.ic_launcher_foreground),
)

/**
 * Android TV / Google TV model viewer — SceneView on a 10-foot screen, driven by a D-pad.
 *
 * Two focus zones, one per job:
 * - **Gallery** (the row of cards at the bottom, focused at launch): ◀ ▶ picks a model — the
 *   stage follows the focused card — ▲ ▼ zooms, OK hands the D-pad to the stage.
 * - **Stage** (the 3D view, framed in `primary` while it holds focus): ◀ ▶ ▲ ▼ orbit the camera,
 *   OK or Back hand the D-pad back to the gallery.
 *
 * Play/Pause toggles the turntable, which runs only while the gallery has focus: once you take the
 * camera, it stays where you put it.
 */
class TvModelViewerActivity : ComponentActivity() {

    // Exposed (internal) so [TvModelViewerScreen] can overlay the [UpdateBanner]
    // on top of the SceneView surface. Same pattern as android-demo.
    internal lateinit var updateManager: InAppUpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        updateManager = InAppUpdateManager(this)
        // Register the activity-result launcher for Google's FLEXIBLE consent
        // modal BEFORE the activity reaches STARTED. Cancelling that modal is
        // delivered here (RESULT_CANCELED) — without it a cancel would strand
        // the in-app Update button as a permanent no-op (#1942 review).
        updateManager.registerForResult(this)

        setContent {
            // TV is dark-first and the whole screen is media: the chrome uses the
            // theme-independent over-media tokens, so the dark scheme is pinned.
            SceneviewTheme(darkTheme = true, dynamicColor = false) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = TvTokens.Accent.primary,
                        onPrimary = TvTokens.Accent.onPrimary,
                        background = TvTokens.Stage.background,
                        surface = TvTokens.Stage.background,
                    )
                ) {
                    TvModelViewerScreen(updateManager = updateManager)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-pick up a download already finished in a previous foreground
        // before issuing a fresh check — handles the "user backgrounded the
        // app mid-install" case without double-prompting.
        updateManager.checkForStalledUpdate()
        updateManager.checkForUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        updateManager.destroy()
    }
}

private enum class Zone { Gallery, Stage }

@OptIn(ExperimentalSceneViewApi::class)
@Composable
private fun TvModelViewerScreen(updateManager: InAppUpdateManager? = null) {
    var focusedIndex by rememberSaveable { mutableIntStateOf(0) }
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var zone by remember { mutableStateOf(Zone.Gallery) }
    var autoRotate by rememberSaveable { mutableStateOf(true) }
    val orbit = remember { OrbitController() }

    val cardFocus = remember { List(models.size) { FocusRequester() } }
    val stageFocus = remember { FocusRequester() }

    // The stage follows the focused card, after a short dwell: sweeping along the row must not
    // decode every model on the way.
    LaunchedEffect(focusedIndex) {
        if (focusedIndex != selectedIndex) {
            delay(SELECTION_DWELL_MS)
            selectedIndex = focusedIndex
            orbit.home()
        }
    }
    // Focus follows the zone. Requested from an effect so the stage's `canFocus` has already
    // flipped when the request lands.
    LaunchedEffect(zone) {
        runCatching {
            when (zone) {
                Zone.Stage -> stageFocus.requestFocus()
                Zone.Gallery -> cardFocus[selectedIndex].requestFocus()
            }
        }
    }
    BackHandler(enabled = zone == Zone.Stage) { zone = Zone.Gallery }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val cameraNode = rememberCameraNode(engine)

    val selected = models[selectedIndex]
    val modelInstance = rememberModelInstance(modelLoader, selected.assetPath)
    // `rememberModelInstance` keeps the previous model on screen until the next one is decoded,
    // so the stage never goes blank between two picks. This is the one actually shown.
    val shownIndex = remember(modelInstance) { if (modelInstance == null) -1 else selectedIndex }
    val loading = shownIndex != focusedIndex

    // Studio lighting without its backdrop: the stage stays `stage-background`. The neutral
    // default covers the HDR decode.
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    val studio = rememberHDREnvironment(environmentLoader, STUDIO_HDR, createSkybox = false)

    // Camera loop. Runs only while something moves — the turntable or a D-pad step still
    // easing in — and parks otherwise, so an idle TV draws nothing (FrameRatePolicy.OnDemand
    // presents a frame per camera write, and none once the writes stop).
    val spinning by rememberUpdatedState(autoRotate && zone == Zone.Gallery)
    LaunchedEffect(cameraNode) {
        // Lift the subject into the band above the gallery instead of the geometric centre.
        cameraNode.setShift(0.0, STAGE_SHIFT)
        orbit.applyTo(cameraNode)
        var last = 0L
        while (true) {
            if (!spinning && orbit.settled) {
                snapshotFlow { spinning || !orbit.settled }.first { it }
                last = 0L
            }
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(MAX_FRAME_STEP_S)
                last = now
                orbit.step(dt, spinning)
                orbit.applyTo(cameraNode)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvTokens.Stage.background)
            .onKeyEvent { event ->
                // Media keys work from either zone.
                val code = event.nativeKeyEvent.keyCode
                val isPlayPause = code == AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                    code == AndroidKeyEvent.KEYCODE_MEDIA_PLAY ||
                    code == AndroidKeyEvent.KEYCODE_MEDIA_PAUSE
                if (isPlayPause && event.type == KeyEventType.KeyUp) autoRotate = !autoRotate
                isPlayPause
            }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            environmentLoader = environmentLoader,
            environment = studio ?: fallbackEnvironment,
            cameraNode = cameraNode,
            // No touch on a TV: the D-pad drives the camera through [OrbitController].
            cameraManipulator = null,
            // Every model is normalised to one unit and centred on the origin below, so the
            // orbit radius frames all of them the same way.
            autoCenterContent = false,
        ) {
            modelInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = 1f,
                    centerOrigin = Position(0f, 0f, 0f),
                    autoAnimate = true,
                    animationLoop = true,
                )
            }
        }

        // The stage as a focus target. Focusable only while it is the active zone, so the
        // gallery's D-pad can never wander into it.
        var stageFocused by remember { mutableStateOf(false) }
        var centerArmed by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(stageFocus)
                .focusProperties { canFocus = zone == Zone.Stage }
                .onFocusChanged { stageFocused = it.isFocused }
                .focusable()
                .onKeyEvent { event -> handleStageKey(event, orbit, onArm = { centerArmed = it }, armed = centerArmed) { zone = Zone.Gallery } }
        )
        StageFocusFrame(visible = stageFocused)

        ChromeScrims()

        Identity(
            model = models[focusedIndex],
            index = focusedIndex,
            loading = loading,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = TvTokens.Overscan.horizontal, vertical = TvTokens.Overscan.vertical),
        )

        KeyHints(
            zone = zone,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = TvTokens.Overscan.horizontal, vertical = TvTokens.Overscan.vertical),
        )

        Gallery(
            focusedIndex = focusedIndex,
            selectedIndex = selectedIndex,
            dimmed = zone == Zone.Stage,
            cardFocus = cardFocus,
            onFocused = { focusedIndex = it },
            onOpen = { index ->
                focusedIndex = index
                if (selectedIndex != index) {
                    selectedIndex = index
                    orbit.home()
                }
                zone = Zone.Stage
            },
            onZoom = { orbit.zoomBy(it) },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = TvTokens.Overscan.vertical - TvTokens.Space.md),
        )

        // Play in-app update banner — overlays the top of the screen during
        // AVAILABLE / DOWNLOADING / READY_TO_INSTALL (no-op otherwise). On TV
        // the action CTA must grab D-pad focus the moment the banner appears,
        // so we hand UpdateBanner a FocusRequester it auto-requests on every
        // actionable transition (#1942 review). FLEXIBLE is the supported
        // Leanback flow.
        val actionFocusRequester = remember { FocusRequester() }
        updateManager?.let { mgr ->
            UpdateBanner(
                updateManager = mgr,
                modifier = Modifier.align(Alignment.TopCenter),
                actionFocusRequester = actionFocusRequester
            )
        }
    }
}

/**
 * The stage's D-pad: arrows orbit, OK hands focus back to the gallery.
 *
 * OK acts on key-up, and only after its own key-down: the press that *opened* the stage was
 * delivered to a gallery card, and its trailing key-up must not close it again.
 */
private fun handleStageKey(
    event: KeyEvent,
    orbit: OrbitController,
    onArm: (Boolean) -> Unit,
    armed: Boolean,
    onDone: () -> Unit,
): Boolean {
    val native = event.nativeKeyEvent
    val repeat = native.repeatCount > 0
    val yawStep = if (repeat) YAW_REPEAT_STEP else YAW_STEP
    val pitchStep = if (repeat) PITCH_REPEAT_STEP else PITCH_STEP
    val down = event.type == KeyEventType.KeyDown
    return when (native.keyCode) {
        // The model turns the way you press, as if you were swiping it.
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (down) orbit.orbitBy(dYaw = yawStep, dPitch = 0f); true }
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) orbit.orbitBy(dYaw = -yawStep, dPitch = 0f); true }
        // Up raises the camera to look down on the model.
        AndroidKeyEvent.KEYCODE_DPAD_UP -> { if (down) orbit.orbitBy(dYaw = 0f, dPitch = pitchStep); true }
        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { if (down) orbit.orbitBy(dYaw = 0f, dPitch = -pitchStep); true }
        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
            if (down) {
                if (!repeat) onArm(true)
            } else if (armed) {
                onArm(false)
                onDone()
            }
            true
        }
        else -> false
    }
}

/**
 * Spherical camera around the origin, eased toward its target every frame.
 *
 * Only the targets are snapshot state: a D-pad press writes a target, which is what wakes the
 * parked camera loop. The eased values are plain fields the loop owns.
 */
@Stable
private class OrbitController {
    var targetYaw by mutableFloatStateOf(0f)
        private set
    var targetPitch by mutableFloatStateOf(HOME_PITCH)
        private set
    var targetDistance by mutableFloatStateOf(HOME_DISTANCE)
        private set

    private var yaw = targetYaw
    private var pitch = targetPitch
    private var distance = targetDistance

    val settled: Boolean
        get() = abs(targetYaw - yaw) < SETTLE_DEGREES &&
            abs(targetPitch - pitch) < SETTLE_DEGREES &&
            abs(targetDistance - distance) < SETTLE_DISTANCE

    fun orbitBy(dYaw: Float, dPitch: Float) {
        targetYaw += dYaw
        targetPitch = (targetPitch + dPitch).coerceIn(MIN_PITCH, MAX_PITCH)
    }

    fun zoomBy(factor: Float) {
        targetDistance = (targetDistance * factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    /** Back to the home framing — pitch and distance only, so the turntable keeps its angle. */
    fun home() {
        targetPitch = HOME_PITCH
        targetDistance = HOME_DISTANCE
    }

    fun step(dt: Float, spinning: Boolean) {
        if (spinning) targetYaw += SPIN_DEGREES_PER_SECOND * dt
        val k = 1f - exp(-FOLLOW_RATE * dt)
        yaw += (targetYaw - yaw) * k
        pitch += (targetPitch - pitch) * k
        distance += (targetDistance - distance) * k
        if (!spinning && settled) {
            yaw = targetYaw
            pitch = targetPitch
            distance = targetDistance
        }
        // Keep the angles small so float precision never degrades on a long-running turntable.
        if (yaw > FULL_TURN && targetYaw > FULL_TURN) {
            yaw -= FULL_TURN
            targetYaw -= FULL_TURN
        } else if (yaw < -FULL_TURN && targetYaw < -FULL_TURN) {
            yaw += FULL_TURN
            targetYaw += FULL_TURN
        }
    }

    fun applyTo(camera: CameraNode) {
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())
        camera.position = Position(
            x = (distance * cos(pitchRad) * sin(yawRad)).toFloat(),
            y = (distance * sin(pitchRad)).toFloat(),
            z = (distance * cos(pitchRad) * cos(yawRad)).toFloat(),
        )
        camera.lookAt(Position(0f, 0f, 0f))
    }
}

/** A `primary` frame around the stage while it holds the D-pad — the stage's focus state. */
@Composable
private fun StageFocusFrame(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(TvTokens.fade),
        exit = fadeOut(TvTokens.fade),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(TvTokens.Space.md)
                .border(
                    BorderStroke(STAGE_FOCUS_STROKE, TvTokens.Accent.primary),
                    RoundedCornerShape(TvTokens.Radius.lg),
                )
        )
    }
}

/** `chrome-scrim` under the top and bottom bands, so white chrome reads on any model. */
@Composable
private fun ChromeScrims() {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(TvTokens.Glass.topBand)
                .background(Brush.verticalGradient(listOf(TvTokens.Glass.scrim, Color.Transparent)))
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(TvTokens.Glass.bottomBand)
                .background(Brush.verticalGradient(listOf(Color.Transparent, TvTokens.Glass.scrim)))
        )
    }
}

@Composable
private fun Identity(model: ModelEntry, index: Int, loading: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TvTokens.Space.xs)) {
        Text(
            text = "SceneView · ${index + 1} / ${models.size}",
            style = TvTokens.Type.caption,
            color = TvTokens.Glass.onGlassMuted,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = model.label, style = TvTokens.Type.display, color = TvTokens.Glass.onGlass)
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(start = TvTokens.Space.md)
                        .size(TvTokens.Space.lg),
                    color = TvTokens.Glass.onGlass,
                    trackColor = TvTokens.Glass.surface,
                    strokeWidth = 3.dp,
                )
            }
        }
        Text(text = model.credit, style = TvTokens.Type.caption, color = TvTokens.Glass.onGlassMuted)
    }
}

/** What the D-pad does right now, for the zone that holds it. */
@Composable
private fun KeyHints(zone: Zone, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(TvTokens.Space.sm)) {
        when (zone) {
            Zone.Gallery -> {
                KeyHint(keys = listOf(ArrowLeft, ArrowRight), label = "Model")
                KeyHint(keys = listOf(ArrowUp, ArrowDown), label = "Zoom")
                KeyHint(keys = emptyList(), okKey = true, label = "Orbit")
            }
            Zone.Stage -> {
                KeyHint(keys = listOf(ArrowLeft, ArrowRight, ArrowUp, ArrowDown), label = "Orbit", active = true)
                KeyHint(keys = emptyList(), okKey = true, label = "Done")
            }
        }
    }
}

@Composable
private fun KeyHint(keys: List<ImageVector>, label: String, okKey: Boolean = false, active: Boolean = false) {
    val shape = RoundedCornerShape(percent = 50)
    val content = if (active) TvTokens.Accent.onPrimary else TvTokens.Glass.onGlass
    Row(
        modifier = Modifier
            .height(HINT_HEIGHT)
            .background(if (active) TvTokens.Accent.primary else TvTokens.Glass.surface, shape)
            .border(1.dp, if (active) TvTokens.Accent.primary else TvTokens.Glass.edgeRing, shape)
            .padding(horizontal = HINT_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvTokens.Space.xs),
    ) {
        keys.forEach { Icon(imageVector = it, contentDescription = null, tint = content, modifier = Modifier.size(HINT_ICON)) }
        if (okKey) {
            Text(
                text = "OK",
                style = TvTokens.Type.caption,
                color = content,
                modifier = Modifier
                    .border(1.dp, content, RoundedCornerShape(TvTokens.Space.xs))
                    .padding(horizontal = TvTokens.Space.xs),
            )
        }
        Text(text = label, style = TvTokens.Type.caption, color = content, modifier = Modifier.padding(start = TvTokens.Space.xs))
    }
}

@Composable
private fun Gallery(
    focusedIndex: Int,
    selectedIndex: Int,
    dimmed: Boolean,
    cardFocus: List<FocusRequester>,
    onFocused: (Int) -> Unit,
    onOpen: (Int) -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(if (dimmed) DIMMED_ALPHA else 1f, TvTokens.fade, label = "galleryAlpha")
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha)
            // ▲ ▼ zoom while the gallery holds focus. Consumed here, before focus search, so they
            // never move focus out of the row.
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val repeat = native.repeatCount > 0
                val factor = when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> if (repeat) ZOOM_REPEAT_IN else ZOOM_IN
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 1f / (if (repeat) ZOOM_REPEAT_IN else ZOOM_IN)
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) onZoom(factor)
                true
            },
        // Vertical padding leaves room for the focused card's scale and glow.
        contentPadding = PaddingValues(horizontal = TvTokens.Overscan.horizontal, vertical = TvTokens.Space.md),
        horizontalArrangement = Arrangement.spacedBy(TvTokens.Space.md),
    ) {
        itemsIndexed(models) { index, model ->
            ModelCard(
                model = model,
                selected = index == selectedIndex,
                focused = index == focusedIndex,
                onClick = { onOpen(index) },
                modifier = Modifier
                    .focusRequester(cardFocus[index])
                    .onFocusChanged { if (it.isFocused) onFocused(index) },
            )
        }
    }
    // First focus goes to the first card, so the D-pad works from the first frame.
    LaunchedEffect(Unit) { runCatching { cardFocus[focusedIndex].requestFocus() } }
}

@Composable
private fun ModelCard(
    model: ModelEntry,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(TvTokens.Radius.md)
    Card(
        onClick = onClick,
        modifier = modifier.width(CARD_WIDTH),
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = TvTokens.Glass.surface,
            contentColor = TvTokens.Glass.onGlass,
            focusedContainerColor = TvTokens.Glass.surface,
            focusedContentColor = TvTokens.Glass.onGlass,
            pressedContainerColor = TvTokens.Glass.surface,
            pressedContentColor = TvTokens.Glass.onGlass,
        ),
        scale = CardDefaults.scale(focusedScale = FOCUSED_SCALE),
        border = CardDefaults.border(
            // Resting: the over-media hairline, or a `primary` ring for the model on stage.
            border = Border(
                border = if (selected) BorderStroke(SELECTED_STROKE, TvTokens.Accent.primary)
                else BorderStroke(1.dp, TvTokens.Glass.edgeRing),
                shape = shape,
            ),
            // Focused: a thick `primary` ring, the one thing on screen you can spot from the sofa.
            focusedBorder = Border(BorderStroke(FOCUS_STROKE, TvTokens.Accent.primary), shape = shape),
            pressedBorder = Border(BorderStroke(FOCUS_STROKE, TvTokens.Accent.primary), shape = shape),
        ),
        glow = CardDefaults.glow(focusedGlow = Glow(elevationColor = TvTokens.Accent.primary, elevation = FOCUS_GLOW)),
    ) {
        Image(
            painter = painterResource(model.thumbnail),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Text(
            text = model.label,
            style = TvTokens.Type.caption,
            color = if (focused) TvTokens.Glass.onGlass else TvTokens.Glass.onGlassMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = TvTokens.Space.sm, vertical = TvTokens.Space.sm),
        )
    }
}

private val ArrowLeft = Icons.AutoMirrored.Filled.KeyboardArrowLeft
private val ArrowRight = Icons.AutoMirrored.Filled.KeyboardArrowRight
private val ArrowUp = Icons.Filled.KeyboardArrowUp
private val ArrowDown = Icons.Filled.KeyboardArrowDown

private const val STUDIO_HDR = "environments/studio_2k.hdr"

private const val SELECTION_DWELL_MS = 300L

// Camera — every model is normalised to 1 unit, so these frame all of them.
private const val HOME_PITCH = 12f
private const val HOME_DISTANCE = 2.4f
private const val MIN_PITCH = -20f
private const val MAX_PITCH = 75f
private const val MIN_DISTANCE = 1.2f
private const val MAX_DISTANCE = 6f
private const val YAW_STEP = 20f
private const val YAW_REPEAT_STEP = 6f
private const val PITCH_STEP = 10f
private const val PITCH_REPEAT_STEP = 3f
private const val ZOOM_IN = 0.85f
private const val ZOOM_REPEAT_IN = 0.95f
private const val SPIN_DEGREES_PER_SECOND = 18f
private const val FOLLOW_RATE = 10f
private const val SETTLE_DEGREES = 0.05f
private const val SETTLE_DISTANCE = 0.001f
private const val FULL_TURN = 360f
private const val MAX_FRAME_STEP_S = 0.1f
private const val STAGE_SHIFT = 0.06

// 10-foot focus states
private val CARD_WIDTH = 112.dp
private const val FOCUSED_SCALE = 1.1f
private val FOCUS_STROKE = 3.dp
private val SELECTED_STROKE = 2.dp
private val FOCUS_GLOW = 12.dp
private val STAGE_FOCUS_STROKE = 4.dp
private const val DIMMED_ALPHA = 0.45f
private val HINT_HEIGHT = 36.dp
private val HINT_PADDING = 14.dp
private val HINT_ICON = 18.dp

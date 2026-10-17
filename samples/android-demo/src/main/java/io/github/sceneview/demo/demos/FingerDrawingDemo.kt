package io.github.sceneview.demo.demos

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.rounded._3dRotation
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager.PrimitiveType
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.LocalDemoSceneCover
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.rememberMaterialsShowcaseEnvironment
import io.github.sceneview.demo.common.rememberStageSkybox
import io.github.sceneview.demo.common.themedStageSky
import io.github.sceneview.demo.demoContentPadding
import io.github.sceneview.demo.demos.internal.Drawing
import io.github.sceneview.demo.demos.internal.FingerDrawing
import io.github.sceneview.demo.demos.internal.FingerDrawingPlacement
import io.github.sceneview.demo.demos.internal.SteadyPen
import io.github.sceneview.demo.demos.internal.Stroke
import io.github.sceneview.demo.demos.internal.TrayStage
import io.github.sceneview.demo.isDemoCompactHeight
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberView
import io.github.sceneview.safeDestroyGeometry
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.ui.LabeledSlider
import io.github.sceneview.utils.screenToRay
import kotlin.math.roundToInt

/**
 * Finger Drawing, 3D mode — paint in the air with one finger, then walk the camera around
 * what you made (#4082).
 *
 * The finger paints on a plane through the centre of the stage that faces the camera. Turn
 * the camera with **Orbit**, come back, and the next stroke lies on a new plane through the
 * same centre: three strokes from three angles are a sculpture, not a sketch.
 *
 * What makes a stroke look like paint rather than a polyline is in
 * [io.github.sceneview.demo.demos.internal.FingerDrawing], which is pure Kotlin and unit
 * tested: a spline through the touch samples, a twist-free tube around it, a thickness that
 * follows the finger's speed and round ends. This file only does the SceneView part, and it
 * is short on purpose:
 *
 * - `View.screenToRay` turns a touch into a ray, and a plane intersection into a point.
 * - [StrokeMesh] uploads one stroke with `Geometry.Builder` and draws it with `MeshNode`.
 * - `onTouchEvent` returns `true` while drawing, so the orbit camera never sees the finger,
 *   and `false` under **Orbit**, so it gets every gesture.
 *
 * The AR mode, [FingerDrawingArDemo], reuses [StrokeMesh], the materials and the controls.
 *
 * @param brush Colour and thickness, shared with the AR mode so a mode switch keeps them.
 */
@Composable
fun FingerDrawingDemo(onBack: () -> Unit, brush: FingerBrush = rememberFingerBrush()) {
    // Inspection mode (Android Studio @Preview pane, Roborazzi snapshot tests): bypass the
    // Filament-backed body BEFORE any rememberEngine() call — LayoutLib ships no .so files.
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = "Finger Drawing", onBack = onBack)
        return
    }

    var drawing by remember { mutableStateOf(Drawing()) }
    var orbiting by rememberSaveable { mutableStateOf(false) }
    // The orbit hint has done its job at the first drag; it comes back with the next Orbit.
    var orbitHintSeen by remember { mutableStateOf(false) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val view = rememberView(engine)
    val cameraNode = rememberCameraNode(engine)
    val renderInvalidator = rememberRenderInvalidator()
    val firstFrame = rememberFirstFrameState(engine)
    val paints = rememberPaintMaterials(materialLoader)

    // A flat stage in the theme's ground colour — paper in light, the dark stage in dark —
    // lit by the photo-studio IBL without its photograph: the paint is glossy, and a gloss
    // with nothing to reflect is flat colour.
    val sky = themedStageSky()
    val stageSkybox = rememberStageSkybox(engine, sky, renderInvalidator::requestRender)
    val studioLight = rememberMaterialsShowcaseEnvironment(environmentLoader, firstFrame)
    val environment = remember(studioLight, stageSkybox) { studioLight.copy(skybox = stageSkybox) }

    val density = LocalDensity.current.density
    val pen = remember { Pen() }

    // Where a touch lands on the plane the current stroke is painted on.
    fun planePoint(x: Float, y: Float): Position? {
        val ray = view.screenToRay(x, y) ?: return null
        return FingerDrawingPlacement.planeHit(ray.origin, ray.direction, pen.planePoint, pen.planeNormal)
    }

    // One touch sample: the finger's speed sets the thickness, and the paint follows the
    // finger through SteadyPen rather than sitting under it — see why there.
    fun extend(from: Drawing, x: Float, y: Float, timeMillis: Long): Drawing {
        val point = planePoint(x, y) ?: return from
        val steady = pen.steady ?: return from
        val dtMillis = timeMillis - pen.lastTimeMillis
        val dtSeconds = dtMillis / MILLIS_PER_SECOND
        pen.speed = FingerDrawing.smoothedSpeed(
            previous = pen.speed,
            measured = FingerDrawing.speedDpPerSecond(x - pen.lastX, y - pen.lastY, dtMillis, density),
            dtSeconds = dtSeconds,
        )
        pen.lastX = x
        pen.lastY = y
        pen.lastTimeMillis = timeMillis
        val followed = steady.follow(point, dtSeconds)
        pen.steady = followed
        return from.penMove(
            point = followed.paint,
            targetRadius = FingerDrawing.radiusFor(brush.thicknessDp, pen.unitsPerDp, pen.speed),
        )
    }

    fun penTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The plane and the scale are fixed for the whole stroke: a stroke is flat,
                // and as thick on screen as the slider says wherever the camera stands.
                val forward = cameraNode.forwardDirection
                pen.planeNormal = forward
                pen.planePoint = FingerDrawingPlacement.paintPlanePoint(
                    cameraPosition = cameraNode.worldPosition,
                    forward = forward,
                    pivot = STAGE_CENTER,
                    minDepth = MIN_PAINT_DEPTH,
                    fallbackDepth = CAMERA_DISTANCE,
                )
                val point = planePoint(event.x, event.y)
                val aside = planePoint(event.x + SCALE_PROBE_DP * density, event.y)
                pen.down = point != null && aside != null
                if (point != null && aside != null) {
                    pen.unitsPerDp = FingerDrawingPlacement.unitsPerDp(point, aside, SCALE_PROBE_DP)
                    pen.lastX = event.x
                    pen.lastY = event.y
                    pen.lastTimeMillis = event.eventTime
                    pen.speed = 0f
                    pen.steady = SteadyPen.at(point)
                    drawing = drawing.penDown(
                        point = point,
                        radius = FingerDrawing.radiusFor(brush.thicknessDp, pen.unitsPerDp, 0f),
                        colorIndex = brush.colorIndex,
                        frameHint = forward,
                        spacing = FingerDrawing.RING_SPACING_DP * pen.unitsPerDp,
                    )
                }
            }

            MotionEvent.ACTION_MOVE -> if (pen.down) {
                // Every sample the system batched into this event, not only the last: at
                // 120 Hz touch and 60 Hz frames, half the finger's path is in the history.
                var next = drawing
                for (i in 0 until event.historySize) {
                    next = extend(
                        from = next,
                        x = event.getHistoricalX(i),
                        y = event.getHistoricalY(i),
                        timeMillis = event.getHistoricalEventTime(i),
                    )
                }
                drawing = extend(next, event.x, event.y, event.eventTime)
            }

            // The lift is a sample too: a finger that rested before leaving has given the
            // paint the time to reach it, and the stroke ends where the finger did.
            MotionEvent.ACTION_UP -> {
                if (pen.down) drawing = extend(drawing, event.x, event.y, event.eventTime)
                pen.down = false
                drawing = drawing.penUp()
            }

            // A second finger ends the stroke where it is rather than drawing a line to it.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                pen.down = false
                drawing = drawing.penUp()
            }
        }
        return true
    }

    // Undo and Clear. There is nothing to look around on an empty stage, so the tap that
    // empties it hands the finger back to the pen: Orbit is for a drawing.
    fun erase(remaining: Drawing) {
        drawing = remaining
        if (remaining.isEmpty) orbiting = false
    }

    val strokeCount = drawing.strokeCount
    val compactHeight = isDemoCompactHeight()

    DemoScaffold(
        title = stringResource(R.string.demo_finger_drawing_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        sceneReady = firstFrame.sceneReady,
        contentIssue = firstFrame.contentIssue,
        themedStage = true,
        // The pill is there from the first frame — a hint while the stage is empty, the count
        // after — and that is deliberate: a pill that appeared with the first stroke would
        // grow the chrome, the stage would re-centre above it (see `contentPadding` below),
        // and the paint would slide out from under the finger in the middle of that stroke.
        peekHeader = when {
            compactHeight -> null
            orbiting && !orbitHintSeen -> stringResource(R.string.demo_finger_drawing_orbit_hint)
            strokeCount == 0 -> stringResource(R.string.demo_finger_drawing_draw_hint)
            else -> pluralStringResource(R.plurals.demo_finger_drawing_strokes, strokeCount, strokeCount)
        },
        onResetSettings = brush::reset,
        dock = listOf(
            DockItem(
                icon = Icons.Rounded._3dRotation,
                label = stringResource(R.string.demo_finger_drawing_orbit),
                onClick = {
                    orbiting = !orbiting
                    orbitHintSeen = false
                    pen.down = false
                    drawing = drawing.penUp()
                },
                selected = orbiting,
                enabled = !drawing.isEmpty,
            ),
            DockItem(
                icon = Icons.AutoMirrored.Outlined.Undo,
                label = stringResource(R.string.demo_finger_drawing_undo),
                onClick = { erase(drawing.undo()) },
                enabled = !drawing.isEmpty,
            ),
            DockItem(
                icon = Icons.Rounded.DeleteSweep,
                label = stringResource(R.string.demo_finger_drawing_clear),
                onClick = { erase(drawing.clear()) },
                enabled = !drawing.isEmpty,
            ),
        ),
        controls = {
            FingerDrawingControls(
                explainer = stringResource(R.string.demo_finger_drawing_explainer),
                colorIndex = brush.colorIndex,
                onColorChange = { brush.colorIndex = it },
                thicknessDp = brush.thicknessDp,
                onThicknessChange = { brush.thicknessDp = it },
                canUndo = !drawing.isEmpty,
                onUndo = { erase(drawing.undo()) },
                onClear = { erase(drawing.clear()) },
            )
        },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The stage runs edge to edge under the glass. What the chrome and the settings
            // sheet cover is handed to the SDK as `contentPadding`: the camera treats the
            // band they leave free as its viewport, so the drawing stays whole above the
            // sheet while the colour is being chosen — and a touch still lands under the
            // finger, because `screenToRay` follows the same padding.
            val safe = WindowInsets.safeDrawing.asPaddingValues()
            val layoutDirection = LocalLayoutDirection.current
            val cover = demoContentPadding(
                cover = LocalDemoSceneCover.current,
                sceneHeight = maxHeight,
                left = safe.calculateLeftPadding(layoutDirection),
                right = safe.calculateRightPadding(layoutDirection),
                statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                compactHeight = compactHeight,
            )
            SceneView(
                modifier = Modifier.fillMaxSize(),
                contentPadding = cover,
                engine = engine,
                view = view,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = environment,
                cameraNode = cameraNode,
                // The drawing is authored around the stage centre; re-centring the scene on
                // its bounds would slide every stroke at each new one.
                autoCenterContent = false,
                cameraManipulator = rememberCameraManipulator(
                    orbitHomePosition = Position(0f, 0f, CAMERA_DISTANCE),
                    targetPosition = STAGE_CENTER,
                ),
                renderInvalidator = renderInvalidator,
                onFrame = firstFrame.onFrame,
                onTouchEvent = { event, _ ->
                    if (orbiting) {
                        if (event.actionMasked == MotionEvent.ACTION_MOVE) orbitHintSeen = true
                        false
                    } else {
                        penTouch(event)
                    }
                },
            ) {
                PaintLights()
                DrawingMeshes(drawing, paints, renderInvalidator::requestRender)
                // The scene renders on demand: a stroke that was undone or wiped has no
                // mesh left to ask for the frame that shows it gone.
                SideEffect { renderInvalidator.requestRender() }
            }
        }
    }
}

/** What a stroke needs to remember between two touch events. Not Compose state: nothing reads it to draw. */
private class Pen {
    var down = false
    var planePoint = Position()
    var planeNormal = Direction(z = -1f)
    var unitsPerDp = 0f
    var lastX = 0f
    var lastY = 0f
    var lastTimeMillis = 0L
    var speed = 0f
    var steady: SteadyPen? = null
}

/**
 * The brush of both Finger Drawing modes: the colour and the thickness the next stroke is
 * painted with. It is held above the 3D | AR switch (`FingerDrawingFragment`), so a colour
 * picked in one mode is still the one in hand in the other.
 */
@Stable
class FingerBrush internal constructor(colorIndex: Int, thicknessDp: Float) {
    /** Index of the colour in the palette. */
    var colorIndex by mutableIntStateOf(colorIndex)

    /** Stroke width at rest, in dp of screen. */
    var thicknessDp by mutableFloatStateOf(thicknessDp)

    /** Back to the first colour and the default thickness. */
    fun reset() {
        colorIndex = 0
        thicknessDp = FingerDrawing.DEFAULT_THICKNESS_DP
    }

    internal companion object {
        val Saver: Saver<FingerBrush, Any> = listSaver(
            save = { listOf(it.colorIndex.toFloat(), it.thicknessDp) },
            restore = { FingerBrush(colorIndex = it[0].toInt(), thicknessDp = it[1]) },
        )
    }
}

/** A [FingerBrush] that survives a rotation; call it above whatever swaps the two modes. */
@Composable
fun rememberFingerBrush(): FingerBrush =
    rememberSaveable(saver = FingerBrush.Saver) {
        FingerBrush(colorIndex = 0, thicknessDp = FingerDrawing.DEFAULT_THICKNESS_DP)
    }

/**
 * The paint: one glossy, non-metallic material per palette colour — wet enamel. The gloss is
 * what gives a tube its volume: the studio highlight runs along it and breaks where it turns.
 */
@Composable
internal fun rememberPaintMaterials(materialLoader: MaterialLoader): List<MaterialInstance> =
    PAINT_COLORS.map { color ->
        val paint = rememberMaterialInstance(
            materialLoader = materialLoader,
            color = color,
            metallic = 0f,
            roughness = PAINT_ROUGHNESS,
            reflectance = PAINT_REFLECTANCE,
        )
        // A stroke that crosses itself tightly can show the inside of its own tube for a
        // ring or two; double-sided, that inside is lit like the outside instead of being a
        // hole. Per-instance override on the ubershader: no .filamat recompile.
        DisposableEffect(paint) {
            paint.setDoubleSided(true)
            onDispose { }
        }
        paint
    }

/**
 * Two accents on top of the scene's default main and fill lights: a warm key from the upper
 * front-left, and a cool rim from behind that draws a bright edge down the far side of every
 * stroke — what separates two strokes that cross from one flat shape.
 */
@Composable
internal fun SceneScope.PaintLights() {
    LightNode(
        type = LightManager.Type.DIRECTIONAL,
        apply = {
            color(1f, 0.95f, 0.88f)
            intensity(KEY_LIGHT_LUX)
            direction(0.35f, -0.8f, -0.5f)
            castShadows(false)
        },
    )
    LightNode(
        type = LightManager.Type.DIRECTIONAL,
        apply = {
            color(0.62f, 0.68f, 1f)
            intensity(RIM_LIGHT_LUX)
            direction(-0.45f, 0.35f, 0.8f)
            castShadows(false)
        },
    )
}

/** Every stroke of [drawing], the one under the finger last. */
@Composable
internal fun SceneScope.DrawingMeshes(
    drawing: Drawing,
    paints: List<MaterialInstance>,
    requestRender: () -> Unit,
) {
    drawing.strokes.forEach { stroke ->
        key(stroke.id) { StrokeMesh(stroke, paints[stroke.colorIndex], requestRender) }
    }
    drawing.active?.let { stroke ->
        key(stroke.id) { StrokeMesh(stroke, paints[stroke.colorIndex], requestRender) }
    }
}

/**
 * Draws one stroke — and keeps drawing it while it grows under the finger.
 *
 * The lifecycle is `CustomGeometryDemo`'s `ProceduralKnot`, with one addition:
 *
 * - `line` and `vertices` are `remember`ed on the **stroke**, a value type, so nothing is
 *   regenerated for a stroke that did not change — finishing the tenth stroke does not
 *   rebuild the first nine.
 * - `geometry` is `remember`ed on the **capacity**, not on the ring count. A growing stroke
 *   would otherwise need new GPU buffers at every finger move; padded to a capacity that
 *   grows in a few steps, it keeps its vertex count, and `Geometry.update` rewrites the
 *   buffers it already has.
 * - `DisposableEffect` frees the buffers when the capacity steps up or the stroke is undone.
 *   `MeshNode` does not own buffers it was handed.
 *
 * [requestRender] is for an on-demand scene, which has to be told that the buffers changed;
 * an AR scene renders every camera frame and leaves it out.
 *
 * `boundingBox` is left out: the box of a mesh that grows would be stale at the next move,
 * and a stroke culled by its first centimetre is a stroke that vanishes while it is drawn.
 */
@Composable
internal fun SceneScope.StrokeMesh(
    stroke: Stroke,
    materialInstance: MaterialInstance,
    requestRender: () -> Unit = {},
) {
    val line = remember(stroke) { FingerDrawing.line(stroke) }
    val capacity = FingerDrawing.capacityFor(line.size)
    val vertices = remember(line, capacity) { FingerDrawing.vertices(line, stroke.frameHint, capacity) }
    val geometry = remember(engine, capacity) {
        Geometry.Builder(PrimitiveType.TRIANGLES)
            .vertices(vertices)
            .indices(FingerDrawing.triangleIndices(capacity))
            .build(engine)
    }
    SideEffect {
        geometry.update(engine, vertices)
        requestRender()
    }
    DisposableEffect(geometry) {
        onDispose { engine.safeDestroyGeometry(geometry) }
    }

    MeshNode(
        primitiveType = PrimitiveType.TRIANGLES,
        vertexBuffer = geometry.vertexBuffer,
        indexBuffer = geometry.indexBuffer,
        materialInstance = materialInstance,
    )
}

/**
 * Settings sheet of both Finger Drawing modes — stateless, so a Roborazzi snapshot test can
 * capture it in pure JVM with no Filament Engine.
 */
@Composable
internal fun FingerDrawingControls(
    explainer: String,
    colorIndex: Int,
    onColorChange: (Int) -> Unit,
    thicknessDp: Float,
    onThicknessChange: (Float) -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onClear: () -> Unit,
) {
    Text(
        text = explainer,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))

    Text(
        text = stringResource(R.string.demo_finger_drawing_colour),
        style = MaterialTheme.typography.labelLarge,
    )
    Row(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
    ) {
        PAINT_COLORS.forEachIndexed { index, color ->
            PaintSwatch(
                color = color,
                name = stringResource(PAINT_COLOR_NAMES[index]),
                selected = index == colorIndex,
                onClick = { onColorChange(index) },
            )
        }
    }
    Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))

    LabeledSlider(
        label = stringResource(R.string.demo_finger_drawing_thickness),
        value = thicknessDp,
        onValueChange = onThicknessChange,
        valueRange = FingerDrawing.MIN_THICKNESS_DP..FingerDrawing.MAX_THICKNESS_DP,
        valueText = stringResource(R.string.demo_finger_drawing_thickness_value, thicknessDp.roundToInt()),
    )
    Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
    ) {
        OutlinedButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.demo_finger_drawing_undo))
        }
        OutlinedButton(onClick = onClear, enabled = canUndo, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.demo_finger_drawing_clear_all))
        }
    }
}

/** One colour of the palette: a dot, ringed when it is the one the finger paints with. */
@Composable
private fun PaintSwatch(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(SWATCH_TOUCH_TARGET)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(SWATCH_RING)
                .then(
                    if (selected) {
                        Modifier.border(SWATCH_RING_WIDTH, MaterialTheme.colorScheme.onSurface, CircleShape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(SWATCH_DOT)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/**
 * The paint palette: DESIGN.md's `stage-tray-rubber-1…5` — the five colours already proven
 * to hold on a light and on a dark stage. Coral first: it is the one a first stroke is in.
 */
internal val PAINT_COLORS: List<Color> = TrayStage.RUBBER_COLORS

private val PAINT_COLOR_NAMES = listOf(
    R.string.demo_finger_drawing_colour_coral,
    R.string.demo_finger_drawing_colour_azure,
    R.string.demo_finger_drawing_colour_amber,
    R.string.demo_finger_drawing_colour_mint,
    R.string.demo_finger_drawing_colour_orchid,
)

private const val PAINT_ROUGHNESS = 0.28f
private const val PAINT_REFLECTANCE = 0.6f
private const val KEY_LIGHT_LUX = 5_000f
private const val RIM_LIGHT_LUX = 3_500f

/** The point the camera orbits, and every painting plane goes through. */
private val STAGE_CENTER = Position(0f, 0f, 0f)

/** Orbit radius at rest, in scene units. */
private const val CAMERA_DISTANCE = 4f

/** Closer than this to the camera, the stage centre no longer carries the painting plane. */
private const val MIN_PAINT_DEPTH = 0.5f

/** Screen distance between the two rays that measure one dp on the painting plane. */
private const val SCALE_PROBE_DP = 100f

private const val MILLIS_PER_SECOND = 1_000f

// 48 dp touch target, 40 dp ring, 28 dp dot — all from the spacing scale.
private val SWATCH_TOUCH_TARGET = SceneViewTokens.Space.xl + SceneViewTokens.Space.md
private val SWATCH_RING = SceneViewTokens.Space.xl + SceneViewTokens.Space.sm
private val SWATCH_DOT = SceneViewTokens.Space.lg + SceneViewTokens.Space.xs
private val SWATCH_RING_WIDTH = SceneViewTokens.Glass.borderWidth * 2

// ── Android Studio @Preview support ────────────────────────────────────────────

@Preview(name = "Demo (light)", showBackground = true)
@Composable
private fun FingerDrawingDemoPreview_Light() {
    SceneViewDemoTheme(darkTheme = false) {
        FingerDrawingDemo(onBack = {})
    }
}

@Preview(
    name = "Demo (dark)",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun FingerDrawingDemoPreview_Dark() {
    SceneViewDemoTheme(darkTheme = true) {
        FingerDrawingDemo(onBack = {})
    }
}

@Preview(name = "Controls only", showBackground = true)
@Composable
private fun FingerDrawingControlsPreview() {
    SceneViewDemoTheme(darkTheme = false) {
        Column(modifier = Modifier.padding(SceneViewTokens.Space.md)) {
            FingerDrawingControls(
                explainer = "Every stroke is a tube generated in Kotlin.",
                colorIndex = 0,
                onColorChange = {},
                thicknessDp = FingerDrawing.DEFAULT_THICKNESS_DP,
                onThicknessChange = {},
                canUndo = true,
                onUndo = {},
                onClear = {},
            )
        }
    }
}

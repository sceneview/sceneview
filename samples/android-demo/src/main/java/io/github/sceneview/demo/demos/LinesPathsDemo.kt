package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.demos.internal.CurveKind
import io.github.sceneview.demo.demos.internal.LinesPathsScene
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberHeroOrbitCameraManipulator
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.LifecycleAwareLaunchedEffect
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.ui.LabeledSlider
import kotlin.math.roundToInt

/** One full lap of the marker around the route, in nanoseconds. */
private const val LAP_DURATION_NANOS = 9_000_000_000.0

/**
 * **Lines, paths, splines and point sets** — the reference for drawing strokes in 3D.
 *
 * ### The thing this demo exists to teach
 *
 * `LineNode` and `PathNode` draw with Filament's `PrimitiveType.LINES`. Every mobile GL and
 * Vulkan backend rasterises that at exactly **one device pixel**, and none of them honour a
 * width request. On a 420 dpi phone that is ~0.4 dp: the previous version of this screen drew
 * its line and its polyline correctly and you could not see either of them
 * ([#3397](https://github.com/sceneview/sceneview/issues/3397),
 * [#3425](https://github.com/sceneview/sceneview/issues/3425)) — the committed render golden
 * was a black viewport with a chain of flat unlit discs across it.
 *
 * A stroke you can see has to be **geometry**. `TubeNode` sweeps a circular cross-section along
 * a polyline with rotation-minimising frames, so a line has a radius in metres, catches the
 * scene's light, occludes correctly and anti-aliases like anything else on screen. That is what
 * every stroke here is built from, and it is the recipe to copy:
 *
 * ```kotlin
 * SceneView(engine = engine, materialLoader = materialLoader) {
 *     TubeNode(
 *         points = catmullRomSpline(controlPoints, segments = 24),
 *         radius = 0.008f,          // metres — a 16 mm stroke
 *         closed = true,
 *         materialInstance = rememberMaterialInstance(materialLoader, color),
 *     )
 * }
 * ```
 *
 * ### What is on screen
 *
 * One point set, drawn four ways, so the relationship between them is visible at a glance:
 *
 * - **Route** — a closed path through eight control points, swept as a tube. The *Curve* chips
 *   rebuild it as a raw **polyline**, a polyline with **rounded** corners (quadratic Bezier
 *   fillets) or an interpolating **spline** (centripetal Catmull-Rom).
 * - **Marker and trail** — a sphere travelling the route at constant speed with a brighter tube
 *   behind it, both sampled by **arc length** so they move evenly whichever curve is selected.
 * - **Ground track** — the same eight points flattened to a plane and drawn as a **dashed**
 *   polyline that marches while the animation runs.
 * - **Control points** — the point set itself, as lit spheres rather than raw GL points.
 *
 * Curves, arc-length sampling and framing live in [LinesPathsScene] as pure functions, covered
 * by `LinesPathsSceneTest`.
 *
 * ### Why the buffers never reallocate
 *
 * Every route is [LinesPathsScene.ROUTE_SAMPLES] points long whatever the curve kind, every
 * dash is [LinesPathsScene.DASH_SAMPLES] and the trail is [LinesPathsScene.TRAIL_SAMPLES]. Point
 * counts are therefore constant for the life of the screen, so changing the curve, dragging the
 * stroke slider or advancing the animation re-uploads vertices into the Filament buffers the
 * tubes already own — no reallocation, nothing to free, and the 18 tubes on screen can be
 * rewritten every frame.
 */
@Composable
fun LinesPathsDemo(onBack: () -> Unit) {
    // Inspection mode (Android Studio @Preview pane, Roborazzi snapshot tests): bypass the
    // Filament-backed body BEFORE rememberEngine(), which needs .so files LayoutLib lacks.
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = "Lines & Paths", onBack = onBack)
        return
    }

    var curve by remember { mutableStateOf(CurveKind.Smooth) }
    var strokeMillimetres by remember { mutableFloatStateOf(LinesPathsScene.DEFAULT_STROKE_MM) }
    var showPoints by remember { mutableStateOf(true) }
    var animating by remember { mutableStateOf(true) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    // IBL: the strokes are lit geometry, so without an environment they read as flat silhouettes
    // — exactly the look the unlit beads this demo replaces had.
    rememberEnvironmentLoader(engine)

    // On-brand hierarchy against the always-dark stage (`SceneViewTokens.Stage.background`):
    // the route is the brightest ramp colour, the moving marker the second brightest so it
    // reads against the route it sits on, the control points mid purple, and the ground track
    // the deepest blue so it stays a backdrop rather than competing with the route.
    val routeMaterial =
        rememberMaterialInstance(materialLoader, SceneViewColors.TintLight, metallic = 0f, roughness = 0.35f)
    val markerMaterial =
        rememberMaterialInstance(materialLoader, SceneViewColors.TintSoft, metallic = 0f, roughness = 0.25f)
    val pointMaterial =
        rememberMaterialInstance(materialLoader, SceneViewColors.Accent, metallic = 0.1f, roughness = 0.4f)
    val groundMaterial =
        rememberMaterialInstance(materialLoader, SceneViewColors.Primary, metallic = 0f, roughness = 0.5f)

    // Lap progress in [0, 1). Driven off the Choreographer rather than an InfiniteTransition so
    // it pauses with the lifecycle (#936) and freezes at a fixed phase in QA mode, which is what
    // makes the render golden deterministic.
    var progress by remember { mutableFloatStateOf(LinesPathsScene.STATIC_PROGRESS) }
    LifecycleAwareLaunchedEffect(animating, DemoSettings.qaMode) {
        if (!animating || DemoSettings.qaMode) {
            progress = LinesPathsScene.STATIC_PROGRESS
            return@LifecycleAwareLaunchedEffect
        }
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    progress = ((progress + (nanos - lastNanos) / LAP_DURATION_NANOS) % 1.0).toFloat()
                }
                lastNanos = nanos
            }
        }
    }

    val route = remember(curve) { LinesPathsScene.route(curve) }
    val routeLength = remember(route) { LinesPathsScene.totalLength(route, closed = true) }
    val strokeRadius = LinesPathsScene.strokeRadius(strokeMillimetres)

    // Marker position and the trail behind it, both by arc length so the speed is constant.
    val markerDistance = progress * routeLength
    val markerPosition = LinesPathsScene.sampleAt(route, closed = true, distance = markerDistance)
    val trailLength = routeLength * LinesPathsScene.TRAIL_FRACTION
    val trail = LinesPathsScene.span(
        points = route,
        closed = true,
        startDistance = markerDistance - trailLength,
        length = trailLength,
        samples = LinesPathsScene.TRAIL_SAMPLES,
    )
    val dashes = LinesPathsScene.dashes(
        points = LinesPathsScene.groundTrack,
        closed = true,
        count = LinesPathsScene.DASH_COUNT,
        dutyCycle = LinesPathsScene.DASH_DUTY_CYCLE,
        phase = progress,
        samples = LinesPathsScene.DASH_SAMPLES,
    )

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_lines_paths_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        // A named cover, because this screen's old one was a bare spinner over black that
        // crossfaded into an equally black scene: loading and loaded were indistinguishable
        // (#3397). The scene itself is now the fix; the label just says what is coming.
        loadingLabel = stringResource(R.string.demo_lines_paths_loading),
        peekHeader = stringResource(
            R.string.demo_lines_paths_status,
            curve.label,
            route.size,
            strokeMillimetres.roundToInt(),
        ),
        onResetSettings = {
            curve = CurveKind.Smooth
            strokeMillimetres = LinesPathsScene.DEFAULT_STROKE_MM
            showPoints = true
            animating = true
        },
        dock = listOf(
            DockItem(
                icon = Icons.Filled.ScatterPlot,
                label = "Points",
                onClick = { showPoints = !showPoints },
                selected = showPoints,
            ),
            DockItem(
                icon = if (animating) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                label = "Animate",
                onClick = { animating = !animating },
                selected = animating,
            ),
        ),
        controls = {
            Text("Curve", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CurveKind.entries.forEach { kind ->
                    FilterChip(
                        selected = curve == kind,
                        onClick = { curve = kind },
                        label = { Text(kind.label) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LabeledSlider(
                label = "Stroke",
                value = strokeMillimetres,
                onValueChange = { strokeMillimetres = it },
                valueRange = LinesPathsScene.MIN_STROKE_MM..LinesPathsScene.MAX_STROKE_MM,
                decimals = 0,
                unit = "mm",
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Toggleable on the whole row so tapping the label flips the state and UiAutomator
            // finds a clickable ancestor — same contract as the Fog mode's switches in LightingLabDemo.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = showPoints, onValueChange = { showPoints = it }),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Control Points", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = showPoints, onCheckedChange = null)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = animating, onValueChange = { animating = it }),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Animate", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = animating, onCheckedChange = null)
            }
        },
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = firstFrame.onFrame,
            engine = engine,
            materialLoader = materialLoader,
            // The dashes move every frame, so a bounding box that changes 60 times a second
            // would have the library re-centring the scene continuously and the whole
            // composition would breathe. LinesPathsScene.SCENE_LIFT centres it instead, once.
            autoCenterContent = false,
            // Keyed on `animating` so the Animate control stops the camera as well as the
            // marker: a toggle labelled "Animate" that leaves the scene turning is a lie, and a
            // still frame is what someone reaches for the pause for.
            cameraManipulator = rememberHeroOrbitCameraManipulator(
                trigger = animating,
                radius = LinesPathsScene.orbitRadius(),
                yHeight = LinesPathsScene.orbitHeight(),
            ),
        ) {
            Node(position = Position(y = LinesPathsScene.SCENE_LIFT)) {
                // The ground track: a straight-segment polyline, drawn as marching dashes. Each
                // dash is its own short tube — capped, or the ends would read as hollow pipes.
                dashes.forEachIndexed { index, dash ->
                    key(index) {
                        TubeNode(
                            points = dash,
                            radius = strokeRadius * LinesPathsScene.GROUND_STROKE_RATIO,
                            radialSegments = LinesPathsScene.TUBE_SEGMENTS,
                            caps = true,
                            materialInstance = groundMaterial,
                        )
                    }
                }

                // The hero: the closed route, whichever curve family is selected.
                TubeNode(
                    points = route,
                    radius = strokeRadius,
                    radialSegments = LinesPathsScene.TUBE_SEGMENTS,
                    closed = true,
                    materialInstance = routeMaterial,
                )

                // The trail rides slightly fatter than the route so it reads as a highlight on
                // top of it rather than z-fighting with it.
                TubeNode(
                    points = trail,
                    radius = strokeRadius * 1.35f,
                    radialSegments = LinesPathsScene.TUBE_SEGMENTS,
                    caps = true,
                    materialInstance = markerMaterial,
                )

                SphereNode(
                    radius = LinesPathsScene.MARKER_RADIUS,
                    materialInstance = markerMaterial,
                    position = markerPosition,
                )

                // The point set the whole scene is built from — lit spheres, not GL points.
                if (showPoints) {
                    LinesPathsScene.controlPoints.forEachIndexed { index, point ->
                        key(index) {
                            SphereNode(
                                radius = LinesPathsScene.POINT_RADIUS,
                                materialInstance = pointMaterial,
                                position = point,
                            )
                        }
                    }
                }
            }
        }
    }
}

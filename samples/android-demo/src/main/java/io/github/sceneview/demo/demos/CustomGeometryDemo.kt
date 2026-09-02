package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager.PrimitiveType
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.rememberMaterialsShowcaseEnvironment
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.demos.internal.KnotParameters
import io.github.sceneview.demo.demos.internal.TorusKnot
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.safeDestroyGeometry
import io.github.sceneview.sample.LifecycleAwareLaunchedEffect
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale

/**
 * **Custom Geometry** — a mesh whose every vertex is computed in Kotlin at runtime.
 *
 * The other geometry demo in this app, [GeometryDemo], shows the *built-in* primitives:
 * `CubeNode`, `SphereNode`, `CylinderNode`, `PlaneNode`. This one shows what to do when the
 * shape you need is not one of them — you generate the vertices yourself and hand them to
 * Filament. The subject is a **(2, 3) torus knot** swept by a twisting, rippling ribbon:
 * 2 197 vertices and 4 032 triangles at the default resolution — 125 to 5 035 across the
 * Segments slider — rebuilt live every time a slider moves.
 *
 * ## The pipeline, in the order the code runs
 *
 * 1. [TorusKnot.vertices] returns `List<Geometry.Vertex>` — position, normal and UV per
 *    vertex. Plain arithmetic, no engine types, unit-tested in `TorusKnotTest`.
 * 2. [TorusKnot.triangleIndices] (or [TorusKnot.lineIndices] in Wireframe) returns the
 *    triangle list. Topology depends only on the segment count, so twisting or rippling the
 *    knot never touches it.
 * 3. `Geometry.Builder().vertices(…).indices(…).build(engine)` uploads both into a Filament
 *    `VertexBuffer` + `IndexBuffer`.
 * 4. `MeshNode(primitiveType, vertexBuffer, indexBuffer, …)` draws them.
 *
 * ## Rebuilding without churning GPU buffers
 *
 * Twist and Ripple move vertices but do **not** change how many there are, so they go
 * through `Geometry.update(engine, vertices)`, which writes into the buffers already
 * allocated. Only the Segments slider — which changes the vertex *count* — allocates new
 * ones, which is why the geometry is `remember`ed on the segment count alone and freed by a
 * matching `DisposableEffect`. Copy that pair: a demo that rebuilt the buffers on every
 * slider tick would leak a `VertexBuffer` per frame of the drag.
 *
 * Every one of those calls is a Filament JNI call and therefore **main-thread only**. The
 * generation in step 1 is pure Kotlin and could move off-thread for a much heavier mesh; the
 * upload cannot.
 *
 * Rebuilt from scratch for [#3423](https://github.com/sceneview/sceneview/issues/3423). The
 * demo it replaces composed built-in `SphereNode`s and `CylinderNode`s into a molecule and
 * extruded 2D polygons through `ShapeNode` — two built-in node types, no custom vertex
 * anywhere, which is the one thing its name promised. The retired `custom-mesh` and `shape`
 * deep links still resolve here through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
@Composable
fun CustomGeometryDemo(onBack: () -> Unit) {
    // Inspection mode (Android Studio @Preview pane, Roborazzi snapshot tests): bypass the
    // Filament-backed body BEFORE any rememberEngine() call — LayoutLib ships no .so files.
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = "Custom Geometry", onBack = onBack)
        return
    }

    var segmentsSlider by remember { mutableFloatStateOf(TorusKnot.DEFAULT_SEGMENTS.toFloat()) }
    var twistSlider by remember { mutableFloatStateOf(TorusKnot.DEFAULT_TWIST_TURNS) }
    var ripple by remember { mutableFloatStateOf(TorusKnot.DEFAULT_RIPPLE) }
    var wireframe by remember { mutableStateOf(false) }

    // Snapped, not raw: a stepped Slider still hands back 167.99998, and the parameters are
    // the `remember` key the whole mesh hangs off — a value that jitters in the last bit
    // would rebuild an identical mesh on every recomposition.
    val parameters = KnotParameters(
        segments = TorusKnot.snapSegments(segmentsSlider),
        twistTurns = TorusKnot.snapTwist(twistSlider),
        ripple = ripple,
    )

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Polished metal: the ribbon's shape is the subject, and a specular surface is what
    // makes a twist legible — the highlight travels along the band as it turns. Needs a real
    // IBL to reflect, hence the studio environment below.
    val solidMaterial = rememberMaterialInstance(
        materialLoader = materialLoader,
        color = SceneViewColors.Primary,
        metallic = 0.7f,
        roughness = 0.25f,
        reflectance = 0.6f,
    )
    // The ribbon is a closed surface, so back faces are normally hidden — but at high ripple
    // the band folds enough to show its own inside, and a single-sided material would punch
    // a hole there. Filament flips the shading normal for back faces of a double-sided
    // material, so the inside lights correctly too. Per-instance override on the ubershader:
    // no .filamat recompile.
    DisposableEffect(solidMaterial) {
        solidMaterial.setDoubleSided(true)
        onDispose { }
    }
    // Wireframe draws lines, which have no meaningful surface normal — an unlit material is
    // the honest choice. Theme primary rather than the fixed brand blue: these are hairlines
    // over the demo background, and #005BC1 on the dark theme's #111318 is unreadable.
    val wireMaterial = rememberUnlitMaterialInstance(
        materialLoader = materialLoader,
        color = MaterialTheme.colorScheme.primary,
    )

    // Continuous Y spin off the Choreographer, shared with GeometryDemo's DemoMath helper.
    // Slower than the default 36°/s: the knot is a dense object and a leisurely turn reads
    // better than a whirl. QA mode freezes it at a recognisable angle for screenshot tests.
    var spinDegrees by remember { mutableFloatStateOf(0f) }
    LifecycleAwareLaunchedEffect(DemoSettings.qaMode) {
        if (DemoSettings.qaMode) {
            spinDegrees = QA_SPIN_DEGREES
            return@LifecycleAwareLaunchedEffect
        }
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    spinDegrees = DemoMath.nextSpinDegrees(
                        previousDegrees = spinDegrees,
                        deltaNanos = nanos - lastNanos,
                        ratePerSecond = SPIN_DEGREES_PER_SECOND,
                    )
                }
                lastNanos = nanos
            }
        }
    }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_custom_geometry_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        // The live proof that the mesh is generated, not loaded: the counts move with the
        // Segments slider, under the user's thumb.
        peekHeader = meshCountsLabel(parameters.segments),
        onResetSettings = {
            segmentsSlider = TorusKnot.DEFAULT_SEGMENTS.toFloat()
            twistSlider = TorusKnot.DEFAULT_TWIST_TURNS
            ripple = TorusKnot.DEFAULT_RIPPLE
            wireframe = false
        },
        dock = listOf(
            DockItem(
                icon = Icons.Filled.GridOn,
                label = WIREFRAME_LABEL,
                onClick = { wireframe = !wireframe },
                selected = wireframe,
            )
        ),
        controls = {
            CustomGeometryControls(
                segments = segmentsSlider,
                onSegmentsChange = { segmentsSlider = it },
                segmentCount = parameters.segments,
                twistTurns = twistSlider,
                onTwistChange = { twistSlider = it },
                ripple = ripple,
                onRippleChange = { ripple = it },
            )
        },
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = firstFrame.onFrame,
            engine = engine,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            // Photo-studio IBL, no skybox: a metallic surface with nothing to reflect reads
            // as flat paint. The knot keeps floating on the theme background, which is what
            // lets the same scene look right in light and dark.
            environment = rememberMaterialsShowcaseEnvironment(environmentLoader),
            // Framing lives in TorusKnot so the fit is arithmetic a unit test checks, not a
            // number tuned by eye. The orbit distance is the LENGTH of `orbitHomePosition`
            // — see GeometryLayout's note and #2930 — and `camera_distance` (#2652) is
            // honoured here so a capture run can reframe the scene from adb.
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = TorusKnot.orbitHomeOffset(
                    DemoSettings.cameraDistance ?: TorusKnot.CAMERA_DISTANCE
                ),
                targetPosition = Position(0f, 0f, TorusKnot.TARGET_Z),
            ),
        ) {
            // Warm key from the upper front-left. The v4.1.0 SceneView defaults already add
            // a 10 000-lux main and a 3 000-lux fill, so this is an accent on top of them,
            // not the light — the same 5 000-lux budget GeometryDemo settled on in #1146.
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                apply = {
                    color(1f, 0.95f, 0.88f)
                    intensity(5_000f)
                    direction(0.35f, -0.8f, -0.5f)
                    castShadows(false)
                },
            )
            // Cool rim from behind, in the brand accent. It draws a bright edge down the far
            // side of the ribbon, which is what separates the knot's overlapping lobes from
            // each other instead of letting them merge into one silhouette.
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                apply = {
                    color(0.62f, 0.68f, 1f)
                    intensity(3_500f)
                    direction(-0.45f, 0.35f, 0.8f)
                    castShadows(false)
                },
            )

            // The knot lies almost flat in XY (its Z extent is a third of its width), so
            // face-on it reads as a rosette. A fixed tilt plus the Y spin turns it into a
            // solid that passes in front of and behind itself.
            Node(rotation = Rotation(x = KNOT_TILT_DEGREES, y = spinDegrees)) {
                ProceduralKnot(
                    parameters = parameters,
                    wireframe = wireframe,
                    materialInstance = if (wireframe) wireMaterial else solidMaterial,
                )
            }
        }
    }
}

/**
 * Generates the knot and draws it — the four steps of the custom-geometry pipeline, in
 * about twenty lines. Lift this whole function into your own app and change
 * [TorusKnot.vertices] for your own generator.
 *
 * The lifecycle is the part worth copying carefully:
 *
 * - `vertices` is `remember`ed on the **parameters**, so nothing regenerates until a value
 *   actually changes.
 * - `geometry` is `remember`ed on the **topology** only (segment count + primitive type).
 *   Twist and Ripple keep the same vertex count, so they reuse the same GPU buffers.
 * - `SideEffect` pushes new vertex data into those buffers via `Geometry.update`.
 * - `DisposableEffect` frees them with `Engine.safeDestroyGeometry` when the topology
 *   changes or the demo leaves. `MeshNode` deliberately does **not** own buffers it was
 *   handed, so this is the caller's job — skip it and every Segments change leaks a
 *   `VertexBuffer` into Filament's native heap.
 */
@Composable
private fun SceneScope.ProceduralKnot(
    parameters: KnotParameters,
    wireframe: Boolean,
    materialInstance: MaterialInstance,
) {
    val vertices = remember(parameters) { TorusKnot.vertices(parameters) }
    val primitiveType = if (wireframe) PrimitiveType.LINES else PrimitiveType.TRIANGLES
    val geometry = remember(engine, parameters.segments, wireframe) {
        Geometry.Builder(primitiveType)
            .vertices(vertices)
            .indices(
                if (wireframe) {
                    TorusKnot.lineIndices(parameters.segments)
                } else {
                    TorusKnot.triangleIndices(parameters.segments)
                }
            )
            .build(engine)
    }
    // Same vertex count, new positions: rewrite the existing buffer. `Geometry.update` is a
    // no-op when the list is unchanged, so this costs nothing on an idle recomposition.
    SideEffect { geometry.update(engine, vertices) }
    DisposableEffect(geometry) {
        onDispose { engine.safeDestroyGeometry(geometry) }
    }

    MeshNode(
        primitiveType = primitiveType,
        vertexBuffer = geometry.vertexBuffer,
        indexBuffer = geometry.indexBuffer,
        boundingBox = geometry.boundingBox,
        materialInstance = materialInstance,
    )
}

/**
 * Controls panel for [CustomGeometryDemo] — stateless, so a Roborazzi snapshot test can
 * capture it in pure JVM with no Filament Engine. Pattern from issue #880.
 *
 * @param segments Raw Segments slider position; [segmentCount] is the snapped value the mesh
 * was actually generated at, and the one shown to the reader.
 */
@Composable
internal fun CustomGeometryControls(
    segments: Float,
    onSegmentsChange: (Float) -> Unit,
    segmentCount: Int,
    twistTurns: Float,
    onTwistChange: (Float) -> Unit,
    ripple: Float,
    onRippleChange: (Float) -> Unit,
) {
    Text(
        text = stringResource(R.string.demo_custom_geometry_explainer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Without this the caption's last line sits directly on the first slider's label and the
    // two read as one paragraph. `space-md` is DESIGN.md's block separator.
    Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))

    LabeledSlider(
        label = "Segments",
        value = segments,
        onValueChange = onSegmentsChange,
        valueRange = TorusKnot.MIN_SEGMENTS.toFloat()..TorusKnot.MAX_SEGMENTS.toFloat(),
        steps = TorusKnot.segmentSliderSteps,
        valueText = "$segmentCount rings",
    )

    LabeledSlider(
        label = "Twist",
        value = twistTurns,
        onValueChange = onTwistChange,
        valueRange = 0f..TorusKnot.MAX_TWIST_TURNS,
        // Stepped, and not by taste: the ribbon only joins up at half-turn multiples.
        steps = TorusKnot.twistSliderSteps,
        valueText = "%.1f turns".format(Locale.US, twistTurns),
    )

    LabeledSlider(
        label = "Ripple",
        value = ripple,
        onValueChange = onRippleChange,
        valueRange = 0f..TorusKnot.MAX_RIPPLE,
        decimals = 2,
    )
}

/** Live mesh size, shown as the scaffold's status pill. */
private fun meshCountsLabel(segments: Int): String = String.format(
    Locale.US,
    "%,d vertices · %,d triangles",
    TorusKnot.vertexCount(segments),
    TorusKnot.triangleCount(segments),
)

/** Content description of the dock's wireframe toggle — also its UI-test handle. */
internal const val WIREFRAME_LABEL = "Wireframe"

/** Fixed tilt about X, in degrees, so the knot never reads as a flat rosette. */
private const val KNOT_TILT_DEGREES = 24f

/** Spin rate about Y, in degrees per second. One revolution every 18 s. */
private const val SPIN_DEGREES_PER_SECOND = 20f

/** Frozen spin angle under `DemoSettings.qaMode`, for deterministic screenshot captures. */
private const val QA_SPIN_DEGREES = 30f

// ── Android Studio @Preview support ────────────────────────────────────────────

@Preview(name = "Demo (light)", showBackground = true)
@Composable
private fun CustomGeometryDemoPreview_Light() {
    SceneViewDemoTheme(darkTheme = false) {
        CustomGeometryDemo(onBack = {})
    }
}

@Preview(
    name = "Demo (dark)",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun CustomGeometryDemoPreview_Dark() {
    SceneViewDemoTheme(darkTheme = true) {
        CustomGeometryDemo(onBack = {})
    }
}

@Preview(name = "Controls only", showBackground = true)
@Composable
private fun CustomGeometryControlsPreview() {
    SceneViewDemoTheme(darkTheme = false) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
        ) {
            CustomGeometryControls(
                segments = TorusKnot.DEFAULT_SEGMENTS.toFloat(),
                onSegmentsChange = {},
                segmentCount = TorusKnot.DEFAULT_SEGMENTS,
                twistTurns = TorusKnot.DEFAULT_TWIST_TURNS,
                onTwistChange = {},
                ripple = TorusKnot.DEFAULT_RIPPLE,
                onRippleChange = {},
            )
        }
    }
}

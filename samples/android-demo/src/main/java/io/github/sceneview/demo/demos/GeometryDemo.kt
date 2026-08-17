package io.github.sceneview.demo.demos

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import io.github.sceneview.sample.LifecycleAwareLaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.demos.internal.GeometryLayout
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.LightNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale

/**
 * Shows the four built-in geometry primitives: Cube, Sphere, Cylinder, Plane.
 *
 * Controls:
 * - Visibility chips per shape (scrollable on narrow viewports)
 * - Metallic / Roughness sliders applied to all visible primitives — lets users see
 *   the full PBR range from a chalky matte (M=0, R=1) to a polished mirror (M=1, R=0)
 * - Continuous Y-axis spin so each shape shows all sides instead of a single static face
 *
 * The four primitives sit in a **2 × 2 cluster**, not a row: a row of four is wider than a
 * phone-portrait frame at any camera distance, so one primitive was always clipped at an
 * edge (#2873). [GeometryLayout] owns every position, size and distance involved, and
 * `GeometryLayoutTest` asserts the cluster still clears the frame with margin.
 */
@Composable
fun GeometryDemo(onBack: () -> Unit) {
    // Inspection mode (Android Studio @Preview pane, Roborazzi snapshot tests):
    // bypass the entire Filament-backed body BEFORE any rememberEngine() call. Without
    // this, the preview pane crashes loading the .so files (Android-arch only, AS
    // LayoutLib doesn't ship them). See DemoPreviewPlaceholder.
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = "Geometry Primitives", onBack = onBack)
        return
    }

    var showCube by remember { mutableStateOf(true) }
    var showSphere by remember { mutableStateOf(true) }
    var showCylinder by remember { mutableStateOf(true) }
    var showPlane by remember { mutableStateOf(true) }
    // PBR sliders. Defaults match a slightly metallic, slightly rough surface — a
    // visually interesting "in-between" rather than either extreme. Range 0..1
    // covers the full Filament PBR space.
    var metallic by remember { mutableFloatStateOf(0.3f) }
    var roughness by remember { mutableFloatStateOf(0.5f) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    // IBL environment gives the primitives real ambient + specular highlights, so the
    // rendering doesn't look flat. Without this the cube/cylinder/plane are unlit.
    val environmentLoader = rememberEnvironmentLoader(engine)

    // On-brand ramp — Primary blue, Accent purple, light blue, soft purple — so the four
    // primitives stay visually distinct while all reading as SceneView.
    // metallic / roughness are pushed into each instance by rememberMaterialInstance
    // itself (via a paired DisposableEffect) — no extra slider-sync LaunchedEffect needed.
    val cubeMaterial = rememberMaterialInstance(materialLoader, SceneViewColors.Ramp4[0], metallic, roughness)
    val sphereMaterial = rememberMaterialInstance(materialLoader, SceneViewColors.Ramp4[1], metallic, roughness)
    val cylinderMaterial = rememberMaterialInstance(materialLoader, SceneViewColors.Ramp4[2], metallic, roughness)
    val planeMaterial = rememberMaterialInstance(materialLoader, SceneViewColors.Ramp4[3], metallic, roughness)

    // The plane spins on its Y axis, so for half of every revolution its back face
    // points at the camera. With the default single-sided culling that half-turn
    // renders nothing and the panel appears to blink out of existence — see #1426.
    // Mark the shared PBR material double-sided so the back face stays visible.
    // setDoubleSided is a per-MaterialInstance override on the ubershader, so this
    // is a code-only change — no .filamat recompile. Keyed on planeMaterial so the
    // override survives a material re-allocation.
    DisposableEffect(planeMaterial) {
        planeMaterial.setDoubleSided(true)
        onDispose { }
    }

    // Continuous Y-axis spin shared by all primitives. withFrameNanos drives the
    // angle off the Choreographer so it runs at the display's refresh rate without
    // a separate Animatable per shape — one frame loop, four nodes share the value.
    // The math (advance + 360° wrap) is in DemoMath.nextSpinDegrees so it can be JVM-
    // unit-tested without firing up Compose / the Choreographer.
    //
    // QA mode (DemoSettings.qaMode = true, set via long-press on the title or
    // `--ez qa_mode true` from adb) freezes the spin at a recognisable 30° angle so
    // screenshot tests get a deterministic frame.
    var spinDegrees by remember { mutableFloatStateOf(0f) }
    // Spin pauses when the app is backgrounded — without the lifecycle wrap
    // the `while(true) { withFrameNanos { … } }` loop kept burning frames
    // (and the SceneView render thread alongside it) on the home screen.
    // See #936.
    LifecycleAwareLaunchedEffect(DemoSettings.qaMode) {
        if (DemoSettings.qaMode) {
            spinDegrees = 30f // ~front-3/4 view, all shapes show their depth
            return@LifecycleAwareLaunchedEffect
        }
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    spinDegrees = DemoMath.nextSpinDegrees(spinDegrees, nanos - lastNanos)
                }
                lastNanos = nanos
            }
        }
    }

    val firstFrame = rememberFirstFrameState()


    DemoScaffold(
        title = stringResource(R.string.demo_geometry_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            // Controls extracted into a separate composable so a Roborazzi snapshot
            // test can capture the panel layout in pure JVM (no Filament, no SceneView).
            // See GeometryDemoControlsSnapshotTest. Pattern from issue #880.
            GeometryDemoControls(
                showCube = showCube, onShowCubeChange = { showCube = it },
                showSphere = showSphere, onShowSphereChange = { showSphere = it },
                showCylinder = showCylinder, onShowCylinderChange = { showCylinder = it },
                showPlane = showPlane, onShowPlaneChange = { showPlane = it },
                metallic = metallic, onMetallicChange = { metallic = it },
                roughness = roughness, onRoughnessChange = { roughness = it },
            )
        }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = firstFrame.onFrame,
            engine = engine,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            // Framing lives in GeometryLayout so the cluster's fit in a portrait frame is
            // arithmetic a unit test can check, not a number tuned by eye (#2873). Note that
            // the orbit distance is the LENGTH of `orbitHomePosition`: Filament takes it as
            // the eye verbatim, and `autoCenterContent = true` (the default, kept here) has
            // already moved the cluster onto the world origin, so `targetPosition` does not
            // enter into the distance — see GeometryLayout's orbit-distance note and #2930.
            // The framing comment this replaces claimed 2.7 m for a camera that was 1.22 m
            // out, which is the other half of why the row never fit.
            //
            // `camera_distance` (#2652) is honoured here even though this demo does NOT use
            // `rememberHeroOrbitCameraManipulator` — the shared manipulator that reads the
            // extra for the hero demos. Without this the extra was a silent no-op on
            // `geometry`, which is why #2873 reports the clipping as unfixable "at every
            // camera distance": the distances tried never reached the camera. That silent
            // no-op on non-hero-orbit demos is #2785's scope; wiring it here fixes it for
            // this one demo and makes the framing verifiable from a capture run.
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = GeometryLayout.orbitHomeOffset(
                    DemoSettings.cameraDistance ?: GeometryLayout.CAMERA_DISTANCE
                ),
                targetPosition = Position(0f, 0f, GeometryLayout.TARGET_Z),
            ),
        ) {
            // Accent fill — a warm low-intensity rim that complements the v4.1.0
            // SceneView defaults (10_000-lux main + 3_000-lux fill + IBL). Pre-v4.1.0
            // this was stacked at 80_000 lux on top of the legacy hardcoded 100k
            // main and read sanely; with the new defaults that combination blew
            // out the metallic/rough sweep — primitives saturated to white and the
            // material slider became visually inert. Re-tuned to 5_000 to match
            // PhysicsDemo's PR #1144 retune (sibling of #1125). See #1146.
            LightNode(
                engine = engine,
                type = LightManager.Type.DIRECTIONAL,
                apply = {
                    color(1.0f, 0.95f, 0.9f)
                    intensity(5_000f)
                    direction(0.3f, -1f, -0.5f)
                    castShadows(false)
                },
            )

            // 2 × 2 cluster centred on x = 0, reading in the same order as the visibility
            // chips (Cube, Sphere / Cylinder, Plane). Two columns instead of four halves
            // the horizontal footprint, which is what makes the group fit a phone-portrait
            // frame — the four-wide row it replaces was wider than the frame at every
            // camera distance (#2873).
            // The three SOLID shapes spin on Y to expose all sides — particularly
            // the cylinder, whose curved side and flat caps read very differently.
            // The plane is deliberately not one of them; see its own rotation below.
            val spinRotation = Rotation(y = spinDegrees)
            if (showCube) {
                CubeNode(
                    materialInstance = cubeMaterial,
                    size = Float3(
                        GeometryLayout.CUBE_EDGE,
                        GeometryLayout.CUBE_EDGE,
                        GeometryLayout.CUBE_EDGE,
                    ),
                    position = Position(
                        x = -GeometryLayout.COLUMN_X,
                        y = GeometryLayout.ROW_Y,
                        z = GeometryLayout.TARGET_Z,
                    ),
                    rotation = spinRotation,
                )
            }
            if (showSphere) {
                SphereNode(
                    materialInstance = sphereMaterial,
                    radius = GeometryLayout.SPHERE_RADIUS,
                    position = Position(
                        x = GeometryLayout.COLUMN_X,
                        y = GeometryLayout.ROW_Y,
                        z = GeometryLayout.TARGET_Z,
                    ),
                    rotation = spinRotation,
                )
            }
            if (showCylinder) {
                CylinderNode(
                    materialInstance = cylinderMaterial,
                    radius = GeometryLayout.CYLINDER_RADIUS,
                    height = GeometryLayout.CYLINDER_HEIGHT,
                    position = Position(
                        x = -GeometryLayout.COLUMN_X,
                        y = -GeometryLayout.ROW_Y,
                        z = GeometryLayout.TARGET_Z,
                    ),
                    rotation = spinRotation,
                )
            }
            if (showPlane) {
                // Plane primitive renders as a flat XY panel facing the camera (+Z).
                // Previous `Float3(0.32, 0.32, 1f)` made a tilted parallelogram because
                // Plane.getVertices uses ALL three size components — a non-zero z on what
                // should be a flat XY quad twists the four corners into a diagonal surface.
                // Use z=0 for a true flat panel, and set `normal = +Z` so lighting hits
                // the visible face.
                PlaneNode(
                    materialInstance = planeMaterial,
                    size = Float3(GeometryLayout.PLANE_EDGE, GeometryLayout.PLANE_EDGE, 0f),
                    normal = Direction(z = 1f),
                    position = Position(
                        x = GeometryLayout.COLUMN_X,
                        y = -GeometryLayout.ROW_Y,
                        z = GeometryLayout.TARGET_Z,
                    ),
                    // NOT `spinRotation`. A zero-thickness quad spun about Y goes
                    // edge-on twice per revolution and disappears outright — the
                    // one shape in this demo that could vanish, in the demo whose
                    // whole subject is shapes. Spinning about Z turns the quad in
                    // its own plane, which leaves its normal pointing at the
                    // camera, and PLANE_TILT_DEGREES tilts that normal by a fixed
                    // amount so the panel still catches the light and reads as a
                    // surface in space rather than a sticker. #3231
                    rotation = Rotation(
                        x = GeometryLayout.PLANE_TILT_DEGREES,
                        z = spinDegrees,
                    ),
                )
            }
        }
    }
}

/**
 * Controls panel for [GeometryDemo], extracted into a separate `@Composable` so a
 * Roborazzi snapshot test (`GeometryDemoControlsSnapshotTest`) can capture the panel
 * layout in pure JVM — no Filament Engine, no `SceneView`, no Choreographer.
 *
 * State + callbacks are passed in by the parent: this composable is **stateless** in
 * the Compose sense, which makes it both unit-testable and straightforwardly
 * reusable. See issue [#880](https://github.com/sceneview/sceneview/issues/880).
 */
@Composable
internal fun GeometryDemoControls(
    showCube: Boolean, onShowCubeChange: (Boolean) -> Unit,
    showSphere: Boolean, onShowSphereChange: (Boolean) -> Unit,
    showCylinder: Boolean, onShowCylinderChange: (Boolean) -> Unit,
    showPlane: Boolean, onShowPlaneChange: (Boolean) -> Unit,
    metallic: Float, onMetallicChange: (Float) -> Unit,
    roughness: Float, onRoughnessChange: (Float) -> Unit,
) {
    Text("Visible Shapes", style = MaterialTheme.typography.labelLarge)
    // horizontalScroll on the chip row so a narrow viewport (or future
    // additional shape chips) still fits without overflow / line break.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(showCube, onClick = { onShowCubeChange(!showCube) }, label = { Text("Cube") })
        FilterChip(showSphere, onClick = { onShowSphereChange(!showSphere) }, label = { Text("Sphere") })
        FilterChip(showCylinder, onClick = { onShowCylinderChange(!showCylinder) }, label = { Text("Cylinder") })
        FilterChip(showPlane, onClick = { onShowPlaneChange(!showPlane) }, label = { Text("Plane") })
    }

    Spacer(modifier = Modifier.height(12.dp))

    LabeledSlider(
        label = "Metallic",
        value = metallic,
        onValueChange = onMetallicChange,
        valueRange = 0f..1f,
        valueText = "%.2f".format(Locale.US, metallic),
    )

    LabeledSlider(
        label = "Roughness",
        value = roughness,
        onValueChange = onRoughnessChange,
        valueRange = 0f..1f,
        valueText = "%.2f".format(Locale.US, roughness),
    )
}

// ── Android Studio @Preview support ────────────────────────────────────────────
//
// `LocalInspectionMode.current == true` inside the preview pane (and inside Roborazzi
// snapshot tests) makes the demo body short-circuit to DemoPreviewPlaceholder above,
// so AS Preview shows the scaffold + a placeholder explaining that the actual 3D
// content is rendered via Live Edit on a connected device. The two previews below
// give the IDE a default + a dark-theme variant — the same pattern can be lifted
// to every demo for "free" preview support.

@Preview(name = "Demo (light)", showBackground = true)
@Composable
private fun GeometryDemoPreview_Light() {
    SceneViewDemoTheme(darkTheme = false) {
        GeometryDemo(onBack = {})
    }
}

@Preview(name = "Demo (dark)", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GeometryDemoPreview_Dark() {
    SceneViewDemoTheme(darkTheme = true) {
        GeometryDemo(onBack = {})
    }
}

@Preview(name = "Controls only", showBackground = true)
@Composable
private fun GeometryDemoControlsPreview() {
    SceneViewDemoTheme(darkTheme = false) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
        ) {
            GeometryDemoControls(
                showCube = true, onShowCubeChange = {},
                showSphere = true, onShowSphereChange = {},
                showCylinder = false, onShowCylinderChange = {},
                showPlane = true, onShowPlaneChange = {},
                metallic = 0.3f, onMetallicChange = {},
                roughness = 0.5f, onRoughnessChange = {},
            )
        }
    }
}

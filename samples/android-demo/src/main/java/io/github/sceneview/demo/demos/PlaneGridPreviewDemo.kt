package io.github.sceneview.demo.demos

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.RenderableManager
import com.google.android.filament.Skybox
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneView
import io.github.sceneview.ar.scene.PlaneRenderer
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.ConnectedChoiceRow
import io.github.sceneview.environment.Environment
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.material.setParameter
import io.github.sceneview.math.Position
import io.github.sceneview.math.Position2
import io.github.sceneview.math.Rotation
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.ui.LabeledSlider
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **AR Plane Grid Preview** — a *non-AR* SceneView that renders the real AR surface material
 * ([PlaneRenderer]'s `plane_renderer.filamat`) on a static, hand-built surface.
 *
 * ### Why this exists (#2224)
 *
 * How the marks read — against a dark room, against a sunlit one, face-on, at a grazing
 * angle — depends on the shader, the camera-to-surface angle and the background. It does
 * **not** depend on ARCore at all. So the shader can be visually QA'd on any 3D-capable device
 * or emulator by reproducing that exact geometry + material, with **no ARCore session and no
 * physical AR device** (the emulator cannot run ARCore camera playback — see `arsceneview`
 * AR-replay honesty gate #1645). That makes surface-shader regressions catchable on a plain
 * Mac/CI emulator instead of only on hardware.
 *
 * ### Faithfulness
 *
 * - The mesh mirrors [io.github.sceneview.ar.PlaneVisualizer] exactly: an outer boundary ring
 *   at local `y = 0` (the shader reads `y` as the alpha ramp → edge, fully feathered out) and
 *   a feathered inner ring at `y = 1` (interior). The shader zeroes local `y` before computing
 *   world position, so the rendered surface is flat in the local XZ plane just like a detected
 *   horizontal plane.
 * - The material parameters mirror what [PlaneRenderer] and its per-plane visualizer set on a
 *   focused surface: world-anchored marks, the bright spot where the camera looks, the ring,
 *   and the edge and distance fades. The reveal animation and the fade on placement are
 *   driven per frame by the AR renderer and are not reproduced here.
 *
 * ### One mark per surface type (#4307)
 *
 * **Floor**, **Wall** and **Ceiling** apply the three styles the renderer picks from
 * `Plane.type`: round white dots, upright blue dashes on staggered rows, hollow warm rings.
 * Picking Wall also stands the surface up, since the shader lays its rows along the wall only
 * once the surface is steeper than ~45°.
 *
 * ### Controls
 *
 * - **Bright background** — toggles the skybox between a dark scene and a light "sunny
 *   outdoor" grey: the two conditions the marks have to stay readable in.
 * - **Surface tilt** — rotates the surface from flat (0°) to upright (90°). You can also
 *   orbit freely by dragging.
 */
@Composable
fun PlaneGridPreviewDemo(onBack: () -> Unit) {
    var brightBackground by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf(PreviewSurface.Floor) }
    var surfaceTilt by remember { mutableFloatStateOf(PreviewSurface.Floor.tiltDegrees) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    // Real production material blob + the parameters PlaneRenderer sets on a focused surface.
    val surfaceMaterialInstance = remember(engine) {
        materialLoader.createMaterial("materials/plane_renderer.filamat")
            .createInstance()
            .apply {
                setParameter(PlaneRenderer.MATERIAL_UV_SCALE, Float2(MARKS_PER_METRE, MARKS_PER_METRE))
                setParameter(PlaneRenderer.MATERIAL_SURFACE_ALPHA, SURFACE_ALPHA)
                setParameter(PlaneRenderer.MATERIAL_CONTRAST, CONTRAST)
                setParameter(PlaneRenderer.MATERIAL_SCAN_PROGRESS, 1f)
                setParameter(PlaneRenderer.MATERIAL_SCAN_PLANE_RADIUS, PLANE_RADIUS)
                setParameter(PlaneRenderer.MATERIAL_OPACITY, 1f)
                setParameter(PlaneRenderer.MATERIAL_FOCUS, 1f)
            }
    }
    // Read here, in composition: a read that only happened inside the effect would not
    // recompose this scope, and the effect would keep the first style for good.
    val style = surface
    SideEffect {
        surfaceMaterialInstance.setParameter(PlaneRenderer.MATERIAL_SURFACE_KIND, style.kind)
        surfaceMaterialInstance.setParameter(PlaneRenderer.MATERIAL_GRID_TINT, style.tint)
        surfaceMaterialInstance.setParameter(PlaneRenderer.MATERIAL_GRID_ALPHA, style.markAlpha)
    }

    val planeGeometry = remember(engine) { buildPlaneGeometry(engine) }

    // Solid-colour background skyboxes. Built once; selected per toggle. Both are freed
    // when rememberEngine() tears the engine down on disposal (engine.safeDestroy()).
    // Filament Skybox colours are LINEAR. The dark backdrop stands for a dim interior; the
    // bright preset (~near white in sRGB) reproduces the "sunny outdoor camera feed" condition
    // under which the #2224 white-blob regression had to be judged, and under which white
    // marks need their dark rim to stay readable.
    val darkSkybox = remember(engine) {
        Skybox.Builder().color(0.05f, 0.05f, 0.06f, 1.0f).build(engine)
    }
    val brightSkybox = remember(engine) {
        Skybox.Builder().color(0.85f, 0.85f, 0.80f, 1.0f).build(engine)
    }
    val environment = remember(brightBackground, darkSkybox, brightSkybox) {
        Environment(skybox = if (brightBackground) brightSkybox else darkSkybox)
    }

    val firstFrame = rememberFirstFrameState(engine)

    DemoScaffold(
        title = stringResource(R.string.demo_plane_grid_preview_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ConnectedChoiceRow(
                options = PreviewSurface.entries,
                selected = surface,
                onSelect = {
                    surface = it
                    surfaceTilt = it.tiltDegrees
                },
                label = { stringResource(it.labelRes) },
            )
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = brightBackground,
                        onValueChange = { brightBackground = it },
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.plane_grid_preview_bright_background),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(checked = brightBackground, onCheckedChange = null)
            }
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            LabeledSlider(
                label = stringResource(R.string.plane_grid_preview_tilt),
                value = surfaceTilt,
                onValueChange = { surfaceTilt = it },
                valueRange = 0f..MAX_TILT_DEGREES,
                valueText = "${surfaceTilt.toInt()}°",
            )
        }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = firstFrame.onFrame,
            engine = engine,
            materialLoader = materialLoader,
            environment = environment,
            // Match ARSceneView's render pipeline: the AR view runs with bloom + SSAO OFF
            // (RenderQuality.Performance is the documented "AR camera-feed" preset). The plain
            // SceneView default keeps bloom ON, which bleeds the translucent marks into a
            // uniform wash — making a healthy material look like a blob it isn't.
            renderQuality = RenderQuality.Performance,
            // Strict placement: keep the plane at the origin so the framing below is deterministic.
            autoCenterContent = false,
            cameraManipulator = rememberCameraManipulator(
                // ~47° downward look at the surface centre — a moderate AR-floor angle. Drag to
                // orbit, or use the tilt slider to stand the surface up.
                orbitHomePosition = Position(0f, 1.4f, 1.3f),
                targetPosition = Position(0f, 0f, 0f),
            ),
        ) {
            Node(rotation = Rotation(x = surfaceTilt)) {
                MeshNode(
                    primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                    vertexBuffer = planeGeometry.vertexBuffer,
                    indexBuffer = planeGeometry.indexBuffer,
                    // Local AABB of the vertex buffer (x/z span the octagon, y is the 0..1 alpha
                    // ramp). Required: Filament rejects an empty AABB on a shadow caster/receiver.
                    boundingBox = Box(0f, 0.5f, 0f, PLANE_RADIUS + 0.1f, 0.6f, PLANE_RADIUS + 0.1f),
                    materialInstance = surfaceMaterialInstance,
                )
            }
        }
    }
}

/**
 * The three styles `planeMaterialPresetFor` (internal to arsceneview) maps `Plane.type` to,
 * mirrored value for value (#4307), each with the tilt that shows it the way it is met in a
 * room: a floor and a ceiling lie flat, a wall stands.
 */
private enum class PreviewSurface(
    @param:StringRes val labelRes: Int,
    val kind: Float,
    val tint: Float3,
    val markAlpha: Float,
    val tiltDegrees: Float,
) {
    Floor(R.string.plane_grid_preview_floor, 0f, Float3(1.0f, 1.0f, 1.0f), 0.85f, 0f),
    Wall(R.string.plane_grid_preview_wall, 1f, Float3(0.16f, 0.45f, 1.0f), 0.80f, 75f),
    Ceiling(R.string.plane_grid_preview_ceiling, 2f, Float3(1.0f, 0.62f, 0.22f), 0.65f, 0f),
}

private const val PLANE_RADIUS = 1.2f

// PlaneRenderer's shared defaults (private to arsceneview), mirrored for the preview.
private const val MARKS_PER_METRE = 10.0f
private const val SURFACE_ALPHA = 0.015f
private const val CONTRAST = 0.55f

private const val MAX_TILT_DEGREES = 90f
private const val PLANE_SIDES = 8
// Edge feather — mirrors PlaneVisualizer's FEATHER_LENGTH / FEATHER_SCALE.
private const val FEATHER_LENGTH = 0.2f
private const val FEATHER_SCALE = 0.2f

/**
 * Builds a static octagonal plane mesh that mirrors
 * [io.github.sceneview.ar.PlaneVisualizer.updateGeometry] byte-for-byte in structure:
 * an outer boundary ring at `y = 0` and a feathered inner ring at `y = 1`, joined by a
 * boundary strip, with the interior triangulated as a fan. The `y` coordinate is the
 * shader's per-vertex alpha ramp (0 at the edge, 1 in the interior), not a height.
 */
private fun buildPlaneGeometry(engine: Engine): Geometry {
    // Octagon outline in the local XZ plane (x, z), like an ARCore HORIZONTAL plane polygon.
    // The z term is negated so the ring winds counter-clockwise when viewed from above (+Y):
    // the up-facing side is then the front face, so it survives the material's default
    // back-face culling when seen by a camera looking down at the "floor".
    val boundary = (0 until PLANE_SIDES).map { i ->
        val angle = (i.toFloat() / PLANE_SIDES) * 2f * PI.toFloat()
        Position2(cos(angle) * PLANE_RADIUS, -sin(angle) * PLANE_RADIUS)
    }
    val n = boundary.size

    val vertices = buildList {
        // Outer boundary ring at y = 0 (plane edge, alpha → 0).
        boundary.forEach { add(Geometry.Vertex(position = Position(it.x, 0f, it.y))) }
        // Inner feathered ring at y = 1 (interior, alpha → 1).
        boundary.forEach { p ->
            val magnitude = sqrt(p.x * p.x + p.y * p.y)
            val scale = if (magnitude != 0f) {
                1f - minOf(FEATHER_LENGTH / magnitude, FEATHER_SCALE)
            } else {
                1f - FEATHER_SCALE
            }
            add(Geometry.Vertex(position = Position(p.x * scale, 1f, p.y * scale)))
        }
    }

    val indices = buildList {
        val firstInner = n
        // Interior fan over the inner ring.
        for (i in 0 until n - 2) {
            add(firstInner); add(firstInner + i + 1); add(firstInner + i + 2)
        }
        // Boundary strip quads connecting the outer (y=0) and inner (y=1) rings.
        for (i in 0 until n) {
            val o1 = i
            val o2 = (i + 1) % n
            val i1 = firstInner + i
            val i2 = firstInner + (i + 1) % n
            add(o1); add(o2); add(i1)
            add(i1); add(o2); add(i2)
        }
    }

    return Geometry.Builder()
        .vertices(vertices)
        .indices(indices)
        .build(engine)
}

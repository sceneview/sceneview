package io.github.sceneview.demo.demos

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import io.github.sceneview.environment.Environment
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.material.setParameter
import io.github.sceneview.material.setTexture
import io.github.sceneview.math.Position
import io.github.sceneview.math.Position2
import io.github.sceneview.math.Rotation
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.ui.LabeledSlider
import io.github.sceneview.texture.ImageTexture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **AR Plane Grid Preview** — a *non-AR* SceneView that renders the real ARCore
 * plane-detection grid material ([PlaneRenderer]'s `plane_renderer.filamat`) on a
 * static, hand-built surface.
 *
 * ### Why this exists (#2224)
 *
 * The plane grid's appearance is driven by a procedural shader whose key failure
 * mode — `fwidth(uv)` saturating at oblique (grazing) camera angles and degenerating
 * the thin grid lines into a solid white blob — depends only on **screen-space
 * derivatives**, i.e. the camera-to-surface angle. It does **not** depend on ARCore
 * at all. So the shader can be visually QA'd on any 3D-capable device or emulator by
 * reproducing that exact geometry + material + grazing angle, with **no ARCore session
 * and no physical AR device** (the emulator cannot run ARCore camera playback — see
 * `arsceneview` AR-replay honesty gate #1645). That makes plane-shader regressions
 * catchable on a plain Mac/CI emulator instead of only on hardware.
 *
 * ### Faithfulness
 *
 * - The mesh mirrors [io.github.sceneview.ar.PlaneVisualizer] exactly: an outer
 *   boundary ring at local `y = 0` (the shader reads `y` as the alpha ramp → plane
 *   edge, fully feathered out) and a feathered inner ring at `y = 1` (interior). The
 *   shader zeroes local `y` before computing world position, so the rendered surface
 *   is flat in the local XZ plane just like a detected horizontal plane.
 * - The material parameters mirror [PlaneRenderer] exactly: the same `plane_renderer.png`
 *   texture, `uvScale = 4.0` ([PlaneRenderer]'s private `BASE_UV_SCALE`, #2224), and the
 *   cool-white `color = (0.85, 0.90, 1.0)` tint.
 *
 * ### Controls
 *
 * - **Bright background** — toggles the skybox between a dark scene and a light
 *   "sunny outdoor" grey. The white-blob regression only reads as a blob *over a
 *   bright camera feed*; this toggle reproduces that judgement condition.
 * - **Surface tilt** — rotates the plane from face-on (0°) toward edge-on/grazing
 *   (80°), sweeping the camera-to-surface angle that drives `fwidth` saturation.
 *   You can also orbit freely by dragging.
 */
@Composable
fun PlaneGridPreviewDemo(onBack: () -> Unit) {
    var brightBackground by remember { mutableStateOf(false) }
    var surfaceTilt by remember { mutableFloatStateOf(DEFAULT_TILT) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    // Real production plane texture (arsceneview asset, merged into the demo APK).
    val planeTexture = remember(engine) {
        ImageTexture.Builder()
            .bitmap(materialLoader.assets, "textures/plane_renderer.png")
            .build(engine)
    }

    // Real production plane material blob + the exact parameters PlaneRenderer sets.
    val planeMaterialInstance = remember(engine) {
        materialLoader.createMaterial("materials/plane_renderer.filamat")
            .createInstance()
            .apply {
                setTexture(PlaneRenderer.MATERIAL_TEXTURE, planeTexture)
                val ratio = planeTexture.getWidth(0).toFloat() / planeTexture.getHeight(0).toFloat()
                setParameter(PlaneRenderer.MATERIAL_UV_SCALE, Float2(PLANE_UV_SCALE, PLANE_UV_SCALE * ratio))
                // #2224 cool-white tint — see PlaneRenderer.planeMaterial.
                setParameter(PlaneRenderer.MATERIAL_COLOR, Float3(0.85f, 0.90f, 1.0f))
            }
    }

    val planeGeometry = remember(engine) { buildPlaneGeometry(engine) }

    // Solid-colour background skyboxes. Built once; selected per toggle. Both are freed
    // when rememberEngine() tears the engine down on disposal (engine.safeDestroy()).
    // Filament Skybox colours are LINEAR. A dark backdrop makes the cool-white grid pop for
    // geometry/line inspection; the bright preset (~near white in sRGB) reproduces the
    // "sunny outdoor camera feed" condition under which the #2224 white-blob regression had
    // to be judged (a healthy fix reads as a faint overlay, not an opaque white blob).
    val darkSkybox = remember(engine) {
        Skybox.Builder().color(0.05f, 0.05f, 0.06f, 1.0f).build(engine)
    }
    val brightSkybox = remember(engine) {
        Skybox.Builder().color(0.85f, 0.85f, 0.80f, 1.0f).build(engine)
    }
    val environment = remember(brightBackground, darkSkybox, brightSkybox) {
        Environment(skybox = if (brightBackground) brightSkybox else darkSkybox)
    }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_plane_grid_preview_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
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
                    "Bright background (outdoor)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(checked = brightBackground, onCheckedChange = null)
            }
            Spacer(modifier = Modifier.height(12.dp))
            LabeledSlider(
                label = "Surface tilt",
                value = surfaceTilt,
                onValueChange = { surfaceTilt = it },
                valueRange = 0f..80f,
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
            // SceneView default keeps bloom ON, which bleeds the 20%-alpha plane into a uniform
            // white wash and hides the grid — making the shipped fix look like a blob it isn't.
            renderQuality = RenderQuality.Performance,
            // Strict placement: keep the plane at the origin so the framing below is deterministic.
            autoCenterContent = false,
            cameraManipulator = rememberCameraManipulator(
                // ~47° downward look at the plane centre — a moderate AR-floor angle where the
                // grid still resolves. Drag to orbit, or use the tilt slider to push toward the
                // grazing angle that drives the #2224 fwidth saturation.
                eyePosition = Position(0f, 1.4f, 1.3f),
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
                    materialInstance = planeMaterialInstance,
                )
            }
        }
    }
}

// PlaneRenderer.BASE_UV_SCALE is private; mirror its #2224 value here. Denser cells keep
// `fwidth(uv)` in a stable range at oblique angles (see plane_renderer.mat gridLine()).
private const val PLANE_UV_SCALE = 4.0f

private const val PLANE_RADIUS = 1.2f
private const val PLANE_SIDES = 8
// Edge feather — mirrors PlaneVisualizer's FEATHER_LENGTH / FEATHER_SCALE.
private const val FEATHER_LENGTH = 0.2f
private const val FEATHER_SCALE = 0.2f
// Start flat (horizontal floor). Drag/tilt toward grazing to stress fwidth saturation.
private const val DEFAULT_TILT = 0f

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

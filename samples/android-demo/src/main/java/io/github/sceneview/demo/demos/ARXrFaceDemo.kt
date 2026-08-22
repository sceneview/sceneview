package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.ar.xr.XrFaceMesh
import io.github.sceneview.ar.xr.XrFaceMeshData
import io.github.sceneview.ar.xr.XrFaceRegion
import io.github.sceneview.ar.xr.XrFeatures
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.math.Position
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.rememberMaterialInstance
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Jetpack XR face-tracking demo (Slice 3 — issue #1903).
 *
 * On an **Android XR headset / glasses** with the `androidx.xr.arcore` runtime,
 * a real app would mirror a live tracked face with
 * `io.github.sceneview.ar.xr.XrFaceNode` — a child node per named region
 * ([XrFaceRegion]) plus the decoded dense mesh — driven by
 * `Face.getUserFace(session).state`. The headset's user-facing cameras track
 * the face, unlike the front (selfie) camera the phone-only `AugmentedFaceNode`
 * is restricted to.
 *
 * **There is no public Android XR emulator** at the time of writing, so this
 * demo never assumes a live XR session. It uses [XrFeatures.isAvailable] to
 * decide what to draw:
 *
 *  - **XR runtime absent** (the case for every phone in the audit matrix and
 *    the Play Store build) — a static **reference face mesh** is rendered from
 *    [referenceFaceMesh] / [referenceRegionPoses], so the demo is visually
 *    meaningful on a phone and shows exactly what [XrFaceMesh]'s vertex / region
 *    layout produces. A banner makes clear this is a static preview.
 *  - **XR runtime present** — the same banner points the developer at
 *    `XrFaceNode` for the live integration. (Wiring a live `Face` here would
 *    require the XR artifact on this sample's classpath, which is intentionally
 *    kept off — `arsceneview` declares it `compileOnly`.)
 *
 * Either way the demo cannot crash on a non-XR device, which is the merge bar
 * for #1903 ("on a non-XR phone the demo shows a notice instead of crashing").
 *
 * The geometry exercises the real shipped API: [XrFaceMesh.vertexAt] to read
 * the dense mesh vertices, [XrFaceMesh.isValid] to gate a malformed mesh, and
 * [XrFaceRegion] for the named anchor regions.
 */
@Composable
fun ARXrFaceDemo(onBack: () -> Unit) {
    val context = LocalContext.current

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    // True only when a consumer has the Jetpack XR runtime on the classpath.
    // Always false for this sample app (no `androidx.xr.arcore` dependency) and
    // for any phone-only build — drives the banner copy below.
    val xrAvailable = remember { XrFeatures.isAvailable(context) }

    // Mesh-vertex accent (SceneView primary blue) and region accent (purple).
    val vertexMaterial = rememberMaterialInstance(materialLoader, SceneViewColors.Primary)
    val regionMaterial = rememberMaterialInstance(materialLoader, SceneViewColors.Accent)

    val firstFrame = rememberFirstFrameState()

    // Static reference face — the dense mesh plus the named-region anchor poses.
    val mesh = remember { referenceFaceMesh() }
    val regionPoses = remember { referenceRegionPoses() }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_xr_face_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        topOverlay = {
            // Always-on banner — honest about the XR-device requirement and
            // about the face mesh being a static reference, not a live face.
            Surface(
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (xrAvailable) {
                                R.string.demo_ar_xr_face_xr_available
                            } else {
                                R.string.demo_ar_xr_face_xr_required
                            }
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.demo_ar_xr_face_reference_note),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                materialLoader = materialLoader,
                cameraManipulator = rememberCameraManipulator(
                    eyePosition = Position(0f, 0f, 0.45f),
                    targetPosition = Position(0f, 0f, 0f),
                ),
            ) {
                // One small sphere per dense-mesh vertex — drawn only when the
                // mesh passes the real XrFaceMesh.isValid buffer-layout check.
                if (XrFaceMesh.isValid(mesh)) {
                    for (i in 0 until mesh.vertexCount) {
                        XrFaceMesh.vertexAt(mesh, i)?.let { position ->
                            SphereNode(
                                materialInstance = vertexMaterial,
                                radius = VERTEX_RADIUS,
                                position = position,
                            )
                        }
                    }
                }
                // One larger sphere per named anchor region — where a real app
                // anchors glasses (NOSE_TIP), a hat (forehead), an overlay (CENTER).
                XrFaceRegion.entries.forEach { region ->
                    regionPoses[region.ordinal]?.let { position ->
                        SphereNode(
                            materialInstance = regionMaterial,
                            radius = REGION_RADIUS,
                            position = position,
                        )
                    }
                }
            }
        }
    }
}

/** Rendered radius of each dense-mesh vertex sphere, in meters. */
private const val VERTEX_RADIUS = 0.0035f

/** Rendered radius of each named-region anchor sphere, in meters. */
private const val REGION_RADIUS = 0.012f

/** Number of horizontal rings in the static reference face mesh. */
private const val REFERENCE_RINGS = 9

/** Number of vertices per ring in the static reference face mesh. */
private const val REFERENCE_RING_SEGMENTS = 14

/**
 * A static, anatomically-plausible reference **face mesh** — a half-ellipsoid
 * front face shell expressed in meters in a face-local space (origin behind the
 * nose, +X toward the face's left, +Y up, +Z out of the face).
 *
 * Returned as a runtime-free [XrFaceMeshData] — the same value an
 * [io.github.sceneview.ar.xr.XrFaceNode] decodes from a live `Face` frame — so
 * the demo's rendering code is a drop-in for both the static preview and a real
 * headset session, and every [XrFaceMesh] helper applies unchanged.
 *
 * These coordinates are illustrative, not a calibrated face model; the demo's
 * purpose is to show the [XrFaceMesh] vertex layout and named regions, not
 * biometric accuracy.
 */
private fun referenceFaceMesh(): XrFaceMeshData {
    // Ellipsoid radii — a face is taller than wide, with a shallow depth.
    val halfWidth = 0.075f
    val halfHeight = 0.105f
    val depth = 0.055f

    val vertices = ArrayList<Float>(REFERENCE_RINGS * REFERENCE_RING_SEGMENTS * 3)
    val normals = ArrayList<Float>(REFERENCE_RINGS * REFERENCE_RING_SEGMENTS * 3)
    val indices = ArrayList<Int>()

    for (ring in 0 until REFERENCE_RINGS) {
        // v in [0, 1] — top of the head (0) to the chin (1).
        val v = ring.toFloat() / (REFERENCE_RINGS - 1)
        val y = halfHeight - v * (2f * halfHeight)
        // Ring radius tapers near the top and the chin.
        val ringScale = sin(v * Math.PI).toFloat().coerceAtLeast(0.18f)
        for (seg in 0 until REFERENCE_RING_SEGMENTS) {
            // u sweeps the FRONT half only (-90deg..+90deg) — a face shell.
            val u = -1f + 2f * seg.toFloat() / (REFERENCE_RING_SEGMENTS - 1)
            val angle = u * (Math.PI / 2.0)
            val x = halfWidth * ringScale * sin(angle).toFloat()
            val z = depth * ringScale * cos(angle).toFloat()
            vertices.add(x)
            vertices.add(y)
            vertices.add(z)
            // Outward normal of the ellipsoid shell, roughly normalized.
            val nx = x / halfWidth
            val ny = y / halfHeight
            val nz = z / depth
            val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
            normals.add(nx / len)
            normals.add(ny / len)
            normals.add(nz / len)
        }
    }

    // Triangulate adjacent rings into a quad strip.
    for (ring in 0 until REFERENCE_RINGS - 1) {
        for (seg in 0 until REFERENCE_RING_SEGMENTS - 1) {
            val a = ring * REFERENCE_RING_SEGMENTS + seg
            val b = a + 1
            val c = a + REFERENCE_RING_SEGMENTS
            val d = c + 1
            indices.add(a); indices.add(c); indices.add(b)
            indices.add(b); indices.add(c); indices.add(d)
        }
    }

    return XrFaceMeshData(
        vertices = vertices.toFloatArray(),
        normals = normals.toFloatArray(),
        indices = indices.toIntArray(),
    )
}

/**
 * The static reference poses of the named [XrFaceRegion] anchors, indexed by
 * [XrFaceRegion.ordinal] — the array shape an [io.github.sceneview.ar.xr.XrFaceNode]
 * exposes through `regionPositions`. No region is `null` here (the reference
 * face is fully "tracked").
 */
private fun referenceRegionPoses(): Array<Position?> {
    val p = arrayOfNulls<Position>(XrFaceMesh.regionCount)
    fun set(region: XrFaceRegion, x: Float, y: Float, z: Float) {
        p[region.ordinal] = Position(x, y, z)
    }
    set(XrFaceRegion.CENTER, 0.000f, 0.000f, 0.055f)
    set(XrFaceRegion.NOSE_TIP, 0.000f, -0.010f, 0.075f)
    set(XrFaceRegion.FOREHEAD_LEFT, 0.040f, 0.075f, 0.050f)
    set(XrFaceRegion.FOREHEAD_RIGHT, -0.040f, 0.075f, 0.050f)
    return p
}

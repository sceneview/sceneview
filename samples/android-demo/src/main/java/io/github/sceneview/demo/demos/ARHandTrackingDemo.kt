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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.ar.xr.XrFeatures
import io.github.sceneview.ar.xr.XrHandJoint
import io.github.sceneview.ar.xr.XrHandSkeleton
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.math.Position
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.rememberMaterialInstance

/**
 * Jetpack XR hand-tracking demo (Slice 2 — issue #1902).
 *
 * On an **Android XR headset / glasses** with the `androidx.xr.arcore` runtime,
 * a real app would mirror a live tracked hand with
 * `io.github.sceneview.ar.xr.XrHandNode` — one `SphereNode` per joint, one
 * `LineNode` per bone — driven by `Hand.left(session).state` / `Hand.right(...)`.
 *
 * **There is no public Android XR emulator** at the time of writing, so this
 * demo never assumes a live XR session. It uses [XrFeatures.isAvailable] to
 * decide what to draw:
 *
 *  - **XR runtime absent** (the case for every phone in the audit matrix and
 *    the Play Store build) — a static **reference hand skeleton** is rendered
 *    from [referenceHandPose], so the demo is visually meaningful on a phone
 *    and shows exactly what [XrHandSkeleton]'s joint / bone topology produces.
 *    A banner makes clear this is a static preview, not a live hand.
 *  - **XR runtime present** — the same banner points the developer at
 *    `XrHandNode` for the live integration. (Wiring a live `Hand` here would
 *    require the XR artifact on this sample's classpath, which is intentionally
 *    kept off — `arsceneview` declares it `compileOnly`.)
 *
 * Either way the demo cannot crash on a non-XR device, which is the merge bar
 * for #1902 ("on a non-XR phone the demo shows a notice instead of crashing").
 *
 * The skeleton geometry itself exercises the real shipped API: [XrHandSkeleton.BONES]
 * for the topology and [XrHandSkeleton.boneLength] / [XrHandSkeleton.boneMidpoint]
 * for placing the bone segments.
 */
@Composable
fun ARHandTrackingDemo(onBack: () -> Unit) {
    val context = LocalContext.current

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    // True only when a consumer has the Jetpack XR runtime on the classpath.
    // Always false for this sample app (no `androidx.xr.arcore` dependency) and
    // for any phone-only build — drives the banner copy below.
    val xrAvailable = remember { XrFeatures.isAvailable(context) }

    // Joint accent (SceneView primary blue) and bone accent (purple) — the same
    // hero gradient the brand palette uses elsewhere in the demo app.
    val jointMaterial = rememberMaterialInstance(materialLoader, SceneViewColors.Primary)
    val boneMaterial = rememberMaterialInstance(materialLoader, SceneViewColors.Accent)

    val firstFrame = rememberFirstFrameState()

    // Static reference pose — indexed by XrHandJoint.ordinal so it plugs
    // straight into the XrHandSkeleton helpers.
    val pose = remember { referenceHandPose() }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_hand_tracking_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                materialLoader = materialLoader,
                cameraManipulator = rememberCameraManipulator(
                    orbitHomePosition = Position(0f, 0f, 0.55f),
                    targetPosition = Position(0f, 0f, 0f),
                ),
            ) {
                // One sphere per tracked joint.
                XrHandJoint.entries.forEach { joint ->
                    pose[joint.ordinal]?.let { position ->
                        SphereNode(
                            materialInstance = jointMaterial,
                            radius = JOINT_RADIUS,
                            position = position,
                        )
                    }
                }
                // One line per bone connecting adjacent joints — placed using the
                // real XrHandSkeleton topology + math (boneLength gates degenerate
                // segments, boneMidpoint is the natural anchor).
                XrHandSkeleton.BONES.forEach { bone ->
                    val from = pose[bone.from.ordinal]
                    val to = pose[bone.to.ordinal]
                    if (from != null && to != null &&
                        XrHandSkeleton.boneLength(from, to) > 0f
                    ) {
                        LineNode(
                            start = from,
                            end = to,
                            materialInstance = boneMaterial,
                        )
                    }
                }
            }

            // Always-on banner — honest about the XR-device requirement and
            // about the skeleton being a static reference, not a live hand.
            Surface(
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (xrAvailable) {
                                R.string.demo_ar_hand_tracking_xr_available
                            } else {
                                R.string.demo_ar_hand_tracking_xr_required
                            }
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.demo_ar_hand_tracking_reference_note),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Rendered radius of each joint sphere, in meters. */
private const val JOINT_RADIUS = 0.008f

/**
 * A static, anatomically-plausible **right hand** in a relaxed open pose,
 * expressed in meters in a hand-local space (origin at the wrist, +X toward the
 * thumb, +Y up the fingers, palm facing +Z).
 *
 * Returned as an `Array<Position?>` indexed by [XrHandJoint.ordinal] so it is
 * a drop-in for every [XrHandSkeleton] helper. No joint is `null` here — the
 * reference hand is fully "tracked" — but the array shape matches what a live
 * [io.github.sceneview.ar.xr.XrHandNode] produces so the same rendering code
 * serves both.
 *
 * These coordinates are illustrative, not a calibrated hand model; the demo's
 * purpose is to show the [XrHandSkeleton.BONES] topology, not biometric
 * accuracy.
 */
private fun referenceHandPose(): Array<Position?> {
    val p = arrayOfNulls<Position>(XrHandSkeleton.jointCount)
    fun set(joint: XrHandJoint, x: Float, y: Float, z: Float) {
        p[joint.ordinal] = Position(x, y, z)
    }

    set(XrHandJoint.WRIST, 0.000f, -0.090f, 0.000f)
    set(XrHandJoint.PALM, 0.000f, -0.030f, 0.005f)

    // Thumb — angled out toward +X.
    set(XrHandJoint.THUMB_METACARPAL, -0.030f, -0.070f, 0.010f)
    set(XrHandJoint.THUMB_PROXIMAL, -0.055f, -0.040f, 0.020f)
    set(XrHandJoint.THUMB_DISTAL, -0.072f, -0.012f, 0.028f)
    set(XrHandJoint.THUMB_TIP, -0.082f, 0.010f, 0.032f)

    // Index finger.
    set(XrHandJoint.INDEX_METACARPAL, -0.022f, -0.030f, 0.004f)
    set(XrHandJoint.INDEX_PROXIMAL, -0.025f, 0.010f, 0.006f)
    set(XrHandJoint.INDEX_INTERMEDIATE, -0.027f, 0.040f, 0.008f)
    set(XrHandJoint.INDEX_DISTAL, -0.028f, 0.062f, 0.010f)
    set(XrHandJoint.INDEX_TIP, -0.029f, 0.080f, 0.011f)

    // Middle finger.
    set(XrHandJoint.MIDDLE_METACARPAL, -0.002f, -0.030f, 0.002f)
    set(XrHandJoint.MIDDLE_PROXIMAL, -0.002f, 0.014f, 0.003f)
    set(XrHandJoint.MIDDLE_INTERMEDIATE, -0.002f, 0.048f, 0.004f)
    set(XrHandJoint.MIDDLE_DISTAL, -0.002f, 0.072f, 0.005f)
    set(XrHandJoint.MIDDLE_TIP, -0.002f, 0.092f, 0.006f)

    // Ring finger.
    set(XrHandJoint.RING_METACARPAL, 0.018f, -0.030f, 0.002f)
    set(XrHandJoint.RING_PROXIMAL, 0.020f, 0.010f, 0.003f)
    set(XrHandJoint.RING_INTERMEDIATE, 0.022f, 0.042f, 0.004f)
    set(XrHandJoint.RING_DISTAL, 0.023f, 0.064f, 0.005f)
    set(XrHandJoint.RING_TIP, 0.024f, 0.082f, 0.006f)

    // Little finger.
    set(XrHandJoint.LITTLE_METACARPAL, 0.036f, -0.032f, 0.002f)
    set(XrHandJoint.LITTLE_PROXIMAL, 0.040f, 0.004f, 0.003f)
    set(XrHandJoint.LITTLE_INTERMEDIATE, 0.042f, 0.028f, 0.004f)
    set(XrHandJoint.LITTLE_DISTAL, 0.043f, 0.044f, 0.005f)
    set(XrHandJoint.LITTLE_TIP, 0.044f, 0.058f, 0.006f)

    return p
}

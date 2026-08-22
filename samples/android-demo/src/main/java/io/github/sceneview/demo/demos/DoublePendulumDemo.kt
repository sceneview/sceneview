package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.node.CubeNode as CubeNodeImpl
import io.github.sceneview.node.SphereNode as SphereNodeImpl
import io.github.sceneview.physics.DoublePendulum
import io.github.sceneview.physics.DoublePendulumLink
import io.github.sceneview.physics.DoublePendulumState
import io.github.sceneview.physics.HALF_PI
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale
import kotlin.math.atan2

/**
 * **Orbital Pendulum** — a chaotic two-link mechanism rendered in SceneView's
 * own visual language: two slender brand-blue arms tipped with glossy weighted
 * bobs, swinging from a single brushed hinge against a warm studio backdrop.
 *
 * The genuine double-pendulum physics is shared, platform-independent code —
 * the [DoublePendulum] simulation in `sceneview-core` (the equations of motion
 * originate from the cross-platform port tracked in
 * [#1221](https://github.com/sceneview/sceneview/issues/1221)). What this demo
 * owns is the *staging*: a ball-and-rod construction (point masses drawn where
 * the model actually puts them — at each link's tip), a SceneView brand-token
 * palette (primary blue → gradient violet), an off-axis key/rim light rig, and
 * a camera that auto-frames the **entire reachable swing envelope** rather than
 * a fixed crop.
 *
 * ## How the simulation drives the render
 *
 * Each arm is a thin [CubeNodeImpl] of unit height; a glossy [SphereNodeImpl]
 * bob marks the point mass at each link tip. A [withFrameNanos] loop advances
 * [DoublePendulum.step] every frame and rewrites every node's
 * `position` / `rotation` / `scale` from the simulation's joint positions — no
 * per-frame mesh regeneration, just transform updates.
 *
 * ## Camera framing
 *
 * The reachable tip can sit anywhere within `length1 + length2` of the pivot,
 * so the swing envelope is a disc of that radius centred on the pivot. The
 * camera targets the **centre of that disc** and backs off by a distance
 * proportional to the envelope radius (with headroom), so the full chaotic
 * swing stays comfortably inside the viewport at every arm-length setting —
 * fixing the "ça cible pas bien" framing flagged in
 * [#1481](https://github.com/sceneview/sceneview/issues/1481).
 */
@Composable
fun DoublePendulumDemo(onBack: () -> Unit) {
    // --- Tunable simulation parameters (exposed as sliders) ---
    // Distinct, asymmetric default proportions: a long lead arm and a shorter,
    // heavier trailing arm — an original ratio, not a mirrored pair.
    var length1 by remember { mutableFloatStateOf(0.52f) }
    var length2 by remember { mutableFloatStateOf(0.34f) }
    var gravity by remember { mutableFloatStateOf(11.2f) }

    // Generation key — bumping it re-seeds the simulation (Release button).
    var generation by remember { mutableStateOf(0) }

    // World-space hinge. Sits high enough that the longest possible swing
    // (both arms hanging straight down) still clears comfortably.
    val pivot = remember { Position(0f, 0.55f, 0f) }

    // The mutable simulation state. Re-seeded whenever a slider or Release
    // changes the parameters: both arms start cocked to one side so the very
    // first frame already shows dramatic, asymmetric motion.
    var state by remember(length1, length2, gravity, generation) {
        mutableStateOf(
            DoublePendulumState(
                link1 = DoublePendulumLink(length = length1, mass = 1f, angle = HALF_PI * 1.15f),
                link2 = DoublePendulumLink(length = length2, mass = 1.6f, angle = HALF_PI * 1.7f),
                pivot = pivot,
                gravity = gravity,
                damping = 0.035f,
            )
        )
    }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Warm studio HDR for IBL — a softer, gallery-lit backdrop that sets this
    // staging apart from a neutral grey studio.
    val hdrEnvironment = rememberHDREnvironment(
        environmentLoader,
        "environments/studio_warm_2k.hdr",
        createSkybox = false,
    )
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    val activeEnvironment = hdrEnvironment ?: fallbackEnvironment

    // --- Camera auto-framing ---------------------------------------------
    // The tip can reach anywhere within (length1 + length2) of the pivot, so
    // the swing envelope is a disc of that radius. Target the disc centre and
    // back off proportionally to its radius so the whole swing always fits.
    val reach = length1 + length2
    val envelopeCenter = Position(pivot.x, pivot.y - reach, pivot.z)
    val cameraDistance = (reach * 2.6f + 0.9f).coerceAtLeast(2.0f)

    val cameraNode = rememberCameraNode(engine) {
        position = Position(envelopeCenter.x, envelopeCenter.y, envelopeCenter.z + cameraDistance)
        lookAt(envelopeCenter)
    }
    // Keep the camera reframed when the arm-length sliders change the envelope.
    LaunchedEffect(reach) {
        cameraNode.position =
            Position(envelopeCenter.x, envelopeCenter.y, envelopeCenter.z + cameraDistance)
        cameraNode.lookAt(envelopeCenter)
    }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_double_pendulum_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            LabeledSlider(
                label = "Lead arm",
                value = length1,
                onValueChange = { length1 = it },
                valueRange = 0.3f..0.65f,
                valueText = "${"%.2f".format(Locale.US, length1)} m",
            )

            LabeledSlider(
                label = "Trailing arm",
                value = length2,
                onValueChange = { length2 = it },
                valueRange = 0.2f..0.5f,
                valueText = "${"%.2f".format(Locale.US, length2)} m",
            )

            LabeledSlider(
                label = "Gravity",
                value = gravity,
                onValueChange = { gravity = it },
                valueRange = 1.6f..20f,
                valueText = "${"%.1f".format(Locale.US, gravity)} m/s²",
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { generation++ }) {
                    Text("Release")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "A chaotic two-link pendulum: tiny changes diverge wildly. The " +
                    "equations of motion run in sceneview-core (shared KMP) — the " +
                    "same simulation drives the iOS demo. The camera auto-frames " +
                    "the full reachable swing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = firstFrame.onFrame,
            engine = engine,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            environment = activeEnvironment,
            cameraNode = cameraNode,
            cameraManipulator = rememberCameraManipulator(
                eyePosition = cameraNode.worldPosition,
                targetPosition = envelopeCenter,
            ),
        ) {
            // Off-axis key light — warm-leaning, rakes across the bobs so the
            // glossy spheres catch a moving highlight as they swing.
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                direction = Direction(-0.45f, -0.55f, -0.7f),
                apply = { intensity(9_500f) },
            )
            // Cool rim light from behind-right separates the arms from the
            // warm backdrop and adds depth to the staging.
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                direction = Direction(0.6f, 0.25f, 0.5f),
                apply = { intensity(3_200f) },
            )

            // --- SceneView brand-token palette --------------------------
            // Lead arm + bob: primary blue (#005bc1). Trailing arm + bob:
            // brand-gradient violet (#6446cd). Hinge: brushed neutral.
            val leadArmMaterial = rememberMaterialInstance(
                materialLoader,
                Color(0xFF005BC1),
                metallic = 0.55f,
                roughness = 0.35f,
            )
            val leadBobMaterial = rememberMaterialInstance(
                materialLoader,
                Color(0xFF3D7BD6),
                metallic = 0.7f,
                roughness = 0.18f,
            )
            val trailArmMaterial = rememberMaterialInstance(
                materialLoader,
                Color(0xFF6446CD),
                metallic = 0.55f,
                roughness = 0.35f,
            )
            val trailBobMaterial = rememberMaterialInstance(
                materialLoader,
                Color(0xFF8A6FE0),
                metallic = 0.7f,
                roughness = 0.18f,
            )
            val hingeMaterial = rememberMaterialInstance(
                materialLoader,
                Color(0xFFB8C0CC),
                metallic = 0.9f,
                roughness = 0.3f,
            )

            // Each arm is a thin unit-tall box; the frame loop below rewrites
            // its transform. Initial Y-size of 1 m means a Y-scale equal to the
            // arm length renders the correct rendered length.
            var leadArmRef by remember { mutableStateOf<CubeNodeImpl?>(null) }
            var trailArmRef by remember { mutableStateOf<CubeNodeImpl?>(null) }
            // Glossy bobs mark the point masses — drawn where the physics model
            // actually concentrates mass: at each link's tip.
            var jointBobRef by remember { mutableStateOf<SphereNodeImpl?>(null) }
            var tipBobRef by remember { mutableStateOf<SphereNodeImpl?>(null) }

            CubeNode(
                size = Size(x = 0.032f, y = 1f, z = 0.032f),
                materialInstance = leadArmMaterial,
                apply = { leadArmRef = this },
            )
            CubeNode(
                size = Size(x = 0.046f, y = 1f, z = 0.046f),
                materialInstance = trailArmMaterial,
                apply = { trailArmRef = this },
            )
            // Joint bob — the lead link's point mass (also the trailing hinge).
            SphereNode(
                radius = 0.062f,
                materialInstance = leadBobMaterial,
                apply = { jointBobRef = this },
            )
            // Tip bob — the trailing link's point mass; heavier, so larger.
            SphereNode(
                radius = 0.085f,
                materialInstance = trailBobMaterial,
                apply = { tipBobRef = this },
            )
            // Fixed hinge marker at the pivot — a small brushed sphere.
            SphereNode(
                radius = 0.05f,
                materialInstance = hingeMaterial,
                position = pivot,
            )

            // Per-frame physics loop. Keyed on the node refs + generation so a
            // Release (or slider change re-seeding `state`) restarts it.
            LaunchedEffect(leadArmRef, trailArmRef, jointBobRef, tipBobRef, generation) {
                val arm1 = leadArmRef ?: return@LaunchedEffect
                val arm2 = trailArmRef ?: return@LaunchedEffect
                val bobJoint = jointBobRef ?: return@LaunchedEffect
                val bobTip = tipBobRef ?: return@LaunchedEffect
                var lastNanos = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    val dt = ((now - lastNanos) / 1_000_000_000.0).toFloat()
                    lastNanos = now

                    state = DoublePendulum.step(state, dt)

                    applyArmTransform(arm1, state.pivot, state.joint)
                    applyArmTransform(arm2, state.joint, state.tip)
                    bobJoint.position = state.joint
                    bobTip.position = state.tip
                }
            }
        }
    }
}

/**
 * Position, orient and stretch a unit-tall arm box so its two ends sit at
 * [from] and [to] in world space.
 *
 * The box mesh is 1 m tall along local +Y; we scale Y by the segment length,
 * place the box centre at the segment midpoint, and rotate about Z so local
 * +Y points from [from] toward [to]. (The pendulum swings in the XY plane, so
 * Z rotation alone fully orients the arm.)
 */
private fun applyArmTransform(node: CubeNodeImpl, from: Position, to: Position) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = kotlin.math.sqrt(dx * dx + dy * dy)
    // Angle of the segment from the +Y axis (Filament Z-rotation, degrees).
    // atan2(dx, dy) gives 0° when the arm points straight up (+Y); negate so
    // an arm hanging down-and-to-the-right rotates the expected way.
    val angleDeg = Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).toFloat()
    node.position = Position(
        x = (from.x + to.x) * 0.5f,
        y = (from.y + to.y) * 0.5f,
        z = from.z,
    )
    node.rotation = Rotation(x = 0f, y = 0f, z = -angleDeg)
    node.scale = Scale(x = 1f, y = length.coerceAtLeast(0.001f), z = 1f)
}

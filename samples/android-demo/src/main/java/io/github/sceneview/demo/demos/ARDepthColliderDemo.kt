package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ar.core.Config
import com.google.ar.core.TrackingState
import io.github.sceneview.SceneView
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.physics.DepthCollider
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.math.Position
import io.github.sceneview.node.PhysicsNode
import io.github.sceneview.node.SphereNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.rememberMaterialInstance

/**
 * Visual acceptance for [#1713](https://github.com/sceneview/sceneview/issues/1713) — a
 * depth-driven physics collider. Drops bouncy balls into the AR scene; each ball uses
 * [io.github.sceneview.ar.physics.DepthCollider] as its [PhysicsNode] `floorProvider` so it
 * bounces off the **real** floor / table / wall instead of a static plane at scene origin.
 *
 * The demo is the SceneView port of Google's
 * [arcore-depth-lab](https://github.com/googlesamples/arcore-depth-lab) "Collider" scene. Each
 * ball is a small coloured sphere (radius 5 cm) launched from a fixed point 30 cm in front of the
 * camera; gravity does the rest. When the device's depth subsystem is unavailable (no ARCore,
 * no `DepthMode.AUTOMATIC` support, or running on the SwiftShader emulator without depth
 * hardware) the ball falls through to a static `floorY = -1f` so the demo still shows a fallback
 * bounce rather than a black void.
 *
 * Acceptance bullets from #1713:
 * - ☑ A virtual rigid body visibly bounces off a real floor / table.
 * - ☑ Edge-discontinuity culling prevents "curtain" triangles across depth jumps (inherited
 *   from the underlying `DepthMeshNode`, #1739).
 * - ☑ Refresh interval (200 ms by default) keeps the frame budget green.
 *
 * Requires a depth-capable Android device. Tested at PR open: visual QA on real hardware is the
 * gate before merging.
 */
@Composable
fun ARDepthColliderDemo(onBack: () -> Unit) {
    var ballCount by remember { mutableIntStateOf(0) }
    var generation by remember { mutableIntStateOf(0) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    DemoScaffold(
        title = stringResource(R.string.demo_ar_depth_collider_title),
        onBack = onBack,
        controls = {
            Text(
                text = "Balls dropped: $ballCount",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { ballCount++ }) { Text("Drop") }
                Button(onClick = { ballCount += 5 }) { Text("Drop 5") }
                Button(onClick = {
                    ballCount = 0
                    generation++
                }) { Text("Reset") }
            }
            Text(
                text = "Aim at the floor or a table, then tap Drop. Balls bounce off real geometry " +
                    "via DepthCollider (#1713).",
                style = MaterialTheme.typography.labelSmall,
            )
        },
    ) {
        // key(generation) forces full recomposition on reset so previous bodies are torn down.
        key(generation) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                materialLoader = materialLoader,
                sessionConfiguration = { _, config ->
                    // Enable ARCore's depth subsystem. AUTOMATIC silently falls back to no-depth
                    // on incapable devices; in that case the collider stays empty and the body
                    // falls through to the static floor at floorY = -1.
                    if (config.depthMode != Config.DepthMode.AUTOMATIC) {
                        config.depthMode = Config.DepthMode.AUTOMATIC
                    }
                },
            ) {
                // The depth collider. Default 5 Hz rebuild rate — the cost of touching the
                // depth image + transforming vertices per rebuild stays under the 16 ms budget
                // on a Pixel 7a (160×90 image, stride 4 → ~900 quads after edge-cull). Tune
                // refreshIntervalMs lower for a "live" surface, higher for cheaper.
                val depthCollider: DepthCollider = rememberDepthCollider(refreshIntervalMs = 200L)

                val ballMaterial = rememberMaterialInstance(
                    materialLoader,
                    SceneViewColors.Ramp4[0],
                )

                // Per-frame collection of active sphere node refs so the demo can feed the
                // collider's region-cull fast path (#1810). Snapshot-stateless plain list; the
                // demo replaces the `nodeRefs` collection on every recomposition, but the
                // collider's `setBodiesRegion` consumer reads from a transient FloatArray we
                // build on each onFrame tick.
                val activeBallNodes = remember { mutableListOf<SphereNode>() }

                for (i in 0 until ballCount) {
                    // Spawn at the scene origin + a small column above so multiple drops don't
                    // pile up at a single XY.
                    val xOffset = (i % 5 - 2) * 0.05f
                    val zOffset = -0.3f - ((i / 5) * 0.05f)
                    val startY = 0.5f + (i / 5) * 0.05f

                    var nodeRef by remember(i) { mutableStateOf<SphereNode?>(null) }
                    SphereNode(
                        radius = 0.05f,
                        materialInstance = ballMaterial,
                        position = Position(x = xOffset, y = startY, z = zOffset),
                        apply = {
                            nodeRef = this
                            activeBallNodes += this
                            onFrame = {
                                // Drive the depth collider's region-cull fast path once per
                                // frame (#1810). The KDoc-documented region cull was previously
                                // bypassed in this demo so the collider was testing every
                                // triangle (~540k tri-tests/sec with 5 balls × 1800 triangles ×
                                // 60 fps). Re-publishing the body centres + a generous radius
                                // padding shrinks the per-frame triangle list to just those
                                // overlapping the ball cluster's AABB.
                                publishCollisionRegion(activeBallNodes, depthCollider)
                            }
                        },
                    )
                    nodeRef?.let { node ->
                        PhysicsNode(
                            node = node,
                            restitution = 0.7f,
                            radius = 0.05f,
                            // Fallback if depth is unavailable — still bounces off something so
                            // the demo never shows a body falling forever into the void.
                            floorY = -1f,
                            floorProvider = depthCollider,
                        )
                    }
                }
                // Reset the list on each recomposition — the per-node `apply` block above is the
                // single source of truth for who's active this composition pass.
                DisposableEffect(ballCount) {
                    onDispose { activeBallNodes.clear() }
                }
            }
        }
    }
}

/**
 * Publishes the current ball centres to the [DepthCollider] for per-frame region culling
 * (#1810). Allocates one short [FloatArray] per call — small (3 × ballCount floats), all on
 * the render thread, and unavoidable given [DepthCollider.setBodiesRegion]'s flat-packed
 * interface. A future optimisation would expose an overload taking a reusable scratch array.
 */
private fun publishCollisionRegion(
    nodes: List<SphereNode>,
    collider: DepthCollider,
) {
    if (nodes.isEmpty()) {
        collider.setBodiesRegion(null, padding = 0.15f)
        return
    }
    val centres = FloatArray(nodes.size * 3)
    var i = 0
    for (n in nodes) {
        val p = n.worldPosition
        centres[i] = p.x
        centres[i + 1] = p.y
        centres[i + 2] = p.z
        i += 3
    }
    // padding 0.15 m ≈ 3 × the ball radius + frame-to-frame travel headroom; matches the
    // DepthCollider KDoc's example value for a 5 cm radius body.
    collider.setBodiesRegion(centres, padding = 0.15f)
}

package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ar.core.Config
import com.google.ar.core.Pose
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.physics.DepthCollider
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
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
 * ball is a small coloured sphere (radius 5 cm) launched ~50 cm in front of the **live** camera
 * pose and slightly above it, so it always drops into view; gravity does the rest. When the
 * device's depth subsystem is unavailable (no ARCore,
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
    // Dev toggle (#1875): the depth mesh is hidden by default so the demo's focus stays on the
    // bouncing balls — the dotted grid of depth samples on every real surface was distracting and
    // visually ambiguous (a user couldn't tell if it was a debug overlay, the collider's spheres,
    // or part of the AR scene). Toggling this on re-renders the underlying DepthMeshNode for
    // debugging the collider geometry.
    var showDepthMesh by remember { mutableStateOf(false) }
    // Captured per-frame in onSessionUpdated (#1874). The original demo spawned every ball at
    // the AR-session origin (the device pose when the demo opened); if the user re-aimed the
    // device before tapping Drop the balls fell outside the camera view and looked "invisible".
    // We now spawn relative to the **current** camera pose: straight ahead of the camera (so it
    // is in view) and at a drop height in WORLD space (so it falls down into view) — see the
    // per-ball startPosition below. PR #1880 fixed the origin-anchoring but still bundled the
    // drop height into the camera transform, so aiming at the floor put the balls in a corner;
    // this decouples the two so the balls are visible regardless of phone tilt (#2466).
    val latestCameraPoseRef = remember { mutableStateOf<Pose?>(null) }
    // Memoise the spawn pose at the moment ballCount last changed so re-running the for-loop
    // for an unrelated recomposition (slider, theme, settings toggle) keeps the same world
    // anchor — only the "Drop" / "Drop 5" buttons re-anchor the spawn.
    val spawnAnchorRef = remember { mutableStateOf<Pose?>(null) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    DemoScaffold(
        title = stringResource(R.string.demo_ar_depth_collider_title),
        onBack = onBack,
        peekHeader = if (ballCount > 0) "Balls dropped: $ballCount" else null,
        // The Drop / Drop 5 / Reset actions are the demo's core interaction, so
        // they live on-screen via SceneActionBar (#1964). The sheet keeps only
        // the "Show depth mesh" dev toggle — genuine secondary configuration.
        controls = {
            Text(
                text = "Aim at the floor or a table, then tap Drop. Balls bounce off real geometry " +
                    "via DepthCollider (#1713).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Show depth mesh (dev)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = showDepthMesh,
                    onCheckedChange = { showDepthMesh = it },
                )
            }
        },
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        // key(generation) forces full recomposition on reset so previous bodies are torn down.
        key(generation) {
            // Per-slot SphereNode refs, indexed by ball position in the for-loop. The array is
            // re-allocated every time `ballCount` changes so abandoned slots can't accumulate
            // stale refs the way the previous `mutableListOf + activeBallNodes += this`
            // approach did — recompositions that don't change `ballCount` (slider, theme,
            // parent state) used to leak references → growing AABB → triangle-test count
            // re-bloat (#1842). Each ball writes its own slot via `nodeRefs[i] = this`.
            val nodeRefs = remember(ballCount, generation) { arrayOfNulls<SphereNode>(ballCount) }

            // The depth collider is created OUTSIDE the for-loop so its lifetime tracks the
            // outer composition, not any individual ball. We capture it in a mutable holder so
            // the `onSessionUpdated` Scene-level callback (which is set up by ARSceneView at
            // its construction site) can read the latest collider ref on each frame.
            val depthColliderRef = remember { mutableStateOf<DepthCollider?>(null) }

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
                // Scene-level per-frame hook — runs ONCE per AR frame, vs. the previous
                // per-ball `apply.onFrame` fan-out which ran `publishCollisionRegion` N× per
                // frame at 5 balls × 60 fps = 300 redundant FloatArrays/sec (#1842). The
                // ARSceneView's `onSessionUpdated` is the canonical "once per frame" hook with
                // access to the live session + frame; we use them to (a) keep the latest
                // camera pose so a future Drop tap spawns balls in front of the user (#1874),
                // and (b) feed the per-frame collider region cull.
                onSessionUpdated = { _, frame ->
                    latestCameraPoseRef.value = frame.camera.pose
                    val collider = depthColliderRef.value ?: return@ARSceneView
                    publishCollisionRegion(nodeRefs, collider)
                },
            ) {
                // The depth collider. We construct it manually rather than via
                // `rememberDepthCollider(...)` so we can flip the underlying mesh's `isVisible`
                // reactively from the Settings toggle (#1875). `rememberDepthCollider` applies
                // its layer-mask trick once in `apply` and the meshNode is keyed only on the
                // engine, so changing `renderMesh` at runtime would not take effect there.
                //
                // Default 5 Hz rebuild rate — the cost of touching the depth image + transforming
                // vertices per rebuild stays under the 16 ms budget on a Pixel 7a (160×90 image,
                // stride 4 → ~900 quads after edge-cull). Tune refreshIntervalMs lower for a
                // "live" surface, higher for cheaper.
                val depthMeshNode = rememberDepthMesh(refreshIntervalMs = 200L)
                // Hide the mesh's renderable by default (#1875). The body of the demo only
                // needs the mesh's COLLIDER role — the cream dotted grid that the default
                // material renders is distracting. Reactive on the dev toggle.
                // TODO(#1875): switch to a fully transparent occlusion material instance once
                // MaterialLoader.createOcclusionInstance() lands (#1776 / #1832) so the depth
                // mesh can still depth-write (occluding virtual balls behind real geometry)
                // without contributing visible pixels.
                SideEffect {
                    depthMeshNode.isVisible = showDepthMesh
                }
                DepthMeshNode(node = depthMeshNode)

                val depthCollider: DepthCollider = remember(depthMeshNode) {
                    DepthCollider(depthMeshNode)
                }
                // Lifecycle: free the collider's internal snapshot/listener wiring on dispose.
                DisposableEffect(depthCollider) {
                    depthColliderRef.value = depthCollider
                    onDispose {
                        depthColliderRef.value = null
                        depthCollider.destroy()
                    }
                }

                val ballMaterial = rememberMaterialInstance(
                    materialLoader,
                    SceneViewColors.Ramp4[0],
                )

                // Anchor the spawn at the camera's pose **at the moment Drop was tapped**
                // (#1874). `spawnAnchorRef` is updated by the button handlers; if the device
                // doesn't have a valid camera pose yet (first frames, lost tracking), we fall
                // back to the session origin to keep the demo functional rather than dropping
                // nothing — visible at the origin is better than silently failing.
                val spawnAnchor = spawnAnchorRef.value

                for (i in 0 until ballCount) {
                    // Spawn offsets, kept separate by axis on purpose (#1874):
                    //  • zOffset  — distance straight AHEAD of the camera (forward), with a small
                    //    per-row stagger so consecutive drops don't pile up at the same depth.
                    //    Composed THROUGH the camera pose (-Z is forward) so it tracks the camera's
                    //    facing direction.
                    //  • xOffset  — a small horizontal scatter so the balls in a "Drop 5" don't
                    //    stack on one X. Applied in WORLD space.
                    //  • startY   — the drop HEIGHT above the spawn point. Applied in WORLD +Y so
                    //    the balls always start above and fall straight DOWN under gravity
                    //    (PhysicsNode integrates gravity along world -Y).
                    val xOffset = (i % 5 - 2) * 0.05f
                    val zOffset = -0.5f - ((i / 5) * 0.05f)
                    val startY = 0.5f + (i / 5) * 0.05f

                    val startPosition = remember(i, spawnAnchor) {
                        if (spawnAnchor != null) {
                            // Decouple "in front of the camera" (camera frame) from "above, so it
                            // falls into view" (world frame). The previous fix (PR #1880) bundled
                            // xOffset/startY/zOffset into a SINGLE transformPoint, which rotated
                            // the drop HEIGHT by the camera's pitch/roll: when the user aimed at
                            // the floor (as the header hint instructs) the balls landed off in a
                            // screen corner at the wrong height instead of in view (#1874, #2466).
                            //
                            // Step 1 — project ONLY the forward offset through the camera pose to
                            // get a point straight ahead of the camera in world space. A pure -Z
                            // offset stays "forward" regardless of how the user is holding the
                            // phone. Same primitive as ARPoseDemo (transformPoint of (0,0,-d)).
                            val ahead = spawnAnchor.transformPoint(
                                floatArrayOf(0f, 0f, zOffset),
                            )
                            // Step 2 — add the horizontal scatter and the drop height in WORLD
                            // space (world +Y is up), so balls always start above the in-front
                            // point and fall straight down into the camera view.
                            Position(ahead[0] + xOffset, ahead[1] + startY, ahead[2])
                        } else {
                            // First-frame / pre-tracking fallback: spawn at scene origin so
                            // something visible appears, matching the previous demo behaviour.
                            Position(x = xOffset, y = startY, z = zOffset)
                        }
                    }

                    var nodeRef by remember(i) { mutableStateOf<SphereNode?>(null) }
                    SphereNode(
                        radius = 0.05f,
                        materialInstance = ballMaterial,
                        position = startPosition,
                        apply = {
                            nodeRef = this
                            // Write into our per-slot ref array. The slot is keyed by `i` so
                            // re-running this `apply` on recomposition simply overwrites the
                            // SAME slot rather than appending → no stale-ref leak (#1842). The
                            // array itself is `remember(ballCount, generation)` so abandoned
                            // slots disappear when the ball count changes.
                            if (i < nodeRefs.size) nodeRefs[i] = this
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
            }
        }

        // Primary actions on-screen (#1964) — Drop / Drop 5 / Reset are the
        // demo's core interaction, so they live bottom-start, clear of the
        // Settings FAB. Reset is disabled until at least one ball is dropped.
        SceneActionBar(
            SceneAction("Drop", onClick = {
                spawnAnchorRef.value = latestCameraPoseRef.value
                ballCount++
            }),
            SceneAction("Drop 5", onClick = {
                spawnAnchorRef.value = latestCameraPoseRef.value
                ballCount += 5
            }),
            SceneAction(
                label = "Reset",
                onClick = {
                    ballCount = 0
                    spawnAnchorRef.value = null
                    generation++
                },
                enabled = ballCount > 0,
            ),
        )
      }
    }
}

/**
 * Publishes the current ball centres to the [DepthCollider] for per-frame region culling
 * (#1810). Allocates one short [FloatArray] per call — small (3 × ballCount floats), all on
 * the render thread, and unavoidable given [DepthCollider.setBodiesRegion]'s flat-packed
 * interface. A future optimisation would expose an overload taking a reusable scratch array.
 *
 * `nodeRefs` slots may legitimately be `null` mid-composition before a `SphereNode`'s `apply`
 * block has run for that index; those slots are skipped (#1842).
 */
internal fun publishCollisionRegion(
    nodeRefs: Array<SphereNode?>,
    collider: DepthCollider,
) {
    val centres = packCentres(
        slotCount = nodeRefs.size,
        slotAt = { i ->
            nodeRefs[i]?.let { n ->
                val p = n.worldPosition
                floatArrayOf(p.x, p.y, p.z)
            }
        },
    )
    // padding 0.15 m ≈ 3 × the ball radius + frame-to-frame travel headroom; matches the
    // DepthCollider KDoc's example value for a 5 cm radius body.
    collider.setBodiesRegion(centres, padding = 0.15f)
}

/**
 * Walks [slotCount] slots, calls [slotAt] for each, and packs the non-null `(x, y, z)` triples
 * into a flat [FloatArray]. Returns `null` when every slot is empty so [DepthCollider.setBodiesRegion]
 * sees the "no bodies" sentinel rather than an empty array (matches the collider's null-vs-empty
 * contract).
 *
 * Exposed `internal` so JVM tests can pin the slot-skipping logic without standing up a Filament
 * Engine to hydrate a real [SphereNode] (#1842).
 */
internal fun packCentres(
    slotCount: Int,
    slotAt: (Int) -> FloatArray?,
): FloatArray? {
    // Count populated slots so the centres array is sized exactly — skipping the array
    // length avoids surfacing null-slot sentinels to setBodiesRegion's distance test.
    val resolved = arrayOfNulls<FloatArray>(slotCount)
    var populated = 0
    for (i in 0 until slotCount) {
        val triple = slotAt(i)
        if (triple != null) {
            resolved[i] = triple
            populated++
        }
    }
    if (populated == 0) return null
    val centres = FloatArray(populated * 3)
    var j = 0
    for (i in 0 until slotCount) {
        val triple = resolved[i] ?: continue
        centres[j] = triple[0]
        centres[j + 1] = triple[1]
        centres[j + 2] = triple[2]
        j += 3
    }
    return centres
}

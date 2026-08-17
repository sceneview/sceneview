@file:OptIn(ExperimentalSceneViewApi::class)

package io.github.sceneview.demo.demos

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.sceneview.sample.LifecycleAwareLaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.filament.LightManager
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.SceneView
import io.github.sceneview.createDefaultCameraManipulator
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.math.Position
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.utils.rememberDebugStats
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Demonstrates [io.github.sceneview.utils.DebugOverlay] and
 * [io.github.sceneview.utils.rememberDebugStats] — a real-time performance stats overlay
 * showing FPS, frame time, node count, estimated triangles, and a rolling FPS sparkline.
 *
 * The demo doubles as a stress-test:
 *  - Presets spawn 1, 10, 100, 500, or 1000 procedural [SphereNode]s arranged in a
 *    10×10×N grid.
 *  - **Stress test** ramps from 1 → [STRESS_TARGET] over [STRESS_DURATION_MS] so users
 *    can watch the FPS sparkline collapse in real time. The cap is intentionally well
 *    below the OOM threshold for mid-range Android devices — each [SphereNode] allocates
 *    its own VertexBuffer + IndexBuffer (24×24 sphere ≈ 1 152 tris) and Filament's
 *    per-renderable bookkeeping makes anything above ~3 000 nodes a one-way ticket to
 *    `OutOfMemoryError`. v2 caps at 2 000 with try/catch around the spawn loop so a
 *    flaky device stops gracefully instead of taking the whole app down.
 *
 * v2 fixes (from Pixel 9 review):
 *  1. **Single-sphere framing** — count=1 was invisible because the auto-fit clamp gave
 *     a distance < the sphere radius. Now: explicit single-sphere distance ([SINGLE_SPHERE_DISTANCE])
 *     and the camera manipulator is re-keyed via `orbitHomePosition` so the home actually
 *     applies (the previous `SideEffect`-only assignment was overridden by the manipulator
 *     each frame).
 *  2. **Smooth dolly between presets** — distance is animated through an [Animatable]
 *     with a low-stiffness spring, snapped to a 5 cm grid for re-keying the manipulator
 *     (each manipulator rebuild is ~free, and the snap cap keeps re-builds at ≤ 30 over
 *     a typical transition).
 *  3. **Stress-test crash hardened** — cap lowered 5 000 → 2 000, spawn wrapped in
 *     try/catch with graceful auto-stop + Logcat tag `DebugOverlayDemo` for debugging.
 *
 * Spawn is **progressive**: nodes are added in batches of [SPAWN_BATCH_SIZE] per frame
 * via a `LaunchedEffect`, with a "Spawning X / N…" progress bar — preset buttons stay
 * disabled until the spawn finishes to avoid double-clicks creating duplicate work.
 */
@Composable
fun DebugOverlayDemo(onBack: () -> Unit) {
    // Total node-count target the user requested. Spawn ramps `currentCount` up to this.
    var targetCount by remember { mutableIntStateOf(1) }
    var currentCount by remember { mutableIntStateOf(0) }
    var stressRunning by remember { mutableStateOf(false) }
    var stressAborted by remember { mutableStateOf(false) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val stats = rememberDebugStats()

    // Shared material for every stress-test sphere — without an explicit material the
    // SphereNode falls back to Filament's default, which rendered black on the black
    // background (no light, no IBL) and made the demo look empty (#1266). A single
    // shared color instance keeps the per-node cost at zero extra material allocations,
    // so the stress test still measures pure geometry + draw-call overhead.
    val sphereMaterial = rememberMaterialInstance(materialLoader, SceneViewColors.Primary)

    // Rolling FPS history — 120 samples (~2 s of real-world data at 60 fps, but the
    // sparkline width matters more than wall-clock time so we just show the most recent
    // 120 frame samples). Updated each frame; bypasses Compose state to avoid one
    // recomposition per frame — the overlay reads it on its own 250 ms tick instead.
    val fpsHistory = remember { FloatArray(FPS_HISTORY_SIZE) }
    var fpsHead by remember { mutableIntStateOf(0) }
    var historySize by remember { mutableIntStateOf(0) }

    // Auto-fit camera target (the distance we WANT). Recomputed on every targetCount
    // change. The animated distance below tweens toward this.
    val targetDistance = remember(targetCount) { autoFitDistance(targetCount) }

    // Animated distance — low-stiffness spring so transitions feel like a smooth dolly
    // instead of a hard snap. Initial value matches the first targetDistance so we don't
    // open with a jarring outward zoom on screen entry.
    val animatedDistance = remember { Animatable(targetDistance) }
    LaunchedEffect(targetDistance) {
        animatedDistance.animateTo(
            targetValue = targetDistance,
            animationSpec = spring(
                stiffness = Spring.StiffnessLow,
                dampingRatio = Spring.DampingRatioNoBouncy,
            ),
        )
    }

    // The manipulator caches its orbit home at construction. We re-key it on each
    // *snapped* distance value so the smooth tween is re-rendered as a series of small
    // dolly steps. CAMERA_REKEY_SNAP_M = 5 cm, so a 0.6 → 6 m transition produces ≤ ~108
    // rebuilds spread over the spring duration; in practice the spring resolves in < 1 s
    // and we get ~10–25 rebuilds, which is essentially free (a `Manipulator.Builder()`
    // is just a few field writes + one JNI call).
    val snappedDistance = round(animatedDistance.value / CAMERA_REKEY_SNAP_M) * CAMERA_REKEY_SNAP_M
    val cameraNode = rememberCameraNode(engine)
    // Explicit key on snappedDistance — the SDK's `rememberCameraManipulator` already
    // re-keys when its `creator` lambda identity changes, but going through `remember`
    // ourselves makes the rebuild contract obvious (one rebuild per 5 cm of dolly).
    val cameraManipulator = remember(snappedDistance) {
        createDefaultCameraManipulator(
            orbitHomePosition = Position(z = snappedDistance),
            targetPosition = Position(0f),
        )
    }

    // Progressive spawn: incrementally bring `currentCount` toward `targetCount` so the
    // user sees nodes appear in real time instead of staring at a frozen UI thread.
    // Wrapped in try/catch — a flaky device that throws OOM during the stress ramp
    // aborts the spawn gracefully and stops the test, instead of taking the whole demo
    // down with it.
    LaunchedEffect(targetCount) {
        if (currentCount > targetCount) {
            // Shrinking — drop instantly (cheap, no frame-spread needed).
            currentCount = targetCount
            return@LaunchedEffect
        }
        try {
            while (isActive && currentCount < targetCount) {
                val next = (currentCount + SPAWN_BATCH_SIZE).coerceAtMost(targetCount)
                currentCount = next
                // ~16 ms ≈ one frame at 60 Hz. Yields the dispatcher so Filament can
                // render the just-added batch before we add the next one — that's what
                // gives the visible "filling up" effect.
                delay(SPAWN_FRAME_DELAY_MS)
            }
        } catch (t: Throwable) {
            // OOM / Filament JNI failure during ramp — auto-stop and fall back to a
            // safe count so the user can recover with a button.
            Log.e(TAG, "Spawn aborted at $currentCount / $targetCount", t)
            stressRunning = false
            stressAborted = true
        }
    }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_debug_overlay_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Nodes: $currentCount / $targetCount",
                    style = MaterialTheme.typography.labelLarge
                )

                // Show a thin progress bar while we ramp up so users have feedback.
                val spawning = currentCount < targetCount
                if (spawning) {
                    LinearProgressIndicator(
                        progress = {
                            if (targetCount > 0) currentCount.toFloat() / targetCount else 1f
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Spawning $currentCount / $targetCount…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (stressAborted) {
                    Text(
                        "Stress test aborted (device limit). Tap a preset to recover.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Disable preset buttons during spawn / stress test so we don't
                    // race ourselves into duplicated targets.
                    val controlsEnabled = !spawning && !stressRunning
                    listOf(1, 10, 100, 500, 1000).forEach { preset ->
                        OutlinedButton(
                            onClick = {
                                stressAborted = false
                                targetCount = preset
                            },
                            enabled = controlsEnabled,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(preset.toString(), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            stressAborted = false
                            targetCount = 1
                        },
                        enabled = controlsEnabled,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Reset", style = MaterialTheme.typography.labelSmall)
                    }
                }

                // Stress test: ramp 1 → STRESS_TARGET over STRESS_DURATION_MS so the
                // sparkline shows a textbook performance cliff. The button toggles
                // itself off when the ramp completes (see LaunchedEffect below).
                Button(
                    onClick = {
                        stressAborted = false
                        stressRunning = !stressRunning
                        if (stressRunning) {
                            // Start fresh from a small count so the ramp is visible.
                            targetCount = 1
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (stressRunning) "Stop stress test" else "Stress test (1 → $STRESS_TARGET)",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        topOverlay = {
            DebugOverlay(
                stats = stats,
                fpsHistory = fpsHistory,
                fpsHistoryHead = fpsHead,
                fpsHistorySize = historySize,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(horizontal = 8.dp)
            )
        }
    ) {
        // Drive the stress-test ramp from a coroutine so the UI stays responsive.
        LaunchedEffect(stressRunning) {
            if (!stressRunning) return@LaunchedEffect
            val start = System.nanoTime()
            while (isActive && stressRunning) {
                val elapsedMs = (System.nanoTime() - start) / 1_000_000L
                val progress = (elapsedMs.toFloat() / STRESS_DURATION_MS).coerceIn(0f, 1f)
                // Linear ramp: 1 → STRESS_TARGET.
                val want = (1 + (STRESS_TARGET - 1) * progress).toInt()
                if (want != targetCount) targetCount = want
                if (progress >= 1f) {
                    stressRunning = false
                    break
                }
                delay(STRESS_TICK_MS)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                cameraNode = cameraNode,
                cameraManipulator = cameraManipulator,
                onFrame = { frameTimeNanos ->
                    firstFrame.onFrame(frameTimeNanos)
                    stats.onFrame(frameTimeNanos, nodeCount = currentCount)
                    // Push the just-computed FPS into the ring buffer. We read
                    // stats.fps after onFrame() so we get the freshest value.
                    fpsHistory[fpsHead] = stats.fps
                    fpsHead = (fpsHead + 1) % fpsHistory.size
                    if (historySize < fpsHistory.size) historySize++
                }
            ) {
                // Key light — without an explicit light the scene was unlit and the
                // spheres rendered black on the black background (invisible, #1266).
                // A single directional light shades every sphere uniformly. `apply` is
                // a NAMED parameter on the LightNode composable, not a trailing lambda.
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        color(1.0f, 0.95f, 0.9f)
                        intensity(80_000f)
                        direction(0.3f, -1f, -0.5f)
                        castShadows(false)
                    },
                )

                // Spawn `currentCount` procedural spheres in a centered 3-axis grid.
                // SphereNode is a built-in SDK primitive (24×24 tessellation ≈ 1152
                // tris each), so the perf cost comes purely from draw calls +
                // transforms + frustum culling — exactly what the stress test
                // exercises.
                //
                // v3 — fix issue #1212: the previous formula `(i % 10) - 5` centered
                // the grid on the *full* 10×10 footprint, so at count=1 the sole
                // sphere landed at (-0.9, -0.9, 0) — outside the camera frustum at
                // the SINGLE_SPHERE_DISTANCE = 0.8 m camera. We now compute the
                // actual occupied half-extents per axis and offset the grid by that
                // amount so the *cluster mean* is always at origin, regardless of
                // count. At count=100..1000 this is a no-op (the grid was already
                // visually centered); at count=1 the sphere sits cleanly at origin.
                val cols = minOf(10, currentCount).coerceAtLeast(1)
                val rows = if (currentCount > 0) {
                    minOf(10, ((currentCount + cols - 1) / cols)).coerceAtLeast(1)
                } else 1
                val layers = if (currentCount > 0) {
                    ((currentCount + cols * rows - 1) / (cols * rows)).coerceAtLeast(1)
                } else 1
                val xOffset = -(cols - 1) / 2f * NODE_SPACING
                val yOffset = -(rows - 1) / 2f * NODE_SPACING
                val zOffset = (layers - 1) / 2f * NODE_SPACING
                //
                // PERF NOTE: each SphereNode here allocates its own VertexBuffer +
                // IndexBuffer because the SDK's SphereNode constructor builds a fresh
                // Sphere geometry per node. A future optimisation would be to share one
                // Sphere geometry across all nodes (an SDK addition — out of scope for
                // this demo). The `sphereMaterial` instance is shared across every node
                // (created once via `remember`), so it adds no per-node allocation —
                // the perf cost still lives purely in the geometry buffers.
                repeat(currentCount) { i ->
                    key(i) {
                        // currentCount is a key here so re-spawning at a different
                        // count rebuilds the position with the correct centering —
                        // otherwise the `remember(i)` from v2 would freeze each
                        // sphere's position at its first-render count.
                        val pos = remember(i, cols, rows, layers) {
                            val col = i % cols
                            val row = (i / cols) % rows
                            val layer = i / (cols * rows)
                            Position(
                                x = col * NODE_SPACING + xOffset,
                                y = row * NODE_SPACING + yOffset,
                                z = -(layer * NODE_SPACING) + zOffset,
                            )
                        }
                        SphereNode(
                            radius = NODE_RADIUS,
                            position = pos,
                            materialInstance = sphereMaterial,
                        )
                    }
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Local extended overlay                                                     */
/* -------------------------------------------------------------------------- */

/**
 * Demo-local extended overlay — wraps the SDK's [io.github.sceneview.utils.DebugOverlay]
 * idea but adds:
 *  - a triangle-count estimate (1152 tris × node count for a 24×24 UV-sphere),
 *  - a rolling FPS sparkline (last [FPS_HISTORY_SIZE] samples).
 *
 * Lives in the demo (not the SDK) because the triangle estimate is sphere-specific —
 * the SDK doesn't expose engine-level draw-call/triangle counters yet, so we can't make
 * it accurate for arbitrary scenes.
 */
@Composable
private fun DebugOverlay(
    stats: io.github.sceneview.utils.DebugStats,
    fpsHistory: FloatArray,
    fpsHistoryHead: Int,
    fpsHistorySize: Int,
    modifier: Modifier = Modifier,
) {
    // Periodic recomposition so the text updates even when no other state changes.
    // Wrapped in LifecycleAwareLaunchedEffect so the 250 ms tick doesn't keep
    // recomposing (and re-running the SceneView render pass that reads `tick`)
    // when the app is backgrounded. See #936.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleAwareLaunchedEffect(Unit) {
        while (true) {
            delay(250)
            tick++
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick

    Column(
        modifier = modifier
            .background(Color(0xAA000000.toInt()))
            .padding(8.dp)
    ) {
        val mono = FontFamily.Monospace
        val fps = stats.fps
        val fpsColor = when {
            fps >= 55f -> Color(0xFF4CAF50)
            fps >= 30f -> Color(0xFFFFC107)
            else -> Color(0xFFE53935)
        }
        BasicText(
            text = "FPS: %.1f".format(Locale.US, fps),
            style = TextStyle(color = fpsColor, fontSize = 12.sp, fontFamily = mono)
        )
        BasicText(
            text = "Frame: %.1f ms".format(Locale.US, stats.frameTimeMs),
            style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = mono)
        )
        BasicText(
            text = "Nodes: %d".format(Locale.US, stats.nodeCount),
            style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = mono)
        )
        // Estimated tris: SphereNode default tessellation is 24 stacks × 24 slices,
        // which yields 24*24*2 = 1152 triangles. Format with thousands grouping so a
        // 5760000-tri stress-test reads as "5,760,000" instead of a soup of digits.
        val tris = stats.nodeCount.toLong() * TRIS_PER_SPHERE
        BasicText(
            text = "Tris: %s".format(formatThousands(tris)),
            style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = mono)
        )

        // Sparkline. 60-frame ring buffer scaled to 120 fps max so a 60 fps run sits at
        // half height. We don't draw the ring "as is" — we walk from the oldest sample
        // to the newest so the line moves left-to-right naturally.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(top = 4.dp),
        ) {
            val w = size.width
            val h = size.height
            if (fpsHistorySize <= 1) return@Canvas
            val maxFps = 120f
            val path = Path()
            val n = fpsHistorySize
            val capacity = fpsHistory.size
            val start = if (n < capacity) 0 else fpsHistoryHead
            for (i in 0 until n) {
                val idx = (start + i) % capacity
                val v = fpsHistory[idx].coerceIn(0f, maxFps)
                val x = if (n == 1) 0f else w * i / (n - 1)
                val y = h - (v / maxFps) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            // 60 fps reference line.
            val refY = h - (60f / maxFps) * h
            drawLine(
                color = Color(0x55FFFFFF),
                start = Offset(0f, refY),
                end = Offset(w, refY),
                strokeWidth = 1f,
            )
            drawPath(
                path = path,
                color = Color(0xFF4CAF50),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Tunables / helpers                                                         */
/* -------------------------------------------------------------------------- */

private const val TAG = "DebugOverlayDemo"

private const val NODE_SPACING = 0.18f
private const val NODE_RADIUS = 0.04f

/** SphereNode default tessellation = 24 stacks × 24 slices → 1152 triangles. */
private const val TRIS_PER_SPHERE = 1152L

/** Spawn ramp tunables — visible "filling up" without dropping the UI thread. */
private const val SPAWN_BATCH_SIZE = 16
private const val SPAWN_FRAME_DELAY_MS = 16L

/** FPS sparkline — 120 samples at 60 fps ≈ 2 s of history. */
private const val FPS_HISTORY_SIZE = 120

/**
 * Stress test: linear ramp 1 → 2 000 over 10 s. v1 used 5 000, which OOM'd Pixel-class
 * devices — each [SphereNode] holds its own VertexBuffer + IndexBuffer (24×24 sphere ≈
 * 4 KB GPU + 5 KB JVM bookkeeping), so 5 000 ≈ 45 MB of native buffers + Filament's
 * per-renderable overhead, on top of the existing scene state. 2 000 is a comfortable
 * upper bound that still produces a clearly visible perf cliff.
 */
private const val STRESS_TARGET = 2_000
private const val STRESS_DURATION_MS = 10_000L
private const val STRESS_TICK_MS = 50L

/** Distance for a single sphere — close enough to fill ~25 % of vertical FOV at 35°. */
private const val SINGLE_SPHERE_DISTANCE = 0.8f

/**
 * Snap the animated camera distance to this grid (in metres) when re-keying the
 * orbit manipulator. 5 cm is below visible-step threshold for a smooth dolly while
 * keeping rebuild count bounded for any reasonable transition (max ~120 rebuilds for
 * a 0 → 6 m sweep, all of them cheap).
 */
private const val CAMERA_REKEY_SNAP_M = 0.05f

/**
 * Camera distance that frames a 10×10×N sphere grid (sphere radius [NODE_RADIUS],
 * spacing [NODE_SPACING]) with ~1.2× padding.
 *
 * v2 changes:
 *  - **Single-sphere case is explicit**: count == 1 returns [SINGLE_SPHERE_DISTANCE]
 *    instead of falling through the bounding-box math (which produced a distance < the
 *    sphere radius, hiding the sphere on Pixel 9).
 *  - **Floor raised**: minimum returned distance is now [SINGLE_SPHERE_DISTANCE] so even
 *    a degenerate count (0, 2, 3) gets a visible framing.
 *
 * Approach for count > 1:
 *  1. Compute the half-extents of the bounding box on each axis from the spawn pattern.
 *  2. Take the bounding-sphere radius via Pythagoras of the three half-extents.
 *  3. Camera distance = radius / tan(fov/2) × 1.2 (padding).
 *
 * Filament's default vertical FOV is ~45°. We use 35° because portrait aspect ratio
 * shrinks the visible cone and we want a comfortable margin around the bounding box.
 */
internal fun autoFitDistance(nodeCount: Int): Float {
    if (nodeCount <= 1) return SINGLE_SPHERE_DISTANCE
    // Grid layout — must match the centering math in the SceneView spawn block
    // above. `cols`, `rows`, `layers` describe the actual occupied footprint:
    //   cols   = min(10, nodeCount)
    //   rows   = min(10, ceil(nodeCount / cols))
    //   layers = ceil(nodeCount / (cols * rows))
    // The grid is *centered on origin* (post-#1212 fix), so the half-extent on
    // each axis is (axisLen - 1) / 2 × NODE_SPACING.
    val cols = minOf(10, nodeCount)
    val rows = minOf(10, ((nodeCount + cols - 1) / cols)).coerceAtLeast(1)
    val layers = ((nodeCount + cols * rows - 1) / (cols * rows)).coerceAtLeast(1)
    val halfX = (cols - 1) / 2f * NODE_SPACING + NODE_RADIUS
    val halfY = (rows - 1) / 2f * NODE_SPACING + NODE_RADIUS
    val halfZ = (layers - 1) / 2f * NODE_SPACING + NODE_RADIUS
    val radius = sqrt(halfX * halfX + halfY * halfY + halfZ * halfZ)
        .coerceAtLeast(NODE_RADIUS * 4f)
    // 35° vertical FOV (radians) → tan(17.5°) ≈ 0.3153.
    val fovRadHalf = Math.toRadians(17.5).toFloat()
    val raw = radius / tan(fovRadHalf) * 1.2f
    return max(SINGLE_SPHERE_DISTANCE, raw)
}

private fun formatThousands(v: Long): String {
    if (v < 1000) return v.toString()
    // Build "1,234,567" without depending on a locale (deterministic across devices).
    val s = v.toString()
    val sb = StringBuilder()
    val first = s.length % 3
    if (first > 0) sb.append(s, 0, first)
    var i = first
    while (i < s.length) {
        if (sb.isNotEmpty()) sb.append(',')
        sb.append(s, i, i + 3)
        i += 3
    }
    return sb.toString()
}

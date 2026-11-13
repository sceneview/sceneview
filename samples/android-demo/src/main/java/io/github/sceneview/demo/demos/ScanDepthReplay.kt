package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.PoseContinuity
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.ScanDepthFormat
import io.github.sceneview.demo.demos.internal.parseArDebugEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * A recorded scan's dense fusion run again, on the JVM: the depth frames of its depth recording
 * ([ScanDepthFormat], `capture-depth.bin`) through the very [ScanDenseMap.fuse] the phone ran, on
 * the phone's own fusion budget, in the order it fused them. Unchanged code gives the cloud the
 * phone saved; a change to the fusion is judged on a real walk.
 *
 * The pose guard replays too: given the session's log, a fresh [PoseContinuity] (or the one under
 * test) is handed every pose the phone's was — the `camera_pose` events and the `pose_guard`
 * ones — and decides which recorded frames are fused, held-back ones included.
 */
internal object ScanDepthReplay {
    /** What a replay gave: the cloud, and how many recorded frames it fused and left out. */
    class Result(
        val cloud: DenseCloud,
        val frames: Int,
        val fused: Int,
        val held: Int,
        /** Frames whose verdict differs from the phone's guard: `0` for the guard unchanged. */
        val guardChanged: Int,
    )

    /**
     * Replays [depth] (a `capture-depth.bin`). With [log] (the session's `capture-session.jsonl`)
     * and a [guard], the guard decides which frames are fused; otherwise the phone's verdicts do.
     * `null` when [depth] is not a depth recording.
     */
    fun replay(depth: File, log: ByteArray? = null, guard: PoseContinuity? = PoseContinuity()): Result? {
        val verdicts = if (log != null && guard != null) guardVerdicts(log, guard) else null
        return ScanDepthFormat.read(depth) { header, frames ->
            // A clock that never moves: one live snapshot, which the replay does not need, then none.
            val map = ScanDenseMap(maxPoints = header.maxPoints, maxLevel = header.maxLevel, clock = { 0L })
            var count = 0
            var fused = 0
            var changed = 0
            for (recorded in frames) {
                count++
                val integrates = verdicts?.get(recorded.nanos) ?: !recorded.rejected
                if (integrates == recorded.rejected) changed++
                if (!integrates) continue
                map.floorPlaneY = recorded.floorPlaneY
                map.fuse(recorded.frame, recorded.nanos, header.farM)
                fused++
            }
            Result(map.savedCloud(), count, fused, count - fused, changed)
        }
    }

    /** Replays the session in [dir] when it holds a depth recording; `null` when it does not. */
    fun replaySession(dir: File, guard: PoseContinuity? = PoseContinuity()): Result? {
        val depth = File(dir, ScanDepthFormat.FILE).takeIf { it.isFile } ?: return null
        val log = File(dir, RerunCapturePack.LOG).takeIf { it.isFile }?.readBytes()
        return replay(depth, log, guard)
    }

    /**
     * Every pose the phone's guard was handed, in order — the frames it integrated (`camera_pose`)
     * and those it held (`pose_guard`) — stepped through [guard]: whether each frame, by its time,
     * integrates.
     */
    fun guardVerdicts(log: ByteArray, guard: PoseContinuity): Map<Long, Boolean> {
        val poses = ArrayList<Pair<Long, DebugPose>>()
        for (line in String(log, Charsets.UTF_8).lineSequence()) {
            if (line.contains("\"${ScanDepthFormat.POSE_GUARD}\"")) {
                val obj = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                val (t, pose, step) = ScanDepthFormat.parsePoseGuard(obj) ?: continue
                // An integrated one is in the camera poses already.
                if (!step.integrates) poses += t to pose
                continue
            }
            val event = parseArDebugEvent(line) as? ArDebugEvent.CameraPose ?: continue
            poses += event.nanos to event.pose
        }
        // Stable: two poses of one frame (handed twice) stay in their order.
        poses.sortBy { it.first }
        val verdicts = HashMap<Long, Boolean>(poses.size * 2)
        for ((t, pose) in poses) verdicts[t] = guard.step(t, pose).integrates
        return verdicts
    }
}

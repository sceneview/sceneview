package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.ScanCoverageState
import io.github.sceneview.demo.demos.ScanWallsState
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Recorded scans replayed through the live walls, before the survey's rules ([LegacyPlaneWalls])
 * and with them ([PlaneWallSource]): how many walls each shows, and what the record screen makes
 * of them ([ScanWallsState]) — whether it invites Finish and names the room. Counts only, never a
 * dimension.
 */
internal object LiveWallsCorpus {
    /** How often the replay looks at the walls, in seconds of the recording; the last frame too. */
    const val STEP_S = 0.5f

    /** The walls shown at the end, the most shown at once, and how many different walls ever showed. */
    class Counts(val final: Int, val maxAtOnce: Int, val distinct: Int) {
        fun toMap(): Map<String, Int> = mapOf("final" to final, "maxAtOnce" to maxAtOnce, "distinct" to distinct)

        override fun toString(): String = "final $final, at once $maxAtOnce, distinct $distinct"
    }

    /**
     * The record screen over the replay, with the rules: the walls its HUD counts at the end,
     * whether it invites Finish ([ScanWallsState.done]) and names the room (two walls and a floor
     * outline, as the live view's HUD), and from when, in seconds of the recording (`null`: never).
     */
    class Screen(val walls: Int, val doneAt: Float?, val namedAt: Float?, val named: Boolean) {
        val done: Boolean get() = doneAt != null

        fun toMap(): Map<String, Any?> =
            mapOf("walls" to walls, "done" to done, "doneAt" to doneAt, "named" to named, "namedAt" to namedAt)

        override fun toString(): String = "walls $walls, done ${doneAt ?: "never"}, named ${namedAt ?: "never"}" +
            if (named || namedAt == null) "" else " (not at the end)"
    }

    class Result(val name: String, val samples: Int, val before: Counts, val after: Counts, val screen: Screen) {
        fun toMap(): Map<String, Any> = mapOf(
            "name" to name,
            "samples" to samples,
            "before" to before.toMap(),
            "after" to after.toMap(),
            "screen" to screen.toMap(),
        )

        override fun toString(): String = "$name: before ($before) -> after ($after); screen ($screen)"
    }

    private class Tally {
        private val ever = HashSet<Int>()
        var last = 0
            private set
        var most = 0
            private set

        fun add(ids: List<Int>) {
            ever += ids
            last = ids.size
            most = max(most, ids.size)
        }

        fun counts() = Counts(last, most, ever.size)
    }

    /**
     * Every capture under [root], by name: `.svscan` and `.rrd` files, and session directories
     * holding a capture's three files. One that does not read is left out.
     */
    fun captures(root: File): List<Pair<String, RerunCapturePack>> = root.walkTopDown()
        .sortedBy { it.path }
        .mapNotNull { file ->
            val name = file.relativeTo(root).path.ifEmpty { file.name }
            when {
                file.isFile && file.extension == RerunScanFile.EXTENSION ->
                    RerunScanFile.read(file.readBytes())?.let { name to it }
                file.isFile && file.extension == RRD ->
                    runCatching { RerunRrdReader.recording(file.readBytes(), FakeRerunImageCodec).pack }
                        .getOrNull()?.let { name to it }
                file.isDirectory && File(file, RerunCapturePack.MANIFEST).isFile ->
                    file.parentFile?.let { RerunSessionStore(it).capture(file.name) }?.let { name to it }
                else -> null
            }
        }
        .toList()

    /**
     * [pack] replayed every [STEP_S] seconds and at its end; `null` when it does not open. The
     * walls are told when ARCore had lost tracking: no camera pose recorded for longer than
     * [TRACE_LOST_AFTER_S] before the time looked at, as the recorder writes nothing then.
     */
    fun replay(name: String, pack: RerunCapturePack): Result? {
        val trace = pack.open()?.trace ?: return null
        val poses = poseTimes(pack, trace)
        val legacy = LegacyPlaneWalls()
        val source = PlaneWallSource()
        val before = Tally()
        val after = Tally()
        // The record screen fed the same frames, its floor covered as a saved scan's replay covers it.
        val screen = ScanWallsState(Replayed(PlaneWallSource(), poses))
        val coverage = ScanCoverageState()
        var doneAt: Float? = null
        var namedAt: Float? = null
        var named = false
        var samples = 0
        var t = 0f
        while (true) {
            val time = min(t, trace.duration)
            val frame = trace.frameAt(time)
            val planes = livePlanesOf(frame)
            val floorY = ArDebugGeometry.floorHeight(frame)
            before.add(legacy.update(planes, floorY))
            after.add(source.update(planes, floorY, time, tracked(poses, time)).walls.map { it.id })
            coverage.observe(frame, saved = trace)
            screen.observe(frame, coverage, frame.camera)
            if (screen.done && doneAt == null) doneAt = time
            named = screen.wallCount >= ArDebugSession.MIN_ROOM_WALLS && RoomMeasure.of(frame.planes, floorY) != null
            if (named && namedAt == null) namedAt = time
            samples++
            if (time >= trace.duration) break
            t += STEP_S
        }
        return Result(name, samples, before.counts(), after.counts(), Screen(screen.wallCount, doneAt, namedAt, named))
    }

    /** [source] told when the recording had lost tracking ([tracked]), as the replay reads it off the poses. */
    private class Replayed(private val source: LiveWallSource, private val poses: FloatArray) : LiveWallSource {
        override fun update(planes: List<LivePlane>, floorY: Float, time: Float, tracking: Boolean): LiveWallSnapshot =
            source.update(planes, floorY, time, tracked(poses, time))

        override fun reset() = source.reset()
    }

    /** When [pack] recorded a camera pose, in seconds of its [trace], in order. */
    private fun poseTimes(pack: RerunCapturePack, trace: ArDebugTrace): FloatArray =
        parseArDebugLog(String(pack.log).lineSequence())
            .filterIsInstance<ArDebugEvent.CameraPose>()
            .map { trace.secondsOf(it.nanos) }
            .sorted()
            .toFloatArray()

    /** Whether ARCore tracked the camera at [time]: a pose in [poses] at most [TRACE_LOST_AFTER_S] before it. */
    private fun tracked(poses: FloatArray, time: Float): Boolean {
        var lo = 0
        var hi = poses.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (poses[mid] <= time) lo = mid + 1 else hi = mid
        }
        return lo > 0 && time - poses[lo - 1] <= TRACE_LOST_AFTER_S
    }

    /** Each capture under [root] replayed, a `<name>.json` each and a `summary.json` written to [out]. */
    fun run(root: File, out: File): List<Result> {
        out.mkdirs()
        val results = captures(root).mapNotNull { (name, pack) -> replay(name, pack) }
        for (result in results) {
            File(out, result.name.replace(File.separatorChar, '_') + ".json").writeText(RerunJson.write(result.toMap()))
        }
        val total = mapOf(
            "sessions" to results.size,
            "beforeFinal" to results.sumOf { it.before.final },
            "afterFinal" to results.sumOf { it.after.final },
            "beforeMaxAtOnce" to results.sumOf { it.before.maxAtOnce },
            "afterMaxAtOnce" to results.sumOf { it.after.maxAtOnce },
            "beforeDistinct" to results.sumOf { it.before.distinct },
            "afterDistinct" to results.sumOf { it.after.distinct },
            "screenWalls" to results.sumOf { it.screen.walls },
            "done" to results.count { it.screen.done },
            "named" to results.count { it.screen.named },
        )
        File(out, "summary.json").writeText(RerunJson.write(mapOf("total" to total, "sessions" to results.map { it.toMap() })))
        return results
    }

    private const val RRD = "rrd"
}

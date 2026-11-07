package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DebugPlaneKind
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.LiveWallsCorpus
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.ScanCopy
import io.github.sceneview.demo.demos.internal.ScanDevice
import io.github.sceneview.demo.demos.internal.ScanGuide
import io.github.sceneview.demo.demos.internal.RerunScanFile
import io.github.sceneview.demo.demos.internal.ScanTurn
import io.github.sceneview.demo.demos.internal.Vec3
import io.github.sceneview.demo.demos.internal.of
import io.github.sceneview.demo.demos.internal.room.PlanCanvas
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A capture's guide replayed, before and after QA-24's fix: the trace's frames fed to the live
 * screen's walls ([ScanWallsState]) as the live view feeds them, every [STEP_S] seconds, and at
 * each one what the screen said —
 *
 * - **before**: the turn the old guide gave ([Legacy]: four sides, no hysteresis, the gap picked
 *   afresh each frame), held on screen as the old `rememberHeld` held it — it changed only once the
 *   turn had left the side shown for 3 s in a row, and a sway back to that side started the 3 s over;
 * - **after**: the heading and distance to the target ([ScanWallsState.guidance]), the words said,
 *   whether the arrow shows, the tick, and the ribbon's ends.
 *
 * Used by [ScanGuideReplayTest] on a synthetic room and by [ScanGuideCorpusTest] on real scans.
 */
internal object ScanGuideCorpus {
    const val STEP_S = 0.1f
    const val SUMMARY = "guide-summary.txt"

    /** The old hold: a new turn showed once it had left the one on screen for this long. */
    const val OLD_HOLD_MS = 3_000L

    private val LOCALE: Locale = Locale.UK

    class Sample(
        val time: Float,
        val pose: DebugPose,
        val walls: Int,
        val oldRaw: ScanTurn?,
        val oldShown: ScanTurn?,
        val oldTarget: FloatArray?,
        val oldText: String?,
        val target: FloatArray?,
        val fix: Boolean,
        val degrees: Float?,
        val distance: Float?,
        val said: Float?,
        val turn: ScanTurn?,
        val aligned: Boolean,
        val reached: Boolean,
        val arrow: Boolean,
        val tick: Boolean,
        val ribbon: FloatArray?,
        val newText: String?,
    )

    /** What a replay shows, in numbers: how often each screen's guide text changed, and its longest stretch unchanged. */
    class Result(
        val name: String,
        val samples: List<Sample>,
        /** At the end: the walls found and the floor edges still open, `ax, az, bx, bz` each. */
        val walls: List<FloatArray> = emptyList(),
        val open: List<FloatArray> = emptyList(),
    ) {
        val oldChanges = changes(samples.map { it.oldText })
        val newChanges = changes(samples.map { it.newText })
        val oldLongestS = longest(samples) { it.oldText }
        val newLongestS = longest(samples) { it.newText }

        /** How far the heading to the target swung while the old text stood unchanged, degrees. */
        val headingSwingInOldLongest: Float = run {
            val (from, to) = longestSpan(samples) { it.oldText }
            val headings = samples.filter { it.time in from..to }.mapNotNull { it.degrees }
            if (headings.isEmpty()) 0f else headings.max() - headings.min()
        }
        /** How many times the new text changed while the old text stood at its longest. */
        val newChangesInOldLongest: Int = run {
            val (from, to) = longestSpan(samples) { it.oldText }
            changes(samples.filter { it.time in from..to }.map { it.newText })
        }

        /** How long the old text said a side its own guide no longer gave: the hold, stale. */
        val oldStaleS = samples.count { it.oldText != null && it.oldRaw != null && it.oldShown != it.oldRaw } * STEP_S
        val ticks = samples.count { it.tick }
        val arrowShare = share(samples.count { it.arrow }, samples.count { it.target != null })
        val distinctOld = samples.mapNotNull { it.oldText }.toSet().size
        val distinctNew = samples.mapNotNull { it.newText }.toSet().size

        override fun toString() = String.format(
            Locale.ROOT,
            "%s: %d samples over %.1f s. Before: text changed %d times (%d distinct), longest unchanged %.1f s " +
                "while the heading swung %.0f° (the new text changed %d times meanwhile); stale %.1f s. " +
                "After: text changed %d times (%d distinct), longest unchanged %.1f s; %d ticks; " +
                "arrow up %.0f %% of the time with a target.",
            name, samples.size, samples.lastOrNull()?.time ?: 0f, oldChanges, distinctOld, oldLongestS,
            headingSwingInOldLongest, newChangesInOldLongest, oldStaleS, newChanges, distinctNew, newLongestS, ticks,
            arrowShare,
        )
    }

    /**
     * [trace] replayed every [stepS] seconds from its start to its end. [fixAt]: from that time on,
     * a part Fix now is filling in at `[x, z]` (the second of the pair), as the review would set it.
     */
    fun replay(name: String, trace: ArDebugTrace, stepS: Float = STEP_S, fixAt: Pair<Float, FloatArray>? = null): Result {
        var nowMs = 0L
        val walls = ScanWallsState(clock = { nowMs })
        val coverage = ScanCoverageState()
        val hold = Held<ScanTurn?>(null, OLD_HOLD_MS)
        val samples = ArrayList<Sample>()
        var ticks = walls.alignedTicks
        var t = 0f
        while (true) {
            val time = min(t, trace.duration)
            nowMs = (time * MS_PER_S).toLong()
            val frame = trace.frameAt(time)
            val pose = frame.camera
            coverage.observe(frame, pose = pose, saved = trace)
            walls.fixTarget = fixAt?.takeIf { time >= it.first }?.second
            walls.observe(frame, coverage, pose)
            val oldCue = if (walls.done || pose == null) null else Legacy.cue(pose, walls.openEdges)
            val oldRaw = oldCue?.let { Legacy.turnTo(pose!!, it[0], it[1]) }
            val oldShown = hold.update(oldRaw, nowMs)
            val found = walls.wallCount
            val g = walls.guidance
            val hint = walls.hint
            val tick = walls.alignedTicks != ticks
            ticks = walls.alignedTicks
            if (pose != null) {
                samples += Sample(
                    time = time,
                    pose = pose,
                    walls = found,
                    oldRaw = oldRaw,
                    oldShown = oldShown,
                    oldTarget = oldCue,
                    oldText = oldShown?.takeIf { found >= 1 }?.let { ScanCopy.turnHint(it) },
                    target = g.target?.let { floatArrayOf(it.x, it.z) },
                    fix = g.target?.fix == true,
                    degrees = g.heading?.degrees,
                    distance = g.heading?.distance,
                    said = g.said.takeUnless { it.isNaN() },
                    turn = g.turn,
                    aligned = g.aligned,
                    reached = g.reached,
                    arrow = walls.arrowShown,
                    tick = tick,
                    ribbon = g.ribbon?.copyOf(),
                    newText = hint?.takeIf { found >= 1 || it.fix }?.let { ScanCopy.guideHint(it, LOCALE) },
                )
            }
            if (time >= trace.duration) break
            t += stepS
        }
        val found = walls.snapshot.walls.map { floatArrayOf(it.ax, it.az, it.bx, it.bz) }
        return Result(name, samples, found, walls.openEdges.map { it.copyOf() })
    }

    /** Every capture under [corpus] replayed, a board PNG and a CSV each and a summary written to [out]. */
    fun run(corpus: File, out: File): List<Result> {
        out.mkdirs()
        val summary = StringBuilder()
        summary.appendLine("Before: the old four-way turn, held 3 s on screen. After: heading + distance, hysteresis, tick.")
        val results = LiveWallsCorpus.captures(corpus).mapNotNull { (name, pack) ->
            val trace = runCatching { pack.open()?.trace }.getOrNull()
            if (trace == null) {
                summary.appendLine("$name: not read")
                return@mapNotNull null
            }
            val result = replay(name, trace)
            val base = name.replace(File.separatorChar, '_')
            File(out, "$base-guide.csv").writeText(csv(result))
            File(out, "$base-guide.png").writeBytes(board(result))
            summary.appendLine(result.toString())
            result
        }
        File(out, SUMMARY).writeText(summary.toString())
        println(summary)
        return results
    }

    /** One row a sample. */
    fun csv(result: Result): String = buildString {
        appendLine(
            "time_s,x,z,yaw_deg,walls,old_raw,old_shown,old_target_x,old_target_z,old_text," +
                "target_x,target_z,fix,heading_deg,distance_m,said_m,turn,aligned,reached,arrow,tick," +
                "ribbon_from_x,ribbon_from_z,ribbon_to_x,ribbon_to_z,new_text",
        )
        for (s in result.samples) {
            val cells = listOf(
                f(s.time), f(s.pose.x), f(s.pose.z), f(yawOf(s.pose)), s.walls.toString(),
                s.oldRaw?.name.orEmpty(), s.oldShown?.name.orEmpty(), f(s.oldTarget?.get(0)), f(s.oldTarget?.get(1)), q(s.oldText),
                f(s.target?.get(0)), f(s.target?.get(1)), s.fix.toString(), f(s.degrees), f(s.distance), f(s.said),
                s.turn?.name.orEmpty(), s.aligned.toString(), s.reached.toString(), s.arrow.toString(), s.tick.toString(),
                f(s.ribbon?.get(0)), f(s.ribbon?.get(1)), f(s.ribbon?.get(2)), f(s.ribbon?.get(3)), q(s.newText),
            )
            appendLine(cells.joinToString(","))
        }
    }

    /**
     * Before on the left, after on the right, the same axes: on top the heading to the target over
     * time (degrees, right up, grid at ±30° and ±135°) — before, the side the old text said (a
     * thick purple step at -90, 0, 90 or 180) over the heading to its own target (grey); after,
     * the arrow's heading (amber; a green band once lined up, a green dot at each tick). Under it,
     * a mark at each change of the text on screen, and after, the distance said (grey, 0–5 m). At
     * the bottom, the floor from above at the end: the walls found (black), the gaps still open
     * (dashed), the phone's path (grey), where each guide led (dots), and
     * after, the ribbon every 2 s. Under each panel: how many times the text changed, and its
     * longest stretch unchanged in seconds.
     */
    @Suppress("LongMethod")
    fun board(result: Result): ByteArray {
        val samples = result.samples
        val canvas = PlanCanvas(BOARD_W, BOARD_H)
        val duration = max(samples.lastOrNull()?.time ?: 1f, 1f)
        // The plan's bounds: the phone's path and every target.
        val edges = result.walls + result.open
        val xs = samples.flatMap { listOfNotNull(it.pose.x, it.target?.get(0), it.oldTarget?.get(0)) } +
            edges.flatMap { listOf(it[0], it[2]) }
        val zs = samples.flatMap { listOfNotNull(it.pose.z, it.target?.get(1), it.oldTarget?.get(1)) } +
            edges.flatMap { listOf(it[1], it[3]) }
        val minX = (xs.minOrNull() ?: 0f) - PLAN_MARGIN_M
        val maxX = (xs.maxOrNull() ?: 0f) + PLAN_MARGIN_M
        val minZ = (zs.minOrNull() ?: 0f) - PLAN_MARGIN_M
        val maxZ = (zs.maxOrNull() ?: 0f) + PLAN_MARGIN_M
        val width = PANEL_W - 2 * PAD
        val planScale = min(width / (maxX - minX), PLAN_H / (maxZ - minZ))
        for (panel in 0..1) {
            val left = panel * PANEL_W + PAD
            fun tx(t: Float) = left + t / duration * width
            fun ty(deg: Float) = PLOT_TOP + (HALF_TURN - deg) / FULL_TURN * PLOT_H
            fun box(top: Int, h: Int) {
                canvas.line(left.toFloat(), top.toFloat(), (left + width).toFloat(), top.toFloat(), 1f, GRID)
                canvas.line(left.toFloat(), (top + h).toFloat(), (left + width).toFloat(), (top + h).toFloat(), 1f, GRID)
                canvas.line(left.toFloat(), top.toFloat(), left.toFloat(), (top + h).toFloat(), 1f, GRID)
                canvas.line((left + width).toFloat(), top.toFloat(), (left + width).toFloat(), (top + h).toFloat(), 1f, GRID)
            }
            box(PLOT_TOP, PLOT_H)
            for (deg in listOf(-135f, -30f, 30f, 135f)) canvas.line(tx(0f), ty(deg), tx(duration), ty(deg), 1f, GRID, dash = 4f)
            canvas.line(tx(0f), ty(0f), tx(duration), ty(0f), 1f, MUTED)
            if (panel == 0) {
                polyline(canvas, samples, ::tx, ::ty, 1.5f, MUTED) { s ->
                    s.oldTarget?.let { ScanGuide.heading(s.pose, it[0], it[1]).degrees }
                }
                polyline(canvas, samples, ::tx, ::ty, STEP_STROKE, OLD) { s ->
                    s.oldShown?.takeIf { s.oldText != null }?.let(::sideDegrees)
                }
            } else {
                for (s in samples) if (s.target != null && s.aligned) {
                    canvas.line(tx(s.time), PLOT_TOP + 1f, tx(s.time), PLOT_TOP + PLOT_H - 1f, width * STEP_S / duration + 1, ALIGNED_BG)
                }
                canvas.line(tx(0f), ty(0f), tx(duration), ty(0f), 1f, MUTED)
                polyline(canvas, samples, ::tx, ::ty, STEP_STROKE, NEW) { s -> s.degrees }
                for (s in samples) if (s.tick) canvas.line(tx(s.time), PLOT_TOP - 6f, tx(s.time), PLOT_TOP - 6f, 8f, ALIGNED)
            }
            // The text on screen: a mark at each change.
            box(STRIP_TOP, STRIP_H)
            val texts = samples.map { if (panel == 0) it.oldText else it.newText }
            for (i in 1 until samples.size) if (texts[i] != texts[i - 1]) {
                canvas.line(tx(samples[i].time), STRIP_TOP.toFloat(), tx(samples[i].time), (STRIP_TOP + STRIP_H).toFloat(), 2f, if (panel == 0) OLD else NEW)
            }
            if (panel == 1) {
                polyline(canvas, samples, ::tx, { m -> STRIP_TOP + STRIP_H - m / MAX_SAID_M * STRIP_H }, 1.5f, MUTED) { s ->
                    s.said?.takeIf { !s.reached }?.coerceAtMost(MAX_SAID_M)
                }
            }
            // The floor from above.
            // Centred in its box.
            val offX = (width - (maxX - minX) * planScale) / 2f
            val offZ = (PLAN_H - (maxZ - minZ) * planScale) / 2f
            fun px(x: Float) = left + offX + (x - minX) * planScale
            fun pz(z: Float) = PLAN_TOP + offZ + (z - minZ) * planScale
            box(PLAN_TOP, PLAN_H)
            for (w in result.walls) canvas.line(px(w[0]), pz(w[1]), px(w[2]), pz(w[3]), 4f, INK)
            for (e in result.open) canvas.line(px(e[0]), pz(e[1]), px(e[2]), pz(e[3]), 2f, MUTED, dash = 5f)
            for (i in 1 until samples.size) {
                canvas.line(px(samples[i - 1].pose.x), pz(samples[i - 1].pose.z), px(samples[i].pose.x), pz(samples[i].pose.z), 1.5f, MUTED)
            }
            if (panel == 1) {
                var next = 0f
                for (s in samples) {
                    val r = s.ribbon ?: continue
                    if (s.time < next) continue
                    next = s.time + RIBBON_EVERY_S
                    canvas.line(px(r[0]), pz(r[1]), px(r[2]), pz(r[3]), 2f, NEW_LIGHT)
                }
            }
            for (s in samples) {
                val target = if (panel == 0) s.oldTarget?.takeIf { s.oldText != null } else s.target
                target?.let { canvas.line(px(it[0]), pz(it[1]), px(it[0]), pz(it[1]), 7f, if (panel == 0) OLD else NEW) }
            }
            val stats = if (panel == 0) {
                String.format(Locale.ROOT, "%d  %.1f", result.oldChanges, result.oldLongestS)
            } else {
                String.format(Locale.ROOT, "%d  %.1f", result.newChanges, result.newLongestS)
            }
            canvas.text(left, PLAN_TOP + PLAN_H + 10, stats, TEXT_SCALE, INK)
        }
        return canvas.png()
    }

    /** [value] of each sample as one line, broken where it is missing or wraps round behind. */
    private fun polyline(
        canvas: PlanCanvas,
        samples: List<Sample>,
        tx: (Float) -> Float,
        ty: (Float) -> Float,
        thickness: Float,
        color: Int,
        value: (Sample) -> Float?,
    ) {
        var last: Pair<Float, Float>? = null
        for (s in samples) {
            val v = value(s)
            if (v == null) {
                last = null
                continue
            }
            val prev = last
            if (prev != null && abs(prev.second - v) < WRAP_DEG) {
                canvas.line(tx(prev.first), ty(prev.second), tx(s.time), ty(v), thickness, color)
            }
            last = s.time to v
        }
    }

    private fun sideDegrees(turn: ScanTurn) = when (turn) {
        ScanTurn.Ahead -> 0f
        ScanTurn.Right -> 90f
        ScanTurn.Left -> -90f
        ScanTurn.Behind -> HALF_TURN
    }

    private fun changes(texts: List<String?>) = texts.zipWithNext().count { (a, b) -> a != b }

    private fun longestSpan(samples: List<Sample>, text: (Sample) -> String?): Pair<Float, Float> {
        var best = 0f to 0f
        var from = 0
        for (i in 1..samples.size) {
            if (i == samples.size || text(samples[i]) != text(samples[from])) {
                if (text(samples[from]) != null) {
                    val span = samples[from].time to samples[i - 1].time
                    if (span.second - span.first > best.second - best.first) best = span
                }
                from = i
            }
        }
        return best
    }

    private fun longest(samples: List<Sample>, text: (Sample) -> String?): Float =
        longestSpan(samples, text).let { it.second - it.first }

    private fun share(n: Int, of: Int) = if (of == 0) 0f else n * PERCENT / of

    private fun yawOf(pose: DebugPose): Float {
        val f = pose.rotate(0f, 0f, -1f)
        return Math.toDegrees(kotlin.math.atan2(-f.x, -f.z).toDouble()).toFloat()
    }

    private fun f(v: Float?) = v?.let { String.format(Locale.ROOT, "%.3f", it) }.orEmpty()

    private fun q(text: String?) = text?.let { "\"" + it.replace("\"", "\"\"") + "\"" }.orEmpty()

    /**
     * The guide as it was before QA-24's fix, kept here to replay it: the longest open gap, a nearer
     * one winning a tie, picked afresh each frame; and four sides cut at 30° and 135°.
     */
    object Legacy {
        fun cue(pose: DebugPose, openEdges: List<FloatArray>): FloatArray? {
            if (openEdges.isEmpty()) return null
            val edge = openEdges.maxBy { e ->
                val length = ScanGuide.hypot(e[2] - e[0], e[3] - e[1])
                val mx = (e[0] + e[2]) / 2f - pose.x
                val mz = (e[1] + e[3]) / 2f - pose.z
                length / (1f + 0.25f * ScanGuide.hypot(mx, mz))
            }
            return floatArrayOf((edge[0] + edge[2]) / 2f, (edge[1] + edge[3]) / 2f)
        }

        fun turnTo(pose: DebugPose, x: Float, z: Float): ScanTurn {
            val degrees = ScanGuide.heading(pose, x, z).degrees
            return when {
                abs(degrees) <= 30f -> ScanTurn.Ahead
                abs(degrees) >= 135f -> ScanTurn.Behind
                degrees > 0 -> ScanTurn.Right
                else -> ScanTurn.Left
            }
        }
    }

    /**
     * The old screen's hold, `rememberHeld`: the value shown changes only once the value has left
     * it for [holdMs] in a row, then to the latest; a return to the value shown starts it over.
     */
    class Held<T>(initial: T, private val holdMs: Long) {
        private var shown = initial
        private var since: Long? = null

        fun update(value: T, nowMs: Long): T {
            if (value == shown) {
                since = null
                return shown
            }
            val start = since ?: nowMs.also { since = it }
            if (nowMs - start >= holdMs) {
                shown = value
                since = null
            }
            return shown
        }
    }

    // ── The synthetic room the guide is checked on ───────────────────────────────────────────

    /** [RerunSyntheticRoom]'s floor, x −2..2 and z −1.8..1.8; its walls at z = −1.8 and x = −2 found, the other two open. */
    private val FLOOR = floatArrayOf(-2f, 0f, -1.8f, 2f, 0f, -1.8f, 2f, 0f, 1.8f, -2f, 0f, 1.8f)

    private fun wall(x0: Float, z0: Float, x1: Float, z1: Float) =
        floatArrayOf(x0, 0.1f, z0, x1, 0.1f, z1, x1, 2.3f, z1, x0, 2.3f, z0)

    /** One leg of the synthetic walk: [seconds] long, the yaw going from [yaw0] to [yaw1] (degrees, left positive), the phone from [from] to [to]. */
    private class Leg(val seconds: Float, val yaw0: Float, val yaw1: Float, val from: Vec3, val to: Vec3)

    /**
     * A scan of [RerunSyntheticRoom] whose camera turns: the floor and two walls found (the sage one
     * at the back, z = −1.8, and the left one), the right and the near walls open. The phone faces
     * the sage wall, sweeping ±[SWAY_DEG] every [SWAY_S] seconds as a scan does, turns round to its
     * right over 16 s to face the near gap, walks towards it, and stands sweeping there. 30 frames a second.
     */
    fun syntheticEvents(): List<ArDebugEvent> {
        val events = ArrayList<ArDebugEvent>()
        events += ArDebugEvent.Plane(0L, 1, DebugPlaneKind.Floor, FLOOR)
        events += ArDebugEvent.Plane(0L, 2, DebugPlaneKind.Wall, wall(-2f, -1.8f, 2f, -1.8f))
        events += ArDebugEvent.Plane(0L, 3, DebugPlaneKind.Wall, wall(-2f, -1.8f, -2f, 1.8f))
        val start = Vec3(-0.3f, 1.4f, -0.6f)
        val near = Vec3(-0.3f, 1.4f, 0.4f)
        val legs = listOf(
            Leg(6f, 0f, 0f, start, start),
            Leg(16f, 0f, -180f, start, start),
            Leg(8f, -180f, -180f, start, near),
            Leg(6f, -180f, -180f, near, near),
        )
        var k = 0
        var elapsed = 0f
        for (leg in legs) {
            val frames = (leg.seconds * FPS).toInt()
            repeat(frames) { i ->
                val u = i / frames.toFloat()
                val t = elapsed + i / FPS
                val sway = SWAY_DEG * sin(2 * PI.toFloat() * t / SWAY_S)
                val yaw = leg.yaw0 + (leg.yaw1 - leg.yaw0) * u + sway
                val at = Vec3(leg.from.x + (leg.to.x - leg.from.x) * u, 1.4f, leg.from.z + (leg.to.z - leg.from.z) * u)
                val pose = RerunSyntheticRoom.lookPose(at, yaw * PI.toFloat() / HALF_TURN, PITCH_DEG * PI.toFloat() / HALF_TURN)
                events += ArDebugEvent.CameraPose((++k) * FRAME_NANOS, pose)
            }
            elapsed += leg.seconds
        }
        return events
    }

    /** [syntheticEvents] as a saved scan, read back as the app reads one. */
    fun syntheticRecording(): ByteArray {
        val lens = ReplayLens.of(
            RerunSyntheticRoom.DEPTH_WIDTH.toFloat(), RerunSyntheticRoom.DEPTH_HEIGHT.toFloat(),
            RerunSyntheticRoom.FOCAL, RerunSyntheticRoom.FOCAL,
        )!!
        val device = ScanDevice("android", "Pixel 9", ScanDevice.TIER_DEPTH, ScanDevice.SOURCE_RAW_DEPTH)
        return RerunScanFile.write(runBlocking { RerunCaptureBuilder.build(syntheticEvents(), lens, emptyList(), device) })
    }

    /** A sweep's swing either side, degrees, and how long one back and forth takes. */
    const val SWAY_DEG = 35f
    const val SWAY_S = 3f

    private const val FPS = 30f
    private const val FRAME_NANOS = 33_333_333L
    private const val PITCH_DEG = -20f
    private const val MS_PER_S = 1000f
    private const val PERCENT = 100f
    private const val HALF_TURN = 180f
    private const val FULL_TURN = 360f
    private const val WRAP_DEG = 180f
    private const val MAX_SAID_M = 5f

    // The board: two panels, 1,000 px wide in all.
    private const val BOARD_W = 1000
    private const val PANEL_W = BOARD_W / 2
    private const val PAD = 16
    private const val PLOT_TOP = 24
    private const val PLOT_H = 220
    private const val STRIP_TOP = PLOT_TOP + PLOT_H + 16
    private const val STRIP_H = 44
    private const val PLAN_TOP = STRIP_TOP + STRIP_H + 16
    private const val PLAN_H = 240
    private const val BOARD_H = PLAN_TOP + PLAN_H + 40
    private const val TEXT_SCALE = 3
    private const val PLAN_MARGIN_M = 0.4f
    private const val STEP_STROKE = 3f
    private const val RIBBON_EVERY_S = 2f

    private const val INK = 0x1C1B1F
    private const val MUTED = 0x9A9AA0
    private const val GRID = 0xDDDDE2
    private const val OLD = 0x6B5B95
    private const val NEW = 0xE08A00
    private const val NEW_LIGHT = 0xF8CF85
    private const val ALIGNED = 0x2F9E5B
    private const val ALIGNED_BG = 0xE2F4E8
}


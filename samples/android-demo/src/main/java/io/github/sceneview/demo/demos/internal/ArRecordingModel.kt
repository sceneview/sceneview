package io.github.sceneview.demo.demos.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Pure, device-free logic behind the AR Recording demo (#3831).
 *
 * The demo itself can only run on a phone with ARCore — the emulator has no camera HAL
 * (#2754) — so everything that decides *what the user sees* lives here, where a JVM unit
 * test pins it: the custom placement track's wire format, the replay de-duplication, the
 * recording rotation, the titles and numbers printed on every card, and what a recording
 * file is said to contain.
 */

// ── Placement track ──────────────────────────────────────────────────────────────────────

/**
 * A rigid pose — translation plus unit rotation quaternion (x, y, z, w) — in ARCore's
 * convention, kept free of ARCore types so the replay math is unit-tested on the JVM.
 */
data class RigidPose(
    val tx: Float,
    val ty: Float,
    val tz: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
) {
    /** `this ∘ other`: a point is moved by [other] first, then by this — as `Pose.compose`. */
    fun compose(other: RigidPose): RigidPose {
        val (rx, ry, rz) = rotate(other.tx, other.ty, other.tz)
        val w = qw * other.qw - qx * other.qx - qy * other.qy - qz * other.qz
        val x = qw * other.qx + qx * other.qw + qy * other.qz - qz * other.qy
        val y = qw * other.qy - qx * other.qz + qy * other.qw + qz * other.qx
        val z = qw * other.qz + qx * other.qy - qy * other.qx + qz * other.qw
        val n = sqrt(x * x + y * y + z * z + w * w).takeIf { it > 0f } ?: 1f
        return RigidPose(tx + rx, ty + ry, tz + rz, x / n, y / n, z / n, w / n)
    }

    fun inverse(): RigidPose {
        val conjugate = RigidPose(0f, 0f, 0f, -qx, -qy, -qz, qw)
        val (x, y, z) = conjugate.rotate(-tx, -ty, -tz)
        return RigidPose(x, y, z, -qx, -qy, -qz, qw)
    }

    private fun rotate(vx: Float, vy: Float, vz: Float): Triple<Float, Float, Float> {
        // v' = v + w·t + q×t, with t = 2·(q×v)
        val cx = 2f * (qy * vz - qz * vy)
        val cy = 2f * (qz * vx - qx * vz)
        val cz = 2f * (qx * vy - qy * vx)
        return Triple(
            vx + qw * cx + (qy * cz - qz * cy),
            vy + qw * cy + (qz * cx - qx * cz),
            vz + qw * cz + (qx * cy - qy * cx),
        )
    }

    val isFinite: Boolean
        get() = tx.isFinite() && ty.isFinite() && tz.isFinite() &&
            qx.isFinite() && qy.isFinite() && qz.isFinite() && qw.isFinite()
}

/**
 * One tap-to-place the user made while recording: which placement it was (0, 1, 2, …) and
 * where it sits **relative to the camera** at the frame the packet was written.
 *
 * Camera-relative rather than world on purpose: a replay rebuilds its own world from the
 * first recorded frame, while the live session's world started when the demo opened —
 * seconds and metres earlier. Re-applying the replayed camera pose of the same frame
 * ([placementWorldPose]) puts the object back on the same spot whatever either world origin
 * was. ARCore replays the camera and the sensors; bringing the app's own content back is
 * the app's job, and this track is how the demo does it.
 */
data class RecordedPlacement(val index: Int, val cameraRelative: RigidPose)

/** The packet to write for an anchor at [anchorWorld] seen from a camera at [cameraWorld]. */
fun placementOf(index: Int, cameraWorld: RigidPose, anchorWorld: RigidPose): RecordedPlacement =
    RecordedPlacement(index, cameraWorld.inverse().compose(anchorWorld))

/** Where a replayed placement goes, given the replayed camera pose of the same frame. */
fun placementWorldPose(placement: RecordedPlacement, cameraWorld: RigidPose): RigidPose =
    cameraWorld.compose(placement.cameraRelative)

/**
 * Wire format of the demo's custom ARCore data track — the "your placements" line of the
 * "What a recording keeps" list.
 *
 * ARCore lets an app add its own tracks to a recording (`RecordingConfig.addTrack`) and read
 * them back frame by frame on replay (`Frame.getUpdatedTrackData`). ARKit has no equivalent,
 * which is why this demo shows it off. A packet is 36 bytes, little endian: format version,
 * placement index, then the camera-relative translation xyz and rotation quaternion xyzw.
 *
 * Every placement is written again about once a second while recording, so a replay that
 * reaches a packet before it has its bearings simply takes the next one.
 */
object PlacementTrack {
    /** Constant track id — the same UUID must be used to record and to read back. */
    val TRACK_ID: UUID = UUID.fromString("7f3c2a54-9d1e-4c8b-a6f0-38310a5e0c01")

    /** MP4 sample-description MIME type of the track. */
    const val MIME_TYPE = "application/vnd.sceneview.placement"

    const val FORMAT_VERSION = 1

    /** Size of one encoded packet: two ints and seven floats. */
    const val PACKET_BYTES = 4 + 4 + 7 * 4

    fun encode(placement: RecordedPlacement): ByteArray =
        ByteBuffer.allocate(PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(FORMAT_VERSION)
            putInt(placement.index)
            with(placement.cameraRelative) {
                putFloat(tx)
                putFloat(ty)
                putFloat(tz)
                putFloat(qx)
                putFloat(qy)
                putFloat(qz)
                putFloat(qw)
            }
        }.array()

    /** Decodes a packet, or `null` for anything that is not a well-formed version-1 packet. */
    fun decode(bytes: ByteArray): RecordedPlacement? = decode(ByteBuffer.wrap(bytes))

    /**
     * Decodes the remaining bytes of [buffer] without moving the caller's position — the
     * buffer ARCore hands back is read-only and may be shared.
     */
    fun decode(buffer: ByteBuffer): RecordedPlacement? {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < PACKET_BYTES) return null
        if (b.getInt() != FORMAT_VERSION) return null
        val index = b.getInt()
        if (index < 0) return null
        val values = FloatArray(7) { b.getFloat() }
        if (values.any { !it.isFinite() }) return null
        return RecordedPlacement(
            index = index,
            cameraRelative = RigidPose(
                tx = values[0], ty = values[1], tz = values[2],
                qx = values[3], qy = values[4], qz = values[5], qw = values[6],
            ),
        )
    }
}

/**
 * Which recorded placements a replay has already brought back.
 *
 * Each placement is written about once a second, so the same index arrives many times; only
 * the first packet read while the replayed camera has its bearings creates an anchor.
 */
class PlacementReplayQueue {
    private val restored = HashSet<Int>()
    private val seen = HashSet<Int>()

    /** Placements already turned back into anchors. */
    val restoredCount: Int get() = restored.size

    /** Distinct placements read from the track so far, restored or not. */
    val seenCount: Int get() = seen.size

    /**
     * `true` when [placement] should become an anchor now: not restored yet, and the replayed
     * camera is [tracking]. Marks it restored when it returns `true`.
     */
    fun shouldRestore(placement: RecordedPlacement, tracking: Boolean): Boolean {
        seen += placement.index
        if (!tracking || !placement.cameraRelative.isFinite) return false
        return restored.add(placement.index)
    }

    fun reset() {
        restored.clear()
        seen.clear()
    }
}

/** How often a placement is written again while recording. */
const val PLACEMENT_REWRITE_NANOS: Long = 1_000_000_000L

/**
 * When a placement is due on the track again while recording — once per
 * [intervalNanos], so a replay that misses one packet catches the next.
 */
class PlacementWriteSchedule(private val intervalNanos: Long = PLACEMENT_REWRITE_NANOS) {
    private val lastWritten = HashMap<Int, Long>()

    fun isDue(index: Int, frameTimestampNanos: Long): Boolean {
        val last = lastWritten[index] ?: return true
        return frameTimestampNanos < last || frameTimestampNanos - last >= intervalNanos
    }

    fun markWritten(index: Int, frameTimestampNanos: Long) {
        lastWritten[index] = frameTimestampNanos
    }

    fun reset() = lastWritten.clear()
}

/**
 * `true` once per camera image.
 *
 * `ARSceneView` runs ARCore in `LATEST_CAMERA_IMAGE` mode, so `onSessionUpdated` fires on
 * every display refresh and repeats the same camera frame (same timestamp) until a new one
 * arrives. Counting callbacks would report 60–120 "frames" a second for a 30 fps camera.
 */
class NewFrameGate {
    private var last = Long.MIN_VALUE

    fun isNew(timestampNanos: Long): Boolean {
        if (timestampNanos <= 0L || timestampNanos == last) return false
        last = timestampNanos
        return true
    }

    fun reset() {
        last = Long.MIN_VALUE
    }
}

// ── Recording rotation ───────────────────────────────────────────────────────────────────

/**
 * Clockwise rotation, in degrees, that makes a recording play back upright.
 *
 * The camera sensor is mounted in landscape (90° on Pixels), so a portrait capture has to
 * be tagged with the sensor-to-display rotation — the value ARCore's own recording sample
 * computes — not the display rotation alone, which is 0 in portrait and left every
 * portrait recording sideways in its thumbnail and in any video player.
 */
fun recordingRotationDegrees(sensorOrientationDegrees: Int, displayRotationDegrees: Int): Int {
    val raw = Math.floorMod(sensorOrientationDegrees - displayRotationDegrees, 360)
    return Math.floorMod((raw + 45) / 90, 4) * 90
}

/** `Surface.ROTATION_*` constant (0…3) for a rotation in degrees, snapped to 90°. */
fun surfaceRotationOf(degrees: Int): Int = Math.floorMod((Math.floorMod(degrees, 360) + 45) / 90, 4)

/** Degrees for a `Surface.ROTATION_*` constant (0…3). */
fun degreesOfSurfaceRotation(surfaceRotation: Int): Int = Math.floorMod(surfaceRotation, 4) * 90

// ── Names and titles ─────────────────────────────────────────────────────────────────────

private const val SESSION_PREFIX = "ar-session-"
private const val SAMPLE_PREFIX = "bundled-"
private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val DAY_OF_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val FULL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

/** File name of a new recording: `ar-session-20260925-140512.mp4`. */
fun recordingFileName(at: LocalDateTime): String = "$SESSION_PREFIX${FILE_STAMP.format(at)}.mp4"

/** What a recording is called on screen — never its raw file name. */
sealed interface RecordingTitle {
    /** Recorded in this app at [at]. */
    data class Captured(val at: LocalDateTime) : RecordingTitle

    /** The sample recording shipped with debug builds. */
    data object Sample : RecordingTitle

    /** Any other file dropped into the folder (for example with `adb push`). */
    data class Named(val name: String) : RecordingTitle
}

fun recordingTitleOf(fileName: String): RecordingTitle {
    val base = fileName.substringBeforeLast('.')
    if (base.startsWith(SAMPLE_PREFIX)) return RecordingTitle.Sample
    if (base.startsWith(SESSION_PREFIX)) {
        try {
            return RecordingTitle.Captured(LocalDateTime.parse(base.removePrefix(SESSION_PREFIX), FILE_STAMP))
        } catch (_: DateTimeParseException) {
            // Falls through to the plain name.
        }
    }
    return RecordingTitle.Named(base)
}

/** "Today, 14:05", "Yesterday, 09:12", "Sep 24, 14:05", "Sep 24, 2025", "Sample recording". */
fun formatRecordingTitle(title: RecordingTitle, today: LocalDate): String = when (title) {
    RecordingTitle.Sample -> "Sample recording"
    is RecordingTitle.Named -> title.name
    is RecordingTitle.Captured -> {
        val day = title.at.toLocalDate()
        when {
            day == today -> "Today, ${TIME.format(title.at)}"
            day == today.minusDays(1) -> "Yesterday, ${TIME.format(title.at)}"
            day.year == today.year -> "${DAY_OF_YEAR.format(title.at)}, ${TIME.format(title.at)}"
            else -> FULL_DATE.format(title.at)
        }
    }
}

// ── Numbers ──────────────────────────────────────────────────────────────────────────────

/** Media-player clock: "0:07", "12:05", "1:02:03". */
fun formatClock(millis: Long): String {
    val total = millis.coerceAtLeast(0L) / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f kB", bytes / 1_000.0)
    else -> "$bytes B"
}

/** How far the phone moved: "35 cm" under a metre, "1.2 m" above. */
fun formatDistance(meters: Float): String {
    val m = if (meters.isFinite()) meters.coerceAtLeast(0f) else 0f
    return if (m < 1f) "${(m * 100).roundToInt()} cm" else String.format(Locale.US, "%.1f m", m)
}

private fun formatCount(n: Int): String = String.format(Locale.US, "%,d", n)

// ── What a recording contains ────────────────────────────────────────────────────────────

/**
 * The tracks found inside a recording file, grouped the way the user thinks about them.
 *
 * An ARCore recording is an ordinary MP4: one video track the gallery app can play, plus
 * data tracks ARCore reads back on replay — accelerometer, gyroscope, per-frame camera data
 * — and any track the app added itself, like this demo's placements.
 */
data class RecordingContents(
    val hasVideo: Boolean,
    val motionSensorTracks: Int,
    val hasCameraData: Boolean,
    val hasPlacements: Boolean,
    val otherDataTracks: Int,
)

/** Classifies the MIME types an `MediaExtractor` lists for a recording. */
fun recordingContentsOf(mimeTypes: List<String>): RecordingContents {
    var video = false
    var motion = 0
    var camera = false
    var placements = false
    var other = 0
    for (raw in mimeTypes) {
        val mime = raw.lowercase(Locale.US)
        when {
            mime.startsWith("video/") -> video = true
            mime == PlacementTrack.MIME_TYPE -> placements = true
            mime.startsWith("application/arcore-accel") ||
                mime.startsWith("application/arcore-gyro") -> motion++
            mime.startsWith("application/arcore-video") -> camera = true
            mime.startsWith("application/") -> other++
        }
    }
    return RecordingContents(video, motion, camera, placements, other)
}

/**
 * One line naming what a recording holds: "Camera video · Motion sensors · 2 placements".
 * [placementCount] is `null` when unknown; the placements part is then named without a count,
 * and left out when the count is known to be zero.
 */
fun recordingContentsLine(contents: RecordingContents, placementCount: Int?): String {
    val parts = buildList {
        if (contents.hasVideo) add("Camera video")
        if (contents.motionSensorTracks > 0) add("Motion sensors")
        val showPlacements = if (placementCount == null) contents.hasPlacements else placementCount > 0
        if (showPlacements) {
            add(
                when (placementCount) {
                    null -> "Your placements"
                    1 -> "1 placement"
                    else -> "$placementCount placements"
                }
            )
        }
    }
    return if (parts.isEmpty()) "Camera video" else parts.joinToString(" · ")
}

// ── Live numbers ─────────────────────────────────────────────────────────────────────────

/** One figure of a live-stats row: a big [value] over a small [label]. */
data class LiveStat(val value: String, val label: String)

/**
 * The four numbers shown while recording and while replaying: camera frames captured, how
 * far the phone moved, surfaces found, and placements ([placementsLabel] is "Placed" while
 * recording, "Restored" on replay).
 */
fun liveCaptureStats(
    frames: Int,
    metersMoved: Float,
    surfaces: Int,
    placements: Int,
    placementsLabel: String = "Placed",
): List<LiveStat> = listOf(
    LiveStat(formatCount(frames), if (frames == 1) "Frame" else "Frames"),
    LiveStat(formatDistance(metersMoved), "Moved"),
    LiveStat(formatCount(surfaces), if (surfaces == 1) "Surface" else "Surfaces"),
    LiveStat(formatCount(placements), placementsLabel),
)

// ── Take quality ─────────────────────────────────────────────────────────────────────────

/** Share of frames at or above which a take is called steady. */
const val STEADY_PERCENT_THRESHOLD = 70

enum class TakeQuality { Steady, Unsteady, Empty }

fun takeQualityOf(trackedFrames: Int, totalFrames: Int): TakeQuality = when {
    totalFrames <= 0 -> TakeQuality.Empty
    steadyPercent(trackedFrames, totalFrames) >= STEADY_PERCENT_THRESHOLD -> TakeQuality.Steady
    else -> TakeQuality.Unsteady
}

fun steadyPercent(trackedFrames: Int, totalFrames: Int): Int =
    if (totalFrames <= 0) 0 else (trackedFrames.coerceIn(0, totalFrames) * 100) / totalFrames

/** The one sentence under a finished take or replay — no "tracking", no "ARCore". */
fun takeQualityLine(trackedFrames: Int, totalFrames: Int): String {
    val percent = steadyPercent(trackedFrames, totalFrames)
    return when (takeQualityOf(trackedFrames, totalFrames)) {
        TakeQuality.Steady -> "The camera held its position $percent% of the time. This take replays well."
        TakeQuality.Unsteady ->
            "The camera held its position only $percent% of the time. Parts of this take may not replay."
        TakeQuality.Empty -> "No camera frames yet."
    }
}

/**
 * Plain name for an ARCore tracking-failure reason, keyed on the enum name so this file
 * stays free of ARCore types.
 */
fun lostReasonLabel(reasonName: String): String = when (reasonName) {
    "INSUFFICIENT_LIGHT" -> "Too dark"
    "EXCESSIVE_MOTION" -> "Moved too fast"
    "INSUFFICIENT_FEATURES" -> "Not enough detail"
    "CAMERA_UNAVAILABLE" -> "Camera in use"
    "BAD_STATE" -> "Restarting"
    else -> "Lost position"
}

/**
 * Where a take lost its place, worst first: "Moved too fast · 5%", at most [limit] lines.
 * [framesByReason] is keyed on the ARCore reason's enum name, as [lostReasonLabel].
 */
fun lostReasonLines(framesByReason: Map<String, Int>, totalFrames: Int, limit: Int = 3): List<String> {
    if (totalFrames <= 0) return emptyList()
    return framesByReason.entries
        .filter { it.value > 0 }
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { (reason, frames) ->
            val percent = ((frames.toLong() * 100 + totalFrames / 2) / totalFrames).coerceAtLeast(1)
            "${lostReasonLabel(reason)} · $percent%"
        }
}

/** The line under "Recording saved": "0:18 · 4.1 MB · 2 placements". */
fun savedTakeSummary(durationMillis: Long, sizeBytes: Long, placements: Int): String = buildList {
    add(formatClock(durationMillis))
    add(formatFileSize(sizeBytes))
    when {
        placements == 1 -> add("1 placement")
        placements > 1 -> add("$placements placements")
    }
}.joinToString(" · ")

/**
 * The line above the shutter once the camera has its bearings: what to do first, then what a
 * take will hold — said before recording, not discovered after.
 */
fun readyToRecordLine(placements: Int): String = when {
    placements <= 0 -> "Tap a surface to place a fox, then record."
    placements == 1 -> "Ready. A take keeps the camera video, the motion sensors and your fox."
    else -> "Ready. A take keeps the camera video, the motion sensors and your $placements foxes."
}

/**
 * What to tell the user when the recorder fails. `ARRecorder.errorMessage` is written for
 * developers ("no AR session attached — call recordFrame(session) first"), so it is never
 * shown; [storageFailed] is `ARRecorder.State.IO_ERROR`, a take ARCore stopped mid-way.
 */
fun recorderErrorLine(storageFailed: Boolean): String =
    if (storageFailed) {
        "Recording stopped: the phone couldn't write the file. Free up some space, then record again."
    } else {
        "The recording couldn't start. Hold the phone still for a second, then try again."
    }

// ── Replay ───────────────────────────────────────────────────────────────────────────────

enum class ReplayPhase { Choosing, Loading, Playing, Finished }

fun replayPhaseOf(hasSelection: Boolean, firstFrameSeen: Boolean, finished: Boolean): ReplayPhase = when {
    !hasSelection -> ReplayPhase.Choosing
    finished -> ReplayPhase.Finished
    !firstFrameSeen -> ReplayPhase.Loading
    else -> ReplayPhase.Playing
}

/** Replay position in `0f..1f`; `0f` while the length is unknown. */
fun replayProgress(elapsedMillis: Long, durationMillis: Long): Float =
    if (durationMillis <= 0L) 0f else (elapsedMillis.toFloat() / durationMillis).coerceIn(0f, 1f)

// ── QA ───────────────────────────────────────────────────────────────────────────────────

/**
 * Screens the QA harness can open directly with `--es qa_state <id>` (`qa_mode` only): the
 * phases that otherwise need a live AR session, which the emulator cannot run.
 */
enum class RecordingQaState(val id: String) {
    Recording("recording"),
    Saved("saved"),
    Recordings("recordings"),
    Replaying("replaying"),
    ReplayFinished("replay-finished"),
    Empty("empty");

    companion object {
        fun of(id: String?): RecordingQaState? = entries.firstOrNull { it.id == id }
    }
}

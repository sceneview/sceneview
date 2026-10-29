package io.github.sceneview.demo.demos.internal

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/*
 * The Rerun demo's Record mode: a room scanned live with ARCore and rebuilt in the same 3D replay
 * as the bundled session — the camera's path, a photo every few steps, the feature points in the
 * colours the camera saw them in, and the planes. Nothing leaves the phone.
 *
 * Everything here is pure Kotlin, tested on the JVM: which poses earn a photo, how a world point
 * lands on ARCore's camera image and what colour it finds there, how that image is turned upright
 * for a thumbnail, and how the photos are packed for the replay. The Android side (RerunScan.kt)
 * only hands it ARCore's buffers.
 */

/**
 * Pinhole intrinsics of ARCore's CPU camera image (`Camera.getImageIntrinsics()`), in the
 * image's own orientation — the sensor's, landscape on a phone.
 */
data class ScanIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
) {
    val isUsable: Boolean get() = fx > 0f && fy > 0f && width > 0 && height > 0

    /** Whether pixel coordinates ([u], [v]) fall on the image. */
    fun contains(u: Float, v: Float): Boolean = u >= 0f && v >= 0f && u < width && v < height
}

/**
 * A `YUV_420_888` camera image, read in place: the luma plane at full size, the two chroma planes
 * at half size in both directions, each with its own row and pixel strides (the chroma planes of
 * an NV21 buffer share memory and have a pixel stride of 2).
 */
class YuvFrame(
    val width: Int,
    val height: Int,
    private val y: ByteBuffer,
    private val yRowStride: Int,
    private val u: ByteBuffer,
    private val v: ByteBuffer,
    private val uvRowStride: Int,
    private val uvPixelStride: Int,
) {
    /** The colour at pixel ([px], [py]), `0xFFRRGGBB`; coordinates are clamped to the image. */
    fun argb(px: Int, py: Int): Int {
        val x = px.coerceIn(0, width - 1)
        val yy = py.coerceIn(0, height - 1)
        val luma = y.get(yy * yRowStride + x).toInt() and 0xFF
        val chroma = (yy shr 1) * uvRowStride + (x shr 1) * uvPixelStride
        return ScanColor.yuvToArgb(luma, u.get(chroma).toInt() and 0xFF, v.get(chroma).toInt() and 0xFF)
    }
}

/** Colour conversion of the camera's frames. */
object ScanColor {
    /**
     * Full-range BT.601 YUV → `0xFFRRGGBB`: what the camera HAL's `YUV_420_888` carries, the
     * conversion `YuvImage` → JPEG applies too. Fixed point, 16 fractional bits.
     */
    @Suppress("MagicNumber") // the BT.601 coefficients
    fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val d = u - 128
        val e = v - 128
        val r = (y + ((91_881 * e) shr 16)).coerceIn(0, 255)
        val g = (y - ((22_554 * d + 46_802 * e) shr 16)).coerceIn(0, 255)
        val b = (y + ((116_130 * d) shr 16)).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Averages `0xFFRRGGBB` colours channel by channel. */
    fun average(colors: IntArray, count: Int = colors.size): Int {
        if (count <= 0) return 0
        var r = 0
        var g = 0
        var b = 0
        for (i in 0 until count) {
            val c = colors[i]
            r += c shr 16 and 0xFF
            g += c shr 8 and 0xFF
            b += c and 0xFF
        }
        return (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
    }
}

/**
 * Where things land on ARCore's camera image, and what the scan takes from it.
 *
 * Two poses are in play. `Camera.getPose()` ([sensor]) is the physical camera, its +X to the
 * right of the image and +Y up it, looking down -Z: a point projects onto the CPU image with
 * the intrinsics as they are. `Camera.getDisplayOrientedPose()` ([display]) is the same camera
 * turned to the screen — upright in portrait — and it is the pose the trail and the frustums use.
 * A photo meant to sit in a frustum is therefore resampled from the sensor image through the
 * rotation between the two.
 */
object ScanProjection {

    /** The world point ([x], [y], [z]) in the local frame of a camera at [pose]. */
    fun toLocal(pose: DebugPose, x: Float, y: Float, z: Float): Vec3 =
        DebugPose(0f, 0f, 0f, -pose.qx, -pose.qy, -pose.qz, pose.qw).rotate(x - pose.x, y - pose.y, z - pose.z)

    /**
     * Pixel coordinates `(u, v)` of the world point on the image of a camera at [sensor], packed
     * in a [FloatArray] of two; `null` behind the camera or outside the image.
     */
    fun project(intrinsics: ScanIntrinsics, sensor: DebugPose, x: Float, y: Float, z: Float): FloatArray? {
        val p = toLocal(sensor, x, y, z)
        if (p.z > -MIN_DEPTH_M) return null
        val u = intrinsics.cx + intrinsics.fx * p.x / -p.z
        val v = intrinsics.cy - intrinsics.fy * p.y / -p.z
        return if (intrinsics.contains(u, v)) floatArrayOf(u, v) else null
    }

    /**
     * One colour per point of the flat `[x,y,z, …]` [points], read from the camera [image] a
     * camera at [sensor] took; `0` for a point the image does not show. Each colour is the mean
     * of the pixel and its four neighbours two pixels out, so sensor noise does not paint a
     * point with one hot pixel.
     */
    fun colors(image: YuvFrame, intrinsics: ScanIntrinsics, sensor: DebugPose, points: FloatArray): IntArray {
        val count = points.size / 3
        val out = IntArray(count)
        val taps = IntArray(TAPS.size / 2)
        for (i in 0 until count) {
            val uv = project(intrinsics, sensor, points[i * 3], points[i * 3 + 1], points[i * 3 + 2]) ?: continue
            val px = uv[0].toInt()
            val py = uv[1].toInt()
            for (k in taps.indices) taps[k] = image.argb(px + TAPS[k * 2], py + TAPS[k * 2 + 1])
            out[i] = ScanColor.average(taps)
        }
        return out
    }

    /**
     * The frustum proportions of the camera as the screen holds it: the sensor image's half
     * extents at one metre, swapped when the display turns the image a quarter.
     */
    fun displayLens(intrinsics: ScanIntrinsics, sensor: DebugPose, display: DebugPose): ReplayLens {
        val halfW = intrinsics.width / 2f / intrinsics.fx
        val halfH = intrinsics.height / 2f / intrinsics.fy
        return if (isQuarterTurn(sensor, display)) ReplayLens(halfH, halfW) else ReplayLens(halfW, halfH)
    }

    /** Whether the screen's X axis runs along the image's Y axis — portrait on a phone. */
    fun isQuarterTurn(sensor: DebugPose, display: DebugPose): Boolean {
        val right = display.rotate(1f, 0f, 0f)
        val sensorRotation = DebugPose(0f, 0f, 0f, sensor.qx, sensor.qy, sensor.qz, sensor.qw)
        val inSensor = toLocal(sensorRotation, right.x, right.y, right.z)
        return abs(inSensor.y) > abs(inSensor.x)
    }

    /** Output size of an upright photo whose long side is [longSide]: portrait when the lens is. */
    fun photoSize(lens: ReplayLens, longSide: Int): Pair<Int, Int> {
        val aspect = lens.halfWidthPerDepth / lens.halfHeightPerDepth
        return if (aspect <= 1f) {
            (longSide * aspect).roundToInt().coerceAtLeast(1) to longSide
        } else {
            longSide to (longSide / aspect).roundToInt().coerceAtLeast(1)
        }
    }

    /**
     * The camera [image] as the screen held it: [outWidth] × [outHeight] `0xFFRRGGBB` pixels,
     * row-major, framed by [lens] around the [display] camera, each pixel the mean of a 2×2
     * grid of samples of the sensor image. What falls outside the sensor image is black.
     *
     * The ray through an output pixel is linear in its coordinates and a rotation keeps it so:
     * the sensor-space ray is `origin + x·stepX + y·stepY`, three vectors rotated once, and each
     * sample costs one division.
     */
    @Suppress("LongParameterList") // the image, its camera, the two poses and the output size
    fun uprightImage(
        image: YuvFrame,
        intrinsics: ScanIntrinsics,
        sensor: DebugPose,
        display: DebugPose,
        lens: ReplayLens,
        outWidth: Int,
        outHeight: Int,
    ): IntArray {
        val out = IntArray(outWidth * outHeight)
        val sx = 2f * lens.halfWidthPerDepth / outWidth
        val sy = -2f * lens.halfHeightPerDepth / outHeight
        val origin = sensorRay(sensor, display, -lens.halfWidthPerDepth, lens.halfHeightPerDepth, -1f)
        val stepX = sensorRay(sensor, display, sx, 0f, 0f)
        val stepY = sensorRay(sensor, display, 0f, sy, 0f)
        val samples = IntArray(4)
        for (oy in 0 until outHeight) {
            for (ox in 0 until outWidth) {
                var n = 0
                for (sub in 0 until 4) {
                    val fx = ox + SUB[sub * 2]
                    val fy = oy + SUB[sub * 2 + 1]
                    val color = sample(
                        image,
                        intrinsics,
                        origin.x + stepX.x * fx + stepY.x * fy,
                        origin.y + stepX.y * fx + stepY.y * fy,
                        origin.z + stepX.z * fx + stepY.z * fy,
                    )
                    if (color != NO_SAMPLE) samples[n++] = color
                }
                out[oy * outWidth + ox] = if (n == 0) OPAQUE_BLACK else ScanColor.average(samples, n)
            }
        }
        return out
    }

    /**
     * The colour the sensor ray ([rx], [ry], [rz]) finds on [image], or [NO_SAMPLE] when it
     * points behind the lens or off the image. A real colour is opaque, so never `0`.
     */
    private fun sample(image: YuvFrame, intrinsics: ScanIntrinsics, rx: Float, ry: Float, rz: Float): Int {
        if (rz > -MIN_DEPTH_M) return NO_SAMPLE
        val u = intrinsics.cx + intrinsics.fx * rx / -rz
        val v = intrinsics.cy - intrinsics.fy * ry / -rz
        return if (intrinsics.contains(u, v)) image.argb(u.toInt(), v.toInt()) else NO_SAMPLE
    }

    /** The display-camera vector ([x], [y], [z]) in the sensor camera's frame. */
    private fun sensorRay(sensor: DebugPose, display: DebugPose, x: Float, y: Float, z: Float): Vec3 {
        val world = display.rotate(x, y, z)
        return toLocal(DebugPose(0f, 0f, 0f, sensor.qx, sensor.qy, sensor.qz, sensor.qw), world.x, world.y, world.z)
    }

    /** Nothing nearer the lens than this projects: ARCore's own near plane is further out. */
    private const val MIN_DEPTH_M = 0.01f

    private const val OPAQUE_BLACK = 0xFF000000.toInt()
    private const val NO_SAMPLE = 0

    /** The colour taps: the pixel, then two pixels left, right, up and down. */
    private val TAPS = intArrayOf(0, 0, -2, 0, 2, 0, 0, -2, 0, 2)

    /** 2×2 sub-pixel grid, in output pixels. */
    private val SUB = floatArrayOf(0.25f, 0.25f, 0.75f, 0.25f, 0.25f, 0.75f, 0.75f, 0.75f)
}

/**
 * Which camera poses earn a photo: the first one, then any that moved [minStepM] or turned
 * [minTurnDegrees] from the last photo's — a photo every few steps and every glance, never two
 * of the same view. Stops at [maxPhotos] so a long scan cannot fill the memory.
 */
class KeyframeGate(
    private val minStepM: Float = MIN_STEP_M,
    minTurnDegrees: Float = MIN_TURN_DEGREES,
    private val maxPhotos: Int = MAX_PHOTOS,
) {
    /** cos(½·turn): two unit quaternions `turn` apart have `|dot|` equal to it. */
    private val minTurnDot = cos(Math.toRadians(minTurnDegrees / 2.0)).toFloat()
    private var last: DebugPose? = null

    var count: Int = 0
        private set

    val isFull: Boolean get() = count >= maxPhotos

    /** Whether a photo taken from [pose] would be kept. Does not commit: see [accept]. */
    fun wants(pose: DebugPose): Boolean {
        if (isFull) return false
        val previous = last ?: return true
        val dx = pose.x - previous.x
        val dy = pose.y - previous.y
        val dz = pose.z - previous.z
        if (dx * dx + dy * dy + dz * dz >= minStepM * minStepM) return true
        val dot = abs(pose.qx * previous.qx + pose.qy * previous.qy + pose.qz * previous.qz + pose.qw * previous.qw)
        return dot < minTurnDot
    }

    /** Records that a photo was taken from [pose]. */
    fun accept(pose: DebugPose) {
        last = pose
        count++
    }

    companion object {
        const val MIN_STEP_M = 0.15f
        const val MIN_TURN_DEGREES = 10f

        /** Photo count bound; encoded byte and resolution limits live in [ScanPhotoPolicy]. */
        const val MAX_PHOTOS = 300
    }
}

/**
 * The scan's photos, packed the way the bundled replay ships its own: one archive of JPEGs and
 * the span of each, so the replay reads a scan and the showcase through the same code.
 */
object ScanArchive {
    /** Where photo [index] (0-based) lives in the scan: `scan/frame-0001.jpg` for the first. */
    fun photoPath(index: Int): String = String.format(Locale.US, "scan/frame-%04d.jpg", index + 1)

    /** Concatenates [photos] (path → bytes, in order) and indexes each. */
    fun pack(photos: List<Pair<String, ByteArray>>): Pair<ByteArray, Map<String, MediaSpan>> {
        val out = ByteArrayOutputStream(photos.sumOf { it.second.size })
        val spans = LinkedHashMap<String, MediaSpan>()
        for ((path, bytes) in photos) {
            if (bytes.isEmpty()) continue
            spans[path] = MediaSpan(out.size(), bytes.size)
            out.write(bytes)
        }
        return out.toByteArray() to spans
    }
}


/** Copy of the Record mode, pure so a test holds it to what the brief promised. */
object ScanCopy {
    /** The promise under the Start button, and in the sheet. */
    const val PRIVACY = "Saved on your phone. Nothing leaves it unless you share it."

    const val IDLE_TITLE = "3D Scan"
    const val IDLE_DETAIL = "Move the phone slowly around a space. $PRIVACY"
    const val WAITING = "Move slowly to get started."

    // The landing (#4627): one name for the feature, "3D Scan", on every surface.
    const val LANDING_TITLE = "3D Scan"
    const val LANDING_BODY = "Move around a space and watch it take shape."

    /** The landing's one primary action. */
    const val RECORD = "Start scanning"
    const val WATCH_SAMPLE = "See an example"
    const val OPEN_FILE = "Open scan"
    const val SESSIONS_TITLE = "Your scans"
    const val ON_THIS_PHONE = "On this phone"
    const val SESSIONS_EMPTY_TITLE = "No scans yet"
    const val SESSIONS_EMPTY = "Scans you make are kept here, on this phone, until you delete them. " +
        "You can also open a .svscan scan file."
    const val OPENING_FILE = "Opening scan…"
    const val DELETE = "Delete"
    const val DELETE_DETAIL = "It is removed from this phone. Files you already shared are not affected."
    const val OPEN_FAILED = "This scan could not be opened."
    const val SAVE_FAILED = "Couldn't save your scan"
    const val SAVE_FAILED_DETAIL = "Your scan is still here, not saved yet. Try again, or discard it."
    const val SAVE_AS_FILE = "Save as file"
    const val SAVING_FILE = "Preparing the file…"
    const val SAVE_AS_FILE_FAILED = "The file could not be written either. Free up some space, then try again."

    /**
     * What the "Couldn't save" card says under its title: why the save failed and what to do,
     * [scanBytes] being what the scan needs on the phone, [refusal] why a part scanned again did
     * not line up (#4815). `null` — a failure not yet read — says what it always said.
     */
    fun saveFailedDetail(
        failure: ScanSaveFailure?,
        scanBytes: Long = 0L,
        refusal: RescanRefusal? = null,
    ): String = when (failure) {
        ScanSaveFailure.Memory ->
            "Your phone ran out of memory saving this long scan. It is still here: try again, " +
                "or save it as a file to keep it."
        ScanSaveFailure.Storage ->
            "Your phone's storage is full" +
                (if (scanBytes > 0) ": this scan needs about ${ScanSaveFailure.mb(scanBytes)}" else "") +
                ". Free up some space, then try again. Your scan is still here."
        ScanSaveFailure.Empty ->
            "This scan caught no camera path, points or surfaces, so there is nothing to keep. " +
                "Discard it and scan again."
        ScanSaveFailure.Other ->
            "Something went wrong saving this scan. It is still here: try again, or save it as a file."
        ScanSaveFailure.Refused -> rescanRefusedDetail(refusal)
        null -> SAVE_FAILED_DETAIL
    }

    /** The card's title: a part that did not line up was never a failed write. */
    fun saveFailedTitle(failure: ScanSaveFailure?): String =
        if (failure == ScanSaveFailure.Refused) RESCAN_REFUSED_TITLE else SAVE_FAILED

    /**
     * The card's main action: Try again, or — for a part that did not line up, which lines up
     * no better a second time — Save as a separate scan.
     */
    fun saveFailedAction(failure: ScanSaveFailure?): String =
        if (failure == ScanSaveFailure.Refused) SAVE_SEPARATE else TRY_AGAIN

    /**
     * Why a part scanned again (#4736) did not line up on its scan, [why] being what lining it up
     * came to (`null`: the re-scan held no depth points), and what keeps it (#4815).
     */
    fun rescanRefusedDetail(why: RescanRefusal?): String {
        val reason = when (why) {
            RescanRefusal.TooLittleOverlap -> "too little of what you scanned before is in it"
            RescanRefusal.NoFloor -> "it shows no floor to line it up on"
            RescanRefusal.NoWalls -> "it shows no wall to line it up on"
            RescanRefusal.Unconstrained -> "it shows too little of the walls around it to hold it in place"
            RescanRefusal.Ambiguous -> "two places in your scan fit it equally well"
            RescanRefusal.Contradicts -> "lined up, it would put surfaces where your scan saw open space"
            RescanRefusal.NoNormals -> "your scan holds no surface directions to line it up on"
            null -> "it holds no depth points to line up"
        }
        return "This part didn't line up with your scan: $reason. Save it as a separate scan to keep " +
            "it, or discard it. Your scan stays as it was."
    }

    const val RESCAN_REFUSED_TITLE = "Couldn't line up this part"
    const val SAVE_SEPARATE = "Save as a separate scan"

    /** A save that failed after its capture screen was left (#4500): said on the landing. */
    const val SAVE_FAILED_AWAY = "Couldn't save your last scan."

    /** `Delete "Room · Sep 28, 2:32 PM"?` */
    fun deleteTitle(title: String): String = "Delete “$title”?"

    /** Why [file] did not open, in the user's words. */
    fun importFailure(failure: RerunImportFailure, file: String): String {
        val name = "“$file”"
        return when (failure) {
            is RerunImportFailure.Unsupported -> "$name is not a SceneView scan file or a Rerun recording."
            is RerunImportFailure.Empty -> "$name holds no camera path, points or surfaces to replay."
            is RerunImportFailure.RrdCompressed ->
                "$name is a compressed Rerun recording. Save it again from Rerun without compression."
            is RerunImportFailure.RrdNewerVersion ->
                "$name comes from a newer version of Rerun than this app can read."
            is RerunImportFailure.Unreadable -> "$name could not be read. It may be damaged or cut short."
        }
    }

    /**
     * A kept session's figures: `4.2 m · 3.8k points · 42 photos · 0:18`, as on iOS. The path
     * length takes the replay's units under [locale] (`13 ft 11 in` in US English), so the card
     * and the replay it opens never disagree.
     */
    fun figures(
        pathMetres: Float,
        points: Int,
        photos: Int,
        seconds: Float,
        locale: Locale = Locale.getDefault(),
    ): String {
        val parts = mutableListOf(
            scanPathLength(pathMetres, locale),
            "${ArDebugFormat.compactCount(points, locale)} ${label(points, "point", "points")}",
        )
        if (photos > 0) parts += "${ArDebugFormat.count(photos, locale)} ${label(photos, "photo", "photos")}"
        if (seconds.isFinite() && seconds > 0f) parts += ArDebugFormat.clock(seconds)
        return parts.joinToString(" · ")
    }

    const val LOADING = "Opening your scan…"
    const val REPLAY_TITLE = "Your scan"
    const val SAMPLE_TITLE = "Example scan"
    /**
     * Said once, briefly, when the photos run out (#4717), and not again: past the limit the
     * scan's points, their colours and its path still record, so nothing is left to warn about.
     * Worded as news, not as a limit hit (#4807): "Photo limit reached" read as a fault.
     */
    const val FULL = "All photos taken — still scanning"

    // The capture lifecycle (#4627): Scanning ⇄ Paused, Finish opens a review that Keep scanning
    // leaves for the same capture; only Save scan ends it.
    const val SCANNING = "Scanning"
    const val PAUSED = "Paused"
    const val PAUSE = "Pause"
    const val RESUME = "Resume"
    const val FINISH = "Finish"

    /** The stop shutter's spoken name (#4807): it ends the capture and opens the review. */
    const val FINISH_SCAN = "Finish scan"
    const val COACH_SCANNING = "Move slowly sideways"
    const val COACH_PAUSED = "Nothing is captured while paused."

    // ScanCoach's lines (#4631): short, and said only while their cause holds.
    const val COACH_SLOWER = "Move slower"
    const val COACH_NOTHING_NEW = "Point at the floor or a wall, 1 to 3 m away"

    /**
     * Nothing passes the usual confidence on the surface in view, or only relaxed samples do
     * (#4717): a plain wall, a tiled floor, a bright sky. Closer, the sensor has more to read.
     */
    const val COACH_LOW_TEXTURE = "Move closer — this surface has little texture"

    // The dense map past its budget (#4629): said once while it merges, never as a limit.
    const val ADJUSTING_DETAIL = "Adjusting detail for a larger space"
    const val SCAN_FULL = "This scan is full. Save it to keep what you have."
    const val TRACKING_LOST = "Scan paused. Point at an area you already scanned."
    const val REVIEW_TITLE = "Review your scan"
    const val REVIEW_DETAIL = "Keep scanning to add more, or save it on this phone."
    const val EMPTY_TITLE = "Nothing captured yet"
    const val EMPTY_DETAIL = "Move the phone slowly around the space, then tap stop again."
    const val KEEP_SCANNING = "Keep scanning"
    const val SAVE_SCAN = "Save scan"
    const val SAVING = "Saving scan…"
    const val TRY_AGAIN = "Try again"
    const val DISCARD = "Discard"
    const val CANCEL = "Cancel"
    const val LEAVE_TITLE = "Leave this scan?"
    const val LEAVE_DETAIL = "Save it on this phone, or discard what you captured."
    const val LEAVE_EMPTY_DETAIL = "Nothing captured yet. Keep scanning, or discard it and leave."
    const val DISCARD_TITLE = "Discard this scan?"
    const val DISCARD_DETAIL = "It was never saved, so it can't be recovered."

    /** The top line of the capture: `Scanning · 0:42`, `Paused · 0:42`. */
    fun status(phase: ScanPhase, clock: String): String =
        "${if (phase == ScanPhase.Scanning) SCANNING else PAUSED} · $clock"

    // What the capture is doing, said in one word (#4540 live walls): working, paused, or stuck.
    const val STUCK = "Stuck"

    /** The top line of the capture from what it is doing: `Scanning · 0:42`, `Stuck · 0:42`. */
    fun status(activity: ScanActivity, clock: String): String {
        val word = when (activity) {
            ScanActivity.Working -> SCANNING
            ScanActivity.Paused -> PAUSED
            ScanActivity.Stuck -> STUCK
        }
        return "$word · $clock"
    }

    /** The walls recognised so far, beside the points: `No walls yet`, `1 wall`, `3 walls`. */
    fun wallsFound(walls: Int): String =
        if (walls <= 0) WALLS_NONE else "$walls ${label(walls, "wall", "walls")}"

    const val WALLS_NONE = "No walls yet"

    /**
     * A room whose walls close it, or whose gauge is full: the scan can end (#4540). "stop" is the
     * red shutter that ends a capture (#4807); there is no Finish button to name any more.
     */
    const val ROOM_DONE = "Room complete — tap stop to finish"

    /** A depth scan getting no usable depth: the true cause of a count stuck at zero. */
    const val COACH_NO_DEPTH = "No depth yet — keep the lens clear and move slowly"

    /** The same, once the scan has points: depth came, then stopped — never "yet". */
    const val COACH_DEPTH_LOST = "Depth lost — keep the lens clear and move slowly"

    /** Standing still with nothing new: the scan waits on the phone. */
    const val COACH_STILL = "Move the phone to scan more"

    /** A wall still missing while nothing new comes: plain walls give ARCore little to hold. */
    const val COACH_BLANK_WALL = "Plain wall? Get closer, at an angle, with a corner in view"

    /** Nothing new while a wall is missing, the phone looking at the floor or the ceiling. */
    const val COACH_FACE_WALLS = "Raise the phone to the walls"

    /** Before the first frame tracks: the scan starts by itself, as soon as AR is ready. */
    const val STARTING = "Getting AR ready — the scan starts by itself"

    /**
     * The turn to the part of the room no wall was seen on yet ([ScanGuide.cue]). It names the
     * dashed line the live view draws on the floor there, which nothing else explained (QA
     * 2026-10-22, remark 4); behind the phone, that line is out of view.
     */
    fun turnHint(turn: ScanTurn): String = when (turn) {
        ScanTurn.Left -> "Turn left — no wall yet at the dashed line"
        ScanTurn.Right -> "Turn right — no wall yet at the dashed line"
        ScanTurn.Behind -> "Turn around — no wall seen behind you yet"
        ScanTurn.Ahead -> "Sweep ahead — no wall yet at the dashed line"
    }

    /**
     * The phone faces a wall drawn only where the scan saw it ([io.github.sceneview.demo.demos.internal.WallExtent]):
     * walking along it is what finishes it (QA 2026-10-22, remark 3).
     */
    const val WALL_TO_FINISH = "Walk along this wall to finish it"

    /**
     * The gauge, said aloud: `Room 72 % scanned`, the room's scan done so far
     * ([ScanGuide.completion]), named so it is never read as the floor covered (#4734).
     */
    fun completion(share: Float): String = "Room ${(share.coerceIn(0f, 1f) * PERCENT).toInt()} % scanned"

    private const val PERCENT = 100

    // The next step of a room's scan, from the walls recognised so far (#4540 live walls).
    const val WALLS_FIRST = "Point at a wall and sweep along it"
    const val WALLS_NEXT = "Turn to the next wall"
    const val WALLS_FOLLOW = "Follow the walls round the room"
    const val WALLS_CLOSE = "Keep going round, then tap stop"

    /** The guidance line of a room's scan once it can see walls: what to do next, in a few words. */
    fun wallGuide(walls: Int): String = when {
        walls <= 0 -> WALLS_FIRST
        walls == 1 -> WALLS_NEXT
        walls < ScanGuide.ROOM_WALLS -> WALLS_FOLLOW
        else -> WALLS_CLOSE
    }

    /** The capture's one count, from zero and never against a cap: `124,300 points captured`. */
    fun pointsCaptured(points: Int): String {
        val n = points.coerceAtLeast(0)
        return "${ArDebugFormat.count(n)} ${label(n, "point", "points")} captured"
    }

    /**
     * The save in progress, with what it is doing (#4719): `Fusing 261k points…`, the capture's
     * own count; [SAVING] when it has none to tell.
     */
    fun fusing(points: Int): String =
        if (points <= 0) SAVING else "Fusing ${figure(points)} ${label(points, "point", "points")}…"

    /** What the review says about the capture so far: `124,300 points · 42 photos · 0:42`. */
    fun reviewFigures(points: Int, photos: Int, clock: String): String {
        val parts = mutableListOf("${ArDebugFormat.count(points.coerceAtLeast(0))} ${label(points, "point", "points")}")
        if (photos > 0) parts += "${ArDebugFormat.count(photos)} ${label(photos, "photo", "photos")}"
        parts += clock
        return parts.joinToString(" · ")
    }

    /**
     * The one coaching line under the capture, or `null` when a card speaks instead. Tracking loss
     * first, then a full map, then the photo limit while its one notice lasts ([photoLimitReached]
     * is that notice, not the lasting state, #4717), then what the user can act on — a surface
     * with no usable depth ([depthStarved], before texture: a starved depth gate looks like a
     * blank surface, #4540), with too little texture ([lowTexture]), moving too fast — then the dense map's
     * "Adjusting detail", then the [coach]'s "nothing new" (#4631) and "standing still", then the
     * plain advice. "Nothing new" never shows while the scan's points keep rising
     * ([pointsRising]): the scan adds to the room, and the top line says it is working. A scan
     * that already [hasPoints] says its depth was lost, not that none came yet.
     */
    @Suppress("LongParameterList") // one flag per cause the line can speak of
    fun coaching(
        phase: ScanPhase,
        trackingLost: Boolean,
        photoLimitReached: Boolean,
        detail: ScanDetail = ScanDetail.Fine,
        coach: ScanCoach.Line? = null,
        lowTexture: Boolean = false,
        depthStarved: Boolean = false,
        pointsRising: Boolean = false,
        hasPoints: Boolean = false,
    ): String? = when (phase) {
        ScanPhase.Scanning -> when {
            trackingLost -> TRACKING_LOST
            detail == ScanDetail.Full -> SCAN_FULL
            photoLimitReached -> FULL
            depthStarved -> if (hasPoints) COACH_DEPTH_LOST else COACH_NO_DEPTH
            lowTexture -> COACH_LOW_TEXTURE
            coach == ScanCoach.Line.Slower -> COACH_SLOWER
            detail == ScanDetail.Adjusting -> ADJUSTING_DETAIL
            coach == ScanCoach.Line.NothingNew && !pointsRising -> COACH_NOTHING_NEW
            coach == ScanCoach.Line.Still -> COACH_STILL
            else -> COACH_SCANNING
        }
        ScanPhase.Paused -> COACH_PAUSED
        else -> null
    }

    /** `1` → `1 photo`, `12` → `12 photos`: the figure's label follows its value. */
    fun label(count: Int, one: String, many: String): String = if (count == 1) one else many

    /**
     * A figure of the Record screen, in full up to `9,999` and compact past it (`12k`), so three
     * of them in the display size fit a phone's width side by side.
     */
    fun figure(value: Int): String =
        if (value < COMPACT_FROM) ArDebugFormat.count(value.coerceAtLeast(0)) else ArDebugFormat.compactCount(value)

    private const val COMPACT_FROM = 10_000
}

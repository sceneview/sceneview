package io.github.sceneview.demo.demos.internal

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/*
 * Room Scan's final fusion, on up-to-date poses.
 *
 * While a scan runs, each raw-depth keyframe is fused with the camera pose of its moment. ARCore
 * keeps correcting its map afterwards, so a wall seen again a minute later lands a few
 * centimetres off its first pass: the wall the scan started on, twice. This file keeps the
 * keyframes, each tied to an anchor, so the fusion is redone when the scan stops with the poses
 * ARCore holds then. Pure Kotlin — no Android, no ARCore — so the JVM tests run all of it.
 */

/** What [RerunAnchoredFrames.drainInto] fused, and how far the anchors had moved its frames. */
data class AnchoredRefusion(
    val frames: Int,
    val anchors: Int,
    /** Mean distance between a frame's camera as captured and as corrected, metres. */
    val meanShiftM: Float,
    /** The largest of those distances, metres. */
    val maxShiftM: Float,
)

/**
 * The raw-depth keyframes of a scan in progress, kept in memory so [drainInto] can fuse them
 * again when it stops, each with the camera pose ARCore would give it then.
 *
 * **Anchors.** ARCore corrects an anchor's pose as its map improves, never the camera poses it
 * handed out earlier. So a frame is stored as its camera *relative to* the latest anchor
 * (`anchor⁻¹ ∘ camera`, both as of the frame's own update) and recomposed with that anchor's
 * pose at the end. The latest anchor, not the nearest in space: drift is shared by what was seen
 * around the same moment, and a frame taken back where the scan started, a minute on, carries
 * that minute's drift. An anchor is due ([anchorDue]) once the camera has moved [STEP_M] or
 * turned [TURN_DEG] from the last one. ARCore charges ongoing work for every live anchor and
 * publishes no ceiling, so there are [maxAnchors] at most: at the cap every other anchor is let
 * go ([addAnchor]'s `detach`), its frames re-parented onto the one before it with the
 * correction they had earned so far, and the spacing doubles ([anchorSpacing]).
 *
 * **Memory.** [maxBytes] of frames at most, each [BYTES_PER_PIXEL] bytes a pixel: 16-bit depth,
 * 8-bit confidence, RGB565 colour ([Rgb565]). A depth image above [maxFramePixels] is kept every
 * n-th pixel, which a 2 cm map does not show. At the cap every other frame is dropped and only
 * every other new one is taken from then on ([frameStride]): a long scan thins evenly in time
 * instead of growing.
 *
 * Fed from one thread, the capture's; [drainInto] runs once the feeding has stopped.
 */
class RerunAnchoredFrames(
    val maxBytes: Long = MAX_BYTES,
    val maxAnchors: Int = MAX_ANCHORS,
    val maxFramePixels: Int = MAX_FRAME_PIXELS,
) {
    init {
        require(maxAnchors >= 2 && maxFramePixels >= 1) { "room for two anchors and a pixel" }
    }

    private val frames = ArrayList<KeptFrame>()
    private val anchors = ArrayList<AnchorSlot>()
    private var nextAnchor = 0
    private var offered = 0

    /** Bytes of depth, confidence and colour held. */
    var bytes: Long = 0
        private set

    /** One frame in this many offered is kept: 1 until [maxBytes] is first reached, then doubling. */
    var frameStride: Int = 1
        private set

    /** What [STEP_M] and [TURN_DEG] are multiplied by: 1 until [maxAnchors] is first reached, then doubling. */
    var anchorSpacing: Int = 1
        private set

    val frameCount: Int get() = frames.size
    val anchorCount: Int get() = anchors.size

    /** The anchor new frames are tied to, [NO_ANCHOR] before the first one. */
    val latestAnchor: Int get() = anchors.lastOrNull()?.id ?: NO_ANCHOR

    /**
     * Whether a frame taken from [camera] wants a new anchor: there is none yet, or the camera
     * has moved or turned a spacing away from where the latest one stands.
     */
    fun anchorDue(camera: DebugPose): Boolean {
        val last = anchors.lastOrNull()?.pose ?: return true
        return (camera.position - last.position).length() >= STEP_M * anchorSpacing ||
            angleDeg(camera, last) >= TURN_DEG * anchorSpacing
    }

    /**
     * Records the anchor just dropped at [pose] and returns its id, which later frames are tied
     * to. At [maxAnchors], every other anchor is let go first: [detach] gets each of their ids.
     */
    fun addAnchor(pose: DebugPose, detach: (Int) -> Unit = {}): Int {
        if (anchors.size >= maxAnchors) thinAnchors(detach)
        val id = nextAnchor++
        anchors += AnchorSlot(id, pose)
        return id
    }

    /**
     * Lets every other anchor go before [maxAnchors] is reached, for when ARCore itself has no
     * room for one more: its own ceiling is not ours to read. [detach] gets each of their ids,
     * frames stay where they are, and the next anchors are spaced twice as far. Returns whether
     * any was let go — none is while fewer than two are held.
     */
    fun makeRoom(detach: (Int) -> Unit = {}): Boolean {
        if (anchors.size < 2) return false
        thinAnchors(detach)
        return true
    }

    /** ARCore's latest [pose] for anchor [id]. An id already let go is ignored. */
    fun moveAnchor(id: Int, pose: DebugPose) {
        slot(id)?.pose = pose
    }

    /**
     * Keeps [frame] for the final fusion, tied to the latest anchor — to the world before the
     * first one, which fuses it again exactly where it was. Returns whether it was kept: one frame
     * in [frameStride] is, and none that alone outweighs [maxBytes].
     */
    fun offer(frame: DepthFrame): Boolean {
        val index = offered++
        if (index % frameStride != 0) return false
        val kept = KeptFrame.of(frame, anchors.lastOrNull()?.pose, latestAnchor, maxFramePixels)
        if (kept.bytes > maxBytes) return false
        while (bytes + kept.bytes > maxBytes && frames.size >= 2) thinFrames()
        if (bytes + kept.bytes > maxBytes) return false
        // The stride may just have doubled, and made this frame one of the skipped ones.
        if (index % frameStride != 0) return false
        frames += kept
        bytes += kept.bytes
        return true
    }

    /** Where each kept frame's camera stands now, oldest first: its anchor's latest pose, recomposed. */
    fun poses(): List<DebugPose> = frames.map(::poseOf)

    /**
     * The final fusion: every kept frame, oldest first, back-projected into [fusion] and
     * integrated into [tsdf] — side by side when the caller's dispatcher has a second thread —
     * with the pose its anchor gives it now. Each frame is let go as soon as it is fused, so the
     * store is empty afterwards, and after a cancellation too, which is honoured between two
     * frames. [progress] gets the share of frames fused, up to 1.
     */
    suspend fun drainInto(
        fusion: DenseFusion,
        tsdf: RerunTsdf? = null,
        progress: (Float) -> Unit = {},
    ): AnchoredRefusion {
        val poses = poses()
        val queue: Array<KeptFrame?> = frames.toTypedArray()
        val anchorCount = anchors.size
        frames.clear()
        bytes = 0
        var shiftSum = 0f
        var shiftMax = 0f
        var colors = IntArray(0)
        coroutineScope {
            for (i in queue.indices) {
                ensureActive()
                val kept = queue[i] ?: continue
                queue[i] = null
                val shift = (poses[i].position - kept.captured).length()
                shiftSum += shift
                if (shift > shiftMax) shiftMax = shift
                if (colors.size != kept.pixels) colors = IntArray(kept.pixels)
                val frame = kept.frame(poses[i], colors)
                // Both read the frame, neither writes it; the next one waits for the two.
                val meshing = tsdf?.let { launch { it.integrate(frame) } }
                fusion.add(DepthBackProjection.project(frame))
                meshing?.join()
                progress((i + 1f) / queue.size)
            }
        }
        val mean = if (queue.isEmpty()) 0f else shiftSum / queue.size
        return AnchoredRefusion(queue.size, anchorCount, mean, shiftMax)
    }

    private fun slot(id: Int): AnchorSlot? = anchors.firstOrNull { it.id == id }

    private fun poseOf(frame: KeptFrame): DebugPose = slot(frame.anchor)?.pose?.then(frame.rel) ?: frame.rel

    /** Lets every other anchor go, oldest kept, and doubles the spacing of the next ones. */
    private fun thinAnchors(detach: (Int) -> Unit) {
        // Each anchor let go -> the one before it, and `heir⁻¹ ∘ gone` as they stand now: composed
        // into a frame's relative pose, it leaves the frame where the lost anchor had brought it.
        val heirs = LinkedHashMap<Int, Pair<Int, DebugPose>>()
        val kept = ArrayList<AnchorSlot>(anchors.size / 2 + 1)
        for (i in anchors.indices) {
            val slot = anchors[i]
            if (i % 2 == 0) {
                kept += slot
            } else {
                val heir = anchors[i - 1]
                heirs[slot.id] = heir.id to heir.pose.inverse().then(slot.pose)
            }
        }
        for (frame in frames) {
            val (heir, shift) = heirs[frame.anchor] ?: continue
            frame.anchor = heir
            frame.rel = shift.then(frame.rel)
        }
        anchors.clear()
        anchors += kept
        anchorSpacing *= 2
        heirs.keys.forEach(detach)
    }

    /** Drops every other frame, oldest kept, and halves the share of the next ones taken. */
    private fun thinFrames() {
        var count = 0
        var held = 0L
        for (i in frames.indices step 2) {
            val frame = frames[i]
            frames[count++] = frame
            held += frame.bytes
        }
        frames.subList(count, frames.size).clear()
        bytes = held
        frameStride *= 2
    }

    companion object {
        /**
         * 48 MB of kept frames: 699 of a Pixel's 160 × 90 raw-depth images, over a minute of scan
         * at the capture's ten a second before the first thinning — and what the live TSDF held
         * during a scan before the final fusion took over building it.
         */
        const val MAX_BYTES = 48L * 1024 * 1024

        /** Depth, confidence and colour of one kept pixel. */
        const val BYTES_PER_PIXEL = 5

        /**
         * 160 × 120: a Pixel's raw depth passes whole, a 640 × 480 time-of-flight image is kept
         * every fourth pixel — 1.6 cm apart at 2 m, under the surfel map's 2 cm.
         */
        const val MAX_FRAME_PIXELS = 160 * 120

        /** Live ARCore anchors a scan holds at most. */
        const val MAX_ANCHORS = 40

        /** Half a metre between two anchors, before any thinning. */
        const val STEP_M = 0.5f

        /** Or a 30° turn: a phone turning on the spot drifts in heading without moving. */
        const val TURN_DEG = 30f

        /** The anchor of a frame tied to the world. */
        const val NO_ANCHOR = -1

        private fun angleDeg(a: DebugPose, b: DebugPose): Float {
            val dot = abs(a.qx * b.qx + a.qy * b.qy + a.qz * b.qz + a.qw * b.qw).coerceAtMost(1f)
            return Math.toDegrees(2.0 * acos(dot.toDouble())).toFloat()
        }
    }
}

/** An anchor as the store knows it: its id and ARCore's latest word on where it is. */
private class AnchorSlot(val id: Int, var pose: DebugPose)

/** One kept frame: [DepthFrame]'s pixels, colour packed, and its camera relative to [anchor]. */
@Suppress("LongParameterList")
private class KeptFrame(
    val width: Int,
    val height: Int,
    val depthMm: ShortArray,
    val confidence: ByteArray?,
    val rgb565: ShortArray,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    /** Where the camera stood as ARCore said at capture: what the correction is measured from. */
    val captured: Vec3,
    var anchor: Int,
    var rel: DebugPose,
) {
    val pixels: Int get() = width * height
    val bytes: Long get() = depthMm.size * 2L + (confidence?.size ?: 0) + rgb565.size * 2L

    /** The frame as the fusion reads it, seen from [pose], its colours unpacked into [colors]. */
    fun frame(pose: DebugPose, colors: IntArray): DepthFrame {
        for (i in 0 until pixels) colors[i] = Rgb565.unpack(rgb565[i])
        return DepthFrame(width, height, depthMm, confidence, colors, fx, fy, cx, cy, pose)
    }

    companion object {
        /**
         * [frame] packed, every [pixelStep]-th pixel of it, relative to the anchor at
         * [anchorPose] (the world without one). An image kept whole shares its depth and
         * confidence arrays with [frame], which nothing writes after the capture.
         */
        fun of(frame: DepthFrame, anchorPose: DebugPose?, anchor: Int, maxPixels: Int): KeptFrame {
            val step = pixelStep(frame.width, frame.height, maxPixels)
            val w = (frame.width + step - 1) / step
            val h = (frame.height + step - 1) / step
            val source = { i: Int -> (i / w) * step * frame.width + (i % w) * step }
            val whole = step == 1
            val depth = frame.depthMm.takeIf { whole && it.size == w * h }
                ?: ShortArray(w * h) { frame.depthMm[source(it)] }
            val confidence = frame.confidence?.let { c ->
                c.takeIf { whole && it.size == w * h } ?: ByteArray(w * h) { c[source(it)] }
            }
            return KeptFrame(
                width = w,
                height = h,
                depthMm = depth,
                confidence = confidence,
                rgb565 = ShortArray(w * h) { Rgb565.pack(frame.colors[source(it)]) },
                // Pixel x of the kept image is pixel x·step of the original: the lens scales along.
                fx = frame.fx / step,
                fy = frame.fy / step,
                cx = frame.cx / step,
                cy = frame.cy / step,
                captured = frame.pose.position,
                anchor = anchor,
                rel = anchorPose?.inverse()?.then(frame.pose) ?: frame.pose,
            )
        }

        /** The smallest stride that brings a [width] × [height] image within [maxPixels]. */
        fun pixelStep(width: Int, height: Int, maxPixels: Int): Int {
            var step = 1
            while (((width + step - 1) / step).toLong() * ((height + step - 1) / step) > maxPixels) step++
            return step
        }
    }
}

/**
 * A depth pixel's colour in 16 bits: `0xFFRRGGBB` rounded to 5-6-5, and `0` for "no colour" as in
 * [DepthFrame.colors] — so black, a colour, packs to the darkest blue instead. Off by 4 levels in
 * 255 at most on red and blue, 2 on green: less than two views of one wall differ by.
 */
object Rgb565 {
    fun pack(argb: Int): Short {
        if (argb == 0) return 0
        val r = ((argb shr 16 and 0xFF) * MAX_5 + HALF) / MAX_8
        val g = ((argb shr 8 and 0xFF) * MAX_6 + HALF) / MAX_8
        val b = ((argb and 0xFF) * MAX_5 + HALF) / MAX_8
        val packed = (r shl 11) or (g shl 5) or b
        return (if (packed == 0) 1 else packed).toShort()
    }

    fun unpack(packed: Short): Int {
        val p = packed.toInt() and 0xFFFF
        if (p == 0) return 0
        val r = ((p shr 11) * MAX_8 + MAX_5 / 2) / MAX_5
        val g = ((p shr 5 and MAX_6) * MAX_8 + MAX_6 / 2) / MAX_6
        val b = ((p and MAX_5) * MAX_8 + MAX_5 / 2) / MAX_5
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private const val MAX_5 = 31
    private const val MAX_6 = 63
    private const val MAX_8 = 255
    private const val HALF = 127
}

/** `this ∘ local`: [local], given in this pose's frame, in the frame this pose is given in. */
fun DebugPose.then(local: DebugPose): DebugPose {
    val p = transform(local.x, local.y, local.z)
    val x = qw * local.qx + qx * local.qw + qy * local.qz - qz * local.qy
    val y = qw * local.qy - qx * local.qz + qy * local.qw + qz * local.qx
    val z = qw * local.qz + qx * local.qy - qy * local.qx + qz * local.qw
    val w = qw * local.qw - qx * local.qx - qy * local.qy - qz * local.qz
    // Renormalised: a relative pose re-parented again and again must stay a rotation.
    val n = sqrt(x * x + y * y + z * z + w * w)
    return if (n > 0f) DebugPose(p.x, p.y, p.z, x / n, y / n, z / n, w / n) else DebugPose(p.x, p.y, p.z)
}

/** The pose that undoes this one: `inverse() ∘ this` is the identity. */
fun DebugPose.inverse(): DebugPose {
    val back = DebugPose(0f, 0f, 0f, -qx, -qy, -qz, qw)
    val p = back.rotate(-x, -y, -z)
    return DebugPose(p.x, p.y, p.z, -qx, -qy, -qz, qw)
}

package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.semantic.RecognizedItem
import io.github.sceneview.demo.demos.internal.semantic.SceneFusion
import io.github.sceneview.demo.demos.internal.semantic.SceneLabel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What a tap on a replayed scan says about the piece of furniture under the finger (#4485): its
 * oriented [box], the [label] scene recognition gave it when one of its boxes is that object, and
 * whether the scan saw it from [oneSided] only — then a side it never saw is missing from the box,
 * which measures short.
 */
data class ReplayTapAnswer(val box: ScanBox, val label: SceneLabel?, val oneSided: Boolean)

/**
 * From a [ScanPick] to what the replay writes on the box: pure Kotlin, run off the main thread
 * after the pick, a few hundred microseconds for a sofa.
 */
object ReplayTap {
    /** A box face takes this many of the object's surfels before it counts as seen. */
    const val MIN_FACE_SURFELS = 12

    /** How far from a face's plane a surfel still belongs to it: a few voxels of fusion noise. */
    const val FACE_REACH_M = 0.06f

    /** …and at most this share of the box across, so a thin box's two faces stay apart. */
    const val FACE_REACH_SHARE = 0.25f

    /** A surfel whose normal leans more than this towards up or down is a top or a bottom, not a face. */
    const val MAX_FACE_NORMAL_Y = 0.5f

    /** A surfel faces a side when its normal is within about 45° of it. */
    const val MIN_FACE_ALIGNMENT = 0.7f

    /** One face spread over this share of a side measures that side on its own. */
    const val MIN_SPREAD_SHARE = 0.6f

    /**
     * A recognised object names the tapped box when it spans this much of the box's height at
     * least: a set on its stand, a lamp on a table, is another object over it.
     */
    const val MIN_HEIGHT_SHARE = 0.5f

    /**
     * …and holds this share of the box's points at least, when its points are what name it: a
     * television that is 30 % of the region grown round it and its stand does not name the stand.
     */
    const val MIN_PICK_SHARE = 0.5f

    /** The answer for [pick] in [cloud], named from [recognized]; `null` unless something was boxed. */
    fun answer(cloud: DenseCloud, pick: ScanPick, recognized: List<RecognizedItem>): ReplayTapAnswer? {
        val found = pick as? ScanPick.Found ?: return null
        return ReplayTapAnswer(found.box, nameOf(found, recognized), seenFromOneSide(cloud, found))
    }

    /**
     * The name of the recognised object [found] is: it holds most of [found]'s points
     * ([MIN_PICK_SHARE]), or it stands on the same footprint ([SceneFusion.SAME_FOOTPRINT], the
     * rule scene recognition fuses its own boxes with) — and either way it spans most of
     * [found]'s height ([MIN_HEIGHT_SHARE]). Doors and windows are holes in a wall, never a piece
     * of furniture. Of two candidates the one that overlaps most wins, then the one whose
     * footprint is closest, then the first label in [SceneLabel]'s order: never the order
     * [recognized] comes in.
     */
    fun nameOf(found: ScanPick.Found, recognized: List<RecognizedItem>): SceneLabel? {
        var best: SceneLabel? = null
        var bestScore = 0f
        var bestFootprint = 0f
        for (item in recognized) {
            if (item.label.isOpening) continue
            if (heightShare(found.box, item.box) < MIN_HEIGHT_SHARE) continue
            val share = pickShare(found.surfels, item.surfels)
            val footprint = SceneFusion.footprintIou(found.box, item.box)
            if (share < MIN_PICK_SHARE && footprint < SceneFusion.SAME_FOOTPRINT) continue
            val score = max(share, footprint)
            val better = best == null || score > bestScore ||
                (score == bestScore && (footprint > bestFootprint || (footprint == bestFootprint && item.label < best)))
            if (better) {
                best = item.label
                bestScore = score
                bestFootprint = footprint
            }
        }
        return best
    }

    /**
     * Whether the scan saw [found] from one side only, read from the normals of its surfels: each
     * vertical face of its box is seen when enough of them stand on it with their normal across
     * it, pointing in or out: the sign of a fused normal is not relied on. A side is measured when
     * both its ends were seen, or one face spread along most of it; an object seen face on only
     * has its depth from its top alone, short of its back. A plant, a cushion pile, a cloud
     * without normals tell no face and are never flagged.
     */
    fun seenFromOneSide(cloud: DenseCloud, found: ScanPick.Found): Boolean {
        val normals = cloud.normals ?: return false
        val box = found.box
        val c = cos(box.yaw)
        val s = sin(box.yaw)
        val halfLength = box.length / 2
        val halfDepth = box.depth / 2
        val reachAlong = min(FACE_REACH_M, box.length * FACE_REACH_SHARE)
        val reachAcross = min(FACE_REACH_M, box.depth * FACE_REACH_SHARE)
        // Per face — the two ends (±u), then the two sides (±v) — its surfels and their spread in
        // its plane: across for an end, along for a side.
        val counts = IntArray(FACES)
        val lows = FloatArray(FACES) { Float.MAX_VALUE }
        val highs = FloatArray(FACES) { -Float.MAX_VALUE }
        val p = cloud.positions
        for (i in found.surfels) {
            if (i < 0 || i >= cloud.count) continue
            val ny = normals[i * 3 + 1]
            if (abs(ny) > MAX_FACE_NORMAL_Y) continue
            val nx = normals[i * 3]
            val nz = normals[i * 3 + 2]
            val flat = hypot(nx, nz)
            if (flat < 1e-3f) continue
            val nu = (nx * c + nz * s) / flat
            val nv = (-nx * s + nz * c) / flat
            val dx = p[i * 3] - box.centerX
            val dz = p[i * 3 + 2] - box.centerZ
            val along = dx * c + dz * s
            val across = -dx * s + dz * c
            val face: Int
            val inPlane: Float
            if (abs(nu) >= MIN_FACE_ALIGNMENT && abs(abs(along) - halfLength) <= reachAlong) {
                face = if (along < 0f) END_LOW else END_HIGH
                inPlane = across
            } else if (abs(nv) >= MIN_FACE_ALIGNMENT && abs(abs(across) - halfDepth) <= reachAcross) {
                face = if (across < 0f) SIDE_LOW else SIDE_HIGH
                inPlane = along
            } else {
                continue
            }
            counts[face]++
            lows[face] = min(lows[face], inPlane)
            highs[face] = max(highs[face], inPlane)
        }
        fun seen(face: Int) = counts[face] >= MIN_FACE_SURFELS
        fun spread(face: Int) = if (seen(face)) highs[face] - lows[face] else 0f
        if ((0 until FACES).none(::seen)) return false
        val lengthKnown = (seen(END_LOW) && seen(END_HIGH)) ||
            max(spread(SIDE_LOW), spread(SIDE_HIGH)) >= MIN_SPREAD_SHARE * box.length
        val depthKnown = (seen(SIDE_LOW) && seen(SIDE_HIGH)) ||
            max(spread(END_LOW), spread(END_HIGH)) >= MIN_SPREAD_SHARE * box.depth
        return !(lengthKnown && depthKnown)
    }

    /** [box]'s length, depth and height as the phone reads lengths under [locale] ([MeasureUnitSystem.forLocale]). */
    fun sizes(box: ScanBox, locale: Locale): List<String> {
        val units = MeasureUnitSystem.forLocale(locale)
        return listOf(box.length, box.depth, box.height).map { formatMeasureLength(it, units, locale) }
    }

    /** The share of [pick]'s points [item] holds too; [pick] is sorted, as a pick's surfels are. */
    private fun pickShare(pick: IntArray, item: IntArray): Float {
        if (pick.isEmpty() || item.isEmpty()) return 0f
        val shared = item.count { pick.binarySearch(it) >= 0 }
        return (shared.toFloat() / pick.size).coerceAtMost(1f)
    }

    /** How much of [picked]'s height [item] spans: 1 standing as tall, 0 over or under it. */
    private fun heightShare(picked: ScanBox, item: ScanBox): Float {
        val shared = min(picked.baseY + picked.height, item.baseY + item.height) - max(picked.baseY, item.baseY)
        return if (picked.height <= 0f) 0f else (shared / picked.height).coerceIn(0f, 1f)
    }

    private const val FACES = 4
    private const val END_LOW = 0
    private const val END_HIGH = 1
    private const val SIDE_LOW = 2
    private const val SIDE_HIGH = 3
}

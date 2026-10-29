package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.floor

/*
 * A lined-up re-scan merged into the scan it completes (#4736): the re-scan's surfels that fill
 * in the parts it was for, added to the scan's dense cloud, and the scan's capture written again
 * around the new cloud. The scan's own surfels are never moved nor dropped; the re-scan adds only
 * where the scan has nothing, inside the parts to fill in and a margin around them.
 *
 * Frame: the scan's, metres, +Y up. Pure: off the main thread.
 */
object RescanMerge {
    /**
     * [scan]'s cloud with [rescan]'s points added, moved by [pose]: those inside one of [open]
     * (`[minX, minY, minZ, maxX, maxY, maxZ]`) grown by [RescanRegistrar.OPEN_MARGIN_M], in a
     * [voxelM] voxel with no scan point in it nor next to it. Attributes one of the two lacks are
     * filled in: full confidence, the finest level, no normal.
     */
    fun cloud(
        scan: DenseCloud,
        rescan: DenseCloud,
        pose: RescanPose,
        open: List<FloatArray>,
        voxelM: Float = DenseFusion.VOXEL_M,
    ): DenseCloud {
        if (open.isEmpty() || rescan.count == 0) return scan
        val taken = occupied(scan, voxelM)
        val moved = pose.moved(rescan)
        val added = (0 until moved.count).filter { i ->
            val x = moved.positions[i * 3]
            val y = moved.positions[i * 3 + 1]
            val z = moved.positions[i * 3 + 2]
            open.any { RescanRegistrar.inside(it, x, y, z, RescanRegistrar.OPEN_MARGIN_M) } && !nextTo(taken, x, y, z, voxelM)
        }
        if (added.isEmpty()) return scan
        return join(scan, moved, added)
    }

    /**
     * [original] with its dense cloud replaced by [cloud]: the media archive packed again in the
     * same order, the cloud's blob in place of the old one, and the manifest's `dense` and `media`
     * written to match. Everything else in the manifest, and the log, is kept as it was. `null`
     * when [original] has no dense cloud or its manifest does not parse.
     */
    fun pack(original: RerunCapturePack, cloud: DenseCloud): RerunCapturePack? {
        val text = String(original.manifest)
        val manifest = ReplayManifest.parse(text) ?: return null
        val dense = manifest.dense ?: return null
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val kept = manifest.media.entries
            .filter { it.key != dense.path }
            .sortedBy { it.value.offset }
            .mapNotNull { (path, span) ->
                if (span.offset < 0 || span.length <= 0 || span.offset + span.length > original.media.size) return@mapNotNull null
                path to original.media.copyOfRange(span.offset, span.offset + span.length)
            }
        val (archive, spans) = ScanArchive.pack(kept + (dense.path to SvpcCodec.encode(cloud)))
        val fields = LinkedHashMap<String, JsonElement>(root)
        fields[DENSE] = buildJsonObject {
            put("path", dense.path)
            put("count", cloud.count)
            put("voxelM", dense.voxelM)
            put("normals", cloud.normals != null)
            putJsonArray("bounds") { cloud.bounds().forEach { add(JsonPrimitive(it)) } }
        }
        fields[MEDIA] = buildJsonArray {
            for ((path, span) in spans) {
                add(
                    buildJsonObject {
                        put("path", path)
                        put("offset", span.offset)
                        put("length", span.length)
                    },
                )
            }
        }
        return RerunCapturePack(JsonObject(fields).toString().toByteArray(), original.log, archive)
    }

    /** The voxels [cloud]'s points cover, a coarser point covering its whole surfel. */
    private fun occupied(cloud: DenseCloud, voxelM: Float): HashSet<Long> {
        val out = HashSet<Long>(cloud.count * 2)
        for (i in 0 until cloud.count) {
            val cx = floor(cloud.positions[i * 3] / voxelM).toInt()
            val cy = floor(cloud.positions[i * 3 + 1] / voxelM).toInt()
            val cz = floor(cloud.positions[i * 3 + 2] / voxelM).toInt()
            val half = (1 shl cloud.levelOf(i).coerceAtMost(MAX_COVERED_LEVEL)) / 2
            for (dx in -half..half) for (dy in -half..half) for (dz in -half..half) out.add(rescanKey(cx + dx, cy + dy, cz + dz))
        }
        return out
    }

    /** Whether a voxel of [taken] is the one ([x], [y], [z]) falls in, or touches it. */
    private fun nextTo(taken: Set<Long>, x: Float, y: Float, z: Float, voxelM: Float): Boolean {
        val cx = floor(x / voxelM).toInt()
        val cy = floor(y / voxelM).toInt()
        val cz = floor(z / voxelM).toInt()
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) if (taken.contains(rescanKey(cx + dx, cy + dy, cz + dz))) return true
        return false
    }

    /** [a]'s points, then [b]'s at [picked]. */
    private fun join(a: DenseCloud, b: DenseCloud, picked: List<Int>): DenseCloud {
        val total = a.count + picked.size
        val positions = a.positions.copyOf(total * 3)
        val colors = a.colors.copyOf(total)
        for ((k, i) in picked.withIndex()) {
            val at = a.count + k
            for (c in 0 until 3) positions[at * 3 + c] = b.positions[i * 3 + c]
            colors[at] = b.colors[i]
        }
        val normals = if (a.normals == null && b.normals == null) {
            null
        } else {
            FloatArray(total * 3).also { out ->
                a.normals?.copyInto(out)
                b.normals?.let { n -> for ((k, i) in picked.withIndex()) for (c in 0 until 3) out[(a.count + k) * 3 + c] = n[i * 3 + c] }
            }
        }
        val confidences = joined(a.confidences, b.confidences, a.count, picked, FULL_CONFIDENCE)
        val levels = joined(a.levels, b.levels, a.count, picked, 0)
        return DenseCloud(positions, colors, normals, confidences, levels)
    }

    /** One byte a point for [a]'s and [b]'s at [picked], [missing] for the side that has none. */
    private fun joined(a: ByteArray?, b: ByteArray?, countA: Int, picked: List<Int>, missing: Int): ByteArray? {
        if (a == null && b == null) return null
        val out = ByteArray(countA + picked.size) { missing.toByte() }
        a?.copyInto(out)
        if (b != null) for ((k, i) in picked.withIndex()) out[countA + k] = b[i]
        return out
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val DENSE = "dense"
    private const val MEDIA = "media"
    private const val FULL_CONFIDENCE = 255
    private const val MAX_COVERED_LEVEL = 3
}

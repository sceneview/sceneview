package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.RerunRrdReader.Failure
import io.github.sceneview.demo.demos.internal.RerunRrdReader.PoseRow
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * What an `.rrd`'s chunks say about the space, gathered entity by entity, and the capture they
 * make. Static data follows Rerun's rule: the last value logged wins. The iOS demo's
 * `RerunRRDReader.Contents`.
 */
internal class RerunRrdContents(chunks: List<RrdChunk>) {
    private class Photo(val time: Long, val order: Int, val data: ByteArray, val mediaType: String?)

    /** One row of `world/points/live`: what the camera saw at [time]. */
    private class Sighting(val time: Long, val order: Int, val positions: FloatArray)

    private class CameraRow(
        val time: Long,
        val order: Int,
        val position: Vec3?,
        val orientation: RerunExportScene.Quat?,
    )

    class Plane(val name: String) {
        var outline: List<Vec3>? = null
        var color: Int? = null
        var meshPositions: List<Vec3>? = null
        var meshTexcoords: List<Pair<Float, Float>>? = null
        var texels: ByteArray? = null
        var texelFormat: RrdChunk.TexelFormat? = null
    }

    private class Anchor(val name: String) {
        var position: Vec3? = null
        var orientation: RerunExportScene.Quat? = null
    }

    private var title: String? = null
    private var points: List<Vec3> = emptyList()
    private var pointColors: IntArray? = null
    private var densePositions: FloatArray? = null
    private var denseColors: IntArray? = null
    private var denseRadius: Float? = null
    private val sightings = ArrayList<Sighting>()
    private val cameraRows = ArrayList<CameraRow>()
    private val photos = ArrayList<Photo>()
    private var pinhole: FloatArray? = null
    private var resolution: FloatArray? = null
    private var path: List<Vec3> = emptyList()
    private val planes = LinkedHashMap<String, Plane>()
    private val anchors = LinkedHashMap<String, Anchor>()
    private var order = 0

    init {
        for (chunk in chunks) read(chunk)
    }

    private fun read(chunk: RrdChunk) {
        when (val entity = chunk.entityPath) {
            "__properties" -> last(chunk.strings("RecordingInfo:name"))?.firstOrNull()?.let { title = it }
            "world/points" -> {
                last(chunk.floatVectors("Points3D:positions", 3))?.let {
                    points = vectors3(it)
                    pointColors = null
                }
                last(chunk.uint32s("Points3D:colors"))?.let { pointColors = it }
            }
            DENSE -> {
                last(chunk.floatVectors("Points3D:positions", 3))?.let {
                    densePositions = it
                    denseColors = null
                }
                last(chunk.uint32s("Points3D:colors"))?.let { denseColors = it }
                last(chunk.floats("Points3D:radii"))?.firstOrNull()?.let { denseRadius = it }
            }
            LIVE_POINTS -> {
                val positions = chunk.floatVectors("Points3D:positions", 3)
                chunk.times?.forEachIndexed { row, time ->
                    val flat = positions?.getOrNull(row)
                    if (time != null && flat != null && flat.size >= 3) sightings += Sighting(time, order++, flat)
                }
            }
            "world/camera" -> readCamera(chunk)
            "world/camera_path" -> last(chunk.strips("LineStrips3D:strips"))?.firstOrNull()?.let { path = it }
            else -> {
                child(PLANES_PREFIX, entity)?.let { readPlane(planes.getOrPut(it) { Plane(it) }, chunk) }
                child(ANCHORS_PREFIX, entity)?.let { readAnchor(anchors.getOrPut(it) { Anchor(it) }, chunk) }
            }
        }
    }

    private fun readCamera(chunk: RrdChunk) {
        val translations = chunk.floatVectors("Transform3D:translation", 3)
        val rotations = chunk.floatVectors("Transform3D:quaternion", 4)
        val blobs = chunk.blobs("EncodedImage:blob")
        val mediaTypes = chunk.strings("EncodedImage:media_type")
        chunk.times?.forEachIndexed { row, time ->
            if (time == null) return@forEachIndexed
            val position = translations?.get(row)?.takeIf { it.size >= 3 }?.let { Vec3(it[0], it[1], it[2]) }
            val orientation = rotations?.get(row)?.let(::quaternion)
            if (position != null || orientation != null) cameraRows += CameraRow(time, order++, position, orientation)
            blobs?.get(row)?.firstOrNull()?.let { blob ->
                photos += Photo(time, order++, blob, mediaTypes?.get(row)?.firstOrNull())
            }
        }
        last(chunk.floatVectors("Pinhole:image_from_camera", 9))?.let { pinhole = it }
        last(chunk.floatVectors("Pinhole:resolution", 2))?.let { resolution = it }
    }

    private fun readPlane(plane: Plane, chunk: RrdChunk) {
        last(chunk.strips("LineStrips3D:strips"))?.firstOrNull()?.let { plane.outline = it }
        last(chunk.uint32s("LineStrips3D:colors"))?.firstOrNull()?.let { plane.color = it }
        last(chunk.floatVectors("Mesh3D:vertex_positions", 3))?.let { plane.meshPositions = vectors3(it) }
        last(chunk.floatVectors("Mesh3D:vertex_texcoords", 2))?.let { flat ->
            plane.meshTexcoords = (0 until flat.size / 2).map { flat[2 * it] to flat[2 * it + 1] }
        }
        last(chunk.blobs("Mesh3D:albedo_texture_buffer"))?.firstOrNull()?.let { plane.texels = it }
        last(chunk.texelFormats("Mesh3D:albedo_texture_format"))?.firstOrNull()?.let { plane.texelFormat = it }
    }

    private fun readAnchor(anchor: Anchor, chunk: RrdChunk) {
        last(chunk.floatVectors("Transform3D:translation", 3))?.takeIf { it.size >= 3 }?.let {
            anchor.position = Vec3(it[0], it[1], it[2])
        }
        last(chunk.floatVectors("Transform3D:quaternion", 4))?.let {
            anchor.orientation = quaternion(it) ?: anchor.orientation
        }
    }

    // Capture

    private class PlaneEvent(
        val entity: String,
        val id: Int,
        val kind: String,
        val polygon: List<Vec3>,
        val texture: Texture?,
    )

    private class AnchorEvent(val entity: String, val position: Vec3, val orientation: RerunExportScene.Quat)

    private class Shot(val time: Long, val data: ByteArray, val mediaType: String?)

    /** The recording as a capture; [codec] re-encodes plane texels as PNG. */
    fun recording(codec: RerunImageCodec): RerunRrdReader.Recording {
        val poses = RerunRrdReader.pathPoses(resolvedPoses(), path, photos.map { it.time })
        val shots = distinctPhotos()
        val planeEvents = planeEvents(codec)
        val anchorEvents = anchors.values.mapNotNull { anchor ->
            val position = anchor.position?.takeIf { it.isFinite() } ?: return@mapNotNull null
            AnchorEvent(ANCHORS_PREFIX + anchor.name, position, anchor.orientation ?: RerunExportScene.Quat.Identity)
        }
        val mapPoints = points.indices.filter { points[it].isFinite() }
        val dense = denseCloud()
        if (poses.isEmpty() && mapPoints.isEmpty() && dense == null && planeEvents.none { it.polygon.size >= 3 }) {
            rrdFail(Failure.NothingToReplay())
        }
        // The session starts at 0 on the writer's timeline (seconds since the first event).
        val seen = sightings.sortedWith(compareBy<Sighting> { it.time }.thenBy { it.order })
        val times = poses.map { it.time } + shots.map { it.time } + seen.map { it.time }
        val start = minOf(0L, times.minOrNull() ?: 0L)
        val end = maxOf(start, times.maxOrNull() ?: start)

        val log = StringBuilder()
        // With the sightings, the map grows as it was seen and the live points come back; the
        // static map only colours them, and gives the points no sighting kept at the start.
        // Without them (an older file, another writer), the whole map is there from the start.
        val colors = MapColors(pointColors)
        val sightingLines = seen.map { it.time to colors.line(it.time, it.positions) }
        val unseen = if (seen.isEmpty()) mapPoints else colors.unseen(mapPoints)
        if (unseen.isNotEmpty()) log.append(pointCloudLine(start, unseen)).append('\n')
        val planeMedia = ArrayList<Pair<String, ByteArray>>()
        val textures = ArrayList<String>()
        for (plane in planeEvents) {
            log.append("{\"t\":$start,\"type\":\"plane\",\"entity\":${Json.string(plane.entity)}")
            log.append(",\"kind\":${Json.string(plane.kind)},\"polygon\":[")
            log.append(plane.polygon.joinToString(",", transform = Json::vector)).append("]}\n")
            val texture = plane.texture
            if (texture != null && plane.polygon.size >= 3) {
                val path = "planes/plane-${plane.id}.png"
                planeMedia += path to texture.png
                textures += "{\"plane\":${plane.id},\"path\":${Json.string(path)}" +
                    ",\"origin\":${Json.vector(texture.origin)}" +
                    ",\"u\":${Json.vector(texture.u)},\"v\":${Json.vector(texture.v)}}"
            }
        }
        for (anchor in anchorEvents) {
            log.append("{\"t\":$start,\"type\":\"anchor\",\"entity\":${Json.string(anchor.entity)}")
            log.append(",\"translation\":${Json.vector(anchor.position)}")
            log.append(",\"quaternion\":${Json.quaternion(anchor.orientation)}}\n")
        }
        val photoMedia = cameraLines(poses, shots, sightingLines, log)

        // Photos first in the archive, as in a recorded capture; plane photos, then the dense
        // cloud's SVPC blob (a `.svscan` v2's `dense/points.bin`) after them.
        val denseMedia = dense?.let { listOf(ReplayDense.PATH to SvpcCodec.encode(it)) }.orEmpty()
        val media = ByteArrayOutputStream()
        val entries = (photoMedia + planeMedia + denseMedia).map { (path, bytes) ->
            val offset = media.size()
            media.write(bytes)
            "{\"path\":${Json.string(path)},\"offset\":$offset,\"length\":${bytes.size}}"
        }
        val manifest = manifest(start, end, photoMedia.size, planeEvents, textures, entries, dense)
        return RerunRrdReader.Recording(
            title,
            RerunCapturePack(manifest.encodeToByteArray(), log.toString().encodeToByteArray(), media.toByteArray()),
        )
    }

    /**
     * `world/dense` as the `.svscan` v2 cloud it was exported from: finite positions, their
     * colours as `0xFFRRGGBB` (the writer's white for a point the camera never coloured stays
     * white), no normals — Rerun's `Points3D` has none. `null` without a dense point.
     */
    private fun denseCloud(): DenseCloud? {
        val flat = densePositions ?: return null
        val colors = denseColors?.takeIf { it.size * 3 == flat.size }
        val kept = (0 until flat.size / 3).filter { i ->
            flat[3 * i].isFinite() && flat[3 * i + 1].isFinite() && flat[3 * i + 2].isFinite()
        }
        if (kept.isEmpty()) return null
        val positions = FloatArray(kept.size * 3)
        kept.forEachIndexed { j, i -> System.arraycopy(flat, 3 * i, positions, 3 * j, 3) }
        val argb = IntArray(kept.size) { j -> colors?.let { (0xFF shl 24) or (it[kept[j]] ushr 8) } ?: 0 }
        return DenseCloud(positions, argb)
    }

    /** The dense cloud's voxel: twice the writer's half-voxel radius. */
    private fun denseVoxelM(): Float =
        denseRadius?.takeIf { it.isFinite() && it > 0f }?.let { it * 2f } ?: DenseFusion.VOXEL_M

    private fun planeEvents(codec: RerunImageCodec): List<PlaneEvent> = planes.values.mapNotNull { plane ->
        val polygon = plane.outline?.filter { it.isFinite() }?.toMutableList()?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        if (polygon.size >= 2 && polygon.first() == polygon.last()) polygon.removeAt(polygon.lastIndex)
        // The replay's log parser names a plane after its entity's last segment the same way.
        val id = plane.name.toIntOrNull() ?: plane.name.hashCode()
        PlaneEvent(PLANES_PREFIX + plane.name, id, planeKind(plane.color), polygon, Texture.of(plane, codec))
    }

    /**
     * The static map's colours by voxel, so a sighting's points take the colour the map gave
     * them; it remembers which voxels a sighting has shown.
     */
    private inner class MapColors(colors: IntArray?) {
        private val colors = colors?.takeIf { it.size == points.size }
        private val byVoxel = HashMap<Long, Int>()
        private val sighted = HashSet<Long>()

        init {
            points.forEachIndexed { i, p -> if (p.isFinite()) byVoxel.putIfAbsent(voxelOf(p), i) }
        }

        /** A `point_cloud` line at [time] for [flat] xyz; a point the map has no colour for gets none. */
        fun line(time: Long, flat: FloatArray): String {
            val line = StringBuilder("{\"t\":$time,\"type\":\"point_cloud\",")
                .append("\"entity\":\"world/points\",\"positions\":[")
            val rgb = StringBuilder()
            var n = 0
            for (i in 0 until flat.size / 3) {
                val p = Vec3(flat[3 * i], flat[3 * i + 1], flat[3 * i + 2])
                if (!p.isFinite()) continue
                val voxel = voxelOf(p)
                sighted += voxel
                if (n++ > 0) {
                    line.append(',')
                    rgb.append(',')
                }
                line.append(Json.vector(p))
                val c = byVoxel[voxel]?.let { colors?.get(it) }
                rgb.append(if (c == null) "[-1,-1,-1]" else "[${c ushr 24},${c ushr 16 and 0xFF},${c ushr 8 and 0xFF}]")
            }
            line.append(']')
            if (colors != null) line.append(",\"colors\":[").append(rgb).append(']')
            return line.append('}').toString()
        }

        /** The map points of [indices] no sighting showed. */
        fun unseen(indices: List<Int>): List<Int> = indices.filter { voxelOf(points[it]) !in sighted }

        private fun voxelOf(p: Vec3) = ArDebugTrace.voxelKey(p.x, p.y, p.z)
    }

    private fun pointCloudLine(start: Long, mapPoints: List<Int>): String {
        val colors = pointColors?.takeIf { it.size == points.size }
        val line = StringBuilder("{\"t\":$start,\"type\":\"point_cloud\",\"entity\":\"world/points\",\"positions\":[")
        mapPoints.joinTo(line, ",") { Json.vector(points[it]) }
        line.append(']')
        if (colors != null) {
            line.append(",\"colors\":[")
            mapPoints.joinTo(line, ",") { i ->
                val c = colors[i]
                "[${c ushr 24},${c ushr 16 and 0xFF},${c ushr 8 and 0xFF}]"
            }
            line.append(']')
        }
        return line.append('}').toString()
    }

    /**
     * Poses, point sightings and photos in time order, at one instant the pose first and the
     * photo last like the recorder; returns the photos' media.
     */
    private fun cameraLines(
        poses: List<PoseRow>,
        shots: List<Shot>,
        sightings: List<Pair<Long, String>>,
        log: StringBuilder,
    ): List<Pair<String, ByteArray>> {
        val photoMedia = ArrayList<Pair<String, ByteArray>>()
        var i = 0
        var j = 0
        var k = 0
        while (i < poses.size || j < shots.size || k < sightings.size) {
            val next = minOf(
                poses.getOrNull(i)?.time ?: Long.MAX_VALUE,
                shots.getOrNull(j)?.time ?: Long.MAX_VALUE,
                sightings.getOrNull(k)?.first ?: Long.MAX_VALUE,
            )
            if (i < poses.size && poses[i].time == next) {
                val pose = poses[i++]
                log.append("{\"t\":${pose.time},\"type\":\"camera_pose\",\"entity\":\"world/camera\"")
                log.append(",\"translation\":${Json.vector(pose.position)}")
                log.append(",\"quaternion\":${Json.quaternion(pose.orientation)}}\n")
            } else if (k < sightings.size && sightings[k].first == next) {
                log.append(sightings[k++].second).append('\n')
            } else {
                val shot = shots[j++]
                val path = "frames/" + "%03d".format(photoMedia.size) + "." + fileExtension(shot.mediaType)
                photoMedia += path to shot.data
                log.append("{\"t\":${shot.time},\"type\":\"image\",\"entity\":\"world/camera/image\"")
                log.append(",\"path\":${Json.string(path)}}\n")
            }
        }
        return photoMedia
    }

    @Suppress("LongParameterList") // the manifest's sections
    private fun manifest(
        start: Long,
        end: Long,
        frames: Int,
        planes: List<PlaneEvent>,
        textures: List<String>,
        media: List<String>,
        dense: DenseCloud?,
    ): String {
        // In Double: hostile times cannot overflow.
        val seconds = (end.toDouble() - start.toDouble()) / NANOS_PER_SECOND
        val frameRate = if (seconds > 0 && frames > 1) frames / seconds else DEFAULT_FRAME_RATE
        val out = StringBuilder("{")
        // A dense cloud makes it a `.svscan` v2 (ReplayManifest.parse); without one it stays v1.
        if (dense != null) {
            out.append("\"version\":2,\"dense\":{\"path\":${Json.string(ReplayDense.PATH)}")
            out.append(",\"count\":${dense.count},\"voxelM\":${Json.number(denseVoxelM())},\"normals\":false")
            out.append(",\"bounds\":[").append(dense.bounds().joinToString(",", transform = Json::number)).append("]},")
        }
        lens()?.let { lens ->
            out.append("\"intrinsics\":{\"width\":${lens.width},\"height\":${lens.height}")
            out.append(",\"fx\":${Json.number(lens.fx)},\"fy\":${Json.number(lens.fy)}")
            out.append(",\"cx\":${Json.number(lens.cx)},\"cy\":${Json.number(lens.cy)}},")
        }
        out.append("\"frameRate\":${Json.number(frameRate.toFloat())},\"frames\":$frames")
        floorY(planes.map { it.kind to it.polygon })?.let { out.append(",\"floorY\":${Json.number(it)}") }
        out.append(",\"textures\":[").append(textures.joinToString(",")).append("],\"media\":[")
        out.append(media.joinToString(",")).append("]}")
        return out.toString()
    }

    /** Camera rows in time order, each completed with the last position and rotation seen (Rerun's latest-at). */
    private fun resolvedPoses(): List<PoseRow> {
        var position: Vec3? = null
        var orientation = RerunExportScene.Quat.Identity
        val out = ArrayList<PoseRow>()
        for (row in cameraRows.sortedWith(compareBy<CameraRow> { it.time }.thenBy { it.order })) {
            row.position?.let { position = it }
            row.orientation?.let { orientation = it }
            val p = position ?: continue
            if (p.isFinite() && orientation.isFinite()) out += PoseRow(row.time, p, orientation)
        }
        return out
    }

    /** The photos in time order, each once: two keyframes that shared a photo logged it twice. */
    private fun distinctPhotos(): List<Shot> {
        val out = ArrayList<Shot>()
        for (photo in photos.sortedWith(compareBy<Photo> { it.time }.thenBy { it.order })) {
            if (photo.data.isEmpty()) continue
            val last = out.lastOrNull()
            if (last == null || last.time != photo.time || !last.data.contentEquals(photo.data)) {
                out += Shot(photo.time, photo.data, photo.mediaType)
            }
        }
        return out
    }

    /**
     * The `Pinhole` in pixels of the photos: column-major `image_from_camera` (`fx` at 0, `fy` at 4,
     * `cx` at 6, `cy` at 7) and `[width, height]`.
     */
    private fun lens(): RerunExportScene.Lens? {
        val m = pinhole?.takeIf { it.size >= 9 } ?: return null
        val size = resolution?.takeIf { it.size >= 2 } ?: floatArrayOf(2 * m[6], 2 * m[7])
        val values = floatArrayOf(m[0], m[4], m[6], m[7], size[0], size[1])
        val valid = values.all { it.isFinite() } && m[0] > 0 && m[4] > 0 &&
            size.take(2).all { it >= 1f && it < MAX_LENS_SIZE }
        if (!valid) return null
        return RerunExportScene.Lens(size[0].roundToInt(), size[1].roundToInt(), m[0], m[4], m[6], m[7])
    }

    /** A plane photo recovered from a `Mesh3D`: the texels as PNG and the texture frame. */
    class Texture(val png: ByteArray, val origin: Vec3, val u: Vec3, val v: Vec3) {
        companion object {
            /**
             * The writer's texture coordinate of a vertex `p` is `(dot(p - origin, u) / |u|²,
             * dot(p - origin, v) / |v|²)`. With `u ⊥ v`, `p = origin + s·u + t·v`: a linear fit of
             * `p` on `(1, s, t)` gives the frame back. It is kept only when the writer's formula,
             * applied to the fitted frame, gives every vertex its texture coordinate again.
             */
            fun of(plane: Plane, codec: RerunImageCodec): Texture? {
                val positions = plane.meshPositions ?: return null
                val uvs = plane.meshTexcoords?.takeIf { it.size == positions.size && it.size >= 3 } ?: return null
                val png = image(plane, codec) ?: return null
                val (origin, u, v) = fitFrame(positions, uvs) ?: return null
                val uu = u.dot(u)
                val vv = v.dot(v)
                val edges = uu > MIN_EDGE && vv > MIN_EDGE && uu.isFinite() && vv.isFinite()
                val maps = origin.isFinite() && edges && positions.zip(uvs).all { (p, uv) ->
                    val d = p - origin
                    val ds = d.dot(u) / uu - uv.first
                    val dt = d.dot(v) / vv - uv.second
                    ds * ds + dt * dt < MAX_UV_ERROR * MAX_UV_ERROR
                }
                return if (maps) Texture(png, origin, u, v) else null
            }

            private fun image(plane: Plane, codec: RerunImageCodec): ByteArray? {
                val texels = plane.texels ?: return null
                val format = plane.texelFormat ?: return null
                return png(texels, format, codec)
            }

            /** Least squares of `p` on `(1, s, t)`: `(origin, u, v)`, `null` when degenerate. */
            private fun fitFrame(positions: List<Vec3>, uvs: List<Pair<Float, Float>>): Triple<Vec3, Vec3, Vec3>? {
                val normal = Array(3) { DoubleArray(3) }
                val rhs = Array(3) { DoubleArray(3) } // rhs[axis][row]
                for ((p, uv) in positions.zip(uvs)) {
                    if (!p.isFinite() || !uv.first.isFinite() || !uv.second.isFinite()) return null
                    val a = doubleArrayOf(1.0, uv.first.toDouble(), uv.second.toDouble())
                    val axes = doubleArrayOf(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())
                    for (r in 0 until 3) {
                        for (c in 0 until 3) normal[r][c] += a[r] * a[c]
                        for (k in 0 until 3) rhs[k][r] += a[r] * axes[k]
                    }
                }
                val inverse = invert(normal) ?: return null
                val solved = rhs.map { b -> DoubleArray(3) { r -> (0 until 3).sumOf { c -> inverse[r][c] * b[c] } } }
                fun column(index: Int) =
                    Vec3(solved[0][index].toFloat(), solved[1][index].toFloat(), solved[2][index].toFloat())
                return Triple(column(0), column(1), column(2))
            }

            private fun invert(m: Array<DoubleArray>): Array<DoubleArray>? {
                val c00 = m[1][1] * m[2][2] - m[1][2] * m[2][1]
                val c01 = m[1][2] * m[2][0] - m[1][0] * m[2][2]
                val c02 = m[1][0] * m[2][1] - m[1][1] * m[2][0]
                val det = m[0][0] * c00 + m[0][1] * c01 + m[0][2] * c02
                if (kotlin.math.abs(det) <= MIN_DETERMINANT) return null
                val inverse = arrayOf(
                    doubleArrayOf(c00, m[0][2] * m[2][1] - m[0][1] * m[2][2], m[0][1] * m[1][2] - m[0][2] * m[1][1]),
                    doubleArrayOf(c01, m[0][0] * m[2][2] - m[0][2] * m[2][0], m[0][2] * m[1][0] - m[0][0] * m[1][2]),
                    doubleArrayOf(c02, m[0][1] * m[2][0] - m[0][0] * m[2][1], m[0][0] * m[1][1] - m[0][1] * m[1][0]),
                )
                return Array(3) { r -> DoubleArray(3) { c -> inverse[r][c] / det } }
            }

            /**
             * Rerun's `ColorModel`: RGB is 2, RGBA 3; `ChannelDatatype.U8` is 6. Anything else (a
             * `pixel_format` such as NV12, or wider channels) is not a plane photo the writer made.
             */
            fun png(texels: ByteArray, format: RrdChunk.TexelFormat, codec: RerunImageCodec): ByteArray? {
                val channels = when (format.colorModel ?: COLOR_MODEL_RGB) {
                    COLOR_MODEL_RGB -> RerunImageCodec.RGB
                    COLOR_MODEL_RGBA -> RerunImageCodec.RGBA
                    else -> return null
                }
                val valid = format.pixelFormat == null && (format.channelDatatype ?: CHANNEL_U8) == CHANNEL_U8 &&
                    format.width in 1..MAX_TEXTURE_SIZE && format.height in 1..MAX_TEXTURE_SIZE &&
                    texels.size >= format.width * format.height * channels
                if (!valid) return null
                val data = texels.copyOf(format.width * format.height * channels)
                return codec.encodePng(RerunImageCodec.Pixels(format.width, format.height, data, channels))
            }

            private const val COLOR_MODEL_RGB = 2L
            private const val COLOR_MODEL_RGBA = 3L
            private const val CHANNEL_U8 = 6L
            private const val MAX_TEXTURE_SIZE = 16_384
            private const val MIN_DETERMINANT = 1e-12
            private const val MIN_EDGE = 1e-12f
            private const val MAX_UV_ERROR = 1e-3f
        }
    }

    /** The session's JSON, written by hand at full `Float` precision so the replay sees the file's exact values. */
    private object Json {
        fun number(v: Float): String = if (v.isFinite()) v.toString() else "0"

        fun vector(v: Vec3): String = "[${number(v.x)},${number(v.y)},${number(v.z)}]"

        fun quaternion(q: RerunExportScene.Quat): String =
            "[${number(q.x)},${number(q.y)},${number(q.z)},${number(q.w)}]"

        fun string(s: String): String {
            val out = StringBuilder("\"")
            for (c in s) {
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c < ' ' -> out.append("\\u%04x".format(c.code))
                    else -> out.append(c)
                }
            }
            return out.append('"').toString()
        }
    }

    companion object {
        const val PLANES_PREFIX = "world/planes/"
        const val ANCHORS_PREFIX = "world/anchors/"

        /** What the camera saw over time, one row per observation ([RerunRrdWriter]). */
        const val LIVE_POINTS = "world/points/live"

        /** A `.svscan` v2's dense cloud, one static row ([RerunRrdWriter]). */
        const val DENSE = "world/dense"
        private const val NANOS_PER_SECOND = 1e9
        private const val DEFAULT_FRAME_RATE = 10.0
        private const val MAX_LENS_SIZE = 1e6f
        private const val FLOOR_MIN_AREA = 0.2f
        private val PLANE_KINDS = listOf("horizontal_upward", "horizontal_downward", "vertical")

        /** Whether a chunk on [entity] carries anything the replay uses; other chunks are not decoded. */
        fun reads(entity: String): Boolean =
            entity in setOf("__properties", "world/points", LIVE_POINTS, DENSE, "world/camera", "world/camera_path") ||
                child(PLANES_PREFIX, entity) != null || child(ANCHORS_PREFIX, entity) != null

        /** `world/planes/7` → `7`; `null` for any other path or a deeper one. */
        fun child(prefix: String, entity: String): String? {
            if (!entity.startsWith(prefix)) return null
            val name = entity.substring(prefix.length)
            return name.takeUnless { it.isEmpty() || '/' in it }
        }

        /** The writer tints each plane kind; the tint is how the kind travels. */
        fun planeKind(color: Int?): String =
            PLANE_KINDS.firstOrNull { RerunRrdWriter.planeColor(it) == color } ?: "unknown"

        /** The recorder's rule: the lowest upward plane of at least 0.2 m², else the lowest upward plane. */
        fun floorY(planes: List<Pair<String, List<Vec3>>>): Float? {
            val upward = planes.filter { it.first == PLANE_KINDS[0] && it.second.size >= 3 }.map { it.second }
            val large = upward.filter { area(it) >= FLOOR_MIN_AREA }
            return large.ifEmpty { upward }.minOfOrNull { polygon -> polygon.map { it.y }.sum() / polygon.size }
        }

        /** Area of a planar polygon (Newell's method). */
        fun area(polygon: List<Vec3>): Float {
            var normal = Vec3.Zero
            for (i in polygon.indices) normal += polygon[i].cross(polygon[(i + 1) % polygon.size])
            return normal.length() / 2
        }

        fun fileExtension(mediaType: String?): String = when (mediaType) {
            RerunImageCodec.PNG -> "png"
            RerunImageCodec.WEBP -> "webp"
            else -> "jpg" // The replay decodes by content; JPEG is what the writer emits most.
        }

        private fun quaternion(xyzw: FloatArray): RerunExportScene.Quat? =
            if (xyzw.size >= 4) RerunExportScene.Quat(xyzw[0], xyzw[1], xyzw[2], xyzw[3]) else null

        private fun vectors3(flat: FloatArray): List<Vec3> =
            List(flat.size / 3) { Vec3(flat[3 * it], flat[3 * it + 1], flat[3 * it + 2]) }

        /** The last non-null row of a component, `null` when the column or every row is missing. */
        private fun <T : Any> last(rows: List<T?>?): T? = rows?.lastOrNull { it != null }
    }
}

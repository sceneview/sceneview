package io.github.sceneview.demo.demos.internal

import java.util.UUID
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.tan

private typealias Column = RerunComponentColumn

/**
 * Writes a [RerunExportScene] as a Rerun `.rrd` recording (`rerun space.rrd` opens it), in pure
 * Kotlin: no Rerun SDK, no protobuf or Arrow library. The iOS demo's `RerunRRDWriter` writes the
 * same recording chunk for chunk (the store source aside), but not yet the default layout
 * ([RerunRrdBlueprint]) this writer puts before it.
 *
 * Targets the `.rrd` format of Rerun **0.38.1** (`RRF2` framing, sorbet `0.1.3` chunks),
 * uncompressed, without the optional footer — viewers read such a file with a full scan, the way
 * they read a live stream. What the viewer shows, on the `time` timeline (seconds since the
 * session started):
 *
 * - `world` — Y-up, right-handed (`ViewCoordinates` RUB), static.
 * - `world/points` — the coloured map (`Points3D`), static.
 * - `world/points/live` — what the camera saw over time (`Points3D`, one row per point-cloud
 *   observation, amber): the replay rebuilds the growing map and its live points from it.
 * - `world/dense` — a `.svscan` v2's dense cloud (`Points3D`: positions, colours, radius half a
 *   voxel), static, in one row: the room's surfaces where `world/points` has its landmarks.
 * - `world/camera` — the pose over time (`Transform3D`), the lens (`Pinhole`, static) and each
 *   photo at its time (`EncodedImage`, JPEG or PNG): every photo of the session when the scene
 *   has them, else the keyframes'.
 * - `world/camera_path` — the whole path (`LineStrips3D`), static. Not under `world/camera`: a
 *   child of a pinhole lives in its 2D image space, and a child of the moving camera would move
 *   with it.
 * - `world/planes/<id>` — the outline (`LineStrips3D`) and, when the plane has a photo, the
 *   textured polygon (`Mesh3D`), static.
 * - `world/anchors/<id>` — the pose (`Transform3D`) and a small labelled box (`Boxes3D`), static.
 *
 * The file opens on a default layout ([RerunRrdBlueprint]): the room in 3D beside the camera's
 * photos, instead of whatever views the viewer would guess.
 */
object RerunRrdWriter {
    /** The Rerun release whose format this writer emits: 0.38.1. */
    const val VERSION_MAJOR = 0
    const val VERSION_MINOR = 38
    const val VERSION_PATCH = 1

    /** The timeline every temporal row is indexed on (a duration, in nanoseconds). */
    const val TIMELINE = "time"

    /** Sorbet schema version of the chunks (the `sorbet:version` schema metadata). */
    const val SORBET_VERSION = "0.1.3"

    /**
     * The row ids' clock: TUIDs are deterministic, so they count from a fixed instant
     * (2026-01-01T00:00:00Z) instead of the wall clock.
     */
    const val TUID_EPOCH_NANOS = 1_767_225_600_000_000_000L

    /** What the store info names as the recording's source. */
    const val STORE_SOURCE = "SceneView Android"

    /** Plane photos are sent as raw RGB texels, at most this many on a side. */
    const val PLANE_TEXTURE_SIZE = 512

    private const val KIND_SET_STORE_INFO = 1L
    private const val KIND_ARROW_MSG = 2L
    private const val KIND_BLUEPRINT_ACTIVATION = 3L
    private const val STORE_KIND_RECORDING = 1L
    private const val STORE_KIND_BLUEPRINT = 2L
    private const val STORE_SOURCE_KIND_OTHER = 6L
    private const val COMPRESSION_NONE = 1L
    private const val ENCODING_ARROW_IPC = 1L

    /** `colors.size != points.size` while colours are given: the file would be ambiguous. */
    class PointColorCountMismatch(val points: Int, val colors: Int) :
        IllegalArgumentException("$colors point colours for $points points")

    /**
     * The `.rrd` file for [scene]. [codec] re-encodes photos Rerun cannot decode (the capture's
     * WebP) and decodes plane photos to texels. One scene and one [recordingId] always give the
     * same bytes.
     *
     * @throws PointColorCountMismatch when [RerunExportScene.pointColors] is neither empty nor
     *   one colour per point.
     */
    fun write(
        scene: RerunExportScene,
        codec: RerunImageCodec,
        applicationId: String = "sceneview_ar_replay",
        recordingId: UUID = UUID.randomUUID(),
    ): ByteArray {
        if (scene.pointColors.isNotEmpty() && scene.pointColors.size != scene.points.size) {
            throw PointColorCountMismatch(scene.points.size, scene.pointColors.size)
        }
        val ids = RerunTuidSequence(recordingId)
        val store = StoreIdentity(STORE_KIND_RECORDING, applicationId, recordingId.toString().lowercase())
        val blueprint = StoreIdentity(STORE_KIND_BLUEPRINT, applicationId, RerunRrdBlueprint.storeId(recordingId))
        val chunks = chunks(scene, codec)
        val hasPhotos = chunks.any { chunk -> chunk.components.any { it.fieldName == "EncodedImage:blob" } }
        val out = LittleEndian()
        out.bytes(streamHeader())
        // The blueprint first, as the Rerun SDKs write it, so the viewer has the layout before data.
        appendMessage(KIND_SET_STORE_INFO, setStoreInfo(blueprint, ids.next()), out)
        for (chunk in RerunRrdBlueprint.chunks(recordingId, hasPhotos)) {
            appendMessage(KIND_ARROW_MSG, arrowMessage(chunk, blueprint, ids), out)
        }
        appendMessage(KIND_BLUEPRINT_ACTIVATION, blueprintActivation(blueprint), out)
        appendMessage(KIND_SET_STORE_INFO, setStoreInfo(store, ids.next()), out)
        for (chunk in chunks) appendMessage(KIND_ARROW_MSG, arrowMessage(chunk, store, ids), out)
        return out.toByteArray()
    }

    // Framing

    /** `RRF2`, the Rerun version, then the options: compression off, protobuf serializer. */
    fun streamHeader(): ByteArray =
        "RRF2".encodeToByteArray() +
            byteArrayOf(VERSION_MAJOR.toByte(), VERSION_MINOR.toByte(), VERSION_PATCH.toByte(), 0) +
            byteArrayOf(0, 2, 0, 0)

    /** A 16-byte `MessageHeader` (kind, length; little-endian u64s) and the payload. */
    private fun appendMessage(kind: Long, payload: ByteArray, out: LittleEndian) {
        out.long(kind)
        out.long(payload.size.toLong())
        out.bytes(payload)
    }

    private class StoreIdentity(val kind: Long, val applicationId: String, val id: String)

    /** `rerun.common.v1alpha1.StoreId`: a recording (kind 1) or a blueprint (2), its id and application. */
    private fun RerunProtobufWriter.storeId(store: StoreIdentity) {
        uint64(1, store.kind)
        string(2, store.id)
        message(3) { string(1, store.applicationId) }
    }

    private fun RerunProtobufWriter.tuid(tuid: RerunTuid) {
        fixed64(1, tuid.timeNanos)
        fixed64(2, tuid.inc)
    }

    /** `rerun.log_msg.v1alpha1.SetStoreInfo`. */
    private fun setStoreInfo(store: StoreIdentity, rowId: RerunTuid): ByteArray {
        val proto = RerunProtobufWriter()
        proto.message(1) { tuid(rowId) }
        proto.message(2) {
            message(2) { storeId(store) }
            message(5) {
                uint64(1, STORE_SOURCE_KIND_OTHER) // `extra` is a UTF-8 string.
                message(2) { string(1, STORE_SOURCE) }
            }
            message(6) {
                int32(1, VERSION_MAJOR or (VERSION_MINOR shl Byte.SIZE_BITS) or (VERSION_PATCH shl Short.SIZE_BITS))
            }
        }
        return proto.bytes
    }

    /**
     * `rerun.log_msg.v1alpha1.BlueprintActivationCommand`: [blueprint] becomes the application's
     * default (`make_default`), without overriding a layout the user saved (`make_active` unset).
     */
    private fun blueprintActivation(blueprint: StoreIdentity): ByteArray {
        val proto = RerunProtobufWriter()
        proto.message(1) { storeId(blueprint) }
        proto.bool(3, true)
        return proto.bytes
    }

    /** `rerun.log_msg.v1alpha1.ArrowMsg` carrying one chunk as an uncompressed IPC stream. */
    private fun arrowMessage(chunk: RerunChunk, store: StoreIdentity, ids: RerunTuidSequence): ByteArray {
        val chunkId = ids.next()
        val rowIds = List(chunk.rowCount) { ids.next() }
        val payload = chunk.arrowIpc(chunkId, rowIds)
        val proto = RerunProtobufWriter()
        proto.message(1) { storeId(store) }
        proto.uint64(2, COMPRESSION_NONE)
        proto.uint64(3, payload.size.toLong())
        proto.uint64(4, ENCODING_ARROW_IPC)
        proto.bytes(5, payload)
        proto.message(6) { tuid(chunkId) }
        proto.bool(7, chunk.times == null)
        return proto.bytes
    }

    // Scene → chunks

    internal fun chunks(scene: RerunExportScene, codec: RerunImageCodec): List<RerunChunk> {
        val chunks = ArrayList<RerunChunk>()
        if (scene.title.isNotEmpty()) {
            chunks += RerunChunk(
                "/__properties",
                listOf(Column.strings("RecordingInfo", "name", "Name", listOf(listOf(scene.title)))),
            )
        }
        chunks += RerunChunk(
            "/world",
            listOf(Column.u8Vectors("ViewCoordinates", "xyz", "ViewCoordinates", listOf(VIEW_COORDINATES_RUB), 3)),
        )
        if (scene.points.isNotEmpty()) {
            val components = arrayListOf(
                Column.vectors("Points3D", "positions", "Position3D", listOf(flatten(scene.points)), 3),
                Column.floats("Points3D", "radii", "Radius", listOf(floatArrayOf(POINT_RADIUS))),
            )
            if (scene.pointColors.isNotEmpty()) {
                components += Column.colors("Points3D", listOf(scene.pointColors.map { packedColor(it.r, it.g, it.b) }))
            }
            chunks += RerunChunk("/world/points", components)
            liveChunk(scene)?.let { chunks += it }
        }
        scene.dense?.takeIf { it.count > 0 }?.let { chunks += denseChunk(it, scene.denseVoxelM) }
        chunks += cameraChunks(scene, codec)
        for (plane in scene.planes) planeChunk(plane, codec)?.let { chunks += it }
        for (anchor in scene.anchors) chunks += anchorChunk(anchor)
        return chunks
    }

    /** The dense cloud, one static row; a point the camera never coloured is written white. */
    private fun denseChunk(cloud: DenseCloud, voxelM: Float): RerunChunk = RerunChunk(
        "/world/dense",
        listOf(
            Column.vectors("Points3D", "positions", "Position3D", listOf(cloud.positions.copyOf(cloud.count * 3)), 3),
            Column.floats("Points3D", "radii", "Radius", listOf(floatArrayOf(voxelM / 2f))),
            Column.colors(
                "Points3D",
                listOf(
                    cloud.colors.map { c ->
                        val rgb = if (c == 0) 0xFFFFFF else c
                        packedColor(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)
                    },
                ),
            ),
        ),
    )

    /**
     * One row per observation, at its time: the map points it saw. A single amber colour and
     * radius per row (Rerun repeats them over the row's points), as the replay draws live points.
     */
    private fun liveChunk(scene: RerunExportScene): RerunChunk? {
        val observations = scene.pointObservations
            .map { o -> o.time to o.points.mapNotNull { scene.points.getOrNull(it) } }
            .filter { it.second.isNotEmpty() }
            .sortedBy { it.first }
        if (observations.isEmpty()) return null
        return RerunChunk(
            "/world/points/live",
            listOf(
                Column.vectors("Points3D", "positions", "Position3D", observations.map { flatten(it.second) }, 3),
                Column.floats("Points3D", "radii", "Radius", observations.map { floatArrayOf(LIVE_POINT_RADIUS) }),
                Column.colors("Points3D", observations.map { listOf(LIVE_POINT_COLOR) }),
            ),
            times = observations.map { nanoseconds(it.first) },
        )
    }

    private fun planeChunk(plane: RerunExportScene.Plane, codec: RerunImageCodec): RerunChunk? {
        if (plane.polygon.size < 2) return null
        val components = arrayListOf(
            Column.strips("LineStrips3D", listOf(listOf(plane.polygon + plane.polygon[0]))),
            Column.colors("LineStrips3D", listOf(listOf(planeColor(plane.kind)))),
        )
        val texture = plane.texture
        if (texture != null && plane.polygon.size >= 3) {
            codec.rgbPixels(texture.imageData, PLANE_TEXTURE_SIZE)?.let { pixels ->
                components += texturedMesh(plane.polygon, texture, pixels)
            }
        }
        return RerunChunk("/world/planes/${plane.id}", components)
    }

    private fun anchorChunk(anchor: RerunExportScene.Anchor) = RerunChunk(
        "/world/anchors/${anchor.id}",
        listOf(
            Column.vectors("Transform3D", "translation", "Translation3D", listOf(flatten(listOf(anchor.position))), 3),
            Column.vectors("Transform3D", "quaternion", "RotationQuat", listOf(quaternion(anchor.orientation)), 4),
            Column.vectors("Boxes3D", "half_sizes", "HalfSize3D", listOf(FloatArray(3) { ANCHOR_BOX }), 3),
            Column.colors("Boxes3D", listOf(listOf(packedColor(255, 200, 0)))),
            Column.strings("Boxes3D", "labels", "Text", listOf(listOf(anchor.modelName ?: "anchor ${anchor.id}"))),
        ),
    )

    private fun cameraChunks(scene: RerunExportScene, codec: RerunImageCodec): List<RerunChunk> {
        val chunks = ArrayList<RerunChunk>()
        val path = scene.cameraPath.sortedBy { it.time }
        // Every photo when the scene has them, else the keyframes' (#4080).
        val keyframes = scene.photos.ifEmpty { scene.keyframes }.sortedBy { it.time }
        // Photos, re-encoded to JPEG when Rerun can't decode them (WebP).
        val photos = keyframes.mapNotNull { keyframe ->
            val data = scene.images[keyframe.imagePath] ?: return@mapNotNull null
            codec.photo(data)?.let { keyframe.time to it }
        }
        pinhole(scene.lens, photos.firstOrNull()?.second)?.let { (matrix, resolution) ->
            chunks += pinholeChunk(matrix, resolution)
        }
        // The path's poses plus each photo's own pose, so the camera sits exactly where the
        // photo was taken at that instant (on a tie, the later row — the photo's — wins).
        val poses = (path.map { Triple(it.time, 0, it) } + keyframes.map { Triple(it.time, 1, it.pose) })
            .sortedWith(compareBy<Triple<Double, Int, RerunExportScene.CameraSample>> { it.first }.thenBy { it.second })
        if (poses.isNotEmpty()) chunks += poseChunk(poses.map { it.first to it.third })
        if (photos.isNotEmpty()) chunks += photoChunk(photos)
        if (path.size >= 2) {
            chunks += RerunChunk(
                "/world/camera_path",
                listOf(
                    Column.strips("LineStrips3D", listOf(listOf(path.map { it.position }))),
                    Column.colors("LineStrips3D", listOf(listOf(packedColor(120, 200, 255)))),
                ),
            )
        }
        return chunks
    }

    private fun poseChunk(poses: List<Pair<Double, RerunExportScene.CameraSample>>) = RerunChunk(
        "/world/camera",
        listOf(
            Column.vectors(
                "Transform3D",
                "translation",
                "Translation3D",
                poses.map { flatten(listOf(it.second.position)) },
                3,
            ),
            Column.vectors(
                "Transform3D",
                "quaternion",
                "RotationQuat",
                poses.map { quaternion(it.second.orientation) },
                4,
            ),
        ),
        times = poses.map { nanoseconds(it.first) },
    )

    private fun photoChunk(photos: List<Pair<Double, RerunImageCodec.Photo>>) = RerunChunk(
        "/world/camera",
        listOf(
            Column.blobs("EncodedImage", "blob", "Blob", photos.map { listOf(it.second.data) }),
            Column.strings("EncodedImage", "media_type", "MediaType", photos.map { listOf(it.second.mediaType) }),
        ),
        times = photos.map { nanoseconds(it.first) },
    )

    private fun pinholeChunk(matrix: FloatArray, resolution: FloatArray) = RerunChunk(
        "/world/camera",
        listOf(
            Column.vectors("Pinhole", "image_from_camera", "PinholeProjection", listOf(matrix), 9),
            Column.vectors("Pinhole", "resolution", "Resolution", listOf(resolution), 2),
            Column.u8Vectors("Pinhole", "camera_xyz", "ViewCoordinates", listOf(VIEW_COORDINATES_RUB), 3),
            Column.floats("Pinhole", "image_plane_distance", "ImagePlaneDistance", listOf(floatArrayOf(IMAGE_PLANE))),
        ),
    )

    /** Column-major `image_from_camera` and `[width, height]`, scaled to the photos' size. */
    private fun pinhole(lens: RerunExportScene.Lens?, photo: RerunImageCodec.Photo?): Pair<FloatArray, FloatArray>? {
        val fx: Float
        val fy: Float
        val cx: Float
        val cy: Float
        val width: Float
        val height: Float
        if (lens != null) {
            if (photo != null && lens.width > 0 && lens.height > 0) {
                val sx = photo.width.toFloat() / lens.width
                val sy = photo.height.toFloat() / lens.height
                fx = lens.fx * sx
                cx = lens.cx * sx
                fy = lens.fy * sy
                cy = lens.cy * sy
                width = photo.width.toFloat()
                height = photo.height.toFloat()
            } else {
                fx = lens.fx
                fy = lens.fy
                cx = lens.cx
                cy = lens.cy
                width = lens.width.toFloat()
                height = lens.height.toFloat()
            }
        } else if (photo != null) {
            // No recorded lens: a 60° vertical field of view.
            width = photo.width.toFloat()
            height = photo.height.toFloat()
            fy = height / (2 * tan(PI.toFloat() / 6))
            fx = fy
            cx = width / 2
            cy = height / 2
        } else {
            return null
        }
        return floatArrayOf(fx, 0f, 0f, 0f, fy, 0f, cx, cy, 1f) to floatArrayOf(width, height)
    }

    /** A fan from the centroid, UV-mapped with the plane photo, as `Mesh3D` columns. */
    private fun texturedMesh(
        polygon: List<Vec3>,
        texture: RerunExportScene.PlaneTexture,
        pixels: RerunImageCodec.Pixels,
    ): List<RerunComponentColumn> {
        val sum = polygon.fold(Vec3.Zero) { acc, p -> acc + p }
        val centroid = Vec3(sum.x / polygon.size, sum.y / polygon.size, sum.z / polygon.size)
        val vertices = listOf(centroid) + polygon
        val uu = maxOf(texture.u.dot(texture.u), java.lang.Float.MIN_NORMAL)
        val vv = maxOf(texture.v.dot(texture.v), java.lang.Float.MIN_NORMAL)
        val texcoords = FloatArray(vertices.size * 2)
        vertices.forEachIndexed { i, point ->
            val d = point - texture.origin
            texcoords[2 * i] = d.dot(texture.u) / uu
            texcoords[2 * i + 1] = d.dot(texture.v) / vv
        }
        val count = polygon.size
        val triangles = IntArray(count * 3)
        for (i in 1..count) {
            triangles[3 * (i - 1) + 1] = i
            triangles[3 * (i - 1) + 2] = i % count + 1
        }
        return listOf(
            Column.vectors("Mesh3D", "vertex_positions", "Position3D", listOf(flatten(vertices)), 3),
            Column.vectors("Mesh3D", "vertex_texcoords", "Texcoord2D", listOf(texcoords), 2),
            Column.u32Vectors("Mesh3D", "triangle_indices", "TriangleIndices", listOf(triangles), 3),
            Column.blobs("Mesh3D", "albedo_texture_buffer", "ImageBuffer", listOf(listOf(texelsOf(pixels)))),
            Column.imageFormat(
                "Mesh3D",
                "albedo_texture_format",
                pixels.width,
                pixels.height,
                rgba = pixels.channels == RerunImageCodec.RGBA,
            ),
        )
    }

    /**
     * [pixels] tightly packed, RGB or RGBA as [RerunImageCodec.Pixels.channels] says. The alpha is
     * kept: a plane photo's unseen texels are transparent black, and without their alpha a
     * reopened file draws them as black patches (#4080).
     */
    private fun texelsOf(pixels: RerunImageCodec.Pixels): ByteArray =
        pixels.data.copyOf(pixels.width * pixels.height * pixels.channels)

    // Helpers

    /** `ViewCoordinates` RUB (Right, Up, Back): Y up, right-handed — the camera looks down -Z. */
    private val VIEW_COORDINATES_RUB = byteArrayOf(3, 1, 6)
    private const val POINT_RADIUS = 0.01f
    private const val LIVE_POINT_RADIUS = 0.008f

    /** `warning` (#F59E0B), the replay's live-point colour. */
    private val LIVE_POINT_COLOR = packedColor(0xF5, 0x9E, 0x0B)
    private const val ANCHOR_BOX = 0.05f
    private const val IMAGE_PLANE = 0.25f
    private const val NANOS_PER_SECOND = 1e9

    fun nanoseconds(seconds: Double): Long = (seconds * NANOS_PER_SECOND).roundToLong()

    /** Rerun's `Color`: `0xRRGGBBAA`, as an `Int` bit pattern. */
    fun packedColor(r: Int, g: Int, b: Int, alpha: Int = 255): Int =
        (r and 0xFF shl 24) or (g and 0xFF shl 16) or (b and 0xFF shl 8) or (alpha and 0xFF)

    /** The outline tint of each plane kind: how the kind travels in the file. */
    fun planeColor(kind: String): Int = when (kind) {
        "horizontal_upward" -> packedColor(90, 210, 140)
        "horizontal_downward" -> packedColor(190, 130, 255)
        "vertical" -> packedColor(255, 165, 70)
        else -> packedColor(200, 200, 200)
    }

    internal fun flatten(points: List<Vec3>): FloatArray {
        val flat = FloatArray(points.size * 3)
        points.forEachIndexed { i, p ->
            flat[3 * i] = p.x
            flat[3 * i + 1] = p.y
            flat[3 * i + 2] = p.z
        }
        return flat
    }

    /** `[x, y, z, w]`, Rerun's `RotationQuat` order. */
    private fun quaternion(q: RerunExportScene.Quat) = floatArrayOf(q.x, q.y, q.z, q.w)
}

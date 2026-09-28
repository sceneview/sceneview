package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

/**
 * Turns a capture — the bundled sample, a recording, an opened file — into a [RerunExportScene],
 * the one value every exporter reads. The iOS demo's `RerunExportAdapter.scene(for:)`.
 */
object RerunExportAdapter {
    /** The model the demo places on every anchor, as the bundled asset name (`models/shiba.glb`). */
    const val ANCHOR_MODEL = "shiba"

    /** Lens intrinsics this small are the unit-focal form a saved scan writes; rescaled to pixels. */
    private const val UNIT_FOCAL_LIMIT = 2f
    private const val UNIT_FOCAL_SCALE = 1000f

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** [capture] as the exporters see it, titled [title]; `null` when its manifest does not parse. */
    fun scene(capture: RerunCapturePack, title: String): RerunExportScene? {
        val opened = capture.open() ?: return null
        return scene(opened, title, lensOf(String(capture.manifest, Charsets.UTF_8)))
    }

    /**
     * The whole session: every map point, the full camera path, the keyframe photos the replay
     * draws in their frustums, the planes as they ended, and the placed models.
     */
    fun scene(opened: OpenedCapture, title: String, lens: RerunExportScene.Lens?): RerunExportScene {
        val trace = opened.trace
        val whole = trace.frameAt(trace.duration)
        val points = (0 until whole.mapPointCount).map { i ->
            Vec3(whole.mapPoints[i * 3], whole.mapPoints[i * 3 + 1], whole.mapPoints[i * 3 + 2])
        }
        val colors = (0 until whole.mapPointCount).map { i ->
            val packed = whole.mapPointColors?.getOrNull(i) ?: 0
            if (packed == 0) {
                RerunExportScene.Rgb.White
            } else {
                RerunExportScene.Rgb(packed shr 16 and 0xFF, packed shr 8 and 0xFF, packed and 0xFF)
            }
        }
        val path = (0 until trace.poseCount).map { i -> sample(trace.poseTime(i).toDouble(), trace.pose(i)) }

        val imageTimes = LinkedHashMap<String, Float>()
        for (i in 0 until trace.imageCount) imageTimes.putIfAbsent(trace.imagePath(i), trace.imageTime(i))
        val keyframes = ArrayList<RerunExportScene.Keyframe>()
        val images = LinkedHashMap<String, ByteArray>()
        val shots = whole.keyframes.zip(whole.keyframeImages).mapNotNull { (pose, image) ->
            image?.let { path -> opened.bytesOf(path)?.let { bytes -> Triple(pose, path, bytes) } }
        }
        for ((pose, image, bytes) in shots) {
            val time = (imageTimes[image] ?: 0f).toDouble()
            keyframes += RerunExportScene.Keyframe(time, image, sample(time, pose))
            images[image] = bytes
        }

        val planes = whole.planes.map { plane ->
            val texture = opened.manifest.textureFor(plane.id)?.let { texture ->
                opened.bytesOf(texture.path)?.let { bytes ->
                    RerunExportScene.PlaneTexture(bytes, texture.origin, texture.u, texture.v)
                }
            }
            val polygon = (0 until plane.vertexCount).map { i ->
                Vec3(plane.polygon[i * 3], plane.polygon[i * 3 + 1], plane.polygon[i * 3 + 2])
            }
            RerunExportScene.Plane(plane.id, plane.kind.wireName, polygon, texture)
        }
        val anchors = whole.anchors.map { anchor ->
            RerunExportScene.Anchor(anchor.id, anchor.pose.position, quatOf(anchor.pose), ANCHOR_MODEL)
        }
        return RerunExportScene(
            title = title,
            lens = lens,
            points = points,
            pointColors = colors,
            cameraPath = path,
            keyframes = keyframes,
            images = images,
            planes = planes,
            anchors = anchors,
        )
    }

    /**
     * The manifest's `intrinsics` as a pixel lens; `null` without them. A missing principal point
     * is the image centre; the unit-focal form a saved scan writes is scaled to a 1000 px focal
     * length, keeping the lens's proportions — all a `.glb` frustum or a `.rrd` pinhole uses.
     */
    @Suppress("ReturnCount") // one early return per missing or invalid value
    fun lensOf(manifest: String): RerunExportScene.Lens? {
        val root = runCatching { json.parseToJsonElement(manifest) as? JsonObject }.getOrNull() ?: return null
        val intrinsics = root["intrinsics"] as? JsonObject ?: return null
        fun value(key: String) = (intrinsics[key] as? JsonPrimitive)?.floatOrNull
        var width = value("width") ?: return null
        var height = value("height") ?: return null
        var fx = value("fx") ?: return null
        var fy = value("fy") ?: return null
        if (minOf(width, height, fx, fy) <= 0f || !(width + height + fx + fy).isFinite()) return null
        var cx = value("cx")
        var cy = value("cy")
        if (fx < UNIT_FOCAL_LIMIT && fy < UNIT_FOCAL_LIMIT) {
            val scale = UNIT_FOCAL_SCALE / fx
            width *= scale
            height *= scale
            fx *= scale
            fy *= scale
            cx = cx?.times(scale)
            cy = cy?.times(scale)
        }
        return RerunExportScene.Lens(
            width = Math.round(width),
            height = Math.round(height),
            fx = fx,
            fy = fy,
            cx = cx ?: (width / 2f),
            cy = cy ?: (height / 2f),
        )
    }

    /** `"Recorded room"` → `recorded-room`; `"space"` when nothing is left. */
    fun fileStem(title: String): String {
        val words = title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return if (words.isEmpty()) "space" else words.joinToString("-")
    }

    private fun sample(time: Double, pose: DebugPose) =
        RerunExportScene.CameraSample(time, pose.position, quatOf(pose))

    private fun quatOf(pose: DebugPose) = RerunExportScene.Quat(pose.qx, pose.qy, pose.qz, pose.qw)
}

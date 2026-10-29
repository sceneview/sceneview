package io.github.sceneview.demo.demos.internal.semantic

import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.ScanObjects
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Keyframe [index] of a recording: its photo's [path] in the media, and the [camera] that took it. */
class SceneKeyframe(val index: Int, val path: String, val time: Float, val camera: SceneCamera)

/** What one keyframe gave: what the detector saw, what of it was found in the room, and how long each step took. */
class SceneFrameReport(
    val keyframe: SceneKeyframe,
    val width: Int,
    val height: Int,
    val detections: List<Detection2D>,
    val lifted: List<SceneObservation>,
    val decodeMillis: Long,
    val detectMillis: Long,
    val liftMillis: Long,
)

/** A recording's recognised parts, surest first, with the work behind them. */
class SceneRecognition(
    val items: List<RecognizedItem>,
    val frames: List<SceneFrameReport>,
    val openings: List<WallOpening>,
    val detector: String,
    val totalMillis: Long,
    /** What the walls' openings were found on, or why there are none ([WallSurvey.note]). */
    val openingsNote: String = "",
)

/**
 * Recognises the parts of a scanned room: [detector] on up to [maxFrames] keyframes spread over the
 * recording, each detection lifted into the scan's cloud ([SceneLift]) and the views fused
 * ([SceneFusion]); the holes in the walls from the cloud ([openings]), a piece of furniture
 * standing in one taking precedence over it.
 *
 * Off the main thread, for seconds: on a Pixel 4a, a keyframe is a few hundred milliseconds.
 */
class SceneRecognizer(
    private val detector: SceneDetector,
    private val openings: WallOpenings = CloudWallOpenings(),
    private val maxFrames: Int = MAX_FRAMES,
) {
    /**
     * The parts of the room photographed in [keyframes] (see [Companion.keyframes]: read from the
     * trace on the thread that owns it) and fused into [cloud], whose [objects] the lifting grows;
     * [image] decodes a keyframe's photo, `null` when it cannot. [picking] is held round each use
     * of [objects], which a tap shares; [pickLimit] is how many of the cloud's surfels a ray may hit.
     */
    @Suppress("LongParameterList")
    suspend fun recognize(
        keyframes: List<SceneKeyframe>,
        cloud: DenseCloud,
        objects: ScanObjects,
        image: (String) -> SceneImage?,
        pickLimit: Int = cloud.count,
        picking: Mutex = Mutex(),
    ): SceneRecognition {
        val started = System.nanoTime()
        val frames = ArrayList<SceneFrameReport>()
        for (keyframe in keyframes.take(maxFrames)) {
            currentCoroutineContext().ensureActive()
            val t0 = System.nanoTime()
            val photo = image(keyframe.path) ?: continue
            val t1 = System.nanoTime()
            val detections = detector.detect(photo)
            val t2 = System.nanoTime()
            val lifted = picking.withLock {
                detections.mapNotNull { detection ->
                    SceneLift.lift(detection, keyframe.index, keyframe.camera) { origin, direction ->
                        objects.pick(origin, direction, pickLimit)
                    }
                }
            }
            val t3 = System.nanoTime()
            frames += SceneFrameReport(
                keyframe, photo.width, photo.height, detections, lifted,
                millis(t1 - t0), millis(t2 - t1), millis(t3 - t2),
            )
        }
        currentCoroutineContext().ensureActive()
        val furniture = SceneFusion.fuse(frames.flatMap { it.lifted }, cloud)
        val survey = openings.survey(cloud) { objects.surfaceOf(it) }
        val holes = survey.openings
        return SceneRecognition(
            merge(furniture, holes), frames, holes, detector.name, millis(System.nanoTime() - started), survey.note,
        )
    }

    companion object {
        const val MAX_FRAMES = 24
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Furniture this close to an opening's middle stands in it: the "hole" is what it hides. */
        private const val IN_FRONT_M = 0.1f

        private fun millis(nanos: Long) = nanos / NANOS_PER_MILLI

        /**
         * Up to [maxFrames] keyframes of [trace], spread evenly over the recording, each with the
         * camera pose it was taken from; a photo with no pose is skipped. Each pose is looked up
         * alone ([poseAt]): this runs while the replay is composed, and a whole frame of the trace
         * would copy the trail and the map up to every photo.
         */
        fun keyframes(trace: ArDebugTrace, lens: ReplayLens, maxFrames: Int = MAX_FRAMES): List<SceneKeyframe> {
            val count = trace.imageCount
            if (count == 0 || maxFrames <= 0) return emptyList()
            val picked = if (count <= maxFrames) {
                (0 until count).toList()
            } else {
                (0 until maxFrames).map { (it * (count - 1).toLong() / (maxFrames - 1).coerceAtLeast(1)).toInt() }.distinct()
            }
            return picked.mapNotNull { index ->
                val time = trace.imageTime(index)
                val pose = poseAt(trace, time) ?: return@mapNotNull null
                SceneKeyframe(index, trace.imagePath(index), time, SceneCamera(pose, lens))
            }
        }

        /**
         * The camera of [trace] at [time], the same as [ArDebugTrace.frameAt] gives: the last pose
         * recorded by then, or none before the first.
         */
        internal fun poseAt(trace: ArDebugTrace, time: Float): DebugPose? {
            val t = time.coerceIn(0f, trace.duration)
            var lo = 0
            var hi = trace.poseCount
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (trace.poseTime(mid) <= t) lo = mid + 1 else hi = mid
            }
            return if (lo > 0) trace.pose(lo - 1) else null
        }

        /**
         * The furniture and the openings as one list, surest first. An opening with a piece of
         * furniture standing in it is what the furniture hid from the scan, not a hole; a door or a
         * window the detector also saw (a model with those classes) is the opening, surer.
         */
        fun merge(furniture: List<RecognizedItem>, openings: List<WallOpening>): List<RecognizedItem> {
            val things = furniture.filterNot { it.label.isOpening }
            val seenOpenings = furniture.filter { it.label.isOpening }
            val holes = openings.mapNotNull { opening ->
                val box = opening.box
                val blocked = things.any { thing ->
                    val near = SceneFusion.inside(thing.box, box.centerX, box.centerZ) ||
                        SceneFusion.footprintIou(thing.box, box) > 0f ||
                        distanceToFootprint(thing, box.centerX, box.centerZ) < IN_FRONT_M
                    near && thing.box.baseY < box.baseY + box.height && box.baseY < thing.box.baseY + thing.box.height
                }
                if (blocked) return@mapNotNull null
                val seen = seenOpenings.filter { it.label == opening.label && SceneFusion.footprintIou(it.box, box) > 0f }
                RecognizedItem(
                    opening.label,
                    box,
                    maxOf(opening.confidence, seen.maxOfOrNull { it.confidence } ?: 0f),
                    seen.maxOfOrNull { it.views } ?: 0,
                    IntArray(0),
                )
            }
            return (things + holes).sortedByDescending { it.confidence }
        }

        private fun distanceToFootprint(item: RecognizedItem, x: Float, z: Float): Float {
            val box = item.box
            val dx = x - box.centerX
            val dz = z - box.centerZ
            val c = cos(box.yaw)
            val s = sin(box.yaw)
            val u = (abs(dx * c + dz * s) - box.length / 2).coerceAtLeast(0f)
            val v = (abs(-dx * s + dz * c) - box.depth / 2).coerceAtLeast(0f)
            return sqrt(u * u + v * v)
        }
    }
}

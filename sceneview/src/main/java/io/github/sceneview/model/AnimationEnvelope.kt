package io.github.sceneview.model

import com.google.android.filament.Box
import io.github.sceneview.Aabb
import io.github.sceneview.FitMode
import io.github.sceneview.framingProbes
import io.github.sceneview.loaders.GltfAnimationData
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.math.times
import io.github.sceneview.toAabb
import io.github.sceneview.union
import java.util.WeakHashMap

/**
 * Separate sampled pose boxes, in model space when returned by [Model.animationEnvelope].
 * [bounds] is their union for measuring size; framing fits each pose separately, never that union.
 *
 * Boxes are copied on input and output: changing a Filament [Box] cannot corrupt a cached result.
 * The first pose of a computed envelope is the rest box, followed by the requested samples.
 */
class AnimationEnvelope(poses: List<Box>) {
    internal val poseBounds: List<Aabb> = poses.map { it.toAabb() }.also {
        require(it.isNotEmpty()) { "An envelope needs at least one pose" }
    }
    private val unionBounds = poseBounds.union()

    /** Each pose as its own box, including the rest box. Returns fresh, caller-owned boxes. */
    val poses: List<Box> get() = poseBounds.map { it.toBox() }

    /** Union of all poses. Do not use its probes to fit an animated silhouette. */
    val bounds: Box get() = unionBounds.toBox()

    /**
     * Re-expresses every pose through [transform], re-boxing its eight transformed corners.
     * Pass a model node's `worldTransform` (read on the main thread) before world-space framing.
     * This operation itself is pure Kotlin and preserves the separation between poses.
     */
    fun transformed(transform: Transform): AnimationEnvelope = AnimationEnvelope(poseBounds.map { box ->
        val corners = box.framingProbes(FitMode.Corners).map { transform * it }
        if (corners.isEmpty()) box.toBox() else Aabb.fromMinMax(
            Position(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }),
            Position(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z })
        ).toBox()
    })
}

private fun Aabb.toBox() = Box(center.x, center.y, center.z, halfExtent.x, halfExtent.y, halfExtent.z)

/**
 * Estimates a skinned clip's occupied volume without an Animator or another native instance.
 *
 * `ModelLoader` retains only node transforms and animation accessor arrays before releasing the
 * source buffers. Sampling is lazy, pure Kotlin, safe on a worker thread after loading, and cached
 * per model, clip and sample count (the most recent eight requests). [samples] is clamped to 2–120;
 * samples are evenly spaced from zero through the clip's last key, endpoints included.
 * Preparation is bounded to 4 MiB of keyframe floats, 4096 source nodes and 256 clips; models
 * exceeding those limits fall back. The bundled Fox retains 43,532 bytes of primitive arrays;
 * on a macOS/JDK 21 JVM, median preparation was 0.45 ms and sampling 24 poses 0.35–0.57 ms (2026-11-13,
 * warm process, excluding the existing JSON parse and object headers; not an Android benchmark).
 *
 * Each pose encloses its joints plus the rest mesh's overhang beyond the rest joint extent on
 * each of the six faces. This **flesh-margin heuristic** is not vertex skinning: it does not prove
 * containment of moving flesh, morph targets, animated rigid attachments or unsampled extrema.
 * LINEAR rotations use shortest-path slerp; STEP and CUBICSPLINE follow glTF interpolation.
 *
 * No skin, no clip, an unknown [clip], unsupported animation data (sparse, meshopt or embedded
 * data-URI accessors), or a parse/sample failure returns only [restPoseBoundingBox]. Models made
 * outside `ModelLoader` also fall back; that case reads Filament's box and requires the main thread.
 * No animation envelope or fitting mode is selected automatically.
 */
fun Model.animationEnvelope(clip: Int = 0, samples: Int = 24): AnimationEnvelope =
    AnimationEnvelopes[this]?.envelope(clip, samples) ?: AnimationEnvelope(listOf(restPoseBoundingBox))

/** [animationEnvelope] selected by the exact glTF clip name; an unknown name returns the rest box. */
fun Model.animationEnvelope(clip: String, samples: Int = 24): AnimationEnvelope =
    AnimationEnvelopes[this]?.let { it.envelope(it.data?.clipIndex(clip) ?: -1, samples) }
        ?: AnimationEnvelope(listOf(restPoseBoundingBox))

/** Does not retain the model, JSON, source buffers, resource resolver or native handles. */
internal object AnimationEnvelopes {
    private val models = WeakHashMap<Model, Entry>()

    @Synchronized
    fun record(model: Model, data: GltfAnimationData?) {
        models[model] = Entry(data, AnimationEnvelope(listOf(model.restPoseBoundingBox)))
    }

    @Synchronized
    operator fun get(model: Model): Entry? = models[model]

    @Synchronized
    fun forget(model: Model) { models.remove(model) }

    internal class Entry(val data: GltfAnimationData?, private val rest: AnimationEnvelope) {
        private val cache by lazy { LinkedHashMap<Pair<Int, Int>, AnimationEnvelope>(8, 0.75f, true) }

        @Synchronized
        fun envelope(clip: Int, samples: Int): AnimationEnvelope {
            if (data == null) return rest
            val key = clip to samples.coerceIn(2, 120)
            return cache.getOrPut(key) {
                val result = try {
                    data.sample(clip, key.second, rest.bounds) ?: rest
                } catch (_: RuntimeException) {
                    rest
                }
                if (cache.size >= 8) cache.remove(cache.keys.first())
                result
            }
        }
    }
}

package io.github.sceneview.model

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import androidx.annotation.MainThread
import io.github.sceneview.AnimationEnvelope
import io.github.sceneview.Aabb
import io.github.sceneview.boundsOfPoints
import io.github.sceneview.fleshBounds
import io.github.sceneview.math.Position
import io.github.sceneview.toAabb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Samples [clip] uniformly, including both endpoints, in model-root space. Main-thread JNI is
 * dispatched automatically. Cached per asset, clip and [samples] (2..120; default 24).
 *
 * One pose per display frame: O(samples × (nodes + joints)) work, O(samples) retained boxes.
 * A cold 24-pose envelope takes at least 24 display frames (~400 ms at 60 Hz); a cache hit does
 * no sampling. The 24-pose envelope construction + fit measured ~0.03–0.12 ms on the host JVM
 * (`AnimationEnvelopeTest`, 2026-11-13); that excludes all JNI. Native cost is not yet measured
 * and depends on the rig/device; `AnimationEnvelopeRenderTest`
 * measures total and maximum batch CPU time (compile-only when no device is available).
 *
 * `ModelLoader` reserves one private, unrendered instance per clip of a skinned animated asset before
 * releasing source data. Sampling never writes the visible instance: paused, blended and morph
 * poses and playback clocks stay unchanged, including on cancellation. The asset owns and frees
 * those extra instances (additional nodes, renderables and Animator data per clip, including
 * when auto-fit is disabled).
 * Assets created outside ModelLoader fall back to [Model.restPoseBoundingBox].
 *
 * Flesh is approximated by the six non-negative gaps between the rest-pose joint extent and
 * [Model.restPoseBoundingBox]. Those axis-aligned gaps expand each sampled joint extent. Rest is
 * included as a separate pose. This does not bound morph deformation or unsampled motion.
 * Models with no skin or animation return just the rest box; [clip] is otherwise zero-based.
 */
suspend fun ModelInstance.animationEnvelope(clip: Int = 0, samples: Int = 24): AnimationEnvelope =
    withContext(Dispatchers.Main.immediate) {
        val sampler = AnimationEnvelopes.sampler(this@animationEnvelope, clip, samples)
        while (sampler.result == null) {
            awaitEnvelopeFrame()
            sampler.step()
        }
        checkNotNull(sampler.result)
    }

/** Named-clip form of [animationEnvelope]; throws if an animated asset has no matching name. */
suspend fun ModelInstance.animationEnvelope(clip: String, samples: Int = 24): AnimationEnvelope =
    withContext(Dispatchers.Main.immediate) {
        check(!isDestroyed) { "Cannot sample a destroyed model" }
        val index = if (skinCount == 0 || animator.animationCount == 0) 0 else
            requireNotNull(animator.getAnimationIndex(clip)) { "Unknown animation: $clip" }
        animationEnvelope(index, samples)
    }

private suspend fun awaitEnvelopeFrame() = suspendCancellableCoroutine { continuation ->
    Choreographer.getInstance().postFrameCallback {
        if (continuation.isActive) continuation.resume(Unit)
    }
}

/** Loader-owned instances; entries are removed before the native asset is destroyed. */
internal object AnimationEnvelopes {
    private val sources = java.util.IdentityHashMap<Model, List<Source>>()

    @MainThread
    fun record(model: Model, createInstance: () -> ModelInstance?) {
        val instance = model.instance
        if (instance.skinCount == 0 || instance.animator.animationCount == 0) return
        val rest = model.restPoseBoundingBox.toAabb()
        // Animator keeps a private TRS cache that TransformManager writes do not reset. Separate
        // instances prevent a rotation-only clip from inheriting another clip's translation.
        sources[model] = List(instance.animator.animationCount) {
            Source(checkNotNull(createInstance()) { "Cannot reserve an animation sampler" }, rest)
        }
    }

    fun forget(model: Model) { sources.remove(model) }

    fun sampler(instance: ModelInstance, clip: Int, samples: Int = 24): EnvelopeSampler {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Animation envelopes require the main thread" }
        check(!instance.isDestroyed) { "Cannot sample a destroyed model" }
        require(samples in 2..120) { "samples must be in 2..120" }
        if (instance.skinCount == 0 || instance.animator.animationCount == 0) {
            return EnvelopeSampler(AnimationEnvelope(listOf(instance.model.restPoseBoundingBox.toAabb())))
        }
        require(clip in 0 until instance.animator.animationCount) { "Invalid animation clip: $clip" }
        val source = sources[instance.model]?.get(clip)
            ?: return EnvelopeSampler(AnimationEnvelope(listOf(instance.model.restPoseBoundingBox.toAabb())))
        return source.samplers.getOrPut(clip to samples) { EnvelopeSampler(source, clip, samples) }
    }

    internal class Source(val instance: ModelInstance, val rest: Aabb) {
        val transforms = instance.engine.transformManager
        val entities = instance.entities.toList()
        val restTransforms = entities.map { transforms.getTransform(transforms.getInstance(it), null) }
        val joints = (0 until instance.skinCount).flatMap { instance.getJointsAt(it).toList() }
            .distinct()
        init {
            // Additional Filament instances initially inherit the asset root. Detach this one so
            // application root edits never change the cached model-space envelope.
            val root = transforms.getInstance(instance.root)
            transforms.setParent(root, 0)
            transforms.setTransform(root, floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
            instance.setLayerMask(0xff, 0)
            instance.isShadowCaster = false
            instance.lightEntityInstances.forEach { light ->
                repeat(8) { channel -> instance.lightManager.setLightChannel(light, channel, false) }
            }
        }
        val restJoints = jointBounds()
        val samplers = mutableMapOf<Pair<Int, Int>, EnvelopeSampler>()

        fun restore() = entities.forEachIndexed { index, entity ->
            transforms.setTransform(transforms.getInstance(entity), restTransforms[index])
        }

        // The private instance root is identity and never parented: world == model-root space.
        fun jointBounds(): Aabb = boundsOfPoints(joints.map {
            val matrix = transforms.getWorldTransform(transforms.getInstance(it), null)
            Position(matrix[12], matrix[13], matrix[14])
        })
    }
}

private val envelopeMainHandler by lazy { Handler(Looper.getMainLooper()) }

/** One step per presented frame; shared consumers reuse the same per-clip work and result. */
internal class EnvelopeSampler private constructor(
    private val source: AnimationEnvelopes.Source?, private val clip: Int, private val samples: Int,
    var result: AnimationEnvelope?
) {
    constructor(result: AnimationEnvelope) : this(null, 0, 0, result)
    constructor(source: AnimationEnvelopes.Source, clip: Int, samples: Int) : this(source, clip, samples, null)
    private val poses = mutableListOf<Aabb>()
    private var sampledInCallback = false
    var maxBatchNanos = 0L
        private set
    var totalSamplingNanos = 0L
        private set

    fun step() {
        if (result != null) return
        check(Looper.myLooper() == Looper.getMainLooper()) { "Animation envelopes require the main thread" }
        // Several ModelNodes (or coroutines) can share this sampler. Spend only one pose's
        // work in a frame callback. The posted reset runs after all callbacks in this Looper
        // dispatch and before the next display frame, without relying on hidden Choreographer APIs.
        if (sampledInCallback) return
        sampledInCallback = true
        envelopeMainHandler.post { sampledInCallback = false }
        val source = checkNotNull(source)
        check(!source.instance.isDestroyed) { "Cannot sample a destroyed model" }
        val start = System.nanoTime()
        try {
            source.restore()
            val animator = source.instance.animator
            animator.applyAnimation(clip, animator.getAnimationDuration(clip) * poses.size / (samples - 1))
            poses += fleshBounds(source.rest, source.restJoints, source.jointBounds())
            if (poses.size == samples) result = AnimationEnvelope(listOf(source.rest) + poses)
        } finally {
            source.restore()
            val elapsed = System.nanoTime() - start
            maxBatchNanos = maxOf(maxBatchNanos, elapsed)
            totalSamplingNanos += elapsed
        }
    }
}

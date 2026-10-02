package io.github.sceneview.ar.depth

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The metric scale of one ML depth map: `1 / z = scale · d + offset`, where `d` is the
 * network's affine-invariant inverse depth and `z` the depth in metres.
 *
 * @property inliers anchors that survived the robust fit.
 * @property zMin nearest inlier anchor, in metres.
 * @property zMax farthest inlier anchor, in metres.
 * @property rmsRelativeError RMS of `|z_fit − z| / z` over the inliers.
 */
internal data class DepthScale(
    val scale: Double,
    val offset: Double,
    val inliers: Int,
    val zMin: Float,
    val zMax: Float,
    val rmsRelativeError: Double,
) {
    /** Metric depth for a network value [d], or `NaN` where the fit gives no positive depth. */
    fun depthMeters(d: Float): Float {
        val inv = scale * d + offset
        return if (inv <= MIN_INVERSE_DEPTH) Float.NaN else (1.0 / inv).toFloat()
    }

    companion object {
        /** `1/z` below this is "infinitely far or behind the camera": no depth. */
        const val MIN_INVERSE_DEPTH = 1e-4
    }
}

/**
 * Robust weighted least-squares fit of `1 / z = s · d + t` over sparse metric anchors.
 *
 * The anchors are ARCore's own measurements projected into the ML image: tracked-plane
 * samples and feature points, each with the network value `d` under it and its depth `z`.
 *
 * ```
 * minimise  Σ wᵢ (s·dᵢ + t − 1/zᵢ)²  +  λₛ (s − ŝ)²  +  λₜ (t − t̂)²
 * wᵢ = cᵢ · zᵢ² · hᵢ
 * ```
 *
 * - `cᵢ` is the anchor's confidence. `zᵢ²` turns an error on `1/z` into a **relative** error on
 *   `z`, so a far anchor weighs as much as a near one in percent.
 * - The least squares starts from a deterministic two-point consensus (64 anchor pairs), so a
 *   cluster of gross outliers cannot drag the first solve away from every true anchor.
 * - `hᵢ` is a Huber weight on that relative error, re-estimated for [Config.irlsIterations]
 *   rounds; from the second round on, anchors off by more than [Config.rejectRelativeError]
 *   are dropped (a feature point on a moving hand, a plane sample hidden behind a chair).
 * - `(ŝ, t̂)` is the previous frame's smoothed fit, a weak prior that keeps the scale from
 *   jumping when the anchors are few. The network re-normalises every image, so the prior is
 *   deliberately weak ([Config.priorWeight]).
 *
 * Pure Kotlin, no Android type: unit-tested on the JVM with synthetic anchors.
 */
internal object DepthScaleFit {

    data class Config(
        val minInliers: Int = 12,
        val minDepthRatio: Float = 1.5f,
        val rejectRelativeError: Double = 0.2,
        val huberRelativeError: Double = 0.05,
        val irlsIterations: Int = 3,
        val priorWeight: Double = 0.05,
    )

    /** Why a fit was refused — surfaced in the debug overlay. */
    enum class Rejection { TooFewAnchors, FlatDepthRange, NonPositiveScale, Singular }

    sealed interface Result {
        data class Fit(val scale: DepthScale) : Result
        data class Rejected(val reason: Rejection, val inliers: Int) : Result
    }

    /**
     * @param d network values under each anchor.
     * @param z anchor depths in metres (along the optical axis, > 0).
     * @param confidence per-anchor confidence in `[0, 1]`.
     * @param count how many entries of the arrays are filled.
     * @param prior the previous smoothed fit, or `null` at start-up.
     */
    @Suppress("LongParameterList")
    fun fit(
        d: FloatArray,
        z: FloatArray,
        confidence: FloatArray,
        count: Int,
        prior: DepthScale? = null,
        config: Config = Config(),
    ): Result {
        if (count < config.minInliers) return Result.Rejected(Rejection.TooFewAnchors, count)
        val weights = DoubleArray(count)
        val inlier = BooleanArray(count) { z[it] > 0f && confidence[it] > 0f && d[it].isFinite() }
        consensus(d, z, confidence, inlier, count, config)?.let { seed ->
            for (i in 0 until count) {
                if (inlier[i] && relativeError(seed, d[i], z[i]) > config.rejectRelativeError) inlier[i] = false
            }
        }
        for (i in 0 until count) weights[i] = if (inlier[i]) baseWeight(z[i], confidence[i]) else 0.0

        var solution = solve(d, z, weights, count, prior, config) ?: return rejected(Rejection.Singular, inlier)
        repeat(config.irlsIterations) { round ->
            for (i in 0 until count) {
                if (!inlier[i]) continue
                val rel = relativeError(solution, d[i], z[i])
                if (round > 0 && rel > config.rejectRelativeError) {
                    inlier[i] = false
                    weights[i] = 0.0
                } else {
                    val huber = if (rel <= config.huberRelativeError) 1.0 else config.huberRelativeError / rel
                    weights[i] = baseWeight(z[i], confidence[i]) * huber
                }
            }
            solution = solve(d, z, weights, count, prior, config) ?: return rejected(Rejection.Singular, inlier)
        }
        // Final pass: drop what the converged fit still disagrees with before judging validity.
        for (i in 0 until count) {
            if (inlier[i] && relativeError(solution, d[i], z[i]) > config.rejectRelativeError) inlier[i] = false
        }
        return judge(solution, d, z, inlier, count, config)
    }

    private fun judge(
        solution: DoubleArray,
        d: FloatArray,
        z: FloatArray,
        inlier: BooleanArray,
        count: Int,
        config: Config,
    ): Result {
        var n = 0
        var zMin = Float.MAX_VALUE
        var zMax = 0f
        var sumSq = 0.0
        for (i in 0 until count) {
            if (!inlier[i]) continue
            n++
            zMin = minOf(zMin, z[i])
            zMax = maxOf(zMax, z[i])
            val rel = relativeError(solution, d[i], z[i])
            sumSq += rel * rel
        }
        return when {
            n < config.minInliers -> Result.Rejected(Rejection.TooFewAnchors, n)
            zMax / zMin < config.minDepthRatio -> Result.Rejected(Rejection.FlatDepthRange, n)
            solution[0] <= 0.0 -> Result.Rejected(Rejection.NonPositiveScale, n)
            else -> Result.Fit(
                DepthScale(solution[0], solution[1], n, zMin, zMax, sqrt(sumSq / n)),
            )
        }
    }

    /**
     * Deterministic two-point consensus: the `(s, t)` through a pair of anchors that agrees,
     * within [Config.rejectRelativeError], with the most confidence. Seeds the least squares so
     * a cluster of gross outliers (often the far ones, which `z²` weighs most) cannot drag the
     * first solve so far that every anchor looks wrong. `null` when no pair gives a positive
     * scale — the least squares then starts from every anchor.
     */
    @Suppress("LongParameterList")
    private fun consensus(
        d: FloatArray,
        z: FloatArray,
        confidence: FloatArray,
        valid: BooleanArray,
        count: Int,
        config: Config,
    ): DoubleArray? {
        var best: DoubleArray? = null
        var bestScore = 0.0
        var state = CONSENSUS_SEED
        val candidate = DoubleArray(2)
        repeat(CONSENSUS_HYPOTHESES) {
            state = state * LCG_MULTIPLIER + LCG_INCREMENT
            val i = ((state ushr LCG_SHIFT) % count).toInt()
            state = state * LCG_MULTIPLIER + LCG_INCREMENT
            val j = ((state ushr LCG_SHIFT) % count).toInt()
            val dd = d[i].toDouble() - d[j]
            val distinct = i != j && valid[i] && valid[j]
            if (!distinct || abs(dd) < MIN_PAIR_SPREAD) return@repeat
            candidate[0] = (1.0 / z[i] - 1.0 / z[j]) / dd
            candidate[1] = 1.0 / z[i] - candidate[0] * d[i]
            if (candidate[0] <= 0.0) return@repeat
            var score = 0.0
            for (k in 0 until count) {
                if (valid[k] && relativeError(candidate, d[k], z[k]) <= config.rejectRelativeError) score += confidence[k]
            }
            if (score > bestScore) {
                bestScore = score
                best = candidate.copyOf()
            }
        }
        return best
    }

    private fun rejected(reason: Rejection, inlier: BooleanArray) =
        Result.Rejected(reason, inlier.count { it })

    private fun baseWeight(z: Float, confidence: Float): Double = confidence.toDouble() * z * z

    private fun relativeError(solution: DoubleArray, d: Float, z: Float): Double {
        val inv = solution[0] * d + solution[1]
        if (inv <= DepthScale.MIN_INVERSE_DEPTH) return Double.POSITIVE_INFINITY
        return abs(1.0 / inv - z) / z
    }

    /** Closed-form 2×2 normal equations with the prior folded in. `null` when singular. */
    @Suppress("LongParameterList")
    private fun solve(
        d: FloatArray,
        z: FloatArray,
        w: DoubleArray,
        count: Int,
        prior: DepthScale?,
        config: Config,
    ): DoubleArray? {
        var sw = 0.0
        var swd = 0.0
        var swdd = 0.0
        var swy = 0.0
        var swdy = 0.0
        for (i in 0 until count) {
            val wi = w[i]
            if (wi <= 0.0) continue
            val di = d[i].toDouble()
            val yi = 1.0 / z[i]
            sw += wi
            swd += wi * di
            swdd += wi * di * di
            swy += wi * yi
            swdy += wi * di * yi
        }
        if (sw <= 0.0) return null
        // Normalise by the total weight so the prior strength is independent of the anchor count.
        var a11 = swdd / sw
        val a12 = swd / sw
        var a22 = 1.0
        var b1 = swdy / sw
        var b2 = swy / sw
        if (prior != null && config.priorWeight > 0.0) {
            // λ is relative to each normal-equation diagonal, so it is dimensionless whatever the
            // network's output range is.
            val lambdaS = config.priorWeight * a11
            val lambdaT = config.priorWeight * a22
            a11 += lambdaS
            a22 += lambdaT
            b1 += lambdaS * prior.scale
            b2 += lambdaT * prior.offset
        }
        val det = a11 * a22 - a12 * a12
        if (abs(det) < SINGULAR_EPSILON * a11 * a22 || det == 0.0) return null
        return doubleArrayOf((b1 * a22 - a12 * b2) / det, (a11 * b2 - a12 * b1) / det)
    }

    private const val SINGULAR_EPSILON = 1e-9
    private const val CONSENSUS_HYPOTHESES = 64
    private const val CONSENSUS_SEED = 0x2545F4914F6CDD1DL
    private const val LCG_MULTIPLIER = 6364136223846793005L
    private const val LCG_INCREMENT = 1442695040888963407L
    private const val LCG_SHIFT = 33
    private const val MIN_PAIR_SPREAD = 1e-6
}

/**
 * Temporal smoothing of [DepthScale] across ML frames, with a short hold when a frame's fit is
 * refused.
 *
 * Smooths `log s` and `t` with an exponential filter of gain [gain]: the network re-normalises
 * every image, so `(s, t)` legitimately moves with the framing and the filter stays light. A
 * refused fit keeps the last scale for at most [maxHeldFrames] ML frames, then the output goes
 * to `null` — an old scale is worse than no depth.
 */
internal class DepthScaleSmoother(
    private val gain: Double = 0.6,
    private val maxHeldFrames: Int = 5,
) {
    private var current: DepthScale? = null
    private var held = 0

    /** The prior for the next fit. */
    val prior: DepthScale? get() = current

    /** Whether the last [update] returned a held scale instead of a fresh fit. */
    var isHolding: Boolean = false
        private set

    fun update(result: DepthScaleFit.Result): DepthScale? {
        val fit = (result as? DepthScaleFit.Result.Fit)?.scale
        val previous = current
        if (fit != null) {
            held = 0
            isHolding = false
            current = if (previous == null) {
                fit
            } else {
                val logS = ln(previous.scale) + gain * (ln(fit.scale) - ln(previous.scale))
                fit.copy(scale = exp(logS), offset = previous.offset + gain * (fit.offset - previous.offset))
            }
            return current
        }
        if (previous != null && held < maxHeldFrames) {
            held++
            isHolding = true
            return previous
        }
        reset()
        return null
    }

    fun reset() {
        current = null
        held = 0
        isHolding = false
    }
}

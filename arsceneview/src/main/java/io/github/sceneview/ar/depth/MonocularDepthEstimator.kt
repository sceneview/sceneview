package io.github.sceneview.ar.depth

import java.io.Closeable

/**
 * A single-image depth network, run by [MlDepthSession] on its own worker thread.
 *
 * The SDK core knows nothing about the runtime: an implementation wraps LiteRT, ONNX Runtime
 * or anything else (`DepthAnythingV2Estimator` in the `arsceneview-depth-ml` module is the
 * reference one). [warmUp], [estimate] and [close] are always called on the **same** thread,
 * which GPU delegates require, and never on the main thread.
 */
interface MonocularDepthEstimator : Closeable {

    /**
     * What the network's output means. A relative output is turned into metres by
     * [MlDepthSession], which fits it against ARCore's plane hits and feature points.
     */
    enum class OutputKind {
        /**
         * Affine-invariant **inverse** depth: `d ≈ a / z + b`, with `a` and `b` unknown and
         * different on every image (Depth Anything V2, MiDaS).
         */
        AffineInverse,

        /** Depth in metres, already metric. Used as is. */
        Metric,
    }

    /** Model input width in pixels. Valid once [warmUp] has returned. */
    val inputWidth: Int

    /** Model input height in pixels. Valid once [warmUp] has returned. */
    val inputHeight: Int

    /** Model output width in pixels. Valid once [warmUp] has returned. */
    val outputWidth: Int

    /** Model output height in pixels. Valid once [warmUp] has returned. */
    val outputHeight: Int

    /** How to read the output. */
    val outputKind: OutputKind

    /**
     * Load the model (download it if needed), compile it for the accelerator and run it once.
     * Blocking; called once, on the estimator thread, before the first [estimate].
     */
    fun warmUp()

    /**
     * Run the network on one image.
     *
     * @param rgb `inputWidth × inputHeight × 3` bytes, interleaved `R, G, B`, row-major, in
     *   the camera sensor orientation. The implementation owns any normalisation.
     * @param out `outputWidth × outputHeight` floats to fill, row-major.
     */
    fun estimate(rgb: ByteArray, out: FloatArray)
}

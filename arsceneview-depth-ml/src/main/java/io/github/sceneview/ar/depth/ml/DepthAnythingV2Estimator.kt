package io.github.sceneview.ar.depth.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import io.github.sceneview.ar.depth.MonocularDepthEstimator
import java.io.File

/**
 * [MonocularDepthEstimator] for Depth Anything V2 Small on LiteRT.
 *
 * ```kotlin
 * val estimator = DepthAnythingV2Estimator(context) // downloads 27.7 MB on first use
 * val mlDepth = MlDepthSession(estimator, context = context)
 * ```
 *
 * The network sees the whole CPU camera image, stretched to its input size, normalised with
 * the ImageNet mean and standard deviation, channel-first. It returns relative inverse depth,
 * which [io.github.sceneview.ar.depth.MlDepthSession] scales to metres.
 *
 * @param model where the `.tflite` file comes from. The default downloads the pinned
 *   Apache-2.0 model into `noBackupFilesDir/sceneview-depth-ml/` and checks its SHA-256.
 * @param backend [Backend.Cpu] (XNNPACK, default) or [Backend.Gpu] (OpenCL/OpenGL, FP32 — the
 *   FP16 default of the GPU delegate gives visibly wrong maps on this network).
 * @param numThreads CPU threads for [Backend.Cpu].
 * @param onDownloadProgress `0..1` while the model downloads, on the estimator thread.
 */
class DepthAnythingV2Estimator(
    context: Context,
    private val model: ModelSource = ModelSource.DepthAnythingV2Small,
    private val backend: Backend = Backend.Cpu,
    private val numThreads: Int = DEFAULT_THREADS,
    private val onDownloadProgress: (Float) -> Unit = {},
) : MonocularDepthEstimator {

    /** Where the network runs. */
    enum class Backend { Cpu, Gpu }

    private val appContext = context.applicationContext

    private var compiled: CompiledModel? = null
    private var inputs: List<TensorBuffer> = emptyList()
    private var outputs: List<TensorBuffer> = emptyList()
    private var input = FloatArray(0)

    override var inputWidth: Int = DEFAULT_WIDTH
        private set
    override var inputHeight: Int = DEFAULT_HEIGHT
        private set
    override var outputWidth: Int = DEFAULT_WIDTH
        private set
    override var outputHeight: Int = DEFAULT_HEIGHT
        private set
    override val outputKind = MonocularDepthEstimator.OutputKind.AffineInverse

    override fun warmUp() {
        val options = when (backend) {
            Backend.Cpu -> CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(numThreads, null, null)
            }
            Backend.Gpu -> CompiledModel.Options(Accelerator.GPU).apply {
                gpuOptions = gpuFp32()
            }
        }
        val started = System.nanoTime()
        val loaded = when (model) {
            is ModelSource.Asset -> CompiledModel.create(appContext.assets, model.path, options)
            is ModelSource.LocalFile -> CompiledModel.create(model.file.absolutePath, options)
            is ModelSource.Url -> {
                val dir = File(appContext.noBackupFilesDir, CACHE_DIR)
                CompiledModel.create(ModelDownloader.ensure(model, dir, onDownloadProgress).absolutePath, options)
            }
        }
        compiled = loaded
        // NCHW [1, 3, H, W] in, [1, H, W] out.
        val inLayout = checkNotNull(loaded.getInputTensorType(INPUT_NAME, SIGNATURE).layout) { "No input layout" }
        val outLayout = checkNotNull(loaded.getOutputTensorType(OUTPUT_NAME, SIGNATURE).layout) { "No output layout" }
        val inDims = inLayout.dimensions
        val outDims = outLayout.dimensions
        require(inDims.size == 4 && inDims[1] == 3) { "Expected an NCHW RGB input, got $inDims" }
        inputHeight = inDims[2]
        inputWidth = inDims[3]
        outputHeight = outDims[outDims.size - 2]
        outputWidth = outDims[outDims.size - 1]
        inputs = loaded.createInputBuffers()
        outputs = loaded.createOutputBuffers()
        input = FloatArray(3 * inputWidth * inputHeight)
        // First run compiles kernels and packs weights: keep it out of the measured frames.
        inputs[0].writeFloat(input)
        loaded.run(inputs, outputs)
        Log.i(TAG, "Depth Anything V2 Small ready ($backend) in ${(System.nanoTime() - started) / NANOS_PER_MS} ms")
    }

    override fun estimate(rgb: ByteArray, out: FloatArray) {
        val model = checkNotNull(compiled) { "warmUp() first" }
        val plane = inputWidth * inputHeight
        for (p in 0 until plane) {
            val r = (rgb[3 * p].toInt() and 0xFF) / 255f
            val g = (rgb[3 * p + 1].toInt() and 0xFF) / 255f
            val b = (rgb[3 * p + 2].toInt() and 0xFF) / 255f
            input[p] = (r - MEAN_R) / STD_R
            input[plane + p] = (g - MEAN_G) / STD_G
            input[2 * plane + p] = (b - MEAN_B) / STD_B
        }
        inputs[0].writeFloat(input)
        model.run(inputs, outputs)
        // LiteRT's Kotlin API has no read-into-array overload (2.1.5 and 2.2.0 alike):
        // `readFloat()` returns a fresh 1.4 MB array from JNI on every run. At 5 Hz that is
        // short-lived young-generation garbage; switch to a read-into call once LiteRT has one.
        val result = outputs[0].readFloat()
        result.copyInto(out, endIndex = minOf(result.size, out.size))
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        compiled?.close()
        compiled = null
    }

    private fun gpuFp32() = CompiledModel.GpuOptions(
        null, null, null, CompiledModel.GpuOptions.Precision.FP32,
        null, null, null, null, null, null, null, null, null, null, null,
    )

    private companion object {
        const val TAG = "DepthAnythingV2"
        const val CACHE_DIR = "sceneview-depth-ml"
        const val SIGNATURE = "serving_default"
        const val INPUT_NAME = "args_0"
        const val OUTPUT_NAME = "output_0"
        const val DEFAULT_WIDTH = 686
        const val DEFAULT_HEIGHT = 518
        const val DEFAULT_THREADS = 4
        const val NANOS_PER_MS = 1_000_000L
        const val MEAN_R = 0.485f
        const val MEAN_G = 0.456f
        const val MEAN_B = 0.406f
        const val STD_R = 0.229f
        const val STD_G = 0.224f
        const val STD_B = 0.225f
    }
}

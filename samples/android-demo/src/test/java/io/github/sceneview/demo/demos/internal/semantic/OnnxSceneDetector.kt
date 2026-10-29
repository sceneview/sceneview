package io.github.sceneview.demo.demos.internal.semantic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * A YOLOX detector ([YoloxCodec]) run by the desktop ONNX Runtime, for the JVM tests. A corpus run
 * here judges the ONNX export, not the file the app ships: the app runs [LiteRtSceneDetector] on
 * the LiteRT conversion of that export, which no JVM test can run — LiteRT runs on a device only.
 * Of that path the JVM tests cover the code round the model: the letterbox, the channels-last
 * rewrite, the check of the tensors' shapes and the decoding ([YoloxCodecTest]). That the
 * conversion finds the export's boxes was measured once, off the tests — all 286 of them on 52
 * photos, at an IoU of 0.98 or more and within 0.005 of the score —; on a device, the instrumented
 * `LiteRtSceneDetectorTest` checks it on one photo when given the file, and is skipped otherwise.
 *
 * The model is the `.onnx` file's bytes, or a direct buffer over them (a file mapped in memory,
 * never copied onto the heap); the input's size is read from it (416 for Tiny and Nano).
 */
class OnnxSceneDetector private constructor(
    override val name: String,
    threads: Int,
    private val minScore: Float,
    private val keep: (String) -> Boolean,
    open: (OrtEnvironment, OrtSession.SessionOptions) -> OrtSession,
) : SceneDetector {
    constructor(
        model: ByteArray,
        name: String = "yolox",
        threads: Int = DEFAULT_THREADS,
        minScore: Float = YoloxCodec.MIN_SCORE,
        keep: (String) -> Boolean = { SceneLabel.ofDetector(it) != null },
    ) : this(name, threads, minScore, keep, { environment, options -> environment.createSession(model, options) })

    /** [model] must be a direct buffer, as a mapped file is: ONNX Runtime reads it in place. */
    constructor(
        model: ByteBuffer,
        name: String = "yolox",
        threads: Int = DEFAULT_THREADS,
        minScore: Float = YoloxCodec.MIN_SCORE,
        keep: (String) -> Boolean = { SceneLabel.ofDetector(it) != null },
    ) : this(name, threads, minScore, keep, { environment, options -> environment.createSession(model, options) })

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(threads)
        open(environment, options)
    }
    private val inputName = session.inputNames.first()

    /** The model's square input side, in pixels. */
    val size: Int = ((session.inputInfo.getValue(inputName).info as TensorInfo).shape.last()).toInt()
        .takeIf { it > 0 } ?: DEFAULT_SIZE

    private val input = FloatArray(3 * size * size)

    @Synchronized
    override fun detect(image: SceneImage): List<Detection2D> {
        val ratio = YoloxCodec.input(image, size, input)
        val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result.get(0) as OnnxTensor
                val dims = (output.info as TensorInfo).shape
                val buffer = output.floatBuffer
                val values = FloatArray(buffer.remaining()).also { buffer.get(it) }
                val columns = dims.last().toInt()
                YoloxCodec.decode(
                    values, values.size / columns, columns, size, ratio, image.width, image.height,
                    minScore = minScore, keep = keep,
                )
            }
        }
    }

    override fun close() = session.close()

    companion object {
        const val DEFAULT_THREADS = 2
        const val DEFAULT_SIZE = 416
    }
}

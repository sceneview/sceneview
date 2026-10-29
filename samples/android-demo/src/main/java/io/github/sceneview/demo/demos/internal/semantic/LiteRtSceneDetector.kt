package io.github.sceneview.demo.demos.internal.semantic

import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A YOLOX detector ([YoloxCodec]) run by LiteRT, the runtime the depth estimator already ships, so
 * the app carries no second one. The model is the `.tflite` conversion of the repository's ONNX
 * export, as a direct buffer over the file (a file mapped in memory, never copied onto the heap);
 * the input's size is read from it (416 for Tiny and Nano).
 *
 * The conversion takes the same input as the export — BGR, 0 to 255, letterboxed by
 * [YoloxCodec.input] — with the channels last (`[1, size, size, 3]`) where the export has them
 * first, and gives the same output, `[1, boxes, 85]`. A model whose tensors read otherwise is
 * refused as it opens ([YoloxCodec.channelsLastLayout]). One [detect] or [close] at a time: an
 * interpreter is not safe to share between threads.
 */
class LiteRtSceneDetector(
    model: ByteBuffer,
    override val name: String = "yolox",
    threads: Int = DEFAULT_THREADS,
    private val minScore: Float = YoloxCodec.MIN_SCORE,
    private val keep: (String) -> Boolean = { SceneLabel.ofDetector(it) != null },
) : SceneDetector {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(threads))
    private val layout = YoloxCodec.channelsLastLayout(
        interpreter.getInputTensor(0).shape(),
        interpreter.getOutputTensor(0).shape(),
    )

    /** The model's square input side, in pixels. */
    val size: Int = layout.size

    private val planar = FloatArray(CHANNELS * size * size)
    private val interleaved = FloatArray(planar.size)
    private val input = direct(planar.size)

    private val columns = layout.columns
    private val values = FloatArray(interpreter.getOutputTensor(0).numElements())
    private val output = direct(values.size)

    @Synchronized
    override fun detect(image: SceneImage): List<Detection2D> {
        val ratio = YoloxCodec.input(image, size, planar)
        // The codec writes the channels one plane after the other; the model reads them per pixel.
        YoloxCodec.channelsLast(planar, size, interleaved)
        input.clear()
        input.asFloatBuffer().put(interleaved)
        output.clear()
        interpreter.run(input, output)
        output.rewind()
        output.asFloatBuffer().get(values)
        return YoloxCodec.decode(
            values, values.size / columns, columns, size, ratio, image.width, image.height,
            minScore = minScore, keep = keep,
        )
    }

    @Synchronized
    override fun close() = interpreter.close()

    companion object {
        const val DEFAULT_THREADS = 2
        private const val CHANNELS = 3

        private fun direct(floats: Int): ByteBuffer =
            ByteBuffer.allocateDirect(floats * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    }
}

package io.github.sceneview.demo.demos.internal.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * JPEG and PNG on the plain JVM — `java.awt.image.BufferedImage` and `javax.imageio.ImageIO` —
 * where `android.graphics` is a stub. The unit tests compile against `android.jar`, which has
 * neither class, so they are reached by name; the JDK that runs the tests has both.
 */
internal object JvmImages {
    /** ARGB pixels, [width] × [height]: a decoded image. */
    class Image(val width: Int, val height: Int, val argb: IntArray)

    fun jpeg(argb: IntArray, width: Int, height: Int, quality: Float = JPEG_QUALITY): ByteArray {
        val image = bufferedImage(argb, width, height)
        val out = ByteArrayOutputStream()
        val writers = imageIo.getMethod("getImageWritersByFormatName", String::class.java).invoke(null, "jpg") as Iterator<*>
        val writer = checkNotNull(writers.next())
        val stream = imageIo.getMethod("createImageOutputStream", Any::class.java).invoke(null, out)
        val writerClass = Class.forName("javax.imageio.ImageWriter")
        writerClass.getMethod("setOutput", Any::class.java).invoke(writer, stream)
        val param = writerClass.getMethod("getDefaultWriteParam").invoke(writer)
        val paramClass = Class.forName("javax.imageio.ImageWriteParam")
        paramClass.getMethod("setCompressionMode", Int::class.javaPrimitiveType).invoke(param, MODE_EXPLICIT)
        paramClass.getMethod("setCompressionQuality", Float::class.javaPrimitiveType).invoke(param, quality)
        val iioImage = Class.forName("javax.imageio.IIOImage")
            .getConstructor(renderedImage, List::class.java, Class.forName("javax.imageio.metadata.IIOMetadata"))
            .newInstance(image, null, null)
        writerClass.getMethod(
            "write",
            Class.forName("javax.imageio.metadata.IIOMetadata"),
            iioImage.javaClass,
            paramClass,
        ).invoke(writer, null, iioImage, param)
        (stream as AutoCloseable).close()
        writerClass.getMethod("dispose").invoke(writer)
        return out.toByteArray()
    }

    fun png(argb: IntArray, width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        imageIo.getMethod("write", renderedImage, String::class.java, OutputStream::class.java)
            .invoke(null, bufferedImage(argb, width, height), "png", out)
        return out.toByteArray()
    }

    /** A JPEG or PNG's pixels; `null` when ImageIO cannot read it. */
    fun decode(bytes: ByteArray): Image? {
        val image = imageIo.getMethod("read", InputStream::class.java).invoke(null, ByteArrayInputStream(bytes)) ?: return null
        val width = buffered.getMethod("getWidth").invoke(image) as Int
        val height = buffered.getMethod("getHeight").invoke(image) as Int
        val argb = buffered.getMethod(
            "getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(image, 0, 0, width, height, null, 0, width) as IntArray
        return Image(width, height, argb)
    }

    private fun bufferedImage(argb: IntArray, width: Int, height: Int): Any {
        val int = Int::class.javaPrimitiveType
        val image = buffered.getConstructor(int, int, int).newInstance(width, height, TYPE_INT_RGB)
        buffered.getMethod("setRGB", int, int, int, int, IntArray::class.java, int, int)
            .invoke(image, 0, 0, width, height, argb, 0, width)
        return image
    }

    private val imageIo by lazy { Class.forName("javax.imageio.ImageIO") }
    private val buffered by lazy { Class.forName("java.awt.image.BufferedImage") }
    private val renderedImage by lazy { Class.forName("java.awt.image.RenderedImage") }
    private const val TYPE_INT_RGB = 1
    private const val MODE_EXPLICIT = 2
    private const val JPEG_QUALITY = 0.85f
}

/** The JVM's stand-in for the app's `Bitmap.compress`: the atlas as a JPEG through ImageIO. */
internal object JvmAtlasEncoder : AtlasEncoder {
    override fun jpeg(argb: IntArray, width: Int, height: Int): ByteArray = JvmImages.jpeg(argb, width, height)
}

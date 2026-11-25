package io.github.sceneview.demo.demos.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Owned bytes only: never retain an ARCore Frame, Image, or plane buffer after the callback. */
internal data class AskYuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
)

internal object AskFrameCapture {
    /** Invoke synchronously inside onSessionUpdated, only once when a live question starts. */
    fun copy(frame: Frame): AskYuvFrame? {
        val image = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            return null
        }
        try {
            val crop = image.cropRect
            val width = crop.width()
            val height = crop.height()
            require(width % 2 == 0 && height % 2 == 0)
            val copied = image.planes.mapIndexed { index, plane ->
                val divisor = if (index == 0) 1 else 2
                copyAskPlane(
                    plane.buffer, width / divisor, height / divisor, plane.rowStride,
                    plane.pixelStride, crop.left / divisor, crop.top / divisor,
                )
            }
            return AskYuvFrame(width, height, copied[0], copied[1], copied[2])
        } finally {
            image.close()
        }
    }

    /** All conversion, orientation, scaling and compression happens off the render thread. */
    suspend fun jpeg(frame: AskYuvFrame, rotationDegrees: Int): AskLiveImage = withContext(Dispatchers.Default) {
        val nv21 = askNv21(frame)
        val intermediate = ByteArrayOutputStream().use { output ->
            check(YuvImage(nv21, ImageFormat.NV21, frame.width, frame.height, null)
                .compressToJpeg(Rect(0, 0, frame.width, frame.height), 100, output))
            output.toByteArray()
        }
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(intermediate, 0, intermediate.size))
        try {
            val rotation = normalizedAskRotation(rotationDegrees)
            val oriented = if (rotation == 0) decoded else Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true,
            )
            try {
                val (rotatedWidth, rotatedHeight) = askRotatedDimensions(frame.width, frame.height, rotation)
                val (width, height) = askScaledDimensions(rotatedWidth, rotatedHeight)
                val scaled = Bitmap.createScaledBitmap(oriented, width, height, true)
                try {
                    val bytes = ByteArrayOutputStream().use { output ->
                        check(scaled.compress(Bitmap.CompressFormat.JPEG, 70, output))
                        output.toByteArray()
                    }
                    val data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    require(data.length <= 400 * 1024) { "Camera image exceeds upload limit" }
                    AskLiveImage(data)
                } finally {
                    if (scaled !== oriented) scaled.recycle()
                }
            } finally {
                if (oriented !== decoded) oriented.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }
}

/** Handles row padding, interleaved chroma, crop offsets and nonzero buffer positions. */
internal fun copyAskPlane(
    buffer: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
    cropLeft: Int = 0,
    cropTop: Int = 0,
): ByteArray {
    require(width > 0 && height > 0 && rowStride > 0 && pixelStride > 0)
    val source = buffer.duplicate()
    val start = source.position() + cropTop * rowStride + cropLeft * pixelStride
    return ByteArray(width * height) { index ->
        source.get(start + (index / width) * rowStride + (index % width) * pixelStride)
    }
}

internal fun askNv21(frame: AskYuvFrame): ByteArray {
    require(frame.width > 0 && frame.height > 0 && frame.width % 2 == 0 && frame.height % 2 == 0)
    val pixels = frame.width * frame.height
    require(frame.y.size == pixels && frame.u.size == pixels / 4 && frame.v.size == pixels / 4)
    return ByteArray(pixels + pixels / 2).also { bytes ->
        frame.y.copyInto(bytes)
        frame.u.indices.forEach { index ->
            bytes[pixels + index * 2] = frame.v[index]
            bytes[pixels + index * 2 + 1] = frame.u[index]
        }
    }
}

internal fun normalizedAskRotation(degrees: Int): Int {
    require(degrees % 90 == 0)
    return (degrees % 360 + 360) % 360
}

/** Rear camera sensor orientation minus display rotation, both expressed in degrees. */
internal fun askCameraRotationDegrees(sensorDegrees: Int, displayDegrees: Int): Int =
    normalizedAskRotation(sensorDegrees - displayDegrees)

internal fun askRotatedDimensions(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
    if (normalizedAskRotation(rotationDegrees) % 180 == 0) width to height else height to width

internal fun askScaledDimensions(width: Int, height: Int, maxSide: Int = 768): Pair<Int, Int> {
    require(width > 0 && height > 0 && maxSide > 0)
    val ratio = (maxSide.toDouble() / maxOf(width, height)).coerceAtMost(1.0)
    return (width * ratio).roundToInt().coerceAtLeast(1) to (height * ratio).roundToInt().coerceAtLeast(1)
}

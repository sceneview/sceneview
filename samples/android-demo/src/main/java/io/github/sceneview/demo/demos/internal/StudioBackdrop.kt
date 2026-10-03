package io.github.sceneview.demo.demos.internal

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import com.google.android.filament.Engine
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.safeDestroySkybox
import io.github.sceneview.safeDestroyTexture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/** A decoded equirectangular HDR image: `width * height` scene-linear RGB triplets, top row first. */
internal class EquirectImage(val width: Int, val height: Int, val rgb: FloatArray) {
    init {
        require(rgb.size == width * height * 3) { "Expected ${width * height * 3} floats, got ${rgb.size}" }
    }
}

/**
 * The Materials demo backdrop: the environment's own HDR, drawn as a soft, highlight-rolled
 * skybox instead of the one `EnvironmentLoader.createHDREnvironment` builds (#4065).
 *
 * That default skybox is the GPU prefilter's cubemap, 256 px a face, sampled at its sharpest
 * level. Behind a sphere wall filmed at a narrow field of view one of its texels covers
 * about 20 screen pixels, and the studio's light panels run ten to a hundred times brighter
 * than the tone mapper's white. Bilinear filtering ramps across each texel in scene-linear
 * light, the tone mapper then clips most of that ramp to white, and what is left of every
 * edge is the texel grid itself: the stair-stepped blocks the demo showed.
 *
 * This backdrop fixes both halves on the CPU, off the main thread:
 * 1. [compressHighlights] rolls luminance above [KNEE] toward [CEILING], so a bright edge
 *    stays inside the tone curve's working range and its ramp stays visible as a ramp.
 * 2. [blur] softens the image by [BLUR_SIGMA] source texels (about half a degree), which is
 *    what a real lens focused on a sphere a metre away does to a wall several metres
 *    behind it — the blur reads as depth of field, not as missing resolution.
 * 3. [buildCubeFaces] resamples to a [FACE_SIZE] px cube, twice the prefilter's.
 *
 * The lighting is untouched: the spheres still take their reflections and irradiance from
 * the full-range IBL the loader prefilters from the same file.
 */
internal object StudioBackdrop {

    /** Cube face edge in pixels. */
    const val FACE_SIZE: Int = 512

    /** Luminance, in scene-linear units, under which the backdrop is left exactly as shot. */
    const val KNEE: Float = 1f

    /** Luminance the highlights roll toward and never pass. */
    const val CEILING: Float = 4f

    /** Gaussian blur radius, in source texels (1024 px across 360° ≈ 0.35° a texel). */
    const val BLUR_SIGMA: Float = 1.5f

    /**
     * Decodes a Radiance `.hdr` (RGBE) file, flat or new-style run-length encoded, as written
     * by every HDRI library the demo draws from.
     */
    fun decodeRgbe(bytes: ByteArray): EquirectImage {
        var pos = 0
        fun readLine(): String {
            val start = pos
            while (pos < bytes.size && bytes[pos] != '\n'.code.toByte()) pos++
            val line = String(bytes, start, pos - start, Charsets.US_ASCII)
            pos++
            return line
        }
        require(readLine().startsWith("#?")) { "Not a Radiance HDR file" }
        var resolution: String
        do {
            resolution = readLine()
            require(pos <= bytes.size) { "Truncated HDR header" }
        } while (!resolution.startsWith("-Y "))
        val parts = resolution.trim().split(Regex("\\s+"))
        require(parts.size == 4 && parts[2] == "+X") { "Unsupported HDR orientation: $resolution" }
        val height = parts[1].toInt()
        val width = parts[3].toInt()

        val scanline = ByteArray(width * 4)
        val rgb = FloatArray(width * height * 3)
        for (y in 0 until height) {
            val rle = width in 8..0x7fff &&
                bytes[pos].toInt() == 2 && bytes[pos + 1].toInt() == 2 &&
                (bytes[pos + 2].toInt() and 0x80) == 0
            if (rle) {
                pos += 4
                for (channel in 0 until 4) pos = readRleChannel(bytes, pos, width, channel, scanline)
            } else {
                System.arraycopy(bytes, pos, scanline, 0, width * 4)
                pos += width * 4
            }
            unpackScanline(scanline, width, rgb, y * width * 3)
        }
        return EquirectImage(width, height, rgb)
    }

    /** Reads one run-length encoded [channel] of a scanline into [scanline]; returns the new offset. */
    private fun readRleChannel(bytes: ByteArray, start: Int, width: Int, channel: Int, scanline: ByteArray): Int {
        var pos = start
        var x = 0
        while (x < width) {
            var count = bytes[pos++].toInt() and 0xff
            if (count > 128) {
                count -= 128
                val value = bytes[pos++]
                repeat(count) { scanline[(x + it) * 4 + channel] = value }
            } else {
                repeat(count) { scanline[(x + it) * 4 + channel] = bytes[pos++] }
            }
            x += count
        }
        return pos
    }

    /** Converts one RGBE [scanline] to linear RGB floats at [offset] in [rgb]. */
    private fun unpackScanline(scanline: ByteArray, width: Int, rgb: FloatArray, offset: Int) {
        for (x in 0 until width) {
            val e = scanline[x * 4 + 3].toInt() and 0xff
            if (e == 0) continue
            val scale = Math.scalb(1f, e - 136)
            val out = offset + x * 3
            rgb[out] = (scanline[x * 4].toInt() and 0xff) * scale
            rgb[out + 1] = (scanline[x * 4 + 1].toInt() and 0xff) * scale
            rgb[out + 2] = (scanline[x * 4 + 2].toInt() and 0xff) * scale
        }
    }

    /**
     * The luminance [value] maps to: unchanged up to [knee], then rolled off smoothly —
     * continuous in value and slope at the knee — toward [ceiling], which it never reaches.
     */
    fun rollOff(value: Float, knee: Float = KNEE, ceiling: Float = CEILING): Float {
        if (value <= knee) return value
        val range = ceiling - knee
        val over = value - knee
        return knee + over / (1f + over / range)
    }

    /** Applies [rollOff] to every pixel's luminance, in place, keeping its hue. */
    fun compressHighlights(image: EquirectImage, knee: Float = KNEE, ceiling: Float = CEILING) {
        val rgb = image.rgb
        for (i in rgb.indices step 3) {
            val luminance = 0.2126f * rgb[i] + 0.7152f * rgb[i + 1] + 0.0722f * rgb[i + 2]
            if (luminance <= knee) continue
            val scale = rollOff(luminance, knee, ceiling) / luminance
            rgb[i] *= scale
            rgb[i + 1] *= scale
            rgb[i + 2] *= scale
        }
    }

    /**
     * A separable Gaussian blur of [sigma] texels: wraps around horizontally, where the
     * panorama's seam is, and clamps vertically at the poles.
     */
    fun blur(image: EquirectImage, sigma: Float = BLUR_SIGMA): EquirectImage {
        if (sigma <= 0f) return image
        val radius = (sigma * 3f).toInt().coerceAtLeast(1)
        val kernel = FloatArray(radius * 2 + 1) { exp(-((it - radius) * (it - radius)) / (2f * sigma * sigma)) }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum

        val w = image.width
        val h = image.height
        val src = image.rgb
        val horizontal = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0f; var g = 0f; var b = 0f
                for (k in kernel.indices) {
                    val sx = Math.floorMod(x + k - radius, w)
                    val i = (y * w + sx) * 3
                    r += src[i] * kernel[k]; g += src[i + 1] * kernel[k]; b += src[i + 2] * kernel[k]
                }
                val o = (y * w + x) * 3
                horizontal[o] = r; horizontal[o + 1] = g; horizontal[o + 2] = b
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0f; var g = 0f; var b = 0f
                for (k in kernel.indices) {
                    val sy = (y + k - radius).coerceIn(0, h - 1)
                    val i = (sy * w + x) * 3
                    r += horizontal[i] * kernel[k]; g += horizontal[i + 1] * kernel[k]; b += horizontal[i + 2] * kernel[k]
                }
                val o = (y * w + x) * 3
                out[o] = r; out[o + 1] = g; out[o + 2] = b
            }
        }
        return EquirectImage(w, h, out)
    }

    /**
     * The unit-length world direction through pixel ([px], [py]) of cube [face], in
     * Filament's face order (+X, −X, +Y, −Y, +Z, −Z) and the OpenGL cube-map layout it
     * uploads to — the same mapping `cmgen` writes its faces with.
     */
    fun direction(face: Int, px: Float, py: Float, size: Int, out: FloatArray) {
        val cx = 2f * px / size - 1f
        val cy = 1f - 2f * py / size
        val x: Float; val y: Float; val z: Float
        when (face) {
            0 -> { x = 1f; y = cy; z = -cx }
            1 -> { x = -1f; y = cy; z = cx }
            2 -> { x = cx; y = 1f; z = -cy }
            3 -> { x = cx; y = -1f; z = cy }
            4 -> { x = cx; y = cy; z = 1f }
            5 -> { x = -cx; y = cy; z = -1f }
            else -> error("Cube face $face")
        }
        val inv = 1f / sqrt(x * x + y * y + z * z)
        out[0] = x * inv; out[1] = y * inv; out[2] = z * inv
    }

    /**
     * Bilinearly samples [image] along direction ([x], [y], [z]) into [out], with the
     * equirectangular mapping Filament's `EquirectangularToCubemap` uses, so the backdrop
     * lines up with the reflections the IBL prefilters from the same image.
     */
    fun sample(image: EquirectImage, x: Float, y: Float, z: Float, out: FloatArray) {
        val u = (atan2(x, z) / PI.toFloat() + 1f) * 0.5f
        val v = (1f - asin(y.coerceIn(-1f, 1f)) * 2f / PI.toFloat()) * 0.5f
        val fx = u * image.width - 0.5f
        val fy = (v * image.height - 0.5f).coerceIn(0f, image.height - 1f)
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val xa = Math.floorMod(x0, image.width)
        val xb = Math.floorMod(x0 + 1, image.width)
        val ya = y0.coerceIn(0, image.height - 1)
        val yb = (y0 + 1).coerceIn(0, image.height - 1)
        val rgb = image.rgb
        for (c in 0 until 3) {
            val top = rgb[(ya * image.width + xa) * 3 + c] * (1f - tx) + rgb[(ya * image.width + xb) * 3 + c] * tx
            val bottom = rgb[(yb * image.width + xa) * 3 + c] * (1f - tx) + rgb[(yb * image.width + xb) * 3 + c] * tx
            out[c] = top * (1f - ty) + bottom * ty
        }
    }

    /**
     * The six faces of a [size] px cube, face after face, as RGB floats in [direction]'s order.
     *
     * Each texel samples the panorama along its direction **mirrored in x**, as Filament's
     * `IBLPrefilterContext.EquirectangularToCubemap` does by default (`Config.mirror = true`,
     * `equirectToCube.mat`). Without the mirror the backdrop shows a different part of the
     * room than the reflections the IBL is built from.
     */
    fun buildCubeFaces(image: EquirectImage, size: Int = FACE_SIZE): FloatArray {
        val faces = FloatArray(6 * size * size * 3)
        val dir = FloatArray(3)
        val texel = FloatArray(3)
        var o = 0
        for (face in 0 until 6) {
            for (py in 0 until size) {
                for (px in 0 until size) {
                    direction(face, px + 0.5f, py + 0.5f, size, dir)
                    sample(image, -dir[0], dir[1], dir[2], texel)
                    faces[o++] = texel[0]; faces[o++] = texel[1]; faces[o++] = texel[2]
                }
            }
        }
        return faces
    }

    /** Decode, roll off, blur and resample — the whole CPU half, for a background thread. */
    fun prepare(bytes: ByteArray, size: Int = FACE_SIZE): FloatArray {
        val image = decodeRgbe(bytes)
        compressHighlights(image)
        return buildCubeFaces(blur(image), size)
    }

    /** Filament half: uploads [faces] and wraps them in a skybox. Main thread only. */
    fun createSkybox(engine: Engine, faces: FloatArray, size: Int = FACE_SIZE): Backdrop {
        val texture = Texture.Builder()
            .width(size)
            .height(size)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(Texture.InternalFormat.R11F_G11F_B10F)
            .build(engine)
        val buffer = ByteBuffer.allocateDirect(faces.size * 4).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(faces)
        val faceBytes = size * size * 3 * 4
        @Suppress("DEPRECATION")
        texture.setImage(
            engine,
            0,
            Texture.PixelBufferDescriptor(buffer, Texture.Format.RGB, Texture.Type.FLOAT),
            IntArray(6) { it * faceBytes },
        )
        val skybox = Skybox.Builder().environment(texture).build(engine)
        return Backdrop(skybox, texture)
    }

    /** A skybox and the cubemap it owns — Filament does not destroy one with the other. */
    class Backdrop(val skybox: Skybox, val texture: Texture)
}

/**
 * The Materials demo's complete IBL + processed skybox for [assetPath]. The previous pair remains
 * visible until both parts of the replacement are ready, so a path change cannot expose a neutral
 * fallback or pair a new reflection with the old backdrop.
 */
@Composable
internal fun rememberStudioEnvironment(
    engine: Engine,
    environmentLoader: EnvironmentLoader,
    assetPath: String,
): Environment? {
    val context: Context = LocalContext.current.applicationContext
    val state = remember(engine, environmentLoader) {
        mutableStateOf<StudioEnvironmentResources?>(null)
    }
    val retired = remember(engine, environmentLoader) { mutableListOf<StudioEnvironmentResources>() }
    LaunchedEffect(engine, environmentLoader, assetPath) {
        var loadedEnvironment: Environment? = null
        var backdrop: StudioBackdrop.Backdrop? = null
        var published = false
        try {
            val loaded = environmentLoader.loadHDREnvironment(
                url = assetPath,
                createSkybox = false,
            ) ?: return@LaunchedEffect
            loadedEnvironment = loaded
            val faces = try {
                withContext(Dispatchers.Default) {
                    StudioBackdrop.prepare(context.assets.open(assetPath).use { it.readBytes() })
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                // Keep the pair on screen: a backdrop that failed to decode is not worth a
                // neutral fallback mid-session.
                Log.w(TAG, "Studio backdrop for $assetPath failed to decode", error)
                return@LaunchedEffect
            }
            currentCoroutineContext().ensureActive()
            val builtBackdrop = runCatching { StudioBackdrop.createSkybox(engine, faces) }.getOrNull()
                ?: return@LaunchedEffect
            backdrop = builtBackdrop
            currentCoroutineContext().ensureActive()

            val replacement = StudioEnvironmentResources(loaded, builtBackdrop)
            val previous = state.value
            state.value = replacement
            published = true
            previous?.let(retired::add)
        } finally {
            // A cancelled/superseded request owns everything it built until publication.
            if (!published) {
                backdrop?.destroy(engine)
                loadedEnvironment?.let(environmentLoader::destroyEnvironment)
            }
        }
    }
    LaunchedEffect(state.value) {
        repeat(2) { withFrameNanos { } }
        retired.forEach { it.destroy(engine, environmentLoader) }
        retired.clear()
    }
    DisposableEffect(engine, environmentLoader, state) {
        onDispose {
            state.value?.destroy(engine, environmentLoader)
            state.value = null
            retired.forEach { it.destroy(engine, environmentLoader) }
            retired.clear()
        }
    }
    return state.value?.environment
}

private const val TAG = "StudioBackdrop"

private class StudioEnvironmentResources(
    private val loadedEnvironment: Environment,
    private val backdrop: StudioBackdrop.Backdrop,
) {
    val environment: Environment = loadedEnvironment.copy(skybox = backdrop.skybox)

    fun destroy(engine: Engine, environmentLoader: EnvironmentLoader) {
        environmentLoader.destroyEnvironment(loadedEnvironment)
        backdrop.destroy(engine)
    }
}

private fun StudioBackdrop.Backdrop.destroy(engine: Engine) {
    engine.safeDestroySkybox(skybox)
    engine.safeDestroyTexture(texture)
}

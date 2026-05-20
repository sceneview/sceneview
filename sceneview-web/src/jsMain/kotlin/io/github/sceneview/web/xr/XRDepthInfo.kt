@file:Suppress("INTERFACE_WITH_SUPERCLASS")

package io.github.sceneview.web.xr

/**
 * External declaration for [XRDepthInformation](https://www.w3.org/TR/webxr-depth-sensing-1/#xrdepthinformation-interface).
 *
 * Provides per-frame depth data captured by the XR runtime (e.g. Quest 3 passthrough,
 * recent Android Chrome on ARCore-capable devices). The depth buffer encodes the
 * distance, in meters, from the camera to the closest real-world surface at each
 * sampled pixel.
 *
 * Two delivery modes exist, selected at session-creation time via the
 * `depthSensing.dataFormatPreference` option:
 *
 * - **CPU usage** — exposes the depth values as an `ArrayBuffer` (read on the CPU,
 *   typically every frame; see [XRCPUDepthInformation]).
 * - **GPU usage** — exposes the depth values as a `WebGLTexture` for direct sampling
 *   inside a fragment shader (see [XRWebGLDepthInformation]).
 *
 * Use [XRFrame.getDepthInformation] (CPU) or
 * `XRWebGLBinding.getDepthInformation(view)` (GPU) to retrieve the per-frame
 * instance. See [DepthOcclusionShader] for the matching fragment-shader port that
 * mirrors Android `ARCameraStream` depth occlusion.
 *
 * Mirrors Android's `Frame.acquireDepthImage16Bits()` / `acquireRawDepthImage16Bits()`.
 *
 * @see <a href="https://www.w3.org/TR/webxr-depth-sensing-1/">WebXR Depth Sensing Module</a>
 */
external interface XRDepthInformation {
    /** Width of the depth buffer in pixels. */
    val width: Int

    /** Height of the depth buffer in pixels. */
    val height: Int

    /**
     * Transform from the **normalized [0,1] view coordinates** of the matching
     * `XRView` to the **normalized [0,1] depth-buffer coordinates**, as an
     * `XRRigidTransform`-equivalent 4x4 matrix. Applied as a `vec2` mapping in
     * the depth-occlusion fragment shader.
     */
    val normDepthBufferFromNormView: XRRigidTransform

    /**
     * Multiplier converting the raw depth-buffer sample to **meters**.
     *
     * For the CPU/luminance-alpha format: each sample is a 16-bit integer
     * and the value in meters is `sample * rawValueToMeters`.
     */
    val rawValueToMeters: Double
}

/**
 * CPU-readable depth information — accessible via [XRFrame.getDepthInformation].
 *
 * The depth values are packed as a typed array. Use [getDepthInMeters] to sample
 * a single pixel (the helper applies the spec-defined extraction rules).
 *
 * @see <a href="https://www.w3.org/TR/webxr-depth-sensing-1/#xr-cpu-depth-info-section">XRCPUDepthInformation</a>
 */
external interface XRCPUDepthInformation : XRDepthInformation {
    /** The depth values as an opaque `ArrayBuffer`. */
    val data: dynamic /* ArrayBuffer */

    /**
     * Get the depth value at `(x, y)` in normalized view coordinates (range `[0, 1]`).
     *
     * @return Depth in meters from the camera, or `0.0` if the sample is invalid.
     */
    fun getDepthInMeters(x: Double, y: Double): Double
}

/**
 * GPU-readable depth information — accessible via `XRWebGLBinding.getDepthInformation(view)`.
 *
 * The depth buffer is exposed as a `WebGLTexture` for direct sampling inside a
 * fragment shader (see [DepthOcclusionShader.FRAGMENT_SHADER]).
 *
 * @see <a href="https://www.w3.org/TR/webxr-depth-sensing-1/#xr-webgl-depth-info-section">XRWebGLDepthInformation</a>
 */
external interface XRWebGLDepthInformation : XRDepthInformation {
    /** The depth buffer as a `WebGLTexture` ready to bind for sampling. */
    val texture: dynamic /* WebGLTexture */
}

/**
 * Depth-sensing usage mode constants for `XRSessionInit.depthSensing.usagePreference`.
 *
 * Pass one or both at session creation:
 *
 * ```kotlin
 * val options = js("{}")
 * options.requiredFeatures = arrayOf(XRFeature.DEPTH_SENSING)
 * options.depthSensing = js("{}")
 * options.depthSensing.usagePreference = arrayOf(XRDepthUsage.GPU_OPTIMIZED, XRDepthUsage.CPU_OPTIMIZED)
 * options.depthSensing.dataFormatPreference = arrayOf(XRDepthFormat.LUMINANCE_ALPHA, XRDepthFormat.FLOAT32)
 * ```
 */
object XRDepthUsage {
    /** Depth values exposed as `ArrayBuffer` for CPU reads. */
    const val CPU_OPTIMIZED = "cpu-optimized"

    /** Depth values exposed as `WebGLTexture` for GPU shader sampling. */
    const val GPU_OPTIMIZED = "gpu-optimized"
}

/**
 * Depth-sensing data-format constants for `XRSessionInit.depthSensing.dataFormatPreference`.
 */
object XRDepthFormat {
    /** Two-channel 8-bit luminance-alpha (16 bits per sample, integer). */
    const val LUMINANCE_ALPHA = "luminance-alpha"

    /** Single-channel 32-bit float (meters). */
    const val FLOAT32 = "float32"
}

/**
 * High-level wrapper around [XRDepthInformation] exposing the **closest** values
 * used by [DepthOcclusionShader] — keeps the call site free of `dynamic` access.
 *
 * Build via [from] inside the render loop.
 *
 * ```kotlin
 * val raw = frame.asDynamic().getDepthInformation(view) as? XRCPUDepthInformation
 * val depth = XRDepthInfo.from(raw)
 * if (depth != null && depth.atNormalized(0.5, 0.5) < 1.0) {
 *     // Surface within 1m of viewport center
 * }
 * ```
 */
class XRDepthInfo private constructor(
    /** Underlying CPU/GPU depth payload — may be either flavor. */
    val raw: XRDepthInformation,
    /** Width in pixels of the depth buffer. */
    val width: Int,
    /** Height in pixels of the depth buffer. */
    val height: Int,
    /** Conversion factor from raw sample to meters (see [XRDepthInformation.rawValueToMeters]). */
    val rawValueToMeters: Double,
) {
    /** True if this is a CPU-readable buffer that supports [atNormalized]. */
    val isCpuReadable: Boolean
        get() = jsTypeOf((raw as? XRCPUDepthInformation)?.asDynamic()?.getDepthInMeters) == "function"

    /**
     * Sample the depth in meters at `(x, y)` in **normalized view coordinates**
     * (`(0,0)` = top-left of the view, `(1,1)` = bottom-right).
     *
     * Returns `Double.POSITIVE_INFINITY` if the underlying buffer is GPU-only or
     * the sample is invalid. Use [isCpuReadable] to gate the call.
     */
    fun atNormalized(x: Double, y: Double): Double {
        val cpu = raw as? XRCPUDepthInformation ?: return Double.POSITIVE_INFINITY
        if (!isCpuReadable) return Double.POSITIVE_INFINITY
        val clampedX = if (x.isNaN()) 0.5 else x.coerceIn(0.0, 1.0)
        val clampedY = if (y.isNaN()) 0.5 else y.coerceIn(0.0, 1.0)
        val v = cpu.getDepthInMeters(clampedX, clampedY)
        return if (v.isNaN() || v <= 0.0) Double.POSITIVE_INFINITY else v
    }

    companion object {
        /**
         * Build an [XRDepthInfo] from a per-frame [XRDepthInformation] payload.
         * Returns `null` if the input is `null` or its `width`/`height` is zero
         * (which the spec uses to signal "no depth available this frame").
         */
        fun from(info: XRDepthInformation?): XRDepthInfo? {
            if (info == null) return null
            val w = info.width
            val h = info.height
            if (w <= 0 || h <= 0) return null
            val r = info.rawValueToMeters
            // Spec disallows non-positive rawValueToMeters but guard defensively.
            if (r.isNaN() || r <= 0.0) return null
            return XRDepthInfo(info, w, h, r)
        }
    }
}

/**
 * Depth-occlusion shader fragments — a Filament-compatible GLSL port of the
 * Android `ARCameraStream` depth-occlusion logic, intended for use as a
 * post-process or material plug-in on Filament.js.
 *
 * The fragment-shader logic samples [XRWebGLDepthInformation.texture] at the
 * current screen position, converts the raw sample to meters via
 * `uRawValueToMeters`, and discards/dims fragments that are **behind** the
 * real-world surface. This mirrors `ARCameraStream`'s
 * "depth occlusion" mode shipped on Android.
 *
 * Wire-up from Kotlin:
 *
 * ```kotlin
 * val matBuilder = filament.Material.Builder()
 *     .package_(/* compiled material bytes */)
 *     .build(engine)
 * val mi = matBuilder.createInstance()
 * mi.setParameter("uDepthTexture", depthInfo.texture)
 * mi.setParameter("uRawValueToMeters", depthInfo.rawValueToMeters.toFloat())
 * mi.setParameter("uNormDepthBufferFromNormView", depthInfo.normDepthBufferFromNormView.matrix)
 * ```
 *
 * In a typical Filament.js pipeline the fragment is invoked from the
 * `material { ... fragmentShader(...) }` slot. The shader is intentionally
 * GLSL-300-ES (the WebGL2 default) and stays under 80 instructions to keep the
 * mobile fragment cost low.
 *
 * @see <a href="https://github.com/google-ar/sceneform-android-sdk">Sceneform reference</a>
 * @see <a href="https://github.com/google-ar/arcore-android-sdk/tree/main/samples/depth_java">ARCore depth samples</a>
 */
object DepthOcclusionShader {
    /** GLSL vertex shader — passes the normalized view UV through. */
    const val VERTEX_SHADER: String = """#version 300 es
precision highp float;
in vec3 position;
in vec2 uv0;
out vec2 vNormViewUV;
uniform mat4 uMvp;
void main() {
    vNormViewUV = uv0;
    gl_Position = uMvp * vec4(position, 1.0);
}
"""

    /**
     * GLSL fragment shader — depth occlusion against the WebXR depth buffer.
     *
     * Uniforms:
     * - `uDepthTexture` — WebGL texture from [XRWebGLDepthInformation.texture]
     * - `uRawValueToMeters` — float, the [XRDepthInformation.rawValueToMeters] factor
     * - `uNormDepthBufferFromNormView` — mat4, [XRDepthInformation.normDepthBufferFromNormView] matrix
     * - `uColor` — vec4 base color
     * - `uFragmentDepthMeters` — float, the virtual fragment's distance from the camera
     *
     * Behavior:
     * - Samples the depth texture at the warped UV.
     * - If the real-world depth is **closer** than the virtual fragment's depth,
     *   the fragment is discarded (occluded by real geometry).
     * - Otherwise the fragment is emitted with `uColor`.
     */
    const val FRAGMENT_SHADER: String = """#version 300 es
precision highp float;
in vec2 vNormViewUV;
out vec4 fragColor;
uniform sampler2D uDepthTexture;
uniform float uRawValueToMeters;
uniform mat4 uNormDepthBufferFromNormView;
uniform vec4 uColor;
uniform float uFragmentDepthMeters;

float sampleDepthMeters(vec2 uv) {
    // Map view UV -> depth-buffer UV via the spec-defined matrix
    vec4 warped = uNormDepthBufferFromNormView * vec4(uv, 0.0, 1.0);
    vec2 dUV = warped.xy;
    // luminance-alpha encoding: raw = (G * 256 + R) (16-bit little-endian)
    vec2 packed = texture(uDepthTexture, dUV).ra;
    float raw = packed.r * 255.0 + packed.g * 255.0 * 256.0;
    return raw * uRawValueToMeters;
}

void main() {
    float realDepth = sampleDepthMeters(vNormViewUV);
    // realDepth == 0 means "no depth available" -> render normally
    if (realDepth > 0.0 && realDepth < uFragmentDepthMeters) {
        discard;
    }
    fragColor = uColor;
}
"""
}

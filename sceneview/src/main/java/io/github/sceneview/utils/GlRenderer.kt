package io.github.sceneview.utils

/**
 * How the device's OpenGL ES implementation names itself: `GL_VENDOR`, `GL_RENDERER` and
 * `GL_VERSION`, as read by [OpenGL.queryRenderer].
 *
 * Read it before creating an engine to keep a scene off an implementation it is known not to
 * survive on. Filament reports a shader program that fails to compile or link, a swap chain
 * it cannot create and a few framebuffer preconditions as *panics*: uncaught C++ exceptions
 * that abort the process, which nothing in Kotlin can catch. The only way to handle such a
 * device is not to start rendering on it.
 */
data class GlRenderer(
    /** `GL_VENDOR`, for example `Qualcomm` or `Google Inc.`. */
    val vendor: String,
    /** `GL_RENDERER`, for example `Adreno (TM) 740` or `Google SwiftShader`. */
    val renderer: String,
    /** `GL_VERSION`, for example `OpenGL ES 3.2 V@0676.42` or `OpenGL ES 3.0 SwiftShader 4.1.0.7`. */
    val version: String,
) {
    /**
     * True for the *legacy SwiftShader GLES frontend* running inside the guest: the CPU
     * rasteriser Android 11 and older ship as `libGLESv2_swiftshader.so`, which virtual
     * devices without a GPU (cloud phones, containers, app scanners) fall back to. It stops
     * at OpenGL ES 3.0 and its shader compiler has hard limits a large program exceeds.
     *
     * Deliberately narrow. It is false for the two SwiftShader setups that do render
     * Filament and that every emulator-based test runs on: the Android Emulator's
     * `swiftshader_indirect` mode (`GL_RENDERER` is the emulator's translator, with
     * SwiftShader only named in brackets) and ANGLE over SwiftShader's Vulkan driver
     * (`GL_RENDERER` starts with `ANGLE`).
     */
    val isLegacySwiftShader: Boolean
        get() = renderer.trim().equals(LEGACY_SWIFTSHADER_RENDERER, ignoreCase = true) ||
            LEGACY_SWIFTSHADER_VERSION.containsMatchIn(version.trim())
}

/** `GL_RENDERER` of the legacy frontend, whole — nothing before or after it. */
private const val LEGACY_SWIFTSHADER_RENDERER = "Google SwiftShader"

/** `GL_VERSION` of the legacy frontend: `OpenGL ES <major>.<minor> SwiftShader <build>`. */
private val LEGACY_SWIFTSHADER_VERSION = Regex("""^OpenGL ES \d+\.\d+ SwiftShader\b""")

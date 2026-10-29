package io.github.sceneview.utils

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.GLES11Ext
import android.opengl.GLES30

/**
 * Convenience class to perform common GL operations.
 */
object OpenGL {

    private const val EGL_OPENGL_ES3_BIT = 0x40

    fun createEglContext(): EGLContext {
        return createEglContext(EGL14.EGL_NO_CONTEXT)
            ?: error("EGL context creation failed")
    }

    fun createEglContext(shareContext: EGLContext?): EGLContext? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(display, null, 0, null, 0)
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = intArrayOf(0)
        val attribs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_NONE)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfig, 0)
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(
            display,
            configs[0], shareContext, contextAttribs, 0
        )
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        val surface = EGL14.eglCreatePbufferSurface(
            display,
            configs[0], surfaceAttribs, 0
        )
        check(
            EGL14.eglMakeCurrent(
                display,
                surface,
                surface,
                context
            )
        ) { "Error making GL context." }
        return context
    }

    /**
     * Reads how the device's OpenGL ES implementation names itself, without creating an
     * engine: a throwaway ES 3 context on a 1×1 pbuffer — the same configuration
     * [createEglContext] asks for — is made current, queried and destroyed.
     *
     * Use it to decide *before* [io.github.sceneview.rememberEngine] whether to render at
     * all, for example to keep a scene off [GlRenderer.isLegacySwiftShader].
     *
     * Costs a context creation: call it off the main thread and keep the result, it cannot
     * change while the process lives. Whatever context was current on the calling thread is
     * current again when it returns.
     *
     * @return null when no ES 3 context can be created or made current — a device on which
     * [createEglContext] fails too.
     */
    fun queryRenderer(): GlRenderer? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val eglVersion = IntArray(2)
        if (display == EGL14.EGL_NO_DISPLAY ||
            !EGL14.eglInitialize(display, eglVersion, 0, eglVersion, 1)
        ) {
            return null
        }
        val previousDisplay = EGL14.eglGetCurrentDisplay()
        val previousContext = EGL14.eglGetCurrentContext()
        val previousDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val previousRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        return try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = intArrayOf(0)
            val attribs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_NONE)
            val hasConfig = EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfig, 0) &&
                numConfig[0] > 0
            if (hasConfig) {
                val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
                context = EGL14.eglCreateContext(
                    display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
                ) ?: EGL14.EGL_NO_CONTEXT
                val surfaceAttribs = intArrayOf(
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE
                )
                surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0)
                    ?: EGL14.EGL_NO_SURFACE
            }
            val current = context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE &&
                EGL14.eglMakeCurrent(display, surface, surface, context)
            val renderer = if (current) GLES30.glGetString(GLES30.GL_RENDERER) else null
            renderer?.let {
                GlRenderer(
                    vendor = GLES30.glGetString(GLES30.GL_VENDOR).orEmpty(),
                    renderer = it,
                    version = GLES30.glGetString(GLES30.GL_VERSION).orEmpty(),
                )
            }
        } finally {
            if (previousContext != EGL14.EGL_NO_CONTEXT && previousDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(previousDisplay, previousDraw, previousRead, previousContext)
            } else {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
            }
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        }
    }

    fun createExternalTextureId(): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val result = textures[0]
        val textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES30.glBindTexture(textureTarget, result)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        return result
    }

    fun destroyEglContext(context: EGLContext?) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglDestroyContext(display, context)) { "Error destroying GL context." }
    }
}

fun EGLContext.destroy() = OpenGL.destroyEglContext(this)
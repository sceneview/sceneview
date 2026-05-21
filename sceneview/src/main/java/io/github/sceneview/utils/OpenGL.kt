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
// The video overlay compositor: the mirrored scene arrives on a SurfaceTexture, the overlay is
// drawn on a bitmap, and one GL pass writes both to the encoder's surface.
package io.github.sceneview.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Trace
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "SceneRecorder"

/**
 * Composites the [CaptureOverlay] over the mirrored scene with OpenGL ES, on its own thread and
 * EGL context, and writes each frame to the encoder's [output] surface.
 *
 * The scene is mirrored into a [SurfaceTexture]. Latching a frame (`updateTexImage`) makes the
 * GPU, not this thread, wait for the frame to be rendered, and it takes no lock the producer
 * needs. The ImageReader route it replaces waited on the CPU for each frame's GPU fence while
 * holding the reader's lock, which `queueBuffer` on Filament's render thread also takes: the
 * render thread stalled behind every recorded frame and a 1080p video came out at about 7 fps on
 * a Pixel 4a.
 *
 * The overlay is drawn on a software [Canvas] — the same kind of canvas a photo's overlay gets —
 * into a bitmap the size of the video, uploaded once per frame and blended in the same pass as
 * the scene (premultiplied alpha, "source over").
 *
 * The constructor sets EGL up on the compositor thread and throws when it cannot: the caller then
 * falls back to another compositor or records without the overlay.
 */
internal class GlOverlayCompositor(
    size: VideoSize,
    private val output: Surface,
    private val overlay: CaptureOverlay,
) : OverlayCompositor {

    private val width = size.width
    private val height = size.height
    private val thread = HandlerThread("SceneRecorderOverlay").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var sceneTexture = 0
    private var overlayTexture = 0
    private var sceneTransformLocation = -1
    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var overlayBitmap: Bitmap
    private lateinit var overlayCanvas: Canvas
    private val sceneTransform = FloatArray(MATRIX_SIZE)

    /** Read by GL at each draw: held here so it outlives every frame. */
    private var quad: FloatBuffer? = null
    private var sceneSurface: Surface? = null
    private var released = false

    override val inputSurface: Surface

    init {
        inputSurface = try {
            runOnThread { setUp() }
        } catch (e: RuntimeException) {
            runCatching { runOnThread { tearDown() } }
            thread.quitSafely()
            throw e
        }
    }

    private fun setUp(): Surface {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { eglError("eglInitialize") }
        val config = chooseConfig(display)
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { eglError("eglCreateContext") }
        eglSurface = EGL14.eglCreateWindowSurface(
            display, config, output, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { eglError("eglCreateWindowSurface") }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            eglError("eglMakeCurrent")
        }

        program = buildProgram()
        sceneTransformLocation = GLES20.glGetUniformLocation(program, "uSceneTransform")
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uScene"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uOverlay"), 1)
        quad = bindQuad(program)

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        sceneTexture = textures[0]
        overlayTexture = textures[1]
        setUpTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sceneTexture)
        setUpTexture(GLES20.GL_TEXTURE_2D, overlayTexture)

        overlayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        overlayCanvas = Canvas(overlayBitmap)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBitmap, 0)
        checkGl("overlay texture")

        surfaceTexture = SurfaceTexture(sceneTexture).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ drawFrame() }, handler)
        }
        return Surface(surfaceTexture).also { sceneSurface = it }
    }

    private fun drawFrame() {
        if (released) return
        Trace.beginSection("SceneRecorder.overlayFrame")
        try {
            // The GPU waits for the producer's fence; this thread does not.
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(sceneTransform)

            overlayBitmap.eraseColor(Color.TRANSPARENT)
            overlay.draw(overlayCanvas, width, height)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, overlayBitmap)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sceneTexture)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUniformMatrix4fv(sceneTransformLocation, 1, false, sceneTransform, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)

            // The scene's own capture time, so the encoder spaces frames as they were rendered.
            val timestamp = surfaceTexture.timestamp
            if (timestamp > 0) EGLExt.eglPresentationTimeANDROID(display, eglSurface, timestamp)
            if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                // The encoder surface goes away while the recording stops.
                Log.w(TAG, "Overlay frame dropped: ${eglError("eglSwapBuffers")}")
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Overlay frame dropped", e)
        } finally {
            Trace.endSection()
        }
    }

    override fun release() {
        handler.post {
            released = true
            tearDown()
        }
        // quitSafely runs the cleanup above first; wait for it so no overlay frame is drawn on the
        // encoder surface once the caller stops the encoder.
        thread.quitSafely()
        thread.join(RELEASE_TIMEOUT_MS)
    }

    /** Frees what [setUp] created, whatever point it reached. Runs on the compositor thread. */
    private fun tearDown() {
        if (::surfaceTexture.isInitialized) {
            surfaceTexture.setOnFrameAvailableListener(null)
            surfaceTexture.release()
        }
        sceneSurface?.release()
        sceneSurface = null
        quad = null
        if (::overlayBitmap.isInitialized) overlayBitmap.recycle()
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (context != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
            if (program != 0) GLES20.glDeleteProgram(program)
            if (sceneTexture != 0) {
                GLES20.glDeleteTextures(2, intArrayOf(sceneTexture, overlayTexture), 0)
            }
        }
        EGL14.eglMakeCurrent(
            display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
        )
        // Destroying the EGL surface disconnects it from the encoder's surface.
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        // No eglTerminate: the display is the process's, Filament renders on it too.
        EGL14.eglReleaseThread()
        eglSurface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun <T> runOnThread(block: () -> T): T {
        val done = CountDownLatch(1)
        var result: Result<T>? = null
        handler.post {
            result = runCatching(block)
            done.countDown()
        }
        check(done.await(SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Overlay set-up timed out" }
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val RELEASE_TIMEOUT_MS = 500L
        const val SETUP_TIMEOUT_MS = 2_000L
        const val MATRIX_SIZE = 16
        const val QUAD_VERTICES = 4
        const val FLOATS_PER_VERTEX = 2
        const val FLOAT_BYTES = 4

        /** `EGL_RECORDABLE_ANDROID`: some encoders only accept buffers from such a config. */
        const val EGL_RECORDABLE_ANDROID = 0x3142

        /** A full-screen strip; the scene's UVs go through its SurfaceTexture transform. */
        val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        // The overlay bitmap's first row is its top: v is flipped for it, not for the scene.
        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            uniform mat4 uSceneTransform;
            varying vec2 vScene;
            varying vec2 vOverlay;
            void main() {
                vec2 uv = aPosition * 0.5 + 0.5;
                vScene = (uSceneTransform * vec4(uv, 0.0, 1.0)).xy;
                vOverlay = vec2(uv.x, 1.0 - uv.y);
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        // Source over, premultiplied: the bitmap's pixels are premultiplied.
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uScene;
            uniform sampler2D uOverlay;
            varying vec2 vScene;
            varying vec2 vOverlay;
            void main() {
                vec3 scene = texture2D(uScene, vScene).rgb;
                vec4 overlay = texture2D(uOverlay, vOverlay);
                gl_FragColor = vec4(overlay.rgb + scene * (1.0 - overlay.a), 1.0);
            }
        """

        fun chooseConfig(display: EGLDisplay): EGLConfig {
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)) {
                eglError("eglChooseConfig")
            }
            return checkNotNull(configs[0].takeIf { count[0] > 0 }) { "No recordable EGL config" }
        }

        fun buildProgram(): Int {
            val program = GLES20.glCreateProgram()
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glBindAttribLocation(program, 0, "aPosition")
            GLES20.glLinkProgram(program)
            // Flagged for deletion; they go with the program.
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                error("Overlay program did not link: $log")
            }
            return program
        }

        fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source.trimIndent())
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("Overlay shader did not compile: $log")
            }
            return shader
        }

        fun bindQuad(program: Int): FloatBuffer {
            val vertices: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * FLOAT_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(QUAD)
            vertices.position(0)
            val location = GLES20.glGetAttribLocation(program, "aPosition")
            GLES20.glEnableVertexAttribArray(location)
            GLES20.glVertexAttribPointer(
                location, FLOATS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, vertices
            )
            return vertices
        }

        fun setUpTexture(target: Int, texture: Int) {
            GLES20.glBindTexture(target, texture)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        fun checkGl(what: String) {
            val error = GLES20.glGetError()
            check(error == GLES20.GL_NO_ERROR) { "$what: GL error 0x${Integer.toHexString(error)}" }
        }

        fun eglError(call: String) = "$call failed: EGL error 0x${Integer.toHexString(EGL14.eglGetError())}"
    }
}

package io.github.sceneview

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLContext
import android.util.Log
import androidx.startup.Initializer
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.utils.OpenGL

/**
 * Does the part of [rememberEngine] that does not need the main thread, ahead of time, on a
 * background thread: loading Filament's native libraries, initialising the GPU driver and creating
 * the shared EGL context the engine is built on.
 *
 * Without it that work runs inside the composition that first calls [rememberEngine], on the main
 * thread — on a Pixel 7a it is most of the long frame a 3D screen opens with. The next
 * [createEglContext] (the default `eglContextCreator` of [rememberEngine]) takes the prepared
 * context and only has to make it current, instead of creating one.
 *
 * **It starts on its own at app startup**, through the `androidx.startup` initializer
 * [EngineWarmUpInitializer] that SceneView's manifest merges into your app; you need no code.
 * An app that would rather not prepare a GPU context at every launch removes the initializer:
 *
 * ```xml
 * <provider
 *     android:name="androidx.startup.InitializationProvider"
 *     android:authorities="${applicationId}.androidx-startup"
 *     tools:node="merge">
 *     <meta-data
 *         android:name="io.github.sceneview.EngineWarmUpInitializer"
 *         tools:node="remove" />
 * </provider>
 * ```
 *
 * and calls [start] itself, from any thread, a little before its first 3D screen — when the user
 * heads for it, say. [start] is cheap and returns at once.
 *
 * What stays on the main thread, because Filament ties an `Engine` to the thread that creates it
 * (its job system adopts that thread, and `Engine.destroy()` must run there too): building the
 * [com.google.android.filament.Engine] itself. Only the work above moves.
 *
 * A prepared context is handed over once. Until a [createEglContext] takes it, the process holds
 * one EGL context on a 1×1 pbuffer, about the cost of the first `rememberEngine()` it saves.
 */
object EngineWarmUp {

    private const val TAG = "EngineWarmUp"

    /**
     * How long [createEglContext] waits for a warm-up still under way before creating its own
     * context. A safety net only: the warm-up does the same work the caller would, already begun.
     */
    private const val HANDOFF_TIMEOUT_MILLIS = 2_000L

    private val slot = WarmUpSlot<OpenGL.PbufferContext>(
        discard = { it.destroy() },
        onFailure = { Log.w(TAG, "Engine warm-up failed; rememberEngine() will do the work", it) },
    )

    /**
     * Starts the warm-up on a background thread and returns at once. Does nothing while a prepared
     * context is waiting to be taken or being prepared, so it is safe to call more than once: after
     * a [rememberEngine] took the prepared context, a new call prepares one for the next engine.
     */
    @JvmStatic
    fun start() {
        slot.start(threadName = "SceneView-EngineWarmUp") {
            loadFilamentLibraries()
            OpenGL.createPbufferContext(EGL14.EGL_NO_CONTEXT).also { it.releaseFromThisThread() }
        }
    }

    /**
     * The prepared context, made current on the calling thread, or `null` when there is none to
     * hand over. Waits for a warm-up still under way, see [HANDOFF_TIMEOUT_MILLIS].
     */
    internal fun takeEglContext(): EGLContext? {
        val prepared = slot.take(HANDOFF_TIMEOUT_MILLIS) ?: return null
        if (!prepared.makeCurrent()) {
            Log.w(TAG, "Could not make the prepared EGL context current: 0x%x".format(EGL14.eglGetError()))
            prepared.destroy()
            return null
        }
        return prepared.context
    }

    /** Whether a prepared context is waiting to be taken. */
    internal val isReady: Boolean get() = slot.isReady
}

/**
 * Starts [EngineWarmUp] at app startup. Merged into your app's manifest by SceneView; see
 * [EngineWarmUp] to remove it.
 */
class EngineWarmUpInitializer : Initializer<Unit> {
    override fun create(context: Context) = EngineWarmUp.start()

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

/**
 * Loads Filament's three native libraries. Idempotent, and safe from any thread: the second load of
 * a library returns at once, or waits for the first one's `JNI_OnLoad`.
 */
internal fun loadFilamentLibraries() {
    Gltfio.init()
    Filament.init()
    Utils.init()
}

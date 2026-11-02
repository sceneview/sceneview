package io.github.sceneview

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLContext
import android.util.Log
import androidx.startup.Initializer
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.utils.Utils
import io.github.sceneview.utils.OpenGL

/**
 * Does the part of opening a 3D screen that does not need the main thread, ahead of time, on a
 * background thread: loading Filament's native libraries, initialising the GPU driver, creating the
 * shared EGL context the engine is built on, and building the Basis Universal tables gltfio's
 * texture loader needs (see [GltfioTextureTables]).
 *
 * Without it that work runs inside the composition that first calls [rememberEngine] and
 * [rememberModelLoader], on the main thread: on a Pixel 7a, about 55 ms of the long frame a 3D
 * screen opens with. The next [createEglContext] (the default `eglContextCreator` of
 * [rememberEngine]) takes the prepared context and only has to make it current, instead of
 * creating one; the first [io.github.sceneview.loaders.ModelLoader] finds the tables built.
 *
 * **It starts on its own at app startup**, through the `androidx.startup` initializer
 * [EngineWarmUpInitializer] that SceneView's manifest merges into your app; you need no code. It
 * costs about 80 ms of CPU on its own thread (done about 135 ms after the process starts, on a
 * Pixel 7a) and one EGL context held until an engine takes it. An app that would rather not spend
 * that at every launch removes the initializer:
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
 * one EGL context on a 1×1 pbuffer.
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
        slot.start(
            threadName = "SceneView-EngineWarmUp",
            afterwards = GltfioTextureTables::buildOnThisThread,
        ) {
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
 * Basis Universal's transcoder tables, which gltfio builds the first time anything in the process
 * creates a [ResourceLoader] (its KTX2 texture provider calls `basisu_transcoder_init()`): about
 * 45 ms on a Pixel 7a, otherwise paid by the first [io.github.sceneview.loaders.ModelLoader], on the
 * main thread, in the composition that creates it.
 *
 * The tables are process-wide; an engine is only the way gltfio's API reaches the code that builds
 * them. So [EngineWarmUp] builds them on its own thread with a throwaway [Engine], created, used and
 * destroyed on that thread as Filament requires. Basis builds them without any locking, so every
 * [ResourceLoader] SceneView creates goes through [creating], which never overlaps that build.
 */
internal object GltfioTextureTables {

    private val lock = Any()

    @Volatile
    private var built = false

    /** Creates a gltfio [ResourceLoader], never while [buildOnThisThread] is building the tables. */
    fun <T> creating(create: () -> T): T = synchronized(lock) { create().also { built = true } }

    /** Builds the tables with a throwaway engine, unless a [ResourceLoader] was created already. */
    fun buildOnThisThread() {
        if (built) return
        withThrowawayEngine { engine ->
            synchronized(lock) {
                if (!built) {
                    ResourceLoader(engine, true).destroy()
                    built = true
                }
            }
        }
    }

    /**
     * Runs [block] with an [Engine] created before it and destroyed after it, on the calling thread:
     * Filament's job system adopts the thread that creates an engine, and only that thread may
     * destroy it.
     */
    fun withThrowawayEngine(block: (Engine) -> Unit) {
        // The no-op backend: no GPU driver and no EGL context to set up and tear down. One job
        // thread: creating a ResourceLoader schedules nothing on the job system.
        val engine = Engine.Builder()
            .backend(Engine.Backend.NOOP)
            .config(Engine.Config().apply { jobSystemThreadCount = 1 })
            .build()
        try {
            block(engine)
        } finally {
            engine.destroy()
        }
    }
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

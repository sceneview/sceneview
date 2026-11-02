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
 * screen opens with. After a warm-up, the next [createEglContext] (the default `eglContextCreator`
 * of [rememberEngine]) takes the prepared context and only has to make it current, and the first
 * [io.github.sceneview.loaders.ModelLoader] finds the tables built.
 *
 * **Opt-in.** Nothing starts it unless the app asks, because it costs every launch that does
 * it: about 60–80 ms of CPU on its own thread, Filament's libraries and the GPU driver loaded, and
 * one EGL context held until an engine takes it — even a launch that never shows 3D, such as a
 * background start for a push message or a WorkManager job. Start it where a 3D screen is likely
 * to follow:
 *
 * - from code, on any thread, a little before the 3D screen — when the user heads for it, or in
 *   `Application.onCreate()` for an app whose first screen is 3D:
 *   ```kotlin
 *   EngineWarmUp.start()
 *   ```
 * - or at app startup without code, by adding [EngineWarmUpInitializer] to the `androidx.startup`
 *   provider in the app's manifest:
 *   ```xml
 *   <provider
 *       android:name="androidx.startup.InitializationProvider"
 *       android:authorities="${applicationId}.androidx-startup"
 *       android:exported="false"
 *       tools:node="merge">
 *       <meta-data
 *           android:name="io.github.sceneview.EngineWarmUpInitializer"
 *           android:value="androidx.startup" />
 *   </provider>
 *   ```
 *
 * **Started at app startup, it competes with the app's own startup.** Measured on a 3D first
 * screen: on a Pixel 10 Pro XL the 3D frame fell from 194 to 151 ms and time to first frame rose
 * from 134 to 149 ms; on a Pixel 7a, 238 to 184 ms and 140 to 145 ms (within noise). When the 3D
 * screen is not the first one, start it after the first frame is drawn instead.
 *
 * A prepared context nobody takes within 5 seconds is released; the libraries and the tables stay,
 * so a later 3D screen still saves most of the work. A [rememberEngine] given its own
 * `eglContextCreator` never takes the prepared context, so for such an app a warm-up only builds
 * the tables.
 *
 * What stays on the main thread, because Filament ties an `Engine` to the thread that creates it
 * (its job system adopts that thread, and `Engine.destroy()` must run there too): building the
 * [com.google.android.filament.Engine] itself. Only the work above moves.
 *
 * An app that creates its own gltfio `ResourceLoader`, rather than through a `ModelLoader`, should
 * not do it while a warm-up may still be building the tables (on a Pixel 7a, the first ~150 ms
 * after [start]): Basis builds them without locking, and only SceneView's own loaders wait for that
 * build.
 */
object EngineWarmUp {

    private const val TAG = "EngineWarmUp"

    /**
     * How long [createEglContext] waits for a warm-up still under way before creating its own
     * context. Short on purpose: past it, the caller redoes the work, so this caps what a slow
     * device can lose to the wait.
     */
    private const val HANDOFF_TIMEOUT_MILLIS = 300L

    /** How long a prepared context waits for an engine before it is released. */
    private const val UNCLAIMED_RELEASE_MILLIS = 5_000L

    private val slot = WarmUpSlot<OpenGL.PbufferContext>(
        discard = { it.destroy() },
        onFailure = { Log.w(TAG, "Engine warm-up failed; rememberEngine() will do the work", it) },
        expireAfterMillis = UNCLAIMED_RELEASE_MILLIS,
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
 * [ResourceLoader] SceneView creates goes through [creating], which never overlaps that build. A
 * `ResourceLoader` an app creates itself does not: [EngineWarmUp] says so.
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
 * Starts [EngineWarmUp] at app startup, for an app that lists it under `androidx.startup`'s
 * `InitializationProvider` in its own manifest. SceneView does not register it: see [EngineWarmUp].
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

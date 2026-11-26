package io.github.sceneview

import android.opengl.EGLContext
import android.os.Looper
import androidx.compose.runtime.RememberObserver
import com.google.android.filament.Engine
import io.github.sceneview.utils.OpenGL
import io.github.sceneview.utils.destroy

// Stable identities distinguish omitted factories from an explicit opt-out without changing the
// published rememberEngine signature. The lambdas do no native work until the first acquisition.
internal val defaultEglContextCreator: () -> EGLContext = { createEglContext() }
internal val defaultEngineCreator: (EGLContext) -> Engine = { createEngine(it) }

internal val defaultEnginePool = EnginePool(
    defaultEglContextCreator,
    defaultEngineCreator,
    destroyOwned = { engine, context -> engine.destroyWhenBackendIdle { context.destroy() } },
    destroyContext = { it.destroy() },
    checkThread = {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Filament engines must be acquired and released on the main thread"
        }
    },
    // ARCameraStream creates external GL textures on Main. A custom engine may have made a
    // different share group current since the last default scene, so restore ours on acquisition.
    onSharedAcquired = { OpenGL.makeEglContextCurrent(it) },
)

/**
 * Main-thread-only ownership behind rememberEngine, independent of JNI for lifecycle tests.
 * The default engine and its backing context live until process exit, including at zero users:
 * replacing them after each screen exhausts Adreno KGSL draw contexts (#5202).
 */
internal class EnginePool<C : Any, E : Any>(
    private val defaultContextCreator: () -> C,
    private val defaultEngineCreator: (C) -> E,
    private val destroyOwned: (E, C) -> Unit,
    private val destroyContext: (C) -> Unit,
    private val checkThread: () -> Unit = {},
    private val onSharedAcquired: (C) -> Unit = {},
) {
    private var shared: Pair<C, E>? = null
    internal var referenceCount: Int = 0
        private set

    fun acquire(
        contextCreator: () -> C = defaultContextCreator,
        engineCreator: (C) -> E = defaultEngineCreator,
    ): EngineLease<E> {
        checkThread()
        val isShared = contextCreator === defaultContextCreator &&
            engineCreator === defaultEngineCreator
        val (context, engine) = if (isShared) {
            acquireShared(contextCreator, engineCreator)
        } else {
            create(contextCreator, engineCreator)
        }
        if (isShared) referenceCount++
        return EngineLease(engine) {
            checkThread()
            if (isShared) {
                check(referenceCount > 0)
                referenceCount--
                // Intentionally retain both handles, even when the last scene leaves.
            } else {
                // Keep the owned-engine backend-idle teardown, including its EGL release.
                destroyOwned(engine, context)
            }
        }
    }

    /**
     * The retained pair, made current again. If the system invalidated its context (a GPU reset)
     * and no scene still uses it, the pair is dropped and a new one created: otherwise every later
     * screen would retry the dead context until the process restarts. With scenes still on it,
     * they own the failure and it propagates.
     */
    private fun acquireShared(contextCreator: () -> C, engineCreator: (C) -> E): Pair<C, E> {
        shared?.let { retained ->
            try {
                onSharedAcquired(retained.first)
                return retained
            } catch (lost: RuntimeException) {
                if (referenceCount > 0) throw lost
                // Dropped, not destroyed: tearing down an engine whose backend context is gone
                // can abort natively, and one stale pair after a GPU reset is a bounded cost.
                shared = null
            }
        }
        val created = create(contextCreator, engineCreator)
        shared = created
        onSharedAcquired(created.first)
        return created
    }

    private fun create(contextCreator: () -> C, engineCreator: (C) -> E): Pair<C, E> {
        val context = contextCreator()
        return try {
            context to engineCreator(context)
        } catch (failure: Throwable) {
            destroyContext(context)
            throw failure
        }
    }
}

/** Also releases an acquisition if Compose abandons the remember before applying its effects. */
internal class EngineLease<E : Any>(val engine: E, private val releaseEngine: () -> Unit) :
    RememberObserver {
    private var released = false

    fun release() {
        if (released) return
        releaseEngine()
        released = true
    }

    override fun onRemembered() = Unit
    override fun onForgotten() = release()
    override fun onAbandoned() = release()
}

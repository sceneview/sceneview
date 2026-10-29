package io.github.sceneview.material

import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Stream
import io.github.sceneview.drainFramePipeline
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.safeDestroyStream
import io.github.sceneview.safeDestroyTexture
import io.github.sceneview.texture.VideoTexture
import io.github.sceneview.whenBackendIdle

class VideoMaterial(
    val engine: Engine,
    private val materialLoader: MaterialLoader,
    chromaKeyColor: Int? = null
) {
    /**
     * Images drawn to the Surface will be made available to the Filament Stream.
     */
    val surfaceTexture = SurfaceTexture(0).apply {
        detachFromGLContext()
    }

    /**
     * The Android surface.
     */
    val surface = Surface(surfaceTexture)

    /**
     * The Filament Stream.
     */
    val stream = Stream.Builder()
        .stream(surfaceTexture)
        .build(engine)

    /**
     * The Filament Texture diffusing the stream.
     */
    val texture = VideoTexture.Builder()
        .stream(stream)
        .build(engine)

    val instance: MaterialInstance = materialLoader.createVideoInstance(texture, chromaKeyColor)

    init {
        instance.setExternalTexture(texture)
    }

    private val teardown = VideoMaterialTeardown(
        releaseSurface = { surface.release() },
        destroyInstance = { materialLoader.destroyMaterialInstance(instance) },
        whenInstanceReclaimed = { onReclaimed, onEngineGone ->
            if (Looper.myLooper() == null) {
                engine.drainFramePipeline()
                onReclaimed()
            } else {
                engine.whenBackendIdle(onEngineGone = onEngineGone) { onReclaimed() }
            }
        },
        destroyTexture = { engine.safeDestroyTexture(texture) },
        destroyStream = { engine.safeDestroyStream(stream) },
        releaseSurfaceTexture = { surfaceTexture.release() },
    )

    /**
     * Destroys [instance], then the [texture], the [stream] and the surfaces. Only the first call
     * does anything.
     *
     * Filament commits every material instance of the engine on every frame, whether or not a
     * renderable uses it, so an [instance] left alive once its [texture] is gone aborts the process
     * ("Invalid texture still bound to MaterialInstance"). The [instance] is destroyed first, and
     * the [texture] and the [stream] only once the backend has executed that destroy: polled on
     * the calling thread's looper every 16 ms, giving up after 5 s (at once, after a blocking
     * drain, on a thread without a [Looper]).
     *
     * Remove [instance] from every renderable that uses it, or destroy those renderables, before
     * calling this: a renderable keeps drawing with the instance it was given and would read it
     * after it is freed.
     *
     * Must be called on the main thread.
     */
    fun destroy() = teardown.destroy()
}

/**
 * The teardown order of a [VideoMaterial], with each Filament or Android release passed in as a
 * single call so that a JVM test can pin the order [VideoMaterial.destroy] runs them in.
 *
 * [whenInstanceReclaimed] runs its first callback once the backend has executed the instance
 * destroy, or its second instead when the engine is already gone (it freed the texture and the
 * stream with it).
 */
internal class VideoMaterialTeardown(
    private val releaseSurface: () -> Unit,
    private val destroyInstance: () -> Unit,
    private val whenInstanceReclaimed: (onReclaimed: () -> Unit, onEngineGone: () -> Unit) -> Unit,
    private val destroyTexture: () -> Unit,
    private val destroyStream: () -> Unit,
    private val releaseSurfaceTexture: () -> Unit,
) {
    private var isDestroyed = false

    fun destroy() {
        // Once only (#4259): a second call would free the texture and the stream again.
        if (isDestroyed) return
        isDestroyed = true
        releaseSurface()
        destroyInstance()
        whenInstanceReclaimed(
            {
                destroyTexture()
                destroyStream()
                releaseSurfaceTexture()
            },
            releaseSurfaceTexture,
        )
    }
}

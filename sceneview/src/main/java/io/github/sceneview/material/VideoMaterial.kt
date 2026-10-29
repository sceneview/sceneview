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

    /**
     * Destroys [instance], then the [texture], the [stream] and the surfaces.
     *
     * Filament commits every material instance of the engine on every frame, whether or not a
     * renderable uses it, so an [instance] left alive once its [texture] is gone aborts the process
     * ("Invalid texture still bound to MaterialInstance"). The [instance] is destroyed first, and
     * the [texture] and the [stream] only once the backend has executed that destroy, at a later
     * frame (at once, after a blocking drain, on a thread without a [Looper]).
     *
     * Remove [instance] from every renderable that uses it, or destroy those renderables, before
     * calling this: a renderable keeps drawing with the instance it was given and would read it
     * after it is freed.
     *
     * Must be called on the main thread.
     */
    fun destroy() {
        surface.release()
        releaseVideoMaterial(
            destroyInstance = { materialLoader.destroyMaterialInstance(instance) },
            whenInstanceReclaimed = { releaseVideo ->
                if (Looper.myLooper() == null) {
                    engine.drainFramePipeline()
                    releaseVideo()
                } else {
                    // Engine already gone: it freed the texture and the stream with it.
                    engine.whenBackendIdle(onEngineGone = { surfaceTexture.release() }) {
                        releaseVideo()
                    }
                }
            },
            releaseVideo = {
                engine.safeDestroyTexture(texture)
                engine.safeDestroyStream(stream)
                surfaceTexture.release()
            },
        )
    }
}

/**
 * The teardown order of [VideoMaterial.destroy], apart from its Filament calls so that a JVM test
 * can pin it: [destroyInstance] first, then [releaseVideo] (texture, stream, `SurfaceTexture`) once
 * [whenInstanceReclaimed] reports that the backend has executed that destroy.
 */
internal fun releaseVideoMaterial(
    destroyInstance: () -> Unit,
    whenInstanceReclaimed: (releaseVideo: () -> Unit) -> Unit,
    releaseVideo: () -> Unit,
) {
    destroyInstance()
    whenInstanceReclaimed(releaseVideo)
}

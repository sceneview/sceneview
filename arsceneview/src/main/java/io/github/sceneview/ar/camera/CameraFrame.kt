package io.github.sceneview.ar.camera

/** ARCore can return a frame with timestamp zero before the camera has produced an image. */
internal fun <T> cameraTextureForFrame(
    timestamp: Long,
    textureName: Int,
    textures: Map<Int, T>,
): T? = if (timestamp == 0L) null else textures[textureName]

/**
 * Presented frames the startup cover waits for: the first one carrying a camera image, then
 * [CAMERA_COVER_SETTLE_FRAMES] more. A TextureView composites the GL frame one or two vsyncs
 * after Filament submits it, so dropping the cover on the first submit could show the opaque
 * black ground in between (grey → black → camera).
 */
internal const val CAMERA_COVER_SETTLE_FRAMES = 2
internal const val CAMERA_COVER_REVEAL_FRAMES = 1 + CAMERA_COVER_SETTLE_FRAMES

/**
 * Counts submitted render passes that contained a camera image, saturating at
 * [CAMERA_COVER_REVEAL_FRAMES] so the caller's state stops changing once the cover is gone.
 * A refused frame (`submitted = false`) or a frame before the first image counts nothing.
 */
internal fun presentedCameraFrames(
    count: Int,
    hasCameraImage: Boolean,
    submitted: Boolean,
): Int = if (count < CAMERA_COVER_REVEAL_FRAMES && hasCameraImage && submitted) count + 1 else count

/** Whether the startup cover still hides the viewport after [count] presented camera frames. */
internal fun cameraCoverVisible(count: Int): Boolean = count < CAMERA_COVER_REVEAL_FRAMES

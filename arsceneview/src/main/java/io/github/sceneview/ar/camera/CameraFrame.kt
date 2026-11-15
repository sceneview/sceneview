package io.github.sceneview.ar.camera

/** ARCore can return a frame with timestamp zero before the camera has produced an image. */
internal fun <T> cameraTextureForFrame(
    timestamp: Long,
    textureName: Int,
    textures: Map<Int, T>,
): T? = if (timestamp == 0L) null else textures[textureName]

/** Keep the startup cover until a camera image has reached a submitted render pass. */
internal fun cameraFramePresented(
    alreadyPresented: Boolean,
    hasCameraImage: Boolean,
    submitted: Boolean,
): Boolean = alreadyPresented || (hasCameraImage && submitted)

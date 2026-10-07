package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Size

/**
 * The largest picture of [videoWidth] × [videoHeight] pixels that fits inside [screen] without
 * being stretched: bars on the sides or above and below, never a squeezed frame. Dimensions a
 * player has not reported yet (zero) keep the whole screen.
 */
internal fun fitInside(screen: Size, videoWidth: Int, videoHeight: Int): Size {
    if (videoWidth <= 0 || videoHeight <= 0) return screen
    if (screen.x <= 0f || screen.y <= 0f) return screen
    val video = videoWidth.toFloat() / videoHeight
    val frame = screen.x / screen.y
    return if (video >= frame) Size(screen.x, screen.x / video, screen.z)
    else Size(screen.y * video, screen.y, screen.z)
}

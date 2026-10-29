package io.github.sceneview.demo.demos.internal

/**
 * What a video's Play / Pause control shows. [playing] picks the glyph, the label and the
 * selected state; [enabled] says whether a tap can change it.
 */
internal data class VideoTransport(val playing: Boolean, val enabled: Boolean)

/**
 * The control reports what the video is doing, not what was asked of it: [requested] stays true
 * when the player could not be prepared or stopped on an error, and a control still reading
 * "Pause", selected, would claim a playback that is not happening.
 *
 * QA holds the video paused on one frame, so the control is locked there unless that seek fell
 * back to playback ([seekFallback]).
 */
internal fun videoTransport(
    requested: Boolean,
    failed: Boolean,
    qa: Boolean,
    seekFallback: Boolean,
): VideoTransport = VideoTransport(
    playing = requested && !failed,
    enabled = !failed && (!qa || seekFallback),
)

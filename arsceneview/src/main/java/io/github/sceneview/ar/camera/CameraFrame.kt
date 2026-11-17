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

/**
 * Whether the camera image bound at [boundTimestamp] may lift the cover: there is one, and it is
 * not the [staleTimestamp] image the texture held when the cover was reset (its surface was
 * destroyed — a stop, for a SurfaceView).
 */
internal fun freshCameraImage(boundTimestamp: Long, staleTimestamp: Long): Boolean =
    boundTimestamp != 0L && boundTimestamp != staleTimestamp

/**
 * How long a reset cover waits for a camera image newer than the stale one before it shows the
 * stale one anyway: 2 s from the first frame presented to the new surface. A live camera
 * delivers a new image within that after a return from the background (≈0.3–0.9 s on a
 * Pixel 4a, so 1 s would leave too thin a margin on a slow device); a source that never will —
 * a finished `playbackDataset` keeps returning its last frame, a stalled camera — would
 * otherwise keep the viewport covered forever.
 */
internal const val STALE_CAMERA_COVER_TIMEOUT_NANOS = 2_000_000_000L

/**
 * Whether a cover reset over a stale camera image may lift without a newer one.
 *
 * Only while the texture still holds that stale image ([boundTimestamp] == [staleTimestamp],
 * non-zero) — a view that never had a camera image keeps its cover. And only once the frames
 * owed to the new surface have been presented ([framesOwed] == 0 and a first present recorded
 * at [firstPresentNanos], `0` = none yet), so the stale image is on screen beneath the cover.
 * Then: at once when playback has finished — no newer image can come — or after
 * [STALE_CAMERA_COVER_TIMEOUT_NANOS].
 */
internal fun staleCameraCoverExpired(
    boundTimestamp: Long,
    staleTimestamp: Long,
    framesOwed: Int,
    firstPresentNanos: Long,
    frameTimeNanos: Long,
    playbackFinished: () -> Boolean,
): Boolean = boundTimestamp != 0L && boundTimestamp == staleTimestamp &&
    framesOwed == 0 && firstPresentNanos != 0L &&
    (frameTimeNanos - firstPresentNanos >= STALE_CAMERA_COVER_TIMEOUT_NANOS || playbackFinished())

/** Whether the startup cover still hides the viewport after [count] presented camera frames. */
internal fun cameraCoverVisible(count: Int): Boolean = count < CAMERA_COVER_REVEAL_FRAMES

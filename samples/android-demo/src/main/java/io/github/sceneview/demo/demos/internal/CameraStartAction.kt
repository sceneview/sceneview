package io.github.sceneview.demo.demos.internal

/**
 * What an AR demo does about a camera that has not delivered its first frame yet (#4069).
 *
 * ARCore can fail to start the camera without reporting it: `ARSceneView` logs and drops
 * any exception thrown by `session.update()`, and a camera that is still held by the
 * previous screen's session simply never produces a frame. The demo then showed
 * "Starting camera" forever. The watchdog gives the session a bounded time, rebuilds it a
 * bounded number of times, then hands over to the failure card so the user is never left
 * with a spinner that is lying.
 */
enum class CameraStartAction {
    /** Nothing to watch: a frame arrived, or AR is already known not to work here. */
    IDLE,

    /** Still inside the start-up window. Keep waiting. */
    WAIT,

    /** The window is spent. Tear the session down and open a fresh one. */
    RESTART_SESSION,

    /** Every restart is spent too. Show the failure card. */
    GIVE_UP,
}

/**
 * Time a session gets to deliver its first camera frame. A healthy cold start takes about
 * 1–3 s, so this only fires on a stuck start.
 */
const val CAMERA_START_TIMEOUT_MS = 6_000L

/** Fresh sessions tried after the first one before giving up. */
const val CAMERA_START_MAX_RESTARTS = 2

/**
 * The watchdog's decision for one tick.
 *
 * @param waitedMs time the current session has been waiting for a frame, counted only
 *   while the screen is resumed and the camera permission is granted, so a permission
 *   dialog or an ARCore install prompt never counts against it.
 * @param frameReceived true once the current session delivered a camera frame.
 * @param restartsDone sessions already rebuilt by the watchdog on this screen.
 * @param arBlocked true when AR is known not to work: ARCore unavailable on the device, or
 *   the session failed to create. Those states have their own explanation on screen.
 */
fun cameraStartAction(
    waitedMs: Long,
    frameReceived: Boolean,
    restartsDone: Int,
    arBlocked: Boolean,
    timeoutMs: Long = CAMERA_START_TIMEOUT_MS,
    maxRestarts: Int = CAMERA_START_MAX_RESTARTS,
): CameraStartAction = when {
    frameReceived || arBlocked -> CameraStartAction.IDLE
    waitedMs < timeoutMs -> CameraStartAction.WAIT
    restartsDone < maxRestarts -> CameraStartAction.RESTART_SESSION
    else -> CameraStartAction.GIVE_UP
}

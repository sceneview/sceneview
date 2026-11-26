package io.github.sceneview.demo.demos.internal

/**
 * Keeps an answer on screen through a tracking loss, one camera frame at a time.
 *
 * - Camera lost: [Action.Hold] once (the screen freezes the answer in front of the phone).
 * - Anchor tracked again: [Action.Release] once if it was held (the anchor takes it back).
 * - Camera tracked but the anchor still not, for [patienceFrames] frames: [Action.Reanchor]
 *   (ARCore sometimes resumes on a fresh map and leaves the old anchor paused for good, so
 *   the screen puts a new anchor where the old one last was). The answer stays held until
 *   the new anchor is tracked.
 *
 * Pure state: the screen owns the poses and the anchors.
 */
internal class AskAnchorWatch(private val patienceFrames: Int = REANCHOR_PATIENCE_FRAMES) {
    enum class Action { None, Hold, Release, Reanchor }

    var holding = false
        private set
    private var lostFrames = 0

    fun reset() {
        holding = false
        lostFrames = 0
    }

    fun step(cameraTracking: Boolean, anchorTracking: Boolean): Action {
        if (anchorTracking) {
            lostFrames = 0
            if (!holding) return Action.None
            holding = false
            return Action.Release
        }
        if (!cameraTracking) {
            lostFrames = 0
            if (holding) return Action.None
            holding = true
            return Action.Hold
        }
        if (++lostFrames < patienceFrames) return Action.None
        lostFrames = 0
        return Action.Reanchor
    }

    companion object {
        /**
         * About a second at 30 fps: enough for ARCore to pick an anchor back up, and for a
         * fresh map to settle before a new anchor goes on it.
         */
        const val REANCHOR_PATIENCE_FRAMES = 30
    }
}

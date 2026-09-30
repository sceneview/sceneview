package io.github.sceneview.demo.demos.internal

/**
 * Where the voyage is, for the render loop and the gestures (both on the main thread): whether it
 * drives the camera, how far a jump away from the free camera has gone, how long since the user
 * last touched the scene, their drag, and the fade and streaks on screen.
 *
 * Three ways to take the camera, three ways back:
 * - a touch or a drag in the scene ([takeOver]): the voyage waits for [IDLE_RESUME_SECONDS] of
 *   calm, then jumps on to the next scene;
 * - a dock tab, Stop or the Voyage toggle ([stop]): the voyage stays stopped until it is started
 *   again ([resumeNow]);
 * - Animate or reduced motion off ([hold]): the voyage pauses, and once they are back it resumes
 *   after the same calm as a touch — unless it had been stopped.
 *
 * Allocation-free per frame.
 */
internal class VoyageState(var playing: Boolean) {
    /** Whether the shot on screen was reached through a jump: its first seconds finish it. */
    var arrivedByWarp = false

    /** Progress of a jump away from the free camera, 0 → 1; negative when none is under way. */
    var leaving = -1f

    /** Seconds since the user last touched the scene; [STOPPED] while nothing is to be waited out. */
    var idleSeconds = STOPPED
        private set

    var yaw = 0f
        private set
    var pitch = 0f
        private set

    var fade = 1f
        private set
    var streaks = 0f
        private set

    /** Drives the streaks' pulses: runs through a jump whatever the scene clock does. */
    var warpClock = 0f
        private set

    /** The focal length last written to the camera, so an unchanged one is not written again. */
    var appliedFocal = CosmosVoyageCamera.DEFAULT_FOCAL

    private var lastNanos = 0L

    // Frame pacing over the shot on screen, logged when it ends: the voyage's smoothness figure.
    private var shotFrames = 0
    private var shotSlowFrames = 0
    private var shotSeconds = 0f

    /**
     * Advances the frame clock; returns this frame's step in seconds, a hitch clamped. Only frames
     * with [counted] set go into the pacing figure: those under the loading cover would drag it down.
     */
    fun step(nanos: Long, counted: Boolean = true): Float {
        val raw = if (lastNanos == 0L) 0f else (nanos - lastNanos) / 1e9f
        val dt = raw.coerceIn(0f, MAX_STEP_SECONDS)
        lastNanos = nanos
        if (playing && counted && raw > 0f) {
            shotFrames++
            shotSeconds += raw
            if (raw > SLOW_FRAME_SECONDS) shotSlowFrames++
        }
        if (idleSeconds >= 0f) idleSeconds += dt
        warpClock += dt
        return dt
    }

    /** A touch or a drag in the scene: the camera is handed back, and the voyage waits for calm. */
    fun takeOver(flight: CosmosFlight) {
        handBack(flight)
        idleSeconds = 0f
    }

    /** A dock tab, Stop, the toggle off: the camera is handed back until the voyage is started again. */
    fun stop(flight: CosmosFlight) {
        handBack(flight)
        idleSeconds = STOPPED
    }

    /**
     * Animate or reduced motion is off, called every frame while it is: the voyage lets go of the
     * camera and the calm is counted from when they come back. A stopped voyage stays stopped.
     */
    fun hold(flight: CosmosFlight) {
        val driving = playing || leaving >= 0f
        handBack(flight)
        if (driving || idleSeconds >= 0f) idleSeconds = 0f
    }

    /** Jumps on to the next scene now: Start, the toggle, or the end of the calm. */
    fun resumeNow() {
        if (playing || leaving >= 0f) return
        idleSeconds = STOPPED
        leaving = 0f
    }

    fun dueToResume(): Boolean = !playing && leaving < 0f && idleSeconds > IDLE_RESUME_SECONDS

    /** The jump away has landed: the voyage has the camera, in the next scene. */
    fun arrive() {
        playing = true
        leaving = -1f
        arrivedByWarp = true
        resetDrag()
    }

    fun drag(scene: CosmosScene, dYaw: Float, dPitch: Float) {
        // The flow field has an edge a turned camera would show: it stays on its framing.
        if (scene == CosmosScene.Flow) return
        yaw += dYaw
        pitch = (pitch + dPitch).coerceIn(-MAX_PITCH_DEGREES, MAX_PITCH_DEGREES)
    }

    fun resetDrag() {
        yaw = 0f
        pitch = 0f
    }

    fun show(fade: Float, streaks: Float) {
        this.fade = fade
        this.streaks = streaks
    }

    /** The free camera: a fade or a jump the user cut short comes back up, the streaks go out. */
    fun settle(dt: Float) {
        val k = (dt / FADE_BACK_SECONDS).coerceIn(0f, 1f)
        fade += (1f - fade) * k
        streaks -= streaks * k
        if (streaks < STREAKS_OFF) streaks = 0f
    }

    /** The frame pacing of the shot that just ended, then a fresh count for the next one. */
    fun shotPacing(scene: CosmosScene): String {
        val fps = if (shotSeconds > 0f) shotFrames / shotSeconds else 0f
        val report = "voyage shot ${scene.name}: $shotFrames frames, ${"%.1f".format(fps)} fps, " +
            "$shotSlowFrames over ${(SLOW_FRAME_SECONDS * MILLIS).toInt()} ms"
        shotFrames = 0
        shotSlowFrames = 0
        shotSeconds = 0f
        return report
    }

    private fun handBack(flight: CosmosFlight) {
        if (playing || leaving >= 0f) flight.start()
        playing = false
        leaving = -1f
    }

    companion object {
        /** Seconds without a touch before the voyage jumps on to the next scene. */
        const val IDLE_RESUME_SECONDS = 12f

        /** How far the free camera may be dragged above or below its framing, in degrees. */
        const val MAX_PITCH_DEGREES = 60f

        /** How long the scene takes to come back up when the user cuts a fade or a jump short, in seconds. */
        const val FADE_BACK_SECONDS = 0.35f

        /** [idleSeconds] when the voyage is not waiting for calm: it is playing, or stopped. */
        const val STOPPED = -1f

        private const val MAX_STEP_SECONDS = 0.1f
        private const val STREAKS_OFF = 0.01f
        private const val SLOW_FRAME_SECONDS = 0.025f
        private const val MILLIS = 1000
    }
}

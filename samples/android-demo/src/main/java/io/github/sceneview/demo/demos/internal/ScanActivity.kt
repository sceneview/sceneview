package io.github.sceneview.demo.demos.internal

/**
 * What a capture is doing, said in one word at the top of the screen (#4540 live walls): a scan
 * that only shows points gave no sign of whether it was working.
 *
 * - [Working] — it captures, and the room grows; moving too fast or standing still is still
 *   working — the coaching line says to slow down or to move, the scan itself is fine.
 * - [Paused] — it captures nothing: the user paused it, or ARCore lost track of the room.
 * - [Stuck] — it runs but adds nothing whatever the phone does: no usable depth, too little
 *   texture, or the phone moves yet nothing new comes. Never once the room is [done][of]; rising
 *   points lift only "nothing new", so the top line says stuck exactly when the coaching line
 *   says why.
 */
enum class ScanActivity {
    Working,
    Paused,
    Stuck;

    companion object {
        /**
         * [depthStarved]: a depth scan that gets no usable depth adds nothing whatever the phone
         * does, so it is [Stuck] — and the coaching line says why ([ScanCopy.COACH_NO_DEPTH]).
         * [done]: the room is complete, and a scan with nothing left to add is not stuck.
         * [pointsRising]: a scan whose points keep rising adds to the room, whatever the floor's
         * coverage says, so "nothing new" does not make it stuck ([PointsRise]); no depth or too
         * little texture still does, as the coaching line still says ([ScanCopy.coaching]).
         */
        @Suppress("LongParameterList") // one flag per cause the status can speak of
        fun of(
            phase: ScanPhase,
            trackingLost: Boolean,
            coach: ScanCoach.Line?,
            lowTexture: Boolean = false,
            depthStarved: Boolean = false,
            done: Boolean = false,
            pointsRising: Boolean = false,
        ): ScanActivity =
            when {
                !phase.integrates || trackingLost -> Paused
                done -> Working
                depthStarved || lowTexture -> Stuck
                coach == ScanCoach.Line.NothingNew && !pointsRising -> Stuck
                else -> Working
            }
    }
}

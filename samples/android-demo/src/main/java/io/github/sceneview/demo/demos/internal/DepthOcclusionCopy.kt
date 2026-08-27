package io.github.sceneview.demo.demos.internal

/**
 * The words `ARDepthOcclusionDemo` puts on screen, as pure functions of its state (#3340).
 *
 * The demo used to label its state pill `DEPTH ON` / `DEPTH OFF` and hide both the
 * explanation and the toggle inside the Settings sheet. The reported symptom — "on voit
 * pas trop ce que ça doit faire" — is what that produces: the one screen the user looks
 * at names an internal ARCore setting, never says what the setting *does*, and offers no
 * way to flip it without first discovering a sheet.
 *
 * So the pill now carries two lines: the state, and the **consequence** of that state.
 * That is the whole trick — occlusion is only legible as a contrast between two frames,
 * and a still frame can only carry that contrast in words. A user reading
 * "Occlusion ON · real objects hide the model" understands the demo in one glance, and a
 * screenshot of either state is self-describing in a bug report.
 *
 * Extracted from the composable so every string and every visibility rule is pinned by a
 * plain JVM test — the demo itself needs ARCore and a camera, so nothing about it can be
 * asserted on an emulator. See `DepthOcclusionCopyTest`.
 */
internal object DepthOcclusionCopy {

    /** Title line of the state pill. Short enough to read without stopping. */
    fun stateTitle(occlusionOn: Boolean): String =
        if (occlusionOn) "Occlusion ON" else "Occlusion OFF"

    /**
     * Second line of the state pill: what the user should therefore *see*.
     *
     * Phrased as an observable outcome, never as a mechanism — "real objects hide the
     * model", not "the camera quad writes gl_FragDepth".
     */
    fun stateConsequence(occlusionOn: Boolean): String =
        if (occlusionOn) {
            "Real objects in front of the model hide it"
        } else {
            "The model draws over everything, however close"
        }

    /**
     * Label of the on-screen toggle button. Names the action, not the state — the pill
     * above it already carries the state, and a button that reads the same as the pill
     * leaves the user unsure whether it reports or acts.
     */
    fun toggleAction(occlusionOn: Boolean): String =
        if (occlusionOn) "Turn occlusion off" else "Turn occlusion on"

    /**
     * The one coaching line under the pill, or `null` when another overlay already owns
     * the screen.
     *
     * - Not tracking: `null`. The scaffold's tracking / scanning banner is louder and
     *   says something more urgent ("move the device", "add light"); stacking a second
     *   sentence on it is noise.
     * - Tracking, nothing placed: say the one gesture that starts the demo.
     * - Placed: say the one gesture that *reveals* the effect, and say what each state
     *   should look like when they do it. This is the before/after, in words.
     */
    fun coachingHint(
        isTracking: Boolean,
        hasPlacedModel: Boolean,
        occlusionOn: Boolean,
    ): String? = when {
        !isTracking -> null
        !hasPlacedModel -> "Tap a flat surface to place the helmet"
        occlusionOn -> "Pass your hand in front of the helmet — it gets cut away"
        else -> "Pass your hand in front — nothing is cut away. Turn occlusion on to compare"
    }

    /**
     * Shown in place of the toggle when `Session.isDepthModeSupported` came back `false`.
     * Kept as one sentence that both states the limit and names the cause.
     */
    const val UNSUPPORTED =
        "This device has no ARCore Depth API, so occlusion can't be demonstrated here"
}

package io.github.sceneview.demo.demos.internal

import java.util.Locale

/*
 * On-screen copy of the Rerun Debug demo (#3831), kept pure so a JVM test can hold it to
 * the rule the QA walkthrough broke: someone who opens the demo from the Play Store, with
 * no computer attached, must read what the demo does — not an error about a service they
 * never heard of. Connection steps live in the settings sheet only.
 */

/** The one sentence that says what the demo does, shown on screen and atop the sheet. */
const val RERUN_INTRO: String =
    "Sends what the camera sees to Rerun on your computer, so you can scrub through it frame by frame."

/** The status line over the camera: a dot, a [title] and a quieter [detail] line. */
data class RerunStatusUx(
    val title: String,
    val detail: String,
    /** `true` while events actually reach the computer — drives the green dot. */
    val live: Boolean,
)

fun rerunStatusUx(isConnected: Boolean, eventsSent: Long, eventsPerSecond: Float): RerunStatusUx =
    if (isConnected) {
        val rate = eventsPerSecond.takeIf { it.isFinite() && it > 0f }?.let {
            " · ${String.format(Locale.US, "%.0f", it)} per second"
        }.orEmpty()
        RerunStatusUx(
            title = "Streaming to your computer",
            detail = "${String.format(Locale.US, "%,d", eventsSent)} " +
                (if (eventsSent == 1L) "event" else "events") + " sent$rate",
            live = true,
        )
    } else {
        RerunStatusUx(
            title = "No computer connected",
            detail = RERUN_INTRO,
            live = false,
        )
    }

/** Heading of the settings-sheet section that holds [RERUN_SETUP_STEPS]. */
const val RERUN_SETUP_TITLE: String = "Connect your computer"

/** One numbered step of "Connect your computer"; [command] is shown in a mono block. */
data class RerunSetupStep(val text: String, val command: String? = null)

val RERUN_SETUP_STEPS: List<RerunSetupStep> = listOf(
    RerunSetupStep("Plug the phone into your computer with a USB cable."),
    RerunSetupStep("Forward the connection to the computer:", "adb reverse tcp:9876 tcp:9876"),
    RerunSetupStep(
        "From a SceneView checkout, start the recorder:",
        "python3 samples/android-demo/tools/rerun-bridge.py --save recording.rrd",
    ),
    RerunSetupStep("Come back here. The status turns green and Save appears."),
)

/**
 * Save is offered only when it can work — while connected, or while a save is already in
 * flight so its progress label stays visible (#2658).
 */
fun rerunShowsSaveAction(isConnected: Boolean, sharing: Boolean): Boolean = isConnected || sharing

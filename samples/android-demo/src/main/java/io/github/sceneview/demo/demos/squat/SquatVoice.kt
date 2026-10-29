package io.github.sceneview.demo.demos.squat

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import java.text.NumberFormat

/** What the coach says out loud after a pose sample, if anything. */
sealed interface SquatAnnouncement {
    /** A rep was just counted: say its number. */
    data class Rep(val count: Int) : SquatAnnouncement

    /** The user stood up without reaching depth: a short "go lower" cue. */
    data object TooShallow : SquatAnnouncement
}

/**
 * The spoken cue for going from [previous] to [current], or `null` when nothing changed
 * worth saying. Each event is said once: a rep the moment the count goes up, the shallow
 * cue the moment the feedback turns to [SquatFeedback.TOO_SHALLOW] — never again on the
 * samples that follow, and never for a Reset (the count going down).
 */
fun squatAnnouncement(previous: SquatCoachState, current: SquatCoachState): SquatAnnouncement? = when {
    current.reps > previous.reps -> SquatAnnouncement.Rep(current.reps)
    current.feedback == SquatFeedback.TOO_SHALLOW &&
        previous.feedback != SquatFeedback.TOO_SHALLOW -> SquatAnnouncement.TooShallow
    else -> null
}

/**
 * Says the rep count out loud ("one", "two", …) and a short cue when a squat is too shallow,
 * so the user squatting a couple of metres from the phone does not have to read the screen.
 *
 * Android [TextToSpeech] in the device language; numbers go through [NumberFormat] so the
 * engine reads them as words. Speech uses the navigation-guidance audio usage, which ducks
 * the user's music instead of pausing it, and flushes the queue: a fast set never makes the
 * voice lag a rep behind. Main thread only. [close] releases the engine.
 *
 * @param shallowCue What to say for [SquatAnnouncement.TooShallow], already localised.
 */
class SquatVoice(context: Context, private val shallowCue: String) : AutoCloseable {

    /** Muted = nothing is said, and whatever was being said stops. */
    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) tts.stop()
        }

    private var ready = false
    private val numbers = NumberFormat.getIntegerInstance()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (!ready) Log.w(TAG, "TextToSpeech unavailable (status $status) — counting stays silent")
    }.apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    /** Speaks the cue for the [previous] → [current] transition, if there is one. */
    fun onCoach(previous: SquatCoachState, current: SquatCoachState) {
        val announcement = squatAnnouncement(previous, current) ?: return
        if (!enabled || !ready) return
        val text = when (announcement) {
            is SquatAnnouncement.Rep -> numbers.format(announcement.count)
            SquatAnnouncement.TooShallow -> shallowCue
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    override fun close() {
        tts.stop()
        tts.shutdown()
    }

    private companion object {
        const val TAG = "SquatVoice"
        const val UTTERANCE_ID = "squat-coach"
    }
}

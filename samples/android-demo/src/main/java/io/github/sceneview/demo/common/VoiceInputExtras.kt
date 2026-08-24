package io.github.sceneview.demo.common

import android.content.Intent
import android.speech.RecognizerIntent

/**
 * Lengthens the system speech recognizer's silence-detection windows so a normal pause
 * for breath or thought does not cut a longer dictation short (#3322).
 *
 * `ACTION_RECOGNIZE_SPEECH`'s stock defaults (~1–1.5 s of silence) are tuned for short
 * search-style utterances; a bug-report note or a free-form AR question is closer to a
 * spoken paragraph, and QA on a Pixel 9 found dictation cutting off mid-sentence well
 * before the speaker was done. These extras are honoured by the AOSP/Google reference
 * recognizer (best-effort on OEM recognizers that ignore them — never a regression,
 * since the fallback is just the stock shorter timeout):
 *  - [RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS] — silence after
 *    which the recognizer decides the utterance is finished.
 *  - [RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS] —
 *    silence after which it returns a possibly-complete partial result.
 *
 * Both call sites (`BugReportSheet`'s note dictation, `PointAndAskDemo`'s question
 * dictation) share this helper so the tuning stays in one place instead of drifting
 * between two copy-pasted literals.
 */
fun Intent.putVoiceSilenceExtras(): Intent = apply {
    putExtra(
        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
        VOICE_COMPLETE_SILENCE_MILLIS,
    )
    putExtra(
        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
        VOICE_POSSIBLY_COMPLETE_SILENCE_MILLIS,
    )
}

/** Silence length before the recognizer ends the utterance — see [putVoiceSilenceExtras]. */
private const val VOICE_COMPLETE_SILENCE_MILLIS = 4_000L

/**
 * Silence length before the recognizer emits a possibly-complete partial —
 * see [putVoiceSilenceExtras]. Shorter than [VOICE_COMPLETE_SILENCE_MILLIS] per the
 * extra's own contract (it fires first, as an interim result).
 */
private const val VOICE_POSSIBLY_COMPLETE_SILENCE_MILLIS = 3_000L

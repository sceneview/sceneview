package io.github.sceneview.demo.common

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.sceneview.demo.R

/**
 * A text field's voice dictation (#4618), shared by the bug-report note and Point & Ask's
 * question. No permission: it chains the system "Speak now" dialog through a
 * [DictationSession], so a pause closes one round and the next one opens straight away.
 */
@Stable
class VoiceDictation internal constructor(
    /** False when the device has no speech recognizer: hide the mic. */
    val available: Boolean,
) {
    /** True while rounds keep chaining: show the stop button and the listening hint. */
    var listening by mutableStateOf(false)
        internal set

    internal var onToggle: () -> Unit = {}

    /** Starts dictating, or stops chaining if already going. */
    fun toggle() = onToggle()
}

/**
 * @param prompt shown in the system dialog.
 * @param text the field's current value.
 * @param onTextChange receives the field's new value each time a round adds text.
 * @param append true to add what is heard after [text] as it was when the mic was tapped,
 * false to replace it.
 */
@Composable
fun rememberVoiceDictation(
    prompt: String,
    text: String,
    onTextChange: (String) -> Unit,
    append: Boolean = true,
): VoiceDictation {
    val context = LocalContext.current
    val latestText by rememberUpdatedState(text)
    val latestOnTextChange by rememberUpdatedState(onTextChange)
    val latestPrompt by rememberUpdatedState(prompt)
    val available = remember(context) {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null
    }
    val dictation = remember { VoiceDictation(available) }
    // What the field held when the mic was tapped; the transcript is joined after it.
    val base = remember { arrayOf("") }
    // A dialog round is open: leaving the app now is the dialog itself, not the user.
    val roundOpen = remember { booleanArrayOf(false) }
    val holder = remember { arrayOfNulls<DictationSession>(1) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        roundOpen[0] = false
        val heard = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        holder[0]?.onRoundResult(heard, canceled = result.resultCode != Activity.RESULT_OK)
    }

    val session = remember {
        DictationSession(
            launcher = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_PROMPT, latestPrompt)
                    putVoiceSilenceExtras()
                }
                runCatching { launcher.launch(intent) }.isSuccess.also { roundOpen[0] = it }
            },
            onUpdate = { state ->
                dictation.listening = state.listening
                if (state.transcript.isNotBlank()) {
                    latestOnTextChange(
                        if (append) appendDictation(base[0], state.transcript) else state.transcript,
                    )
                }
            },
        ).also { holder[0] = it }
    }

    // Leaving the app between two rounds ends the dictation. While a round is open, the
    // dialog is what covers this screen; its result comes back only once the app is in front
    // again, so a reopened round can never surface over another app.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !roundOpen[0]) session.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    dictation.onToggle = {
        if (session.listening) {
            session.stop()
        } else {
            base[0] = latestText
            session.start()
        }
    }
    return dictation
}

/** A text field's trailing mic: a mic while idle, a stop button while [VoiceDictation.listening]. */
@Composable
fun VoiceDictationButton(dictation: VoiceDictation, idleContentDescription: String) {
    IconButton(onClick = dictation::toggle) {
        if (dictation.listening) {
            Icon(
                Icons.Filled.Stop,
                contentDescription = stringResource(R.string.voice_dictation_stop_cd),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(Icons.Outlined.Mic, contentDescription = idleContentDescription)
        }
    }
}

/** A text field's supporting line while dictation chains — tells the user pauses are fine. */
@Composable
fun VoiceDictationListeningHint() {
    Text(
        text = stringResource(R.string.voice_dictation_listening),
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Joins dictated text after what the field held when the mic was tapped. */
fun appendDictation(base: String, transcript: String): String =
    if (base.isBlank()) transcript else "${base.trimEnd()} $transcript"

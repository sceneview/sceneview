package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.AskInArDemo

/**
 * Append-only fragment for the `ask-ar` demo. See [DemoFragment].
 *
 * A prototype: ask out loud, and the answer writes itself by hand in the room. Answers are
 * scripted on the phone. `sceneview://demo/ask-ar?q=<question>` plays a whole question and
 * answer by itself, for filming.
 */
object AskArFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ask-ar",
        sourcePath = "src/main/java/io/github/sceneview/demo/demos/AskInArDemo.kt",
        titleRes = R.string.demo_ask_ar_title,
        subtitleRes = R.string.demo_ask_ar_subtitle,
        category = DemoCategory.PLACE_AR,
        icon = Icons.Filled.RecordVoiceOver,
        order = 24,
        addedIn = "4.74.0",
        status = DemoStatus.InReview,
        tags = setOf(
            "ask", "question", "answer", "voice", "speech", "mic", "handwriting", "writing",
            "text", "tube", "anchor", "ar", "assistant",
        ),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        AskInArDemo(onBack)
    }
}

package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.ReplayTap
import io.github.sceneview.demo.demos.internal.ReplayTapAnswer
import io.github.sceneview.demo.demos.internal.semantic.nameRes
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.overMediaEdge

/** The test tag of the label a tap on a replayed scan puts on the furniture it boxed. */
internal const val REPLAY_TAP_LABEL_TAG = "scan-object-size"

/**
 * The label over a piece of furniture tapped in the replay of a scan (#4485): its name when scene
 * recognition gave it one, its box's length × depth × height in the phone's units — feet and
 * inches in US English, metres elsewhere ([ReplayTap.sizes]) —, and a quieter word when the scan
 * saw it from one side only and its box may be short of its back. A card of the stage's chrome,
 * read out once each time it changes.
 */
@Composable
internal fun ReplayTapLabel(answer: ReplayTapAnswer, modifier: Modifier = Modifier) {
    val (length, depth, height) = ReplayTap.sizes(answer.box, LocalLocale.current.platformLocale)
    val name = answer.label?.let { stringResource(it.nameRes()) }
    val note = if (answer.oneSided) stringResource(R.string.demo_replay_tap_one_side) else null
    val described = if (name != null) {
        stringResource(R.string.demo_replay_tap_named_description, name, length, depth, height)
    } else {
        stringResource(R.string.demo_replay_tap_description, length, depth, height)
    }
    val description = if (note != null) stringResource(R.string.demo_replay_tap_with_note, described, note) else described
    val chrome = LocalStageChrome.current
    val shape = RoundedCornerShape(SceneViewTokens.Radius.sm)
    Column(
        modifier = modifier
            .testTag(REPLAY_TAP_LABEL_TAG)
            .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
            .background(chrome.card, shape)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = description
            }
            .padding(horizontal = Space.md, vertical = Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val sizes = stringResource(R.string.demo_replay_tap_sizes, length, depth, height)
        if (name != null) {
            Text(name, style = SceneViewTokens.Type.card.copy(color = chrome.onCard))
            Text(sizes, style = SceneViewTokens.Type.caption.copy(color = chrome.onCard, fontFeatureSettings = "tnum"))
        } else {
            Text(sizes, style = SceneViewTokens.Type.card.copy(color = chrome.onCard, fontFeatureSettings = "tnum"))
        }
        Text(
            stringResource(R.string.demo_ar_rerun_object_size_caption),
            style = SceneViewTokens.Type.caption.copy(color = chrome.onCardMuted),
        )
        if (note != null) Text(note, style = SceneViewTokens.Type.caption.copy(color = chrome.onCardMuted))
    }
}

package io.github.sceneview.demo.demos

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.RoomFigures
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens.Type

/**
 * What a room's result says of the figures drawn on its model when they are not the room's
 * ([RoomFigures.Scanned]): that they span what was scanned, and what to film for the room's own.
 * On the card a cupboard's figures stand on ([SpaceMeasureCard]), in the same place.
 */
@Composable
internal fun RoomFiguresCard(modifier: Modifier = Modifier) {
    val chrome = LocalStageChrome.current
    OverlayCard(testTag = ROOM_FIGURES_CARD_TAG, modifier = modifier.semantics(mergeDescendants = true) { }) {
        Text(stringResource(R.string.scan_room_figures_scanned), style = Type.card, color = chrome.onCard)
        Text(stringResource(R.string.scan_room_figures_scanned_hint), style = Type.body, color = chrome.onCardMuted)
    }
}

internal const val ROOM_FIGURES_CARD_TAG = "rerun_room_figures_card"

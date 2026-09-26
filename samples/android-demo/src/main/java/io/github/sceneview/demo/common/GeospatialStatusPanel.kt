package io.github.sceneview.demo.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.ScreenRotationAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.DemoBottomOverlayScope
import io.github.sceneview.demo.demos.internal.GEOSPATIAL_ACCURACY_SEGMENTS
import io.github.sceneview.demo.demos.internal.GeospatialIndicator
import io.github.sceneview.demo.demos.internal.GeospatialPrimaryAction
import io.github.sceneview.demo.demos.internal.GeospatialStatusCard
import io.github.sceneview.demo.demos.internal.GeospatialTone
import io.github.sceneview.demo.demos.internal.message
import io.github.sceneview.demo.demos.internal.tone
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.md_theme_dark_onPrimary

/**
 * The Geospatial Anchors demo's one bottom surface once the camera is live
 * ([#3832](https://github.com/sceneview/sceneview/issues/3832)): where the user stands
 * with localization, what to do about it, what happened to the last drop, and the Drop
 * button — in that order, in one `DESIGN.md` AR Overlay Card.
 *
 * ## Why this replaced a pill and a small action bar
 *
 * Before #3832 the main view carried one coaching pill ("Ready — point at the ground and
 * tap Drop here") and a default-size `SceneActionBar` button; accuracy, visual
 * positioning coverage and the result of each drop lived only in the settings sheet. A
 * Geospatial session is a *continuous* job — localization improves as the user pans
 * across buildings, as in Google Maps Live View and the ARCore `hello_geo` sample — so
 * the readout has to stay on screen, the way the Cloud Anchor room-mapping meter does.
 *
 * The meter is [ArOverlayMeter], the same instrument as that room-mapping meter, lit in
 * `warning` while the user must keep moving, `primary` once accuracy is usable and
 * `success` once it is locked.
 *
 * The Drop button is full width and [DROP_BUTTON_MIN_HEIGHT] tall, inside the card, so
 * the one thing to do on this screen is the largest control on it. Its colours are the
 * dark-scheme `primary` / `on-primary` pair in both themes, for the reason every AR
 * overlay accent is: it is read against the scrim, never against `surface`.
 *
 * Every multi-line sentence uses balanced line breaking, so a hint that needs two lines
 * splits evenly instead of leaving its last word alone on the second one (#3832).
 */
@Composable
fun DemoBottomOverlayScope.GeospatialStatusPanel(
    card: GeospatialStatusCard,
    action: GeospatialPrimaryAction,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CardShell(modifier, GEOSPATIAL_STATUS_CARD_TAG) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm + SceneViewTokens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            // TalkBack announces "Location locked" without the user going looking.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            when (card.indicator) {
                GeospatialIndicator.Working -> CircularProgressIndicator(
                    modifier = Modifier.size(STATUS_INDICATOR_SIZE),
                    color = SceneViewTokens.ArOverlay.accentProgress,
                    strokeWidth = 2.dp,
                )
                GeospatialIndicator.Move -> StatusIcon(
                    icon = Icons.Rounded.ScreenRotationAlt,
                    tint = SceneViewTokens.ArOverlay.accentGuidance,
                )
                GeospatialIndicator.Done -> StatusIcon(
                    icon = Icons.Rounded.CheckCircle,
                    tint = SceneViewTokens.ArOverlay.accentSuccess,
                )
            }
            Text(
                text = card.title,
                style = SceneViewTokens.Type.card,
                color = SceneViewTokens.ArOverlay.onScrim,
            )
        }

        ArOverlayMeter(
            filled = card.meterSegments,
            segments = GEOSPATIAL_ACCURACY_SEGMENTS,
            accent = card.meterTone.accent(),
            description = "Location accuracy, ${card.meterSegments} of $GEOSPATIAL_ACCURACY_SEGMENTS",
            modifier = Modifier.testTag(GEOSPATIAL_ACCURACY_METER_TAG),
        )

        card.accuracy?.let { accuracy ->
            Text(
                text = accuracy,
                style = SceneViewTokens.Type.caption,
                color = SceneViewTokens.ArOverlay.onScrim,
                modifier = Modifier.testTag(GEOSPATIAL_ACCURACY_TAG),
            )
        }
        card.coverage?.let { coverage ->
            Text(
                text = coverage,
                style = SceneViewTokens.Type.caption.balanced(),
                color = SceneViewTokens.ArOverlay.onScrimMuted,
            )
        }
        card.hint?.let { hint ->
            Text(
                text = hint,
                style = SceneViewTokens.Type.body.balanced(),
                color = SceneViewTokens.ArOverlay.onScrimMuted,
            )
        }

        card.drop?.let { drop ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(GEOSPATIAL_DROP_FEEDBACK_TAG),
            ) {
                when (drop.tone()) {
                    GeospatialTone.Progress -> CircularProgressIndicator(
                        modifier = Modifier.size(STATUS_INDICATOR_SIZE),
                        color = SceneViewTokens.ArOverlay.accentProgress,
                        strokeWidth = 2.dp,
                    )
                    GeospatialTone.Success -> StatusIcon(
                        icon = Icons.Rounded.CheckCircle,
                        tint = SceneViewTokens.ArOverlay.accentSuccess,
                    )
                    GeospatialTone.Guidance, GeospatialTone.Blocked -> StatusIcon(
                        icon = Icons.Rounded.ErrorOutline,
                        tint = drop.tone().accent(),
                    )
                }
                Text(
                    text = drop.message(),
                    style = SceneViewTokens.Type.body.balanced(),
                    color = SceneViewTokens.ArOverlay.onScrim,
                )
            }
        }

        Button(
            onClick = onDrop,
            enabled = action.enabled,
            shape = RoundedCornerShape(SceneViewTokens.Radius.md),
            colors = ButtonDefaults.buttonColors(
                containerColor = SceneViewTokens.ArOverlay.accentProgress,
                contentColor = md_theme_dark_onPrimary,
                disabledContainerColor = SceneViewTokens.ArOverlay.meterTrack,
                disabledContentColor = SceneViewTokens.ArOverlay.onScrimMuted,
            ),
            // DESIGN.md "large" button: 28 horizontal; the height comes from the minimum.
            contentPadding = PaddingValues(
                horizontal = SceneViewTokens.Space.lg + SceneViewTokens.Space.xs,
                vertical = SceneViewTokens.Space.sm + SceneViewTokens.Space.xs,
            ),
            modifier = Modifier
                .padding(top = SceneViewTokens.Space.xs)
                .fillMaxWidth()
                .heightIn(min = DROP_BUTTON_MIN_HEIGHT)
                .semantics { action.reason?.let { stateDescription = it } }
                .testTag(GEOSPATIAL_DROP_BUTTON_TAG),
        ) {
            Icon(
                imageVector = Icons.Rounded.PinDrop,
                contentDescription = null,
                modifier = Modifier.size(STATUS_INDICATOR_SIZE),
            )
            Text(
                text = action.label,
                style = SceneViewTokens.Type.card,
                modifier = Modifier.padding(start = SceneViewTokens.Space.sm),
            )
        }
    }
}

@Composable
private fun StatusIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(STATUS_INDICATOR_SIZE),
    )
}

/** The coaching overlay's accent for [this] tone. */
private fun GeospatialTone.accent(): Color = when (this) {
    GeospatialTone.Guidance -> SceneViewTokens.ArOverlay.accentGuidance
    GeospatialTone.Progress -> SceneViewTokens.ArOverlay.accentProgress
    GeospatialTone.Success -> SceneViewTokens.ArOverlay.accentSuccess
    GeospatialTone.Blocked -> SceneViewTokens.ArOverlay.accentBlocked
}

/**
 * Balanced line breaking: a sentence that needs two lines splits them evenly, so its last
 * word is never alone on the second line (#3832).
 */
private fun TextStyle.balanced(): TextStyle = copy(
    lineBreak = LineBreak(
        strategy = LineBreak.Strategy.Balanced,
        strictness = LineBreak.Strictness.Normal,
        wordBreak = LineBreak.WordBreak.Default,
    ),
)

/** Same leading-glyph size as the Cloud Anchor card and the coaching pill. */
private val STATUS_INDICATOR_SIZE = SceneViewTokens.Space.md + SceneViewTokens.Space.xs

/** `touch-target` plus `space-sm`: the largest control on the screen, on purpose. */
private val DROP_BUTTON_MIN_HEIGHT = SceneViewTokens.Layout.touchTarget + SceneViewTokens.Space.sm

/** Test tags, so a UI test or a QA flow can find the card without reading copy. */
const val GEOSPATIAL_STATUS_CARD_TAG = "geospatial-status-card"

/** @see GEOSPATIAL_STATUS_CARD_TAG */
const val GEOSPATIAL_DROP_BUTTON_TAG = "geospatial-drop-button"

/** @see GEOSPATIAL_STATUS_CARD_TAG */
const val GEOSPATIAL_ACCURACY_TAG = "geospatial-accuracy"

/** @see GEOSPATIAL_STATUS_CARD_TAG */
const val GEOSPATIAL_ACCURACY_METER_TAG = "geospatial-accuracy-meter"

/** @see GEOSPATIAL_STATUS_CARD_TAG */
const val GEOSPATIAL_DROP_FEEDBACK_TAG = "geospatial-drop-feedback"

package io.github.sceneview.demo.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import io.github.sceneview.demo.DemoBottomOverlayScope
import io.github.sceneview.demo.demos.internal.CloudAnchorCard
import io.github.sceneview.demo.demos.internal.RoomQuality
import io.github.sceneview.demo.demos.internal.ROOM_QUALITY_SEGMENTS
import io.github.sceneview.demo.demos.internal.filledSegments
import io.github.sceneview.demo.demos.internal.label
import io.github.sceneview.demo.demos.internal.shortCloudAnchorCode
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * The one card surface of the Cloud Anchor demo (#3421) — whatever the current step needs
 * the user to *see*, as opposed to the one sentence [DemoStatusBanner] tells them.
 *
 * ## Why it is a scrim card and not a themed `Surface`
 *
 * The screen it replaces put its Cloud Anchor id field in
 * `Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))` — a *themed*
 * surface floating over a live camera feed. `DESIGN.md` names that the one case that must
 * not follow the theme: the ground is an arbitrary camera frame, so in light mode the
 * demo's only custom chrome was a near-white slab over the room, and in dark a near-black
 * one, neither of which the text was contrasted against on purpose. These cards use the
 * same `ar-scrim` ground, `on-ar-scrim` text and `radius-lg` as the coaching pill, so the
 * two read as one overlay language and both behave identically in light and dark.
 *
 * ## Why one composable and not five
 *
 * The old screen decided independently, in three places, whether to show the id field,
 * the hosted-id readout and the banner — which is how it ended up showing the *resolve*
 * field while the user was placing an anchor to host, and hiding it the moment a resolve
 * succeeded. [CloudAnchorCard] is a sealed type chosen once, so exactly one of these can
 * be on screen and the choice is unit-tested rather than emergent.
 *
 * Rendered as a child of the scaffold's `bottomOverlay` Column, above the action bar and
 * below the status pill — hence the [DemoBottomOverlayScope] receiver, the same
 * constraint [DemoStatusBanner] uses to guarantee it cannot collide with the dock.
 *
 * @param card which card to draw, derived from the flow state.
 * @param onCodeChange edits to the Resolve step's code field.
 * @param copied `true` for the few seconds after a Copy, so the hosted-code card can
 *   confirm it — Android only shows its own clipboard confirmation from API 33 and this
 *   app's `minSdk` is 28.
 */
@Composable
fun DemoBottomOverlayScope.CloudAnchorFlowCard(
    card: CloudAnchorCard,
    onCodeChange: (String) -> Unit,
    copied: Boolean = false,
    modifier: Modifier = Modifier,
) {
    when (card) {
        CloudAnchorCard.None -> Unit
        is CloudAnchorCard.Blocked -> CardShell(modifier, CLOUD_ANCHOR_BLOCKED_CARD_TAG) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm + SceneViewTokens.Space.xs),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = SceneViewTokens.ArOverlay.accentBlocked,
                    modifier = Modifier.size(INDICATOR_SIZE),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
                ) {
                    Text(
                        text = card.copy.title,
                        style = SceneViewTokens.Type.card,
                        color = SceneViewTokens.ArOverlay.onScrim,
                    )
                    Text(
                        text = card.copy.body,
                        style = SceneViewTokens.Type.body,
                        color = SceneViewTokens.ArOverlay.onScrimMuted,
                    )
                }
            }
        }

        is CloudAnchorCard.RoomMapping -> CardShell(modifier, CLOUD_ANCHOR_MAPPING_CARD_TAG) {
            CardLabel("Room mapping")
            RoomQualityMeter(card.quality)
            Text(
                text = card.quality.label(),
                style = SceneViewTokens.Type.body,
                color = SceneViewTokens.ArOverlay.onScrim,
            )
        }

        is CloudAnchorCard.HostedCode -> CardShell(modifier, CLOUD_ANCHOR_CODE_CARD_TAG) {
            CardLabel("Anchor code")
            CodeText(card.code)
            Text(
                text = if (copied) {
                    "Copied to clipboard"
                } else {
                    "Expires in ${card.ttlDays} day".let { if (card.ttlDays == 1) it else it + "s" }
                },
                style = SceneViewTokens.Type.caption,
                color = if (copied) {
                    SceneViewTokens.ArOverlay.accentProgress
                } else {
                    SceneViewTokens.ArOverlay.onScrimMuted
                },
            )
        }

        is CloudAnchorCard.ResolveInput -> CardShell(modifier, CLOUD_ANCHOR_INPUT_CARD_TAG) {
            CardLabel("Anchor code")
            CodeField(value = card.code, onValueChange = onCodeChange)
        }

        is CloudAnchorCard.ResolvedCode -> CardShell(modifier, CLOUD_ANCHOR_CODE_CARD_TAG) {
            CardLabel("Anchor code")
            Row(
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = SceneViewTokens.ArOverlay.accentProgress,
                    modifier = Modifier.size(INDICATOR_SIZE),
                )
                CodeText(card.code)
            }
        }
    }
}

/** Test tags, so a UI test or a QA flow can assert which card is up without reading copy. */
const val CLOUD_ANCHOR_BLOCKED_CARD_TAG = "cloud-anchor-blocked-card"

/** @see CLOUD_ANCHOR_BLOCKED_CARD_TAG */
const val CLOUD_ANCHOR_MAPPING_CARD_TAG = "cloud-anchor-mapping-card"

/** @see CLOUD_ANCHOR_BLOCKED_CARD_TAG */
const val CLOUD_ANCHOR_CODE_CARD_TAG = "cloud-anchor-code-card"

/** @see CLOUD_ANCHOR_BLOCKED_CARD_TAG */
const val CLOUD_ANCHOR_INPUT_CARD_TAG = "cloud-anchor-input-card"

/** Matches [DemoStatusBanner]'s leading indicator, so pill and card share one rhythm. */
private val INDICATOR_SIZE = SceneViewTokens.Space.md + SceneViewTokens.Space.xs

/** Height of one room-mapping meter segment. */
private val METER_HEIGHT = SceneViewTokens.Space.sm

/**
 * The shared scrim shell every card sits in: same ground, border, radius, lift and max
 * width as the coaching pill, so nothing about a card has to be decided per case.
 */
@Composable
private fun DemoBottomOverlayScope.CardShell(
    modifier: Modifier,
    testTag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(SceneViewTokens.Radius.lg)
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = modifier
                .padding(horizontal = SceneViewTokens.Space.md)
                .widthIn(max = SceneViewTokens.ArOverlay.maxWidth)
                .fillMaxWidth()
                // Shadow before background so the lift is cast by the card's shape
                // rather than clipped inside it — same order as the coaching pill.
                .shadow(elevation = SceneViewTokens.Elevation.lg, shape = shape, clip = false)
                .background(color = cardScrim(), shape = shape)
                .border(
                    width = SceneViewTokens.ArOverlay.borderWidth,
                    color = cardBorder(),
                    shape = shape,
                )
                .padding(SceneViewTokens.Space.md)
                .testTag(testTag),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            content = content,
        )
    }
}

/**
 * The card's ground, and its hairline.
 *
 * Reads the *theme's* darkness rather than the system's, exactly as [DemoStatusBanner]
 * does: `SceneViewDemoTheme` takes an explicit `darkTheme` flag that previews and the
 * in-app override both set, and `isSystemInDarkTheme()` would ignore it and answer for
 * the OS. Only the opacity moves between themes — the colour does not — because the thing
 * behind the card is a camera frame, not a `surface`.
 */
@Composable
private fun cardScrim(): Color = if (isDarkTheme()) {
    SceneViewTokens.ArOverlay.scrimDark
} else {
    SceneViewTokens.ArOverlay.scrimLight
}

/** @see cardScrim */
@Composable
private fun cardBorder(): Color = if (isDarkTheme()) {
    SceneViewTokens.ArOverlay.borderDark
} else {
    SceneViewTokens.ArOverlay.borderLight
}

@Composable
private fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Small uppercase-free caption naming what the card holds. */
@Composable
private fun CardLabel(text: String) {
    Text(
        text = text,
        style = SceneViewTokens.Type.caption,
        color = SceneViewTokens.ArOverlay.onScrimMuted,
    )
}

/**
 * A Cloud Anchor id, shortened and monospaced.
 *
 * Monospace because it is a code, not prose (`font-mono` in `DESIGN.md`), and shortened
 * because the full 40-odd opaque characters wrapped across three lines in the old sheet
 * and read as noise. The accessible name keeps the full value, so TalkBack and any UI
 * test still see the real id.
 */
@Composable
private fun CodeText(code: String) {
    Text(
        text = shortCloudAnchorCode(code),
        style = SceneViewTokens.Type.body.copy(fontFamily = FontFamily.Monospace),
        color = SceneViewTokens.ArOverlay.onScrim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clearAndSetSemantics { contentDescription = "Anchor code $code" },
    )
}

/**
 * The Resolve step's code field.
 *
 * A [BasicTextField] rather than an `OutlinedTextField`: every Material text-field colour
 * is a theme role, and this field floats over a camera frame where a theme role is the
 * wrong ground (see this file's header). Auto-capitalisation and auto-correct are off —
 * a Cloud Anchor id is case-sensitive and a keyboard that "fixes" it produces a code that
 * silently never resolves.
 */
@Composable
private fun CodeField(value: String, onValueChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = SceneViewTokens.Type.body.copy(
            fontFamily = FontFamily.Monospace,
            color = SceneViewTokens.ArOverlay.onScrim,
        ),
        cursorBrush = SolidColor(SceneViewTokens.ArOverlay.accentProgress),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = SceneViewTokens.ArOverlay.meterTrack,
                shape = RoundedCornerShape(SceneViewTokens.Radius.sm),
            )
            .padding(
                horizontal = SceneViewTokens.Space.sm + SceneViewTokens.Space.xs,
                vertical = SceneViewTokens.Space.sm + SceneViewTokens.Space.xs,
            )
            .testTag(CLOUD_ANCHOR_CODE_FIELD_TAG),
        decorationBox = { field ->
            if (value.isEmpty()) {
                Text(
                    text = "Paste the shared code",
                    style = SceneViewTokens.Type.body.copy(fontFamily = FontFamily.Monospace),
                    color = SceneViewTokens.ArOverlay.onScrimMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            field()
        },
    )
}

/** Test tag for the Resolve step's code field. */
const val CLOUD_ANCHOR_CODE_FIELD_TAG = "cloud-anchor-code-field"

/**
 * Three segments that fill as ARCore's `FeatureMapQuality` improves around the anchor.
 *
 * This feedback did not exist before #3421, and its absence was the demo's most expensive
 * omission: hosting straight from a standing start is the dominant cause of
 * `ERROR_HOSTING_DATASET_PROCESSING_FAILED`, and the old screen answered that with the
 * raw constant. A meter is the right shape because the user's job here is *continuous* —
 * keep walking until it fills — which no sentence conveys as directly.
 *
 * Colour follows the status pill's tone rather than a traffic light: `warning` while the
 * user still has to move, `primary` once ARCore says the map is good enough. The unlit
 * track is the `Button glass` fill, i.e. the same "present but empty" white every other
 * over-media element uses.
 */
@Composable
private fun RoomQualityMeter(quality: RoomQuality) {
    val filled = quality.filledSegments()
    val accent: Color = if (quality == RoomQuality.Insufficient) {
        SceneViewTokens.ArOverlay.accentGuidance
    } else {
        SceneViewTokens.ArOverlay.accentProgress
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "Room mapping ${quality.label()}, " +
                    "$filled of $ROOM_QUALITY_SEGMENTS"
            },
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
    ) {
        repeat(ROOM_QUALITY_SEGMENTS) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(METER_HEIGHT)
                    .background(
                        color = if (index < filled) accent else SceneViewTokens.ArOverlay.meterTrack,
                        shape = RoundedCornerShape(SceneViewTokens.Radius.xs),
                    ),
            )
        }
    }
}

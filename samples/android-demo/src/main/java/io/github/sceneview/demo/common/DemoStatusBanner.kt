package io.github.sceneview.demo.common

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ScreenRotationAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.DemoBottomOverlayScope
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * How loud a [DemoStatusBanner] should be.
 *
 * Deliberately three values, not a `Color` parameter. Every demo used to pick its
 * own — `primary.copy(alpha = 0.85f)` here, `error.copy(alpha = 0.85f)` there,
 * `surface` elsewhere — with the result that the same severity looked different
 * from one demo to the next, and "ARCore is initialising" (normal, transient) got
 * the same red as "no API key configured" (broken, needs the user to act).
 *
 * Since #3265 the tone no longer picks the pill's *background* — it picks the
 * leading indicator and its accent. See [DemoStatusBanner].
 */
enum class DemoStatusTone {
    /** Normal, transient state: initialising, scanning, waiting for a lock. */
    Progress,

    /** Working, but degraded or waiting on the user to do something physical. */
    Guidance,

    /** Broken until the user changes something — missing key, unsupported device. */
    Blocked,
}

/** Test tag for the shared banner, so a UI test can assert on it by contract. */
const val DEMO_STATUS_BANNER_TAG = "demo-status-banner"

/** Vertical padding inside the pill — half of `space-lg`, the one value not on the scale. */
private val PILL_VERTICAL_PADDING = 12.dp

/** Gap between the leading indicator and the sentence. */
private val PILL_ICON_GAP = 12.dp

/** Leading icon / spinner box. Big enough to read at arm's length, small enough to stay a hint. */
private val PILL_INDICATOR_SIZE = 20.dp

/** Lift over a busy camera frame — the hairline border alone is not separation enough. */
private val PILL_ELEVATION = 8.dp

/**
 * The one status banner shape for the demo app — the AR coaching overlay.
 *
 * Only callable inside `DemoScaffold(bottomOverlay = …)`, because the receiver is
 * what carries the geometry it cannot otherwise know: how much room the Settings
 * FAB is taking at the bottom-end of *this* demo. It applies that inset at the
 * **end only**, then centres the pill inside what is left. Not symmetrically:
 * the FAB and its peek chip occupy exactly one corner, so reserving their band
 * on both edges spends the reserve twice to protect one side, and the pill it
 * leaves can be narrower than the word it has to hold (measured: 73 dp against
 * `ar-measure`'s first-launch header, where end-only leaves 242 dp). The pill
 * still reads as centred, because the band it centres in is the band visibly
 * free of chrome.
 *
 * Before this existed, 25 demo files each wrote their own: a `Text` with an
 * `align(Alignment.BottomCenter)`, a hand-picked `padding(bottom = 24.dp)` or
 * `32.dp`, a hand-picked background colour and alpha, and no idea the FAB or the
 * action bar were sitting in the same band. A sweep of the demo directory found
 * fifteen confirmed collisions, most of them visible on first launch. This
 * composable is not a style helper — it is the thing that makes the geometry a
 * demo author cannot get wrong, because they never write it.
 *
 * ## Why the pill is a dark scrim and not brand blue (#3265)
 *
 * It used to paint itself in `primary` / `tertiary` / `error` at 92 % alpha, one
 * flat colour per tone, with 14 sp body text on top. Over a live camera feed that
 * is the wrong ground twice over: mid-tone brand colours sit close to a lot of
 * real-world luminance (blue on sky, purple on a lit wall), and letting the tone
 * pick the *background* meant the most common state — "scanning", the one every
 * AR demo shows on launch — was the hardest to read, in the largest area of
 * colour, for the least reason.
 *
 * The shape every shipping AR app converges on instead (ARKit's coaching overlay,
 * ARCore's coaching card, Polycam's capture hints) is the one this now uses: a
 * dark near-opaque scrim, white text, and the severity carried by a small leading
 * indicator rather than by 300 dp² of colour. `ar-scrim` in `DESIGN.md` is that
 * ground, and it deliberately does **not** flip with the app theme — the thing
 * behind it is a camera frame, not a `surface`; only its opacity moves (light
 * mode is used outdoors more often, where the frame is brightest). Text is
 * `on-ar-scrim` white at `text-body` / `weight-medium`, ≈ 19:1 against the
 * scrim, where the old 14 sp on a mid-tone container failed WCAG AA outdoors
 * even at 92 % alpha.
 *
 * ## Nothing to say means nothing on screen
 *
 * [text] is nullable, and a null or blank string animates the pill *out* instead
 * of leaving an empty capsule floating over the scene. A demo whose step is done
 * therefore auto-hides by passing `null` — no per-demo `AnimatedVisibility`
 * needed, though the demos that already wrap this in one keep working unchanged,
 * which is why the parameter list is otherwise identical to the old one.
 *
 * @param text the complete sentence to show, or `null`/blank to hide the banner.
 *   Banners say what is happening and what to do about it; they are not labels.
 *   Keep them short — the pill holds three lines and truncates past that.
 * @param tone the severity, which picks the leading indicator and its accent.
 * @param icon overrides the tone's default leading icon. A `Progress` banner with
 *   no override shows an indeterminate spinner rather than an icon.
 */
@Composable
fun DemoBottomOverlayScope.DemoStatusBanner(
    text: String?,
    tone: DemoStatusTone = DemoStatusTone.Progress,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val visible = !text.isNullOrBlank()

    // Hold the last real sentence for the length of the exit animation: reading
    // `text` straight through would blank the pill's content on the same frame the
    // fade starts, and the user would watch an empty capsule slide away.
    var lastText by remember { mutableStateOf(text.orEmpty()) }
    var lastTone by remember { mutableStateOf(tone) }
    var lastIcon by remember { mutableStateOf(icon) }
    if (visible) {
        lastText = text!!
        lastTone = tone
        lastIcon = icon
    }

    // Read the *theme's* darkness, not the system's: `SceneViewDemoTheme` takes an
    // explicit `darkTheme` flag (previews and the in-app theme override both set it),
    // and `isSystemInDarkTheme()` would ignore that and answer for the OS.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val scrim = if (dark) {
        SceneViewTokens.ArOverlay.scrimDark
    } else {
        SceneViewTokens.ArOverlay.scrimLight
    }
    val borderColor = if (dark) {
        SceneViewTokens.ArOverlay.borderDark
    } else {
        SceneViewTokens.ArOverlay.borderLight
    }
    val accent: Color = when (lastTone) {
        DemoStatusTone.Progress -> SceneViewTokens.ArOverlay.accentProgress
        DemoStatusTone.Guidance -> SceneViewTokens.ArOverlay.accentGuidance
        DemoStatusTone.Blocked -> SceneViewTokens.ArOverlay.accentBlocked
    }
    val leadingIcon: ImageVector? = lastIcon ?: when (lastTone) {
        // Progress has no icon on purpose: the spinner *is* the indicator, and it
        // says "still working" in a way no static glyph does.
        DemoStatusTone.Progress -> null
        DemoStatusTone.Guidance -> Icons.Rounded.ScreenRotationAlt
        DemoStatusTone.Blocked -> Icons.Rounded.ErrorOutline
    }
    val shape = RoundedCornerShape(SceneViewTokens.Radius.lg)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // End-only, and centred inside what is left — *not* the symmetric inset
            // this used to apply. The FAB and its peek chip occupy exactly one corner,
            // so reserving their band on both edges spends the reserve twice to
            // protect one side. That was affordable while the reserve was a flat
            // 104 dp; it stopped being affordable once the reserve started tracking
            // the real chip, which can legitimately be a third of the screen. Measured
            // on a 411 dp screen with `ar-measure`'s first-launch header: symmetric
            // left the pill 73 dp — narrower than the word "measuring" — while
            // end-only leaves 242 dp. The pill still reads as centred, because the
            // band it centres in is the band visibly free of chrome (#3229).
            .padding(end = settingsFabReservedSpace),
        contentAlignment = Alignment.Center,
    ) {
        // Fully qualified: the receiver is a `ColumnScope`, whose `AnimatedVisibility`
        // extension would win resolution here and animate the wrong axis.
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            // Rise on entry, fall on exit, both with `ease-expressive`: the pill
            // belongs to the bottom band it comes from. The exit is the shorter of
            // the two — a message that no longer applies should get out of the way.
            enter = fadeIn(
                animationSpec = tween(
                    durationMillis = SceneViewTokens.Duration.mediumMillis,
                    easing = SceneViewTokens.Ease.expressive,
                ),
            ) + slideInVertically(
                animationSpec = tween(
                    durationMillis = SceneViewTokens.Duration.mediumMillis,
                    easing = SceneViewTokens.Ease.expressive,
                ),
                initialOffsetY = { it / 3 },
            ),
            exit = fadeOut(
                animationSpec = tween(
                    durationMillis = SceneViewTokens.Duration.shortMillis,
                    easing = SceneViewTokens.Ease.expressive,
                ),
            ) + slideOutVertically(
                animationSpec = tween(
                    durationMillis = SceneViewTokens.Duration.shortMillis,
                    easing = SceneViewTokens.Ease.expressive,
                ),
                targetOffsetY = { it / 3 },
            ),
        ) {
            Row(
                modifier = modifier
                    .padding(horizontal = SceneViewTokens.Space.md)
                    .widthIn(max = SceneViewTokens.ArOverlay.maxWidth)
                    // Shadow before background so the lift is cast by the pill's
                    // shape rather than clipped inside it.
                    .shadow(elevation = PILL_ELEVATION, shape = shape, clip = false)
                    .background(color = scrim, shape = shape)
                    .border(
                        width = SceneViewTokens.ArOverlay.borderWidth,
                        color = borderColor,
                        shape = shape,
                    )
                    .padding(
                        horizontal = SceneViewTokens.Space.md,
                        vertical = PILL_VERTICAL_PADDING,
                    )
                    // TalkBack should announce a coaching change without the user
                    // going looking for it — that is the whole point of the surface.
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(DEMO_STATUS_BANNER_TAG),
                horizontalArrangement = Arrangement.spacedBy(PILL_ICON_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(PILL_INDICATOR_SIZE),
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(PILL_INDICATOR_SIZE),
                        color = accent,
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    text = lastText,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = SceneViewTokens.ArOverlay.onScrim,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

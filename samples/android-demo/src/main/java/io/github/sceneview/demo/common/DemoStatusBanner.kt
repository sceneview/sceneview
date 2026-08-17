package io.github.sceneview.demo.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.DemoBottomOverlayScope

/**
 * How loud a [DemoStatusBanner] should be.
 *
 * Deliberately three values, not a `Color` parameter. Every demo used to pick its
 * own — `primary.copy(alpha = 0.85f)` here, `error.copy(alpha = 0.85f)` there,
 * `surface` elsewhere — with the result that the same severity looked different
 * from one demo to the next, and "ARCore is initialising" (normal, transient) got
 * the same red as "no API key configured" (broken, needs the user to act).
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

/**
 * The one status banner shape for the demo app.
 *
 * Only callable inside `DemoScaffold(bottomOverlay = …)`, because the receiver is
 * what carries the geometry it cannot otherwise know: how much room the Settings
 * FAB is taking at the bottom-end of *this* demo. It applies that inset
 * symmetrically — the banner is centred, and a centred element only keeps its end
 * edge out of the FAB's band when the band is reserved on both sides.
 *
 * Before this existed, 23 demo files each wrote their own: a `Text` with an
 * `align(Alignment.BottomCenter)`, a hand-picked `padding(bottom = 24.dp)` or
 * `32.dp`, a hand-picked background colour and alpha, and no idea the FAB or the
 * action bar were sitting in the same band. A sweep of the demo directory found
 * fifteen confirmed collisions, most of them visible on first launch. This
 * composable is not a style helper — it is the thing that makes the geometry a
 * demo author cannot get wrong, because they never write it.
 *
 * @param text the complete sentence to show. Banners say what is happening and
 *   what to do about it; they are not labels.
 */
@Composable
fun DemoBottomOverlayScope.DemoStatusBanner(
    text: String,
    tone: DemoStatusTone = DemoStatusTone.Progress,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val container: Color = when (tone) {
        DemoStatusTone.Progress -> scheme.primary
        DemoStatusTone.Guidance -> scheme.tertiary
        DemoStatusTone.Blocked -> scheme.error
    }
    val content: Color = when (tone) {
        DemoStatusTone.Progress -> scheme.onPrimary
        DemoStatusTone.Guidance -> scheme.onTertiary
        DemoStatusTone.Blocked -> scheme.onError
    }
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
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            textAlign = TextAlign.Center,
            modifier = modifier
                .background(
                    // 0.92f rather than the 0.85f the demos had settled on: these
                    // banners are read over a live camera feed, and at 0.85 the
                    // highest-contrast text in the app was still failing WCAG AA
                    // against a bright outdoor scene showing through.
                    color = container.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(24.dp),
                )
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .testTag(DEMO_STATUS_BANNER_TAG),
        )
    }
}

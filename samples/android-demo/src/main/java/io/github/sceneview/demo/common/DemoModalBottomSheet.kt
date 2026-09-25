@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.common

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.view.WindowCompat
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * The one entry point every demo-app `ModalBottomSheet` goes through (#3716).
 *
 * The system paints the navigation bar's contrast scrim and its icon appearance
 * (light/dark) from the **dialog window's** own state — material3's `ModalBottomSheet`
 * opens in its own `Dialog`, a window that never inherits `MainActivity`'s
 * `enableEdgeToEdge()`, and the library itself never touches either setting (checked
 * against the `material3` 1.5.0-alpha27 `.aar`: no reference to
 * `isNavigationBarContrastEnforced` anywhere in it). Left alone, a dark-theme sheet still
 * requests the *default* (light) navigation-bar appearance, and the system adds a pale
 * translucent scrim to guarantee contrast for icons the sheet never actually asked for —
 * a light band across the bottom of a dark sheet.
 *
 * Fixed here, once, for every sheet in the app:
 * - the **navigation-bar** appearance flags follow the **sheet's own** darkness
 *   (`MaterialTheme.colorScheme.surface.luminance()`, the same reading `DemoStatusBanner`
 *   and `CloudAnchorCards` use) rather than `isSystemInDarkTheme()` — the app's own theme
 *   can diverge from the OS setting, and reading the OS would answer the wrong question.
 *   The nav bar sits directly behind the sheet's own bottom edge (padded, not clipped, by
 *   content — see below), so the sheet's colour is genuinely what is behind it;
 * - the contrast scrim is switched off on API 29+, exactly as `enableEdgeToEdge()` already
 *   does for the host Activity window: the sheet supplies its own contrast (DESIGN.md's
 *   `surface-container` against `on-surface`), so the system scrim is redundant on top of
 *   a themed surface and is what was producing the mismatch;
 * - the **status-bar** appearance flags do *not* follow the sheet's darkness (#3796). A
 *   *bottom* sheet never reaches the status-bar row — that area is still whatever the host
 *   Activity window was already showing under its own scrim, which is a themed `surface` on
 *   most screens but the always-dark 3D/AR stage (`DESIGN.md`'s `stage-background`,
 *   `#0B0F16` in both themes) on every demo viewer. Deriving the flag from the *sheet's*
 *   theme answered the wrong question there: in the light theme the sheet is light
 *   (`isDark == false`), so it requested dark status-bar icons, which then sat on the
 *   still-dark scene behind the dialog's full-bleed scrim and disappeared. The dialog is a
 *   separate window from the Activity's, so it does not inherit the Activity's own
 *   already-correct flag (set once by `enableEdgeToEdge()` for a themed screen, or live by
 *   `DemoScaffold`'s chrome effect for a stage screen) — this wrapper reads that flag off
 *   the host window and mirrors it onto the dialog window instead of recomputing it, so the
 *   answer is correct on both kinds of screen without the wrapper needing to know which
 *   kind is behind it.
 *
 * Content still owns its own navigation-bar *content* padding
 * (`Modifier.navigationBarsPadding()`, or `WindowInsets.navigationBars` folded into a
 * `LazyColumn`'s `contentPadding`) — a scrolling list wants the inset added to its
 * scrollable padding rather than clamping the sheet's own height, so this wrapper does not
 * impose one shape on every sheet's content; it only owns the window chrome.
 */
@Composable
fun DemoModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    // Read BEFORE the Dialog exists: `LocalView.current` here is still the caller's own
    // content view, so this chain reaches the host Activity, not the sheet's own window.
    // That flag is already correct for whatever the status-bar row actually sits over —
    // see the class doc — so it is copied, not recomputed from the sheet's theme.
    val callerView = LocalView.current
    val hostStatusBarsLight = remember(callerView) {
        generateSequence(callerView.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
            ?.window
            ?.let { WindowCompat.getInsetsController(it, callerView).isAppearanceLightStatusBars }
    } ?: !isDark
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        scrimColor = scrimColor,
        properties = ModalBottomSheetProperties(
            securePolicy = SecureFlagPolicy.Inherit,
            isAppearanceLightStatusBars = hostStatusBarsLight,
            isAppearanceLightNavigationBars = !isDark,
        ),
    ) {
        val view = LocalView.current
        DisposableEffect(view, isDark) {
            val dialogWindow = (view.parent as? DialogWindowProvider)?.window
            val previousEnforced = if (dialogWindow != null && Build.VERSION.SDK_INT >= 29) {
                dialogWindow.isNavigationBarContrastEnforced
            } else {
                null
            }
            if (dialogWindow != null && Build.VERSION.SDK_INT >= 29) {
                dialogWindow.isNavigationBarContrastEnforced = false
            }
            onDispose {
                if (dialogWindow != null && previousEnforced != null && Build.VERSION.SDK_INT >= 29) {
                    dialogWindow.isNavigationBarContrastEnforced = previousEnforced
                }
            }
        }
        content()
    }
}

/**
 * Colours for a sheet you tweak a live scene through (#3827) — the demo settings sheet
 * and the Model Viewer's Lighting sheet.
 *
 * Pass [glassContainerColor] as the container and [NoScrim] as the scrim: the fill is
 * `surface-container` at the `glass-sheet` opacity for the current theme, so the scene
 * reads through it, and nothing dims the scene around it. Browsing sheets (model picker,
 * credits, what's new) keep the opaque default — you read those, you do not watch
 * something change behind them.
 */
object DemoSheetDefaults {
    /** `glass-sheet`: `surface-container` at 88 % (light) / 90 % (dark). */
    @Composable
    fun glassContainerColor(): Color {
        val scheme = MaterialTheme.colorScheme
        val isDark = scheme.surface.luminance() < 0.5f
        return scheme.surfaceContainer.copy(
            alpha = if (isDark) SceneViewTokens.Glass.sheetAlphaDark else SceneViewTokens.Glass.sheetAlphaLight,
        )
    }

    /** No dimming behind a glass sheet — the scene is what you are looking at. */
    val NoScrim: Color = Color.Transparent
}

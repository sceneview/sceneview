@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.common

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy

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
 * - the appearance flags follow the **theme's** darkness
 *   (`MaterialTheme.colorScheme.surface.luminance()`, the same reading `DemoStatusBanner`
 *   and `CloudAnchorCards` use) rather than `isSystemInDarkTheme()` — the app's own theme
 *   can diverge from the OS setting, and reading the OS would answer the wrong question;
 * - the contrast scrim is switched off on API 29+, exactly as `enableEdgeToEdge()` already
 *   does for the host Activity window: the sheet supplies its own contrast (DESIGN.md's
 *   `surface-container` against `on-surface`), so the system scrim is redundant on top of
 *   a themed surface and is what was producing the mismatch.
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        properties = ModalBottomSheetProperties(
            securePolicy = SecureFlagPolicy.Inherit,
            isAppearanceLightStatusBars = !isDark,
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
                if (dialogWindow != null && previousEnforced != null) {
                    dialogWindow.isNavigationBarContrastEnforced = previousEnforced
                }
            }
        }
        content()
    }
}

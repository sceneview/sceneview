package io.github.sceneview.demo.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The colours of one AR Debug View palette — see [SceneViewTokens.DebugView].
 *
 * The `*Glow` factors multiply the colour past 1.0 in linear light so bloom picks it up; 1 is
 * flat.
 */
@Immutable
class DebugPalette(
    val trailOld: Color,
    val trailMid: Color,
    val trailNew: Color,
    val trailHeadGlow: Float,
    val frustum: Color,
    val frustumGlow: Float,
    val frustumFace: Color,
    val keyframe: Color,
    val mapPoint: Color,
    val livePoint: Color,
    val livePointGlow: Float,
    val floorFill: Color,
    val floorOutline: Color,
    val wallFill: Color,
    val wallOutline: Color,
    val otherFill: Color,
    val otherOutline: Color,
    val anchor: Color,
    val anchorGlow: Float,
    val gridMinor: Color,
    val gridMajor: Color,
    val axisX: Color,
    val axisY: Color,
    val axisZ: Color,
)

/**
 * `DESIGN.md` — Themed stage (#4080): the ground a [io.github.sceneview.demo.DemoScaffold] stage
 * is drawn on, and the chrome that floats over it.
 *
 * Every demo's stage is media — a Filament scene or the camera — and its chrome is the
 * theme-independent glass of [Media]. The Rerun demo is the exception: its stage is a view the
 * app draws itself (the replay, the landing page), so it can follow the theme. In light theme it
 * takes [Light]: the ground is `surface-dim`, the chrome is `glass-sheet` (`surface-container`
 * at 88 %) with `on-surface` glyphs, the chrome bands wash towards the ground instead of towards
 * black, and the status bar keeps the theme's dark icons. In dark theme it keeps [Media], so the
 * dark replay is the one it always was.
 *
 * A demo opts in with `DemoScaffold(themedStage = true)`; anything under it reads the palette
 * from [LocalStageChrome].
 */
@Immutable
class StageChrome(
    /** The stage's own ground, behind and around the scene. */
    val ground: Color,
    /** Fill of a glass control (back button, identity pill, dock). */
    val glass: Color,
    val onGlass: Color,
    val onGlassMuted: Color,
    /** `over-media-edge`, the ring and the halo outside it. */
    val edgeRing: Color,
    val edgeHalo: Color,
    /** `chrome-scrim` under the top band, `chrome-scrim-dock` under the bottom one. */
    val scrim: Color,
    val scrimDock: Color,
    /** Fill of an overlay card (a HUD, a timeline), and its text. */
    val card: Color,
    val onCard: Color,
    val onCardMuted: Color,
    /** An unlit track or dot on a card: "present but empty". */
    val track: Color,
    /** The one filled accent (the dock's accent disc, a primary action) and its foreground. */
    val accent: Color,
    val onAccent: Color,
    /** Whether the status bar needs white icons over this chrome. */
    val lightStatusIcons: Boolean,
    /** The 3D view's palette on this ground. */
    val debug: DebugPalette,
) {
    companion object {
        /** Media: the dark, theme-independent chrome of every demo. */
        val Media = StageChrome(
            ground = SceneViewTokens.Stage.background,
            glass = SceneViewTokens.Glass.surface,
            onGlass = SceneViewTokens.Glass.onGlass,
            onGlassMuted = SceneViewTokens.Glass.onGlassMuted,
            edgeRing = SceneViewTokens.Glass.edgeRing,
            edgeHalo = SceneViewTokens.Glass.edgeHalo,
            scrim = SceneViewTokens.Glass.scrim,
            scrimDock = SceneViewTokens.Glass.scrimDock,
            card = SceneViewTokens.ArOverlay.scrimDark,
            onCard = SceneViewTokens.ArOverlay.onScrim,
            onCardMuted = SceneViewTokens.ArOverlay.onScrimMuted,
            track = SceneViewTokens.ArOverlay.meterTrack,
            accent = SceneViewTokens.ArOverlay.accentProgress,
            onAccent = SceneViewTokens.ArOverlay.onAccentProgress,
            lightStatusIcons = true,
            debug = SceneViewTokens.DebugView.Dark,
        )

        /**
         * Light: `surface-dim` ground, `glass-sheet` chrome, `on-surface` text (13:1 on the
         * glass), `on-surface-dim` secondary text (8.9:1), a 12 % `on-surface` edge, and the
         * light scheme's `primary` as the accent.
         */
        val Light = StageChrome(
            ground = Color(0xFFF1F3F5),
            glass = Color(0xE0FFFFFF),
            onGlass = Color(0xFF1A1A2E),
            onGlassMuted = Color(0xFF3D4654),
            edgeRing = Color(0x1F1A1A2E),
            edgeHalo = Color.Transparent,
            scrim = Color(0x99F1F3F5),
            scrimDock = Color(0xADF1F3F5),
            card = Color(0xE0FFFFFF),
            onCard = Color(0xFF1A1A2E),
            onCardMuted = Color(0xFF3D4654),
            track = Color(0x141A1A2E),
            accent = Color(0xFF005BC1),
            onAccent = Color(0xFFFFFFFF),
            lightStatusIcons = false,
            debug = SceneViewTokens.DebugView.Light,
        )
    }
}

/** The stage chrome in effect: [StageChrome.Media] unless a themed stage provides another. */
val LocalStageChrome = staticCompositionLocalOf { StageChrome.Media }

/** The stage chrome for the current theme: [StageChrome.Light] in light, [StageChrome.Media] in dark. */
@Composable
fun themedStageChrome(): StageChrome =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) StageChrome.Media else StageChrome.Light

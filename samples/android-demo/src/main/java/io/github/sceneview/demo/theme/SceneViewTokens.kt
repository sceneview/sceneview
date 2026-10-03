package io.github.sceneview.demo.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Compose translation of the non-colour, non-typography tokens in `DESIGN.md`.
 *
 * Names mirror the token names in `DESIGN.md` one-for-one — `Space.md` is `space-md` —
 * so a reader (human or AI) can move between the spec and the code without a lookup
 * table. Material 3 slot names stay on the Material side of the boundary: `Shape.kt`
 * writes `extraSmall = RoundedCornerShape(SceneViewTokens.Radius.xs)`, which reads as
 * the translation it is.
 *
 * An immutable object fits these application-wide constants better than a
 * CompositionLocal: none of the source tokens varies with theme or composition.
 */
@Immutable
object SceneViewTokens {
    /** Model-viewer stage, deliberately identical in light and dark themes. */
    object Stage {
        val background = Color(0xFF0B0F16)
    }
    /** `DESIGN.md` — Spatial Gallery overlay colours. */
    object SpatialGalleryColor {
        val stageScrimStart = Color.Transparent
        val stageScrimEnd = Color(0xE6000000)
        val glassSurfaceLight = Color(0xB8FFFFFF)
        val glassSurfaceDark = Color(0x0DFFFFFF)
        val glassBorderLight = Color(0x14FFFFFF)
        val glassBorderDark = Color(0x14FFFFFF)
        val glassBorderWidth = 1.dp
    }

    /**
     * `DESIGN.md` — Liquid Glass, the "button glass" row, as the demo chrome uses it.
     *
     * Theme-independent on purpose: the chrome floats over a live Filament/ARCore
     * viewport, which is media, not a themed surface. White on media reads in both
     * themes, so the same values serve light and dark. There is no blur: a
     * `SurfaceView` cannot be sampled by a Compose render effect.
     *
     * ## Why this is no longer the spec's 8 % (#3681)
     *
     * 8 % white is a value borrowed from platforms that back it with a real backdrop
     * blur, which separates the panel from the media by *structure* — the fill alone was
     * never doing the work. Without blur it has to, and it cannot: 8 % over the
     * `#0B0F16` stage is 1.47 → **1.20:1**, and 1.14:1 over a 60 %-scrimmed camera
     * feed. The panel edge was a guess in both themes.
     *
     * 14 % is the first fill that clears 1.25:1 on both grounds with margin — 1.47:1 and
     * 1.35:1 — while staying obviously glass rather than a solid sheet. The web and iOS
     * surfaces keep 8 % where they have real blur.
     *
     * ## And why the border became an edge (#3503)
     *
     * 1.25:1 is a *fill* bar. The line that tells you where a control begins is measured by
     * WCAG 1.4.11 at **3:1**, and the 24 % white border never came close — not because of
     * its opacity but because of where Compose drew it: `Modifier.border` strokes inside
     * the bounds, on top of the panel's own 14 % white fill, which is 1.03:1. Every value
     * we could have chosen was invisible. The replacement is [edgeRing] + [edgeHalo],
     * painted outside the shape, on the media itself.
     */
    object Glass {
        /** `glass-surface` over media — white at 14 %. */
        val surface = Color(0x24FFFFFF)
        /**
         * `glass-border` — white at 24 %, 1 dp.
         *
         * Kept as a *width* only. The colour is gone: a hairline painted inside a glass
         * panel is 1.03:1 against its own fill, so it never identified anything. The edge
         * of an over-media control is [edgeRing] + [edgeHalo], applied with
         * `Modifier.overMediaEdge(shape)`.
         */
        val borderWidth = 1.dp

        /**
         * `over-media-edge`, inner band — white at 36 %, 1 dp.
         *
         * The boundary that says "this is a control" on an element floating over a camera
         * frame. It is measured against WCAG 1.4.11, which asks **3:1** for the visual
         * information needed to identify a component — not against the 1.25:1 surface bar
         * the old `glass-border` was tuned to, which is a *fill* threshold and was never
         * the right test for an edge.
         *
         * White alone cannot pass it: over a white wall (#F5F5F5) a 36 % white line is
         * 1.4:1, and raising the opacity makes it worse, not better. The edge therefore has
         * two bands — this one plus [edgeHalo] immediately outside it — so whichever band
         * loses against the room, the other one wins. Applied with
         * `Modifier.overMediaEdge(shape)`.
         */
        val edgeRing = Color(0x5CFFFFFF)

        /**
         * `over-media-edge`, outer band — black at 75 %, 1 dp, drawn **outside** the shape.
         *
         * Outside is the whole point. Compose's `Modifier.border` strokes *inside* the
         * bounds, i.e. on top of the element's own fill, where a 14 % white glass panel and
         * a white line differ by 1.03:1 — the border was invisible by construction, on every
         * ground, in both themes. Painted outside, the pair is read against the media:
         * ≥ 3:1 on a white wall via the halo, ≥ 3:1 on a night scene via the ring.
         */
        val edgeHalo = Color(0xBF000000)

        /** Width of each of the two [edgeRing] / [edgeHalo] bands. */
        val edgeWidth = 1.dp
        /** Foreground on glass over media: always white. */
        val onGlass = Color.White
        /** Secondary foreground on glass — white at 72 %. */
        val onGlassMuted = Color(0xB8FFFFFF)
        /** `GlassIconButton` visual diameter. The touch target is [Layout.touchTarget]. */
        val iconButtonSize = 44.dp
        /** `GlassPill` height. */
        val pillHeight = 36.dp
        /** `GlassPill` horizontal content padding. */
        val pillPaddingHorizontal = 14.dp

        /**
         * `chrome-scrim` — the wash the chrome bands sit on.
         *
         * White on media only reads when the media is dark, and a Filament scene is
         * whatever the demo authored: the contact-shadow studio is a near-white room,
         * where an 8 % white fill and white glyphs disappear entirely. The chrome
         * therefore carries its own ground, the same transparent-to-black media scrim
         * the Spatial Gallery puts under white copy. 60 % black takes a white
         * background down to ~5.6:1 against white text, so the band reads on the
         * brightest scene the demos ship and stays unobtrusive on the darkest.
         */
        val scrim = Color(0x99000000)

        /**
         * `chrome-scrim-dock` — the same wash, two points darker, under the **bottom**
         * band only.
         *
         * The bottom band carries something the top band does not: the dock's captions
         * sit on the dock's own [surface] fill — white at 14 % — and *that* sits on the
         * scrim. [scrim]'s own "~5.6:1" is measured for text directly on the wash, and
         * it is right for the identity row. It does not describe the dock, because the
         * glass fill lifts the ground back up before the caption ever lands on it.
         *
         * Over a white scene, composing the real stack (white caption / white 14 % /
         * black α / white):
         *
         * ```
         * 60 %  scene 255 -> scrim 102.0 -> glass 123.6  ->  white text  4.20:1   FAIL
         * 68 %  scene 255 -> scrim  82.0 -> glass 106.4  ->  white text  5.37:1   pass
         * ```
         *
         * Computed from the tokens, not sampled: it takes a white *camera frame* to
         * photograph, and 1.4.3's threshold is about the worst case anyway. On a black
         * scene both values are the same 15.5:1 — the scrim is doing nothing there.
         *
         * It is deliberately *not* applied to [scrim] wholesale. The top band's text
         * lands on the wash directly, already clears 4.5:1 at 60 %, and darkening the
         * status-bar end of the screen would buy contrast nobody asked for at the cost
         * of hiding more of the scene. One number moves, where the defect is.
         */
        val scrimDock = Color(0xAD000000)
        /** Height of the top scrim: the identity row, its gutter and the status bar. */
        val scrimTopHeight = 160.dp
        /**
         * Floor for the bottom scrim: enough for the dock band alone. The scaffold
         * grows it to the measured `dockClearance + bottomOverlayBand` whenever a
         * demo stacks a status pill or a legend above the dock, so the ground always
         * reaches the topmost thing standing on it.
         */
        val scrimBottomHeight = 220.dp
        /**
         * Where the scrim stops being flat and starts fading out, as a fraction of its
         * height measured from the screen edge. The chrome sits inside the flat part.
         */
        const val scrimPlateau = 0.55f

        /**
         * `glass-sheet`, light — `surface-container` at 88 %, with no scrim behind it (#3827).
         *
         * A settings sheet exists to be watched through: you drag a slider and look at what
         * it did to the scene. An opaque sheet over a dimming scrim hid exactly that. There
         * is still no blur (a `SurfaceView` cannot be sampled), so the fill alone carries
         * legibility. Composited over the three grounds a demo can put behind it — the
         * `#0B0F16` stage, a mid-grey scene, a white AR wall — `on-surface` never drops
         * under 13:1 and `on-surface-variant` never under 7.4:1 (stage is the worst ground
         * in light). 78 % already passed on contrast (5.8:1), but on the emulator a lit
         * model read through the chips as a second, sharp image under the labels; 88 %
         * keeps the scene as a silhouette and the controls as the only thing in focus.
         */
        const val sheetAlphaLight = 0.88f

        /**
         * `glass-sheet`, dark — `surface-container` at 90 %.
         *
         * The worst ground flips in dark: a dark sheet over a *white* scene.
         * At 78 % `on-surface-variant` fell to 3.0:1 there; 90 % holds 4.5:1, and
         * `on-surface` 9.5:1. Over the dark stage the extra opacity costs nothing visible —
         * the translucency that matters is over bright content, and it is still there.
         */
        const val sheetAlphaDark = 0.90f

        /**
         * `sheet-peek` — the resting detent of the demo settings sheet, as a fraction of
         * the window height (#3827). About a third: the header and the first controls are
         * in reach, and the upper two thirds — where every demo frames its hero — stay
         * visible and live. Dragging up reveals the rest; a sheet whose controls are
         * shorter than this hugs them instead.
         */
        const val sheetPeekFraction = 0.36f
    }

    /**
     * Home / app-wide type scale — the only five text styles the demo app's own
     * chrome uses (design spec §2). `-0.02em` on display/title; line height 1.2
     * on display/title, 1.35 on body.
     *
     * Tokens: `type-display 32sp/700`, `type-title 22sp/600`, `type-card 17sp/600`,
     * `type-body 15sp/400`, `type-caption 13sp/500`.
     */
    object Type {
        val display = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
        val title = TextStyle(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em)
        val card = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        val body = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
        val caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    }

    /**
     * Home screen colours that are NOT Material roles (design spec §2).
     *
     * The hero is an image card that stays dark in both themes, so its text and
     * pill are fixed: `hero-title #FFFFFF`, `hero-subtitle rgba(255,255,255,0.80)`,
     * `hero-pill-bg #FFFFFF`, `hero-pill-text #1A1A2E`. Filter chips follow
     * `DESIGN.md` surfaces rather than the M3 ramp, which is why they carry
     * explicit light/dark pairs: unselected `chip-bg` = `surface-dim`
     * (#F1F3F5 / #161B22) with `chip-text` = `on-surface-dim` (#3D4654 / #9CA3AF);
     * selected `chip-selected-bg` = `on-surface` (#1A1A2E / #F3F4F6) with
     * `chip-selected-text` = `surface` (#FFFFFF / #0D1117). `header-overlay` is
     * `surface` at 94 %.
     */
    object HomeColor {
        val heroTitle = Color(0xFFFFFFFF)
        val heroSubtitle = Color(0xCCFFFFFF)
        val heroPillBackground = Color(0xFFFFFFFF)
        val heroPillText = Color(0xFF1A1A2E)
        /**
         * Hero placeholder / stage field, `#0B0F16` — matches the viewer stage clear
         * colour. This is the **light** and full-screen value; see
         * [heroFieldEmbeddedDark] for why a stage inside a card needs its own.
         */
        val heroField = Color(0xFF0B0F16)

        /**
         * A stage **embedded in a card**, in dark: `surface-container`.
         *
         * `#0B0F16` against the `#0D1117` dark page is 1.01:1 — the stage field, and so
         * the whole card carrying it, is the page. Painted over the card fill it also
         * defeats whatever surface role the card was given, which is why this cannot be
         * fixed from the colour scheme alone. A full-screen stage keeps `#0B0F16`: there
         * is no card for it to disappear into.
         */
        val heroFieldEmbeddedDark = Color(0xFF232A39)

        /**
         * `hero-sky-*` — the dusk Compose paints behind the transparent home stage
         * (#3948), top to bottom. The 3D flight is rendered over this gradient with no
         * skybox of its own, so the sky is one gradient in both themes: [heroSkyTop] is
         * the `stage-background` value, [heroSkyHorizon] is also the scene's fog colour,
         * which is what lets the far ridges dissolve into it.
         */
        val heroSkyTop = Color(0xFF0B0F16)
        val heroSkyDusk = Color(0xFF3B1D46)
        val heroSkyHorizon = Color(0xFFE2734F)
        val heroSkyGround = Color(0xFF2A1220)

        val chipBackgroundLight = Color(0xFFF1F3F5)
        /**
         * Tracks `surfaceContainerHigh`: a chip is a container and has to read as one.
         * #161B22 was 1.09:1 against the page — in light the identical construction is
         * 1.11:1 and reads, because at the light end that ratio is a visible step and at
         * the dark end it is not. 1.54:1 now.
         */
        val chipBackgroundDark = Color(0xFF2C3546)
        val chipTextLight = Color(0xFF3D4654)
        /** Tracks `onSurfaceVariant`; 5.33:1 on the chip background above. */
        val chipTextDark = Color(0xFFA4ABB7)
        val chipSelectedBackgroundLight = Color(0xFF1A1A2E)
        val chipSelectedBackgroundDark = Color(0xFFF3F4F6)
        val chipSelectedTextLight = Color(0xFFFFFFFF)
        val chipSelectedTextDark = Color(0xFF0D1117)

        /**
         * `primary-light` — the primary role at 8 % (light) / 10 % (dark), the
         * "Subtle backgrounds" row of `DESIGN.md`. The hue itself stays the
         * Material `primary` role so the tint follows the scheme; only the
         * opacity is a token, because the spec sets a different one per theme.
         */
        const val primaryLightAlphaLight = 0.08f
        const val primaryLightAlphaDark = 0.10f

        /**
         * `outline-subtle` — the 1 dp card + header hairline, and the home search
         * field's unfocused border.
         *
         * The dark value was #1F2937: 1.29:1 against the page, which is why the search
         * field only existed once you focused it. It now tracks `outlineVariant`
         * (2.38:1 on the page, 1.81:1 on a card) so the hairline is a boundary rather
         * than a texture. Light is unchanged.
         */
        val outlineSubtleLight = Color(0xFFEBEDF0)
        val outlineSubtleDark = Color(0xFF46516A)

        const val headerOverlayAlpha = 1f

        /** `header-glass`, light: `surface` over a blurred copy of the list under the header. */
        const val headerGlassAlphaLight = 0.72f

        /** `header-glass`, dark. */
        const val headerGlassAlphaDark = 0.78f

        /**
         * `card-glass`, light — the frosted caption of a home card: `surface-container`
         * (white) at 80 % over a blurred copy of the card's own image, so the caption is
         * tinted by the picture it describes instead of sitting in a white box under it. At
         * 72 % a dark picture pulled the glass to a muddy grey; 80 % keeps it frosted white.
         * The blur averages the image, so the worst ground is a uniformly black one: there
         * `on-surface` holds 10.6:1 and `on-surface-variant` 5.9:1.
         */
        const val cardGlassAlphaLight = 0.80f

        /**
         * `card-glass`, dark — `surface-container` at 90 %, the `glass-sheet` value. Light
         * pictures do reach dark mode (the Animation card's stage is a light grey): at 85 %
         * its caption measured 4.4:1, under AA. At 90 % the worst ground, a uniformly white
         * image, holds `on-surface-variant` at 4.56:1 and `on-surface` at 9.6:1.
         */
        const val cardGlassAlphaDark = 0.90f
    }

    /** Home screen geometry (design spec §2) — `home-*` tokens. */
    object Home {
        val headerHeight = 56.dp
        val markSize = 24.dp
        /** Gap between the header's leading glyph (mark or back arrow) and its title. */
        val markGap = 10.dp
        val searchFieldHeight = 48.dp
        val contentPadding = 20.dp
        val gridGutter = 12.dp
        val heroHeight = 320.dp
        val heroHeightExpanded = 400.dp
        val heroPadding = 24.dp
        val heroSubtitleMaxWidth = 260.dp
        val heroPillHeight = 44.dp
        val heroPillPaddingHorizontal = 18.dp
        val heroTopGap = 8.dp
        val chipRowTopGap = 28.dp
        val chipRowHeight = 40.dp
        val chipGap = 8.dp
        val chipPaddingHorizontal = 16.dp
        val gridTopGap = 20.dp
        val gridBottomInset = 32.dp
        /**
         * Space above a catalogue section header (#2239) — `space-lg`. Enough that the
         * header reads as belonging to the group under it rather than to the one above.
         */
        val sectionHeaderTopGap = 24.dp
        /** Space between a section header and its group of rows. */
        val sectionHeaderBottomGap = 12.dp
        val cardRadius = 20.dp
        val cardTextPaddingTop = 12.dp
        val cardTextPaddingHorizontal = 14.dp
        val cardTextPaddingBottom = 14.dp
        val cardOutlineWidth = 1.dp
        val iconTileGlyph = 40.dp
        /** Width from which the hero grows to [heroHeightExpanded]. */
        const val expandedWidthDp = 600
        const val heroScrimStart = 0.5f

        /**
         * How far the home stage runs past the bottom of the featured band before it
         * fades into `surface` (#3948) — the sky ends on a gradient, not on a card edge.
         */
        val heroStageBleed = 48.dp

        /** Where [HomeColor.heroSkyHorizon] sits in the stage, as a fraction of its height. */
        const val heroSkyHorizon = 0.44f

        /**
         * `card-media-aspect` — a catalogue card's picture is square, not 5:4: the caption
         * no longer sits in a box of its own under it, so the picture takes the room.
         */
        const val cardMediaAspect = 1f

        /** `card-glass-blur` — how far a card's own image is blurred under its caption. */
        val cardGlassBlur = 28.dp

        /**
         * `card-glass-melt` — the band over which a card's sharp picture dissolves into its
         * frosted caption. There is no line between the two: the image turns into the glass.
         */
        val cardGlassMelt = 28.dp


        // ── Home list (`home-row-*`, `home-banner-*` in DESIGN.md) ────────────
        // Under the 3D header the Home is a list of pictures: each row is its demo's
        // capture, edge to edge, dissolving into the capture's own colour
        // (`home-row-ambient`), one vertical scroll, no carousel.

        /** `home-row-height` — the least height of a row; it grows with its text. */
        val rowHeight = 116.dp

        /** `home-row-radius` — every row's four corners (`card-radius`). */
        val rowRadius = 20.dp

        /** `home-row-gap` — page between two rows. */
        val rowGap = 10.dp

        /** `home-row-media` — the share of the row's width its picture fills, top to bottom. */
        const val rowMediaFraction = 0.5f

        /**
         * `home-row-dissolve` — where, across the picture, it starts dissolving into the
         * row's tint; it is gone at its trailing edge.
         */
        const val rowDissolveStart = 0.42f

        /**
         * Where the text starts, as a share of the row's width: inside the dissolve, where
         * the picture is down to about a tenth, so the two overlap without the picture
         * reaching the letters.
         */
        const val rowTextStartFraction = 0.44f

        /** Row text insets: `space-md` at the trailing edge, 14 dp above and below. */
        val rowTextPaddingEnd = 16.dp
        val rowTextPaddingVertical = 14.dp

        /** Title-to-subtitle gap inside a row; also the badge line to the title. */
        val rowTextGap = 4.dp

        /**
         * `home-badge` — the "New" / "Updated" / status pill, on its own line above a
         * row's title: `space-sm` across, 3 dp above and below, a 12 dp glyph.
         */
        val badgePaddingVertical = 3.dp
        val badgeIcon = 12.dp

        /** Glyph of a row that has no picture (a demo without a capture, a utility row). */
        val rowGlyph = 40.dp

        /** `home-row-min-width` — from two of these across, the list goes multi-column. */
        val rowMinWidth = 340.dp

        /** Gap between two groups that have no section header between them. */
        val groupGap = 16.dp

        /** `home-banner-aspect` — a Featured row's picture, across the row's width. */
        const val bannerAspect = 2f

        /** `home-banner-dissolve` — where, down the picture, it starts dissolving. */
        const val bannerDissolveStart = 0.55f

        /** How far a banner's caption is pulled up into its picture's dissolve. */
        val bannerCaptionOverlap = 28.dp

        /** A banner caption's side insets. */
        val bannerTextPaddingHorizontal = 16.dp

        /** `header-glass-blur` — backdrop blur of the sticky header over the scrolled list. */
        val headerGlassBlur = 24.dp
    }

    /**
     * About screen geometry (`about-*` in `DESIGN.md`).
     *
     * The mark is the launcher icon, so its size is a product decision, not a spacing
     * one. 80 dp: big enough to be read as identity, small enough that the dark tile
     * does not become a hole punched in a light page — and it keeps the support card
     * on the first screenful of a 411 x 891 dp phone, which is the point of #3565.
     */
    object About {
        /** `ic_sceneview_hero` tile — the launcher icon at identity size. */
        val markSize = 80.dp
        /** Leading glyph of an action row. Smaller than the mark by an order. */
        val rowIcon = 20.dp
        /** Trailing affordance (open-in-new / chevron). */
        val rowAffordance = 16.dp
        /** Inset of a row divider, so it starts under the label, not under the icon. */
        val dividerInset = 48.dp
    }

    /** `DESIGN.md` — Spring motion: `spring(dampingRatio 0.85, stiffness 450)`, one spring for press, sheets, dock. */
    object Spring {
        const val dampingRatio = 0.85f
        const val stiffness = 450f
        const val pressScale = 0.98f
    }

    /**
     * `DESIGN.md` — AR coaching overlay colours (`ar-scrim`, `on-ar-scrim`, …).
     *
     * These do **not** flip with the app theme the way a surface token does: the
     * ground behind an AR overlay is an arbitrary camera frame, so the pill stays
     * a dark scrim with white text in both themes, and only its opacity moves
     * (light mode is used outdoors more often, where the frame is brightest).
     * The accents are the dark-scheme values of the Material roles for the same
     * reason — they are read on black, never on `surface`.
     */
    object ArOverlay {
        val scrimLight = Color(0xF0000000)
        val scrimDark = Color(0xE0000000)
        val onScrim = Color(0xFFFFFFFF)

        /**
         * `on-ar-scrim-dim` — secondary text on an AR overlay (labels, captions, the
         * body of an explanation card). White at 72 % in both themes, like [onScrim],
         * because the ground is a camera frame and not a `surface`.
         */
        val onScrimMuted = Color(0xB8FFFFFF)

        /**
         * Unlit track of a meter drawn on the scrim — the room-mapping segments of the
         * Cloud Anchor demo. `Button glass` from `DESIGN.md`: white at 8 %, the same
         * fill every other over-media element uses for "present but empty".
         */
        val meterTrack = Color(0x14FFFFFF)

        /** Transient work in progress — spinner accent. `primary` (dark value). */
        val accentProgress = Color(0xFFA4C1FF)

        /**
         * Foreground on a control *filled* with [accentProgress] — the dark-scheme
         * `onPrimary`, in both themes (#3726): the dock's accent disc, the Physics "Drop"
         * button. 7.3:1 on [accentProgress]. A filled over-media control that reached for
         * `colorScheme.primary` / `onPrimary` instead changed tint with the system theme
         * while the chrome around it stayed fixed.
         */
        val onAccentProgress = Color(0xFF002F64)

        /** Waiting on the user to move the phone — `warning`. */
        val accentGuidance = Color(0xFFF59E0B)

        /**
         * A step just finished and needs no more of the user's effort — `success`
         * ([#3834](https://github.com/sceneview/sceneview/issues/3834)). Distinct from
         * [accentProgress]: "Well mapped" is not merely further along than "Good enough",
         * it is the state that unblocks Host, and it read as identical to every other
         * lavender bar until this was added.
         */
        val accentSuccess = Color(0xFF16A34A)

        /** Broken until something changes — dark-scheme `error`. */
        val accentBlocked = Color(0xFFFFB4AB)

        /**
         * A capture is in progress — `danger`, the camera-app convention for "recording"
         * (#3831): the live dot of the AR Recording card and the shutter disc. The only red
         * drawn over the camera, and there it never means an error.
         */
        val accentRecord = Color(0xFFEA4335)

        /** Widest a coaching pill may grow — a long line stays one readable column. */
        val maxWidth = 480.dp
    }

    /**
     * `DESIGN.md` — the over-media **mode pill** (`mode-pill-*`): Cosmos's "Starlight |
     * Spacetime" switch, stacked over the dock in the scaffold's `bottomOverlay` band.
     *
     * Opaque and theme-independent, like [ArOverlay]: it sits on a scene that goes from a
     * black sky to a lit grey sheet, so no glass fill reads on every ground. The container
     * (L 0.0135) clears 3:1 against any ground of luminance ≥ 0.14, and the outline (L 0.64)
     * against any ground ≤ 0.18 — together they cover every ground. White label on the
     * container 16.5:1; the selected segment's label 19.2:1.
     */
    object ModePill {
        val container = Color(0xFF1A1F28)
        val outline = Color(0xFFD1D2D4)
        val onContainer = Color(0xFFFFFFFF)
        val selectedContainer = Color(0xFFFFFFFF)
        val onSelected = Color(0xFF0B0F16)

        /** The outline's width: one hairline, opaque. */
        val outlineWidth = 1.dp
    }

    /**
     * `DESIGN.md` — AR Debug View: the in-app 3D view of the Rerun demo (#3950).
     *
     * Two palettes, one per ground (#4080). [Dark] is drawn on [Stage.background] — the live
     * camera's picture-in-picture, and the replay in dark theme. [Light] is drawn on the light
     * stage the replay takes in light theme (`surface-dim`, see [StageChrome.Light]). Every
     * colour is an existing palette value — the brand ramp for the trail, `warning` for what the
     * camera sees right now, `success` for anchors, the axis convention X red / Y green / Z blue.
     * The `glow` factors multiply a colour past 1.0 in linear light so bloom picks it up: only the
     * few "live" elements glow, so the eye lands on the present. On the light ground nothing
     * glows — bloom only brightens, and a haze on a light stage reads as a smudge — so the
     * present is carried by saturation instead.
     */
    object DebugView {
        /** On the dark stage. */
        val Dark = DebugPalette(
            // Trail, oldest → newest: `accent-deep` → `tint-soft` → `tint-light`.
            trailOld = Color(0xFF5A32A3),
            trailMid = Color(0xFFD2A8FF),
            trailNew = Color(0xFFA4C1FF),
            // Glows are held low (#4306): the room is the result, the tracking aids stand behind it.
            trailHeadGlow = 2.0f,
            // The live camera frustum, and the fainter history frusta left every 60 cm.
            frustum = Color(0xFFA4C1FF),
            frustumGlow = 1.4f,
            frustumFace = Color(0x24A4C1FF),
            keyframe = Color(0x40A4C1FF),
            // Map points: everything seen so far, dim white — the room emerges as a cloud.
            mapPoint = Color(0x8CFFFFFF),
            // Live points: what the camera sees this second — `warning`, glowing.
            livePoint = Color(0xFFF59E0B),
            livePointGlow = 1.4f,
            // Planes: translucent fill + solid outline, by orientation.
            floorFill = Color(0x29A4C1FF),
            floorOutline = Color(0xD9A4C1FF),
            wallFill = Color(0x24D2A8FF),
            wallOutline = Color(0xCCD2A8FF),
            otherFill = Color(0x1FFFFFFF),
            otherOutline = Color(0xB3FFFFFF),
            // Anchors — `success`: something the user placed and that holds.
            anchor = Color(0xFF16A34A),
            anchorGlow = 1.6f,
            // Floor grid: 0.5 m minor, 1 m major, white at 7 % / 14 %.
            gridMinor = Color(0x12FFFFFF),
            gridMajor = Color(0x24FFFFFF),
            // Origin gizmo: X `danger`, Y `success`, Z `primary` (dark value).
            axisX = Color(0xFFEA4335),
            axisY = Color(0xFF16A34A),
            axisZ = Color(0xFFA4C1FF),
        )

        /**
         * On the light stage: the same meanings in the light scheme's values. The trail runs
         * `tint-soft` → `tertiary` → `primary` (the oldest is now the palest, as it is the
         * dimmest on the dark stage); the frustum and the floor planes take `primary`, the walls
         * `tertiary`; history points are `on-surface` at 55 %; the grid is `on-surface` at
         * 8 % / 16 %.
         */
        val Light = DebugPalette(
            trailOld = Color(0xFFD2A8FF),
            trailMid = Color(0xFF6446CD),
            trailNew = Color(0xFF005BC1),
            trailHeadGlow = 1f,
            frustum = Color(0xFF005BC1),
            frustumGlow = 1f,
            frustumFace = Color(0x1F005BC1),
            keyframe = Color(0x4D005BC1),
            mapPoint = Color(0x8C1A1A2E),
            livePoint = Color(0xFFF59E0B),
            livePointGlow = 1f,
            floorFill = Color(0x1F005BC1),
            floorOutline = Color(0xD9005BC1),
            wallFill = Color(0x1F6446CD),
            wallOutline = Color(0xCC6446CD),
            otherFill = Color(0x141A1A2E),
            otherOutline = Color(0x991A1A2E),
            anchor = Color(0xFF16A34A),
            anchorGlow = 1f,
            gridMinor = Color(0x141A1A2E),
            gridMajor = Color(0x291A1A2E),
            axisX = Color(0xFFEA4335),
            axisY = Color(0xFF16A34A),
            axisZ = Color(0xFF005BC1),
        )

        /** Picture-in-picture over the camera: portrait 3:4, like the phone it shows. */
        val pipWidth = 128.dp
        val pipHeight = 170.dp

        /**
         * A phone on its side (#4306): under [compactStageHeight] the replay's stacked cards leave
         * the room no height, so the figures and the timeline stand on either side of it,
         * [compactCardWidth] wide each — as narrow as the four figures allow. Over the camera the
         * picture-in-picture shrinks to fit under the timeline, the same 3:4.
         */
        val compactStageHeight = 500.dp
        val compactCardWidth = 280.dp
        val compactPipWidth = 96.dp
        val compactPipHeight = 128.dp
    }

    /** `DESIGN.md` — Spacing scale (`space-*`). */
    object Space {
        val xs = 4.dp
        val sm = 8.dp
        val md = 16.dp
        val lg = 24.dp
        val xl = 32.dp
        val x2l = 48.dp
        val x3l = 64.dp
        val x4l = 96.dp
    }

    /** `DESIGN.md` — Corner radius scale (`radius-*`). */
    object Radius {
        val xs = 8.dp
        val sm = 12.dp
        val md = 16.dp
        val lg = 24.dp
        val xl = 28.dp
        val full = 9999.dp
    }

    /**
     * `DESIGN.md` — Shadows (`shadow-*`), as Compose elevation. A CSS multi-layer
     * shadow has no exact Compose twin; these are the `shadowElevation` values
     * whose blur radius matches the spec's dominant layer (sm 3px, md 12px, lg 40px).
     */
    object Elevation {
        val sm = 1.dp
        val md = 4.dp
        val lg = 12.dp
    }

    /** `DESIGN.md` — Motion durations (`duration-*`). */
    object Duration {
        const val shortMillis = 200
        const val mediumMillis = 350
        const val longMillis = 700
        /** Design spec §6 — the one fade (`tween(300, FastOutSlowIn)`). */
        const val fadeMillis = 300
        /**
         * `motion-handover` — the loading cover giving way to the first rendered
         * frame (#4160). Short on purpose: the scene is already there, so every
         * extra millisecond of fade is the user waiting on a veil, not on work.
         */
        const val handoverMillis = 150
    }

    /**
     * Demo-chrome motion (`final-spec.md` §6): one spring, one fade.
     *
     * The spring drives dock show/hide and press scale; the fade drives every
     * opacity change in the chrome (chrome toggle, menu, preview crossfade uses
     * [Duration.mediumMillis]). Nothing else animates.
     */
    object Motion {
        const val springDampingRatio = 0.85f
        const val springStiffness = 450f
        const val fadeMillis = 300
        /** Press scale on dock items and glass buttons. */
        const val pressScale = 0.97f

        fun <T> spring(): SpringSpec<T> = spring(
            dampingRatio = springDampingRatio,
            stiffness = springStiffness,
        )

        fun <T> fade(): TweenSpec<T> = tween(
            durationMillis = fadeMillis,
            easing = FastOutSlowInEasing,
        )
    }

    /** `DESIGN.md` — Easing curves (`ease-*`). */
    object Ease {
        val spring = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
        val expressive = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }

    /** `DESIGN.md` — Layout constants (`container-padding`, `nav-height`). */
    object Layout {
        val containerPaddingDesktop = 24.dp
        val containerPaddingMobile = 16.dp
        val navigationHeight = 64.dp
        /** Minimum touch target (M3 / WCAG). */
        val touchTarget = 48.dp
        /** Height of the demo dock (`HorizontalFloatingToolbar`). */
        val dockHeight = 64.dp
        /** Dock items are [touchTarget] square; their icons are this size. */
        val dockIconSize = 22.dp
        /**
         * `dock-accent` — visual diameter of the dock's filled accent disc. Its touch
         * target stays [touchTarget]. 40 dp inside the 64 dp dock leaves **12 dp on
         * every side** — the same air as the first labelled item has from the leading
         * end (8 dp toolbar padding + the item's 4 dp inset). At 48 dp the disc sat
         * 8 dp from the rounded end and read as touching it (#3835).
         */
        val dockAccentSize = 40.dp
        val viewerEnvironmentTile = 72.dp
        val viewerAnimationButton = 48.dp
        val selectedOutlineWidth = 2.dp
        /**
         * The 1 dp `outlineVariant` hairline that separates a container from the
         * container behind it, where the tonal step alone cannot: nesting two deep
         * (page → sheet → tile) leaves 1.17:1 at the dark end, short of the 1.25:1 a
         * fill needs to read. See the ramp note in `Color.kt`.
         */
        val hairlineWidth = 1.dp
        val heroStageHeight = 360.dp
        const val mediaAspect = 1.25f
    }
}

import SwiftUI

/// SceneView iOS Theme — Apple HIG + Liquid Glass
///
/// Brand colors from the SceneView M3 design system (see DESIGN.md, source: #005bc1).
/// Uses SwiftUI native patterns — no Material Design concepts.
/// Liquid Glass effects for floating surfaces (iOS 26+).
enum SceneViewTheme {

    // MARK: - Brand Colors

    /// Primary brand blue — light: #005BC1, dark: #A4C1FF
    static let primary = Color("AccentColor")

    /// Tertiary accent — light: #6446CD, dark: #D2A8FF
    static let tertiary = Color(light: .init(red: 0.392, green: 0.275, blue: 0.804),
                                 dark: .init(red: 0.824, green: 0.659, blue: 1.0))

    // MARK: - Status Colors

    static let statusStable = Color.green
    static let statusBeta = Color.blue
    static let statusAlpha = Color.purple
    static let statusPlanned = Color.gray

    // MARK: - Semantic Colors

    /// Surface for elevated cards/sheets — `systemBackground` on iOS,
    /// `windowBackgroundColor` on macOS.
    static let surfaceElevated = Color.systemBackground

    /// Secondary surface (grouped backgrounds) — `secondarySystemBackground`
    /// on iOS, `underPageBackgroundColor` on macOS.
    static let surfaceGrouped = Color.secondarySystemBackground

    // MARK: - Typography

    /// Hero title style
    static func heroTitle(_ text: Text) -> some View {
        text
            .font(.system(size: 34, weight: .bold, design: .default))
            .foregroundStyle(.primary)
    }

    /// Section title style
    static func sectionTitle(_ text: Text) -> some View {
        text
            .font(.title2.bold())
            .foregroundStyle(.primary)
    }

    /// Caption style
    static func caption(_ text: Text) -> some View {
        text
            .font(.caption)
            .foregroundStyle(.secondary)
    }

    // MARK: - Shape Constants

    /// Card corner radius
    static let cardRadius: CGFloat = 16

    /// Button corner radius
    static let buttonRadius: CGFloat = 12

    /// Chip / badge corner radius
    static let chipRadius: CGFloat = 8

    // MARK: - Spacing

    static let spacingXS: CGFloat = 4
    static let spacingSM: CGFloat = 8
    static let spacingMD: CGFloat = 16
    static let spacingLG: CGFloat = 24
    static let spacingXL: CGFloat = 32
    static let spacing2XL: CGFloat = 48
}

// MARK: - Design tokens (DESIGN.md) — the SwiftUI twin of Android's `SceneViewTokens.kt`

/// SwiftUI translation of the `DESIGN.md` tokens the demo app's own chrome uses.
///
/// Names mirror the token names in `DESIGN.md` and in Android's
/// `SceneViewTokens.kt` one-for-one — `Space.md` is `space-md` — so a reader
/// can move between the spec, the Android code and this file without a lookup
/// table. Never hardcode a colour or a size in demo UI: add the token here.
enum SceneViewTokens {
    /// Model-viewer stage, deliberately identical in light and dark themes (`#0B0F16`).
    enum Stage {
        static let background = Color(red: 0x0B / 255, green: 0x0F / 255, blue: 0x16 / 255)

        /// Widest the HD poster draws, in points. Its render is 600 px wide:
        /// on a phone the stage width (~350 pt) already stays under this, and
        /// on an iPad it keeps the upscale near the phone's instead of
        /// stretching the render across a 1,000-point stage (3.4x).
        static let posterMaxWidth: CGFloat = 400

        /// Fills for primitives that must stay apart on the stage — Android's
        /// `SceneViewColors.Ramp4`: `primary` light #005BC1, `gradient-hero` end
        /// #6446CD, `primary` dark #A4C1FF, `tertiary` dark #D2A8FF. Fixed in both
        /// themes, like the stage they sit on.
        static let shapeRamp: [UIColor] = [
            UIColor(red: 0x00 / 255, green: 0x5B / 255, blue: 0xC1 / 255, alpha: 1),
            UIColor(red: 0x64 / 255, green: 0x46 / 255, blue: 0xCD / 255, alpha: 1),
            UIColor(red: 0xA4 / 255, green: 0xC1 / 255, blue: 0xFF / 255, alpha: 1),
            UIColor(red: 0xD2 / 255, green: 0xA8 / 255, blue: 0xFF / 255, alpha: 1),
        ]

        /// A picked primitive — `DESIGN.md` `info` #EA580C, the "informational
        /// highlight" status colour. Warm and outside `shapeRamp`, so a pick
        /// reads at a glance against the blue and violet fills.
        static let shapePicked = UIColor(red: 0xEA / 255, green: 0x58 / 255, blue: 0x0C / 255, alpha: 1)

        /// Stage sky behind a physical set (Rolling Balls, #4083) — Android's
        /// `themedStageSky()` (#4089), drawn as a gradient because the tray's
        /// camera looks down: the top of the frame is the horizon
        /// (`surface-container`, #FFFFFF / #232A39), the bottom the zenith ground
        /// (#F1F3F5 / `stage-background` #0B0F16). Never a black void.
        static let skyHorizon = Color(
            light: .white,
            dark: Color(red: 0x23 / 255, green: 0x2A / 255, blue: 0x39 / 255)
        )
        static let skyGround = Color(
            light: Color(red: 0xF1 / 255, green: 0xF3 / 255, blue: 0xF5 / 255),
            dark: Color(red: 0x0B / 255, green: 0x0F / 255, blue: 0x16 / 255)
        )

        /// The Rolling Balls tray floor — Android's `StageSky.floor`:
        /// `surface-container-highest` light (#E9ECEF), `surface-dim` dark
        /// (#161B22). RealityKit does not resolve dynamic colours, so the demo
        /// picks one per colour scheme.
        static func trayFloor(dark: Bool) -> UIColor {
            dark ? UIColor(red: 0x16 / 255, green: 0x1B / 255, blue: 0x22 / 255, alpha: 1)
                 : UIColor(red: 0xE9 / 255, green: 0xEC / 255, blue: 0xEF / 255, alpha: 1)
        }

        /// The Rolling Balls tray rails — `accent-deep` (#5A32A3), Android's
        /// `SceneViewColors.AccentDeep`.
        static let trayRail = UIColor(red: 0x5A / 255, green: 0x32 / 255, blue: 0xA3 / 255, alpha: 1)

        /// The Lighting stage floor — Android's `LightingStage.FLOOR_COLOR` (#2A3346): a
        /// blue-grey slate dark enough for a contact shadow, light enough to catch the key.
        /// Fixed in both themes, like the stage.
        static let lightingFloor = UIColor(red: 0x2A / 255, green: 0x33 / 255, blue: 0x46 / 255, alpha: 1)
    }

    /// `DESIGN.md` — Spatial Gallery overlay colours.
    enum SpatialGalleryColor {
        static let stageScrimStart = Color.clear
        static let stageScrimEnd = Color.black.opacity(0.90)
        /// `glass-surface`, dark value — the source pill on the Explore hero. It sits on the
        /// media scrim, never on a page, so it keeps the dark value in both themes (Android's
        /// `SpatialGalleryColor.glassSurfaceDark`).
        static let glassSurfaceDark = Color.white.opacity(0.05)
    }

    /// `DESIGN.md` — Liquid Glass, the "button glass" row, as the demo chrome uses it.
    ///
    /// Theme-independent on purpose: the chrome floats over a live RealityKit /
    /// ARKit viewport, which is media, not a themed surface. Same 1 pt 24 % white
    /// border as Android; the fill stays at 8 % because iOS puts `.ultraThinMaterial`
    /// over it — a RealityKit view *can* be sampled.
    enum Glass {
        /// `glass-surface` over media — white at 8 %.
        static let surface = Color.white.opacity(0.08)
        /// `glass-ceiling` — `#2A2B2C` at 60 %, above the material, dark scheme only.
        ///
        /// The fill above is a *floor*: it keeps glass visible over black. Over
        /// bright media the dark material resolves to the same grey as the
        /// scrimmed ground behind it (measured 1.01:1 over the studio
        /// backdrop, 2026-09-18) and the control loses its shape again, from
        /// the other side. The ceiling pulls the container back to the tone it
        /// already has over a dark stage, so it holds ≥ 1.25:1 against both.
        static let ceiling = Color(red: 42 / 255, green: 43 / 255, blue: 44 / 255).opacity(0.6)
        /// `glass-border` — white at 24 %, 1 pt: the Android value.
        ///
        /// 8 % was not perceptible over a dark viewport; 12 % measured 1.47:1
        /// against the ground (2026-09-18), short of the 3:1 an edge needs to
        /// count as the component's boundary. Over a mid-grey ground the fill
        /// matches the ground and the edge is all that is left of the control.
        static let border = Color.white.opacity(0.24)
        static let borderWidth: CGFloat = 1
        /// Foreground on glass over media: always white.
        static let onGlass = Color.white
        /// Secondary foreground on glass — white at 72 %.
        static let onGlassMuted = Color.white.opacity(0.72)
        /// Disabled foreground on glass.
        static let onGlassDisabled = Color.white.opacity(0.38)
        /// Glass icon button visual diameter. The touch target is `Layout.touchTarget`.
        static let iconButtonSize: CGFloat = 44
        /// Identity pill height.
        static let pillHeight: CGFloat = 36
        /// Identity pill horizontal content padding.
        static let pillPaddingHorizontal: CGFloat = 14
    }

    /// App type scale — the only five text styles the demo chrome uses.
    /// `-0.02em` tracking on display/title; `type-display 32/700`,
    /// `type-title 22/600`, `type-card 17/600`, `type-body 15/400`,
    /// `type-caption 13/500`.
    enum TypeScale {
        /// Default-size point sizes, for text that scales them with Dynamic
        /// Type (`@ScaledMetric`) — the home card captions.
        static let titleSize: CGFloat = 22
        static let cardSize: CGFloat = 17
        static let bodySize: CGFloat = 15
        static let captionSize: CGFloat = 13
        static let trackingTight: CGFloat = -0.02

        static let display = Font.system(size: 32, weight: .bold)
        static let displayTracking: CGFloat = -0.02 * 32
        static let title = Font.system(size: titleSize, weight: .semibold)
        static let titleTracking: CGFloat = trackingTight * titleSize
        static let card = Font.system(size: cardSize, weight: .semibold)
        static let body = Font.system(size: bodySize, weight: .regular)
        static let bodyMedium = Font.system(size: 15, weight: .medium)
        static let bodySemibold = Font.system(size: 15, weight: .semibold)
        static let caption = Font.system(size: 13, weight: .medium)
        static let captionRegular = Font.system(size: 13, weight: .regular)
        static let captionSemibold = Font.system(size: 13, weight: .semibold)
        /// Chrome text. Same 13 pt as `type-caption` at the default size, but a
        /// text *style*, so it follows Dynamic Type; `DemoScaffold` caps the
        /// chrome at XXL and lets the controls sheet scale freely.
        static let chromeLabel = Font.footnote.weight(.semibold)
        static let chromeCaption = Font.footnote.weight(.medium)
        /// Dock captions: one word under a 22 pt icon, six across on a 402 pt
        /// screen — the tab-bar size. Scales with Dynamic Type; when the row no
        /// longer fits, the dock drops to icons and keeps the spoken labels.
        static let chromeDockCaption = Font.caption2.weight(.medium)
    }

    /// Home screen colours that are NOT system roles (`DESIGN.md` "Demo App Home").
    ///
    /// The hero is an image card that stays dark in both themes, so its text and
    /// pill are fixed. Chips follow the `DESIGN.md` surface ramp, which is why
    /// they carry explicit light/dark pairs.
    enum HomeColor {
        static let heroTitle = Color.white
        static let heroSubtitle = Color.white.opacity(0.80)
        static let heroPillBackground = Color.white
        static let heroPillText = Color(red: 0x1A / 255, green: 0x1A / 255, blue: 0x2E / 255)
        /// `DESIGN.md` `hero-sky-*`: the dusk gradient painted behind the
        /// transparent live flight on the home stage (#3948). One gradient in
        /// both themes — the hero stays dark. The horizon stop is also the
        /// flight's fog colour, so the far ridges dissolve into it.
        static let heroSkyTop = Color(red: 0x0B / 255, green: 0x0F / 255, blue: 0x16 / 255)
        static let heroSkyDusk = Color(red: 0x3B / 255, green: 0x1D / 255, blue: 0x46 / 255)
        static let heroSkyHorizon = Color(red: 0xE2 / 255, green: 0x73 / 255, blue: 0x4F / 255)
        static let heroSkyGround = Color(red: 0x2A / 255, green: 0x12 / 255, blue: 0x20 / 255)
        /// Hero stage field — an **embedded** stage, so it follows the
        /// container scale in dark rather than the full-screen stage colour.
        ///
        /// It used to be `Stage.background` (#0B0F16) in both themes. On a
        /// white page that reads at 18.71:1 and anchors the whole screen; on
        /// the #0D1117 dark page it reads at **1.014:1** — the card had no
        /// background at all, only its 1 pt hairline. A full-screen stage has
        /// no page around it and keeps #0B0F16 (see `Stage.background`).
        static let heroField = Color(
            light: Color(red: 0x0B / 255, green: 0x0F / 255, blue: 0x16 / 255),
            dark: Color(red: 0x22 / 255, green: 0x29 / 255, blue: 0x3E / 255)
        )

        /// `chip-bg` = `surface-dim` — #F1F3F5 / #222831.
        ///
        /// Dark was #161B22, which sits at 1.09:1 on `surface` — a container
        /// whose background simply is not there. Raised to clear 1.25:1, the
        /// floor below which a filled container reads as bare page.
        static let chipBackground = Color(
            light: Color(red: 0xF1 / 255, green: 0xF3 / 255, blue: 0xF5 / 255),
            dark: Color(red: 0x22 / 255, green: 0x28 / 255, blue: 0x31 / 255)
        )
        /// `chip-text` = `on-surface-dim` — #3D4654 / #A4ABB7.
        ///
        /// Dark was #9CA3AF, the value `DESIGN.md` retired with the surface-ramp
        /// lift (Android's `onSurfaceVariant` moved with it). Over the dark
        /// `card-glass` composited on a white picture it measured 4.16:1; #A4ABB7
        /// holds 4.55:1 there.
        static let chipText = Color(
            light: Color(red: 0x3D / 255, green: 0x46 / 255, blue: 0x54 / 255),
            dark: Color(red: 0xA4 / 255, green: 0xAB / 255, blue: 0xB7 / 255)
        )
        /// DESIGN.md `chip-selected-bg` in light; `primary` in dark.
        ///
        /// The first dark pass used the #242D41 `primary-light` tint with a
        /// primary outline. Reviewed against the light screen it lost the
        /// "which one is on?" read at a glance — a thin outline on a barely
        /// lifted fill states *selectable*, not *selected*, and the row of
        /// unselected chips sits only a few percent darker. Dark now mirrors
        /// what light already does (a solid, unmissable pill), swapping the
        /// shouting white of the previous build for the brand blue.
        static let chipSelectedBackground = Color(
            light: Color(red: 0x1A / 255, green: 0x1A / 255, blue: 0x2E / 255),
            dark: Color(red: 0xA4 / 255, green: 0xC1 / 255, blue: 0xFF / 255)
        )
        /// DESIGN.md `chip-selected-text` in light; `surface` in dark, the only
        /// value that clears AA on the `primary` pill above.
        static let chipSelectedText = Color(
            light: .white,
            dark: Color(red: 0x0D / 255, green: 0x11 / 255, blue: 0x17 / 255)
        )
        /// DESIGN.md Primary, `primary-light` — the "subtle background" tint,
        /// pre-composited over the surface it sits on: `#005BC1` at 8 % over
        /// white in light, `#A4C1FF` at 10 % over `surface-container` in dark.
        ///
        /// The container for a primary *action* — paired with `primary` for its
        /// label and glyph. Distinct from `chip-selected-bg` on purpose: a
        /// selection and an action must not wear the same skin (they did, and
        /// the promoted "Surprise me" read as a selected card in dark and as an
        /// unreadable navy slab in light, #3585).
        static let primaryContainer = Color(
            light: Color(red: 0xEB / 255, green: 0xF0 / 255, blue: 0xF8 / 255),
            dark: Color(red: 0x24 / 255, green: 0x2D / 255, blue: 0x41 / 255)
        )
        /// `on-surface` — #1A1A2E / #F3F4F6.
        static let onSurface = Color(
            light: Color(red: 0x1A / 255, green: 0x1A / 255, blue: 0x2E / 255),
            dark: Color(red: 0xF3 / 255, green: 0xF4 / 255, blue: 0xF6 / 255)
        )
        /// `on-surface-dim` — #3D4654 / #9CA3AF.
        static let onSurfaceDim = chipText
        /// DESIGN.md Text, `on-surface-faint` — #5C6370 / #6B7280.
        /// Reserved for decorative tertiary glyphs, never focus or body copy.
        static let onSurfaceFaint = Color(
            light: Color(red: 0x5C / 255, green: 0x63 / 255, blue: 0x70 / 255),
            dark: Color(red: 0x6B / 255, green: 0x72 / 255, blue: 0x80 / 255)
        )
        /// Placeholder text in a field — `on-surface-faint` in light (#5C6370),
        /// `on-surface-dim` in dark (#9CA3AF; the faint dark grey is 2.9:1 on a
        /// field). The system placeholder measured 1.7:1 on the light field and
        /// 2.4:1 on the dark one; this pair measures 6.05:1 and 5.69:1.
        static let placeholder = Color(
            light: Color(red: 0x5C / 255, green: 0x63 / 255, blue: 0x70 / 255),
            dark: Color(red: 0x9C / 255, green: 0xA3 / 255, blue: 0xAF / 255)
        )
        /// DESIGN.md Primary, `primary` — #005BC1 / #A4C1FF.
        /// Focus and action glyphs use the accent rather than a text grey.
        static let primary = Color(
            light: Color(red: 0x00 / 255, green: 0x5B / 255, blue: 0xC1 / 255),
            dark: Color(red: 0xA4 / 255, green: 0xC1 / 255, blue: 0xFF / 255)
        )
        /// `surface-container-high` — #F1F3F5 / #2C3546, "a container on a
        /// container": `home-row-bg`, the grey tile of every home list row.
        static let surfaceContainerHigh = Color(
            light: Color(red: 0xF1 / 255, green: 0xF3 / 255, blue: 0xF5 / 255),
            dark: Color(red: 0x2C / 255, green: 0x35 / 255, blue: 0x46 / 255)
        )
        /// `surface-container-highest` — #E9ECEF / #354056: one step above a
        /// home row, the ground of a row's glyph thumb while it has no capture.
        static let surfaceContainerHighest = Color(
            light: Color(red: 0xE9 / 255, green: 0xEC / 255, blue: 0xEF / 255),
            dark: Color(red: 0x35 / 255, green: 0x40 / 255, blue: 0x56 / 255)
        )
        /// Home-section accents, sampled evenly along `gradient-hero` —
        /// `primary` (#005BC1 / #A4C1FF) to `tertiary` (#6446CD / #D2A8FF) —
        /// verbatim from Android's `DemoCategoryAccent` (samples/common). They
        /// tint the "New" / "Updated" chip of a home card, as on Android.
        static let sectionAccentView3D = primary
        static let sectionAccentCreate = Color(
            light: Color(red: 0x19 / 255, green: 0x56 / 255, blue: 0xC4 / 255),
            dark: Color(red: 0xB0 / 255, green: 0xBB / 255, blue: 0xFF / 255)
        )
        static let sectionAccentPlaceAR = Color(
            light: Color(red: 0x32 / 255, green: 0x50 / 255, blue: 0xC7 / 255),
            dark: Color(red: 0xBB / 255, green: 0xB4 / 255, blue: 0xFF / 255)
        )
        static let sectionAccentUnderstand = Color(
            light: Color(red: 0x4B / 255, green: 0x4B / 255, blue: 0xCA / 255),
            dark: Color(red: 0xC6 / 255, green: 0xAE / 255, blue: 0xFF / 255)
        )
        static let sectionAccentDevTools = Color(
            light: Color(red: 0x64 / 255, green: 0x46 / 255, blue: 0xCD / 255),
            dark: Color(red: 0xD2 / 255, green: 0xA8 / 255, blue: 0xFF / 255)
        )
        /// `on-primary` — text and icons on a `primary` fill: #FFFFFF / #0D1117.
        static let onPrimary = chipSelectedText
        /// M3 `secondary-container` — #D9E3F8 / #3D4758, Android's
        /// `md_theme_*_secondaryContainer`. The About support card, the only
        /// tinted surface of that screen (`DESIGN.md` "Demo App About").
        static let secondaryContainer = Color(
            light: Color(red: 0xD9 / 255, green: 0xE3 / 255, blue: 0xF8 / 255),
            dark: Color(red: 0x3D / 255, green: 0x47 / 255, blue: 0x58 / 255)
        )
        /// M3 `on-secondary-container` — #121C2B / #D9E3F8: 13.3:1 light and
        /// 7.3:1 dark on `secondaryContainer`.
        static let onSecondaryContainer = Color(
            light: Color(red: 0x12 / 255, green: 0x1C / 255, blue: 0x2B / 255),
            dark: Color(red: 0xD9 / 255, green: 0xE3 / 255, blue: 0xF8 / 255)
        )
        /// DESIGN.md Status, `danger` — #EA4335 in both themes. A glyph colour
        /// (3.5:1 on `chip-bg` light, 3.8:1 dark), never body text.
        static let danger = Color(red: 0xEA / 255, green: 0x43 / 255, blue: 0x35 / 255)
        /// DESIGN.md Borders, `outline` — #D6DAE0 / #2A3346.
        /// The Cards row specifies this 1 pt contour for elevated surfaces.
        static let outline = Color(
            light: Color(red: 0xD6 / 255, green: 0xDA / 255, blue: 0xE0 / 255),
            dark: Color(red: 0x2A / 255, green: 0x33 / 255, blue: 0x46 / 255)
        )
        /// DESIGN.md Borders, `outline` as it reads today — #D6DAE0 / #8B95A6:
        /// the boundary that identifies a control (WCAG 1.4.11). `outline`
        /// above still carries the previous dark value (#2A3346, 1.5:1 on the
        /// page) because the home cards draw their contour with it; a field at
        /// rest takes this one, or it is invisible until focused.
        static let controlOutline = Color(
            light: Color(red: 0xD6 / 255, green: 0xDA / 255, blue: 0xE0 / 255),
            dark: Color(red: 0x8B / 255, green: 0x95 / 255, blue: 0xA6 / 255)
        )
        /// `outline-subtle` — #EBEDF0 / #46516A, the 1 pt card + header hairline.
        ///
        /// Dark was #1F2937 — darker than the `surface-container` it is drawn
        /// on (#22293E), so a divider inside a sheet measured 1.04:1. `DESIGN.md`
        /// already carried #46516A; this file had not followed.
        static let outlineSubtle = Color(
            light: Color(red: 0xEB / 255, green: 0xED / 255, blue: 0xF0 / 255),
            dark: Color(red: 0x46 / 255, green: 0x51 / 255, blue: 0x6A / 255)
        )
        /// `surface` — #FFFFFF / #0D1117 (the page ground).
        static let surface = Color(
            light: .white,
            dark: Color(red: 0x0D / 255, green: 0x11 / 255, blue: 0x17 / 255)
        )
        /// DESIGN.md Surfaces, `surface-container` — #FFFFFF / #232A39.
        /// A lighter fill supplies dark elevation without a black shadow.
        ///
        /// Dark was #161C2C: 1.11:1 on `surface`, so every card, tile and row
        /// dissolved into the page and the screen read as one flat sheet.
        /// #232A39 (1.32:1, the DESIGN.md and Android value; iOS briefly
        /// carried #22293E, 1.31:1) clears 1.25:1, the floor at which a
        /// container's background is actually visible. (On a near-black page
        /// the flare term of the WCAG ratio puts that floor at L* >= 15.1 —
        /// nothing darker can reach it, whatever the page is set to.)
        static let surfaceContainer = Color(light: surfaceContainerLight, dark: surfaceContainerDark)
        private static let surfaceContainerLight = Color.white
        private static let surfaceContainerDark = Color(red: 0x23 / 255, green: 0x2A / 255, blue: 0x39 / 255)
        /// Derived from DESIGN.md dark `glass-surface`: 5 % white composited
        /// over `surface-container`, rounded to #2F3549. Kept opaque so artwork
        /// cannot bleed through floating status chips or the search field.
        ///
        /// Tracks `surface-container` upward so a floating element stays one
        /// visible step above the card it sits on (1.56:1 on `surface`).
        static let floatingSurface = Color(
            light: .white,
            dark: Color(red: 0x2F / 255, green: 0x35 / 255, blue: 0x49 / 255)
        )
        /// DESIGN.md `header-overlay`: `surface` at 100 % in both modes. Light
        /// was 0.94; under the full-bleed Featured cards the 6 % see-through
        /// read as an overlap bug behind the wordmark and the status bar.
        static let headerOverlayAlpha: Double = 1

        /// `card-glass` — the frosted caption of a home card: `surface-container`
        /// at **80 %** in light and **90 %** in dark (the `glass-sheet` value),
        /// laid over a blurred copy of the card's own picture, so each caption is
        /// tinted by what it shows. Android's `cardGlassAlphaLight/Dark` (#4144).
        ///
        /// The blur averages the picture, so the worst ground is a uniform one:
        /// light over black holds `on-surface` 10.6:1 and `on-surface-dim`
        /// 5.9:1; dark over white 9.5:1 and 4.55:1. At 72 % a dark picture turned
        /// the light glass a muddy grey; at 85 % the Animation card's light-grey
        /// stage took the dark caption under AA.
        static let cardGlass = Color(
            light: surfaceContainerLight.opacity(0.80),
            dark: surfaceContainerDark.opacity(0.90)
        )
    }

    /// Home screen geometry (`home-*` tokens).
    enum Home {
        static let headerHeight: CGFloat = 56
        static let markSize: CGFloat = 24
        static let searchFieldHeight: CGFloat = 48
        static let contentPadding: CGFloat = 20
        static let gridGutter: CGFloat = 12
        static let heroHeight: CGFloat = 320
        static let heroHeightExpanded: CGFloat = 400
        static let heroPadding: CGFloat = 24
        static let heroSubtitleMaxWidth: CGFloat = 260
        static let heroPillHeight: CGFloat = 44
        static let heroPillPaddingHorizontal: CGFloat = 18
        static let heroTopGap: CGFloat = 8
        static let chipRowTopGap: CGFloat = 28
        static let chipRowHeight: CGFloat = 40
        static let chipGap: CGFloat = 8
        static let chipPaddingHorizontal: CGFloat = 16
        static let gridTopGap: CGFloat = 20
        /// DESIGN.md `section-header-top-gap` / `section-header-bottom-gap`:
        /// space above a catalogue section header (`space-sm` for the first one,
        /// right under the chips), and from it to its group of rows.
        static let sectionHeaderTopGap: CGFloat = 24
        static let sectionHeaderBottomGap: CGFloat = 12
        static let gridBottomInset: CGFloat = 32
        static let cardRadius: CGFloat = 20
        static let cardTextPaddingTop: CGFloat = 12
        static let cardTextPaddingHorizontal: CGFloat = 14
        static let cardTextPaddingBottom: CGFloat = 14
        static let cardOutlineWidth: CGFloat = 1
        static let iconTileGlyph: CGFloat = 40
        static let heroScrimStart: CGFloat = 0.5
        /// How far the home stage runs past the hero band before it has faded
        /// into the page — Android's `heroStageBleed`.
        static let heroStageBleed: CGFloat = 48
        /// Where the horizon sits down the stage sky, as a fraction of its height.
        static let heroSkyHorizon: CGFloat = 0.44
        /// Where the sun sits across the stage, as a fraction of its width.
        static let heroSunX: CGFloat = 0.31

        /// `card-media-aspect` — a catalogue card's picture is square: the
        /// caption no longer sits in a box of its own under it (#4144).
        static let cardMediaAspect: CGFloat = 1
        /// `card-glass-blur` — 28 pt, the blur of the picture copy under a card
        /// caption. Compose passes it as a `RenderEffect` radius, which Skia turns
        /// into a Gaussian sigma of `0.577 × radius + 0.5`; SwiftUI's `blur(radius:)`
        /// takes the sigma itself, hence ``cardGlassBlurSigma``.
        static let cardGlassBlur: CGFloat = 28
        static let cardGlassBlurSigma: CGFloat = cardGlassBlur * 0.57735 + 0.5
        /// `card-glass-melt` — the band over which the sharp picture dissolves
        /// into the frosted caption (the fade spans twice this, centred on the
        /// caption's top).
        static let cardGlassMelt: CGFloat = 28
        /// Explore's "Try a demo" card — Android's `SAMPLE_CARD_WIDTH`
        /// (`hero-stage-height` 360 less `space-3xl` 64).
        static let sampleCardWidth: CGFloat = 296
        /// Fraction of the band's scroll travel the sky and the flight lag behind.
        static let heroParallax: CGFloat = 0.35

        // Home list (`home-row-*` in DESIGN.md): under the 3D header the Home
        // is a standard grouped list — two-line rows on neutral grey tiles, one
        // vertical scroll, no carousel. Android's `SceneViewTokens.Home.row*`.

        /// `home-row-thumb` — the leading square of a row: the demo's capture
        /// (`radius-sm`) or a glyph.
        static let rowThumb: CGFloat = 56
        /// Glyph inside a `home-row-thumb` that has no capture.
        static let rowThumbGlyph: CGFloat = 28
        /// `home-row-min-height` — the two-line list item with a 56 pt image.
        static let rowMinHeight: CGFloat = 72
        /// Row insets: `space-md` across (also the thumb-to-text gap), `space-sm` down.
        static let rowPaddingHorizontal: CGFloat = 16
        static let rowPaddingVertical: CGFloat = 8
        /// Title-to-subtitle gap inside a row.
        static let rowTextGap: CGFloat = 2
        /// `home-row-gap` — the seam of page between two rows of one group.
        static let rowGap: CGFloat = 2
        /// `home-row-radius-outer` — a group's four outer corners (`radius-md`).
        static let rowRadiusOuter: CGFloat = 16
        /// `home-row-radius-inner` — every corner a row shares with a neighbour.
        static let rowRadiusInner: CGFloat = 4
        /// `home-row-min-width` — from two of these across, the list goes
        /// multi-column (an iPad).
        static let rowMinWidth: CGFloat = 340
        /// `home-group-gap` — between two groups with no section header between
        /// them ("Featured" and "Browse online models").
        static let groupGap: CGFloat = 16
        /// Opacity of `on-surface` over a row while it is pressed — the M3
        /// pressed state layer, Android's ripple. No scale: a row is a list item.
        static let rowPressedAlpha: Double = 0.10
    }

    /// `DESIGN.md` — Demo App About (`about-*`), the iOS twin of Android's
    /// `SceneViewTokens.About`.
    enum About {
        /// `about-mark` — the launcher icon at identity size, clipped to
        /// `radius-xl`. The same picture in light and dark: it is the
        /// product's identity, not a themed surface.
        static let markSize: CGFloat = 80
    }

    /// `DESIGN.md` — Motion: the `ease-expressive` curve, the three durations,
    /// and the two patterns the catalogue uses (scroll reveal, staggered entry).
    ///
    /// Only the catalogue and the home hero animate with these; the chrome
    /// keeps ``Spring``. Everything here degrades to a plain opacity change
    /// under `accessibilityReduceMotion` — the spec's "disable translateY and
    /// scale, keep opacity fades".
    enum Motion {
        /// `ease-expressive` — cubic-bezier(0.2, 0, 0, 1).
        static func expressive(_ duration: Double) -> Animation {
            .timingCurve(0.2, 0, 0, 1, duration: duration)
        }
        /// `duration-short`.
        static let short: Double = 0.2
        /// `duration-medium`.
        static let medium: Double = 0.35
        /// `duration-long`.
        static let long: Double = 0.7

        /// Scroll reveal — `translateY(24px) opacity(0)` → `translateY(0) opacity(1)`
        /// over `duration-long` with `ease-expressive`.
        static let revealOffset: CGFloat = 24
        static var reveal: Animation { expressive(long) }

        /// Staggered catalogue entry: each item starts `staggerStep` after the
        /// one before it, capped at `staggerMaxDelay` so a long grid never
        /// makes the last card wait — the cascade states reading order, it is
        /// not a queue.
        static let staggerStep: Double = 0.045
        static let staggerMaxDelay: Double = 0.32

        /// Home hero turntable, radians per second — a slow drift, well under
        /// the viewer's own orbit, because the hero is a poster and not a demo.
        static let heroOrbitSpeed: Float = 0.14
    }

    /// `DESIGN.md` — one spring: `spring(dampingRatio 0.85, stiffness 450)`.
    /// SwiftUI's `response` form of the same curve is 0.35 s / 0.85.
    enum Spring {
        static let response: Double = 0.35
        static let dampingFraction: Double = 0.85
        static let pressScale: CGFloat = 0.98
        /// Press scale on dock items and glass buttons.
        static let chromePressScale: CGFloat = 0.97
        static var animation: Animation { .spring(response: response, dampingFraction: dampingFraction) }
        /// `motion-fade` — the one fade (300 ms, ease-in-out).
        static var fade: Animation { .easeInOut(duration: 0.3) }
    }

    /// `DESIGN.md` — Spacing scale (`space-*`).
    enum Space {
        static let xs: CGFloat = 4
        static let sm: CGFloat = 8
        static let md: CGFloat = 16
        static let lg: CGFloat = 24
        static let xl: CGFloat = 32
        static let x2l: CGFloat = 48
    }

    /// `DESIGN.md` — Shadows, light mode (dark draws a hairline instead).
    /// Each is two CSS box-shadows; a CSS blur is twice a SwiftUI radius.
    enum Shadow {
        struct Layer {
            let opacity: Double
            let radius: CGFloat
            let y: CGFloat
        }
        /// `shadow-sm` — 0 1px 3px rgba(0,0,0,0.08), 0 1px 2px rgba(0,0,0,0.06).
        static let sm = [Layer(opacity: 0.08, radius: 1.5, y: 1), Layer(opacity: 0.06, radius: 1, y: 1)]
        /// `shadow-md` — 0 4px 12px rgba(0,0,0,0.1), 0 2px 4px rgba(0,0,0,0.06).
        static let md = [Layer(opacity: 0.10, radius: 6, y: 4), Layer(opacity: 0.06, radius: 2, y: 2)]
    }

    /// `DESIGN.md` — Corner radius scale (`radius-*`).
    enum Radius {
        static let xs: CGFloat = 8
        static let sm: CGFloat = 12
        static let md: CGFloat = 16
        static let lg: CGFloat = 24
        static let xl: CGFloat = 28
    }

    /// `DESIGN.md` — Layout constants and the Floating Dock geometry.
    enum Layout {
        /// Minimum touch target.
        static let touchTarget: CGFloat = 48
        /// `dock-height`.
        static let dockHeight: CGFloat = 64
        /// `dock-icon`.
        static let dockIconSize: CGFloat = 22
        /// `dock-items` — at most 4 demo items before Controls and the accent.
        static let dockMaxItems = 4
        static let viewerEnvironmentTile: CGFloat = 72
        static let viewerAnimationButton: CGFloat = 48
        static let selectedOutlineWidth: CGFloat = 2
        /// `media-aspect` — 5:4 home card media.
        static let mediaAspect: CGFloat = 1.25
        /// `hero-stage-height` — the Explore hero stage.
        static let heroStageHeight: CGFloat = 360
        /// Width of the leading-edge strip that listens for the demo host's
        /// swipe-to-dismiss. Narrow on purpose: the rest of the screen belongs
        /// to the scene's own orbit / pan gestures.
        static let edgeSwipeWidth: CGFloat = 20
        /// How far that edge drag must travel before it dismisses.
        static let edgeSwipeDismiss: CGFloat = 64
    }

    /// `DESIGN.md` — "Demo Scaffold (iOS)": where the chrome sits, in points.
    ///
    /// These are the only numbers that place chrome on a demo screen.
    /// `DemoScaffold` applies them once; a demo never pads its own bottom,
    /// never reads a safe-area inset and never picks a horizontal margin.
    enum Chrome {
        /// `chrome-margin` — the one horizontal margin. The back button, the
        /// identity pill, every accessory and the sheet content all start and
        /// end here.
        static let margin: CGFloat = 16
        /// `chrome-top-gap` — top safe-area edge (status bar, Dynamic Island)
        /// to the visual top of the top row.
        static let topGap: CGFloat = 8
        /// `chrome-cluster-gap` — accessory to dock.
        static let clusterGap: CGFloat = 12
        /// `chrome-scrim-top` — height of the top scrim band, from the screen edge.
        static let scrimTop: CGFloat = 160
        /// `chrome-scrim-bottom` — minimum height of the bottom scrim band.
        static let scrimBottomMin: CGFloat = 220
        /// Share of a scrim band, nearest the screen edge, that stays flat.
        static let scrimFlat: CGFloat = 0.55
        /// `chrome-scrim` — black at 60 %.
        static let scrim = Color.black.opacity(0.60)
        /// Chrome entrance travel: the top row drops in, the bottom cluster rises.
        static let enterTop: CGFloat = 12
        static let enterBottom: CGFloat = 24

        /// `dock-bottom` — distance from the **screen** edge to the dock's
        /// bottom edge: 8 pt above the home-indicator safe area (34 + 8 = 42 pt
        /// on a Face ID iPhone), never less than `chrome-margin` (16 pt on a
        /// Home-button iPhone, where the inset is 0).
        static func dockBottom(safeArea: CGFloat) -> CGFloat {
            max(margin, safeArea + 8)
        }
    }

    /// The AR tab's hero stage — Android's `SceneViewTokens.ArHero`. Dark in
    /// both themes, like the Home hero: it is a camera view before the camera.
    enum ArHero {
        static let height: CGFloat = 312
        /// Stage gradient, top to floor (`#0B0F16` → `#14284A`).
        static let stageTop = Color(red: 0x0B / 255, green: 0x0F / 255, blue: 0x16 / 255)
        static let stageBottom = Color(red: 0x14 / 255, green: 0x28 / 255, blue: 0x4A / 255)
        /// Detected-plane dots, the ripple that runs through them and the
        /// reticle (`#A4C1FF`), as RGB for the RealityKit material too.
        static let planeDotRGB = (r: 0xA4 / 255.0, g: 0xC1 / 255.0, b: 0xFF / 255.0)
        static let planeDot = Color(red: planeDotRGB.r, green: planeDotRGB.g, blue: planeDotRGB.b)
        static let planeDotAlpha: Double = 0.22
        static let planeRippleAlpha: Double = 0.85
        static let planeDotRadius: CGFloat = 1.6
        /// Viewfinder corner brackets.
        static let bracket = Color.white.opacity(0x66 / 255.0)
        static let bracketLength: CGFloat = 22
        static let bracketStroke: CGFloat = 2
        static let bracketInset: CGFloat = 16
        /// Contact shadow under the placed model.
        static let contactShadow = Color.black.opacity(0x99 / 255.0)
        /// Scrim behind the copy at the foot of the stage.
        static let copyScrim = stageTop.opacity(0xCC / 255.0)
        /// `motion-ar-hero-ripple` — one ripple crossing the detected plane.
        static let rippleSeconds: Double = 2.4
        /// A model stays placed this long before the next one is placed.
        static let placementSeconds: Double = 5.5
        /// Share of the stage's width the copy may take: the models stand in the rest.
        static let copyWidthShare: CGFloat = 0.6
        static let statusIcon: CGFloat = 18
        static let ctaHeight: CGFloat = 52
    }

    /// Chrome tokens for a screen whose stage is the **camera feed**
    /// (`DESIGN.md` "AR Coaching Overlay" / "AR Overlay Card").
    ///
    /// The scrim bands of ``Chrome`` exist because a 3D stage can be any
    /// brightness. A camera feed is not a stage: darkening 160 pt of sky and
    /// 220 pt of floor is darkening the thing the user came to look at. Over
    /// the feed the ground belongs to each control instead — near-opaque,
    /// exactly the size of what it carries.
    enum ARChrome {
        /// `ar-scrim` — theme-independent ground; only the opacity flips,
        /// because light mode is used outdoors more often.
        static func scrim(_ scheme: ColorScheme) -> Color {
            Color.black.opacity(scheme == .dark ? 0.88 : 0.94)
        }
        /// `ar-scrim-border` — the hairline that separates the control from a
        /// busy frame.
        static func border(_ scheme: ColorScheme) -> Color {
            Color.white.opacity(scheme == .dark ? 0.10 : 0.16)
        }
        static let borderWidth: CGFloat = 1
        /// `on-ar-scrim` — white in both themes; the ground is the camera.
        static let onScrim = Color.white
        /// `on-ar-scrim-dim` — secondary text on the scrim.
        static let onScrimDim = Color.white.opacity(0.72)
        /// Status accents read on the scrim — the **dark-scheme** values in
        /// both themes (`DESIGN.md` "AR Coaching Overlay": Blocked #FFB4AB,
        /// Guidance `warning`, Positive `success`).
        static let danger = Color(red: 0xFF / 255, green: 0xB4 / 255, blue: 0xAB / 255)
        static let warning = Color(red: 0xF5 / 255, green: 0x9E / 255, blue: 0x0B / 255)
        static let success = Color(red: 0x16 / 255, green: 0xA3 / 255, blue: 0x4A / 255)
        /// Unlit track of a meter on the scrim, and "present but empty" over
        /// media — white at 8 %, Android's `ArOverlay.meterTrack`.
        static let meterTrack = Color.white.opacity(0x14 / 255)
    }

    /// `DESIGN.md` "AR Debug View" — the Rerun replay's palette, the values of
    /// Android's `SceneViewTokens.DebugView` so both apps draw the same room.
    /// Fixed in both themes: the ground is always `Stage.background`.
    ///
    /// Stored as `0xAARRGGBB` so the RealityKit layers and the SwiftUI chrome read
    /// the same value.
    enum DebugView {
        /// Trail, oldest → newest: `accent-deep` → `tertiary` dark → `tint-light`.
        static let trailOld: UInt32 = 0xFF5A_32A3
        static let trailMid: UInt32 = 0xFFD2_A8FF
        static let trailNew: UInt32 = 0xFFA4_C1FF
        static let frustum: UInt32 = 0xFFA4_C1FF
        static let keyframe: UInt32 = 0x59A4_C1FF
        static let mapPoint: UInt32 = 0x8CFF_FFFF
        /// What the camera sees this second — `warning`.
        static let livePoint: UInt32 = 0xFFF5_9E0B
        static let floorFill: UInt32 = 0x29A4_C1FF
        static let floorOutline: UInt32 = 0xD9A4_C1FF
        static let wallFill: UInt32 = 0x24D2_A8FF
        static let wallOutline: UInt32 = 0xCCD2_A8FF
        static let otherFill: UInt32 = 0x1FFF_FFFF
        static let otherOutline: UInt32 = 0xB3FF_FFFF
        /// Placed anchors — `success`.
        static let anchor: UInt32 = 0xFF16_A34A
        static let gridMinor: UInt32 = 0x12FF_FFFF
        static let gridMajor: UInt32 = 0x24FF_FFFF
        static let axisX: UInt32 = 0xFFEA_4335
        static let axisY: UInt32 = 0xFF16_A34A
        static let axisZ: UInt32 = 0xFFA4_C1FF
        /// Record mode draws over the camera, not over `Stage.background`: plane fills at
        /// 40 % so a surface reads as found from a metre away, outlines opaque.
        static let liveFloorFill: UInt32 = 0x66A4_C1FF
        static let liveWallFill: UInt32 = 0x66D2_A8FF
        static let liveFloorOutline: UInt32 = 0xFFA4_C1FF
        static let liveWallOutline: UInt32 = 0xFFD2_A8FF
        /// The picture-in-picture: a portrait 3:4 card.
        static let pipSize = CGSize(width: 128, height: 170)

        static func color(_ argb: UInt32) -> Color {
            Color(.sRGB,
                  red: Double((argb >> 16) & 0xFF) / 255,
                  green: Double((argb >> 8) & 0xFF) / 255,
                  blue: Double(argb & 0xFF) / 255,
                  opacity: Double((argb >> 24) & 0xFF) / 255)
        }
    }
}

// MARK: - Color Extension for Light/Dark

extension Color {
    /// Create a color that adapts to light/dark mode
    init(light: Color, dark: Color) {
        #if canImport(UIKit)
        self.init(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(dark)
                : UIColor(light)
        })
        #elseif canImport(AppKit)
        self.init(nsColor: NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
                ? NSColor(dark)
                : NSColor(light)
        })
        #else
        self = light
        #endif
    }
}

// MARK: - Cross-platform System Colors

extension Color {
    /// `UIColor.systemBackground` on iOS; `NSColor.windowBackgroundColor` on macOS.
    static var systemBackground: Color {
        #if canImport(UIKit)
        Color(uiColor: .systemBackground)
        #elseif canImport(AppKit)
        Color(nsColor: .windowBackgroundColor)
        #else
        Color(white: 1)
        #endif
    }

    /// `UIColor.secondarySystemBackground` on iOS;
    /// `NSColor.underPageBackgroundColor` on macOS.
    static var secondarySystemBackground: Color {
        #if canImport(UIKit)
        Color(uiColor: .secondarySystemBackground)
        #elseif canImport(AppKit)
        Color(nsColor: .underPageBackgroundColor)
        #else
        Color(white: 0.95)
        #endif
    }

    /// `UIColor.tertiarySystemBackground` on iOS;
    /// `NSColor.controlBackgroundColor` on macOS.
    static var tertiarySystemBackground: Color {
        #if canImport(UIKit)
        Color(uiColor: .tertiarySystemBackground)
        #elseif canImport(AppKit)
        Color(nsColor: .controlBackgroundColor)
        #else
        Color(white: 0.92)
        #endif
    }
}

// MARK: - View Modifiers

extension View {
    /// Applies `.navigationBarTitleDisplayMode(.inline)` on iOS; a no-op on
    /// macOS, where the modifier is unavailable. Lets shared SwiftUI code
    /// request an inline navigation title without per-call-site `#if os`.
    @ViewBuilder
    func navigationBarTitleInline() -> some View {
        #if os(iOS)
        self.navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }

    /// Hides the navigation bar on iOS (`.toolbar(.hidden, for: .navigationBar)`);
    /// a no-op on macOS, where `ToolbarPlacement.navigationBar` does not exist
    /// and the call fails to compile (the v4.33.0 macOS archive, #3556).
    @ViewBuilder
    func hideNavigationBar() -> some View {
        #if os(iOS)
        self.toolbar(.hidden, for: .navigationBar)
        #else
        self
        #endif
    }

    /// The `Glass` contract from `DESIGN.md`, as a single modifier: the 8 % white
    /// floor, the material, the dark-scheme ceiling, then the 1 pt white border.
    ///
    /// A bare `.ultraThinMaterial` is a *blur of what is behind it*, not a
    /// colour. Over a live 3D viewport that has gone dark — an unlit scene, an
    /// AR camera feed in a dim room, a `stage-background` clear colour — it has
    /// nothing bright to sample and resolves to very nearly the black behind
    /// it, so the control loses its background and only the label floats. The
    /// fill gives it a floor that does not depend on the scene; the border
    /// gives it an edge where even the floor is not enough.
    ///
    /// Use this anywhere chrome sits over media. Themed surfaces inside a page
    /// take `HomeColor.surfaceContainer` instead.
    ///
    /// On iOS 26 / macOS 26 and later the stack above is replaced by the
    /// system's own Liquid Glass (`glassEffect(.regular)`), which samples,
    /// tints and edges itself against whatever is behind it — the floor,
    /// ceiling and border were a hand-built approximation of exactly that.
    /// Pass `interactive: true` on a control (a button, the dock, an option
    /// strip) so the glass answers the touch; a read-only pill stays still.
    /// `id` names the shape inside a ``GlassEffectContainer`` so it morphs
    /// with its neighbours instead of cross-fading (the dock cluster).
    func glassBackground<S: InsettableShape>(in shape: S, interactive: Bool = false,
                                             id: String? = nil) -> some View {
        modifier(GlassBackground(shape: shape, interactive: interactive, id: id, native: true))
    }

    /// Edge-to-edge variant for bars that have no corner radius of their own.
    func glassBackground() -> some View {
        self.glassBackground(in: Rectangle())
    }

    /// The material stack of ``glassBackground(in:interactive:id:)`` on every
    /// OS version — for cards that sit *in* a page (About, Credits, a recent
    /// search row) rather than float over media. Apple keeps Liquid Glass to
    /// the floating navigation layer; a content card made of it competes with
    /// the chrome it sits under.
    func materialGlassBackground<S: InsettableShape>(in shape: S) -> some View {
        modifier(GlassBackground(shape: shape, interactive: false, id: nil, native: false))
    }

    /// Apply SceneView card styling
    func sceneViewCard() -> some View {
        self
            .background(.regularMaterial)
            .clipShape(RoundedRectangle(cornerRadius: SceneViewTheme.cardRadius))
    }

    /// Apply status badge styling
    func statusBadge(color: Color) -> some View {
        self
            .font(.caption2.bold())
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color.opacity(0.15))
            .foregroundStyle(color)
            .clipShape(Capsule())
    }
}

/// Body of `glassBackground(in:)` — a modifier only because the ceiling depends
/// on the colour scheme.
private struct GlassBackground<S: InsettableShape>: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.arChromeGround) private var arGround
    @Environment(\.chromeGlassNamespace) private var glassNamespace
    let shape: S
    let interactive: Bool
    let id: String?
    let native: Bool

    func body(content: Content) -> some View {
        if let arGround {
            // Over a camera feed the control carries its own near-opaque
            // ground instead of standing on a screen-wide scrim band. Blur is
            // deliberately absent: `.ultraThinMaterial` samples the live feed,
            // which is exactly the content the label has to beat.
            content
                .background(arGround, in: shape)
                .overlay(
                    shape.strokeBorder(
                        SceneViewTokens.ARChrome.border(colorScheme),
                        lineWidth: SceneViewTokens.ARChrome.borderWidth
                    )
                )
        } else if native, #available(iOS 26, macOS 26, visionOS 26, *) {
            nativeGlass(content)
        } else {
            glass(content)
        }
    }

    /// iOS 26+: the system Liquid Glass. `DESIGN.md` — "iOS 26+: native
    /// glassEffect; below: the material stack".
    ///
    /// The 1 pt `glass-border` stays. Over the near-black stage the system
    /// glass alone measured 1.08:1 fill and 1.34:1 edge against the ground
    /// (2026-09-26, iPhone 17 Pro Max, iOS 26.3) — the dock was a row of
    /// floating labels. With the border the edge is 4.0:1 (the material stack
    /// below 26: 3.1:1). It is drawn
    /// inside the glass so the interactive press stretches it with the shape.
    @available(iOS 26, macOS 26, visionOS 26, *)
    @ViewBuilder
    private func nativeGlass(_ content: Content) -> some View {
        let glassed = content
            .overlay(
                shape.strokeBorder(
                    SceneViewTokens.Glass.border,
                    lineWidth: SceneViewTokens.Glass.borderWidth
                )
            )
            .glassEffect(interactive ? .regular.interactive() : .regular, in: shape)
        if let id, let glassNamespace {
            glassed.glassEffectID(id, in: glassNamespace)
        } else {
            glassed
        }
    }

    @ViewBuilder
    private func glass(_ content: Content) -> some View {
        content
            // Nearest the content first: ceiling, material, floor.
            .background(colorScheme == .dark ? SceneViewTokens.Glass.ceiling : .clear, in: shape)
            .background(.ultraThinMaterial, in: shape)
            .background(SceneViewTokens.Glass.surface, in: shape)
            .overlay(
                shape.strokeBorder(
                    SceneViewTokens.Glass.border,
                    lineWidth: SceneViewTokens.Glass.borderWidth
                )
            )
    }
}

/// The ground every glass control uses when the stage is a camera feed, or
/// `nil` on an ordinary 3D stage. Set once by ``DemoScaffold`` in AR mode.
private struct ARChromeGroundKey: EnvironmentKey {
    static let defaultValue: Color? = nil
}

/// The namespace glass shapes in one ``GlassEffectContainer`` morph within —
/// set by ``DemoScaffold`` on its bottom cluster (accessory + dock).
private struct ChromeGlassNamespaceKey: EnvironmentKey {
    static let defaultValue: Namespace.ID? = nil
}

extension EnvironmentValues {
    var arChromeGround: Color? {
        get { self[ARChromeGroundKey.self] }
        set { self[ARChromeGroundKey.self] = newValue }
    }

    var chromeGlassNamespace: Namespace.ID? {
        get { self[ChromeGlassNamespaceKey.self] }
        set { self[ChromeGlassNamespaceKey.self] = newValue }
    }
}

extension View {
    /// The background of a sheet that rests on a partial detent over the 3D
    /// stage. iOS 26+: none of our own — the system draws its Liquid Glass
    /// sheet, so the scene the controls act on stays visible behind them,
    /// and turns it opaque by itself at the `.large` detent. Below 26:
    /// `style`, the themed surface the sheet has always had (`DESIGN.md`,
    /// Demo Scaffold — "iOS 26+: native glass on partial detents").
    @ViewBuilder
    func partialSheetBackground<S: ShapeStyle>(_ style: S) -> some View {
        #if os(iOS)
        if #available(iOS 26, *) {
            self
        } else {
            self.presentationBackground(style)
        }
        #else
        self.presentationBackground(style)
        #endif
    }

    /// Groups the glass shapes below it so they sample one backdrop and morph
    /// into each other (iOS 26+); a plain pass-through before that.
    @ViewBuilder
    func glassEffectGroup(spacing: CGFloat? = nil) -> some View {
        if #available(iOS 26, macOS 26, visionOS 26, *) {
            GlassEffectContainer(spacing: spacing) { self }
        } else {
            self
        }
    }
}

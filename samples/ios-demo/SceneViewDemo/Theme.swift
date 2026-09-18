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
    }

    /// `DESIGN.md` — Spatial Gallery overlay colours.
    enum SpatialGalleryColor {
        static let stageScrimStart = Color.clear
        static let stageScrimEnd = Color.black.opacity(0.90)
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
        static let display = Font.system(size: 32, weight: .bold)
        static let displayTracking: CGFloat = -0.02 * 32
        static let title = Font.system(size: 22, weight: .semibold)
        static let titleTracking: CGFloat = -0.02 * 22
        static let card = Font.system(size: 17, weight: .semibold)
        static let body = Font.system(size: 15, weight: .regular)
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
        /// `chip-text` = `on-surface-dim` — #3D4654 / #9CA3AF.
        static let chipText = Color(
            light: Color(red: 0x3D / 255, green: 0x46 / 255, blue: 0x54 / 255),
            dark: Color(red: 0x9C / 255, green: 0xA3 / 255, blue: 0xAF / 255)
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
        /// `on-primary` — text and icons on a `primary` fill: #FFFFFF / #0D1117.
        static let onPrimary = chipSelectedText
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
        /// DESIGN.md Surfaces, `surface-container` — #FFFFFF / #22293E.
        /// A lighter fill supplies dark elevation without a black shadow.
        ///
        /// Dark was #161C2C: 1.11:1 on `surface`, so every card, tile and row
        /// dissolved into the page and the screen read as one flat sheet.
        /// #22293E clears 1.25:1, the floor at which a container's background
        /// is actually visible. (On a near-black page the flare term of the
        /// WCAG ratio puts that floor at L* >= 15.1 — nothing darker can reach
        /// it, whatever the page is set to.)
        static let surfaceContainer = Color(
            light: .white,
            dark: Color(red: 0x22 / 255, green: 0x29 / 255, blue: 0x3E / 255)
        )
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
        /// Legacy light appearance only; dark uses `header-overlay` at 100 %.
        static let headerOverlayAlpha: Double = 0.94
    }

    /// Home screen geometry (`home-*` tokens).
    enum Home {
        static let headerHeight: CGFloat = 56
        static let markSize: CGFloat = 24
        static let searchFieldHeight: CGFloat = 48
        static let contentPadding: CGFloat = 20
        static let gridGutter: CGFloat = 12
        static let gridMinCell: CGFloat = 156
        static let gridMinCellExpanded: CGFloat = 220
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
        static let gridBottomInset: CGFloat = 32
        static let cardRadius: CGFloat = 20
        static let cardTextPaddingTop: CGFloat = 12
        static let cardTextPaddingHorizontal: CGFloat = 14
        static let cardTextPaddingBottom: CGFloat = 14
        static let cardOutlineWidth: CGFloat = 1
        static let iconTileGlyph: CGFloat = 40
        static let heroScrimStart: CGFloat = 0.5
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
    func glassBackground<S: InsettableShape>(in shape: S) -> some View {
        modifier(GlassBackground(shape: shape))
    }

    /// Edge-to-edge variant for bars that have no corner radius of their own.
    func glassBackground() -> some View {
        self.glassBackground(in: Rectangle())
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
    let shape: S

    func body(content: Content) -> some View {
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

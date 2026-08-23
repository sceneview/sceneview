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
    /// ARKit viewport, which is media, not a themed surface. Same 8 % white fill
    /// and 1 pt 8 % white border as Android; iOS adds `.ultraThinMaterial`
    /// underneath because a RealityKit view *can* be sampled.
    enum Glass {
        /// `glass-surface` over media — white at 8 %.
        static let surface = Color.white.opacity(0.08)
        /// `glass-border` — white at 8 %, 1 pt.
        static let border = Color.white.opacity(0.08)
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
        /// Hero placeholder / stage field — matches the viewer stage clear colour.
        static let heroField = Stage.background

        /// `chip-bg` = `surface-dim` — #F1F3F5 / #161B22.
        static let chipBackground = Color(
            light: Color(red: 0xF1 / 255, green: 0xF3 / 255, blue: 0xF5 / 255),
            dark: Color(red: 0x16 / 255, green: 0x1B / 255, blue: 0x22 / 255)
        )
        /// `chip-text` = `on-surface-dim` — #3D4654 / #9CA3AF.
        static let chipText = Color(
            light: Color(red: 0x3D / 255, green: 0x46 / 255, blue: 0x54 / 255),
            dark: Color(red: 0x9C / 255, green: 0xA3 / 255, blue: 0xAF / 255)
        )
        /// `chip-selected-bg` = `on-surface` — #1A1A2E / #F3F4F6.
        static let chipSelectedBackground = Color(
            light: Color(red: 0x1A / 255, green: 0x1A / 255, blue: 0x2E / 255),
            dark: Color(red: 0xF3 / 255, green: 0xF4 / 255, blue: 0xF6 / 255)
        )
        /// `chip-selected-text` = `surface` — #FFFFFF / #0D1117.
        static let chipSelectedText = Color(
            light: .white,
            dark: Color(red: 0x0D / 255, green: 0x11 / 255, blue: 0x17 / 255)
        )
        /// `on-surface` — #1A1A2E / #F3F4F6.
        static let onSurface = Color(
            light: Color(red: 0x1A / 255, green: 0x1A / 255, blue: 0x2E / 255),
            dark: Color(red: 0xF3 / 255, green: 0xF4 / 255, blue: 0xF6 / 255)
        )
        /// `on-surface-dim` — #3D4654 / #9CA3AF.
        static let onSurfaceDim = chipText
        /// `outline-subtle` — #EBEDF0 / #1F2937, the 1 pt card + header hairline.
        static let outlineSubtle = Color(
            light: Color(red: 0xEB / 255, green: 0xED / 255, blue: 0xF0 / 255),
            dark: Color(red: 0x1F / 255, green: 0x29 / 255, blue: 0x37 / 255)
        )
        /// `surface` — #FFFFFF / #0D1117 (the page ground and the card fill).
        static let surface = Color(
            light: .white,
            dark: Color(red: 0x0D / 255, green: 0x11 / 255, blue: 0x17 / 255)
        )
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

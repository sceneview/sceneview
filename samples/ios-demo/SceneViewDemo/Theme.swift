import SwiftUI

/// SceneView iOS Theme — Apple HIG + Liquid Glass
///
/// Brand colors aligned with Stitch M3 design system (source: #005bc1).
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

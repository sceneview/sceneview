import SwiftUI

/// One demo on the home grid (`DESIGN.md` "Demo App Home", Card) — the iOS
/// twin of Android's `DemoMediaCard.kt`.
///
/// Anatomy, top to bottom: a 5:4 media slot (`media-aspect`) showing the
/// captured preview when the asset catalog has one (`preview_<sceneId>` with a
/// dark appearance variant, copied from the Android `drawable-nodpi` set) and
/// the category-tinted SF Symbol tile otherwise; then title (`type-card`, one
/// line) and subtitle (`type-caption`, weight 400, one line). `surface` fill,
/// 1 pt `outline-subtle` hairline, 20 pt radius, no shadow, no scrim — the
/// media is the card. Press scales the card to 0.98 on the one app spring.
struct DemoMediaCard: View {
    let demo: DemoItem
    let onTap: () -> Void

    var body: some View {
        MediaCard(
            title: demo.title,
            subtitle: demo.subtitle,
            previewName: demo.previewImageName,
            icon: demo.icon,
            accent: demo.category.accent,
            status: demo.status,
            onTap: onTap
        )
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        switch demo.status {
        case .working: return "\(demo.title): \(demo.subtitle)"
        case .knownIssue: return "\(demo.title): \(demo.subtitle). Known issue."
        case .inReview: return "\(demo.title): \(demo.subtitle). In review."
        case .comingSoon: return "\(demo.title): \(demo.subtitle). Coming soon."
        }
    }
}

/// The closing grid item — same anatomy as a demo card, static icon media —
/// that opens the online model gallery (`ExploreTab`).
struct BrowseOnlineModelsCard: View {
    let onTap: () -> Void

    var body: some View {
        MediaCard(
            title: "Browse online models",
            subtitle: "Sketchfab, Icosa, Poly Haven",
            previewName: nil,
            icon: "globe",
            accent: SceneViewTheme.primary,
            status: .working,
            onTap: onTap
        )
        .accessibilityLabel("Browse online models")
    }
}

private struct MediaCard: View {
    let title: String
    let subtitle: String
    let previewName: String?
    let icon: String
    let accent: Color
    let status: DemoStatus
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .topTrailing) {
                    media
                    if let label = status.badgeLabel {
                        StatusChip(label: label)
                            .padding(SceneViewTokens.Space.sm)
                    }
                }
                .aspectRatio(SceneViewTokens.Layout.mediaAspect, contentMode: .fit)
                .clipped()

                VStack(alignment: .leading, spacing: SceneViewTokens.Space.xs) {
                    Text(title)
                        .font(SceneViewTokens.TypeScale.card)
                        .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                        .lineLimit(1)
                    Text(subtitle)
                        .font(SceneViewTokens.TypeScale.captionRegular)
                        .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, SceneViewTokens.Home.cardTextPaddingTop)
                .padding(.horizontal, SceneViewTokens.Home.cardTextPaddingHorizontal)
                .padding(.bottom, SceneViewTokens.Home.cardTextPaddingBottom)
            }
            .background(SceneViewTokens.HomeColor.surface)
            .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Home.cardRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: SceneViewTokens.Home.cardRadius, style: .continuous)
                    .strokeBorder(SceneViewTokens.HomeColor.outlineSubtle,
                                  lineWidth: SceneViewTokens.Home.cardOutlineWidth)
            )
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    @ViewBuilder
    private var media: some View {
        if let previewName {
            Color.clear.overlay(
                Image(previewName)
                    .resizable()
                    .scaledToFill()
            )
        } else {
            ZStack {
                SceneViewTokens.HomeColor.chipBackground
                Image(systemName: icon)
                    .font(.system(size: SceneViewTokens.Home.iconTileGlyph))
                    .foregroundStyle(accent)
            }
        }
    }
}

/// "Preview" / "In review" / "Soon" — the neutral status chip on the media.
private struct StatusChip: View {
    let label: String

    var body: some View {
        HStack(spacing: SceneViewTokens.Space.xs) {
            Image(systemName: "info.circle")
                .font(.system(size: 12))
            Text(label)
                .font(SceneViewTokens.TypeScale.caption)
                .lineLimit(1)
        }
        .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
        .padding(.horizontal, SceneViewTokens.Space.sm)
        .padding(.vertical, 3)
        .background(SceneViewTokens.HomeColor.surface.opacity(0.92), in: Capsule())
        .overlay(Capsule().strokeBorder(SceneViewTokens.HomeColor.outlineSubtle,
                                        lineWidth: SceneViewTokens.Home.cardOutlineWidth))
    }
}

extension DemoItem {
    /// Asset-catalog name of this demo's home-card preview, or `nil` when the
    /// image pipeline has not produced one yet (the card then shows the icon
    /// tile). Android: `DemoEntry.previewPainter()`.
    var previewImageName: String? {
        let name = "preview_" + sceneId.replacingOccurrences(of: "-", with: "_")
        #if canImport(UIKit)
        return UIImage(named: name) == nil ? nil : name
        #elseif canImport(AppKit)
        return NSImage(named: name) == nil ? nil : name
        #else
        return nil
        #endif
    }
}

extension DemoCategory {
    /// Icon-tile tint per category — the iOS twin of Android's `DemoCategoryAccent`.
    var accent: Color {
        switch self {
        case .basics3D: return SceneViewTheme.primary
        case .lighting: return .orange
        case .content: return .green
        case .interaction: return SceneViewTheme.tertiary
        case .advanced: return .teal
        case .ar: return .pink
        }
    }
}

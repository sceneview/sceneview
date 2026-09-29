import SwiftUI

/// One demo on the home grid (`DESIGN.md` "Demo App Home", "Home cards are
/// pictures first") — the iOS twin of Android's `DemoMediaCard.kt` (#4144).
///
/// Anatomy: a square picture (`card-media-aspect`) showing the captured preview
/// when the asset catalog has one (`preview_<sceneId>`, where each image comes
/// from is recorded in `tools/demo-previews/README.md`) and the category-tinted
/// SF Symbol tile otherwise; its lower edge *melts* into a frosted caption. The
/// caption's ground is a blurred copy of the same picture under `card-glass`,
/// faded in over `card-glass-melt`, so there is no line between image and text
/// and each card is tinted by what it shows. Title (`type-card`) and subtitle
/// (`type-caption`, weight 400) wrap, never truncate. No border in light
/// (`shadow-sm` lifts it), the 1 pt `outline-subtle` in dark; 20 pt radius.
///
/// With `featuredWidth` it is the "Featured" shelf's card: a 4:5 portrait of
/// that width, picture full-bleed, the frosted caption floating on its lower
/// part, `type-title`, `radius-xl`, `shadow-md`, and the picture lagging its
/// card by `featured-parallax` while the shelf is swiped.
///
/// Cards side by side end level: a card fills the height its row offers and
/// the caption's glass runs down with it — the grid row, or an `HStack` fixed
/// to its tallest card (`levelledRow()`). Press scales the card to 0.98 on the
/// one app spring.
struct DemoMediaCard: View {
    let demo: DemoItem
    /// Set for a "Featured" shelf card: its width, from which its 4:5 floor follows.
    var featuredWidth: CGFloat?
    let onTap: () -> Void

    var body: some View {
        MediaCard(
            title: demo.title,
            subtitle: demo.subtitle,
            previewName: demo.previewImageName,
            icon: demo.icon,
            accent: demo.category.accent,
            status: demo.status,
            badgeIcon: nil,
            featuredWidth: featuredWidth,
            mediaAlignment: featuredWidth != nil && HomeCatalogue.featuredLeadingAnchored.contains(demo.sceneId)
                ? .leading : .center,
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

/// The closing grid item — same anatomy as a demo card, with the Model Viewer
/// hero artwork under a scrim and a globe badge — that opens the online model
/// gallery (`ExploreTab`).
struct BrowseOnlineModelsCard: View {
    let onTap: () -> Void

    var body: some View {
        MediaCard(
            title: "Browse online models",
            subtitle: GallerySourcesRegistry.availableSourceNames,
            previewName: "preview_hero_model_viewer",
            icon: "globe",
            accent: SceneViewTheme.primary,
            status: .working,
            badgeIcon: "globe",
            featuredWidth: nil,
            mediaAlignment: .center,
            onTap: onTap
        )
        .accessibilityLabel("Browse online models")
    }
}

extension View {
    /// Cards laid side by side in an `HStack` end level: the stack takes its
    /// tallest card's height and every card (`maxHeight: .infinity`) fills it,
    /// its caption glass running down to the common bottom. Android reads its
    /// row peers' captions at layout time for the same result (#4144).
    func levelledRow() -> some View {
        fixedSize(horizontal: false, vertical: true)
    }
}

private struct MediaCard: View {
    @Environment(\.colorScheme) private var colorScheme

    let title: String
    let subtitle: String
    let previewName: String?
    let icon: String
    let accent: Color
    let status: DemoStatus
    /// When set, the picture gets the hero scrim and this SF Symbol as a glass
    /// badge in its bottom-leading corner (the "Browse online models" card).
    let badgeIcon: String?
    let featuredWidth: CGFloat?
    /// Where the picture is anchored when it is cropped to the card.
    let mediaAlignment: Alignment
    let onTap: () -> Void

    /// Top of a featured card's caption (its melt padding included), in the
    /// card's own space. A grid card's is fixed: one melt above the picture's
    /// bottom edge.
    @State private var featuredCaptionTop: CGFloat = .nan

    private var featured: Bool { featuredWidth != nil }
    private var melt: CGFloat { SceneViewTokens.Home.cardGlassMelt }
    private var radius: CGFloat { featured ? SceneViewTokens.Radius.xl : SceneViewTokens.Home.cardRadius }

    var body: some View {
        Button(action: onTap) {
            Group {
                if let featuredWidth {
                    featuredLayout(width: featuredWidth)
                } else {
                    gridLayout
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay {
                if colorScheme == .dark {
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .strokeBorder(SceneViewTokens.HomeColor.outlineSubtle,
                                      lineWidth: SceneViewTokens.Home.cardOutlineWidth)
                }
            }
            .cardShadow(colorScheme == .dark ? [] : (featured ? SceneViewTokens.Shadow.md : SceneViewTokens.Shadow.sm))
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    // MARK: Grid card

    /// Square picture, then the caption. The caption's top sits one melt above
    /// the picture's bottom edge and carries a melt of padding, so the text
    /// starts right under the picture and the glass rises over its last melt.
    private var gridLayout: some View {
        VStack(alignment: .leading, spacing: 0) {
            Color.clear
                .aspectRatio(SceneViewTokens.Home.cardMediaAspect, contentMode: .fit)
                // Size the picture from the card width alone: a height proposal
                // from the grid row would shrink a `.fit` slot narrower than the card.
                .fixedSize(horizontal: false, vertical: true)
            captionText
                .padding(.horizontal, SceneViewTokens.Home.cardTextPaddingHorizontal)
                .padding(.bottom, SceneViewTokens.Home.cardTextPaddingBottom)
            Spacer(minLength: 0)
        }
        // Fill the row: the tallest caption beside this card sets the height, and
        // the glass runs down to it.
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background {
            GeometryReader { geo in
                gridLayers(size: geo.size)
            }
        }
    }

    @ViewBuilder
    private func gridLayers(size: CGSize) -> some View {
        let mediaHeight = size.width / SceneViewTokens.Home.cardMediaAspect
        // The frosted band starts two melts above the picture's bottom edge: the
        // caption's top is one melt above that edge, and the fade spans a melt
        // on either side of it.
        let bandTop = max(0, min(mediaHeight - 2 * melt, size.height))
        let bandHeight = max(1, size.height - bandTop)
        ZStack(alignment: .top) {
            SceneViewTokens.HomeColor.surfaceContainer
            // 1. The picture, sharp.
            media(width: size.width, height: mediaHeight)
                .overlay { if badgeIcon != nil { badgeScrim } }
            // 2. The same picture, blurred, over the caption band only. Inside the
            //    band it has the sharp picture's exact size and crop, so the two
            //    coincide through the fade; below the picture it carries on as
            //    its mirror image, which under the blur reads as the picture's
            //    colours running on under the caption.
            if previewName != nil {
                VStack(spacing: 0) {
                    media(width: size.width, height: mediaHeight)
                    media(width: size.width, height: mediaHeight)
                        .scaleEffect(x: 1, y: -1)
                }
                .offset(y: -bandTop)
                .frame(width: size.width, height: bandHeight, alignment: .top)
                .clipped()
                .blur(radius: SceneViewTokens.Home.cardGlassBlurSigma, opaque: true)
                .mask { meltMask(captionTop: melt, height: bandHeight) }
                .offset(y: bandTop)
            }
            // 3. The glass tint — what the caption's contrast is measured against.
            Rectangle()
                .fill(SceneViewTokens.HomeColor.cardGlass)
                .mask { meltMask(captionTop: mediaHeight - melt, height: size.height) }
        }
        .frame(width: size.width, height: size.height, alignment: .top)
        .clipped()
        .overlay(alignment: .topTrailing) { statusChip }
        .overlay(alignment: .topLeading) {
            if let badgeIcon {
                badge(badgeIcon)
                    .frame(width: size.width, height: max(0, mediaHeight - melt), alignment: .bottomLeading)
            }
        }
    }

    // MARK: Featured card

    /// Portrait card, picture full-bleed, the caption pinned to its bottom. The
    /// card is at least 4:5 and grows with a longer caption.
    private func featuredLayout(width: CGFloat) -> some View {
        let inset = SceneViewTokens.Home.heroPadding - SceneViewTokens.Space.xs
        return VStack(alignment: .leading, spacing: 0) {
            Spacer(minLength: 0)
            captionText
                .padding(.top, melt)
                .padding(.horizontal, inset)
                .padding(.bottom, inset)
                .onGeometryChange(for: CGFloat.self) { proxy in
                    proxy.frame(in: .named(Self.cardSpace)).minY
                } action: { featuredCaptionTop = $0 }
        }
        .frame(width: width)
        .frame(minHeight: width / SceneViewTokens.Home.featuredMediaAspect, maxHeight: .infinity)
        .coordinateSpace(.named(Self.cardSpace))
        .background {
            GeometryReader { geo in
                featuredLayers(size: geo.size)
            }
        }
        .overlay(alignment: .topTrailing) { statusChip }
    }

    @ViewBuilder
    private func featuredLayers(size: CGSize) -> some View {
        ZStack {
            SceneViewTokens.HomeColor.surfaceContainer
            parallaxMedia(size: size)
            if !featuredCaptionTop.isNaN {
                if previewName != nil {
                    parallaxMedia(size: size)
                        .blur(radius: SceneViewTokens.Home.cardGlassBlurSigma, opaque: true)
                        .mask { meltMask(captionTop: featuredCaptionTop, height: size.height) }
                }
                Rectangle()
                    .fill(SceneViewTokens.HomeColor.cardGlass)
                    .mask { meltMask(captionTop: featuredCaptionTop, height: size.height) }
            }
        }
        .frame(width: size.width, height: size.height)
    }

    /// The featured picture, drawn `featured-media-overscan` larger than the card
    /// and sliding against the shelf's scroll by `featured-parallax`. The shelf's
    /// content margin is subtracted, so a card snapped at rest has no shift.
    private func parallaxMedia(size: CGSize) -> some View {
        let slack = size.width * (SceneViewTokens.Home.featuredMediaOverscan - 1) / 2
        return media(width: size.width, height: size.height)
            .scaleEffect(SceneViewTokens.Home.featuredMediaOverscan)
            .visualEffect { content, proxy in
                let travel = proxy.frame(in: .scrollView(axis: .horizontal)).minX
                    - SceneViewTokens.Home.contentPadding
                let shift = min(slack, max(-slack, -travel * SceneViewTokens.Home.featuredParallax))
                return content.offset(x: shift)
            }
            .frame(width: size.width, height: size.height)
            .clipped()
    }

    // MARK: Shared parts

    private var captionText: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.xs) {
            // Wrap, never truncate — Android sets `maxLines = Int.MAX_VALUE` (#3786).
            Text(title)
                .font(featured ? SceneViewTokens.TypeScale.title : SceneViewTokens.TypeScale.card)
                .tracking(featured ? SceneViewTokens.TypeScale.titleTracking : 0)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                .fixedSize(horizontal: false, vertical: true)
            Text(subtitle)
                .font(featured ? SceneViewTokens.TypeScale.body : SceneViewTokens.TypeScale.captionRegular)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Clear down to one melt above `captionTop`, opaque from one melt below it:
    /// the mask of the blurred copy and of the glass tint, so they melt in together.
    private func meltMask(captionTop: CGFloat, height: CGFloat) -> LinearGradient {
        let end = min(max(captionTop + melt, 0), height) / height
        let start = min(max(captionTop - melt, 0), height) / height
        return LinearGradient(
            stops: [
                .init(color: .clear, location: start),
                .init(color: .black, location: max(end, start)),
            ],
            startPoint: .top, endPoint: .bottom
        )
    }

    /// The picture, cropped to fill `width` × `height` at `mediaAlignment`, or the
    /// category-tinted icon tile when no preview exists yet.
    @ViewBuilder
    private func media(width: CGFloat, height: CGFloat) -> some View {
        if let previewName {
            Color.clear
                .frame(width: width, height: height)
                .overlay(alignment: mediaAlignment) {
                    Image(previewName)
                        .resizable()
                        .scaledToFill()
                }
                .clipped()
        } else {
            ZStack {
                SceneViewTokens.HomeColor.chipBackground
                Image(systemName: icon)
                    .font(.system(size: SceneViewTokens.Home.iconTileGlyph))
                    .foregroundStyle(accent)
            }
            .frame(width: width, height: height)
        }
    }

    private var badgeScrim: some View {
        LinearGradient(
            stops: [
                .init(color: SceneViewTokens.SpatialGalleryColor.stageScrimStart,
                      location: SceneViewTokens.Home.heroScrimStart),
                .init(color: SceneViewTokens.SpatialGalleryColor.stageScrimEnd, location: 1),
            ],
            startPoint: .top, endPoint: .bottom
        )
    }

    private func badge(_ symbol: String) -> some View {
        Image(systemName: symbol)
            .font(.system(size: 18, weight: .semibold))
            .foregroundStyle(SceneViewTokens.HomeColor.heroPillText)
            .frame(width: SceneViewTokens.Home.heroPillHeight - 8,
                   height: SceneViewTokens.Home.heroPillHeight - 8)
            .background(SceneViewTokens.HomeColor.heroPillBackground, in: Circle())
            .padding(SceneViewTokens.Space.sm)
    }

    @ViewBuilder
    private var statusChip: some View {
        if let label = status.badgeLabel {
            StatusChip(label: label)
                .padding(featured ? SceneViewTokens.Space.md : SceneViewTokens.Space.sm)
        }
    }

    private static let cardSpace = "demo-media-card"
}

private extension View {
    /// Draws `layers` (a `DESIGN.md` shadow token) under the view.
    func cardShadow(_ layers: [SceneViewTokens.Shadow.Layer]) -> some View {
        layers.reduce(AnyView(self)) { view, layer in
            AnyView(view.shadow(color: .black.opacity(layer.opacity), radius: layer.radius, x: 0, y: layer.y))
        }
    }
}

/// "Preview" / "In review" / "Soon" — the neutral status chip on the media.
private struct StatusChip: View {
    let label: String
    @Environment(\.colorScheme) private var colorScheme

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
        // Dark is opaque and one step above the card; the legacy light alpha is preserved.
        .background(colorScheme == .dark ? SceneViewTokens.HomeColor.floatingSurface
                                        : SceneViewTokens.HomeColor.surface.opacity(0.92), in: Capsule())
        .overlay(Capsule().strokeBorder(colorScheme == .dark ? SceneViewTokens.HomeColor.outline
                                                           : SceneViewTokens.HomeColor.outlineSubtle,
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

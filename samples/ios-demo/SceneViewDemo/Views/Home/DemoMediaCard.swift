import SwiftUI
#if canImport(UIKit)
import CoreImage
import UIKit
#endif

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
    /// Shelf cards after this one, so the last card's picture rests unshifted
    /// when the shelf reaches its end.
    var featuredTrailingCards = 0
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
            featuredTrailingCards: featuredTrailingCards,
            mediaAlignment: featuredWidth != nil && HomeCatalogue.featuredLeadingAnchored.contains(demo.sceneId)
                ? .leading : .center,
            onTap: onTap
        )
        .dynamicTypeSize(...MediaCard.largestTypeSize)
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
        .dynamicTypeSize(...MediaCard.largestTypeSize)
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
    /// Featured shelf cards after this one — where the shelf's end lies.
    var featuredTrailingCards = 0
    /// Where the picture is anchored when it is cropped to the card.
    let mediaAlignment: Alignment
    let onTap: () -> Void

    @Environment(\.displayScale) private var displayScale

    /// Top of a featured card's caption (its melt padding included), in the
    /// card's own space, once measured. Until then the glass sits at
    /// `featured-caption-top-estimate` of the card, so it is drawn on the first
    /// frame instead of popping in one frame late. A grid card's is fixed: one
    /// melt above the picture's bottom edge.
    @State private var featuredCaptionTop: CGFloat?

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
            // The shadow is cast by a plain shape behind the card, not by the
            // card itself: a `.shadow` on the card would re-render the whole
            // card — pictures and blur — offscreen on every scroll frame.
            .background {
                if colorScheme != .dark {
                    let layers = featured ? SceneViewTokens.Shadow.md : SceneViewTokens.Shadow.sm
                    ForEach(layers.indices, id: \.self) { index in
                        RoundedRectangle(cornerRadius: radius, style: .continuous)
                            .fill(SceneViewTokens.HomeColor.surfaceContainer)
                            .shadow(color: .black.opacity(layers[index].opacity),
                                    radius: layers[index].radius, x: 0, y: layers[index].y)
                    }
                }
            }
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
            //    The picture is static, so the band is a bitmap made once.
            if let previewName {
                gridGlassBand(previewName: previewName, width: size.width, mediaHeight: mediaHeight,
                              bandTop: bandTop, bandHeight: bandHeight)
                    .offset(y: bandTop)
            }
            // 3. The glass tint — what the caption's contrast is measured against.
            Rectangle()
                .fill(glassTint(captionTop: mediaHeight - melt, height: size.height))
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

    @ViewBuilder
    private func gridGlassBand(
        previewName: String, width: CGFloat, mediaHeight: CGFloat, bandTop: CGFloat, bandHeight: CGFloat
    ) -> some View {
        #if canImport(UIKit)
        if let band = GlassBandCache.band(
            previewName: previewName, width: width, mediaHeight: mediaHeight,
            bandTop: bandTop, bandHeight: bandHeight, fadeEnd: mediaHeight - bandTop,
            sigma: SceneViewTokens.Home.cardGlassBlurSigma, scale: displayScale
        ) {
            Image(uiImage: band)
                .resizable()
                .frame(width: width, height: bandHeight)
        }
        #else
        VStack(spacing: 0) {
            media(width: width, height: mediaHeight)
            media(width: width, height: mediaHeight)
                .scaleEffect(x: 1, y: -1)
        }
        .offset(y: -bandTop)
        .frame(width: width, height: bandHeight, alignment: .top)
        .clipped()
        .blur(radius: SceneViewTokens.Home.cardGlassBlurSigma, opaque: true)
        .mask { meltMask(captionTop: melt, height: bandHeight) }
        #endif
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
        let captionTop = featuredCaptionTop
            ?? size.height * SceneViewTokens.Home.featuredCaptionTopEstimate
        ZStack {
            SceneViewTokens.HomeColor.surfaceContainer
            parallaxMedia(size: size)
            // Live blur here: the picture slides under the parallax.
            if previewName != nil {
                parallaxMedia(size: size)
                    .blur(radius: SceneViewTokens.Home.cardGlassBlurSigma, opaque: true)
                    .mask { meltMask(captionTop: captionTop, height: size.height) }
            }
            Rectangle()
                .fill(glassTint(captionTop: captionTop, height: size.height))
        }
        .frame(width: size.width, height: size.height)
    }

    /// The featured picture, drawn `featured-media-overscan` larger than the card
    /// and sliding against the shelf's scroll by `featured-parallax`, measured from
    /// the card's own rest position so a card snapped at rest has no shift.
    ///
    /// A card rests at the leading content margin — except the last ones, which
    /// the shelf cannot scroll that far: they rest when the shelf hits its end.
    /// So the travel is the smaller of the distance to the leading margin and
    /// the scroll left before the end, which is zero at the end. The scroll left
    /// is read from this card's trailing extent (itself plus the cards after it),
    /// so no per-frame state reaches the card.
    private func parallaxMedia(size: CGSize) -> some View {
        let slack = size.width * (SceneViewTokens.Home.featuredMediaOverscan - 1) / 2
        let margin = SceneViewTokens.Home.contentPadding
        let trailingExtent = size.width
            + CGFloat(featuredTrailingCards) * (size.width + SceneViewTokens.Home.gridGutter)
        return media(width: size.width, height: size.height)
            .scaleEffect(SceneViewTokens.Home.featuredMediaOverscan)
            .visualEffect { content, proxy in
                let minX = proxy.frame(in: .scrollView(axis: .horizontal)).minX
                let viewport = proxy.bounds(of: .scrollView(axis: .horizontal))?.width ?? .infinity
                let toLeading = minX - margin
                let toEnd = minX + trailingExtent + margin - viewport
                let travel = min(toLeading, max(0, toEnd))
                let shift = min(slack, max(-slack, -travel * SceneViewTokens.Home.featuredParallax))
                return content.offset(x: shift)
            }
            .frame(width: size.width, height: size.height)
            .clipped()
    }

    // MARK: Shared parts

    // The caption follows Dynamic Type, as Android's follows the font scale:
    // the `type-*` sizes below are the default-size values, scaled with the
    // text style of the same size. `DemoMediaCard` caps the scale (see there).
    @ScaledMetric(relativeTo: .title2) private var featuredTitleSize = SceneViewTokens.TypeScale.titleSize
    @ScaledMetric(relativeTo: .headline) private var cardTitleSize = SceneViewTokens.TypeScale.cardSize
    @ScaledMetric(relativeTo: .subheadline) private var featuredBodySize = SceneViewTokens.TypeScale.bodySize
    @ScaledMetric(relativeTo: .footnote) private var cardBodySize = SceneViewTokens.TypeScale.captionSize

    private var captionText: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.xs) {
            // Wrap, never truncate — Android sets `maxLines = Int.MAX_VALUE` (#3786).
            Text(title)
                .font(.system(size: featured ? featuredTitleSize : cardTitleSize, weight: .semibold))
                .tracking(featured ? SceneViewTokens.TypeScale.trackingTight * featuredTitleSize : 0)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                .fixedSize(horizontal: false, vertical: true)
            Text(subtitle)
                .font(.system(size: featured ? featuredBodySize : cardBodySize, weight: .regular))
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

    /// `card-glass` faded in like ``meltMask(captionTop:height:)`` — a gradient
    /// fill, so the tint needs no mask pass.
    private func glassTint(captionTop: CGFloat, height: CGFloat) -> LinearGradient {
        let end = min(max(captionTop + melt, 0), height) / height
        let start = min(max(captionTop - melt, 0), height) / height
        let glass = SceneViewTokens.HomeColor.cardGlass
        return LinearGradient(
            stops: [
                .init(color: glass.opacity(0), location: start),
                .init(color: glass, location: max(end, start)),
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

    /// The largest Dynamic Type a card caption takes: `accessibility2` puts a
    /// grid title at 33 pt, about Android's 2.0 font-scale ceiling. Past it a
    /// half-width card holds a word or two per line.
    static let largestTypeSize = DynamicTypeSize.accessibility2
}

#if canImport(UIKit)
/// The grid card's frosted band as a bitmap, rendered once per picture and size.
///
/// A grid card's picture never moves inside its card, so its blurred copy is
/// static: blurring it live would run a Gaussian pass and a gradient mask per
/// card on every scroll frame. Here the band — the picture continued as its
/// mirror image, cropped to the caption band, blurred by `card-glass-blur` and
/// faded in over `card-glass-melt` — is drawn once and kept.
@MainActor
enum GlassBandCache {
    private static let cache = NSCache<NSString, UIImage>()
    private static let context = CIContext(options: [.cacheIntermediates: false])

    /// - Parameters:
    ///   - mediaHeight: height of the sharp picture; the mirror starts there.
    ///   - bandTop: top of the band in card space.
    ///   - fadeEnd: where, in band space, the fade reaches full opacity.
    static func band(
        previewName: String,
        width: CGFloat,
        mediaHeight: CGFloat,
        bandTop: CGFloat,
        bandHeight: CGFloat,
        fadeEnd: CGFloat,
        sigma: CGFloat,
        scale: CGFloat
    ) -> UIImage? {
        let key = "\(previewName)|\(width)|\(mediaHeight)|\(bandTop)|\(bandHeight)|\(scale)" as NSString
        if let hit = cache.object(forKey: key) { return hit }
        guard let source = UIImage(named: previewName), width > 0, bandHeight > 0 else { return nil }

        let size = CGSize(width: width, height: bandHeight)
        let opaque = UIGraphicsImageRendererFormat()
        opaque.scale = scale
        opaque.opaque = true
        // 1. The picture, aspect-filled and centred, then its mirror image below.
        let composed = UIGraphicsImageRenderer(size: size, format: opaque).image { ctx in
            let cg = ctx.cgContext
            cg.translateBy(x: 0, y: -bandTop)
            let slot = CGRect(x: 0, y: 0, width: width, height: mediaHeight)
            let fill = aspectFill(source.size, in: slot)
            cg.saveGState()
            cg.clip(to: slot)
            source.draw(in: fill)
            cg.restoreGState()
            cg.saveGState()
            cg.translateBy(x: 0, y: 2 * mediaHeight)
            cg.scaleBy(x: 1, y: -1)
            cg.clip(to: slot)
            source.draw(in: fill)
            cg.restoreGState()
        }
        // 2. Blurred with its edges clamped — SwiftUI's `blur(opaque: true)`.
        guard let input = composed.cgImage.map(CIImage.init(cgImage:)) else { return nil }
        let blurred = input.clampedToExtent()
            .applyingGaussianBlur(sigma: Double(sigma * scale))
            .cropped(to: input.extent)
        guard let blurredCG = context.createCGImage(blurred, from: input.extent) else { return nil }

        // 3. Faded in from the band's top to `fadeEnd`.
        let clear = UIGraphicsImageRendererFormat()
        clear.scale = scale
        clear.opaque = false
        let faded = UIGraphicsImageRenderer(size: size, format: clear).image { ctx in
            UIImage(cgImage: blurredCG, scale: scale, orientation: .up)
                .draw(in: CGRect(origin: .zero, size: size))
            let colors = [UIColor.black.withAlphaComponent(0).cgColor, UIColor.black.cgColor] as CFArray
            guard let gradient = CGGradient(colorsSpace: nil, colors: colors, locations: [0, 1]) else { return }
            ctx.cgContext.setBlendMode(.destinationIn)
            ctx.cgContext.drawLinearGradient(
                gradient,
                start: .zero,
                end: CGPoint(x: 0, y: min(max(fadeEnd, 1), bandHeight)),
                options: [.drawsAfterEndLocation]
            )
        }
        cache.setObject(faded, forKey: key)
        return faded
    }

    private static func aspectFill(_ image: CGSize, in rect: CGRect) -> CGRect {
        guard image.width > 0, image.height > 0 else { return rect }
        let scale = max(rect.width / image.width, rect.height / image.height)
        let size = CGSize(width: image.width * scale, height: image.height * scale)
        return CGRect(x: rect.midX - size.width / 2, y: rect.midY - size.height / 2,
                      width: size.width, height: size.height)
    }
}
#endif

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

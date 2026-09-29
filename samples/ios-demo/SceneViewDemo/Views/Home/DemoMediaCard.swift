import SwiftUI
#if canImport(UIKit)
import CoreImage
import UIKit
#endif

/// One demo as a picture card (`DESIGN.md` "The picture card") — the iOS twin
/// of Android's `DemoMediaCard.kt` (#4144). The Home lists its demos as rows
/// (`DemoListRow`, #4186); the Explore tab's "Try a demo" row keeps this card.
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
/// Cards side by side end level: a card fills the height its row offers and
/// the caption's glass runs down with it — an `HStack` fixed to its tallest
/// card (`levelledRow()`). Press scales the card to 0.98 on the one app spring.
struct DemoMediaCard: View {
    let demo: DemoItem
    let onTap: () -> Void

    private var freshness: DemoFreshness { DemoFreshness.of(demo) }

    var body: some View {
        MediaCard(
            title: demo.title,
            subtitle: demo.subtitle,
            previewName: demo.previewImageName,
            icon: demo.icon,
            accent: demo.category.accent,
            status: demo.status,
            freshness: freshness,
            freshnessAccent: demo.section.accent,
            onTap: onTap
        )
        .dynamicTypeSize(...Self.largestTypeSize)
        .accessibilityLabel(demo.homeAccessibilityLabel)
    }

    /// The largest Dynamic Type a card caption or a home row takes:
    /// `accessibility2` puts a card title at 33 pt, about Android's 2.0
    /// font-scale ceiling. Past it a half-width card holds a word or two per line.
    static let largestTypeSize = DynamicTypeSize.accessibility2
}

extension DemoItem {
    /// What VoiceOver reads for this demo, as a home row or a picture card:
    /// the freshness, the title and subtitle, then any status caveat.
    var homeAccessibilityLabel: String {
        let fresh = DemoFreshness.of(self).label.map { "\($0). " } ?? ""
        switch status {
        case .working: return "\(fresh)\(title): \(subtitle)"
        case .knownIssue: return "\(fresh)\(title): \(subtitle). Known issue."
        case .inReview: return "\(fresh)\(title): \(subtitle). In review."
        case .comingSoon: return "\(fresh)\(title): \(subtitle). Coming soon."
        }
    }
}

extension View {
    /// Cards laid side by side in an `HStack` end level: the stack takes its
    /// tallest card's height and every card (`maxHeight: .infinity`) fills it,
    /// its caption glass running down to the common bottom.
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
    /// "New" / "Updated" chip on the picture's top-leading corner, opposite
    /// the status chip — Android's `FreshnessChip`.
    let freshness: DemoFreshness
    /// Tint of the freshness chip: the demo's home-section accent.
    let freshnessAccent: Color
    let onTap: () -> Void

    @Environment(\.displayScale) private var displayScale

    private var melt: CGFloat { SceneViewTokens.Home.cardGlassMelt }
    private var radius: CGFloat { SceneViewTokens.Home.cardRadius }

    var body: some View {
        Button(action: onTap) {
            gridLayout
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
                        let layers = SceneViewTokens.Shadow.sm
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
        .overlay(alignment: .topLeading) { freshnessChip }
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

    // MARK: Shared parts

    // The caption follows Dynamic Type, as Android's follows the font scale:
    // the `type-*` sizes below are the default-size values, scaled with the
    // text style of the same size. `DemoMediaCard` caps the scale (see there).
    @ScaledMetric(relativeTo: .headline) private var cardTitleSize = SceneViewTokens.TypeScale.cardSize
    @ScaledMetric(relativeTo: .footnote) private var cardBodySize = SceneViewTokens.TypeScale.captionSize

    private var captionText: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.xs) {
            // Wrap, never truncate — Android sets `maxLines = Int.MAX_VALUE` (#3786).
            Text(title)
                .font(.system(size: cardTitleSize, weight: .semibold))
                .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                .fixedSize(horizontal: false, vertical: true)
            Text(subtitle)
                .font(.system(size: cardBodySize, weight: .regular))
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

    /// The picture, cropped to fill `width` × `height`, centred, or the
    /// category-tinted icon tile when no preview exists yet.
    @ViewBuilder
    private func media(width: CGFloat, height: CGFloat) -> some View {
        if let previewName {
            Color.clear
                .frame(width: width, height: height)
                .overlay {
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

    @ViewBuilder
    private var statusChip: some View {
        if let label = status.badgeLabel {
            StatusChip(label: label)
                .padding(SceneViewTokens.Space.sm)
        }
    }

    @ViewBuilder
    private var freshnessChip: some View {
        if let label = freshness.label {
            FreshnessChip(label: label, accent: freshnessAccent)
                .padding(SceneViewTokens.Space.sm)
        }
    }
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

private extension View {
    /// The pill both chips share — Android's chip `Surface`:
    /// `surface-container` at 92 % with an `outline-subtle` hairline, in both
    /// themes. On a home row it reads one step off the grey tile (below it in
    /// dark, above it in light); the former dark `floating-surface` fill was
    /// within a hair of the dark row and the pill disappeared into it.
    func chipPill() -> some View {
        background(SceneViewTokens.HomeColor.surfaceContainer.opacity(0.92), in: Capsule())
            .overlay(Capsule().strokeBorder(SceneViewTokens.HomeColor.outlineSubtle,
                                            lineWidth: SceneViewTokens.Home.cardOutlineWidth))
    }
}

/// "Preview" / "In review" / "Soon" — the neutral status chip, on a card's
/// picture or on a home row's title line.
struct StatusChip: View {
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
        .chipPill()
    }
}

/// "New" / "Updated" on a card's picture, top-leading, or on a home row's title
/// line — Android's `FreshnessChip`.
/// Same pill as ``StatusChip`` so the two read as one family, but the sparkle
/// and the label take the demo's section accent: freshness is an invitation,
/// status a caveat, and they must not look alike at a glance.
struct FreshnessChip: View {
    let label: String
    let accent: Color

    var body: some View {
        HStack(spacing: SceneViewTokens.Space.xs) {
            Image(systemName: "sparkles")
                .font(.system(size: 12, weight: .semibold))
            Text(label)
                .font(SceneViewTokens.TypeScale.caption.weight(.semibold))
                .lineLimit(1)
        }
        .foregroundStyle(accent)
        .padding(.horizontal, SceneViewTokens.Space.sm)
        .padding(.vertical, 3)
        .chipPill()
        .accessibilityHidden(true)
    }
}

extension DemoSection {
    /// The section's accent — Android's `DemoCategoryAccent`, keyed the same way.
    var accent: Color {
        switch self {
        case .view3d: return SceneViewTokens.HomeColor.sectionAccentView3D
        case .create: return SceneViewTokens.HomeColor.sectionAccentCreate
        case .placeAR: return SceneViewTokens.HomeColor.sectionAccentPlaceAR
        case .understand: return SceneViewTokens.HomeColor.sectionAccentUnderstand
        case .devTools: return SceneViewTokens.HomeColor.sectionAccentDevTools
        }
    }
}

extension DemoItem {
    /// Asset-catalog name of this demo's preview, or `nil` when the image
    /// pipeline has not produced one yet (the card or row then shows the icon
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

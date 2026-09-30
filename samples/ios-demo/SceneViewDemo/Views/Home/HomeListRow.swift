import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Which of the two Home row anatomies a demo is drawn with — Android's
/// `HomeRowStyle` (`HomeListRow.kt`).
enum HomeRowStyle {
    /// `home-row`: the picture on the leading half, dissolving sideways into the row.
    case fused
    /// `home-banner`: the picture across the full width, dissolving down into its caption.
    case banner
}

/// One demo on the Home list (`home-row` / `home-banner` in `DESIGN.md`) — the
/// iOS twin of Android's `DemoListRow`.
///
/// The picture is the row. It runs to the row's own edges — no inset, no frame,
/// no radius of its own — and dissolves into the row's colour, which is the
/// picture's colour (``HomeAmbient``): a night capture carries on as a deep navy
/// under the text, the fox as a warm brown. Nothing reads as a thumbnail pasted
/// on a grey tile.
///
/// ``HomeRowStyle/fused`` puts the picture on the leading half, full height,
/// dissolving sideways under the start of the text: the catalogue stays a list
/// you scan by title. ``HomeRowStyle/banner`` gives the picture the full width
/// and lets it dissolve down into its caption — the Featured group, where the
/// pictures are the point.
struct DemoListRow: View {
    let demo: DemoItem
    var style: HomeRowStyle = .fused
    let onTap: () -> Void

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let dark = colorScheme == .dark
        let freshness = DemoFreshness.of(demo)
        let preview = demo.previewImageName
        let tint = preview.map { HomeAmbient.tint(imageNamed: $0, dark: dark) }
            ?? HomeAmbient.tint(accent: demo.category.accent, dark: dark)
        // Anchored like Android's `FEATURED_MEDIA_ALIGNMENT`: a picture listed
        // there keeps its leading edge in frame when the row crops it.
        let anchor: Alignment = HomeCatalogue.leadingAnchored.contains(demo.sceneId) ? .leading : .center
        HomeListRow(title: demo.title, subtitle: demo.subtitle, tint: tint, style: style, onTap: onTap) {
            if let preview {
                Color.clear
                    .overlay(alignment: anchor) {
                        Image(preview)
                            .resizable()
                            .scaledToFill()
                    }
                    .clipped()
            } else {
                HomeGlyphPanel(systemName: demo.icon, accent: demo.category.accent, tint: tint)
            }
        } badges: {
            if let label = freshness.label {
                FreshnessChip(label: label, accent: demo.section.accent)
            }
            if let label = demo.status.badgeLabel {
                StatusChip(label: label)
            }
        }
        .accessibilityLabel(demo.homeAccessibilityLabel)
    }
}

/// The row that opens the online model gallery (`ExploreTab`) — Android's
/// `BrowseOnlineRow`: one more ``HomeRowStyle/fused`` row, a globe on a
/// `primary`-tinted panel, so it sits in the list's rhythm instead of being a
/// banner of its own.
struct BrowseOnlineRow: View {
    let onTap: () -> Void

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let accent = SceneViewTokens.HomeColor.primary
        let tint = HomeAmbient.tint(accent: accent, dark: colorScheme == .dark)
        HomeListRow(title: "Browse online models",
                    subtitle: "Discover models from online collections",
                    tint: tint,
                    style: .fused,
                    onTap: onTap) {
            HomeGlyphPanel(systemName: "globe", accent: accent, tint: tint)
        } badges: {
            EmptyView()
        }
        .accessibilityLabel("Browse online models")
        .accessibilityHint("Discover models from online collections")
    }
}

/// Columns of the Home list for a content area `width` wide (the page's side
/// insets already taken off) — Android's `homeListColumns`: one on a phone,
/// then as many `home-row-min-width` columns as fit.
func homeListColumns(width: CGFloat) -> Int {
    let home = SceneViewTokens.Home.self
    return max(1, Int((width + home.rowGap) / (home.rowMinWidth + home.rowGap)))
}

// MARK: - Row anatomy

private struct HomeListRow<Media: View, Badges: View>: View {
    let title: String
    let subtitle: String
    let tint: Color
    let style: HomeRowStyle
    let onTap: () -> Void
    @ViewBuilder let media: () -> Media
    /// Chips drawn after the title, on its line.
    @ViewBuilder let badges: () -> Badges

    var body: some View {
        let home = SceneViewTokens.Home.self
        let shape = RoundedRectangle(cornerRadius: home.rowRadius, style: .continuous)
        Button(action: onTap) {
            Group {
                switch style {
                case .fused: fused
                case .banner: banner
                }
            }
            .background(tint)
            .clipShape(shape)
            .contentShape(shape)
        }
        .buttonStyle(HomeRowButtonStyle(shape: shape))
        .dynamicTypeSize(...DemoMediaCard.largestTypeSize)
        .accessibilityElement(children: .combine)
    }

    /// `home-row`: the picture fills the leading `home-row-media-fraction` of the
    /// row, top to bottom, and dissolves into the tint from `home-row-dissolve`
    /// on; the text starts where the picture has all but gone.
    private var fused: some View {
        let home = SceneViewTokens.Home.self
        return FractionalLeadingInset(fraction: home.rowTextStartFraction) {
            HomeRowCaption(title: title, subtitle: subtitle, badges: badges)
                .padding(.trailing, home.rowTextPaddingEnd)
                .padding(.vertical, home.rowTextPaddingVertical)
        }
        .frame(maxWidth: .infinity, minHeight: home.rowHeight, alignment: .leading)
        .background(alignment: .leading) {
            GeometryReader { proxy in
                media()
                    .frame(width: proxy.size.width * home.rowMediaFraction, height: proxy.size.height)
                    .clipped()
                    .homeDissolve(towards: .trailing, from: home.rowDissolveStart)
            }
            .accessibilityHidden(true)
        }
    }

    /// `home-banner`: the picture across the row at `home-banner-aspect`,
    /// dissolving from `home-banner-dissolve` down into the tint; the caption is
    /// pulled up into the dissolve so the title sits where the picture ends, not
    /// under an edge.
    private var banner: some View {
        let home = SceneViewTokens.Home.self
        return VStack(alignment: .leading, spacing: 0) {
            media()
                .aspectRatio(home.bannerAspect, contentMode: .fit)
                .frame(maxWidth: .infinity)
                .clipped()
                .homeDissolve(towards: .bottom, from: home.bannerDissolveStart)
                .accessibilityHidden(true)
            HomeRowCaption(title: title, subtitle: subtitle, badges: badges)
                .padding(.horizontal, home.bannerTextPaddingHorizontal)
                .padding(.bottom, home.rowTextPaddingVertical)
                .padding(.top, -home.bannerCaptionOverlap)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Title in `type-card` semibold with the chips on its line, the subtitle in
/// `type-caption` regular. The text follows Dynamic Type, as Android's follows
/// the font scale: the row grows with it; nothing truncates.
private struct HomeRowCaption<Badges: View>: View {
    let title: String
    let subtitle: String
    @ViewBuilder let badges: () -> Badges

    @ScaledMetric(relativeTo: .headline) private var titleSize = SceneViewTokens.TypeScale.cardSize
    @ScaledMetric(relativeTo: .footnote) private var subtitleSize = SceneViewTokens.TypeScale.captionSize

    var body: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Home.rowTextGap) {
            HStack(spacing: SceneViewTokens.Space.sm) {
                Text(title)
                    .font(.system(size: titleSize, weight: .semibold))
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                badges()
                    .fixedSize()
            }
            Text(subtitle)
                .font(.system(size: subtitleSize, weight: .regular))
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The row's press: `on-surface` at the pressed state-layer opacity over the
/// row, like a list cell's highlight. No scale, no shadow, no outline.
private struct HomeRowButtonStyle: ButtonStyle {
    let shape: RoundedRectangle

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .overlay {
                shape.fill(SceneViewTokens.HomeColor.onSurface
                    .opacity(configuration.isPressed ? SceneViewTokens.Home.rowPressedAlpha : 0))
            }
            .animation(SceneViewTokens.Spring.fade, value: configuration.isPressed)
    }
}

/// A demo with no capture yet (or a utility row): its glyph on an accent wash
/// over the row's tint — Android's `GlyphPanel`.
private struct HomeGlyphPanel: View {
    let systemName: String
    let accent: Color
    let tint: Color

    var body: some View {
        ZStack {
            tint
            accent.opacity(SceneViewTokens.Home.rowGlyphWashAlpha)
            Image(systemName: systemName)
                .font(.system(size: SceneViewTokens.Home.rowGlyph))
                .foregroundStyle(accent)
        }
        .accessibilityHidden(true)
    }
}

/// Lays its content out from `fraction` of the proposed width to the end —
/// Android's `Spacer(weight(f))` + `weight(1 - f)` pair. Mirrored under a
/// right-to-left layout like any `Layout`.
private struct FractionalLeadingInset: Layout {
    let fraction: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let child = subviews.first else { return .zero }
        let width = proposal.width ?? child.sizeThatFits(.unspecified).width / (1 - fraction)
        let size = child.sizeThatFits(ProposedViewSize(width: width * (1 - fraction), height: nil))
        return CGSize(width: width, height: size.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let child = subviews.first else { return }
        let inset = bounds.width * fraction
        child.place(at: CGPoint(x: bounds.minX + inset, y: bounds.midY),
                    anchor: .leading,
                    proposal: ProposedViewSize(width: bounds.width - inset, height: bounds.height))
    }
}

private enum HomeDissolveEdge { case trailing, bottom }

private extension View {
    /// Fades the view out towards `edge`, fully opaque up to `start` (a fraction
    /// of its size along that axis) and gone at the edge, on a cosine ease so the
    /// fade has no band where it starts or ends — Android's `Modifier.dissolve`.
    /// What shows through is the row's own tint.
    func homeDissolve(towards edge: HomeDissolveEdge, from start: CGFloat) -> some View {
        let stops = (0...8).map { i -> Gradient.Stop in
            let u = Double(i) / 8
            return Gradient.Stop(color: .black.opacity(0.5 * (1 + cos(Double.pi * u))),
                                 location: start + (1 - start) * CGFloat(u))
        }
        let gradient = Gradient(stops: [Gradient.Stop(color: .black, location: 0)] + stops)
        return mask {
            switch edge {
            case .trailing: LinearGradient(gradient: gradient, startPoint: .leading, endPoint: .trailing)
            case .bottom: LinearGradient(gradient: gradient, startPoint: .top, endPoint: .bottom)
            }
        }
    }
}

// MARK: - Ambient tint

/// `home-row-ambient` — the colour a Home row takes from its own picture
/// (`DESIGN.md`), the twin of Android's `HomeAmbient.kt`; keep the two in step.
///
/// The hue and saturation come from the picture; the lightness does not. It is
/// solved so the tint lands on one fixed relative luminance per appearance
/// (``luminanceDark``, ``luminanceLight``), so `on-surface` and
/// `on-surface-dim` hold the same contrast on every row whatever the picture is.
enum HomeAmbient {
    /// Relative luminance of the tint in dark: `surface-container-high`
    /// (#2C3546), the grey tile the rows replace, so the text keeps its contrast
    /// there: `on-surface` 11.2:1, `on-surface-dim` 5.3:1.
    static let luminanceDark: Double = 0.035
    /// Relative luminance of the tint in light: a pale wash one step off the
    /// white page. `on-surface` 14.5:1, `on-surface-dim` 8.1:1.
    static let luminanceLight: Double = 0.84
    /// The tint never goes past this HSL saturation: a colour, never a poster.
    static let maxSaturation: Double = 0.5

    /// One sRGB colour, components in 0...1.
    struct RGB: Equatable {
        var r: Double
        var g: Double
        var b: Double
    }

    @MainActor private static var cache: [String: Color] = [:]

    /// The tint of the asset-catalog picture `name` in the given appearance,
    /// sampled once and cached.
    @MainActor
    static func tint(imageNamed name: String, dark: Bool) -> Color {
        let key = name + (dark ? "|dark" : "|light")
        if let cached = cache[key] { return cached }
        let rgb = sample(imageNamed: name, dark: dark).map { tint(seed: $0, dark: dark) }
            ?? tint(seed: RGB(r: 0.5, g: 0.5, b: 0.5), dark: dark)
        let color = Color(.sRGB, red: rgb.r, green: rgb.g, blue: rgb.b)
        cache[key] = color
        return color
    }

    /// The row colour of a glyph row: the tint of its accent, as if the accent
    /// were a picture.
    @MainActor
    static func tint(accent: Color, dark: Bool) -> Color {
        #if canImport(UIKit)
        let traits = UITraitCollection(userInterfaceStyle: dark ? .dark : .light)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(accent).resolvedColor(with: traits).getRed(&r, green: &g, blue: &b, alpha: &a)
        let rgb = tint(seed: RGB(r: Double(r), g: Double(g), b: Double(b)), dark: dark)
        #else
        let rgb = tint(seed: RGB(r: 0.5, g: 0.5, b: 0.5), dark: dark)
        #endif
        return Color(.sRGB, red: rgb.r, green: rgb.g, blue: rgb.b)
    }

    /// The picture's colour: the mean of `rgba` (8-bit RGBA, alpha ignored)
    /// where each pixel weighs `0.1 + chroma`, so a fox on a grey studio floor
    /// reads orange, not grey, while a neutral picture stays neutral.
    static func seed(rgba: [UInt8]) -> RGB {
        var r = 0.0, g = 0.0, b = 0.0, total = 0.0
        var i = 0
        while i + 3 < rgba.count {
            let pr = Double(rgba[i]) / 255, pg = Double(rgba[i + 1]) / 255, pb = Double(rgba[i + 2]) / 255
            let weight = 0.1 + (max(pr, pg, pb) - min(pr, pg, pb))
            r += pr * weight
            g += pg * weight
            b += pb * weight
            total += weight
            i += 4
        }
        guard total > 0 else { return RGB(r: 0.5, g: 0.5, b: 0.5) }
        return RGB(r: r / total, g: g / total, b: b / total)
    }

    /// The row colour for a picture whose colour is `seed`: the seed's hue, its
    /// saturation capped at ``maxSaturation``, and the HSL lightness that puts
    /// the result on the appearance's fixed relative luminance.
    static func tint(seed: RGB, dark: Bool) -> RGB {
        let (hue, saturation) = hueSaturation(seed)
        let s = min(saturation * 1.2, maxSaturation)
        let target = dark ? luminanceDark : luminanceLight
        // Luminance grows monotonically with HSL lightness at a fixed hue and saturation.
        var low = 0.0, high = 1.0
        for _ in 0..<24 {
            let mid = (low + high) / 2
            if luminance(hsl(hue, s, mid)) < target { low = mid } else { high = mid }
        }
        return hsl(hue, s, (low + high) / 2)
    }

    /// WCAG relative luminance of an sRGB colour.
    static func luminance(_ c: RGB) -> Double {
        func linear(_ v: Double) -> Double { v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * linear(c.r) + 0.7152 * linear(c.g) + 0.0722 * linear(c.b)
    }

    /// The picture at 50 × 40 (1/16 of an 800 × 640 card, Android's
    /// `inSampleSize`), in the appearance's variant of the imageset.
    private static func sample(imageNamed name: String, dark: Bool) -> RGB? {
        #if canImport(UIKit)
        let traits = UITraitCollection(userInterfaceStyle: dark ? .dark : .light)
        guard let image = UIImage(named: name, in: .main, compatibleWith: traits)?.cgImage,
              let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        let width = 50, height = 40
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height,
                                          bitsPerComponent: 8, bytesPerRow: width * 4, space: space,
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.interpolationQuality = .low
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        return drawn ? seed(rgba: pixels) : nil
        #else
        return nil
        #endif
    }

    private static func hueSaturation(_ c: RGB) -> (Double, Double) {
        let maxC = max(c.r, c.g, c.b), minC = min(c.r, c.g, c.b)
        let delta = maxC - minC
        let l = (maxC + minC) / 2
        guard delta > 1e-6 else { return (0, 0) }
        let s = delta / (1 - abs(2 * l - 1))
        let h: Double
        switch maxC {
        case c.r: h = 60 * ((c.g - c.b) / delta).truncatingRemainder(dividingBy: 6).positiveModulo6
        case c.g: h = 60 * ((c.b - c.r) / delta + 2)
        default: h = 60 * ((c.r - c.g) / delta + 4)
        }
        return (h, min(max(s, 0), 1))
    }

    private static func hsl(_ h: Double, _ s: Double, _ l: Double) -> RGB {
        let c = (1 - abs(2 * l - 1)) * s
        let x = c * (1 - abs((h / 60).truncatingRemainder(dividingBy: 2) - 1))
        let m = l - c / 2
        let (r, g, b): (Double, Double, Double)
        switch h {
        case ..<60: (r, g, b) = (c, x, 0)
        case ..<120: (r, g, b) = (x, c, 0)
        case ..<180: (r, g, b) = (0, c, x)
        case ..<240: (r, g, b) = (0, x, c)
        case ..<300: (r, g, b) = (x, 0, c)
        default: (r, g, b) = (c, 0, x)
        }
        func clamp(_ v: Double) -> Double { min(max(v, 0), 1) }
        return RGB(r: clamp(r + m), g: clamp(g + m), b: clamp(b + m))
    }
}

private extension Double {
    /// Kotlin's `mod(6f)`: the remainder brought into 0..<6.
    var positiveModulo6: Double { self < 0 ? self + 6 : self }
}

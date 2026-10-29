import SwiftUI

/// The home screen's single focal point: a window onto the live dusk flight
/// (`HomeHeroStage`, drawn under the catalogue) with the Model Viewer's copy
/// at its bottom left — `type-display` title, `type-body` subtitle at 80 %
/// white, one 44 pt "Open" pill. No card, no corners, no shadow: the landscape
/// runs edge to edge behind it and the stage paints the legibility scrim. The
/// whole band is one button. The iOS twin of Android's featured window page
/// (`HomeFeatured.kt`, #3948).
///
/// The text colours are fixed tokens (`SceneViewTokens.HomeColor`), not system
/// roles: the stage is a dusk sky in both themes.
struct HomeHero: View {
    let height: CGFloat
    let onTap: () -> Void

    /// The helmet flying beside the camera on the hero stage: the Model
    /// Viewer's own first-run model, so the band shows what the tap opens.
    /// Guarded by `ViewerAssetTests.testHomeHeroModelShipsItsUSDZ`.
    static let heroAssetName = "khronos_damaged_helmet"

    /// Android's window page title — the demo it opens.
    private static let title = "Model Viewer"
    private static let subtitle = "Explore a model in 3D or in your room"

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
                Text(Self.title)
                    .font(SceneViewTokens.TypeScale.display)
                    .tracking(SceneViewTokens.TypeScale.displayTracking)
                    .foregroundStyle(SceneViewTokens.HomeColor.heroTitle)
                Text(Self.subtitle)
                    .font(SceneViewTokens.TypeScale.body)
                    .foregroundStyle(SceneViewTokens.HomeColor.heroSubtitle)
                    .lineLimit(2)
                HeroPill(label: "Open")
                    .padding(.top, SceneViewTokens.Space.sm)
            }
            .padding(SceneViewTokens.Home.heroPadding)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            .frame(height: height)
            .contentShape(Rectangle())
        }
        // The copy answers the press (0.98, or a dim under Reduce Motion); the
        // landscape behind it is the stage's and stays put.
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityLabel("\(Self.title). \(Self.subtitle). Open")
        .accessibilityIdentifier("home-hero")
    }
}

/// The hero's call to action — Android's `HeroPill`: a white 44 pt capsule,
/// the label then an arrow.
struct HeroPill: View {
    let label: String

    var body: some View {
        HStack(spacing: SceneViewTokens.Home.heroPillIconGap) {
            Text(label)
                .font(SceneViewTokens.TypeScale.bodySemibold)
            Image(systemName: "arrow.right")
                .font(.system(size: SceneViewTokens.Home.heroPillIconSize, weight: .semibold))
                .accessibilityHidden(true)
        }
        .foregroundStyle(SceneViewTokens.HomeColor.heroPillText)
        .padding(.horizontal, SceneViewTokens.Home.heroPillPaddingHorizontal)
        .frame(height: SceneViewTokens.Home.heroPillHeight)
        .background(SceneViewTokens.HomeColor.heroPillBackground, in: Capsule())
    }
}

// MARK: - Featured pager

/// Android's `FeaturedPager` (HomeFeatured.kt), at two pages: the window onto
/// the live stage (``HomeHero``, passed in so the Home keeps its zoom source),
/// then the illustrated What's new page. The second page only exists while a
/// home demo carries a "New" or "Updated" chip; with one page there is no
/// pager and no dots. Android's two banner pages (Explore online, Labs) are
/// rows further down the iOS Home and are not repeated here.
struct HomeFeaturedPager<Hero: View>: View {
    let height: CGFloat
    /// Home demos carrying a "New" / "Updated" chip.
    let freshCount: Int
    let onWhatsNew: () -> Void
    @ViewBuilder let hero: () -> Hero

    @State private var page: Int? = 0

    var body: some View {
        if freshCount > 0 {
            ScrollView(.horizontal) {
                // One `contentPadding` between pages, so the next page waits
                // just past the screen edge instead of peeking into the gutter.
                HStack(spacing: SceneViewTokens.Home.contentPadding) {
                    hero()
                        .containerRelativeFrame(.horizontal)
                        .id(0)
                    HomeWhatsNewPage(count: freshCount, onTap: onWhatsNew)
                        .frame(height: height)
                        .containerRelativeFrame(.horizontal)
                        .id(1)
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.viewAligned)
            .scrollPosition(id: $page)
            .scrollIndicators(.hidden)
            // The live stage and the card's shadow run past the page bounds.
            .scrollClipDisabled()
            .frame(height: height)
            .overlay(alignment: .bottomTrailing) {
                PagerDots(count: 2, selected: page ?? 0)
                    .padding(SceneViewTokens.Home.heroPadding)
                    .allowsHitTesting(false)
            }
            // A container element of its own: an identifier on a plain
            // container is pushed down onto the children, where the hero's
            // own identifiers win and `home-pager` is found nowhere.
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("home-pager")
        } else {
            hero()
        }
    }
}

/// Android's pager indicator: 6 pt dots, `space-xs` apart, the current one
/// in `hero-title`, the others `hero-subtitle` at 40 %.
private struct PagerDots: View {
    let count: Int
    let selected: Int

    var body: some View {
        HStack(spacing: SceneViewTokens.Space.xs) {
            ForEach(0..<count, id: \.self) { index in
                Circle()
                    .fill(index == selected
                          ? SceneViewTokens.HomeColor.heroTitle
                          : SceneViewTokens.HomeColor.heroSubtitle.opacity(0.4))
                    .frame(width: SceneViewTokens.Home.indicatorDot, height: SceneViewTokens.Home.indicatorDot)
            }
        }
        .animation(SceneViewTokens.Spring.animation, value: selected)
        .accessibilityHidden(true)
    }
}

/// The pager's What's new page — Android's `WhatsNewFeaturedPage`: a
/// `hero-field` card with the stage's scrim, the sparkle at its top end,
/// "New in 4.63", the count of new or updated demos and a "See what changed"
/// pill. Shadow in light, a hairline in dark, like every Home card.
struct HomeWhatsNewPage: View {
    let count: Int
    let onTap: () -> Void

    @Environment(\.colorScheme) private var colorScheme

    /// `4.63.0-beta` → `4.63`.
    static func majorMinor(_ version: String) -> String {
        WhatsNewSince.baseVersion(of: version).split(separator: ".").prefix(2).joined(separator: ".")
    }

    private var title: String { "New in \(Self.majorMinor(DemoFreshness.appVersion))" }
    private var subtitle: String {
        count == 1
            ? "1 sample is new or updated — tap to see which"
            : "\(count) samples are new or updated — tap to see which"
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: SceneViewTokens.Radius.xl, style: .continuous)
        Button(action: onTap) {
            ZStack(alignment: .bottomLeading) {
                shape.fill(SceneViewTokens.HomeColor.heroField)
                shape.fill(LinearGradient(
                    stops: [
                        .init(color: SceneViewTokens.SpatialGalleryColor.stageScrimStart,
                              location: SceneViewTokens.Home.heroScrimStart),
                        .init(color: SceneViewTokens.SpatialGalleryColor.stageScrimEnd, location: 1),
                    ],
                    startPoint: .top, endPoint: .bottom))
                Image(systemName: "sparkles")
                    .font(.system(size: SceneViewTokens.Home.featuredGlyph, weight: .medium))
                    .foregroundStyle(SceneViewTokens.HomeColor.heroSubtitle)
                    .padding(SceneViewTokens.Home.heroPadding)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
                    Text(title)
                        .font(SceneViewTokens.TypeScale.display)
                        .tracking(SceneViewTokens.TypeScale.displayTracking)
                        .foregroundStyle(SceneViewTokens.HomeColor.heroTitle)
                    Text(subtitle)
                        .font(SceneViewTokens.TypeScale.body)
                        .foregroundStyle(SceneViewTokens.HomeColor.heroSubtitle)
                        .lineLimit(2)
                        .frame(maxWidth: SceneViewTokens.Home.heroSubtitleMaxWidth, alignment: .leading)
                    HeroPill(label: "See what changed")
                        .padding(.top, SceneViewTokens.Space.sm)
                }
                .padding(SceneViewTokens.Home.heroPadding)
            }
            .overlay {
                if colorScheme == .dark {
                    shape.strokeBorder(SceneViewTokens.HomeColor.outlineSubtle,
                                       lineWidth: SceneViewTokens.Home.cardOutlineWidth)
                }
            }
            .background {
                if colorScheme != .dark {
                    let layers = SceneViewTokens.Shadow.md
                    ForEach(layers.indices, id: \.self) { index in
                        shape.fill(SceneViewTokens.HomeColor.heroField)
                            .shadow(color: .black.opacity(layers[index].opacity),
                                    radius: layers[index].radius, x: 0, y: layers[index].y)
                    }
                }
            }
            .contentShape(shape)
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityLabel("\(title). \(subtitle). See what changed")
        .accessibilityIdentifier("home-pager-whats-new")
    }
}

/// Press feedback on the one app spring: scale to 0.98 while pressed, no
/// highlight. Shared by the hero, the media cards and the chrome.
///
/// Under `accessibilityReduceMotion` the scale is dropped and the press reads
/// as a dim instead — the spec's "disable scale, keep opacity".
struct PressScaleButtonStyle: ButtonStyle {
    var scale: CGFloat = SceneViewTokens.Spring.pressScale

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? scale : 1)
            .opacity(configuration.isPressed && reduceMotion ? 0.8 : 1)
            .animation(SceneViewTokens.Spring.animation, value: configuration.isPressed)
    }
}

// MARK: - Catalogue entrance

/// `DESIGN.md` Motion, "Scroll reveal" + the catalogue's staggered entry: the
/// item fades in and rises `revealOffset` on `ease-expressive`, `position`
/// places it in the cascade — each slot starts `staggerStep` after the one
/// before it, capped at `staggerMaxDelay`.
///
/// **The cascade is driven by one flag the catalogue owns**, never by per-item
/// `@State` set from `onAppear`. A lazily built grid row that is rebuilt —
/// which is exactly what the home grid does while the hero's RealityKit stage
/// renders — would reset that state and replay its fade, and the measured
/// result was a catalogue pulsing between transparent and opaque forever. A
/// flag read from the parent cannot regress: a rebuilt card reads `true` and is
/// simply there.
///
/// Under `accessibilityReduceMotion` the rise and the cascade both go; the
/// opacity fade stays.
struct StaggeredReveal: ViewModifier {
    let position: Int
    /// The catalogue's entrance flag — flipped once, one frame after it appears.
    let revealed: Bool

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .opacity(revealed ? 1 : 0)
            .offset(y: revealed || reduceMotion ? 0 : SceneViewTokens.Motion.revealOffset)
            .animation(animation, value: revealed)
    }

    private var animation: Animation {
        guard !reduceMotion else {
            return SceneViewTokens.Motion.expressive(SceneViewTokens.Motion.medium)
        }
        return SceneViewTokens.Motion.reveal.delay(
            min(Double(position) * SceneViewTokens.Motion.staggerStep,
                SceneViewTokens.Motion.staggerMaxDelay)
        )
    }
}

extension View {
    /// Reveals this view as item `position` of a catalogue whose entrance flag
    /// is `revealed` — see ``StaggeredReveal``.
    func staggeredReveal(position: Int, revealed: Bool) -> some View {
        modifier(StaggeredReveal(position: position, revealed: revealed))
    }
}

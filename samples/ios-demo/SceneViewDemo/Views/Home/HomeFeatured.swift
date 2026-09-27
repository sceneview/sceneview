import SwiftUI

/// The Showcase's featured band — a horizontal pager, the iOS twin of Android's
/// `HomeFeatured.kt` (#3567).
///
/// Page one is the live ``HomeHero``, untouched. The pages after it are the
/// editorial picks Android features (`FEATURED_DEMO_IDS`), each drawn with the
/// hero's visual contract: `radius-xl` clip, the demo's own grid capture cropped
/// to fill, the scrim from transparent at 50 % to `stage-scrim-end`, bottom-left
/// copy — `type-display` title, `type-body` subtitle at 80 % white, one 44 pt
/// "Open" pill. Light: soft shadow; dark: 1 pt `outline`. Pages stay dark in both
/// themes, so their text colours are fixed `HomeColor` tokens.
///
/// Dots sit bottom-right over the scrim, opposite the copy, so the band stays
/// exactly the hero's height and the Showcase's vertical rhythm is unchanged.
struct HomeFeaturedPager<Hero: View>: View {
    /// The demos after the hero, in page order. Unknown ids are dropped by the caller.
    let pages: [DemoItem]
    let height: CGFloat
    /// The page the band shows, owned by the screen so the hero's stage can stop
    /// while another page is up.
    @Binding var page: Int
    let onOpen: (DemoItem) -> Void
    @ViewBuilder let hero: () -> Hero

    private var count: Int { pages.count + 1 }

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(spacing: SceneViewTokens.Space.md) {
                hero()
                    .containerRelativeFrame(.horizontal)
                    .id(0)
                ForEach(Array(pages.enumerated()), id: \.element.sceneId) { index, demo in
                    HomeFeaturedCard(demo: demo, height: height) { onOpen(demo) }
                        .containerRelativeFrame(.horizontal)
                        .id(index + 1)
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.viewAligned)
        .scrollIndicators(.hidden)
        .scrollPosition(id: Binding(get: { page }, set: { page = $0 ?? 0 }))
        // The cards' light-mode shadow needs room the horizontal clip would cut.
        .contentMargins(.vertical, SceneViewTokens.Space.md, for: .scrollContent)
        .padding(.vertical, -SceneViewTokens.Space.md)
        .frame(height: height)
        .overlay(alignment: .bottomTrailing) {
            if count > 1 {
                HomeFeaturedDots(count: count, selected: page)
                    .padding(SceneViewTokens.Home.heroPadding)
                    .allowsHitTesting(false)
            }
        }
        .accessibilityIdentifier("home-featured-pager")
    }
}

/// One featured demo page. Its media is the demo's own `preview_<id>` capture —
/// the same image its grid card shows, so featuring a demo needs no new artwork.
struct HomeFeaturedCard: View {
    let demo: DemoItem
    let height: CGFloat
    let onTap: () -> Void

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Button(action: onTap) {
            ZStack(alignment: .bottomLeading) {
                (colorScheme == .dark ? SceneViewTokens.HomeColor.surfaceContainer
                                      : SceneViewTokens.HomeColor.heroField)
                if let preview = demo.previewImageName {
                    Color.clear
                        .frame(height: height)
                        .overlay {
                            Image(preview)
                                .resizable()
                                .scaledToFill()
                        }
                        .clipped()
                }
                LinearGradient(
                    stops: [
                        .init(color: SceneViewTokens.SpatialGalleryColor.stageScrimStart,
                              location: SceneViewTokens.Home.heroScrimStart),
                        .init(color: colorScheme == .dark ? SceneViewTokens.HomeColor.surfaceContainer
                                                        : SceneViewTokens.SpatialGalleryColor.stageScrimEnd,
                              location: 1),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
                VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
                    Text(demo.title)
                        .font(SceneViewTokens.TypeScale.display)
                        .tracking(SceneViewTokens.TypeScale.displayTracking)
                        .foregroundStyle(SceneViewTokens.HomeColor.heroTitle)
                    Text(demo.subtitle)
                        .font(SceneViewTokens.TypeScale.body)
                        .foregroundStyle(SceneViewTokens.HomeColor.heroSubtitle)
                        .lineLimit(2)
                        .frame(maxWidth: SceneViewTokens.Home.heroSubtitleMaxWidth, alignment: .leading)
                    Text("Open")
                        .font(SceneViewTokens.TypeScale.bodySemibold)
                        .foregroundStyle(SceneViewTokens.HomeColor.heroPillText)
                        .padding(.horizontal, SceneViewTokens.Home.heroPillPaddingHorizontal)
                        .frame(height: SceneViewTokens.Home.heroPillHeight)
                        .background(SceneViewTokens.HomeColor.heroPillBackground, in: Capsule())
                        .padding(.top, SceneViewTokens.Space.sm)
                }
                .padding(SceneViewTokens.Home.heroPadding)
            }
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.xl, style: .continuous))
            .overlay {
                if colorScheme == .dark {
                    RoundedRectangle(cornerRadius: SceneViewTokens.Radius.xl, style: .continuous)
                        .strokeBorder(SceneViewTokens.HomeColor.outline,
                                      lineWidth: SceneViewTokens.Home.cardOutlineWidth)
                }
            }
            .shadow(color: .black.opacity(colorScheme == .dark ? 0 : 0.12), radius: 12, y: 4)
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityLabel("\(demo.title). \(demo.subtitle). Open")
        .accessibilityIdentifier("home-featured-\(demo.sceneId)")
    }
}

/// The carousel dots — fixed hero tokens, because the band is dark in both themes.
private struct HomeFeaturedDots: View {
    let count: Int
    let selected: Int

    /// 6 pt dot, the carousel convention; matches Android's `indicatorDot`.
    private static let dot: CGFloat = 6

    var body: some View {
        HStack(spacing: SceneViewTokens.Space.xs) {
            ForEach(0..<count, id: \.self) { index in
                Circle()
                    .fill(index == selected ? SceneViewTokens.HomeColor.heroTitle
                                            : SceneViewTokens.HomeColor.heroSubtitle.opacity(0.4))
                    .frame(width: Self.dot, height: Self.dot)
            }
        }
        .animation(SceneViewTokens.Spring.fade, value: selected)
        .accessibilityHidden(true)
    }
}

import SwiftUI

/// One demo on the Home list (`home-row` in `DESIGN.md`) — the iOS twin of
/// Android's `DemoListRow` (`HomeListRow.kt`, #4186): a standard two-line list
/// item, not a showcase card.
///
/// The 3D header above is the only showpiece on the screen; everything under
/// it reads like the settings or the library of any well-made app, which is the
/// point the Home makes: the scene drops into an ordinary app. Anatomy: a
/// `home-row-media` picture of the demo's own capture, 5:4 like the capture so
/// the generated scene reads rather than a 56 pt crop of it (#4201; the category
/// glyph at the same size while none exists), the title in `type-body` semibold, the subtitle in
/// `type-caption` regular, and the "New" / "Updated" / status chips on the
/// title line so the subtitle keeps the row's full width. The row sits on the
/// `home-row-bg` tile and takes its corners from its place in its group
/// (``HomeRowCorners``), so a section reads as one grey block split by
/// `home-row-gap` seams of page.
struct DemoListRow: View {
    let demo: DemoItem
    let corners: HomeRowCorners
    let onTap: () -> Void

    var body: some View {
        let freshness = DemoFreshness.of(demo)
        HomeListRow(title: demo.title, subtitle: demo.subtitle, corners: corners, onTap: onTap) {
            if let preview = demo.previewImageName {
                // Anchored like Android's `FEATURED_MEDIA_ALIGNMENT`: a preview
                // listed there keeps one edge in frame when cropped. The
                // captures are 5:4 themselves, so at `home-row-media` the whole
                // scene shows; the anchor only matters to a wider capture.
                Color.clear
                    .overlay(alignment: HomeCatalogue.leadingAnchored.contains(demo.sceneId) ? .leading : .center) {
                        Image(preview)
                            .resizable()
                            .scaledToFill()
                    }
                    .homeRowMedia()
            } else {
                HomeRowGlyphThumb(systemName: demo.icon, tint: demo.category.accent, media: true)
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
/// `BrowseOnlineRow`: one more list item, a globe on the thumb square, so it
/// sits in the list's rhythm instead of being a banner of its own.
struct BrowseOnlineRow: View {
    let onTap: () -> Void

    var body: some View {
        HomeListRow(title: "Browse online models",
                    subtitle: "Discover models from online collections",
                    corners: .lone,
                    onTap: onTap) {
            HomeRowGlyphThumb(systemName: "globe", tint: SceneViewTokens.HomeColor.primary)
        } badges: {
            EmptyView()
        }
        .accessibilityLabel("Browse online models")
        .accessibilityHint("Discover models from online collections")
    }
}

/// Which corners of a row are its group's outer corners — Android's
/// `rowCorners`. A group is one block: its four outer corners take
/// `home-row-radius-outer`, every corner shared with a neighbour takes
/// `home-row-radius-inner`. With a short last line the block ends in a step,
/// and the corner over the step is outer too.
struct HomeRowCorners: Equatable {
    var topLeading: Bool
    var topTrailing: Bool
    var bottomTrailing: Bool
    var bottomLeading: Bool

    /// A row alone in its group: four outer corners.
    static let lone = HomeRowCorners(topLeading: true, topTrailing: true, bottomTrailing: true, bottomLeading: true)

    /// The corners of the row at `index` of a group of `count` rows laid out
    /// `columns` across.
    init(index: Int, count: Int, columns: Int) {
        let cols = max(columns, 1)
        let row = index / cols
        let col = index % cols
        let lastRow = (count - 1) / cols
        let lastInLine = col == cols - 1 || index == count - 1
        let nothingBelow = index + cols > count - 1
        self.init(topLeading: row == 0 && col == 0,
                  topTrailing: row == 0 && lastInLine,
                  bottomTrailing: nothingBelow && lastInLine,
                  bottomLeading: row == lastRow && col == 0)
    }

    init(topLeading: Bool, topTrailing: Bool, bottomTrailing: Bool, bottomLeading: Bool) {
        self.topLeading = topLeading
        self.topTrailing = topTrailing
        self.bottomTrailing = bottomTrailing
        self.bottomLeading = bottomLeading
    }

    /// The row's tile shape.
    var shape: UnevenRoundedRectangle {
        func radius(_ outer: Bool) -> CGFloat {
            outer ? SceneViewTokens.Home.rowRadiusOuter : SceneViewTokens.Home.rowRadiusInner
        }
        return UnevenRoundedRectangle(
            topLeadingRadius: radius(topLeading),
            bottomLeadingRadius: radius(bottomLeading),
            bottomTrailingRadius: radius(bottomTrailing),
            topTrailingRadius: radius(topTrailing),
            style: .continuous
        )
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

private struct HomeListRow<Leading: View, Badges: View>: View {
    let title: String
    let subtitle: String
    let corners: HomeRowCorners
    let onTap: () -> Void
    @ViewBuilder let leading: () -> Leading
    /// Chips drawn after the title, on its line.
    @ViewBuilder let badges: () -> Badges

    // The text follows Dynamic Type, as Android's follows the font scale: the
    // `type-*` sizes are the default-size values, scaled with the text style of
    // the same size. The row grows with it; nothing truncates.
    @ScaledMetric(relativeTo: .subheadline) private var titleSize = SceneViewTokens.TypeScale.bodySize
    @ScaledMetric(relativeTo: .footnote) private var subtitleSize = SceneViewTokens.TypeScale.captionSize

    var body: some View {
        let home = SceneViewTokens.Home.self
        Button(action: onTap) {
            HStack(spacing: home.rowPaddingHorizontal) {
                leading()
                VStack(alignment: .leading, spacing: home.rowTextGap) {
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
            .padding(.horizontal, home.rowPaddingHorizontal)
            .padding(.vertical, home.rowPaddingVertical)
            .frame(maxWidth: .infinity, minHeight: home.rowMinHeight, maxHeight: .infinity, alignment: .leading)
            .contentShape(corners.shape)
        }
        .buttonStyle(HomeRowButtonStyle(shape: corners.shape))
        .dynamicTypeSize(...DemoMediaCard.largestTypeSize)
        .accessibilityElement(children: .combine)
    }
}

/// The row's tile, and its press: `on-surface` at the pressed state-layer
/// opacity over the grey, like a list cell's highlight. No scale, no shadow,
/// no outline in either theme — the tone carries it.
private struct HomeRowButtonStyle: ButtonStyle {
    let shape: UnevenRoundedRectangle

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                shape.fill(SceneViewTokens.HomeColor.surfaceContainerHigh)
                    .overlay {
                        shape.fill(SceneViewTokens.HomeColor.onSurface
                            .opacity(configuration.isPressed ? SceneViewTokens.Home.rowPressedAlpha : 0))
                    }
            }
            .animation(SceneViewTokens.Spring.fade, value: configuration.isPressed)
    }
}

/// A glyph one step up the surface ramp from the row: on the `home-row-media`
/// frame for a demo without a capture, on the `home-row-thumb` square for a
/// utility row.
private struct HomeRowGlyphThumb: View {
    let systemName: String
    let tint: Color
    var media = false

    var body: some View {
        let tile = ZStack {
            SceneViewTokens.HomeColor.surfaceContainerHighest
            Image(systemName: systemName)
                .font(.system(size: SceneViewTokens.Home.rowThumbGlyph))
                .foregroundStyle(tint)
        }
        Group {
            if media { tile.homeRowMedia() } else { tile.homeRowThumb() }
        }
        .accessibilityHidden(true)
    }
}

private extension View {
    /// The thumb square: `home-row-thumb`, `radius-sm`.
    func homeRowThumb() -> some View {
        frame(width: SceneViewTokens.Home.rowThumb, height: SceneViewTokens.Home.rowThumb)
            .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.sm, style: .continuous))
    }

    /// A demo row's picture: `home-row-media`, 120 pt at 5:4, `radius-sm`.
    func homeRowMedia() -> some View {
        let home = SceneViewTokens.Home.self
        return frame(width: home.rowMediaWidth, height: home.rowMediaWidth / home.rowMediaAspect)
            .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.sm, style: .continuous))
    }
}

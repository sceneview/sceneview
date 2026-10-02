import SwiftUI

/// The "What's new" sheet — Android's `WhatsNewSheet` (WhatsNewUi.kt): the
/// latest releases' highlights from the bundled `CHANGELOG.md`, newest first,
/// under a "New in this build — try them" list of the demos that carry a "New"
/// or "Updated" chip (and any still in review). Everything on it is derived.
///
/// A demo row closes the sheet and opens the demo; the Home brings the sheet
/// back when the demo closes (``WhatsNewSheetPhase``).
struct WhatsNewSheet: View {
    let releases: [WhatsNewRelease]
    let tryDemos: [DemoItem]
    let onDemo: (DemoItem) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
                Text("What's new")
                    .font(SceneViewTokens.TypeScale.title)
                    .tracking(SceneViewTokens.TypeScale.titleTracking)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("whats-new-title")
                    .padding(.bottom, -SceneViewTokens.Space.sm)
                Text("Recently shipped and fixed, straight from the release notes")
                    .font(SceneViewTokens.TypeScale.captionRegular)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)

                if !tryDemos.isEmpty {
                    Text("New in this build — try them")
                        .font(SceneViewTokens.TypeScale.bodySemibold)
                        .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(tryDemos, id: \.sceneId) { demo in
                        TryDemoRow(demo: demo) { onDemo(demo) }
                            .accessibilityIdentifier("whats-new-demo-\(demo.sceneId)")
                    }
                }

                ForEach(releases) { release in
                    ReleaseSection(release: release)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, SceneViewTokens.Space.lg - SceneViewTokens.Space.xs)
            .padding(.top, SceneViewTokens.Space.lg)
            // Clear of the home indicator at the bottom of the last release.
            .padding(.bottom, SceneViewTokens.Space.xl)
        }
        .scrollIndicators(.hidden)
        .accessibilityIdentifier("whats-new-sheet")
    }
}

/// Which way the What's new sheet is — Android's `ReturningSheetState`. A demo
/// opened from the sheet closes it for the trip and brings it back on return,
/// so back from a demo lands on the list the user was working through, not on
/// a bare Home.
enum WhatsNewSheetPhase: Equatable {
    case closed
    case open
    /// A demo in the sheet was opened: the sheet is hidden now, shown again
    /// once the demo closes.
    case reopenOnReturn

    mutating func open() { self = .open }
    /// A plain dismissal: swipe down or the scrim.
    mutating func dismiss() { if self == .open { self = .closed } }
    mutating func leaveForSample() { self = .reopenOnReturn }
    /// The demo opened from the sheet has closed.
    mutating func hostReturned() { if self == .reopenOnReturn { self = .open } }
}

/// "Try them" row — Android's `InReviewDemoRow`: the demo's symbol in its
/// section accent, title, a two-line subtitle, and in debug builds the internal
/// "In review" label.
private struct TryDemoRow: View {
    let demo: DemoItem
    let onTap: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous)
        Button(action: onTap) {
            HStack(spacing: SceneViewTokens.Space.sm + SceneViewTokens.Space.xs) {
                Image(systemName: demo.icon)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(demo.section.accent)
                    .frame(width: 24)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(demo.title)
                        .font(SceneViewTokens.TypeScale.bodySemibold)
                        .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                        .lineLimit(1)
                    Text(demo.subtitle)
                        .font(SceneViewTokens.TypeScale.captionRegular)
                        .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                #if DEBUG
                if demo.status == .inReview {
                    Text("In review")
                        .font(SceneViewTokens.TypeScale.caption)
                        .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                }
                #endif
            }
            .padding(.horizontal, SceneViewTokens.Space.md - 2)
            .padding(.vertical, SceneViewTokens.Space.sm + 2)
            .frame(minHeight: SceneViewTokens.Layout.touchTarget)
            .background(SceneViewTokens.HomeColor.surfaceContainerHigh, in: shape)
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens the demo")
    }
}

/// One release — Android's `ReleaseSection`: "v4.51.0" and its date, the
/// title when there is one, then a group per category in Added → Removed order.
private struct ReleaseSection: View {
    let release: WhatsNewRelease

    var body: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
            HStack(alignment: .firstTextBaseline) {
                Text("v\(release.version)")
                    .font(.system(size: SceneViewTokens.TypeScale.cardSize, weight: .bold))
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                if let date = release.date {
                    Text(date)
                        .font(SceneViewTokens.TypeScale.caption)
                        .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                }
            }
            if let title = release.title {
                Text(title)
                    .font(SceneViewTokens.TypeScale.body)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
            }
            ForEach(WhatsNewCategory.allCases, id: \.self) { category in
                let group = release.highlights.filter { $0.category == category }
                if !group.isEmpty {
                    CategoryGroup(category: category, highlights: group)
                }
            }
        }
        .accessibilityIdentifier("whats-new-release-\(release.version)")
    }
}

private struct CategoryGroup: View {
    let category: WhatsNewCategory
    let highlights: [WhatsNewHighlight]

    var body: some View {
        let accent = category.accent
        VStack(alignment: .leading, spacing: 6) {
            Text(category.label)
                .font(SceneViewTokens.TypeScale.captionSemibold)
                .foregroundStyle(accent)
                .padding(.top, SceneViewTokens.Space.xs)
            ForEach(Array(highlights.enumerated()), id: \.offset) { _, highlight in
                HStack(alignment: .firstTextBaseline, spacing: SceneViewTokens.Space.sm) {
                    Circle()
                        .fill(accent)
                        .frame(width: 6, height: 6)
                        // On the first line's x-height, not its baseline.
                        .alignmentGuide(.firstTextBaseline) { $0[.bottom] + 3 }
                        .accessibilityHidden(true)
                    Text(highlight.headline)
                        .font(SceneViewTokens.TypeScale.captionRegular)
                        .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                        .lineSpacing(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .accessibilityElement(children: .combine)
            }
        }
    }
}

extension WhatsNewCategory {
    /// Android's `categoryAccent`: Added `primary`, Fixed `tertiary`, Changed
    /// and Performance `secondary`, Removed `error`.
    var accent: Color {
        switch self {
        case .added: return SceneViewTokens.HomeColor.primary
        case .fixed: return SceneViewTokens.HomeColor.tertiary
        case .changed, .performance: return SceneViewTokens.HomeColor.secondary
        case .removed: return SceneViewTokens.HomeColor.error
        }
    }
}

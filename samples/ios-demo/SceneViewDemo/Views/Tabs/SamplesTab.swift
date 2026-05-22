import SwiftUI
import SceneViewSwift

/// Samples tab — curated preset scenes grouped by category, presented as
/// Liquid Glass cards in a scrollable list.
///
/// Tapping an available sample opens it in a `.fullScreenCover`: every demo
/// mounts a full-screen `SceneView` (RealityView) viewport that needs the whole
/// screen — a partial sheet detent renders the 3D surface as a black, half-height
/// panel that obscures the list (#1392). Only the lightweight `ComingSoonScreen`
/// (a plain scrollable info screen, no 3D surface) uses the `.sheet`.
///
/// A `3D / AR` filter chip at the top filters the visible categories.
struct SamplesTab: View {
    // Lazy initialization on first access so the @MainActor constraint of
    // GeneratedScenes.all() is satisfied — View init runs on the main actor
    // but Swift requires the @MainActor annotation at the call site for
    // nonisolated stored property defaults.
    @State private var scenes: [DemoItem] = []

    @State private var selectedScene: DemoItem?
    @State private var fullScreenScene: DemoItem?
    @State private var filter: ScopeFilter = .all

    private enum ScopeFilter: String, CaseIterable, Identifiable {
        case all = "All"
        case threeD = "3D"
        case ar = "AR"

        var id: String { rawValue }
        var icon: String {
            switch self {
            case .all: return "square.grid.2x2.fill"
            case .threeD: return "cube.fill"
            case .ar: return "arkit"
            }
        }
    }

    private static func shouldOpenFullScreen(_ scene: DemoItem) -> Bool {
        // Every available demo mounts a full-screen `SceneView` (RealityView)
        // viewport. A partial `.medium` sheet detent renders that 3D surface as
        // a black, half-height panel that covers the Samples list (#1392), so
        // all available demos — 3D and AR alike — take the whole screen.
        // Coming-soon entries route to the lightweight `ComingSoonScreen` and
        // stay in the `.sheet`.
        scene.status.isAvailable
    }

    private var filteredScenes: [DemoItem] {
        switch filter {
        case .all: return scenes
        case .threeD: return scenes.filter { $0.category != .ar }
        case .ar: return scenes.filter { $0.category == .ar }
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 24) {
                    filterBar

                    let grouped = Dictionary(grouping: filteredScenes) { $0.category }
                    let sortedCategories = grouped.keys.sorted()
                    ForEach(sortedCategories, id: \.self) { category in
                        categorySection(category: category, items: grouped[category]!)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .navigationTitle("Samples")
            .onAppear {
                if scenes.isEmpty {
                    scenes = Self.allScenes()
                }
            }
            .sheet(item: $selectedScene) { scene in
                sheetDestination(for: scene)
                    #if os(iOS)
                    .presentationDetents([.medium, .large])
                    .presentationBackground(.regularMaterial)
                    .presentationCornerRadius(28)
                    .presentationDragIndicator(.visible)
                    #endif
            }
            #if os(iOS)
            .fullScreenCover(item: $fullScreenScene) { scene in
                NavigationStack {
                    sheetDestination(for: scene)
                        // A `.fullScreenCover` has no drag-to-dismiss handle
                        // (unlike the `.sheet` path), so it needs an explicit
                        // Close affordance or the user gets stuck inside the
                        // demo with no way back to the Samples list — see
                        // #1580. Matches the Close button the deep-link
                        // placeholder already uses (DemoDeepLinkRegistry).
                        .toolbar {
                            ToolbarItem(placement: .topBarLeading) {
                                Button {
                                    SceneViewHaptic.shared.light()
                                    fullScreenScene = nil
                                } label: {
                                    Label("Close", systemImage: "xmark")
                                }
                                .accessibilityLabel("Close demo")
                                .accessibilityIdentifier("demo-close")
                            }
                        }
                }
            }
            #endif
        }
    }

    // MARK: - Filter bar

    private var filterBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(ScopeFilter.allCases) { option in
                    Button {
                        filter = option
                        #if os(iOS)
                        SceneViewHaptic.shared.selection()
                        #endif
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: option.icon)
                                .font(.caption.weight(.semibold))
                            Text(option.rawValue)
                                .font(.subheadline.weight(.medium))
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(
                            filter == option
                                ? AnyShapeStyle(.tint)
                                : AnyShapeStyle(.regularMaterial),
                            in: Capsule()
                        )
                        .foregroundStyle(filter == option ? Color.white : Color.primary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(option.rawValue) filter")
                    .accessibilityAddTraits(filter == option ? .isSelected : [])
                }
            }
        }
        .scrollClipDisabled()
    }

    // MARK: - Category section

    private func categorySection(category: DemoCategory, items: [DemoItem]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(category.rawValue)
                .font(.title3.weight(.bold))
                .padding(.horizontal, 4)

            VStack(spacing: 8) {
                ForEach(items) { scene in
                    Button {
                        handleTap(scene)
                    } label: {
                        SceneRow(scene: scene)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(accessibilityLabel(for: scene))
                }
            }
        }
    }

    private func handleTap(_ scene: DemoItem) {
        #if os(iOS)
        SceneViewHaptic.shared.light()
        if Self.shouldOpenFullScreen(scene) {
            fullScreenScene = scene
        } else {
            selectedScene = scene
        }
        #else
        selectedScene = scene
        #endif
    }

    @ViewBuilder
    private func sheetDestination(for scene: DemoItem) -> some View {
        switch scene.status {
        case .available:
            scene.destination
                .navigationTitle(scene.title)
                .navigationBarTitleInline()
        case .comingSoon:
            ComingSoonScreen(
                title: scene.title,
                subtitle: scene.subtitle,
                icon: scene.icon
            )
        }
    }

    private func accessibilityLabel(for scene: DemoItem) -> String {
        switch scene.status {
        case .available:
            return "\(scene.title): \(scene.subtitle)"
        case .comingSoon:
            return "\(scene.title): \(scene.subtitle). Coming soon."
        }
    }

    // MARK: - Scene catalog

    /// Returns all demos, sourced from the append-only `GeneratedScenes` registry.
    ///
    /// To add a demo, create a `*Scene.swift` file under
    /// `Views/Demos/Scenes/` and run (or let Xcode run)
    /// `samples/ios-demo/scripts/collate-ios-demos.sh`. No other file
    /// needs to be edited — this function delegates entirely to the
    /// generated aggregate (issue #1872).
    @MainActor
    private static func allScenes() -> [DemoItem] {
        GeneratedScenes.all()
    }
}

// MARK: - Scene row view (Liquid Glass card)

private struct SceneRow: View {
    let scene: DemoItem

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: scene.status.isAvailable
                                ? [Color.blue.opacity(0.25), Color.purple.opacity(0.15)]
                                : [Color.secondary.opacity(0.15), Color.secondary.opacity(0.08)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                Image(systemName: scene.icon)
                    .font(.title3)
                    .foregroundStyle(scene.status.isAvailable ? Color.blue : Color.secondary)
            }
            .frame(width: 44, height: 44)
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(scene.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(scene.status.isAvailable ? Color.primary : Color.secondary)
                        .lineLimit(1)

                    if scene.status.isComingSoon {
                        Text("Soon")
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(.orange.opacity(0.18), in: Capsule())
                            .foregroundStyle(.orange)
                    }
                }

                Text(scene.subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .opacity(scene.status.isAvailable ? 1.0 : 0.7)
            }

            Spacer(minLength: 4)

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.06), lineWidth: 0.5)
        )
    }
}

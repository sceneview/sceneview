import SwiftUI
import SceneViewSwift

/// The Showcase tab — the iOS twin of Android's `HomeScreen.kt`.
///
/// One scroll view, no nested scroll, no background scene: a 56 pt header
/// (cube mark + wordmark + search), the `HomeHero`, the category chip row,
/// then every demo as a `DemoMediaCard` in flat editorial `DemoItem.order`,
/// closed by a `BrowseOnlineModelsCard` that pushes the online gallery
/// (`ExploreTab`, embedded) onto this stack.
///
/// The header is a pinned overlay drawn over the scroll view: transparent
/// while the hero is on screen, `surface` at 94 % plus a bottom hairline once
/// the content has scrolled under it. Its search action swaps the wordmark row
/// for a 48 pt field that filters title / subtitle / category / tags
/// (`filterDemos`, pure and unit-tested in `HomeFilterTests`).
///
/// Demos open in a `.fullScreenCover` exactly as the former Samples tab did —
/// every demo mounts a full-screen RealityKit viewport (#1392). The cover
/// keeps the `Close` toolbar item (`demo-close`) for demos that have no glass
/// chrome of their own; `.demoChrome` hides that navigation bar and draws its
/// own back button under the same identifier.
struct ShowcaseTab: View {
    @State private var scenes: [DemoItem] = []
    @State private var selectedCategory: DemoCategory?
    @State private var query = ""
    @State private var searchOpen = false
    @State private var scrolled = false
    @State private var fullScreenScene: DemoItem?
    @State private var comingSoonScene: DemoItem?
    @State private var showExplore = false

    @Environment(\.horizontalSizeClass) private var sizeClass

    private var expanded: Bool { sizeClass == .regular }
    private var searching: Bool { !query.trimmingCharacters(in: .whitespaces).isEmpty }

    private var visible: [DemoItem] {
        let byId = Dictionary(uniqueKeysWithValues: scenes.map { ($0.sceneId, $0) })
        return filterDemos(scenes.map(HomeSearchEntry.init), category: selectedCategory, query: query)
            .compactMap { byId[$0.id] }
    }

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: expanded ? SceneViewTokens.Home.gridMinCellExpanded
                                              : SceneViewTokens.Home.gridMinCell),
                  spacing: SceneViewTokens.Home.gridGutter)]
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    // The pinned header overlay covers this band; the spacer keeps
                    // the hero from starting underneath it.
                    Color.clear.frame(height: SceneViewTokens.Home.headerHeight + SceneViewTokens.Home.heroTopGap)

                    // While a query is active the hero steps aside so the results
                    // sit right under the header (Android parity).
                    if !searching {
                        HomeHero(height: expanded ? SceneViewTokens.Home.heroHeightExpanded
                                                  : SceneViewTokens.Home.heroHeight) {
                            open(sceneId: Self.heroDemoId)
                        }
                    }

                    CategoryChipRow(selected: $selectedCategory)
                        .padding(.top, searching ? 0 : SceneViewTokens.Home.chipRowTopGap)
                        .padding(.bottom, SceneViewTokens.Home.gridTopGap)

                    if visible.isEmpty && searching {
                        EmptySearchState(query: query) { query = "" }
                    }

                    LazyVGrid(columns: columns, spacing: SceneViewTokens.Home.gridGutter) {
                        ForEach(visible) { demo in
                            DemoMediaCard(demo: demo) { open(demo) }
                        }
                        if !searching {
                            BrowseOnlineModelsCard { showExplore = true }
                        }
                    }
                    .animation(SceneViewTokens.Spring.animation, value: visible.map(\.sceneId))
                }
                .animation(SceneViewTokens.Spring.fade, value: searching)
                .padding(.horizontal, SceneViewTokens.Home.contentPadding)
                .padding(.bottom, SceneViewTokens.Home.gridBottomInset)
            }
            .background(SceneViewTokens.HomeColor.surface)
            .scrollDismissesKeyboard(.immediately)
            .onScrollGeometryChange(for: Bool.self) { geometry in
                geometry.contentOffset.y + geometry.contentInsets.top > SceneViewTokens.Home.heroTopGap
            } action: { _, isScrolled in
                withAnimation(SceneViewTokens.Spring.fade) { scrolled = isScrolled }
            }
            .overlay(alignment: .top) {
                HomeHeader(scrolled: scrolled, query: $query, searchOpen: $searchOpen)
            }
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(isPresented: $showExplore) {
                ExploreTab(embedded: true)
            }
            .onAppear {
                if scenes.isEmpty { scenes = GeneratedScenes.all() }
            }
            .sheet(item: $comingSoonScene) { scene in
                ComingSoonScreen(
                    title: scene.title,
                    subtitle: scene.subtitle,
                    icon: scene.icon,
                    androidOnlyReason: scene.androidOnlyReason
                )
                #if os(iOS)
                .presentationDetents([.medium, .large])
                .presentationBackground(.regularMaterial)
                .presentationCornerRadius(SceneViewTokens.Radius.xl)
                .presentationDragIndicator(.visible)
                #endif
            }
            #if os(iOS)
            .fullScreenCover(item: $fullScreenScene) { scene in
                DemoCover(scene: scene) { fullScreenScene = nil }
            }
            #else
            .sheet(item: $fullScreenScene) { scene in
                DemoCover(scene: scene) { fullScreenScene = nil }
            }
            #endif
        }
    }

    /// The demo the hero opens.
    static let heroDemoId = "model-viewer"

    private func open(sceneId: String) {
        guard let scene = scenes.first(where: { $0.sceneId == sceneId }) else { return }
        open(scene)
    }

    private func open(_ scene: DemoItem) {
        #if os(iOS)
        SceneViewHaptic.shared.light()
        #endif
        if scene.status.isAvailable {
            fullScreenScene = scene
        } else {
            comingSoonScene = scene
        }
    }
}

/// Full-screen host of one demo. A `.fullScreenCover` has no drag-to-dismiss
/// handle, so it needs an explicit Close affordance (#1580); `.demoChrome`
/// hides this bar and draws its own glass back button instead.
struct DemoCover: View {
    let scene: DemoItem
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            scene.destination
                .navigationTitle(scene.title)
                .navigationBarTitleInline()
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button {
                            #if os(iOS)
                            SceneViewHaptic.shared.light()
                            #endif
                            onClose()
                        } label: {
                            Label("Close", systemImage: "xmark")
                        }
                        .accessibilityLabel("Close demo")
                        .accessibilityIdentifier("demo-close")
                    }
                }
        }
        .environment(\.demoTitle, scene.title)
    }
}

// MARK: - Header

private struct HomeHeader: View {
    let scrolled: Bool
    @Binding var query: String
    @Binding var searchOpen: Bool

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                if searchOpen {
                    SearchRow(query: $query) {
                        query = ""
                        searchOpen = false
                    }
                    .transition(.opacity)
                } else {
                    TitleRow { searchOpen = true }
                        .transition(.opacity)
                }
            }
            .frame(height: SceneViewTokens.Home.headerHeight)
            .animation(SceneViewTokens.Spring.fade, value: searchOpen)
            Rectangle()
                .fill(SceneViewTokens.HomeColor.outlineSubtle)
                .frame(height: SceneViewTokens.Home.cardOutlineWidth)
                .opacity(scrolled ? 1 : 0)
        }
        .background(
            SceneViewTokens.HomeColor.surface
                .opacity(scrolled || searchOpen ? SceneViewTokens.HomeColor.headerOverlayAlpha : 0)
                .ignoresSafeArea(edges: .top)
        )
    }
}

private struct TitleRow: View {
    let onSearch: () -> Void

    var body: some View {
        HStack(spacing: SceneViewTokens.Space.sm + 2) {
            Image(systemName: "cube.fill")
                .font(.system(size: SceneViewTokens.Home.markSize - 4, weight: .semibold))
                .foregroundStyle(SceneViewTheme.primary)
                .frame(width: SceneViewTokens.Home.markSize, height: SceneViewTokens.Home.markSize)
                .accessibilityHidden(true)
            Text("SceneView")
                .font(SceneViewTokens.TypeScale.title)
                .tracking(SceneViewTokens.TypeScale.titleTracking)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
            Spacer()
            Button(action: onSearch) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                    .frame(width: SceneViewTokens.Layout.touchTarget, height: SceneViewTokens.Layout.touchTarget)
            }
            .buttonStyle(.plain)
            .offset(x: 12)
            .accessibilityLabel("Search demos")
            .accessibilityIdentifier("home-search")
        }
        .padding(.horizontal, SceneViewTokens.Home.contentPadding)
    }
}

private struct SearchRow: View {
    @Binding var query: String
    let onClose: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: SceneViewTokens.Space.sm) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
            TextField("Search demos", text: $query)
                .font(SceneViewTokens.TypeScale.body)
                .focused($focused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .accessibilityIdentifier("home-search-field")
            Button {
                // One tap: drop focus (keyboard), clear the query, collapse the field.
                focused = false
                onClose()
            } label: {
                Image(systemName: "xmark")
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                    .frame(width: SceneViewTokens.Layout.touchTarget - 8, height: SceneViewTokens.Layout.touchTarget - 8)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close search")
        }
        .padding(.leading, SceneViewTokens.Space.md)
        .padding(.trailing, SceneViewTokens.Space.xs)
        .frame(height: SceneViewTokens.Home.searchFieldHeight)
        .background(SceneViewTokens.HomeColor.surface, in: Capsule())
        .overlay(Capsule().strokeBorder(focused ? SceneViewTokens.HomeColor.onSurfaceDim
                                                : SceneViewTokens.HomeColor.outlineSubtle,
                                        lineWidth: SceneViewTokens.Home.cardOutlineWidth))
        .padding(.horizontal, SceneViewTokens.Home.contentPadding)
        .onAppear { focused = true }
    }
}

// MARK: - Category chips

/// `nil` = All. Order is the chip order.
private let chipCategories: [DemoCategory?] = [nil, .basics3D, .lighting, .content, .interaction, .advanced, .ar]

private struct CategoryChipRow: View {
    @Binding var selected: DemoCategory?

    var body: some View {
        // Bleeds to the screen edges (cancelling the page inset) and carries the
        // side inset as leading *and* trailing content padding, so the last chip
        // stops at the same margin as the cards.
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: SceneViewTokens.Home.chipGap) {
                ForEach(chipCategories, id: \.self) { category in
                    CategoryChip(label: category?.shortLabel ?? "All", selected: category == selected) {
                        #if os(iOS)
                        SceneViewHaptic.shared.selection()
                        #endif
                        selected = category
                    }
                }
            }
            .padding(.horizontal, SceneViewTokens.Home.contentPadding)
        }
        .padding(.horizontal, -SceneViewTokens.Home.contentPadding)
    }
}

private struct CategoryChip: View {
    let label: String
    let selected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            Text(label)
                .font(selected ? SceneViewTokens.TypeScale.bodyMedium : SceneViewTokens.TypeScale.body)
                .foregroundStyle(selected ? SceneViewTokens.HomeColor.chipSelectedText
                                          : SceneViewTokens.HomeColor.chipText)
                .lineLimit(1)
                .padding(.horizontal, SceneViewTokens.Home.chipPaddingHorizontal)
                .frame(height: SceneViewTokens.Home.chipRowHeight)
                .background(selected ? SceneViewTokens.HomeColor.chipSelectedBackground
                                     : SceneViewTokens.HomeColor.chipBackground,
                            in: Capsule())
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityLabel("\(label) filter")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct EmptySearchState: View {
    let query: String
    let onClear: () -> Void

    var body: some View {
        VStack(spacing: SceneViewTokens.Space.sm) {
            Text("No demos match “\(query)”")
                .font(SceneViewTokens.TypeScale.body)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
            Button("Clear", action: onClear)
                .font(SceneViewTokens.TypeScale.bodyMedium)
                .tint(SceneViewTheme.primary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, SceneViewTokens.Space.xl)
    }
}

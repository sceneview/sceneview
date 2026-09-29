import SwiftUI
import SceneViewSwift

/// The Showcase tab — the iOS twin of Android's `HomeScreen.kt`.
///
/// One scroll view, no nested scroll, no background scene: a 56 pt header
/// (cube mark + wordmark + search), the `HomeHero`, the "Featured" shelf
/// (`HomeCatalogue.featuredIds`), a full-width `BrowseOnlineModelsCard` that
/// pushes the online gallery (`ExploreTab`, embedded) onto this stack, the
/// section chip row, then every demo as a `DemoMediaCard` under its
/// `DemoSection` header in editorial `DemoItem.order`. Layout and order mirror Android's
/// `HomeScreen.kt` (#3907); demos in `HomeCatalogue.hiddenFromHome` keep
/// their deep links but are not listed here.
///
/// The header is a pinned overlay drawn over the scroll view: transparent
/// while the hero is on screen, `surface` at 94 % light / 100 % dark plus a bottom hairline once
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
    /// Whether this tab is the one on screen, with nothing presented over it.
    /// Gates the hero's live 3D stage: a RealityKit view still rendering behind
    /// a demo would be a second scene competing with the one the user opened.
    var isActive: Bool = true

    @State private var scenes: [DemoItem] = []
    @State private var selectedSection: DemoSection?
    @State private var query = ""
    @State private var searchOpen = false
    @State private var scrolled = false
    @State private var fullScreenScene: DemoItem?
    @State private var comingSoonScene: DemoItem?
    @State private var showExplore = false
    /// The `matchedTransitionSource` id of whatever opened the current demo. A
    /// featured demo is on screen twice (shelf and section), so the zoom has to
    /// know which of the two cards it grows out of.
    @State private var transitionSourceId = ""

    /// Source namespace for the iOS 18 zoom presentation transition: the tapped
    /// card (or the hero) morphs into the full-screen demo instead of the demo
    /// sliding up over it with no visual link to what was tapped (#3599).
    @Namespace private var cardNamespace

    /// Drives the entrance cascade (``StaggeredReveal``): flipped once, one
    /// frame after the catalogue appears, and never back. It lives here, on the
    /// screen, rather than in each item: a card in a `LazyVGrid` is rebuilt
    /// whenever the grid is — constantly, while the hero's 3D stage renders —
    /// and per-item state would replay the fade forever. Read from the parent,
    /// a rebuilt card is simply already revealed.
    @State private var catalogueRevealed = false

    /// The page's scroll offset, for the hero stage's travel and parallax.
    /// An observable object rather than `@State`, so only the stage's
    /// scroll-driven frames — not this whole screen — redraw as the page scrolls.
    @State private var heroScroll = HomeHeroScroll()
    /// The hero's flight, kept for the life of the screen: the 3D view is
    /// unmounted off screen, behind a demo, in the background and during a
    /// search, and resumes on the frame it left (``HomeHeroFlightHost``).
    @State private var heroFlight = HomeHeroFlightHost()
    /// The status bar's height: the hero stage starts above the content, at
    /// the top edge of the display.
    @State private var topInset: CGFloat = 0

    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.scenePhase) private var scenePhase

    private var expanded: Bool { sizeClass == .regular }

    /// The hero's 3D stage runs only here: visible tab, foreground app, nothing
    /// presented on top. Everything else tears it down — see ``HomeHeroStage``.
    private var heroLive: Bool {
        isActive
            && scenePhase == .active
            && fullScreenScene == nil
            && comingSoonScene == nil
            && !showExplore
    }
    private var searching: Bool { !query.trimmingCharacters(in: .whitespaces).isEmpty }

    /// Every demo the home lists — the catalogue minus the demos parked in
    /// `HomeCatalogue.hiddenFromHome`.
    private var homeScenes: [DemoItem] {
        scenes.filter { HomeCatalogue.isOnHome($0.sceneId) }
    }

    private var visible: [DemoItem] {
        let byId = Dictionary(uniqueKeysWithValues: scenes.map { ($0.sceneId, $0) })
        return filterDemos(homeScenes.map(HomeSearchEntry.init), section: selectedSection, query: query)
            .compactMap { byId[$0.id] }
    }

    /// The Featured shelf, in priority order. Hidden while searching and shown
    /// whatever chip is selected — Android parity.
    private var featured: [DemoItem] {
        let byId = Dictionary(uniqueKeysWithValues: homeScenes.map { ($0.sceneId, $0) })
        return HomeCatalogue.featuredIds.compactMap { byId[$0] }
    }

    /// `demos` cut into sections, in `DemoSection` order. Each card carries its
    /// reading-order index across the whole list, for the entrance cascade.
    private func sections(of demos: [DemoItem]) -> [HomeSectionGroup] {
        let indexed = Array(demos.enumerated())
        return DemoSection.allCases.compactMap { section in
            let cards = indexed.filter { $0.element.section == section }
                .map { HomeSectionGroup.Card(index: $0.offset, demo: $0.element) }
            return cards.isEmpty ? nil : HomeSectionGroup(section: section, cards: cards)
        }
    }

    private var showFeatured: Bool { !searching && !featured.isEmpty }

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: expanded ? SceneViewTokens.Home.gridMinCellExpanded
                                              : SceneViewTokens.Home.gridMinCell),
                  spacing: SceneViewTokens.Home.gridGutter)]
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    VStack(alignment: .leading, spacing: 0) {
                        // The pinned header overlay covers this band; the spacer keeps
                        // the hero from starting underneath it.
                        Color.clear.frame(height: SceneViewTokens.Home.headerHeight + SceneViewTokens.Home.heroTopGap)

                        // While a query is active the hero steps aside so the results
                        // sit right under the header (Android parity).
                        if !searching {
                            HomeHero(height: heroHeight) {
                                open(sceneId: Self.heroDemoId)
                            }
                            #if os(iOS)
                            .matchedTransitionSource(id: Self.heroDemoId, in: cardNamespace)
                            #endif
                            .staggeredReveal(position: 0, revealed: catalogueRevealed)
                        }
                    }
                    // The dusk sky and the live flight, under the header and the
                    // hero band, full-bleed from the top edge of the display.
                    .background(alignment: .top) {
                        if !searching {
                            HomeHeroStage(height: heroStageHeight, topInset: topInset,
                                          restTop: heroRestTop, live: heroLive, scroll: heroScroll,
                                          flight: heroFlight)
                                .padding(.horizontal, -SceneViewTokens.Home.contentPadding)
                        }
                    }

                    if showFeatured {
                        HomeSectionHeader(title: "Featured")
                            .padding(.top, SceneViewTokens.Home.sectionHeaderTopGap)
                            .padding(.bottom, SceneViewTokens.Home.sectionHeaderBottomGap)
                            .accessibilityIdentifier("home-section-featured")
                            .staggeredReveal(position: 1, revealed: catalogueRevealed)
                        featuredShelf
                    }

                    // Between the shelf and the chips, full width — Android's
                    // `browse-online` grid item. It steps aside with the hero
                    // while a query is live.
                    if !searching {
                        BrowseOnlineModelsCard { showExplore = true }
                            .padding(.top, SceneViewTokens.Home.gridGutter)
                            .accessibilityIdentifier("home-browse-online")
                            .staggeredReveal(position: chipRevealPosition - 1, revealed: catalogueRevealed)
                    }

                    CategoryChipRow(selected: $selectedSection)
                        .padding(.top, searching ? 0 : SceneViewTokens.Home.chipRowTopGap)
                        .padding(.bottom, searching ? SceneViewTokens.Space.sm : SceneViewTokens.Home.gridTopGap)
                        .staggeredReveal(position: chipRevealPosition, revealed: catalogueRevealed)

                    // While a query is live the count is the only feedback that
                    // the list under it is the answer to what was typed. It
                    // counts up and down in place (`contentTransition`) instead
                    // of swapping strings, so the eye follows the number rather
                    // than re-reading the sentence.
                    if searching && !visible.isEmpty {
                        Text(visible.count == 1 ? "1 demo" : "\(visible.count) demos")
                            .font(SceneViewTokens.TypeScale.captionRegular)
                            .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                            .contentTransition(.numericText(value: Double(visible.count)))
                            .animation(SceneViewTokens.Motion.expressive(SceneViewTokens.Motion.short),
                                       value: visible.count)
                            .padding(.bottom, SceneViewTokens.Home.gridTopGap)
                            .accessibilityIdentifier("home-result-count")
                    }

                    if visible.isEmpty && searching {
                        EmptySearchState(query: query) { query = "" }
                    }

                    sectionedGrid(visible)
                        .animation(SceneViewTokens.Spring.animation, value: visible.map(\.sceneId))
                }
                .animation(SceneViewTokens.Spring.fade, value: searching)
                .padding(.horizontal, SceneViewTokens.Home.contentPadding)
                .padding(.bottom, SceneViewTokens.Home.gridBottomInset)
            }
            .background(SceneViewTokens.HomeColor.surface)
            .scrollDismissesKeyboard(.immediately)
            // "Scrolled" once the header band has gone under the header, as on
            // Android (the grid's header spacer leaving the viewport): until
            // then the header sits on the stage's sky.
            .onScrollGeometryChange(for: Bool.self) { geometry in
                geometry.contentOffset.y + geometry.contentInsets.top > heroRestTop
            } action: { _, isScrolled in
                withAnimation(SceneViewTokens.Spring.fade) { scrolled = isScrolled }
            }
            // The stage's offset stops at its own height: past it the stage is
            // off screen, the transform stops changing and nothing is written.
            .onScrollGeometryChange(for: CGFloat.self) { geometry in
                min(geometry.contentOffset.y + geometry.contentInsets.top, heroStageHeight)
            } action: { _, offset in
                heroScroll.offset = offset
            }
            .onScrollGeometryChange(for: Bool.self) { geometry in
                geometry.contentOffset.y + geometry.contentInsets.top < heroStageHeight - topInset
            } action: { _, onScreen in
                heroScroll.onScreen = onScreen
            }
            .onScrollGeometryChange(for: CGFloat.self) { geometry in
                geometry.contentInsets.top
            } action: { _, inset in
                topInset = inset
            }
            .overlay(alignment: .top) {
                HomeHeader(scrolled: scrolled, overStage: overStage, query: $query, searchOpen: $searchOpen)
            }
            #if os(iOS)
            // Light status-bar icons while they sit on the sky.
            .toolbarColorScheme(overStage ? .dark : nil, for: .navigationBar)
            #endif
            .hideNavigationBar()
            .navigationDestination(isPresented: $showExplore) {
                ExploreTab(embedded: true)
            }
            .onAppear {
                if scenes.isEmpty { scenes = GeneratedScenes.all() }
            }
            .task {
                // One frame late, so the first layout paints the pre-reveal
                // state and the cascade has something to animate from.
                guard !catalogueRevealed else { return }
                try? await Task.sleep(for: .milliseconds(16))
                guard !Task.isCancelled else { return }
                catalogueRevealed = true
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
                .partialSheetBackground(.regularMaterial)
                .presentationCornerRadius(SceneViewTokens.Radius.xl)
                .presentationDragIndicator(.visible)
                #endif
            }
            #if os(iOS)
            .fullScreenCover(item: $fullScreenScene) { scene in
                DemoCover(scene: scene) { fullScreenScene = nil }
                    // The card that was tapped expands into the demo, and
                    // collapses back into it on close. Before this, a demo
                    // appeared with the stock cover slide and nothing tied it
                    // to the card the thumb had just hit (#3599). The source is
                    // the `DemoMediaCard` — or the `HomeHero` when the demo is
                    // opened from it, which is why both carry a
                    // `matchedTransitionSource` (`transitionSourceId`: the
                    // scene id, or `featured-<id>` for a Featured shelf card).
                    .navigationTransition(.zoom(sourceID: transitionSourceId, in: cardNamespace))
                    // The zoom transition brings the system's interactive
                    // dismissal with it: a pinch-in or a downward drag anywhere
                    // on the cover shrinks it back toward its card. On a 3D
                    // stage that pinch is the camera's zoom-out, and the cover
                    // won it — the demo collapsed instead of the camera pulling
                    // back (#4008). Demos close from their back button and the
                    // leading-edge strip (`DemoCover.edgeDismissStrip`), so the
                    // system gesture is switched off; the zoom still plays on
                    // open and on close.
                    .interactiveDismissDisabled()
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

    private var heroHeight: CGFloat {
        expanded ? SceneViewTokens.Home.heroHeightExpanded : SceneViewTokens.Home.heroHeight
    }

    /// Where the hero band starts below the content's top edge.
    private var heroRestTop: CGFloat {
        SceneViewTokens.Home.headerHeight + SceneViewTokens.Home.heroTopGap
    }

    /// The stage: status bar, header band, hero band, then the bleed that fades
    /// into the page — Android's `HomeHeroStage` height.
    private var heroStageHeight: CGFloat {
        topInset + heroRestTop + heroHeight + SceneViewTokens.Home.heroStageBleed
    }

    /// The header sits on the stage's sky: white type and light status-bar icons.
    private var overStage: Bool { !scrolled && !searching && !searchOpen }

    /// The "Featured" shelf under the hero — Android's `FeaturedShelf` (#4144):
    /// the demos we push as a swipeable row of 4:5 portrait cards, the editorial
    /// row of a store's front page rather than two more cells of the grid.
    ///
    /// Each card is `featured-card-width` wide, so the next one peeks at the
    /// trailing edge and says "swipe"; a fling settles on a card's leading edge.
    /// The row bleeds out of the page inset and carries it as a content margin,
    /// like the chip row, so cards scroll to the screen edge. The cards end
    /// level, and each picture lags its card by `featured-parallax`.
    private var featuredShelf: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: SceneViewTokens.Home.gridGutter) {
                ForEach(Array(featured.enumerated()), id: \.element.sceneId) { index, demo in
                    let sourceId = "featured-\(demo.sceneId)"
                    DemoMediaCard(demo: demo, featuredWidth: featuredCardWidth,
                                  featuredTrailingCards: featured.count - 1 - index) { open(demo, from: sourceId) }
                        #if os(iOS)
                        .matchedTransitionSource(id: sourceId, in: cardNamespace)
                        #endif
                        .accessibilityIdentifier("home-featured-\(demo.sceneId)")
                        .staggeredReveal(position: index + 2, revealed: catalogueRevealed)
                }
            }
            .levelledRow()
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.viewAligned)
        .contentMargins(.horizontal, SceneViewTokens.Home.contentPadding, for: .scrollContent)
        // The light `shadow-md` reaches below and beside each card.
        .scrollClipDisabled()
        .padding(.horizontal, -SceneViewTokens.Home.contentPadding)
    }

    private var featuredCardWidth: CGFloat {
        expanded ? SceneViewTokens.Home.featuredCardWidthExpanded : SceneViewTokens.Home.featuredCardWidth
    }

    /// Every visible demo under its section header. Headers are drawn only
    /// when more than one section is on screen: with a single chip selected,
    /// the chip already names it (DESIGN.md, section headers).
    @ViewBuilder
    private func sectionedGrid(_ demos: [DemoItem]) -> some View {
        let groups = sections(of: demos)
        let showSections = groups.count > 1
        let firstCardSlot = chipRevealPosition + 1
        ForEach(Array(groups.enumerated()), id: \.element.section) { groupIndex, group in
            if showSections {
                HomeSectionHeader(title: group.section.title)
                    // The chip row already leaves `gridTopGap` under it.
                    .padding(.top, groupIndex == 0
                             ? SceneViewTokens.Home.sectionHeaderTopGap - SceneViewTokens.Home.gridTopGap
                             : SceneViewTokens.Home.sectionHeaderTopGap)
                    .padding(.bottom, SceneViewTokens.Home.sectionHeaderBottomGap)
                    .accessibilityIdentifier("home-section-\(group.section.rawValue)")
            }
            LazyVGrid(columns: columns, spacing: SceneViewTokens.Home.gridGutter) {
                ForEach(group.cards, id: \.demo.sceneId) { card in
                    DemoMediaCard(demo: card.demo) { open(card.demo, from: card.demo.sceneId) }
                        #if os(iOS)
                        .matchedTransitionSource(id: card.demo.sceneId, in: cardNamespace)
                        #endif
                        .staggeredReveal(position: firstCardSlot + card.index, revealed: catalogueRevealed)
                }
            }
        }
    }

    /// Reveal slot of the chip row: after the hero (0), when shown the Featured
    /// header and its cards, and the "Browse online models" row.
    private var chipRevealPosition: Int {
        (showFeatured ? featured.count + 2 : 1) + (searching ? 0 : 1)
    }

    private func open(sceneId: String) {
        guard let scene = scenes.first(where: { $0.sceneId == sceneId }) else { return }
        open(scene, from: sceneId)
    }

    private func open(_ scene: DemoItem, from sourceId: String) {
        transitionSourceId = sourceId
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
///
/// **One host, whatever opened the demo.** The catalogue builds it from a
/// `DemoItem`; a deep link (`sceneview://demo/<id>`) builds it from the id's
/// resolved destination through `DemoDeepLinkRegistry.cover(for:onClose:)`.
/// Before that, a deep link presented the destination bare, so a demo with no
/// chrome of its own — every hand-rolled AR screen — opened with no way out
/// but force-quitting the app.
///
/// The leading-edge swipe is this host's own: interactive dismissal is off on
/// both paths (the catalogue's zoom transition would otherwise add a pinch and
/// drag-down dismissal that steals the stage's own pinch, #4008), and an AR demo fills the screen with
/// a camera feed that a user will try to swipe away. It is confined to a
/// narrow strip at the leading edge so it cannot compete with the orbit / pan
/// gestures the stage itself installs.
struct DemoCover: View {
    let title: String
    let destination: AnyView
    let onClose: () -> Void

    init(scene: DemoItem, onClose: @escaping () -> Void) {
        self.init(title: scene.title, destination: scene.destination, onClose: onClose)
    }

    init(title: String, destination: AnyView, onClose: @escaping () -> Void) {
        self.title = title
        self.destination = destination
        self.onClose = onClose
    }

    var body: some View {
        NavigationStack {
            destination
                .navigationTitle(title)
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
        .environment(\.demoTitle, title)
        #if os(iOS)
        .overlay(alignment: .leading) { edgeDismissStrip }
        #endif
    }

    #if os(iOS)
    private var edgeDismissStrip: some View {
        Color.clear
            .frame(width: SceneViewTokens.Layout.edgeSwipeWidth)
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: SceneViewTokens.Layout.edgeSwipeWidth)
                    .onEnded { value in
                        // A deliberate rightward drag, not an incidental one:
                        // past the threshold and more horizontal than vertical.
                        guard value.translation.width >= SceneViewTokens.Layout.edgeSwipeDismiss,
                              abs(value.translation.height) < value.translation.width else { return }
                        SceneViewHaptic.shared.light()
                        onClose()
                    }
            )
            .ignoresSafeArea()
            .accessibilityHidden(true)
    }
    #endif
}

// MARK: - Header

private struct HomeHeader: View {
    @Environment(\.colorScheme) private var colorScheme

    let scrolled: Bool
    /// Over the hero stage's sky rather than the page: the title row turns to
    /// the hero's fixed whites (Android `overStage`).
    var overStage = false
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
                    TitleRow(overStage: overStage) { searchOpen = true }
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
                // DESIGN.md `header-overlay` is opaque in dark. No material:
                // the pinned catalogue header must not sample scrolling artwork.
                .opacity(scrolled || searchOpen ? (colorScheme == .dark ? 1 : SceneViewTokens.HomeColor.headerOverlayAlpha) : 0)
                .ignoresSafeArea(edges: .top)
        )
    }
}

private struct TitleRow: View {
    var overStage = false
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
                .foregroundStyle(overStage ? SceneViewTokens.HomeColor.heroTitle : SceneViewTokens.HomeColor.onSurface)
                .animation(SceneViewTokens.Spring.fade, value: overStage)
            Spacer()
            Button(action: onSearch) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(overStage ? SceneViewTokens.HomeColor.heroSubtitle : SceneViewTokens.HomeColor.onSurfaceDim)
                    .animation(SceneViewTokens.Spring.fade, value: overStage)
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
    @Environment(\.colorScheme) private var colorScheme

    @Binding var query: String
    let onClose: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: SceneViewTokens.Space.sm) {
            Image(systemName: "magnifyingglass")
                // Tertiary glyph only; the actual focus indication uses primary below.
                .foregroundStyle(colorScheme == .dark ? SceneViewTokens.HomeColor.onSurfaceFaint
                                                     : SceneViewTokens.HomeColor.onSurfaceDim)
            TextField("Search demos", text: $query,
                      prompt: Text("Search demos")
                          .foregroundStyle(SceneViewTokens.HomeColor.placeholder))
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
        .background(colorScheme == .dark ? SceneViewTokens.HomeColor.floatingSurface
                                        : SceneViewTokens.HomeColor.surface, in: Capsule())
        .overlay(Capsule().strokeBorder(colorScheme == .dark
                                       ? (focused ? SceneViewTokens.HomeColor.primary : SceneViewTokens.HomeColor.controlOutline)
                                       : (focused ? SceneViewTokens.HomeColor.onSurfaceDim : SceneViewTokens.HomeColor.outlineSubtle),
                                        lineWidth: SceneViewTokens.Home.cardOutlineWidth))
        .padding(.horizontal, SceneViewTokens.Home.contentPadding)
        .onAppear { focused = true }
    }
}

// MARK: - Section headers

/// One home section and its cards; `index` is the card's position across the
/// whole visible list, which drives the entrance cascade.
private struct HomeSectionGroup {
    struct Card {
        let index: Int
        let demo: DemoItem
    }

    let section: DemoSection
    let cards: [Card]
}

/// A catalogue section header (DESIGN.md): `on-surface`, semibold, no colour
/// of its own — the iOS twin of Android's `SectionHeader`.
private struct HomeSectionHeader: View {
    let title: String

    var body: some View {
        Text(title)
            .font(SceneViewTokens.TypeScale.card)
            .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

// MARK: - Section chips

/// `nil` = All, then Android's `DEMO_CATEGORIES` order. Order is the chip order.
private let chipSections: [DemoSection?] = [nil] + DemoSection.allCases.map { Optional($0) }

private struct CategoryChipRow: View {
    @Binding var selected: DemoSection?

    var body: some View {
        // Bleeds to the screen edges (cancelling the page inset) and carries the
        // side inset as leading *and* trailing content padding, so the last chip
        // stops at the same margin as the cards.
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: SceneViewTokens.Home.chipGap) {
                ForEach(chipSections, id: \.self) { section in
                    CategoryChip(label: section?.chipLabel ?? "All", selected: section == selected) {
                        #if os(iOS)
                        SceneViewHaptic.shared.selection()
                        #endif
                        selected = section
                    }
                }
            }
            .padding(.horizontal, SceneViewTokens.Home.contentPadding)
        }
        .padding(.horizontal, -SceneViewTokens.Home.contentPadding)
    }
}

private struct CategoryChip: View {
    @Environment(\.colorScheme) private var colorScheme

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
                                     : (colorScheme == .dark ? SceneViewTokens.HomeColor.surfaceContainer
                                                            : SceneViewTokens.HomeColor.chipBackground),
                            in: Capsule())
                .overlay {
                    if colorScheme == .dark {
                        // Spec outline makes unselected chips legible; primary marks
                        // selection without the former white capsule's visual weight.
                        Capsule().strokeBorder(selected ? SceneViewTokens.HomeColor.primary
                                                        : SceneViewTokens.HomeColor.outline,
                                               lineWidth: SceneViewTokens.Home.cardOutlineWidth)
                    }
                }
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

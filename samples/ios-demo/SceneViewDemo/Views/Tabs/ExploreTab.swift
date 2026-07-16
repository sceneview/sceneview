import SwiftUI
import RealityKit
import SceneViewSwift
#if os(iOS)
import QuickLook
#endif

/// Model data for the gallery.
struct ModelItem: Identifiable, Hashable {
    let id: String
    let name: String
    let icon: String
    let asset: String
    let scale: Float
    let category: ModelCategory

    func hash(into hasher: inout Hasher) { hasher.combine(id) }
    static func == (lhs: ModelItem, rhs: ModelItem) -> Bool { lhs.id == rhs.id }

    static let all: [ModelItem] = [
        // Vehicles
        ModelItem(id: "red_car",              name: "Red Car",       icon: "car.fill",         asset: "red_car",              scale: 1.0, category: .vehicles),
        ModelItem(id: "ferrari_f40",          name: "Ferrari F40",   icon: "car.side.fill",    asset: "ferrari_f40",          scale: 0.6, category: .vehicles),
        ModelItem(id: "porsche_911",          name: "Porsche 911",   icon: "car.rear.fill",    asset: "porsche_911",          scale: 0.6, category: .vehicles),
        ModelItem(id: "porsche_911_turbo",    name: "Porsche Turbo", icon: "car.side.fill",    asset: "porsche_911_turbo",    scale: 0.5, category: .vehicles),
        ModelItem(id: "lamborghini_countach", name: "Lamborghini",   icon: "car.fill",         asset: "lamborghini_countach", scale: 0.5, category: .vehicles),
        ModelItem(id: "shelby_cobra",         name: "Shelby Cobra",  icon: "car.rear.fill",    asset: "shelby_cobra",         scale: 0.6, category: .vehicles),
        ModelItem(id: "bmw_m3_e30",           name: "BMW M3 E30",    icon: "car.side.fill",    asset: "bmw_m3_e30",           scale: 0.6, category: .vehicles),
        ModelItem(id: "mercedes_a45_amg",     name: "Mercedes AMG",  icon: "car.fill",         asset: "mercedes_a45_amg",     scale: 0.5, category: .vehicles),
        ModelItem(id: "audi_tt",              name: "Audi TT",       icon: "car.rear.fill",    asset: "audi_tt",              scale: 0.7, category: .vehicles),
        ModelItem(id: "fiat_punto",           name: "Fiat Punto",    icon: "car.side.fill",    asset: "fiat_punto",           scale: 0.7, category: .vehicles),
        ModelItem(id: "tesla_cybertruck",     name: "Cybertruck",    icon: "truck.box.fill",   asset: "tesla_cybertruck",     scale: 0.6, category: .vehicles),
        ModelItem(id: "cyberpunk_car",        name: "Cyberpunk Car", icon: "bolt.car.fill",    asset: "cyberpunk_car",        scale: 0.8, category: .vehicles),
        ModelItem(id: "cyberpunk_hovercar",   name: "Hovercar",      icon: "airplane",         asset: "cyberpunk_hovercar",   scale: 0.6, category: .vehicles),

        // Creatures (`animated_dragon` removed in #1152 Stage 3 IPA slim-down
        // — the dragon role is still represented by `black_dragon` below.)
        ModelItem(id: "black_dragon",        name: "Black Dragon",      icon: "lizard.fill",       asset: "black_dragon",        scale: 0.5, category: .creatures),
        ModelItem(id: "phoenix_bird",        name: "Phoenix",           icon: "bird.fill",         asset: "phoenix_bird",        scale: 0.8, category: .creatures),
        ModelItem(id: "animated_butterfly",  name: "Butterfly",         icon: "sparkles",          asset: "animated_butterfly",  scale: 0.8, category: .creatures),
        ModelItem(id: "mosquito_amber",      name: "Mosquito in Amber", icon: "ant.fill",          asset: "mosquito_amber",      scale: 1.0, category: .creatures),
        ModelItem(id: "cyberpunk_character", name: "Cyber Guy",         icon: "figure.run",        asset: "cyberpunk_character", scale: 0.7, category: .creatures),

        // Objects
        ModelItem(id: "game_boy_classic", name: "Game Boy",       icon: "gamecontroller.fill",    asset: "game_boy_classic", scale: 0.8, category: .objects),
        ModelItem(id: "nintendo_switch",  name: "Switch",         icon: "square.grid.2x2.fill",  asset: "nintendo_switch",  scale: 0.8, category: .objects),
        ModelItem(id: "ps5_dualsense",    name: "PS5 Controller", icon: "gamecontroller",         asset: "ps5_dualsense",    scale: 0.8, category: .objects),
        ModelItem(id: "nike_air_jordan",  name: "Air Jordan",     icon: "shoe.fill",              asset: "nike_air_jordan",  scale: 0.8, category: .objects),
        ModelItem(id: "retro_piano",      name: "Retro Piano",    icon: "pianokeys",              asset: "retro_piano",      scale: 0.7, category: .objects),
        ModelItem(id: "fantasy_book",     name: "Fantasy Book",   icon: "book.fill",              asset: "fantasy_book",     scale: 0.7, category: .objects),

        // Scenes
        ModelItem(id: "tree_scene",           name: "Tree Scene",  icon: "tree.fill",      asset: "tree_scene",           scale: 0.6, category: .scenes),
        ModelItem(id: "ship_in_clouds",       name: "Ship in Sky", icon: "cloud.fill",     asset: "ship_in_clouds",       scale: 0.5, category: .scenes),
        ModelItem(id: "earthquake_california", name: "Earthquake", icon: "waveform.path",  asset: "earthquake_california", scale: 0.4, category: .scenes),
    ]
}

enum ModelCategory: String, CaseIterable {
    case vehicles = "Vehicles"
    case creatures = "Creatures"
    case objects = "Objects"
    case scenes = "Scenes"
    case favorites = "Favorites"

    var gradientColors: [Color] {
        switch self {
        case .vehicles:  return [Color.blue.opacity(0.3), Color.cyan.opacity(0.15)]
        case .creatures: return [Color.orange.opacity(0.3), Color.yellow.opacity(0.15)]
        case .objects:   return [Color.purple.opacity(0.3), Color.pink.opacity(0.15)]
        case .scenes:    return [Color.green.opacity(0.3), Color.teal.opacity(0.15)]
        case .favorites: return [Color.red.opacity(0.3), Color.pink.opacity(0.15)]
        }
    }

    var iconColor: Color {
        switch self {
        case .vehicles:  return .blue
        case .creatures: return .orange
        case .objects:   return .purple
        case .scenes:    return .green
        case .favorites: return .red
        }
    }
}

/// The 18 official Sketchfab categories returned by `GET /v3/categories`.
///
/// The `slug` is exactly what the Sketchfab Data API expects in `?categories=`; the
/// `displayName` is what users see on the chip. SF Symbol `icon` is picked per category.
///
/// Source: live `https://api.sketchfab.com/v3/categories` (snapshot 2026-05-11).
enum SketchfabCategory: String, CaseIterable, Identifiable {
    case animalsPets             = "animals-pets"
    case architecture            = "architecture"
    case artAbstract             = "art-abstract"
    case carsVehicles            = "cars-vehicles"
    case charactersCreatures     = "characters-creatures"
    case culturalHeritageHistory = "cultural-heritage-history"
    case electronicsGadgets      = "electronics-gadgets"
    case fashionStyle            = "fashion-style"
    case foodDrink               = "food-drink"
    case furnitureHome           = "furniture-home"
    case music                   = "music"
    case naturePlants            = "nature-plants"
    case newsPolitics            = "news-politics"
    case people                  = "people"
    case placesTravel            = "places-travel"
    case scienceTechnology       = "science-technology"
    case sportsFitness           = "sports-fitness"
    case weaponsMilitary         = "weapons-military"

    var id: String { rawValue }
    var slug: String { rawValue }

    var displayName: String {
        switch self {
        case .animalsPets:             return "Animals & Pets"
        case .architecture:            return "Architecture"
        case .artAbstract:             return "Art & Abstract"
        case .carsVehicles:            return "Cars & Vehicles"
        case .charactersCreatures:     return "Characters & Creatures"
        case .culturalHeritageHistory: return "Cultural Heritage"
        case .electronicsGadgets:      return "Electronics"
        case .fashionStyle:            return "Fashion & Style"
        case .foodDrink:               return "Food & Drink"
        case .furnitureHome:           return "Furniture & Home"
        case .music:                   return "Music"
        case .naturePlants:            return "Nature & Plants"
        case .newsPolitics:            return "News & Politics"
        case .people:                  return "People"
        case .placesTravel:            return "Places & Travel"
        case .scienceTechnology:       return "Science & Tech"
        case .sportsFitness:           return "Sports & Fitness"
        case .weaponsMilitary:         return "Weapons & Military"
        }
    }

    var icon: String {
        switch self {
        case .animalsPets:             return "pawprint.fill"
        case .architecture:            return "building.2.fill"
        case .artAbstract:             return "paintpalette.fill"
        case .carsVehicles:            return "car.side.fill"
        case .charactersCreatures:     return "figure.stand"
        case .culturalHeritageHistory: return "building.columns.fill"
        case .electronicsGadgets:      return "cpu.fill"
        case .fashionStyle:            return "tshirt.fill"
        case .foodDrink:               return "fork.knife"
        case .furnitureHome:           return "sofa.fill"
        case .music:                   return "music.note"
        case .naturePlants:            return "leaf.fill"
        case .newsPolitics:            return "newspaper.fill"
        case .people:                  return "person.2.fill"
        case .placesTravel:            return "globe.americas.fill"
        case .scienceTechnology:       return "atom"
        case .sportsFitness:           return "figure.run"
        case .weaponsMilitary:         return "shield.lefthalf.filled"
        }
    }
}

/// User defaults–backed list of the last 5 search queries, surfaced under "Recent searches".
@MainActor
@Observable
final class RecentSearches {
    private let storageKey = "io.github.sceneview.demo.recentSearches"
    private let maxItems = 5

    private(set) var items: [String] = []

    init() { load() }

    func push(_ query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        items.removeAll { $0.caseInsensitiveCompare(trimmed) == .orderedSame }
        items.insert(trimmed, at: 0)
        if items.count > maxItems { items = Array(items.prefix(maxItems)) }
        save()
    }

    func remove(_ query: String) {
        items.removeAll { $0 == query }
        save()
    }

    func clear() {
        items.removeAll()
        save()
    }

    private func load() {
        items = UserDefaults.standard.stringArray(forKey: storageKey) ?? []
    }

    private func save() {
        UserDefaults.standard.set(items, forKey: storageKey)
    }
}

/// The main Explore tab — Liquid Glass discovery hub for 3D models.
///
/// Layout follows the Stitch mockup (iOS Liquid Glass design system):
/// - Featured carousel of curated models (currently from the bundled `ModelItem.all` set;
///   V1.1 will pull from `SketchfabService.featured()` when an API key is configured).
/// - Categories chips that filter / search by topic.
/// - Recent searches list, persisted across launches.
/// - Native `.searchable` search bar that queries Sketchfab when an API key is set.
struct ExploreTab: View {
    @State private var searchText = ""
    @State private var selectedModel: ModelItem?
    /// The source-agnostic model the user tapped — pushes `GalleryModelViewerScreen`.
    @State private var viewingModel: GalleryModel?
    @State private var selectedCategory: SketchfabCategory?
    @State private var recentSearches = RecentSearches()

    // Multi-source resilience (#2645 / #2700): the tab browses whichever catalog
    // the user picks (Sketchfab | Icosa Gallery | Poly Haven), remembering the
    // last choice. A single degraded source never blanks the tab — the picker +
    // the samples row + the other sources stay usable.
    @State private var sources = GallerySourcesRegistry()
    /// Feeds keyed by the selected source's advertised `FeedKind`s (each source
    /// exposes its own subset — Poly Haven has no "staff picks", for instance).
    @State private var feedsByKind: [FeedKind: [GalleryModel]] = [:]
    @State private var isLoadingFeeds = false
    /// `true` once a Sketchfab feed/search fails with HTTP 401/403 — the key is
    /// present but rejected. Drives the disabled banner so the user sees an
    /// explanation instead of a silently empty feed. Reset on every (re)load and
    /// on source switch. A missing key simply drops Sketchfab from the picker
    /// (no banner), matching the Android behaviour (#2095 / #2645).
    @State private var keyRejected = false
    @State private var showSketchfabDisabledInfo = false
    /// When `true`, the Sketchfab feeds filter to `animated=true` (skeletal rigs).
    /// Ignored by the CC sources, which don't expose the flag.
    @State private var animatedOnly = false

    // Search execution state (#1239 parity). `activeSearchQuery` (set on submit
    // or on a recent-search tap) drives the search `.task`. `searchResults`:
    //   - `nil`      → query is loading
    //   - `.isEmpty` → query ran and returned 0 hits
    //   - non-empty  → render the results carousel, hide the feeds
    @State private var activeSearchQuery = ""
    @State private var searchResults: [GalleryModel]?

    /// Shared namespace for the iOS 18 zoom transition: the carousel card's
    /// thumbnail morphs into the GalleryModelViewerScreen hero on push.
    @Namespace private var heroNamespace
    private let favoritesManager = FavoritesManager.shared

    /// The currently selected source (Sketchfab | Icosa | Poly Haven).
    private var selectedSource: any ModelSource { sources.selected }
    private var isSearching: Bool { !activeSearchQuery.isEmpty }
    private var allFeedsEmpty: Bool {
        selectedSource.feedKinds.allSatisfy { (feedsByKind[$0] ?? []).isEmpty }
    }

    /// Curated featured set — first 6 bundled models, picked for visual variety.
    /// Used as fallback when no Sketchfab API key is configured.
    /// `animated_dragon` replaced with `black_dragon` after #1152 Stage 3
    /// dropped the 8.6 MB `animated_dragon.usdz` from the bundle.
    private var featuredModels: [ModelItem] {
        let ids = ["ferrari_f40", "black_dragon", "cyberpunk_character",
                   "game_boy_classic", "fantasy_book", "tree_scene"]
        return ids.compactMap { id in ModelItem.all.first { $0.id == id } }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 28) {
                    // Source picker (#2645 / #2700) — stays visible even mid-search.
                    // Switching catalogs resets browse + search state back to the
                    // new source's feeds (see selectSource — parity with Android's
                    // onSelectSource in ExploreTabScreen.kt). Hidden only when a
                    // single source is available (nothing to choose between).
                    if sources.sources.count > 1 {
                        sourcePickerRow
                    }
                    // "Sketchfab unavailable" banner — only when the selected
                    // source is Sketchfab and its key was rejected at runtime
                    // (401/403). A missing key drops Sketchfab from the picker, so
                    // this never fires in that case. Outlined (not error-colored):
                    // the tab is degraded but functional (other sources work).
                    if selectedSource.id == .sketchfab && keyRejected {
                        sketchfabDisabledBanner
                    }
                    // "Try a sample" + the Animated filter belong to the browse
                    // experience — hidden while searching. The Animated filter is
                    // meaningful only for Sketchfab; the CC sources hide it.
                    if !isSearching {
                        trySampleSection
                        if selectedSource.supportsAnimatedFilter {
                            filtersBar
                        }
                    }

                    if isSearching {
                        searchResultsSection
                    } else {
                        // One carousel per feed the selected source advertises.
                        // Each empty section self-hides; if every feed is empty and
                        // we're not still loading, fall back to the bundled curated
                        // carousel so the tab is never blank (#2645 / #2700).
                        ForEach(selectedSource.feedKinds, id: \.self) { kind in
                            galleryFeedSection(kind: kind, models: feedsByKind[kind] ?? [])
                        }
                        if allFeedsEmpty && !isLoadingFeeds {
                            bundledFeaturedSection
                        }
                    }

                    categoriesSection
                    if !recentSearches.items.isEmpty {
                        recentSearchesSection
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .navigationTitle("Explore")
            // Placeholder names the catalog being searched so the field reflects
            // the picked source (Sketchfab / Icosa Gallery / Poly Haven), #2645.
            // The selected source is always usable (Sketchfab is dropped from the
            // picker when it has no key), so the field is always live.
            .searchable(
                text: $searchText,
                prompt: "Search 3D models on \(selectedSource.id.displayName)"
            )
            .onSubmit(of: .search) { submitSearch() }
            .onChange(of: searchText) { _, newValue in
                // Clearing the field cancels the active search and restores the
                // default carousels (#1239 parity).
                if newValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    activeSearchQuery = ""
                    searchResults = nil
                }
            }
            // Reload the selected source's feeds on first appearance, source
            // switch, or Animated toggle. `.task(id:)` cancels the previous run
            // when the key changes — the single cancel-then-restart pipeline that
            // mirrors the Android LaunchedEffect.
            .task(id: feedsTaskKey) { await loadFeeds() }
            // Execute the search when the active query (or source) changes.
            .task(id: searchTaskKey) { await runSearch() }
            // Pull-to-refresh — every available source has live feeds to refresh
            // (Icosa / Poly Haven need no key), so it's always wired.
            .refreshable { await loadFeeds(force: true) }
            .navigationDestination(item: $selectedModel) { model in
                ModelViewerScreen(model: model)
            }
            .navigationDestination(item: $viewingModel) { model in
                GalleryModelViewerScreen(model: model, source: source(for: model))
                    // iOS 18 zoom navigation transition — the source thumbnail
                    // matchedTransitionSource lives on the FeaturedGalleryCard
                    // identified by `model.cardKey` (unique across sources + feeds).
                    // `.zoom(sourceID:in:)` is unavailable on macOS, where the
                    // destination simply pushes with the default transition.
                    #if os(iOS)
                    .navigationTransition(.zoom(
                        sourceID: "gallery-hero-\(model.cardKey)",
                        in: heroNamespace
                    ))
                    #endif
            }
            .sheet(item: $selectedCategory) { category in
                CategorySheet(category: category) { query in
                    searchText = query
                    recentSearches.push(query)
                    activeSearchQuery = query
                }
                .presentationDetents([.medium, .large])
                #if os(iOS)
                .presentationBackground(.regularMaterial)
                .presentationCornerRadius(28)
                #endif
            }
            // `.alert(...)` is the SwiftUI counterpart of the Android `AlertDialog`
            // in ExploreTabScreen.kt — surfaced when the Sketchfab key is rejected.
            .alert(
                "Sketchfab unavailable",
                isPresented: $showSketchfabDisabledInfo
            ) {
                Button("Got it", role: .cancel) { showSketchfabDisabledInfo = false }
            } message: {
                Text("The Sketchfab API key was rejected (revoked, wrong scope, or a rate-limit burst), so its Trending, Staff Picks and Recently Added carousels are unavailable right now.\n\nSwitch to Icosa Gallery or Poly Haven from the source picker — those Creative-Commons catalogs are always available and need no key.")
            }
        }
    }

    // MARK: - Task keys

    /// Re-keys the feeds `.task` on source switch or Animated toggle.
    private var feedsTaskKey: String { "\(selectedSource.id.slug)|\(animatedOnly)" }
    /// Re-keys the search `.task` on query change or source switch.
    private var searchTaskKey: String { "\(activeSearchQuery)|\(selectedSource.id.slug)" }

    /// Resolve the concrete source that produced `model` (robust against a source
    /// switch while the viewer is on screen).
    private func source(for model: GalleryModel) -> any ModelSource {
        sources.sources.first { $0.id == model.sourceId } ?? selectedSource
    }

    // MARK: - Source picker

    /// Source-picker chip row (#2645 / #2700): one chip per available `ModelSource`
    /// (Sketchfab | Icosa Gallery | Poly Haven), the selected one highlighted.
    private var sourcePickerRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Text("Source")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
                ForEach(sources.sources, id: \.id) { source in
                    SourceChip(
                        title: source.id.displayName,
                        isOn: source.id == selectedSource.id
                    ) {
                        selectSource(source)
                    }
                }
            }
        }
        .scrollClipDisabled()
    }

    /// Switch the active source, resetting browse + search state so the previous
    /// catalog's cards never flash under the new source's headers.
    private func selectSource(_ source: any ModelSource) {
        guard source.id != selectedSource.id else { return }
        searchText = ""
        activeSearchQuery = ""
        searchResults = nil
        feedsByKind = [:]
        keyRejected = false
        sources.select(source)
        #if os(iOS)
        SceneViewHaptic.shared.selection()
        #endif
    }

    /// Outlined banner shown when the selected Sketchfab source was rejected at
    /// runtime (401/403). Mirrors the Android `SketchfabDisabledBanner` (#2095).
    /// Neutral styling — the app is degraded but functional (other sources work).
    private var sketchfabDisabledBanner: some View {
        Button {
            showSketchfabDisabledInfo = true
        } label: {
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: "info.circle")
                    .font(.title3)
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Sketchfab unavailable")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text("The API key was rejected — try Icosa Gallery or Poly Haven.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color.secondary.opacity(0.08))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(Color.secondary.opacity(0.25), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityHint("Shows why Sketchfab is unavailable and how to switch source.")
    }

    // MARK: - Multi-source data loading

    /// (Re)load the selected source's feeds, one `FeedKind` at a time with
    /// per-feed resilience: a single degraded feed (network blip, rate limit,
    /// decode error) never cancels its siblings, so surviving feeds still render.
    /// Mirrors the Android `supervisorScope` in `ExploreTabScreen.kt` (#2645).
    ///
    /// A Sketchfab 401/403 flips `keyRejected` so the banner shows instead of the
    /// feed silently self-hiding. Pass `force: true` from pull-to-refresh.
    private func loadFeeds(force: Bool = false) async {
        let source = selectedSource
        // The `.task(id:)` re-runs on source/animated change; skip a redundant
        // reload when we already have feeds and this isn't a manual refresh.
        if !force && !feedsByKind.isEmpty { return }
        isLoadingFeeds = true
        keyRejected = false
        defer { isLoadingFeeds = false }

        let animated = animatedOnly && source.supportsAnimatedFilter
        let results = await withTaskGroup(
            of: (FeedKind, [GalleryModel], Bool).self
        ) { group -> [(FeedKind, [GalleryModel], Bool)] in
            for kind in source.feedKinds {
                group.addTask {
                    do {
                        let models = try await source.feed(kind: kind, animatedOnly: animated, limit: 10)
                        return (kind, models, false)
                    } catch let SketchfabError.requestFailed(statusCode)
                        where statusCode == 401 || statusCode == 403 {
                        return (kind, [], true)
                    } catch {
                        return (kind, [], false)
                    }
                }
            }
            var collected: [(FeedKind, [GalleryModel], Bool)] = []
            for await item in group { collected.append(item) }
            return collected
        }

        // Ignore a stale result if the user switched source mid-flight.
        guard source.id == selectedSource.id else { return }
        var byKind: [FeedKind: [GalleryModel]] = [:]
        var rejected = false
        for (kind, models, wasRejected) in results {
            byKind[kind] = models
            rejected = rejected || wasRejected
        }
        feedsByKind = byKind
        keyRejected = rejected
    }

    /// Execute the active search against the selected source. `nil` results means
    /// "in flight"; an empty list means "0 hits"; non-empty renders the carousel.
    /// Source search does not accept an `animated` filter (Sketchfab only exposes
    /// it on the feed endpoints; the CC sources have no such concept), #2645.
    private func runSearch() async {
        let source = selectedSource
        let query = activeSearchQuery
        guard !query.isEmpty else { return }
        searchResults = nil
        do {
            let results = try await source.search(query: query, limit: 24)
            guard source.id == selectedSource.id && query == activeSearchQuery else { return }
            searchResults = results
        } catch let SketchfabError.requestFailed(statusCode)
            where statusCode == 401 || statusCode == 403 {
            keyRejected = true
            searchResults = []
        } catch {
            // A transient blip surfaces the empty state and clears on the next
            // query — no permanent latch (matches the Android WAF handling).
            searchResults = []
        }
    }

    /// Explicit Enter press: record the query and fire the search.
    private func submitSearch() {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return }
        recentSearches.push(query)
        activeSearchQuery = query
        #if os(iOS)
        SceneViewHaptic.shared.light()
        #endif
    }

    // MARK: - "Try a sample" section (home-feed mix — samples + models)

    /// 6 curated sample demos surfaced on Explore so the home feed shows what
    /// SceneView can do beyond just downloaded models. Tap navigates to the demo's
    /// own screen, which renders through SceneView (same path as the Scenes tab).
    private var trySampleSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Try a sample")
                .font(.title2.weight(.bold))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    SamplePromoCard(title: "PBR Materials", subtitle: "Metallic + roughness spectrum", icon: "paintpalette.fill", gradient: [.purple.opacity(0.35), .pink.opacity(0.18)]) {
                        AnyView(MaterialsDemo())
                    }
                    SamplePromoCard(title: "Lighting", subtitle: "Directional · point · spot", icon: "lightbulb.fill", gradient: [.yellow.opacity(0.30), .orange.opacity(0.18)]) {
                        AnyView(LightingDemo())
                    }
                    SamplePromoCard(title: "Physics", subtitle: "Dynamic · static · kinematic", icon: "figure.walk", gradient: [.green.opacity(0.30), .teal.opacity(0.18)]) {
                        AnyView(PhysicsDemo())
                    }
                    SamplePromoCard(title: "Dynamic Sky", subtitle: "Time-of-day sun simulation", icon: "sun.horizon.fill", gradient: [.blue.opacity(0.30), .cyan.opacity(0.18)]) {
                        AnyView(DynamicSkyDemo())
                    }
                    SamplePromoCard(title: "3D Text", subtitle: "Extruded fonts with style", icon: "textformat", gradient: [.indigo.opacity(0.30), .purple.opacity(0.18)]) {
                        AnyView(TextDemo())
                    }
                    SamplePromoCard(title: "Scene Gallery", subtitle: "Themed Sketchfab bundles streamed on demand", icon: "square.grid.3x3.fill", gradient: [.red.opacity(0.28), .orange.opacity(0.15)]) {
                        AnyView(SceneGalleryDemo())
                    }
                }
                .padding(.bottom, 4)
            }
            .scrollClipDisabled()
        }
    }

    /// Horizontal row of filter chips above the feed carousels.
    private var filtersBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                FilterChip(label: "Animated", systemImage: "wand.and.stars", isOn: animatedOnly) {
                    // The feeds `.task(id:)` re-keys on `animatedOnly` and reloads.
                    feedsByKind = [:]
                    animatedOnly.toggle()
                    #if os(iOS)
                    SceneViewHaptic.shared.selection()
                    #endif
                }
                // Future chips (V1.1): License, Min poly count, Author.
            }
        }
        .scrollClipDisabled()
    }

    // MARK: - Feed section helpers (source-agnostic carousels)

    /// Localised carousel title for a `FeedKind`.
    private func feedTitle(_ kind: FeedKind) -> String {
        switch kind {
        case .trending: return "Trending"
        case .staffPicks: return "Staff Picks"
        case .recentlyAdded: return "Recently Added"
        }
    }

    /// One horizontal carousel of source-agnostic `GalleryModel`s. Self-hides when
    /// the feed is empty and we're not still loading — better than telling users
    /// "Nothing here yet" when a source is unreachable (a degraded source never
    /// blanks the tab, #2645 / #2700).
    @ViewBuilder
    private func galleryFeedSection(kind: FeedKind, models: [GalleryModel]) -> some View {
        if !models.isEmpty || isLoadingFeeds {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text(feedTitle(kind))
                        .font(.title2.weight(.bold))
                    if isLoadingFeeds && models.isEmpty {
                        Spacer()
                        ProgressView().controlSize(.small)
                    }
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(models, id: \.cardKey) { model in
                            FeaturedGalleryCard(model: model, transitionNamespace: heroNamespace) {
                                viewingModel = model
                                #if os(iOS)
                                SceneViewHaptic.shared.light()
                                #endif
                            }
                        }
                    }
                    .padding(.bottom, 4)
                }
                .scrollClipDisabled()
            }
        }
    }

    /// Search-results carousel (#1239 parity). `searchResults == nil` ⇒ loading;
    /// empty ⇒ 0 hits; non-empty ⇒ render the carousel.
    @ViewBuilder
    private var searchResultsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Search results")
                    .font(.title2.weight(.bold))
                if searchResults == nil {
                    Spacer()
                    ProgressView().controlSize(.small)
                }
            }
            if let searchResults {
                if searchResults.isEmpty {
                    Text("No results for \u{201C}\(activeSearchQuery)\u{201D}")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 14) {
                            ForEach(searchResults, id: \.cardKey) { model in
                                FeaturedGalleryCard(model: model, transitionNamespace: heroNamespace) {
                                    viewingModel = model
                                    #if os(iOS)
                                    SceneViewHaptic.shared.light()
                                    #endif
                                }
                            }
                        }
                        .padding(.bottom, 4)
                    }
                    .scrollClipDisabled()
                }
            }
        }
    }

    /// Fallback single-row carousel of bundled local models, shown only when every
    /// live source feed is empty (all sources unreachable) so the tab is never blank.
    private var bundledFeaturedSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Featured")
                    .font(.title2.weight(.bold))
                Spacer()
                if isLoadingFeeds {
                    ProgressView()
                        .controlSize(.small)
                }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    ForEach(featuredModels) { model in
                        FeaturedCard(model: model) {
                            selectedModel = model
                            #if os(iOS)
                            SceneViewHaptic.shared.light()
                            #endif
                        }
                    }
                }
                .padding(.bottom, 4)
            }
            .scrollClipDisabled()
        }
    }

    // MARK: - Categories section (chips grid)

    private var categoriesSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Categories")
                .font(.title2.weight(.bold))
            // FlowLayout-style category chips. Uses LazyVGrid for portable wrapping.
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 8)],
                      alignment: .leading, spacing: 8) {
                ForEach(SketchfabCategory.allCases) { category in
                    CategoryChip(category: category) {
                        selectedCategory = category
                        #if os(iOS)
                        SceneViewHaptic.shared.selection()
                        #endif
                    }
                }
            }
        }
    }

    // MARK: - Recent searches

    private var recentSearchesSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Recent searches")
                    .font(.title2.weight(.bold))
                Spacer()
                Button("Clear") {
                    recentSearches.clear()
                }
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.tint)
            }
            VStack(spacing: 6) {
                ForEach(recentSearches.items, id: \.self) { query in
                    RecentSearchRow(query: query) {
                        // Tapping a recent search re-runs it against the current
                        // source (#1239 parity).
                        searchText = query
                        activeSearchQuery = query
                    } onRemove: {
                        recentSearches.remove(query)
                    }
                }
            }
        }
    }
}

// MARK: - Featured card (large image-tile in the horizontal carousel)

private struct FeaturedCard: View {
    let model: ModelItem
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                ZStack {
                    LinearGradient(
                        colors: model.category.gradientColors,
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                    Image(systemName: model.icon)
                        .font(.system(size: 56, weight: .semibold))
                        .foregroundStyle(model.category.iconColor)
                        .shadow(color: model.category.iconColor.opacity(0.3), radius: 12)
                }
                .frame(width: 200, height: 160)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))

                VStack(alignment: .leading, spacing: 2) {
                    Text(model.name)
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Text(model.category.rawValue)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: 200, alignment: .leading)
                .padding(.top, 8)
                .padding(.horizontal, 4)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(model.name), \(model.category.rawValue), featured")
    }
}

// MARK: - Featured gallery card (source-agnostic — live image from the source CDN)

/// One carousel card for a source-agnostic `GalleryModel` (Sketchfab | Icosa |
/// Poly Haven). Replaces the former Sketchfab-only card so adding a source never
/// touches the UI (#2645 / #2700).
private struct FeaturedGalleryCard: View {
    let model: GalleryModel
    /// Namespace used by iOS 18's `.navigationTransition(.zoom(sourceID:in:))`
    /// to animate the card thumbnail into the GalleryModelViewerScreen's hero.
    /// Optional so the card stays usable in contexts without the zoom transition.
    var transitionNamespace: Namespace.ID? = nil
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .topLeading) {
                    AsyncImage(url: model.preferredThumbnailURL()) { phase in
                        switch phase {
                        case .empty:
                            ZStack {
                                Color.tertiarySystemBackground
                                ProgressView()
                                    .controlSize(.small)
                            }
                        case .success(let image):
                            image
                                .resizable()
                                .aspectRatio(contentMode: .fill)
                        case .failure:
                            ZStack {
                                Color.tertiarySystemBackground
                                Image(systemName: "photo")
                                    .font(.title2)
                                    .foregroundStyle(.secondary)
                            }
                        @unknown default:
                            Color.tertiarySystemBackground
                        }
                    }
                    .frame(width: 200, height: 160)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .modifier(MatchedSourceModifier(
                        id: "gallery-hero-\(model.cardKey)",
                        namespace: transitionNamespace
                    ))

                    // Top-left "Animated" pill (when applicable)
                    if model.isAnimated {
                        Label("Animated", systemImage: "wand.and.stars")
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(.thinMaterial, in: Capsule())
                            .padding(8)
                    }
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(model.name)
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    HStack(spacing: 6) {
                        Text(model.primaryTagDisplay)
                            .lineLimit(1)
                        if model.faceCount > 0 {
                            Text("•")
                            Text(model.formattedFaceCount + " polys")
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                }
                .frame(maxWidth: 200, alignment: .leading)
                .padding(.top, 8)
                .padding(.horizontal, 4)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(model.name), \(model.sourceId.displayName) model")
    }
}

/// Conditionally applies `.matchedTransitionSource(id:in:)` when a namespace is
/// provided. iOS 18+ wires this into `.navigationTransition(.zoom(...))` so
/// the card thumbnail morphs into the GalleryModelViewerScreen's hero on push.
/// Returns the unmodified view when no namespace is passed.
private struct MatchedSourceModifier: ViewModifier {
    let id: String
    let namespace: Namespace.ID?

    func body(content: Content) -> some View {
        if let namespace {
            content.matchedTransitionSource(id: id, in: namespace)
        } else {
            content
        }
    }
}

// Gallery models render through `GalleryModelViewerScreen` (SceneView SDK), not
// via any catalog's web iframe viewer. The whole point of this demo app is to
// showcase SceneView's renderer. Single tap → viewer that shows the preview
// state first — matches the Android UX in `GalleryModelViewerScreen.kt` (#1203,
// renamed from `SketchfabModelViewerScreen.kt` in #2645 / #2685).

// MARK: - Sample promo card (compact entry-point to a Scenes tab demo)

/// Compact card surfaced in the Explore home feed's "Try a sample" carousel.
/// Tapping pushes the demo destination onto the local NavigationStack — exactly
/// what the Samples tab would do, so the demo renders through SceneView.
private struct SamplePromoCard: View {
    let title: String
    let subtitle: String
    let icon: String
    let gradient: [Color]
    let destination: () -> AnyView

    var body: some View {
        NavigationLink {
            destination()
                .navigationTitle(title)
                .navigationBarTitleInline()
        } label: {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .bottomLeading) {
                    LinearGradient(colors: gradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                        .frame(width: 200, height: 130)
                    Image(systemName: icon)
                        .font(.system(size: 44, weight: .semibold))
                        .foregroundStyle(.tint)
                        .padding(14)
                }
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: 200, alignment: .leading)
                .padding(.top, 8)
                .padding(.horizontal, 4)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title), sample demo. \(subtitle)")
    }
}

// MARK: - Filter chip (toggleable Animated / etc.)

private struct FilterChip: View {
    let label: String
    let systemImage: String
    let isOn: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 6) {
                Image(systemName: systemImage)
                    .font(.caption.weight(.semibold))
                Text(label)
                    .font(.subheadline.weight(.medium))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(
                isOn ? AnyShapeStyle(.tint) : AnyShapeStyle(.tint.opacity(0.12)),
                in: Capsule()
            )
            .foregroundStyle(isOn ? AnyShapeStyle(.white) : AnyShapeStyle(.tint))
            .overlay(
                Capsule().strokeBorder(Color.clear)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(label) filter, \(isOn ? "on" : "off")")
    }
}

// MARK: - Source chip (Sketchfab | Icosa Gallery | Poly Haven picker)

/// One chip in the source-picker row (#2645 / #2700). The selected source is
/// filled; the others are tinted-outline.
private struct SourceChip: View {
    let title: String
    let isOn: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(
                    isOn ? AnyShapeStyle(.tint) : AnyShapeStyle(.tint.opacity(0.12)),
                    in: Capsule()
                )
                .foregroundStyle(isOn ? AnyShapeStyle(.white) : AnyShapeStyle(.tint))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title) source, \(isOn ? "selected" : "not selected")")
    }
}

// MARK: - Category chip

private struct CategoryChip: View {
    let category: SketchfabCategory
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 6) {
                Image(systemName: category.icon)
                    .font(.caption.weight(.semibold))
                Text(category.displayName)
                    .font(.subheadline.weight(.medium))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .background(.tint.opacity(0.12), in: Capsule())
            .foregroundStyle(.tint)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(category.displayName) category")
    }
}

// MARK: - Recent search row

private struct RecentSearchRow: View {
    let query: String
    let onTap: () -> Void
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Button(action: onTap) {
                Text(query)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            Button(action: onRemove) {
                Image(systemName: "xmark")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(6)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Remove \(query) from recent searches")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

// MARK: - Category sheet (presented as a modal when a chip is tapped)

private struct CategorySheet: View {
    let category: SketchfabCategory
    let onSearchTriggered: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Spacer(minLength: 16)
                Image(systemName: category.icon)
                    .font(.system(size: 48, weight: .semibold))
                    .foregroundStyle(.tint)
                    .padding(20)
                    .background(.tint.opacity(0.15), in: Circle())

                Text(category.displayName)
                    .font(.title.weight(.bold))

                Text("Browse \(category.displayName.lowercased()) models from Sketchfab. Tap the search button to load results for this category.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

                Button {
                    onSearchTriggered(category.displayName)
                    dismiss()
                } label: {
                    Label("Search \(category.displayName)", systemImage: "magnifyingglass")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(.tint, in: Capsule())
                        .foregroundStyle(.white)
                }
                .padding(.horizontal, 24)

                Spacer()
            }
            .navigationTitle("Category")
            .navigationBarTitleInline()
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

// MARK: - Model card

private struct ModelCard: View {
    let model: ModelItem
    let isFavorite: Bool
    let onTap: () -> Void
    let onToggleFavorite: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 8) {
                ZStack(alignment: .topTrailing) {
                    // Model icon preview area
                    RoundedRectangle(cornerRadius: 12)
                        .fill(
                            LinearGradient(
                                colors: model.category.gradientColors,
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(height: 120)
                        .overlay {
                            Image(systemName: model.icon)
                                .font(.system(size: 36))
                                .foregroundStyle(model.category.iconColor)
                        }

                    // Favorite button
                    Button(action: onToggleFavorite) {
                        Image(systemName: isFavorite ? "heart.fill" : "heart")
                            .font(.body)
                            .foregroundStyle(isFavorite ? .red : .secondary)
                            .padding(8)
                            .background(.ultraThinMaterial, in: Circle())
                    }
                    .padding(6)
                }

                VStack(spacing: 2) {
                    Text(model.name)
                        .font(.subheadline.weight(.medium))
                        .lineLimit(1)
                    Text(model.category.rawValue)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 4)
            }
            .padding(8)
            .background(Color.secondarySystemBackground)
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(model.name), \(model.category.rawValue)")
        .accessibilityAddTraits(.isButton)
    }
}

// MARK: - Full-screen model viewer

struct ModelViewerScreen: View {
    let model: ModelItem
    /// Frozen to `false` when `qa_mode` is active (set by `?qa_mode=1`
    /// deep-link or `-qa_mode 1` launch argument) so QA screenshots are
    /// deterministic — mirrors Android's `qa_mode` intent extra.
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false
    @State private var autoRotate = true
    @State private var loadedModel: ModelNode?
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var selectedEnvironment: SceneEnvironment = .studio
    @State private var showShareSheet = false
    /// URL to a USDZ file bundled with the app. When set, iOS Quick Look opens
    /// over the scene with its built-in AR button (top-right in the QL viewer).
    @State private var arPreviewURL: URL?
    private let favoritesManager = FavoritesManager.shared

    init(model: ModelItem) {
        self.model = model
    }

    var body: some View {
        ZStack {
            LinearGradient(
                gradient: Gradient(colors: [
                    Color(red: 0.08, green: 0.08, blue: 0.12),
                    Color(red: 0.15, green: 0.15, blue: 0.22),
                    Color(red: 0.10, green: 0.10, blue: 0.18)
                ]),
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            sceneView
                .ignoresSafeArea()

            if isLoading {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(.white)
                    .scaleEffect(1.5)
            }

            if let errorMessage {
                VStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.title)
                        .foregroundStyle(.yellow)
                    Text("Failed to load model")
                        .font(.headline)
                        .foregroundStyle(.white)
                    Text(errorMessage)
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.7))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }
                .padding()
                .background(.ultraThinMaterial)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .padding()
            }

            VStack {
                Spacer()
                controlsOverlay
            }
        }
        .navigationTitle(model.name)
        .navigationBarTitleInline()
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button {
                    favoritesManager.toggle(model.id)
                    #if os(iOS)
                    SceneViewHaptic.shared.light()
                    #endif
                } label: {
                    Image(systemName: favoritesManager.isFavorite(model.id) ? "heart.fill" : "heart")
                        .foregroundStyle(favoritesManager.isFavorite(model.id) ? .red : .white)
                }
                .accessibilityLabel(favoritesManager.isFavorite(model.id) ? "Remove from favorites" : "Add to favorites")

                Button {
                    autoRotate.toggle()
                    #if os(iOS)
                    SceneViewHaptic.shared.selection()
                    #endif
                } label: {
                    Image(systemName: autoRotate ? "rotate.3d.fill" : "rotate.3d")
                        .foregroundStyle(.white)
                }
                .accessibilityLabel(autoRotate ? "Stop rotation" : "Start rotation")

                #if os(iOS)
                Button {
                    shareScreenshot()
                } label: {
                    Image(systemName: "square.and.arrow.up")
                        .foregroundStyle(.white)
                }
                .accessibilityLabel("Share screenshot")
                #endif
            }
        }
        .task {
            await loadModel()
        }
        #if os(iOS)
        .quickLookPreview($arPreviewURL)
        #endif
    }

    // MARK: - Model Loading

    private func loadModel() async {
        isLoading = true
        errorMessage = nil
        do {
            let node = try await ModelNode.load(model.asset)
            _ = node.scaleToUnits(model.scale)
            // centerOrigin recenters the model's bounding box on world origin
            // so the orbit camera (looking at 0,0,0) frames the body, not the
            // asset's authored pivot point (often the floor of the bounding box).
            // Mirrors the Android fix (commit 36156142, QA 2026-05-11).
            _ = node.centerOrigin()
            loadedModel = node
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    // MARK: - Scene

    @ViewBuilder
    private var sceneView: some View {
        // qa_mode freezes auto-rotation for deterministic QA screenshots.
        if autoRotate && !qaMode {
            SceneView { root in
                if let loadedModel {
                    loadedModel.entity.position = .zero
                    root.addChild(loadedModel.entity)
                }
            }
            .environment(selectedEnvironment)
            .cameraControls(.orbit)
            .autoRotate(speed: 0.4)
            .id("viewer-auto-\(loadedModel != nil)-\(selectedEnvironment.name)")
        } else {
            SceneView { root in
                if let loadedModel {
                    loadedModel.entity.position = .zero
                    root.addChild(loadedModel.entity)
                }
            }
            .environment(selectedEnvironment)
            .cameraControls(.orbit)
            .id("viewer-manual-\(loadedModel != nil)-\(selectedEnvironment.name)")
        }
    }

    // MARK: - Controls

    private var controlsOverlay: some View {
        VStack(spacing: 12) {
            #if os(iOS)
            // Prominent AR entry point — opens Apple Quick Look AR over the scene with the
            // bundled USDZ asset. Quick Look's built-in AR button (top-right of the QL
            // viewer) then drops the model into the user's real environment.
            if Bundle.main.url(forResource: model.asset, withExtension: "usdz") != nil {
                Button {
                    arPreviewURL = Bundle.main.url(forResource: model.asset, withExtension: "usdz")
                    SceneViewHaptic.shared.light()
                } label: {
                    Label("View in AR", systemImage: "arkit")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(.tint, in: Capsule())
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("View this model in AR")
            }
            #endif

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(SceneEnvironment.allPresets, id: \.name) { env in
                        Button {
                            selectedEnvironment = env
                            #if os(iOS)
                            SceneViewHaptic.shared.light()
                            #endif
                        } label: {
                            Text(env.name)
                                .font(.caption2)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .background(
                                    selectedEnvironment.name == env.name
                                        ? AnyShapeStyle(.blue)
                                        : AnyShapeStyle(.white.opacity(0.15))
                                )
                                .clipShape(Capsule())
                                .foregroundStyle(.white)
                        }
                    }
                }
            }

            Text("Pinch to zoom \u{00B7} Drag to orbit")
                .font(.caption)
                .foregroundStyle(.white.opacity(0.5))
        }
        .padding()
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding()
    }

    // MARK: - Share

    #if os(iOS)
    @MainActor
    private func shareScreenshot() {
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let window = windowScene.windows.first else { return }

        let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
        let image = renderer.image { ctx in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }

        let activityVC = UIActivityViewController(
            activityItems: [image, "Check out this 3D model in SceneView!"],
            applicationActivities: nil
        )

        if let presenter = window.rootViewController {
            if let popover = activityVC.popoverPresentationController {
                popover.sourceView = presenter.view
                popover.sourceRect = CGRect(x: presenter.view.bounds.midX, y: 40, width: 0, height: 0)
            }
            presenter.present(activityVC, animated: true)
        }
    }
    #endif
}

// MARK: - Gallery model viewer (source-agnostic — renders USDZ sources in SceneView)

/// Full-screen viewer for a source-agnostic `GalleryModel` (Sketchfab | Icosa |
/// Poly Haven). iOS port of Android's `GalleryModelViewerScreen.kt` (renamed from
/// `SketchfabModelViewerScreen.kt` in #2645 / #2685). See #2700.
///
/// **Render path (Sketchfab):** downloads the USDZ through the source's
/// `download(model:progress:)`, caches it, then loads it into `SceneView` via
/// `ModelNode.load(contentsOf:)`. Every model the user renders flows through
/// SceneView's renderer (RealityKit on iOS) — never a web iframe viewer.
///
/// **Honest degradation (Icosa / Poly Haven):** these Creative-Commons catalogs
/// are glTF-native, and RealityKit loads only USDZ, so their in-app 3D render is
/// honestly deferred: the viewer shows the preview (thumbnail + attribution +
/// tags) with a clearly-labelled "3D preview coming soon" state instead of a
/// fake or a crash. Browse + credit are fully available. The Android demo renders
/// these via Filament; keep the two in sync when Apple-side glTF support lands.
///
/// Wow-factor polish (carried from the Sketchfab viewer):
/// - Premium studio HDR environment by default (PBR-flattering reflections).
/// - Cross-fade from the source thumbnail (Ken-Burns zoom) to the live SceneView.
/// - Subtle radial vignette overlay for cinematic / Apple-Store framing.
/// - Auto-rotate on by default.
struct GalleryModelViewerScreen: View {
    let model: GalleryModel
    let source: any ModelSource
    @State private var loadedNode: ModelNode?
    @State private var isLoading = false
    @State private var downloadProgress: Double = 0
    @State private var errorMessage: String?
    @State private var selectedEnvironment: SceneEnvironment = .studio
    /// Frozen to `false` when `qa_mode` is active — see `DeepLinkRouter.qaModeDefaultsKey`.
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false
    @State private var autoRotate = true
    /// Drives the thumbnail Ken-Burns zoom during download, and the post-reveal
    /// cross-fade once the SceneView is ready.
    @State private var thumbnailZoom: CGFloat = 1.0
    @State private var sceneRevealed = false
    /// Mirrors Android's `Stage.Preview` initial state — gates the network
    /// download behind an explicit "Open in SceneView" CTA so the user sees the
    /// attribution / tags / not-renderable state before committing.
    @State private var hasUserOpened = false
    @Environment(\.dismiss) private var dismiss

    /// `true` when a model from this source can be rendered in-app through
    /// SceneView (RealityKit). Sketchfab serves USDZ → renders; the glTF-native
    /// CC sources are browse + search only (honest "coming soon"). See #2700.
    private var canRenderInApp: Bool { source.rendersInApp && model.downloadable }

    /// Largest source thumbnail (for the during-download Ken-Burns hero).
    private var heroThumbnailURL: URL? {
        model.preferredThumbnailURL(minWidth: 640, maxWidth: 1280)
    }

    private var ctaTitle: String {
        if canRenderInApp { return "Open in SceneView" }
        if !source.rendersInApp { return "3D preview coming soon" }
        return "Not downloadable"
    }

    var body: some View {
        ZStack {
            LinearGradient(
                gradient: Gradient(colors: [
                    Color(red: 0.08, green: 0.08, blue: 0.12),
                    Color(red: 0.15, green: 0.15, blue: 0.22),
                    Color(red: 0.10, green: 0.10, blue: 0.18),
                ]),
                startPoint: .top, endPoint: .bottom
            )
            .ignoresSafeArea()

            if hasUserOpened {
                // Downloading / Rendering stages — mirrors Android's
                // `Stage.Downloading` + `Stage.Rendering`.
                sceneView
                    .ignoresSafeArea()
                    .opacity(sceneRevealed ? 1 : 0)

                // Thumbnail cross-fade overlay — shows the source image with a
                // slow Ken-Burns zoom during download. Fades out (over 0.6 s)
                // once the SceneView has had time to mount the loaded ModelNode,
                // producing the "come to life" transition.
                if !sceneRevealed {
                    thumbnailHero
                        .ignoresSafeArea()
                        .transition(.opacity)
                }

                // Cinematic vignette — radial dark gradient at the corners,
                // invisible in the centre.
                vignette
                    .ignoresSafeArea()
                    .allowsHitTesting(false)

                if isLoading {
                    VStack(spacing: 14) {
                        ProgressView(value: max(0.05, downloadProgress))
                            .progressViewStyle(.linear)
                            .tint(.white)
                            .frame(width: 220)
                        Text("Loading \(model.name)\u{2026}")
                            .font(.subheadline)
                            .foregroundStyle(.white)
                        Text("Streaming from \(model.sourceId.displayName) \u{00B7} rendering in SceneView")
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.6))
                    }
                    .padding(20)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
                }

                if let errorMessage {
                    errorOverlay(message: errorMessage)
                }

                VStack {
                    Spacer()
                    controlsOverlay
                }
            } else {
                // `Stage.Preview` — thumbnail + attribution / tags + CTA before
                // committing to the download. Matches Android's `PreviewContent`.
                // The not-renderable state is surfaced here so the user is never
                // pushed into a viewer that can't render the model.
                previewContent
            }
        }
        .navigationTitle(model.name)
        .navigationBarTitleInline()
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    autoRotate.toggle()
                    #if os(iOS)
                    SceneViewHaptic.shared.selection()
                    #endif
                } label: {
                    Image(systemName: autoRotate ? "rotate.3d.fill" : "rotate.3d")
                }
                .accessibilityLabel(autoRotate ? "Stop rotation" : "Start rotation")
                .disabled(!hasUserOpened || loadedNode == nil)
            }
        }
        // The download is gated behind the user's CTA tap (`hasUserOpened`) so
        // the .task only fires once they've consented from the preview state.
        .task(id: hasUserOpened) {
            guard hasUserOpened else { return }
            await loadFromSource()
        }
        .onAppear {
            // Start the Ken-Burns slow zoom on the thumbnail the moment the screen
            // appears, so it's already animating when the user reads the title.
            withAnimation(.easeInOut(duration: 8).repeatForever(autoreverses: true)) {
                thumbnailZoom = 1.18
            }
        }
        .onChange(of: loadedNode != nil) { _, ready in
            guard ready else { return }
            // Give the SceneView one frame to mount its content before fading
            // the thumbnail out — avoids a brief black flash mid-transition.
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(220))
                withAnimation(.easeOut(duration: 0.6)) { sceneRevealed = true }
                #if os(iOS)
                SceneViewHaptic.shared.light()
                #endif
            }
        }
    }

    /// `Stage.Preview` parallel to Android's `PreviewContent`. Thumbnail +
    /// attribution + tag chips + CTA. When the source can't render in-app
    /// (glTF-only CC catalogs, or a non-downloadable Sketchfab model) the CTA is
    /// disabled and an honest explanation replaces the "come to life" promise.
    @ViewBuilder
    private var previewContent: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                AsyncImage(url: heroThumbnailURL) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().aspectRatio(contentMode: .fit)
                    default:
                        Rectangle().fill(.tint.opacity(0.08))
                            .aspectRatio(16/9, contentMode: .fit)
                            .overlay { ProgressView() }
                    }
                }
                .frame(maxHeight: 280)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))

                // Attribution line — author · license · via <source>. For CC-BY
                // assets crediting the author is a licence requirement, so it is
                // surfaced regardless of which catalog the model came from.
                Text(model.attributionLine)
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.85))

                if !model.tags.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(model.tags.prefix(10), id: \.self) { tag in
                                Text(tag)
                                    .font(.caption)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 4)
                                    .background(.white.opacity(0.15), in: Capsule())
                                    .foregroundStyle(.white)
                            }
                        }
                    }
                }

                Divider().overlay(.white.opacity(0.2))

                Button {
                    guard canRenderInApp else { return }
                    hasUserOpened = true
                    #if os(iOS)
                    SceneViewHaptic.shared.selection()
                    #endif
                } label: {
                    Label(ctaTitle,
                          systemImage: canRenderInApp ? "cube.transparent.fill" : "cube.transparent")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(.tint, in: Capsule())
                        .foregroundStyle(.white)
                }
                .disabled(!canRenderInApp)
                .opacity(canRenderInApp ? 1.0 : 0.5)

                if !canRenderInApp {
                    Text(notRenderableExplanation)
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.6))
                        .frame(maxWidth: .infinity, alignment: .center)
                }
            }
            .padding(20)
        }
    }

    /// Honest explanation for the non-render state — the iOS subset boundary.
    private var notRenderableExplanation: String {
        if !source.rendersInApp {
            return "3D preview is coming soon on iOS for \(model.sourceId.displayName). "
                + "This Creative-Commons model is glTF-only, and SceneView on Apple "
                + "platforms (RealityKit) renders USDZ. You can browse and credit it "
                + "now — Sketchfab models render live in SceneView."
        }
        return "This model is not downloadable on the Sketchfab free tier and can't "
            + "be rendered in SceneView yet."
    }

    /// Error overlay extracted for re-use from Downloading/Rendering + Retry.
    @ViewBuilder
    private func errorOverlay(message: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.title)
                .foregroundStyle(.yellow)
            Text("Failed to load model").font(.headline).foregroundStyle(.white)
            Text(message)
                .font(.caption)
                .foregroundStyle(.white.opacity(0.7))
                .multilineTextAlignment(.center)
                .padding(.horizontal)
            Button("Retry") {
                errorMessage = nil
                hasUserOpened = false
                #if os(iOS)
                SceneViewHaptic.shared.light()
                #endif
            }
            .buttonStyle(.borderedProminent)
            .padding(.top, 4)
        }
        .padding()
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .padding()
    }

    private func loadFromSource() async {
        guard loadedNode == nil else { return }
        isLoading = true
        errorMessage = nil
        downloadProgress = 0
        do {
            let localURL = try await source.download(
                model: model,
                progress: { p in
                    Task { @MainActor in
                        self.downloadProgress = p
                    }
                }
            )
            let node = try await ModelNode.load(contentsOf: localURL)
            _ = node.scaleToUnits(1.0)
            // centerOrigin recenters the bounding box on world origin so the orbit
            // camera frames the model body, not the asset's authored pivot.
            _ = node.centerOrigin()
            loadedNode = node
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
        isLoading = false
    }

    @ViewBuilder
    private var sceneView: some View {
        if autoRotate && !qaMode {
            SceneView { root in
                if let loadedNode {
                    loadedNode.entity.position = .zero
                    root.addChild(loadedNode.entity)
                }
            }
            .environment(selectedEnvironment)
            .cameraControls(.orbit)
            .autoRotate(speed: 0.4)
            .id("gallery-auto-\(loadedNode != nil)-\(selectedEnvironment.name)")
        } else {
            SceneView { root in
                if let loadedNode {
                    loadedNode.entity.position = .zero
                    root.addChild(loadedNode.entity)
                }
            }
            .environment(selectedEnvironment)
            .cameraControls(.orbit)
            .id("gallery-manual-\(loadedNode != nil)-\(selectedEnvironment.name)")
        }
    }

    /// Cinematic vignette — costs ~0 GPU and lifts the model in the centre.
    private var vignette: some View {
        RadialGradient(
            colors: [.black.opacity(0.0), .black.opacity(0.35)],
            center: .center,
            startRadius: 200,
            endRadius: 800
        )
        .blendMode(.multiply)
    }

    /// Hero thumbnail shown during download with a slow Ken-Burns zoom; cross-fades
    /// out once the SceneView has mounted the loaded ModelNode.
    @ViewBuilder
    private var thumbnailHero: some View {
        ZStack {
            AsyncImage(url: heroThumbnailURL) { phase in
                switch phase {
                case .success(let image):
                    image.resizable().aspectRatio(contentMode: .fill)
                default:
                    Color.clear
                }
            }
            .scaleEffect(thumbnailZoom)
            .blur(radius: 6)
            Color.black.opacity(0.30)
        }
    }

    private var controlsOverlay: some View {
        VStack(spacing: 12) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(SceneEnvironment.allPresets, id: \.name) { env in
                        Button {
                            selectedEnvironment = env
                            #if os(iOS)
                            SceneViewHaptic.shared.light()
                            #endif
                        } label: {
                            Text(env.name)
                                .font(.caption2)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .background(
                                    selectedEnvironment.name == env.name
                                        ? AnyShapeStyle(.blue)
                                        : AnyShapeStyle(.white.opacity(0.15))
                                )
                                .clipShape(Capsule())
                                .foregroundStyle(.white)
                        }
                    }
                }
            }

            HStack(spacing: 10) {
                if model.faceCount > 0 {
                    Label(model.formattedFaceCount + " polys", systemImage: "square.grid.3x3")
                }
                if model.isAnimated {
                    Label("Animated", systemImage: "wand.and.stars")
                }
            }
            .font(.caption.weight(.medium))
            .foregroundStyle(.white.opacity(0.7))
        }
        .padding()
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding()
    }
}

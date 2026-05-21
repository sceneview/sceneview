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
    @State private var viewingSketchfabModel: SketchfabModel?
    /// Which carousel the user tapped from — set together with
    /// `viewingSketchfabModel` so the destination's `.navigationTransition
    /// (.zoom(sourceID:))` builds the same ID as the source card. Without this
    /// prefix the matched source ID would be `"sketchfab-hero-\(uid)"` on both
    /// the Staff Picks card and the Most Liked card for the same model (the
    /// Sketchfab API returns overlapping uids across feeds), giving SwiftUI two
    /// matched sources with the same key in the same namespace.
    @State private var viewingFeedID: String?
    @State private var selectedCategory: SketchfabCategory?
    @State private var recentSearches = RecentSearches()
    @State private var sketchfabStaffPicks: [SketchfabModel] = []
    @State private var sketchfabMostLiked: [SketchfabModel] = []
    @State private var sketchfabRecent: [SketchfabModel] = []
    @State private var isLoadingFeeds = false
    @State private var feedsError: String?
    /// When `true`, all three carousels filter to `animated=true` (skeletal rigs).
    @State private var animatedOnly = false
    /// Shared namespace for the iOS 18 zoom transition: the carousel card's
    /// thumbnail morphs into the SketchfabModelViewerScreen hero when the
    /// navigation pushes the viewer.
    @Namespace private var heroNamespace
    private let favoritesManager = FavoritesManager.shared

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
                    // Home feed mix — samples first (always available), then live Sketchfab
                    // feeds when an API key is configured. Each row shows SceneView SDK
                    // content (bundled or streamed) — never the Sketchfab web viewer.
                    trySampleSection
                    if !sketchfabStaffPicks.isEmpty || !sketchfabMostLiked.isEmpty || !sketchfabRecent.isEmpty {
                        filtersBar
                    }
                    if sketchfabStaffPicks.isEmpty && sketchfabMostLiked.isEmpty && sketchfabRecent.isEmpty {
                        bundledFeaturedSection
                    } else {
                        feedSection(title: "Staff Picks",   feedID: "staff", models: sketchfabStaffPicks)
                        feedSection(title: "Most Liked",    feedID: "liked", models: sketchfabMostLiked)
                        feedSection(title: "Recently Added", feedID: "recent", models: sketchfabRecent)
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
            .searchable(text: $searchText, prompt: "Search 3D models on Sketchfab")
            .onSubmit(of: .search) {
                let q = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
                if !q.isEmpty {
                    recentSearches.push(q)
                    #if os(iOS)
                    HapticManager.lightTap()
                    #endif
                }
            }
            .task { await loadSketchfabFeeds() }
            // Pull-to-refresh on the Sketchfab carousels — matches the Android
            // ExploreTabScreen.PullToRefreshBox added in #1203. Only fires
            // when an API key is configured (loadSketchfabFeeds early-returns
            // otherwise) so builds without the key don't spin a useless
            // spinner. `force: true` bypasses the "already loaded" guard so
            // a manual refresh actually re-fetches.
            .refreshable { await loadSketchfabFeeds(force: true) }
            .navigationDestination(item: $selectedModel) { model in
                ModelViewerScreen(model: model)
            }
            .navigationDestination(item: $viewingSketchfabModel) { model in
                SketchfabModelViewerScreen(model: model)
                    // iOS 18 zoom navigation transition — the source thumbnail
                    // matchedTransitionSource lives on the FeaturedSketchfabCard
                    // identified by `(viewingFeedID, model.uid)` since the same
                    // Sketchfab model can appear in more than one carousel.
                    .navigationTransition(.zoom(
                        sourceID: "sketchfab-hero-\(viewingFeedID ?? "any")-\(model.uid)",
                        in: heroNamespace
                    ))
            }
            .sheet(item: $selectedCategory) { category in
                CategorySheet(category: category) { query in
                    searchText = query
                    recentSearches.push(query)
                }
                .presentationDetents([.medium, .large])
                #if os(iOS)
                .presentationBackground(.regularMaterial)
                .presentationCornerRadius(28)
                #endif
            }
        }
    }

    // MARK: - Sketchfab data loading

    /// Loads the three curated feeds in parallel. Falls back silently to the bundled
    /// `featuredModels` carousel when no API key is configured or the network call fails.
    ///
    /// Pass `force: true` from `.refreshable {}` so manual pull-to-refresh
    /// bypasses the "already loaded" guard and actually re-fetches.
    private func loadSketchfabFeeds(force: Bool = false) async {
        guard SketchfabConfig.apiKey != nil else { return }
        if !force,
           !sketchfabStaffPicks.isEmpty || !sketchfabMostLiked.isEmpty || !sketchfabRecent.isEmpty {
            return
        }
        isLoadingFeeds = true
        defer { isLoadingFeeds = false }

        async let staff = SketchfabService.shared.staffPicks(animated: animatedOnly ? true : nil, limit: 10)
        async let liked = SketchfabService.shared.featured(animated: animatedOnly ? true : nil, limit: 10)
        async let recent = SketchfabService.shared.recentlyAdded(animated: animatedOnly ? true : nil, limit: 10)

        do {
            let (s, l, r) = try await (staff, liked, recent)
            sketchfabStaffPicks = s
            sketchfabMostLiked = l
            sketchfabRecent = r
            feedsError = nil
        } catch {
            feedsError = "Couldn't reach Sketchfab — showing offline picks"
        }
    }

    /// Triggered when the user toggles a filter chip. Clears the current feeds
    /// (so the loading state shows again) then re-runs the parallel fetch.
    private func reloadWithFilters() {
        sketchfabStaffPicks = []
        sketchfabMostLiked = []
        sketchfabRecent = []
        Task { await loadSketchfabFeeds() }
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
                    animatedOnly.toggle()
                    #if os(iOS)
                    HapticManager.selectionChanged()
                    #endif
                    reloadWithFilters()
                }
                // Future chips (V1.1): License, Min poly count, Author.
            }
        }
        .scrollClipDisabled()
    }

    // MARK: - Feed section helpers (Staff Picks / Most Liked / Recently Added)

    /// One horizontal carousel of Sketchfab models, used three times in the body.
    private func feedSection(title: String, feedID: String, models: [SketchfabModel]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.title2.weight(.bold))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    ForEach(models) { model in
                        FeaturedSketchfabCard(
                            model: model,
                            transitionNamespace: heroNamespace,
                            transitionFeedID: feedID,
                        ) {
                            // Push directly to the viewer. The viewer's initial
                            // state is `Stage.Preview` (description / tags /
                            // CTA), mirroring Android's `Stage.Preview` in
                            // SketchfabModelViewerScreen.kt — the download only
                            // fires after the user taps the CTA.
                            viewingFeedID = feedID
                            viewingSketchfabModel = model
                            #if os(iOS)
                            HapticManager.lightTap()
                            #endif
                        }
                    }
                }
                .padding(.bottom, 4)
            }
            .scrollClipDisabled()
        }
    }

    /// Fallback single-row carousel of bundled local models, shown when no Sketchfab
    /// API key is configured (or while the live data is still loading).
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
            if let feedsError {
                Text(feedsError)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.bottom, 4)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    ForEach(featuredModels) { model in
                        FeaturedCard(model: model) {
                            selectedModel = model
                            #if os(iOS)
                            HapticManager.lightTap()
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
                        HapticManager.selectionChanged()
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
                        searchText = query
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

// MARK: - Featured Sketchfab card (live image from sketchfab.com CDN)

private struct FeaturedSketchfabCard: View {
    let model: SketchfabModel
    /// Namespace used by iOS 18's `.navigationTransition(.zoom(sourceID:in:))`
    /// to animate the card thumbnail into the SketchfabModelViewerScreen's hero
    /// when the parent NavigationStack pushes it. Optional so the card stays
    /// usable in contexts that don't wire up the zoom transition (e.g. future
    /// list views that present via .sheet).
    var transitionNamespace: Namespace.ID? = nil
    /// Carousel id used in the matched source key so a model appearing in two
    /// feeds (Staff Picks + Most Liked) doesn't collide on the same namespace
    /// key. Pairs with `viewingFeedID` on ExploreTab.
    var transitionFeedID: String? = nil
    let onTap: () -> Void

    /// Pick a thumbnail close to the card's render size (≥320 wide, ≤640) to avoid
    /// downloading the 2k-pixel original for a 200×160 view.
    private var thumbnailURL: URL? {
        let images = model.thumbnails.images
        let preferred = images.first(where: { $0.width >= 320 && $0.width <= 640 })
            ?? images.max(by: { $0.width < $1.width })
            ?? images.first
        return preferred.flatMap { URL(string: $0.url) }
    }

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .topLeading) {
                    AsyncImage(url: thumbnailURL) { phase in
                        switch phase {
                        case .empty:
                            ZStack {
                                Color(.tertiarySystemBackground)
                                ProgressView()
                                    .controlSize(.small)
                            }
                        case .success(let image):
                            image
                                .resizable()
                                .aspectRatio(contentMode: .fill)
                        case .failure:
                            ZStack {
                                Color(.tertiarySystemBackground)
                                Image(systemName: "photo")
                                    .font(.title2)
                                    .foregroundStyle(.secondary)
                            }
                        @unknown default:
                            Color(.tertiarySystemBackground)
                        }
                    }
                    .frame(width: 200, height: 160)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .modifier(MatchedSourceModifier(
                        id: "sketchfab-hero-\(transitionFeedID ?? "any")-\(model.uid)",
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
        .accessibilityLabel("\(model.name), Sketchfab model")
    }
}

/// Conditionally applies `.matchedTransitionSource(id:in:)` when a namespace is
/// provided. iOS 18+ wires this into `.navigationTransition(.zoom(...))` so
/// the card thumbnail morphs into the SketchfabModelViewerScreen's hero on push.
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

// MARK: - Helpers on SketchfabModel for card / sheet display

private extension SketchfabModel {
    /// True when the Sketchfab model carries one or more skeletal animations.
    var isAnimated: Bool { animationCount > 0 }

    var formattedFaceCount: String {
        if faceCount >= 1_000_000 { return String(format: "%.1fM", Double(faceCount) / 1_000_000) }
        if faceCount >= 1_000 { return String(format: "%.1fk", Double(faceCount) / 1_000) }
        return "\(faceCount)"
    }

    var formattedLikeCount: String {
        if likeCount >= 1_000 { return String(format: "%.1fk", Double(likeCount) / 1_000) }
        return "\(likeCount)"
    }

    /// First tag in Title Case, or a generic fallback.
    var primaryTagDisplay: String {
        tags?.first?.name.capitalized ?? "3D Model"
    }
}

// Sketchfab models render through `SketchfabModelViewerScreen` (SceneView SDK),
// not via Sketchfab's web iframe viewer. The whole point of this demo app is to
// showcase SceneView's renderer. The previous intermediate `SketchfabModelSheet`
// (preview state with description + "Open in SceneView" CTA) was removed in
// favour of a direct card → viewer push so iOS 18's
// `.navigationTransition(.zoom)` can fire — matches the Android UX added in
// #1203 (single tap → modal viewer that starts loading immediately).

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
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
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
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
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
            .background(Color(.secondarySystemBackground))
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
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button {
                    favoritesManager.toggle(model.id)
                    #if os(iOS)
                    HapticManager.lightTap()
                    #endif
                } label: {
                    Image(systemName: favoritesManager.isFavorite(model.id) ? "heart.fill" : "heart")
                        .foregroundStyle(favoritesManager.isFavorite(model.id) ? .red : .white)
                }
                .accessibilityLabel(favoritesManager.isFavorite(model.id) ? "Remove from favorites" : "Add to favorites")

                Button {
                    autoRotate.toggle()
                    #if os(iOS)
                    HapticManager.selectionChanged()
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
        if autoRotate {
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
                    HapticManager.lightTap()
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
                            HapticManager.lightTap()
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

// MARK: - Sketchfab model viewer (downloads GLB via SketchfabService, renders in SceneView)

/// Full-screen viewer for a model coming from the Sketchfab API.
///
/// Downloads the GLB through `SketchfabService.downloadModel(uid:progress:)`, caches it under
/// `Caches/sketchfab/`, then loads it into `SceneView` via `ModelNode.load(contentsOf: URL)`.
///
/// This is the showcase path for the demo: every model the user touches — bundled or
/// streamed — flows through SceneView's renderer (RealityKit on iOS). The Sketchfab web
/// viewer / iframe / embed is intentionally never used.
///
/// Wow-factor polish (2026-05-11 session `wow-factor-sketchfab`):
/// - Premium studio HDR environment by default (PBR-flattering reflections).
/// - Cross-fade from the Sketchfab thumbnail (Ken-Burns zoom) to the live SceneView
///   so the model "comes to life" instead of popping in.
/// - Subtle radial vignette overlay for cinematic / Apple-Store framing.
/// - Auto-rotate on by default — the model presents itself from every angle.
struct SketchfabModelViewerScreen: View {
    let model: SketchfabModel
    @State private var loadedNode: ModelNode?
    @State private var isLoading = false
    @State private var downloadProgress: Double = 0
    @State private var errorMessage: String?
    @State private var selectedEnvironment: SceneEnvironment = .studio
    @State private var autoRotate = true
    /// Drives the thumbnail Ken-Burns zoom during download, and the post-reveal
    /// cross-fade once the SceneView is ready.
    @State private var thumbnailZoom: CGFloat = 1.0
    @State private var sceneRevealed = false
    /// Mirrors Android's `Stage.Preview` initial state — gates the network
    /// download behind an explicit "Open in SceneView" CTA so the user sees
    /// the description / tags / non-downloadable warning before committing.
    /// Flips to `true` on CTA tap (or on retry from the error state).
    @State private var hasUserOpened = false
    @Environment(\.dismiss) private var dismiss

    /// Largest Sketchfab thumbnail (for the during-download Ken-Burns hero).
    private var heroThumbnailURL: URL? {
        let images = model.thumbnails.images
        let preferred = images.max(by: { $0.width < $1.width }) ?? images.first
        return preferred.flatMap { URL(string: $0.url) }
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

                // Thumbnail cross-fade overlay — shows the Sketchfab image with a
                // slow Ken-Burns zoom during download. Fades out (over 0.6 s)
                // once the SceneView has had time to mount the loaded ModelNode,
                // producing the "come to life" transition.
                if !sceneRevealed {
                    thumbnailHero
                        .ignoresSafeArea()
                        .transition(.opacity)
                }

                // Cinematic vignette — radial dark gradient at the corners,
                // invisible in the centre. Costs nothing to render and gives the
                // viewer the "Apple Store hero" framing without obscuring the
                // model.
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
                        Text("Streaming from Sketchfab \u{00B7} rendering in SceneView")
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
                // `Stage.Preview` — show description / tags / "Open in SceneView"
                // CTA before committing to the download. Matches Android's
                // `Stage.Preview` `PreviewContent`. The non-downloadable warning
                // is surfaced here so the user is not pushed into a viewer that
                // can't render the model on the Sketchfab free tier.
                previewContent
            }
        }
        .navigationTitle(model.name)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    autoRotate.toggle()
                    #if os(iOS)
                    HapticManager.selectionChanged()
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
            await loadFromSketchfab()
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
                HapticManager.lightTap()
                #endif
            }
        }
    }

    /// `Stage.Preview` parallel to Android's `PreviewContent`. Description +
    /// tag chips + "Open in SceneView" CTA. Disables the CTA when the model
    /// is not downloadable on the user's Sketchfab tier (carries the warning
    /// the dropped `SketchfabModelSheet` used to surface).
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

                if let desc = model.description, !desc.isEmpty {
                    Text(desc.trimmingCharacters(in: .whitespacesAndNewlines))
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.85))
                        .lineLimit(8)
                }

                if let tags = model.tags, !tags.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(tags.prefix(10), id: \.name) { tag in
                                Text(tag.name)
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
                    hasUserOpened = true
                    #if os(iOS)
                    HapticManager.selectionChanged()
                    #endif
                } label: {
                    Label(model.downloadable ? "Open in SceneView" : "Not downloadable",
                          systemImage: "cube.transparent.fill")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(.tint, in: Capsule())
                        .foregroundStyle(.white)
                }
                .disabled(!model.downloadable)
                .opacity(model.downloadable ? 1.0 : 0.5)

                if !model.downloadable {
                    Text("This model is not downloadable on the Sketchfab free tier and can't be rendered in SceneView yet.")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.6))
                        .frame(maxWidth: .infinity, alignment: .center)
                }
            }
            .padding(20)
        }
    }

    /// Error overlay extracted for re-use from both Downloading/Rendering and
    /// future Retry flows.
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
                HapticManager.lightTap()
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

    private func loadFromSketchfab() async {
        guard loadedNode == nil else { return }
        isLoading = true
        errorMessage = nil
        downloadProgress = 0
        do {
            let localURL = try await SketchfabService.shared.downloadModel(
                uid: model.uid,
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
        if autoRotate {
            SceneView { root in
                if let loadedNode {
                    loadedNode.entity.position = .zero
                    root.addChild(loadedNode.entity)
                }
            }
            .environment(selectedEnvironment)
            .cameraControls(.orbit)
            .autoRotate(speed: 0.4)
            .id("sketchfab-auto-\(loadedNode != nil)-\(selectedEnvironment.name)")
        } else {
            SceneView { root in
                if let loadedNode {
                    loadedNode.entity.position = .zero
                    root.addChild(loadedNode.entity)
                }
            }
            .environment(selectedEnvironment)
            .cameraControls(.orbit)
            .id("sketchfab-manual-\(loadedNode != nil)-\(selectedEnvironment.name)")
        }
    }

    /// Cinematic vignette — costs ~0 GPU and lifts the model in the centre
    /// without obscuring it. Matches the "Apple Store" / Sketchfab hero look.
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
            // Soft blur so the thumbnail feels like a "preview state" rather
            // than the final render, and the transition to SceneView reads as
            // "now it's real and live".
            .blur(radius: 6)
            // Slight darkening so the foreground progress card stays legible.
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
                            HapticManager.lightTap()
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

import SwiftUI

// MARK: - Source selection persistence + registry (iOS port of Android `ModelSources`)

/// `UserDefaults`-backed memory of the last source picked in the Explore tab
/// (#2645 / #2700). One enum slug — far too small to justify anything heavier.
@MainActor
struct SelectedSourceStore {
    private let key = "io.github.sceneview.demo.explore.selectedSource"

    func load() -> ModelSourceId? {
        ModelSourceId.fromSlug(UserDefaults.standard.string(forKey: key))
    }

    func save(_ id: ModelSourceId) {
        UserDefaults.standard.set(id.slug, forKey: key)
    }
}

/// Observable handle over the available `ModelSource`s and the currently
/// selected one. The picker calls `select`; the selection is persisted so the
/// tab reopens on the same catalog.
///
/// The available-source list is built in display order, dropping unavailable
/// sources: Sketchfab without a key, and Icosa on iOS until glTF loads (#3655).
/// Poly Haven is always available, so the list is never empty and the tab is
/// never sourceless.
///
/// The order is the default: Sketchfab when the build has a key, otherwise
/// Poly Haven, whose models open in 3D on iOS (#3789). Android defaults to
/// Icosa on a keyless build because Filament renders its GLB; RealityKit does
/// not, so the two platforms differ here on purpose.
@MainActor
@Observable
final class GallerySourcesRegistry {
    /// Only the sources usable in this build (Sketchfab is dropped without a key).
    let sources: [any ModelSource]

    private(set) var selected: any ModelSource

    private let store = SelectedSourceStore()

    init() {
        let available = Self.availableSources()
        // Poly Haven is always available, so `available` is never empty. A saved
        // choice that is no longer available (Icosa, picked before #3789) falls
        // back to the first source.
        self.sources = available
        let saved = store.load()
        self.selected = available.first { $0.id == saved } ?? available[0]
    }

    /// Every source this build can list, in picker order.
    private static func availableSources() -> [any ModelSource] {
        let all: [any ModelSource] = [SketchfabSource(), PolyHavenSource(), IcosaGallerySource()]
        return all.filter(\.isAvailable)
    }

    /// The catalogs Explore lists in this build, for the Home card that opens
    /// it — "Sketchfab, Poly Haven" with a key, "Poly Haven" without.
    static var availableSourceNames: String {
        availableSources().map(\.id.displayName).joined(separator: ", ")
    }

    /// Switch the active source (idempotent) and persist the choice.
    func select(_ source: any ModelSource) {
        guard source.id != selected.id else { return }
        selected = source
        store.save(source.id)
    }
}

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
/// sources (Sketchfab without a key). Icosa + Poly Haven are always available,
/// so the list is never empty and the tab is never sourceless.
@MainActor
@Observable
final class GallerySourcesRegistry {
    /// Only the sources usable in this build (Sketchfab is dropped without a key).
    let sources: [any ModelSource]

    private(set) var selected: any ModelSource

    private let store = SelectedSourceStore()

    init() {
        let all: [any ModelSource] = [SketchfabSource(), IcosaGallerySource(), PolyHavenSource()]
        let available = all.filter(\.isAvailable)
        // Icosa + Poly Haven are always available, so `available` is never empty.
        self.sources = available
        let saved = store.load()
        self.selected = available.first { $0.id == saved } ?? available[0]
    }

    /// Switch the active source (idempotent) and persist the choice.
    func select(_ source: any ModelSource) {
        guard source.id != selected.id else { return }
        selected = source
        store.save(source.id)
    }
}

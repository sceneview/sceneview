import SwiftUI

/// Sheet presented from the About tab crediting every third-party 3D asset
/// the iOS demo app ships or streams (#1152 Stage 3, #3214).
///
/// **Why this exists.** CC-BY 4.0 — the license on most of the catalogue —
/// requires *visible* attribution in the redistributed artefact. The App Store
/// build carries 31 `.usdz` models, 7 HDR environments and a few first-party
/// media files, and until #3214 none of them was credited anywhere a user
/// could see. Mirrors the Android `CreditsSheet.kt`.
///
/// Two sources, one sheet:
///  - **Bundled** — `Resources/BundledCredits.json`, GENERATED from
///    `assets/catalog.json` by `.claude/scripts/generate-credits.py` and gated
///    by its `--check` mode. Never hand-edit it; re-run the script after
///    adding, removing or renaming a bundled asset.
///  - **Streamed** — `SampleAssets.all`, the curated Sketchfab slugs the demos
///    download on demand.
struct CreditsSheet: View {
    @Environment(\.dismiss) private var dismiss

    private let bundled = BundledCredits.load()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("SceneView is Apache 2.0. The 3D models, environments and media below keep the license granted by their authors — tap a row to open the original page.")
                        .font(.callout)
                        .foregroundStyle(.secondary)

                    bundledSection
                    streamedSection
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
            }
            .background(SceneViewTheme.surfaceGrouped)
            .navigationTitle("Credits")
            .navigationBarTitleInline()
            .toolbar {
                #if os(iOS)
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
                #else
                ToolbarItem(placement: .automatic) {
                    Button("Done") { dismiss() }
                }
                #endif
            }
        }
    }

    // MARK: - Bundled (generated JSON)

    @ViewBuilder
    private var bundledSection: some View {
        switch bundled {
        case .success(let credits):
            sectionHeader("Bundled in this app — \(credits.total) assets")
            ForEach(credits.sections, id: \.title) { section in
                Text(section.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(.top, 4)
                VStack(spacing: 8) {
                    ForEach(section.assets, id: \.file) { asset in
                        creditsRow(
                            icon: Self.icon(for: section.title),
                            title: asset.name,
                            subtitle: "by \(asset.author) — \(asset.license)",
                            url: URL(string: asset.sourceUrl)
                        )
                    }
                }
            }
        case .failure(let error):
            sectionHeader("Bundled in this app")
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.yellow)
                Text("Bundled asset credits are unavailable in this build (\(error.localizedDescription)). The full list lives in the project's assets/CREDITS.md.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
    }

    // MARK: - Streamed (Sketchfab)

    private let orderedCategories = [
        "solar", "gallery", "animation", "park",
        "ar_placement", "physics", "materials",
    ]

    @ViewBuilder
    private var streamedSection: some View {
        sectionHeader("Streamed from Sketchfab — \(SampleAssets.all.count) models")
            .padding(.top, 8)
        Text("Downloaded on demand, all CC-BY 4.0.")
            .font(.footnote)
            .foregroundStyle(.secondary)

        let groups = Dictionary(grouping: SampleAssets.all) { $0.category }
        let knownOrder = orderedCategories.filter { groups[$0] != nil }
        let remaining = Array(groups.keys).filter { !orderedCategories.contains($0) }.sorted()

        ForEach(knownOrder + remaining, id: \.self) { category in
            if let slugs = groups[category], !slugs.isEmpty {
                Text(label(for: category))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(.top, 4)

                VStack(spacing: 8) {
                    ForEach(slugs, id: \.uid) { slug in
                        creditsRow(
                            icon: "globe",
                            title: slug.displayName,
                            subtitle: "by \(slug.author) — CC-BY 4.0",
                            url: slug.sketchfabURL
                        )
                    }
                }
            }
        }
    }

    // MARK: - Building blocks

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(.headline)
            .foregroundStyle(.tint)
            .padding(.top, 4)
    }

    @ViewBuilder
    private func creditsRow(icon: String, title: String, subtitle: String, url: URL?) -> some View {
        if let url {
            Link(destination: url) { rowContent(icon: icon, title: title, subtitle: subtitle, opensLink: true) }
                .buttonStyle(.plain)
                .accessibilityLabel("\(title), \(subtitle). Opens \(url.host ?? "link").")
        } else {
            rowContent(icon: icon, title: title, subtitle: subtitle, opensLink: false)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(title), \(subtitle).")
        }
    }

    private func rowContent(icon: String, title: String, subtitle: String, opensLink: Bool) -> some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color.accentColor.opacity(0.15))
                Image(systemName: icon)
                    .font(.title3)
                    .foregroundStyle(.tint)
            }
            .frame(width: 40, height: 40)
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            Spacer(minLength: 4)

            if opensLink {
                Image(systemName: "arrow.up.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private static func icon(for sectionTitle: String) -> String {
        switch sectionTitle {
        case "3D models": return "cube"
        case "HDR environments": return "sun.max"
        default: return "doc"
        }
    }

    private func label(for category: String) -> String {
        switch category {
        case "solar": return "Solar (Orbital AR)"
        case "gallery": return "Gallery"
        case "animation": return "Animation"
        case "park": return "Park (Multi-model)"
        case "ar_placement": return "AR Placement"
        case "physics": return "Physics"
        case "materials": return "Materials"
        default: return category.capitalized
        }
    }
}

// MARK: - BundledCredits.json

/// Decoded shape of `Resources/BundledCredits.json`. Keys are the stable API
/// of `generate-credits.py`'s JSON scopes: the script adds keys but never
/// renames them, so optional fields here only ever grow.
struct BundledCredits: Decodable {
    struct Section: Decodable {
        let title: String
        let assets: [Asset]
    }

    struct Asset: Decodable {
        let file: String
        let name: String
        let author: String
        let license: String
        let licenseUrl: String
        let sourceUrl: String
        let size: Int
        let note: String
    }

    let scope: String
    let total: Int
    let sections: [Section]

    enum LoadError: LocalizedError {
        case missing

        var errorDescription: String? {
            switch self {
            case .missing: return "BundledCredits.json is not in the bundle"
            }
        }
    }

    /// Reads the generated JSON from the main bundle. A missing or malformed
    /// file is reported, not fatal: the sheet still shows the streamed credits.
    static func load(bundle: Bundle = .main) -> Result<BundledCredits, Error> {
        guard let url = bundle.url(forResource: "BundledCredits", withExtension: "json") else {
            return .failure(LoadError.missing)
        }
        do {
            let data = try Data(contentsOf: url)
            return .success(try JSONDecoder().decode(BundledCredits.self, from: data))
        } catch {
            return .failure(error)
        }
    }
}

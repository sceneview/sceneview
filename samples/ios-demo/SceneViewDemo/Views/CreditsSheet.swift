import SwiftUI

/// Sheet presented from the About tab listing every streamed Sketchfab model
/// the iOS demo app may load, grouped by `SketchfabSlug.category`. Each row
/// shows the display name, author, license and links to the original
/// Sketchfab page.
///
/// **Why this exists.** CC-BY 4.0 — the only Sketchfab license `SampleAssets`
/// accepts — requires *visible* attribution in the redistributed artefact.
/// Without this sheet, shipping the streamed models would violate the license.
/// Mirrors the Android `CreditsSheet.kt` 1:1 (#1152 Stage 3).
///
/// Bundled (non-streamed) assets are credited in the project-level
/// `assets/CREDITS.md` and shipped in the build as resource metadata.
struct CreditsSheet: View {
    @Environment(\.dismiss) private var dismiss

    private let orderedCategories = [
        "solar", "gallery", "animation", "park",
        "ar_placement", "physics", "materials",
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Every model below is streamed from Sketchfab under CC-BY 4.0. Tap a row to open the original page.")
                        .font(.callout)
                        .foregroundStyle(.secondary)

                    let groups = Dictionary(grouping: SampleAssets.all) { $0.category }
                    let knownOrder = orderedCategories.filter { groups[$0] != nil }
                    let remaining = Array(groups.keys).filter { !orderedCategories.contains($0) }.sorted()

                    ForEach(knownOrder + remaining, id: \.self) { category in
                        if let slugs = groups[category], !slugs.isEmpty {
                            Text(label(for: category))
                                .font(.headline)
                                .foregroundStyle(.tint)
                                .padding(.top, 4)

                            VStack(spacing: 8) {
                                ForEach(slugs, id: \.uid) { slug in
                                    creditsRow(for: slug)
                                }
                            }
                        }
                    }

                    Text("Bundled (offline) models are credited in the project's assets/CREDITS.md.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .padding(.top, 8)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
            }
            .navigationTitle("Streamed model credits")
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

    @ViewBuilder
    private func creditsRow(for slug: SketchfabSlug) -> some View {
        Link(destination: slug.sketchfabURL) {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(Color.accentColor.opacity(0.15))
                    Image(systemName: "globe")
                        .font(.title3)
                        .foregroundStyle(.tint)
                }
                .frame(width: 40, height: 40)
                .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 2) {
                    Text(slug.displayName)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text("by \(slug.author) — CC-BY 4.0")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 4)

                Image(systemName: "arrow.up.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(slug.displayName) by \(slug.author), CC-BY 4.0. Opens Sketchfab.")
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

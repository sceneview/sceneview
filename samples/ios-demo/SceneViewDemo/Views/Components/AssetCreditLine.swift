import SwiftUI

/// The attribution caption under a streamed-slug picker — credits **the model
/// that is actually on screen**, not the one the slug would stream (#2966).
///
/// Before this view every demo printed `by <slug.author> · CC-BY 4.0`
/// unconditionally. On a keyless build the resolver hands back the bundled
/// fallback, so the App Store build captioned a Khronos Damaged Helmet
/// "by ChubbyPanda". The caption is an attribution surface, so it now follows
/// the same measured verdict the `AssetSourcePill` uses:
///
///  - ``AssetSourceState/streamed`` → the slug's Sketchfab author, CC-BY 4.0
///    (the only licence the streamed registry admits).
///  - ``AssetSourceState/bundled`` → the fallback's own name, author and
///    licence from `BundledAssetCredits` — which is not always CC-BY.
///  - ``AssetSourceState/streaming`` → nothing is on screen yet, so no byline.
struct AssetCreditLine: View {
    let slug: SketchfabSlug
    let source: AssetSourceState
    /// Text colour — `.secondary` on a sheet, a translucent white when the
    /// caption floats over the scene (Scene Gallery, Materials).
    var style: AnyShapeStyle = AnyShapeStyle(.secondary)

    /// The caption text, or `nil` while nothing is on screen. A pure function
    /// so `AssetCreditLineTests` can pin all three branches without SwiftUI.
    static func text(slug: SketchfabSlug, source: AssetSourceState) -> String? {
        switch source {
        case .streaming:
            return nil
        case .streamed:
            return "by \(slug.author) · CC-BY 4.0"
        case .bundled:
            guard let credit = BundledAssetCredits.fallbackCredit(for: slug) else {
                // Unknown bundled file: say so rather than borrow the streamed
                // author. `BundledAssetCreditsTests` keeps this branch unreachable
                // for the shipped registry.
                return "Offline stand-in · uncredited bundled asset"
            }
            return "Offline stand-in: \(credit.name) by \(credit.author) · \(credit.license)"
        }
    }

    var body: some View {
        if let text = Self.text(slug: slug, source: source) {
            Text(text)
                .font(.caption2)
                .foregroundStyle(style)
                .accessibilityIdentifier("assetCreditLine")
        }
    }
}

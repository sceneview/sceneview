import Foundation

/// Attribution for one USDZ shipped inside the app bundle
/// (`samples/ios-demo/SceneViewDemo/Models/`).
///
/// Mirrors the matching entry of `assets/catalog.json` — the single source the
/// generated `assets/CREDITS.md` is built from. Keep the three fields verbatim
/// with the catalog; the table below is the only place the running app can
/// read them from, since the catalog itself is not bundled.
struct BundledAssetCredit: Hashable, Sendable {
    /// Human-readable asset name (`catalog.json` `name`).
    let name: String
    /// Original author, exactly as the catalog credits them.
    let author: String
    /// Short licence label shown on screen — `"CC-BY 4.0"`, `"CC-BY-NC 4.0"`,
    /// `"CC-BY-NC-SA 4.0"`. Not every bundled model is CC-BY, so the caption
    /// must print this rather than assume the registry's streamed licence.
    let license: String
}

/// Per-file attribution for the bundled USDZ set, keyed on the same
/// bundle-relative path `SketchfabSlug.fallbackBundledPath` declares.
///
/// **Why this exists (#2966).** Every demo that streams a `SketchfabSlug`
/// captions the model `by <slug.author> · CC-BY 4.0`. On a keyless build — the
/// default local build *and* the App Store build — the resolver hands back the
/// bundled fallback instead, which is a different model by a different author,
/// sometimes under a different licence. The caption is an attribution surface,
/// so it has to credit whatever is actually on screen. `AssetCreditLine` reads
/// this table whenever `AssetSourceProbe` measured a fallback.
///
/// `BundledAssetCreditsTests` pins that every fallback the registry can hand
/// out has a row here, so a new `fallbackBundledPath` cannot ship uncredited.
enum BundledAssetCredits {

    /// Attribution for a bundle-relative path such as `Models/khronos_fox.usdz`,
    /// or `nil` when the file is not a known bundled asset.
    static func credit(forBundledPath path: String) -> BundledAssetCredit? {
        byPath[path]
    }

    /// Attribution for the bundled asset a slug falls back to.
    static func fallbackCredit(for slug: SketchfabSlug) -> BundledAssetCredit? {
        credit(forBundledPath: slug.fallbackBundledPath)
    }

    /// One row per file under `Models/`, in `catalog.json` order.
    static let byPath: [String: BundledAssetCredit] = [
        "Models/game_boy_classic.usdz": .init(name: "Game Boy Classic", author: "JonhyOliver", license: "CC-BY 4.0"),
        "Models/tree_scene.usdz": .init(name: "Low Poly Tree Scene", author: "mateustorresg", license: "CC-BY 4.0"),
        "Models/khronos_toy_car.usdz": .init(name: "Toy Car", author: "KhronosGroup", license: "CC-BY 4.0"),
        "Models/red_car.usdz": .init(name: "A Red Car", author: "SerjogaSan", license: "CC-BY 4.0"),
        "Models/animated_butterfly.usdz": .init(name: "Animated Flying Fluttering Butterfly Loop", author: "LasquetiSpice", license: "CC-BY 4.0"),
        "Models/retro_piano.usdz": .init(name: "Retro Piano", author: "DailyArt", license: "CC-BY-NC 4.0"),
        "Models/phoenix_bird.usdz": .init(name: "Phoenix Bird", author: "NORBERTO-3D", license: "CC-BY 4.0"),
        "Models/cyberpunk_car.usdz": .init(name: "Cyberpunk Car", author: "4d_Bob", license: "CC-BY 4.0"),
        "Models/fantasy_book.usdz": .init(name: "Medieval Fantasy Book", author: "Pixel", license: "CC-BY 4.0"),
        "Models/mosquito_amber.usdz": .init(name: "Mosquito in Amber", author: "Loïc Norgeot", license: "CC-BY 4.0"),
        "Models/ship_in_clouds.usdz": .init(name: "Ship in Clouds", author: "Bastien Genbrugge", license: "CC-BY 4.0"),
        "Models/cyberpunk_hovercar.usdz": .init(name: "Cyberpunk Hovercar", author: "Karol Miklas", license: "CC-BY 4.0"),
        "Models/cyberpunk_character.usdz": .init(name: "Cyberpunk Character", author: "Esk", license: "CC-BY 4.0"),
        "Models/porsche_911.usdz": .init(name: "FREE 1975 Porsche 911 (930) Turbo", author: "Karol Miklas", license: "CC-BY 4.0"),
        "Models/black_dragon.usdz": .init(name: "Black Dragon with Idle Animation", author: "Arturs J", license: "CC-BY 4.0"),
        "Models/fiat_punto.usdz": .init(name: "1995 Fiat Punto GT", author: "Karol Miklas", license: "CC-BY 4.0"),
        "Models/shelby_cobra.usdz": .init(name: "1965 AC Shelby Cobra 427", author: "vecarz", license: "CC-BY-NC-SA 4.0"),
        "Models/audi_tt.usdz": .init(name: "2007 Audi TT Coupe", author: "Ddiaz Design", license: "CC-BY-NC-SA 4.0"),
        "Models/earthquake_california.usdz": .init(name: "August 7, 2024 M5.2 Earthquake in California", author: "Kyle", license: "CC-BY 4.0"),
        "Models/lamborghini_countach.usdz": .init(name: "2021 Lamborghini Countach LPI 800-4", author: "Lexyc16", license: "CC-BY-NC 4.0"),
        "Models/nike_air_jordan.usdz": .init(name: "Nike Air Jordan", author: "Ar41k", license: "CC-BY 4.0"),
        "Models/ferrari_f40.usdz": .init(name: "Ferrari F40", author: "Black Snow", license: "CC-BY 4.0"),
        "Models/porsche_911_turbo.usdz": .init(name: "1975 Porsche 911 (930) Turbo", author: "Lionsharp Studios", license: "CC-BY 4.0"),
        "Models/ps5_dualsense.usdz": .init(name: "PlayStation 5 DualSense", author: "AHarmlessPotato", license: "CC-BY 4.0"),
        "Models/tesla_cybertruck.usdz": .init(name: "Tesla Cybertruck", author: "hashikemu", license: "CC-BY 4.0"),
        "Models/mercedes_a45_amg.usdz": .init(name: "Mercedes-Benz A45 AMG 2018", author: "Lexyc16", license: "CC-BY-NC 4.0"),
        "Models/nintendo_switch.usdz": .init(name: "Nintendo Switch Diorama", author: "Mikkel Garde Blaase", license: "CC-BY-NC 4.0"),
        "Models/bmw_m3_e30.usdz": .init(name: "BMW M3 Coupe (E30) 1986", author: "Lexyc16", license: "CC-BY-NC 4.0"),
        "Models/khronos_damaged_helmet.usdz": .init(name: "Damaged Helmet", author: "KhronosGroup (theblueturtle_)", license: "CC-BY 4.0"),
        "Models/khronos_fox.usdz": .init(name: "Fox", author: "PixelMannen, tomkranis", license: "CC-BY 4.0"),
        "Models/khronos_lantern.usdz": .init(name: "Lantern", author: "Microsoft", license: "CC-BY 4.0"),
    ]
}

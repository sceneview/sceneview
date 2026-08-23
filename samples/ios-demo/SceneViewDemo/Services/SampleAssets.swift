import Foundation

/// ✅ **Validated registry (2026-05-22, #2095)** — every `uid` below was
/// verified against `GET /v3/models/<uid>`: each returns HTTP 200, is
/// `isDownloadable: true`, and carries a CC-BY 4.0 license. The earlier
/// Stage 1 placeholders all 404'd; they have been replaced with real,
/// downloadable Sketchfab models. Mirrors `SampleAssets.kt` 1:1.
///
/// Curated registry of Sketchfab models streamed by the iOS demo app.
///
/// Mirrors the Android registry `SampleAssets.kt` 1:1 — same uids, same
/// categories, same scale hints. The iOS fallback paths point to bundled
/// USDZ assets under `samples/ios-demo/SceneViewDemo/Models/` whereas Android
/// points to GLBs under `samples/android-demo/src/main/assets/models/`; the
/// uid is the cross-platform key.
///
/// Every entry is a `SketchfabSlug` whose license is **CC-BY** (Creative
/// Commons Attribution 4.0 International) — the only license SceneView's
/// demo apps redistribute. Non-CC-BY models (NC, ND, SA, Sketchfab Standard)
/// are deliberately rejected by `SketchfabSlug`'s `init(...)` and surfaced
/// by `validate()` so the registry can't silently regress.
///
/// **Adding a new entry — checklist.**
///
///  1. Confirm the Sketchfab page says **CC-BY 4.0** (a generic "Creative
///     Commons" badge is not enough).
///  2. Confirm the model is downloadable in `usdz` format (iOS prefers usdz
///     for RealityKit compatibility; the iOS resolver does not transcode
///     glTF).
///  3. Eyeball a realistic `scaleToUnits` — the bounds sanity check in
///     `SketchfabAssetResolver.boundsAreSane(_:slug:)` rejects values outside
///     `[0.05 m, 5 m]`.
///  4. Pick an existing bundled fallback that IS the same kind of thing as the
///     streamed model, or declare `fallbackRole: .placeholder` so the pill says
///     "Offline placeholder" instead of pretending (#2960). Every
///     `.subjectMatch` must be added to the reviewed allowlist in
///     `BundledAssetPrimBudgetTests.testEveryFallbackIsASubjectMatchOrADeclaredPlaceholder`.
enum SampleAssets {

    /// All curated entries flattened into a single list.
    static let all: [SketchfabSlug] = [
        // ── Solar / Orbital scene (OrbitalARDemo) ──────────────────────────
        SketchfabSlug(
            uid: "0f24b085e8654e4db09c2fe681a79e3f",
            displayName: "Fantasy Butterfly",
            author: "lunequinox",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/animated_butterfly.usdz",
            scaleToUnits: 0.25,
            hasBakedAnimation: true,
            category: "solar",
            tags: ["insect", "low-poly"]
        ),
        SketchfabSlug(
            uid: "80f8d9a6dadc411e89ca366cb0cfb0d9",
            displayName: "Fluttering Butterfly",
            author: "LasquetiSpice",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/animated_butterfly.usdz",
            scaleToUnits: 0.18,
            hasBakedAnimation: true,
            category: "solar",
            tags: ["insect", "wings"]
        ),
        SketchfabSlug(
            uid: "d4fbcbaab845402999f30c5aa75851e6",
            displayName: "Animated Butterfly",
            author: "leorehman333",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/animated_butterfly.usdz",
            scaleToUnits: 0.12,
            hasBakedAnimation: true,
            category: "solar",
            tags: ["insect"]
        ),
        SketchfabSlug(
            uid: "8ca3b9aa82694e6b8bc53a69b4529539",
            displayName: "Animated Butterflies",
            author: "bestgamekits",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/animated_butterfly.usdz",
            scaleToUnits: 0.35,
            hasBakedAnimation: true,
            category: "solar",
            tags: ["insect", "swarm"]
        ),

        // ── Gallery (SceneGalleryDemo) ─────────────────────────────────────
        SketchfabSlug(
            uid: "42e02439c61049d681c897441d40aaa1",
            displayName: "Nile (Classical Statue)",
            author: "rigsters",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/retro_piano.usdz",
            // Offline placeholder (#2960): no statue is bundled; a piano keeps the gallery picker working.
            fallbackRole: .placeholder,
            scaleToUnits: 0.85,
            hasBakedAnimation: false,
            category: "gallery",
            tags: ["sculpture", "scan"]
        ),
        SketchfabSlug(
            uid: "88ed6191446749b9a9e24b995bcb5e1d",
            displayName: "PBR Low-Poly Fox",
            author: "Ida..Faber",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            // Keyless fallback: a fox for a fox (#2960). `khronos_fox` is already
            // bundled and is what the Android registry maps this slug to
            // (`SampleAssets.kt` → `models/khronos_fox.glb`), so the two platforms
            // now show the same subject under the same label. The previous
            // `phoenix_bird` rendered a mythical bird under "PBR Low-Poly Fox".
            fallbackBundledPath: "Models/khronos_fox.usdz",
            scaleToUnits: 0.40,
            hasBakedAnimation: false,
            category: "gallery",
            tags: ["animal", "low-poly"]
        ),
        SketchfabSlug(
            uid: "7377ec591df04445a1aae370017aaa13",
            displayName: "Desk Lamp",
            author: "BlueHour",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            // Keyless fallback: a lantern is a lamp (#2960). Already bundled, and
            // the Android registry maps this slug the same way
            // (`models/khronos_lantern.glb`). The previous `fantasy_book` rendered
            // a book under "Desk Lamp". `khronos_lantern` is also the ar_placement
            // "Floor Lamp" fallback, which is fine: both consumers are one-model
            // pickers, so the two are never on screen together (#2355 binds only
            // where several slots render at once).
            fallbackBundledPath: "Models/khronos_lantern.usdz",
            scaleToUnits: 0.45,
            hasBakedAnimation: false,
            category: "gallery",
            tags: ["furniture", "lighting"]
        ),
        SketchfabSlug(
            uid: "eeb9d9f0627f4783b5d16a8732f0d1a4",
            displayName: "Vintage Camera",
            author: "MartijnVaes",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/game_boy_classic.usdz",
            // Offline placeholder (#2960): a Game Boy is a retro gadget, not a camera.
            fallbackRole: .placeholder,
            scaleToUnits: 0.20,
            hasBakedAnimation: false,
            category: "gallery",
            tags: ["hard-surface", "pbr"]
        ),

        // ── Animation (AnimationDemo) ──────────────────────────────────────
        SketchfabSlug(
            uid: "574e006a4e50408d9565e82fafe8ef19",
            displayName: "Retro TV Robot",
            author: "ArtsByKev",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/cyberpunk_character.usdz",
            scaleToUnits: 1.30,
            hasBakedAnimation: true,
            category: "animation",
            tags: ["character", "loop"]
        ),
        SketchfabSlug(
            uid: "ad9bc16464744935b1ac9b7768a17474",
            displayName: "Catfish Mech",
            author: "jungle_jim",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/cyberpunk_character.usdz",
            scaleToUnits: 1.45,
            hasBakedAnimation: true,
            category: "animation",
            tags: ["character", "mech"]
        ),
        SketchfabSlug(
            uid: "7190ff66cb3d4e729a2ab95aeb9e797f",
            displayName: "Walking Robot",
            author: "ArtsByKev",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            // Keyless fallback: an animated humanoid for a walking robot (#2960).
            // The previous `animated_butterfly` was picked for its baked clip, not
            // its subject — `cyberpunk_character` carries one too (verified: its
            // `scene.usdc` declares a `SkelAnimation`) and is already
            // `AnimationDemo`'s own bundled hero, so the playback point survives
            // while the subject stops contradicting the label.
            fallbackBundledPath: "Models/cyberpunk_character.usdz",
            scaleToUnits: 0.40,
            hasBakedAnimation: true,
            category: "animation",
            tags: ["character", "loop"]
        ),
        SketchfabSlug(
            uid: "cc4ab41731cc4c94a6adf2983821d1a8",
            displayName: "Enforcer Mk1",
            author: "Mr0btainable",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            // Keyless fallback: same reasoning as "Walking Robot" above — a mech
            // reads as an animated humanoid, never as a butterfly (#2960). Sharing
            // `cyberpunk_character` with the other three animation slugs is safe:
            // `AnimationDemo` is a picker, one model at a time.
            fallbackBundledPath: "Models/cyberpunk_character.usdz",
            scaleToUnits: 0.55,
            hasBakedAnimation: true,
            category: "animation",
            tags: ["character", "mech"]
        ),

        // ── Park scene composition (MultiModelDemo) ────────────────────────
        SketchfabSlug(
            uid: "d841c3bcc5324daebee50f45619e05fc",
            displayName: "Oak Trees",
            author: "bumstrum",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/tree_scene.usdz",
            scaleToUnits: 2.40,
            hasBakedAnimation: false,
            category: "park",
            tags: ["nature", "tree"]
        ),
        SketchfabSlug(
            uid: "6d1aeea748f147789004bc03e1930d32",
            displayName: "Stylized Tree",
            author: "yonimantz09",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            // Keyless fallback for the MultiModelDemo *bench* slot. The streamed
            // model is a tree, but the four park slots map to distinct diorama
            // roles (tree / bench / dog / bird) — so the fallbacks must be
            // DISTINCT too, or keyless mode stacks four identical 14 MB
            // tree_scene islands instead of a scene (#2355). A small furniture-
            // like prop (1.8 MB retro_piano) reads as the foreground bench.
            fallbackBundledPath: "Models/retro_piano.usdz",
            // Offline placeholder (#2960): deliberate bench-slot stand-in, see above — still not a tree.
            fallbackRole: .placeholder,
            scaleToUnits: 1.80,
            hasBakedAnimation: false,
            category: "park",
            tags: ["nature", "tree"]
        ),
        SketchfabSlug(
            uid: "4f6ab5594a8a415aba3f958682b9ced5",
            displayName: "Mighty Oak Trees",
            author: "Jagobo",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            // Keyless fallback for the MultiModelDemo *dog* (animated occupant)
            // slot — a small animated creature (3.1 MB animated_butterfly).
            // Distinct silhouette from the tree backdrop (#2355).
            fallbackBundledPath: "Models/animated_butterfly.usdz",
            // Offline placeholder (#2960): deliberate dog-slot stand-in — a butterfly is not a tree.
            fallbackRole: .placeholder,
            scaleToUnits: 2.60,
            hasBakedAnimation: false,
            category: "park",
            tags: ["nature", "tree"]
        ),
        SketchfabSlug(
            uid: "fd582b0d4a8c4af1a1b5c4f21a481c93",
            displayName: "Skovfogedegen Oak",
            author: "rigsters",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            // Keyless fallback for the MultiModelDemo *bird* slot — an actual
            // bird (phoenix_bird) AND the lightest bundled model at 1.1 MB, so
            // it lands FIRST and dismisses the "Loading park scene…" scrim early
            // (#1056 progressive reveal — previously defeated because every slot
            // loaded the same heavy 14 MB tree_scene). Distinct silhouette
            // (#2355).
            fallbackBundledPath: "Models/phoenix_bird.usdz",
            // Offline placeholder (#2960): deliberate bird-slot stand-in — a phoenix is not a tree.
            fallbackRole: .placeholder,
            scaleToUnits: 2.30,
            hasBakedAnimation: false,
            category: "park",
            tags: ["nature", "tree", "scan"]
        ),

        // ── AR placement (ARPlacementDemo) ─────────────────────────────────
        SketchfabSlug(
            uid: "5f5ccee1514c440887c072fae8e0d699",
            displayName: "Coffee Mug",
            author: "FrenchBaguette",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/retro_piano.usdz",
            // Offline placeholder (#2960): no mug is bundled; the piano only keeps something placeable.
            fallbackRole: .placeholder,
            scaleToUnits: 0.10,
            hasBakedAnimation: false,
            category: "ar_placement",
            tags: ["kitchen", "prop"]
        ),
        SketchfabSlug(
            uid: "1ab9bf841df04c07b1819be596327629",
            displayName: "Potted Monstera",
            author: "ChubbyPanda",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            // Keyless fallback — no plant is bundled, so a Khronos reference
            // object stands in rather than a mislabelled real object; the
            // previous tree_scene read as an actual potted plant (#2940).
            fallbackBundledPath: "Models/khronos_damaged_helmet.usdz",
            // Offline placeholder (#2960): no plant is bundled.
            fallbackRole: .placeholder,
            scaleToUnits: 0.45,
            hasBakedAnimation: false,
            category: "ar_placement",
            tags: ["plant", "decor"]
        ),
        SketchfabSlug(
            uid: "5ae3c72285474862a89d69c2f2ad2246",
            displayName: "Crates & Barrels",
            author: "jeandiz",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/game_boy_classic.usdz",
            // Offline placeholder (#2960): no crate is bundled.
            fallbackRole: .placeholder,
            scaleToUnits: 0.60,
            hasBakedAnimation: false,
            category: "ar_placement",
            tags: ["prop", "low-poly"]
        ),
        // Furniture trio — table / floor lamp / picture frame — placement picker.
        SketchfabSlug(
            uid: "7fab655234e84e0ea6a3ada36ece2ad1",
            displayName: "Wooden End Table",
            author: "mozillareality",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            // Keyless fallback — no table is bundled either; an inanimate Khronos
            // reference prop, distinct from this category's other fallbacks
            // because the picker can stack several in one scene (#2355).
            // NB: both placement demos place at a hardcoded `scaleToUnits(0.3)`
            // and never read the value below, so pick for silhouette, not size.
            fallbackBundledPath: "Models/khronos_toy_car.usdz",
            // Offline placeholder (#2960): no table is bundled.
            fallbackRole: .placeholder,
            scaleToUnits: 0.60,
            hasBakedAnimation: false,
            category: "ar_placement",
            tags: ["furniture"]
        ),
        SketchfabSlug(
            uid: "ca1cf1c435ec4012b9b6d5128333ad83",
            displayName: "Floor Lamp",
            author: "Mad_Lobster_Workshop",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            // Keyless fallback — khronos_lantern is the one bundled asset that
            // genuinely IS what the label says, so unlike its two siblings here
            // it is a match rather than a stand-in (#2940).
            fallbackBundledPath: "Models/khronos_lantern.usdz",
            scaleToUnits: 1.55,
            hasBakedAnimation: false,
            category: "ar_placement",
            tags: ["furniture", "lighting"]
        ),
        SketchfabSlug(
            uid: "b54984abe2394345a81621719bf8bf1a",
            displayName: "Picture Frame",
            author: "jamiemcfarlane",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            // Keyless fallback — was game_boy_classic, which "Crates & Barrels"
            // above already claims. Both demos in this category accumulate
            // placed anchors, so the two chips rendered the identical Game Boy
            // side by side (#2940's defect, missed by #2962 because only three
            // of this category's six slugs were repointed). No picture frame is
            // bundled, so the last unclaimed Khronos reference object stands in
            // rather than a mislabelled real object (#2355).
            fallbackBundledPath: "Models/khronos_fox.usdz",
            // Offline placeholder (#2960): no frame is bundled.
            fallbackRole: .placeholder,
            scaleToUnits: 0.40,
            hasBakedAnimation: false,
            category: "ar_placement",
            tags: ["decor", "wall"]
        ),

        // ── Physics (PhysicsDemo) ──────────────────────────────────────────
        SketchfabSlug(
            uid: "f91f4cf36fec4e5e8fabda6deda315bc",
            displayName: "Decorated Vase",
            author: "apariciosilva3D",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/fantasy_book.usdz",
            // Offline placeholder (#2960): no pottery is bundled; the book keeps a rigid body to drop.
            fallbackRole: .placeholder,
            scaleToUnits: 0.30,
            hasBakedAnimation: false,
            category: "physics",
            tags: ["pottery"]
        ),
        SketchfabSlug(
            uid: "5b7aefe2295f4ea5953bccb970ae76c0",
            displayName: "Wine Barrel",
            author: "niver_mk",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/fantasy_book.usdz",
            // Offline placeholder (#2960): no barrel is bundled.
            fallbackRole: .placeholder,
            scaleToUnits: 0.50,
            hasBakedAnimation: false,
            category: "physics",
            tags: ["prop"]
        ),
        SketchfabSlug(
            uid: "7bea362f018a4b39a66efdf126992926",
            displayName: "Pottery Vases",
            author: "local.yany",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/fantasy_book.usdz",
            // Offline placeholder (#2960): no pottery is bundled.
            fallbackRole: .placeholder,
            scaleToUnits: 0.45,
            hasBakedAnimation: false,
            category: "physics",
            tags: ["pottery"]
        ),
        SketchfabSlug(
            uid: "024a0d26f2ab4be8bacf86127e23e6aa",
            displayName: "Ancient Potteries",
            author: "skodvirr",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/fantasy_book.usdz",
            // Offline placeholder (#2960): no pottery is bundled.
            fallbackRole: .placeholder,
            scaleToUnits: 0.35,
            hasBakedAnimation: false,
            category: "physics",
            tags: ["pottery"]
        ),

        // ── Materials (MaterialsDemo) ──────────────────────────────────────
        SketchfabSlug(
            uid: "b13a625c2e3b4b6aa26a27711a0cac39",
            displayName: "Iridescent Beetle",
            author: "disc3d",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/mosquito_amber.usdz",
            scaleToUnits: 0.15,
            hasBakedAnimation: false,
            category: "materials",
            tags: ["scan", "iridescence"]
        ),
        SketchfabSlug(
            uid: "72a1583116e049e1adce28b2baf5527c",
            displayName: "Crystal Glass Decanter",
            author: "Antrea",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/mosquito_amber.usdz",
            // Offline placeholder (#2960): amber is translucent, but it is not a decanter.
            fallbackRole: .placeholder,
            scaleToUnits: 0.35,
            hasBakedAnimation: false,
            category: "materials",
            tags: ["transmission", "glass"]
        ),
        SketchfabSlug(
            uid: "a54b2ac109d146fb80cfc37c9da26cfb",
            displayName: "Cushioned Sofa",
            author: "klava88",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/mosquito_amber.usdz",
            // Offline placeholder (#2960): no furniture is bundled; a mosquito in amber is not a sofa.
            fallbackRole: .placeholder,
            scaleToUnits: 0.90,
            hasBakedAnimation: false,
            category: "materials",
            tags: ["sheen", "fabric"]
        ),
    ]

    /// Lookup by Sketchfab uid (the primary key).
    static let byUID: [String: SketchfabSlug] = {
        Dictionary(uniqueKeysWithValues: all.map { ($0.uid, $0) })
    }()

    /// Lookup by category name. Never `nil` — returns an empty list for an
    /// unknown category.
    static let byCategory: [String: [SketchfabSlug]] = {
        Dictionary(grouping: all, by: { $0.category })
    }()

    /// Distinct categories present in the registry.
    static var categories: [String] { byCategory.keys.sorted() }

    /// Validate the registry — duplicates, malformed uids, missing licenses.
    ///
    /// Called at process start and by `SampleAssetsTests` so any regression
    /// in the curation list fails fast.
    static func validate() {
        let grouped = Dictionary(grouping: all, by: { $0.uid })
        let duplicates = grouped.filter { $0.value.count > 1 }.keys.sorted()
        precondition(
            duplicates.isEmpty,
            "Duplicate Sketchfab uids in SampleAssets.all: \(duplicates)"
        )
        for slug in all {
            let isHex = slug.uid.count == 32 && slug.uid.allSatisfy { ch in
                ("0"..."9").contains(ch) || ("a"..."f").contains(ch)
            }
            precondition(
                isHex,
                "SketchfabSlug.uid must be a 32-char lowercase-hex Sketchfab id"
                + " (uid='\(slug.uid)', displayName='\(slug.displayName)')"
            )
        }
    }
}

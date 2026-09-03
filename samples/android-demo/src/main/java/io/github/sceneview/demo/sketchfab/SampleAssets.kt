package io.github.sceneview.demo.sketchfab

/**
 * ✅ **Validated registry (2026-05-22, #2095)** — every `uid` below was
 * verified against `GET /v3/models/<uid>`: each returns **HTTP 200**, is
 * **`isDownloadable: true`**, and carries a **CC-BY 4.0** license. The earlier
 * Stage 1 placeholders all 404'd, which silently broke every streamed sample
 * demo; they have been replaced with real, downloadable Sketchfab models.
 *
 * If a model is later deleted or relicensed by its author the resolver still
 * falls back to `fallbackBundledPath`, so the demo never shows a broken state.
 * Re-validate the registry by re-running `GET /v3/models/<uid>` for every entry
 * if a streamed demo starts failing.
 *
 * The `fallbackBundledPath` + `licenseUrl` columns are authoritative — they
 * decide what the resolver hands a demo when the network/key is unavailable,
 * and the constructor's CC-BY check fires unconditionally.
 *
 * Curated registry of Sketchfab models streamed by the demo apps.
 *
 * Every entry is a [SketchfabSlug] whose license is **CC-BY** (Creative
 * Commons Attribution 4.0 International) — the only license SceneView's demo
 * apps redistribute. Non-CC-BY models (NC, ND, SA, Sketchfab Standard) are
 * deliberately rejected by [SketchfabSlug]'s constructor and surfaced by
 * [requireValid] so the registry can't silently regress.
 *
 * Entries are grouped by [SketchfabSlug.category] to match the demo that
 * streams them:
 *
 *  - `solar` — animated companions / orbital decoration for `OrbitalARDemo`.
 *  - `gallery` — variety pack for `SceneGalleryDemo`.
 *  - `animation` — skeletal-animated models for `AnimationDemo`.
 *  - `park` — outdoor tree set for the `MultiModelDemo` park composition.
 *  - `ar_placement` — household-scale items for `ARPlacementDemo` /
 *    `ARPlacementDemo` (which absorbed `ARInstantPlacementDemo` in #3405).
 *  - `physics` — crash-test bodies for `PhysicsDemo`.
 *  - `materials` — PBR-material showcase models for `MaterialsDemo`.
 *
 * **Adding a new entry — checklist.** This list is small and reviewed by hand
 * because each entry implies a permanent third-party dependency on a model
 * an author can delete or relicense at any time.
 *
 *  1. Verify the Sketchfab page says **CC-BY 4.0** (a generic "Creative
 *     Commons" badge is not enough — click through to confirm "Attribution").
 *  2. Verify the model is marked downloadable in `glb` format (the resolver
 *     prefers glb > gltf > usdz; iOS can also consume usdz).
 *  3. Compute a realistic [SketchfabSlug.scaleToUnits] by eyeballing a
 *     Sketchfab viewer reading. Out-of-range models are rejected by
 *     [SketchfabAssetResolver.boundsAreSane].
 *  4. Pick an existing bundled fallback that visually resembles the streamed
 *     model — the user should not see a "broken" demo if the network is down.
 *
 * The registry intentionally keeps animal/object/themed variety low (≤ 4 per
 * category) to keep both the APK fallback footprint and the curation surface
 * small. Variety expansion is what the live Sketchfab API is for — see
 * [SketchfabService.search].
 */
object SampleAssets {

    /**
     * All curated entries flattened into a single list. Use [byCategory] when
     * iterating a single demo, [byUid] when looking up a specific slug.
     */
    val all: List<SketchfabSlug> = listOf(
        // ── Solar / Orbital scene (OrbitalARDemo) ──────────────────────────
        // 4 animated companions. Each carries a skeletal animation.
        // Every slot points at a *distinct* bundled GLB: OrbitalARDemo shows all
        // four at once on one anchor, so a shared fallback rendered the same
        // model four times around the ring in keyless mode (#3341, same defect
        // class as #2940 for `ar_placement`). Guarded by SampleAssetsTest.
        SketchfabSlug(
            uid = "0f24b085e8654e4db09c2fe681a79e3f",
            displayName = "Fantasy Butterfly",
            author = "lunequinox",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/threejs_soldier.glb",
            scaleToUnits = 0.25f,
            hasBakedAnimation = true,
            category = "solar",
            tags = listOf("insect", "low-poly"),
        ),
        SketchfabSlug(
            uid = "80f8d9a6dadc411e89ca366cb0cfb0d9",
            displayName = "Fluttering Butterfly",
            author = "LasquetiSpice",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_fox.glb",
            scaleToUnits = 0.18f,
            hasBakedAnimation = true,
            category = "solar",
            tags = listOf("insect", "wings"),
        ),
        SketchfabSlug(
            uid = "d4fbcbaab845402999f30c5aa75851e6",
            displayName = "Animated Butterfly",
            author = "leorehman333",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/shiba.glb",
            scaleToUnits = 0.12f,
            hasBakedAnimation = true,
            category = "solar",
            tags = listOf("insect"),
        ),
        SketchfabSlug(
            uid = "8ca3b9aa82694e6b8bc53a69b4529539",
            displayName = "Animated Butterflies",
            author = "bestgamekits",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_toy_car.glb",
            scaleToUnits = 0.35f,
            hasBakedAnimation = true,
            category = "solar",
            tags = listOf("insect", "swarm"),
        ),

        // ── Gallery (SceneGalleryDemo) ─────────────────────────────────────
        // 4 variety-pack models. Each gallery chip points at a *distinct*
        // bundled GLB so two chips never render the identical fallback model
        // when offline (#1433). `displayName` / `author` describe the streamed
        // Sketchfab model; the chosen fallback visually resembles it.
        SketchfabSlug(
            uid = "42e02439c61049d681c897441d40aaa1",
            displayName = "Nile (Classical Statue)",
            author = "rigsters",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            // Restored to `khronos_toy_car.glb` now that the asset is fixed (#1433).
            // The bundled GLB previously had a valid header but failed to parse in
            // `gltfio` ("Unable to parse glTF file"): a babylon.js export bug left an
            // out-of-bounds `clearcoatTexture` index and webp images on the core
            // `source` instead of via `EXT_texture_webp`. #2390 worked around it by
            // repointing this fallback (and "Coffee Mug") at decodable GLBs, which
            // made "Nile" share the helmet with "Vintage Camera" (a #1433 relaxation).
            // The asset has been re-exported (dangling texture dropped, normalized,
            // Draco decompressed) so it decodes; restoring it makes all four gallery
            // chips distinct again.
            fallbackBundledPath = "models/khronos_toy_car.glb",
            scaleToUnits = 0.85f,
            hasBakedAnimation = false,
            category = "gallery",
            tags = listOf("sculpture", "scan"),
        ),
        SketchfabSlug(
            uid = "88ed6191446749b9a9e24b995bcb5e1d",
            displayName = "PBR Low-Poly Fox",
            author = "Ida..Faber",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_fox.glb",
            scaleToUnits = 0.40f,
            hasBakedAnimation = false,
            category = "gallery",
            tags = listOf("animal", "low-poly"),
        ),
        SketchfabSlug(
            uid = "7377ec591df04445a1aae370017aaa13",
            displayName = "Desk Lamp",
            author = "BlueHour",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_lantern.glb",
            scaleToUnits = 0.45f,
            hasBakedAnimation = false,
            category = "gallery",
            tags = listOf("furniture", "lighting"),
        ),
        SketchfabSlug(
            uid = "eeb9d9f0627f4783b5d16a8732f0d1a4",
            displayName = "Vintage Camera",
            author = "MartijnVaes",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_damaged_helmet.glb",
            scaleToUnits = 0.20f,
            hasBakedAnimation = false,
            category = "gallery",
            tags = listOf("hard-surface", "pbr"),
        ),

        // ── Animation (AnimationDemo) ──────────────────────────────────────
        // 4 skeletal-animated models for the carousel.
        SketchfabSlug(
            uid = "574e006a4e50408d9565e82fafe8ef19",
            displayName = "Retro TV Robot",
            author = "ArtsByKev",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/threejs_soldier.glb",
            scaleToUnits = 1.30f,
            hasBakedAnimation = true,
            category = "animation",
            tags = listOf("character", "loop"),
        ),
        SketchfabSlug(
            uid = "ad9bc16464744935b1ac9b7768a17474",
            displayName = "Catfish Mech",
            author = "jungle_jim",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/threejs_soldier.glb",
            scaleToUnits = 1.45f,
            hasBakedAnimation = true,
            category = "animation",
            tags = listOf("character", "mech"),
        ),
        SketchfabSlug(
            uid = "7190ff66cb3d4e729a2ab95aeb9e797f",
            displayName = "Walking Robot",
            author = "ArtsByKev",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/shiba.glb",
            scaleToUnits = 0.40f,
            hasBakedAnimation = true,
            category = "animation",
            tags = listOf("character", "loop"),
        ),
        SketchfabSlug(
            uid = "cc4ab41731cc4c94a6adf2983821d1a8",
            displayName = "Enforcer Mk1",
            author = "Mr0btainable",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_fox.glb",
            scaleToUnits = 0.55f,
            hasBakedAnimation = true,
            category = "animation",
            tags = listOf("character", "mech"),
        ),

        // ── Park scene composition (MultiModelDemo) ────────────────────────
        // 4 outdoor trees that compose the "park" scene — a small grove the
        // MultiModelDemo arranges around the placement reticle.
        SketchfabSlug(
            uid = "d841c3bcc5324daebee50f45619e05fc",
            displayName = "Oak Trees",
            author = "bumstrum",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_lantern.glb",
            scaleToUnits = 2.40f,
            hasBakedAnimation = false,
            category = "park",
            tags = listOf("nature", "tree"),
        ),
        SketchfabSlug(
            uid = "6d1aeea748f147789004bc03e1930d32",
            displayName = "Stylized Tree",
            author = "yonimantz09",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_lantern.glb",
            scaleToUnits = 1.80f,
            hasBakedAnimation = false,
            category = "park",
            tags = listOf("nature", "tree"),
        ),
        SketchfabSlug(
            uid = "4f6ab5594a8a415aba3f958682b9ced5",
            displayName = "Mighty Oak Trees",
            author = "Jagobo",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/shiba.glb",
            scaleToUnits = 2.60f,
            hasBakedAnimation = false,
            category = "park",
            tags = listOf("nature", "tree"),
        ),
        SketchfabSlug(
            uid = "fd582b0d4a8c4af1a1b5c4f21a481c93",
            displayName = "Skovfogedegen Oak",
            author = "rigsters",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/threejs_soldier.glb",
            scaleToUnits = 2.30f,
            hasBakedAnimation = false,
            category = "park",
            tags = listOf("nature", "tree", "scan"),
        ),

        // ── AR placement (ARPlacementDemo — the one placement flow, #3405) ─
        // Real-world-scale household items + furniture so the placement reticle
        // reads as grounded. The "Pick what to place" sheet exposes this whole
        // category as a chip row — 6 entries balances variety against the
        // curation surface (each new entry implies a permanent third-party
        // dependency on a model an author can delete or relicense).
        //
        // The six fallbacks below are PAIRWISE DISTINCT (#2355), pinned by
        // `SampleAssetsTest.ar_placement fallbacks are pairwise distinct`. Both
        // demos in this category ACCUMULATE placed models — `TapToPlaceState`
        // owns the list, every tap appends — so on a keyless build two chips
        // sharing one fallback render as the identical asset side by side in a
        // single frame, under two different labels. That is the #2940 defect,
        // fixed on iOS by #2962 and guarded there by #2973; it was still live
        // here, with `khronos_lantern.glb` claimed by three slugs and
        // `khronos_damaged_helmet.glb` by two.
        //
        // The demo now bundles nine GLBs (#3324 added the Khronos velvet sofa,
        // sheen chair and iridescent dish for the picker), so the mapping is no
        // longer a bijection with no slack — and two of the three silhouette-only
        // stand-ins #2960 complained about could finally be repointed at
        // something that RESEMBLES the streamed model rather than merely
        // differing from its neighbours: "Coffee Mug" → the dish, "Wooden End
        // Table" → the chair. Distinctness is still the hard guarantee (pinned by
        // `SampleAssetsTest`); resemblance is now true for four of six. The two
        // that remain shape-class-only are "Potted Monstera" (`shiba`, a compact
        // ground mass) and "Picture Frame" (`threejs_soldier`, the upright
        // silhouette) — neither has a plausible bundled counterpart, and adding
        // one for its own sake would be binary the APK does not need. #2960
        // tracks the same mismatch class on the iOS registry, which is untouched
        // here (it bundles USDZs, not these GLBs).
        SketchfabSlug(
            uid = "5f5ccee1514c440887c072fae8e0d699",
            displayName = "Coffee Mug",
            author = "FrenchBaguette",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            // Tableware for tableware (#3324). Was `khronos_toy_car.glb`, a
            // silhouette-class-only stand-in: #2960 documented the semantic gap and said
            // closing it needed assets the APK did not ship. It ships them now — the
            // Khronos Iridescent Dish arrived with the picker re-curation — so a keyless
            // user arming "Coffee Mug" sees a glazed dish rather than a toy car.
            fallbackBundledPath = "models/khronos_iridescent_dish.glb",
            scaleToUnits = 0.10f,
            hasBakedAnimation = false,
            category = "ar_placement",
            tags = listOf("kitchen", "prop"),
        ),
        SketchfabSlug(
            uid = "1ab9bf841df04c07b1819be596327629",
            displayName = "Potted Monstera",
            author = "ChubbyPanda",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            // Was `khronos_lantern.glb`, which the Floor Lamp entry below has the
            // stronger claim to (a lantern IS a lamp). Shiba is the compact
            // ground-level mass left in the bundle — silhouette-class only (#2960).
            fallbackBundledPath = "models/shiba.glb",
            scaleToUnits = 0.45f,
            hasBakedAnimation = false,
            category = "ar_placement",
            tags = listOf("plant", "decor"),
        ),
        SketchfabSlug(
            uid = "5ae3c72285474862a89d69c2f2ad2246",
            displayName = "Crates & Barrels",
            author = "jeandiz",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_damaged_helmet.glb",
            scaleToUnits = 0.60f,
            hasBakedAnimation = false,
            category = "ar_placement",
            tags = listOf("prop", "low-poly"),
        ),
        // Furniture trio — table / floor lamp / picture frame — make the
        // picker feel like a tiny IKEA showroom for the AR-placement demos.
        SketchfabSlug(
            uid = "7fab655234e84e0ea6a3ada36ece2ad1",
            displayName = "Wooden End Table",
            author = "mozillareality",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            // Furniture for furniture (#3324). Was `khronos_fox.glb` — a four-legged
            // animal standing in for a four-legged table, which is the #2960 semantic
            // gap in its purest form. The Khronos Sheen Chair is now bundled, so the
            // keyless stand-in for a piece of furniture is a piece of furniture.
            fallbackBundledPath = "models/khronos_sheen_chair.glb",
            scaleToUnits = 0.60f,
            hasBakedAnimation = false,
            category = "ar_placement",
            tags = listOf("furniture"),
        ),
        SketchfabSlug(
            uid = "ca1cf1c435ec4012b9b6d5128333ad83",
            displayName = "Floor Lamp",
            author = "Mad_Lobster_Workshop",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_lantern.glb",
            scaleToUnits = 1.55f,
            hasBakedAnimation = false,
            category = "ar_placement",
            tags = listOf("furniture", "lighting"),
        ),
        SketchfabSlug(
            uid = "b54984abe2394345a81621719bf8bf1a",
            displayName = "Picture Frame",
            author = "jamiemcfarlane",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            // Was `khronos_damaged_helmet.glb` (collided with Crates & Barrels).
            // The soldier is the only upright silhouette left — the shape class a
            // wall-hung frame reads closest to. It ships baked animation clips,
            // but `hasBakedAnimation = false` describes the STREAMED Sketchfab
            // model, and no demo asks a fallback to play: it renders at rest.
            fallbackBundledPath = "models/threejs_soldier.glb",
            scaleToUnits = 0.40f,
            hasBakedAnimation = false,
            category = "ar_placement",
            tags = listOf("decor", "wall"),
        ),

        // ── Physics (PhysicsDemo) ──────────────────────────────────────────
        // Crash-test bodies for the dynamics demo — pottery / barrel silhouette
        // variety in the carousel when the user taps "Drop".
        SketchfabSlug(
            uid = "f91f4cf36fec4e5e8fabda6deda315bc",
            displayName = "Decorated Vase",
            author = "apariciosilva3D",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_lantern.glb",
            scaleToUnits = 0.30f,
            hasBakedAnimation = false,
            category = "physics",
            tags = listOf("pottery"),
        ),
        SketchfabSlug(
            uid = "5b7aefe2295f4ea5953bccb970ae76c0",
            displayName = "Wine Barrel",
            author = "niver_mk",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_damaged_helmet.glb",
            scaleToUnits = 0.50f,
            hasBakedAnimation = false,
            category = "physics",
            tags = listOf("prop"),
        ),
        SketchfabSlug(
            uid = "7bea362f018a4b39a66efdf126992926",
            displayName = "Pottery Vases",
            author = "local.yany",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_damaged_helmet.glb",
            scaleToUnits = 0.45f,
            hasBakedAnimation = false,
            category = "physics",
            tags = listOf("pottery"),
        ),
        SketchfabSlug(
            uid = "024a0d26f2ab4be8bacf86127e23e6aa",
            displayName = "Ancient Potteries",
            author = "skodvirr",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_lantern.glb",
            scaleToUnits = 0.35f,
            hasBakedAnimation = false,
            category = "physics",
            tags = listOf("pottery"),
        ),

        // ── Materials (MaterialsDemo) ──────────────────────────────────────
        // PBR material showcase — scanned insect, transparency, fabric.
        SketchfabSlug(
            uid = "b13a625c2e3b4b6aa26a27711a0cac39",
            displayName = "Iridescent Beetle",
            author = "disc3d",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_damaged_helmet.glb",
            scaleToUnits = 0.15f,
            hasBakedAnimation = false,
            category = "materials",
            tags = listOf("scan", "iridescence"),
        ),
        SketchfabSlug(
            uid = "72a1583116e049e1adce28b2baf5527c",
            displayName = "Crystal Glass Decanter",
            author = "Antrea",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_damaged_helmet.glb",
            scaleToUnits = 0.35f,
            hasBakedAnimation = false,
            category = "materials",
            tags = listOf("transmission", "glass"),
        ),
        SketchfabSlug(
            uid = "a54b2ac109d146fb80cfc37c9da26cfb",
            displayName = "Cushioned Sofa",
            author = "klava88",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_damaged_helmet.glb",
            scaleToUnits = 0.90f,
            hasBakedAnimation = false,
            category = "materials",
            tags = listOf("sheen", "fabric"),
        ),
    )

    /** Lookup by Sketchfab uid (the primary key). */
    val byUid: Map<String, SketchfabSlug> = all.associateBy { it.uid }

    /** Lookup by category name (`solar`, `gallery`, …). Never `null` —
     *  returns an empty list for an unknown category. */
    val byCategory: Map<String, List<SketchfabSlug>> = all.groupBy { it.category }

    /** Distinct categories present in the registry. */
    val categories: List<String> get() = byCategory.keys.sorted()

    /**
     * Validate the registry — duplicates, missing licenses, malformed uids.
     *
     * Called at process start and by [SampleAssetsTest] so any regression in
     * the curation list fails fast (typically a missing CC-BY tag, a
     * duplicated uid copy-paste, or an empty author string).
     */
    fun requireValid() {
        // Duplicates would silently make `byUid` lose entries — surface them
        // explicitly so the registry's `all` and `byUid` stay 1:1.
        val duplicateUids = all
            .groupBy { it.uid }
            .filterValues { it.size > 1 }
            .keys
        require(duplicateUids.isEmpty()) {
            "Duplicate Sketchfab uids in SampleAssets.all: $duplicateUids"
        }
        // Uid format — Sketchfab uses 32-char hex.
        all.forEach { slug ->
            require(slug.uid.length == 32 && slug.uid.all { it in '0'..'9' || it in 'a'..'f' }) {
                "SketchfabSlug.uid must be a 32-char lowercase-hex Sketchfab id " +
                    "(uid='${slug.uid}', displayName='${slug.displayName}')"
            }
        }
    }
}

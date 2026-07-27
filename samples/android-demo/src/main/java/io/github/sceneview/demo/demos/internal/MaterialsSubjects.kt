package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabSlug

/**
 * One selectable subject in the Materials demo's **PBR Materials** section —
 * either the bundled default or one of the curated streamed Sketchfab models.
 *
 * The split exists so the section's *cold-launch* state cannot depend on the
 * network. See [MaterialsSubjects] for the determinism contract.
 */
sealed interface MaterialsSubject {

    /** Chip label. */
    val displayName: String

    /** CC-BY attribution shown under the chips. */
    val author: String

    /** `KHR_materials_*` extension family this subject demonstrates. */
    val extensionTag: String

    /**
     * A model shipped inside the APK. Renders with zero network access, from the
     * same bytes on every launch and every device — the property the store /
     * QA capture depends on.
     *
     * @property assetPath `assets/`-relative path, loadable by the asset-path
     *   `rememberModelInstance(modelLoader, String)` overload.
     */
    data class Bundled(
        val assetPath: String,
        override val displayName: String,
        override val author: String,
        override val extensionTag: String,
    ) : MaterialsSubject

    /**
     * A curated Sketchfab model streamed at runtime through
     * [io.github.sceneview.demo.sketchfab.SketchfabAssetResolver]. What actually
     * renders depends on the API key, the network and the disk cache — which is
     * exactly why one is never the default subject (#2874).
     */
    data class Streamed(val slug: SketchfabSlug) : MaterialsSubject {
        override val displayName: String get() = slug.displayName
        override val author: String get() = slug.author
        override val extensionTag: String get() = slug.tags.firstOrNull().orEmpty()
    }
}

/**
 * Subject list + framing constants for the **PBR Materials** section, kept out of
 * the composable so the determinism contract below is unit-testable on a bare JVM
 * (`MaterialsSubjectsTest`).
 *
 * ## Why this exists (#2874)
 *
 * The section used to render `SampleAssets.byCategory["materials"]` slug 0
 * directly. That slug is *streamed*: with an API key and a warm network it
 * resolves to the Sketchfab model, and without either it resolves to the slug's
 * bundled fallback. So the very first frame after a cold launch showed a
 * different subject depending on network state — measured on 2026-07-27, two
 * captures of the same demo id on two tablet AVDs from the same build produced
 * an insect and a helmet. A store slot, a Maestro assertion and a listing diff
 * all need the opposite: the same pixels every run.
 *
 * The contract this object encodes:
 *
 *  1. **The default subject is [MaterialsSubject.Bundled]** — index
 *     [DEFAULT_INDEX] is always local, so the cold-launch frame never depends on
 *     the network, an API key or the cache. Enforced by `MaterialsSubjectsTest`.
 *  2. **Variety stays, as an explicit user action** — the streamed slugs are
 *     still there, one chip each, one tap away.
 *  3. **Framing is subject-independent** — every subject is normalised to
 *     [FRAMING_UNITS] and viewed from [ORBIT_RADIUS_METERS], instead of each
 *     model's own `scaleToUnits` (0.15 m for the beetle, 0.90 m for the sofa),
 *     which is why the subject used to read as a speck at some chips and not
 *     others.
 *
 * The environment is pinned separately —
 * [io.github.sceneview.demo.common.MATERIALS_SHOWCASE_HDR].
 */
object MaterialsSubjects {

    /** Index of the subject a cold launch opens on. Always a [MaterialsSubject.Bundled]. */
    const val DEFAULT_INDEX: Int = 0

    /**
     * Size, in metres, every subject's bounding cube is normalised to
     * (`ModelNode(scaleToUnits = …)`).
     *
     * A single value for every chip is the point: the camera never moves between
     * chips, so a per-model scale is what made one subject fill the viewport and
     * the next one read as a speck.
     */
    const val FRAMING_UNITS: Float = 1.0f

    /**
     * Orbit radius, in metres, for the section's hero camera.
     *
     * Chosen by LOOKING at the capture (`capture-play-store-screenshots.sh
     * --form-factor phone --demos materials`), not by maximising a metric.
     * Measured on the resulting 1080-px-wide phone frames: the default subject's
     * base spans 98–100% of the viewport width and the car body 35–46% of it,
     * the spread being where the idle orbit happens to be. That is tight enough
     * to read at Play-Store thumbnail size, where the previous framing — each
     * model scaled to its own `scaleToUnits` (0.15 m for the beetle) and viewed
     * from a fixed 1.2 m — left the subject small in a mostly-empty frame.
     *
     * Must stay clear of [FRAMING_UNITS] — an orbit radius inside the subject's
     * own bounding cube puts the camera *inside* the model, the failure mode the
     * store-capture script documents for `model-viewer` at 2.0 m.
     */
    const val ORBIT_RADIUS_METERS: Float = 2.4f

    /**
     * The bundled default subject: Khronos' **ToyCar**, already in the APK
     * (`assets/models/khronos_toy_car.glb`, CC-BY 4.0 — see
     * `assets/CREDITS.md`).
     *
     * It is the right default on the merits, not just because it is local: the
     * GLB declares `KHR_materials_clearcoat`, `KHR_materials_sheen` and
     * `KHR_materials_transmission` (read straight out of its `extensionsUsed`),
     * i.e. the three extension families this whole section is about, on the
     * car body, the seat fabric and the windows respectively. The previous
     * offline path — every slug falling back to `khronos_damaged_helmet.glb`,
     * a plain metallic-roughness model with no `KHR_materials_*` extension at
     * all — showed none of them.
     */
    val BUNDLED_DEFAULT: MaterialsSubject.Bundled = MaterialsSubject.Bundled(
        assetPath = "models/khronos_toy_car.glb",
        displayName = "Toy Car",
        author = "Khronos",
        extensionTag = "KHR_materials_clearcoat · _sheen · _transmission",
    )

    /**
     * The section's chips, in order: the bundled default first, then every
     * curated streamed slug.
     *
     * @param slugs streamed candidates; defaults to the registry's `materials`
     *   category. Injectable so the test can pin the input.
     */
    fun all(
        slugs: List<SketchfabSlug> = SampleAssets.byCategory["materials"].orEmpty(),
    ): List<MaterialsSubject> = buildList {
        add(BUNDLED_DEFAULT)
        slugs.forEach { add(MaterialsSubject.Streamed(it)) }
    }
}

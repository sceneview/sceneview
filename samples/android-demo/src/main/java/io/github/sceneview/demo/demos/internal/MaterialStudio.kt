package io.github.sceneview.demo.demos.internal

import androidx.annotation.StringRes
import io.github.sceneview.demo.R
import androidx.compose.ui.graphics.Color
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.math.Position

/**
 * The physical layer a material adds **on top of** the metallic-roughness base — the part
 * that needs more than `baseColor / metallic / roughness / reflectance` to exist.
 *
 * Each entry maps to one `KHR_materials_*` glTF extension and to one flag on Filament's
 * gltfio ubershader key ([com.google.android.filament.gltfio.MaterialProvider.MaterialKey]),
 * which is how [io.github.sceneview.demo.demos.MaterialsDemo] renders it without shipping a
 * new `.filamat`: the ubershader that Filament already uses for every loaded glTF model is
 * asked for the same feature a glTF file would have asked for.
 *
 * [None] is not a lesser case — five of the nine library entries are plain
 * metallic-roughness surfaces, because that pair alone is what separates gold from steel
 * from ceramic, and a materials demo that skips it teaches the wrong lesson first.
 */
internal enum class MaterialTrait(val label: String, val extension: String) {
    /** Metallic-roughness only — `MaterialLoader.createColorInstance`. */
    None("", ""),

    /** A second specular lobe over the base — car paint, lacquer, varnished wood. */
    ClearCoat("Clear coat", "KHR_materials_clearcoat"),

    /** Retro-reflective fuzz at grazing angles — velvet, felt, brushed fabric. */
    Sheen("Sheen", "KHR_materials_sheen"),

    /** Light passes *through* the surface and refracts — glass, crystal, water. */
    Transmission("Transmission", "KHR_materials_transmission"),

    /** The surface emits light of its own, independent of the environment. */
    Emissive("Emissive", "KHR_materials_emissive_strength"),
}

/**
 * One material in the studio library: everything needed to build a Filament
 * `MaterialInstance` and to describe it to the user in one line.
 *
 * @param id            Stable identifier — used as a `key` and in the peek header.
 * @param label         Short display name, e.g. `"Polished Gold"`.
 * @param note          One clause explaining *why it looks like that*, shown under the picker.
 * @param color         Base colour. For a metal this is its measured reflectance (see
 *                      [MaterialStudio]); for a dielectric it is the diffuse albedo.
 * @param metallic      `0` dielectric … `1` metal.
 * @param roughness     `0` mirror … `1` fully diffuse.
 * @param reflectance   Dielectric Fresnel reflectance at normal incidence. Ignored when
 *                      [metallic] is `1`, which is why every metal below leaves it at `0.5`.
 * @param trait         The extension layer, if any.
 * @param traitAmount   Strength of [trait]: clear-coat / sheen / transmission factor, or
 *                      emissive strength in arbitrary multiples of the base emissive colour.
 * @param traitRoughness Roughness of the [trait] layer — the coat's own gloss, the sheen's
 *                      fuzz spread. Unused by [MaterialTrait.Transmission] and
 *                      [MaterialTrait.Emissive].
 * @param traitColor    Colour of the [trait] layer — the sheen tint or the emitted light.
 * @param ior           Index of refraction, used by [MaterialTrait.Transmission]. Glass is
 *                      `1.5`, water `1.33`, diamond `2.42`.
 */
internal data class StudioMaterial(
    val id: String,
    val label: String,
    val note: String,
    @StringRes val nameRes: Int,
    @StringRes val explainerRes: Int,
    val color: Color,
    val metallic: Float,
    val roughness: Float,
    val reflectance: Float = 0.5f,
    val trait: MaterialTrait = MaterialTrait.None,
    val traitAmount: Float = 0f,
    val traitRoughness: Float = 0.1f,
    val traitColor: Color = Color.White,
    val ior: Float = 1.5f,
) {
    /**
     * `"metallic 1.00 · roughness 0.12"`, plus the trait when there is one.
     *
     * The arguments default to the declared values, so `summary()` describes the material as
     * the library defines it; the *Inspect* mode passes its live slider values instead, which
     * is why the status pill keeps telling the truth while the user drags.
     */
    fun summary(
        metallic: Float = this.metallic,
        roughness: Float = this.roughness,
        traitAmount: Float = this.traitAmount,
    ): String = buildString {
        append("metallic ")
        append(format2(metallic))
        append(" · roughness ")
        append(format2(roughness))
        if (trait != MaterialTrait.None) {
            append(" · ")
            append(trait.label.lowercase())
            append(' ')
            append(format2(traitAmount))
        }
    }
}

/** Two decimals without pulling `String.format` and its locale into a pure function. */
private fun format2(value: Float): String {
    val hundredths = kotlin.math.round(value * 100f).toInt().coerceAtLeast(0)
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

/**
 * The material library, the wall it is arranged on, and the framing that shows it — the
 * whole of the *Materials* demo that is not Compose or Filament, so it can be read and
 * unit-tested on its own (`MaterialStudioTest`).
 *
 * ## Where the colours come from
 *
 * A metal has no diffuse albedo: its base colour **is** its specular reflectance, and those
 * are measured physical constants, not a palette choice. The four metals below carry the
 * values from Filament's own materials guide ("Standard metal reflectance") so that the gold
 * ball in this demo is the same gold a physically-based renderer anywhere else would
 * produce. The dielectrics and the emissive tint come from the brand ramp
 * ([SceneViewColors], mirrored from `DESIGN.md`) because *those* are a choice.
 *
 * The byte triples below are the guide's **linear** values × 255, not their sRGB encodings.
 * That is deliberate: SceneView passes colour components to Filament without a gamma
 * conversion (`io.github.sceneview.math.colorOf` says so in as many words), so what the
 * shader receives is exactly the number written here. Writing the sRGB encoding instead
 * would make the demo's gold a different gold from every reference image of it. The one
 * cost is that a Compose swatch drawn from the same value reads a little darker than the
 * rendered ball; the ball is the subject, so the ball wins.
 */
internal object MaterialStudio {

    // ── Measured metal reflectance, Filament materials guide §"Standard metal reflectance"

    /** Gold — linear (1.000, 0.766, 0.336). */
    private val Gold = Color(0xFFFFC356)

    /** Copper — linear (0.955, 0.637, 0.538). */
    private val Copper = Color(0xFFF4A289)

    /** Chromium — linear (0.550, 0.556, 0.554). */
    private val Chromium = Color(0xFF8C8E8D)

    /** Aluminium — linear (0.913, 0.921, 0.925). */
    private val Aluminium = Color(0xFFE9EBEC)

    /** Glazed white — a dielectric albedo, not a metal reflectance. */
    private val Porcelain = Color(0xFFF2EFE9)

    /**
     * The nine materials, in wall order (row-major, three columns).
     *
     * The order is not decorative — each row is one lesson, read left to right:
     *
     * - **Row 1, the roughness ladder at `metallic = 1`.** Chrome, gold, copper: same
     *   shading model, three roughness values, and the highlight goes from a mirror of the
     *   room to a soft smear. This is most of what "a metal" means and it belongs first.
     * - **Row 2, the dielectric middle ground.** Rough metal, glazed ceramic, clear-coated
     *   paint — where `metallic` stops being a switch and starts being a description.
     * - **Row 3, the extension family.** Sheen, transmission and emission: the three things
     *   the metallic-roughness pair cannot express at all, whatever values you give it.
     */
    val library: List<StudioMaterial> = listOf(
        StudioMaterial(
            id = "chrome",
            nameRes = R.string.demo_materials_preset_chrome,
            explainerRes = R.string.demo_materials_explainer_chrome,
            label = "Mirror Chrome",
            note = "A metal at roughness 0.02 reflects the studio almost perfectly.",
            color = Chromium,
            metallic = 1f,
            roughness = 0.02f,
        ),
        StudioMaterial(
            id = "gold",
            nameRes = R.string.demo_materials_preset_gold,
            explainerRes = R.string.demo_materials_explainer_gold,
            label = "Polished Gold",
            note = "Metals have no diffuse colour: this tint is gold's own reflectance.",
            color = Gold,
            metallic = 1f,
            roughness = 0.14f,
        ),
        StudioMaterial(
            id = "copper",
            nameRes = R.string.demo_materials_preset_copper,
            explainerRes = R.string.demo_materials_explainer_copper,
            label = "Satin Copper",
            note = "The same shading, one notch rougher — the highlight spreads and softens.",
            color = Copper,
            metallic = 1f,
            roughness = 0.32f,
        ),
        StudioMaterial(
            id = "aluminium",
            nameRes = R.string.demo_materials_preset_aluminium,
            explainerRes = R.string.demo_materials_explainer_aluminium,
            label = "Brushed Aluminium",
            note = "Roughness 0.55: the environment is still reflected, just scattered.",
            color = Aluminium,
            metallic = 1f,
            roughness = 0.55f,
        ),
        StudioMaterial(
            id = "ceramic",
            nameRes = R.string.demo_materials_preset_ceramic,
            explainerRes = R.string.demo_materials_explainer_ceramic,
            label = "Glazed Ceramic",
            note = "A dielectric keeps its own colour and adds a small, sharp highlight.",
            color = Porcelain,
            metallic = 0f,
            roughness = 0.06f,
            reflectance = 0.7f,
        ),
        StudioMaterial(
            id = "car-paint",
            nameRes = R.string.demo_materials_preset_car_paint,
            explainerRes = R.string.demo_materials_explainer_car_paint,
            label = "Car Paint",
            note = "A glossy clear coat over a metallic flake base — two specular lobes.",
            color = SceneViewColors.Primary,
            metallic = 0.85f,
            roughness = 0.42f,
            trait = MaterialTrait.ClearCoat,
            traitAmount = 1f,
            traitRoughness = 0.03f,
        ),
        StudioMaterial(
            id = "velvet",
            nameRes = R.string.demo_materials_preset_velvet,
            explainerRes = R.string.demo_materials_explainer_velvet,
            label = "Velvet",
            note = "Sheen adds a retro-reflective rim that lights up at grazing angles.",
            color = SceneViewColors.AccentDeep,
            metallic = 0f,
            roughness = 0.85f,
            reflectance = 0.2f,
            trait = MaterialTrait.Sheen,
            traitAmount = 1f,
            traitRoughness = 0.3f,
            traitColor = SceneViewColors.TintSoft,
        ),
        StudioMaterial(
            id = "crystal",
            nameRes = R.string.demo_materials_preset_crystal,
            explainerRes = R.string.demo_materials_explainer_crystal,
            label = "Crystal",
            note = "Transmission refracts what is behind the surface at an IOR of 1.5.",
            color = Color(0xFFEFF6FF),
            metallic = 0f,
            roughness = 0.05f,
            reflectance = 0.6f,
            trait = MaterialTrait.Transmission,
            traitAmount = 1f,
            ior = 1.5f,
        ),
        StudioMaterial(
            id = "glow",
            nameRes = R.string.demo_materials_preset_glow,
            explainerRes = R.string.demo_materials_explainer_glow,
            label = "Signal Glow",
            note = "Emission owes nothing to the environment — it still lights at night.",
            color = SceneViewColors.SurfaceDim,
            metallic = 0f,
            roughness = 0.6f,
            reflectance = 0.35f,
            trait = MaterialTrait.Emissive,
            traitAmount = 4f,
            traitColor = SceneViewColors.TintLight,
        ),
    )

    /** Index of [library]'s default selection — the clear coat, the most obviously "material" one. */
    const val DEFAULT_INDEX: Int = 5

    // ── The wall ──────────────────────────────────────────────────────────────────────────

    /**
     * Columns in the gallery wall.
     *
     * Three, which makes the wall square — and a square block is what fits the *clear* part
     * of a portrait phone. The demo chrome reserves a 160 dp band at the top and at least
     * 220 dp at the bottom (`DESIGN.md`, "Glass Chrome over Media"), so barely 60 % of the
     * height is unobstructed. A two-column wall of the same nine balls is twice as tall as
     * it is wide and puts its first and last row under the scrims.
     */
    const val COLUMNS: Int = 3

    /** Radius of a gallery ball, metres. */
    const val BALL_RADIUS: Float = 0.2f

    /** Centre-to-centre distance between two gallery balls, metres. */
    const val BALL_SPACING: Float = 0.6f

    /** Latitude / longitude subdivisions of every ball — high enough that a mirror has no facets. */
    const val BALL_STACKS: Int = 48
    const val BALL_SLICES: Int = 48

    /** Radius of the single hero ball in *Inspect*, metres. */
    const val HERO_RADIUS: Float = 0.45f

    /** Half the gap between the two hero balls when *Compare* is on, metres. */
    const val COMPARE_OFFSET: Float = 0.5f

    /** Radius of each hero ball when *Compare* splits the stage in two, metres. */
    const val COMPARE_RADIUS: Float = 0.34f

    /**
     * Wall positions for [count] balls, row-major over [COLUMNS] columns, centred on the
     * origin and lying in the `z = 0` plane.
     *
     * Row 0 is at the **top**: reading order on screen has to match reading order in
     * [library], or the note under the picker describes a different ball than the one the
     * eye lands on.
     */
    fun wallPositions(count: Int = library.size, columns: Int = COLUMNS): List<Position> {
        if (count <= 0 || columns <= 0) return emptyList()
        val rows = (count + columns - 1) / columns
        val xOffset = (columns - 1) * BALL_SPACING / 2f
        val yOffset = (rows - 1) * BALL_SPACING / 2f
        return (0 until count).map { index ->
            val row = index / columns
            val column = index % columns
            Position(
                x = column * BALL_SPACING - xOffset,
                y = yOffset - row * BALL_SPACING,
                z = 0f,
            )
        }
    }

    /** Full width of the wall in world units, balls included — feeds the orbit auto-fit. */
    fun wallExtentX(count: Int = library.size, columns: Int = COLUMNS): Float {
        if (count <= 0 || columns <= 0) return 0f
        val usedColumns = minOf(columns, count)
        return (usedColumns - 1) * BALL_SPACING + 2f * BALL_RADIUS
    }

    /** Full height of the wall in world units, balls included. */
    fun wallExtentY(count: Int = library.size, columns: Int = COLUMNS): Float {
        if (count <= 0 || columns <= 0) return 0f
        val rows = (count + columns - 1) / columns
        return (rows - 1) * BALL_SPACING + 2f * BALL_RADIUS
    }

    // ── Camera ────────────────────────────────────────────────────────────────────────────

    /**
     * Half-amplitude of the gallery's camera sweep, degrees.
     *
     * The wall is flat, so the camera cannot orbit it: a quarter turn shows the balls edge-on
     * and a half turn shows their backs. It **sweeps** instead — a slow pendulum either side
     * of head-on, which is enough to walk every specular highlight across all nine spheres
     * (the thing a still image of a material wall cannot show) while keeping the composition,
     * and the piece of the environment behind it, the same at both ends of the swing.
     */
    const val SWEEP_DEGREES: Float = 24f

    /** One full there-and-back sweep, milliseconds. */
    const val SWEEP_PERIOD_MILLIS: Int = 14_000

    /** One full turn of the *Inspect* orbit, milliseconds. */
    const val ORBIT_PERIOD_MILLIS: Int = 22_000

    /**
     * Camera yaw at sweep [phase] ∈ `[0, 1)`, degrees.
     *
     * A cosine, not a triangle: the ends of a linear sweep reverse with a visible jerk, and
     * this is the one motion on the screen.
     */
    fun sweepYaw(phase: Float, amplitudeDegrees: Float = SWEEP_DEGREES): Float =
        -amplitudeDegrees * kotlin.math.cos(2.0 * kotlin.math.PI * phase).toFloat()

    /**
     * Deterministic sweep phase in QA mode.
     *
     * Three eighths of the cycle, which [sweepYaw] turns into `+cos(45°) · SWEEP_DEGREES`
     * — about 70 % of the way to one end of the swing. It has to be off-centre: head-on
     * (phase `0.25` or `0.75`, where the cosine crosses zero) puts the key light behind the
     * camera and flattens every specular highlight on the wall, which is the one thing this
     * screen exists to show. `MaterialStudioTest` pins that, because "a constant phase" and
     * "a useful phase" are two different requirements and only the first is obvious.
     */
    const val STATIC_SWEEP_PHASE: Float = 0.375f

    /** Deterministic orbit yaw in QA mode, degrees. */
    const val STATIC_ORBIT_YAW: Float = 35f

    // ── Environment ───────────────────────────────────────────────────────────────────────

    /**
     * One studio-lit environment per entry, all already bundled in `assets/environments/`.
     *
     * The skybox is **drawn**, unlike the demo this replaces (#2874). A material demo whose
     * background is flat grey is asking the viewer to take the reflections on faith: chrome,
     * clear coat and transmission are entirely a picture *of the environment*, so hiding the
     * environment removes the evidence. What #2874 actually broke was reproducibility under a
     * 360° orbit — a capture landed on a different part of the sphere every run. The sweep
     * above is bounded and QA mode pins its phase, so the backdrop of any two captures of
     * the same mode is the same backdrop.
     */
    data class StudioEnvironment(val label: String, val assetPath: String)

    val environments: List<StudioEnvironment> = listOf(
        StudioEnvironment("Studio", "environments/studio_warm_2k.hdr"),
        StudioEnvironment("Interior", "environments/studio_2k.hdr"),
        StudioEnvironment("Sunset", "environments/sunset_2k.hdr"),
        StudioEnvironment("Night", "environments/night_sky_2k.hdr"),
    )

    /** The seamless-sweep photo studio — the one that gives every trait a highlight to show. */
    const val DEFAULT_ENVIRONMENT_INDEX: Int = 0
}

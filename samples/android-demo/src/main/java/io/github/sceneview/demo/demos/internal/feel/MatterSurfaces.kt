package io.github.sceneview.demo.demos.internal.feel

/**
 * Physical appearance constants, in LINEAR sRGB; never theme/UI colours. These are illustrative
 * finishes, not measured BRDFs. Steel's RGB is conductor reflectance; gel is a clear interface
 * coloured by volume absorption. The other RGB values are diffuse albedos.
 *
 *                 albedo / reflectance     roughness  metal  coat  sheen
 * Memory foam     .86 .79 .59              .94        0      0     0
 * Gel             1 1 1                   .045       0      0     0  (amber absorption, IOR 1.45)
 * Velvet          .009 .065 .028           .90        0      0     emerald pile, strength 1
 * Leather         .22 .075 .028            .44        0      .22   0  (coat roughness .30)
 * Carrara marble  .82 .83 .80              .09        0      1     0  (coat roughness .055)
 * Brushed steel   .56 .58 .60              .26        1      0     0  (anisotropy .95)
 * Opening ceramic .025 .03 .04             .16        0      .80   0
 */
internal object MatterSurfaces {
    data class Surface(
        val red: Float, val green: Float, val blue: Float,
        val roughness: Float,
        val metallic: Float = 0f,
        val coat: Float = 0f,
        val coatRoughness: Float = 0.14f,
        val sheen: Float = 0f,
        val sheenRoughness: Float = .38f,
        val anisotropy: Float = 0f,
        val falloff: Float = 2f,
        val bulge: Float = 0f,
        val wrinkles: Float = 0f,
        val foldCount: Float = 0f,
        val rimRadius: Float = 1.15f,
        val rimWidth: Float = .32f,
        val visualMm: Float = FeelMaterial.WORLD_PER_MM,
    )

    private val surfaces = arrayOf(
        Surface(.86f, .79f, .59f, .94f, bulge = .20f, rimRadius = 1.28f,
            rimWidth = .45f, visualMm = .032f),
        Surface(1f, 1f, 1f, .045f, falloff = 2.2f, bulge = .48f,
            rimRadius = 1.08f, rimWidth = .22f, visualMm = .036f),
        Surface(.009f, .065f, .028f, .90f, sheen = 1f, bulge = .22f,
            wrinkles = .075f, foldCount = 18f, visualMm = .055f),
        Surface(.22f, .075f, .028f, .44f, coat = .22f, coatRoughness = .30f, falloff = 2.6f,
            bulge = .26f, wrinkles = .13f, foldCount = 7f, rimRadius = 1.12f,
            rimWidth = .23f, visualMm = .075f),
        Surface(.82f, .83f, .80f, .09f, coat = 1f, coatRoughness = .055f),
        Surface(.56f, .58f, .60f, .26f, metallic = 1f, anisotropy = .95f),
    )
    val ceramic = Surface(.025f, .03f, .04f, .16f, coat = .80f)
    const val GEL_IOR = 1.45f
    const val GEL_TRANSMISSION = 1f
    // Beer-Lambert coefficients per model-space unit: colour lives INSIDE the gel.
    const val GEL_ABSORPTION_RED = .08f
    const val GEL_ABSORPTION_GREEN = 1.25f
    const val GEL_ABSORPTION_BLUE = 5.5f
    operator fun get(material: FeelMaterial): Surface = surfaces[material.ordinal]

    /** Signed for gel's overshoot; always leaves a supported layer under the deepest dent. */
    fun visualDepth(depthMm: Float, material: FeelMaterial): Float {
        if (material.rigid || !depthMm.isFinite()) return 0f
        val depth = if (material == FeelMaterial.MemoryFoam) {
            // A light 3 mm press already makes a 0.39-unit bowl, with a smooth return to zero.
            // Only the display mapping changes; the spring and slow recovery stay physical.
            FeelSimulation.PAD_THICKNESS * .78f * depthMm.coerceAtLeast(0f) / (depthMm.coerceAtLeast(0f) + 2.7f)
        } else depthMm * get(material).visualMm
        return depth.coerceIn(-FeelSimulation.PAD_THICKNESS * .25f, FeelSimulation.PAD_THICKNESS * .78f)
    }

    /** The shader's signed peak displacement, converted with the same scene millimetre scale. */
    fun captionDepthMm(depthMm: Float, material: FeelMaterial): Float =
        visualDepth(depthMm, material) / FeelMaterial.WORLD_PER_MM

    /** At rest, sub-2 mm recovery reads as character; contact still reports whole millimetres. */
    fun showsDisplacement(material: FeelMaterial, state: Indentation, pressed: Boolean,
                          peakDepthMm: Float = captionDepthMm(state.depthMm, material)): Boolean =
        !material.rigid && (if (pressed) kotlin.math.abs(peakDepthMm) >= .5f else kotlin.math.abs(state.depthMm) >= 2f) &&
            (pressed || kotlin.math.abs(visualDepth(state.depthMm, material)) > .003f)
}

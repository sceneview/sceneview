package io.github.sceneview.demo.demos.internal.feel

/**
 * Physical appearance constants, in LINEAR sRGB; never theme/UI colours. These are illustrative
 * finishes, not measured BRDFs. Steel's RGB is conductor reflectance; the others are diffuse
 * albedos. The shader adds zero-mean roughness detail around these base values.
 *
 *                 albedo / reflectance     roughness  metal  coat  sheen
 * Memory foam     .78 .72 .59              .94        0      0     0
 * Gel             .55 .86 .79              .08        0      0     0  (IOR 1.35)
 * Velvet          .025 .055 .14            .88        0      0     blue pile
 * Leather         .22 .075 .028            .48        0      .25   0
 * Carrara marble  .82 .83 .80              .19        0      .70   0
 * Brushed steel   .56 .58 .60               .30        1      0     0  (anisotropy .80)
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
        val anisotropy: Float = 0f,
        val falloff: Float = 2f,
        val bulge: Float = 0f,
        val wrinkles: Float = 0f,
        val foldCount: Float = 0f,
        val visualMm: Float = FeelMaterial.WORLD_PER_MM,
    )

    private val surfaces = arrayOf(
        Surface(.78f, .72f, .59f, .94f, bulge = .07f, visualMm = .032f),
        Surface(.55f, .86f, .79f, .08f, falloff = 2.2f, bulge = .22f, visualMm = .036f),
        Surface(.025f, .055f, .14f, .88f, sheen = .75f, bulge = .08f,
            wrinkles = .035f, foldCount = 18f, visualMm = .055f),
        Surface(.22f, .075f, .028f, .48f, coat = .25f, falloff = 2.6f,
            bulge = .04f, wrinkles = .055f, foldCount = 12f, visualMm = .075f),
        Surface(.82f, .83f, .80f, .19f, coat = .70f),
        Surface(.56f, .58f, .60f, .30f, metallic = 1f, anisotropy = .80f),
    )
    val ceramic = Surface(.025f, .03f, .04f, .16f, coat = .80f)
    const val GEL_IOR = 1.35f
    const val GEL_TRANSMISSION = 0.92f
    operator fun get(material: FeelMaterial): Surface = surfaces[material.ordinal]

    /** Signed for gel's overshoot; always leaves a supported layer under the deepest dent. */
    fun visualDepth(depthMm: Float, material: FeelMaterial): Float = if (material.rigid) 0f else
        (depthMm * get(material).visualMm).coerceIn(-FeelSimulation.PAD_THICKNESS * .25f,
            FeelSimulation.PAD_THICKNESS * .78f)
}

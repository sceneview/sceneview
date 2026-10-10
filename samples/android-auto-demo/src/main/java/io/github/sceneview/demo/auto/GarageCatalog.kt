package io.github.sceneview.demo.auto

import io.github.sceneview.model.ModelInstance

/** One finish a car can wear. */
internal sealed interface Paint {
    val label: String
}

/** A finish the model itself ships, as a glTF `KHR_materials_variants` variant. */
internal data class VariantPaint(override val label: String, val variantName: String) : Paint

/**
 * A finish made by tinting the body material: [red], [green], [blue] are the **linear** base
 * colour written to `baseColorFactor`. Only offered for a model whose body is one untextured
 * material, so the tint cannot bleed onto trim or glass.
 */
internal data class TintPaint(
    override val label: String,
    val red: Float,
    val green: Float,
    val blue: Float,
) : Paint

/**
 * One car of the garage. [credit] is shown with the car: CC BY asks for the author wherever the
 * work is displayed. Source of truth for authors and licences: `assets/CREDITS.md`.
 *
 * @param paints        Finishes in the order the Paint control cycles them; empty when the body
 *                      is textured and cannot be repainted cleanly.
 * @param paintMaterial Name of the glTF material a [TintPaint] is written to.
 * @param length        Longest side the model is scaled to, in metres.
 * @param sink          How far the model is lowered, in metres, when its bounding box reaches
 *                      under its tyres (steered wheels inflate an axis-aligned box) and a
 *                      bottom-aligned car would hover.
 */
internal data class Car(
    val label: String,
    val assetPath: String,
    val credit: String,
    val paints: List<Paint> = emptyList(),
    val paintMaterial: String? = null,
    val length: Float = GarageStage.CAR_LENGTH,
    val sink: Float = 0f,
)

/**
 * One lighting mood: the HDR the car reflects, and the key light that casts its shadow.
 *
 * @param keyIntensity Key light illuminance, in lux.
 * @param keyKelvin    Key light colour temperature.
 */
internal data class Lighting(
    val label: String,
    val hdrPath: String,
    val keyIntensity: Float,
    val keyKelvin: Float,
)

/**
 * What the garage shows. Every path is staged by `stageGarageAssets` (build.gradle) from files
 * already registered in `assets/manifest.json` — `GarageCatalogTest` checks each one.
 */
internal object GarageCatalog {

    val cars = listOf(
        Car(
            label = "Car Concept",
            assetPath = "models/CarConcept.glb",
            credit = "Darmstadt Graphics Group · CC BY 4.0",
            paints = listOf(
                VariantPaint("Carmine Candy", "Carmine Candy"),
                VariantPaint("Pearly Swirly", "Pearly Swirly"),
                VariantPaint("Torched Graphite", "Torched Graphite"),
            ),
            sink = 0.16f,
        ),
        Car(
            label = "Ferrari F40",
            assetPath = "models/ferrari_f40.glb",
            credit = "Black Snow · CC BY 4.0",
            paints = listOf(
                // The first entry is the model's own base colour, so "back to red" is exact.
                TintPaint("Rosso", 0.2528f, 0.0129f, 0.0129f),
                TintPaint("Giallo", 0.80f, 0.42f, 0.0f),
                TintPaint("Argento", 0.45f, 0.47f, 0.50f),
                TintPaint("Midnight", 0.004f, 0.012f, 0.05f),
            ),
            paintMaterial = "material",
        ),
        Car(
            label = "Toy Car",
            assetPath = "models/khronos_toy_car.glb",
            credit = "Guido Odendahl, Eric Chadwick · CC0",
            // The model is a toy on its cloth: scaled so its cloth stays inside the podium.
            length = 4.0f,
        ),
    )

    val lightings = listOf(
        Lighting("Studio", "environments/studio_2k.hdr", keyIntensity = 60_000f, keyKelvin = 6_000f),
        Lighting("Warm", "environments/studio_warm_2k.hdr", keyIntensity = 50_000f, keyKelvin = 3_400f),
        Lighting("Night", "environments/rooftop_night_2k.hdr", keyIntensity = 25_000f, keyKelvin = 9_000f),
    )
}

/**
 * Puts [paint] on this instance of [car]. Main thread only (Filament JNI), and both branches
 * write to raw Filament objects — the caller asks for a frame afterwards.
 */
internal fun ModelInstance.applyPaint(car: Car, paint: Paint) {
    when (paint) {
        is VariantPaint -> {
            val index = materialVariantNames.indexOf(paint.variantName)
            if (index >= 0) applyMaterialVariant(index)
        }
        is TintPaint -> materialInstances
            .filter { it.name == car.paintMaterial }
            .forEach { it.setParameter("baseColorFactor", paint.red, paint.green, paint.blue, 1f) }
    }
}

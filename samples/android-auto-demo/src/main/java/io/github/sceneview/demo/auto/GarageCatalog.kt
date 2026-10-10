package io.github.sceneview.demo.auto

import io.github.sceneview.model.ModelInstance

/** One finish a car can wear: a glTF `KHR_materials_variants` variant the model itself ships. */
internal data class Paint(val label: String, val variantName: String)

/**
 * One car of the garage. [credit] is shown with the car: CC BY asks for the author wherever the
 * work is displayed. Source of truth for authors and licences: `assets/CREDITS.md`.
 *
 * @param paints      Finishes in the order the Paint control cycles them; empty when the model
 *                    ships no variant.
 * @param length      Longest side the whole model is scaled to, in metres.
 * @param bodyLength  Length of the car itself once scaled, in metres — shorter than [length]
 *                    when the model carries a prop. Sizes the shadow, the headlights and the
 *                    chase camera.
 * @param sink        How far the model is lowered, in metres, when its bounding box reaches
 *                    under its tyres (steered wheels inflate an axis-aligned box) and a
 *                    bottom-aligned car would hover.
 * @param roadSink    How much further it is lowered on the road, once its [showroomOnly] nodes
 *                    no longer hold it up.
 * @param forwardYaw  Degrees to turn the model so its nose points along `+Z`, the direction
 *                    Drive mode moves it.
 * @param showroomOnly Names of the model's nodes shown on the podium and hidden on the road
 *                    (the Toy Car's display cloth).
 */
internal data class Car(
    val label: String,
    val assetPath: String,
    val credit: String,
    val paints: List<Paint> = emptyList(),
    val length: Float = GarageStage.CAR_LENGTH,
    val bodyLength: Float = length,
    val sink: Float = 0f,
    val roadSink: Float = 0f,
    val forwardYaw: Float = 0f,
    val showroomOnly: Set<String> = emptySet(),
)

/**
 * One lighting mood: the HDR the car reflects, and the key light that casts its shadow.
 *
 * @param keyIntensity Key light illuminance, in lux.
 * @param keyKelvin    Key light colour temperature.
 * @param headlights   Whether a car on the road switches its headlights on: a night mood.
 */
internal data class Lighting(
    val label: String,
    val hdrPath: String,
    val keyIntensity: Float,
    val keyKelvin: Float,
    val headlights: Boolean = false,
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
                Paint("Carmine Candy", "Carmine Candy"),
                Paint("Pearly Swirly", "Pearly Swirly"),
                Paint("Torched Graphite", "Torched Graphite"),
            ),
            sink = 0.16f,
        ),
        Car(
            label = "Toy Car",
            assetPath = "models/khronos_toy_car.glb",
            credit = "Guido Odendahl, Eric Chadwick · CC0",
            // The model is a toy on its cloth: scaled so its cloth stays inside the podium,
            // which leaves the car itself a little over two metres long.
            length = 4.0f,
            bodyLength = 2.2f,
            // The cloth is 0.93 m thick at this scale: on the road the car comes down by that.
            roadSink = 0.93f,
            showroomOnly = setOf("Fabric"),
        ),
    )

    val lightings = listOf(
        Lighting("Studio", "environments/studio_2k.hdr", keyIntensity = 60_000f, keyKelvin = 6_000f),
        Lighting("Warm", "environments/studio_warm_2k.hdr", keyIntensity = 50_000f, keyKelvin = 3_400f),
        Lighting(
            label = "Night",
            hdrPath = "environments/rooftop_night_2k.hdr",
            keyIntensity = 25_000f,
            keyKelvin = 9_000f,
            headlights = true,
        ),
    )
}

/**
 * Puts [paint] on this instance. Main thread only (Filament JNI), and it writes to raw Filament
 * objects — the caller asks for a frame afterwards.
 */
internal fun ModelInstance.applyPaint(paint: Paint) {
    val index = materialVariantNames.indexOf(paint.variantName)
    if (index >= 0) applyMaterialVariant(index)
}

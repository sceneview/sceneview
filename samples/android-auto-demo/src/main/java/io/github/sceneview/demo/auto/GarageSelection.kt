package io.github.sceneview.demo.auto

import android.content.Context
import androidx.core.content.edit

/**
 * What the driver picked: indices into [GarageCatalog]. Immutable, so every control is one pure
 * step and the whole state is three integers to persist.
 */
internal data class GarageSelection(
    val car: Int = 0,
    val paint: Int = 0,
    val lighting: Int = 0,
) {
    /** The next car, in its first finish — a finish index means nothing on another car. */
    fun nextCar(cars: List<Car>): GarageSelection =
        copy(car = (car + 1) % cars.size, paint = 0)

    /** The next finish of the current car; unchanged when it has fewer than two. */
    fun nextPaint(cars: List<Car>): GarageSelection {
        val count = cars[car].paints.size
        return if (count < 2) this else copy(paint = (paint + 1) % count)
    }

    fun nextLighting(lightings: List<Lighting>): GarageSelection =
        copy(lighting = (lighting + 1) % lightings.size)

    /**
     * This selection made valid for a catalog: a value persisted by an older build (a car since
     * removed, a finish that no longer exists) falls back to the first entry instead of crashing.
     */
    fun sanitized(cars: List<Car>, lightings: List<Lighting>): GarageSelection {
        val safeCar = car.takeIf { it in cars.indices } ?: 0
        val paintCount = cars[safeCar].paints.size
        return GarageSelection(
            car = safeCar,
            paint = paint.takeIf { safeCar == car && it in 0 until paintCount } ?: 0,
            lighting = lighting.takeIf { it in lightings.indices } ?: 0,
        )
    }
}

/**
 * Keeps the selection across launches. Android Auto starts the activity fresh every time the
 * phone reconnects to the car, and the car quality guidelines ask an app to come back where it
 * was left — saved instance state does not survive that, preferences do.
 */
internal class GarageStore(context: Context) {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): GarageSelection = GarageSelection(
        car = preferences.getInt(KEY_CAR, 0),
        paint = preferences.getInt(KEY_PAINT, 0),
        lighting = preferences.getInt(KEY_LIGHTING, 0),
    ).sanitized(GarageCatalog.cars, GarageCatalog.lightings)

    fun save(selection: GarageSelection) = preferences.edit {
        putInt(KEY_CAR, selection.car)
        putInt(KEY_PAINT, selection.paint)
        putInt(KEY_LIGHTING, selection.lighting)
    }

    private companion object {
        const val FILE = "garage"
        const val KEY_CAR = "car"
        const val KEY_PAINT = "paint"
        const val KEY_LIGHTING = "lighting"
    }
}

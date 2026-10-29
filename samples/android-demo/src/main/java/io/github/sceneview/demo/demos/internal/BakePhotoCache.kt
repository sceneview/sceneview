package io.github.sceneview.demo.demos.internal

/**
 * The photos of one plane bake, decoded only while a plane still paints from them. [picks] lists,
 * plane by plane in bake order, the photos each one paints from. A photo is decoded once, on the
 * first plane that asks for it, and let go after the last, so a room's bake holds a handful of
 * photos at a time instead of every photo it decoded, until the build ends. A photo [load] cannot
 * decode is remembered as missing, not decoded again. Planes are asked for in order, each once:
 * a plane asked for early, or twice, would decode its photos again or let go of photos a plane still
 * to come needs, so [photosFor] throws [IllegalStateException] instead.
 */
class BakePhotoCache<T : Any>(private val picks: List<List<Int>>, private val load: (Int) -> T?) {
    private val lastPlane = HashMap<Int, Int>()
    private val pixels = HashMap<Int, T>()
    private val missing = HashSet<Int>()
    private var next = 0

    /** Photos decoded so far, missing ones included. */
    var loads = 0
        private set

    /** The most decoded photos held at once. */
    var peak = 0
        private set

    /** The photos held now: decoded, with a plane still to come that paints from them. */
    val held: Set<Int> get() = pixels.keys.toSet()

    init {
        picks.forEachIndexed { plane, photos -> photos.forEach { lastPlane[it] = plane } }
    }

    /** [plane]'s photos, decoded if no earlier plane decoded them; the ones it is the last to use are let go. */
    fun photosFor(plane: Int): List<T> {
        check(plane == next) { "plane $plane asked for, plane $next expected: planes are asked for in order, each once" }
        next++
        val photos = picks[plane]
        val out = photos.mapNotNull { index ->
            pixels[index] ?: if (index in missing) {
                null
            } else {
                loads++
                load(index)?.also { pixels[index] = it } ?: run { missing += index; null }
            }
        }
        peak = maxOf(peak, pixels.size)
        photos.forEach { index ->
            if (lastPlane[index] == plane) {
                pixels.remove(index)
                missing.remove(index)
            }
        }
        return out
    }

    fun logLine(): String = "photos held at once: at most $peak of $loads decoded"
}

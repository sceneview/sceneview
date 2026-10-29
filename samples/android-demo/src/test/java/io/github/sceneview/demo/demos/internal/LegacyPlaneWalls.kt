package io.github.sceneview.demo.demos.internal

/**
 * The live walls as they were before they followed the survey's rules, kept in the tests only to
 * count what the rules take out: every vertical plane longer than [MIN_WALL_M] made a wall, a wall
 * never went, and it was drawn as tall as the highest plane seen.
 */
internal class LegacyPlaneWalls {
    private class Track(val id: Int, var segment: Segment)

    private val tracks = ArrayList<Track>()
    private var nextId = 0

    /** The ids of the walls shown after [planes], over a floor at [floorY]. */
    fun update(planes: List<LivePlane>, floorY: Float): List<Int> {
        val pieces = planes.mapNotNull { plane ->
            if (plane.kind != LivePlane.Kind.Wall || plane.vertexCount < 3) return@mapNotNull null
            Segment.ofPolygon(plane.polygon)?.takeIf { it.length >= MIN_PIECE_M && it.top > floorY }
        }
        val taken = HashSet<Track>()
        for (segment in PlaneWallSource.clusters(pieces).sortedByDescending { it.length }) {
            val matches = tracks.filter { it !in taken && PlaneWallSource.sameWall(it.segment, segment) }
            if (matches.isEmpty()) {
                tracks += Track(nextId++, segment).also { taken += it }
                continue
            }
            val keep = matches.minBy { it.id }
            var grown = segment
            matches.forEach { grown = grown.spanning(it.segment) }
            keep.segment = grown
            tracks.removeAll { it in matches && it !== keep }
            taken += keep
        }
        return tracks.filter { it.segment.length >= MIN_WALL_M }.map { it.id }
    }

    /** The height drawn with no ceiling seen: the top of the highest wall, over [floorY]. */
    fun height(floorY: Float): Float? = tracks.maxOfOrNull { it.segment.top }?.minus(floorY)

    companion object {
        const val MIN_WALL_M = 0.5f
        const val MIN_PIECE_M = 0.25f
    }
}

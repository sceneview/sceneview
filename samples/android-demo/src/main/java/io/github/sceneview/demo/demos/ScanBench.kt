package io.github.sceneview.demo.demos

import android.util.Log
import io.github.sceneview.demo.demos.internal.room.RoomDepthMesh
import io.github.sceneview.demo.demos.internal.room.ScanFloorSlab

/**
 * The live scan's benchmark line, read by `.claude/scripts/scan_replay_bench.py` from logcat:
 * `ScanBench: {"schema":2,...}`. One `cadence` event per one-second window of AR frames, and one
 * `surface_ready` event the first time the scan covers any floor; once the result is fused again
 * from the depth recording, one `depth_model` event ([depthModelLine]). Debug builds only — the
 * caller gates it — so a store build never writes it.
 *
 * `outside_elements` is sent as `null`: the live scan does not yet count surfels outside the
 * closed walls' hull, and the bench reads `null` as "not measured", never as zero.
 */
internal class ScanBench(
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val sink: (String) -> Unit = { Log.i(TAG, it) },
) {
    private var windowStartMs = -1L
    private var frames = 0
    private var surfaceSent = false

    /** One AR frame. [tracking] is ARCore's tracking state name; [coveredSquareMetres] the floor covered so far. */
    fun frame(tracking: String, coveredSquareMetres: Float) {
        val now = clockMs()
        if (windowStartMs < 0) windowStartMs = now
        if (now - windowStartMs >= WINDOW_MS) {
            sink(
                """{"schema":2,"event":"cadence","fps":$frames,"window_start_epoch_ms":$windowStartMs,""" +
                    """"tracking_state":"$tracking","covered_m2":${area(coveredSquareMetres)}}""",
            )
            windowStartMs = now
            frames = 0
        }
        frames++
        if (!surfaceSent && coveredSquareMetres > 0f) {
            surfaceSent = true
            sink(
                """{"schema":2,"event":"surface_ready","surface_ready":true,""" +
                    """"covered_m2":${area(coveredSquareMetres)},"outside_elements":null}""",
            )
        }
    }

    private fun area(m2: Float) = if (m2.isFinite()) "%.2f".format(java.util.Locale.ROOT, m2) else "null"

    /**
     * The result's surface fused again from the depth recording, once built: [depthModelLine].
     * Sent after the live phase, so the bench's live figures never read it. A bench line never
     * fails the result it describes: off a device (JVM tests) `android.util.Log` throws.
     */
    fun depthModel(
        opensOn: String,
        fused: RoomDepthMesh.Result,
        floorY: Float?,
        floorSource: String?,
        slab: ScanFloorSlab.Result?,
    ) {
        val line = depthModelLine(opensOn, fused, floorY, floorSource, slab)
        runCatching { sink(line) }
    }

    companion object {
        const val TAG = "ScanBench"
        const val WINDOW_MS = 1_000L

        /** The largest pieces listed by area; the count says how many there are in all. */
        const val MAX_PIECES_LISTED = 8

        /**
         * `{"schema":2,"event":"depth_model",...}`: how the walk was split ([RoomDepthMesh.Result.segments],
         * the one fused, what each covered) and, under a [slab], the floor's pieces before and after
         * the strays went and the floor triangles it took out. [opensOn] is what the result opens on
         * (`depth_model`, `shell`); [floorY] the floor it was handed, from [floorSource] (`scan`,
         * `arcore`), `null` when read off the mesh.
         *
         * For a fresh partial-room fusion, `floor_m2_by_stage` sums triangle areas projected onto
         * XZ at `floor_trace_y` (scan floor, fused ARCore median, or the mesh estimate before hole
         * filling). The early stages use the slab's all-vertices ±4 cm and |face normal Y| ≥ 0.8
         * tests. `wider_truncation` replays the same segment with twice the TSDF band and requires
         * eight observed corners. `truncation` is the same count with the nominal band.
         * `weakest_corner` then requires the corner minimum, and `weight` the strongest-corner
         * minimum too. These cell sweeps use the result's stride and cover the whole field.
         * `meshed` limits `weight` to the cells extracted before any terminal triangle cap.
         * `components` measures the actual mesh after component filtering. After hole filling,
         * `near_floor` measures all candidate faces in a doubled height band, `height_band`
         * restores ±4 cm, and `classified` applies the normal test too. The slab's grid area is
         * reported separately. `floor_wider_band_weight_m2` applies both weight gates to the wider
         * replay, for comparison with `weight`.
         *
         * `floor_gate_counts` includes nominal observed voxels and voxels observed only in the
         * wider replay, both within ±4 cm. The latter are a band sensitivity measure: widening
         * also changes block selection and clipping, and the replay has the same block budget.
         * Cell rejections are disjoint, in gate order, for cells centred within ±4 cm with both
         * TSDF signs among their observed corners. Triangle rejections are from `near_floor` to
         * `height_band` (height) and from `height_band` to `classified` (normal). Missing traces
         * or failed wider replays report `null` for the corresponding measurements.
         */
        fun depthModelLine(
            opensOn: String,
            fused: RoomDepthMesh.Result,
            floorY: Float?,
            floorSource: String?,
            slab: ScanFloorSlab.Result?,
        ): String = buildString {
            append("""{"schema":2,"event":"depth_model","opens_on":${quoted(opensOn)}""")
            append(""","frames":${fused.frames},"fused_frames":${fused.fused},"guard_held":${fused.guardHeld}""")
            append(""","segments":${fused.segments},"segment_chosen":${fused.chosenSegment}""")
            append(""","segment_cells":${ints(fused.segmentCells)},"segment_frames":${ints(fused.segmentFrames)}""")
            append(""","dropped_components":${fused.droppedComponents},"triangles":${fused.mesh.triangleCount}""")
            append(""","floor_y":${metres(floorY)},"floor_source":${floorSource?.let(::quoted) ?: "null"}""")
            append(""","arcore_floor_y":${metres(fused.floorPlaneY)},"slab":""")
            if (slab == null) {
                append("null")
            } else {
                append("""{"floor_y":${metres(slab.floorY)},"area_m2":${m2(slab.areaM2)}""")
                append(""","pieces_before":${slab.piecesBeforeM2.size},"pieces_before_m2":${areas(slab.piecesBeforeM2)}""")
                append(""","pieces_after":${slab.piecesAfterM2.size},"pieces_after_m2":${areas(slab.piecesAfterM2)}""")
                append(""","floor_triangles_removed":${slab.floorTriangles}""")
                append(""","floor_triangles_off_slab":${slab.floorTrianglesOffSlab}""")
                append(""","floor_triangles_in_dropped_pieces":${slab.floorTrianglesInDroppedPieces}""")
                append(""","below_triangles":${slab.belowTriangles},"stray_triangles":${slab.strayTriangles}}""")
            }
            appendFloorTrace(fused)
            append("}")
        }

        private fun StringBuilder.appendFloorTrace(fused: RoomDepthMesh.Result) {
            val trace = fused.floorTrace
            append(""","floor_trace_y":${metres(trace?.floorY)},"floor_trace_stride":${trace?.stride},"floor_trace_capped":${trace?.capped}""")
            append(""","floor_trace_dropped_blocks":${trace?.let { fused.droppedBlocks }},"floor_wider_band_dropped_blocks":${trace?.widerDroppedBlocks}""")
            append(""","floor_wider_band_weight_m2":${m2(trace?.widerTruncationWeightM2)}""")
            if (trace == null) {
                append(""","floor_m2_by_stage":null,"floor_gate_counts":null""")
                return
            }
            val cells = trace.cells
            val classification = trace.classification
            append(""","floor_m2_by_stage":{"wider_truncation":${m2(trace.widerTruncationM2)},"truncation":${m2(cells.observedM2)}""")
            append(""","weakest_corner":${m2(cells.weakestCornerM2)},"weight":${m2(cells.weightM2)},"meshed":${m2(cells.meshedM2)},"components":${m2(trace.componentsM2)}""")
            append(""","near_floor":${m2(classification.nearFloorM2)},"height_band":${m2(classification.heightBandM2)},"classified":${m2(classification.classifiedM2)}}""")
            append(""","floor_gate_counts":{"observed_voxels":${trace.observedVoxels},"wider_band_only_voxels":${trace.widerBandOnlyVoxels}""")
            append(""","unobserved_corner_cells":${cells.unobservedCornerCells},"weakest_corner_cells":${cells.weakestCornerCells},"min_weight_cells":${cells.minWeightCells}""")
            append(""","height_triangles":${classification.heightTriangles},"normal_triangles":${classification.normalTriangles}}""")
        }

        /** [value] as a JSON string: quotes, backslashes, control characters and line separators escaped. */
        private fun quoted(value: String) = buildString {
            append('"')
            for (ch in value) {
                when {
                    ch == '"' || ch == '\\' -> append('\\').append(ch)
                    // Line separators too: the bench reads logcat line by line.
                    ch < ' ' || ch == '\u2028' || ch == '\u2029' -> append("\\u%04x".format(java.util.Locale.ROOT, ch.code))
                    else -> append(ch)
                }
            }
            append('"')
        }

        private fun ints(values: List<Int>) = values.joinToString(",", "[", "]")

        private fun areas(values: List<Float>) = values.take(MAX_PIECES_LISTED).joinToString(",", "[", "]") { m2(it) }

        private fun m2(value: Float?) = if (value != null && value.isFinite()) "%.2f".format(java.util.Locale.ROOT, value) else "null"

        private fun metres(value: Float?) =
            if (value != null && value.isFinite()) "%.3f".format(java.util.Locale.ROOT, value) else "null"
    }
}

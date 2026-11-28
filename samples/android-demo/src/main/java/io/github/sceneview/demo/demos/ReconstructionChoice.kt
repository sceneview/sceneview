package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.ReconstructionMethod
import io.github.sceneview.demo.demos.internal.RerunScanModel
import io.github.sceneview.demo.demos.internal.RerunTsdf
import io.github.sceneview.demo.demos.internal.ScanBox
import io.github.sceneview.demo.demos.internal.ScanScope
import io.github.sceneview.demo.demos.internal.ScanSubject
import io.github.sceneview.demo.demos.internal.ScreenedPoisson
import io.github.sceneview.demo.demos.internal.model.TexturedSyntheticRoom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * The end of a scan's reconstruction, when it is not the fused TSDF (#4630): [ScreenedPoisson]
 * over the scan's surfels, its field handed to the model's meshing as a TSDF — and, before the
 * model is built, the question of which of the two finishes it.
 */

/**
 * [source]'s surface by [method], as a TSDF to mesh: `null` for [ReconstructionMethod.Fused] —
 * the model fuses as it always has — and when Poisson has nothing to work from (no surfels
 * with normals) or no grid fits its budget; the model then fuses too.
 */
internal fun reconstructedTsdf(
    source: RerunModelSource,
    method: ReconstructionMethod,
    voxelM: Float = RerunTsdf.VOXEL_M,
    progress: (Float) -> Unit,
): RerunTsdf? {
    if (method != ReconstructionMethod.Poisson) return null
    val cloud = reconstructionCloud(source) ?: return null
    return ScreenedPoisson.reconstruct(cloud, voxelM = voxelM, progress = progress)?.tsdf
}

/**
 * How an object's scan ([ScanScope.Object]) is finished: the thing it went round ([ScanSubject])
 * at least [FILLED_OBJECT_M] across is filled ([ReconstructionMethod.Poisson]) on the room's
 * grid; a smaller one, or none found, keeps the fused surface. On real scans the fused
 * office chair is shreds — its thin stem and open back give the depth too little to hold —
 * where the fill draws the seat, the stem and the back whole; a pair of shoes keeps its two
 * shoes fused, which a grid that coarse rounds into one lump. [aims] is where the phone looked
 * ([ScanSubject.aims]); [voxelM] the grid the surfels were kept on, when the scan says it.
 */
internal fun objectReconstruction(source: RerunModelSource, aims: List<ScanSubject.Aim>?, voxelM: Float?): ReconstructionMethod {
    if (aims.isNullOrEmpty()) return ReconstructionMethod.Fused
    val cloud = reconstructionCloud(source) ?: return ReconstructionMethod.Fused
    val subject = runCatching { ScanSubject.of(cloud, voxelM ?: DenseFusion.VOXEL_M, aims) }.getOrNull()
    return if (fillsObject(subject)) ReconstructionMethod.Poisson else ReconstructionMethod.Fused
}

/**
 * Whether the scan shown ([shownId]) has its data: `false` only while its own files are being read
 * ([readingId], the stored scan an opening reads). An opening let go half-way, then another scan
 * shown, holds that scan back from nothing.
 */
internal fun captureLoaded(readingId: String?, shownId: String?): Boolean = readingId == null || readingId != shownId

/**
 * What an [ScanScope.Object] scan's result is decided on, read once per scan and its data: the box
 * it went round ([subject], [ScanSubject]) and whether it is filled ([filled], as
 * [objectReconstruction] would say, #5114) — off its [source] while the scan still has one, else
 * its saved surfels and the trace's aims, which is all a reopen has.
 */
internal class ObjectResultInputs(val filled: Boolean, val subject: ScanBox?) {
    companion object {
        /** Not an object, or one with nothing to judge it from: never filled, no box. */
        val NONE = ObjectResultInputs(filled = false, subject = null)

        /**
         * [scope]'s inputs; `null` while an object's saved data are still being read ([loaded]
         * false): nothing is to be fused or kept for it on a guess meanwhile.
         */
        fun of(
            scope: ScanScope,
            source: RerunModelSource?,
            surfels: DenseCloud?,
            aims: List<ScanSubject.Aim>?,
            voxelM: Float?,
            loaded: Boolean,
        ): ObjectResultInputs? {
            if (scope != ScanScope.Object) return NONE
            if (!loaded) return null
            if (aims.isNullOrEmpty()) return NONE
            val cloud = source?.usable()?.let(::reconstructionCloud) ?: surfels?.takeIf { it.count > 0 } ?: return NONE
            val subject = runCatching { ScanSubject.of(cloud, voxelM ?: DenseFusion.VOXEL_M, aims) }.getOrNull()
            return ObjectResultInputs(fillsObject(subject), subject)
        }
    }
}

/**
 * Whether a kept model an earlier finish pipeline built ([RerunScanModel.stalePipeline]) is built
 * again from its reopened scan, [source]: only where this pipeline finishes it differently — an
 * object filled now ([objectReconstruction], pipeline 9). Anywhere else a rebuild could only
 * lose: a model meshed at capture from the raw depth (1.25 cm for an object) would be meshed
 * again from the surfels the recording keeps, coarser for good, and a room would come out the
 * same. A pipeline that changes another finish says so here.
 */
internal fun rebuildsStale(scope: ScanScope, source: RerunModelSource, aims: List<ScanSubject.Aim>?, voxelM: Float?): Boolean =
    scope == ScanScope.Object && objectReconstruction(source, aims, voxelM) == ReconstructionMethod.Poisson

/** Whether an object as big as [subject] is filled ([objectReconstruction]): `false` for none. */
internal fun fillsObject(subject: ScanBox?): Boolean =
    subject != null && maxOf(subject.length, subject.depth, subject.height) >= FILLED_OBJECT_M

/**
 * The smallest object filled ([objectReconstruction]), in metres across its largest side: twelve
 * cells of the room's 5 cm grid. The chair (97 cm) is over it; the shoes (29 cm) are well under.
 */
internal const val FILLED_OBJECT_M = 0.6f

/** What "Filled" ([ReconstructionMethod.Poisson]) can make of a scan, told before it is picked. */
internal sealed interface FillPlan {
    /** The scan kept no surfels with normals: there is nothing to fill from. */
    data object NoSurfels : FillPlan

    /** No grid within [ScreenedPoisson.MAX_COARSENING] doublings fits the fill's memory. */
    data object TooBig : FillPlan

    /** It fills on a grid [coarser] times the voxel's spacing: 1 in full detail. */
    data class Fills(val coarser: Int) : FillPlan
}

/** What "Filled" can make of [source] within [maxBytes]: the grid [ScreenedPoisson] would solve on. */
internal fun fillPlan(source: RerunModelSource, maxBytes: Long = ScreenedPoisson.MAX_BYTES): FillPlan {
    val cloud = reconstructionCloud(source)?.takeIf { it.normals != null && it.count > 0 } ?: return FillPlan.NoSurfels
    val spacing = ScreenedPoisson.spacing(cloud, maxBytes = maxBytes) ?: return FillPlan.TooBig
    return FillPlan.Fills((spacing / RerunTsdf.VOXEL_M).roundToInt())
}

/** The surfels [ScreenedPoisson] works from: the scan's, or the QA room's. */
private fun reconstructionCloud(source: RerunModelSource): DenseCloud? = when (source) {
    is RerunModelSource.Live -> source.cloud
    is RerunModelSource.Surfels -> source.cloud
    is RerunModelSource.Synthetic -> texturedSyntheticCloud()
}

/**
 * The question asked at the end of a scan, what [fill] can make of it, until it is answered: the
 * first answer counts, and dismissing it finishes [ReconstructionMethod.Fused].
 */
internal class FinishQuestion(val fill: FillPlan) {
    private val answer = CompletableDeferred<ReconstructionMethod>()

    fun finish(method: ReconstructionMethod) {
        answer.complete(method)
    }

    fun dismiss() = finish(ReconstructionMethod.Fused)

    suspend fun await(): ReconstructionMethod = answer.await()
}

/**
 * How [source]'s model is finished. Not [asks], or with no surfels to fill from, as scanned
 * without asking; else the question goes to [show] — with what "Filled" can make of the scan
 * within [maxBytes], worked out off the main thread — until answered, then [show] hears `null`.
 */
internal suspend fun finishMethod(
    source: RerunModelSource,
    asks: Boolean,
    maxBytes: Long = ScreenedPoisson.MAX_BYTES,
    show: (FinishQuestion?) -> Unit,
): ReconstructionMethod {
    if (!asks) return ReconstructionMethod.Fused
    val fill = withContext(Dispatchers.Default) { fillPlan(source, maxBytes) }
    if (fill == FillPlan.NoSurfels) return ReconstructionMethod.Fused
    val question = FinishQuestion(fill)
    show(question)
    return try {
        question.await()
    } finally {
        show(null)
    }
}

/** QA only: the textured synthetic room's surfels, as its recording keeps them. */
private fun texturedSyntheticCloud(): DenseCloud {
    val fusion = DenseFusion()
    TexturedSyntheticRoom.frames().forEach { fusion.add(DepthBackProjection.project(it)) }
    return fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
}

package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.ReconstructionMethod
import io.github.sceneview.demo.demos.internal.RerunTsdf
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
internal fun reconstructedTsdf(source: RerunModelSource, method: ReconstructionMethod, progress: (Float) -> Unit): RerunTsdf? {
    if (method != ReconstructionMethod.Poisson) return null
    val cloud = reconstructionCloud(source) ?: return null
    return ScreenedPoisson.reconstruct(cloud, progress = progress)?.tsdf
}

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

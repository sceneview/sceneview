package io.github.sceneview

import io.github.sceneview.model.AnimationEnvelope
import io.github.sceneview.model.animationEnvelopeCovers
import io.github.sceneview.model.animationEnvelope
import io.github.sceneview.model.composedAnimationEnvelope
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.RenderableNode

internal const val MAX_AUTO_FIT_CLIPS = 8
private const val AUTO_FIT_SAMPLES = 24

internal fun resolveAutoFitMode(
    qualifyingModels: Int,
    hasStaticContent: Boolean,
    requiresMeasuredPose: Boolean = false,
): FitMode = if (qualifyingModels > 0 && !hasStaticContent && !requiresMeasuredPose) {
    FitMode.Silhouette
} else {
    FitMode.Corners
}

internal fun qualifiesForAnimationEnvelope(renderableVisibility: List<Boolean>): Boolean =
    renderableVisibility.isNotEmpty() && renderableVisibility.all { it }

internal fun autoFitClips(
    playing: Collection<Int>,
    animationCount: Int,
    retainedClip: Int? = null,
    playbackPresent: Boolean = playing.isNotEmpty(),
    max: Int = MAX_AUTO_FIT_CLIPS,
): List<Int> {
    if (animationCount <= 0 || max <= 0) return emptyList()
    val source = when {
        playing.isNotEmpty() -> playing
        // A paused entry writes no transforms. Keep the pose Filament actually displays instead
        // of selecting the paused clip merely because it is present in the public map.
        playbackPresent -> listOfNotNull(retainedClip?.takeIf { it in 0 until animationCount })
        else -> buildList {
            addAll(0 until animationCount)
            // Filament keeps the last applied transforms after playback stops. Move that clip to
            // the sampling tail so a capped idle fallback cannot discard the on-screen pose.
            if (retainedClip in 0 until animationCount) {
                remove(retainedClip)
                add(retainedClip!!)
            }
        }
    }
    val valid = source.asSequence()
        .filter { it in 0 until animationCount }
        .distinct()
        .toList()
    // ModelNode applies clips in map iteration order, so later clips have the final say when
    // several clips target the same joints. Keep that tail when the sampling budget is capped.
    return valid.takeLast(max)
}

internal fun mergeEnvelopes(envelopes: List<AnimationEnvelope>): AnimationEnvelope? {
    if (envelopes.isEmpty()) return null
    val poses = mutableListOf<Aabb>()
    val restPoses = mutableSetOf<Aabb>()
    envelopes.forEach { envelope ->
        envelope.poseBounds.forEachIndexed { index, pose ->
            if (index != 0 || restPoses.add(pose)) poses += pose
        }
    }
    return AnimationEnvelope(poses.map { it.toBox() })
}

private fun Aabb.toBox() = com.google.android.filament.Box(
    center.x, center.y, center.z, halfExtent.x, halfExtent.y, halfExtent.z
)

internal data class AutoFraming(
    val fitMode: FitMode,
    val animationEnvelope: AnimationEnvelope?,
    val includeMeasuredPose: Boolean = false,
)

internal data class AutoFramingPlayback(
    val modelIdentity: Int,
    val clips: List<Int>,
    val pausedClips: List<Int> = emptyList(),
    val retainedClip: Int? = null,
)

/** Cheap, stable invalidation key; unlike [resolveAutoFraming], this never samples clip poses. */
internal fun autoFramingPlaybackSignature(contentRoots: List<Node>): List<AutoFramingPlayback> {
    val playback = mutableListOf<AutoFramingPlayback>()

    fun visit(node: Node) {
        if (!node.isVisible) return
        if (node is ModelNode) {
            val (active, paused) = node.playingAnimations.entries.partition { it.value.speed != 0f }
            playback += AutoFramingPlayback(
                System.identityHashCode(node),
                active.map { it.key },
                paused.map { it.key },
                node.lastAppliedAnimationClip,
            )
        }
        node.childNodes.forEach(::visit)
    }
    contentRoots.forEach(::visit)
    return playback
}

/** Main-thread walk: model metadata, renderable AABBs and world transforms reach Filament JNI. */
internal fun resolveAutoFraming(
    contentRoots: List<Node>,
    computeEnvelope: Boolean = true,
): AutoFraming {
    val qualifying = mutableListOf<ModelNode>()
    var hasStaticContent = false

    fun visit(node: Node, skippedRenderables: Set<Node> = emptySet()) {
        if (!node.isVisible) return
        // A qualifying model's own renderables are not classified again, but whatever an app
        // parents under them (geometry, another model) still is: it renders too.
        if (node in skippedRenderables) {
            node.childNodes.forEach { visit(it, skippedRenderables) }
            return
        }
        val model = node as? ModelNode
        val qualifyingModel = model?.takeIf {
            runCatching {
                it.skinCount > 0 && it.animationCount > 0 &&
                    qualifiesForAnimationEnvelope(it.renderableNodes.map { renderable ->
                        renderable.isVisible
                    })
            }.getOrDefault(false)
        }
        val qualifies = qualifyingModel != null
        if (qualifyingModel != null) qualifying += qualifyingModel

        if (!qualifies && node is RenderableNode) {
            val hasBounds = runCatching { !node.axisAlignedBoundingBox.toAabb().isEmpty }
                .getOrDefault(false)
            if (hasBounds) hasStaticContent = true
        }

        val ownedRenderables = qualifyingModel?.renderableNodes?.toHashSet() ?: skippedRenderables
        node.childNodes.forEach { visit(it, ownedRenderables) }
    }
    contentRoots.forEach { visit(it) }

    data class Selection(
        val node: ModelNode,
        val clips: List<Int>,
        val composed: Boolean,
        val coverageComplete: Boolean,
    )
    val selections = qualifying.map { modelNode ->
        val activeClips = modelNode.playingAnimations
            .filterValues { it.speed != 0f }
            .keys
        val retainedClips = modelNode.retainedAnimationClips
        val composed = retainedClips.size > 1
        val clips = if (composed) retainedClips else autoFitClips(
            activeClips,
            modelNode.animationCount,
            modelNode.lastAppliedAnimationClip,
            playbackPresent = modelNode.playingAnimations.isNotEmpty(),
        )
        Selection(
            modelNode,
            clips,
            composed,
            modelNode.model.animationEnvelopeCovers(clips),
        )
    }
    val hasIncompleteCoverage = selections.any { !it.coverageComplete }
    val includeMeasuredPose = hasStaticContent || hasIncompleteCoverage
    val mode = resolveAutoFitMode(
        qualifying.size,
        hasStaticContent,
        requiresMeasuredPose = hasIncompleteCoverage,
    )
    if (!computeEnvelope || selections.isEmpty()) {
        return AutoFraming(mode, null, includeMeasuredPose)
    }
    val envelopes = selections.mapNotNull { selection ->
        runCatching {
            // ModelNode's entity is `modelInstance.root` (`liveRoot()` in its constructor), the
            // same glTF asset root whose coordinates Model.animationEnvelope returns. Therefore
            // the root node's world transform takes those model-space boxes directly to world.
            val envelope = if (selection.composed) {
                selection.node.model.composedAnimationEnvelope(
                    selection.clips,
                    AUTO_FIT_SAMPLES,
                )
            } else {
                selection.node.model.animationEnvelope(selection.clips, AUTO_FIT_SAMPLES)
            }
            envelope.transformed(selection.node.worldTransform)
        }.getOrNull()
    }
    return AutoFraming(mode, mergeEnvelopes(envelopes), includeMeasuredPose)
}

package io.github.sceneview

import io.github.sceneview.model.AnimationEnvelope
import io.github.sceneview.model.animationEnvelope
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.RenderableNode

internal const val MAX_AUTO_FIT_CLIPS = 8
private const val AUTO_FIT_SAMPLES = 24

internal fun resolveAutoFitMode(qualifyingModels: Int, hasStaticContent: Boolean): FitMode =
    if (qualifyingModels > 0 && !hasStaticContent) FitMode.Silhouette else FitMode.Corners

internal fun autoFitClips(
    playing: Collection<Int>,
    animationCount: Int,
    max: Int = MAX_AUTO_FIT_CLIPS,
): List<Int> {
    if (animationCount <= 0 || max <= 0) return emptyList()
    val source = playing.takeIf { it.isNotEmpty() } ?: (0 until animationCount).toList()
    return source.asSequence()
        .filter { it in 0 until animationCount }
        .distinct()
        .sorted()
        .take(max)
        .toList()
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
)

/** Main-thread walk: model metadata, renderable AABBs and world transforms reach Filament JNI. */
internal fun resolveAutoFraming(
    contentRoots: List<Node>,
    computeEnvelope: Boolean = true,
): AutoFraming {
    val qualifying = mutableListOf<ModelNode>()
    var hasStaticContent = false

    fun visit(node: Node, skippedRenderables: Set<Node> = emptySet()) {
        if (!node.isVisible || node in skippedRenderables) return
        val model = node as? ModelNode
        val qualifyingModel = model?.takeIf {
            runCatching { it.skinCount > 0 && it.animationCount > 0 }.getOrDefault(false)
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

    val mode = resolveAutoFitMode(qualifying.size, hasStaticContent)
    if (mode != FitMode.Silhouette || !computeEnvelope) return AutoFraming(mode, null)
    val envelopes = qualifying.mapNotNull { modelNode ->
        runCatching {
            val clips = autoFitClips(modelNode.playingAnimations.keys, modelNode.animationCount)
            // ModelNode's entity is `modelInstance.root` (`liveRoot()` in its constructor), the
            // same glTF asset root whose coordinates Model.animationEnvelope returns. Therefore
            // the root node's world transform takes those model-space boxes directly to world.
            modelNode.model.animationEnvelope(clips, AUTO_FIT_SAMPLES)
                .transformed(modelNode.worldTransform)
        }.getOrNull()
    }
    return AutoFraming(mode, mergeEnvelopes(envelopes))
}

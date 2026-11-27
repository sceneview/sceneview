package io.github.sceneview

import io.github.sceneview.model.AnimationEnvelope
import io.github.sceneview.model.animationEnvelope
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.RenderableNode

internal const val MAX_AUTO_FIT_CLIPS = 8
private const val AUTO_FIT_SAMPLES = 24

internal fun resolveAutoFitMode(
    qualifyingModels: Int,
    hasStaticContent: Boolean,
    hasConcurrentPlayback: Boolean = false,
): FitMode = if (qualifyingModels > 0 && !hasStaticContent && !hasConcurrentPlayback) {
    FitMode.Silhouette
} else {
    FitMode.Corners
}

internal fun hasConcurrentPlayback(playing: Collection<Int>, animationCount: Int): Boolean =
    playing.asSequence()
        .filter { it in 0 until animationCount }
        .distinct()
        .take(2)
        .count() > 1

internal fun autoFitClips(
    playing: Collection<Int>,
    animationCount: Int,
    max: Int = MAX_AUTO_FIT_CLIPS,
): List<Int> {
    if (animationCount <= 0 || max <= 0) return emptyList()
    val source = playing.takeIf { it.isNotEmpty() } ?: (0 until animationCount).toList()
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
)

internal data class AutoFramingPlayback(
    val modelIdentity: Any,
    val clips: List<Int>,
)

/** Cheap, stable invalidation key; unlike [resolveAutoFraming], this never samples clip poses. */
internal fun autoFramingPlaybackSignature(contentRoots: List<Node>): List<AutoFramingPlayback> {
    val playback = mutableListOf<AutoFramingPlayback>()

    fun visit(node: Node) {
        if (!node.isVisible) return
        if (node is ModelNode) {
            playback += AutoFramingPlayback(node, node.playingAnimations.keys.toList())
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

    // Separate clip envelopes cannot represent the composed pose that Filament produces by
    // applying several clips in sequence. Keep the live, already-composed renderable bounds and
    // their conservative corner fit instead of replacing them with an unsafe analytic envelope.
    val concurrentPlayback = qualifying.any {
        hasConcurrentPlayback(it.playingAnimations.keys, it.animationCount)
    }
    val mode = resolveAutoFitMode(qualifying.size, hasStaticContent, concurrentPlayback)
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

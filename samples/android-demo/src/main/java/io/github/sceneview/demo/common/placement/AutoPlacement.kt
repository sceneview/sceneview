package io.github.sceneview.demo.common.placement

/** Compatibility names for the demo adapter; placement policy lives in the SDK. */
typealias PlacementPhase = io.github.sceneview.ar.PlacementPhase
typealias AssetTicket = io.github.sceneview.ar.AssetTicket
typealias FrameEffect = io.github.sceneview.ar.FrameEffect
typealias FrameInput = io.github.sceneview.ar.FrameInput
typealias UsableSurfacePolicy = io.github.sceneview.ar.UsableSurfacePolicy
typealias FallbackCandidate<T> = io.github.sceneview.ar.FallbackCandidate<T>
typealias Ndc = io.github.sceneview.ar.Ndc
typealias ViewportProjection = io.github.sceneview.ar.ViewportProjection
typealias AutoPlacementController = io.github.sceneview.ar.AutoPlacementState

// ── Scale label (plan §2.3) ──────────────────────────────────────────────────────────────

/** Which words the pinch read-out uses. */
enum class ScaleLabelMode {
    /** The object's size is a measured real-world size: "Actual size". */
    ACTUAL,

    /** A showcase size nothing vouches for: "Preview size". Never claim what is not known. */
    PREVIEW,
}

/**
 * A real-world size is only trustworthy when someone measured it — a bundled row whose
 * metres are the asset's own, or an opened file whose bounds were read. Everything else
 * (the 0.3 m default, a streamed row's fitted guess) is a preview.
 */
fun scaleLabelMode(sizeIsMeasured: Boolean): ScaleLabelMode =
    if (sizeIsMeasured) ScaleLabelMode.ACTUAL else ScaleLabelMode.PREVIEW

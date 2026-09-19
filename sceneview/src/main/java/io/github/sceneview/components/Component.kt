package io.github.sceneview.components

import com.google.android.filament.Engine
import io.github.sceneview.Entity

/**
 * Base interface for Filament ECS components.
 *
 * Every SceneView component (camera, renderable, light) is attached to an [entity] managed by a
 * Filament [Engine]. Implementations expose typed accessors that delegate to the appropriate
 * Filament manager (RenderableManager, LightManager, etc.).
 */
interface Component {
    /** The Filament engine that owns this component's resources. */
    val engine: Engine

    /** The entity this component is attached to. */
    val entity: Entity

    /**
     * Called by every accessor here that changes what the camera would see.
     *
     * Filament's managers are write-only from the SDK's point of view: setting a light's intensity
     * or a camera's exposure reaches the engine and nothing else, so under
     * [io.github.sceneview.FrameRatePolicy.OnDemand] — the default — the change sits there with no
     * frame coming to show it. The lighting demo measured exactly that: dragging *Environment
     * rotation* from 302° to 100° and *Exposure* from 1.00 to 2.72 on a parked scene produced 0
     * Filament frames and a viewport still lit the old way (#3718).
     *
     * The rule the SDK holds itself to is therefore: **a public mutator of an SDK-owned type that
     * changes what is drawn invalidates on its own.** This is the seam that lets an interface with
     * no backing field do it — [io.github.sceneview.node.Node] subclasses override it with
     * [io.github.sceneview.node.Node.requestRender].
     *
     * It stops at the SDK's own types. A *raw* Filament object the caller obtained and mutated
     * directly — `MaterialInstance.setParameter`, an `IndirectLight`, a `Skybox`, `LightManager`
     * through its own handle — is invisible here, and the caller asks for the frame itself.
     *
     * The default does nothing, so an implementer that is not in a scene costs nothing.
     */
    fun onComponentChanged() {}
}
#if os(iOS)
import RealityKit

/// The shadow ``ARSceneView`` gives the content an `onTapOnPlane` callback places, so a placed
/// model reads as resting on the real surface instead of floating above it.
///
/// Set it with ``ARSceneView/placementShadow(_:)``. Without that modifier the style follows
/// the `groundingShadows` initializer flag — ``grounding`` by default, ``none`` when the flag
/// is `false` — so existing apps keep their behaviour.
///
/// ```swift
/// ARSceneView(onTapOnPlane: { position, arView in
///     let anchor = AnchorEntity(world: position)
///     anchor.addChild(model.entity.clone(recursive: true))
///     arView.scene.addAnchor(anchor)
/// })
/// .placementShadow(.contact(intensity: shadowIntensity))   // tunable, live
/// ```
public enum ARPlacementShadow: Sendable, Equatable {
    /// RealityKit's `GroundingShadowComponent(castsShadow: true)` on every placed model entity:
    /// a real shadow cast by the scene lights onto the detected surface. The default.
    case grounding
    /// A procedural ``ContactShadowNode`` under each placed anchor's content, fitted to its
    /// bounds at its lowest point — needs no light and has a tunable peak opacity
    /// (`nil` = the context's ``ContactShadowContext/intensity``). When the anchor holds a
    /// single entity the shadow is added to it, so it follows, turns and hides with the model;
    /// otherwise it goes on the anchor. Content that already carries a contact shadow (a model
    /// built with ``ModelNode/withContactShadow(_:intensity:spread:)``) gets no second one.
    /// Changing the context or intensity on a later render retunes the shadows placed this way.
    case contact(ContactShadowContext = .floor, intensity: Float? = nil)
    /// No automatic shadow; the app grounds its content itself, or not at all.
    case none
}

extension ARSceneView {
    /// Chooses the shadow given to content placed by `onTapOnPlane`. Overrides the
    /// `groundingShadows` initializer flag. See ``ARPlacementShadow``.
    ///
    /// Like `groundingShadows`, it applies to anchors the callback adds to `arView.scene`
    /// *synchronously*; content anchored later (after an async load) must be grounded by the
    /// app — e.g. with ``ModelNode/withContactShadow(_:intensity:spread:)``.
    public func placementShadow(_ style: ARPlacementShadow) -> ARSceneView {
        var copy = self
        copy.placementShadowStyle = style
        return copy
    }
}

extension ARSceneView.Coordinator {
    /// Takes the style resolved by `makeUIView` / `updateUIView`. A contact style retunes the
    /// contact shadows this view placed earlier, so an intensity slider acts live; other
    /// changes only affect later placements.
    @MainActor
    func updatePlacementShadow(_ style: ARPlacementShadow, in arView: ARView) {
        guard style != placementShadow else { return }
        placementShadow = style
        guard case let .contact(context, intensity) = style else { return }
        for anchor in arView.scene.anchors {
            Self.retunePlacementShadows(under: anchor, context: context, intensity: intensity)
        }
    }

    /// Grounds the anchors that joined `arView.scene` after the snapshot with `style`.
    /// Called from `handleTap`, which UIKit runs on the main thread.
    func applyPlacementShadow(
        _ style: ARPlacementShadow,
        in arView: ARView,
        addedSince existing: Set<Entity.ID>
    ) {
        switch style {
        case .grounding:
            applyGroundingShadows(in: arView, addedSince: existing)
        case let .contact(context, intensity):
            MainActor.assumeIsolated {
                for anchor in arView.scene.anchors where !existing.contains(anchor.id) {
                    Self.applyContactShadow(to: anchor, context: context, intensity: intensity)
                }
            }
        case .none:
            break
        }
    }

    /// Adds a fitted contact shadow to `anchor`'s content, unless it has no visible geometry
    /// yet or already carries one anywhere in its subtree (a cloned model built with
    /// `withContactShadow()` holds it one level down). A single child gets the shadow, so it
    /// follows that model when the app moves or hides it; several go under the anchor.
    /// Replacing that child drops its shadow: give the new model one with
    /// `withContactShadow()`, or place it on a new anchor.
    @MainActor
    static func applyContactShadow(
        to anchor: Entity,
        context: ContactShadowContext,
        intensity: Float?
    ) {
        guard !anchor.containsContactShadow,
              anchor.contactShadowFreeBounds(relativeTo: anchor) != nil
        else { return }
        let target = anchor.children.count == 1 ? anchor.children[0] : anchor
        let shadow = ContactShadowNode.under(target, context: context, intensity: intensity)
        shadow.entity.components[ContactShadowComponent.self]?.isPlacementShadow = true
    }

    @MainActor
    private static func retunePlacementShadows(
        under entity: Entity,
        context: ContactShadowContext,
        intensity: Float?
    ) {
        for child in entity.children {
            if let settings = child.components[ContactShadowComponent.self],
               settings.isPlacementShadow,
               let shadow = ContactShadowNode(child) {
                shadow.retune(context: context, intensity: intensity ?? context.intensity)
            } else {
                retunePlacementShadows(under: child, context: context, intensity: intensity)
            }
        }
    }
}
#endif // os(iOS)

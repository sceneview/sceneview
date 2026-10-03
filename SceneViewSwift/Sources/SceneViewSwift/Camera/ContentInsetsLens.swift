#if os(iOS) || os(macOS)
import RealityKit
import simd

/// Swaps a camera entity between its perspective lens and the projective one
/// ``SceneView/contentInsets(_:)`` renders through.
///
/// RealityKit keeps rendering through `PerspectiveCameraComponent` when an
/// entity carries it next to a `ProjectiveTransformCameraComponent`, so only
/// one of the two is ever on the camera: the perspective lens is set aside
/// while a custom projection is in, and put back untouched when it leaves.
struct ContentInsetsLens {
    /// The perspective lens, while the camera renders through a custom
    /// projection. `nil` when the lens is on the camera.
    var shelved: PerspectiveCameraComponent?

    /// Renders `camera` through `matrix`, or through its perspective lens
    /// again when `matrix` is `nil`.
    @MainActor
    mutating func set(_ matrix: simd_float4x4?, on camera: Entity) {
        guard let matrix else {
            camera.components.remove(ProjectiveTransformCameraComponent.self)
            if let lens = shelved {
                shelved = nil
                camera.components.set(lens)
            }
            return
        }
        if shelved == nil, let lens = camera.components[PerspectiveCameraComponent.self] {
            shelved = lens
            camera.components.remove(PerspectiveCameraComponent.self)
        }
        camera.components.set(ProjectiveTransformCameraComponent(projectionMatrix: matrix))
    }
}
#endif

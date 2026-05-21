#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// Procedural geometry node for creating primitive shapes.
///
/// Mirrors SceneView Android's `CubeNode`, `SphereNode`, `CylinderNode`, `PlaneNode` —
/// uses RealityKit's `MeshResource` for mesh generation with PBR materials.
///
/// ```swift
/// SceneView { content in
///     let cube = GeometryNode.cube(size: 0.5, color: .red)
///         .position(.init(x: 0, y: 0.25, z: -2))
///     content.addChild(cube.entity)
///
///     let metalSphere = GeometryNode.sphere(
///         radius: 0.3,
///         material: .pbr(color: .gray, metallic: 1.0, roughness: 0.2)
///     )
///     content.addChild(metalSphere.entity)
/// }
/// ```
public struct GeometryNode: Sendable {
    /// The underlying RealityKit entity.
    public let entity: ModelEntity

    /// World-space position.
    public var position: SIMD3<Float> {
        get { entity.position }
        nonmutating set { entity.position = newValue }
    }

    /// Orientation as a quaternion.
    public var rotation: simd_quatf {
        get { entity.orientation }
        nonmutating set { entity.orientation = newValue }
    }

    /// Scale factor.
    public var scale: SIMD3<Float> {
        get { entity.scale }
        nonmutating set { entity.scale = newValue }
    }

    // MARK: - Default material

    /// Builds the library-default material for a procedurally-created shape.
    ///
    /// The default is a physically-based, matte-neutral material (`metallic: 0`,
    /// `roughness: 0.5`) so that shapes react correctly to image-based lighting
    /// (the HDR environment that `SceneView` wires by default). This is the single
    /// biggest visual-quality jump over RealityKit's unlit-flat `SimpleMaterial`.
    ///
    /// Pass `unlit: true` to opt into a flat-fill `UnlitMaterial` look — useful
    /// for debug visualizations and overlays that should not react to scene
    /// lighting.
    static func defaultMaterial(
        color: SimpleMaterial.Color,
        unlit: Bool = false
    ) -> any RealityKit.Material {
        if unlit {
            // UnlitMaterial renders a flat fill that ignores IBL/scene lighting.
            // SimpleMaterial(isMetallic: false) is still lit, so it would
            // contradict the `unlit` contract.
            return UnlitMaterial(color: color)
        }
        var pbr = PhysicallyBasedMaterial()
        pbr.baseColor = .init(tint: color)
        pbr.metallic = .init(floatLiteral: 0.0)
        pbr.roughness = .init(floatLiteral: 0.5) // matte-neutral default, not glossy
        return pbr
    }

    // MARK: - Cube

    /// Creates a cube (box) geometry.
    ///
    /// - Parameters:
    ///   - size: Edge length in meters.
    ///   - color: Material color tint.
    ///   - cornerRadius: Corner rounding radius. Default 0.
    ///   - unlit: When `true`, uses a flat unlit material instead of the default
    ///     physically-based material. Default `false`.
    /// - Returns: A `GeometryNode` containing a box mesh.
    public static func cube(
        size: Float = 1.0,
        color: SimpleMaterial.Color = .white,
        cornerRadius: Float = 0,
        unlit: Bool = false
    ) -> GeometryNode {
        let mesh = MeshResource.generateBox(
            size: size,
            cornerRadius: cornerRadius
        )
        let material = defaultMaterial(color: color, unlit: unlit)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        entity.generateCollisionShapes(recursive: false)
        return GeometryNode(entity: entity)
    }

    /// Creates a cube with PBR material.
    public static func cube(
        size: Float = 1.0,
        material: GeometryMaterial,
        cornerRadius: Float = 0
    ) -> GeometryNode {
        let mesh = MeshResource.generateBox(
            size: size,
            cornerRadius: cornerRadius
        )
        let entity = ModelEntity(mesh: mesh, materials: [material.rkMaterial])
        entity.generateCollisionShapes(recursive: false)
        return GeometryNode(entity: entity)
    }

    // MARK: - Sphere

    /// Creates a sphere geometry.
    ///
    /// - Parameters:
    ///   - radius: Sphere radius in meters.
    ///   - color: Material color tint.
    ///   - unlit: When `true`, uses a flat unlit material instead of the default
    ///     physically-based material. Default `false`.
    /// - Returns: A `GeometryNode` containing a sphere mesh.
    public static func sphere(
        radius: Float = 0.5,
        color: SimpleMaterial.Color = .white,
        unlit: Bool = false
    ) -> GeometryNode {
        let mesh = MeshResource.generateSphere(radius: radius)
        let material = defaultMaterial(color: color, unlit: unlit)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        entity.generateCollisionShapes(recursive: false)
        return GeometryNode(entity: entity)
    }

    /// Creates a sphere with PBR material.
    public static func sphere(
        radius: Float = 0.5,
        material: GeometryMaterial
    ) -> GeometryNode {
        let mesh = MeshResource.generateSphere(radius: radius)
        let entity = ModelEntity(mesh: mesh, materials: [material.rkMaterial])
        entity.generateCollisionShapes(recursive: false)
        return GeometryNode(entity: entity)
    }

    // MARK: - Cylinder

    /// Creates a cylinder geometry.
    ///
    /// - Parameters:
    ///   - radius: Cylinder radius in meters.
    ///   - height: Cylinder height in meters.
    ///   - color: Material color tint.
    ///   - unlit: When `true`, uses a flat unlit material instead of the default
    ///     physically-based material. Default `false`.
    /// - Returns: A `GeometryNode` containing a cylinder mesh.
    public static func cylinder(
        radius: Float = 0.5,
        height: Float = 1.0,
        color: SimpleMaterial.Color = .white,
        unlit: Bool = false
    ) -> GeometryNode {
        let mesh = MeshResource.generateCylinder(
            height: height,
            radius: radius
        )
        let material = defaultMaterial(color: color, unlit: unlit)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        entity.generateCollisionShapes(recursive: false)
        return GeometryNode(entity: entity)
    }

    // MARK: - Plane

    /// Creates a plane geometry.
    ///
    /// - Parameters:
    ///   - width: Plane width in meters.
    ///   - depth: Plane depth in meters.
    ///   - color: Material color tint.
    ///   - unlit: When `true`, uses a flat unlit material instead of the default
    ///     physically-based material. Default `false`.
    /// - Returns: A `GeometryNode` containing a plane mesh.
    public static func plane(
        width: Float = 1.0,
        depth: Float = 1.0,
        color: SimpleMaterial.Color = .white,
        unlit: Bool = false
    ) -> GeometryNode {
        let mesh = MeshResource.generatePlane(width: width, depth: depth)
        let material = defaultMaterial(color: color, unlit: unlit)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        entity.generateCollisionShapes(recursive: false)
        return GeometryNode(entity: entity)
    }

    // MARK: - Cone

    /// Creates a cone geometry using `MeshResource.generateCone(height:radius:)`.
    ///
    /// - Parameters:
    ///   - height: Cone height in meters.
    ///   - radius: Base radius in meters.
    ///   - color: Material color tint.
    ///   - unlit: When `true`, uses a flat unlit material instead of the default
    ///     physically-based material. Default `false`.
    /// - Returns: A `GeometryNode` containing a cone mesh.
    public static func cone(
        height: Float = 1.0,
        radius: Float = 0.5,
        color: SimpleMaterial.Color = .white,
        unlit: Bool = false
    ) -> GeometryNode {
        let mesh = MeshResource.generateCone(height: height, radius: radius)
        let material = defaultMaterial(color: color, unlit: unlit)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        entity.generateCollisionShapes(recursive: false)
        return GeometryNode(entity: entity)
    }

    // MARK: - Torus

    /// Creates a torus geometry by procedurally generating the mesh.
    ///
    /// - Parameters:
    ///   - majorRadius: Distance from the center of the torus to the center of the tube.
    ///   - minorRadius: Radius of the tube itself.
    ///   - majorSegments: Number of segments around the main ring. Default 48.
    ///   - minorSegments: Number of segments around the tube cross-section. Default 24.
    ///   - color: Material color tint.
    ///   - unlit: When `true`, uses a flat unlit material instead of the default
    ///     physically-based material. Default `false`.
    /// - Returns: A `GeometryNode` containing a torus mesh.
    public static func torus(
        majorRadius: Float = 0.4,
        minorRadius: Float = 0.15,
        majorSegments: Int = 48,
        minorSegments: Int = 24,
        color: SimpleMaterial.Color = .white,
        unlit: Bool = false
    ) -> GeometryNode {
        let mesh = generateTorusMesh(
            majorRadius: majorRadius,
            minorRadius: minorRadius,
            majorSegments: majorSegments,
            minorSegments: minorSegments
        )
        let material = defaultMaterial(color: color, unlit: unlit)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        entity.generateCollisionShapes(recursive: false)
        return GeometryNode(entity: entity)
    }

    /// Creates a torus with PBR material.
    public static func torus(
        majorRadius: Float = 0.4,
        minorRadius: Float = 0.15,
        majorSegments: Int = 48,
        minorSegments: Int = 24,
        material: GeometryMaterial
    ) -> GeometryNode {
        let mesh = generateTorusMesh(
            majorRadius: majorRadius,
            minorRadius: minorRadius,
            majorSegments: majorSegments,
            minorSegments: minorSegments
        )
        let entity = ModelEntity(mesh: mesh, materials: [material.rkMaterial])
        entity.generateCollisionShapes(recursive: false)
        return GeometryNode(entity: entity)
    }

    // MARK: - Capsule

    /// Creates a capsule geometry (cylinder with hemisphere caps).
    ///
    /// - Parameters:
    ///   - radius: Radius of the capsule (cylinder and hemisphere caps).
    ///   - height: Total height including the two hemisphere caps.
    ///   - capSegments: Number of vertical segments per hemisphere cap. Default 12.
    ///   - radialSegments: Number of segments around the circumference. Default 24.
    ///   - color: Material color tint.
    ///   - unlit: When `true`, uses a flat unlit material instead of the default
    ///     physically-based material. Default `false`.
    /// - Returns: A `GeometryNode` containing a capsule mesh.
    public static func capsule(
        radius: Float = 0.25,
        height: Float = 1.0,
        capSegments: Int = 12,
        radialSegments: Int = 24,
        color: SimpleMaterial.Color = .white,
        unlit: Bool = false
    ) -> GeometryNode {
        let mesh = generateCapsuleMesh(
            radius: radius,
            height: height,
            capSegments: capSegments,
            radialSegments: radialSegments
        )
        let material = defaultMaterial(color: color, unlit: unlit)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        entity.generateCollisionShapes(recursive: false)
        return GeometryNode(entity: entity)
    }

    /// Creates a capsule with PBR material.
    public static func capsule(
        radius: Float = 0.25,
        height: Float = 1.0,
        capSegments: Int = 12,
        radialSegments: Int = 24,
        material: GeometryMaterial
    ) -> GeometryNode {
        let mesh = generateCapsuleMesh(
            radius: radius,
            height: height,
            capSegments: capSegments,
            radialSegments: radialSegments
        )
        let entity = ModelEntity(mesh: mesh, materials: [material.rkMaterial])
        entity.generateCollisionShapes(recursive: false)
        return GeometryNode(entity: entity)
    }

    // MARK: - Transform helpers

    /// Returns self positioned at the given coordinates.
    @discardableResult
    public func position(_ position: SIMD3<Float>) -> GeometryNode {
        entity.position = position
        return self
    }

    /// Returns self scaled uniformly.
    @discardableResult
    public func scale(_ uniform: Float) -> GeometryNode {
        entity.scale = .init(repeating: uniform)
        return self
    }

    /// Returns self rotated by the given quaternion.
    @discardableResult
    public func rotation(_ rotation: simd_quatf) -> GeometryNode {
        entity.orientation = rotation
        return self
    }

    /// Returns self rotated by angle around axis.
    @discardableResult
    public func rotation(angle: Float, axis: SIMD3<Float>) -> GeometryNode {
        entity.orientation = simd_quatf(angle: angle, axis: axis)
        return self
    }

    /// Returns self with a grounding shadow.
    @discardableResult
    public func withGroundingShadow() -> GeometryNode {
        if #available(iOS 18.0, visionOS 2.0, *) {
            entity.components.set(GroundingShadowComponent(castsShadow: true))
        }
        return self
    }

    // MARK: - Mesh generation (private)

    /// Generates a torus mesh using parametric surface equations.
    private static func generateTorusMesh(
        majorRadius: Float,
        minorRadius: Float,
        majorSegments: Int,
        minorSegments: Int
    ) -> MeshResource {
        var positions: [SIMD3<Float>] = []
        var normals: [SIMD3<Float>] = []
        var uvs: [SIMD2<Float>] = []
        var indices: [UInt32] = []

        for i in 0...majorSegments {
            let u = Float(i) / Float(majorSegments) * 2 * .pi
            let cosU = cos(u)
            let sinU = sin(u)

            for j in 0...minorSegments {
                let v = Float(j) / Float(minorSegments) * 2 * .pi
                let cosV = cos(v)
                let sinV = sin(v)

                let x = (majorRadius + minorRadius * cosV) * cosU
                let y = minorRadius * sinV
                let z = (majorRadius + minorRadius * cosV) * sinU

                positions.append(SIMD3<Float>(x, y, z))

                let nx = cosV * cosU
                let ny = sinV
                let nz = cosV * sinU
                normals.append(SIMD3<Float>(nx, ny, nz))

                uvs.append(SIMD2<Float>(
                    Float(i) / Float(majorSegments),
                    Float(j) / Float(minorSegments)
                ))
            }
        }

        let stride = minorSegments + 1
        for i in 0..<majorSegments {
            for j in 0..<minorSegments {
                let a = UInt32(i * stride + j)
                let b = UInt32(i * stride + j + 1)
                let c = UInt32((i + 1) * stride + j + 1)
                let d = UInt32((i + 1) * stride + j)

                indices.append(contentsOf: [a, b, c, a, c, d])
            }
        }

        var descriptor = MeshDescriptor(name: "GeometryNode_Torus")
        descriptor.positions = MeshBuffers.Positions(positions)
        descriptor.normals = MeshBuffers.Normals(normals)
        descriptor.textureCoordinates = MeshBuffers.TextureCoordinates(uvs)
        descriptor.primitives = .triangles(indices)

        return (try? MeshResource.generate(from: [descriptor]))
            ?? MeshResource.generateSphere(radius: majorRadius)
    }

    /// Generates a capsule mesh (cylinder body + two hemisphere caps).
    private static func generateCapsuleMesh(
        radius: Float,
        height: Float,
        capSegments: Int,
        radialSegments: Int
    ) -> MeshResource {
        let cylinderHeight = max(height - 2 * radius, 0)
        let halfCylinder = cylinderHeight / 2

        var positions: [SIMD3<Float>] = []
        var normals: [SIMD3<Float>] = []
        var uvs: [SIMD2<Float>] = []
        var indices: [UInt32] = []

        // Top hemisphere cap
        for i in 0...capSegments {
            let phi = Float(i) / Float(capSegments) * (.pi / 2) // 0 to pi/2
            let sinPhi = sin(phi)
            let cosPhi = cos(phi)

            for j in 0...radialSegments {
                let theta = Float(j) / Float(radialSegments) * 2 * .pi
                let cosTheta = cos(theta)
                let sinTheta = sin(theta)

                let x = radius * sinPhi * cosTheta
                let y = halfCylinder + radius * cosPhi
                let z = radius * sinPhi * sinTheta

                positions.append(SIMD3<Float>(x, y, z))
                normals.append(SIMD3<Float>(sinPhi * cosTheta, cosPhi, sinPhi * sinTheta))
                uvs.append(SIMD2<Float>(
                    Float(j) / Float(radialSegments),
                    1.0 - Float(i) / Float(capSegments) * 0.25
                ))
            }
        }

        // Cylinder body (2 rings: top and bottom)
        for i in 0...1 {
            let y = i == 0 ? halfCylinder : -halfCylinder
            for j in 0...radialSegments {
                let theta = Float(j) / Float(radialSegments) * 2 * .pi
                let cosTheta = cos(theta)
                let sinTheta = sin(theta)

                positions.append(SIMD3<Float>(radius * cosTheta, y, radius * sinTheta))
                normals.append(SIMD3<Float>(cosTheta, 0, sinTheta))
                uvs.append(SIMD2<Float>(
                    Float(j) / Float(radialSegments),
                    i == 0 ? 0.75 : 0.5
                ))
            }
        }

        // Bottom hemisphere cap
        for i in 0...capSegments {
            let phi = Float(i) / Float(capSegments) * (.pi / 2) // 0 to pi/2
            let sinPhi = sin(phi)
            let cosPhi = cos(phi)

            for j in 0...radialSegments {
                let theta = Float(j) / Float(radialSegments) * 2 * .pi
                let cosTheta = cos(theta)
                let sinTheta = sin(theta)

                let x = radius * sinPhi * cosTheta
                let y = -halfCylinder - radius * cosPhi
                let z = radius * sinPhi * sinTheta

                positions.append(SIMD3<Float>(x, y, z))
                normals.append(SIMD3<Float>(sinPhi * cosTheta, -cosPhi, sinPhi * sinTheta))
                uvs.append(SIMD2<Float>(
                    Float(j) / Float(radialSegments),
                    0.25 - Float(i) / Float(capSegments) * 0.25
                ))
            }
        }

        let stride = radialSegments + 1

        // Top cap indices
        for i in 0..<capSegments {
            for j in 0..<radialSegments {
                let a = UInt32(i * stride + j)
                let b = UInt32(i * stride + j + 1)
                let c = UInt32((i + 1) * stride + j + 1)
                let d = UInt32((i + 1) * stride + j)
                indices.append(contentsOf: [a, c, b, a, d, c])
            }
        }

        // Cylinder body indices
        let cylBase = UInt32((capSegments + 1) * stride)
        for j in 0..<UInt32(radialSegments) {
            let a = cylBase + j
            let b = cylBase + j + 1
            let c = cylBase + UInt32(stride) + j + 1
            let d = cylBase + UInt32(stride) + j
            indices.append(contentsOf: [a, c, b, a, d, c])
        }

        // Bottom cap indices
        let botBase = UInt32((capSegments + 1) * stride + 2 * stride)
        for i in 0..<UInt32(capSegments) {
            for j in 0..<UInt32(radialSegments) {
                let a = botBase + i * UInt32(stride) + j
                let b = botBase + i * UInt32(stride) + j + 1
                let c = botBase + (i + 1) * UInt32(stride) + j + 1
                let d = botBase + (i + 1) * UInt32(stride) + j
                indices.append(contentsOf: [a, b, c, a, c, d])
            }
        }

        var descriptor = MeshDescriptor(name: "GeometryNode_Capsule")
        descriptor.positions = MeshBuffers.Positions(positions)
        descriptor.normals = MeshBuffers.Normals(normals)
        descriptor.textureCoordinates = MeshBuffers.TextureCoordinates(uvs)
        descriptor.primitives = .triangles(indices)

        return (try? MeshResource.generate(from: [descriptor]))
            ?? MeshResource.generateSphere(radius: radius)
    }
}

// MARK: - Material

/// Material configuration for geometry nodes.
///
/// Mirrors SceneView Android's MaterialInstance configuration.
/// Supports simple colors, PBR with texture maps, and unlit materials.
///
/// ```swift
/// // Simple color
/// let red = GeometryMaterial.simple(color: .red)
///
/// // PBR with color
/// let metal = GeometryMaterial.pbr(color: .gray, metallic: 1.0, roughness: 0.2)
///
/// // PBR with textures (load textures first)
/// let textured = GeometryMaterial.textured(
///     baseColor: albedoTexture,
///     normal: normalTexture,
///     metallic: 1.0,
///     roughness: 0.3
/// )
/// ```
public enum GeometryMaterial: @unchecked Sendable {
    /// Simple non-metallic material.
    case simple(color: SimpleMaterial.Color)

    /// Physically-based rendering material with color tint.
    case pbr(
        color: SimpleMaterial.Color,
        metallic: Float = 0.0,
        roughness: Float = 0.5
    )

    /// PBR material with texture maps.
    ///
    /// - Parameters:
    ///   - baseColor: Albedo/diffuse texture.
    ///   - normal: Normal map texture (optional).
    ///   - metallic: Metallic value (0 = dielectric, 1 = metal).
    ///   - roughness: Roughness value (0 = smooth/mirror, 1 = rough/diffuse).
    ///   - tint: Color tint applied on top of the base texture.
    case textured(
        baseColor: TextureResource,
        normal: TextureResource? = nil,
        metallic: Float = 0.0,
        roughness: Float = 0.5,
        tint: SimpleMaterial.Color = .white
    )

    /// Unlit material (no lighting response).
    case unlit(color: SimpleMaterial.Color)

    /// Unlit material with a texture (no lighting response).
    case unlitTextured(texture: TextureResource, tint: SimpleMaterial.Color = .white)

    /// A pre-built RealityKit material (e.g. from ``CustomMaterial`` factories).
    ///
    /// Use with ``CustomMaterial`` factory methods:
    /// ```swift
    /// let glassMat = CustomMaterial.glass(tint: .cyan)
    /// let sphere = GeometryNode.sphere(
    ///     radius: 0.3,
    ///     material: .custom(glassMat)
    /// )
    /// ```
    case custom(any RealityKit.Material)

    var rkMaterial: any RealityKit.Material {
        switch self {
        case .simple(let color):
            return SimpleMaterial(color: color, isMetallic: false)

        case .pbr(let color, let metallic, let roughness):
            var mat = PhysicallyBasedMaterial()
            mat.baseColor = .init(tint: color)
            mat.metallic = .init(floatLiteral: metallic)
            mat.roughness = .init(floatLiteral: roughness)
            return mat

        case .textured(let baseColor, let normal, let metallic, let roughness, let tint):
            var mat = PhysicallyBasedMaterial()
            mat.baseColor = .init(tint: tint, texture: .init(baseColor))
            mat.metallic = .init(floatLiteral: metallic)
            mat.roughness = .init(floatLiteral: roughness)
            if let normal = normal {
                mat.normal = .init(texture: .init(normal))
            }
            return mat

        case .unlit(let color):
            return UnlitMaterial(color: color)

        case .unlitTextured(let texture, let tint):
            var mat = UnlitMaterial()
            mat.color = .init(tint: tint, texture: .init(texture))
            return mat

        case .custom(let material):
            return material
        }
    }
}

// MARK: - Texture loading helpers

extension GeometryMaterial {
    /// Loads a texture from a bundle resource name.
    ///
    /// ```swift
    /// let texture = try await GeometryMaterial.loadTexture("textures/brick_diffuse.png")
    /// let material = GeometryMaterial.textured(baseColor: texture, roughness: 0.8)
    /// ```
    public static func loadTexture(_ name: String) async throws -> TextureResource {
        try await TextureResource(named: name)
    }

    /// Loads a texture from a URL.
    public static func loadTexture(contentsOf url: URL) async throws -> TextureResource {
        try await TextureResource(contentsOf: url)
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)

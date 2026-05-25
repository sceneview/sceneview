// swift-tools-version: 5.10
//
// Root-level Swift Package manifest for the SceneView monorepo.
//
// Why this exists
// ===============
// Pre-#920, the canonical Swift-Package-Manager entry point for SceneView
// was a mirror repo `github.com/sceneview/sceneview-swift` whose contents
// were a manual copy of the `SceneViewSwift/` subtree of this monorepo.
// That mirror got stuck on `v4.0.0` while the monorepo shipped through
// `v4.0.9`, so every iOS consumer following the README was silently 9
// versions behind. The mirror is now archived (read-only) in favour of
// SPM consumers pointing at this monorepo directly.
//
// This file makes the monorepo itself a valid SPM root. The single
// product `SceneViewSwift` points at `SceneViewSwift/Sources/SceneViewSwift`
// via the `path` parameter, so Xcode users can now do:
//
//     .package(url: "https://github.com/sceneview/sceneview", from: "4.15.2")
//
// and pin to any tag the monorepo has cut, no manual mirroring required.
// The `SceneViewSwift/Package.swift` sub-manifest is left in place for
// internal use by `swift test --package-path SceneViewSwift` and for any
// pre-archive consumer still resolving against the mirror's frozen v4.0.0.

import PackageDescription

let package = Package(
    name: "SceneViewSwift",
    platforms: [
        .iOS("18.0"),
        .macOS("15.0"),
        .visionOS(.v1)
    ],
    products: [
        .library(
            name: "SceneViewSwift",
            targets: ["SceneViewSwift"]
        )
    ],
    dependencies: [],
    targets: [
        .target(
            name: "SceneViewSwift",
            dependencies: [],
            // Same sources the sub-manifest declares — single source of truth
            // lives in `SceneViewSwift/Sources/SceneViewSwift/`; this just
            // surfaces it at the monorepo root for SPM consumers.
            path: "SceneViewSwift/Sources/SceneViewSwift"
        ),
        .testTarget(
            name: "SceneViewSwiftTests",
            dependencies: ["SceneViewSwift"],
            path: "SceneViewSwift/Tests/SceneViewSwiftTests"
        )
    ]
)

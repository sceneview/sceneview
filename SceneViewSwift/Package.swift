// swift-tools-version: 5.10

import PackageDescription

let package = Package(
    name: "SceneViewSwift",
    platforms: [
        .iOS("18.0"),
        .macOS("15.0"),
        // visionOS 2.0: RealityKit's light entities (DirectionalLight,
        // PointLight, SpotLight) and the per-entity `shadow` API are all
        // `@available(visionOS 2.0, *)`. The 3D node layer depends on them,
        // so v1 cannot build the target. Closes #1366.
        .visionOS("2.0")
    ],
    products: [
        .library(
            name: "SceneViewSwift",
            targets: ["SceneViewSwift"]
        ),
        // Monocular depth on Core ML (Depth Anything V2 Small). Separate so
        // the base package stays model-free: the 25.4 MB weights are fetched at
        // runtime by `DepthModelStore`, never bundled.
        .library(
            name: "SceneViewDepthML",
            targets: ["SceneViewDepthML"]
        )
    ],
    dependencies: [
        // DocC generation — `swift package generate-documentation --target SceneViewSwift`
        // produces a browsable .doccarchive. CI publishes it alongside the SPM tag so
        // Apple-side consumers get a real Apple-style docs site instead of just KDoc.
        // (#945)
        .package(url: "https://github.com/apple/swift-docc-plugin", from: "1.4.0")
    ],
    targets: [
        .target(
            name: "SceneViewSwift",
            dependencies: [],
            path: "Sources/SceneViewSwift"
        ),
        .target(
            name: "SceneViewDepthML",
            dependencies: ["SceneViewSwift"],
            path: "Sources/SceneViewDepthML",
            exclude: ["NOTICE.md"]
        ),
        .testTarget(
            name: "SceneViewSwiftTests",
            dependencies: ["SceneViewSwift"],
            path: "Tests/SceneViewSwiftTests",
            resources: [.copy("Resources/ml-depth-fit-vectors.json")]
        ),
        // DepthModelStore and DepthAnythingV2Estimator against a tiny model
        // built in the test and served by a stub URLProtocol: no network.
        .testTarget(
            name: "SceneViewDepthMLTests",
            dependencies: ["SceneViewDepthML", "SceneViewSwift"],
            path: "Tests/SceneViewDepthMLTests"
        )
    ]
)

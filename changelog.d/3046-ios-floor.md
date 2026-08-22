<!-- category: Fixed -->
<!-- breaking: false -->
Every statement of the Apple platform floor now matches `SceneViewSwift/Package.swift`
(iOS 18 / macOS 15 / visionOS 2). The floor moved in #719 but `llms.txt` on the website,
`docs.html`, `structured-data.json`, the Copilot instructions, the MCP setup guides, the
demo's About screen and the visionOS badges kept promising iOS 17 / macOS 14 /
visionOS 1, and two pages still asked for Xcode 15 although Swift 6 and the iOS 18 target
need Xcode 16. The root `Package.swift` also declared `.visionOS(.v1)` for a target whose
light and shadow APIs need visionOS 2. A new `.claude/scripts/check-ios-floor.sh` compares
the podspecs, both manifests and the user-facing docs against the sub-manifest and exits 1
on drift.

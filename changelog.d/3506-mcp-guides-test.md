<!-- category: Fixed -->
- **The MCP roadmap no longer advertises a version that already shipped
  ([#3506](https://github.com/sceneview/sceneview/pull/3506)).**
  `get_platform_roadmap` returned an "Upcoming" section still promising `v4.0.0` —
  SceneViewSwift stabilization, Android XR, the Flutter and React Native bridges — while
  4.34.0 is published, so every host that asked the server what was coming next was told
  the current major line was still ahead of it. The section now names the workstreams
  without a version number, and says that `5.0.0` is a deliberate milestone rather than
  an automatic bump.

<!-- category: Added -->
- **`mcp/src/guides.ts` is under test ([#3506](https://github.com/sceneview/sceneview/pull/3506)).**
  It was the last substantial module in `mcp/src/` with no test file: 655 lines of static
  content that four tools return verbatim, with nothing pinning it. 18 cases now cover
  the `BEST_PRACTICES` key set and the guarantee that `all` still contains every topic
  body, the absence of uninterpolated module constants, every Maven and SPM coordinate
  carrying `LATEST_SCENEVIEW_RELEASE` instead of a literal, the platform rows and the
  Filament/RealityKit split, balanced code fences, and the Upcoming-version rule that
  caught the rot above.

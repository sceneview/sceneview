- **CI:** iOS App Store review submission failed on **4.24.0** and **4.25.0** with HTTP 409
  `ENTITY_ERROR.RELATIONSHIP.INVALID` ("The specified build has a different platform than the
  version"). The `deploy-ios` job's submit step selected "the latest VALID build" with no
  platform filter, so — because the iOS and macOS deploy jobs run in **parallel** against the
  **same** App Store record (shared bundleId → shared app_id) — it could attach the **macOS**
  build to the iOS version. The build lookup now resolves each build's platform via the
  included `preReleaseVersion` and selects the iOS build (the build-side twin of the #2731
  version-hijack fix, which only filtered the *version* lookup). (#2731)

<!-- category: Fixed -->

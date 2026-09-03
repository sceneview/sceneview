<!-- category: Fixed -->
<!-- breaking: false -->
`:samples:android-demo:testReleaseUnitTest` failed 13 tests on `main`
(`DemoScaffoldTopBandTest`, `DemoScaffoldBottomBandTest`, `DemoBackgroundRoleTest`) while
the exact same tests passed in the `debug` variant, and nothing in CI ever ran the release
variant to notice — the demo's JVM tests only reached CI as a dependency of
`verifyRoborazziDebug`, which is `debug`-only.

The cause was a dependency scope, not application behaviour. Every failing test hosts a
composable through `createComposeRule()`, which launches a bare
`androidx.activity.ComponentActivity` by explicit component name. That activity's manifest
entry came from `androidx.compose.ui:ui-test-manifest` on `debugImplementation` — correct
per that artifact's own guidance ("never let it reach a shipped APK"), but it also meant the
entry only reached the `debug` variant's manifest and resource-link pipeline.
`testReleaseUnitTest` links its unit-test resource package from the `release` variant's own
implementation graph, which a `debugImplementation` dependency never touches, so every
Robolectric-hosted compose-rule test in the release variant died with "Unable to resolve
activity ... ComponentActivity".

Fixed by declaring the same `ComponentActivity` manifest entry directly in
`samples/android-demo/src/main/AndroidManifest.xml` instead of pulling it in from
`ui-test-manifest` — it has no launcher intent-filter and nothing in the app calls it, so it
ships as an inert, unreachable entry in every build type and now resolves for both unit-test
variants. `:samples:android-demo:testReleaseUnitTest` is also now wired into the `Unit
tests` CI job (same `android` path gate as the existing debug suite), so this variant has
real coverage going forward instead of sitting invisible on `main`.

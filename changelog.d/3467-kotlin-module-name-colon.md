<!-- category: Fixed -->
- **The demo's release bundle builds again under AGP 9; the Play Internal deploy on
  `main` was broken since the AGP 9 move
  ([#3467](https://github.com/sceneview/sceneview/issues/3467)).**
  `:samples:android-demo:buildReleasePreBundle` failed with `Entry name contains invalid
  characters: root/META-INF/SceneView:sceneview_release.kotlin_module`. Kotlin Gradle
  plugin 2.4.10 names every JVM/Android compilation `<project.group>:<project.name>` (plus
  `_<variant>` for Android variants), and the default Gradle group of a subproject is the
  root project's name — so every module in this build compiled as `SceneView:<module>`,
  with a colon in the Kotlin module name. The compiler writes the `.kotlin_module` file
  under a sanitised name (`SceneView_sceneview_release`), which is why debug APKs, the
  AARs and `assembleDebug` were all green; but R8, on the minified release build, re-emits
  that resource under the raw module name, and AGP 9's bundle packaging validates zip entry
  names and rejects the colon. The app's existing `META-INF/*.kotlin_module` packaging
  exclude is not a fallback: AGP 9 does not apply it on the bundle path at all — the AAB
  built with this fix still carries all 19 `.kotlin_module` entries, so excluding the
  resource could never have made the entry legal.

  The fix is at the root: the root `build.gradle` now sets a colon-free Kotlin module name
  on every JVM/Android compilation of every subproject — `<project.name>` for `main`,
  `<project.name>_<compilation>` otherwise, i.e. `sceneview_release`, `sceneview-core`,
  `sceneview-compose`, `android-demo_release` — which is the name KGP used before 2.4. It
  covers `kotlin-android` modules and the multiplatform ones (`androidTarget()`, AGP 9's
  `androidLibrary { }` and `jvm()` targets) alike; Kotlin/Native and Kotlin/JS compilations
  are untouched. The published `.api` dumps are unchanged (`apiCheck` passes).

  `:samples:android-demo:bundleRelease` — the exact task the Play deploys run — joins the
  local verification list for build-system changes alongside the `assembleDebug` /
  `testReleaseUnitTest` / `lintDebug` set recorded for the AGP 9 move
  ([#3440](https://github.com/sceneview/sceneview/issues/3440)), and CI's `build` job now
  builds the demo's release bundle whenever a PR touches the build system (`gradle/**`,
  `build.gradle*`, `settings.gradle*`, `gradle.properties`, the wrapper), so this gap
  cannot reopen silently.

<!-- category: Changed -->
- **Every Android module now compiles against SDK 37, and the `okhttp` bump it blocked has
  landed ([#3385](https://github.com/sceneview/sceneview/issues/3385)).** `okhttp-android`
  5.5.0 raised its own compile-SDK floor to 37, so `checkDebugAarMetadata` failed the build
  and pinned the SDK at 5.4.0. `compileSdk` moves 36 → 37 in `sceneview`, `arsceneview`,
  `sceneview-compose`, `samples/common`, `samples/android-demo`, `samples/android-tv-demo`
  and `tools/snippets-check`, and 35 → 37 in the Flutter (`flutter/sceneview_flutter/android`)
  and React Native (`react-native/react-native-sceneview/android`) bridges, whose host demo
  apps follow so a plugin never compiles against a higher SDK than the app embedding it.
  `targetSdk` is deliberately unchanged at 36: the AAR-metadata floor is a *compile* floor,
  and moving `targetSdk` opts the Play Store app into API 37 runtime behaviour changes that
  need their own device QA. AGP stays at 8.13.2, which was tested up to 36, so
  `android.suppressUnsupportedCompileSdk=37.0` documents the gap in each of the four
  independent builds' `gradle.properties` — remove it with the AGP upgrade.
<!-- RELEASE NOTE (maintainer-only):
     compileSdk 37 unblocks okhttp 5.5.0 (#3345) and nothing else. navigation-compose 2.10.0
     (#3416) and Compose Multiplatform 1.12.0 (#3418) fail the same task for a DIFFERENT
     reason — their AAR metadata demands "Android Gradle plugin 9.1.0 or higher", verified
     locally on this branch. Both stay blocked until AGP moves to 9.1+, which is a separate
     migration (the repo is on 8.13.2 and AGP 9 is a major). The `androidx.compose.material3`
     ignore rule in .github/dependabot.yml stays for the same reason: its "AGP 9.1+ /
     compileSdk 37+" note now has only its compileSdk half satisfied. -->

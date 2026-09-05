<!-- category: Fixed -->
- **The Flutter demo's `pubspec.lock` now records `flutter_sceneview` at the plugin's current version
  ([#3462](https://github.com/sceneview/sceneview/issues/3462)).** The lockfile still pinned the
  path-based plugin entry at 4.31.0 while `flutter/sceneview_flutter/pubspec.yaml` had moved on, so
  a fresh clone's first `flutter pub get` rewrote a tracked file before any code was touched. The
  lockfile stays committed (the Flutter team's recommendation for applications); the demo README now
  says it is refreshed by `flutter pub get` and never hand-edited.

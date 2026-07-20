<!-- category: Fixed -->
- CI: backfilled the `sceneview_flutter` CHANGELOG entries for 4.23.0 and 4.24.0 — `pubspec.yaml` was bumped to 4.24.0 with no matching CHANGELOG entry, so the pub.dev publish preflight (`flutter pub publish --dry-run`, #2735) failed the `Build flutter-demo APK` job on every non-path-gated PR and nightly run (#2775).

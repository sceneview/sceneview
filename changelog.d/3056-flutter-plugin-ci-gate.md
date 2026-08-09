<!-- category: Tests -->
- CI now runs `flutter analyze` and `flutter test` against the published
  `flutter_sceneview` package itself. Previously every check in the
  `flutter-demo` job except the pub.dev publish dry-run ran in
  `samples/flutter-demo`, so the package's `lib/` and `test/` trees were
  analyzed by nothing and its 18 Dart unit tests were run by nothing — an
  analyzer error in the code shipped to pub.dev could reach `main` unnoticed.
  The job is renamed `Flutter plugin + demo APK` to match what it now covers.

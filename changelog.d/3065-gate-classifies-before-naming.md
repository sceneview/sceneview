<!-- category: Fixed -->
- **Quality gate:** `pre-push-check.sh` no longer announces a cause it did not
  establish. A Gradle step that dies because the host is not set up (no
  `local.properties` / `sdk.dir` / `ANDROID_HOME`, missing SDK package or NDK,
  unusable JDK) now reports `⚠ … did NOT run`, prints the exact one-line fix and
  counts as an *incomplete* gate — instead of claiming the public API "drifted"
  and prescribing `./gradlew apiDump`, a remedy that would have committed a bogus
  `.api` diff. `apiCheck` additionally requires a positive comparison cue from the
  Kotlin binary-compatibility validator, so a build that dies inside `apiBuild` is
  reported as "not compared", never as a drift. The same rule now covers the
  non-Gradle checkers (demo assets, skill drift, gpt knowledge, vendored chain,
  runner routing) via `script_report_failure`.

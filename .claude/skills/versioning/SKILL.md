---
name: versioning
description: The complete version location map — 30+ files across Android gradle.properties, npm, Flutter, docs, website, samples and Swift — plus the two INDEPENDENT tracks that must never be synced to VERSION_NAME: the Flutter/RN consumed Maven dependency (must LAG to the last released version) and sceneview-mcp on npm. Use when bumping the version, running sync-versions.sh, releasing, or republishing the MCP.
---

## Version Location Map

**Source of truth:** `gradle.properties` -> `VERSION_NAME=X.Y.Z`

Every file below MUST be updated when bumping the version. Use `/version-bump` or `bash .claude/scripts/sync-versions.sh --fix`.

| Category | File | Pattern |
|---|---|---|
| **Android** | `gradle.properties` (root) | `VERSION_NAME=X.Y.Z` |
| | `sceneview/gradle.properties` | `VERSION_NAME=X.Y.Z` |
| | `arsceneview/gradle.properties` | `VERSION_NAME=X.Y.Z` |
| | `sceneview-core/gradle.properties` | `VERSION_NAME=X.Y.Z` |
| **npm** | `sceneview-web/package.json` | `"version": "X.Y.Z"` |
| | `react-native/react-native-sceneview/package.json` | `"version": "X.Y.Z"` |
| **Flutter** | `flutter/sceneview_flutter/pubspec.yaml` | `version: X.Y.Z` |
| | `flutter/.../android/build.gradle` | `version 'X.Y.Z'` |
| | `flutter/.../ios/flutter_sceneview.podspec` | `s.version = 'X.Y.Z'` |
| **Docs** | `llms.txt` | `io.github.sceneview:sceneview:X.Y.Z` |
| | `README.md` | install snippets |
| | `CLAUDE.md` | code examples section |
| | `docs/docs/index.md` | install snippets |
| | `docs/docs/quickstart.md` | dependency snippets |
| | `docs/docs/llms-full.txt` | artifact versions |
| | `docs/docs/cheatsheet.md` | install snippets |
| | `docs/docs/platforms.md` | install line |
| | `docs/docs/android-xr.md` | install snippets |
| | `docs/docs/migration.md` | "upgrade to" version |
| | `gpt/knowledge-*.md` (×4) | GENERATED from `llms.txt` — `node tools/generate-gpt-knowledge.js`, never hand-edit; `sync-versions.sh --fix` regenerates (#2724) |
| **Website** | `website-static/index.html` | softwareVersion, badge, code |
| | `sceneview.github.io/index.html` | deployed version (separate repo) |
| **Samples** | `samples/android-demo/build.gradle` | versionName default |
| | `samples/ios-demo/SceneViewDemo.xcodeproj/project.pbxproj` | `MARKETING_VERSION = X.Y.Z` (iOS + macOS App Store marketing version — the `SceneViewDemo` app target, both Debug & Release configs; the test target's `1.0` is a placeholder, leave it) |
| | `sceneview/Module.md` | version ref |
| **Swift** | `SceneViewSwift/` uses git tag `vX.Y.Z` | not a file version |

> ⚠️ **Do NOT bump the Flutter/RN plugins' *consumed* SceneView dependency.**
> The `io.github.sceneview:(ar)sceneview:X.Y.Z` lines in
> `flutter/.../android/build.gradle` and
> `react-native/.../android/build.gradle.kts` are dependencies on the
> **published** Maven Central artifact — they must lag to the **last released**
> version and cannot point at the in-flight release (it isn't on Maven Central
> yet; pointing at it breaks the `Build flutter-demo APK` CI check). Only the
> plugins' OWN package versions (`version 'X.Y.Z'`, `pubspec.yaml`, podspec,
> `package.json`) bump to the release version. `sync-versions.sh` reports these
> consumed-dep coordinates WARN-only and never auto-bumps them (issue #1494).

> ⚠️ **`mcp/package.json` and `mcp/src/index.ts` follow an INDEPENDENT version
> track — do NOT sync them to `VERSION_NAME`.**
> `sceneview-mcp` (npm) has its own release cadence (e.g. `4.0.12` while the
> SDK is at `4.10.0`) and is published independently of the Maven Central
> artifacts. `sync-versions.sh` deliberately **excludes** `mcp/package.json`
> from the version check — forcing them to match once caused a regression
> where the sync agent downgraded `mcp/package.json` behind the published npm
> `@next` tag. When releasing `sceneview-mcp`, bump these two files to the
> next *MCP* version, never to the SDK `VERSION_NAME` (issue #1705).
>
> **Republishing the MCP without a full SDK release.** Because the MCP is not
> tied to a `v*` tag, `mcp/` changes between releases leave npm stale (it once
> rotted a month behind at 4.0.12). To ship the MCP on demand, bump
> `mcp/package.json` + `mcp/package-lock.json` by a patch (the generated
> `mcp/src/generated/version.ts` is refreshed by `npm run prepare` **only when the
> MCP itself is (re)built** — a plain SDK-only release bumps `gradle.properties`
> without touching `mcp/`, so it does NOT run that lifecycle and `version.ts` went
> stale at v4.25.0; `sync-versions.sh` therefore independently checks its
> `LATEST_SCENEVIEW_RELEASE` against `VERSION_NAME` (CRITICAL) and regenerates it in
> `--fix` — that is the real backstop on the release path, #2906),
> land it on `main`, then dispatch the **`mcp-publish.yml`** workflow
> (`gh workflow run mcp-publish.yml -R sceneview/sceneview --ref main`). It
> mirrors `release.yml`'s `publish-mcp` job (same `NPM_TOKEN`, build/test/publish)
> and is idempotent — re-dispatch on an already-published version is a clean
> no-op. `maintenance.yml`'s `mcp-npm-freshness` job WARNs daily if npm lags the
> local `mcp/package.json`. `/release` Step 3.5 covers this in the release flow.

**Automation:**
- `bash .claude/scripts/sync-versions.sh` — checks all 30+ locations
- `bash .claude/scripts/sync-versions.sh --fix` — auto-fixes mismatches
- Claude Code plugin marketplace lives in [`sceneview/claude-marketplace`](https://github.com/sceneview/claude-marketplace) — run `bash scripts/sync-plugin-versions.sh` from THAT repo
- `bash .claude/scripts/quality-gate.sh` — full pre-push quality gate
- `bash .claude/scripts/cross-platform-check.sh` — API parity across platforms
- `bash .claude/scripts/release-checklist.sh` — pre-release validation

---


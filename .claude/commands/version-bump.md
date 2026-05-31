---
description: Coordinated version update across all 30+ platform-specific files (Android, iOS, Web, Flutter, RN, docs, website).
---

# /version-bump — Coordinated version update across all platforms

Bump the SceneView version across ALL locations in a single, atomic operation.

> ⛔ **What is NOT bumped here.** `sceneview-mcp` (`mcp/package.json`, `mcp/src/index.ts`)
> is on an **independent npm track** — `sync-versions.sh` excludes it; bump it only when
> releasing the MCP, never to the SDK `VERSION_NAME` (#1705). The flutter/RN **consumed**
> `io.github.sceneview:*` dependency lines lag to the **last published** release, never the
> in-flight one — `sync-versions.sh` reports them WARN-only and never auto-bumps (#1494).

**Usage:** `/version-bump 3.6.0` or just `/version-bump` (will ask for version)

---

## Complete Version Location Map (30+ files)

### Source of truth
- `gradle.properties` -> `VERSION_NAME=X.Y.Z`

### Android modules (must match root exactly)
- `sceneview/gradle.properties` -> `VERSION_NAME=`
- `arsceneview/gradle.properties` -> `VERSION_NAME=`
- `sceneview-core/gradle.properties` -> `VERSION_NAME=`

### npm packages
- ⛔ `mcp/package.json` / `mcp/src/index.ts` — **NOT bumped here** (independent MCP track, excluded by `sync-versions.sh`, #1705)
- `sceneview-web/package.json` -> `"version": "X.Y.Z"`
- `react-native/react-native-sceneview/package.json` -> `"version": "X.Y.Z"`

### Flutter (3 files!)
- `flutter/sceneview_flutter/pubspec.yaml` -> `version: X.Y.Z`
- `flutter/sceneview_flutter/android/build.gradle` -> `version 'X.Y.Z'`
- `flutter/sceneview_flutter/ios/sceneview_flutter.podspec` -> `s.version = 'X.Y.Z'`

### Swift Package (uses git tags, not file version)
- `SceneViewSwift/` — version is the git tag `vX.Y.Z`
- `SceneViewSwift/README.md` — SPM version reference

### Documentation (artifact version references)
- `llms.txt` — `io.github.sceneview:sceneview:X.Y.Z` (multiple occurrences)
- `README.md` — install snippets
- `CLAUDE.md` — code examples section (`io.github.sceneview:sceneview:X.Y.Z`)
- `sceneview/Module.md` — version reference
- `arsceneview/Module.md` — version reference

### Docs site (MkDocs) — all with Maven artifact refs
- `docs/docs/index.md` — install snippets, badge
- `docs/docs/quickstart.md` — dependency snippets
- `docs/docs/llms-full.txt` — full API ref versions
- `docs/docs/cheatsheet.md` — install snippets
- `docs/docs/platforms.md` — install line
- `docs/docs/migration.md` — "upgrade to" version
- `docs/docs/android-xr.md` — install snippets
- `docs/docs/codelabs/codelab-ar-compose.md` — dependency snippets

### Website (sceneview.github.io repo AND website-static/ in this repo)
- `website-static/index.html` — softwareVersion JSON-LD, hero badge, code snippets
- Deployed: `../sceneview.github.io/index.html` — same content

### Demo apps
- `samples/android-demo/build.gradle` — versionName default value
- `samples/flutter-demo/pubspec.yaml` — version field

### CLAUDE.md session state
- "Latest release" line in session continuity section

---

## Steps

### 1. Read current version
```bash
grep '^VERSION_NAME=' gradle.properties | cut -d= -f2
```

### 2. Ask for new version (if not provided as argument)
"Current version is X.Y.Z. What version do you want to bump to?"

### 3. Update ALL locations — `sync-versions.sh --fix` is the single source of truth

`bash .claude/scripts/sync-versions.sh --fix` is the AUTHORITATIVE updater: it owns the
canonical location list and rewrites every file atomically, so the script and this doc
can never drift. Run it instead of hand-editing (it already excludes the MCP files,
#1705, and treats flutter/RN consumed deps WARN-only, #1494):

```bash
bash .claude/scripts/sync-versions.sh --fix
```

Only fall back to manual `sed` if the script cannot reach a file. The key patterns:

**Gradle modules:**
```bash
for f in gradle.properties sceneview/gradle.properties arsceneview/gradle.properties sceneview-core/gradle.properties; do
  sed -i '' "s/^VERSION_NAME=.*/VERSION_NAME=NEW_VERSION/" "$f"
done
```

**Maven artifact references in docs:**
Replace `io.github.sceneview:sceneview:OLD` with `io.github.sceneview:sceneview:NEW` in:
llms.txt, README.md, CLAUDE.md, all docs/docs/*.md files

**npm packages:**
Update version field in all package.json files

**Flutter:**
Update pubspec.yaml, android/build.gradle, ios/*.podspec

**Website:**
Update softwareVersion, hero badge, code snippets in website-static/index.html

### 4. Rebuild MCP dist
```bash
cd mcp && npm run prepare
```

### 5. Run sync-versions.sh
```bash
bash .claude/scripts/sync-versions.sh
```
If any mismatch remains, fix it before proceeding.

### 6. Verify with grep
```bash
# Should find NO references to old version in critical files
grep -rn "OLD_VERSION" gradle.properties */gradle.properties mcp/package.json llms.txt README.md
# Should find new version in all expected places
grep -rn "NEW_VERSION" gradle.properties */gradle.properties mcp/package.json llms.txt README.md
```

### 7. Commit
```bash
git add -A
git commit -m "chore: bump version to X.Y.Z"
```

### 8. Remind about website
"Don't forget to update the website (sceneview.github.io) if there are version references there."

---

**NEVER bump version in only one file. The whole point of this command is atomic, everywhere-at-once updates.**

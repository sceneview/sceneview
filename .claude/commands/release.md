---
description: Full SceneView release workflow — version bump, changelog, tag, multi-platform publish.
---

# /release — SceneView release workflow

Guided workflow to bump version, update all references, and prepare a release across ALL platforms.

Ask the user: "What version are we releasing? (current: check root gradle.properties)"

---

## Step 1: Bump version everywhere (use /version-bump)

Run `/version-bump X.Y.Z` which updates ALL 30+ version locations at once.

`/version-bump` runs `sync-versions.sh --fix` — the **single source of truth** for the
location list. Do NOT hand-maintain a copy; the list below is reference-only and may lag.
⛔ **#1705**: `sceneview-mcp` is on an INDEPENDENT npm track — `sync-versions.sh` EXCLUDES
`mcp/package.json` + `mcp/src/index.ts`; never bump them to `VERSION_NAME`. The flutter/RN
*consumed* `io.github.sceneview:*` deps lag to the last PUBLISHED release, never the
in-flight one (#1494). Reference list:

### Source of truth
1. `gradle.properties` (root) — `VERSION_NAME=X.Y.Z`

### Android modules (must match root exactly)
2. `sceneview/gradle.properties` — `VERSION_NAME=`
3. `arsceneview/gradle.properties` — `VERSION_NAME=`
4. `sceneview-core/gradle.properties` — `VERSION_NAME=`

### npm packages
5. ~~`mcp/package.json`~~ — **excluded** (independent MCP track, #1705)
6. `sceneview-web/package.json` — `"version": "X.Y.Z"`
7. `react-native/react-native-sceneview/package.json` — `"version": "X.Y.Z"`

### Flutter
8. `flutter/sceneview_flutter/pubspec.yaml` — `version: X.Y.Z`
9. `flutter/sceneview_flutter/android/build.gradle` — `version 'X.Y.Z'`
10. `flutter/sceneview_flutter/ios/sceneview_flutter.podspec` — `s.version = 'X.Y.Z'`

### Documentation
11. `llms.txt` — all `io.github.sceneview:*:X.Y.Z` artifact references
12. `README.md` — install snippets
13. `CLAUDE.md` — code examples section
14. `docs/docs/index.md` — install snippets
15. `docs/docs/quickstart.md` — dependency snippets
16. `docs/docs/llms-full.txt` — artifact versions
17. `docs/docs/cheatsheet.md` — install snippets
18. `docs/docs/platforms.md` — install line
19. `docs/docs/android-xr.md` — install snippets

### Website
20. `website-static/index.html` — softwareVersion, badge, code snippets
21. `sceneview.github.io/index.html` — same (deployed website, separate repo)

### Demo apps
22. `samples/android-demo/build.gradle` — versionName default
23. `sceneview/Module.md`, `arsceneview/Module.md` — version refs

### MCP source
24. ~~`mcp/src/index.ts`~~ — **excluded** (independent MCP track, #1705)

## Step 2: Collate the CHANGELOG

Changelog entries no longer live under a manually-edited `## Unreleased` anchor —
each PR drops a fragment in `changelog.d/` (see `changelog.d/README.md`). Collate
them into a new release section in one command:

```bash
bash .claude/scripts/collate-changelog.sh X.Y.Z
```

This reads every `changelog.d/*.md` fragment, groups the bullets by category
(`### Added` / `### Changed` / `### Fixed` / `### Removed` / `### Tests` /
`### Docs`), prepends a `## vX.Y.Z — <date>` section to `CHANGELOG.md`, folds in
any legacy `## Unreleased` entries, deletes the consumed fragments, and leaves an
empty `## Unreleased` placeholder. Pass `--dry-run` first to preview, or
`--date YYYY-MM-DD` to override the date.

After collation, review `git diff CHANGELOG.md` and hand-edit the
`## vX.Y.Z — <date>` title to add the thematic summary (the format
`release.yml` extracts and publishes as the GitHub Release body), then
reorder/merge bullets if needed.

Cross-check against recent git log: `git log <last-tag>..HEAD --oneline`

## Step 3: Rebuild MCP

```bash
cd mcp && npm run prepare && npm test
```

Verify dist/ files are updated and tests pass.

### Step 3.5: Republish sceneview-mcp if `mcp/` changed (independent track, #1705)

`sceneview-mcp` is on its OWN npm cadence — `release.yml` only republishes it
on a `v*` tag, so a tag-less period of `mcp/` changes leaves npm stale (this is
how it rotted a month behind at 4.0.12). After cutting the SDK release, check
whether the MCP needs a republish:

```bash
NPM_VER=$(npm view sceneview-mcp version)
PKG_VER=$(node -p "require('./mcp/package.json').version")
echo "npm=$NPM_VER  local=$PKG_VER"
```

If `mcp/` changed since `npm=$NPM_VER` (refreshed SDK doc refs, tooling fix,
etc.) and `local` is not ahead, bump `mcp/package.json` + `mcp/package-lock.json`
by a **patch** (e.g. 4.0.12 → 4.0.13 — never sync to `VERSION_NAME`; the
generated `mcp/src/generated/version.ts` is refreshed by `npm run prepare`),
land it on `main`, then republish via the dispatchable workflow (no tag needed):

```bash
gh workflow run mcp-publish.yml -R sceneview/sceneview --ref main
# verify (bounded — no infinite loop):
gh run watch "$(gh run list --workflow=mcp-publish.yml --limit 1 --json databaseId --jq '.[0].databaseId')" -R sceneview/sceneview --exit-status
npm view sceneview-mcp version   # must equal the new mcp/package.json version
```

`mcp-publish.yml` is idempotent — if the version is already on npm it logs a
notice and succeeds, so a re-dispatch never errors.

## Step 4: Update CLAUDE.md session state

Update the "Current state" section with:
- Date to today
- Latest release version
- Summary of what changed

## Step 5: Verify with sync-versions.sh

```bash
bash .claude/scripts/sync-versions.sh
```

ALL checks must pass. If any mismatch, fix before proceeding.

## Step 6: Run quality gate

```bash
bash .claude/scripts/quality-gate.sh --quick
```

## Step 6.5: Device QA gate (deterministic, non-blocking — #1683)

The cross-platform device-QA harness (umbrella #1560) **informs** the release;
it can **never** block it indefinitely. Two ways to satisfy the gate:

**A. Let the release gate dispatch its own run (recommended).** Skip this step
— `release-checklist.sh` (Step 7) calls `release-device-qa-gate.sh`, which:

- triggers its OWN Device QA run via `gh workflow run "Device QA"` — a
  `workflow_dispatch` run is isolated from push-concurrency cancellation
  (#1665/#1667), so it can never be killed by a later push (the root cause of
  the 58-commit freeze in #1683);
- polls **that specific run id** with a **bounded loop + hard timeout**
  (`RELEASE_QA_TIMEOUT_MIN`, default 60 min);
- grades the result by leg.

**B. Run it locally first.** `bash .claude/scripts/device-qa.sh --platform=all`
produces `device-qa-report.json` at the repo root; if that file is present the
gate reads it directly (fast path, no dispatch).

### Gate policy

Per CLAUDE.md "Release-gate policy for continue-on-error legs (#1651)" — the
single source of truth — only `web` is BLOCKING; `android` and `ar` are
ADVISORY (flaky emulator / CI assumeTrue-SKIP):

| Leg | Role | A failure means |
|---|---|---|
| **web** (Playwright) | **BLOCKING** | release-gate **FAIL** (hard block) |
| **ar** (ARCore replay) | **ADVISORY** | **WARN** only — never blocks (CI assumeTrue-SKIP when bundled recording / Play Services for AR absent, #2433) |
| **android** (Maestro emulator) | **ADVISORY** | **WARN** only — never blocks (flaky SwiftShader #1643/#1676) |
| _timeout / dispatch failure / missing artifact_ | — | `device-qa: TIMEOUT (advisory)` — **proceeds with warning** |

A genuine FAIL on `web` is the **only** outcome that blocks tagging.
Everything else (advisory red, timeout, stuck harness) yields
`RELEASE-GATE: PASS-WITH-WARNINGS` and the release proceeds. A flaky or
cancelled harness can never hold shipping hostage.

## Step 7: Commit and tag

```bash
git add -A
git commit -m "chore: release X.Y.Z"
git tag vX.Y.Z
```

## Step 8: Push

Ask the user: "Push to main and trigger release workflow?"

If yes:
```bash
git push origin main --tags
```

This triggers:
- **release.yml**: Maven Central publish, npm MCP publish, npm sceneview-web publish, GitHub Release
- **play-store.yml**: Android demo AAB build and Play Store upload
- **app-store.yml**: iOS demo TestFlight upload (if Apple cert is configured)
- **docs.yml**: Website + docs rebuild and deploy

## Step 9: Verify published artifacts

Wait 5-10 minutes, then run `/sync-check --published-only` to verify all artifacts are live — and `/store-status` for the REAL App Store / Play live versions (CI-green ≠ live, #2252):
- Maven Central: sceneview, arsceneview, sceneview-core
- npm: sceneview-mcp, @sceneview/sceneview-web
- GitHub Release with APKs attached
- SPM tag available

## Step 9.5: Verify the GitHub Release body matches CHANGELOG.md

**`release.yml` extracts the `## vX.Y.Z — …` section from `CHANGELOG.md` and
publishes it as the GitHub Release body.** Browse to
`https://github.com/sceneview/sceneview/releases/tag/vX.Y.Z` and confirm:

- Title `## vX.Y.Z — <thematic summary> (YYYY-MM-DD)` is rendered
- Themed sections (`### Added`, `### Fixed`, `### Changed`, …) are present
- A `BREAKING` block appears at the top if the release introduces breaking changes
- `**Full Changelog**: …` link at the bottom

If the release accidentally shipped the auto-generated `What's Changed → Other
Changes` PR list (no `## vX.Y.Z` section was in CHANGELOG.md at tag time, or
the workflow fell back), repair it manually:

```bash
VERSION=X.Y.Z
awk -v ver="## v$VERSION" '
  $0 ~ "^"ver"( |$)"  { found=1; print; next }
  found && /^## v[0-9]/ { exit }
  found                 { print }
' CHANGELOG.md | gh release edit "v$VERSION" --notes-file -
```

The hand-written narrative is the project's release-note standard (see
v4.0.9 / v4.1.0 for the reference quality). Never leave a release on the bare
auto-generated list.

## Step 10: Post-release

1. Update the deployed website (sceneview.github.io) if needed
2. Post to Discord (automatic via webhook)
3. Notify Thomas about LinkedIn post draft

---

## Artifact publishing matrix

| Artifact | Where | How | Trigger |
|---|---|---|---|
| sceneview | Maven Central | release.yml | git tag v* |
| arsceneview | Maven Central | release.yml | git tag v* |
| sceneview-core | Maven Central | release.yml | git tag v* |
| sceneview-mcp | npm | release.yml **or** mcp-publish.yml | git tag v* / `workflow_dispatch` |
| sceneview-web | npm | release.yml | git tag v* |
| SceneViewSwift | SPM (git tag) | git tag | Manual |
| GitHub Release | GitHub | release.yml | git tag v* |
| Demo APKs | GitHub Release | build-apks.yml | git tag v* |
| Play Store | Google Play | play-store.yml | push to main |
| TestFlight | App Store | app-store.yml | push to main (needs cert) |
| Website | GitHub Pages | docs.yml | push to main |

**Important:** Never skip the sync-versions check. Version drift is the #1 source of bugs in this repo.

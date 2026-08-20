# SceneView agent skills — registry & submission

This directory ships the SceneView SDK as **agent skills** for AI coding
assistants. Each skill is a self-contained `SKILL.md` (with `references/`)
that an agent loads to get the API contract, recipes, and migration guide for
one SceneView platform.

## Skills in this directory

| Skill | Platform | Install script |
|---|---|---|
| [`sceneview`](sceneview/SKILL.md) | Android — Jetpack Compose + Filament + ARCore | `.claude/scripts/install-sceneview-skill.sh` |
| [`sceneview-ios`](sceneview-ios/SKILL.md) | Apple — SwiftUI + RealityKit (iOS/macOS/visionOS) | `.claude/scripts/install-sceneview-ios-skill.sh` |
| [`sceneview-web`](sceneview-web/SKILL.md) | Web — Filament.js (WebGL2/WASM) + WebXR | `.claude/scripts/install-sceneview-web-skill.sh` |

All three are Apache-2.0 (see each `SKILL.md` frontmatter `license` field) and
maintained by the [`sceneview-tools`](https://github.com/sceneview) org.

## Local install (primary distribution)

The primary, supported way to use these skills is the **local install** — each
script copies the skill into the Android CLI skill registry at
`~/.android/cli/skills/xr/<skill>/`:

```bash
bash .claude/scripts/install-sceneview-skill.sh         # sceneview (Android)
bash .claude/scripts/install-sceneview-ios-skill.sh     # sceneview-ios
bash .claude/scripts/install-sceneview-web-skill.sh     # sceneview-web
```

After install, `android skills list` shows each skill under the `xr` category.
Re-run a script after pulling new commits to refresh the installed copy.

`bash .claude/scripts/check-sceneview-skill.sh` validates all three skills
against the live library source (frontmatter, API identifiers, demo refs) and
runs manually; the CI workflows that used to invoke it have been removed.

## Google `android-cli` registry submission (#1082)

Getting the skills into Google's **hosted** `android-cli` skill registry would
make `android skills add sceneview` Just Work for every agent-tool user, with
no repo clone. This is a **parallel track** — the local install stays primary
and is never blocked on the registry outcome.

### Submission packet

The submission packet for the `sceneview` Android skill (the first candidate —
iOS and web follow once the Android one is accepted):

- **Skill name:** `sceneview`
- **Category:** `xr`
- **Content:** [`agents/sceneview/SKILL.md`](sceneview/SKILL.md) + everything
  under [`agents/sceneview/references/`](sceneview/references/).
- **License:** Apache-2.0 (declared in the `SKILL.md` frontmatter).
- **Source of truth:** <https://github.com/sceneview/sceneview> — `llms.txt`
  and the demos under `samples/android-demo/`.
- **Maintenance contact:** the `sceneview-tools` org
  (<https://github.com/sceneview>); issues at
  <https://github.com/sceneview/sceneview/issues>.
- **Drift guarantee:** `check-sceneview-skill.sh` is wired into CI so the
  skill cannot silently drift from the library source.

### Submission steps

The `android-cli` docs at
<https://developer.android.com/tools/agents/android-cli> do **not** yet
describe a public skill-submission flow (verified for CLI v0.7). Until Google
publishes one, the submission proceeds through Google's issue tracker:

1. Confirm no public flow has appeared — re-check the `android-cli` docs page
   and `android skills --help` for an `add`/`publish`/`submit` verb.
2. File a feature request on Google's Issue Tracker under the Android Studio /
   developer-tools component (start at
   <https://issuetracker.google.com/issues/new?component=2091212>), titled
   e.g. *"Add `sceneview` 3D/AR skill to the android-cli hosted skill
   registry"*, attaching the submission packet above.
3. Link this `REGISTRY.md` and the repo so reviewers can inspect the skill,
   its references, and the CI drift guard.
4. Once accepted, repeat for `sceneview-ios` and `sceneview-web`.

### Status

| Skill | Registry status |
|---|---|
| `sceneview` | Pending — submission filed via Issue Tracker; awaiting a public `android-cli` registry flow |
| `sceneview-ios` | Not yet submitted — follows `sceneview` acceptance |
| `sceneview-web` | Not yet submitted — follows `sceneview` acceptance |

Update this table and [issue #1082](https://github.com/sceneview/sceneview/issues/1082)
as the submission progresses (accepted / pending / declined).

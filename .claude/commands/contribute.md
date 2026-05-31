---
description: Full SceneView contribution workflow — pre-PR checks, multi-platform sync, opens a ready-to-submit PR.
---

Full contribution workflow for SceneView. Runs all checks and prepares a ready-to-submit pull request.

This command walks through every step a contributor needs: understanding the codebase, making a change, reviewing it, documenting it, and opening a PR.

## Usage

Run `/contribute` with a description of what you want to fix or add:

```
/contribute Fix ModelNode not updating when modelInstance changes
/contribute Add support for point light color temperature
/contribute Update samples/android-demo to use new CameraManipulator API
```

Or run it with no arguments after you've already made changes to get a full pre-PR checklist.

---

## Workflow

### 1. Understand the codebase

- Read `CLAUDE.md` — critical rules for threading, Compose API, and module structure.
- Read `llms.txt` — complete API reference.
- Read `docs/docs/migration.md` — what changed between major versions.
- Run `git log --oneline -10` to understand recent history.
- Read the relevant source files before making any change.

### 2. Make the change

Follow these rules:
- **Thread safety**: Filament JNI calls on main thread only. Use `rememberModelInstance` in composables, `loadModelInstanceAsync` in imperative code.
- **Compose-first**: Express node hierarchy declaratively inside `SceneView { }` / `ARSceneView { }` content blocks.
- **Minimal diff**: Only change what's needed. Don't reformat unrelated code.
- **Module boundaries**: Core 3D in `sceneview/`, AR in `arsceneview/`, shared sample helpers in `samples/common/`.

### 3. Auto-review

Run `/review` to check threading, Compose API, Kotlin style, and module boundaries.

Fix any FAIL or WARN items before proceeding.

### 4. Auto-document

Run `/document` to generate or update KDoc for changed public APIs and check if `llms.txt` needs updating.

### 5. Check tests

Run `/review --coverage` to audit coverage and generate missing test cases.

### 6. Build check

```bash
./gradlew :sceneview:assembleDebug :arsceneview:assembleDebug --stacktrace
```

If you changed Filament materials (`.mat` files):
```bash
# Enable Filament plugin in gradle.properties, then:
./gradlew assembleDebug
```

### 7. Commit

Use conventional commit format:
```
fix: ModelNode updates when modelInstance changes at runtime
feat: add LightNode color temperature parameter
docs: update model-viewer sample for CameraManipulator API
```

### 8. Open a pull request

PR title should start with an uppercase letter.
PR description should explain **what** changed and **why**.

Use this template:
```markdown
## What
Short description of the change.

## Why
The problem this fixes or the feature this adds.

## How
Approach taken. Any non-obvious design decisions.

## Checklist
- [ ] `/review` passed (no FAIL items)
- [ ] `/document` run — KDoc updated, llms.txt updated if needed
- [ ] `/review --coverage` run — new tests added for new behaviour
- [ ] `assembleDebug` build passes
- [ ] Filament materials recompiled if `.mat` files changed
```

---

## Quick reference

| Command | What it does |
|---|---|
| `/review` | Threading, Compose API, Kotlin style, module boundaries |
| `/document` | KDoc generation + llms.txt diff |
| `/review --coverage` | Coverage audit + test generation |
| `/contribute` | This file — full workflow |

MCP server for IDE integration:
```json
{ "mcpServers": { "sceneview": { "command": "npx", "args": ["-y", "sceneview-mcp"] } } }
```

# Contributing to SceneView

We welcome contributions of all kinds — bug fixes, new features, documentation, and samples.

---

## Quick start

```bash
# Fork and clone
git clone https://github.com/YOUR_USERNAME/sceneview.git
cd sceneview

# Open in Android Studio, build, and run a sample to verify setup
```

## AI-assisted workflow

Contribute with whichever assistant you already use. The repo carries a rules
file under each of the names the tools look for — `AGENTS.md`, `CLAUDE.md`,
`.cursorrules`, `.github/copilot-instructions.md`, `.windsurfrules` — so yours
finds the codebase conventions with no setup. Point it at `AGENTS.md` and ask
for the workflow you want; setup for every tool is in
[AI-assisted development](ai-development.md#per-tool-setup).

The repo also ships slash commands, which are a Claude Code feature. Inside the
project root:

| Command | What it does |
|---|---|
| `/contribute` | Full guided workflow from understanding to PR |
| `/review` | Checks threading, Compose API, Kotlin style, module boundaries |
| `/document` | Generates/updates KDoc and `llms.txt` for changed APIs |
| `/review --coverage` | Audits coverage and generates missing tests |

`AGENTS.md` describes the same workflows in prose, so another assistant can run
them by name.

---

## Code style

We follow the official [Kotlin style guide](https://developer.android.com/kotlin/style-guide).
The code style is stored in the repository — Android Studio picks it up automatically.

Key rules:

- **4-space indentation** (no tabs)
- **Trailing commas** in multi-line parameter lists
- **`internal`** visibility for implementation details
- **No wildcard imports**

---

## Pull request guidelines

1. **Fork → branch → PR** — create a feature branch from `main`
2. **Keep changes minimal** — fix what you came to fix, don't refactor the world
3. **Start PR title with uppercase** — e.g., "Add PhysicsNode collision callbacks"
4. **Describe your changes** — a short summary helps reviewers
5. **Same Git name/email as your GitHub account** — for contributor role attribution

---

## Device QA

The demo apps are exercised on real emulators/simulators by an autonomous
device-QA harness that drives them like a real user (taps, swipes,
camera-orbit drags) across Android, iOS, web, and AR replay. Run a full pass
with `bash .claude/scripts/device-qa.sh --platform=all`. When you add or change
a demo, update its Maestro flow (`.maestro/android/`, `.maestro/ios/`) and the
web Playwright coverage in the same PR. A green device-QA pass is mandatory at
every release checkpoint. See [`CONTRIBUTING.md`](https://github.com/sceneview/sceneview/blob/main/CONTRIBUTING.md)
and [`.maestro/README.md`](https://github.com/sceneview/sceneview/blob/main/.maestro/README.md)
for details.

---

## Module structure

| Module | What to change |
|---|---|
| `sceneview/` | Core 3D library — nodes, scene, rendering, materials |
| `arsceneview/` | AR layer — ARCore integration, AR-specific nodes |
| `samples/` | Sample apps — add new samples or improve existing ones |
| `docs/` | This documentation site |

---

## Threading rules

!!! warning "Critical"
    Filament JNI calls **must** run on the main thread. Never call `modelLoader.createModel*`
    or `materialLoader.*` from a background coroutine. Use `rememberModelInstance` in composables
    or `loadModelInstanceAsync` for imperative code.

---

## Filament materials

If you modify `.mat` files, recompile them using the
[current Filament version](https://github.com/google/filament/releases).
Enable the Filament plugin in `gradle.properties` and rebuild.

---

## Issues & discussions

- **Bug reports** → [GitHub Issues](https://github.com/sceneview/sceneview/issues) (use the templates)
- **Questions** → [GitHub Discussions](https://github.com/sceneview/sceneview/discussions)
- **Chat** → [Discord](https://discord.gg/UbNDDBTNqb)

---

## License

By contributing, you agree that your contributions will be licensed under the
[Apache License 2.0](https://github.com/sceneview/sceneview/blob/main/LICENSE).

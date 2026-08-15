# AI-Assisted Development

SceneView is the first 3D/AR library designed for AI-assisted development. Every API is documented in a machine-readable format that AI tools understand natively.

---

## Why this matters

When you ask an AI to help you build a 3D scene, it needs to know the exact API — function names, parameter types, threading rules, common patterns. Most 3D libraries have large, complex APIs that AI tools hallucinate about.

SceneView solves this with three layers:

1. **`llms.txt`** — a machine-readable API reference at the repo root
2. **`sceneview-mcp`** — an MCP server that gives AI tools full API context
3. **Claude Code skills** — guided workflows for contributing, reviewing, and documenting

---

## For app developers

### Use with Claude Code

Install [Claude Code](https://claude.ai/code), then **either** install the official plugin (recommended — bundles MCP + 11 contributor commands + cross-platform reminder hooks):

```bash
/plugin marketplace add sceneview/claude-marketplace
/plugin install sceneview@sceneview
```

**Or** add just the MCP server directly:

```bash
echo '{
  "mcpServers": {
    "sceneview": { "command": "npx", "args": ["-y", "sceneview-mcp"] }
  }
}' > .claude/mcp.json
```

Now Claude has the full SceneView API. Ask it to:

- "Add a 3D model viewer to my product detail screen"
- "Add AR tap-to-place with pinch-to-scale"
- "Add a dynamic sky with fog that changes based on a slider"
- "Show a loading indicator while the model loads"

The AI will generate correct SceneView code — no hallucinated methods, no outdated patterns.

### Use with Codex, Cursor, Copilot and other agents

Add the MCP server to the agent's MCP config — the same JSON block as above works for
every MCP-compatible client. For agents that read a project rules file, copy `llms.txt`
into your project root; `AGENTS.md` (the cross-vendor standard, read by Codex, Cursor,
Copilot, Gemini CLI and others) is the file most of them look for first.

### Use with ChatGPT / Claude web

Paste the contents of [`llms.txt`](https://github.com/sceneview/sceneview/blob/main/llms.txt) into your conversation, then ask your question. The AI will use the correct API.

---

## For SceneView contributors

### Slash commands

Inside the SceneView repo with Claude Code (commands shown unprefixed work locally; once you install the [SceneView plugin](https://github.com/sceneview/claude-marketplace), they're available everywhere as `/sceneview:*`):

| Command | What it does |
|---|---|
| `/contribute` | Full guided workflow — understand the codebase, make changes, prepare a PR |
| `/review` | Threading, Compose API, style, module boundaries — plus `--score` (weighted eval), `--coverage` (test gaps), `high` (multi-agent triptych) |
| `/document` | Generate/update KDoc for changed public APIs, update `llms.txt` |
| `/release`, `/quality-gate`, `/sync-check`, `/store-status`, `/version-bump`, `/maintain` | Pre-PR + release lifecycle |

> **Tip — namespace conflict:** the bare `/review` command shadows a Claude Code built-in. With the plugin installed, prefer the prefixed form `/sceneview:review` to disambiguate.

### Example workflow

```bash
cd sceneview
claude

# Then in Claude Code:
> /contribute
# Claude walks you through understanding the codebase,
# making changes, running checks, and preparing a PR.
```

---

## What's in `llms.txt`

A machine-readable API reference (7,600+ lines) covering:

- All composable signatures with parameter types and defaults
- Code examples for every node type
- Threading rules and common pitfalls
- Resource loading patterns
- Gesture and interaction APIs
- Math types and coordinate system
- AR-specific APIs (anchors, image tracking, face mesh, cloud anchors)

The file is maintained alongside the source code and updated with every release.

---

## What's in the MCP server

The `sceneview-mcp` package provides **31 tools** that AI assistants can call, plus two
resources. The most used ones:

| Tool | What it does |
|---|---|
| `get_sample` / `list_samples` | A complete, compilable sample for any of 33 scenarios (Kotlin or Swift) |
| `get_node_reference` | Exact signature, parameters and defaults for any node type |
| `validate_code` | Checks generated code against 30+ rules — including symbol existence against the real public API |
| `get_setup` / `get_ios_setup` / `get_web_setup` / `get_ar_setup` | Per-platform dependency, manifest and permission setup |
| `get_best_practices` | Threading, lifecycle and Compose rules — including the main-thread Filament constraint |
| `migrate_code` / `get_migration_guide` | Automatic 2.x / 3.x migration with a changelog |
| `search_models` / `generate_3d_model` | Find a real asset on Sketchfab, or generate a new GLB (both BYOK) |
| `analyze_project` | Scans a local project for outdated deps and known anti-patterns |

Resources: `sceneview://api` (the full API reference) and `sceneview://known-issues`
(live GitHub issues).

The full list of 31 is in the [MCP README](https://github.com/sceneview/sceneview/tree/main/mcp#readme).

### Setup

```json
{
  "mcpServers": {
    "sceneview": {
      "command": "npx",
      "args": ["-y", "sceneview-mcp"]
    }
  }
}
```

Works with Claude Code, Claude Desktop, Codex, Cursor, GitHub Copilot, and any MCP-compatible tool.

---

## Why no other 3D library has this

| Library | AI support |
|---|---|
| **SceneView** | `llms.txt` + MCP server + Claude Code skills |
| Unity | Generic docs, frequent hallucinations on API |
| Sceneform | Archived, AI trained on outdated code |
| Raw ARCore | Low-level API, AI struggles with GL/Vulkan boilerplate |
| Rajawali | Minimal docs, AI has no training data |

SceneView's AI tooling means faster development, fewer bugs, and correct code on the first try. This is a competitive advantage that compounds — the more developers use AI tools, the more SceneView's AI-first approach matters.

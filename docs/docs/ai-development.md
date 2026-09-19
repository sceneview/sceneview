# AI-Assisted Development

SceneView is built to be read by AI coding assistants. Every API is documented
in a machine-readable format, and the setup below is the same for all of them.

---

## Why this matters

When you ask an AI to help you build a 3D scene, it needs to know the exact API — function names, parameter types, threading rules, common patterns. An assistant that has to infer a 3D API from prose documentation invents plausible-looking calls that do not exist.

SceneView addresses this with three layers:

1. **`llms.txt`** — a machine-readable API reference at the repo root, readable
   by anything that can fetch a URL
2. **`sceneview-mcp`** — an MCP server that gives any MCP client the full API
   context, over stdio or over Streamable HTTP
3. **Rules files** — the same API contract under each of the filenames the
   tools look for: `AGENTS.md`, `CLAUDE.md`, `.github/copilot-instructions.md`,
   plus the legacy-but-still-read `.cursorrules` and `.windsurfrules`

---

## For app developers

### The server

One command, and it is the same command everywhere:

```bash
npx -y sceneview-mcp
```

It also runs hosted, over Streamable HTTP, for clients that cannot spawn a
local process:

```
https://mcp.sceneview.dev/mcp
```

What differs between tools is only *where the config lives and what the keys
are called*. The common shape is a JSON `mcpServers` object; the per-tool
sections below give the exceptions. When in doubt, paste the command above into
whatever field your client offers, and check that client's own documentation
for the file it reads.

### Per-tool setup

Alphabetical. No tool is recommended over another — each entry is the
mechanism that tool actually uses, taken from its own documentation.

#### Claude Code

```bash
claude mcp add sceneview -- npx -y sceneview-mcp
```

Or commit it for the whole team, in `.mcp.json` **at the project root** — the
project-scoped file named by the documentation (local and user scopes live in
`~/.claude.json`):

```json
{
  "mcpServers": {
    "sceneview": { "type": "stdio", "command": "npx", "args": ["-y", "sceneview-mcp"] }
  }
}
```

Verify with `claude mcp list` — `sceneview` should show `✔ Connected`. There is
also a plugin that bundles the MCP server with the contributor commands below:
`/plugin marketplace add sceneview/claude-marketplace`, then
`/plugin install sceneview@sceneview`.
References: [code.claude.com/docs/en/mcp](https://code.claude.com/docs/en/mcp)
and [code.claude.com/docs/en/plugin-marketplaces](https://code.claude.com/docs/en/plugin-marketplaces)

#### Cline

MCP Servers icon → Configure → Configure MCP Servers, or `~/.cline/mcp.json`:

```json
{
  "mcpServers": {
    "sceneview": {
      "command": "npx", "args": ["-y", "sceneview-mcp"],
      "disabled": false, "autoApprove": []
    }
  }
}
```

Reference: [docs.cline.bot/mcp/configuring-mcp-servers](https://docs.cline.bot/mcp/configuring-mcp-servers)

#### Codex

```bash
codex mcp add sceneview -- npx -y sceneview-mcp
```

Or edit `~/.codex/config.toml` — the same config serves the CLI, the IDE
extension and the app:

```toml
[mcp_servers.sceneview]
command = "npx"
args = ["-y", "sceneview-mcp"]
```

The repository is also a Codex plugin, carrying three skills — `sceneview`
(Compose), `sceneview-ios` (SwiftUI) and `sceneview-web` (Filament.js / WebXR).
Codex scans `.agents/skills` in every directory from your working directory up
to the repository root, so in a checkout it finds them with no setup;
`.agents/plugins/marketplace.json` declares the repository as a local
marketplace, installable with `codex plugin marketplace add .`, and
`.codex-plugin/plugin.json` is kept as the documented compatibility fallback.
References: [learn.chatgpt.com/docs/extend/mcp](https://learn.chatgpt.com/docs/extend/mcp),
[learn.chatgpt.com/docs/build-skills](https://learn.chatgpt.com/docs/build-skills)
and [developers.openai.com/plugins/build/plugins](https://developers.openai.com/plugins/build/plugins)

#### Cursor

`.cursor/mcp.json` for one project, `~/.cursor/mcp.json` for every project:

```json
{
  "mcpServers": {
    "sceneview": { "command": "npx", "args": ["-y", "sceneview-mcp"] }
  }
}
```

A one-click Cursor install link — a `cursor://anysphere.cursor-deeplink/mcp/install`
deeplink — is on [the setup page](https://sceneview.github.io/#ai-setup).
References: [cursor.com/docs/mcp](https://cursor.com/docs/mcp) and
[cursor.com/docs/mcp/install-links](https://cursor.com/docs/mcp/install-links)

#### Gemini CLI

`~/.gemini/settings.json`, the standard `mcpServers` block:

```json
{
  "mcpServers": {
    "sceneview": { "command": "npx", "args": ["-y", "sceneview-mcp"] }
  }
}
```

The repository root also carries a `gemini-extension.json`, so
`gemini extensions install https://github.com/sceneview/sceneview` registers the
same server with nothing to paste — at the cost of cloning the monorepo, which is
over 2 GB of history. The settings block above is the light way in.

Reference: [google-gemini.github.io/gemini-cli/docs/tools/mcp-server.html](https://google-gemini.github.io/gemini-cli/docs/tools/mcp-server.html)

#### Gemini in Android Studio

Settings → Tools → AI → MCP Servers. Android Studio connects over HTTP, not
stdio, so this entry points at the hosted server — the local
`npx -y sceneview-mcp` command cannot be registered here:

```json
{
  "mcpServers": {
    "sceneview": { "httpUrl": "https://mcp.sceneview.dev/mcp", "enabled": true }
  }
}
```

Reference: [developer.android.com/studio/gemini/add-mcp-server](https://developer.android.com/studio/gemini/add-mcp-server)

#### GitHub Copilot

`.vscode/mcp.json` — note the `servers` key, not `mcpServers`:

```json
{
  "servers": {
    "sceneview": {
      "type": "stdio",
      "command": "npx", "args": ["-y", "sceneview-mcp"]
    }
  }
}
```

For Copilot CLI: `copilot mcp add sceneview -- npx -y sceneview-mcp`. Writing
that file by hand takes a third shape — `~/.copilot/mcp-config.json` uses
`mcpServers` like most clients, but each server needs `"type": "local"` rather
than `"stdio"`. In a SceneView checkout, Copilot also picks up the repository
instructions in `.github/copilot-instructions.md`, which are "available for use
by Copilot as soon as you save the file"; for Copilot code review only, custom
instructions must be enabled in your personal settings (on by default).
References:
[code.visualstudio.com/docs/agents/reference/mcp-configuration](https://code.visualstudio.com/docs/agents/reference/mcp-configuration),
[docs.github.com — add MCP servers to Copilot CLI](https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-mcp-servers)
and [docs.github.com — add repository instructions](https://docs.github.com/en/copilot/how-tos/configure-custom-instructions/add-repository-instructions)

#### JetBrains AI Assistant

Settings → Tools → AI Assistant → Model Context Protocol (MCP) → Add, then
paste:

```json
{
  "mcpServers": {
    "sceneview": { "command": "npx", "args": ["-y", "sceneview-mcp"] }
  }
}
```

The documentation lists Junie among the external clients it detects, and
IntelliJ IDEA among the IDEs. Junie also reads the same JSON straight from a
file — `.junie/mcp/mcp.json` for one project, `~/.junie/mcp/mcp.json` for every
project.
References: [jetbrains.com/help/ai-assistant/mcp.html](https://www.jetbrains.com/help/ai-assistant/mcp.html)
and [junie.jetbrains.com — CLI MCP configuration](https://junie.jetbrains.com/docs/junie-cli-mcp-configuration.html)

#### Xcode

Xcode exposes *its own* tools to external agents rather than hosting this
server: enable it under Settings → Intelligence → Model Context Protocol, then
run your CLI alongside Xcode with both servers registered — for instance
`claude mcp add --transport stdio xcode -- xcrun mcpbridge` or
`codex mcp add xcode -- xcrun mcpbridge`, plus SceneView from the same CLI.
Reference: [developer.apple.com — giving external agents access to Xcode](https://developer.apple.com/documentation/xcode/giving-external-agents-access-to-xcode)

#### Any other client

Antigravity, Continue, Devin Desktop (formerly Windsurf), Goose, Kilo Code,
Kiro, OpenCode, OpenHands, Qwen Code, Zed and any other MCP-compatible client
take the same command. Consult your client's own documentation for where its
config file lives.

### No MCP support?

Point the assistant at the plain-text reference — it is the whole SDK in one
file, and any tool that can read a URL or a pasted block can use it:

```
https://sceneview.github.io/llms.txt
```

### What to ask for

Once the context is in place, ask in plain language:

- "Add a 3D model viewer to my product detail screen"
- "Add AR tap-to-place with pinch-to-scale"
- "Add a dynamic sky with fog that changes based on a slider"
- "Show a loading indicator while the model loads"

---

## For SceneView contributors

### Rules files

A checkout carries one context file per convention, holding the same guidance:
`AGENTS.md` (Codex and every agent following the AGENTS.md convention),
`CLAUDE.md`, `.github/copilot-instructions.md`, plus `.cursorrules` and
`.windsurfrules` — both documented by their vendors as legacy single files that
are still read, the current mechanisms being `.cursor/rules/*.mdc` for Cursor
and `.devin/rules/` (or `.windsurf/rules/`) for Devin Desktop / Windsurf,
alongside `AGENTS.md` in both cases. Whichever tool you run in the repo, it
finds its own.

### Slash commands

Slash commands are a Claude Code feature, so this section is specific to it.
Working in the repo with another assistant? `AGENTS.md` describes the same
workflows in prose — ask for them by name.

Inside the SceneView repo with Claude Code (commands shown unprefixed work
locally; with the [SceneView plugin](https://github.com/sceneview/claude-marketplace)
installed they are available everywhere as `/sceneview:*`):

| Command | What it does |
|---|---|
| `/contribute` | Full guided workflow — understand the codebase, make changes, prepare a PR |
| `/review` | Threading, Compose API, style, module boundaries — plus `--score` (weighted eval), `--coverage` (test gaps), `high` (multi-agent triptych) |
| `/document` | Generate/update KDoc for changed public APIs, update `llms.txt` |
| `/release`, `/quality-gate`, `/sync-check`, `/store-status`, `/version-bump`, `/maintain` | Pre-PR + release lifecycle |

> **Tip — namespace conflict:** the bare `/review` command shadows a Claude Code built-in. With the plugin installed, prefer the prefixed form `/sceneview:review` to disambiguate.

---

## What's in `llms.txt`

A machine-readable API reference covering:

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

The `sceneview-mcp` package provides 32 tools that AI assistants can call, in
five families:

- **Reference** — `get_node_reference`, `get_best_practices`, `get_material_guide`,
  `get_animation_guide`, `get_gesture_guide`, `get_collision_guide`,
  `get_performance_tips`, `get_model_optimization_guide`, `get_web_rendering_guide`
- **Setup** — `get_setup`, `get_platform_setup`, `get_ar_setup`, `get_ios_setup`,
  `get_web_setup`, `list_platforms`, `get_platform_roadmap`
- **Samples and code** — `get_sample`, `list_samples`, `generate_scene`,
  `validate_code`, `analyze_project`
- **Migration and diagnosis** — `get_migration_guide`, `migrate_code`,
  `debug_issue`, `get_troubleshooting`
- **Assets, preview and docs search** — `search_models`, `generate_3d_model`,
  `view_3d_model`, `render_3d_preview`, `create_3d_artifact`,
  `search_android_docs`, `fetch_android_doc`

Any MCP client can call them; see [per-tool setup](#per-tool-setup) for the
config shape yours expects. This list is the server's own registry
(`mcp/src/tools/`) — ask your client to list the server's tools if you want to
check it against the version you have installed.

---

## What SceneView ships for AI tooling

| Layer | Where it lives |
|---|---|
| Install descriptors | `gemini-extension.json` at the repo root (Gemini CLI), `mcp/manifest.json` (MCP Bundle) |
| Machine-readable API reference | `llms.txt`, at the repo root and at [sceneview.github.io/llms.txt](https://sceneview.github.io/llms.txt) |
| MCP server | `sceneview-mcp`, over stdio or Streamable HTTP |
| Rules files | one per convention, in every checkout |
| Skills | `agents/sceneview`, `agents/sceneview-ios`, `agents/sceneview-web` |

All five are maintained alongside the source and updated with every release, so
an assistant reading them is reading the API that actually shipped.

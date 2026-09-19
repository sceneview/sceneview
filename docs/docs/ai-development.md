# AI-Assisted Development

SceneView is built to be read by AI coding assistants. Every API is documented
in a machine-readable format, and the setup below is the same for all of them.

---

## Why this matters

When you ask an AI to help you build a 3D scene, it needs to know the exact API — function names, parameter types, threading rules, common patterns. Most 3D libraries have large, complex APIs that AI tools hallucinate about.

SceneView solves this with three layers:

1. **`llms.txt`** — a machine-readable API reference at the repo root, readable
   by anything that can fetch a URL
2. **`sceneview-mcp`** — an MCP server that gives any MCP client the full API
   context, over stdio or over Streamable HTTP
3. **Rules files** — the same API contract under each of the filenames the
   tools look for: `AGENTS.md`, `CLAUDE.md`, `.cursorrules`,
   `.github/copilot-instructions.md`, `.windsurfrules`

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
are called*. The common shape is `mcpServers`; VS Code uses `servers` with
`"type": "stdio"`, Codex a `[mcp_servers.*]` TOML table, Copilot CLI
`"type": "local"`, and Kilo Code an `mcp` key in `kilo.jsonc` where `command`
is an array. When in doubt, paste the command above into whatever field your
client offers.

### Per-tool setup

Alphabetical. No tool is recommended over another — each entry is the
mechanism that tool actually uses, taken from its own documentation.

#### Claude Code

```bash
claude mcp add sceneview -- npx -y sceneview-mcp
```

Or commit it for the whole team, in `.mcp.json` **at the project root** — the
only project-scoped MCP file Claude Code reads. `.claude/mcp.json` and
`~/.claude/mcp.json` are silently ignored:

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
Reference: [code.claude.com/docs/en/mcp](https://code.claude.com/docs/en/mcp)

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

The repository is also an OpenAI plugin: `.codex-plugin/plugin.json` points at
the three skills under `agents/` — `sceneview` (Compose), `sceneview-ios`
(SwiftUI) and `sceneview-web` (Filament.js / WebXR). From a checkout,
`codex plugin marketplace add "$PWD"` then
`codex plugin add sceneview@sceneview-local` (an absolute path — a relative one
does not resolve). Codex also picks the skills up on its own from
`.agents/skills/`.
Reference: [learn.chatgpt.com/docs/extend/mcp](https://learn.chatgpt.com/docs/extend/mcp)

#### Cursor

`.cursor/mcp.json` for one project, `~/.cursor/mcp.json` for every project:

```json
{
  "mcpServers": {
    "sceneview": { "command": "npx", "args": ["-y", "sceneview-mcp"] }
  }
}
```

Cursor also accepts an [install link](https://sceneview.github.io/#ai-setup).
Reference: [cursor.com/docs/mcp/install-links](https://cursor.com/docs/mcp/install-links)

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

To run it locally inside the same IDE, use Junie instead: the standard
`mcpServers` snippet in `.junie/mcp/mcp.json`.
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
than `"stdio"`. In a SceneView checkout, Copilot also reads
`.github/copilot-instructions.md` with no setup at all.
References:
[code.visualstudio.com/docs/agents/reference/mcp-configuration](https://code.visualstudio.com/docs/agents/reference/mcp-configuration)
and [docs.github.com — add MCP servers to Copilot CLI](https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-mcp-servers)

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

Works for AI Assistant and Junie, in IntelliJ IDEA, Android Studio and the
other JetBrains IDEs. Junie also reads the same JSON straight from a file —
`.junie/mcp/mcp.json` for one project, `~/.junie/mcp/mcp.json` for every
project.
Reference: [jetbrains.com/help/ai-assistant/mcp.html](https://www.jetbrains.com/help/ai-assistant/mcp.html)

#### Xcode

Agents running inside Xcode read the rules files at your project root —
`AGENTS.md` and `CLAUDE.md`. Xcode 26.3 itself exposes *its own* tools to
external agents rather than hosting this server: enable it under
Settings → Intelligence → Model Context Protocol, then run your CLI alongside
Xcode with both servers registered — for instance
`claude mcp add --transport stdio xcode -- xcrun mcpbridge` or
`codex mcp add xcode -- xcrun mcpbridge`, plus SceneView from the same CLI.
Reference: [developer.apple.com — giving external agents access to Xcode](https://developer.apple.com/documentation/xcode/giving-external-agents-access-to-xcode)

#### Any other client

Antigravity, Continue, Devin Desktop, Goose, Kilo Code, Kiro, OpenCode,
OpenHands, Qwen Code, Windsurf, Zed and any other MCP-compatible client take
the same command. Consult your client's own documentation for where its config
file lives.

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
`CLAUDE.md`, `.cursorrules`, `.github/copilot-instructions.md` and
`.windsurfrules`. Whichever tool you run in the repo, it finds its own.

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

The `sceneview-mcp` package provides tools that AI assistants can call:

- **`get_api_reference`** — returns the full `llms.txt` content
- **`get_node_reference`** — look up a specific node type's API
- **`get_sample_code`** — get working example code for a use case
- **`get_threading_rules`** — threading and lifecycle rules

Any MCP client can call them; see [per-tool setup](#per-tool-setup) for the
config shape yours expects.

---

## Why no other 3D library has this

| Library | AI support |
|---|---|
| **SceneView** | `llms.txt` + MCP server + a rules file per convention |
| Unity | Generic docs, frequent hallucinations on API |
| Sceneform | Archived, AI trained on outdated code |
| Raw ARCore | Low-level API, AI struggles with GL/Vulkan boilerplate |
| Rajawali | Minimal docs, AI has no training data |

SceneView's AI tooling means faster development, fewer bugs, and correct code on the first try. This is a competitive advantage that compounds — the more developers use AI tools, the more SceneView's AI-first approach matters.

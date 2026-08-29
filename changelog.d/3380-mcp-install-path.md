<!-- category: Fixed -->
<!-- RELEASE NOTE (maintainer-only):
     Every published install snippet for `sceneview-mcp` told Claude Code users
     to write their config to `.claude/mcp.json`. Claude Code never reads that
     path — the project scope lives in `.mcp.json` at the repository root, and
     an unread config produces no warning, no error, and no server: the tools
     simply never appear. Measured effect: zero `sceneview` tool calls across
     165 sessions. The repo shipped the same mistake in its own committed
     config (`.claude/mcp.json`, tracked since 467ef58f0), while `.gitignore`
     blanket-ignored the path that actually works, so a fresh clone could never
     get the server either. Verified against Claude Code's own documentation
     and by an A/B `claude mcp list`: identical JSON at `.claude/mcp.json`
     yields no `sceneview` entry, at `.mcp.json` it reports `✔ Connected`. -->
- **`sceneview-mcp` install instructions now name the path Claude Code actually reads.** Every surface (`llms.txt` and the generated `gpt/knowledge-*.md`, `docs/docs/ai-development.md`, `website-static/.well-known/llms.txt`, `mcp/demo/`) now leads with `claude mcp add --scope project sceneview -- npx -y sceneview-mcp` and names `.mcp.json` at the project root, with `~/.claude.json` for user scope; the old `.claude/mcp.json` and `~/.claude/mcp.json` snippets were silently ignored by Claude Code, so the server never loaded. The repo's own config moved from `.claude/mcp.json` to a committed root `.mcp.json`, which `.gitignore` no longer excludes.

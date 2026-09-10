# Privacy Policy — sceneview-mcp

**Last updated:** April 8, 2026

## Summary

sceneview-mcp does not collect, store, or process any personal data. The
Service is free: there is no account, no subscription and no billing.

## Details

### Data Collection

- **No personal data collected** — We do not collect names, email addresses, IP addresses, or any other personally identifiable information.
- **No cookies** — The Service does not use cookies of any kind.
- **No behavioral tracking** — No fingerprinting or cross-session tracking.
- **No request logging** — Tool arguments, results, and prompt content are never logged or transmitted.
- **Anonymous telemetry** — sceneview-mcp sends an anonymous, opt-out telemetry ping on the MCP handshake and on each tool call. See the "Telemetry (Free Tier)" section below for what's collected and how to opt out.

### Telemetry

To understand which MCP clients our users run on and which tools are actually useful, sceneview-mcp sends a minimal, anonymous telemetry event at two moments:

1. **On initialization** — once per MCP client handshake.
2. **On tool call** — once per invocation of any tool.

**What's collected (exhaustive list):**

| Field | Example | Why |
|---|---|---|
| `timestamp` | `2026-04-11T09:23:15.421Z` | Time-bucketing for weekly rollups |
| `event` | `"init"` or `"tool"` | Distinguish handshake from tool calls |
| `client` | `"claude-desktop"` | Which MCP client is being used |
| `clientVersion` | `"0.11.0"` | Client version as reported during the MCP handshake |
| `mcpVersion` | `"4.0.0-rc.1"` | Version of this MCP server |
| `tier` | always `"free"` | Legacy wire field; there is no paid tier |
| `tool` | `"get_node_reference"` | Tool name — only for `event: "tool"` |

**What's NEVER collected:**

- IP address, hostname, OS user, machine identifier
- Prompt text, tool arguments, tool results
- API keys, billing information, email addresses
- Files read or generated, project paths
- Any other personally identifiable information

The telemetry endpoint is a Cloudflare Worker that deliberately does not log client IP addresses or forward them to any downstream store. Events are aggregated into anonymous weekly counters only.

**How to opt out:**

Set `SCENEVIEW_TELEMETRY=0` in the environment where the MCP server runs. For Claude Desktop, add it to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "sceneview": {
      "command": "npx",
      "args": ["-y", "sceneview-mcp"],
      "env": { "SCENEVIEW_TELEMETRY": "0" }
    }
  }
}
```

Telemetry is also automatically skipped when `CI=true` is set (continuous-integration environments).

**Why it's opt-out, not opt-in:** opt-in telemetry systematically under-represents the exact users we need to learn from, which makes product decisions worse for everyone. The payload is minimal enough and the opt-out mechanism simple enough that we believe this is the right trade-off. If you disagree, the one-line opt-out above is a first-class citizen.

### Data Processing

- All MCP tool calls are processed in-memory and discarded immediately after the response is returned.
- No data is persisted to disk, database, or cloud storage by the MCP server itself.

### Third-Party Services

| Service | Purpose | Data Sent |
|---|---|---|
| **GitHub public API** | Fetch open issues for `sceneview://known-issues` | None (public API, no auth) |

- GitHub API results are cached in-memory for 10 minutes
- The cache is discarded when the process ends

### GDPR Compliance

Compliant by design — no personal data is collected or processed.
**Data Protection Officer**: not required under current processing scale.

### CCPA Compliance

This Service is compliant with the California Consumer Privacy Act (CCPA) by design: no personal information is collected, sold, or shared.

### Children's Privacy

This Service does not knowingly collect any data from anyone, including children under 13.

### Changes to This Policy

We may update this Privacy Policy from time to time. Changes will be reflected in the "Last updated" date above.

### Contact

Thomas Gorisse — [https://github.com/sceneview/sceneview](https://github.com/sceneview/sceneview)

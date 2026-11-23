<!-- category: Changed -->
- `release.yml` publishes sceneview-mcp to the official MCP Registry after npm, through the job's GitHub OIDC token (`mcp-publisher` v1.8.8, checksum-pinned). The registry still listed 3.4.7. The step is a no-op when the version is already listed, so an MCP-only dispatch fills the gap. The server and package descriptions no longer name iOS.

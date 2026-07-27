<!-- category: Fixed -->

- **MCP:** `sceneview-mcp` no longer advertises a one-release-old SDK pin to AI agents.
  `mcp/src/generated/version.ts` is auto-generated but, unlike its gitignored `llms-txt.ts` /
  `symbols.ts` siblings, **committed** — and the v4.25.0 release bumped `gradle.properties`
  without regenerating it, so `LATEST_SCENEVIEW_RELEASE` (and the `analyze-project`
  `android-ok` test fixture's SDK pin) stayed at `4.24.0`, the version the MCP hands out in
  its install snippets. Both are regenerated to `4.25.0`. The MCP's own npm version
  (`PACKAGE_VERSION`) is on an independent track and is left untouched (#1705).
- **Tooling:** `sync-versions.sh` now verifies `LATEST_SCENEVIEW_RELEASE` against
  `VERSION_NAME` (CRITICAL) and regenerates `version.ts` + the fixture in `--fix`. A future SDK
  bump that forgets the MCP regeneration is now caught by the release pipeline
  (`release-fast.yml` re-runs the check for zero residuals) instead of silently shipping a
  stale pin. `PACKAGE_VERSION` stays deliberately out of the check (#1705).

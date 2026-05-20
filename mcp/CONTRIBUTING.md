# Contributing to `sceneview-mcp`

This document covers the day-to-day mechanics specific to the MCP package.
For project-wide guidelines (commit style, multi-platform sync, release
flow) see the repo-root [`CONTRIBUTING.md`](../CONTRIBUTING.md).

---

## Generated files — DO regenerate, don't hand-edit

Two files under `src/generated/` are committed to git but **never edited by
hand**. Both are recreated by `npm run build` (and `npm test`, via the
`prebuild` hook in `package.json`):

| File | Generator | Source | Purpose |
|---|---|---|---|
| `src/generated/llms-txt.ts`  | `scripts/generate-llms-txt.js` | repo-root `llms.txt`          | Embeds the full SceneView API reference as a TS string constant so the gateway (which runs on Cloudflare Workers and has no filesystem) and the stdio npm package both ship a byte-identical bundle. |
| `src/generated/version.ts`   | `scripts/generate-version.js`  | `package.json` + `../gradle.properties` | Snapshots the MCP package version and the SceneView SDK version (`VERSION_NAME`) at build time so the MCP server advertises the *actually-published* numbers — never a hardcoded stale literal. |

### When to regenerate the LLMs bundle

**Any time you edit the repo-root `llms.txt`**, even by one character. If
you forget, AI clients consuming `sceneview-mcp` will keep seeing the old
API surface (this happened repeatedly throughout the May 2026 AR sprint —
`DepthMeshNode`, `Frame.hitTestDepth`, the Future-returning Cloud Anchor
APIs all landed in `llms.txt` while the bundle stayed pinned to the
previous release, see issue #1808).

**How:**

```bash
cd mcp
node scripts/generate-llms-txt.js
# or, equivalently, `npm run build` — the prebuild hook calls the same
# generator and then re-runs `tsc`.
```

Commit the regenerated `src/generated/llms-txt.ts` alongside your
`llms.txt` change in the same PR.

### CI drift guard

`.claude/scripts/sync-versions.sh` (run by both `quality-gate.sh` and the
`ci.yml` `quality-gate` job) verifies that `src/generated/llms-txt.ts` is
identical to what the generator would produce from the current root
`llms.txt`. If you edited `llms.txt` and forgot the regen step, CI fails
with:

```
MISMATCH: mcp/src/generated/llms-txt.ts is out of sync with root llms.txt
  Run: cd mcp && node scripts/generate-llms-txt.js
```

`sync-versions.sh --fix` will regenerate it for you locally.

The same script also catches drift in `docs/docs/llms.txt` (which must be
a byte-for-byte mirror of root `llms.txt` so the mkdocs site serves the
same content as the raw GitHub URL LLM clients fetch).

---

## Tests

```bash
cd mcp
npm test
```

`vitest run` covers ~1800 unit tests including the bundle round-trip
(`src/generated.test.ts`) and the npm tarball completeness check
(`src/package-files.test.ts`). The `pretest` step regenerates both
files so a stale local checkout never masks a real drift.

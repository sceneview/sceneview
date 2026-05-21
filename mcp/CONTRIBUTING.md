# Contributing to `sceneview-mcp`

This document covers the day-to-day mechanics specific to the MCP package.
For project-wide guidelines (commit style, multi-platform sync, release
flow) see the repo-root [`CONTRIBUTING.md`](../CONTRIBUTING.md).

---

## Generated files — never hand-edit, never commit

Two files under `src/generated/` are **build artefacts**, recreated from
their sources by the npm lifecycle scripts — never edited by hand:

| File | Generator | Source | Committed? | Purpose |
|---|---|---|---|---|
| `src/generated/llms-txt.ts`  | `scripts/generate-llms-txt.js` | repo-root `llms.txt`          | **No** — `.gitignore`d | Embeds the full SceneView API reference as a TS string constant so the gateway (which runs on Cloudflare Workers and has no filesystem) and the stdio npm package both ship a byte-identical bundle. |
| `src/generated/version.ts`   | `scripts/generate-version.js`  | `package.json` + `../gradle.properties` | Yes | Snapshots the MCP package version and the SceneView SDK version (`VERSION_NAME`) at build time so the MCP server advertises the *actually-published* numbers — never a hardcoded stale literal. |

`src/generated/llms-txt.ts` is **not committed to git** (issue #1928). It
embeds the entire ~230 KB root `llms.txt` as a single string literal, so a
committed copy produced a guaranteed merge conflict on *every* parallel PR
that also touched `llms.txt` — even when the `llms.txt` edits were in
non-overlapping sections. It is now generated fresh by the npm lifecycle:

- `prebuild` → runs before `npm run build`
- `prepare` → runs after `npm install` **and** before `npm publish`
- `test` → regenerates before `vitest run`

So a clean checkout has the file the moment you run `npm install`, and the
published tarball always ships the freshly-generated `dist/generated/llms-txt.js`.

### Editing the LLMs bundle

There is **no manual regen step and nothing to commit**. Edit the
repo-root `llms.txt`; the build regenerates `src/generated/llms-txt.ts`
from it automatically. If you want to inspect the result locally:

```bash
cd mcp
npm run build   # the prebuild hook runs scripts/generate-llms-txt.js, then tsc
```

### CI drift guard

`.claude/scripts/check-llms-drift.sh` (run by `quality-gate.sh` and the
`ci.yml` `quality-gate` job) verifies that `docs/docs/llms.txt` is a
byte-for-byte mirror of root `llms.txt` so the mkdocs site serves the same
content as the raw GitHub URL LLM clients fetch. `sync-versions.sh --fix`
re-copies the mirror for you locally.

`src/generated/llms-txt.ts` no longer needs a drift check: being generated
fresh on every build, publish and test run, it cannot drift from root
`llms.txt`.

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

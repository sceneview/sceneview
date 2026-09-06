# SceneView as a ChatGPT / Codex plugin — package & submission packet

OpenAI's unit of distribution is the **plugin**: a folder with a manifest at
`.codex-plugin/plugin.json`, optional skills, an optional MCP server and optional UI,
listed in one **Plugins Directory shared by ChatGPT and Codex**
(<https://developers.openai.com/plugins>). This repository *is* that plugin: the
manifest sits at the repo root and points at the skills that already live under
`agents/` and at the free `sceneview-mcp` server.

| Component | Where | Directory type |
|---|---|---|
| Manifest | [`.codex-plugin/plugin.json`](../.codex-plugin/plugin.json) | required |
| Skills (3) | [`agents/sceneview`](sceneview/SKILL.md), [`agents/sceneview-ios`](sceneview-ios/SKILL.md), [`agents/sceneview-web`](sceneview-web/SKILL.md) | skills-only submission works with these alone |
| Skill display metadata | `agents/<skill>/agents/openai.yaml` | optional |
| Bundled MCP (stdio, Codex) | [`.codex-plugin/mcp.json`](../.codex-plugin/mcp.json) → `npx -y sceneview-mcp` | optional |
| Remote MCP (ChatGPT) | `npx sceneview-mcp --http` — Streamable HTTP at `/mcp`, see [`mcp/README.md`](../mcp/README.md) | needs a public URL |
| 3D viewer UI | `ui://widget/3d-viewer.html` served by the same server (MCP Apps, `text/html;profile=mcp-app`) | optional |
| Codex discovery in any checkout | [`.agents/skills/*`](../.agents/skills) symlinks → `agents/*`; [`.agents/plugins/marketplace.json`](../.agents/plugins/marketplace.json) for local install | — |

Why the repo root and not a copy: the skills are validated against the library
source by `.claude/scripts/check-sceneview-skill.sh`, and a second copy under
`plugins/` would be one more surface to drift. Codex follows symlinks in
`.agents/skills`, so the canonical files stay under `agents/`.

## Two submission shapes, in order

1. **Skills-only** — zero infrastructure, self-serve, no domain verification. The three
   `SKILL.md` files carry the API contract (`llms.txt` link), recipes, migration guide and
   demo references. Submit this first.
2. **Skills + MCP** — adds the 29 free tools and the inline 3D viewer. Requires a public
   production URL serving `sceneview-mcp --http`, domain verification and a CSP
   declaration. The hosted gateway was deleted on 2026-08-31, so this shape waits for an
   explicit hosting decision; nothing in the package assumes one.

Both shapes are compliant with the directory's monetization rule (no selling or promoting
subscriptions inside the plugin): the remote surface serves the **free tier only** and
refuses Pro tool names, and the skills link to Apache-2.0 sources.

## Listing copy (English, as submitted)

- **Name:** SceneView 3D & AR
- **Short description:** Write working 3D and AR code for Android, Apple and the web on
  the first try.
- **Long description:** see `interface.longDescription` in the manifest — keep the two in
  sync by editing the manifest, this file only mirrors it.
- **Category:** Developer Tools · **Capabilities:** Read
- **Website:** <https://sceneview.github.io> · **Support:**
  <https://github.com/sceneview/sceneview/issues>
- **Privacy policy:** <https://sceneview.github.io/privacy> · **Terms:**
  <https://github.com/sceneview/sceneview/blob/main/mcp/TERMS.md>
- **Logo:** `branding/exports/logo/logo-512.png` (512 px PNG, the manifest's `logo`);
  **composer icon:** `website-static/favicon-192.png`. One screenshot of the 3D widget
  (DamagedHelmet, 800 × 600, headless Chrome + SwiftShader) exists for the listing form;
  it is kept with the maintainer's listing assets, not in the repository.

## Starter prompts (minimum 5)

1. Build a Jetpack Compose screen that loads a `.glb` model with SceneView, orbit camera
   and a light.
2. Add ARCore plane detection with SceneView and place the model where the user taps.
3. Write a SwiftUI view that shows a USDZ model with SceneViewSwift on iOS and visionOS.
4. Render a GLB in the browser with `sceneview-web` and add a WebXR "View in AR" button.
5. Migrate this SceneView 2.x snippet to the 4.x composable API.
6. Show me this 3D model URL inline and tell me how to load it in Compose.
7. Open the `.3mf` file a print flow gave me in a Compose viewer, then place it in AR at its
   real size.

## Test cases (6 positive, 3 negative — OpenAI's format)

| # | Prompt | Expected behaviour | Result shape |
|---|---|---|---|
| P1 | "Load `models/helmet.glb` in a Compose screen with SceneView" | Skill `sceneview` triggers; code uses `SceneView { }`, `rememberEngine`, `rememberModelLoader`, `rememberModelInstance`; dependency `io.github.sceneview:sceneview:4.34.0` | Kotlin snippet that compiles against 4.34.0 |
| P2 | "Place that model on a detected plane when I tap" | `ARSceneView { }` with plane detection and a hit-test on tap; dependency `arsceneview` | Kotlin snippet |
| P3 | "Same thing on iOS with SwiftUI" | Skill `sceneview-ios`; `SceneView { }` / `ARSceneView { }` from SceneViewSwift, SPM tag `4.34.0` | Swift snippet |
| P4 | "Show me `https://…/DamagedHelmet.glb` in 3D" (MCP shape only) | Tool `view_3d_model` is called; the widget renders the model inline; text names the URL | `structuredContent.modelUrl` + widget |
| P5 | "Which SceneView sample fits an AR anchor demo?" (MCP shape only) | `list_samples` then `get_sample` | Sample id + Kotlin source |
| P6 | "Open this `.3mf` in Compose and place it in AR at its real size" | Skill `sceneview` triggers; the answer uses the ordinary `rememberModelInstance(modelLoader, uri)` path and invents no `loadThreeMf` API; the millimetre → metre scaling is named | Kotlin snippet |
| N1 | "Write this with Unity / Unreal / raw ARCore" | Skill does not trigger (its description scopes it out); the assistant answers generically or asks | No SceneView code |
| N2 | "Call `generate_3d_model` to make me a chair" (MCP shape only) | Remote server refuses the Pro tool with an `isError` result naming the free tier | Error result, no charge, no external call |
| N3 | "Show this model: `file:///Users/me/model.glb`" (MCP shape only) | `view_3d_model` returns an error for a non-HTTPS URL; nothing is fetched | Error result |

Test credentials: none — every tool on the remote surface is anonymous and read-only.

## Owner gestures (cannot be automated)

1. Verified publisher identity at <https://platform.openai.com/plugins> (individual, under
   the SceneView name; "Apps Management" permission on the org).
2. **Skills-only submission** — upload the skill bundle (the three directories under
   `agents/`), paste the copy above, pick countries, attach release notes.
3. Only if the MCP shape is wanted: choose a host for `sceneview-mcp --http`, set
   `OPENAI_APPS_CHALLENGE_TOKEN` to the token the portal issues, confirm
   `https://<host>/.well-known/openai-apps-challenge` returns it, then register the URL in
   ChatGPT developer mode (Settings → Security and login → Developer mode) to test before
   submitting.
4. After approval, publish from the portal — approval alone does not list the plugin.

## Local testing (what could and could not be verified here)

- The local-marketplace install **was exercised** with Codex CLI 0.149.0 from a clean
  checkout, and Codex listed the three skills as `sceneview:sceneview`, `sceneview:sceneview-ios`
  and `sceneview:sceneview-web` when asked what it had available:

  ```bash
  codex plugin marketplace add "$PWD"          # registers marketplace `sceneview-local`
  codex plugin add sceneview@sceneview-local   # → ~/.codex/plugins/cache/sceneview-local/sceneview/<version>/
  codex plugin list                            # sceneview@sceneview-local  installed, enabled  4.34.0
  ```

  Two things the install taught us: `codex plugin marketplace add ./` with a relative
  path does not resolve, pass an absolute path; and the install copies the repository
  into the cache **without** the `.agents/skills` symlinks, so the skills resolve through
  the manifest's `"skills": "./agents/"` — the symlinks only serve discovery inside a
  checkout, never inside an installed plugin.
- The `path` in `marketplace.json` is relative to the repository root (documented example
  is `./plugins/my-plugin`); this repo is the plugin itself, hence `./`.
- The remote server was exercised with `curl` against `node dist/index.js --http`
  (`initialize`, `tools/list`, `resources/read`); rendering of the widget inside ChatGPT's
  sandbox needs the developer-mode registration above.

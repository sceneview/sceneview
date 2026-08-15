# AI surfaces — état des lieux and cleanup plan

> Companion to [`ai-surfaces-exploration.md`](./ai-surfaces-exploration.md) (which asks
> *what should we build?*). This one asks *what do we already have, what of it is false,
> and which assistants do we still want to carry?*
> Session 2026-08-15. Every claim below was verified — file:line, npm registry, Stripe
> API, or a dated public source (§8). Where verification was impossible, it says so.

## 0. Method, and the three things that could not be verified here

Verified live: the npm registry (`npm view`), the Stripe account (read-only API), the
repo listing for the account, and every file:line cited.

Could **not** be verified from this container, all three needing the Mac or a permission:

1. **The deployed Workers.** `sceneview-mcp`, `hub-mcp` and `sceneview-telemetry` on
   `*.mcp-tools-lab.workers.dev` are all blocked by the egress proxy — no `/health`
   probe was possible. Their live state is an assumption in everything below.
   → run `/store-status` or `mcp-gateway-golive.sh` from the Mac.
2. **The private repos.** `add_repo` on `ThomasGorisse/mcp-creator-kit` was refused by
   the permission classifier, so the portfolio below is mapped from listing metadata
   (name, visibility, last push) only — not from source.
3. **The commercial detail.** Deliberately kept out of this file — see §4.

## 1. The map — everything that exists today

### 1.1 In this repo

| Surface | Path | Verified state |
|---|---|---|
| stdio MCP server | `mcp/` | npm `sceneview-mcp@4.0.16`, **31** tool definitions (`mcp/src/tools/definitions.ts`) |
| Hosted gateway | `mcp-gateway/` | Worker; `/mcp` (Bearer) + `/mcp/public` (anon); 67 tools mounted; MCP proto `2025-03-26` |
| Vertical packages | `mcp/packages/*` | 5 libs, **all published on npm** — automotive 1.1.0, healthcare 1.1.0, gaming 1.0.0, interior 1.0.0 |
| Telemetry | `telemetry-worker/` | Worker, linked from the public site |
| Custom GPT | `pro/gpt-store/` + `gpt/` | Instructions + 4 knowledge files + an OpenAPI schema |
| Agent skills | `agents/sceneview{,-ios,-web}` | 3 skills + installers |
| Editor rules | `.cursorrules`, `.windsurfrules`, `.github/copilot-instructions.md` | 3 near-duplicates, 113/91/98 lines, **all drifted — §2** |
| AI docs | `docs/docs/ai-development.md`, `ai-context.md` | **advertise tools that do not exist — §2** |
| Registries | `smithery.yaml`, MCP Registry | Listed |

### 1.2 The portfolio beyond SceneView

Mapped from the account's repo listing. Nothing here is in this repo, and **nothing has
been pushed since 2026-06-05** except the three private August ones.

| Org | Repos | Last push |
|---|---|---|
| `mcp-tools-lab` | `finance-mcp`, `legal-docs-mcp`, `french-admin-mcp`, `education-mcp` | 2026-06-05 |
| `mcp-tools-lab` | `telegram-ai-bot`, `prompt-store`, `ai-invoice` *(archived)* | 2026-04-11 |
| `sceneview-tools` | `architecture-mcp`, `ecommerce-3d-mcp`, `realestate-mcp` | 2026-06-05 |
| `sceneview-tools` | `3d-viewer-extension` | 2026-04-11 |
| `ThomasGorisse` *(private)* | `mcp-creator-kit`, `social-media-mcp`, `sceneview-shopify` | 2026-08-05 |
| `sceneview` | `claude-marketplace`, `sceneview.github.io` | active |
| `sceneview` *(archived)* | `sceneview-swift`, `sceneform-android`, `sceneview-flutter`, `sceneform-reactnative` | — |

Two structural problems fall straight out of this map:

> **Measured 2026-08-15 from the bridge — this bullet's premise was half wrong, and the
> real shape is worse.** The source is *not* lost: it is in this repo's history at
> `c1a5c99f4e^`, and the live build is pinned to `e9d04f4adf` (52 tools deployed against
> 78 committed, so restoring it is a behaviour change, not a redeploy). But
> `c1a5c99f4e` deleted **two** components. `hub-mcp`, the stdio client, is **still
> published on npm** — deprecated on all four versions, still installable, and its `dist`
> hardcodes the live `/mcp`, `/pricing` and telemetry endpoints while advertising **78**
> tools against a Worker that serves **52**. Any retire-or-fold decision has to cover both.
> Two more findings from the same pass: the live money-handling Worker **predates both
> dependency-security commits** (`hono`/`postcss`, `ip-address`) — recorded, reachability
> not assessed — and a **fourth Worker, `arcamera-api`**, exists under the same account and
> appears in neither plan. `sceneview-mcp` by contrast is **deployed == committed**, so the
> `2025-03-26` protocol version and both §3.1b defects are confirmed *running*, not inferred.

- **`hub-mcp` is live and billable, and its source is in none of these repos.** A live
  Stripe webhook points at `hub-mcp.mcp-tools-lab.workers.dev/stripe/webhook`, and the
  hub code was deleted from this repo in the "remove off-topic portfolio code" cleanup
  (`hub-gateway/`, `hub-mcp/` — see CHANGELOG). Either it lives in a private repo not
  identified here, or **only on Cloudflare**. A money-handling Worker with no known
  source of truth is the single most urgent item in this document.
- **SceneView's public infrastructure is hosted under `mcp-tools-lab`.** The gateway,
  the telemetry Worker, and a link on the public homepage
  (`website-static/index.html:1068`) all expose that name. It is a portfolio-lab
  identifier on the SDK's front door. A custom domain (`mcp.sceneview.dev`, already noted as
  a pending item in `mcp-gateway/wrangler.toml:7`) fixes the visible half cheaply.

## 2. Verified drift — what is currently false

### 2.1 The tool count says something different on every surface

| Surface | Claim | Source |
|---|---|---|
| `mcp/src/tools/definitions.ts` | **31** | ground truth for the npm package |
| `docs/docs/ai-context.md:32` | 31 | ✅ agrees |
| `mcp/README.md` | 31 free | ✅ agrees |
| `website-static/index.html:164` (JSON-LD) | **28** | ❌ stale — and it is the answer Google reads |
| `website-static/index.html:383` (stat tile) | **28** | ❌ stale |
| `README.md` ("Why AI recommends SceneView") | **28+** | ❌ stale |
| Stripe product copy (live) | **51+** | ❌ invented |

`tool-count-claims.test.ts` guards the in-repo numbers and is why the first three agree.
It cannot see the website, the README prose, or Stripe — so those three drifted freely.

### 2.2 The AI docs advertise four MCP tools, and three of them do not exist

`docs/docs/ai-development.md` lists `get_api_reference`, `get_node_reference`,
`get_sample_code`, `get_threading_rules`. Only `get_node_reference` exists. The real
ones are `get_sample`, `get_best_practices`, and the `sceneview://api` resource.

This is the project's own thesis failing on the project's own docs page: an AI reading
it will call three tools that return "unknown tool".

### 2.3 The editor rules files hand out a 30-minor-versions-stale dependency

`VERSION_NAME=4.30.0`. All three rules files tell the assistant to use **`4.0.0`**:

- `.cursorrules:20,22` · `.windsurfrules:19,20` · `.github/copilot-instructions.md:7,8,97`

### 2.4 One archived-mirror URL survives, in the one file the gate cannot see

`.cursorrules:77` still points SPM at `https://github.com/sceneview/sceneview-swift.git`
— archived since #1215. `check-sceneview-swift-urls.sh` reports OK because it globs by
extension (`git grep -l … -- '*.md' '*.txt' … '*.sh'`) and **`.cursorrules` has no
extension**. The gate is not wrong, it is blind: extensionless files are outside every
pattern it scans. Widening the glob is a two-character fix; the class it belongs to
(a gate whose scope silently excludes a real surface) is worth one pass over the others.

### 2.5 `llms-full.txt` is the small one

`llms.txt` = 7,619 lines / 385 KB. `docs/docs/llms-full.txt` = 292 lines / 11 KB. The
docs describe this correctly ("compact" vs "complete"), but the **names are inverted**
against the `llms.txt` convention every agent assumes, where `-full` is the larger file.
Any agent that follows the convention fetches the summary believing it has the full API.

### 2.6 The site has no route to the thing that is for sale

There are live products and a working billing gateway, and **zero** links from
`website-static/` or `docs/docs/` to `/pricing` or to the gateway. The only
`workers.dev` link on the homepage points at a telemetry health endpoint.

## 3. Which assistants we should carry

Current public naming (word-boundary sweep over `README.md`, `docs/docs/*.md`,
`website-static/*.html`, `mcp/README.md`, `llms.txt`):

```
Claude      README, ai-context, ai-development, website ×3, mcp/README, llms.txt
Cursor      README, ai-context, ai-development, migration, showcase, website ×3, mcp/README, llms.txt
Windsurf    README, ai-context, ai-development, website ×3, mcp/README
ChatGPT     README, ai-context, ai-development, website ×2
Copilot     README, ai-context, website ×2
Gemini      ai-context, samples, website ×1, llms.txt
Codex       — nothing, anywhere
Antigravity — nothing, anywhere
Zed         — nothing in the repo, but named in live Stripe product copy
```

So the two assistants we want to prioritise are respectively **absent** (Codex) and
**barely present** (Gemini), while the surface carries Windsurf in seven places and
sells to "Zed" in Stripe copy that appears nowhere else.

**Proposed scope.**

| Tier | Who | What we owe them |
|---|---|---|
| **1 — first-class, tested** | Claude (Code · Desktop · connectors), OpenAI (Codex CLI · ChatGPT app), Gemini (Antigravity CLI · Gemini Enterprise MCP URL) | Install path documented **and** exercised; named in copy |
| **2 — documented, config only** | Cursor, GitHub Copilot, VS Code | One config block, no promises we do not test |
| **3 — drop from copy** | Windsurf, Zed, Perplexity | Remove; `AGENTS.md` keeps them working anyway |

### 3.1 The one change that serves Codex and Gemini at once: `AGENTS.md`

We do not have one. It is now the open standard under Linux Foundation stewardship
(60k+ repos) and is read natively by **Codex, Cursor, Copilot, Gemini CLI, Aider,
Windsurf and Zed**; `.cursorrules` has dropped out of Cursor's own docs as legacy.

Writing a single `AGENTS.md` — generated from the same source as everything else —
replaces three drifting hand-written files with one, reaches Codex and Gemini where they
actually look, and keeps every tier-2 and tier-3 client working without us naming them.
`CLAUDE.md` stays as-is: it is the contributor-workflow file, a different job.

This is the highest ratio of reach to effort in the whole document.

## 4. The commercial surface

Read live from Stripe: **8 active products** in two families — *SceneView MCP*
(Pro / Team, monthly + yearly) and *Hub* (Portfolio / Team, monthly + yearly, sold as
"45+ tools across 11 MCP verticals") — plus **2 enabled live webhook endpoints**, one per
gateway.

Three structural observations, safe to record here:

1. The Hub family sells the whole portfolio, but §1.2 shows that portfolio has been
   dormant since June, and §1's first bullet shows the Hub's own gateway source is
   unaccounted for. **We are selling a bundle we cannot currently rebuild.**
2. The two families overlap without a stated relationship — is SceneView Pro a subset of
   Hub Portfolio, and which one does a SceneView user land on?
3. The Stripe copy is the *only* surface claiming "51+ tools" and the only one naming
   Zed. Product copy is a public surface and no gate covers it.

**The customer, subscription and revenue figures are deliberately not written here** —
`.claude/plans/` lives in a public repository, and "how many paying customers does the
Pro tier have" is not a fact to publish in a PR. They were delivered in-session and
belong in a private note.

## 5. Cleanup plan

**Wave 1 — falsehoods. DONE (2026-08-15).**
1. ✅ The three phantom tool names in `ai-development.md`, replaced with the real surface.
2. ✅ `4.0.0` → `4.30.0` in the three rules files.
3. ✅ Archived SPM mirror out of `.cursorrules:77`; `check-sceneview-swift-urls.sh` now
   scans extensionless files.
4. ✅ Tool counts aligned on the derived 31 — and the sweep found **five** stale sites,
   not the three this plan predicted: homepage JSON-LD, homepage stat tile, homepage
   feature card, the homepage comparison table, `docs.html`, `claude-3d.html`, plus the
   README. `playground.html` was worse than stale — its copy-to-clipboard prompt listed
   five tool names (`create_scene`, `add_model`, `configure_camera`, `set_environment`,
   `add_ar_plane_detection`) that have never existed, handed straight to the user's
   assistant.
5. ⏸ **Held** — the `llms.txt` / `llms-full.txt` rename changes public URLs that external
   consumers fetch. Needs a redirect plan, not a rename.

**Wave 2 — scope. DONE (2026-08-15).**
6. ✅ [`AGENTS.md`](../../AGENTS.md) written as the canonical rules file, and **wired into
   the snippet compile harness** (`tools/extract-doc-snippets.js` + `snippets-check.yml`)
   so its Kotlin is verified in CI alongside `llms.txt`. `.cursorrules`, `.windsurfrules`
   and `.github/copilot-instructions.md` are now pointers. Writing it caught two more
   falsehoods the audit had missed: `copilot-instructions.md` named four node types that
   do not exist (`GeospatialNode`, `DepthNode`, `InstantPlacementNode`, `ArrowNode`), and
   the `.cursorrules` Swift snippet used a `SceneView(environment:)` initializer and a
   `ModelNode(named:)` that are both absent from `SceneViewSwift` — the real API is the
   `.environment()` modifier and `try await ModelNode.load(_:)`.
7. ✅ Assistant list re-cut across README, `ai-context.md`, `ai-development.md`,
   `mcp/README.md` and the homepage: Codex install path added everywhere, Gemini
   documented (Antigravity + Enterprise), Windsurf dropped from promoted copy.
   ⏸ **Partial** — the homepage's logo chips for Codex and Gemini are missing, because
   `assets/ai-tools/` has no OpenAI or Google brand SVG and inventing a trademark on the
   front page is worse than an absent one. The Windsurf chip was removed rather than
   replaced. Needs the real assets from the vendors' brand pages.
   ⏸ **Held** — `playground.html` still ships a functional "Open Windsurf" deep-link
   button. That is a feature, not copy; removing it is a product call.
8. ✅ Folded into `ai-development.md` and `mcp/README.md` rather than a new page — no
   mkdocs nav churn, and the setup lands next to the other clients.
9. ⏸ **Held** — the Stripe product copy is customer-visible and lives outside the repo.
   Fixing "51+ tools" and the "Zed" reference is a live write to the payment provider;
   flagged for explicit go-ahead.

**Wave 3 — structure (needs decisions, §7)**
10. Locate or reconstruct the `hub-mcp` source, then decide: fold the Hub into this repo,
    give it its own repo, or retire the product and disable its webhook.
11. Custom domain for the gateway; drop `mcp-tools-lab` from the public site.
12. Decide the fate of the dormant portfolio repos — one bundle, or archive them.
13. Link pricing from the site (§2.6).

## 6. The root-cause fix

Five of the seven Wave-1 items are the same bug: **a number or a name typed by hand into
a surface no gate can see.** The repo already solved this once — `gpt/knowledge-*.md` is
generated from `llms.txt` and gated, and `tool-count-claims.test.ts` re-derives counts
from the registry.

Extend that pattern to a single `ai-surfaces.json` manifest — supported clients, tool
counts per surface, current version, canonical URLs — and generate from it: `AGENTS.md`,
the copilot instructions, the website JSON-LD and stat tiles, the README AI block, and
the docs install tables. Then one gate fails the build when a surface disagrees.

Stripe copy cannot be generated, but it *can* be asserted: a read-only check comparing
live product descriptions against the manifest would have caught "51+ tools" and "Zed".

> **Follow-ups are tracked in [#3192](https://github.com/sceneview/sceneview/issues/3192)** —
> the Karma/ChromeHeadless crash on the blocking web leg, the `exit 127` gate-classification
> audit, the Hub's two components (Worker + the still-installable npm client), and the MCP
> protocol bump that gates everything MCP-Apps. Each needs its own PR; none belong in #3189.

## 7. Decisions needed

1. **Sign off the tier table (§3)** — in particular dropping Windsurf and Zed from copy.
2. **`AGENTS.md` as the single rules file** — yes, and do `.cursorrules` /
   `.windsurfrules` become pointers or disappear?
3. **The Hub.** Fold in, separate repo, or retire? This one gates Wave 3 and is the only
   item with money attached.
4. **The dormant portfolio repos** — invest, bundle, or archive?
5. **Custom domain** for the gateway — worth it now?

## 8. Sources

- [AGENTS.md spec and adoption (2026)](https://www.morphllm.com/agents-md-guide) ·
  [field guide](https://www.iuriio.com/blog/posts/2026/05/agents-md-field-guide-2026) ·
  [Codex setup](https://thepromptshelf.dev/blog/agents-md-codex-setup-guide-2026/)
- npm registry, verified 2026-08-15: `sceneview-mcp@4.0.16`, `automotive-3d-mcp@1.1.0`,
  `healthcare-3d-mcp@1.1.0`, `gaming-3d-mcp@1.0.0`, `interior-design-3d-mcp@1.0.0`,
  `sceneview-web@4.30.0`
- Stripe API (live, read-only), account `SceneView`, 2026-08-15
- Distribution-channel facts (ChatGPT App Directory, Claude connectors, Gemini Spark,
  AppFunctions): see [`ai-surfaces-exploration.md`](./ai-surfaces-exploration.md) §9

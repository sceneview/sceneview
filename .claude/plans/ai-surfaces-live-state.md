# AI surfaces — measured live state (from the Mac, 2026-08-15)

> Companion to [`ai-surfaces-exploration.md`](./ai-surfaces-exploration.md) and
> [`ai-surfaces-cleanup.md`](./ai-surfaces-cleanup.md). Those two were written in a cloud
> container with no egress to `*.workers.dev`, `modelcontextprotocol.io` or
> `developers.openai.com`, and marked four things as unverifiable. This file closes
> them **with real output quoted**, from a machine with network egress, the Android SDK,
> `gh` on the private repos, and the local `agy` CLI.
>
> Everything below is a measurement or a quoted command result. Where a probe was
> impossible, there is an explicit **could not verify** line. No commercial figures
> (prices, customer counts, revenue) are recorded here — this repository is public.

**Headline: the exploration plan's single BLOCKING finding (§3.1, "the widget declares a
mimeType nobody implements") is WRONG.** The mimeType is correct and matches both current
specs. Two *different*, real defects were found in its place. See §4.

---

## 1. The three deployed Workers are all live

Probed 2026-08-15 with `curl`. All three answer.

| Worker | `/health` | `/` | `/pricing` |
|---|---|---|---|
| `sceneview-mcp.mcp-tools-lab.workers.dev` | **200** JSON | **200** HTML (16.0 KB) | **200** HTML (17.4 KB) |
| `hub-mcp.mcp-tools-lab.workers.dev` | **200** JSON | **200** HTML (5.3 KB) | **200** HTML (5.7 KB) |
| `sceneview-telemetry.mcp-tools-lab.workers.dev` | **200** JSON | **200** HTML (3.6 KB) | **404** `{"error":"not_found"}` |

```
$ curl -s https://sceneview-mcp.mcp-tools-lab.workers.dev/health
{"ok":true,"service":"sceneview-mcp-gateway","version":"1.0.0"}

$ curl -s https://sceneview-telemetry.mcp-tools-lab.workers.dev/health
{"ok":true,"service":"sceneview-telemetry","version":"1.0.0"}

$ curl -s https://hub-mcp.mcp-tools-lab.workers.dev/health
{"ok":true,"service":"hub-mcp-gateway","version":"0.0.1","environment":"production",
 "registry":{"libraries":[
   {"id":"architecture","label":"architecture-mcp","toolCount":10},
   {"id":"realestate","label":"realestate-mcp","toolCount":4},
   {"id":"french_admin","label":"french-admin-mcp","toolCount":4},
   {"id":"ecommerce3d","label":"ecommerce-3d-mcp","toolCount":3},
   {"id":"legal_docs","label":"legal-docs-mcp","toolCount":3},
   {"id":"finance","label":"finance-mcp","toolCount":3},
   {"id":"education","label":"education-mcp","toolCount":3},
   {"id":"social_media","label":"social-media-mcp","toolCount":3},
   {"id":"health_fitness","label":"health-fitness-mcp","toolCount":3},
   {"id":"automotive3d","label":"automotive-3d-mcp","toolCount":9},
   {"id":"healthcare3d","label":"healthcare-3d-mcp","toolCount":7}],
  "totalTools":52}}
```

`hub-mcp` is not a dormant deployment: it is serving a live 11-library registry in
`ENVIRONMENT=production`.

### 1.1 The live MCP protocol version is `2025-03-26` — the repo source was accurate

`ai-surfaces-exploration.md` §3.2 read `PROTOCOL_VERSION = "2025-03-26"` from
`mcp-gateway/src/mcp/transport.ts:116` and noted that *deployed ≠ committed*. Measured:
the deployed Worker reports the same value, so there is no drift between source and
deployment on this point.

```
$ curl -s -X POST .../mcp/public -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
         "protocolVersion":"2026-07-28","capabilities":{},
         "clientInfo":{"name":"bridge-probe","version":"1.0.0"}}}'
{"jsonrpc":"2.0","id":1,"result":{
  "protocolVersion":"2025-03-26",
  "capabilities":{"tools":{"listChanged":false},
                  "resources":{"listChanged":false,"subscribe":false},
                  "prompts":{"listChanged":false}},
  "serverInfo":{"name":"sceneview-mcp-gateway","version":"1.0.0"}}}
```

Two things to note in that response, both load-bearing for §4:

- The server **does not down-negotiate** — it was offered `2026-07-28` and answered
  `2025-03-26` regardless.
- `capabilities` contains **no `extensions` field**. That is the actual blocker for
  MCP Apps (§4.2).

**Correction to §3.2:** the plan says the gateway is "three revisions behind". Measured
against the spec repo, the published revisions are `2024-11-05`, `2025-03-26`,
`2025-06-18`, `2025-11-25`, `2026-07-28` — so it is **four** revisions behind, and
`2026-07-28` is confirmed as the current one.

### 1.2 Live tool and resource counts

```
$ tools/list on /mcp/public  →  67 tool names          (matches ai-surfaces-cleanup.md §1.1)
$ resources/list on /mcp/public →
{"resources":[
  {"uri":"ui://widget/3d-viewer.html","name":"SceneView 3D Viewer",
   "mimeType":"text/html;profile=mcp-app"},
  {"uri":"ui://widget/scene-showcase.html","name":"SceneView Scene Showcase",
   "mimeType":"text/html;profile=mcp-app"}]}
```

Both widget resources are live and reachable anonymously.

`hub-mcp`'s MCP endpoint is auth-gated, as designed — probed without a key, read-only:

```
$ curl -s -X POST https://hub-mcp.mcp-tools-lab.workers.dev/mcp -d '{…"initialize"…}'
HTTP 401
{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"Unauthorized",
 "data":{"detail":"Missing API key"}}}
```

---

## 2. `hub-mcp` source: FOUND. It is in this repo's git history

`ai-surfaces-cleanup.md` §1.2 calls this "the single most urgent item in this document"
— a money-handling Worker whose source might exist only as a deployed artifact. It does
not. **The full source is recoverable from `sceneview` git history.**

```
$ git log --all --diff-filter=D --name-only -- 'hub-gateway/*' 'hub-mcp/*'
c1a5c99f4e  2026-05-07 23:51:46 +0200
            chore(security): remove off-topic personal-portfolio code from public repo
            → 65 files, 13 141 deletions
```

The pre-deletion tree `c1a5c99f4e^` (= `4f8800b48a`) contains the whole Worker:
`src/index.ts`, `src/routes/webhooks.ts` (the Stripe receiver), `src/billing/{checkout,
key-provisioning,stripe-client,tiers}.ts`, `src/auth/{api-keys,middleware}.ts`,
`src/db/{schema,usage}.ts`, `src/mcp/{registry,transport,access,types}.ts`,
`src/rate-limit/*`, all 11 `src/libraries/*.ts`, plus `wrangler.toml` and `package.json`.

**Identity match — this is the deployed Worker, not a lookalike:**

| Evidence | Deployed (`/health`) | `c1a5c99f4e^` source |
|---|---|---|
| service name | `hub-mcp-gateway` | `package.json` → `"name": "hub-mcp-gateway"` |
| version | `0.0.1` | `package.json` → `"version": "0.0.1"`, hardcoded again at `src/index.ts:39` |
| Worker name / hostname | `hub-mcp.mcp-tools-lab.workers.dev` | `wrangler.toml` → `name = "hub-mcp"`, and the URL is written in its own header comment |
| library set | 11 libraries, same ids | `src/mcp/registry.ts` → same 11 imports |
| Stripe webhook route | `/stripe/webhook` | `src/routes/webhooks.ts`, mounted at `src/index.ts:59` |

### 2.1 But the deployed build is OLDER than the last committed state

`/health` returns `getRegistrySummary()`, which reads `lib.definitions.length` — the full
count, not a tier-filtered one (`src/mcp/registry.ts:172-185`). So the numbers are
directly comparable, and they disagree:

| | architecture | realestate | french-admin | ecommerce-3d | legal-docs | finance | education | social-media | health-fitness | automotive-3d | healthcare-3d | **total** |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **deployed** | 10 | 4 | 4 | 3 | 3 | 3 | 3 | 3 | 3 | 9 | 7 | **52** |
| **last committed** | 14 | 7 | 9 | 3 | 6 | 6 | 6 | 3 | 8 | 9 | 7 | **78** |

(Counted as `grep -cE '^    name: "'` over each `hub-gateway/src/libraries/*.ts` at
`c1a5c99f4e^`; `automotive-3d` and `healthcare-3d` are thin re-exports of
`mcp/packages/{automotive,healthcare}/src/tools.ts`, counted there — 9 and 7.)

78 corroborates independently: `ee441e4785 feat(hub-mcp): v0.3.0 — 78 tools with
bridge-API upgrades`.

So the live Worker was deployed from an **earlier** tree than the one that was deleted.
Redeploying `c1a5c99f4e^` verbatim would *change live behaviour* (52 → 78 tools) — which
means "restore from git history" is a real change, not a no-op, and needs a diff review
rather than a `wrangler deploy`.

**Could not verify:** which exact commit the live build corresponds to. Attempts to count
the registry at each intermediate commit (`678db5d1db`, `d02eec313d`, `9a6e210eba`,
`f375960b60`, `9452e15a78`, `a88f7f8c58`) returned 0 for every non-vendored library, i.e.
the count harness did not reproduce inside the loop even though the standalone
`git show c1a5c99f4e^:…` count worked. Treated as a measurement failure on my side, not
as evidence about those commits. Not pursued further — the two numbers that matter (52
live, 78 last-committed) are each directly measured.

### 2.2 Where the source is NOT

Checked so the next session does not re-check:

- **Local disk** — no `hub-gateway`/`hub-mcp` directory anywhere under `~/Projects`,
  `~/Documents`, `~/Downloads`, `~/Desktop` (depth 4). No `wrangler.toml` naming
  `hub-mcp` outside git history. Every `stripe/webhook` hit on disk is
  `mcp-gateway/src/routes/webhooks.ts` (Gateway #1) in the main checkout or one of its
  worktrees.
- **The three private repos**, inspected on the *remote* (all pushed 2026-08-05, all
  ahead of the local clones — do not trust the local clones' last-commit dates):
  - `ThomasGorisse/mcp-creator-kit` (`master`) — `cli/ docs/ examples/ template/`. No hub.
  - `ThomasGorisse/social-media-mcp` (`main`) — a plain stdio MCP: `src/ tests/`,
    `server.json`. No hub.
  - `ThomasGorisse/sceneview-shopify` (`main`) — a Remix Shopify app. Has a
    `wrangler.json` and `app/routes/webhooks.app.*.tsx`, but those are **Shopify**
    webhooks, not Stripe, and nothing references hub.
  - Grep for `hub-mcp|hub-gateway|hub_mcp` across `*.ts *.js *.json *.toml *.md` in all
    three local clones: **zero hits**.

**Could not verify — `wrangler`:** not installed on this machine, and neither is Node.

```
$ command -v wrangler npx node pnpm yarn bun
(no output)
$ node -v
zsh: command not found: node
```

So `wrangler whoami` / `wrangler deployments list` **did not run**. The deployment date of
the live `hub-mcp` build, and the Cloudflare account it sits under, remain unverified.
That is the one open question left on the hub, and it is the question that would pin
§2.1's gap to a commit. (Node absence is itself worth noting: the repo's JS/TS gates
cannot run on this machine as currently provisioned.)

---

## 3. Cross-vendor pass: one leg ran, one leg SKIPped honestly

### 3.1 `codex` — SKIP, and the delegation plan is now stale

```
$ bash .claude/scripts/llm-delegate.sh codex --context .claude/plans/ai-surfaces-exploration.md \
    "Adversarial review: which of these bets is wrong, and what did we miss on the OpenAI side …"
SKIP: codex CLI not installed (npm i -g @openai/codex)
```

`ai-surfaces-exploration.md` §0 says `codex` and `agy` "are installed and authenticated on
the Mac only". **Half of that is no longer true.** `codex` is absent from this machine —
consistent with there being no `npm` at all here (§2.2). `multi-llm-delegation.md` §1,
which records `codex` 0.145.0 authenticated on 2026-07-23, is stale on this point and its
routing matrix ("Codex ≫ Gemini for this job") currently has no runnable Codex leg.

The wrapper behaved correctly: exit 0 with an honest `SKIP:` line, per the #2343 rule.
**The OpenAI-side adversarial review did not happen.** Do not read anything below as
covering it.

### 3.2 `agy` (Antigravity / Gemini) — ran

Ran with inline context only, per `multi-llm-delegation.md` §1 (headless `agy` must never
roam the filesystem). Everything in this subsection is **advisory, single-vendor, and
unverified** — recorded because it disagrees with the plan in specific, checkable ways,
not because it is right.

**The five bets it calls wrong:**

1. **Kill §4.1 (phone→cloud capture).** Called "UX suicide and architectural bloat": the
   pairing-code round trip is "a 2012 IoT pairing workflow"; storing home-interior photos
   and spatial point clouds creates privacy liability "with zero moat"; and it is the
   wrong product identity for "a native 3D/AR rendering engine for developers, not a
   consumer cloud capture SaaS."
2. **§3.1's "1-day dual-emit" is not a one-day fix** — *independently of the mimeType
   question* (§4 below). Two mechanisms: OpenAI's sandbox drives a `window.openai`
   `postMessage` RPC contract while MCP Apps drives the client-host JSON-RPC bridge
   (`callTool`, `readResource`), so one static HTML resource with two MIME tags and no
   per-host runtime adapter "will result in silent runtime initialization failures"; and
   restricted iframes in mobile chat webviews (WKWebView / Android WebView) routinely
   strip hardware WebGL, so a Filament.js canvas should be expected to black-screen on
   mobile until tested against each host's CSP and feature policy.
3. **AppFunctions is mis-tiered.** Argues it belongs above the capture pipeline, not in
   Tier C: Android is SceneView's stronghold, `androidx.appfunctions` is Google's official
   on-device agentic invocation layer, and — the actionable part — the
   `appfunctions-compiler` and its local test harness mean `@AppFunction` work does **not**
   have to wait on the trusted-tester approval. If true, that removes the "apply and wait"
   dependency the plan builds Tier C around.
4. **§4.2 (assistant→phone) is under-ranked** — "100× more valuable than
   phone-to-assistant", and the plan already half-concedes this by calling it the right
   thing to build first while ranking it below the capture pipeline.
5. **Antigravity is not a CLI gallery mirror.** It frames `agy` + Antigravity IDE +
   Antigravity 2.0 as one ecosystem with a real customization surface
   (`.agents/plugins/sceneview/` — `plugin.json`, `mcp_config.json`, `hooks.json`,
   `rules/AGENTS.md`, `skills/…/SKILL.md`), and notes it renders interactive artifacts in
   a side pane — a way around §5's iframe problem for developers, if not for consumers.

**What it says we missed on the Google side:**

- **Google Scene Viewer** (`intent://arvr.google.com/scene-viewer/1.0?…`) — a zero-install
  AR runtime already on-device via Play Services, launchable from any chat link or QR with
  1:1 scale and plane detection, with automatic 3D fallback. Its claim is that this
  "bypasses chat iframe CSP" entirely and that §4.2 should target it *alongside* our own
  deep link rather than building a bespoke flow. This is the single most concrete item in
  the pass, and it is cheap to verify.
- **Gemini Enterprise is not "one docs page, zero code".** Claims Vertex AI Agent Builder
  wants an **OpenAPI 3.0** manifest and OAuth2/OIDC bearer validation, making §6 item 10 a
  generator plus a gateway auth change. ⚠️ Directly contradicts §2's "customer points it at
  any Streamable HTTP MCP" and is **unverified** — check Google's primary docs before
  re-planning.
- **Gemini Multimodal Live API** (bidirectional WebSocket audio + video) as a real-time
  alternative to §4.1's asynchronous capture upload.
- **AppFunctions detail** — `androidx.appfunctions:appfunctions-compiler`, `@AppFunction`
  on Kotlin suspend functions, and a `PendingIntent` hand-off into our camera Activity for
  live capture, result returned by structured IPC.

Worth noting where this lands: `agy` reached "§3.1's fix is not a one-day job" from the
runtime/sandbox side while reasoning from the plan's own (wrong) mimeType table. §4 below
gets to a harder version of the same conclusion from the specs. Two independent routes to
"do not implement Tier A item 1 as written".

---

## 4. The MCP Apps / Apps SDK contract, verified against the primary specs

This is the task where being wrong would be self-defeating, so: sources are the official
spec repository (`modelcontextprotocol/modelcontextprotocol`, read through `gh api`) and
`developers.openai.com/apps-sdk`.

### 4.1 §3.1's table is wrong. The mimeType we ship is CORRECT

**Both** contracts have converged on `text/html;profile=mcp-app`. The two strings §3.1
tells us to migrate to are historical.

`text/html;profile=mcp-app` in the official spec repo — normative locations, not blog
posts:

```
seps/1865-mcp-apps-interactive-user-interfaces-for-mcp.md
docs/extensions/overview.mdx
docs/specification/2026-07-28/basic/versioning.mdx
docs/specification/draft/basic/versioning.mdx
schema/2026-07-28/examples/ClientCapabilities/extensions-ui-mime-types.json
schema/draft/examples/ClientCapabilities/extensions-ui-mime-types.json
seps/2133-extensions.md
```

`text/html+mcp` in the same repo — **one** occurrence, in a superseded announcement:

```
blog/content/posts/2025-11-21-mcp-apps.md:  mimeType: "text/html+mcp"
```

`text/html+skybridge` — there is an **official migration guide away from it**,
`modelcontextprotocol/ext-apps/docs/migrate_from_openai_apps.md`, whose conversion table
is unambiguous about the direction of travel:

```
line 26:  | UI Resource MIME type: `text/html+skybridge` | UI Resource MIME type: `text/html;profile=mcp-app` |
line 32:  | `_meta["openai/outputTemplate"]`              | `_meta.ui.resourceUri`  | URI of UI resource |
line 52:  | `text/html+skybridge` | `text/html;profile=mcp-app` | Auto-set by `registerAppResource()`;
                                                                 use `RESOURCE_MIME_TYPE` constant if manual |
line 196: 5. **Resource MIME Type**: `text/html+skybridge` → `text/html;profile=mcp-app`
```

So `text/html+skybridge` and `openai/outputTemplate` are the **left-hand column** — the
strings being migrated *from*. `ai-surfaces-exploration.md` §3.1 read that pairing out of
secondary sources and pointed the arrow backwards.

`text/html+skybridge` is likewise **not present** on the current Apps SDK page
(`developers.openai.com/apps-sdk/build/custom-ux`), which instead states:

> "Expose the component as an MCP resource with the MCP Apps UI MIME type
> (`text/html;profile=mcp-app`). If you use `@modelcontextprotocol/ext-apps/server`,
> prefer `RESOURCE_MIME_TYPE` instead of embedding the string"

And on the `_meta` key, same page:

> "The compatibility aliases remain available for existing integrations. New UI should
> use the shared fields and bridge methods in the middle column."
>
> "For broader MCP Apps compatibility, use `_meta.ui.resourceUri`. ChatGPT also honors
> `_meta["openai/outputTemplate"]` as a compatibility alias."

**Corrected table — replaces §3.1's:**

| Host | mimeType | tool `_meta` key |
|---|---|---|
| MCP Apps (SEP-1865, `io.modelcontextprotocol/ui`) | `text/html;profile=mcp-app` | `_meta.ui.resourceUri` → a `ui://` URI |
| OpenAI Apps SDK / ChatGPT | `text/html;profile=mcp-app` | `_meta.ui.resourceUri`; `openai/outputTemplate` is a **legacy compatibility alias** |
| `text/html+skybridge` | superseded, absent from current docs | — |
| `text/html+mcp` | superseded, 2025-11-21 blog post only | — |
| **ours** (`widgets.ts:26`, live, §1.2) | `text/html;profile=mcp-app` ✅ | `_meta.ui.resourceUri` ✅ *but in the wrong place — §4.3* |

**Consequence: Tier A item 1 as written ("dual-emit `text/html+skybridge` +
`text/html+mcp`") would BREAK a currently-correct resource.** Do not do it. Drop it from
the plan. Had we implemented §3.1 as specified, we would have shipped exactly the class of
bug it claimed to be fixing.

Both official code samples confirm the shape (`docs/extensions/apps/build.mdx:290-325`,
and the Apps SDK page's `registerAppResource`/`registerAppTool` samples):

```ts
const resourceUri = "ui://get-time/mcp-app.html";
// on the TOOL:
_meta: { ui: { resourceUri } },
// on the RESOURCE:
{ uri: resourceUri, mimeType: RESOURCE_MIME_TYPE, text: html }
```

### 4.2 Real defect #1: the gateway declares no `extensions` capability

MCP Apps is an **opt-in extension**, and the spec is explicit about negotiation
(`docs/extensions/client-matrix.mdx`):

> "Extensions are always opt-in: a client only uses an extension if **both client and
> server declare support in the `extensions` field** of their capabilities."

The extension identifier is `io.modelcontextprotocol/ui`, and the declaration looks like
(`schema/2026-07-28/examples/ClientCapabilities/extensions-ui-mime-types.json`):

```json
{ "extensions": { "io.modelcontextprotocol/ui": { "mimeTypes": ["text/html;profile=mcp-app"] } } }
```

Our live `initialize` response (§1.1) has no `extensions` key, and
`transport.ts:317-330` has no code path that would emit one. The `extensions` framework
arrived with **2026-07-28** (SEP-2133) — so §3.2's version bump is not a hygiene item
running in parallel with §3.1: **it is the prerequisite.** A server pinned to
`2025-03-26` has no protocol slot in which to declare the UI extension.

This re-orders Tier A: the version bump comes first and item 1 dissolves into it.

### 4.3 Real defect #2: `_meta.ui.resourceUri` is on the tool *result*, not the tool *description*

The spec puts it on the declaration, for a stated reason
(`docs/extensions/apps/overview.mdx:53`):

> "**UI preloading**: The tool *description* includes a `_meta.ui.resourceUri` field
> pointing to a `ui://` resource. The host can preload this resource before …"

Measured on the live gateway — 67 tools returned, and **not one** carries the pointer:

```
$ tools/list on /mcp/public | grep -c resourceUri     → 0
$ tools/list on /mcp/public | grep -c outputTemplate  → 0
```

Confirmed in source: `handleToolsList()` (`transport.ts:270`) emits plain definitions,
while the pointer is attached to the *result* of `tools/call`
(`transport.ts:405-407`, `r._meta = { …, ui: { resourceUri: widgetUri } }`). The
file's own comment says as much: *"Widget tools attach `_meta.ui.resourceUri` so
OpenAI-Apps-aware clients …"* — on results.

A host that decides whether to render a widget by reading `tools/list` therefore never
learns the widget exists. This, plus §4.2, is a far better explanation of "renders in zero
clients" than the mimeType theory — and unlike that theory, both halves are measured
rather than inferred.

### 4.4 Also verified while there

- Current spec revision is **`2026-07-28`**; published revisions are `2024-11-05`,
  `2025-03-26`, `2025-06-18`, `2025-11-25`, `2026-07-28`.
- ChatGPT **and** Claude (web + Desktop) both appear in the MCP Apps support column of
  `docs/extensions/client-matrix.mdx`, alongside VS Code Copilot, Microsoft 365 Copilot,
  Cursor, Goose, Postman, MCPJam, Archestra.AI, PostHog Code. §2's premise — that fixing
  this unlocks two directories at once — holds; the matrix is community-maintained, so
  treat the list as indicative.
- The draft spec moves negotiation to `server/discover` plus an
  `io.modelcontextprotocol/clientCapabilities` block in each request's `_meta`. Worth
  reading before implementing §4.2 so the work is not immediately stale.

---

## 5. Could not verify — the honest list

1. **`wrangler` anything.** Not installed; no Node/npm on this machine (§2.2). Deployment
   dates, the Cloudflare account, and the commit behind the live `hub-mcp` build are all
   still unknown.
2. **The OpenAI-side adversarial review.** `codex` is not installed here; the leg SKIPped
   (§3.1). Nothing in this document substitutes for it.
3. **The exact commit the live `hub-mcp` build was deployed from** (§2.1) — measurement
   harness failed, not pursued.
4. **Every `agy` claim in §3.2.** None was checked. Three are cheap and worth doing first
   because they would move the plan: (a) Gemini Enterprise needing OpenAPI 3.0 + OAuth2
   for Vertex AI Agent Builder, which contradicts the exploration plan outright;
   (b) `@AppFunction` being buildable and locally testable *without* trusted-tester
   approval, which would remove Tier C's "apply and wait" dependency; (c) Scene Viewer's
   `intent://arvr.google.com/scene-viewer/1.0?…` covering §4.2 with no app install.
5. **Whether the widget actually renders** once §4.2 and §4.3 are fixed. No probe was run
   in a real ChatGPT or Claude client — that is still Tier A item 3.
6. **Stripe.** Read-only mandate respected; no Stripe API call was made from this
   session. The webhook endpoint's live state is carried over from
   `ai-surfaces-cleanup.md` §4, not re-measured.

## 6. What this changes in the two plans

- **`ai-surfaces-exploration.md` §3.1** — wrong, and actively harmful as written. Replace
  with §4.1's table. **Tier A item 1 must not be implemented.**
- **§3.2** — upgraded from hygiene to **prerequisite**, and it is four revisions behind,
  not three (§4.2).
- **New Tier A item** — put `_meta.ui.resourceUri` on tool *descriptions* in
  `tools/list`, and declare `io.modelcontextprotocol/ui` in `initialize` capabilities
  (§4.2, §4.3). This is the actual "widget renders nowhere" fix.
- **`ai-surfaces-cleanup.md` §1.2 / Wave 3 item 10** — the hub source is **not** lost
  (§2). The decision (fold in / own repo / retire) can be made on real code. But restoring
  it is a live behaviour change, 52 → 78 tools (§2.1), not a restore.
- **§0 of both plans** — the Workers are live and confirmed (§1); that assumption can be
  retired.
- **`multi-llm-delegation.md` §1** — stale: `codex` is no longer installed on this Mac.

## 6b. Off-topic but measured here: a false RED in the #3159 foreign-tree detector

Found by running `pre-push-check.sh` for this change, so it is recorded rather than
dropped. Unrelated to AI surfaces; **pre-existing on `main`** — this branch does not touch
`.claude/scripts/lib/gradle-run.sh` or its self-test.

`gradle_foreign_tree_paths()` (`gradle-run.sh:108`) matches
`(file://)?/[^ :"']*/src/[^ :"']*`. That pattern can start matching at a slash **inside a
relative path**, so an ordinary in-repo log line is read as an absolute path belonging to
another checkout. Minimal reproducer:

```
$ printf 'OK: samples/android-demo/src/main/java/X.kt in sync\n' > /tmp/rel.log
$ gradle_foreign_tree_paths /tmp/rel.log "$PWD"
/android-demo/src/main/java/X.kt          ← should be empty
```

Real consequence in this run: `roborazzi.log:148` contains
`OK: samples/android-demo/src/main/java/io/github/sceneview/demo/fragments/GeneratedDemos.kt
already in sync with 54 fragment(s).`, and the gate graded a healthy leg as unrun —
`⚠ :samples:android-demo tests did not run to a verdict — its log describes a DIFFERENT
checkout`. `test-gradle-run.sh:742-753` catches it honestly
(`✗ 1 real gate log(s) trip the check — it would block this repo's own clean runs`),
which is why the gate reports 1 failure on a documentation-only change.

Likely fix: anchor the match so only a genuine absolute path qualifies (require the match
to start at a line start or after a non-path character), and add the relative-fragment
case to the self-test. Needs its own PR — it is gate internals, not this branch's subject.

## 7. Commands, for reproduction

```bash
# §1 — liveness
for h in sceneview-mcp hub-mcp sceneview-telemetry; do
  for p in /health / /pricing; do
    curl -sS -m 20 -o /dev/null -w "%{http_code} $h$p\n" \
      "https://$h.mcp-tools-lab.workers.dev$p"
  done
done

# §1.1 — live protocol version
curl -s -X POST https://sceneview-mcp.mcp-tools-lab.workers.dev/mcp/public \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2026-07-28","capabilities":{},"clientInfo":{"name":"probe","version":"1.0.0"}}}'

# §2 — the hub source
git log --all --diff-filter=D --name-only -- 'hub-gateway/*' 'hub-mcp/*'
git ls-tree -r --name-only c1a5c99f4e^ -- hub-gateway hub-mcp
git show c1a5c99f4e^:hub-gateway/wrangler.toml

# §4 — the specs
gh search code --limit 20 'profile=mcp-app repo:modelcontextprotocol/modelcontextprotocol'
gh api repos/modelcontextprotocol/modelcontextprotocol/contents/docs/extensions/apps/build.mdx \
  --jq .content | base64 -d
```

## 8. Sources (primary, fetched 2026-08-15)

- `modelcontextprotocol/modelcontextprotocol` @ `main`, via `gh api` /
  `gh search code` — `seps/1865-…`, `seps/2133-extensions.md`,
  `docs/extensions/apps/{overview,build}.mdx`, `docs/extensions/client-matrix.mdx`,
  `docs/specification/2026-07-28/basic/versioning.mdx`,
  `schema/2026-07-28/examples/ClientCapabilities/extensions-ui-mime-types.json`
- [`ext-apps` — Migrate from OpenAI Apps](https://github.com/modelcontextprotocol/ext-apps/blob/main/docs/migrate_from_openai_apps.md)
  — the authoritative `skybridge` → `profile=mcp-app` conversion table
- [Apps SDK — custom UX](https://developers.openai.com/apps-sdk/build/custom-ux)
- [Apps SDK — MCP server](https://developers.openai.com/apps-sdk/build/mcp-server)
- [MCP Apps — Migrate OpenAI App](https://apps.extensions.modelcontextprotocol.io/api/documents/migrate-openai-app.html)
- Live probes of `*.mcp-tools-lab.workers.dev` (§7 reproduces them)
- `git` history of this repository at `c1a5c99f4e`
- `agy` (Antigravity CLI) via `.claude/scripts/llm-delegate.sh gemini` — **advisory,
  unverified**

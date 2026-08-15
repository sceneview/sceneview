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
>
> **Revision 2 (same day).** Two of revision 1's own "could not verify" items were wrong,
> not unverifiable, and are corrected in place rather than quietly dropped: `wrangler` and
> Node *were* installed (off `PATH` — §2.3), and the live `hub-mcp` commit *is* pinnable
> (§2.1). Struck-through entries in §5 mark what moved. Where revision 1 stated something
> that turned out to be false, the false statement is quoted before the correction — a
> retraction that hides what it retracts cannot be audited.

**Headline: the exploration plan's single BLOCKING finding (§3.1, "the widget declares a
mimeType nobody implements") is WRONG.** The mimeType is correct and matches both current
specs. Two *different*, real defects were found in its place. See §4.

**Second headline, from revision 2: the live `hub-mcp` Worker is `e9d04f4adf` (2026-04-12),
six commits behind the deleted tree — including both dependency-security commits — and its
deprecated-but-installable npm client `hub-mcp@0.3.0` still points at it.** See §2.1.

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

So the live Worker was deployed from an **earlier** tree than the one that was deleted.
Redeploying `c1a5c99f4e^` verbatim would *change live behaviour* (52 → 78 tools) — which
means "restore from git history" is a real change, not a no-op, and needs a diff review
rather than a `wrangler deploy`.

#### The live build is `e9d04f4adf` — pinned, triple-corroborated

The first version of this section listed the commit as *could not verify*. `wrangler` was
then found and run (§2.2), which pins it. Mind the timezone — this is the whole crux:
the live deploy is `2026-04-12T17:37:30.770Z` = **`19:37:30+02:00`**, and hub-gateway
commit dates in this repo are `+02:00`.

| Evidence | Value |
|---|---|
| Last `hub-mcp` deployment (`wrangler deployments list`) | `2026-04-12T17:37:30.770Z` |
| `e9d04f4adf` commit date | `2026-04-12T19:37:09+02:00` = `17:37:09Z` — **21 s earlier** |
| Registry count at `e9d04f4adf` | **52**, matching `/health` library-by-library |
| `hub-mcp@0.1.0` on npm, published `17:46:34Z` (9 min later) | description: "**52** AI tools across 11 libraries" |

`e9d04f4adf fix(hub-gateway): fix test assertions to match actual registry state` is
therefore the tree that is live. The per-library counts at that commit are identical to
the deployed table above, not merely equal in total:

```
e9d04f4adf TOTAL=52 | architecture=10 automotive-3d=9 ecommerce-3d=3 education=3
                      finance=3 french-admin=4 health-fitness=3 healthcare-3d=7
                      legal-docs=3 realestate=4 social-media=3
```

Two corrections to the first version of this section:

- **The earlier count loop returning 0 was my bug, and it is fixed.** It iterated over a
  hardcoded path list instead of `git ls-tree`, and had no fallback for the two thin
  re-exports. With `git ls-tree` + the fallback, every commit counts cleanly. So §2.1 no
  longer rests on an unexplained measurement failure.
- **`ee441e4785` does not corroborate the 78.** It touches `hub-mcp/` — the *separate*
  stdio client package (see below) — not `hub-gateway/`. The hub-gateway registry actually
  reached 78 in **`9452e15a78 chore: bump version 4.0.0 → 4.0.1`**, a commit whose message
  mentions no tools at all. Measured: `aae0fd5a56` → 52, `9452e15a78` → 78. A +26-tool
  registry upgrade rode in under a version-bump subject line; worth knowing before anyone
  reads that history as documentation.

#### Six commits landed after the live build

Everything in `hub-gateway/` after `2026-04-12T19:37:30+02:00`, i.e. **not** in the running
Worker:

| Commit | Date | Subject |
|---|---|---|
| `aae0fd5a56` | 2026-04-13 | `fix(hub-gateway): update FREE_TOOLS count 14→23 …` |
| `9452e15a78` | 2026-04-13 | `chore: bump version 4.0.0 → 4.0.1` (**this is the 52→78**) |
| `6637a58c72` | 2026-05-05 | `chore(security): bump hono → 4.12.17 and postcss → 8.5.14 (13 Dependabot alerts)` |
| `a155966bab` | 2026-05-06 | `chore(deps): npm audit fix — clear 8 ip-address moderate vulns` |
| `a88f7f8c58` | 2026-05-07 | `chore(security+plugins): CDI-safety scrub + multi-agent review fixes` |
| `c1a5c99f4e` | 2026-05-07 | `chore(security): remove off-topic personal-portfolio code from public repo` |

The consequence is stated plainly because it is the one operational finding here: **the
live money-handling Worker predates both dependency-security commits.** It is running the
pre-bump `hono`/`postcss`, i.e. the tree that the 13 Dependabot alerts and the 8
`ip-address` advisories were filed against. Whether any of those are reachable in a Worker
runtime is *not* assessed here — that needs the advisory list against the actual call
paths, and this pass was read-only verification. Recorded as a finding, not as a severity.

Also missing from the live build by 42 seconds: `2a191f2c04 fix(gateway): hub-mcp KV
handoff + docs stdio + landing tool count (#816)`, committed `17:38:12+02:00`.

#### There was a second component, and it is published on npm

`c1a5c99f4e` deleted **two** directories, and the first version of this document named only
one:

- `hub-gateway/` — the Worker source, deployed as `hub-mcp` (all of §2 above).
- `hub-mcp/` — a stdio MCP client that *proxies to that Worker*. `src/proxy.ts:18,22`
  hardcode `https://hub-mcp.mcp-tools-lab.workers.dev/mcp` and `…/pricing`;
  `src/telemetry.ts:23,25` hardcode the telemetry Worker's `/v1/events` and `/v1/batch`.

That client is **still on the public npm registry** and still installable:

```
$ curl -s https://registry.npmjs.org/hub-mcp | …
name: hub-mcp   dist-tags: {latest: 0.3.0, beta: 0.3.0}
versions: 0.1.0 (2026-04-12T17:46:34Z), 0.2.0, 0.2.1, 0.3.0 (2026-04-13T07:30:34Z)
maintainers: [thomasgorisse]
tarball https://registry.npmjs.org/hub-mcp/-/hub-mcp-0.3.0.tgz -> HTTP 200, 14290 bytes
```

**All four versions are deprecated**, which is the mitigating fact and the reason this is
not an incident:

```
deprecated: "Project discontinued — the MCPs it aggregated are unrelated and have been
             split. Install each MCP individually instead."
```

The wind-down was deliberate and coherent: registry `modified` is
`2026-05-07T22:04:44.876Z`, i.e. the deprecation was applied ~13 minutes *after* the
deletion commit (`21:51:46Z`).

The published tarball was then unpacked and read directly, so the following is measured on
the **artifact**, not inferred from the deleted source:

```
$ curl -sO https://registry.npmjs.org/hub-mcp/-/hub-mcp-0.3.0.tgz && tar xzf hub-mcp-0.3.0.tgz
$ grep -rhoE 'https://[a-z0-9.-]*\.workers\.dev[^"'\'' )]*' package/ | sort -u
https://hub-mcp.mcp-tools-lab.workers.dev/mcp
https://hub-mcp.mcp-tools-lab.workers.dev/pricing
https://sceneview-telemetry.mcp-tools-lab.workers.dev/v1/batch
https://sceneview-telemetry.mcp-tools-lab.workers.dev/v1/events
$ grep -coE '"?name"?: ?"' package/dist/tools.js
78
```

So, concretely, an `npm i hub-mcp` today still yields a working client that (a) points at
the live billable Worker's `/mcp` and `/pricing`, and (b) posts to the live telemetry
Worker. npm deprecation prints a warning; it does not block install.

And the 52-vs-78 gap is not only in the description — **the shipped client's own tool table
has 78 entries while the Worker it forwards to serves 52.** Per the package's own model
("free tier runs locally; Pro tools forward to the hosted gateway"), the local tools are
unaffected, but any forwarded call for one of the 26 tools that exist only in the client
reaches a gateway that never had them. That is a real functional mismatch with both ends
measured, and it is the reason this subsection exists rather than being a footnote: it is
the one place where the incomplete wind-down is user-visible.

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

### 2.3 `wrangler` DID run — correcting this document's own first version

The first version of this section reported `wrangler` and Node as absent, on the strength of
`command -v wrangler npx node pnpm yarn bun` returning nothing. **That conclusion was
wrong, and the method is the lesson:** `command -v` only sees what is on `PATH`, and this
machine keeps Node under `nvm` and `wrangler` as a project-local devDependency — neither is
on a non-interactive shell's `PATH`. Both were present the whole time:

```
node     ~/.nvm/versions/node/v22.14.0/bin/node          (v22.14.0)
wrangler ~/Projects/sceneview/mcp-gateway/node_modules/.bin/wrangler   (4.95.0)
state    ~/Library/Preferences/.wrangler/   (126 logs, most recent today)
```

So the corollary drawn from the absence — "the repo's JS/TS gates cannot run on this
machine" — was also wrong, and is withdrawn.

Read-only commands only; no `deploy`, no `secret`, no `d1`. Authenticated:

```
$ wrangler whoami
Account Name: Thomas Gorisse's Account
Token type:   OAuth Token
… scopes include workers (write), d1 (write)
```

(Email `thomas.gorisse@gmail.com`. The account ID is deliberately filtered out of this
document — public repo.)

`wrangler deployments list --name <worker>`, latest deployment per Worker:

| Worker | Last deployment | In the plans? |
|---|---|---|
| `hub-mcp` | `2026-04-12T17:37:30.770Z` | yes — pins §2.1 |
| `sceneview-mcp` | `2026-07-17T11:01:31.285Z` | yes |
| `sceneview-telemetry` | `2026-04-16T21:55:18.551Z` | yes |
| **`arcamera-api`** | `2026-08-14T12:27:58.776Z` | **no — in neither plan** |

Three things to take from that table:

1. **`sceneview-mcp` is deployed == committed.** Zero commits touch `mcp-gateway/` after
   `2026-07-17T11:01:31Z` (`git log --since` → 0). This matters more than it looks: it
   means §1.1's live `2025-03-26` and the two defects in §4.2/§4.3 — both read out of
   source — are genuinely what is running, not a source-vs-deployed guess. For Gateway #1,
   "deployed ≠ committed" is currently a distinction without a difference.
2. **`arcamera-api` is a fourth Worker under the same account**, absent from both plans,
   and the only one deployed recently (yesterday). It belongs to the `ar-model-viewer`
   project, not to sceneview. Flagged for inventory completeness; not investigated, since
   it is outside this task's scope.
3. **`wrangler deployments list` returns at most 10 entries.** Each Worker above showed
   exactly 10, so these are the *latest* 10 and the true first-deploy dates are truncated
   and unknown. Only the **last** deployment per Worker is a measured fact — which is the
   one that matters, and is what the table reports. (An earlier note in this session read
   the oldest of the 10 as "earliest deployment ever" and reasoned from it toward a
   different commit; that inference was unsound and is dropped.)

**Still not verified on the hub:** whether the Cloudflare secrets (Stripe keys, D1/KV
bindings) currently set on the live `hub-mcp` still match what `wrangler.toml` at
`e9d04f4adf` expects. Reading them requires `wrangler secret list`, and the sanctioned
mandate here was read-only against billable state; a secret is exactly the case CLAUDE.md
says to stop on. Left for Thomas.

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

Items 1 and 3 of this list were **resolved later in the same session** and are kept here,
struck, rather than silently deleted — a "could not verify" that quietly disappears is
indistinguishable from one that was never asked.

1. ~~**`wrangler` anything.**~~ **RESOLVED — §2.3.** `wrangler` 4.95.0 and Node v22.14.0
   were both present, off `PATH`; `whoami` and `deployments list` ran read-only. What
   remains unverified on the hub is narrower: the **live Cloudflare secrets** (`wrangler
   secret list` not run — secrets are a stop condition, not a read).
2. **The OpenAI-side adversarial review.** `codex` is not installed here; the leg SKIPped
   (§3.1). Nothing in this document substitutes for it. **Still open** — this is the
   largest single gap in the whole pass, and no other finding covers it.
3. ~~**The exact commit the live `hub-mcp` build was deployed from.**~~ **RESOLVED —
   §2.1.** It is `e9d04f4adf`, pinned by a 21-second deploy/commit gap, an exact
   per-library count match, and the npm description of the build published 9 minutes later.
4. **`agy`'s claims in §3.2** — one checked, two still open:
   - (b) **CONFIRMED.** `developer.android.com/ai/appfunctions` states AppFunctions "is in
     an experimental preview" and that Gemini integration is "in a private preview with
     trusted testers", but answers the operative question directly in its own FAQ: *"I'm an
     app developer. Can I implement AppFunctions today?"* → *"Yes, it's possible to
     implement and test AppFunctions within your app."* So `agy` is right that Tier C's
     "apply and wait" dependency can be dropped: the code and its tests are unblocked
     today; only the *Gemini-facing* activation waits on the trusted-tester program. Those
     are two separate gates, and the plan currently conflates them into one.
   - (a) **Still unverified — and not for lack of trying.** Gemini Enterprise needing
     OpenAPI 3.0 + OAuth2 (which contradicts the exploration plan outright).
     `cloud.google.com/gemini-enterprise/docs/mcp` redirects, and the redirect target
     returns 404. Google's own docs URL for the claim does not currently resolve, so the
     contradiction stands unadjudicated. **Do not re-plan §6 item 10 on either version of
     this until someone reads a page that loads.**
   - (c) **Still unverified.** Scene Viewer's
     `intent://arvr.google.com/scene-viewer/1.0?…` covering §4.2 with no app install. Cheap
     to settle, but it needs a device or emulator, not a fetch — so it belongs to a
     `device-qa` pass, not to this one.
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
  it is a live behaviour change, 52 → 78 tools (§2.1), not a restore. The live tree is
  `e9d04f4adf`; diff against that, not against `c1a5c99f4e^`.
- **The hub has a second component, and the cleanup plan tracks only one** — `hub-mcp` the
  npm stdio client, deprecated but still installable and still pointing at the live billable
  Worker (§2.1). Any retire/fold decision has to cover both, or the client outlives the
  Worker it proxies to.
- **The live hub predates both dependency-security commits** (§2.1). Not triaged here;
  belongs in whatever decision is made about the hub, and is a reason not to leave it
  running untouched by default.
- **`arcamera-api`** — a fourth Worker in neither plan (§2.3). The Cloudflare inventory both
  plans work from is incomplete.
- **§0 of both plans** — the Workers are live and confirmed (§1); that assumption can be
  retired. For Gateway #1, "deployed ≠ committed" is also settled: they are equal (§2.3).
- **`ai-surfaces-exploration.md` Tier C (AppFunctions)** — the "apply and wait" dependency
  can be dropped. Implementation and local testing are unblocked today; only Gemini-facing
  activation waits on trusted-tester (§5 item 4b).
- **`multi-llm-delegation.md` §1** — stale: `codex` is no longer installed on this Mac.
  Worth adding the `PATH` lesson from §2.3 to that plan's own probing advice: `command -v`
  is not a tool-availability check on this machine, where Node lives under `nvm` and CLIs
  live in project-local `node_modules/.bin`.

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

**Re-verified in revision 2, with a caveat that matters.** On a later run of the same gate
the self-test **passed** — not because the bug was fixed, but because that run produced no
`roborazzi.log` containing the triggering line. The bug itself is unchanged:

```
$ source .claude/scripts/lib/gradle-run.sh
$ printf 'OK: samples/android-demo/src/main/java/X.kt in sync\n' > /tmp/rel.log
$ gradle_foreign_tree_paths /tmp/rel.log "$PWD"
/android-demo/src/main/java/X.kt          ← still wrong
```

So this defect is **intermittent at the gate level and deterministic at the function
level**. A green self-test here is evidence about which logs happened to exist, not about
the detector. Anyone who "cannot reproduce" it should run the three lines above rather than
re-run the gate.

## 6c. A second false RED, same root cause as §2.3: `node` off `PATH`

Also measured while gating this change, and worth recording because it is the same `PATH`
mistake as §2.3 — this time made by the gate rather than by me.

Run with a bare non-interactive `PATH`, `pre-push-check.sh` reported:

```
✗ 3 CHECK(S) FAILED — DO NOT PUSH
[18/22] ⚠ MCP tool-claim gate NOT checked — the checker could not be executed (exit 127)
        .claude/scripts/pre-push-check.sh: line 591: node: command not found
[20/22] ✗ 1 of 42 gate self-test(s) failed
        → 11 of 12 test-check-mcp-tool-claims.sh cases failed, every one rc=127
```

Re-run with `PATH="$HOME/.nvm/versions/node/v22.14.0/bin:$PATH"` and the same tree, same
commit, goes to **0 failures**:

```
[18/22] ✓ check-mcp-tool-claims: OK — 109 prose file(s) scanned, 67 known tools, 38 samples
[20/22] ✓ 42 gate self-test(s) pass
```

Two things follow:

- **A missing interpreter is being graded as a failed check.** `exit 127` is
  "cannot execute", which is the gate's own "could not run" category — it has one, it uses
  it correctly at `[18/22]` ("NOT checked"), and then the self-test leg at `[20/22]` counts
  the same 127s as substantive failures and turns the verdict RED. `DO NOT PUSH` on a
  machine that simply keeps Node under `nvm` is a false RED, and it is the kind that
  teaches people to push anyway.
- **Incidental confirmation of §1.2**: the tool-claim gate independently reports
  **67 known tools**, matching the live `tools/list` count measured against the deployed
  Worker. Two unrelated methods, same number.

Suggested fix, in the same spirit as §6b and likewise its own PR: resolve `node` through
`nvm`'s default (or fail the *self-test* as "could not run" rather than "failed") so a
127 never colours the verdict.

## 6d. `apiCheck` could not be verified locally — and CI says it is fine

Stated plainly rather than buried, because it is the one leg this change did not get a
local verdict on.

`apiCheck` fails in this worktree with ~200 `Unresolved reference 'compose'` errors in
`sceneview-compose/src/commonMain/.../CameraState.kt`. It is **not** this change and not
this branch:

- The branch touches no Kotlin and no Gradle config — `git diff --name-only
  origin/main...HEAD -- sceneview-compose/ gradle/ '*.gradle.kts' gradle.properties` is
  empty. This commit changes exactly one Markdown file.
- `apiCheck` is a **blocking** job in `ci.yml:558` (`./gradlew apiCheck --stacktrace`), and
  the `CI` run on `main` at `2026-08-15T12:43:31Z` is `success`.
- It is not the evicted-cache class either: it survives `--refresh-dependencies` and a
  `rm -rf sceneview-compose/build .kotlin`, failing in ~500 ms.

So: local host state in this worktree, gated green by CI, cause not diagnosed — chasing it
further is off-task for a verification pass. The gate's own final verdict on this change is
`0 checks failed, 1 could not run`, and that one is this.

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

# §2.1 — pin the live build. Counts every library, incl. the two thin re-exports.
# NOTE: the earlier version of this loop hardcoded paths and silently returned 0.
for c in e9d04f4adf aae0fd5a56 9452e15a78 c1a5c99f4e^; do
  tot=0
  for f in $(git ls-tree --name-only "$c" hub-gateway/src/libraries/ | grep -v index.ts); do
    lib=$(basename "$f" .ts); n=$(git show "$c:$f" | grep -cE '^    name: "')
    case "$lib" in
      automotive-3d) [ "$n" -eq 0 ] && n=$(git show "$c:mcp/packages/automotive/src/tools.ts" | grep -cE '^    name: "');;
      healthcare-3d) [ "$n" -eq 0 ] && n=$(git show "$c:mcp/packages/healthcare/src/tools.ts" | grep -cE '^    name: "');;
    esac
    tot=$((tot+n))
  done
  echo "$c TOTAL=$tot"
done   # -> e9d04f4adf 52, aae0fd5a56 52, 9452e15a78 78, c1a5c99f4e^ 78

# §2.1 — commits after the live deploy. Deploy is UTC, commits are +02:00.
git log --date=iso-strict --format='%h %ad %s' --all \
  --since='2026-04-12T17:37:30Z' -- hub-gateway/

# §2.1 — the npm client that survives the deletion
curl -s https://registry.npmjs.org/hub-mcp | python3 -c \
  'import json,sys; d=json.load(sys.stdin); print(d["dist-tags"], d["time"]["modified"]); \
   [print(v, repr(m.get("deprecated","NO"))) for v,m in d["versions"].items()]'

# §2.3 — wrangler. READ-ONLY: no deploy, no secret, no d1.
export PATH="$HOME/.nvm/versions/node/v22.14.0/bin:$PATH"
cd ~/Projects/sceneview/mcp-gateway
./node_modules/.bin/wrangler whoami
for w in hub-mcp sceneview-mcp sceneview-telemetry arcamera-api; do
  ./node_modules/.bin/wrangler deployments list --name "$w" | grep '^Created:' | tail -1
done   # NB: lists at most 10 deployments — only the LAST one is a fact

# §2.3 — Gateway #1 deployed == committed
git log --oneline --all --since='2026-07-17T11:01:31Z' -- mcp-gateway/ | wc -l   # -> 0

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
  unverified** except the AppFunctions claim (§5 item 4b)
- [`developer.android.com/ai/appfunctions`](https://developer.android.com/ai/appfunctions)
  — preview status and the "can I implement today?" FAQ answer
- npm registry API, `registry.npmjs.org/hub-mcp` — versions, timestamps, deprecation
- `wrangler` 4.95.0 (`whoami`, `deployments list`) — read-only, §2.3

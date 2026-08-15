# AI surfaces exploration — connectors, MCP Apps, plugins, and the AR bridge

> Exploration session 2026-08-15. Question asked: *is there anything worth building at
> the connector / MCP / plugin level — e.g. attaching AR-measured dimensions to a photo
> dropped into a chat?*
> Status: **research + design only, nothing implemented.** Every "measured" line below
> was verified in this repo or from a dated public source (§9); everything else is
> marked as an assumption to test.

> **Companion note:** [`ai-surfaces-cleanup.md`](./ai-surfaces-cleanup.md) is the
> état des lieux — what already exists across the portfolio, what of it is factually
> wrong today, and which assistants we should keep carrying. Read that one first if the
> question is *what do we clean up?* rather than *what do we build?*

## 0. What could NOT be done in this session

The request was to brainstorm *with* Gemini and ChatGPT. That was not possible here and
the honest reason matters for the next session:

```
$ bash .claude/scripts/llm-delegate.sh codex  "ping"
SKIP: codex CLI not installed (npm i -g @openai/codex)
$ bash .claude/scripts/llm-delegate.sh gemini "ping"
SKIP: Antigravity CLI not installed (curl -fsSL https://antigravity.google/cli/install.sh | bash)
```

This is a **cloud container**, not the Mac. `codex` / `agy` are installed and
authenticated on the Mac only (see [`multi-llm-delegation.md`](./multi-llm-delegation.md)),
and no `OPENAI_API_KEY` / Google credential is present in the environment. The harness
also blocks direct egress to `modelcontextprotocol.io`, `developers.openai.com`,
`claude.com` and `mcpui.dev`, so the protocol facts below come from web *search* results
(dated, cited) rather than from the primary specs.

**→ Re-run the cross-vendor pass from the Mac** before committing to any of this:

```bash
bash .claude/scripts/llm-delegate.sh codex  --context .claude/plans/ai-surfaces-exploration.md \
  "Adversarial review: which of these bets is wrong, and what did we miss on the OpenAI side?"
bash .claude/scripts/llm-delegate.sh gemini --context .claude/plans/ai-surfaces-exploration.md \
  "Same, from the Google/Gemini side — AppFunctions, Antigravity, Gemini Enterprise."
```

Claude's own take is below; it is a single-vendor opinion until that happens.

## 1. What we already ship (measured in this repo, 2026-08-15)

| Surface | Where | State |
|---|---|---|
| stdio MCP server | `mcp/` → npm `sceneview-mcp@4.0.16` | Live, 31 free tools, 1965 tests |
| Hosted MCP gateway | `mcp-gateway/` → Cloudflare Worker | Live; `/mcp` (Bearer) + `/mcp/public` (anon, 60/h/IP) |
| Vertical packages | `mcp/packages/{automotive,gaming,healthcare,interior,rerun}` | Pro tier, Stripe-gated |
| Apps-SDK widget | `mcp-gateway/src/mcp/widgets.ts` + `widget-tools.ts` | Built (`view_3d_model`), **not renderable — see §3.1** |
| Custom GPT | `pro/gpt-store/gpt-instructions.md` | Instructions only |
| Claude Code plugin | `sceneview/claude-marketplace` | Live — MCP + 11 commands + hooks |
| Agent skills | `agents/sceneview{,-ios,-web}` | Live, installers in `.claude/scripts/` |
| Registries | MCP Registry, Smithery (`smithery.yaml`) | Listed |

The reflex answer to "should we do connectors/MCP?" is therefore **no — we already did**.
The real question is *which of the existing surfaces is broken, unlisted, or aimed at a
channel that has no self-serve door.* That reframing drives everything below.

## 2. The distribution map as it actually stands (Aug 2026)

| Channel | Self-serve? | Our lever |
|---|---|---|
| **ChatGPT App Directory** | ✅ open since 2025-12-17, reviewed | Submit — but only once the widget renders (§3.1) |
| **Claude connectors directory** | ✅ in-app portal, **requires a Team/Enterprise org** | Submit; ~950 servers listed as of 2026 |
| **MCP Apps** (interactive UI in-chat) | ✅ official MCP extension since 2026-01-26, incl. **Claude mobile** | Highest-leverage single fix we have |
| **Gemini app / Spark connectors** | ❌ **partnership-only**, no public submission | None. Do not chase. |
| **Gemini Enterprise** | ✅ customer points it at any Streamable HTTP MCP | One docs page, zero code |
| **Antigravity CLI extensions** | ✅ gallery (replaced the shut-down `gemini` CLI, 2026-06-18) | Cheap mirror of the Claude Code plugin |
| **Android AppFunctions** | ⚠️ private preview, trusted testers, Android 16+ | Apply — the only *native* agentic-AR door |
| **iOS App Intents** | ✅ but reaches Siri/Shortcuts **only**, not ChatGPT/Claude | Low value for chat, real value for Shortcuts |

The asymmetry is the headline: **on the Google side there is no consumer door to knock
on**, so "a Gemini connector" is not a thing we can ship this quarter. The Gemini-shaped
opportunity is *AppFunctions on the Android demo app*, which is a completely different
build — and one that showcases the SDK far better than a docs connector would.

## 3. Three findings that gate everything else

### 3.1 ~~The widget speaks a protocol nobody implements~~ — **THIS WAS WRONG**

**Corrected 2026-08-15 against the primary source. Do not implement what this
section originally said; it would have broken working code.**

The original claim was that `mcp-gateway/src/mcp/widgets.ts:26` declares a
homegrown `text/html;profile=mcp-app` matching no real host, and that the fix was
to emit `text/html+skybridge` + `openai/outputTemplate` instead. That is
backwards. The official
[`ext-apps` migration table](https://github.com/modelcontextprotocol/ext-apps/blob/main/docs/migrate_from_openai_apps.md)
reads:

| | OLD (OpenAI Apps SDK) | NEW (MCP Apps) |
|---|---|---|
| Resource MIME type | `text/html+skybridge` | **`text/html;profile=mcp-app`** |
| Resource metadata | `_meta["openai/widgetCSP"]` etc. | **`_meta.ui.*`** |

So `text/html;profile=mcp-app` and `_meta.ui.*` are the **destination** of the
migration, not a private invention — the gateway already ships the current
contract. `text/html+mcp` survives only in a superseded 2025-11-21 blog post.

**How this got written wrong is the point.** The container had no egress to
`modelcontextprotocol.io`, `developers.openai.com` or `mcpui.dev`, so the table
was assembled from dated *secondary* sources and a `+skybridge` string that was
real but historical. §0 and the section itself both said "verify against the
primary specs before writing code" — that safeguard is the only reason this was
caught before anyone implemented it. Keep writing it.

### 3.1b The two defects that ARE real (measured live, 2026-08-15)

Both verified against `/mcp/public` on the deployed Worker and against source:

1. **`initialize` declares no `extensions` capability.** MCP Apps is opt-in
   through `io.modelcontextprotocol/ui`, and the versioned extensions framework
   arrived with spec revision **2026-07-28**. `transport.ts:318` advertises only
   `tools` / `resources` / `prompts`. This makes §3.2 not parallel hygiene but
   the **prerequisite**: the widget cannot be offered until the server speaks a
   revision that has extensions at all.
2. **`_meta.ui.resourceUri` rides on tool RESULTS, not tool DECLARATIONS.**
   `transport.ts:405` attaches it to the JSON-RPC result; `widget-tools.ts`
   declares no `_meta` at all, and `tools/list` returns 67 tools with zero
   `resourceUri` occurrences. The spec puts the pointer on the declaration so a
   host can preload the UI — a host deciding from `tools/list` never learns the
   widget exists.

Neither is the rewrite §3.1 originally prescribed. Both are smaller, and both
have to land before asking whether the widget renders.

### 3.2 The gateway advertises MCP 2025-03-26 — four revisions behind, and it gates §3.1b

`transport.ts:116` pins `PROTOCOL_VERSION = "2025-03-26"`. The current revision is
**2026-07-28** (stateless request/response, explicitly aimed at serverless/edge — which
is exactly what a Cloudflare Worker is) and it is the one that ships MCP Apps and Tasks
under a versioned extensions framework. Being four revisions behind is a plausible
silent reason for feature-detection failures in newer clients, and the stateless model
should *simplify* the Worker rather than complicate it.

### 3.3 The Claude directory needs an org we may not have

Submission happens inside Claude.ai under **organization admin settings**, gated on a
Team or Enterprise plan. That is an account/billing prerequisite, not an engineering
one — worth checking before planning the submission, because it can block a
merge-ready PR for weeks.

## 4. The AR idea, taken seriously

The original framing — *"add the AR measurements when you add a photo to a Claude
conversation"* — is the strongest idea in this exploration, but it has to be inverted to
be buildable. We cannot hook the chat client's photo picker. What we **can** own is the
capture end: a phone app that measures, and a tool the assistant calls to fetch what was
measured.

### 4.1 SceneView Capture — phone as sensor, chat as interface

```
 ┌── SceneView demo app (Android/iOS) ─────────┐
 │  Measure mode: tap-tap → distance, area,    │
 │  bounding box, room outline                 │
 │  Uses what already exists: DepthMeshNode,   │
 │  Frame.hitTestDepth, plane detection,       │
 │  anchors                                    │
 └────────────────┬────────────────────────────┘
                  │  POST /captures  (photo + measurements +
                  │  camera intrinsics + confidence + units)
                  ▼
 ┌── mcp-gateway (already live, already has ───┐
 │  auth, D1, rate limits, Stripe) │  returns  │
 │  a 6-char pairing code + deep link          │
 └────────────────┬────────────────────────────┘
                  │  MCP tools: list_ar_captures,
                  │  get_ar_capture(code)
                  ▼
 ┌── Any assistant: Claude, ChatGPT, Gemini ───┐
 │  Enterprise, Cursor, Claude Code            │
 │  + MCP App widget: photo with measurement   │
 │    overlay, tap a dimension to re-ask       │
 └─────────────────────────────────────────────┘
```

Why this one is worth it:

- **Vendor-neutral by construction.** One MCP surface, and it lands in every client that
  speaks MCP — which is the only way to be present on the Google side at all today.
- **It is a demo *and* a product.** Every capture is a live proof of what SceneView's AR
  stack does, aimed at the exact developer we want ("I could build this").
- **It reuses everything.** The gateway has auth, D1, KV rate limiting, billing. The AR
  primitives exist. The widget shell exists. The new surface is a capture endpoint, a
  measure UI, and two tools.
- **The questions it unlocks are the ones an LLM is actually good at.** "Will this fit in
  my trunk?" · "How much paint for this wall?" · "Quote me a countertop." A photo alone
  cannot answer those; a photo *with metric ground truth* can.

**Accuracy honesty is a hard requirement, not a nice-to-have.** ARCore's Depth API covers
>87% of active devices (Oct 2025) and Raw Depth is more accurate per-pixel, but published
app-level results land around **10–20 cm at 95% confidence** for building documentation —
even on LiDAR phones. Every capture must carry a confidence interval and the tool
description must tell the model to quote ranges, never a false 3-decimal number. Getting
this wrong is a trust bug, and it is the kind of thing an LLM will happily launder into
confident prose.

**Privacy is the other hard requirement.** A photo of someone's home plus a room
geometry leaving the device is materially more sensitive than anything we ship today.
Non-negotiables: explicit per-capture opt-in, short TTL with real deletion, no capture
in the anonymous `/mcp/public` tier, an update to `mcp/PRIVACY.md`, and — worth
considering — measurements-only mode where the photo never leaves the phone.

### 4.2 The cheap inverse: assistant → phone

Half the value, a tenth of the work, and no privacy surface at all: a tool that returns a
**QR code / deep link** launching AR placement on the phone. "Show me this chair in my
living room" → scan → SceneView AR. No upload, no storage, no consent flow. This is the
right thing to build *first*, and it doubles as the fallback for §5 if in-chat AR turns
out to be impossible.

### 4.3 The Gemini-native variant: AppFunctions

Expose the demo app's capabilities as annotated Kotlin suspend functions
(`measureObject()`, `placeModel()`, `scanRoom()`) so Gemini can invoke them on-device —
no screen scraping, no tap simulation. It is Android 16+ and in **private preview with
trusted testers**, with Uber/DoorDash/OpenTable already integrated, so the action item is
**apply for the preview now** and treat the code as a follow-up. Strategically this is the
most interesting position on the board: it makes SceneView the AR layer of agentic
Android, which is a claim no docs connector can make.

## 5. Open technical question: can AR run *inside* a chat widget?

Almost certainly not, and it should be tested rather than assumed. WebXR in an iframe
requires `allow="xr-spatial-tracking"` on the *host's* iframe — which we do not control —
and `model-viewer`'s WebXR mode has a long-standing iframe bug (black screen). ChatGPT
additionally renders app widgets in a **double-iframe sandbox**. So plan for: 3D orbit
viewer inline (definitely works), "View in AR" as an **escape hatch** to a full-page
viewer / Scene Viewer intent / AR Quick Look (§4.2). If in-chat AR ever does work it is a
genuine differentiator, so it is worth a 30-minute probe before deciding.

## 6. Ranked plan

**Tier A — fix what is already built (days, unblocks two directories)**
1. ~~Dual-emit the widget contract.~~ **WITHDRAWN — the premise was wrong (§3.1).**
   The shipped mimeType is already the current one. Do this instead, in order:
   bump the protocol revision so `extensions` exists at all (§3.2), declare
   `io.modelcontextprotocol/ui`, then move the widget pointer from the tool
   result onto the tool declaration. §3.1b
2. Bump the gateway to MCP 2026-07-28 (stateless suits a Worker). §3.2
3. Probe-test the widget in ChatGPT *and* Claude before any submission.
4. Submit to the ChatGPT App Directory and the Claude connectors directory
   (check the Team/Enterprise prerequisite first — §3.3).

**Tier B — the AR bridge (weeks, the actual new product)**
5. Measure mode in the demo apps, built on the existing depth/anchor primitives.
6. `assistant → phone` deep link + QR tool first (§4.2) — ship value with zero privacy
   surface.
7. Capture upload + `get_ar_capture` + overlay widget, with confidence intervals and a
   real consent/TTL story (§4.1).

**Tier C — cheap parallel bets**
8. Apply to the AppFunctions preview. §4.3
9. Antigravity CLI extension mirroring the Claude Code plugin.
10. One docs page: "use SceneView MCP from Gemini Enterprise" — the Streamable HTTP URL
    already exists.
11. Generate every surface's manifest from `llms.txt` the way `gpt/knowledge-*.md` is
    generated — we now maintain six divergent descriptions of the same tool set, and
    that is a drift class waiting to happen.

## 7. What NOT to do

- **Do not chase a Gemini app / Spark connector.** Partnership-only; there is no door.
- **Do not invest further in the Custom GPT.** The Apps SDK path supersedes it.
- **Do not port the widget to a heavier renderer before §3.1 lands.** Rendering fidelity
  is irrelevant while the contract prevents it from rendering at all.
- **Do not build capture upload before the deep-link version.** §4.2 tests the demand at
  a fraction of the cost and none of the privacy exposure.

## 8. Decisions needed from the user

1. Tier A (fix + list) alone, or Tier A + Tier B (the AR bridge as a real product line)?
2. Do we have — or want — a Claude Team/Enterprise org for the directory submission?
3. Is a hosted photo+geometry capture acceptable at all, or measurements-only?
4. Should the cross-vendor pass (§0) run on the Mac before any of this is scheduled?

## 9. Sources

Secondary sources, dated. Re-verify §3.1 and §3.2 against the primary specs from a
machine with unrestricted egress before implementing.

- [Developers can now submit apps to ChatGPT — OpenAI](https://openai.com/index/developers-can-now-submit-apps-to-chatgpt/)
- [App submission guidelines — Apps SDK](https://developers.openai.com/apps-sdk/app-submission-guidelines)
- [Submitting to the Connectors Directory — Claude docs](https://claude.com/docs/connectors/building/submission)
- [Interactive connectors and MCP Apps — Anthropic](https://claude.com/blog/interactive-tools-in-claude)
- [MCP 2026-07-28 spec: stateless core, coming to Claude — Anthropic](https://claude.com/blog/bringing-mcp-2026-07-28-to-claude)
- [MCP Apps — Bringing UI capabilities to MCP clients](https://blog.modelcontextprotocol.io/posts/2026-01-26-mcp-apps/)
- [OpenAI Apps SDK Integration — MCP-UI](https://mcpui.dev/guide/apps-sdk)
- [Gemini Spark now supports 3rd-party apps, including MCP — 9to5Google](https://9to5google.com/2026/06/30/gemini-spark-apps-more/)
- [Google details MCP-like AppFunctions that let Gemini use Android apps — 9to5Google](https://9to5google.com/2026/02/25/android-appfunctions-gemini/)
- [Overview of AppFunctions — Android Developers](https://developer.android.com/ai/appfunctions)
- [Use Depth in your Android app — ARCore](https://developers.google.com/ar/develop/java/depth/developer-guide)
- [Phone 3D scanning: LiDAR apps, accuracy, and formats](https://www.3dmag.com/3d-wikipedia/phone-3d-scanning-lidar-iphone-3d-apps-guide/)
- [WebXR does not work from inside iframe — google/model-viewer#1318](https://github.com/google/model-viewer/issues/1318)
- [Customize a WebXR AR experience — modelviewer.dev](https://modelviewer.dev/examples/augmentedreality/)

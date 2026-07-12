---
description: Verify the REAL live state of published artifacts (iOS App Store, Maven Central, npm) vs the expected version — CI-green is never proof of live. Runs the store-status workflow.
model: sonnet
effort: low
---

# /store-status — is what's LIVE actually our version?

CI-green means **upload**, not **approved** or **live**. iOS sat on 4.0.3 for three
weeks while CI was green (#2252). This skill verifies the REAL live versions.

Run the saved workflow:

```
Workflow({ name: "store-status" })                       # expected = gradle.properties VERSION_NAME
Workflow({ name: "store-status", args: { version: "4.17.0" } })
```

It probes in parallel and compares each to the expected version:
- **iOS** — `itunes.apple.com/lookup?id=6761329763` → live App Store version + release date
- **Maven Central** — `repo1.maven.org` HEAD on the `.pom` (HTTP 200 = live)
- **npm** — `npm view sceneview-web@<v> version`

…then updates the store/live bullet under `## NOW` in `.claude/STATE.md`.

**Apple state is now an API GET, not a browser session (#2612 P1).** App Store
Connect rejection-state, expired-agreement (`REQUIRED_AGREEMENTS_MISSING_OR_EXPIRED`),
and cert/profile expiry are detectable read-only via the ASC API — run:

```
GATE_HARD=0 STORE_PREFLIGHT_JSON_OUT=/tmp/sp.json \
  bash .claude/scripts/store-preflight.sh          # SKIPs cleanly without ASC secrets
```

It reuses `app-store.yml`'s ASC key (no new scope) and also runs daily in
`maintenance.yml` (`store-preflight` job → step summary). Wiring the probe into
this saved workflow's parallel probe set is a P1 follow-up (store-status.js
orchestrates LLM agents, not a shell call — kept out to avoid touching the
saved-workflow contract).

**Still a browser-only gap — do NOT fake it:** Play Console review-state (no
public API) and the ASC Resolution Center *message thread* (state is API-visible,
the conversation is not) still need an authenticated session. Spawn a dedicated
session for those — never infer "approved" from a workflow badge. See
`feedback_verify_live_store_state`.

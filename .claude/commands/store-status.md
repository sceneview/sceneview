---
description: Verify the REAL live state of published artifacts (iOS App Store, Maven Central, npm) vs the expected version — CI-green is never proof of live. Runs the store-status workflow.
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

**Known gap — do NOT fake it:** App Store Connect rejection-state and Play Console
review-state need an authenticated browser session (Chrome MCP). Spawn a dedicated
session for that (the orchestrator delegates interactive work) — never infer "approved"
from a workflow badge. See `feedback_verify_live_store_state`.

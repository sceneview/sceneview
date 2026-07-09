---
description: Comprehensive pre-push quality gate — compile, unit tests, bundle build, website JS validation.
model: sonnet
effort: low
---

# /quality-gate — Comprehensive pre-push quality gate

Run this BEFORE every push to ensure nothing is broken across ALL platforms.

---

## Quick mode

For fast checks (version sync, security, code quality — no build/test):
```bash
bash .claude/scripts/quality-gate.sh --quick
```

## Full mode (default)

Runs everything including build and tests:
```bash
bash .claude/scripts/quality-gate.sh
```

## What it checks

### 1. Git state
- Current branch
- No merge conflicts
- No large files (>10MB) staged

### 2. Version sync (CRITICAL)
- `gradle.properties` (root) vs all modules
- `llms.txt` artifact versions match
- `README.md` install snippets match

### 3. Security (CRITICAL)
- No secrets tracked (`.env`, `credentials.json`, `keystore.jks`, `google-services.json`)
- `local.properties` not in git
- No API keys in staged changes

### 4. Code quality
- No `!!` (force unwrap) in Kotlin — prefer safe calls
- No Filament JNI calls on background threads (THREADING VIOLATION)
- No new TODO/FIXME without corresponding issue

### 5. Build & test (full mode only)
- `./gradlew assembleDebug`
- `./gradlew :sceneview:testDebugUnitTest :arsceneview:testDebugUnitTest`
- `cd mcp && npm test`

### 6. Cross-platform consistency
- If Android APIs changed, check if `llms.txt` was updated
- If new public APIs added, flag for documentation

## After running

If the gate BLOCKS:
1. Fix all FAIL items
2. Re-run the gate
3. Only push when all checks pass

If warnings only:
- Review each warning
- Push if warnings are intentional/expected

## Automated usage

This can also be used as a pre-push hook. To install:
```bash
echo '#!/bin/bash
bash .claude/scripts/quality-gate.sh --quick' > .git/hooks/pre-push
chmod +x .git/hooks/pre-push
```

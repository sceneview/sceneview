#!/usr/bin/env bash
# qa-keys.sh — resolve + export the demo-app API keys for device-QA, and report
# their PRESENCE (never their value) so the harness can build WITH the keys and
# HONESTLY skip the key-gated paths when a key is absent (#2343).
#
# THE BUG THIS FIXES (#2343)
# --------------------------
# `samples/android-demo/build.gradle` reads two store secrets:
#   * SKETCHFAB_API_KEY  (env, else repo-root local.properties `sketchfab.api.key`)
#       → BuildConfig.SKETCHFAB_API_KEY → the Explore tab's Sketchfab carousels
#         + search. Empty ⇒ SketchfabConfig.apiKey is null, Explore is disabled.
#   * ARCORE_API_KEY     (env, else repo-root local.properties `ARCORE_API_KEY`)
#       → AndroidManifest `arcoreApiKey` placeholder → Cloud Anchors / Geospatial
#         / Streetscape. Empty ⇒ an empty manifest placeholder ⇒ runtime
#         ERROR_NOT_AUTHORIZED on every Cloud-backed demo.
# A *debug* build with empty keys compiles silently. The QA harness builds
# `assembleDebug` with NO keys injected, so those paths are never exercised — yet
# the run reports a complete green QA. This helper closes that gap: it exports
# the resolved keys so the existing build.gradle wiring injects them, and it
# tells the caller whether each key is present so a keyless run is reported as
# `skipped` (advisory), never a silent pass.
#
# SECURITY INVARIANT
# ------------------
# This helper NEVER prints, logs, or returns a key VALUE — only presence
# (present/absent) and a length-free banner. Do not add any `echo "$key"`.
#
# Usage from another script:
#     SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
#     source "$SCRIPT_DIR/lib/qa-keys.sh"
#     qa_keys_resolve_all          # resolve + export both keys, set presence vars
#     # → exports SKETCHFAB_API_KEY / ARCORE_API_KEY (if found) into the env so a
#     #   child `./gradlew assembleDebug` injects them via build.gradle, and sets:
#     #     QA_SKETCHFAB_KEY_PRESENT = true|false
#     #     QA_ARCORE_KEY_PRESENT    = true|false
#     qa_keys_banner_if_absent     # print the LOUD stderr banner(s) when absent
#
# Presence is what QA needs — not a live API round-trip — so presence is detected
# locally by the `_qa_keys_nonblank` whitespace-strip check (a key that is " " /
# "\n" counts as ABSENT, the #1909 hit pattern); no verify-*-key.sh script and no
# network probe is involved.

# Guard against double-sourcing in the same shell.
if [[ -n "${QA_KEYS_SH_SOURCED:-}" ]]; then
  # `return` works because this file is sourced, not executed; the `|| true`
  # keeps a stray direct-exec from erroring. shellcheck can't see the source
  # context, so it flags the guard as unreachable — it is not.
  # shellcheck disable=SC2317
  return 0 2>/dev/null || true
fi
QA_KEYS_SH_SOURCED=1

# Resolve this helper's own dir so it can locate the repo-root local.properties
# regardless of the caller's CWD.
QA_KEYS_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# .claude/scripts/lib -> .claude/scripts
QA_KEYS_SCRIPTS_DIR="$(cd "$QA_KEYS_LIB_DIR/.." && pwd)"
# .claude/scripts -> repo root
QA_KEYS_REPO_ROOT="$(cd "$QA_KEYS_SCRIPTS_DIR/../.." && pwd)"

# Path to the repo-root local.properties (gitignored — never committed).
qa_keys_local_properties_path() {
  echo "$QA_KEYS_REPO_ROOT/local.properties"
}

# Read a property from repo-root local.properties. Echoes the value (or empty).
# Internal: callers must not log the result for a secret.
_qa_keys_read_local_property() {
  local prop="$1"
  local file
  file="$(qa_keys_local_properties_path)"
  [[ -f "$file" ]] || { echo ""; return 0; }
  # `key=value`, ignoring `#`/`!` comment lines and surrounding whitespace.
  # Take the LAST match so a later override wins, matching java.util.Properties.
  local line value
  line="$(grep -E "^[[:space:]]*${prop//./\\.}[[:space:]]*=" "$file" 2>/dev/null | tail -n1 || true)"
  [[ -n "$line" ]] || { echo ""; return 0; }
  value="${line#*=}"
  # Trim leading/trailing whitespace.
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  echo "$value"
}

# True (0) if a string is non-empty after stripping all whitespace. A secret
# that is literally " " / "\n" must count as ABSENT (the #1909 hit pattern).
_qa_keys_nonblank() {
  local s="${1:-}"
  s="${s//[[:space:]]/}"
  [[ -n "$s" ]]
}

# Resolve the Sketchfab key: env SKETCHFAB_API_KEY, else local.properties
# `sketchfab.api.key`. Exports SKETCHFAB_API_KEY when found so a child Gradle
# build injects it. Sets QA_SKETCHFAB_KEY_PRESENT=true|false. Returns 0 if
# present, 1 if absent. Never logs the value.
qa_keys_resolve_sketchfab() {
  local key="${SKETCHFAB_API_KEY:-}"
  if ! _qa_keys_nonblank "$key"; then
    key="$(_qa_keys_read_local_property "sketchfab.api.key")"
  fi
  if _qa_keys_nonblank "$key"; then
    export SKETCHFAB_API_KEY="$key"
    QA_SKETCHFAB_KEY_PRESENT=true
    export QA_SKETCHFAB_KEY_PRESENT
    return 0
  fi
  QA_SKETCHFAB_KEY_PRESENT=false
  export QA_SKETCHFAB_KEY_PRESENT
  return 1
}

# Resolve the ARCore Cloud key: env ARCORE_API_KEY, else local.properties
# `ARCORE_API_KEY`. Exports ARCORE_API_KEY when found. Sets
# QA_ARCORE_KEY_PRESENT=true|false. Returns 0 if present, 1 if absent.
qa_keys_resolve_arcore() {
  local key="${ARCORE_API_KEY:-}"
  if ! _qa_keys_nonblank "$key"; then
    key="$(_qa_keys_read_local_property "ARCORE_API_KEY")"
  fi
  if _qa_keys_nonblank "$key"; then
    export ARCORE_API_KEY="$key"
    QA_ARCORE_KEY_PRESENT=true
    export QA_ARCORE_KEY_PRESENT
    return 0
  fi
  QA_ARCORE_KEY_PRESENT=false
  export QA_ARCORE_KEY_PRESENT
  return 1
}

# Resolve + export both keys and set the presence vars. Idempotent.
qa_keys_resolve_all() {
  qa_keys_resolve_sketchfab || true
  qa_keys_resolve_arcore || true
  # Mark resolution done so a child process (e.g. qa-android-demos.sh spawned by
  # device-qa.sh) does not re-print the banner the parent already printed.
  export QA_KEYS_RESOLVED=1
}

# Force a fresh KEYED build when a key is present (#2343 — closes the silent-green
# this whole helper exists to kill).
#
# WHY DELETE THE APK INSTEAD OF TRUSTING GRADLE UP-TO-DATE
# -------------------------------------------------------
# The keys reach the build via `System.getenv(...)` in
# samples/android-demo/build.gradle (buildConfigField / manifestPlaceholder). An
# env var is NOT a tracked Gradle task input, so a debug APK assembled WITHOUT
# the keys (a prior run, a manual ./gradlew, or the CI build-android-apk
# artifact) is considered UP-TO-DATE — `assembleDebug` / `installDebug` would
# REUSE the keyless APK even though the keys are now in the env. Installing that
# stale APK means Sketchfab Explore is disabled and AR Cloud throws
# ERROR_NOT_AUTHORIZED, yet record_key_subleg would report the sub-leg `passed`
# off RESOLUTION-time presence — exactly the false green #2343 fixes. Deleting
# the APK makes the output genuinely missing, so the assemble task re-runs and
# re-reads the env. Call this BEFORE any `if [[ ! -f "$APK" ]]` build guard and
# before the AR leg's Gradle `installDebug`. (DO NOT re-introduce a stale-APK
# shortcut here — see #2343 / PR #2347 review.)
#   $1 path to the demo debug APK (the only artifact that carries the keys).
# No-op when neither key is present (a keyless run must NOT churn the build).
# Never logs a key value — only that a fresh build is forced.
qa_keys_force_fresh_build_if_present() {
  local apk="${1:?qa_keys_force_fresh_build_if_present needs the APK path}"
  if [[ "${QA_SKETCHFAB_KEY_PRESENT:-false}" == "true" || "${QA_ARCORE_KEY_PRESENT:-false}" == "true" ]]; then
    if [[ -f "$apk" ]]; then
      rm -f "$apk"
      echo "[qa-keys] a key is present — removed the existing demo APK to force a fresh KEYED build (#2343): $apk" >&2
    fi
  fi
}

# Print a LOUD, boxed, unmissable banner to stderr for whichever key is absent,
# so a keyless QA run can NEVER be read as "complete". No key value is printed.
qa_keys_banner_if_absent() {
  local lp
  lp="$(qa_keys_local_properties_path)"
  if [[ "${QA_SKETCHFAB_KEY_PRESENT:-false}" != "true" ]]; then
    {
      echo ""
      echo "┌────────────────────────────────────────────────────────────────────────────┐"
      echo "│ ⚠️  QA INCOMPLETE — SKETCHFAB_API_KEY absent                                  │"
      echo "│                                                                            │"
      echo "│ The Explore tab's Sketchfab carousels + search were NOT tested. The demo    │"
      echo "│ build shipped BuildConfig.SKETCHFAB_API_KEY=\"\" → Explore is disabled.       │"
      echo "│ This leg is reported SKIPPED (advisory), never a pass.                      │"
      echo "│                                                                            │"
      echo "│ Set it for a full local QA:                                                 │"
      echo "│   • repo-root local.properties:  sketchfab.api.key=<token>                  │"
      echo "│   • or the SKETCHFAB_API_KEY environment variable                           │"
      echo "│   ($lp)"
      echo "└────────────────────────────────────────────────────────────────────────────┘"
    } >&2
  fi
  if [[ "${QA_ARCORE_KEY_PRESENT:-false}" != "true" ]]; then
    {
      echo ""
      echo "┌────────────────────────────────────────────────────────────────────────────┐"
      echo "│ ⚠️  QA INCOMPLETE — ARCORE_API_KEY absent                                     │"
      echo "│                                                                            │"
      echo "│ AR Cloud demos (Cloud Anchors / Geospatial / Streetscape) were NOT tested.  │"
      echo "│ The build shipped an empty arcoreApiKey manifest placeholder → those demos  │"
      echo "│ fail at runtime with ERROR_NOT_AUTHORIZED.                                  │"
      echo "│ This leg is reported SKIPPED (advisory), never a pass.                      │"
      echo "│                                                                            │"
      echo "│ Set it for a full local QA:                                                 │"
      echo "│   • repo-root local.properties:  ARCORE_API_KEY=<key>                       │"
      echo "│   • or the ARCORE_API_KEY environment variable                              │"
      echo "│   ($lp)"
      echo "└────────────────────────────────────────────────────────────────────────────┘"
    } >&2
  fi
}

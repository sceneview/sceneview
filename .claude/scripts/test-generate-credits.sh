#!/usr/bin/env bash
#
# test-generate-credits.sh — self-test for generate-credits.py (#2941).
#
# The gate this pins is a licence-compliance gate: `assets/CREDITS.md` and the
# copy bundled in the Play Store APK are what discharge CC-BY 4.0 §3a for every
# third-party asset SceneView ships. Before #2941 the generator wrote one of the
# five tracked CREDITS.md files and the gate checked that one — the APK copy was
# 60 lines against 150, two months stale, and six bundled files had no
# attribution line anywhere. Nothing in CI could have said so.
#
# A drift gate that silently PASSes is worse than none (same rationale as
# test-check-doc-drift.sh / test-check-web-dts.sh), so this suite works by
# MUTATION: build a fixture repo, verify `--check` is green on it, then verify
# that every class of divergence turns it red. Each scenario asserts on the
# script's OUTPUT, not just its exit code, so it keeps meaning something if the
# generator is rewritten.
#
# Case 0 is a vacuity guard, and it is not decoration: the measurement that
# opened #2941 first returned an empty diff because the extractor matched
# nothing, which reads exactly like "the files agree". A fixture whose
# generated files came out empty would make every later "drift detected"
# assertion pass for the wrong reason.
#
# Runs in repo-hygiene (ci.yml) and standalone; needs bash + python3 only.
#
# Exit: 0 all scenarios hold · 1 a scenario failed.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
GEN="$REPO_ROOT/.claude/scripts/generate-credits.py"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/generate-credits-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

FAILURES=0
OUT=""
RC=0

ANDROID_ASSETS="samples/android-demo/src/main/assets"
IOS_AUDIO="samples/ios-demo/SceneViewDemo/Audio"
WEB_AUDIO="samples/web-demo/site/audio"

# run <mode...> — capture stdout+stderr and exit code of the generator against
# the fixture root. Never runs against the real repo tree.
run() {
    RC=0
    OUT="$(GENERATE_CREDITS_ROOT="$TMP/fixture" python3 "$GEN" "$@" 2>&1)" || RC=$?
}

scenario() { # $1 = name, $2 = expected exit, $3 = regex the output must match
    local name="$1" expected="$2" want="${3:-}"
    if [ "$RC" -ne "$expected" ]; then
        echo "[FAIL] $name — expected exit $expected, got $RC"
        while IFS= read -r line; do echo "       | $line"; done <<< "$OUT"
        FAILURES=$((FAILURES + 1))
        return
    fi
    if [ -n "$want" ] && ! grep -qE "$want" <<< "$OUT"; then
        echo "[FAIL] $name — exit $RC as expected, but output did not match /$want/"
        while IFS= read -r line; do echo "       | $line"; done <<< "$OUT"
        FAILURES=$((FAILURES + 1))
        return
    fi
    echo "[PASS] $name"
}

fail() { echo "[FAIL] $1"; FAILURES=$((FAILURES + 1)); }
pass() { echo "[PASS] $1"; }

# ── fixture ────────────────────────────────────────────────────────────────
# Small and hand-built rather than copied from the repo: the real catalogue is
# 90 models, and a fixture whose expected content moves whenever someone adds a
# model is a fixture that will be deleted the first time it goes red.
reset_fixture() {
    rm -rf "$TMP/fixture"
    local f="$TMP/fixture"
    mkdir -p "$f/assets/audio" "$f/$ANDROID_ASSETS/models" \
             "$f/$ANDROID_ASSETS/environments" "$f/$ANDROID_ASSETS/audio" \
             "$f/$IOS_AUDIO" "$f/$WEB_AUDIO"

    cat > "$f/assets/catalog.json" <<'JSON'
{
  "models": [
    {
      "id": "fixture_model",
      "name": "Fixture Model",
      "source": "sketchfab",
      "sourceUrl": "https://sketchfab.com/3d-models/fixture",
      "author": "Fixture Author",
      "license": "CC-BY-4.0",
      "formats": { "glb": { "file": "models/glb/fixture_model.glb" } }
    }
  ],
  "environments": [
    {
      "id": "fixture_env",
      "name": "Fixture Env",
      "source": "polyhaven",
      "file": "environments/hdr/fixture_env_2k.hdr"
    }
  ]
}
JSON

    # Bundled binaries. Content is irrelevant; presence and byte count are not.
    printf 'glb-bytes' > "$f/$ANDROID_ASSETS/models/fixture_model.glb"
    printf 'hdr-bytes' > "$f/$ANDROID_ASSETS/environments/fixture_env_2k.hdr"
    printf 'wav-bytes' > "$f/$ANDROID_ASSETS/audio/bell.wav"

    cat > "$f/assets/audio/CREDITS.md" <<'MD'
# Audio assets — credits & licenses

Fixture audio source of truth.
MD

    # Bring the fixture to a generated, in-sync state.
    GENERATE_CREDITS_ROOT="$f" python3 "$GEN" > "$TMP/gen.log" 2>&1
}

echo "test-generate-credits.sh"

# ── 0. VACUITY GUARD ───────────────────────────────────────────────────────
# Prove the fixture generation produced real, non-trivial content before any
# "drift was detected" assertion is allowed to mean anything.
reset_fixture
VACUITY_OK=1
for rel in "assets/CREDITS.md" "$ANDROID_ASSETS/CREDITS.md" \
           "$IOS_AUDIO/CREDITS.md" "$WEB_AUDIO/CREDITS.md"; do
    if [ ! -s "$TMP/fixture/$rel" ]; then
        fail "vacuity: $rel was not written, or is empty"
        VACUITY_OK=0
    fi
done
# The generated APK copy must actually name the bundled asset — an empty
# template would satisfy "non-empty" and defeat the whole suite.
if ! grep -q 'models/fixture_model.glb' "$TMP/fixture/$ANDROID_ASSETS/CREDITS.md"; then
    fail "vacuity: the generated APK copy does not mention the bundled model"
    VACUITY_OK=0
fi
if ! grep -q 'Fixture Author' "$TMP/fixture/$ANDROID_ASSETS/CREDITS.md"; then
    fail "vacuity: the generated APK copy does not carry the model's author"
    VACUITY_OK=0
fi
[ "$VACUITY_OK" -eq 1 ] && pass "vacuity guard — all four files generated with real content"

# ── 1. Baseline: a freshly generated tree is in sync ───────────────────────
reset_fixture
run --check
scenario "freshly generated fixture passes --check" 0 "assets/CREDITS.md is in sync"

# ── 2. THE #2941 case: the APK copy drifts alone ───────────────────────────
# This is the exact shape that shipped for two months: assets/CREDITS.md
# correct, the copy the user receives stale, gate green.
reset_fixture
printf '\nHand-edited line that no generator would produce.\n' \
    >> "$TMP/fixture/$ANDROID_ASSETS/CREDITS.md"
run --check
scenario "a hand-edited APK CREDITS copy fails --check" 1 \
    "DRIFT: $ANDROID_ASSETS/CREDITS.md"

# ── 3. A bundled asset is added and the copy is not regenerated ────────────
# Same drift, arriving the way it actually arrives: through the assets folder.
reset_fixture
printf 'second-glb' > "$TMP/fixture/$ANDROID_ASSETS/models/fixture_model_copy.glb"
# Give it a catalog entry so this tests DRIFT, not the uncredited path below.
python3 - "$TMP/fixture/assets/catalog.json" <<'PY'
import json, sys
p = sys.argv[1]
d = json.load(open(p))
m = dict(d["models"][0])
m.update(id="fixture_model_copy", name="Fixture Model Copy",
         formats={"glb": {"file": "models/glb/fixture_model_copy.glb"}})
d["models"].append(m)
json.dump(d, open(p, "w"), indent=2)
PY
run --check
scenario "a new bundled asset without regeneration fails --check" 1 \
    "DRIFT: $ANDROID_ASSETS/CREDITS.md"

# ── 4. An UNCREDITED bundled file — no catalog entry, no declaration ───────
# Regenerating cannot fix this one, so it must be its own verdict rather than
# a drift message that sends the reader in a circle.
reset_fixture
mkdir -p "$TMP/fixture/$ANDROID_ASSETS/mystery"
printf 'who-shipped-this' > "$TMP/fixture/$ANDROID_ASSETS/mystery/unknown.glb"
run --check
scenario "a bundled file with no attribution anywhere fails --check" 1 \
    "UNCREDITED:.*mystery/unknown.glb|UNCREDITED"
run
scenario "the same file also fails a plain regeneration" 1 "UNCREDITED"

# ── 4b. …but a macOS Finder turd is NOT an uncredited asset ────────────────
# `.DS_Store` is gitignored, cannot reach the APK, and appears in any folder
# the Finder displays. Treating it as a bundled asset would paint the gate red
# on a developer's machine with a failure CI can never reproduce — the worst
# kind, because the next move is to stop believing the gate.
reset_fixture
printf 'finder-junk' > "$TMP/fixture/$ANDROID_ASSETS/models/.DS_Store"
mkdir -p "$TMP/fixture/$ANDROID_ASSETS/.gradle"
printf 'build-junk' > "$TMP/fixture/$ANDROID_ASSETS/.gradle/cache.bin"
run --check
scenario "dotfiles and dot-directories are not treated as bundled assets" 0 "is in sync"

# ── 5. A mirror is edited alone ────────────────────────────────────────────
# The three audio CREDITS.md are byte-identical copies of one hand-written
# source. Nothing but this check stops one of them being edited on its own.
reset_fixture
printf '\nios-only edit\n' >> "$TMP/fixture/$IOS_AUDIO/CREDITS.md"
run --check
scenario "an edited iOS audio mirror fails --check" 1 "DRIFT: $IOS_AUDIO/CREDITS.md"

reset_fixture
printf '\nweb-only edit\n' >> "$TMP/fixture/$WEB_AUDIO/CREDITS.md"
run --check
scenario "an edited web audio mirror fails --check" 1 "DRIFT: $WEB_AUDIO/CREDITS.md"

# ── 6. The mirror SOURCE changes and the mirrors are not refreshed ─────────
reset_fixture
printf '\nnew line in the source of truth\n' >> "$TMP/fixture/assets/audio/CREDITS.md"
run --check
scenario "an updated audio source with stale mirrors fails --check" 1 \
    "DRIFT: $IOS_AUDIO/CREDITS.md"
run
run --check
scenario "regenerating refreshes the mirrors and restores green" 0 "is in sync"

# ── 7. A mirror is deleted outright ────────────────────────────────────────
reset_fixture
rm -f "$TMP/fixture/$WEB_AUDIO/CREDITS.md"
run --check
scenario "a deleted mirror fails --check" 1 "MISSING"

# ── 8. The pre-existing contract still holds ───────────────────────────────
reset_fixture
printf '\nhand edit\n' >> "$TMP/fixture/assets/CREDITS.md"
run --check
scenario "an edited assets/CREDITS.md still fails --check" 1 "DRIFT: assets/CREDITS.md"

# `--chekc` must not fall through to the write path and exit 0 — that would
# turn the blocking gate into a false green that also mutates the workspace.
reset_fixture
run --chekc
scenario "a misspelled --check is rejected, not silently run" 1 "unknown argument"

# ── 9. --check never writes ────────────────────────────────────────────────
# The gate runs on developer machines through pre-push-check.sh; a --check that
# mutates the tree would rewrite files under a push the developer did not ask
# for, and would also mask the drift it just found on a re-run.
reset_fixture
printf '\ndrifted\n' >> "$TMP/fixture/$ANDROID_ASSETS/CREDITS.md"
BEFORE="$(cat "$TMP/fixture/$ANDROID_ASSETS/CREDITS.md")"
run --check
AFTER="$(cat "$TMP/fixture/$ANDROID_ASSETS/CREDITS.md")"
if [ "$BEFORE" = "$AFTER" ]; then
    pass "--check leaves a drifted file untouched"
else
    fail "--check rewrote the file it was only supposed to inspect"
fi

# ── 10. Every tracked CREDITS.md is claimed by the script ──────────────────
# The #2941 failure was not a bug in a check, it was a file nobody had listed.
# So the list itself is pinned: a sixth CREDITS.md added to the repo without a
# line in generate-credits.py fails here rather than shipping unchecked.
DECLARED="$(python3 - "$GEN" <<'PY'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
print("\n".join(sorted(set(re.findall(r'[\w./-]*CREDITS\.md', src)))))
PY
)"
TRACKED="$(cd "$REPO_ROOT" && git ls-files | grep -E '(^|/)CREDITS\.md$' || true)"
if [ -z "$TRACKED" ]; then
    fail "vacuity: found no tracked CREDITS.md at all — the enumeration is broken"
elif [ -z "$DECLARED" ]; then
    fail "vacuity: found no CREDITS.md path declared in generate-credits.py"
else
    UNCLAIMED=""
    while IFS= read -r t; do
        [ -n "$t" ] || continue
        grep -qxF "$t" <<< "$DECLARED" || UNCLAIMED="$UNCLAIMED $t"
    done <<< "$TRACKED"
    if [ -n "$UNCLAIMED" ]; then
        fail "tracked CREDITS.md not named in generate-credits.py:$UNCLAIMED"
    else
        pass "all $(grep -c . <<< "$TRACKED") tracked CREDITS.md files are named in the generator"
    fi
fi

# ── 11. The real repo is in sync ───────────────────────────────────────────
# Cheap, and it is the assertion that would have caught #2941 on the day it
# started. Read-only: --check never writes (case 9).
RC=0
OUT="$(cd "$REPO_ROOT" && python3 "$GEN" --check 2>&1)" || RC=$?
scenario "the real repository passes --check" 0 "is in sync"

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "test-generate-credits.sh: all scenarios passed"
    exit 0
fi
echo "test-generate-credits.sh: $FAILURES scenario(s) FAILED"
exit 1

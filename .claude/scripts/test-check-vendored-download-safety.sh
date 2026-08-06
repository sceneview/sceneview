#!/usr/bin/env bash
#
# test-check-vendored-download-safety.sh — self-test for
# check-vendored-download-safety.sh.
#
# This gate is dormant by construction: on the tree as it stands it prints an
# INFO line and exits 0, and it will keep doing that until someone wires
# third_party/filament-kmp into the build — possibly years from now. A gate
# whose failing path is never exercised is a gate nobody knows is broken. Worse,
# its first probe was ALREADY wrong once: it matched only `include("<path>")`
# and was blind to the `include ":x"` + `projectDir = file(...)` form Gradle
# actually uses, so wiring the tree left it green.
#
# So the failing path is exercised here, on synthetic trees, every CI run.
# Each probe is checked in BOTH directions, and against the near-miss that
# would satisfy a lazy implementation of it.
#
# Exit codes: 0 = the gate behaves as specified, 1 = it does not.

set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
SCRIPT="$ROOT/.claude/scripts/check-vendored-download-safety.sh"
PASS=0; FAIL=0

ok()  { printf '  ✓ %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  ✗ %s\n' "$1"; FAIL=$((FAIL+1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Builds a synthetic repo root.
#   $1 = case name
#   $2 = "wired" | "dormant" | "absent"
#   $3 = extra Kotlin appended to FilamentDownloads.kt (the hardening under test)
make_tree() {
    local name="$1" wiring="$2" extra="${3:-}"
    local d="$WORK/$name"
    rm -rf "$d"; mkdir -p "$d"

    if [[ "$wiring" != "absent" ]]; then
        mkdir -p "$d/third_party/filament-kmp/build-logic/src/main/kotlin"
        # The unhardened baseline, carrying the two real shapes: a download with
        # no digest, and a symlink created from an unvalidated linkName guarded
        # only by the entry-path check (the near-miss for probe 2).
        cat > "$d/third_party/filament-kmp/build-logic/src/main/kotlin/FilamentDownloads.kt" <<'KOTLIN'
object FilamentDownloads {
    private fun downloadToCache(cacheDir: File, name: String, url: String): File {
        val cached = cacheDir.resolve(name)
        if (cached.exists()) return cached
        URI(url).toURL().openConnection().inputStream.use { it.copyTo(tmp.outputStream()) }
        return cached
    }

    fun extractAll(tarball: File, destDir: File) {
        val destPath = destDir.toPath().toAbsolutePath().normalize()
        val out = destPath.resolve(entry.name).normalize()
        check(out.startsWith(destPath)) { "Tar entry escapes destination: ${entry.name}" }
        Files.createSymbolicLink(out, java.nio.file.Paths.get(entry.linkName))
    }
}
KOTLIN
        [[ -n "$extra" ]] && printf '%s\n' "$extra" >> \
            "$d/third_party/filament-kmp/build-logic/src/main/kotlin/FilamentDownloads.kt"
    fi

    mkdir -p "$d/.github/workflows"
    if [[ "$wiring" == "wired" ]]; then
        # The relocated-subproject form, NOT include("<path>") — the exact shape
        # the first version of probe 0 was blind to.
        cat > "$d/settings.gradle" <<'GRADLE'
include ":filament-kmp"
project(":filament-kmp").projectDir = file("third_party/filament-kmp")
GRADLE
    else
        echo 'include ":sceneview"' > "$d/settings.gradle"
    fi
    echo "$d"
}

run() { # $1 = tree dir -> sets OUT, RC
    set +e
    OUT="$(VENDORED_SAFETY_ROOT="$1" bash "$SCRIPT" 2>&1)"; RC=$?
    set -e
}

echo "test-check-vendored-download-safety.sh"

# ── Reachability: the gate must fire when, and only when, the tree is built ──

run "$(make_tree absent absent)"
{ [[ $RC -eq 0 ]] && grep -q "nothing vendored" <<<"$OUT"; } \
    && ok "no vendored tree → pass" \
    || bad "no vendored tree should pass (rc=$RC)"

run "$(make_tree dormant dormant)"
{ [[ $RC -eq 0 ]] && grep -q "nothing builds it" <<<"$OUT"; } \
    && ok "vendored but unbuilt → pass, stays quiet" \
    || bad "vendored-but-unbuilt should pass quietly (rc=$RC)"

run "$(make_tree wired wired)"
{ [[ $RC -eq 1 ]] && grep -q "does not verify what it downloaded" <<<"$OUT" \
    && grep -q "unvalidated entry.linkName" <<<"$OUT"; } \
    && ok "wired + unhardened → FAIL on both probes" \
    || bad "wired+unhardened must fail on both probes (rc=$RC)"

# The regression that actually happened: detection must not depend on the
# `include("<path>")` spelling. make_tree writes the projectDir form, so the
# case above already covers it — assert the reason explicitly.
grep -q "settings file references" <<<"$OUT" \
    && ok "wiring detected via projectDir = file(...), not just include(\"path\")" \
    || bad "wiring detection missed the relocated-subproject form"

# ── Probe 1: pinned digest ───────────────────────────────────────────────────

run "$(make_tree comment wired '// TODO: verify the sha256 of each download one day')"
grep -q "does not verify what it downloaded" <<<"$OUT" \
    && ok "a 'sha256' MENTION in a comment does not satisfy the digest probe" \
    || bad "digest probe satisfied by a bare comment — hollow"

run "$(make_tree digestonly wired 'fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes())')"
grep -q "does not verify what it downloaded" <<<"$OUT" \
    && ok "digest plumbing WITHOUT a pinned hash does not satisfy the probe" \
    || bad "digest probe passed with nothing to compare against"

run "$(make_tree digestfull wired 'val EXPECTED = mapOf("a.tgz" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes())')"
{ grep -q "downloads verified against a pinned digest" <<<"$OUT" \
    && grep -q "unvalidated entry.linkName" <<<"$OUT"; } \
    && ok "digest + pinned hash → probe 1 green, probe 2 independently still red" \
    || bad "probe 1 should go green alone, without dragging probe 2 with it"

# ── Probe 2: symlink target validation ───────────────────────────────────────
# The baseline tree already contains `check(out.startsWith(destPath))`. Every
# case above that reported probe 2 red proves that check does NOT satisfy it —
# which is the whole point, since it guards the entry path, not the link target.

run "$(make_tree linkguard wired 'val target = out.parent.resolve(entry.linkName).normalize()
check(target.startsWith(destPath)) { "Symlink escapes destination" }')"
{ [[ $RC -ne 0 ]] && grep -q "symlink targets validated" <<<"$OUT"; } \
    && ok "a real linkName containment check → probe 2 green (probe 1 still red)" \
    || bad "probe 2 not satisfied by a genuine linkName guard (rc=$RC)"

run "$(make_tree hardened wired 'val EXPECTED = mapOf("a.tgz" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
val target = out.parent.resolve(entry.linkName).normalize()
check(target.startsWith(destPath)) { "Symlink escapes destination" }')"
{ [[ $RC -eq 0 ]] && grep -q "is hardened" <<<"$OUT"; } \
    && ok "wired + both fixes → pass" \
    || bad "a fully hardened wired tree must pass (rc=$RC)"

echo
if [[ $FAIL -gt 0 ]]; then
    echo "FAILED: $FAIL of $((PASS+FAIL))"
    exit 1
fi
echo "OK: $PASS/$PASS"

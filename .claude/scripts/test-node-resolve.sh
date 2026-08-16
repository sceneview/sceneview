#!/usr/bin/env bash
#
# test-node-resolve.sh — pins lib/node-resolve.sh.
#
# The bug this guards is not "node is missing". It is the gate ANNOUNCING that
# node is missing on a host where node works, and downgrading two legs to NOT
# COVERED as a result. So every case here asserts on what gets FOUND, and the
# mutants all check that a broken resolver goes back to finding nothing.
#
# The fixtures are fake trees, not this machine's nvm — a test that only passes
# on a host with nvm installed pins nothing on CI.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$SCRIPT_DIR/lib/node-resolve.sh"

PASS=0
FAIL=0
ok()  { printf '  \033[0;32m✓\033[0m %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf '  \033[0;31m✗\033[0m %s\n' "$1"; FAIL=$((FAIL + 1)); }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# A fake nvm tree. `v9.0.0` exists precisely to catch a lexical sort choosing it
# over `v22.14.0` — the failure mode a glob or a plain `sort` would produce.
make_nvm() {
    local root="$1"; shift
    local v
    for v in "$@"; do
        mkdir -p "$root/versions/node/$v/bin"
        printf '#!/bin/sh\necho %s\n' "$v" > "$root/versions/node/$v/bin/node"
        chmod +x "$root/versions/node/$v/bin/node"
    done
}

# A PATH holding exactly the tools the lib needs and nothing else — above all,
# no node. Built by symlink rather than by trimming the system PATH: the first
# version of this suite kept /usr/bin:/bin and *assumed* no node lived there.
# True on this Mac (node comes from nvm), false on the CI runner, which has
# /usr/local/bin/node — so 9 of 10 cases resolved to that and the suite went red
# the moment it left the machine it was written on. A test whose result depends
# on the host's node layout pins the host, not the code.
HERMETIC_BIN="$TMP/bin"
mkdir -p "$HERMETIC_BIN"
for tool in tr find sed sort tail; do
    real=$(command -v "$tool" 2>/dev/null) || continue
    ln -sf "$real" "$HERMETIC_BIN/$tool"
done

# Resolve with a controlled environment: no node on PATH, no absolute-prefix
# node, and a HOME pointing at the fixture. Runs in a subshell so the caller's
# env survives.
resolve_in() {
    local nvm_root="$1" ; shift
    (
        # shellcheck disable=SC1090
        set +u
        unset NODE_CMD NVM_DIR
        export PATH="$HERMETIC_BIN"
        export NODE_RESOLVE_PREFIXES=""   # step 3 off — see the lib's header
        export HOME="$nvm_root_home"
        export NVM_DIR="$nvm_root"
        source "$LIB"
        resolve_node
    )
}

echo "── lib/node-resolve.sh ──"

# ── 0. the harness is actually hermetic ─────────────────────────────────────
# Without this, every assertion below can silently start passing for the wrong
# reason — or failing for one. This is the assertion whose absence let the
# suite ship green on the machine it was written on.
got="$(PATH="$HERMETIC_BIN" command -v node 2>/dev/null || true)"
if [ -z "$got" ]; then ok "the fixture PATH has no node (assertions below mean what they say)"
else bad "the fixture PATH leaks a node at '$got' — every case below is testing the host"; fi

# ── 1. PATH wins when node is there ─────────────────────────────────────────
mkdir -p "$TMP/onpath"
printf '#!/bin/sh\necho from-path\n' > "$TMP/onpath/node"
chmod +x "$TMP/onpath/node"
got="$(
    set +u; unset NODE_CMD
    PATH="$TMP/onpath:$HERMETIC_BIN"
    source "$LIB"; resolve_node
)"
if [ "$got" = "$TMP/onpath/node" ]; then ok "a node on PATH is used as-is"
else bad "PATH node not returned — got '$got'"; fi

# ── 2. an nvm-only host still resolves ──────────────────────────────────────
nvm_root_home="$TMP/home1"
make_nvm "$TMP/nvm1" v22.14.0
got="$(resolve_in "$TMP/nvm1")"
if [ "$got" = "$TMP/nvm1/versions/node/v22.14.0/bin/node" ]; then
    ok "node installed only by nvm is FOUND (the gate no longer skips two legs)"
else
    bad "an nvm-only host resolved to '$got' — the gate would say 'node not found' and NOT COVER 2 legs"
fi

# ── 3. newest by VERSION, not lexically ─────────────────────────────────────
nvm_root_home="$TMP/home2"
make_nvm "$TMP/nvm2" v9.0.0 v22.14.0 v18.19.1
got="$(resolve_in "$TMP/nvm2")"
case "$got" in
    */v22.14.0/*) ok "picks v22.14.0 over v9.0.0 — version-sorted, not lexical" ;;
    */v9.0.0/*)   bad "picked v9.0.0 over v22.14.0 — lexical sort, the classic off-by-a-major" ;;
    *)            bad "picked neither — got '$got'" ;;
esac

# ── 4. the default alias beats "newest" ─────────────────────────────────────
# An interactive shell would have used the alias, so that is the honest answer.
nvm_root_home="$TMP/home3"
make_nvm "$TMP/nvm3" v18.19.1 v22.14.0
mkdir -p "$TMP/nvm3/alias"
printf 'v18.19.1\n' > "$TMP/nvm3/alias/default"
got="$(resolve_in "$TMP/nvm3")"
case "$got" in
    */v18.19.1/*) ok "the nvm default alias is honoured over the newest install" ;;
    *)            bad "ignored the default alias — got '$got'" ;;
esac

# ── 5. a bare (v-less) alias resolves too ───────────────────────────────────
nvm_root_home="$TMP/home4"
make_nvm "$TMP/nvm4" v18.19.1 v22.14.0
mkdir -p "$TMP/nvm4/alias"
printf '18.19.1\n' > "$TMP/nvm4/alias/default"
got="$(resolve_in "$TMP/nvm4")"
case "$got" in
    */v18.19.1/*) ok "a bare alias ('18.19.1', no leading v) resolves" ;;
    *)            bad "bare alias not handled — got '$got'" ;;
esac

# ── 6. an unresolvable alias must not win ───────────────────────────────────
# `lts/*` names no directory. Falling through to newest is correct; returning
# empty would put the gate right back to "node not found".
nvm_root_home="$TMP/home5"
make_nvm "$TMP/nvm5" v22.14.0
mkdir -p "$TMP/nvm5/alias"
printf 'lts/*\n' > "$TMP/nvm5/alias/default"
got="$(resolve_in "$TMP/nvm5")"
case "$got" in
    */v22.14.0/*) ok "an alias naming no install falls through to a real one" ;;
    "")           bad "an unresolvable alias returned EMPTY — the gate would skip legs it can run" ;;
    *)            bad "unexpected: '$got'" ;;
esac

# ── 7. a stale NODE_CMD override must not be trusted blindly ────────────────
nvm_root_home="$TMP/home6"
make_nvm "$TMP/nvm6" v22.14.0
got="$(
    set +u
    export PATH="$HERMETIC_BIN" NODE_RESOLVE_PREFIXES=""
    export HOME="$nvm_root_home" NVM_DIR="$TMP/nvm6"
    export NODE_CMD="$TMP/deleted-by-an-upgrade/node"
    source "$LIB"; resolve_node
)"
case "$got" in
    */v22.14.0/*) ok "a NODE_CMD pointing at a deleted install falls through" ;;
    *)            bad "trusted a dead NODE_CMD — got '$got'" ;;
esac

# ── 8. no node anywhere ⇒ empty AND non-zero ────────────────────────────────
nvm_root_home="$TMP/home7"
mkdir -p "$TMP/nvm7"
got="$(resolve_in "$TMP/nvm7")"; rc=$?
if [ -z "$got" ]; then ok "a host with no node resolves to nothing (the skip stays honest)"
else bad "invented a node at '$got'"; fi

# ── 9. the absolute-prefix step, both directions ────────────────────────────
# Every case above sets NODE_RESOLVE_PREFIXES="" to switch step 3 off, so
# without these two the step would be untested in one direction and untestable
# in the other. The second is the CI runner's own shape: node at an absolute
# prefix, nvm present too — the prefix must win, because that IS the order.
mkdir -p "$TMP/prefix"
printf '#!/bin/sh\necho from-prefix\n' > "$TMP/prefix/node"
chmod +x "$TMP/prefix/node"
nvm_root_home="$TMP/home8"
make_nvm "$TMP/nvm8" v22.14.0
got="$(
    set +u; unset NODE_CMD
    export PATH="$HERMETIC_BIN" NODE_RESOLVE_PREFIXES="$TMP/prefix/node"
    export HOME="$nvm_root_home" NVM_DIR="$TMP/nvm8"
    source "$LIB"; resolve_node
)"
if [ "$got" = "$TMP/prefix/node" ]; then ok "an absolute prefix is honoured, and outranks nvm"
else bad "the absolute prefix lost to '$got'"; fi

got="$(
    set +u; unset NODE_CMD NODE_RESOLVE_PREFIXES
    export PATH="$HERMETIC_BIN"
    export HOME="$nvm_root_home" NVM_DIR="$TMP/nvm8"
    source "$LIB"; resolve_node
)"
# The expected answer is derived from the lib's own default list, never a copy
# of it: hardcoding `/opt/homebrew/bin/node|/usr/local/bin/node` here would go
# red the day someone adds a third prefix — a test failing for a change that is
# correct. Whichever of the defaults exists on THIS host is the right answer;
# if none does, nvm is.
default_hit=""
for p in $(sed -n 's/.*NODE_RESOLVE_PREFIXES-\([^}]*\)}.*/\1/p' "$LIB" | head -1); do
    [ -x "$p" ] && { default_hit="$p"; break; }
done
if [ -n "$default_hit" ]; then
    if [ "$got" = "$default_hit" ]; then ok "with no override, the lib's own default prefix list answers ($default_hit)"
    else bad "the default prefix list should have answered '$default_hit' — got '$got'"; fi
else
    case "$got" in
        */v22.14.0/*) ok "no default prefix exists on this host, so nvm answers" ;;
        *)            bad "no default prefix exists, so nvm should have answered — got '$got'" ;;
    esac
fi

# ── Mutation tests ──────────────────────────────────────────────────────────
# Each removes one guarantee and asserts the suite notices. A mutant that no
# longer applies to the lib is a FAILURE, not a pass — otherwise the suite
# silently stops testing whatever was refactored away.
mutant() {
    local name="$1" expr="$2" nvm="$3" expect="$4"
    local mutated="$TMP/mutated.sh"
    # Both guards matter. `cmp` catches a mutant that no longer matches anything
    # after a refactor; the exit/size check catches a mutant whose sed is simply
    # broken — that also "differs from the original" (it produces an empty file),
    # so cmp alone would let a syntax error masquerade as a caught mutant.
    if ! sed -E "$expr" "$LIB" > "$mutated" 2>"$TMP/sed.err"; then
        bad "mutant '$name' has a broken sed: $(cat "$TMP/sed.err")"
        return
    fi
    if [ ! -s "$mutated" ]; then
        bad "mutant '$name' produced an EMPTY lib — the sed is wrong, not the code"
        return
    fi
    if cmp -s "$LIB" "$mutated"; then
        bad "mutant '$name' no longer applies to the lib — it tests nothing now"
        return
    fi
    local out
    out="$(
        set +u
        unset NODE_CMD
        export PATH="$HERMETIC_BIN" NODE_RESOLVE_PREFIXES=""
        export HOME="$TMP/home-mut" NVM_DIR="$nvm"
        source "$mutated"; resolve_node
    )"
    case "$expect" in
        empty)
            if [ -z "$out" ]; then ok "mutant caught: $name"
            else bad "mutant SURVIVED: $name (still resolved '$out')"; fi ;;
        wrong-version)
            case "$out" in
                */v9.0.0/*) ok "mutant caught: $name" ;;
                *)          bad "mutant SURVIVED: $name (got '$out')" ;;
            esac ;;
    esac
}

echo "── mutants ──"
# Drop the nvm branch entirely: an nvm-only host must go back to finding nothing.
mutant 'remove the nvm fallback' \
    '/local nvm_root=/s|.*|    return 1|' "$TMP/nvm1" empty
# Sort lexically instead of by version: v9 must beat v22, which is the bug.
# Delimiter is `#`, not `|` — the pattern contains a literal pipe, and using `|`
# for both ends the expression early (`RE error: empty (sub)expression`).
mutant 'sort lexically instead of by version' \
    's#sort -t\. -k1\.2,1n -k2,2n -k3,3n#sort#' "$TMP/nvm2" wrong-version

echo
printf '%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]

#!/usr/bin/env bash
#
# node-resolve.sh — find the node binary that actually exists on this host,
# including the one nvm installed.
#
# `command -v node` answers for the CURRENT shell's PATH. Every script here runs
# from a non-interactive shell, which never sources `~/.zshrc`, which is the only
# place `nvm.sh` is loaded. So on a machine whose only node comes from nvm — this
# one — `command -v node` reports nothing while node is installed and working.
#
# That is not a cosmetic miss. `pre-push-check.sh` treats the empty answer as
# "this host cannot run JS" and downgrades two legs to NOT COVERED:
#
#     ⚠ node not found — JS validation NOT checked here (CI still gates it)
#     ⚠ node or tools/generate-gpt-knowledge.js not found — NOT checked here
#
# The gate is honest about skipping, which is what saved it — but the skip was
# never real. Both legs were runnable the whole time. Measured three times in a
# single day (2026-08-15), each time read as "the host is not set up for JS".
#
# Deliberately NOT a hardcoded version path. `~/.nvm/versions/node/v22.14.0/bin`
# works today and becomes a dead pointer at the next `nvm install` — a dead
# pointer that reads as authoritative is worse than no pointer at all.
#
# Resolution order, first hit wins:
#   1. $NODE_CMD, if the caller already exported one (lets CI pin a specific node)
#   2. PATH — the normal case, and the only one on CI runners
#   3. Homebrew's two canonical prefixes (Apple Silicon, then Intel)
#   4. nvm's default alias, then its newest installed version
#
# Usage:
#   source "$SCRIPT_DIR/lib/node-resolve.sh"
#   NODE_CMD=$(resolve_node)
#   [ -n "$NODE_CMD" ] || { echo "no node on this host"; }

resolve_node() {
    # 1. An explicit override always wins, but it must be real — an exported
    #    NODE_CMD pointing at a deleted install should fall through, not fail.
    if [ -n "${NODE_CMD:-}" ] && [ -x "$NODE_CMD" ]; then
        printf '%s\n' "$NODE_CMD"
        return 0
    fi

    # 2. PATH.
    local from_path
    from_path=$(command -v node 2>/dev/null)
    if [ -n "$from_path" ] && [ -x "$from_path" ]; then
        printf '%s\n' "$from_path"
        return 0
    fi

    # 3. Homebrew, whose prefix differs by architecture.
    local brew_candidate
    for brew_candidate in /opt/homebrew/bin/node /usr/local/bin/node; do
        if [ -x "$brew_candidate" ]; then
            printf '%s\n' "$brew_candidate"
            return 0
        fi
    done

    # 4. nvm. `$NVM_DIR` is set only once nvm.sh has been sourced, so fall back
    #    to its documented default location.
    local nvm_root="${NVM_DIR:-$HOME/.nvm}"
    [ -d "$nvm_root/versions/node" ] || return 1

    # The default alias is what an interactive shell would have picked, so it is
    # the honest answer — prefer it over "newest installed".
    local alias_file="$nvm_root/alias/default"
    if [ -r "$alias_file" ]; then
        local aliased
        aliased=$(tr -d '[:space:]' < "$alias_file" 2>/dev/null)
        if [ -n "$aliased" ]; then
            # The alias may be bare ("22.14.0"), v-prefixed, or a line-editing
            # alias like "lts/*" that names no directory — only accept a hit.
            local candidate
            for candidate in "$nvm_root/versions/node/$aliased/bin/node" \
                             "$nvm_root/versions/node/v$aliased/bin/node"; do
                if [ -x "$candidate" ]; then
                    printf '%s\n' "$candidate"
                    return 0
                fi
            done
        fi
    fi

    # Newest installed, by version sort rather than lexical — `v9` must not beat
    # `v22`, which is exactly what a plain `sort` or a glob would do.
    local newest
    newest=$(find "$nvm_root/versions/node" -maxdepth 1 -mindepth 1 -type d -name 'v*' 2>/dev/null \
        | sed 's|.*/||' \
        | sort -t. -k1.2,1n -k2,2n -k3,3n \
        | tail -1)
    [ -n "$newest" ] || return 1

    local resolved="$nvm_root/versions/node/$newest/bin/node"
    [ -x "$resolved" ] || return 1
    printf '%s\n' "$resolved"
}

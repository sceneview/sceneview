#!/usr/bin/env bash
# Resolve an iOS Simulator destination at run time: wait for CoreSimulator to be
# ready, then pick a device by UDID instead of pinning one by model name.
#
# WHY THIS EXISTS — AND WHAT THE REAL DEFECT TURNED OUT TO BE (#3174).
#
# The symptom is a job failing with:
#
#   xcodebuild: error: Unable to find a device matching the provided destination
#   specifier: { platform:iOS Simulator, OS:latest, name:iPhone 16 Pro }
#       Available destinations for the "SceneViewSwift" scheme:
#           { platform:macOS, … name:My Mac }
#           { platform:iOS, … name:Any iOS Device }
#           { platform:iOS Simulator, … name:Any iOS Simulator Device }
#
# The obvious reading is "the runner does not have that device". It is wrong,
# and acting on it makes things worse. Measured on run 31807717171 attempt 1,
# a GitHub-hosted `macos-15` runner, INSIDE ONE JOB:
#
#   14:07:45  step 5  `Build Swift Package (iOS)`  FAILS on iPhone 16 Pro,
#                     and the available-destination list contains no concrete
#                     simulator at all — only placeholders.
#   14:08:01  step 10 `Build & test iOS sample demo` RESOLVES the SAME
#                     destination: { … id:DB7A4F45-…, OS:26.2, name:iPhone 16 Pro }
#                     and goes on to succeed.
#
# Same runner, same device name, 16 seconds apart. The device was there all
# along. What was not ready was CoreSimulator: early in a runner's life the
# device set is not yet enumerable, so `xcodebuild` sees placeholders only. A
# rerun "fixes" it by starting over on a warmer machine, which is why this reads
# as flakiness and why attempt 2 was green.
#
# TWO CONSEQUENCES, and the second is the one that is easy to get wrong:
#
#   1. A `name=<model>` pin is still worth removing. It is a promise about a
#      machine we do not own — `Select Xcode` walks a preference list, and image
#      contents change — and a UDID resolved here is not.
#   2. ⛔ RESOLVING WITHOUT WAITING WOULD BE STRICTLY WORSE THAN THE BUG. Called
#      at the top of a job, `simctl list` hits the same cold window, returns no
#      iOS device, and a resolver that failed fast there would kill the WHOLE
#      job — where today only one step dies and the rest of the job still runs.
#      So the wait is not a nicety bolted onto the resolver; it is the half that
#      fixes the measured defect. Do not remove it to "simplify".
#
# The wait is bounded and it does NOT degrade to a green: after the timeout the
# functions return non-zero and dump the device list. A resolver that shrugged
# and emitted an empty destination would turn a broken runner into a quiet pass,
# the false-green class already paid for in #1515 and #2878.
#
# USAGE
#   . .claude/scripts/lib/ios-simulator.sh
#   dest="$(ios_simulator_destination)"   # platform=iOS Simulator,id=<UDID>
#   udid="$(ios_simulator_udid)"          # just the UDID
#
# ⛔ CALL IT IN AN ASSIGNMENT, never inside another command's arguments. The
# fail-closed contract above lives in the RETURN CODE, and `set -e` cannot see a
# command substitution that fails inside an argument list — the outer command's
# own status governs:
#
#   echo "IOS_DEST=$(ios_simulator_destination)" >> "$GITHUB_ENV"   # ⛔ exits 0, writes empty
#   IOS_DEST="$(ios_simulator_destination)"                         # ✅ exits 1, aborts
#
# Both CI call sites shipped the first form and silently discarded every
# guarantee this file makes. Pinned by test-ios-simulator.sh, which greps the
# workflows — a library self-test cannot see a caller's mistake.
#
# Override the wait with IOS_SIM_WAIT_SECONDS (default 180, 0 = one attempt).

IOS_SIM_WAIT_SECONDS="${IOS_SIM_WAIT_SECONDS:-180}"
IOS_SIM_POLL_SECONDS="${IOS_SIM_POLL_SECONDS:-5}"

# Picks the best device out of a `simctl list devices` listing on stdin, and
# prints "<udid>\t<name>". Empty output means "nothing usable in this listing".
#
# Selection: an iPhone beats any other iOS device, and within a class the newest
# iOS runtime wins. The runtime version is parsed out of each `-- iOS <ver> --`
# header and COMPARED, not inferred from position: today simctl emits those
# headers in ascending order, and an earlier revision of this file relied on that
# by simply overwriting on every match. That is an unpinned assumption about
# another team's output format, and the failure mode is silent — a reordered
# listing would quietly select the OLDEST runtime instead of failing. Comparing
# costs one awk function.
#
# We hand xcodebuild the UDID, never the display name — that stays unambiguous
# when one host carries two devices of the same name under different runtimes.
_ios_simulator_pick() {
    awk '
        # "26.2" -> 26002000, so a plain numeric > compares runtimes correctly
        # across differing component counts ("17" vs "17.0.1").
        function vnum(v,   parts, n, i, r) {
            n = split(v, parts, ".")
            r = 0
            for (i = 1; i <= 3; i++) r = r * 1000 + (i <= n ? parts[i] + 0 : 0)
            return r
        }
        /^-- iOS / {
            in_ios = 1
            ver = $0
            sub(/^-- iOS +/, "", ver); sub(/ +--.*$/, "", ver)
            cur = vnum(ver)
            next
        }
        /^-- /      { in_ios = 0; next }
        in_ios && match($0, /\([0-9A-Fa-f-]{36}\)/) {
            udid = substr($0, RSTART + 1, RLENGTH - 2)
            name = substr($0, 1, RSTART - 1)
            sub(/^ +/, "", name); sub(/ +$/, "", name)
            if (name ~ /^iPhone/) {
                if (!iphone || cur > iphone_v) { iphone = udid; iphone_name = name; iphone_v = cur }
            } else {
                if (!other  || cur > other_v)  { other  = udid; other_name  = name; other_v  = cur }
            }
        }
        END {
            if (iphone)     print iphone "\t" iphone_name
            else if (other) print other  "\t" other_name
        }
    '
}

# Prints the UDID of the best available iOS simulator, waiting up to
# IOS_SIM_WAIT_SECONDS for CoreSimulator to enumerate its devices.
ios_simulator_udid() {
    local deadline=$((SECONDS + IOS_SIM_WAIT_SECONDS))
    local listing="" picked="" waited=0

    while :; do
        # A failing `simctl` is NOT fatal on its own here: the cold window this
        # function exists for can also make `simctl` itself unhappy. Keep its
        # output for the final error message and let the timeout decide. The
        # `|| true` matters — callers run under `set -e`.
        listing="$(xcrun simctl list devices available 2>&1)" || true
        picked="$(printf '%s\n' "$listing" | _ios_simulator_pick)"

        [ -n "$picked" ] && break
        [ "$SECONDS" -ge "$deadline" ] && break

        if [ "$waited" -eq 0 ]; then
            printf 'ios-simulator: CoreSimulator lists no iOS device yet — waiting up to %ss (#3174).\n' \
                "$IOS_SIM_WAIT_SECONDS" >&2
        fi
        waited=1
        sleep "$IOS_SIM_POLL_SECONDS"
    done

    if [ -z "$picked" ]; then
        printf 'ios-simulator: no iOS simulator became available within %ss.\n' \
            "$IOS_SIM_WAIT_SECONDS" >&2
        printf '%s\n' "$listing" >&2
        return 1
    fi

    [ "$waited" -eq 1 ] && printf 'ios-simulator: CoreSimulator became ready.\n' >&2
    printf 'ios-simulator: using %s\n' "${picked#*$'\t'}" >&2
    printf '%s\n' "${picked%%$'\t'*}"
}

# Prints a full `-destination` argument for xcodebuild.
ios_simulator_destination() {
    local udid
    udid="$(ios_simulator_udid)" || return 1
    printf 'platform=iOS Simulator,id=%s\n' "$udid"
}

#!/usr/bin/env bash
# qa-connectivity.sh — probe REAL network reachability before trusting a
# streamed-asset device-QA leg (#2959).
#
# THE GAP THIS CLOSES
# --------------------
# device-qa.sh already asked `settings get global airplane_mode_on` (#2959,
# PR #3317) before trusting the `sketchfab` / `arcore-cloud` sub-legs — that
# catches the exact failure measured closing #2942 (a `qa-clean` snapshot
# cold-booting with the radio in airplane mode, so two different Sketchfab
# slugs both rendered the same bundled helmet). It does NOT catch a device
# whose radio is ON but whose route is dead: a captive portal, a DNS resolver
# that stopped answering, a dropped VPN, or a host Wi-Fi with no upstream. In
# every one of those the airplane-mode-only check reports "online" while every
# streamed slug still silently resolves to its bundled fallback — the exact
# #2959 failure mode, through a different door.
#
# This probe layers three signals, most to least specific, and refuses to
# call the result "online" unless one of them actually proves a route out:
#   1. airplane_mode_on       — radio state (the original #2959 signal).
#   2. `dumpsys connectivity` — Android's own captive-portal-VALIDATED
#      NetworkAgentInfo flag: the SAME signal the status-bar icon uses, so it
#      already folds in DNS + HTTP + captive-portal detection.
#   3. `ping` to the real streamed-asset host — DNS resolution + an ICMP round
#      trip to `api.sketchfab.com`, the host every SampleAssets.kt streamed
#      entry actually resolves through (SketchfabConfig.BASE_URL, mirrored on
#      iOS by SceneViewDemo/Services/SketchfabConfig.swift). `curl` is not
#      assumed present on a stock emulator image (verify-sketchfab-key.sh
#      already guards curl host-side only); `ping` (toybox) is present on
#      every Android API level this harness targets.
#
# Any step whose command errors or is unavailable reports "unknown" for that
# signal — never "false". A probe that could not run must never manufacture a
# verdict either way: an "unknown" signal cannot turn the combined result
# "online" (fail-closed, the same rule #2959 established for a missing
# ANDROID_SERIAL), but it also does not by itself turn it "offline" — only a
# signal that positively answered "no route" does.
#
# Usage:
#   source lib/qa-connectivity.sh
#   qa_connectivity_probe "$serial"
#   echo "$QA_CONNECTIVITY_STATUS"   # online | offline | unknown
#   qa_connectivity_json             # compact JSON for a report file
#   qa_connectivity_verdict_line     # one human-readable line

# Guard against double-sourcing in the same shell (same pattern as qa-keys.sh).
if [[ -n "${QA_CONNECTIVITY_SH_SOURCED:-}" ]]; then
  # shellcheck disable=SC2317
  return 0 2>/dev/null || true
fi
QA_CONNECTIVITY_SH_SOURCED=1

# The host every streamed demo (Android + iOS) actually resolves through.
QA_CONNECTIVITY_CHECK_HOST="api.sketchfab.com"

# qa_connectivity_probe <serial> — probes the given device/emulator and
# exports:
#   QA_CONNECTIVITY_STATUS          online | offline | unknown
#   QA_CONNECTIVITY_AIRPLANE_MODE   0 | 1 | unknown
#   QA_CONNECTIVITY_VALIDATED       true | false | unknown  (dumpsys signal)
#   QA_CONNECTIVITY_HOST_REACHABLE  true | false | unknown  (ping signal)
#   QA_CONNECTIVITY_HOST            the host probed (QA_CONNECTIVITY_CHECK_HOST)
#   QA_CONNECTIVITY_METHOD          free-text trace of what decided the verdict
qa_connectivity_probe() {
  local serial="${1:?qa_connectivity_probe needs a device serial}"
  local adb_bin="${ADB_BIN:-adb}"

  QA_CONNECTIVITY_AIRPLANE_MODE="unknown"
  QA_CONNECTIVITY_VALIDATED="unknown"
  QA_CONNECTIVITY_HOST_REACHABLE="unknown"
  QA_CONNECTIVITY_HOST="$QA_CONNECTIVITY_CHECK_HOST"

  local mode
  mode="$("$adb_bin" -s "$serial" shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r\n')"
  case "$mode" in
    0|1) QA_CONNECTIVITY_AIRPLANE_MODE="$mode" ;;
    *)   QA_CONNECTIVITY_AIRPLANE_MODE="unknown" ;;
  esac

  # Short-circuit: a CONFIRMED airplane mode ON is definitive — no point
  # probing further, and it keeps this the cheap, common case fast.
  if [[ "$QA_CONNECTIVITY_AIRPLANE_MODE" == "1" ]]; then
    QA_CONNECTIVITY_STATUS="offline"
    QA_CONNECTIVITY_METHOD="airplane_mode_on=1"
    export QA_CONNECTIVITY_STATUS QA_CONNECTIVITY_AIRPLANE_MODE QA_CONNECTIVITY_VALIDATED \
           QA_CONNECTIVITY_HOST_REACHABLE QA_CONNECTIVITY_HOST QA_CONNECTIVITY_METHOD
    return 0
  fi

  # Signal 2: Android's own captive-portal-validated network flag. Output
  # shape varies by API level; treat any explicit VALIDATED mention as a pass,
  # a real dump with no such mention as a real "no", and a dumpsys failure
  # (permission, absent) as "unknown".
  local dump
  if dump="$("$adb_bin" -s "$serial" shell dumpsys connectivity 2>/dev/null)" && [[ -n "$dump" ]]; then
    if printf '%s' "$dump" | grep -qiE 'validated:[[:space:]]*true|\[VALIDATED\]|VALIDATED\}'; then
      QA_CONNECTIVITY_VALIDATED="true"
    else
      QA_CONNECTIVITY_VALIDATED="false"
    fi
  fi

  # Signal 3: an actual fetch of the real streamed-asset host — DNS
  # resolution + an ICMP round trip through the device's own network
  # namespace (not the host's). A missing/erroring `ping` is "unknown", never
  # "false": a locked-down or minimal emulator image without `ping` must not
  # manufacture an offline verdict off a tool gap.
  local ping_out
  ping_out="$("$adb_bin" -s "$serial" shell ping -c 1 -W 2 "$QA_CONNECTIVITY_CHECK_HOST" 2>&1)"
  if printf '%s' "$ping_out" | grep -qE '1 packets transmitted, 1 (packets )?received'; then
    QA_CONNECTIVITY_HOST_REACHABLE="true"
  elif printf '%s' "$ping_out" | grep -qiE 'unknown host|bad address|name or service not known|network is unreachable|100% packet loss'; then
    QA_CONNECTIVITY_HOST_REACHABLE="false"
  fi
  # else: unrecognized output (e.g. "ping: not found", permission denied) —
  # stays "unknown", per the fail-closed rule above.

  if [[ "$QA_CONNECTIVITY_VALIDATED" == "true" || "$QA_CONNECTIVITY_HOST_REACHABLE" == "true" ]]; then
    QA_CONNECTIVITY_STATUS="online"
  elif [[ "$QA_CONNECTIVITY_VALIDATED" == "false" && "$QA_CONNECTIVITY_HOST_REACHABLE" == "false" ]]; then
    QA_CONNECTIVITY_STATUS="offline"
  else
    QA_CONNECTIVITY_STATUS="unknown"
  fi
  QA_CONNECTIVITY_METHOD="airplane_mode_on=0 dumpsys_validated=${QA_CONNECTIVITY_VALIDATED} ping:${QA_CONNECTIVITY_CHECK_HOST}=${QA_CONNECTIVITY_HOST_REACHABLE}"

  export QA_CONNECTIVITY_STATUS QA_CONNECTIVITY_AIRPLANE_MODE QA_CONNECTIVITY_VALIDATED \
         QA_CONNECTIVITY_HOST_REACHABLE QA_CONNECTIVITY_HOST QA_CONNECTIVITY_METHOD
}

# qa_connectivity_json — compact single-line JSON object from the last probe,
# for embedding into a `connectivity` field in a machine-readable report.
# Never call before qa_connectivity_probe — falls back to "unknown"/empty
# fields rather than erroring, so a caller that forgot the probe gets an
# honest "unknown" record instead of a crash.
qa_connectivity_json() {
  printf '{"status":"%s","airplaneMode":"%s","checkedHost":"%s","method":"%s"}' \
    "${QA_CONNECTIVITY_STATUS:-unknown}" \
    "${QA_CONNECTIVITY_AIRPLANE_MODE:-unknown}" \
    "${QA_CONNECTIVITY_HOST:-$QA_CONNECTIVITY_CHECK_HOST}" \
    "${QA_CONNECTIVITY_METHOD:-not probed}"
}

# qa_connectivity_verdict_line — one human-readable line for the run log.
qa_connectivity_verdict_line() {
  echo "[qa-connectivity] status=${QA_CONNECTIVITY_STATUS:-unknown} airplane_mode=${QA_CONNECTIVITY_AIRPLANE_MODE:-unknown} checked_host=${QA_CONNECTIVITY_HOST:-$QA_CONNECTIVITY_CHECK_HOST} (${QA_CONNECTIVITY_METHOD:-not probed})"
}

# qa_connectivity_probe_host -- HOST-side reachability probe (#2959), for a
# platform whose test runner shares the HOST's network stack rather than a
# device's own namespace -- the iOS Simulator is the case this harness has
# today: probing the simulator itself would just re-measure the Mac's own
# network, so ios-device-qa.sh calls this instead of qa_connectivity_probe.
# Uses `curl` (present on every macOS host this harness runs on, unlike a
# stock Android emulator image) against the same streamed-asset host. Sets
# the same QA_CONNECTIVITY_* globals as qa_connectivity_probe so callers can
# share qa_connectivity_json / qa_connectivity_verdict_line either way.
qa_connectivity_probe_host() {
  QA_CONNECTIVITY_AIRPLANE_MODE="n/a"    # meaningless for a host probe
  QA_CONNECTIVITY_VALIDATED="n/a"
  QA_CONNECTIVITY_HOST="$QA_CONNECTIVITY_CHECK_HOST"

  if ! command -v curl >/dev/null 2>&1; then
    QA_CONNECTIVITY_HOST_REACHABLE="unknown"
    QA_CONNECTIVITY_STATUS="unknown"
    QA_CONNECTIVITY_METHOD="curl not available on host"
    export QA_CONNECTIVITY_STATUS QA_CONNECTIVITY_AIRPLANE_MODE QA_CONNECTIVITY_VALIDATED \
           QA_CONNECTIVITY_HOST_REACHABLE QA_CONNECTIVITY_HOST QA_CONNECTIVITY_METHOD
    return 0
  fi

  # Any HTTP response (even a 4xx) proves a route exists -- only a connection
  # failure (curl's synthetic "000") means no route. No auth token needed:
  # this only asks "is the host reachable", not "is the key valid" (that is
  # verify-sketchfab-key.sh's job).
  local http_status
  http_status="$(curl -sS -o /dev/null -w '%{http_code}' \
    --connect-timeout 5 --max-time 10 \
    "https://${QA_CONNECTIVITY_CHECK_HOST}/v3/" 2>/dev/null || echo "000")"

  if [[ "$http_status" == "000" ]]; then
    QA_CONNECTIVITY_HOST_REACHABLE="false"
    QA_CONNECTIVITY_STATUS="offline"
  else
    QA_CONNECTIVITY_HOST_REACHABLE="true"
    QA_CONNECTIVITY_STATUS="online"
  fi
  QA_CONNECTIVITY_METHOD="host curl https://${QA_CONNECTIVITY_CHECK_HOST}/v3/ -> ${http_status}"

  export QA_CONNECTIVITY_STATUS QA_CONNECTIVITY_AIRPLANE_MODE QA_CONNECTIVITY_VALIDATED \
         QA_CONNECTIVITY_HOST_REACHABLE QA_CONNECTIVITY_HOST QA_CONNECTIVITY_METHOD
}

# qa_connectivity_banner_offline <legs...> — LOUD boxed stderr banner, same
# convention as qa_keys_banner_if_absent, for the DEFAULT (no --allow-offline)
# path: the caller is refusing to let this pass unnoticed. With --allow-offline
# the caller should print a single quiet line instead (see device-qa.sh).
qa_connectivity_banner_offline() {
  local legs="$*"
  {
    echo ""
    echo "┌────────────────────────────────────────────────────────────────────────────┐"
    echo "│ ⚠️  QA INCOMPLETE — no route to ${QA_CONNECTIVITY_HOST:-$QA_CONNECTIVITY_CHECK_HOST} (#2959)"
    echo "│                                                                            │"
    echo "│ Streamed-asset legs would silently resolve to their bundled fallback — the  │"
    echo "│ same file in, same file out, so the reload path never runs (measured        │"
    echo "│ closing #2942). Skipped, never counted as a pass: ${legs}"
    echo "│                                                                            │"
    echo "│ airplane_mode_on=${QA_CONNECTIVITY_AIRPLANE_MODE:-unknown}  dumpsys validated=${QA_CONNECTIVITY_VALIDATED:-unknown}  ping ${QA_CONNECTIVITY_HOST:-$QA_CONNECTIVITY_CHECK_HOST}=${QA_CONNECTIVITY_HOST_REACHABLE:-unknown}"
    echo "│                                                                            │"
    echo "│ Pass --allow-offline to acknowledge this and silence the banner on a        │"
    echo "│ deliberately offline run.                                                   │"
    echo "└────────────────────────────────────────────────────────────────────────────┘"
  } >&2
}

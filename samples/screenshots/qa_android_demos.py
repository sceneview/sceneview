#!/usr/bin/env python3
"""
Android Demo App Visual QA Script
Automates testing of all 24 non-AR demos in the SceneView demo app.

Uses Google's `android` CLI (developer.android.com/tools/agents/android-cli) for
UI dumps (JSON with precomputed `center` coords — no XML parsing) and screenshots
(no LF/CRLF corruption). Falls back to adb if the CLI is not installed.
"""

import json
import re
import subprocess
import time
import os
import shutil
import sys
import xml.etree.ElementTree as ET
import html

# Force unbuffered output
sys.stdout.reconfigure(line_buffering=True)
sys.stderr.reconfigure(line_buffering=True)

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")


def _android_cli_bin():
    """Locate the `android` binary on each call.

    Resolved lazily so a binary installed mid-run (e.g. by another helper) is
    picked up without re-importing this module.
    """
    found = shutil.which("android")
    if found:
        return found
    home_local = os.path.expanduser("~/.local/bin/android")
    return home_local if os.path.exists(home_local) else None


# Pin the serial when multiple devices are attached. `android screen capture`
# has no --device flag in v0.7; the layout subcommand does.
ANDROID_SERIAL = os.environ.get("ANDROID_SERIAL", "")
PACKAGE = "io.github.sceneview.demo.qa"  # debug build: applicationIdSuffix ".qa" (#4138)
ACTIVITY = "io.github.sceneview.demo.MainActivity"
SCREENSHOT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "android")

DEMOS = [
    "Model Viewer",
    "Geometry Primitives",
    "Auto Rotate",
    "Multi Model",
    "Lighting",
    "Dynamic Sky",
    "Fog",
    "Environment Gallery",
    "Text Labels",
    "Lines & Paths",
    "Image Planes",
    "Billboard",
    "Video",
    "Camera Controls",
    "Gesture Editing",
    "Collision",
    "ViewNode",
    "Physics",
    "Post Processing",
    "Custom Mesh",
    "All Shapes",
    "Reflection Probes",
    "Secondary Camera",
    "Debug Overlay",
]


def adb(*args):
    """Run an adb command and return stdout."""
    cmd = [ADB] + list(args)
    result = subprocess.run(cmd, capture_output=True, timeout=30)
    return result.stdout, result.stderr, result.returncode


def adb_shell_str(*args):
    """Run adb shell command and return stdout as string."""
    stdout, _, _ = adb("shell", *args)
    return stdout.decode("utf-8", errors="replace")


def screenshot(filename):
    """Take a screenshot and save to local file.

    Prefers Google's `android screen capture` (LF/CRLF-safe, writes the PNG
    directly). Falls back to `adb exec-out screencap` if the CLI is missing
    or fails (e.g. multi-device — `screen capture` has no --device flag in v0.7).
    """
    filepath = os.path.join(SCREENSHOT_DIR, filename)
    # Remove any stale file from a prior iteration so a failed CLI call is not
    # confused with success.
    try:
        os.remove(filepath)
    except OSError:
        pass
    android_bin = _android_cli_bin()
    if android_bin:
        try:
            result = subprocess.run(
                [android_bin, "--no-metrics", "screen", "capture", "--output", filepath],
                capture_output=True,
                timeout=30,
            )
            if result.returncode == 0 and os.path.exists(filepath) and os.path.getsize(filepath) > 1000:
                print(f"  Screenshot saved: {filename} ({os.path.getsize(filepath)} bytes)")
                return True
            err = result.stderr.decode("utf-8", errors="replace").strip()
            if err:
                print(f"  android screen capture stderr: {err[:200]}")
        except (subprocess.TimeoutExpired, FileNotFoundError, OSError) as exc:
            print(f"  android screen capture raised: {exc!r}")
    # adb fallback. Pinned serial avoids multi-device ambiguity.
    if ANDROID_SERIAL:
        stdout, _, rc = adb("-s", ANDROID_SERIAL, "exec-out", "screencap", "-p")
    else:
        stdout, _, rc = adb("exec-out", "screencap", "-p")
    if rc == 0 and len(stdout) > 1000:
        with open(filepath, "wb") as f:
            f.write(stdout)
        print(f"  Screenshot saved (adb fallback): {filename} ({len(stdout)} bytes)")
        return True
    print(f"  Screenshot FAILED: {filename}")
    return False


def get_layout_json():
    """Dump UI hierarchy as JSON via `android layout`.

    Returns a list of nodes, each with `text`, `center`, `bounds`, `interactions`.
    Returns None if the android CLI is unavailable or any error occurs; callers
    fall back to `get_ui_xml()` + `find_node_in_xml()`.
    """
    android_bin = _android_cli_bin()
    if not android_bin:
        return None
    cmd = [android_bin, "--no-metrics", "layout", "--pretty"]
    if ANDROID_SERIAL:
        cmd.append(f"--device={ANDROID_SERIAL}")
    try:
        result = subprocess.run(cmd, capture_output=True, timeout=30)
    except (subprocess.TimeoutExpired, FileNotFoundError, OSError):
        return None
    if result.returncode != 0:
        return None
    try:
        return json.loads(result.stdout.decode("utf-8", errors="replace"))
    except (json.JSONDecodeError, ValueError, UnicodeDecodeError):
        return None


def find_node_in_layout(nodes, text):
    """Find the (x, y) center of the first node whose text matches.

    Operates on the JSON list returned by `get_layout_json()` — no XML parsing.
    """
    if not nodes:
        return None
    for node in nodes:
        node_text = node.get("text", "")
        if html.unescape(node_text) != text:
            continue
        center = node.get("center", "")
        # Accept negative coords too — off-screen elements report `[-N,M]`.
        m = re.match(r"\[(-?\d+),(-?\d+)\]", center)
        if m:
            return int(m.group(1)), int(m.group(2))
    return None


def get_ui_xml():
    """Dump UI hierarchy and return XML string (adb fallback path)."""
    adb_shell_str("uiautomator", "dump", "/sdcard/ui.xml")
    return adb_shell_str("cat", "/sdcard/ui.xml")


def find_node_in_xml(xml_str, text):
    """Find a node with matching text in uiautomator XML (adb fallback path)."""
    try:
        root = ET.fromstring(xml_str)
    except ET.ParseError:
        return None

    for elem in root.iter("node"):
        node_text = elem.get("text", "")
        decoded = html.unescape(node_text)
        if decoded == text:
            bounds = elem.get("bounds", "")
            if bounds:
                parts = bounds.replace("][", ",").replace("[", "").replace("]", "").split(",")
                if len(parts) == 4:
                    x1, y1, x2, y2 = int(parts[0]), int(parts[1]), int(parts[2]), int(parts[3])
                    return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def check_app_alive():
    """Check if our app is in the foreground using a lightweight command."""
    text = adb_shell_str("dumpsys", "window", "displays", "|", "grep", "mCurrentFocus")
    return PACKAGE in text


def force_stop_and_launch():
    """Force stop and relaunch the app."""
    print("Force-stopping app...")
    adb_shell_str("am", "force-stop", PACKAGE)
    time.sleep(1)
    print("Launching app...")
    adb_shell_str("am", "start", "-n", f"{PACKAGE}/{ACTIVITY}")
    time.sleep(4)


def go_back():
    """Press back button."""
    adb_shell_str("input", "keyevent", "BACK")
    time.sleep(1)


def scroll_down():
    """Scroll down in the list."""
    adb_shell_str("input", "swipe", "540", "1400", "540", "900", "300")
    time.sleep(0.5)


def scroll_to_top():
    """Scroll to the very top of the list."""
    for _ in range(8):
        adb_shell_str("input", "swipe", "540", "400", "540", "1800", "150")
        time.sleep(0.15)
    time.sleep(0.3)


def find_and_tap_demo(demo_name, max_scrolls=12):
    """Find a demo by name in the list and tap it.

    Uses `android layout` (JSON, precomputed centers) when available, falls back
    to `adb shell uiautomator dump` + XML parsing.
    """
    for attempt in range(max_scrolls):
        coords = None
        layout = get_layout_json()
        if layout is not None:
            coords = find_node_in_layout(layout, demo_name)
        if coords is None:
            xml_str = get_ui_xml()
            coords = find_node_in_xml(xml_str, demo_name)
        if coords:
            cx, cy = coords
            print(f"  Found '{demo_name}' at ({cx}, {cy}), tapping...")
            adb_shell_str("input", "tap", str(cx), str(cy))
            return True
        scroll_down()
    return False


def sanitize_filename(name):
    """Convert demo name to a safe filename."""
    return name.lower().replace(" ", "_").replace("&", "and").replace("'", "")


def main():
    os.makedirs(SCREENSHOT_DIR, exist_ok=True)
    results = {}

    force_stop_and_launch()
    time.sleep(1)
    screenshot("00_main_list.png")

    for i, demo in enumerate(DEMOS):
        idx = str(i + 1).zfill(2)
        fname = f"{idx}_{sanitize_filename(demo)}.png"
        print(f"\n[{idx}/24] Testing: {demo}")

        # Scroll to top for first demo
        if i == 0:
            scroll_to_top()

        # Find and tap
        found = find_and_tap_demo(demo)
        if not found:
            print(f"  NOT FOUND: {demo}")
            results[demo] = "NOT_FOUND"
            # Recovery: relaunch + scroll to top, then scroll down to roughly where we were
            force_stop_and_launch()
            scroll_to_top()
            for _ in range(max(0, i // 3)):
                scroll_down()
            continue

        # Wait for rendering
        wait_time = 6 if demo in ("Model Viewer", "Multi Model", "Auto Rotate", "Video", "Physics") else 4
        print(f"  Waiting {wait_time}s for rendering...")
        time.sleep(wait_time)

        # Check for crash
        if not check_app_alive():
            print(f"  CRASH detected for: {demo}")
            results[demo] = "CRASH"
            # Screenshot crash state
            screenshot(f"{idx}_{sanitize_filename(demo)}_crash.png")
            # Recovery
            force_stop_and_launch()
            scroll_to_top()
            for _ in range(max(0, i // 3)):
                scroll_down()
            continue

        # Take screenshot
        if screenshot(fname):
            results[demo] = "OK"
        else:
            results[demo] = "SCREENSHOT_FAILED"

        # Go back
        go_back()
        time.sleep(0.5)

        # Verify we're back
        if not check_app_alive():
            print("  Lost focus, relaunching...")
            force_stop_and_launch()
            scroll_to_top()
            for _ in range(max(0, (i + 1) // 3)):
                scroll_down()

    # Summary
    print("\n" + "=" * 60)
    print("QA SUMMARY")
    print("=" * 60)
    counts = {"OK": 0, "CRASH": 0, "NOT_FOUND": 0, "SCREENSHOT_FAILED": 0}
    for demo in DEMOS:
        status = results.get(demo, "UNKNOWN")
        tag = {"OK": "PASS", "CRASH": "FAIL", "NOT_FOUND": "MISS"}.get(status, "FAIL")
        print(f"  [{tag}] {demo}: {status}")
        if status in counts:
            counts[status] += 1

    print(f"\nTotal: {counts['OK']} OK, {counts['CRASH']} CRASH, {counts['NOT_FOUND']} NOT_FOUND")
    print(f"Screenshots saved to: {SCREENSHOT_DIR}")
    return 0 if counts["CRASH"] == 0 and counts["NOT_FOUND"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

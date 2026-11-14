#!/usr/bin/env python3
"""Compare two debug APKs on private ARCore walks; no build, upload or device cleanup of walks."""

import argparse
import csv
from datetime import datetime
import html
import json
import math
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import statistics
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = Path(__file__).with_name("scan-replay-bench-fixtures")
PACKAGE = "io.github.sceneview.demo.qa"
ACTIVITY = f"{PACKAGE}/io.github.sceneview.demo.MainActivity"
DEVICE_RECORDINGS = f"/sdcard/Android/data/{PACKAGE}/files/ar-recordings"
SCOPE_PREFS = "shared_prefs/sceneview_demo_ar_rerun.xml"
SCOPE_KEY = "ar_rerun_scan_scope"
REFERENCES = dict(zip("ABCDE", (
    "ar-session-20260928-003538", "ar-session-20260928-003416",
    "ar-session-20260929-092427", "ar-session-20260930-224347",
    "ar-session-20260926-202254",
)))
POLL_SECONDS = 2.0
METRICS = (
    ("ar_fps_mean", "AR hook fps · mean", "fps", 1),
    ("ar_fps_worst", "AR hook fps · worst second", "fps", 1),
    ("sf_fps_mean", "SurfaceFlinger fps · mean", "fps", 1),
    ("first_surface_s", "First surface / point", "s", -1),
    ("floor_m2", "Final HUD floor", "m²", 1),
    ("points", "Final HUD points", "points", 1),
    ("moving_too_fast_s", "Moving too fast", "s", -1),
    ("outside_elements", "Outside room", "surfels", -1),
    ("fatals", "FATAL", "lines", -1),
    ("anrs", "ANR", "lines", -1),
    ("result_ready_s", "Photo + Model + Plan", "s", -1),
)
POLL_FIELDS = ("phase", "elapsed_s", "epoch_s", "warning", "tabs_ready", "floor_m2",
               "points", "text", "error")


class BenchError(Exception):
    pass


class LockBusy(BenchError):
    pass


def command(args, timeout=60, check=True):
    try:
        result = subprocess.run([str(a) for a in args], stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise BenchError(f"{args[0]}: {exc}") from exc
    if check and result.returncode:
        raise BenchError(f"{args[0]} exited {result.returncode}: {result.stdout[-2000:]}")
    return result.stdout


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def write_tsv(path, rows, fields):
    with path.open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, delimiter="\t", extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def number(value):
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0 else None


def parse_hud(text):
    # Compose may merge the HUD into one node. Accept comma, thin/NBSP, apostrophe and dot
    # grouping for counts, plus either decimal separator for floor area.
    # scan_coverage_label says "Scan map: 14 m² of floor covered..."; older HUDs
    # put "14 m² floor" directly in the status line.
    area = re.search(r"(\d+(?:[.,]\d+)?)\s*m(?:²|2)\s+(?:of\s+)?floor\b", text, re.I)
    points = re.search(r"(\d(?:[\d,.'’\s\u00a0\u202f]*\d)?)\s+points?\b", text, re.I)
    return {"floor_m2": float(area[1].replace(",", ".")) if area else None,
            "points": int(re.sub(r"\D", "", points[1])) if points else None}


def parse_ui(xml):
    start = xml.find("<?xml")
    if start < 0:
        start = xml.find("<hierarchy")
    if start < 0:
        raise BenchError("uiautomator returned no XML hierarchy")
    try:
        tree = ET.fromstring(xml[start:].strip())
    except ET.ParseError as exc:
        raise BenchError(f"Invalid uiautomator XML: {exc}") from exc
    texts, targets = [], {}
    for node in tree.iter("node"):
        labels = [node.get(key, "").strip() for key in ("text", "content-desc")]
        bounds = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
        for label in filter(None, labels):
            if label not in texts:
                texts.append(label)
            if bounds:
                x0, y0, x1, y1 = map(int, bounds.groups())
                if x1 > x0 and y1 > y0:
                    targets.setdefault(label.casefold(), ((x0 + x1) // 2, (y0 + y1) // 2))
    text = " | ".join(texts)
    return {"text": text, "targets": targets, "warning": "moving too fast" in text.casefold(),
            "tabs_ready": all(name in {t.casefold() for t in texts} for name in ("photo", "model", "plan")),
            **parse_hud(text)}


def latency_frames(text):
    """SurfaceFlinger: desiredPresentTime, actualPresentTime, frameReadyTime (nanoseconds)."""
    frames = set()
    for line in text.splitlines():
        fields = line.split()
        if len(fields) == 3 and all(re.fullmatch(r"\d+", f) for f in fields):
            actual = int(fields[1])
            if 0 < actual < 9_000_000_000_000_000_000:
                frames.add(actual)
    return sorted(frames)


def latency_sample(text, previous=0):
    frames = [stamp for stamp in latency_frames(text) if stamp > previous]
    fps = (len(frames) - 1) * 1e9 / (frames[-1] - frames[0]) if len(frames) >= 3 else None
    return fps, max(frames, default=previous)


def select_layers(text):
    return [line.strip() for line in text.splitlines()
            if PACKAGE in line and "surfaceview" in line.lower() and "blast" in line.lower()]


def parse_logcat(text, launch_epoch, live_end_epoch=None):
    first, outside, fps = None, None, []
    fatals = anrs = 0
    observed = False
    for line in text.splitlines():
        stamp = re.match(r"\s*(\d+\.\d+)\s", line)
        if not stamp or launch_epoch is None or float(stamp[1]) < launch_epoch:
            continue
        epoch = float(stamp[1])
        fatals += bool(re.search(r"\bFATAL\b|\bFatal signal\b", line))
        anrs += bool(re.search(r"\bam_anr\b|\bANR in\b", line))
        match = re.search(r"\bScanBench\s*:\s*(\{.*\})\s*$", line)
        if not match:
            continue
        try:
            data = json.loads(match[1])
        except (ValueError, TypeError):
            continue
        if not isinstance(data, dict) or data.get("schema") not in (1, 2):
            continue
        if live_end_epoch is not None and epoch > live_end_epoch:
            continue
        observed = True
        if first is None and data.get("surface_ready") is True:
            first = epoch - launch_epoch
        if "outside_elements" in data:
            outside = number(data["outside_elements"])
        cadence = number(data.get("fps"))
        window = number(data.get("window_start_epoch_ms"))
        if cadence is not None and (window is None or window / 1000 >= launch_epoch):
            fps.append(cadence)
    return {"first_surface_s": first, "outside_elements": outside,
            "ar_fps_mean": statistics.mean(fps) if fps else None,
            "ar_fps_worst": min(fps) if fps else None, "ar_seconds": len(fps),
            "fatals": fatals if launch_epoch is not None else None,
            "anrs": anrs if launch_epoch is not None else None, "bench_observed": observed}


def truth(value):
    return value is True or str(value).casefold() in ("1", "true")


def parse_polls(rows, live_end_s, tap_s=None):
    final = {"floor_m2": None, "points": None}
    warning_seconds = 0.0
    covered_seconds = 0.0
    ready = None
    live = [r for r in rows if r["phase"] == "live"]
    for index, row in enumerate(live):
        elapsed = float(row["elapsed_s"])
        if row.get("error") or elapsed > live_end_s:
            continue
        # Hold a positive observation for at most one 2 s poll interval. Missed dumps must
        # not turn into invented warning time. Preserve actual timestamps for inspection.
        next_s = float(live[index + 1]["elapsed_s"]) if index + 1 < len(live) else live_end_s
        span = max(0.0, min(POLL_SECONDS, next_s - elapsed, live_end_s - elapsed))
        covered_seconds += span
        if truth(row.get("warning")):
            warning_seconds += span
        for key in final:
            value = row.get(key)
            if value not in (None, ""):
                final[key] = float(value) if key == "floor_m2" else int(value)
    if tap_s is not None:
        for row in rows:
            if row["phase"] == "result" and not row.get("error") and truth(row.get("tabs_ready")):
                if float(row["elapsed_s"]) >= tap_s:
                    ready = float(row["elapsed_s"]) - tap_s
                    break
    return {**final, "moving_too_fast_s": warning_seconds if covered_seconds else None,
            "poll_observed_s": covered_seconds, "result_ready_s": ready}


def duration(path):
    raw = command(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                   "-of", "default=noprint_wrappers=1:nokey=1", path])
    try:
        seconds = float(raw.strip())
    except ValueError as exc:
        raise BenchError(f"No duration for {path.name}") from exc
    if not math.isfinite(seconds) or seconds <= 0:
        raise BenchError(f"Invalid duration for {path.name}")
    return seconds


def segment_lengths(seconds):
    seconds = math.ceil(seconds)
    return [min(180, seconds - start) for start in range(0, seconds, 180)]


class DeviceLock:
    def __init__(self, path=Path("/tmp/pixel4a.lock"), timeout=600, interval=15):
        self.path, self.timeout, self.interval = path, timeout, interval
        self.owner = f"scan-replay-bench {datetime.now().astimezone().isoformat()} pid={os.getpid()} {uuid.uuid4().hex}\n"
        self.held = False

    def __enter__(self):
        deadline = time.monotonic() + self.timeout
        announced = False
        while True:
            try:
                self.path.mkdir(mode=0o700)
                break
            except FileExistsError:
                try:
                    owner = (self.path / "owner").read_text().strip()
                except OSError:
                    owner = "owner unavailable (lock retained)"
                if time.monotonic() >= deadline:
                    raise LockBusy(f"Skipped after {self.timeout}s waiting for {self.path}: {owner}")
                if not announced:
                    print(f"Device lock held: {owner}; waiting up to {self.timeout}s", flush=True)
                    announced = True
                time.sleep(min(self.interval, max(0, deadline - time.monotonic())))
        # A failed owner write leaves the lock intact. Never infer ownership from its age.
        (self.path / "owner").write_text(self.owner)
        self.held = True
        return self

    def __exit__(self, *_):
        if self.held:
            try:
                if (self.path / "owner").read_text() == self.owner:
                    (self.path / "owner").unlink()
                    self.path.rmdir()
            except OSError:
                pass
            self.held = False


class Device:
    def __init__(self, adb, serial):
        self.prefix = [adb, "-s", serial]

    def call(self, *args, **kwargs):
        return command(self.prefix + list(args), **kwargs)

    def shell(self, *args, **kwargs):
        return self.call("shell", shlex.join(str(a) for a in args), **kwargs)

    def script(self, script, **kwargs):
        return self.call("shell", script, **kwargs)

    def start(self, script, output):
        return subprocess.Popen(self.prefix + ["shell", script], stdout=output, stderr=subprocess.STDOUT)

    def screenshot(self, target):
        with target.open("wb") as stream:
            try:
                subprocess.run(self.prefix + ["exec-out", "screencap", "-p"], stdout=stream,
                               stderr=subprocess.PIPE, check=True, timeout=15)
            except (OSError, subprocess.SubprocessError) as exc:
                raise BenchError(f"screencap failed: {exc}") from exc


def device_epoch(raw):
    match = re.search(r"^BENCH_EPOCH=(\d+\.\d+)$", raw, re.M)
    if not match:
        raise BenchError("Device date +%s.%N did not return an epoch timestamp")
    return float(match[1])


DATE_MARKER = 'printf "BENCH_EPOCH="; date +%s.%N'


def ui_poll(device, remote_xml, launch_epoch, phase):
    # Never read a stale dump after a failed uiautomator invocation. The timestamp follows
    # the dump; it is in the same device epoch clock as am start and logcat -v epoch.
    script = (f"uiautomator dump {shlex.quote(remote_xml)} >/dev/null && "
              f"{{ {DATE_MARKER}; cat {shlex.quote(remote_xml)}; }}")
    raw = device.script(script, timeout=12)
    epoch = device_epoch(raw)
    return {"phase": phase, "epoch_s": epoch, "elapsed_s": epoch - launch_epoch,
            "error": "", **parse_ui(raw)}


def tap(device, targets, text):
    coordinates = targets.get(text.casefold())
    if coordinates is None:
        raise BenchError(f'No text/content-desc target: "{text}"')
    return device_epoch(device.script(f"{DATE_MARKER}; input tap {coordinates[0]} {coordinates[1]}", timeout=10))


def ensure_walk(device, walk):
    device.shell("mkdir", "-p", DEVICE_RECORDINGS)
    target = f"{DEVICE_RECORDINGS}/{walk.name}"
    remote_size = device.shell("stat", "-c", "%s", target, check=False).strip()
    if remote_size != str(walk.stat().st_size):
        device.call("push", walk, target, timeout=300)
        actual = device.shell("stat", "-c", "%s", target).strip()
        if actual != str(walk.stat().st_size):
            raise BenchError(f"Pushed walk size mismatch: {walk.name}")
    return target


def install_build(device, apk, out, slot):
    with DeviceLock():
        device.shell("am", "force-stop", PACKAGE)
        install = device.call("install", "-r", "-d", apk, timeout=300)
        (out / f"install-{slot}.txt").write_text(install)
        if "Success" not in install:
            raise BenchError("APK installation did not report Success")
        device.shell("pm", "grant", PACKAGE, "android.permission.CAMERA")
        return installed_build(device)


def installed_build(device):
    # The lock is released between walks; detect another owner's intervening install.
    raw = device.shell("dumpsys", "package", PACKAGE)
    fields = [line.strip() for line in raw.splitlines()
              if re.match(r"\s*(lastUpdateTime|versionCode|codePath)=", line)]
    if not fields:
        raise BenchError("Cannot identify the installed build")
    return "\n".join(fields)


def scan_preferences(raw):
    try:
        prefs = ET.fromstring(raw)
    except ET.ParseError as exc:
        raise BenchError("Invalid scan scope SharedPreferences XML") from exc
    if prefs.tag != "map":
        raise BenchError("Scan scope SharedPreferences root is not a map")
    return prefs


def select_room(device):
    # ar_playback_file bypasses the scope chooser and starts capture immediately.
    # On these debug APKs, run-as can set ScanScopeStore's actual preference while
    # the app is force-stopped, before any playback frames or timing are consumed.
    # Preserve other keys and honour Android's recovery backup; replace atomically
    # and remove that backup so SharedPreferences cannot restore the old scope.
    read = (f"if [ -f {SCOPE_PREFS}.bak ]; then cat {SCOPE_PREFS}.bak; "
            f"elif [ -f {SCOPE_PREFS} ]; then cat {SCOPE_PREFS}; else printf '<map />'; fi")
    try:
        prefs = scan_preferences(device.shell("run-as", PACKAGE, "sh", "-c", read))
        for entry in list(prefs):
            if entry.get("name") == SCOPE_KEY:
                prefs.remove(entry)
        ET.SubElement(prefs, "string", name=SCOPE_KEY).text = "room"
        content = ET.tostring(prefs, encoding="unicode")
        temporary = f"{SCOPE_PREFS}.scan-bench-{uuid.uuid4().hex}"
        write = (f"mkdir -p shared_prefs && printf '%s\\n' {shlex.quote(content)} > {temporary} && "
                 f"mv -f {temporary} {SCOPE_PREFS} && rm -f {SCOPE_PREFS}.bak")
        device.shell("run-as", PACKAGE, "sh", "-c", write)
        saved = scan_preferences(device.shell("run-as", PACKAGE, "sh", "-c", read))
        scopes = [entry.text for entry in saved if entry.tag == "string" and entry.get("name") == SCOPE_KEY]
        if scopes != ["room"]:
            raise BenchError("Room preference did not persist")
    except BenchError as exc:
        raise BenchError(f"Cannot select Room before playback (debug run-as required): {exc}") from exc


MEASURE_ONLY = False


class _Timer:
    def __init__(self, length):
        self.end = time.monotonic() + length
        self.returncode = 0

    def poll(self):
        return None if time.monotonic() < self.end else 0


class SleepRecorder:
    """--measure-only live phase: no screenrecord, no uiautomator, no SurfaceFlinger dumps.
    The scan runs alone on the device for the same duration, so ScanBench reads its own cadence."""
    def __init__(self, phase, length):
        self.phase = phase
        self.process = _Timer(length)
        self.deadline = time.monotonic() + length + 20

    def finish(self, interrupted=False):
        return self

    def pull(self):
        return None


def new_recorder(device, run_dir, phase, index, length, token):
    if MEASURE_ONLY and phase == "live":
        return SleepRecorder(phase, length)
    return Recorder(device, run_dir, phase, index, length, token)


class Recorder:
    """Own exactly one remote screenrecord PID; finalize/pull before removing its files."""
    def __init__(self, device, run_dir, phase, index, length, token):
        self.device, self.run_dir, self.phase, self.index = device, run_dir, phase, index
        self.remote = f"/sdcard/scan-bench-{token}-{phase}-{index}.mp4"
        self.pid = self.remote + ".pid"
        self.stream = (run_dir / f"{phase}-{index}-screenrecord.txt").open("w")
        script = (f"echo $$ > {shlex.quote(self.pid)}; exec screenrecord --bit-rate 6000000 "
                  f"--time-limit {length} {shlex.quote(self.remote)}")
        self.process = device.start(script, self.stream)
        self.deadline = time.monotonic() + length + 20

    def finish(self, interrupted=False):
        try:
            if self.process.poll() is None:
                pid = self.device.shell("cat", self.pid, check=False).strip()
                if pid.isdigit():
                    self.device.shell("kill", "-2", pid, check=False)
                try:
                    self.process.wait(timeout=10)
                except subprocess.TimeoutExpired as exc:
                    self.process.terminate()
                    raise BenchError("screenrecord did not finish after SIGINT; remote video retained") from exc
            if self.process.returncode and not interrupted:
                raise BenchError(f"screenrecord exited {self.process.returncode}; see its log")
            return self
        finally:
            self.stream.close()

    def pull(self):
        local = self.run_dir / f"{self.phase}-{self.index:03}.mp4"
        self.device.call("pull", self.remote, local, timeout=180)
        # Only recordings/PID files owned by this run; the source walk stays in the app.
        self.device.shell("rm", "-f", self.remote, self.pid)
        return local


def join_segments(parts, target):
    parts = [part for part in parts if part is not None]
    if not parts:
        return
    if len(parts) == 1:
        parts[0].replace(target)
        return
    manifest = target.with_suffix(".concat.txt")
    # Generated filenames contain no quotes or newlines; use paths relative to the manifest.
    manifest.write_text("".join(f"file '{part.name}'\n" for part in parts))
    command(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "1", "-i", manifest,
             "-c", "copy", target], timeout=180)
    manifest.unlink()
    for part in parts:
        part.unlink()


def launch_script(dataset):
    args = ["am", "start", "-W", "-n", ACTIVITY, "--es", "demo", "ar-rerun",
            "--es", "telemetry_consent", "denied", "--es", "ar_playback_file", dataset]
    return f"{DATE_MARKER}; exec {shlex.join(args)}"


def new_result(walk, label, slot):
    return {"walk": walk.stem, "walk_label": next((k for k, v in REFERENCES.items() if v == walk.stem), "—"),
            "label": label, "build": slot, "status": "not run", "error": "", "warnings": [],
            "directory": f"{walk.stem}/{slot}", **{key: None for key, *_ in METRICS}}


def record_run(device, walk, result, run_dir, seconds, build_identity):
    """Caller owns the lock for the whole run. Launch, tap and logs use device epoch time."""
    launch_epoch = live_end_epoch = tap_epoch = build_epoch = None
    launch_process = recorder = None
    rows, sf_samples, previous = [], [], {}
    xml = f"/sdcard/scan-bench-{uuid.uuid4().hex}.xml"
    token = uuid.uuid4().hex
    parts = {"live": [], "result": []}
    model_selected = False
    touched_app = False
    try:
        if installed_build(device) != build_identity:
            raise BenchError("Installed build changed while lock was released; run skipped to avoid a mislabeled comparison")
        dataset = ensure_walk(device, walk)
        device.shell("input", "keyevent", "KEYCODE_WAKEUP")
        device.shell("am", "force-stop", PACKAGE)
        touched_app = True
        select_room(device)
        result["scan_scope"] = "room"
        device.call("logcat", "-b", "all", "-c")
        result["clip_duration_s"] = seconds
        result["live_requested_s"] = math.ceil(seconds + 20)
        with (run_dir / "polls.tsv").open("w", newline="") as polls:
            writer = csv.DictWriter(polls, fieldnames=POLL_FIELDS, delimiter="\t", extrasaction="ignore")
            writer.writeheader()
            with (run_dir / "launch.txt").open("w") as launch_file:
                recorder = new_recorder(device, run_dir, "live", 0, segment_lengths(seconds + 20)[0], token)
                launch_process = device.start(launch_script(dataset), launch_file)
                launch_host = time.monotonic()
                while time.monotonic() - launch_host < 10:
                    raw = (run_dir / "launch.txt").read_text()
                    if "BENCH_EPOCH=" in raw and re.search(r"BENCH_EPOCH=\d+\.\d+\n", raw):
                        launch_epoch = device_epoch(raw)
                        break
                    time.sleep(.05)
                if launch_epoch is None:
                    raise BenchError("No launch clock returned; see launch.txt")
                result["launch_epoch_s"] = launch_epoch
                for phase, lengths in (("live", segment_lengths(seconds + 20)), ("result", [25])):
                    if phase == "result":
                        # Read a fresh hierarchy for the exact Stop and review target.
                        stop_ui = ui_poll(device, xml, launch_epoch, "live")
                        rows.append(stop_ui)
                        writer.writerow(stop_ui)
                        polls.flush()
                        if "stop and review" not in stop_ui["targets"]:
                            raise BenchError('"Stop and review" not found; live capture retained')
                        recorder = Recorder(device, run_dir, "result", 0, 25, token)
                        tap_epoch = tap(device, stop_ui["targets"], "Stop and review")
                        live_end_epoch = tap_epoch
                        result["stop_tap_epoch_s"] = tap_epoch
                    next_poll = time.monotonic()
                    for index, length in enumerate(lengths):
                        if index:
                            recorder = new_recorder(device, run_dir, phase, index, length, token)
                        while recorder.process.poll() is None:
                            if time.monotonic() > recorder.deadline:
                                raise BenchError("screenrecord exceeded its time limit")
                            if time.monotonic() < next_poll or (MEASURE_ONLY and phase == "live"):
                                time.sleep(min(.1, max(0.0, next_poll - time.monotonic())) or .1)
                                continue
                            try:
                                row = ui_poll(device, xml, launch_epoch, phase)
                                if phase == "result":
                                    if row["tabs_ready"] and not model_selected:
                                        result["model_tap_epoch_s"] = tap(device, row["targets"], "Model")
                                        model_selected = True
                                    elif build_epoch is None:
                                        # Stop enters ScanPhase.Reviewing. ScanReviewCard offers
                                        # BUILD_MODEL, or BUILD_ANYWAY when there are repairs.
                                        for label in ("Build 3D model", "Build anyway"):
                                            if label.casefold() in row["targets"]:
                                                build_epoch = tap(device, row["targets"], label)
                                                result["build_tap_epoch_s"] = build_epoch
                                                break
                            except BenchError as exc:
                                epoch = launch_epoch + time.monotonic() - launch_host
                                row = {"phase": phase, "epoch_s": epoch, "elapsed_s": epoch - launch_epoch,
                                       "error": str(exc), "text": ""}
                            rows.append(row)
                            writer.writerow(row)
                            polls.flush()
                            if phase == "live":
                                sample = {"elapsed_s": row["elapsed_s"], "fps": None}
                                try:
                                    layers = select_layers(device.shell("dumpsys", "SurfaceFlinger", "--list", timeout=5))
                                    if len(layers) != 1:
                                        raise BenchError(f"Expected one app SurfaceView BLAST layer, found {len(layers)}")
                                    layer = layers[0]
                                    raw = device.shell("dumpsys", "SurfaceFlinger", "--latency", layer, timeout=5)
                                    raw_name = f"sf-{len(sf_samples):04}.txt"
                                    (run_dir / raw_name).write_text(raw)
                                    fps, newest = latency_sample(raw, previous.get(layer, 0))
                                    previous[layer] = newest
                                    sample.update(fps=fps, layer=layer, raw=raw_name)
                                except BenchError as exc:
                                    sample["error"] = str(exc)
                                sf_samples.append(sample)
                            next_poll += POLL_SECONDS
                            if next_poll < time.monotonic():
                                # Do not issue catch-up dumps back to back after a slow dump.
                                next_poll += math.ceil((time.monotonic() - next_poll) / POLL_SECONDS) * POLL_SECONDS
                        parts[phase].append(recorder.finish())
                        recorder = None
                    if phase == "live":
                        live_end_epoch = device_epoch(device.script(DATE_MARKER))
                        if launch_process.poll() is None:
                            raise BenchError("am start -W did not finish during the live capture")
                        raw = (run_dir / "launch.txt").read_text()
                        if launch_process.returncode or re.search(r"Error:|Exception|Status:\s*(?!ok)\w+", raw):
                            raise BenchError("Launch failed; see launch.txt")
                    elif model_selected:
                        device.screenshot(run_dir / "model.png")
            result["status"] = "captured"
    finally:
        if recorder is not None:
            try:
                parts[recorder.phase].append(recorder.finish(interrupted=True))
            except (BenchError, OSError) as exc:
                result["warnings"].append(f"Recording recovery: {exc}")
        if launch_process is not None and launch_process.poll() is None:
            launch_process.terminate()
            try:
                launch_process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                launch_process.kill()
                launch_process.wait(timeout=5)
        if touched_app:
            try:
                logs = device.call("logcat", "-b", "all", "-d", "-v", "epoch", timeout=30)
                (run_dir / "logcat.txt").write_text(logs)
                result.update(parse_logcat(logs, launch_epoch, live_end_epoch))
            except BenchError as exc:
                result["warnings"].append(f"Logcat unavailable: {exc}")
            try:
                device.shell("am", "force-stop", PACKAGE)
                device.shell("rm", "-f", xml)
            except BenchError as exc:
                result["warnings"].append(f"Device cleanup failed: {exc}")
        for phase, recordings in parts.items():
            try:
                join_segments([recording.pull() for recording in recordings], run_dir / f"{phase}.mp4")
            except (BenchError, OSError) as exc:
                result["warnings"].append(f"{phase} video assembly failed: {exc}")
        result["live_end_epoch_s"] = live_end_epoch
        write_json(run_dir / "surfaceflinger.json", sf_samples)
        valid_sf = [s["fps"] for s in sf_samples if s["fps"] is not None]
        result["sf_fps_mean"] = statistics.mean(valid_sf) if valid_sf else None
        result["sf_samples"] = len(valid_sf)
        if launch_epoch is not None:
            end = (live_end_epoch - launch_epoch) if live_end_epoch else max((r["elapsed_s"] for r in rows if r["phase"] == "live"), default=0)
            result.update(parse_polls(rows, end, tap_epoch - launch_epoch if tap_epoch else None))
        if not result.get("bench_observed"):
            result["warnings"].append("No live ScanBench observations; the APK may lack the hook.")
        if not valid_sf:
            result["warnings"].append("No SurfaceView BLAST cadence available; no activity-layer fallback.")
        if any(r.get("error") for r in rows):
            result["warnings"].append("Some accessibility polls failed; see polls.tsv. Warning time excludes missing intervals.")
        if result.get("result_ready_s") is None:
            result["warnings"].append("Photo + Model + Plan were not observed together within the result recording.")
        if not model_selected:
            result["warnings"].append("Model view could not be selected; no model.png capture.")
        if result.get("fatals") or result.get("anrs"):
            result["status"] = "failed"
            result["error"] = "FATAL or ANR in logcat"
        elif result["status"] == "captured" and (result.get("result_ready_s") is None or not result.get("ar_seconds")):
            result["status"] = "incomplete"


def has_drawtext():
    return bool(re.search(r"\bdrawtext\s", command(["ffmpeg", "-hide_banner", "-filters"])))


def extract_frame(video, target, at=None):
    timing = ["-sseof", "-2"] if at is None else ["-ss", str(at)]
    # Reverse only the scaled final two seconds: select the actual last frame even when
    # screenrecord's variable frame rate has no frame within 150 ms of the container end.
    filters = "scale=320:640:force_original_aspect_ratio=decrease,pad=320:640:(ow-iw)/2:(oh-ih)/2,format=yuvj420p"
    if at is None:
        filters += ",reverse"
    command(["ffmpeg", "-v", "error", "-y", *timing, "-i", video, "-frames:v", "1",
             "-vf", filters,
             "-q:v", "3", target])
    if not target.is_file():
        raise BenchError(f"No frame produced for {video.name} at {at}")


def run_media(out, result):
    run_dir = out / result["directory"]
    live = run_dir / "live.mp4"
    if live.is_file():
        seconds = duration(live)
        label = re.sub(r"[^A-Za-z0-9._-]+", "-", result["label"]).strip(".-") or result["build"]
        tiles = run_dir / f'{result["walk"]}-{label}-tiles.jpg'
        rows = max(1, math.ceil(math.ceil(seconds / 2) / 8))
        command(["ffmpeg", "-v", "error", "-y", "-i", live, "-vf",
                 f"fps=1/2:start_time=0,scale=216:-2,tile=8x{rows}", "-frames:v", "1", "-q:v", "3", tiles], timeout=180)
        result["tiles"] = str(tiles.relative_to(out))
        for at in (8, 16, 24):
            extract_frame(live, run_dir / f"live-{at}.jpg", at)
    video = run_dir / "result.mp4"
    if video.is_file():
        extract_frame(video, run_dir / "result-end.jpg")


def comparison_media(out, pair, drawtext):
    walk = pair[0]["walk"]
    target = out / f"{walk}-ab.jpg"
    paths = [out / r["directory"] / name for name in ("live-8.jpg", "live-16.jpg", "live-24.jpg", "result-end.jpg") for r in pair]
    if not all(path.is_file() for path in paths):
        return None
    args = ["ffmpeg", "-v", "error", "-y"]
    for path in paths:
        args += ["-i", path]
    filters = []
    for i in range(8):
        label = f"Build {'AB'[i % 2]} - {('8 s', '16 s', '24 s', 'Result end')[i // 2]}"
        effect = f"drawtext=text='{label}':fontsize=22:fontcolor=white:box=1:boxcolor=black:x=8:y=8" if drawtext else "null"
        filters.append(f"[{i}:v]{effect}[v{i}]")
    for row in range(4):
        filters.append(f"[v{row * 2}][v{row * 2 + 1}]hstack=inputs=2[row{row}]")
    filters.append("[row0][row1][row2][row3]vstack=inputs=4[out]")
    command(args + ["-filter_complex", ";".join(filters), "-map", "[out]", "-frames:v", "1", "-q:v", "3", target])
    return target.name


def escape(value):
    return html.escape(str(value), quote=True)


def metric(value, unit):
    return "Unavailable" if value is None else f"{value:,.2f} {unit}"


def delta(a, b, direction, unit):
    if a is None or b is None:
        return "Unavailable"
    change = b - a
    verdict = "same" if abs(change) < .005 else ("better" if change * direction > 0 else "worse")
    return f'{change:+,.2f} {unit} · <span class="{verdict}">{verdict}</span>'


def image_link(path, caption):
    return f'<figure><a href="{escape(path)}"><img loading="lazy" src="{escape(path)}" alt="{escape(caption)}"></a><figcaption>{escape(caption)}</figcaption></figure>'


def generate_sheet(out, results, labels, notes=()):
    grouped = {}
    for result in results:
        grouped.setdefault(result["walk"], {})[result["build"]] = result
    summary, sections = [], []
    for index, (walk, builds) in enumerate(grouped.items()):
        a, b = builds["A"], builds["B"]
        title = f'{a["walk_label"]} · {walk}'
        summary.append(f'<tr><th><a href="#walk-{index}">{escape(title)}</a></th><td>{escape(a["status"])} / {escape(b["status"])}</td>' +
                       "".join(f"<td>{metric(a.get(k), u)} → {metric(b.get(k), u)}<br>{delta(a.get(k), b.get(k), d, u)}</td>" for k, _, u, d in METRICS) + "</tr>")
        panels = []
        for slot, result in (("A", a), ("B", b)):
            media = image_link(result["tiles"], f"Live · one image every 2 s, row by row from t = 0 s") if result.get("tiles") else '<p class="missing">No live contact sheet</p>'
            run_dir = out / result["directory"]
            if (run_dir / "model.png").is_file():
                media += image_link(f'{result["directory"]}/model.png', "Model view at the end")
            links = " · ".join(f'<a href="{escape(result["directory"])}/{name}">{name}</a>' for name in
                               ("live.mp4", "result.mp4", "polls.tsv", "logcat.txt", "surfaceflinger.json") if (run_dir / name).is_file())
            issues = ([result["error"]] if result["error"] else []) + result["warnings"]
            panels.append(f'<div class="build"><h3>Build {slot} · {escape(result["label"])}</h3><p>{escape(result["status"])}</p>{media}<p>{links}</p>' +
                          "".join(f'<p class="note">{escape(issue)}</p>' for issue in issues) + "</div>")
        ab = f"{walk}-ab.jpg"
        comparison = image_link(ab, f"Left: build A ({labels[0]}). Right: build B ({labels[1]}). Rows: live t = 8, 16, 24 s, then the last result frame.") if (out / ab).is_file() else "<p>A | B comparison unavailable until both recordings contain all four frames.</p>"
        rows = "".join(f"<tr><th>{title}</th><td>{metric(a.get(k), u)}</td><td>{metric(b.get(k), u)}</td><td>{delta(a.get(k), b.get(k), d, u)}</td></tr>" for k, title, u, d in METRICS)
        sections.append(f'<section id="walk-{index}"><h2>{escape(title)}</h2><div class="pair">{"".join(panels)}</div>'
                        f'<div class="comparison">{comparison}</div><div class="scroll"><table><thead><tr><th>Measure</th>'
                        f'<th>A · {escape(labels[0])}</th><th>B · {escape(labels[1])}</th><th>B − A</th></tr></thead><tbody>{rows}</tbody></table></div></section>')
    css = Path(__file__).with_name("scan-replay-bench.css").read_text()
    document = f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src 'self' file:; style-src 'unsafe-inline'">
<title>Scan replay comparison</title><style>{css}</style></head><body>
<header><h1>Scan replay comparison</h1><p>Build A · {escape(labels[0])} / Build B · {escape(labels[1])}</p>
<p>Private local report · {len(grouped)} walks · <a href="mesures.tsv">mesures.tsv</a></p></header><main>
<h2>Summary</h2><p>Each cell: A → B, then B − A. Walk letters A–E identify the reference walks; build letters identify the APKs.</p>
<div class="scroll"><table><thead><tr><th>Walk</th><th>Status A / B</th>{"".join(f'<th>{title}</th>' for _, title, _, _ in METRICS)}</tr></thead><tbody>{"".join(summary)}</tbody></table></div>
{"".join(f'<p>{escape(note)}</p>' for note in notes)}
<p class="note">Higher cadence, floor and point counts are labelled better; lower delay, warning time, outside counts and errors are labelled better.
Floor and point counts describe completeness, not geometric accuracy. Missing measures remain unavailable.</p>
<p class="note">AR fps counts live render-hook calls in full 1 s buckets, including repeated trace frames and gaps recovered when calls resume.
A terminal stall with no later call has no final bucket. SurfaceFlinger is a separate mean of valid 2 s polls of the unique app SurfaceView BLAST layer's
actual presentation timestamps; its finite history can miss long stalls. Neither counter proves new camera images.</p>
<p class="note">First surface / point: device epoch at am start to ScanBench surface_ready, before GPU presentation.
Final floor and points come from the last live accessibility HUD. Moving too fast integrates positive polls for at most 2 s each;
missing intervals are excluded (poll_observed_s in TSV). Dump completion times are recorded; slow dumps can exceed the 2 s schedule.
Result delay ends at the first poll containing Photo, Model and Plan together, with 2 s polling resolution plus dump latency.</p>
<p class="note">Outside room: last live count of drawn surfel centres outside closed detected walls' convex hull in XZ, with 10 cm tolerance.
It is unavailable before a closed room, and is not an object count. FATAL and ANR count matching log lines after launch, including the result phase;
one incident can produce more than one line. Both APKs need the debug hook. Videos start just before launch/tap; the live start-up screen lasts about 6 s.</p>
{"".join(sections)}</main></body></html>'''
    (out / "sheet.html").write_text(document)
    write_json(out / "results.json", results)
    fields = ["walk", "walk_label", "build", "label", "status"] + [key for key, *_ in METRICS] + [
        "ar_seconds", "sf_samples", "poll_observed_s", "launch_epoch_s", "stop_tap_epoch_s", "error", "warnings"]
    write_tsv(out / "mesures.tsv", [{**r, "warnings": " | ".join(r["warnings"])} for r in results], fields)


def output_dir(value):
    path = Path(value).expanduser().resolve()
    if path == ROOT or ROOT in path.parents or any((p / ".git").exists() for p in (path, *path.parents)):
        raise BenchError("--out must be outside every repository (private home imagery)")
    if path.exists() and any(path.iterdir()):
        raise BenchError("--out must be empty; choose a fresh directory to avoid mixing builds")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    return path


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("apk-a", "apk-b", "label-a", "label-b"):
        parser.add_argument("--" + name)
    parser.add_argument("--corpus", default=str(ROOT / "samples/android-demo/ar-corpus/pixel9-thomas/ar-recordings"))
    parser.add_argument("--walks", default="reference", help="Default: reference A–E. Use all, or comma-separated IDs (optional .mp4) / reference letters")
    parser.add_argument("--serial", default="09291JEC223501")
    parser.add_argument("--out", default=str(Path.home() / "sceneview-bench" / datetime.now().strftime("%Y-%m-%d-%H%M")))
    parser.add_argument("--measure-only", action="store_true",
                        help="Live phase without screenrecord, uiautomator or SurfaceFlinger dumps: cadence from ScanBench alone")
    parser.add_argument("--self-test", action="store_true", help="Synthetic parser, device lifecycle and ffmpeg/report checks; no adb")
    args = parser.parse_args(argv)
    global MEASURE_ONLY
    MEASURE_ONLY = args.measure_only
    if not args.self_test:
        for name in ("apk_a", "apk_b"):
            if not getattr(args, name):
                parser.error("--" + name.replace("_", "-") + " is required")
    return args


def walks_for(args):
    corpus = Path(args.corpus).expanduser().resolve()
    if not corpus.is_dir():
        raise BenchError(f"Corpus does not exist: {corpus}; use --corpus with the private ar-recordings directory")
    if args.walks == "reference":
        names = list(REFERENCES.values())
    elif args.walks == "all":
        names = sorted(p.stem for p in corpus.glob("*.mp4"))
    else:
        names = [REFERENCES.get(n.strip(), n.strip()) for n in args.walks.split(",")]
    names = [n if n.endswith(".mp4") else n + ".mp4" for n in names]
    if not names or any(n == ".mp4" or Path(n).name != n for n in names) or len(set(names)) != len(names):
        raise BenchError("--walks requires distinct IDs or filenames without paths")
    walks = [(corpus / n).resolve() for n in names]
    if any(not p.is_file() or p.parent != corpus for p in walks):
        raise BenchError("A selected walk is missing or resolves outside the corpus")
    return walks


def run_builds(device, apks, walks, results, out, labels, notes, drawtext):
    for slot, apk in zip("AB", apks):
        selected = [r for r in results if r["build"] == slot]
        try:
            identity = install_build(device, apk, out, slot)
        except (BenchError, OSError) as exc:
            for result in selected:
                result.update(status="skipped", error=f"Build installation skipped/failed: {exc}")
            generate_sheet(out, results, labels, notes)
            continue
        for index, (walk, result) in enumerate(zip(walks, selected)):
            run_dir = out / result["directory"]
            run_dir.mkdir(parents=True)
            print(f'Build {slot} · walk {result["walk_label"]} · {walk.stem} [{index + 1}/{len(walks)}]', flush=True)
            try:
                seconds = duration(walk)
                with DeviceLock():
                    record_run(device, walk, result, run_dir, seconds, identity)
            except LockBusy as exc:
                result.update(status="skipped", error=str(exc))
            except (BenchError, OSError) as exc:
                result.update(status="failed", error=str(exc))
            finally:
                write_json(run_dir / "metrics.json", result)
            # CPU/media work holds no device lock.
            try:
                run_media(out, result)
                pair = [r for r in results if r["walk"] == walk.stem]
                comparison_media(out, pair, drawtext)
            except (BenchError, OSError) as exc:
                result["warnings"].append(f"Media generation: {exc}")
            write_json(run_dir / "metrics.json", result)
            generate_sheet(out, results, labels, notes)


def main(argv=None):
    args = arguments(argv)
    os.umask(0o077)
    out, results, notes = None, [], []
    labels = [args.label_a or (Path(args.apk_a).stem if args.apk_a else "main"),
              args.label_b or (Path(args.apk_b).stem if args.apk_b else "v2")]
    exit_code = 0
    try:
        for executable in ("ffmpeg", "ffprobe"):
            if not shutil.which(executable):
                raise BenchError(f"Missing dependency: {executable}")
        if args.self_test:
            out = output_dir(args.out)
            sys.path.insert(0, str(FIXTURES))
            from self_test import run_tests
            return run_tests(sys.modules[__name__], out)
        apks = [Path(args.apk_a).expanduser().resolve(), Path(args.apk_b).expanduser().resolve()]
        if any(not p.is_file() for p in apks):
            raise BenchError("Both APKs must exist")
        walks = walks_for(args)
        adb = shutil.which("adb") or str(Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))) / "platform-tools/adb")
        if not Path(adb).is_file():
            raise BenchError("adb unavailable; add Android platform-tools to PATH")
        out = output_dir(args.out)
        print(command(["df", "-h", out]).strip(), flush=True)
        labels = [args.label_a or apks[0].stem, args.label_b or apks[1].stem]
        results = [new_result(walk, label, slot) for walk in walks for slot, label in zip("AB", labels)]
        generate_sheet(out, results, labels, notes)
        run_builds(Device(adb, args.serial), apks, walks, results, out, labels, notes, has_drawtext())
        exit_code = 1 if any(r["status"] != "captured" or r["warnings"] for r in results) else 0
    except KeyboardInterrupt:
        exit_code = 130
        notes.append("Interrupted; completed artifacts retained, remaining runs not run.")
    except (BenchError, OSError) as exc:
        print(f"scan-replay-bench: {exc}", file=sys.stderr)
        exit_code = 2
    finally:
        if out:
            if results:
                generate_sheet(out, results, labels, notes)
            print(out, flush=True)
    return exit_code


if __name__ == "__main__":
    def interrupt(_signum, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupt)
    sys.exit(main())

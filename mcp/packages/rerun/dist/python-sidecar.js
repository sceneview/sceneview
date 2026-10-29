// ─── Python sidecar generator ─────────────────────────────────────────────────
//
// Emits a standalone Python script that receives JSON-lines events from the
// Kotlin/Swift RerunBridge and re-logs them into the Rerun viewer.
//
// The generated script supports two modes:
//   - LIVE (default)   — rr.init(spawn=True), opens the viewer process.
//   - SAVE (--save)    — writes a shareable .rrd file, optionally on demand
//                        when the app sends a {"type":"control","cmd":"save_now"}
//                        message (triggered by the in-app "Save & Share" button).
//
// Keep this in lockstep with samples/android-demo/tools/rerun-bridge.py.
export function generatePythonSidecar(options = {}) {
    const port = options.port ?? 9876;
    const recordingName = options.recordingName ?? "sceneview-bridge";
    const spawnViewer = options.spawnViewer ?? true;
    const shareBaseUrl = options.shareBaseUrl ?? "https://sceneview.github.io/rerun/";
    if (port < 1 || port > 65535) {
        throw new Error(`Invalid port "${port}". Must be between 1 and 65535.`);
    }
    if (!recordingName.trim()) {
        throw new Error("recordingName cannot be empty");
    }
    if (!shareBaseUrl.trim()) {
        throw new Error("shareBaseUrl cannot be empty");
    }
    // Default-mode flag the CLI parser flips when --save / --spawn aren't passed.
    // Tests (and downstream tooling) read the constant `DEFAULT_SPAWN`.
    const defaultSpawn = spawnViewer ? "True" : "False";
    return `"""Rerun sidecar for SceneView AR sessions.

Listens on TCP :${port} for JSON-lines events emitted by a SceneView Android
(arsceneview.rerun.RerunBridge) or iOS (SceneViewSwift.RerunBridge) client,
and re-logs each event into the Rerun viewer as the matching archetype.

Two modes:
  - LIVE  (default)  spawns the Rerun viewer for real-time inspection.
  - SAVE  (--save)   writes a sharable .rrd recording instead, optionally
                     on demand via the in-app "Save & Share" button.

Setup:
    pip install rerun-sdk numpy
    python rerun-bridge.py                # live viewer
    python rerun-bridge.py --save         # save to ~/.sceneview/recordings/<ts>.rrd
    python rerun-bridge.py --save out.rrd # save to a specific file/dir

Then on your device:
    Android: adb reverse tcp:${port} tcp:${port}
    iOS:     point the bridge at this machine's LAN IP

Host the resulting .rrd at a public https address that ends in .rrd and share via:
    ${shareBaseUrl}?url=<encoded-public-url>
The host has to allow cross-origin requests (CORS): a public GitHub
repository's raw.githubusercontent.com link and GitHub Pages do, a GitHub
release asset does not (checked 2026-10-13); an R2 or S3 bucket does with a
CORS rule.
"""
from __future__ import annotations

import argparse
import datetime as _dt
import json
import socket
import sys
from pathlib import Path
from typing import Any
from urllib.parse import quote as _urlquote

import numpy as np
import rerun as rr

HOST = "0.0.0.0"
PORT = ${port}
APPLICATION_ID = "${recordingName}"
DEFAULT_SPAWN = ${defaultSpawn}
DEFAULT_SAVE_DIR = Path.home() / ".sceneview" / "recordings"
SHARE_BASE_URL = "${shareBaseUrl}"


def _quat(xyzw: list[float]) -> rr.Quaternion:
    return rr.Quaternion(xyzw=xyzw)


def handle_event(ev: dict[str, Any]) -> None:
    """Dispatch a single JSON event to the matching Rerun archetype."""
    t = int(ev.get("t", 0))
    rr.set_time_nanos("device_clock", t)
    kind = ev.get("type")
    entity = ev.get("entity", "world/unknown")

    if kind == "camera_pose":
        rr.log(
            entity,
            rr.Transform3D(
                translation=ev["translation"],
                rotation=_quat(ev["quaternion"]),
            ),
        )
    elif kind == "plane":
        poly = np.array(ev["polygon"], dtype=np.float32)
        if len(poly) >= 3:
            closed = np.vstack([poly, poly[:1]])
            rr.log(entity, rr.LineStrips3D([closed]))
    elif kind == "point_cloud":
        positions = np.array(ev["positions"], dtype=np.float32)
        if positions.size > 0:
            rr.log(entity, rr.Points3D(positions, radii=0.005))
    elif kind == "anchor":
        rr.log(
            entity,
            rr.Transform3D(
                translation=ev["translation"],
                rotation=_quat(ev["quaternion"]),
            ),
        )
    elif kind == "hit_result":
        rr.log(
            entity,
            rr.Points3D(
                np.array([ev["translation"]], dtype=np.float32),
                radii=0.015,
                colors=[(255, 200, 0)],
            ),
        )
    elif kind == "camera_trail":
        poly = np.array(ev["positions"], dtype=np.float32)
        if len(poly) >= 2:
            rr.log(entity, rr.LineStrips3D([poly], colors=[(120, 200, 255)]))
    elif kind == "scalar":
        value = float(ev.get("value", 0.0))
        archetype = getattr(rr, "Scalars", None) or getattr(rr, "Scalar", None)
        if archetype is not None:
            rr.log(entity, archetype(value))
    else:
        print(f"[rerun-bridge] unknown event type: {kind}", file=sys.stderr)


def _resolve_save_path(arg: str | None) -> Path:
    if arg:
        p = Path(arg).expanduser().resolve()
        if p.is_dir():
            p = p / _timestamp_filename()
    else:
        DEFAULT_SAVE_DIR.mkdir(parents=True, exist_ok=True)
        p = DEFAULT_SAVE_DIR / _timestamp_filename()
    return p


def _timestamp_filename() -> str:
    return _dt.datetime.now().strftime("%Y-%m-%d_%H-%M-%S") + ".rrd"


def _viewer_url_for_local_file(path: Path) -> str:
    return SHARE_BASE_URL + "?url=" + _urlquote(path.as_uri(), safe="")


def _send_control_ack(conn: socket.socket, payload: dict[str, Any]) -> None:
    try:
        conn.sendall((json.dumps(payload) + "\\n").encode("utf-8"))
    except Exception as exc:
        print(f"[rerun-bridge] ack send failed: {exc}", file=sys.stderr)


def _flush_and_save(save_path: Path) -> None:
    save_path.parent.mkdir(parents=True, exist_ok=True)
    rr.save(str(save_path))


def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="rerun-bridge")
    g = p.add_mutually_exclusive_group()
    g.add_argument("--save", nargs="?", const="", metavar="PATH",
                   help="Write recording to PATH (file or directory) instead of spawning the viewer.")
    g.add_argument("--spawn", action="store_true", default=False,
                   help="Force spawning the live Rerun viewer.")
    p.add_argument("--port", type=int, default=PORT)
    p.add_argument("--host", default=HOST)
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)

    if args.save is not None:
        save_mode = True
    elif args.spawn:
        save_mode = False
    else:
        save_mode = not DEFAULT_SPAWN
    save_path = _resolve_save_path(args.save) if save_mode else None

    rr.init(APPLICATION_ID, spawn=not save_mode)
    if save_mode:
        print(f"[rerun-bridge] save mode -> will write {save_path}", flush=True)
    else:
        print("[rerun-bridge] live mode -> Rerun viewer should open shortly", flush=True)

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(1)
    print(f"[rerun-bridge] listening on {args.host}:{args.port}", flush=True)

    try:
        while True:
            conn, addr = srv.accept()
            print(f"[rerun-bridge] client connected: {addr}", flush=True)
            buf = b""
            events = 0
            saved_for_this_client = False
            try:
                while True:
                    chunk = conn.recv(65536)
                    if not chunk:
                        break
                    buf += chunk
                    while b"\\n" in buf:
                        line, buf = buf.split(b"\\n", 1)
                        if not line.strip():
                            continue
                        try:
                            ev = json.loads(line.decode("utf-8"))
                        except Exception as exc:
                            print(f"[rerun-bridge] skip malformed: {exc}", file=sys.stderr)
                            continue

                        if ev.get("type") == "control":
                            cmd = ev.get("cmd")
                            if cmd == "save_now" and save_mode and save_path is not None:
                                _flush_and_save(save_path)
                                saved_for_this_client = True
                                url = _viewer_url_for_local_file(save_path)
                                print(f"[rerun-bridge] saved {events} events -> {save_path}", flush=True)
                                print(f"[rerun-bridge] open in browser -> {url}", flush=True)
                                _send_control_ack(conn, {
                                    "type": "control", "ack": "saved",
                                    "path": str(save_path), "events": events, "viewerUrl": url,
                                })
                            elif cmd == "save_now":
                                _send_control_ack(conn, {
                                    "type": "control", "ack": "save_unsupported",
                                    "reason": "sidecar started in live mode; relaunch with --save",
                                })
                            else:
                                print(f"[rerun-bridge] unknown control cmd: {cmd}", file=sys.stderr)
                            continue

                        try:
                            handle_event(ev)
                            events += 1
                        except Exception as exc:
                            print(f"[rerun-bridge] event skip: {exc}", file=sys.stderr)
            finally:
                conn.close()
                if save_mode and not saved_for_this_client and events > 0 and save_path is not None:
                    _flush_and_save(save_path)
                    url = _viewer_url_for_local_file(save_path)
                    print(f"[rerun-bridge] saved {events} events on disconnect -> {save_path}", flush=True)
                    print(f"[rerun-bridge] open in browser -> {url}", flush=True)
                else:
                    print(f"[rerun-bridge] client disconnected (logged {events} events)", flush=True)
    except KeyboardInterrupt:
        print("\\n[rerun-bridge] shutting down", flush=True)
    finally:
        srv.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
`;
}

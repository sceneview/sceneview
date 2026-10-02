"""Rerun sidecar for SceneView's "AR Debug (Rerun)" sample demo.

Listens on TCP :9876 for the JSON-lines wire format emitted by the SceneView
Android RerunBridge (arsceneview.rerun.RerunBridge) and re-logs each event
into the Rerun viewer as the matching archetype:

  camera_pose  -> rr.Transform3D
  plane        -> rr.LineStrips3D  (closed world-space polygon)
  point_cloud  -> rr.Points3D
  anchor       -> rr.Transform3D
  hit_result   -> rr.Points3D      (single highlighted point)

It also derives a "scan health" dashboard from the same stream, with no app
change: feature points per frame, surfaces and anchors seen so far, and the
camera's speed, under `metrics/scan/`, plus the path the phone walked
(`world/trail`). The recording carries a default layout
(the AR world on the left, the three graphs on the right), so the viewer opens
on it instead of guessing one.

By default the Rerun viewer is spawned automatically (live mode). Pass
`--save` to instead write a sharable .rrd recording to disk — perfect for
sending to a colleague or attaching to a bug report.

Setup on your dev machine (tested with rerun-sdk 0.38; 0.23 is the minimum):
    pip install "rerun-sdk>=0.23" numpy
    python samples/android-demo/tools/rerun-bridge.py             # live viewer
    python samples/android-demo/tools/rerun-bridge.py --save      # save to ~/.sceneview/recordings/
    python samples/android-demo/tools/rerun-bridge.py --save out.rrd

No device at hand? Replay the session bundled with the demo app:
    python samples/android-demo/tools/rerun-bridge.py \
        --replay samples/android-demo/src/main/assets/rerun/sample-session.jsonl

The app talks to the sidecar on :9876, so the spawned viewer listens on :9877
(`--viewer-port`). Both used to claim :9876, and live mode stopped at startup
with "Address already in use".

Then on your Android device (connected via USB with adb):
    adb reverse tcp:9876 tcp:9876
    # launch the SceneView demo app
    # open the Samples tab -> AR Debug (Rerun)

Inside the app, tap the "Save & Share" button to ask this sidecar to flush
the current recording and print the path + viewer URL. The sidecar prints:

    [rerun-bridge] saved 1234 events -> /home/you/.sceneview/recordings/2026-05-06_23-30-12.rrd
    [rerun-bridge] open in browser -> https://sceneview.github.io/rerun/?url=file:///home/you/...

Drop the .rrd onto a public host (R2, GitHub release, S3) and pass its URL
to https://sceneview.github.io/rerun/?url=<encoded-url> to share.

The rerun-3d-mcp package generates a similar sidecar
(mcp/packages/rerun/src/python-sidecar.ts) for users who don't clone this
repo. That generator still targets the pre-0.23 SDK: port changes here to it
if the package is revived.
"""
from __future__ import annotations

import argparse
import datetime as _dt
import json
import os
import socket
import sys
from collections import deque
from pathlib import Path
from typing import Any
from urllib.parse import quote as _urlquote

import numpy as np
import rerun as rr

try:
    import rerun.blueprint as rrb
except ImportError:  # an SDK without blueprints still records, just unlaid-out
    rrb = None

HOST = "0.0.0.0"
PORT = 9876
# The viewer's own server. It must differ from PORT: rerun's default is also
# 9876, and the sidecar could not bind its socket once the viewer held it.
VIEWER_PORT = 9877
DEVICE_TIMELINE = "device_clock"
APPLICATION_ID = "sceneview-ar-debug"
# Each colour matches its graph, so a point in 3D and its curve read as one.
POINT_COLOR = (255, 150, 60)
TRAIL_COLOR = (120, 220, 160)
DEFAULT_SAVE_DIR = Path.home() / ".sceneview" / "recordings"
SHARE_BASE_URL = "https://sceneview.github.io/rerun/"


def _quat(xyzw: list[float]) -> rr.Quaternion:
    return rr.Quaternion(xyzw=xyzw)


def _set_device_time(t_nanos: int) -> None:
    """Stamp the following log calls with the device's frame timestamp.

    The timestamp counts from the device's boot, not from the epoch, so it is
    a duration. `rr.set_time_nanos` left the SDK in 0.23: on a current
    install every event raised and the recording stayed empty.
    """
    if hasattr(rr, "set_time"):
        rr.set_time(DEVICE_TIMELINE, duration=np.timedelta64(t_nanos, "ns"))
    else:  # rerun-sdk < 0.23
        rr.set_time_nanos(DEVICE_TIMELINE, t_nanos)


class ScanHealth:
    """Scan-health series derived from the event stream itself.

    Nothing new crosses the wire: the counts come from the events the app
    already sends, so an older app build gets the dashboard too.
    """

    POINTS = "metrics/scan/points/feature_points"
    PLANES = "metrics/scan/surfaces/planes"
    ANCHORS = "metrics/scan/surfaces/anchors"
    SPEED = "metrics/scan/camera/speed"
    TRAIL = "world/trail"
    # Speed over the last half second, not between two poses: poses carry
    # millimetre-rounded translations, and pose-to-pose speed saws.
    SPEED_WINDOW_NANOS = 500_000_000
    # The trail keeps the last minute or so at the bridge's 10 Hz, and is
    # re-logged once a second so the recording stays small.
    TRAIL_MAX_POINTS = 600
    TRAIL_EVERY_NANOS = 1_000_000_000

    def __init__(self) -> None:
        self.planes: set[str] = set()
        self.anchors: set[str] = set()
        self.poses: deque[tuple[int, np.ndarray]] = deque()
        self.trail: deque[np.ndarray] = deque(maxlen=self.TRAIL_MAX_POINTS)
        self.trail_logged_at: int | None = None

    @staticmethod
    def log_styles() -> None:
        """Name and colour each series once, for the whole recording."""
        if not hasattr(rr, "SeriesLines"):
            return
        for entity, name, color, counter in (
            (ScanHealth.POINTS, "feature points", POINT_COLOR, False),
            (ScanHealth.PLANES, "planes seen", (90, 170, 255), True),
            (ScanHealth.ANCHORS, "anchors", (250, 210, 80), True),
            (ScanHealth.SPEED, "camera speed (m/s)", TRAIL_COLOR, False),
        ):
            style = {"names": name, "colors": [color], "widths": 2.0}
            if counter:
                # A count holds until the next event: draw steps, not ramps.
                style["interpolation_mode"] = "StepAfter"
            try:
                rr.log(entity, rr.SeriesLines(**style), static=True)
            except TypeError:  # interpolation_mode is recent
                style.pop("interpolation_mode", None)
                rr.log(entity, rr.SeriesLines(**style), static=True)

    def observe(self, ev: dict[str, Any], t_nanos: int) -> None:
        kind = ev.get("type")
        if kind == "camera_pose":
            position = np.asarray(ev["translation"], dtype=np.float64)
            self.poses.append((t_nanos, position))
            while len(self.poses) > 2 and t_nanos - self.poses[1][0] >= self.SPEED_WINDOW_NANOS:
                self.poses.popleft()
            t0, p0 = self.poses[0]
            if t_nanos - t0 >= self.SPEED_WINDOW_NANOS // 2:
                _log_scalar(self.SPEED, np.linalg.norm(position - p0) / ((t_nanos - t0) / 1e9))
            self.trail.append(position)
            if self.trail_logged_at is None or t_nanos - self.trail_logged_at >= self.TRAIL_EVERY_NANOS:
                if len(self.trail) >= 2:
                    rr.log(self.TRAIL, rr.LineStrips3D([np.array(self.trail)], colors=[TRAIL_COLOR]))
                    self.trail_logged_at = t_nanos
        elif kind == "point_cloud":
            _log_scalar(self.POINTS, len(ev.get("positions", [])))
        elif kind == "plane":
            self.planes.add(ev.get("entity", ""))
            _log_scalar(self.PLANES, len(self.planes))
        elif kind == "anchor":
            self.anchors.add(ev.get("entity", ""))
            _log_scalar(self.ANCHORS, len(self.anchors))


def _log_scalar(entity: str, value: float) -> None:
    # Rerun >= 0.23 exposes Scalars; older versions used Scalar (singular).
    archetype = getattr(rr, "Scalars", None) or getattr(rr, "Scalar", None)
    if archetype is not None:
        rr.log(entity, archetype(float(value)))


def log_static_scene() -> None:
    """What holds for the whole session: axes, camera gizmo, series styles."""
    # ARCore and ARKit share a right-handed, Y-up world; without this the
    # viewer orbits around Z and the room lies on its side.
    rr.log("world", rr.ViewCoordinates.RIGHT_HAND_Y_UP, static=True)
    if hasattr(rr, "TransformAxes3D"):
        # A bare Transform3D draws nothing on current viewers: give the phone
        # a visible frame.
        rr.log("world/camera", rr.TransformAxes3D(0.15), static=True)
    ScanHealth.log_styles()


def default_blueprint() -> Any:
    """The layout the viewer opens on: the AR world beside the scan health.

    None on an SDK without blueprints; the viewer then falls back to its own
    heuristics, as before.
    """
    if rrb is None:
        return None
    return rrb.Blueprint(
        rrb.Horizontal(
            rrb.Spatial3DView(
                name="AR world",
                origin="world",
                line_grid=rrb.LineGrid3D(visible=True, spacing=0.5),
            ),
            rrb.Vertical(
                rrb.TimeSeriesView(name="Feature points", origin="metrics/scan/points"),
                rrb.TimeSeriesView(name="Surfaces and anchors", origin="metrics/scan/surfaces"),
                rrb.TimeSeriesView(name="Camera speed (m/s)", origin="metrics/scan/camera"),
            ),
            column_shares=[3, 2],
        ),
        rrb.BlueprintPanel(state="collapsed"),
        rrb.SelectionPanel(state="collapsed"),
        # Anything else (your own `logScalar` entities, hits, trails outside
        # `world/`) still gets a view of its own.
        auto_views=True,
    )


_health = ScanHealth()


def handle_event(ev: dict[str, Any]) -> None:
    """Dispatch a single JSON event to the matching Rerun archetype."""
    t = int(ev.get("t", 0))
    _set_device_time(t)
    kind = ev.get("type")
    entity = ev.get("entity", "world/unknown")
    _health.observe(ev, t)

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
            # Close the loop so the viewer draws a full outline.
            closed = np.vstack([poly, poly[:1]])
            rr.log(entity, rr.LineStrips3D([closed]))
    elif kind == "point_cloud":
        positions = np.array(ev["positions"], dtype=np.float32)
        if positions.size > 0:
            # 1 cm: at 5 mm the cloud vanished at room scale.
            rr.log(entity, rr.Points3D(positions, radii=0.01, colors=[POINT_COLOR]))
    elif kind == "anchor":
        rr.log(
            entity,
            rr.Transform3D(
                translation=ev["translation"],
                rotation=_quat(ev["quaternion"]),
            ),
        )
        if hasattr(rr, "TransformAxes3D"):
            rr.log(entity, rr.TransformAxes3D(0.08))
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
        # Tier-S "wow" feature: a 3D polyline of every accumulated camera
        # position so far — visually shows how the operator moved their
        # phone over the session. App-side keeps the buffer bounded.
        poly = np.array(ev["positions"], dtype=np.float32)
        if len(poly) >= 2:
            rr.log(entity, rr.LineStrips3D([poly], colors=[(120, 200, 255)]))
    elif kind == "scalar":
        # Generic per-frame timeseries (tracking quality, feature point
        # count, frame latency).
        _log_scalar(entity, ev.get("value", 0.0))
    else:
        print(f"[rerun-bridge] unknown event type: {kind}", file=sys.stderr)


def _resolve_save_path(arg: str | None) -> Path:
    """Return the path the .rrd will be written to."""
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
    """Build the SceneView viewer URL pointing at a local file:// URL.

    Mostly useful as a copy-paste hint — most browsers refuse to fetch
    file:// from an HTTPS page, so the user typically uploads first. We
    print this anyway so the workflow is discoverable.
    """
    return SHARE_BASE_URL + "?url=" + _urlquote(path.as_uri(), safe="")


def _send_control_ack(conn: socket.socket, payload: dict[str, Any]) -> None:
    """Best-effort write of a JSON-lines control acknowledgment."""
    try:
        conn.sendall((json.dumps(payload) + "\n").encode("utf-8"))
    except Exception as exc:
        print(f"[rerun-bridge] ack send failed: {exc}", file=sys.stderr)


def _flush_and_save(save_path: Path) -> None:
    """Write the in-memory recording to disk."""
    save_path.parent.mkdir(parents=True, exist_ok=True)
    rr.save(str(save_path), default_blueprint=default_blueprint())


def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="rerun-bridge",
        description="Receive SceneView AR JSON-lines events and either spawn the Rerun viewer (live) or save to .rrd (shareable).",
    )
    g = p.add_mutually_exclusive_group()
    g.add_argument(
        "--save",
        nargs="?",
        const="",
        metavar="PATH",
        help="Write the recording to PATH (a file or directory) instead of spawning the viewer. "
             "Defaults to ~/.sceneview/recordings/<timestamp>.rrd. Triggered when the client "
             "disconnects, or on demand via the in-app 'Save & Share' button (control message).",
    )
    g.add_argument(
        "--spawn",
        action="store_true",
        default=False,
        help="Force spawning the live Rerun viewer (the default when --save is omitted).",
    )
    p.add_argument("--port", type=int, default=PORT, help=f"TCP port to listen on (default: {PORT}).")
    p.add_argument("--host", default=HOST, help=f"Host interface to bind (default: {HOST}).")
    p.add_argument(
        "--viewer-port",
        type=int,
        default=VIEWER_PORT,
        help=f"Port of the spawned Rerun viewer, in live mode (default: {VIEWER_PORT}). "
             "Must differ from --port.",
    )
    p.add_argument(
        "--replay",
        metavar="JSONL",
        help="Re-log a recorded JSON-lines session (for instance the demo's "
             "assets/rerun/sample-session.jsonl) instead of listening for a device.",
    )
    args = p.parse_args(argv)
    if args.viewer_port == args.port:
        p.error("--viewer-port must differ from --port: the viewer and the sidecar cannot share it")
    return args


def replay(path: Path) -> int:
    """Re-log every event of a JSON-lines session; returns how many were logged."""
    events = 0
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            ev = json.loads(line)
            if ev.get("type") == "control":
                continue
            handle_event(ev)
            events += 1
    return events


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)

    save_mode = args.save is not None
    save_path: Path | None = _resolve_save_path(args.save) if save_mode else None

    global _health
    rr.init(APPLICATION_ID)
    if not save_mode:
        # Spawn on a port of its own: the sidecar needs args.port for the app.
        rr.spawn(port=args.viewer_port, default_blueprint=default_blueprint())
    log_static_scene()

    if save_mode:
        print(f"[rerun-bridge] save mode -> will write {save_path}", flush=True)
    else:
        print(
            f"[rerun-bridge] live mode -> Rerun viewer should open shortly (port {args.viewer_port})",
            flush=True,
        )

    if args.replay:
        events = replay(Path(args.replay))
        if save_mode and save_path is not None:
            _flush_and_save(save_path)
            print(f"[rerun-bridge] replayed {events} events -> {save_path}", flush=True)
        else:
            print(f"[rerun-bridge] replayed {events} events into the viewer", flush=True)
        return 0

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(1)
    print(f"[rerun-bridge] listening on {args.host}:{args.port}", flush=True)
    print(f"[rerun-bridge] run 'adb reverse tcp:{args.port} tcp:{args.port}' on your Mac, then", flush=True)
    print("[rerun-bridge] launch the SceneView demo app -> Samples -> AR Debug (Rerun)", flush=True)

    try:
        while True:
            conn, addr = srv.accept()
            print(f"[rerun-bridge] client connected: {addr}", flush=True)
            # A new connection is a new AR session: restart the counts.
            _health = ScanHealth()
            buf = b""
            events = 0
            saved_for_this_client = False
            try:
                while True:
                    chunk = conn.recv(65536)
                    if not chunk:
                        break
                    buf += chunk
                    while b"\n" in buf:
                        line, buf = buf.split(b"\n", 1)
                        if not line.strip():
                            continue
                        try:
                            ev = json.loads(line.decode("utf-8"))
                        except Exception as exc:
                            print(f"[rerun-bridge] skip malformed: {exc}", file=sys.stderr)
                            continue

                        # Control messages let the app trigger save-and-share
                        # without disconnecting the socket.
                        if ev.get("type") == "control":
                            cmd = ev.get("cmd")
                            if cmd == "save_now" and save_mode and save_path is not None:
                                _flush_and_save(save_path)
                                saved_for_this_client = True
                                url = _viewer_url_for_local_file(save_path)
                                print(
                                    f"[rerun-bridge] saved {events} events -> {save_path}",
                                    flush=True,
                                )
                                print(f"[rerun-bridge] open in browser -> {url}", flush=True)
                                _send_control_ack(conn, {
                                    "type": "control",
                                    "ack": "saved",
                                    "path": str(save_path),
                                    "events": events,
                                    "viewerUrl": url,
                                })
                            elif cmd == "save_now":
                                # Sidecar in live mode: nothing to save.
                                _send_control_ack(conn, {
                                    "type": "control",
                                    "ack": "save_unsupported",
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
                # Auto-save on disconnect if no explicit save_now happened.
                if save_mode and not saved_for_this_client and events > 0 and save_path is not None:
                    _flush_and_save(save_path)
                    url = _viewer_url_for_local_file(save_path)
                    print(
                        f"[rerun-bridge] saved {events} events on disconnect -> {save_path}",
                        flush=True,
                    )
                    print(f"[rerun-bridge] open in browser -> {url}", flush=True)
                else:
                    print(
                        f"[rerun-bridge] client disconnected (logged {events} events)",
                        flush=True,
                    )
    except KeyboardInterrupt:
        print("\n[rerun-bridge] shutting down", flush=True)
    finally:
        srv.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

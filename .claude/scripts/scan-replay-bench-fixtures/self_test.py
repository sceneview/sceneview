"""Synthetic fixtures only. Never invoke adb or read real scan media."""

import csv
import json
import subprocess
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch


def run_tests(bench, out):
    fixtures = Path(__file__).parent

    class Parsers(unittest.TestCase):
        def test_hud_thousands_separators(self):
            for case in json.loads((fixtures / "hud.json").read_text()):
                with self.subTest(text=case["text"]):
                    self.assertEqual(bench.parse_hud(case["text"]), {k: case[k] for k in ("floor_m2", "points")})

        def test_ui_text_description_and_bounds(self):
            ui = bench.parse_ui((fixtures / "live.xml").read_text())
            self.assertEqual(ui["targets"]["stop and review"], (500, 1550))
            self.assertTrue(ui["warning"])
            self.assertFalse(ui["tabs_ready"])
            result = bench.parse_ui((fixtures / "result.xml").read_text())
            self.assertEqual(result["targets"]["model"], (499, 1650))
            self.assertTrue(result["tabs_ready"])
            self.assertIn("Photo & model", result["text"])
            self.assertFalse(bench.parse_ui('<hierarchy><node text="Photo Model Plan"/></hierarchy>')["tabs_ready"])
            with self.assertRaises(bench.BenchError):
                bench.parse_ui("ERROR: could not get idle state")

        def test_logcat_epoch_live_cutoff_and_stall(self):
            result = bench.parse_logcat((fixtures / "logcat.txt").read_text(), 1794500100, 1794500111)
            self.assertAlmostEqual(result["first_surface_s"], 6.25)
            self.assertAlmostEqual(result["ar_fps_mean"], 50 / 3)
            self.assertEqual(result["ar_fps_worst"], 0)
            self.assertEqual(result["outside_elements"], 7)
            self.assertEqual(result["ar_seconds"], 3)
            self.assertEqual((result["fatals"], result["anrs"]), (1, 2))
            self.assertIsNone(bench.parse_logcat("", None)["fatals"])
            self.assertIsNone(bench.parse_logcat("", 100)["ar_fps_mean"])
            null_last = '1794500110.900 1 1 I ScanBench: {"schema":2,"outside_elements":null}\n'
            self.assertIsNone(bench.parse_logcat((fixtures / "logcat.txt").read_text() + null_last, 1794500100, 1794500111)["outside_elements"])

        def test_polls_missing_intervals_and_result(self):
            with (fixtures / "polls.tsv").open() as stream:
                rows = list(csv.DictReader(stream, delimiter="\t"))
            result = bench.parse_polls(rows, 15, 15)
            self.assertEqual(result["floor_m2"], 14)
            self.assertEqual(result["points"], 18714)
            self.assertEqual(result["moving_too_fast_s"], 5)
            self.assertEqual(result["poll_observed_s"], 7)
            self.assertEqual(result["result_ready_s"], 4)
            self.assertIsNone(bench.parse_polls([], 10)["moving_too_fast_s"])

        def test_surfaceflinger_presentation_column_and_duplicates(self):
            raw = (fixtures / "surfaceflinger.txt").read_text()
            fps, newest = bench.latency_sample(raw)
            self.assertAlmostEqual(fps, 30, places=5)
            self.assertEqual(len(bench.latency_frames(raw)), 4)
            self.assertIsNone(bench.latency_sample(raw, newest)[0])
            layers = f'{bench.PACKAGE}/MainActivity\nSurfaceView[{bench.PACKAGE}] BLAST#1\nSurfaceView[other] BLAST#2'
            self.assertEqual(bench.select_layers(layers), [f'SurfaceView[{bench.PACKAGE}] BLAST#1'])

        def test_launch_scope_component_and_clock(self):
            script = bench.launch_script(bench.DEVICE_RECORDINGS + "/walk.mp4")
            self.assertIn("date +%s.%N", script)
            self.assertIn(f"am start -W -n {bench.ACTIVITY}", script)
            self.assertIn("--es telemetry_consent denied", script)
            self.assertIn("--es scan_scope room", script)
            self.assertIn("/files/ar-recordings/walk.mp4", script)
            self.assertAlmostEqual(bench.device_epoch("BENCH_EPOCH=123.456\nStatus: ok"), 123.456)
            with self.assertRaises(bench.BenchError):
                bench.device_epoch("BENCH_EPOCH=123.%N")

        def test_push_only_absent_or_size_changed(self):
            walk = out / "walk.mp4"
            walk.write_bytes(b"test")

            class Device:
                def __init__(self):
                    self.size, self.pushes = None, 0

                def shell(self, *args, **kwargs):
                    return str(self.size) if args[0] == "stat" else ""

                def call(self, *args, **kwargs):
                    self.assert_push = args[0]
                    if args[0] != "push":
                        raise AssertionError(args)
                    self.size = Path(args[1]).stat().st_size
                    self.pushes += 1

            device = Device()
            for _ in range(2):
                self.assertEqual(bench.ensure_walk(device, walk), bench.DEVICE_RECORDINGS + "/walk.mp4")
            self.assertEqual(device.pushes, 1)
            walk.write_bytes(b"changed")
            bench.ensure_walk(device, walk)
            self.assertEqual(device.pushes, 2)
            walk.unlink()

        def test_lock_owner_wait_and_timeout(self):
            path = out / "pixel4a.lock"
            path.mkdir()
            (path / "owner").write_text("another session")
            with patch.object(bench.time, "monotonic", side_effect=[0, 0, 0, 600]), patch.object(bench.time, "sleep") as sleep:
                with self.assertRaises(bench.LockBusy):
                    with bench.DeviceLock(path):
                        self.fail("must not steal lock")
                sleep.assert_called_once_with(15)
            self.assertEqual((path / "owner").read_text(), "another session")
            (path / "owner").unlink()
            path.rmdir()
            with bench.DeviceLock(path):
                self.assertIn("scan-replay-bench", (path / "owner").read_text())
            self.assertFalse(path.exists())
            with bench.DeviceLock(path):
                (path / "owner").write_text("replacement owner")
            self.assertTrue(path.is_dir())
            (path / "owner").unlink()
            path.rmdir()

        def test_walk_selection_and_private_output(self):
            corpus = out / "corpus"
            corpus.mkdir()
            for name in list(bench.REFERENCES.values()) + [f"walk-{i}" for i in range(9)]:
                (corpus / f"{name}.mp4").touch()
            args = SimpleNamespace(corpus=str(corpus), walks="reference")
            self.assertEqual([p.stem for p in bench.walks_for(args)], list(bench.REFERENCES.values()))
            args.walks = "all"
            self.assertEqual(len(bench.walks_for(args)), 14)
            args.walks = "B,ar-session-20260928-003538.mp4"
            self.assertEqual([p.stem for p in bench.walks_for(args)], [bench.REFERENCES["B"], bench.REFERENCES["A"]])
            for invalid in ("A,A", "../private", "missing", "A,"):
                args.walks = invalid
                with self.assertRaises(bench.BenchError):
                    bench.walks_for(args)
            with self.assertRaises(bench.BenchError):
                bench.output_dir(bench.ROOT / "private-output")

        def test_install_once_per_build_and_unlock_between_runs(self):
            events = []
            walks = [Path(bench.REFERENCES[x] + ".mp4") for x in "AB"]
            results = [bench.new_result(walk, slot, slot) for walk in walks for slot in "AB"]
            locked = False

            class Lock:
                def __enter__(self):
                    nonlocal locked
                    if locked:
                        raise AssertionError("nested device lock")
                    locked = True
                    events.append("lock")

                def __exit__(self, *_):
                    nonlocal locked
                    locked = False
                    events.append("unlock")

            class Device:
                def shell(self, *args, **kwargs):
                    if not locked:
                        raise AssertionError("device accessed unlocked")
                    return "versionCode=42\nlastUpdateTime=now\ncodePath=/app/test" if args[0] == "dumpsys" else ""

                def call(self, *args, **kwargs):
                    if not locked or args[:3] != ("install", "-r", "-d"):
                        raise AssertionError(args)
                    events.append("install " + str(args[3]))
                    return "Success"

            def record(device, walk, result, *_):
                self.assertTrue(locked)
                events.append("run " + result["build"] + " " + result["walk_label"])
                result["status"] = "captured"

            target = out / "ordering"
            target.mkdir()
            with patch.object(bench, "DeviceLock", Lock), patch.object(bench, "record_run", record), \
                    patch.object(bench, "duration", return_value=30), patch.object(bench, "run_media"), \
                    patch.object(bench, "comparison_media"), patch.object(bench, "generate_sheet"):
                bench.run_builds(Device(), ["main.apk", "v2.apk"], walks, results, target, ["main", "v2"], [], False)
            self.assertEqual(events, ["lock", "install main.apk", "unlock", "lock", "run A A", "unlock", "lock", "run A B", "unlock",
                                      "lock", "install v2.apk", "unlock", "lock", "run B A", "unlock", "lock", "run B B", "unlock"])

        def test_install_timeout_skips_build(self):
            target = out / "skipped"
            target.mkdir()
            walks = [Path(bench.REFERENCES["A"] + ".mp4")]
            results = [bench.new_result(walks[0], slot, slot) for slot in "AB"]
            with patch.object(bench, "install_build", side_effect=bench.LockBusy("another owner")), \
                    patch.object(bench, "record_run") as record, patch.object(bench, "generate_sheet"):
                bench.run_builds(None, ["a", "b"], walks, results, target, ["a", "b"], [], False)
                record.assert_not_called()
            self.assertTrue(all(r["status"] == "skipped" for r in results))

        def test_live_then_result_then_pull_with_device_clock(self):
            # Exercise the whole device workflow with synthetic time and adb responses.
            # No real device, camera footage, sleep or screenrecord is used.
            clock = [1000.0]
            events = []
            walk = out / "workflow-walk.mp4"
            walk.write_bytes(b"synthetic walk")
            run_dir = out / "workflow"
            run_dir.mkdir()

            def sleep(seconds):
                clock[0] += max(seconds, .001)

            class Process:
                def __init__(self, end):
                    self.end = end
                    self.returncode = None

                def poll(self):
                    if clock[0] >= self.end:
                        self.returncode = 0
                    return self.returncode

                def wait(self, **kwargs):
                    clock[0] = max(clock[0], self.end)
                    return self.poll()

            class Device:
                reviewing = False
                model = False
                launch = None

                def shell(self, *args, **kwargs):
                    events.append(("shell", args))
                    if args[0] == "stat":
                        return str(walk.stat().st_size)
                    if args[:2] == ("dumpsys", "package"):
                        return "versionCode=42\nlastUpdateTime=now"
                    if args[:3] == ("dumpsys", "SurfaceFlinger", "--list"):
                        return f"SurfaceView[{bench.PACKAGE}] BLAST#1"
                    if args[:3] == ("dumpsys", "SurfaceFlinger", "--latency"):
                        t = int(clock[0] * 1e9)
                        return f"16666666\n1 {t - 66666666} 1\n2 {t - 33333333} 2\n3 {t} 3"
                    return ""

                def start(self, script, output):
                    events.append(("start", script))
                    if "screenrecord" in script:
                        self_assert.assertIn("--bit-rate 6000000", script)
                        length = int(script.split("--time-limit ")[1].split()[0])
                        return Process(clock[0] + length)
                    self.launch = clock[0]
                    output.write(f"BENCH_EPOCH={self.launch:.6f}\nStatus: ok\n")
                    output.flush()
                    return Process(clock[0])

                def script(self, script, **kwargs):
                    events.append(("script", script))
                    stamp = f"BENCH_EPOCH={clock[0]:.6f}\n"
                    if "uiautomator" in script:
                        sleep(.1)
                        name = "result.xml" if self.reviewing else "live.xml"
                        return stamp + (fixtures / name).read_text()
                    if "input tap 500 1550" in script:
                        self.reviewing = True
                    if "input tap 499 1650" in script:
                        self.model = True
                    return stamp

                def call(self, *args, **kwargs):
                    events.append(("call", args))
                    if args[0] == "pull":
                        self_assert.assertTrue(self.reviewing and self.model)
                        Path(args[2]).write_bytes(b"synthetic recorded video")
                    if args[0] == "logcat" and "-d" in args:
                        return (f'{self.launch + 6:.3f} 1 1 I ScanBench: {{"schema":2,"surface_ready":true,"outside_elements":0}}\n'
                                f'{self.launch + 7:.3f} 1 1 I ScanBench: {{"schema":2,"fps":30}}\n')
                    return ""

                def screenshot(self, path):
                    self_assert.assertTrue(self.model)
                    events.append(("screenshot", str(path)))
                    path.write_bytes(b"synthetic screenshot")

            self_assert = self
            device = Device()
            result = bench.new_result(walk, "main", "A")
            with patch.object(bench.time, "monotonic", side_effect=lambda: clock[0]), patch.object(bench.time, "sleep", sleep):
                bench.record_run(device, walk, result, run_dir, 10, "versionCode=42\nlastUpdateTime=now")
            self.assertEqual(result["status"], "captured")
            self.assertEqual(result["ar_fps_mean"], 30)
            self.assertEqual(result["first_surface_s"], 6)
            self.assertEqual(result["points"], 18714)
            starts = [value for kind, value in events if kind == "start" and "screenrecord" in value]
            self.assertIn("--time-limit 30", starts[0])
            self.assertIn("--time-limit 25", starts[1])
            screenshot_index = next(i for i, (kind, _) in enumerate(events) if kind == "screenshot")
            pulls = [i for i, (kind, value) in enumerate(events) if kind == "call" and value[0] == "pull"]
            self.assertEqual(len(pulls), 2)
            self.assertTrue(all(i > screenshot_index for i in pulls))
            self.assertTrue((run_dir / "live.mp4").is_file())
            self.assertTrue((run_dir / "result.mp4").is_file())
            with (run_dir / "polls.tsv").open() as stream:
                polls = list(csv.DictReader(stream, delimiter="\t"))
            self.assertEqual({r["phase"] for r in polls}, {"live", "result"})
            self.assertTrue(all(float(r["elapsed_s"]) == float(r["epoch_s"]) - device.launch for r in polls))
            deletes = [value for kind, value in events if kind == "shell" and value[0] == "rm"]
            self.assertFalse(any(bench.DEVICE_RECORDINGS in str(args) for args in deletes))

        def test_sheet_and_real_ffmpeg_without_drawtext(self):
            results = []
            palettes = {"A": ["0x005BC1", "0x004494", "0x4B5E86", "0x7C2F00"],
                        "B": ["0xA33F00", "0xADC6FF", "0xB3C6F4", "0xFFB595"]}
            for slot, color in (("A", "0x005BC1"), ("B", "0xA33F00")):
                result = bench.new_result(Path(bench.REFERENCES["A"] + ".mp4"), f"{slot} <synthetic>", slot)
                result.update(status="captured", ar_fps_mean=20 if slot == "A" else 25,
                              ar_fps_worst=10 if slot == "A" else 8, sf_fps_mean=30,
                              first_surface_s=6.25, floor_m2=14, points=18714, moving_too_fast_s=5,
                              outside_elements=7, fatals=0, anrs=0, result_ready_s=4)
                run_dir = out / result["directory"]
                run_dir.mkdir(parents=True)
                # Change colour at 8, 16, 24 s so ordering is observable without drawtext.
                filters = ",".join(f"drawbox=color={palettes[slot][i]}:t=fill:enable='gte(t,{i * 8})'" for i in (1, 2, 3))
                bench.command(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
                               f"color=c={color}:s=160x320:r=2:d=26", "-vf", filters,
                               "-c:v", "libx264", "-pix_fmt", "yuv420p", run_dir / "live.mp4"])
                bench.command(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
                               f"color=c={color}:s=160x320:r=2:d=2", "-c:v", "libx264", "-pix_fmt", "yuv420p", run_dir / "result.mp4"])
                bench.run_media(out, result)
                self.assertTrue((out / result["tiles"]).is_file())
                self.assertTrue((run_dir / "result-end.jpg").is_file())
                results.append(result)
            self.assertEqual(bench.comparison_media(out, results, False), results[0]["walk"] + "-ab.jpg")
            comparison = out / (results[0]["walk"] + "-ab.jpg")
            for row in range(4):
                for column, slot in enumerate("AB"):
                    rgb = subprocess.run(["ffmpeg", "-v", "error", "-i", str(comparison), "-vf",
                                          f"format=rgb24,crop=1:1:{column * 320 + 160}:{row * 640 + 320}",
                                          "-frames:v", "1", "-f", "rawvideo", "pipe:1"], capture_output=True, check=True).stdout
                    expected = bytes.fromhex(palettes[slot][row + 1 if row < 3 else 0][2:])
                    self.assertEqual(len(rgb), 3)
                    self.assertTrue(all(abs(a - b) <= 8 for a, b in zip(rgb, expected)), (row, slot, rgb, expected))
            bench.generate_sheet(out, results, ["main <synthetic>", "v2"], ["Synthetic fixtures; no device or private images."])
            page = (out / "sheet.html").read_text()
            self.assertIn("better</span>", page)
            self.assertIn("worse</span>", page)
            self.assertIn("main &lt;synthetic&gt;", page)
            self.assertIn("prefers-color-scheme: dark", page)
            self.assertIn("Left: build A", page)
            self.assertNotIn("data:image", page)
            self.assertNotIn("https://", page)
            self.assertNotIn("gradient", page)
            with (out / "mesures.tsv").open() as stream:
                rows = list(csv.DictReader(stream, delimiter="\t"))
            self.assertEqual(len(rows), 2)
            self.assertEqual(rows[0]["walk_label"], "A")
            self.assertEqual(rows[1]["ar_fps_mean"], "25")
            self.assertEqual(bench.segment_lengths(201), [180, 21])

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(Parsers)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    print(f"Synthetic sheet: {out / 'sheet.html'}", flush=True)
    return 0 if result.wasSuccessful() else 1

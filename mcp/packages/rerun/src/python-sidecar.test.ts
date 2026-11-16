import { describe, it, expect } from "vitest";
import { generatePythonSidecar } from "./python-sidecar.js";
import { spawnSync } from "node:child_process";
import { mkdtempSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

describe("generatePythonSidecar", () => {
  it("emits a script that uses rerun-sdk", () => {
    const src = generatePythonSidecar();
    expect(src).toContain("import rerun as rr");
    expect(src).toContain("rr.init(");
    expect(src).toContain("rr.Transform3D");
    expect(src).toContain("rr.Points3D");
    expect(src).toContain("rr.LineStrips3D");
  });

  it("defaults to port 9876", () => {
    const src = generatePythonSidecar();
    expect(src).toContain("PORT = 9876");
  });

  it("honours a custom port", () => {
    const src = generatePythonSidecar({ port: 12345 });
    expect(src).toContain("PORT = 12345");
  });

  it("rejects out-of-range ports", () => {
    expect(() => generatePythonSidecar({ port: 0 })).toThrow(/invalid port/i);
    expect(() => generatePythonSidecar({ port: 70000 })).toThrow(/invalid port/i);
  });

  it("defaults to spawning the viewer", () => {
    const src = generatePythonSidecar();
    expect(src).toContain("DEFAULT_SPAWN = True");
  });

  it("disables default spawn when spawnViewer=false", () => {
    const src = generatePythonSidecar({ spawnViewer: false });
    expect(src).toContain("DEFAULT_SPAWN = False");
    expect(src).not.toContain("DEFAULT_SPAWN = True");
  });

  it("supports save mode + control protocol", () => {
    const src = generatePythonSidecar();
    expect(src).toContain("--save");
    expect(src).toContain('cmd == "save_now"');
    expect(src).toContain("rr.save(");
    expect(src).toContain('"ack": "saved"');
  });

  it("includes share base URL", () => {
    const src = generatePythonSidecar();
    expect(src).toContain('SHARE_BASE_URL = "https://sceneview.github.io/rerun/"');
  });

  it("honours a custom share base URL", () => {
    const src = generatePythonSidecar({ shareBaseUrl: "https://example.dev/v/" });
    expect(src).toContain('SHARE_BASE_URL = "https://example.dev/v/"');
  });

  it("defaults application id to sceneview-bridge", () => {
    const src = generatePythonSidecar();
    expect(src).toContain('APPLICATION_ID = "sceneview-bridge"');
  });

  it("honours a custom recordingName", () => {
    const src = generatePythonSidecar({ recordingName: "robot-lab" });
    expect(src).toContain('APPLICATION_ID = "robot-lab"');
  });

  it("rejects an empty recordingName", () => {
    expect(() => generatePythonSidecar({ recordingName: "  " })).toThrow(
      /cannot be empty/i,
    );
  });

  it("dispatches all 5 event types", () => {
    const src = generatePythonSidecar();
    expect(src).toContain('kind == "camera_pose"');
    expect(src).toContain('kind == "plane"');
    expect(src).toContain('kind == "point_cloud"');
    expect(src).toContain('kind == "anchor"');
    expect(src).toContain('kind == "hit_result"');
  });
});

describe("local save protocol (sample and generated sidecars)", () => {
  for (const mode of ["explicit", "disconnect"]) {
    it(`prints matching usable instructions on ${mode}, without a local viewer URL`, () => {
      const dir = realpathSync(mkdtempSync(join(tmpdir(), "rerun-save-")));
      try {
        const generated = join(dir, "rerun-bridge.py");
        writeFileSync(generated, generatePythonSidecar());
        const path = join(dir, "recording with spaces.rrd");
        const outputs = [
          fileURLToPath(new URL("../../../../samples/android-demo/tools/rerun-bridge.py", import.meta.url)),
          generated,
        ].map((script) => {
          const result = spawnSync("python3", [
            fileURLToPath(new URL("../src/__fixtures__/sidecar-save.py", import.meta.url)),
            script, path, mode,
          ], { encoding: "utf8" });
          expect(result.stderr).toBe("");
          expect(result.status).toBe(0);
          expect(result.stdout).toContain(`SDK_SAVE=${path}`);
          expect(result.stdout).toContain("saved 1 events");
          expect(result.stdout).not.toMatch(/file:|file%3a|open in browser/i);
          const instructions = result.stdout.split("\n").filter((line) =>
            line.startsWith("[rerun-bridge] view locally ->") ||
            line.startsWith("[rerun-bridge] to share ->"),
          );
          expect(instructions).toHaveLength(2);
          expect(instructions[0]).toContain("drop the .rrd file on https://sceneview.github.io/rerun/");
          expect(instructions[1]).toContain("public HTTPS address ending in .rrd");
          expect(instructions[1]).toContain("CORS");
          expect(instructions[1]).toContain("https://sceneview.github.io/rerun/?url=<encoded-public-url>");
          const ackLine = result.stdout.split("\n").find((line) => line.startsWith("ACK="));
          if (mode === "explicit") {
            expect(JSON.parse(ackLine!.slice(4))).toEqual({
              type: "control", ack: "saved", path, events: 1,
            });
          } else {
            expect(ackLine).toBeUndefined();
          }
          return instructions;
        });
        expect(outputs[0]).toEqual(outputs[1]);
      } finally {
        rmSync(dir, { recursive: true, force: true });
      }
    });
  }
});

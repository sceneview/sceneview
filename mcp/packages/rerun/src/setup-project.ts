// ─── Project setup generator ──────────────────────────────────────────────────
//
// Emits boilerplate (build config + minimal usage snippet) to wire Rerun into an
// existing SceneView project on Android, iOS, Web, or a standalone Python pipeline.

export type SetupPlatform = "android" | "ios" | "web" | "python";

export const SETUP_PLATFORMS: SetupPlatform[] = ["android", "ios", "web", "python"];

export interface SetupProjectOptions {
  platform: SetupPlatform;
  /** Relative target directory the snippets should be dropped into. Default: "." */
  targetDir?: string;
  /** Include the Python sidecar stub alongside the platform output. Default: true for android/ios, false otherwise. */
  includeSidecar?: boolean;
}

export interface SetupFile {
  path: string;
  contents: string;
  language: "kotlin" | "swift" | "javascript" | "html" | "python" | "toml" | "ruby" | "markdown";
}

export interface SetupProjectResult {
  platform: SetupPlatform;
  files: SetupFile[];
  instructions: string[];
}

/**
 * Generates the minimum set of files a SceneView developer needs to start
 * logging to Rerun on their target platform.
 */
export function generateSetupProject(options: SetupProjectOptions): SetupProjectResult {
  const { platform } = options;
  const targetDir = options.targetDir ?? ".";
  const includeSidecar = options.includeSidecar ?? (platform === "android" || platform === "ios");

  switch (platform) {
    case "android":
      return androidSetup(targetDir, includeSidecar);
    case "ios":
      return iosSetup(targetDir, includeSidecar);
    case "web":
      return webSetup(targetDir);
    case "python":
      return pythonSetup(targetDir);
  }
}

// ─── Android ──────────────────────────────────────────────────────────────────

function androidSetup(targetDir: string, includeSidecar: boolean): SetupProjectResult {
  const files: SetupFile[] = [
    {
      path: `${targetDir}/app/build.gradle.kts`,
      language: "kotlin",
      contents: `// Add to your existing app/build.gradle.kts dependencies block:
dependencies {
    // SceneView AR (required — ships the RerunBridge helper)
    implementation("io.github.sceneview:arsceneview:4.16.9")

    // Kotlin coroutines — already a transitive dep of SceneView, listed for clarity
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
`,
    },
    {
      path: `${targetDir}/app/src/main/java/com/example/rerun/ARWithRerun.kt`,
      language: "kotlin",
      contents: `package com.example.rerun

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rerun.rememberRerunBridge

/**
 * Minimal ARSceneView that streams camera pose + planes + point clouds to a
 * Rerun viewer running on localhost:9876.
 *
 * Setup (one-time, on your dev machine):
 *   1. pip install rerun-sdk
 *   2. python rerun-bridge.py     # see the generated sidecar
 *   3. rerun                      # launches the viewer
 *   4. adb reverse tcp:9876 tcp:9876
 */
@Composable
fun ARWithRerun() {
    val rerun = rememberRerunBridge(
        host = "127.0.0.1",
        port = 9876,
        enabled = true,
    )
    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        onSessionUpdated = { _, frame ->
            rerun.logFrame(frame)
        },
    )
}
`,
    },
  ];

  if (includeSidecar) {
    files.push(pythonSidecarFile(`${targetDir}/tools/rerun-bridge.py`));
  }

  return {
    platform: "android",
    files,
    instructions: [
      "1. Copy the generated files into your Android project.",
      "2. On your dev machine: pip install rerun-sdk",
      "3. Run the sidecar: python tools/rerun-bridge.py",
      "4. Run the viewer: rerun",
      "5. adb reverse tcp:9876 tcp:9876",
      "6. Launch the app and call ARWithRerun() from your NavHost.",
    ],
  };
}

// ─── iOS ──────────────────────────────────────────────────────────────────────

function iosSetup(targetDir: string, includeSidecar: boolean): SetupProjectResult {
  const files: SetupFile[] = [
    {
      path: `${targetDir}/Package.swift`,
      language: "swift",
      contents: `// Add SceneViewSwift to your Swift Package dependencies:
dependencies: [
    .package(url: "https://github.com/sceneview/SceneViewSwift.git", from: "3.7.0"),
]

// …and add the product to your target:
.target(
    name: "YourApp",
    dependencies: [
        .product(name: "SceneViewSwift", package: "SceneViewSwift"),
    ]
)
`,
    },
    {
      path: `${targetDir}/YourApp/ARWithRerunView.swift`,
      language: "swift",
      contents: `import SwiftUI
import SceneViewSwift

/// ARKit session that streams camera pose + planes + point clouds to a Rerun
/// viewer running on the Mac connected via USB (or over the LAN).
///
/// Setup (one-time, on your Mac):
///   1. pip install rerun-sdk
///   2. python rerun-bridge.py
///   3. rerun
///   4. iPhone and Mac on the same Wi-Fi, or tether via USB and use localhost
struct ARWithRerunView: View {
    @StateObject private var rerun = RerunBridge(
        host: "192.168.1.42",   // replace with your Mac's LAN IP
        port: 9876
    )

    var body: some View {
        ARSceneView(
            onFrame: { frame in
                rerun.logFrame(frame)
            }
        )
        .ignoresSafeArea()
        .onAppear { rerun.connect() }
        .onDisappear { rerun.disconnect() }
    }
}
`,
    },
  ];

  if (includeSidecar) {
    files.push(pythonSidecarFile(`${targetDir}/tools/rerun-bridge.py`));
  }

  return {
    platform: "ios",
    files,
    instructions: [
      "1. Add SceneViewSwift to your Swift Package dependencies.",
      "2. On your Mac: pip install rerun-sdk",
      "3. Run the sidecar: python tools/rerun-bridge.py",
      "4. Run the viewer: rerun",
      "5. Make sure iPhone and Mac are on the same network (or USB-tether and use 127.0.0.1).",
      "6. Replace the host IP in ARWithRerunView.swift.",
    ],
  };
}

// ─── Web ──────────────────────────────────────────────────────────────────────

function webSetup(targetDir: string): SetupProjectResult {
  return {
    platform: "web",
    files: [
      {
        path: `${targetDir}/index.html`,
        language: "html",
        contents: `<!doctype html>
<html>
<head>
    <meta charset="utf-8" />
    <title>SceneView + Rerun</title>
    <style>
        body { margin: 0; font-family: system-ui; background: #0f0f14; color: #e6e6eb; }
        .split { display: grid; grid-template-columns: 1fr 1fr; height: 100vh; }
        #sceneview-canvas, #rerun-viewer { width: 100%; height: 100%; }
    </style>
</head>
<body>
    <div class="split">
        <canvas id="sceneview-canvas"></canvas>
        <div id="rerun-viewer"></div>
    </div>

    <!-- SceneView Web (Filament WASM) -->
    <script src="https://unpkg.com/sceneview-web@3.6.2/dist/filament.js"></script>
    <script src="https://unpkg.com/sceneview-web@3.6.2/dist/sceneview.js"></script>

    <!-- Rerun Web Viewer (WASM) -->
    <script type="module">
        import { WebViewer } from "https://cdn.jsdelivr.net/npm/@rerun-io/web-viewer/+esm";
        const viewer = new WebViewer();
        await viewer.start(
            "https://app.rerun.io/static/0.22.0/rerun_demo/dna.rrd",
            document.getElementById("rerun-viewer"),
            { manifest_url: null, hide_welcome_screen: true }
        );
    </script>
</body>
</html>
`,
      },
    ],
    instructions: [
      "1. Serve the HTML file via any static server (e.g. python -m http.server).",
      "2. Open index.html in a modern browser (WebGL2 + WASM required).",
      "3. SceneView renders on the left, Rerun viewer on the right.",
      "4. Replace the .rrd URL with your own recording.",
    ],
  };
}

// ─── Python (standalone pipeline) ─────────────────────────────────────────────

function pythonSetup(targetDir: string): SetupProjectResult {
  return {
    platform: "python",
    files: [
      {
        path: `${targetDir}/requirements.txt`,
        language: "markdown",
        contents: `rerun-sdk>=0.22.0
numpy>=1.24.0
`,
      },
      pythonSidecarFile(`${targetDir}/rerun-bridge.py`),
    ],
    instructions: [
      "1. pip install -r requirements.txt",
      "2. python rerun-bridge.py",
      "3. In another terminal: rerun --connect",
      "4. Point your SceneView client at tcp://localhost:9876",
    ],
  };
}

// ─── Shared helpers ───────────────────────────────────────────────────────────

function pythonSidecarFile(path: string): SetupFile {
  return {
    path,
    language: "python",
    contents: `"""Minimal Rerun sidecar — listens on TCP :9876, re-logs to Rerun.

Reads one JSON object per line from the SceneView client, maps it to the
matching Rerun archetype, and lets the Rerun viewer render it in real time.
"""
from __future__ import annotations

import json
import socket
import sys

import numpy as np
import rerun as rr

HOST = "127.0.0.1"
PORT = 9876


def handle_event(ev: dict) -> None:
    t = int(ev.get("t", 0))
    rr.set_time_nanos("device_clock", t)
    kind = ev.get("type")
    entity = ev.get("entity", "world/unknown")

    if kind == "camera_pose":
        rr.log(
            entity,
            rr.Transform3D(
                translation=ev["translation"],
                rotation=rr.Quaternion(xyzw=ev["quaternion"]),
            ),
        )
    elif kind == "plane":
        poly = np.array(ev["polygon"], dtype=np.float32)
        if len(poly) >= 3:
            rr.log(entity, rr.LineStrips3D([np.vstack([poly, poly[:1]])]))
    elif kind == "point_cloud":
        rr.log(entity, rr.Points3D(np.array(ev["positions"], dtype=np.float32)))
    elif kind == "anchor":
        rr.log(
            entity,
            rr.Transform3D(
                translation=ev["translation"],
                rotation=rr.Quaternion(xyzw=ev["quaternion"]),
            ),
        )
    elif kind == "hit_result":
        rr.log(entity, rr.Points3D([ev["translation"]]))


def main() -> int:
    rr.init("sceneview-bridge", spawn=True)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((HOST, PORT))
    srv.listen(1)
    print(f"[rerun-bridge] listening on {HOST}:{PORT}", flush=True)

    conn, addr = srv.accept()
    print(f"[rerun-bridge] client connected from {addr}", flush=True)

    buf = b""
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
                handle_event(json.loads(line.decode("utf-8")))
            except Exception as exc:
                print(f"[rerun-bridge] skip: {exc}", file=sys.stderr)

    print("[rerun-bridge] client disconnected", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
`,
  };
}

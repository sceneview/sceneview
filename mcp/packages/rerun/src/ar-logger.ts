// ─── AR logger code generator ─────────────────────────────────────────────────
//
// Emits a ready-to-paste helper (Kotlin or Swift) that logs SceneView AR
// session data to Rerun using the JSON-lines wire format.

export type LoggerLanguage = "kotlin" | "swift";

export const LOGGER_LANGUAGES: LoggerLanguage[] = ["kotlin", "swift"];

export type LoggerDataType =
  | "pose"
  | "planes"
  | "point_cloud"
  | "anchors"
  | "hit_results";

export const LOGGER_DATA_TYPES: LoggerDataType[] = [
  "pose",
  "planes",
  "point_cloud",
  "anchors",
  "hit_results",
];

export interface ArLoggerOptions {
  language: LoggerLanguage;
  dataTypes: LoggerDataType[];
  /** Throttle in Hz (default: 10 — the bridge emits at most 10 frames/sec). */
  rateHz?: number;
  /** Host for the TCP socket. Default: "127.0.0.1" */
  host?: string;
  /** Port for the TCP socket. Default: 9876 */
  port?: number;
}

/**
 * Returns a Kotlin or Swift snippet the developer can drop into their project
 * to stream ARCore/ARKit frames to Rerun via the JSON-lines wire format.
 */
export function generateArLogger(options: ArLoggerOptions): string {
  const {
    language,
    dataTypes,
    rateHz = 10,
    host = "127.0.0.1",
    port = 9876,
  } = options;

  if (dataTypes.length === 0) {
    throw new Error("At least one dataType is required");
  }
  for (const dt of dataTypes) {
    if (!LOGGER_DATA_TYPES.includes(dt)) {
      throw new Error(`Invalid dataType "${dt}". Valid: ${LOGGER_DATA_TYPES.join(", ")}`);
    }
  }

  return language === "kotlin"
    ? generateKotlinLogger(dataTypes, rateHz, host, port)
    : generateSwiftLogger(dataTypes, rateHz, host, port);
}

// ─── Kotlin (Android / ARCore) ────────────────────────────────────────────────

function generateKotlinLogger(
  dataTypes: LoggerDataType[],
  rateHz: number,
  host: string,
  port: number,
): string {
  const logBlocks: string[] = [];

  if (dataTypes.includes("pose")) {
    logBlocks.push(`            // Camera pose
            val camPose = frame.camera.pose
            bridge.logCameraPose(camPose, nowNanos)`);
  }
  if (dataTypes.includes("planes")) {
    logBlocks.push(`            // Detected planes
            bridge.logPlanes(frame.getUpdatedTrackables(Plane::class.java), nowNanos)`);
  }
  if (dataTypes.includes("point_cloud")) {
    logBlocks.push(`            // Point cloud — IMPORTANT: call release() on the acquired cloud
            frame.acquirePointCloud().use { cloud ->
                bridge.logPointCloud(cloud, nowNanos)
            }`);
  }
  if (dataTypes.includes("anchors")) {
    logBlocks.push(`            // Session anchors
            bridge.logAnchors(session.allAnchors, nowNanos)`);
  }
  if (dataTypes.includes("hit_results")) {
    logBlocks.push(`            // Last hit result — set by the tap handler below
            latestFrame = frame
            lastHit?.let { bridge.logHitResult(it, nowNanos) }`);
  }

  const logsPlanes = dataTypes.includes("planes");
  const logsHits = dataTypes.includes("hit_results");
  const imports = [
    "androidx.compose.foundation.layout.fillMaxSize",
    "androidx.compose.runtime.Composable",
    ...(logsHits
      ? [
          "androidx.compose.runtime.getValue",
          "androidx.compose.runtime.mutableStateOf",
          "androidx.compose.runtime.remember",
          "androidx.compose.runtime.setValue",
        ]
      : []),
    "androidx.compose.ui.Modifier",
    ...(logsHits ? ["com.google.ar.core.Frame", "com.google.ar.core.HitResult"] : []),
    ...(logsPlanes ? ["com.google.ar.core.Plane"] : []),
    "io.github.sceneview.ar.ARSceneView",
    "io.github.sceneview.ar.rerun.rememberRerunBridge",
    ...(logsHits ? ["io.github.sceneview.rememberOnGestureListener"] : []),
  ]
    .map((name) => `import ${name}`)
    .join("\n");

  return `package com.example.rerun

${imports}

/**
 * AR scene that logs ${dataTypes.join(", ")} to Rerun at ${rateHz} Hz.
 *
 * Wire format: JSON lines over TCP, one object per event. Drops frames on
 * backpressure so the main thread is never blocked.
 */
@Composable
fun ARWithRerunLogger() {
    val bridge = rememberRerunBridge(
        host = "${host}",
        port = ${port},
        rateHz = ${rateHz},
        enabled = true,
    )
${logsHits ? `
    // Latest ARCore frame and the last tap hit-tested against it
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    var lastHit by remember { mutableStateOf<HitResult?>(null) }
` : ""}
    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        onSessionUpdated = { session, frame ->
            val nowNanos = frame.timestamp
${logBlocks.join("\n")}
        },${logsHits ? `
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { e, _ ->
                lastHit = latestFrame?.hitTest(e)?.firstOrNull()
            }
        ),` : ""}
    )
}
`;
}

// ─── Swift (iOS / ARKit) ──────────────────────────────────────────────────────

function generateSwiftLogger(
  dataTypes: LoggerDataType[],
  rateHz: number,
  host: string,
  port: number,
): string {
  const logBlocks: string[] = [];

  if (dataTypes.includes("pose")) {
    logBlocks.push(`            // Camera pose
            bridge.logCameraPose(frame.camera.transform, timestamp: nowNanos)`);
  }
  if (dataTypes.includes("planes")) {
    logBlocks.push(`            // Detected plane anchors
            let planes = frame.anchors.compactMap { $0 as? ARPlaneAnchor }
            bridge.logPlanes(planes, timestamp: nowNanos)`);
  }
  if (dataTypes.includes("point_cloud")) {
    logBlocks.push(`            // Raw feature points
            if let raw = frame.rawFeaturePoints {
                bridge.logPointCloud(raw, timestamp: nowNanos)
            }`);
  }
  if (dataTypes.includes("anchors")) {
    logBlocks.push(`            // All non-plane anchors
            let anchors = frame.anchors.filter { !($0 is ARPlaneAnchor) }
            bridge.logAnchors(anchors, timestamp: nowNanos)`);
  }
  if (dataTypes.includes("hit_results")) {
    logBlocks.push(`            // Last hit result (update from your tap handler)
            if let hit = self.lastHit {
                bridge.logHitResult(hit, timestamp: nowNanos)
            }`);
  }

  return `import SwiftUI
import SceneViewSwift
import ARKit

/// AR view that logs ${dataTypes.join(", ")} to Rerun at ${rateHz} Hz.
///
/// Wire format: JSON lines over TCP, one object per event. Drops frames on
/// backpressure so the main thread is never blocked.
struct ARWithRerunLoggerView: View {
    @StateObject private var bridge = RerunBridge(
        host: "${host}",
        port: ${port},
        rateHz: ${rateHz}
    )
    @State private var lastHit: ARRaycastResult? = nil

    var body: some View {
        ARSceneView(
            onFrame: { frame in
                let nowNanos = Int64(frame.timestamp * 1_000_000_000)
${logBlocks.join("\n")}
            }
        )
        .ignoresSafeArea()
        .onAppear { bridge.connect() }
        .onDisappear { bridge.disconnect() }
    }
}
`;
}

import { describe, it, expect } from "vitest";
import { generateArLogger, LOGGER_LANGUAGES, LOGGER_DATA_TYPES, } from "./ar-logger.js";
describe("LOGGER_LANGUAGES", () => {
    it("exposes kotlin and swift", () => {
        expect(LOGGER_LANGUAGES).toEqual(["kotlin", "swift"]);
    });
});
describe("LOGGER_DATA_TYPES", () => {
    it("includes pose, planes, point_cloud, anchors, hit_results", () => {
        expect(LOGGER_DATA_TYPES).toContain("pose");
        expect(LOGGER_DATA_TYPES).toContain("planes");
        expect(LOGGER_DATA_TYPES).toContain("point_cloud");
        expect(LOGGER_DATA_TYPES).toContain("anchors");
        expect(LOGGER_DATA_TYPES).toContain("hit_results");
    });
});
describe("generateArLogger — kotlin", () => {
    it("emits a composable that uses ARSceneView + rememberRerunBridge", () => {
        const code = generateArLogger({
            language: "kotlin",
            dataTypes: ["pose", "planes"],
        });
        expect(code).toContain("@Composable");
        expect(code).toContain("io.github.sceneview.ar.ARSceneView");
        expect(code).toContain("io.github.sceneview.ar.rerun.rememberRerunBridge");
        expect(code).toContain("onSessionUpdated");
    });
    it("includes pose logging when requested", () => {
        const code = generateArLogger({
            language: "kotlin",
            dataTypes: ["pose"],
        });
        expect(code).toContain("frame.camera.pose");
        expect(code).toContain("bridge.logCameraPose");
        expect(code).not.toContain("getUpdatedPlanes");
    });
    it("wraps point cloud acquisition in use { } to release natively", () => {
        const code = generateArLogger({
            language: "kotlin",
            dataTypes: ["point_cloud"],
        });
        expect(code).toContain("acquirePointCloud()");
        expect(code).toContain(".use {");
    });
    it("honours the rateHz option in the bridge constructor", () => {
        const code = generateArLogger({
            language: "kotlin",
            dataTypes: ["pose"],
            rateHz: 30,
        });
        expect(code).toContain("rateHz = 30");
    });
});
describe("generateArLogger — swift", () => {
    it("emits a SwiftUI view that uses ARSceneView + RerunBridge", () => {
        const code = generateArLogger({
            language: "swift",
            dataTypes: ["pose", "anchors"],
        });
        expect(code).toContain("import SwiftUI");
        expect(code).toContain("import SceneViewSwift");
        expect(code).toContain("import ARKit");
        expect(code).toContain("ARSceneView");
        expect(code).toContain("RerunBridge");
    });
    it("uses frame.camera.transform for pose logging", () => {
        const code = generateArLogger({
            language: "swift",
            dataTypes: ["pose"],
        });
        expect(code).toContain("frame.camera.transform");
        expect(code).toContain("logCameraPose");
    });
    it("filters ARPlaneAnchor from other anchors when planes are enabled", () => {
        const code = generateArLogger({
            language: "swift",
            dataTypes: ["planes"],
        });
        expect(code).toContain("ARPlaneAnchor");
        expect(code).toContain("frame.anchors");
    });
    it("honours the host/port options", () => {
        const code = generateArLogger({
            language: "swift",
            dataTypes: ["pose"],
            host: "10.0.0.5",
            port: 9877,
        });
        expect(code).toContain('"10.0.0.5"');
        expect(code).toContain("9877");
    });
});
describe("generateArLogger — validation", () => {
    it("throws on empty dataTypes", () => {
        expect(() => generateArLogger({ language: "kotlin", dataTypes: [] })).toThrow(/at least one/i);
    });
    it("throws on invalid dataType", () => {
        expect(() => generateArLogger({
            language: "kotlin",
            // @ts-expect-error — testing runtime validation
            dataTypes: ["bogus"],
        })).toThrow(/invalid datatype/i);
    });
});

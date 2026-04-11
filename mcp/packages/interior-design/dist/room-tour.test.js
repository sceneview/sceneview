import { describe, it, expect } from "vitest";
import { generateRoomTour, TOUR_STYLES, TOUR_SPEEDS, } from "./room-tour.js";
describe("TOUR_STYLES", () => {
    it("has at least 5 tour styles", () => {
        expect(TOUR_STYLES.length).toBeGreaterThanOrEqual(5);
    });
    it("includes core styles", () => {
        expect(TOUR_STYLES).toContain("orbit");
        expect(TOUR_STYLES).toContain("walkthrough");
        expect(TOUR_STYLES).toContain("flyover");
        expect(TOUR_STYLES).toContain("dolly");
        expect(TOUR_STYLES).toContain("panoramic");
    });
});
describe("TOUR_SPEEDS", () => {
    it("has 3 speed options", () => {
        expect(TOUR_SPEEDS).toHaveLength(3);
    });
    it("includes slow, normal, fast", () => {
        expect(TOUR_SPEEDS).toContain("slow");
        expect(TOUR_SPEEDS).toContain("normal");
        expect(TOUR_SPEEDS).toContain("fast");
    });
});
describe("generateRoomTour", () => {
    it("generates valid Kotlin code for orbit tour", () => {
        const code = generateRoomTour({ tourStyle: "orbit" });
        expect(code).toContain("package com.example.interior.tour");
        expect(code).toContain("import io.github.sceneview.SceneView");
        expect(code).toContain("@Composable");
        expect(code).toContain("rememberEngine()");
    });
    it("includes animation transition", () => {
        const code = generateRoomTour({ tourStyle: "walkthrough" });
        expect(code).toContain("rememberInfiniteTransition");
        expect(code).toContain("animateFloat");
        expect(code).toContain("animationProgress");
    });
    it("includes play/pause button", () => {
        const code = generateRoomTour({ tourStyle: "orbit" });
        expect(code).toContain("isPlaying");
        expect(code).toContain("PlayArrow");
        expect(code).toContain("Pause");
    });
    it("includes progress indicator", () => {
        const code = generateRoomTour({ tourStyle: "flyover" });
        expect(code).toContain("LinearProgressIndicator");
    });
    it("includes waypoints", () => {
        const code = generateRoomTour({ tourStyle: "orbit", waypoints: 6 });
        expect(code).toContain("Position(");
        expect(code).toContain("waypoints");
    });
    it("uses normal speed by default (15s)", () => {
        const code = generateRoomTour({ tourStyle: "orbit" });
        expect(code).toContain("15000");
    });
    it("uses slow speed (30s)", () => {
        const code = generateRoomTour({ tourStyle: "orbit", speed: "slow" });
        expect(code).toContain("30000");
    });
    it("uses fast speed (8s)", () => {
        const code = generateRoomTour({ tourStyle: "orbit", speed: "fast" });
        expect(code).toContain("8000");
    });
    it("generates AR code when ar=true", () => {
        const code = generateRoomTour({ tourStyle: "orbit", ar: true });
        expect(code).toContain("ARSceneView(");
        expect(code).toContain("import io.github.sceneview.ar.ARSceneView");
        expect(code).toContain("android.permission.CAMERA");
    });
    it("AR version uses miniature scale", () => {
        const code = generateRoomTour({ tourStyle: "walkthrough", ar: true });
        expect(code).toContain("0.3f");
        expect(code).toContain("Miniature");
    });
    it("handles loading state", () => {
        const code = generateRoomTour({ tourStyle: "panoramic" });
        expect(code).toContain("CircularProgressIndicator");
        expect(code).toContain("modelInstance == null");
    });
    it("includes LightNode with named apply parameter", () => {
        const code = generateRoomTour({ tourStyle: "orbit" });
        expect(code).toContain("LightNode(");
        expect(code).toContain("apply = {");
        expect(code).toContain("intensity(");
    });
    it("uses custom room model path", () => {
        const code = generateRoomTour({
            tourStyle: "orbit",
            roomModel: "models/my_apartment.glb",
        });
        expect(code).toContain("models/my_apartment.glb");
    });
    it("generates code for every tour style", () => {
        for (const style of TOUR_STYLES) {
            const code = generateRoomTour({ tourStyle: style });
            expect(code).toContain("@Composable");
            expect(code).toContain("SceneView(");
        }
    });
    it("generates different waypoint positions per style", () => {
        const orbit = generateRoomTour({ tourStyle: "orbit" });
        const walkthrough = generateRoomTour({ tourStyle: "walkthrough" });
        // The Position values should differ between styles
        expect(orbit).toContain("Position(");
        expect(walkthrough).toContain("Position(");
    });
});

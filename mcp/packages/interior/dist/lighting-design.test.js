import { describe, it, expect } from "vitest";
import { generateLightingDesign, LIGHT_TYPES, COLOR_TEMPERATURES, } from "./lighting-design.js";
describe("LIGHT_TYPES", () => {
    it("has at least 10 light types", () => {
        expect(LIGHT_TYPES.length).toBeGreaterThanOrEqual(10);
    });
    it("includes core light types", () => {
        expect(LIGHT_TYPES).toContain("ambient");
        expect(LIGHT_TYPES).toContain("spot");
        expect(LIGHT_TYPES).toContain("accent");
        expect(LIGHT_TYPES).toContain("pendant");
        expect(LIGHT_TYPES).toContain("recessed");
        expect(LIGHT_TYPES).toContain("natural");
    });
});
describe("COLOR_TEMPERATURES", () => {
    it("has at least 4 color temperatures", () => {
        expect(COLOR_TEMPERATURES.length).toBeGreaterThanOrEqual(4);
    });
    it("includes warm-white and daylight", () => {
        expect(COLOR_TEMPERATURES).toContain("warm-white");
        expect(COLOR_TEMPERATURES).toContain("daylight");
        expect(COLOR_TEMPERATURES).toContain("candlelight");
    });
});
describe("generateLightingDesign", () => {
    describe.each([false, true])("fixture types with ar=%s", (ar) => {
        const fixtures = [
            ["ambient", "POINT", 4_000],
            ["spot", "SPOT", 12_000],
            ["accent", "SPOT", 6_000],
            ["pendant", "POINT", 8_000],
            ["recessed", "SPOT", 10_000],
            ["track", "SPOT", 9_000],
            ["sconce", "POINT", 5_000],
            ["floor-lamp", "POINT", 6_000],
            ["table-lamp", "POINT", 4_000],
            ["chandelier", "POINT", 15_000],
            ["strip-led", "POINT", 3_000],
            ["natural", "DIRECTIONAL", 8_000],
        ];
        // Already scaled model bounds, including wide, deep, and tall rooms.
        const rooms = [
            { x: 2, y: 1.35, z: 2 },
            { x: 2, y: 0.4, z: 0.8 },
            { x: 0.6, y: 1, z: 2 },
            { x: 0.5, y: 2, z: 0.7 },
        ];
        it.each(fixtures)("emits %s as %s", (light, type) => {
            const code = generateLightingDesign({ lights: [light], ar });
            expect(code.match(/type = LightManager.Type.\w+/g)).toEqual([
                `type = LightManager.Type.${type}`,
            ]);
            if (type !== "DIRECTIONAL") {
                expect(code).toContain("import io.github.sceneview.math.Position");
                expect(code).toContain("position = Position(");
            }
            else {
                expect(code).not.toContain("position = Position(");
            }
            if (type === "SPOT") {
                expect(code).toContain("spotLightCone(0.5f, 0.8f)");
            }
            else {
                expect(code).not.toContain("spotLightCone(");
            }
            if (type === "POINT") {
                expect(code).not.toContain("direction(");
            }
            else {
                expect(code).toContain("direction(0f, -1f, 0f)");
            }
        });
        it.each(fixtures)("keeps %s inside the room and lets it reach the floor", (light, type) => {
            const code = generateLightingDesign({ lights: [light], ar });
            const falloff = code.match(/falloff\((.+)\)/);
            if (type === "DIRECTIONAL") {
                expect(falloff).toBeNull();
                return;
            }
            const coordinates = code.match(/position = Position\((.+)\),/)[1].split(",");
            for (const roomSize of ar ? [{ x: 4, y: 2.7, z: 4 }] : rooms) {
                const [x, y, z] = coordinates.map((value) => evaluateKotlin(value, { roomSize }));
                expect(Math.abs(x)).toBeLessThan(roomSize.x / 2);
                expect(y).toBeGreaterThan(0);
                expect(y).toBeLessThan(roomSize.y);
                expect(Math.abs(z)).toBeLessThan(roomSize.z / 2);
                const roomFalloff = ar ? 0 : evaluateKotlin(code.match(/val roomFalloff = (.+)/)[1], { roomSize });
                const radius = evaluateKotlin(falloff[1], { roomFalloff });
                // AR floors can be above or below the session origin. Anchor-local y=0 follows them.
                for (const floorWorldY of ar ? [-3, 0.8, 7] : [0]) {
                    const fixtureWorldY = floorWorldY + y;
                    // Filament contributes no light at the radius itself, so equality is insufficient.
                    expect(radius).toBeGreaterThan(Math.abs(fixtureWorldY - floorWorldY));
                }
            }
        });
        it.each(fixtures)("emits %s at the default light's scale in Filament's expected photometric unit", (light, type, lux) => {
            const code = generateLightingDesign({ lights: [light], ar });
            const intensity = code.match(/intensity\((.+)\)/)[1];
            if (type === "DIRECTIONAL") {
                expect(evaluateKotlin(intensity)).toBe(lux);
                expect(code).toContain("// lux");
                return;
            }
            expect(code).toContain("import kotlin.math.PI");
            expect(code).toContain("Convert preview lux to lumens");
            for (const roomSize of rooms) {
                const height = evaluateKotlin(code.match(/val height = (.+)/)[1], { roomSize });
                const lumens = evaluateKotlin(intensity, { height });
                // Inverse-square lighting at normal incidence: E = Phi / (solid angle * distance^2).
                // Filament's SPOT uses pi; FOCUSED_SPOT (not emitted here) depends on its cone.
                const solidAngle = type === "POINT" ? 4 * Math.PI : Math.PI;
                expect(lumens / (solidAngle * height * height)).toBeCloseTo(lux);
            }
        });
        it("keeps the types and positions of a mixed lighting setup", () => {
            const code = generateLightingDesign({ lights: LIGHT_TYPES, ar });
            expect(code.match(/type = LightManager.Type.\w+/g)).toEqual(fixtures.map(([, type]) => `type = LightManager.Type.${type}`));
            expect(code.match(/position = Position\(/g)).toHaveLength(11);
            expect(code.match(/spotLightCone\(/g)).toHaveLength(4);
        });
        it("keeps wall lights near the walls and the accent light in a corner", () => {
            const roomSize = ar ? { x: 4, y: 2.7, z: 4 } : { x: 0.6, y: 1, z: 2 };
            for (const [light, inset] of [["sconce", 0.475], ["strip-led", 0.475], ["accent", 0.375]]) {
                const code = generateLightingDesign({ lights: [light], ar });
                const [x, , z] = code.match(/position = Position\((.+)\),/)[1]
                    .split(",").map((value) => evaluateKotlin(value, { roomSize }));
                expect(z / roomSize.z).toBeCloseTo(-inset);
                expect(x / roomSize.x).toBeCloseTo(light === "strip-led" ? 0 : inset);
            }
        });
    });
    it("normalizes the room dimensions and bottom-centers the model", () => {
        const code = generateLightingDesign({ lights: ["sconce", "accent"] });
        expect(code).toContain("scaleToUnits = 2.0f");
        expect(code).toContain("centerOrigin = Position(0f, -1f, 0f)");
        for (const halfExtent of [[4, 2.7, 4], [0.5, 2, 1], [2, 1, 8], [0, 0, 0]]) {
            const size = Object.values(evaluateRoomSize(code, halfExtent, halfExtent));
            expect(size.every(Number.isFinite)).toBe(true);
            expect(Math.max(...size)).toBeCloseTo(halfExtent.some((value) => value > 0) ? 2 : 0);
        }
    });
    it("keeps fixtures inside a skinned room when raw and rest-pose bounds diverge", () => {
        const code = generateLightingDesign({ lights: LIGHT_TYPES });
        // A 4 x 2.7 x 4 m room containing a centimeter-exported skin: the unskinned
        // accessor box is 20 x 189 x 20 m, while the SDK's corrected box is the room.
        // Supply both boxes so the emitted accessor, not the test, chooses the bounds.
        const roomSize = evaluateRoomSize(code, [10, 94.5, 10], [2, 1.35, 2]);
        const positions = [...code.matchAll(/position = Position\((.+)\),/g)].map((match) => match[1].split(",").map((value) => evaluateKotlin(value, { roomSize })));
        // The old accessor places ambient at 1.85185, above ModelNode's 1.35 m ceiling.
        expect(positions[0][1]).toBeCloseTo(1.25);
        expect(roomSize).toEqual({ x: 2, y: 1.35, z: 2 });
        expect(positions).toHaveLength(11);
        for (const [x, y, z] of positions) {
            expect(Math.abs(x)).toBeLessThan(1);
            expect(y).toBeGreaterThan(0);
            expect(y).toBeLessThan(1.35);
            expect(Math.abs(z)).toBeLessThan(1);
        }
        expect(code).toContain("import io.github.sceneview.model.restPoseBoundingBox");
        expect(code).toContain('implementation("io.github.sceneview:sceneview:4.69.4")');
    });
    it("places every AR fixture under the tapped horizontal plane's anchor", () => {
        const code = generateLightingDesign({ lights: LIGHT_TYPES, ar: true });
        expect(code).toContain("mutableStateOf<Anchor?>(null)");
        expect(code).toContain("anchor = latestFrame?.hitTest(e)?.firstOrNull");
        expect(code).toContain("Plane.Type.HORIZONTAL_UPWARD_FACING");
        expect(code).toContain("plane.isPoseInPolygon(hit.hitPose)");
        expect(code).toContain("?.createAnchorOrNull()");
        expect(code).toContain("anchor?.let { placedAnchor ->");
        const start = code.indexOf("AnchorNode(anchor = placedAnchor) {");
        expect(start).toBeGreaterThan(0);
        const open = code.indexOf("{", start);
        let depth = 1;
        let end = open + 1;
        for (; end < code.length && depth > 0; end++) {
            if (code[end] === "{")
                depth++;
            if (code[end] === "}")
                depth--;
        }
        expect(code.slice(open, end).match(/LightNode\(/g)).toHaveLength(LIGHT_TYPES.length);
        expect(code.slice(0, open)).not.toContain("LightNode(");
        expect(code.slice(end)).not.toContain("LightNode(");
        expect(code).not.toContain("roomSize");
    });
    it("emits the Kotlin scene scaffold for ambient + spot", () => {
        const code = generateLightingDesign({ lights: ["ambient", "spot"] });
        expect(code).toContain("package com.example.interior.lighting");
        expect(code).toContain("import io.github.sceneview.SceneView");
        expect(code).toContain("@Composable");
        expect(code).toContain("rememberEngine()");
    });
    it("generates LightNode for each light type", () => {
        const code = generateLightingDesign({
            lights: ["ambient", "spot", "accent"],
        });
        expect(code).toContain("Ambient light");
        expect(code).toContain("Spot light");
        expect(code).toContain("Accent light");
    });
    it("uses LightNode with its type and named apply parameter", () => {
        const code = generateLightingDesign({ lights: ["pendant"] });
        expect(code).toContain("LightNode(");
        expect(code).toContain("apply = {");
        expect(code).toContain("type = LightManager.Type.");
        expect(code).toContain("import com.google.android.filament.LightManager");
        expect(code).toContain("intensity(");
    });
    it("includes dimmer sliders when dimmable=true", () => {
        const code = generateLightingDesign({
            lights: ["ambient", "spot"],
            dimmable: true,
        });
        expect(code).toContain("Slider");
        expect(code).toContain("light0Intensity");
        expect(code).toContain("light1Intensity");
    });
    it("excludes dimmer sliders when dimmable=false", () => {
        const code = generateLightingDesign({
            lights: ["ambient"],
            dimmable: false,
        });
        expect(code).not.toContain("light0Intensity");
    });
    it("uses warm-white color by default", () => {
        const code = generateLightingDesign({ lights: ["ambient"] });
        expect(code).toContain("warm-white");
        expect(code).toContain("2700K");
    });
    it("uses candlelight color when specified", () => {
        const code = generateLightingDesign({
            lights: ["table-lamp"],
            colorTemperature: "candlelight",
        });
        expect(code).toContain("candlelight");
        expect(code).toContain("1900K");
    });
    it("uses daylight color when specified", () => {
        const code = generateLightingDesign({
            lights: ["natural"],
            colorTemperature: "daylight",
        });
        expect(code).toContain("daylight");
        expect(code).toContain("6500K");
    });
    it("generates AR code when ar=true", () => {
        const code = generateLightingDesign({
            lights: ["ambient", "spot"],
            ar: true,
        });
        expect(code).toContain("ARSceneView(");
        expect(code).toContain("import io.github.sceneview.ar.ARSceneView");
        expect(code).toContain("android.permission.CAMERA");
    });
    it("handles loading state", () => {
        const code = generateLightingDesign({ lights: ["pendant"] });
        expect(code).toContain("CircularProgressIndicator");
        expect(code).toContain("modelInstance == null");
    });
    it("uses custom room model path", () => {
        const code = generateLightingDesign({
            lights: ["ambient"],
            roomModel: "models/my_room.glb",
        });
        expect(code).toContain("models/my_room.glb");
    });
    it("generates code for every light type individually", () => {
        for (const light of LIGHT_TYPES) {
            const code = generateLightingDesign({ lights: [light] });
            expect(code).toContain("@Composable");
            expect(code).toContain("LightNode");
        }
    });
    it("uses neutral-white color temperature (4000K)", () => {
        const code = generateLightingDesign({
            lights: ["ambient"],
            colorTemperature: "neutral-white",
        });
        expect(code).toContain("neutral-white");
        expect(code).toContain("4000K");
    });
    it("uses cool-white color temperature (5000K)", () => {
        const code = generateLightingDesign({
            lights: ["track"],
            colorTemperature: "cool-white",
        });
        expect(code).toContain("cool-white");
        expect(code).toContain("5000K");
    });
    it("uses default room model when none specified", () => {
        const code = generateLightingDesign({ lights: ["sconce"] });
        expect(code).toContain("models/room/interior_scene.glb");
    });
    it("COLOR_TEMPERATURES has 5 options", () => {
        expect(COLOR_TEMPERATURES.length).toBe(5);
        expect(COLOR_TEMPERATURES).toContain("neutral-white");
        expect(COLOR_TEMPERATURES).toContain("cool-white");
    });
    it("LIGHT_TYPES includes track, sconce, chandelier, strip-led", () => {
        expect(LIGHT_TYPES).toContain("track");
        expect(LIGHT_TYPES).toContain("sconce");
        expect(LIGHT_TYPES).toContain("chandelier");
        expect(LIGHT_TYPES).toContain("strip-led");
    });
});
// Evaluate only arithmetic emitted in these trusted generator fixtures, not arbitrary Kotlin.
function evaluateKotlin(expression, values = {}) {
    const arithmetic = expression
        .replace(/(\d)f\b/g, "$1")
        .replace(/PI\.toFloat\(\)/g, "Math.PI")
        .replace(/maxOf\(/g, "Math.max(")
        .replace(/if \((.+)\) (.+) else (.+)/, "($1) ? ($2) : ($3)");
    return Function(...Object.keys(values), `return (${arithmetic});`)(...Object.values(values));
}
function evaluateRoomSize(code, rawHalfExtent, correctedHalfExtent) {
    const instance = {
        asset: {
            boundingBox: { halfExtent: rawHalfExtent },
            restPoseBoundingBox: { halfExtent: correctedHalfExtent },
        },
    };
    const halfExtent = evaluateKotlin(code.match(/val halfExtent = (.+)/)[1], { instance });
    const largestDimension = evaluateKotlin(code.match(/val largestDimension = (.+)/)[1], { halfExtent });
    const roomScale = evaluateKotlin(code.match(/val roomScale = (.+)/)[1], { largestDimension });
    const dimensions = code.match(/Position\(\s*(halfExtent[\s\S]+?)\s*\)/)[1].split(",");
    const [x, y, z] = dimensions.map((value) => evaluateKotlin(value, { halfExtent, roomScale }));
    return { x, y, z };
}

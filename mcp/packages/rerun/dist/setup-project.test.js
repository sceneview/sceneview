import { describe, it, expect } from "vitest";
import { generateSetupProject, SETUP_PLATFORMS } from "./setup-project.js";
describe("SETUP_PLATFORMS", () => {
    it("exposes the four supported platforms", () => {
        expect(SETUP_PLATFORMS).toEqual(["android", "ios", "web", "python"]);
    });
});
describe("generateSetupProject — android", () => {
    const result = generateSetupProject({ platform: "android", targetDir: "myapp" });
    it("emits at least the build.gradle.kts and a Kotlin composable", () => {
        const paths = result.files.map((f) => f.path);
        expect(paths).toContain("myapp/app/build.gradle.kts");
        expect(paths).toContain("myapp/app/src/main/java/com/example/rerun/ARWithRerun.kt");
    });
    it("includes the Python sidecar by default", () => {
        const sidecar = result.files.find((f) => f.path.endsWith("rerun-bridge.py"));
        expect(sidecar).toBeDefined();
        expect(sidecar.contents).toContain("rerun as rr");
    });
    it("references arsceneview:4.16.9 in the Gradle file", () => {
        const gradle = result.files.find((f) => f.path.endsWith("build.gradle.kts"));
        expect(gradle.contents).toContain("io.github.sceneview:arsceneview:4.16.9");
    });
    it("uses rememberRerunBridge in the Kotlin composable", () => {
        const kt = result.files.find((f) => f.path.endsWith("ARWithRerun.kt"));
        expect(kt.contents).toContain("rememberRerunBridge");
        expect(kt.contents).toContain("onSessionUpdated");
        expect(kt.contents).toContain("rerun.logFrame(frame)");
    });
    it("omits the sidecar when includeSidecar=false", () => {
        const bare = generateSetupProject({
            platform: "android",
            targetDir: "myapp",
            includeSidecar: false,
        });
        expect(bare.files.some((f) => f.path.endsWith("rerun-bridge.py"))).toBe(false);
    });
});
describe("generateSetupProject — ios", () => {
    const result = generateSetupProject({ platform: "ios", targetDir: "myapp" });
    it("emits a Package.swift and a SwiftUI view", () => {
        const paths = result.files.map((f) => f.path);
        expect(paths).toContain("myapp/Package.swift");
        expect(paths).toContain("myapp/YourApp/ARWithRerunView.swift");
    });
    it("references SceneViewSwift 3.7.0", () => {
        const pkg = result.files.find((f) => f.path.endsWith("Package.swift"));
        expect(pkg.contents).toContain("3.7.0");
        expect(pkg.contents).toContain("SceneViewSwift");
    });
    it("uses RerunBridge and ARSceneView in the SwiftUI view", () => {
        const swift = result.files.find((f) => f.path.endsWith("ARWithRerunView.swift"));
        expect(swift.contents).toContain("RerunBridge");
        expect(swift.contents).toContain("ARSceneView");
        expect(swift.contents).toContain("logFrame");
    });
});
describe("generateSetupProject — web", () => {
    const result = generateSetupProject({ platform: "web", targetDir: "docs" });
    it("emits a single index.html", () => {
        expect(result.files.length).toBe(1);
        expect(result.files[0].path).toBe("docs/index.html");
    });
    it("embeds the Rerun Web Viewer module", () => {
        const html = result.files[0].contents;
        expect(html).toContain("@rerun-io/web-viewer");
        expect(html).toContain("WebViewer");
        expect(html).toContain("sceneview-web");
    });
});
describe("generateSetupProject — python", () => {
    const result = generateSetupProject({ platform: "python", targetDir: "pipeline" });
    it("emits requirements.txt + rerun-bridge.py", () => {
        const paths = result.files.map((f) => f.path);
        expect(paths).toContain("pipeline/requirements.txt");
        expect(paths).toContain("pipeline/rerun-bridge.py");
    });
    it("pins rerun-sdk in requirements", () => {
        const req = result.files.find((f) => f.path.endsWith("requirements.txt"));
        expect(req.contents).toMatch(/rerun-sdk>=/);
    });
});
describe("generateSetupProject — instructions", () => {
    it("returns non-empty step-by-step instructions for every platform", () => {
        for (const platform of SETUP_PLATFORMS) {
            const r = generateSetupProject({ platform });
            expect(r.instructions.length).toBeGreaterThan(2);
        }
    });
});

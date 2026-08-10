import { describe, it, expect } from "vitest";
import { LATEST_FLUTTER_PUB_RELEASE, LATEST_SCENEVIEW_RELEASE } from "./generated/version.js";
import { getPlatformSetup, listPlatforms, PLATFORM_IDS, type Platform, type SetupType } from "./platform-setup.js";

describe("PLATFORM_IDS", () => {
  it("contains all 7 platforms", () => {
    expect(PLATFORM_IDS).toHaveLength(7);
    expect(PLATFORM_IDS).toContain("android");
    expect(PLATFORM_IDS).toContain("ios");
    expect(PLATFORM_IDS).toContain("web");
    expect(PLATFORM_IDS).toContain("flutter");
    expect(PLATFORM_IDS).toContain("react-native");
    expect(PLATFORM_IDS).toContain("desktop");
    expect(PLATFORM_IDS).toContain("tv");
  });
});

describe("getPlatformSetup", () => {
  it("returns Android 3D setup with Gradle dependency", () => {
    const result = getPlatformSetup("android", "3d");
    expect(result).toContain(`io.github.sceneview:sceneview:${LATEST_SCENEVIEW_RELEASE}`);
    expect(result).toContain("rememberEngine");
    expect(result).toContain("SceneView(");
  });

  it("returns Android AR setup with manifest and permissions", () => {
    const result = getPlatformSetup("android", "ar");
    expect(result).toContain("arsceneview");
    expect(result).toContain("CAMERA");
    expect(result).toContain("com.google.ar.core");
    expect(result).toContain("ARScene");
  });

  it("returns iOS 3D setup with SPM dependency", () => {
    const result = getPlatformSetup("ios", "3d");
    expect(result).toContain("SceneViewSwift");
    expect(result).toContain("Package.swift");
    expect(result).toContain("RealityKit");
  });

  it("returns iOS AR setup with Info.plist", () => {
    const result = getPlatformSetup("ios", "ar");
    expect(result).toContain("NSCameraUsageDescription");
    expect(result).toContain("ARSceneView");
  });

  it("returns Web setup with npm install", () => {
    const result = getPlatformSetup("web", "3d");
    expect(result).toContain("npm install");
    expect(result).toContain("Filament.js");
  });

  it("returns 'AR not supported' for web AR", () => {
    const result = getPlatformSetup("web", "ar");
    expect(result).toContain("AR is not supported");
  });

  it("returns Flutter setup with pubspec", () => {
    const result = getPlatformSetup("flutter", "3d");
    expect(result).toContain("pubspec.yaml");
    expect(result).toContain("sceneview_flutter");
  });

  // The pubspec caret range must name a version that ALREADY EXISTS on
  // pub.dev. Until 2026-08-10 this guide interpolated LATEST_SCENEVIEW_RELEASE
  // — the in-flight SDK version, which runs ahead of the plugin's own release
  // train — and emitted `^4.26.0` while pub.dev's newest was 4.24.0: a line
  // `flutter pub get` cannot resolve, handed out by the server developers ask
  // how to install. Asserting "not the SDK version" is the half that actually
  // catches a regression; asserting the pub version alone would still pass if
  // the two happened to coincide at the moment someone reintroduced the bug.
  it("pins the Flutter pubspec to the pub.dev release, never to VERSION_NAME", () => {
    const result = getPlatformSetup("flutter", "3d");
    expect(result).toContain(`flutter_sceneview: ^${LATEST_FLUTTER_PUB_RELEASE}`);
    // Widened to `string` on purpose. Both constants are `as const`, so
    // comparing them directly is a TS2367 compile error ("no overlap")
    // precisely when they differ — which is the only situation in which the
    // guard has any work to do. Keeping the literal types here would mean the
    // suite stops compiling the day the two versions diverge.
    const pubRelease: string = LATEST_FLUTTER_PUB_RELEASE;
    const sdkRelease: string = LATEST_SCENEVIEW_RELEASE;
    if (pubRelease !== sdkRelease) {
      expect(result).not.toContain(`flutter_sceneview: ^${sdkRelease}`);
    }
  });

  it("returns React Native setup with npm install", () => {
    const result = getPlatformSetup("react-native", "3d");
    expect(result).toContain("npm install");
    expect(result).toContain("@sceneview/react-native");
  });

  it("returns Desktop setup with Compose Desktop", () => {
    const result = getPlatformSetup("desktop", "3d");
    expect(result).toContain("Compose Desktop");
  });

  it("returns 'AR not supported' for desktop AR", () => {
    const result = getPlatformSetup("desktop", "ar");
    expect(result).toContain("AR is not supported");
  });

  it("returns TV setup with D-pad controls", () => {
    const result = getPlatformSetup("tv", "3d");
    expect(result).toContain("D-pad");
    expect(result).toContain("leanback");
  });

  it("returns 'AR not supported' for TV AR", () => {
    const result = getPlatformSetup("tv", "ar");
    expect(result).toContain("AR is not supported");
  });

  it("returns error for unknown platform", () => {
    const result = getPlatformSetup("unknown" as Platform, "3d");
    expect(result).toContain("Unknown platform");
  });
});

describe("listPlatforms", () => {
  it("returns a markdown table with all platforms", () => {
    const result = listPlatforms();
    expect(result).toContain("Android");
    expect(result).toContain("iOS");
    expect(result).toContain("Web");
    expect(result).toContain("Flutter");
    expect(result).toContain("React Native");
    expect(result).toContain("Desktop");
    expect(result).toContain("Android TV");
  });

  it("shows AR support status per platform", () => {
    const result = listPlatforms();
    // Platforms with AR
    expect(result).toContain("| Yes | Yes |");  // Android, iOS, Flutter, React Native
    // Platforms without AR
    expect(result).toContain("| Yes | No |");   // Web, Desktop, TV
  });
});

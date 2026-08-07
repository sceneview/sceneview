// Smoke tests for the React Native bridge.
//
// The Flutter `features_page.dart` and the README claim a set of methods are
// "implemented" — the #909 umbrella found some of those claims to be lies on
// the native side. These tests pin the **observable** half of the JS bridge:
// every documented method must return a Promise (resolved or rejected), never
// `undefined` or a thrown error from the JS layer itself.
//
// We exercise `ARRecorder` because it is pure JS dispatch on top of
// `NativeModules.RNARRecorder` — easy to mock without a renderer. The
// `SceneView` / `ARSceneView` components require `requireNativeComponent`,
// which only works inside a running RN app; a smoke test for them would need
// the full Jest preset for React Native and lives in a follow-up.

// We mock 'react-native' to give us a deterministic Platform + NativeModules
// surface in a Node test environment, without dragging in the full
// react-native preset.
jest.mock(
  "react-native",
  () => {
    return {
      Platform: { OS: "android" },
      NativeModules: {},
      // Stubbed surface — only `Platform`/`NativeModules` are read by ARRecorder.
      // Touching anything else from here should fail loudly.
      requireNativeComponent: () => null,
      View: () => null,
      Text: () => null,
      StyleSheet: { create: (s: unknown) => s },
    };
  },
  { virtual: true }
);

import { ARRecorder } from "../src/index";

describe("ARRecorder bridge smoke tests", () => {
  test("every method returns a Promise on an unsupported platform", () => {
    const recorder = new ARRecorder();

    // The methods that the README + Flutter features page claim are
    // "implemented" must return Promises — never `undefined`, never throw
    // synchronously. On Android the promise rejects with the documented
    // "iOS-only" error.
    const start = recorder.start();
    const stop = recorder.stop();
    const save = recorder.saveToPhotoLibrary("/tmp/foo.mov");

    expect(start).toBeInstanceOf(Promise);
    expect(stop).toBeInstanceOf(Promise);
    expect(save).toBeInstanceOf(Promise);

    // Swallow the rejections so the test runner does not flag them.
    return Promise.all([start.catch(() => null), stop.catch(() => null), save.catch(() => null)]);
  });

  test("isSupported reflects the platform + native module presence", () => {
    // The mocked Platform.OS is 'android' and NativeModules is empty, so
    // isSupported MUST be false. If the bridge ever flips a default that
    // claims iOS support on Android, this test catches it.
    expect(ARRecorder.isSupported).toBe(false);
  });

  test("start rejects on unsupported platforms with a helpful message", async () => {
    const recorder = new ARRecorder();
    await expect(recorder.start()).rejects.toThrow(/iOS/);
    await expect(recorder.start()).rejects.toThrow(/#1051/);
  });
});

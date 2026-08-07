import type { TapEvent } from "../src/index";

/**
 * `TapEvent.nodeName` must stay `string | null` — exactly, with no `undefined`.
 *
 * Every native dispatch path writes the key: Android's single
 * `TapEvent.getEventData()` (`putString` / `putNull`, shared by
 * `SceneViewManager` and `ARSceneViewManager`), the iOS 3D wrapper and the iOS
 * AR wrapper (both build their payload with `rnTapPayload`, which seeds
 * `"nodeName": NSNull()` before any branch runs). So a consumer needs one
 * `nodeName == null` guard, not a two-sentinel `null`/`undefined` one.
 *
 * That the key is always *written* is a separate claim from what a hit
 * *reports*, and the latter is not uniform: on `ARSceneView`, Android names the
 * tapped model while iOS is always `null` (no entity hit-test hook, #2051).
 * These assertions are about the type, which both platforms satisfy.
 *
 * These are compile-time assertions — `ts-jest` type-checks this file, so
 * re-introducing `?` or `| undefined` on `nodeName` fails the run rather than
 * silently widening a published type. The runtime `expect` only keeps Jest
 * from reporting an empty suite.
 */

/** Compiles only when `T` and `U` are the *same* type, not merely assignable. */
type Exact<T, U> =
  (<G>() => G extends T ? 1 : 2) extends <G>() => G extends U ? 1 : 2 ? true : false;

type AssertTrue<T extends true> = T;

// `nodeName` is exactly `string | null`. Widening it to `string | null |
// undefined` (an optional `nodeName?:`) makes `Exact<…>` resolve to `false`
// and this line stops compiling.
type NodeNameIsExactlyStringOrNull = AssertTrue<Exact<TapEvent["nodeName"], string | null>>;

// `nodeName` is required, so a payload literal that omits it is rejected.
// @ts-expect-error — the key is never absent on any dispatch path.
const missingNodeName: TapEvent = { x: 0, y: 0, z: 0 };

const nullNodeName: TapEvent = { x: 0, y: 0, z: 0, nodeName: null };
const namedNodeName: TapEvent = { x: 1, y: 2, z: 3, nodeName: "robot" };

describe("TapEvent.nodeName", () => {
  it("is a single null sentinel across every dispatch path", () => {
    // Referenced so `noUnusedLocals`-style lint and ts-jest keep the
    // assertions above alive rather than tree-shaking them.
    const typeCheck: NodeNameIsExactlyStringOrNull = true;
    expect(typeCheck).toBe(true);
    expect(missingNodeName).toBeDefined();

    // The one guard a consumer has to write.
    expect(nullNodeName.nodeName == null).toBe(true);
    expect(namedNodeName.nodeName == null).toBe(false);
  });
});

import { describe, expect, it } from "vitest";
import { ANIMATION_GUIDE, GESTURE_GUIDE, PERFORMANCE_TIPS } from "./advanced-guides.js";

// ─── Animation Guide Tests ───────────────────────────────────────────────────

describe("ANIMATION_GUIDE", () => {
  it("contains glTF model animation section with autoAnimate", () => {
    expect(ANIMATION_GUIDE).toContain("autoAnimate");
    expect(ANIMATION_GUIDE).toContain("Animator");
    expect(ANIMATION_GUIDE).toContain("animationCount");
  });

  it("contains spring animation section with Spring presets", () => {
    expect(ANIMATION_GUIDE).toContain("SpringAnimation");
    expect(ANIMATION_GUIDE).toContain("StiffnessMedium");
    expect(ANIMATION_GUIDE).toContain("DampingRatio");
  });

  it("contains property animation section with Compose integration", () => {
    expect(ANIMATION_GUIDE).toContain("animateFloatAsState");
    expect(ANIMATION_GUIDE).toContain("InfiniteTransition");
    expect(ANIMATION_GUIDE).toContain("rememberInfiniteTransition");
  });

  it("contains SmoothTransform section", () => {
    expect(ANIMATION_GUIDE).toContain("SmoothTransform");
    expect(ANIMATION_GUIDE).toContain("smoothTime");
    expect(ANIMATION_GUIDE).toContain("advance");
  });

  it("contains compilable Kotlin code with proper imports and structure", () => {
    expect(ANIMATION_GUIDE).toContain("@Composable");
    expect(ANIMATION_GUIDE).toContain("rememberEngine");
    expect(ANIMATION_GUIDE).toContain("rememberModelLoader");
    expect(ANIMATION_GUIDE).toContain("rememberModelInstance");
    expect(ANIMATION_GUIDE).toContain("SceneView(");
  });

  it("contains AR animation example", () => {
    expect(ANIMATION_GUIDE).toContain("ARSceneView(");
    expect(ANIMATION_GUIDE).toContain("AnchorNode");
    expect(ANIMATION_GUIDE).toContain("createAnchorOrNull()");
  });

  it("places the AR model on a confirmed tap on empty space, not on ACTION_UP", () => {
    expect(ANIMATION_GUIDE).toContain("onSingleTapConfirmed = { event, node ->");
    expect(ANIMATION_GUIDE).toContain("if (node == null)");
    expect(ANIMATION_GUIDE).not.toContain("ACTION_UP)");
    expect(ANIMATION_GUIDE).not.toContain("anchorCreated");
    expect(ANIMATION_GUIDE).toContain("import com.google.ar.core.Plane\n");
    expect(ANIMATION_GUIDE).toContain("import io.github.sceneview.ar.arcore.createAnchorOrNull\n");
    expect(ANIMATION_GUIDE).toContain("import io.github.sceneview.rememberOnGestureListener\n");
  });

  it("includes key takeaways section", () => {
    expect(ANIMATION_GUIDE).toContain("Key Takeaways");
    expect(ANIMATION_GUIDE).toContain("Compose animations");
    expect(ANIMATION_GUIDE).toContain("main thread");
  });
});

// ─── Gesture Guide Tests ─────────────────────────────────────────────────────

describe("GESTURE_GUIDE", () => {
  it("contains isEditable section with gesture table", () => {
    expect(GESTURE_GUIDE).toContain("isEditable = true");
    expect(GESTURE_GUIDE).toContain("Pinch to scale");
    expect(GESTURE_GUIDE).toContain("Drag to rotate");
    expect(GESTURE_GUIDE).toContain("Tap to select");
  });

  it("contains custom gesture handling with onTouchEvent", () => {
    expect(GESTURE_GUIDE).toContain("onTouchEvent");
    expect(GESTURE_GUIDE).toContain("MotionEvent.ACTION_DOWN");
    expect(GESTURE_GUIDE).toContain("MotionEvent.ACTION_MOVE");
    expect(GESTURE_GUIDE).toContain("MotionEvent.ACTION_UP");
  });

  it("contains AR tap-to-place example", () => {
    expect(GESTURE_GUIDE).toContain("createAnchorOrNull()");
    expect(GESTURE_GUIDE).toContain("AnchorNode");
    expect(GESTURE_GUIDE).toContain("ARSceneView(");
    expect(GESTURE_GUIDE).toContain("planeRenderer");
  });

  it("places on a confirmed tap on empty space, never on ACTION_UP", () => {
    const tapToPlace = GESTURE_GUIDE.slice(
      GESTURE_GUIDE.indexOf("## 3. Tap-to-Place in AR"),
      GESTURE_GUIDE.indexOf("## 4. ")
    );
    expect(tapToPlace).toContain("onGestureListener = rememberOnGestureListener(");
    expect(tapToPlace).toContain("onSingleTapConfirmed = { event, node ->");
    expect(tapToPlace).toContain("if (node == null)");
    expect(tapToPlace).toContain("isEditable = true");
    expect(tapToPlace).not.toContain("onTouchEvent = {");
    expect(tapToPlace).not.toContain("MotionEvent.ACTION_UP");
    expect(tapToPlace).not.toContain("anchorCreated");
    expect(tapToPlace).toContain("import com.google.ar.core.Plane\n");
    expect(tapToPlace).toContain("import io.github.sceneview.ar.arcore.createAnchorOrNull\n");
    expect(tapToPlace).toContain("import io.github.sceneview.rememberOnGestureListener\n");
    // The key takeaway teaches the same hook.
    expect(GESTURE_GUIDE).not.toContain("on `ACTION_UP` in `onTouchEvent`, hit-test");
    expect(GESTURE_GUIDE).toContain(
      "**AR tap-to-place** — in `onGestureListener = rememberOnGestureListener(onSingleTapConfirmed"
    );
  });

  it("contains drag-to-rotate with custom sensitivity", () => {
    expect(GESTURE_GUIDE).toContain("sensitivity");
    expect(GESTURE_GUIDE).toContain("deltaX");
    expect(GESTURE_GUIDE).toContain("rotationY");
  });

  it("contains pinch-to-scale with limits", () => {
    expect(GESTURE_GUIDE).toContain("ScaleGestureDetector");
    expect(GESTURE_GUIDE).toContain("minScale");
    expect(GESTURE_GUIDE).toContain("maxScale");
    expect(GESTURE_GUIDE).toContain("coerceIn");
  });

  it("contains HitResultNode surface cursor example", () => {
    expect(GESTURE_GUIDE).toContain("HitResultNode");
    expect(GESTURE_GUIDE).toContain("cursor");
  });

  it("contains compilable Kotlin code with proper SceneView patterns", () => {
    expect(GESTURE_GUIDE).toContain("@Composable");
    expect(GESTURE_GUIDE).toContain("rememberEngine");
    expect(GESTURE_GUIDE).toContain("rememberModelLoader");
    expect(GESTURE_GUIDE).toContain("ModelNode(");
  });
});

// ─── Performance Tips Tests ──────────────────────────────────────────────────

describe("PERFORMANCE_TIPS", () => {
  it("loads the small HDR through the real EnvironmentLoader API", () => {
    expect(PERFORMANCE_TIPS).toContain("rememberEnvironment(environmentLoader) {");
    expect(PERFORMANCE_TIPS).toContain(
      'environmentLoader.createHDREnvironment("environments/sky_2k.hdr")'
    );
    expect(PERFORMANCE_TIPS).not.toContain(
      'rememberEnvironment(engine, "environments/sky_2k.hdr")'
    );
  });

  it("contains polygon budget table with device tiers", () => {
    expect(PERFORMANCE_TIPS).toContain("Low-end");
    expect(PERFORMANCE_TIPS).toContain("Mid-range");
    expect(PERFORMANCE_TIPS).toContain("High-end");
    expect(PERFORMANCE_TIPS).toContain("Max Triangles");
  });

  it("contains LOD section", () => {
    expect(PERFORMANCE_TIPS).toContain("LOD");
    expect(PERFORMANCE_TIPS).toContain("Level of Detail");
    expect(PERFORMANCE_TIPS).toContain("screen coverage");
  });

  it("contains texture compression guidance with KTX2", () => {
    expect(PERFORMANCE_TIPS).toContain("KTX2");
    expect(PERFORMANCE_TIPS).toContain("Basis Universal");
    expect(PERFORMANCE_TIPS).toContain("gltf-transform");
    expect(PERFORMANCE_TIPS).toContain("1024x1024");
  });

  it("contains Draco and Meshopt compression commands", () => {
    expect(PERFORMANCE_TIPS).toContain("draco");
    expect(PERFORMANCE_TIPS).toContain("meshopt");
    expect(PERFORMANCE_TIPS).toContain("60-90%");
  });

  it("contains engine reuse patterns with correct and wrong examples", () => {
    expect(PERFORMANCE_TIPS).toContain("rememberEngine");
    expect(PERFORMANCE_TIPS).toContain("CORRECT");
    expect(PERFORMANCE_TIPS).toContain("WRONG");
  });

  it("contains frustum culling section", () => {
    expect(PERFORMANCE_TIPS).toContain("Frustum Culling");
    expect(PERFORMANCE_TIPS).toContain("bounding box");
  });

  it("contains instancing section with tree example", () => {
    expect(PERFORMANCE_TIPS).toContain("Instancing");
    expect(PERFORMANCE_TIPS).toContain("modelInstance");
    expect(PERFORMANCE_TIPS).toContain("tree.glb");
  });

  it("contains profiling section with Systrace", () => {
    expect(PERFORMANCE_TIPS).toContain("Systrace");
    expect(PERFORMANCE_TIPS).toContain("systrace.py");
    expect(PERFORMANCE_TIPS).toContain("Frame time");
    expect(PERFORMANCE_TIPS).toContain("16.6ms");
  });

  it("contains post-processing optimization table", () => {
    expect(PERFORMANCE_TIPS).toContain("isPostProcessingEnabled = false");
    expect(PERFORMANCE_TIPS).not.toContain("postProcessing = false");
    expect(PERFORMANCE_TIPS).toContain("Bloom");
    expect(PERFORMANCE_TIPS).toContain("SSAO");
    expect(PERFORMANCE_TIPS).toContain("FXAA");
  });

  it("contains key takeaways section", () => {
    expect(PERFORMANCE_TIPS).toContain("Key Takeaways");
    expect(PERFORMANCE_TIPS).toContain("Compress models");
    expect(PERFORMANCE_TIPS).toContain("Reuse engines");
  });
});

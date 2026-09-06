import { describe, it, expect } from "vitest";
import {
  checkDeprecatedApi,
  checkMissingSceneViewImports,
} from "./deprecated-api-check.js";

describe("checkDeprecatedApi", () => {
  it("reports Scene usage", () => {
    expect(checkDeprecatedApi("Scene()")).toEqual([
      {
        severity: "error",
        message: "`Scene { }` is deprecated since v3.6. Use `SceneView { }`.",
      },
    ]);
  });

  it("reports ARScene usage", () => {
    expect(checkDeprecatedApi("ARScene()")).toEqual([
      {
        severity: "error",
        message: "`ARScene { }` is deprecated since v3.6. Use `ARSceneView { }`.",
      },
    ]);
  });

  it("reports Sceneform ArSceneView usage", () => {
    expect(checkDeprecatedApi("ArSceneView()")).toEqual([
      {
        severity: "error",
        message: "`ArSceneView()` is Sceneform 1.x. Use `ARSceneView { }` from io.github.sceneview.",
      },
    ]);
  });

  it("reports loadModelAsync usage", () => {
    expect(checkDeprecatedApi("loadModelAsync")).toEqual([
      {
        severity: "error",
        message: "`loadModelAsync` is Sceneform. Use `rememberModelInstance` in composables.",
      },
    ]);
  });

  it("reports Engine.create usage", () => {
    expect(checkDeprecatedApi("Engine.create")).toEqual([
      {
        severity: "error",
        message: "`Engine.create` is imperative. Use `rememberEngine()` in composables.",
      },
    ]);
  });

  it("reports Sceneform imports", () => {
    expect(checkDeprecatedApi("import com.google.ar.sceneform.Node")).toEqual([
      {
        severity: "error",
        message: "Sceneform imports are obsolete. SceneView 3.x does not use Sceneform.",
      },
    ]);
  });

  it("does not treat SceneView as Scene", () => {
    expect(checkDeprecatedApi("SceneView()")).toEqual([]);
  });

  it("does not treat MyScene as Scene", () => {
    expect(checkDeprecatedApi("MyScene()")).toEqual([]);
  });

  it("does not treat a qualified Scene as deprecated", () => {
    expect(checkDeprecatedApi("foo.Scene()")).toEqual([]);
  });

  it("does not treat ARSceneView as ARScene", () => {
    expect(checkDeprecatedApi("ARSceneView()")).toEqual([]);
  });

  it("returns no issues for clean modern code", () => {
    const code = "import io.github.sceneview.SceneView\nSceneView { }";
    expect(checkDeprecatedApi(code)).toEqual([]);
  });

  it("returns multiple violations in declaration order", () => {
    const issues = checkDeprecatedApi(
      "import com.google.ar.sceneform.Node\nEngine.create()\nScene()\nloadModelAsync",
    );

    expect(issues).toEqual([
      {
        severity: "error",
        message: "`Scene { }` is deprecated since v3.6. Use `SceneView { }`.",
      },
      {
        severity: "error",
        message: "`loadModelAsync` is Sceneform. Use `rememberModelInstance` in composables.",
      },
      {
        severity: "error",
        message: "`Engine.create` is imperative. Use `rememberEngine()` in composables.",
      },
      {
        severity: "error",
        message: "Sceneform imports are obsolete. SceneView 3.x does not use Sceneform.",
      },
    ]);
  });
});

describe("checkMissingSceneViewImports", () => {
  it("warns when SceneView is used without its import", () => {
    expect(checkMissingSceneViewImports("SceneView { }")).toEqual([
      {
        severity: "warning",
        message: "Missing SceneView import. Add: import io.github.sceneview.SceneView",
      },
    ]);
  });

  it("does not warn when the SceneView import is present", () => {
    const code = "import io.github.sceneview.SceneView\nSceneView { }";
    expect(checkMissingSceneViewImports(code)).toEqual([]);
  });

  it("warns when ARSceneView is used without its import", () => {
    expect(checkMissingSceneViewImports("ARSceneView { }")).toEqual([
      {
        severity: "warning",
        message: "Missing ARSceneView import. Add: import io.github.sceneview.ar.ARSceneView",
      },
    ]);
  });

  it("does not warn when the ARSceneView import is present", () => {
    const code = "import io.github.sceneview.ar.ARSceneView\nARSceneView { }";
    expect(checkMissingSceneViewImports(code)).toEqual([]);
  });

  it("does not accept the SceneView import for ARSceneView usage", () => {
    const code = "import io.github.sceneview.SceneView\nARSceneView { }";
    expect(checkMissingSceneViewImports(code)).toEqual([
      {
        severity: "warning",
        message: "Missing ARSceneView import. Add: import io.github.sceneview.ar.ARSceneView",
      },
    ]);
  });

  it("does not accept the ARSceneView import for SceneView usage", () => {
    const code = "import io.github.sceneview.ar.ARSceneView\nSceneView { }";
    expect(checkMissingSceneViewImports(code)).toEqual([
      {
        severity: "warning",
        message: "Missing SceneView import. Add: import io.github.sceneview.SceneView",
      },
    ]);
  });

  it("does not warn for SceneViewSwift", () => {
    expect(checkMissingSceneViewImports("SceneViewSwift()")).toEqual([]);
  });

  it("does not warn for MySceneView", () => {
    expect(checkMissingSceneViewImports("MySceneView()")).toEqual([]);
  });

  it("does not accept a longer SceneView import name", () => {
    const code = "import io.github.sceneview.SceneViewFoo\nSceneView { }";
    expect(checkMissingSceneViewImports(code)).toEqual([
      {
        severity: "warning",
        message: "Missing SceneView import. Add: import io.github.sceneview.SceneView",
      },
    ]);
  });
});

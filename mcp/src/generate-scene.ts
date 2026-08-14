import { LATEST_SCENEVIEW_RELEASE } from "./generated/version.js";
/**
 * generate-scene.ts
 *
 * Generates a complete SceneView{} or ARSceneView{} composable from a text description.
 * Maps common objects/concepts to SceneView node types and builds compilable code.
 *
 * All generated code targets SceneView v4.0.0 API and is verified against llms.txt.
 */

export interface SceneElement {
  type: "model" | "light" | "geometry" | "text" | "environment" | "camera" | "ar-anchor";
  nodeType: string;
  properties: Record<string, string>;
  comment?: string;
}

export interface GeneratedScene {
  code: string;
  description: string;
  elements: SceneElement[];
  isAR: boolean;
  dependencies: string[];
  notes: string[];
}

// ── Object-to-node mapping ──────────────────────────────────────────────────

interface ObjectMapping {
  keywords: string[];
  nodeType: string;
  geometryType?: "cube" | "sphere" | "cylinder" | "plane";
  defaultScale: number;
  defaultPosition: [number, number, number];
  /** For geometry nodes: color name from Compose Color */
  color?: string;
  comment: string;
}

const OBJECT_MAPPINGS: ObjectMapping[] = [
  // Furniture
  {
    keywords: ["table"],
    nodeType: "CubeNode",
    geometryType: "cube",
    defaultScale: 1.0,
    defaultPosition: [0, 0.4, 0],
    color: "Color(0.55f, 0.35f, 0.17f)",
    comment: "Table (flat cube)",
  },
  {
    keywords: ["chair"],
    nodeType: "ModelNode",
    defaultScale: 0.8,
    defaultPosition: [0, 0, -1],
    comment: "Chair (use GLB model)",
  },
  {
    keywords: ["desk"],
    nodeType: "CubeNode",
    geometryType: "cube",
    defaultScale: 1.2,
    defaultPosition: [0, 0.35, 0],
    color: "Color(0.55f, 0.35f, 0.17f)",
    comment: "Desk",
  },
  {
    keywords: ["shelf", "bookshelf"],
    nodeType: "CubeNode",
    geometryType: "cube",
    defaultScale: 1.5,
    defaultPosition: [2, 0.75, 0],
    color: "Color(0.55f, 0.35f, 0.17f)",
    comment: "Shelf",
  },
  {
    keywords: ["bed"],
    nodeType: "CubeNode",
    geometryType: "cube",
    defaultScale: 2.0,
    defaultPosition: [0, 0.25, 0],
    color: "Color.White",
    comment: "Bed",
  },
  {
    keywords: ["sofa", "couch"],
    nodeType: "CubeNode",
    geometryType: "cube",
    defaultScale: 1.8,
    defaultPosition: [0, 0.35, 0],
    color: "Color(0.3f, 0.3f, 0.6f)",
    comment: "Sofa",
  },

  // Basic shapes
  {
    keywords: ["box", "cube", "crate"],
    nodeType: "CubeNode",
    geometryType: "cube",
    defaultScale: 0.5,
    defaultPosition: [0, 0.25, 0],
    color: "Color.Red",
    comment: "Box",
  },
  {
    keywords: ["sphere", "ball", "globe", "orb"],
    nodeType: "SphereNode",
    geometryType: "sphere",
    defaultScale: 0.5,
    defaultPosition: [0, 0.5, 0],
    color: "Color.Blue",
    comment: "Sphere",
  },
  {
    keywords: ["cylinder", "pillar", "column"],
    nodeType: "CylinderNode",
    geometryType: "cylinder",
    defaultScale: 0.5,
    defaultPosition: [0, 0.5, 0],
    color: "Color.Green",
    comment: "Cylinder",
  },
  {
    keywords: ["plane", "floor", "ground"],
    nodeType: "PlaneNode",
    geometryType: "plane",
    defaultScale: 5.0,
    defaultPosition: [0, 0, 0],
    color: "Color.DarkGray",
    comment: "Ground plane",
  },
  {
    keywords: ["wall"],
    nodeType: "CubeNode",
    geometryType: "cube",
    defaultScale: 3.0,
    defaultPosition: [0, 1.5, -3],
    color: "Color.LightGray",
    comment: "Wall",
  },

  // Environment
  {
    keywords: ["tree", "plant"],
    nodeType: "ModelNode",
    defaultScale: 2.0,
    defaultPosition: [2, 0, 2],
    comment: "Tree (use GLB model)",
  },
  {
    keywords: ["car", "vehicle"],
    nodeType: "ModelNode",
    defaultScale: 2.0,
    defaultPosition: [0, 0, 0],
    comment: "Car (use GLB model)",
  },
  {
    keywords: ["house", "building"],
    nodeType: "ModelNode",
    defaultScale: 5.0,
    defaultPosition: [0, 0, -5],
    comment: "Building (use GLB model)",
  },

  // People / characters
  {
    keywords: ["person", "character", "human", "avatar"],
    nodeType: "ModelNode",
    defaultScale: 1.7,
    defaultPosition: [0, 0, 0],
    comment: "Character (use GLB model)",
  },
  {
    keywords: ["robot", "android"],
    nodeType: "ModelNode",
    defaultScale: 1.5,
    defaultPosition: [0, 0, 0],
    comment: "Robot (use GLB model)",
  },

  // Animals
  {
    keywords: ["dog", "puppy"],
    nodeType: "ModelNode",
    defaultScale: 0.6,
    defaultPosition: [1, 0, 0],
    comment: "Dog (use GLB model)",
  },
  {
    keywords: ["cat", "kitten"],
    nodeType: "ModelNode",
    defaultScale: 0.4,
    defaultPosition: [-1, 0, 0],
    comment: "Cat (use GLB model)",
  },
  {
    keywords: ["bird"],
    nodeType: "ModelNode",
    defaultScale: 0.2,
    defaultPosition: [0, 2, 0],
    comment: "Bird (use GLB model)",
  },
  {
    keywords: ["horse"],
    nodeType: "ModelNode",
    defaultScale: 2.5,
    defaultPosition: [0, 0, 2],
    comment: "Horse (use GLB model)",
  },
  {
    keywords: ["fish"],
    nodeType: "ModelNode",
    defaultScale: 0.3,
    defaultPosition: [0, 0.5, 0],
    comment: "Fish (use GLB model)",
  },
  {
    keywords: ["dinosaur", "dino", "t-rex"],
    nodeType: "ModelNode",
    defaultScale: 5.0,
    defaultPosition: [0, 0, -3],
    comment: "Dinosaur (use GLB model)",
  },
  {
    keywords: ["dragon"],
    nodeType: "ModelNode",
    defaultScale: 4.0,
    defaultPosition: [0, 2, -3],
    comment: "Dragon (use GLB model)",
  },

  // More vehicles
  {
    keywords: ["airplane", "plane", "jet"],
    nodeType: "ModelNode",
    defaultScale: 5.0,
    defaultPosition: [0, 5, 0],
    comment: "Airplane (use GLB model)",
  },
  {
    keywords: ["boat", "ship"],
    nodeType: "ModelNode",
    defaultScale: 4.0,
    defaultPosition: [0, 0, -3],
    comment: "Boat (use GLB model)",
  },
  {
    keywords: ["bicycle", "bike"],
    nodeType: "ModelNode",
    defaultScale: 1.2,
    defaultPosition: [2, 0, 0],
    comment: "Bicycle (use GLB model)",
  },
  {
    keywords: ["motorcycle", "motorbike"],
    nodeType: "ModelNode",
    defaultScale: 1.5,
    defaultPosition: [2, 0, 0],
    comment: "Motorcycle (use GLB model)",
  },
  {
    keywords: ["truck"],
    nodeType: "ModelNode",
    defaultScale: 3.0,
    defaultPosition: [0, 0, -2],
    comment: "Truck (use GLB model)",
  },

  // More environment
  {
    keywords: ["flower", "rose"],
    nodeType: "ModelNode",
    defaultScale: 0.3,
    defaultPosition: [1, 0, 1],
    comment: "Flower (use GLB model)",
  },
  {
    keywords: ["rock", "stone", "boulder"],
    nodeType: "SphereNode",
    geometryType: "sphere",
    defaultScale: 0.8,
    defaultPosition: [1, 0.4, 1],
    color: "Color.Gray",
    comment: "Rock",
  },
  {
    keywords: ["mountain", "hill"],
    nodeType: "ModelNode",
    defaultScale: 10.0,
    defaultPosition: [0, 0, -10],
    comment: "Mountain (use GLB model)",
  },
  {
    keywords: ["fence"],
    nodeType: "CubeNode",
    geometryType: "cube",
    defaultScale: 2.0,
    defaultPosition: [3, 0.5, 0],
    color: "Color(0.55f, 0.35f, 0.17f)",
    comment: "Fence",
  },
  {
    keywords: ["bridge"],
    nodeType: "ModelNode",
    defaultScale: 5.0,
    defaultPosition: [0, 0, 0],
    comment: "Bridge (use GLB model)",
  },

  // More furniture / objects
  {
    keywords: ["lamp", "light fixture"],
    nodeType: "ModelNode",
    defaultScale: 0.5,
    defaultPosition: [1, 0.8, 0],
    comment: "Lamp (use GLB model)",
  },
  {
    keywords: ["tv", "television", "screen", "monitor"],
    nodeType: "CubeNode",
    geometryType: "cube",
    defaultScale: 1.2,
    defaultPosition: [0, 0.8, -2],
    color: "Color.Black",
    comment: "TV/Screen (flat cube)",
  },
  {
    keywords: ["door", "gate"],
    nodeType: "CubeNode",
    geometryType: "cube",
    defaultScale: 2.0,
    defaultPosition: [0, 1, -3],
    color: "Color(0.55f, 0.35f, 0.17f)",
    comment: "Door",
  },
  {
    keywords: ["window"],
    nodeType: "CubeNode",
    geometryType: "cube",
    defaultScale: 1.0,
    defaultPosition: [2, 1.5, -3],
    color: "Color(0.7f, 0.85f, 1f)",
    comment: "Window",
  },
  {
    keywords: ["stairs", "staircase"],
    nodeType: "ModelNode",
    defaultScale: 2.0,
    defaultPosition: [3, 0, 0],
    comment: "Stairs (use GLB model)",
  },
  {
    keywords: ["book", "books"],
    nodeType: "CubeNode",
    geometryType: "cube",
    defaultScale: 0.2,
    defaultPosition: [0, 0.5, 0],
    color: "Color(0.6f, 0.1f, 0.1f)",
    comment: "Book",
  },
  {
    keywords: ["bottle", "vase"],
    nodeType: "CylinderNode",
    geometryType: "cylinder",
    defaultScale: 0.2,
    defaultPosition: [0, 0.3, 0],
    color: "Color(0.2f, 0.6f, 0.3f)",
    comment: "Bottle/Vase",
  },
  {
    keywords: ["trophy", "cup"],
    nodeType: "ModelNode",
    defaultScale: 0.3,
    defaultPosition: [0, 0.5, 0],
    comment: "Trophy (use GLB model)",
  },

  // Food
  {
    keywords: ["pizza"],
    nodeType: "CylinderNode",
    geometryType: "cylinder",
    defaultScale: 0.3,
    defaultPosition: [0, 0.5, 0],
    color: "Color(0.9f, 0.7f, 0.2f)",
    comment: "Pizza (flat cylinder)",
  },
  {
    keywords: ["cake"],
    nodeType: "CylinderNode",
    geometryType: "cylinder",
    defaultScale: 0.3,
    defaultPosition: [0, 0.5, 0],
    color: "Color(1f, 0.85f, 0.7f)",
    comment: "Cake (cylinder)",
  },
  {
    keywords: ["apple", "fruit"],
    nodeType: "SphereNode",
    geometryType: "sphere",
    defaultScale: 0.08,
    defaultPosition: [0, 0.5, 0],
    color: "Color.Red",
    comment: "Apple/Fruit",
  },
];

// ── Text keywords ───────────────────────────────────────────────────────────
const TEXT_KEYWORDS = ["label", "text", "sign", "title", "name", "caption", "annotation"];
const LIGHT_KEYWORDS = ["sun", "sunlight", "daylight", "bright", "lit", "sunny", "well-lit"];
const INDOOR_KEYWORDS = ["room", "indoor", "inside", "interior"];
const OUTDOOR_KEYWORDS = ["outdoor", "outside", "garden", "park", "street"];
const AR_KEYWORDS = ["ar", "augmented", "real world", "camera", "place in room", "place on"];
const DARK_KEYWORDS = ["night", "dark", "dim", "mood", "candlelight"];
const ANIMATED_KEYWORDS = ["animated", "spinning", "rotating", "orbiting", "moving", "bouncing"];

function parseDescription(description: string): {
  objects: { mapping: ObjectMapping; count: number; position?: [number, number, number] }[];
  isAR: boolean;
  isIndoor: boolean;
  isOutdoor: boolean;
  needsSky: boolean;
  isDark: boolean;
  hasText: boolean;
  isAnimated: boolean;
} {
  const lower = description.toLowerCase();
  const objects: { mapping: ObjectMapping; count: number }[] = [];

  // Parse quantity + object patterns
  for (const mapping of OBJECT_MAPPINGS) {
    for (const keyword of mapping.keywords) {
      // Match "two chairs", "3 tables", "a chair", "chairs"
      const countPatterns = [
        new RegExp(`(\\d+)\\s+${keyword}s?`, "i"),
        new RegExp(`(one|two|three|four|five|six|seven|eight|nine|ten)\\s+${keyword}s?`, "i"),
        new RegExp(`(a|an)\\s+${keyword}\\b`, "i"),
        new RegExp(`\\b${keyword}s\\b`, "i"), // plural without number = 2
        new RegExp(`\\b${keyword}\\b`, "i"), // singular without article = 1
      ];

      for (const pat of countPatterns) {
        const match = lower.match(pat);
        if (match) {
          let count = 1;
          if (match[1]) {
            const numWords: Record<string, number> = {
              one: 1,
              two: 2,
              three: 3,
              four: 4,
              five: 5,
              six: 6,
              seven: 7,
              eight: 8,
              nine: 9,
              ten: 10,
              a: 1,
              an: 1,
            };
            count = numWords[match[1].toLowerCase()] || parseInt(match[1]) || 1;
          }
          if (pat.source.includes("s\\b") && !match[1]) count = 2; // plural default
          objects.push({ mapping, count });
          break;
        }
      }
    }
  }

  // Deduplicate
  const seen = new Set<string>();
  const unique = objects.filter((o) => {
    const key = o.mapping.keywords[0];
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });

  return {
    objects: unique,
    isAR: AR_KEYWORDS.some((k) => lower.includes(k)),
    isIndoor: INDOOR_KEYWORDS.some((k) => lower.includes(k)),
    isOutdoor: OUTDOOR_KEYWORDS.some((k) => lower.includes(k)),
    needsSky: OUTDOOR_KEYWORDS.some((k) => lower.includes(k)) || lower.includes("sky"),
    isDark: DARK_KEYWORDS.some((k) => lower.includes(k)),
    hasText: TEXT_KEYWORDS.some((k) => lower.includes(k)),
    isAnimated: ANIMATED_KEYWORDS.some((k) => lower.includes(k)),
  };
}

function spreadPositions(
  count: number,
  basePos: [number, number, number],
  spacing: number
): [number, number, number][] {
  if (count === 1) return [basePos];
  const positions: [number, number, number][] = [];
  const halfSpan = ((count - 1) * spacing) / 2;
  for (let i = 0; i < count; i++) {
    positions.push([basePos[0] + i * spacing - halfSpan, basePos[1], basePos[2]]);
  }
  return positions;
}

/** Generate a material variable name from a color expression */
function colorToVarName(color: string): string {
  return (
    color
      .replace("Color.", "")
      .replace(/Color\(.*\)/, "custom")
      .replace(/[^a-zA-Z0-9]/g, "")
      .toLowerCase() + "Mat"
  );
}

export function generateScene(description: string): GeneratedScene {
  const parsed = parseDescription(description);
  const elements: SceneElement[] = [];
  const notes: string[] = [];
  const dependencies: string[] = [];
  let hasModel = false;
  let hasGeometry = false;

  // Generate elements for each parsed object
  for (const obj of parsed.objects) {
    const positions = spreadPositions(obj.count, obj.mapping.defaultPosition, 1.5);
    for (let i = 0; i < obj.count; i++) {
      const pos = positions[i];
      if (obj.mapping.nodeType === "ModelNode") {
        hasModel = true;
        elements.push({
          type: "model",
          nodeType: "ModelNode",
          properties: {
            modelPath: `models/${obj.mapping.keywords[0]}.glb`,
            scaleToUnits: String(obj.mapping.defaultScale),
            position: `Position(${pos[0].toFixed(1)}f, ${pos[1].toFixed(1)}f, ${pos[2].toFixed(1)}f)`,
          },
          comment: obj.mapping.comment,
        });
      } else {
        hasGeometry = true;
        const color = obj.mapping.color || "Color.Gray";
        elements.push({
          type: "geometry",
          nodeType: obj.mapping.nodeType,
          properties: {
            size: String(obj.mapping.defaultScale),
            color,
            geometryType: obj.mapping.geometryType || "cube",
            position: `Position(${pos[0].toFixed(1)}f, ${pos[1].toFixed(1)}f, ${pos[2].toFixed(1)}f)`,
          },
          comment: obj.mapping.comment,
        });
      }
      // element added
    }
  }

  // Add text node if description mentions text/labels
  if (parsed.hasText) {
    elements.push({
      type: "text",
      nodeType: "TextNode",
      properties: {
        text: "Label",
        position: "Position(0f, 1.5f, 0f)",
      },
      comment: "3D text label",
    });
  }

  // Add light
  if (!parsed.isDark) {
    elements.push({
      type: "light",
      nodeType: "LightNode",
      properties: {
        type: parsed.isOutdoor ? "LightManager.Type.SUN" : "LightManager.Type.SUN",
        intensity: parsed.isOutdoor ? "110_000f" : "100_000f",
        castShadows: "true",
      },
      comment: parsed.isOutdoor ? "Sunlight" : "Main directional light",
    });
  } else {
    elements.push({
      type: "light",
      nodeType: "LightNode",
      properties: {
        type: "LightManager.Type.POINT",
        intensity: "50_000f",
        position: "Position(0f, 2f, 0f)",
        castShadows: "false",
        falloff: "10.0f",
      },
      comment: "Dim ambient light",
    });
  }

  // Add ground plane if not already present
  const hasGround = elements.some(
    (e) => e.nodeType === "PlaneNode" || (e.comment && e.comment.toLowerCase().includes("ground"))
  );
  if (!hasGround && !parsed.isAR) {
    hasGeometry = true;
    elements.push({
      type: "geometry",
      nodeType: "PlaneNode",
      properties: {
        size: "10.0",
        color: "Color.DarkGray",
        geometryType: "plane",
        position: "Position(0f, 0f, 0f)",
      },
      comment: "Ground plane",
    });
  }

  // Build the code
  const isAR = parsed.isAR;
  dependencies.push(
    isAR
      ? `io.github.sceneview:arsceneview:${LATEST_SCENEVIEW_RELEASE}`
      : `io.github.sceneview:sceneview:${LATEST_SCENEVIEW_RELEASE}`
  );

  // Build model instance declarations
  const modelElements = elements.filter((e) => e.type === "model");
  const uniqueModels = new Map<string, string>();
  modelElements.forEach((e) => {
    const path = e.properties.modelPath;
    if (!uniqueModels.has(path)) {
      const varName =
        path
          .replace("models/", "")
          .replace(".glb", "")
          .replace(/[^a-zA-Z0-9]/g, "") + "Instance";
      uniqueModels.set(path, varName);
    }
  });

  // Collect unique material colors for geometry nodes
  const uniqueColors = new Map<string, string>();
  const geometryElements = elements.filter((e) => e.type === "geometry");
  for (const e of geometryElements) {
    const color = e.properties.color || "Color.Gray";
    if (!uniqueColors.has(color)) {
      uniqueColors.set(color, colorToVarName(color));
    }
  }

  // Generate Kotlin code
  const lines: string[] = [];
  lines.push("@Composable");
  lines.push(`fun GeneratedScene() {`);
  lines.push("    val engine = rememberEngine()");

  if (hasModel) {
    lines.push("    val modelLoader = rememberModelLoader(engine)");
  }
  if (hasGeometry) {
    lines.push("    val materialLoader = rememberMaterialLoader(engine)");
  }
  if (!isAR) {
    lines.push("    val environmentLoader = rememberEnvironmentLoader(engine)");
  }
  lines.push("");

  // Model instance declarations
  for (const [path, varName] of uniqueModels) {
    lines.push(`    val ${varName} = rememberModelInstance(modelLoader, "${path}")`);
  }
  if (uniqueModels.size > 0) lines.push("");

  // Animation state if needed
  if (parsed.isAnimated && !isAR) {
    lines.push("    var rotationAngle by remember { mutableFloatStateOf(0f) }");
    lines.push("    LaunchedEffect(Unit) {");
    lines.push("        while (true) {");
    lines.push("            withFrameNanos { _ -> rotationAngle += 0.5f }");
    lines.push("        }");
    lines.push("    }");
    lines.push("");
  }

  // SceneView or ARSceneView
  if (isAR) {
    lines.push("    var anchor by remember { mutableStateOf<Anchor?>(null) }");
    lines.push("");
    lines.push("    ARSceneView(");
    lines.push("        modifier = Modifier.fillMaxSize(),");
    lines.push("        engine = engine,");
    if (hasModel) lines.push("        modelLoader = modelLoader,");
    lines.push("        planeRenderer = true,");
    lines.push("        sessionConfiguration = { session, config ->");
    lines.push(
      "            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR"
    );
    lines.push("        },");
    lines.push("        onTouchEvent = { event, hitResult ->");
    lines.push("            if (event.action == MotionEvent.ACTION_UP && hitResult != null) {");
    lines.push("                anchor = hitResult.createAnchor()");
    lines.push("            }");
    lines.push("            true");
    lines.push("        }");
    lines.push("    ) {");
    lines.push("        anchor?.let { a ->");
    lines.push("            AnchorNode(anchor = a) {");
  } else {
    lines.push("    SceneView(");
    lines.push("        modifier = Modifier.fillMaxSize(),");
    lines.push("        engine = engine,");
    if (hasModel) lines.push("        modelLoader = modelLoader,");
    lines.push("        cameraManipulator = rememberCameraManipulator(),");
    if (!parsed.isDark) {
      lines.push("        environment = rememberEnvironment(environmentLoader) {");
      lines.push('            environmentLoader.createHDREnvironment("environments/sky_2k.hdr")');
      lines.push("                ?: createEnvironment(environmentLoader)");
      lines.push("        },");
      lines.push("        mainLightNode = rememberMainLightNode(engine) { intensity = 100_000f }");
    }
    lines.push("    ) {");
  }

  const indent = isAR ? "                " : "        ";

  // Declare material instances for geometry nodes
  if (hasGeometry && !isAR) {
    for (const [color, varName] of uniqueColors) {
      const roughness = color.includes("DarkGray") ? "0.9f" : "0.6f";
      lines.push(`${indent}val ${varName} = remember(materialLoader) {`);
      lines.push(
        `${indent}    materialLoader.createColorInstance(${color}, roughness = ${roughness})`
      );
      lines.push(`${indent}}`);
    }
    lines.push("");
  } else if (hasGeometry && isAR) {
    // In AR context, indent is deeper
    const arMatIndent = "                ";
    for (const [color, varName] of uniqueColors) {
      lines.push(`${arMatIndent}val ${varName} = remember(materialLoader) {`);
      lines.push(
        `${arMatIndent}    materialLoader.createColorInstance(${color}, roughness = 0.6f)`
      );
      lines.push(`${arMatIndent}}`);
    }
    lines.push("");
  }

  // Generate nodes
  for (const elem of elements) {
    lines.push(`${indent}// ${elem.comment}`);

    switch (elem.type) {
      case "model": {
        const varName = uniqueModels.get(elem.properties.modelPath)!;
        lines.push(`${indent}${varName}?.let { instance ->`);
        lines.push(`${indent}    ModelNode(`);
        lines.push(`${indent}        modelInstance = instance,`);
        lines.push(`${indent}        scaleToUnits = ${elem.properties.scaleToUnits}f,`);
        if (elem.properties.position !== "Position(0.0f, 0.0f, 0.0f)") {
          lines.push(`${indent}        position = ${elem.properties.position},`);
        }
        if (parsed.isAnimated) {
          lines.push(`${indent}        autoAnimate = true`);
        }
        lines.push(`${indent}    )`);
        lines.push(`${indent}}`);
        break;
      }
      case "geometry": {
        const nodeType = elem.nodeType;
        const matVar = uniqueColors.get(elem.properties.color || "Color.Gray") || "grayMat";
        const geoType = elem.properties.geometryType;
        const size = parseFloat(elem.properties.size);

        if (nodeType === "PlaneNode") {
          lines.push(`${indent}${nodeType}(`);
          lines.push(`${indent}    size = Size(${size.toFixed(1)}f, ${size.toFixed(1)}f),`);
          lines.push(`${indent}    materialInstance = ${matVar}`);
          lines.push(`${indent})`);
        } else if (nodeType === "SphereNode") {
          lines.push(`${indent}${nodeType}(`);
          lines.push(`${indent}    radius = ${(size / 2).toFixed(2)}f,`);
          lines.push(`${indent}    materialInstance = ${matVar},`);
          if (elem.properties.position !== "Position(0.0f, 0.0f, 0.0f)") {
            lines.push(`${indent}    position = ${elem.properties.position}`);
          }
          lines.push(`${indent})`);
        } else if (nodeType === "CylinderNode") {
          lines.push(`${indent}${nodeType}(`);
          lines.push(`${indent}    radius = ${(size / 2).toFixed(2)}f,`);
          lines.push(`${indent}    height = ${size.toFixed(1)}f,`);
          lines.push(`${indent}    materialInstance = ${matVar},`);
          if (elem.properties.position !== "Position(0.0f, 0.0f, 0.0f)") {
            lines.push(`${indent}    position = ${elem.properties.position}`);
          }
          lines.push(`${indent})`);
        } else {
          // CubeNode
          lines.push(`${indent}${nodeType}(`);
          lines.push(`${indent}    size = Size(${size.toFixed(1)}f),`);
          if (elem.properties.position !== "Position(0.0f, 0.0f, 0.0f)") {
            lines.push(`${indent}    center = ${elem.properties.position},`);
          }
          lines.push(`${indent}    materialInstance = ${matVar}`);
          lines.push(`${indent})`);
        }
        break;
      }
      case "text": {
        lines.push(`${indent}TextNode(`);
        lines.push(`${indent}    text = "${elem.properties.text}",`);
        lines.push(`${indent}    fontSize = 48f,`);
        lines.push(`${indent}    textColor = android.graphics.Color.WHITE,`);
        lines.push(`${indent}    backgroundColor = 0xCC000000.toInt(),`);
        lines.push(`${indent}    widthMeters = 0.6f,`);
        lines.push(`${indent}    heightMeters = 0.2f,`);
        lines.push(`${indent}    position = ${elem.properties.position}`);
        lines.push(`${indent})`);
        break;
      }
      case "light": {
        lines.push(`${indent}LightNode(`);
        lines.push(`${indent}    type = ${elem.properties.type},`);
        lines.push(`${indent}    apply = {`);
        lines.push(`${indent}        intensity(${elem.properties.intensity})`);
        lines.push(`${indent}        castShadows(${elem.properties.castShadows})`);
        if (elem.properties.falloff) {
          lines.push(`${indent}        falloff(${elem.properties.falloff})`);
        }
        lines.push(`${indent}    }`);
        if (elem.properties.position) {
          lines.push(`${indent}    // position = ${elem.properties.position}`);
        }
        lines.push(`${indent})`);
        break;
      }
    }
    lines.push("");
  }

  // Close braces
  if (isAR) {
    lines.push("            }");
    lines.push("        }");
  }
  lines.push("    }");
  lines.push("}");

  // Notes
  if (hasModel) {
    notes.push(
      "This scene references GLB model files. You need to provide actual .glb files in `src/main/assets/models/`."
    );
    notes.push("Free 3D models: https://sketchfab.com/features/free-3d-models (download as GLB)");
  }
  if (isAR) {
    notes.push(
      'AR requires camera permission at runtime and ARCore on the device. See `get_platform_setup("android", "ar")` for full setup.'
    );
  }
  if (elements.length <= 2 && !hasModel && !hasGeometry) {
    notes.push(
      "Only a light was generated. The description didn't match any known objects. Try mentioning specific objects like 'table', 'chair', 'sphere', 'cube', etc."
    );
  }
  if (parsed.isAnimated) {
    notes.push(
      "Animation is enabled. For model animations, the GLB file must contain embedded animations. For geometry, rotation is applied via LaunchedEffect."
    );
  }

  return {
    code: lines.join("\n"),
    description,
    elements,
    isAR,
    dependencies,
    notes,
  };
}

export function formatGeneratedScene(result: GeneratedScene): string {
  const parts: string[] = [];

  parts.push(`## Generated Scene\n`);
  parts.push(`**Description:** "${result.description}"`);
  parts.push(`**Mode:** ${result.isAR ? "AR (ARSceneView)" : "3D (SceneView)"}`);
  parts.push(`**Elements:** ${result.elements.length} nodes\n`);

  parts.push(`### Dependency\n`);
  parts.push("```kotlin");
  result.dependencies.forEach((d) => {
    parts.push(`implementation("${d}")`);
  });
  parts.push("```\n");

  parts.push(`### Code\n`);
  parts.push("```kotlin");
  parts.push(result.code);
  parts.push("```\n");

  if (result.notes.length > 0) {
    parts.push(`### Notes\n`);
    result.notes.forEach((n, i) => {
      parts.push(`${i + 1}. ${n}`);
    });
  }

  return parts.join("\n");
}

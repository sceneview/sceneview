// ─── Free car 3D model database ───────────────────────────────────────────────

export interface CarModel {
  id: string;
  name: string;
  category: CarModelCategory;
  source: string;
  sourceUrl: string;
  format: string;
  license: string;
  description: string;
  tags: string[];
}

export type CarModelCategory =
  | "complete-car"
  | "concept"
  | "classic"
  | "parts"
  | "interior"
  | "wheels"
  | "engine"
  | "test-model";

export const CAR_MODEL_CATEGORIES: CarModelCategory[] = [
  "complete-car", "concept", "classic", "parts",
  "interior", "wheels", "engine", "test-model",
];

export const CAR_MODELS: Record<string, CarModel> = {
  // ── Khronos glTF Sample Models ──────────────────────────────────────────────
  "khronos-toycar": {
    id: "khronos-toycar",
    name: "Khronos ToyCar",
    category: "test-model",
    source: "Khronos glTF Sample Models",
    sourceUrl: "https://github.com/KhronosGroup/glTF-Sample-Models/tree/main/Models/ToyCar",
    format: "GLB (ready for SceneView)",
    license: "CC0 1.0 (Public Domain)",
    description: "Official Khronos glTF sample model — stylized toy car with PBR materials (clearcoat, metallic paint). Perfect for testing SceneView car rendering and material capabilities.",
    tags: ["toy", "sample", "pbr", "clearcoat", "khronos", "test"],
  },
  "sf-moto-helmet": {
    id: "sf-moto-helmet",
    name: "Motorcycle Helmet",
    category: "test-model",
    source: "Sketchfab — sayedgamal655 (CC BY)",
    sourceUrl: "https://sketchfab.com/3d-models/moto-helmet-6c75ac1b13a047bd86742f37ce25adac",
    format: "GLB (ready for SceneView: https://sceneview.github.io/models/platforms/moto_helmet_v2.glb)",
    license: "CC BY 4.0",
    description: "Motorcycle helmet with glossy shell paint and a tinted visor — a PBR material reference for automotive surfaces (metallic, roughness, normal maps). Credit sayedgamal655.",
    tags: ["helmet", "motorcycle", "pbr", "reference", "material-test"],
  },

  // ── Sketchfab Free Cars (CC-licensed) ───────────────────────────────────────
  "sf-low-poly-sedan": {
    id: "sf-low-poly-sedan",
    name: "Low-Poly Sedan",
    category: "complete-car",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/car",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Clean low-poly sedan model — ~5K triangles, solid colors, ideal for mobile performance. Great starting point for configurator demos.",
    tags: ["sedan", "low-poly", "mobile", "configurator", "free"],
  },
  "sf-sports-car": {
    id: "sf-sports-car",
    name: "Sports Car Concept",
    category: "concept",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/sports-car",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Stylized sports car concept — aerodynamic body, detailed wheels, PBR metallic paint. ~25K triangles, suitable for mid-range Android devices.",
    tags: ["sports", "concept", "pbr", "metallic", "aerodynamic"],
  },
  "sf-suv-offroad": {
    id: "sf-suv-offroad",
    name: "Off-Road SUV",
    category: "complete-car",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/suv",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Rugged off-road SUV with roof rack, bull bar, and mud tires. Separate mesh for doors/hood for interactive features.",
    tags: ["suv", "offroad", "truck", "rugged", "doors"],
  },
  "sf-classic-muscle": {
    id: "sf-classic-muscle",
    name: "Classic Muscle Car",
    category: "classic",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/muscle-car",
    format: "GLB",
    license: "CC BY 4.0",
    description: "1960s-style muscle car — chrome bumpers, V8 hood scoop, white-wall tires. Retro styling with modern PBR materials.",
    tags: ["classic", "muscle", "retro", "chrome", "vintage"],
  },
  "sf-electric-concept": {
    id: "sf-electric-concept",
    name: "Electric Vehicle Concept",
    category: "concept",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/electric-car",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Futuristic electric vehicle concept — smooth body, flush door handles, aerodynamic underbody. Includes animated charging port.",
    tags: ["electric", "ev", "concept", "futuristic", "charging"],
  },
  "sf-pickup-truck": {
    id: "sf-pickup-truck",
    name: "Pickup Truck",
    category: "complete-car",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/pickup-truck",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Full-size pickup truck with bed, tailgate, and tow hitch. Separate meshes for doors and tailgate animation.",
    tags: ["truck", "pickup", "utility", "towing", "doors"],
  },

  // ── Engine / Parts models ──────────────────────────────────────────────────
  "sf-v8-engine": {
    id: "sf-v8-engine",
    name: "V8 Engine Assembly",
    category: "engine",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/v8-engine",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Detailed V8 engine with separate components — block, heads, intake, exhaust manifolds, alternator, water pump. Ideal for exploded-view parts catalog.",
    tags: ["engine", "v8", "parts", "exploded", "mechanical"],
  },
  "sf-turbo-engine": {
    id: "sf-turbo-engine",
    name: "Turbocharged 4-Cylinder",
    category: "engine",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/turbo-engine",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Turbocharged inline-4 engine with turbocharger, intercooler piping, and exhaust manifold. Animated turbo spool-up.",
    tags: ["engine", "turbo", "inline-4", "parts", "animation"],
  },
  "sf-brake-assembly": {
    id: "sf-brake-assembly",
    name: "Brake Caliper & Rotor Assembly",
    category: "parts",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/brake",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Multi-piston brake caliper with ventilated rotor, brake pads, and mounting bracket. Each component is a separate mesh for exploded views.",
    tags: ["brakes", "caliper", "rotor", "parts", "exploded"],
  },
  "sf-alloy-wheel": {
    id: "sf-alloy-wheel",
    name: "Multi-Spoke Alloy Wheel",
    category: "wheels",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/wheel",
    format: "GLB",
    license: "CC BY 4.0",
    description: "19-inch multi-spoke alloy wheel with tire, valve stem, and center cap. High-detail PBR brushed aluminum finish.",
    tags: ["wheel", "alloy", "tire", "rim", "pbr"],
  },
  "sf-suspension-kit": {
    id: "sf-suspension-kit",
    name: "Coilover Suspension Kit",
    category: "parts",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/suspension",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Adjustable coilover suspension — strut, spring, top mount, adjustment collars. Separate meshes for assembly animation.",
    tags: ["suspension", "coilover", "strut", "parts", "adjustable"],
  },

  // ── Interior models ─────────────────────────────────────────────────────────
  "sf-car-interior": {
    id: "sf-car-interior",
    name: "Car Interior — Driver Cockpit",
    category: "interior",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/car-interior",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Detailed car interior — dashboard, steering wheel, center console, seats, door panels. Suitable for interior configurator or VR walkthrough.",
    tags: ["interior", "dashboard", "seats", "cockpit", "configurator"],
  },
  "sf-steering-wheel": {
    id: "sf-steering-wheel",
    name: "Sport Steering Wheel",
    category: "interior",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/steering-wheel",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Multi-function sport steering wheel with paddle shifters, buttons, and leather wrap texture. High-detail PBR materials.",
    tags: ["steering", "interior", "sport", "controls", "leather"],
  },
  "sf-dashboard-panel": {
    id: "sf-dashboard-panel",
    name: "Digital Dashboard Panel",
    category: "interior",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/dashboard",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Modern digital instrument cluster panel with dual screens, air vents, and center display bezel. Ready for ViewNode gauge overlay.",
    tags: ["dashboard", "digital", "cluster", "screens", "interior"],
  },

  // ── Google Poly / Poly Pizza (CC BY) ────────────────────────────────────────
  "poly-go-kart": {
    id: "poly-go-kart",
    name: "Go-Kart",
    category: "complete-car",
    source: "Poly Pizza (CC BY)",
    sourceUrl: "https://poly.pizza/",
    format: "GLB (ready for SceneView)",
    license: "CC BY 3.0",
    description: "Low-poly go-kart model — simple geometry, bright colors, ~2K triangles. Perfect for lightweight demos and performance testing.",
    tags: ["kart", "low-poly", "simple", "lightweight", "racing"],
  },
  "poly-race-car": {
    id: "poly-race-car",
    name: "Low-Poly Race Car",
    category: "complete-car",
    source: "Poly Pizza (CC BY)",
    sourceUrl: "https://poly.pizza/",
    format: "GLB (ready for SceneView)",
    license: "CC BY 3.0",
    description: "Stylized race car — wing, roll cage, racing livery. Low-poly (~3K triangles), mobile-friendly.",
    tags: ["racing", "low-poly", "mobile", "sport", "livery"],
  },
};

export const CAR_MODEL_IDS = Object.keys(CAR_MODELS);

export function listCarModels(category?: string, tag?: string): CarModel[] {
  let models = Object.values(CAR_MODELS);

  if (category) {
    models = models.filter((m) => m.category === category);
  }
  if (tag) {
    models = models.filter((m) => m.tags.includes(tag));
  }

  return models;
}

export function getCarModel(id: string): CarModel | undefined {
  return CAR_MODELS[id];
}

export function formatCarModelList(models: CarModel[]): string {
  if (models.length === 0) {
    return "No car models found matching the specified filters.";
  }

  const header = `## Free Car 3D Models (${models.length})\n\n`;
  const rows = models
    .map(
      (m) =>
        `### \`${m.id}\`\n**${m.name}** — *${m.category}*\n${m.description}\n- **Source:** [${m.source}](${m.sourceUrl})\n- **Format:** ${m.format}\n- **License:** ${m.license}\n- **Tags:** ${m.tags.join(", ")}`
    )
    .join("\n\n---\n\n");

  return header + rows;
}

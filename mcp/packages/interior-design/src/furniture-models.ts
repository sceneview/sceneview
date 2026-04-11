// ─── Free furniture 3D model database ─────────────────────────────────────────

export interface FurnitureModel {
  id: string;
  name: string;
  category: FurnitureModelCategory;
  source: string;
  sourceUrl: string;
  format: string;
  license: string;
  description: string;
  tags: string[];
}

export type FurnitureModelCategory =
  | "seating"
  | "table"
  | "bed"
  | "storage"
  | "lighting"
  | "decor"
  | "kitchen"
  | "bathroom"
  | "outdoor"
  | "office";

export const FURNITURE_MODEL_CATEGORIES: FurnitureModelCategory[] = [
  "seating", "table", "bed", "storage", "lighting",
  "decor", "kitchen", "bathroom", "outdoor", "office",
];

export const FURNITURE_MODELS: Record<string, FurnitureModel> = {
  // ── Poly Haven (CC0) ────────────────────────────────────────────────────────
  "ph-modern-sofa": {
    id: "ph-modern-sofa",
    name: "Modern Sofa",
    category: "seating",
    source: "Poly Haven",
    sourceUrl: "https://polyhaven.com/models",
    format: "GLB",
    license: "CC0 (Public Domain)",
    description: "Contemporary 3-seat sofa with fabric upholstery. PBR textures included — diffuse, normal, roughness, AO. Optimized for real-time rendering.",
    tags: ["sofa", "couch", "living-room", "seating", "modern"],
  },
  "ph-armchair": {
    id: "ph-armchair",
    name: "Armchair",
    category: "seating",
    source: "Poly Haven",
    sourceUrl: "https://polyhaven.com/models",
    format: "GLB",
    license: "CC0 (Public Domain)",
    description: "Classic armchair with cushioned seat and wooden legs. PBR materials. Suitable for living room and reading nook scenes.",
    tags: ["armchair", "chair", "living-room", "seating", "classic"],
  },
  "ph-dining-chair": {
    id: "ph-dining-chair",
    name: "Dining Chair",
    category: "seating",
    source: "Poly Haven",
    sourceUrl: "https://polyhaven.com/models",
    format: "GLB",
    license: "CC0 (Public Domain)",
    description: "Wooden dining chair with upholstered seat. Lightweight mesh, suitable for placing multiple instances in a dining room scene.",
    tags: ["chair", "dining", "wood", "seating"],
  },
  "ph-coffee-table": {
    id: "ph-coffee-table",
    name: "Coffee Table",
    category: "table",
    source: "Poly Haven",
    sourceUrl: "https://polyhaven.com/models",
    format: "GLB",
    license: "CC0 (Public Domain)",
    description: "Round coffee table with marble top and brass legs. PBR materials with metallic/roughness maps.",
    tags: ["table", "coffee-table", "living-room", "marble", "modern"],
  },
  "ph-dining-table": {
    id: "ph-dining-table",
    name: "Dining Table",
    category: "table",
    source: "Poly Haven",
    sourceUrl: "https://polyhaven.com/models",
    format: "GLB",
    license: "CC0 (Public Domain)",
    description: "Rectangular dining table in solid oak with natural grain texture. Seats 6. PBR wood materials.",
    tags: ["table", "dining", "wood", "oak", "large"],
  },
  "ph-desk": {
    id: "ph-desk",
    name: "Office Desk",
    category: "office",
    source: "Poly Haven",
    sourceUrl: "https://polyhaven.com/models",
    format: "GLB",
    license: "CC0 (Public Domain)",
    description: "Minimalist office desk with drawers, walnut finish. Suitable for home office and study room scenes.",
    tags: ["desk", "office", "work", "walnut", "modern"],
  },
  "ph-bookshelf": {
    id: "ph-bookshelf",
    name: "Bookshelf",
    category: "storage",
    source: "Poly Haven",
    sourceUrl: "https://polyhaven.com/models",
    format: "GLB",
    license: "CC0 (Public Domain)",
    description: "5-tier bookshelf in pine wood. Includes sample book meshes on shelves. PBR materials with wood grain.",
    tags: ["shelf", "bookshelf", "storage", "living-room", "office"],
  },
  "ph-pendant-light": {
    id: "ph-pendant-light",
    name: "Pendant Light",
    category: "lighting",
    source: "Poly Haven",
    sourceUrl: "https://polyhaven.com/models",
    format: "GLB",
    license: "CC0 (Public Domain)",
    description: "Industrial-style pendant light with metal shade. Includes emissive material for realistic glow effect in SceneView.",
    tags: ["lamp", "pendant", "lighting", "industrial", "ceiling"],
  },
  "ph-floor-lamp": {
    id: "ph-floor-lamp",
    name: "Floor Lamp",
    category: "lighting",
    source: "Poly Haven",
    sourceUrl: "https://polyhaven.com/models",
    format: "GLB",
    license: "CC0 (Public Domain)",
    description: "Arc floor lamp with fabric shade and marble base. Emissive material for ambient glow. Ideal for living room corners.",
    tags: ["lamp", "floor-lamp", "lighting", "modern", "arc"],
  },
  "ph-bed-frame": {
    id: "ph-bed-frame",
    name: "Bed Frame (Queen)",
    category: "bed",
    source: "Poly Haven",
    sourceUrl: "https://polyhaven.com/models",
    format: "GLB",
    license: "CC0 (Public Domain)",
    description: "Queen-size platform bed with upholstered headboard. Includes mattress and pillow meshes. PBR fabric textures.",
    tags: ["bed", "bedroom", "queen", "upholstered", "platform"],
  },
  "ph-nightstand": {
    id: "ph-nightstand",
    name: "Nightstand",
    category: "storage",
    source: "Poly Haven",
    sourceUrl: "https://polyhaven.com/models",
    format: "GLB",
    license: "CC0 (Public Domain)",
    description: "Two-drawer nightstand in white lacquer with brass handles. Compact mesh for bedroom scenes.",
    tags: ["nightstand", "bedroom", "storage", "bedside", "white"],
  },
  "ph-indoor-plant": {
    id: "ph-indoor-plant",
    name: "Indoor Plant (Fiddle Leaf)",
    category: "decor",
    source: "Poly Haven",
    sourceUrl: "https://polyhaven.com/models",
    format: "GLB",
    license: "CC0 (Public Domain)",
    description: "Fiddle leaf fig in ceramic pot. Detailed leaf geometry with translucent leaf material for realistic lighting.",
    tags: ["plant", "indoor", "decor", "fiddle-leaf", "pot"],
  },
  "ph-rug-round": {
    id: "ph-rug-round",
    name: "Round Area Rug",
    category: "decor",
    source: "Poly Haven",
    sourceUrl: "https://polyhaven.com/models",
    format: "GLB",
    license: "CC0 (Public Domain)",
    description: "Round woven area rug (2m diameter). Flat geometry with detailed textile PBR texture — suitable for living room and bedroom.",
    tags: ["rug", "carpet", "decor", "round", "woven"],
  },

  // ── Khronos glTF Sample Assets (Apache 2.0 / CC BY 4.0) ────────────────────
  "khronos-box": {
    id: "khronos-box",
    name: "Textured Box",
    category: "storage",
    source: "Khronos glTF Sample Assets",
    sourceUrl: "https://github.com/KhronosGroup/glTF-Sample-Assets",
    format: "GLB",
    license: "Apache 2.0",
    description: "Simple textured box — ideal as a placeholder for cabinets, storage units, or testing material switching in SceneView.",
    tags: ["box", "storage", "placeholder", "testing"],
  },
  "khronos-chair": {
    id: "khronos-chair",
    name: "Sponza Chair",
    category: "seating",
    source: "Khronos glTF Sample Assets",
    sourceUrl: "https://github.com/KhronosGroup/glTF-Sample-Assets",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Ornate chair from the Sponza scene. Detailed carved wood with fabric seat. High-poly, suitable for archviz quality.",
    tags: ["chair", "ornate", "archviz", "sponza", "classic"],
  },
  "khronos-lantern": {
    id: "khronos-lantern",
    name: "Lantern",
    category: "lighting",
    source: "Khronos glTF Sample Assets",
    sourceUrl: "https://github.com/KhronosGroup/glTF-Sample-Assets",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Oil lantern with glass and metal materials. Emissive flame element. Great for rustic interior lighting demos.",
    tags: ["lantern", "lamp", "lighting", "rustic", "glass"],
  },
  "khronos-water-bottle": {
    id: "khronos-water-bottle",
    name: "Water Bottle",
    category: "decor",
    source: "Khronos glTF Sample Assets",
    sourceUrl: "https://github.com/KhronosGroup/glTF-Sample-Assets",
    format: "GLB",
    license: "Apache 2.0",
    description: "Transparent water bottle with refraction material. Tests SceneView's transparency and transmissive rendering.",
    tags: ["bottle", "glass", "decor", "transparent", "kitchen"],
  },

  // ── Sketchfab (CC-licensed furniture) ───────────────────────────────────────
  "sf-kitchen-island": {
    id: "sf-kitchen-island",
    name: "Kitchen Island with Stools",
    category: "kitchen",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/kitchen",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Modern kitchen island with quartz countertop and 3 bar stools. Includes sink and storage drawers.",
    tags: ["kitchen", "island", "stool", "counter", "modern"],
  },
  "sf-bathroom-vanity": {
    id: "sf-bathroom-vanity",
    name: "Bathroom Vanity",
    category: "bathroom",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/bathroom",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Double-sink bathroom vanity with mirror and marble countertop. Includes faucet and cabinet meshes.",
    tags: ["bathroom", "vanity", "sink", "mirror", "marble"],
  },
  "sf-wardrobe": {
    id: "sf-wardrobe",
    name: "Sliding Wardrobe",
    category: "storage",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/wardrobe",
    format: "GLB",
    license: "CC BY 4.0",
    description: "3-door sliding wardrobe with mirror panel and internal shelving. Animated door opening.",
    tags: ["wardrobe", "closet", "bedroom", "storage", "sliding"],
  },
  "sf-outdoor-chair": {
    id: "sf-outdoor-chair",
    name: "Outdoor Lounge Chair",
    category: "outdoor",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/outdoor-furniture",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Weather-resistant outdoor lounge chair with cushion. Rattan material with fabric cushion textures.",
    tags: ["outdoor", "lounge", "chair", "patio", "rattan"],
  },
  "sf-tv-console": {
    id: "sf-tv-console",
    name: "TV Console Unit",
    category: "storage",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/tv-stand",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Low-profile TV console with open shelving and cable management. Walnut veneer finish with matte black metal frame.",
    tags: ["tv-stand", "console", "living-room", "storage", "entertainment"],
  },
  "sf-wall-mirror": {
    id: "sf-wall-mirror",
    name: "Decorative Wall Mirror",
    category: "decor",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/mirror",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Round decorative wall mirror with gold frame. Reflective material for testing SceneView environment reflections.",
    tags: ["mirror", "wall", "decor", "gold", "round"],
  },
};

export const FURNITURE_MODEL_IDS = Object.keys(FURNITURE_MODELS);

export function listFurnitureModels(category?: string, tag?: string): FurnitureModel[] {
  let models = Object.values(FURNITURE_MODELS);

  if (category) {
    models = models.filter((m) => m.category === category);
  }
  if (tag) {
    models = models.filter((m) => m.tags.includes(tag));
  }

  return models;
}

export function getFurnitureModel(id: string): FurnitureModel | undefined {
  return FURNITURE_MODELS[id];
}

export function formatFurnitureModelList(models: FurnitureModel[]): string {
  if (models.length === 0) {
    return "No furniture models found matching the specified filters.";
  }

  const header = `## Free Furniture 3D Models (${models.length})\n\n`;
  const rows = models
    .map(
      (m) =>
        `### \`${m.id}\`\n**${m.name}** — *${m.category}*\n${m.description}\n- **Source:** [${m.source}](${m.sourceUrl})\n- **Format:** ${m.format}\n- **License:** ${m.license}\n- **Tags:** ${m.tags.join(", ")}`
    )
    .join("\n\n---\n\n");

  return header + rows;
}

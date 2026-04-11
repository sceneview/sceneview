// ─── Free game-ready 3D model database ───────────────────────────────────────

export interface GameModel {
  id: string;
  name: string;
  category: GameModelCategory;
  source: string;
  sourceUrl: string;
  format: string;
  license: string;
  description: string;
  tags: string[];
}

export type GameModelCategory =
  | "character"
  | "weapon"
  | "vehicle"
  | "environment"
  | "prop"
  | "creature"
  | "building"
  | "item"
  | "effect"
  | "ui";

export const GAME_MODEL_CATEGORIES: GameModelCategory[] = [
  "character", "weapon", "vehicle", "environment", "prop",
  "creature", "building", "item", "effect", "ui",
];

export const GAME_MODELS: Record<string, GameModel> = {
  // ── Khronos glTF Sample Models ────────────────────────────────────────────
  "khronos-damaged-helmet": {
    id: "khronos-damaged-helmet",
    name: "Damaged Helmet",
    category: "prop",
    source: "Khronos glTF Sample Models",
    sourceUrl: "https://github.com/KhronosGroup/glTF-Sample-Models",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Sci-fi damaged helmet with PBR materials — metallic, roughness, normal, emissive maps. Ideal for testing SceneView rendering.",
    tags: ["helmet", "sci-fi", "pbr", "sample", "metallic"],
  },
  "khronos-avocado": {
    id: "khronos-avocado",
    name: "Avocado",
    category: "item",
    source: "Khronos glTF Sample Models",
    sourceUrl: "https://github.com/KhronosGroup/glTF-Sample-Models",
    format: "GLB",
    license: "CC0 1.0",
    description: "Photorealistic avocado with PBR textures. Good collectible/food item for inventory systems.",
    tags: ["food", "item", "pbr", "collectible"],
  },
  "khronos-flight-helmet": {
    id: "khronos-flight-helmet",
    name: "Flight Helmet",
    category: "prop",
    source: "Khronos glTF Sample Models",
    sourceUrl: "https://github.com/KhronosGroup/glTF-Sample-Models",
    format: "GLB",
    license: "CC0 1.0",
    description: "Vintage flight helmet with goggles. Detailed PBR model suitable for aviation or steampunk themes.",
    tags: ["helmet", "aviation", "vintage", "pbr"],
  },
  "khronos-lantern": {
    id: "khronos-lantern",
    name: "Lantern",
    category: "item",
    source: "Khronos glTF Sample Models",
    sourceUrl: "https://github.com/KhronosGroup/glTF-Sample-Models",
    format: "GLB",
    license: "CC0 1.0",
    description: "Metallic lantern with emissive light. Great for dungeon/adventure game lighting props.",
    tags: ["lantern", "light", "dungeon", "emissive", "prop"],
  },
  "khronos-animated-cube": {
    id: "khronos-animated-cube",
    name: "Animated Cube",
    category: "effect",
    source: "Khronos glTF Sample Models",
    sourceUrl: "https://github.com/KhronosGroup/glTF-Sample-Models",
    format: "GLB",
    license: "CC0 1.0",
    description: "Simple animated rotating cube. Tests animation playback in SceneView.",
    tags: ["cube", "animation", "test", "geometry"],
  },
  "khronos-box-animated": {
    id: "khronos-box-animated",
    name: "Animated Box",
    category: "effect",
    source: "Khronos glTF Sample Models",
    sourceUrl: "https://github.com/KhronosGroup/glTF-Sample-Models",
    format: "GLB",
    license: "CC0 1.0",
    description: "Textured box with rotation animation. Useful as a loot crate or treasure chest placeholder.",
    tags: ["box", "animation", "loot", "crate"],
  },

  // ── Kenney (CC0 1.0 — Public Domain) ──────────────────────────────────────
  "kenney-character-robot": {
    id: "kenney-character-robot",
    name: "Robot Character",
    category: "character",
    source: "Kenney.nl",
    sourceUrl: "https://kenney.nl/assets/animated-characters",
    format: "GLB",
    license: "CC0 1.0 (Public Domain)",
    description: "Low-poly animated robot character with idle, walk, run, jump animations. Ready for game use.",
    tags: ["robot", "character", "animated", "low-poly"],
  },
  "kenney-character-adventurer": {
    id: "kenney-character-adventurer",
    name: "Adventurer Character",
    category: "character",
    source: "Kenney.nl",
    sourceUrl: "https://kenney.nl/assets/animated-characters-2",
    format: "GLB",
    license: "CC0 1.0 (Public Domain)",
    description: "Low-poly adventurer character with sword. Includes idle, walk, attack, die animations.",
    tags: ["adventurer", "character", "animated", "fantasy", "low-poly"],
  },
  "kenney-pirate-kit": {
    id: "kenney-pirate-kit",
    name: "Pirate Ship Kit",
    category: "vehicle",
    source: "Kenney.nl",
    sourceUrl: "https://kenney.nl/assets/pirate-kit",
    format: "GLB",
    license: "CC0 1.0 (Public Domain)",
    description: "Modular pirate ship parts — hull, mast, sail, cannon, wheel. Build custom ships.",
    tags: ["pirate", "ship", "vehicle", "modular", "low-poly"],
  },
  "kenney-tower-defense": {
    id: "kenney-tower-defense",
    name: "Tower Defense Kit",
    category: "building",
    source: "Kenney.nl",
    sourceUrl: "https://kenney.nl/assets/tower-defense-kit",
    format: "GLB",
    license: "CC0 1.0 (Public Domain)",
    description: "50+ tower defense pieces — towers, walls, gates, paths. Low-poly, game-ready.",
    tags: ["tower", "defense", "building", "modular", "strategy"],
  },
  "kenney-nature-kit": {
    id: "kenney-nature-kit",
    name: "Nature Kit",
    category: "environment",
    source: "Kenney.nl",
    sourceUrl: "https://kenney.nl/assets/nature-kit",
    format: "GLB",
    license: "CC0 1.0 (Public Domain)",
    description: "Trees, rocks, flowers, grass. 60+ nature elements for outdoor level design.",
    tags: ["nature", "trees", "rocks", "environment", "outdoor"],
  },
  "kenney-furniture-kit": {
    id: "kenney-furniture-kit",
    name: "Furniture Kit",
    category: "prop",
    source: "Kenney.nl",
    sourceUrl: "https://kenney.nl/assets/furniture-kit",
    format: "GLB",
    license: "CC0 1.0 (Public Domain)",
    description: "Interior furniture — chairs, tables, beds, shelves, lamps. Low-poly, ready for indoor scenes.",
    tags: ["furniture", "interior", "props", "indoor", "low-poly"],
  },
  "kenney-weapons-pack": {
    id: "kenney-weapons-pack",
    name: "Weapons Pack",
    category: "weapon",
    source: "Kenney.nl",
    sourceUrl: "https://kenney.nl/assets/weapon-pack",
    format: "GLB",
    license: "CC0 1.0 (Public Domain)",
    description: "Swords, axes, bows, staffs, shields — 30+ weapon models. Low-poly, game-ready.",
    tags: ["weapons", "sword", "axe", "bow", "shield", "fantasy"],
  },

  // ── Quaternius (CC0 1.0 — Public Domain) ──────────────────────────────────
  "quaternius-low-poly-animals": {
    id: "quaternius-low-poly-animals",
    name: "Low-Poly Animals Pack",
    category: "creature",
    source: "Quaternius",
    sourceUrl: "https://quaternius.com/packs/ultimateanimatedanimals.html",
    format: "GLB",
    license: "CC0 1.0 (Public Domain)",
    description: "40+ animated animals — wolf, bear, deer, rabbit, bird. Each with idle, walk, run, attack animations.",
    tags: ["animals", "creature", "animated", "low-poly", "nature"],
  },
  "quaternius-medieval-fantasy": {
    id: "quaternius-medieval-fantasy",
    name: "Medieval Fantasy Pack",
    category: "building",
    source: "Quaternius",
    sourceUrl: "https://quaternius.com/packs/medievalfantasy.html",
    format: "GLB",
    license: "CC0 1.0 (Public Domain)",
    description: "Medieval buildings — castle, tavern, blacksmith, church, houses. Complete village set.",
    tags: ["medieval", "fantasy", "buildings", "village", "rpg"],
  },
  "quaternius-space-kit": {
    id: "quaternius-space-kit",
    name: "Space Kit",
    category: "vehicle",
    source: "Quaternius",
    sourceUrl: "https://quaternius.com/packs/ultimatespacekit.html",
    format: "GLB",
    license: "CC0 1.0 (Public Domain)",
    description: "Spaceships, space stations, asteroids, planets. 60+ sci-fi models for space games.",
    tags: ["space", "spaceship", "sci-fi", "asteroids", "station"],
  },

  // ── Sketchfab (CC BY / CC0) ───────────────────────────────────────────────
  "sf-treasure-chest": {
    id: "sf-treasure-chest",
    name: "Treasure Chest (Animated)",
    category: "item",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/treasure-chest",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Animated treasure chest with open/close animation and gold coins inside. RPG essential.",
    tags: ["treasure", "chest", "animated", "rpg", "loot"],
  },
  "sf-dragon": {
    id: "sf-dragon",
    name: "Low-Poly Dragon",
    category: "creature",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/dragon",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Animated dragon with fly, idle, and attack animations. Low-poly style for mobile performance.",
    tags: ["dragon", "creature", "animated", "fantasy", "boss"],
  },
  "sf-crystal-gems": {
    id: "sf-crystal-gems",
    name: "Crystal Gems Set",
    category: "item",
    source: "Sketchfab (CC0)",
    sourceUrl: "https://sketchfab.com/tags/crystal",
    format: "GLB",
    license: "CC0 1.0",
    description: "Set of colorful crystal gems — ruby, emerald, sapphire, amethyst, diamond. Inventory collectibles.",
    tags: ["crystal", "gem", "collectible", "item", "shiny"],
  },
  "sf-campfire": {
    id: "sf-campfire",
    name: "Campfire (Animated)",
    category: "environment",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/campfire",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Animated campfire with flickering flames and emissive glow. Ambient particle effect.",
    tags: ["campfire", "fire", "environment", "animated", "outdoor"],
  },
  "sf-portal": {
    id: "sf-portal",
    name: "Magic Portal",
    category: "effect",
    source: "Sketchfab (CC BY)",
    sourceUrl: "https://sketchfab.com/tags/portal",
    format: "GLB",
    license: "CC BY 4.0",
    description: "Animated magic portal with swirling energy and emissive rings. Level transition effect.",
    tags: ["portal", "magic", "effect", "animated", "transition"],
  },
};

export const GAME_MODEL_IDS = Object.keys(GAME_MODELS);

export function listGameModels(category?: string, tag?: string): GameModel[] {
  let models = Object.values(GAME_MODELS);

  if (category) {
    models = models.filter((m) => m.category === category);
  }
  if (tag) {
    models = models.filter((m) => m.tags.includes(tag));
  }

  return models;
}

export function getGameModel(id: string): GameModel | undefined {
  return GAME_MODELS[id];
}

export function formatModelList(models: GameModel[]): string {
  if (models.length === 0) {
    return "No models found matching the specified filters.";
  }

  const header = `## Free Game-Ready 3D Models (${models.length})\n\n`;
  const rows = models
    .map(
      (m) =>
        `### \`${m.id}\`\n**${m.name}** — *${m.category}*\n${m.description}\n- **Source:** [${m.source}](${m.sourceUrl})\n- **Format:** ${m.format}\n- **License:** ${m.license}\n- **Tags:** ${m.tags.join(", ")}`
    )
    .join("\n\n---\n\n");

  return header + rows;
}

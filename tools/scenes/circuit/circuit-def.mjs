// Night circuit — the one description of the set, shared by the GLB builder
// (geometry + emissive materials) and the IBL builder (what the car body reflects).
// Units: metres. The car sits at the origin on the start grid and points +Z.

export const TRACK = { half: 7.5, zMin: -170, zMax: 170 };

// Emissive materials: colour (linear) and strength (1 = display white, > 1 blooms).
export const EMIT = {
  flood: { color: [1.0, 0.95, 0.86], strength: 26 },
  led: { color: [0.55, 0.82, 1.0], strength: 5 },
  ledWarm: { color: [1.0, 0.72, 0.4], strength: 4 },
  startRed: { color: [1.0, 0.03, 0.015], strength: 22 },
  city: { color: [1.0, 0.7, 0.42], strength: 6 },
  cityCool: { color: [0.6, 0.8, 1.0], strength: 6 },
  beacon: { color: [1.0, 0.05, 0.02], strength: 12 },
  // Textured: `tex` is the emissive image, `color` its average (what the environment map uses).
  garage: { tex: 'garage.jpg', color: [0.3, 0.2, 0.11], strength: 0.85 },
  seats: { tex: 'stand.png', color: [0.08, 0.08, 0.1], strength: 0.3 },
  window: { tex: 'windows.jpg', color: [0.3, 0.23, 0.15], strength: 1.5 },
  board: { tex: 'board.png', color: [0.1, 0.2, 0.42], strength: 2.6 },
  banner: { tex: 'board.png', color: [0.1, 0.2, 0.42], strength: 3.2 }
};
// The five columns of the start gantry are five materials, so the page can light them one
// after the other (setMaterial('Light startRed0'…)): same look as the shared entry.
for (let i = 0; i < 5; i++) EMIT['startRed' + i] = EMIT.startRed;

// Opaque materials: [r, g, b] linear, roughness, metallic.
export const MAT = {
  paint: { color: [0.62, 0.62, 0.6], rough: 0.3, metal: 0 },
  kerbRed: { color: [0.5, 0.012, 0.012], rough: 0.28, metal: 0 },
  kerbWhite: { color: [0.6, 0.6, 0.58], rough: 0.28, metal: 0 },
  runoff: { color: [0.012, 0.014, 0.016], rough: 0.55, metal: 0 },
  concrete: { color: [0.11, 0.115, 0.12], rough: 0.7, metal: 0 },
  steel: { color: [0.32, 0.33, 0.35], rough: 0.32, metal: 1 },
  truss: { color: [0.02, 0.022, 0.026], rough: 0.45, metal: 0.8 },
  building: { color: [0.012, 0.014, 0.018], rough: 0.5, metal: 0 },
  glass: { color: [0.01, 0.012, 0.016], rough: 0.06, metal: 0 },
  stand: { color: [0.02, 0.022, 0.03], rough: 0.7, metal: 0 }
};

/** Seeded PRNG (mulberry32): the set is the same on every build. */
export function rng(seed) {
  let a = seed >>> 0;
  return function () {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * Build the set as two lists:
 *   boxes:  { mat, c: [x,y,z], s: [sx,sy,sz] }            axis-aligned boxes
 *   panels: { emit, c: [x,y,z], u: [..], v: [..] }         emissive rectangles, half-axes u and v,
 *           facing cross(u, v); `uv: [u0, v0, u1, v1]` maps a textured one (u along `u`, v up);
 *           `ibl: false` keeps one out of the environment map.
 */
export function buildSet() {
  const boxes = [], panels = [];
  const box = (mat, c, s) => boxes.push({ mat, c, s });
  const panel = (emit, c, u, v, extra) => panels.push(Object.assign({ emit, c, u, v }, extra || {}));
  const H = TRACK.half;

  // ── Kerbs: red/white, both edges, around the grid ──
  for (let i = -40; i < 40; i++) {
    const z = i * 2 + 1, mat = (i & 1) ? 'kerbRed' : 'kerbWhite';
    box(mat, [-(H + 0.5), 0.02, z], [1, 0.05, 2]);
    box(mat, [H + 0.5, 0.02, z], [1, 0.05, 2]);
  }
  // Run-off strips outside the kerbs (dark, slightly glossy: wet paint).
  box('runoff', [-(H + 2.4), 0.0, 0], [2.8, 0.02, 340]);
  box('runoff', [H + 3.4, 0.0, 0], [4.8, 0.02, 340]);

  // ── Track paint: edge lines, start line, grid slots ──
  box('paint', [-(H - 0.25), 0.004, 0], [0.16, 0.008, 340]);
  box('paint', [H - 0.25, 0.004, 0], [0.16, 0.008, 340]);
  box('paint', [0, 0.004, 13.4], [2 * H - 0.8, 0.008, 0.5]);           // start line, under the gantry
  for (let k = 0; k < 9; k++) {                                         // staggered grid slots
    const z = 2.9 - k * 8, x = (k & 1) ? 3.4 : 0;
    box('paint', [x, 0.004, z], [2.6, 0.008, 0.14]);
    box('paint', [x - 1.3, 0.004, z - 0.45], [0.14, 0.008, 0.9]);
    box('paint', [x + 1.3, 0.004, z - 0.45], [0.14, 0.008, 0.9]);
  }

  // ── Left: pit wall carrying LED boards, a catch fence, then the pit building ──
  const wx = -(H + 3.9);
  box('concrete', [wx, 0.55, 0], [0.4, 1.1, 340]);
  // LED perimeter boards on the track face of the wall: one 7.2 m artwork, repeated.
  panel('board', [wx + 0.21, 0.56, 0], [0, 0, -165.6], [0, 0.4, 0], { uv: [0, 0, 46, 1] });
  panel('led', [wx + 0.21, 1.06, 0], [0, 0, -170], [0, 0.025, 0]);      // faces +X (the track)
  for (let i = -34; i <= 34; i++) box('truss', [wx, 1.95, i * 5], [0.07, 1.7, 0.07]);   // fence posts
  box('truss', [wx, 2.8, 0], [0.06, 0.06, 340]);
  box('truss', [wx, 2.0, 0], [0.04, 0.04, 340]);
  const bx = -(H + 13);                                                 // building front face
  box('building', [bx - 6, 5.5, -10], [12, 11, 230]);
  box('building', [bx - 6, 11.4, -10], [14, 0.8, 232]);                 // roof slab, overhanging
  const rb = rng(3);
  for (let i = -18; i <= 15; i++) {                                     // garages, one bay every 6.6 m
    const z = i * 6.6 - 10, bay = Math.floor(rb() * 4);
    panel('garage', [bx + 0.03, 1.85, z], [0, 0, -2.6], [0, 1.75, 0], { uv: [bay / 4, 0, (bay + 1) / 4, 1] });
    box('concrete', [bx + 0.15, 2.0, z + 3.3], [0.5, 4.0, 1.4]);        // pillar between two bays
    // Hospitality glazing above the bay.
    const w = Math.floor(rb() * 4);
    panel('window', [bx + 0.04, 7.6, z], [0, 0, -3.0], [0, 1.15, 0], { uv: [w / 4, 0, (w + 1) / 4, 1] });
  }
  box('truss', [bx + 0.2, 4.05, -10], [0.7, 0.9, 226]);                 // fascia over the garage doors
  panel('banner', [bx + 0.56, 4.05, -10], [0, 0, -108], [0, 0.3, 0], { uv: [0, 0, 36, 1] });
  panel('led', [bx + 0.5, 3.56, -10], [0, 0, -113], [0, 0.03, 0]);      // light line under the fascia
  box('truss', [bx + 0.1, 9.0, -10], [0.4, 0.5, 226]);                  // balcony rail over the glazing
  panel('ledWarm', [bx - 0.0 + 1.02, 10.95, -10], [0, 0, -116], [0, 0.06, 0]); // roof-edge light line

  // ── Right: steel barrier, grandstand under a lit roof ──
  const rx = H + 6.2;
  box('steel', [rx, 0.42, 0], [0.12, 0.3, 340]);
  box('steel', [rx, 0.82, 0], [0.12, 0.3, 340]);
  for (let i = -42; i <= 42; i++) box('truss', [rx + 0.12, 0.5, i * 4], [0.1, 1.0, 0.12]);
  const gx = H + 12;
  for (let s = 0; s < 9; s++) {
    box('stand', [gx + 1.2 + s * 1.5, 0.8 + s * 0.9, -6], [1.5, 1.6 + s * 1.8, 170]);
    // The riser of each tier, facing the track: seats, a row of them every 0.9 m.
    if (s > 0) panel('seats', [gx + 0.44 + s * 1.5, 1.6 + s * 1.8 - 0.9, -6], [0, 0, 85], [0, 0.9, 0], { uv: [s * 0.37, (s % 8) * 0.125, s * 0.37 + 5.3, (s % 8) * 0.125 + 0.125], ibl: false });
  }
  box('truss', [gx + 7, 13.2, -6], [17, 0.5, 172]);                     // roof
  for (let i = -8; i <= 8; i++) {                                       // roof floodlights, facing the track
    const z = i * 10 - 6;
    panel('flood', [gx - 1.6, 12.7, z], [0, 0, 1.3], [-0.28, -0.28, 0]);
    box('truss', [gx + 6, 6.6, z + 5], [0.35, 13.2, 0.35]);
  }
  panel('led', [gx + 0.42, 1.62, -6], [0, 0, 85], [0, 0.04, 0]);       // stand-front light line (faces -X)
  box('truss', [gx - 0.2, 0.62, -6], [0.3, 1.24, 170]);                 // board carrier in front of the stand
  panel('board', [gx - 0.36, 0.66, -6], [0, 0, 84], [0, 0.5, 0], { uv: [0, 0, 19, 1] });

  // ── Light masts, both sides ──
  const masts = [];
  for (const z of [-128, -92, -56, -20, 16, 52, 88, 124]) {
    masts.push([-(H + 11.5), z + 9, 1]); masts.push([H + 9.5, z - 9, -1]);
  }
  for (const [x, z, dir] of masts) {
    box('truss', [x, 11, z], [0.45, 22, 0.45]);
    box('truss', [x + dir * 0.9, 22.2, z], [2.6, 0.3, 3.4]);
    // Two rows of lamps, tilted down toward the track.
    for (let r = 0; r < 2; r++) {
      panel('flood', [x + dir * (1.2 + r * 0.5), 21.6 - r * 0.9, z], [0, 0, 1.5 * dir], [-0.24 * dir, -0.32, 0]);
    }
  }

  // ── Start gantry, ahead of the car: five red lights over the track ──
  const zg = 15;
  box('truss', [-(H + 1.6), 3.9, zg], [0.5, 7.8, 0.5]);
  box('truss', [H + 1.6, 3.9, zg], [0.5, 7.8, 0.5]);
  box('truss', [0, 7.6, zg], [2 * H + 3.7, 0.9, 0.7]);
  box('truss', [0, 6.75, zg], [9.2, 0.9, 0.45]);
  for (let i = -2; i <= 2; i++) {
    for (let r = 0; r < 2; r++) {
      panel('startRed' + (i + 2), [i * 1.7, 6.95 - r * 0.42, zg - 0.24], [-0.17, 0, 0], [0, 0.17, 0]); // faces -Z, toward the grid
    }
  }
  panel('banner', [0, 7.6, zg - 0.36], [-(H + 1.2), 0, 0], [0, 0.3, 0], { uv: [0, 0, 3, 1] });

  // ── Bridge behind the grid: closes the view down the straight ──
  const zb = -62;
  box('truss', [-(H + 2.2), 4.4, zb], [0.9, 8.8, 1.6]);
  box('truss', [H + 4.4, 4.4, zb], [0.9, 8.8, 1.6]);
  box('truss', [1, 8.4, zb], [2 * H + 8, 1.9, 2.2]);
  panel('banner', [1, 8.4, zb + 1.11], [H + 3.4, 0, 0], [0, 0.72, 0], { uv: [0, 0, 2, 1] });   // faces +Z, toward the car
  panel('ledWarm', [1, 7.4, zb + 1.11], [H + 3.6, 0, 0], [0, 0.05, 0]);

  // ── Far end: a control tower and a skyline of lit windows ──
  box('building', [-(H + 24), 17, 60], [9, 34, 9]);
  panel('window', [-(H + 19.48), 30, 60], [0, 0, -4], [0, 1.6, 0]);
  panel('beacon', [-(H + 24), 34.6, 60], [0.25, 0, 0], [0, 0.25, 0], { both: true });
  const r = rng(7);
  for (let i = 0; i < 46; i++) {
    const far = i < 23 ? -1 : 1;                                        // both ends of the straight
    const x = (r() - 0.5) * 520, z = far * (230 + r() * 140);
    const w = 10 + r() * 22, h = 14 + r() * 62, d = 10 + r() * 20;
    box('building', [x, h / 2, z], [w, h, d]);
    const rows = Math.floor(h / 5), cols = Math.max(2, Math.floor(w / 4));
    for (let a = 1; a < rows; a++) for (let b = 0; b < cols; b++) {
      if (r() > 0.34) continue;
      const px = x - w / 2 + (b + 0.5) * (w / cols), py = a * 5;
      panel(r() > 0.7 ? 'cityCool' : 'city', [px, py, z - far * (d / 2 + 0.05)], [0.9 * far, 0, 0], [0, 0.7, 0], { ibl: false });
    }
    if (h > 50) panel('beacon', [x, h + 0.6, z - far * (d / 2)], [0.5 * far, 0, 0], [0, 0.5, 0], { ibl: false });
  }
  return { boxes, panels };
}

/** Sky radiance (linear, display-relative) for a unit direction — blue hour, dusk glow toward -Z. */
export function sky(dx, dy, dz) {
  const el = Math.max(0, dy);
  const t = Math.pow(1 - el, 3.2);                       // 1 at the horizon, 0 at the zenith
  const zen = [0.006, 0.012, 0.034], hor = [0.03, 0.06, 0.13];
  const c = [zen[0] + (hor[0] - zen[0]) * t, zen[1] + (hor[1] - zen[1]) * t, zen[2] + (hor[2] - zen[2]) * t];
  // Dusk: a warm band low on the horizon, centred behind the car (-Z).
  const az = Math.atan2(dx, -dz);                        // 0 toward -Z
  const band = Math.exp(-(az * az) / (2 * 0.75 * 0.75)) * Math.exp(-el / 0.085);
  c[0] += 0.78 * band; c[1] += 0.25 * band; c[2] += 0.05 * band;
  const band2 = Math.exp(-(az * az) / (2 * 1.5 * 1.5)) * Math.exp(-el / 0.28);
  c[0] += 0.07 * band2; c[1] += 0.035 * band2; c[2] += 0.06 * band2;
  // Light haze over the venue.
  const haze = Math.exp(-el / 0.05) * 0.035;
  c[0] += haze; c[1] += haze * 0.95; c[2] += haze * 0.9;
  return c;
}

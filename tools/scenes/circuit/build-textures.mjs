// Procedural textures of the night-circuit set: wet asphalt (roughness + normal + base
// colour, tileable) and a plain tyre-sidewall normal map that replaces the one carrying
// third-party logos. Writes raw RGB and lets ffmpeg encode it.
//   node build-textures.mjs <outDir>
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { rng } from './circuit-def.mjs';

const out = process.argv[2];
fs.mkdirSync(out, { recursive: true });

function save(name, w, h, rgb, args) {
  const raw = path.join(out, name + '.rgb');
  fs.writeFileSync(raw, rgb);
  execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', w + 'x' + h, '-i', raw, ...args, path.join(out, name)]);
  fs.unlinkSync(raw);
}

/** Tileable value noise on a `px` x `py` lattice, smooth-interpolated. */
function makeNoise(px, py, seed) {
  const r = rng(seed), g = new Float32Array(px * py);
  for (let i = 0; i < g.length; i++) g[i] = r();
  const f = (t) => t * t * (3 - 2 * t);
  return function (x, y) {            // x, y in [0, 1)
    const fx = x * px, fy = y * py;
    const x0 = Math.floor(fx) % px, y0 = Math.floor(fy) % py;
    const x1 = (x0 + 1) % px, y1 = (y0 + 1) % py;
    const tx = f(fx - Math.floor(fx)), ty = f(fy - Math.floor(fy));
    const a = g[y0 * px + x0], b = g[y0 * px + x1], c = g[y1 * px + x0], d = g[y1 * px + x1];
    return a + (b - a) * tx + (c - a) * ty + (a - b - c + d) * tx * ty;
  };
}
function fbm(octaves, px, py, seed) {
  const layers = [];
  for (let o = 0; o < octaves; o++) layers.push(makeNoise(px << o, py << o, seed + o * 31));
  return function (x, y) {
    let v = 0, amp = 0.5, tot = 0;
    for (let o = 0; o < octaves; o++) { v += layers[o](x, y) * amp; tot += amp; amp *= 0.5; }
    return v / tot;
  };
}
const sstep = (a, b, x) => { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); };

// ── Wet asphalt ──
// Three scales, three textures:
//   asphalt_normal.jpg  512², one tile = 2 m: the aggregate grain (4 mm per texel)
//   asphalt_orm.jpg     256², one tile = 16 m: how damp each patch is (roughness)
//   asphalt_color.jpg   256², one tile = 16 m
//   puddles.png         512², one tile = 16 m, alpha = standing water, in long shallow ruts
//                       along the track; drawn as a second, mirror-flat layer just above.
{
  const N = 512, g1 = makeNoise(128, 128, 23), g2 = makeNoise(256, 256, 29), Hh = new Float32Array(N * N), nrm = Buffer.alloc(N * N * 3);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) Hh[y * N + x] = g1(x / N, y / N) * 0.6 + g2(x / N, y / N) * 0.4;
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const hx = Hh[y * N + (x + 1) % N] - Hh[y * N + (x + N - 1) % N];
    const hy = Hh[((y + 1) % N) * N + x] - Hh[((y + N - 1) % N) * N + x];
    let nx = -hx * 1.1, ny = -hy * 1.1, nz = 1;
    const l = Math.hypot(nx, ny, nz); nx /= l; ny /= l; nz /= l;
    const i = (y * N + x) * 3;
    nrm[i] = Math.round((nx * 0.5 + 0.5) * 255); nrm[i + 1] = Math.round((ny * 0.5 + 0.5) * 255); nrm[i + 2] = Math.round((nz * 0.5 + 0.5) * 255);
  }
  save('asphalt_normal.jpg', N, N, nrm, ['-q:v', '3', '-pix_fmt', 'yuvj444p']);

  const M = 256, damp = fbm(4, 7, 4, 5), patch = fbm(3, 24, 24, 9), orm = Buffer.alloc(M * M * 3), col = Buffer.alloc(M * M * 3);
  for (let y = 0; y < M; y++) for (let x = 0; x < M; x++) {
    const u = x / M, v = y / M, i = (y * M + x) * 3, d = damp(u, v), q = patch(u, v);
    const rough = 0.14 + d * 0.24 + q * 0.06;
    orm[i] = 255; orm[i + 1] = Math.round(rough * 255); orm[i + 2] = 0;
    const c = 0.27 + q * 0.06 - (1 - d) * 0.05;                         // sRGB: the wetter, the darker
    col[i] = Math.round(c * 255); col[i + 1] = Math.round(c * 255); col[i + 2] = Math.round(Math.min(1, c * 1.05) * 255);
  }
  save('asphalt_orm.jpg', M, M, orm, ['-q:v', '3']);
  save('asphalt_color.jpg', M, M, col, ['-q:v', '4']);

  // The car's grid box stays clear of standing water: a mirror next to a tyre shows the sky
  // where the car's own reflection belongs (no SSR on the default tiers), and the car floats.
  // The tile repeats every 16 m, so the box's distant copies are just dry patches.
  // uv = (x, z) / 16 on the water layer; the car's footprint is |x| < 1.12, |z - 0.25| < 2.17.
  const dryness = (u, v) => {
    const wx = ((u * 16 + 8) % 16 + 16) % 16 - 8, wz = ((v * 16 + 8) % 16 + 16) % 16 - 8;
    const dx = Math.max(0, Math.abs(wx) - 1.12), dz = Math.max(0, Math.abs(wz - 0.25) - 2.17);
    return sstep(1.5, 3.5, Math.hypot(dx, dz));
  };
  const P = 512, pud = fbm(4, 5, 2, 11), rgba = Buffer.alloc(P * P * 4);
  for (let y = 0; y < P; y++) for (let x = 0; x < P; x++) {
    const i = (y * P + x) * 4, a = sstep(0.5, 0.64, pud(x / P, y / P)) * dryness((x + 0.5) / P, (y + 0.5) / P);   // cut at 0.5 by the material
    rgba[i] = rgba[i + 1] = 20; rgba[i + 2] = 22; rgba[i + 3] = Math.round(a * 255);
  }
  const raw = path.join(out, 'puddles.rgba'); fs.writeFileSync(raw, rgba);
  execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgba', '-s', P + 'x' + P, '-i', raw, path.join(out, 'puddles.png')]);
  fs.unlinkSync(raw);
}

// ── Garage interiors, 4 bays side by side (256x128 each): emissive ──
{
  const W = 1024, H = 128, img = Buffer.alloc(W * H * 3), r = rng(17);
  for (let bay = 0; bay < 4; bay++) {
    const cab = []; let x = 0.06 + r() * 0.1;
    while (x < 0.9) { const w = 0.08 + r() * 0.16; if (r() > 0.3) cab.push([x, Math.min(0.95, x + w), 0.48 + r() * 0.2, r()]); x += w + 0.03 + r() * 0.12; }
    const gap = 0.2 + r() * 0.5, tint = r();
    for (let py = 0; py < H; py++) for (let px = 0; px < 256; px++) {
      const u = (px + 0.5) / 256, v = (py + 0.5) / H;                 // v: 0 at the lintel, 1 at the floor
      let L = 0.16 + 0.42 * Math.exp(-Math.max(0, v - 0.1) / 0.28);      // light falling from the ceiling
      let c = [1.0, 0.8, 0.58];
      if (v > 0.07 && v < 0.12 && Math.abs(((u * 4) % 1) - 0.5) < 0.4) { L = 1.6; c = [1, 0.95, 0.85]; }      // strip lights
      if (v > 0.26 && v < 0.38 && u > gap - 0.16 && u < gap + 0.16) { L = 0.6; c = tint > 0.5 ? [0.5, 0.75, 1] : [1, 0.42, 0.3]; } // team panel on the back wall
      if (v > 0.84) L = 0.2 + 0.2 * (1 - v) / 0.16;                     // floor
      for (const [a, b, top, hue] of cab) if (u > a && u < b && v > top && v < 0.86) { L = 0.05 + 0.04 * hue; c = hue > 0.5 ? [1, 0.3, 0.2] : [0.5, 0.6, 1]; }
      L *= 1 - 0.6 * Math.pow(Math.abs(2 * u - 1), 6);                  // side walls fall off
      const i = (py * W + bay * 256 + px) * 3;
      for (let k = 0; k < 3; k++) img[i + k] = Math.round(255 * Math.pow(Math.min(1, L * c[k] / 1.6), 1 / 2.2));
    }
  }
  save('garage.jpg', W, H, img, ['-q:v', '4']);
}

// ── Grandstand seating, 256x64: 8 rows of 64 seats under the roof lights. Empty seats are
//    dark blue shells; the crowd is muted cloth with a lighter head, a few bright jackets ──
{
  const W = 256, H = 64, img = Buffer.alloc(W * H * 3), r = rng(53);
  for (let py = 0; py < H; py += 8) for (let px = 0; px < W; px += 4) {
    const taken = r() > 0.38, hue = r(), L = taken ? 0.14 + r() * 0.3 : 0.05;
    const c = !taken ? [0.3, 0.42, 0.8] : hue < 0.1 ? [1, 0.3, 0.22] : hue < 0.2 ? [1, 0.8, 0.3] : hue < 0.55 ? [0.8, 0.78, 0.75] : [0.5, 0.58, 0.75];
    for (let y = 0; y < 8; y++) for (let x = 0; x < 4; x++) {
      const head = taken && y >= 1 && y < 3 && x >= 1 && x < 3;
      const k = head ? 1.5 : (x === 3 || y === 0 || y > 6) ? 0.2 : 1, i = ((py + y) * W + px + x) * 3;
      const cc = head ? [1, 0.82, 0.7] : c;
      for (let j = 0; j < 3; j++) img[i + j] = Math.round(255 * Math.pow(Math.min(1, L * k * cc[j]), 1 / 2.2));
    }
  }
  save('stand.png', W, H, img, []);
}

// ── Hospitality glazing, 4 variants side by side (256x128 each, 4 panes): emissive ──
{
  const W = 1024, H = 128, img = Buffer.alloc(W * H * 3), r = rng(29);
  for (let k = 0; k < 16; k++) {
    const lit = r(), L0 = lit < 0.3 ? 0.015 : lit < 0.55 ? 0.12 : lit < 0.85 ? 0.45 : 0.9, cool = r() > 0.75, blinds = r() > 0.6;
    for (let py = 0; py < H; py++) for (let px = 0; px < 64; px++) {
      const u = (px + 0.5) / 64, v = (py + 0.5) / H;
      let L = L0 * (0.75 + 0.25 * Math.exp(-v / 0.4));
      if (blinds && ((py >> 2) & 1)) L *= 0.55;
      if (u < 0.05 || u > 0.95 || v < 0.05 || v > 0.95) L = 0;          // mullions
      const c = cool ? [0.7, 0.85, 1] : [1, 0.8, 0.56], i = (py * W + k * 64 + px) * 3;
      for (let j = 0; j < 3; j++) img[i + j] = Math.round(255 * Math.pow(Math.min(1, L * c[j]), 1 / 2.2));
    }
  }
  save('windows.jpg', W, H, img, ['-q:v', '4']);
}

// ── Tyre sidewall normal map, 512²: concentric ribs, no lettering ──
{
  const N = 512, nrm = Buffer.alloc(N * N * 3);
  const ribs = [[0.47, 0.012, 1], [0.5, 0.008, -1], [0.62, 0.01, 1], [0.9, 0.012, 1], [0.95, 0.008, -1]];
  const height = (r) => ribs.reduce((h, [c, w, s]) => h + s * Math.exp(-((r - c) * (r - c)) / (2 * w * w)), 0);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const px = (x + 0.5) / N * 2 - 1, py = (y + 0.5) / N * 2 - 1, r = Math.hypot(px, py) || 1e-6;
    const dh = (height(r + 0.002) - height(r - 0.002)) / 0.004 * 0.012;
    let nx = -dh * px / r, ny = -dh * py / r, nz = 1;
    const l = Math.hypot(nx, ny, nz); nx /= l; ny /= l; nz /= l;
    const i = (y * N + x) * 3;
    nrm[i] = Math.round((nx * 0.5 + 0.5) * 255); nrm[i + 1] = Math.round((ny * 0.5 + 0.5) * 255); nrm[i + 2] = Math.round((nz * 0.5 + 0.5) * 255);
  }
  save('tire_normal.png', N, N, nrm, []);
}

// ── Tyre sidewall base colour: plain rubber ──
save('tire_color.png', 8, 8, Buffer.alloc(8 * 8 * 3, 30), []);

// fade.png: 4 x 64 white, alpha 1 at the top row (uv v = 0) to 0 at the bottom. The rain
// streaks and the headlight beams fade along it — the glTF ubershader ignores vertex alpha.
{
  const W = 4, H = 64, img = Buffer.alloc(W * H * 4, 255);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) img[(y * W + x) * 4 + 3] = Math.round(255 * Math.pow(1 - y / (H - 1), 1.6));
  const raw = path.join(out, 'fade.rgba'); fs.writeFileSync(raw, img);
  execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgba', '-s', W + 'x' + H, '-i', raw, path.join(out, 'fade.png')]);
  fs.unlinkSync(raw);
}
console.log(fs.readdirSync(out).map((f) => f + ' ' + fs.statSync(path.join(out, f)).size).join('\n'));

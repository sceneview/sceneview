// Studio showroom — environment maps shared by the website viewer and the Android model
// viewer. A synthetic photo studio, rendered from the subject's position into an equirect
// Radiance .hdr: a neutral room, a large overhead softbox, two long strip softboxes along
// the sides, four tall strips at the corners, and a bright cove where the walls meet the
// floor. On car paint and glass this gives long clean highlight lines and one horizon line,
// with no sky, no trees and no windows.
//
// Two variants share the same lights and differ only in the room around them:
//   dark  — a charcoal showroom: dark walls, so paint reads deep and the strips read crisp.
//   light — a white photo studio: grey-white walls and floor, so the subject reads airy.
//
// Deterministic (no randomness, no input files): the same command always writes the same
// bytes, so the asset is reproducible from this file alone. First-party, CC0-1.0.
//
//   node build-hdr.mjs <out.hdr> [dark|light] [width=2048]
//   cmgen --quiet --format=ktx --size=256 --deploy=<dir> <out.hdr>   # web: <dir>/<name>_ibl.ktx
//
// The room is symmetric under x -> -x and z -> -z, so a loader that mirrors or turns the
// map half a turn (equirect conventions differ between tools) lights the subject the same.
// Direction convention: Filament's (cmgen): u = 0.5 looks down +Z, az = (2u - 1) * PI,
// el = (1 - 2v) * PI / 2, d = [sin(az) cos(el), sin(el), cos(az) cos(el)].
import fs from 'node:fs';

const out = process.argv[2];
const THEME = process.argv[3] || 'dark';
const W = parseInt(process.argv[4] || '2048', 10), H = W / 2;
if (!out || !['dark', 'light'].includes(THEME) || !(W >= 64 && W <= 8192 && W % 2 === 0)) {
  console.error('usage: node build-hdr.mjs <out.hdr> [dark|light] [width]');
  process.exit(2);
}

// ── The room, in metres, from the subject's centre (the eye) ────────────────────────────
const FLOOR = -0.6;          // floor plane, 60 cm under the eye: a car's centre
const WALL = 7.0;            // radius of the cylindrical cyclorama wall
const CEILING = 4.2;         // flat ceiling above the softbox
const NEUTRAL = [1, 1, 1];   // every light is the same neutral white: no colour cast

// Radiance (linear, arbitrary unit: the viewer scales it with the IBL intensity).
const LIGHTS = {
  overhead: 8.0,             // main softbox
  strip: 5.0,                // side strips
  corner: 2.0,               // corner strips
};
const ROOMS = {
  dark: {
    wall: 0.035,             // charcoal walls
    ceiling: 0.028,
    cove: 0.30,              // extra brightness where the wall meets the floor
    coveFalloff: 0.8,        // metres of wall height over which the cove fades by e
    floor: 0.055,
    pool: 0.11,              // floor lit by the overhead softbox, around the subject
    poolRadius: 1.9,         // metres (gaussian sigma)
  },
  light: {
    wall: 0.30,              // grey-white cyclorama
    ceiling: 0.20,
    cove: 0.40,
    coveFalloff: 0.8,
    floor: 0.40,
    pool: 0.35,
    poolRadius: 1.9,
  },
};
const R = { ...LIGHTS, ...ROOMS[THEME] };

// Softboxes are rectangles: centre c, half-extent vectors u and v. Each is opaque, uniform
// inside, and darkens over the last few centimetres of its diffuser, like a real one.
const rects = [];
function softbox(c, u, v, rad, edge) {
  rects.push({ c, u, v, rad, edge });
}
// Overhead: 2 m x 3.6 m, 3.8 m above the floor, long side along z.
softbox([0, 3.2, 0], [1.0, 0, 0], [0, 0, 1.8], R.overhead, 0.06);
// Side strips: 6 m long, 45 cm tall, high on each side (19-26 degrees up).
for (const sx of [-1, 1]) softbox([sx * 3.4, 1.4, 0], [0, 0, 3.0], [0, 0.225, 0], R.strip, 0.04);
// Corner strips: 40 cm wide, from 10 cm to 2.2 m above the floor, facing the subject.
for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
  const c = [sx * 2.9, 0.55, sz * 3.3];
  const h = Math.hypot(c[0], c[2]);
  const u = [-sz * 3.3 / h * 0.2, 0, sx * 2.9 / h * 0.2];     // horizontal, across the line of sight
  softbox(c, u, [0, 1.05, 0], R.corner, 0.03);
}

const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const smooth = (e0, e1, x) => { const t = Math.min(1, Math.max(0, (x - e0) / (e1 - e0))); return t * t * (3 - 2 * t); };
for (const r of rects) {
  r.n = cross(r.u, r.v);
  r.lu = Math.hypot(...r.u); r.lv = Math.hypot(...r.v);
  r.d0 = dot(r.n, r.c);
}

// Radiance seen along the unit direction d.
function radiance(d) {
  let best = Infinity, rad = 0;
  // The room's shell first: floor, cylindrical wall or ceiling, whichever is nearest.
  const rh = Math.hypot(d[0], d[2]);
  const tWall = rh > 1e-9 ? WALL / rh : Infinity;
  const tFloor = d[1] < 0 ? FLOOR / d[1] : Infinity;
  const tCeil = d[1] > 0 ? CEILING / d[1] : Infinity;
  if (tFloor <= tWall && tFloor <= tCeil) {
    best = tFloor;
    const x = d[0] * tFloor, z = d[2] * tFloor;
    rad = R.floor + R.pool * Math.exp(-(x * x + z * z) / (2 * R.poolRadius * R.poolRadius));
  } else if (tWall <= tCeil) {
    best = tWall;
    const h = d[1] * tWall - FLOOR;                     // height above the floor
    rad = R.wall + R.cove * Math.exp(-h / R.coveFalloff);
  } else {
    best = tCeil;
    rad = R.ceiling;
  }
  // Then the softboxes hanging inside it.
  for (const r of rects) {
    const dn = dot(r.n, d);
    if (Math.abs(dn) < 1e-9) continue;
    const t = r.d0 / dn;
    if (t <= 0 || t >= best) continue;
    const h = [d[0] * t - r.c[0], d[1] * t - r.c[1], d[2] * t - r.c[2]];
    const a = Math.abs(dot(h, r.u)) / r.lu, b = Math.abs(dot(h, r.v)) / r.lv;   // metres from centre
    if (a > r.lu || b > r.lv) continue;
    best = t;
    const w = smooth(0, r.edge, r.lu - a) * smooth(0, r.edge, r.lv - b);
    rad = r.rad * (0.35 + 0.65 * w);
  }
  return rad;
}

// 3x3 supersampling: the softbox edges stay clean lines instead of stair steps.
const S = 3;
const data = new Float32Array(W * H);
for (let y = 0; y < H; y++) {
  for (let x = 0; x < W; x++) {
    let sum = 0;
    for (let sy = 0; sy < S; sy++) {
      for (let sx = 0; sx < S; sx++) {
        const u = (x + (sx + 0.5) / S) / W, v = (y + (sy + 0.5) / S) / H;
        const az = (2 * u - 1) * Math.PI, el = (1 - 2 * v) * Math.PI / 2;
        sum += radiance([Math.sin(az) * Math.cos(el), Math.sin(el), Math.cos(az) * Math.cos(el)]);
      }
    }
    data[y * W + x] = sum / (S * S);
  }
}

// Radiance RGBE with new-style run-length scanlines: most of the room is flat, so runs
// keep the file small.
function rgbe(value) {
  const r = value * NEUTRAL[0], g = value * NEUTRAL[1], b = value * NEUTRAL[2];
  const m = Math.max(r, g, b);
  if (m <= 1e-32) return [0, 0, 0, 0];
  const e = Math.ceil(Math.log2(m) + 1e-9), s = 256 / Math.pow(2, e);
  return [Math.min(255, Math.floor(r * s)), Math.min(255, Math.floor(g * s)), Math.min(255, Math.floor(b * s)), e + 128];
}
function encodeChannel(bytes, outBytes) {
  const n = bytes.length;
  let i = 0;
  while (i < n) {
    let run = 1;
    while (i + run < n && run < 127 && bytes[i + run] === bytes[i]) run++;
    if (run >= 4) { outBytes.push(128 + run, bytes[i]); i += run; continue; }
    let j = i;
    while (j < n && j - i < 128) {
      let r = 1;
      while (j + r < n && r < 4 && bytes[j + r] === bytes[j]) r++;
      if (r >= 4) break;
      j++;
    }
    outBytes.push(j - i);
    for (let k = i; k < j; k++) outBytes.push(bytes[k]);
    i = j;
  }
}
const head = Buffer.from('#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y ' + H + ' +X ' + W + '\n', 'ascii');
const rows = [];
for (let y = 0; y < H; y++) {
  const ch = [new Uint8Array(W), new Uint8Array(W), new Uint8Array(W), new Uint8Array(W)];
  for (let x = 0; x < W; x++) {
    const p = rgbe(data[y * W + x]);
    ch[0][x] = p[0]; ch[1][x] = p[1]; ch[2][x] = p[2]; ch[3][x] = p[3];
  }
  const row = [2, 2, W >> 8, W & 255];
  for (let c = 0; c < 4; c++) encodeChannel(ch[c], row);
  rows.push(Buffer.from(row));
}
fs.writeFileSync(out, Buffer.concat([head, ...rows]));
let peak = 0, mean = 0;
for (let i = 0; i < data.length; i++) { peak = Math.max(peak, data[i]); mean += data[i]; }
console.log(out, THEME, fs.statSync(out).size, 'bytes,', W + 'x' + H + ', peak', peak.toFixed(2), 'mean', (mean / data.length).toFixed(4));

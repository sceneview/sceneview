// Night circuit — environment map. Renders what the car "sees" from the grid (the sky,
// the floodlights, the LED lines, the dark masses of the buildings) into an equirect
// Radiance .hdr, from the same set description as the GLB. `cmgen` turns it into the KTX.
//   node build-ibl.mjs <out.hdr> [width]
import fs from 'node:fs';
import { buildSet, EMIT, MAT, sky } from './circuit-def.mjs';

const out = process.argv[2], W = parseInt(process.argv[3] || '1024', 10), H = W / 2;
const set = buildSet();
const eye = [0, 0.9, 0.3];

// Rectangles to hit: emissive panels, and the faces of the large boxes as dark occluders.
const rects = [];
for (const p of set.panels) {
  if (p.ibl === false) continue;
  const e = EMIT[p.emit];
  // A light line a few centimetres thin is less than a texel of the cube map: draw it
  // 20 cm thick at half the radiance, so it still streaks along the body.
  let v = p.v, k = 1; const t = Math.hypot(...p.v), l = Math.hypot(...p.u);
  if (t < 0.2 && l > 5) { v = p.v.map((x) => x * 0.2 / t); k = 0.5; }
  rects.push({ c: p.c, u: p.u, v, rad: e.color.map((x) => x * e.strength * k) });
}
for (const b of set.boxes) {
  if (b.s[1] < 3 || b.s[0] * b.s[1] * b.s[2] < 40) continue;        // only masses that hide the sky
  const m = MAT[b.mat], rad = m.color.map((x) => x * 0.25);
  const [cx, cy, cz] = b.c, [sx, sy, sz] = b.s;
  rects.push({ c: [cx - sx / 2, cy, cz], u: [0, 0, sz / 2], v: [0, sy / 2, 0], rad });
  rects.push({ c: [cx + sx / 2, cy, cz], u: [0, 0, sz / 2], v: [0, sy / 2, 0], rad });
  rects.push({ c: [cx, cy, cz - sz / 2], u: [sx / 2, 0, 0], v: [0, sy / 2, 0], rad });
  rects.push({ c: [cx, cy, cz + sz / 2], u: [sx / 2, 0, 0], v: [0, sy / 2, 0], rad });
  rects.push({ c: [cx, cy - sy / 2, cz], u: [sx / 2, 0, 0], v: [0, 0, sz / 2], rad });
}
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
for (const r of rects) {
  r.n = cross(r.u, r.v); r.uu = dot(r.u, r.u); r.vv = dot(r.v, r.v);
  r.d0 = dot(r.n, [r.c[0] - eye[0], r.c[1] - eye[1], r.c[2] - eye[2]]);
}
function trace(d) {
  let best = Infinity, rad = null;
  for (const r of rects) {
    const dn = dot(r.n, d); if (Math.abs(dn) < 1e-9) continue;
    const t = r.d0 / dn; if (t <= 0.05 || t >= best) continue;
    const h = [eye[0] + d[0] * t - r.c[0], eye[1] + d[1] * t - r.c[1], eye[2] + d[2] * t - r.c[2]];
    if (Math.abs(dot(h, r.u)) > r.uu || Math.abs(dot(h, r.v)) > r.vv) continue;
    best = t; rad = r.rad;
  }
  if (d[1] < 0) {                                                     // the wet ground, in front of anything farther
    const tg = -eye[1] / d[1];
    if (tg < best) {
      // A dark mirror: a faint echo of what stands above it.
      const up = trace.depth ? null : (trace.depth = 1, trace([d[0], -d[1], d[2]]));
      trace.depth = 0;
      const k = 0.05 + 0.25 * Math.pow(1 + d[1], 5);
      return up ? [0.004 + up[0] * k, 0.004 + up[1] * k, 0.005 + up[2] * k] : [0.004, 0.004, 0.005];
    }
  }
  return rad || sky(d[0], Math.max(0, d[1]), d[2]);
}

// 2x2 supersampling keeps the small lamps from flickering between builds.
const data = new Float32Array(W * H * 3);
for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
  let r = 0, g = 0, b = 0;
  for (let sy = 0; sy < 2; sy++) for (let sx = 0; sx < 2; sx++) {
    const u = (x + 0.25 + sx * 0.5) / W, v = (y + 0.25 + sy * 0.5) / H;
    const az = (2 * u - 1) * Math.PI, el = (1 - 2 * v) * Math.PI / 2;
    const c = trace([Math.sin(az) * Math.cos(el), Math.sin(el), Math.cos(az) * Math.cos(el)]);
    r += c[0]; g += c[1]; b += c[2];
  }
  const i = (y * W + x) * 3; data[i] = r / 4; data[i + 1] = g / 4; data[i + 2] = b / 4;
}

// Radiance RGBE, new-style scanlines written as literal runs (no compression needed).
const head = Buffer.from('#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y ' + H + ' +X ' + W + '\n', 'ascii');
const rows = [];
for (let y = 0; y < H; y++) {
  const rgbe = new Uint8Array(W * 4);
  for (let x = 0; x < W; x++) {
    const i = (y * W + x) * 3, r = data[i], g = data[i + 1], b = data[i + 2], m = Math.max(r, g, b);
    if (m > 1e-32) {
      const e = Math.ceil(Math.log2(m) + 1e-9), s = 256 / Math.pow(2, e);
      rgbe[x * 4] = Math.min(255, Math.floor(r * s)); rgbe[x * 4 + 1] = Math.min(255, Math.floor(g * s));
      rgbe[x * 4 + 2] = Math.min(255, Math.floor(b * s)); rgbe[x * 4 + 3] = e + 128;
    }
  }
  const row = [2, 2, W >> 8, W & 255];
  for (let ch = 0; ch < 4; ch++) for (let x = 0; x < W; x += 128) {
    const n = Math.min(128, W - x); row.push(n);
    for (let k = 0; k < n; k++) row.push(rgbe[(x + k) * 4 + ch]);
  }
  rows.push(Buffer.from(row));
}
fs.writeFileSync(out, Buffer.concat([head, ...rows]));
console.log(out, fs.statSync(out).size, 'bytes,', rects.length, 'rects');

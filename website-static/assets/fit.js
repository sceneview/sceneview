/* SceneView site — numeric camera fit, shared by the page (live 3D) and by
 * poster.html (the still the page shows first), so the poster and the live
 * render frame the model identically.
 *
 * Camera model = sceneview.js orbit: eye = target + (sin a·r, e·r, cos a·r),
 * look-at target, vertical FOV 32°. For every angle the model can turn to
 * (24 samples × 8 bounding-box corners) the fit guarantees:
 *   - the lowest corner sits on `bottom` (NDC) — the frame's bottom edge is
 *     the floor line, where the CSS contact shadow and reflection start;
 *   - |x| ≤ fill and the highest corner ≤ top — nothing is ever clipped.
 * It returns the smallest radius that satisfies both, plus the contact-shadow
 * ellipse as fractions of the frame (CSS --sx --sy --sw --sh). */
(function (root) {
  'use strict';
  var UP = [0, 1, 0];
  function sub(a, b) { return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]; }
  function dot(a, b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
  function cross(a, b) { return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]; }
  function norm(a) { var l = Math.hypot(a[0], a[1], a[2]) || 1; return [a[0] / l, a[1] / l, a[2] / l]; }

  function camera(t, r, e, ang) {
    var eye = [t[0] + Math.sin(ang) * r, t[1] + e * r, t[2] + Math.cos(ang) * r];
    var f = norm(sub(t, eye)), rt = norm(cross(f, UP));
    return { eye: eye, f: f, rt: rt, up: cross(rt, f) };
  }
  function project(cam, p, aspect, tanH) {
    var d = sub(p, cam.eye), z = dot(d, cam.f);
    if (z <= 1e-4) return null;
    return [dot(d, cam.rt) / (z * tanH * aspect), dot(d, cam.up) / (z * tanH)];
  }
  // The hull the fit keeps in frame. 'box': the 8 bounding-box corners, exact
  // for boxy models (a chair). 'ellipse': two rings inscribed in the box at its
  // bottom and top, closer to rounded models (the car sits on a draped cloth),
  // whose box corners lie far outside the mesh and would leave the model
  // floating above the floor line.
  function hullPoints(b, kind) {
    var out = [];
    if (kind === 'ellipse') {
      var cx = (b.min[0] + b.max[0]) / 2, cz = (b.min[2] + b.max[2]) / 2;
      var rx = (b.max[0] - b.min[0]) / 2, rz = (b.max[2] - b.min[2]) / 2;
      for (var k = 0; k < 24; k++) {
        var a = k * Math.PI / 12, x = cx + Math.cos(a) * rx, z = cz + Math.sin(a) * rz;
        out.push([x, b.min[1], z], [x, b.max[1], z]);
      }
      return out;
    }
    for (var i = 0; i < 8; i++) out.push([(i & 1 ? b.max : b.min)[0], (i & 2 ? b.max : b.min)[1], (i & 4 ? b.max : b.min)[2]]);
    return out;
  }

  function fit(bbox, o) {   // o: aspect, elev, angle, fill, top, bottom, hull, samples, fov
    o = o || {};
    var aspect = o.aspect || 1.25, e = o.elev == null ? 0.25 : o.elev;
    var fill = o.fill || 0.9, top = o.top == null ? 0.8 : o.top, bottom = o.bottom == null ? -0.985 : o.bottom;
    var angle = o.angle || 0, N = o.samples || 24;
    var tanH = Math.tan((o.fov || 32) * Math.PI / 360);
    var b = { min: [].slice.call(bbox.min), max: [].slice.call(bbox.max) };
    var cx = (b.min[0] + b.max[0]) / 2, cz = (b.min[2] + b.max[2]) / 2;
    var ymin = b.min[1], ymax = b.max[1];
    var size = sub(b.max, b.min), diag = Math.hypot(size[0], size[1], size[2]) || 1;
    var pts = hullPoints(b, o.hull);

    function extent(ty, r) {
      var minY = Infinity, maxY = -Infinity, maxX = 0;
      for (var k = 0; k < N; k++) {
        var cam = camera([cx, ty, cz], r, e, angle + k * 2 * Math.PI / N);
        for (var i = 0; i < pts.length; i++) {
          var p = project(cam, pts[i], aspect, tanH);
          if (!p) return null;
          if (p[1] < minY) minY = p[1];
          if (p[1] > maxY) maxY = p[1];
          if (Math.abs(p[0]) > maxX) maxX = Math.abs(p[0]);
        }
      }
      return { minY: minY, maxY: maxY, maxX: maxX };
    }
    // Raising the target raises the whole camera, so the model moves down:
    // minY decreases monotonically with ty.
    function solveTy(r) {
      var lo = ymin - 3 * diag, hi = ymax + 3 * diag;
      for (var i = 0; i < 48; i++) {
        var mid = (lo + hi) / 2, x = extent(mid, r);
        if (!x || x.minY < bottom) hi = mid; else lo = mid;
      }
      return (lo + hi) / 2;
    }
    function ok(r) {
      var ty = solveTy(r), x = extent(ty, r);
      return x && x.maxX <= fill && x.maxY <= top ? ty : null;
    }
    var rlo = diag * 0.55, rhi = diag * 40;
    for (var i = 0; i < 48; i++) { var mid = (rlo + rhi) / 2; if (ok(mid) != null) rhi = mid; else rlo = mid; }
    var r = rhi, ty = solveTy(r);

    // Contact shadow: a disc on the floor under the model, angle-independent,
    // projected at the starting angle.
    var cam = camera([cx, ty, cz], r, e, angle);
    var B = [cx, ymin, cz], R = 0.5 * (size[0] + size[2]) / 2 * 0.92;
    var rh = norm([cam.rt[0], 0, cam.rt[2]]), fh = norm([cam.f[0], 0, cam.f[2]]);
    function at(dx, dz) { return project(cam, [B[0] + rh[0] * dx + fh[0] * dz, B[1], B[2] + rh[2] * dx + fh[2] * dz], aspect, tanH); }
    var c = at(0, 0), L = at(-R, 0), Rt = at(R, 0), nr = at(0, -R), fr = at(0, R);
    var frac = function (p) { return [(p[0] + 1) / 2, (1 - p[1]) / 2]; };
    var C = frac(c);
    var shadow = {
      x: C[0], y: C[1],
      w: Math.abs(frac(Rt)[0] - frac(L)[0]),
      h: Math.abs(frac(nr)[1] - frac(fr)[1])
    };
    return { target: [cx, ty, cz], radius: r, height: ty + e * r, elev: e, angle: angle, contact: B, shadow: shadow, extent: extent(ty, r) };
  }

  root.SVFit = { fit: fit };
})(window);

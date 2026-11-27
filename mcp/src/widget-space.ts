/**
 * Dependency-free isometric Canvas 2D preview. Every vertex uses metres, including
 * the 1.75 m person and the explicitly labelled 0.75 m reference table. No GLB,
 * WebGL context or WASM is needed to explain a box and its dimensions.
 * Embedded only in the show_in_space resource; the host bridge and QR are shared.
 */
export const SPACE_DRAWING_JS = `
      var spaceData = null;
      function drawSpace(data) {
        spaceData = data;
        var ctx = canvas.getContext("2d");
        if (!ctx) throw new Error("This host cannot draw the size preview. Use the phone link below.");
        var width = Math.max(1, canvas.clientWidth), height = Math.max(1, canvas.clientHeight);
        // Keep dimensions and table legs left of the desktop AR card. The canvas
        // retains its full height; on a phone the action is below the drawing.
        var drawingWidth = width - (arEl.classList.contains("wide") ? 216 : 0);
        var ratio = Math.min(window.devicePixelRatio || 1, 2);
        canvas.width = Math.round(width * ratio); canvas.height = Math.round(height * ratio);
        ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
        ctx.clearRect(0, 0, width, height);

        // sizeCm is always width, height, depth. Preserve proportions at every size.
        var w = data.sizeCm[0] / 100, h = data.sizeCm[1] / 100, d = data.sizeCm[2] / 100;
        var base = data.placement === "table" ? 0.75 : 0;
        // Move towards the viewer as we move left, so the person's feet share
        // the box's front floor line (x + z = d), rather than receding behind it.
        var humanX = -0.85, humanZ = d - humanX;
        function humanPoint(x, y) { return [humanX + x / Math.SQRT2, y, humanZ - x / Math.SQRT2]; }
        function iso(x, y, z) { return [0.866 * (x - z), 0.4 * (x + z) - y]; }
        var anchors = [iso.apply(null, humanPoint(-0.35, 0)), iso.apply(null, humanPoint(0.35, 1.96))];
        // Fit the actual objects and floor, not an empty cuboid above the whole
        // floor: that cuboid made both the box and the person unnecessarily small.
        [0, w + 0.45].forEach(function (x) {
          [base - 0.25, base + h + 0.25].forEach(function (y) {
            [-0.3, d + 0.45].forEach(function (z) { anchors.push(iso(x, y, z)); });
          });
        });
        var floor = [[humanX-0.3,0,-0.3],[w+0.4,0,-0.3],[w+0.4,0,d+0.4],
          [0,0,d+0.4],[humanX-0.3,0,humanZ+0.3]];
        floor.forEach(function (v) { anchors.push(iso.apply(null, v)); });
        var minX = Math.min.apply(null, anchors.map(function (p) { return p[0]; }));
        var maxX = Math.max.apply(null, anchors.map(function (p) { return p[0]; }));
        var minY = Math.min.apply(null, anchors.map(function (p) { return p[1]; }));
        var maxY = Math.max.apply(null, anchors.map(function (p) { return p[1]; }));
        var scale = Math.min(Math.max(1, drawingWidth - 56) / (maxX - minX), Math.max(1, height - 36) / (maxY - minY));
        function point(x, y, z) {
          var p = iso(x, y, z);
          return [drawingWidth / 2 + (p[0] - (minX + maxX) / 2) * scale,
            height / 2 + (p[1] - (minY + maxY) / 2) * scale];
        }
        function path(vertices, fill, stroke, closed) {
          ctx.beginPath();
          vertices.forEach(function (v, i) {
            var p = point(v[0], v[1], v[2]);
            if (i) ctx.lineTo(p[0], p[1]); else ctx.moveTo(p[0], p[1]);
          });
          if (closed !== false) ctx.closePath();
          if (fill) { ctx.fillStyle = fill; ctx.fill(); }
          if (stroke) { ctx.strokeStyle = stroke; ctx.lineWidth = 1; ctx.stroke(); }
        }
        function label(text, p, color) {
          ctx.font = "500 12px system-ui, sans-serif";
          ctx.textAlign = "center"; ctx.textBaseline = "middle";
          var tw = ctx.measureText(text).width + 14;
          // Keep labels readable even on a narrow phone or for very small boxes.
          p[0] = Math.max(tw / 2 + 3, Math.min(width - tw / 2 - 3, p[0]));
          p[1] = Math.max(12, Math.min(height - 12, p[1]));
          ctx.fillStyle = "#0b0f16"; ctx.fillRect(p[0] - tw / 2, p[1] - 10, tw, 20);
          ctx.fillStyle = color || "#c9d9ff"; ctx.fillText(text, p[0], p[1]);
        }
        function dimension(a, b, text, offsetX, offsetY) {
          var p = point.apply(null, a), q = point.apply(null, b);
          var length = Math.hypot(q[0] - p[0], q[1] - p[1]) || 1;
          var nx = -(q[1] - p[1]) / length * 4, ny = (q[0] - p[0]) / length * 4;
          ctx.beginPath(); ctx.moveTo(p[0], p[1]); ctx.lineTo(q[0], q[1]);
          [p, q].forEach(function (v) { ctx.moveTo(v[0] - nx, v[1] - ny); ctx.lineTo(v[0] + nx, v[1] + ny); });
          ctx.strokeStyle = "#adc6ff"; ctx.lineWidth = 1; ctx.stroke();
          label(text, [(p[0] + q[0]) / 2 + offsetX, (p[1] + q[1]) / 2 + offsetY]);
        }

        // Ground plane and quiet grid, shared by the person and the reference table.
        path(floor, "#151d2b", "#2c384d");
        for (var gx = 0; gx <= w; gx += Math.max(0.25, w / 12))
          path([[gx,0,-0.3],[gx,0,d+0.4]], null, "#202c3f", false);
        for (var gz = 0; gz <= d; gz += Math.max(0.25, d / 12))
          path([[humanX-0.3,0,gz],[w+0.4,0,gz]], null, "#202c3f", false);
        // A shared contact line makes the zero-height plane explicit.
        path([humanPoint(-0.3,0),[0,0,d]], null, "#64748b", false);
        if (base) {
          [[-0.12,-0.12],[w+0.12,-0.12],[-0.12,d+0.12],[w+0.12,d+0.12]].forEach(function (v) {
            path([[v[0],0,v[1]],[v[0],base,v[1]]], null, "#64748b", false);
          });
          path([[-0.16,base,-0.16],[w+0.16,base,-0.16],[w+0.16,base,d+0.16],[-0.16,base,d+0.16]], "#263348", "#64748b");
        }
        // A soft footprint and three translucent faces of the dimensioned volume.
        path([[0,base,0],[w,base,0],[w,base,d],[0,base,d]], "rgba(0,0,0,0.35)");
        path([[0,base,d],[w,base,d],[w,base+h,d],[0,base+h,d]], "rgba(0,91,193,0.32)", "#adc6ff");
        path([[w,base,0],[w,base,d],[w,base+h,d],[w,base+h,0]], "rgba(80,141,229,0.24)", "#adc6ff");
        path([[0,base+h,0],[w,base+h,0],[w,base+h,d],[0,base+h,d]], "rgba(173,198,255,0.25)", "#c9d9ff");
        ctx.setLineDash([3,4]);
        path([[0,base,d],[0,base,0],[w,base,0]], null, "#6b8abd", false);
        path([[0,base,0],[0,base+h,0]], null, "#6b8abd", false);
        ctx.setLineDash([]);

        // Flat human outline: feet at zero, crown at 1.75 metres.
        var body = [[-.16,1.47],[-.24,1.39],[-.32,.96],[-.24,.93],[-.14,1.23],[-.13,.85],[-.17,0],[-.06,0],[0,.75],[.06,0],[.17,0],[.13,.85],[.14,1.23],[.24,.93],[.32,.96],[.24,1.39],[.16,1.47]];
        path(body.map(function (v) { return humanPoint(v[0], v[1]); }), "rgba(179,198,244,0.18)", "#8d9bb4");
        var head = point(humanX,1.64,humanZ);
        ctx.beginPath(); ctx.ellipse(head[0],head[1],0.1*Math.SQRT2*0.866*scale,0.11*scale,0,0,Math.PI*2);
        ctx.fillStyle = "rgba(179,198,244,0.18)"; ctx.fill(); ctx.strokeStyle = "#8d9bb4"; ctx.stroke();
        label("1.75 m", point(humanX,1.96,humanZ), "#b3c6f4");
        dimension([0,base-.13,d+.2],[w,base-.13,d+.2], data.sizeCm[0] + " cm wide", 0, 12);
        dimension([w+.2,base,0],[w+.2,base+h,0], data.sizeCm[1] + " cm tall", 38, 0);
        dimension([-.18,base,0],[-.18,base,d], data.sizeCm[2] + " cm deep", -35, 8);

        document.getElementById("engine").textContent = base ? "Table · 75 cm reference" : "Floor placement";
        document.getElementById("hint").textContent = "Human reference · 1.75 m";
        formatEl.textContent = "cm";
        sizeEl.textContent = data.sizeCm.join(" × ") + " cm" + (data.dimensionSource === "estimated" ? " · estimated size" : "");
        sizeEl.hidden = false;
        canvas.setAttribute("aria-label", data.label + ": " + sizeEl.textContent + ". " + data.placement + " placement. Human reference: 1.75 metres.");
      }
      var spaceObserver = typeof ResizeObserver === "function" ? new ResizeObserver(function () {
        if (spaceData && !disposed) drawSpace(spaceData);
      }) : null;
      if (spaceObserver) spaceObserver.observe(canvasWrap);
`;

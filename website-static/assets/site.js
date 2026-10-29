/* SceneView site — behaviour layer. No framework, no build step.
 * Everything here is progressive: without JS the page shows posters, static
 * code and working links.
 *
 * mount()/unmount(): every listener is bound to one AbortController and every
 * live WebGL context is disposed in unmount(), so nothing leaks a context or a
 * scroll handler. mount() runs once on load.
 *
 * The 3D stages only use the public SceneView Web API (js/sceneview.js):
 * create({ transparent, renderMode: 'onDemand' }), getCameraOrbit(),
 * setCameraOrbit(), setAutoRotate(), requestRender(), whenReady(), dispose().
 * A stage with data-scene is a whole picture instead of one model in the CSS
 * studio: its lights, lens and camera move live in assets/scenes/<name>.js,
 * loaded on demand, and this file only frames it and drives play / pause.
 * No version string lives in this file: install lines are in the HTML, where
 * .claude/scripts/sync-versions.sh keeps them in step with each release. */
(function () {
  'use strict';
  var doc = document.documentElement;
  function $(s, r) { return (r || document).querySelector(s); }
  function $$(s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); }
  function store(k, v) { try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) { return null; } }
  function clamp(x, a, b) { return Math.min(b, Math.max(a, x)); }
  var mq = function (q) { return matchMedia(q).matches; };

  var ac = null;                         // AbortController of the current mount
  function on(el, ev, fn, opt) { if (el) el.addEventListener(ev, fn, Object.assign({ signal: ac.signal }, opt || {})); }

  /* ── Device budget (no GPU probe here: it runs in goLive, on demand) ── */
  var qs = new URLSearchParams(location.search);
  var caps = (function () {
    var conn = navigator.connection || {};
    var mobile = mq('(pointer: coarse)') || innerWidth < 768;
    var reducedData = mq('(prefers-reduced-data: reduce)') || !!conn.saveData || /2g$/.test(conn.effectiveType || '');
    var auto = !reducedData && (navigator.deviceMemory || 8) >= 4;
    if (qs.get('live') === '0') auto = false;
    if (qs.get('live') === '1') auto = true;
    return { mobile: mobile, reduced: mq('(prefers-reduced-motion: reduce)'), reducedData: reducedData, auto: auto, maxLive: mobile ? 1 : 2 };
  })();
  var gl2 = null;
  function hasWebGL2() {
    if (gl2 !== null) return gl2;
    try {
      var c = document.createElement('canvas'), g = c.getContext('webgl2');
      gl2 = !!g; var lose = g && g.getExtension('WEBGL_lose_context'); lose && lose.loseContext();
    } catch (e) { gl2 = false; }
    return gl2;
  }

  /* ── Runtime (Filament WASM + sceneview.js), loaded once ── */
  var runtime = null;
  function loadScript(src) {
    return new Promise(function (res, rej) {
      var s = document.createElement('script'); s.src = src; s.async = false;
      s.onload = res; s.onerror = function () { rej(new Error('load ' + src)); };
      document.head.appendChild(s);
    });
  }
  function loadRuntime() {
    // A failed download is forgotten, so the next tap tries again instead of failing forever.
    return runtime || (runtime = loadScript('/js/filament/filament.js').then(function () { return loadScript('/js/sceneview.js'); })
      .catch(function (e) { runtime = null; throw e; }));
  }

  /* ── Stage ─────────────────────────────────────────────────
   * DOM: .stage > .stage__box (5:4) > [.stage__shadow, .stage__frame > img + canvas]
   * The frame's bottom edge is the floor line: the fit (fit.js) puts the
   * model's lowest point there at every angle, so the CSS contact shadow and
   * the box-reflect reflection line up with the poster and the live render. */
  var live = [];                         // [{stage, sv}] oldest first
  function num(stage, k, d) { var v = parseFloat(stage.dataset[k]); return isNaN(v) ? d : v; }
  function fitOptions(stage) {
    return { aspect: 1.25, elev: num(stage, 'elev', 0.25), angle: num(stage, 'angle', 0), fill: num(stage, 'fill', 0.9), top: num(stage, 'top', 0.7), hull: stage.dataset.hull || 'box' };
  }
  function setShadow(stage, s) {
    var box = $('.stage__box', stage);
    box.style.setProperty('--sx', s.x.toFixed(4)); box.style.setProperty('--sy', s.y.toFixed(4));
    box.style.setProperty('--sw', s.w.toFixed(4)); box.style.setProperty('--sh', s.h.toFixed(4));
  }
  function requestRender(stage) { var sv = stage._sv; if (sv) sv.requestRender(); }

  // Push the fitted orbit to the camera. The fit is scaled about the model's
  // contact point: moving the camera toward it by 1/s keeps that point at the
  // same place on screen, so the floor line and the CSS shadow stay put.
  function applyOrbit(stage) {
    var sv = stage._sv, L = stage._lock;
    if (!sv || !L) return;
    var s = stage._scale || 1, B = L.contact, t = L.target, r = L.radius / s;
    var target = [B[0] + (t[0] - B[0]) / s, B[1] + (t[1] - B[1]) / s, B[2] + (t[2] - B[2]) / s];
    sv.setCameraOrbit({ target: target, radius: r, height: target[1] + r * L.elev });
  }

  // Scale the model about its contact point. Live: the camera moves (applyOrbit).
  // Poster: the whole box scales about the same point in CSS.
  function setScale(stage, s) {
    stage._scale = s;
    var box = $('.stage__box', stage);
    box.style.setProperty('--obj-scale', stage._sv ? 1 : s);
    box.style.setProperty('--shadow-scale', stage._sv ? s : 1);
    if (stage._sv) applyOrbit(stage);
  }

  // Bound once per stage in mount(); every handler reads stage._sv when it runs.
  function attach(stage) {
    // Mouse: lock the orbit to the fit. A horizontal drag turns the model, but
    // the vertical part of a drag cannot lift the camera off the fitted height.
    // The SDK's own handlers sit on the canvas; this runs after them (bubble).
    on(stage, 'mousemove', function (e) { if (e.buttons) applyOrbit(stage); });
    // The SDK zooms on wheel and calls preventDefault, which would trap the
    // page scroll over the canvas. Stop the event before it reaches the canvas.
    on(stage, 'wheel', function (e) { e.stopPropagation(); }, { capture: true, passive: true });
    // Touch: the canvas has touch-action: pan-y, so the SDK leaves a vertical
    // swipe to the page and turns the model on a sideways drag. It cancels the
    // moves it takes: those are the ones to lock to the fit, like the mouse.
    on(stage, 'touchmove', function (e) { if (e.defaultPrevented) applyOrbit(stage); }, { passive: true });
  }

  // "minX,minY,minZ,maxX,maxY,maxZ" of the model, in its own units. Written in
  // the HTML next to each model path (the SDK has no public bounds getter).
  function bboxOf(stage) {
    var v = (stage.dataset.bbox || '').split(',').map(parseFloat);
    if (v.length !== 6 || v.some(isNaN)) return null;
    return { min: v.slice(0, 3), max: v.slice(3) };
  }

  function applyFit(stage, sv) {
    var bb = bboxOf(stage);
    if (!bb || !window.SVFit) { sv.frameModel({ fill: num(stage, 'fill', 0.9) }); return; }
    var f = SVFit.fit(bb, fitOptions(stage));
    stage._lock = f;
    sv.setCameraOrbit({ angle: f.angle });
    setShadow(stage, f.shadow);
    setScale(stage, stage._scale || 1);
  }

  function setPlaying(stage, playing) {
    var b = $('.stage__pause', stage.closest('[data-stage-wrap]') || stage);
    var what = stage.dataset.scene ? 'camera move' : 'rotation';
    if (b) {
      b.setAttribute('aria-pressed', String(!playing));
      b.setAttribute('aria-label', (playing ? 'Pause the ' : 'Resume the ') + what);
    }
    if (stage._scene) { if (playing) stage._scene.play(); else stage._scene.pause(); }
    else if (stage._sv) { stage._sv.setAutoRotate(playing); stage._sv.requestRender(); }
  }

  /* ── Scene stages (data-scene) ── */
  function loadScene(name) {
    var have = function () { return window.SVScenes && window.SVScenes[name]; };
    return have() ? Promise.resolve(have()) : loadScript('/assets/scenes/' + name + '.js').then(have);
  }
  // Where the subject sits in the picture, from the box's shape. A wide box leaves its
  // left to the page's copy: the subject keeps the same share of the width, right of
  // centre. A square box centres it. The posters are rendered with the same two framings.
  function frameScene(stage) {
    var scene = stage._scene, r = $('.stage__box', stage).getBoundingClientRect();
    if (!scene || !r.height) return;
    var aspect = r.width / r.height;
    if (aspect < 1.2) scene.setFrame(0, clamp(1.55 / aspect, 1.2, 1.9));
    else scene.setFrame(1.375, clamp(2.08 / aspect, 1, 1.7));
  }

  function park(entry) {
    // The context-loss listener goes first: it holds the viewer, and the canvas it sits
    // on is swapped out below, so left on it, every pass kept a detached canvas and its
    // engine alive. Removed before dispose(), it never sees the loss dispose() causes.
    if (entry.off) entry.off();
    if (entry.stage._scene) { entry.stage._scene.dispose(); entry.stage._scene = null; }
    try { entry.sv.dispose(); } catch (e) {}
    stopIdle(entry.stage);
    entry.stage.classList.remove('is-live', 'is-loading');
    var old = $('canvas', entry.stage), fresh = old.cloneNode(false);
    old.replaceWith(fresh);             // a disposed canvas cannot be re-initialised
    entry.stage._sv = null; entry.stage._lock = null;
    setScale(entry.stage, entry.stage._scale || 1);
  }

  function contextLost(sv) {
    for (var i = live.length; i--;) {
      if (live[i].sv !== sv) continue;
      console.warn('[site] WebGL context lost, back to the poster');
      park(live.splice(i, 1)[0]);
    }
  }

  // sceneview.js rejects a loadModel() that a later call replaced, or that its viewer's
  // dispose() cut short (a lost context, a park), with an AbortError (#4566). Whatever
  // replaced or disposed it owns the stage now, so the older load has nothing to undo.
  function superseded(e) { return !!e && e.name === 'AbortError'; }

  // Motion stops by itself after 20 s on screen (WCAG 2.2.2); the pause button stops it
  // sooner. Off screen the countdown waits, as the hero's film does: on wall time, a
  // visitor who scrolled away 2 s into the film and came back 20 s later found it frozen
  // where they had left it, on a Play button.
  var MOTION_MS = 20000;
  function startIdle(stage) {
    stopIdle(stage);
    if (caps.reduced) return;
    stage._idleLeft = MOTION_MS;
    if (stage._inView !== false) armIdle(stage);
  }
  function armIdle(stage) {
    if (stage._idle || !(stage._idleLeft > 0)) return;
    stage._idleAt = Date.now();
    stage._idle = setTimeout(function () { stage._idle = 0; stage._idleLeft = 0; setPlaying(stage, false); }, stage._idleLeft);
  }
  function holdIdle(stage) {
    if (!stage._idle) return;
    clearTimeout(stage._idle); stage._idle = 0;
    stage._idleLeft -= Date.now() - stage._idleAt;
  }
  function stopIdle(stage) { clearTimeout(stage._idle); stage._idle = 0; stage._idleLeft = 0; }

  // The canvas takes over from the poster once the model is in, and the motion starts.
  function showModel(stage, sv) {
    setTimeout(function () { if (stage._sv !== sv) return; stage.classList.remove('is-loading'); stage.classList.add('is-live'); }, 400);
    setPlaying(stage, !caps.reduced);
    startIdle(stage);
  }

  function goLive(stage) {
    if (stage._sv || stage.classList.contains('is-loading')) return Promise.resolve();
    if (!hasWebGL2()) { stage.classList.add('no-webgl'); return Promise.resolve(); }
    while (live.length >= caps.maxLive) park(live.shift());
    stage.classList.add('is-loading');
    var canvas = $('canvas', stage), signal = ac.signal, def = null, mine = null;
    return loadRuntime().then(function () {
      return stage.dataset.scene ? loadScene(stage.dataset.scene) : null;
    }).then(function (d) {
      def = d;
      if (def) return window.SceneView.create(canvas, Object.assign({ initTimeoutMs: 20000 }, def.create));
      return window.SceneView.create(canvas, {
        transparent: true,
        renderMode: 'onDemand',           // no frames while nothing moves
        iblUrl: '/environments/neutral_ibl.ktx',
        iblIntensity: num(stage, 'ibl', 32000),
        lightIntensity: num(stage, 'light', 90000),
        fov: 32,
        autoRotate: !caps.reduced,
        initTimeoutMs: 20000
      });
    }).then(function (sv) {
      if (signal.aborted) { sv.dispose(); return; }
      mine = sv;
      // A GPU reset (a driver hang, Chrome restarting its GPU process) takes the page's
      // WebGL contexts with it, and the engine on one cannot come back. The stage stayed
      // "Live" on an empty canvas: it goes back to its poster and its load button instead.
      // park() removes this listener (entry.off) before it disposes the viewer, unmount()
      // included, so it is not tied to the page's mount signal: that signal is never
      // aborted while the page lives, and would keep the canvas and the viewer reachable.
      var entry = { stage: stage, sv: sv };
      var lost = function () { if (stage._sv === sv) contextLost(sv); };
      canvas.addEventListener('webglcontextlost', lost);
      entry.off = function () { canvas.removeEventListener('webglcontextlost', lost); };
      stage._sv = sv; live.push(entry);
      if (def) return sv.loadModel(def.model).then(function () {
        if (stage._sv !== sv) return;     // lost and parked while it loaded
        var scene = stage._scene = def.setup(sv, {
          canvas: canvas, mobile: caps.mobile,
          // A hand on the picture ends the camera move: the button shows it can resume.
          onGrab: function () { stopIdle(stage); setPlaying(stage, false); }
        });
        frameScene(stage);
        scene.setTurn(stage._turn || 0);
        scene.hold(0);                    // the poster's pose, until the picture is complete
        return sv.whenReady();            // textures decoded, environment in
      }).then(function () {
        if (stage._sv !== sv) return;
        sv.requestRender();
        setTimeout(function () {
          if (stage._sv !== sv) return;
          stage.classList.remove('is-loading'); stage.classList.add('is-live');
          setPlaying(stage, !caps.reduced);
          startIdle(stage);
        }, 400);
      });
      return sv.loadModel(stage.dataset.src).then(function () {
        if (stage._sv !== sv) return;     // lost and parked while it loaded
        applyFit(stage, sv);
        if (stage.dataset.anim && stage._animate !== false) sv.playAnimation(parseInt(stage.dataset.anim, 10), true);
        showModel(stage, sv);
      });
    }).catch(function (e) {
      // A model chip tapped during this first load replaced it (swapModel() shows that
      // one), or a lost context parked its viewer: the stage is not this load's any more.
      if (superseded(e)) return;
      if (mine && stage._sv !== mine) return;   // parked, or relaunched by the visitor since
      console.warn('[site] live 3D unavailable, keeping the poster:', e && e.message);
      // A viewer whose model never arrived is torn down: the poster stays and the next tap starts clean.
      for (var i = live.length; i--;) if (live[i].sv === mine) park(live.splice(i, 1)[0]);
      stage.classList.remove('is-loading');
    });
  }

  function swapModel(stage, chip) {
    stage.dataset.src = '/models/platforms/' + chip.dataset.file;
    ['elev', 'angle', 'hull', 'bbox'].forEach(function (k) { if (chip.dataset[k]) stage.dataset[k] = chip.dataset[k]; });
    if (chip.dataset.anim) stage.dataset.anim = chip.dataset.anim; else delete stage.dataset.anim;
    var poster = $('.stage__poster', stage);
    if (poster && chip.dataset.poster) poster.src = chip.dataset.poster;
    if (chip.dataset.shadow) {
      var s = chip.dataset.shadow.split(',').map(parseFloat);
      setShadow(stage, { x: s[0], y: s[1], w: s[2], h: s[3] });
    }
    if (stage._sv) {
      stage.classList.add('is-loading');
      var sv = stage._sv;
      sv.loadModel(stage.dataset.src).then(function () {
        if (stage._sv !== sv) return;
        applyFit(stage, sv);
        if (stage.dataset.anim && stage._animate !== false) sv.playAnimation(parseInt(stage.dataset.anim, 10), true);
        // Tapped before the first model was shown: this model is the stage's first.
        if (stage.classList.contains('is-live')) stage.classList.remove('is-loading');
        else showModel(stage, sv);
      }).catch(function (e) {
        // A later tap replaced this one, or the viewer was parked: someone else owns the stage.
        if (superseded(e) || stage._sv !== sv) return;
        console.warn('[site] model swap failed:', e && e.message);
        // No model was ever shown: tear the viewer down, as a failed first load does.
        if (!stage.classList.contains('is-live')) {
          for (var i = live.length; i--;) if (live[i].sv === sv) park(live.splice(i, 1)[0]);
        }
        stage.classList.remove('is-loading');
      });
    }
  }

  /* ── Mount ─────────────────────────────────────────────── */
  function mount() {
    ac = new AbortController();
    doc.classList.add('js-ready');
    if (document.readyState === 'complete') doc.classList.add('is-loaded');
    else on(window, 'load', function () { doc.classList.add('is-loaded'); });

    /* Theme */
    var mqLight = matchMedia('(prefers-color-scheme: light)');
    function effectiveTheme() { return doc.dataset.theme || (mqLight.matches ? 'light' : 'dark'); }
    function labelTheme() { $$('.theme-toggle').forEach(function (b) { b.setAttribute('aria-label', effectiveTheme() === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'); }); }
    $$('.theme-toggle').forEach(function (b) {
      on(b, 'click', function () { var next = effectiveTheme() === 'dark' ? 'light' : 'dark'; doc.dataset.theme = next; store('sceneview-theme', next); labelTheme(); });
    });
    labelTheme();

    /* Mobile sheet, with a focus trap */
    var sheet = $('#menu'), opener = null;
    function setSheet(open) {
      if (!sheet) return;
      sheet.classList.toggle('is-open', open);
      sheet.setAttribute('aria-hidden', String(!open));
      $$('[aria-controls="menu"]').forEach(function (b) { b.setAttribute('aria-expanded', String(open)); });
      doc.style.overflow = open ? 'hidden' : '';
      if (open) { var f = $('a, button', $('.sheet__panel', sheet)); f && f.focus(); }
      else if (opener) opener.focus();
    }
    $$('[aria-controls="menu"]').forEach(function (b) { on(b, 'click', function () { opener = b; setSheet(!sheet.classList.contains('is-open')); }); });
    $$('[data-close-sheet]').forEach(function (el) { on(el, 'click', function () { setSheet(false); }); });
    on(document, 'keydown', function (e) {
      if (!sheet || !sheet.classList.contains('is-open')) return;
      if (e.key === 'Escape') { setSheet(false); return; }
      if (e.key !== 'Tab') return;
      var f = $$('a[href], button:not([disabled])', $('.sheet__panel', sheet)); if (!f.length) return;
      var first = f[0], last = f[f.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    });

    /* A tab strip too narrow for its tabs scrolls sideways (the code card on a
       phone). The selected tab is brought clear of the faded edges — the strip
       scrolls, never the page — and data-more names the side(s) still hiding tabs. */
    function stripEdges(list) {
      var max = list.scrollWidth - list.clientWidth, x = list.scrollLeft;
      var more = max > 1 ? ((x > 1 ? 'start ' : '') + (x < max - 1 ? 'end' : '')).trim() : '';
      if (more) list.setAttribute('data-more', more); else list.removeAttribute('data-more');
    }
    function revealTab(list, tab, instant) {
      if (list.scrollWidth - list.clientWidth <= 1) return;
      var lr = list.getBoundingClientRect(), tr = tab.getBoundingClientRect();
      var pad = parseFloat(getComputedStyle(list).scrollPaddingLeft) || 0;
      var d = tr.left < lr.left + pad ? tr.left - lr.left - pad : tr.right > lr.right - pad ? tr.right - lr.right + pad : 0;
      if (d) list.scrollTo({ left: list.scrollLeft + d, behavior: instant || caps.reduced ? 'auto' : 'smooth' });
    }
    function fitStrips() {
      $$('[role="tablist"]').forEach(function (list) {
        var sel = $('[role="tab"][aria-selected="true"]', list);
        if (sel) revealTab(list, sel, true);
        stripEdges(list);
      });
    }

    /* Tabs (ARIA tabs; data-sync keeps groups on the same platform) */
    function selectTab(tab, sync) {
      var list = tab.closest('[role="tablist"]');
      $$('[role="tab"]', list).forEach(function (t) {
        var sel = t === tab;
        t.setAttribute('aria-selected', String(sel)); t.tabIndex = sel ? 0 : -1;
        var p = document.getElementById(t.getAttribute('aria-controls'));
        if (p) p.hidden = !sel;
      });
      revealTab(list, tab);
      var key = tab.dataset.key, group = list.dataset.sync;
      if (sync !== false && group && key) {
        store('sv-tab-' + group, key);
        $$('[role="tablist"][data-sync="' + group + '"]').forEach(function (other) {
          if (other === list) return;
          var t = $('[role="tab"][data-key="' + key + '"]', other);
          if (t && t.getAttribute('aria-selected') !== 'true') selectTab(t, false);
        });
      }
      var card = list.closest('.codecard');
      // The tabs carry data-status too: aim at the footer, or the first tab is rewritten.
      var status = card && $('.codecard__foot [data-status]', card);
      if (status && tab.dataset.status) { status.className = 'status status--' + tab.dataset.status; status.textContent = tab.dataset.statusLabel || tab.dataset.status; }
      var qlink = card && $('[data-quickstart]', card);
      if (qlink && tab.dataset.href) { qlink.href = tab.dataset.href; qlink.textContent = 'Quickstart · ' + tab.textContent + ' →'; }
      list.dispatchEvent(new CustomEvent('sv:tab', { detail: { key: key } }));
    }
    $$('[role="tablist"]').forEach(function (list) {
      var tabs = $$('[role="tab"]', list);
      tabs.forEach(function (t, i) {
        on(t, 'click', function () { selectTab(t); });
        on(t, 'keydown', function (e) {
          var cols = parseInt(getComputedStyle(list).getPropertyValue('--cols'), 10) || tabs.length;
          var d = { ArrowRight: 1, ArrowLeft: -1, ArrowDown: cols < tabs.length ? cols : 0, ArrowUp: cols < tabs.length ? -cols : 0 }[e.key];
          if (e.key === 'Home') d = -i; if (e.key === 'End') d = tabs.length - 1 - i;
          if (!d) return; e.preventDefault();
          var n = tabs[(i + d + tabs.length) % tabs.length]; n.focus(); selectTab(n);
        });
      });
      var saved = list.dataset.sync && store('sv-tab-' + list.dataset.sync);
      var pick = saved && $('[role="tab"][data-key="' + saved + '"]', list);
      if (pick) selectTab(pick, false);
      on(list, 'scroll', function () { stripEdges(list); }, { passive: true });
    });
    fitStrips();
    on(window, 'resize', fitStrips, { passive: true });
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(fitStrips);

    /* Install: six platforms, each a real tab panel in the HTML. "Get started"
       and the quickstart link follow the selected platform. */
    $$('[data-install]').forEach(function (list) {
      on(list, 'sv:tab', function (e) {
        var tab = $('[role="tab"][data-key="' + e.detail.key + '"]', list); if (!tab) return;
        $$('[data-start]').forEach(function (a) { a.href = tab.dataset.href; });
        $$('[data-start-label]').forEach(function (s) { s.textContent = 'Quickstart · ' + tab.dataset.label; });
      });
    });

    /* One source for numbers that change: the star count is the data-metric
       anchor (refreshed weekly), the version is the Android install line. */
    var stars = $('[data-metric="stars"]');
    if (stars) $$('[data-stars]').forEach(function (el) { el.textContent = (el.dataset.starsPrefix || '') + stars.textContent.trim(); });
    var ver = /sceneview:sceneview:(\d+)\.(\d+)\.(\d+)/.exec(($('#ins-android') || {}).textContent || '');
    if (ver) $$('[data-release]').forEach(function (a) {
      a.textContent = 'v' + ver[1] + '.' + ver[2];
      a.href = 'https://github.com/sceneview/sceneview/releases/tag/v' + ver[1] + '.' + ver[2] + '.' + ver[3];
    });

    /* Copy: the tick and "Copied" show only once the text is on the clipboard. */
    function copy(b, text) {
      if (!navigator.clipboard) return;
      navigator.clipboard.writeText(text).then(function () {
        b.classList.add('is-done'); b.setAttribute('aria-label', 'Copied');
        setTimeout(function () { b.classList.remove('is-done'); b.setAttribute('aria-label', 'Copy to clipboard'); }, 1600);
      }, function () {});
    }
    $$('.copy').forEach(function (b) {
      on(b, 'click', function () {
        var src = b.dataset.copy ? document.getElementById(b.dataset.copy) : $('code', b.parentElement);
        if (b.hasAttribute('data-copy-panel')) {
          var block = b.closest('.codecard');
          src = block && $('[role="tabpanel"]:not([hidden]) code', block);
        }
        copy(b, b.dataset.copyText || (src && src.textContent) || '');
      });
    });

    /* AI assistants: eight equal buttons, none selected by default. Every setup
       is also laid out, hidden and aria-hidden, in the cell of the one shown
       (site.css): the box takes the height of the tallest, so the page under it
       does not jump. Only the shown setup changes, so the live region reads it. */
    $$('[data-assistants]').forEach(function (group) {
      var out = document.getElementById(group.dataset.assistants);
      var shown = out && $('.mcp__setup', out);
      if (!shown) return;
      $$('template[id^="mcp-"]').forEach(function (tpl) {
        var ghost = document.createElement('div');
        ghost.className = 'mcp__setup';
        ghost.setAttribute('aria-hidden', 'true');
        ghost.inert = true;
        ghost.innerHTML = tpl.innerHTML;
        out.appendChild(ghost);
      });
      $$('button', group).forEach(function (b) {
        on(b, 'click', function () {
          var was = b.getAttribute('aria-pressed') === 'true';
          $$('button', group).forEach(function (x) { x.setAttribute('aria-pressed', 'false'); });
          var tpl = document.getElementById(was ? 'mcp-any' : 'mcp-' + b.dataset.id);
          if (!was) b.setAttribute('aria-pressed', 'true');
          if (tpl) {
            shown.innerHTML = tpl.innerHTML;
            $$('.copy', shown).forEach(function (c) {
              on(c, 'click', function () { copy(c, c.dataset.copyText || $('code', c.parentElement).textContent); });
            });
          }
        });
      });
    });

    /* Reveal: hidden only once this script runs (a JS failure leaves content visible). */
    var reveals = $$('.reveal');
    if ('IntersectionObserver' in window && !qs.has('noreveal') && !caps.reduced) {
      doc.classList.add('js-reveal');
      var io = new IntersectionObserver(function (es) {
        es.forEach(function (e) { if (e.isIntersecting) { e.target.classList.add('is-in'); io.unobserve(e.target); } });
      }, { rootMargin: '0px 0px -8% 0px' });
      reveals.forEach(function (el) { io.observe(el); });
      ac.signal.addEventListener('abort', function () { io.disconnect(); });
    }

    /* Stages. Starting an engine (WASM compile, a GLB, an environment) costs main-thread
       time in 100 ms slices: an automatic start waits until the page has stopped scrolling,
       so it never lands in the middle of a scroll. A tap on "View in 3D" starts at once. */
    var lastScroll = 0;
    on(window, 'scroll', function () { lastScroll = performance.now(); }, { passive: true });
    function whenStill(fn) {
      (function wait() {
        if (ac.signal.aborted) return;
        var quiet = performance.now() - lastScroll;
        if (quiet < 300) { setTimeout(wait, 300 - quiet); return; }
        (window.requestIdleCallback || setTimeout)(function () {
          if (ac.signal.aborted) return;
          if (performance.now() - lastScroll < 300) wait(); else fn();
        }, { timeout: 1500 });
      })();
    }
    $$('[data-stage]').forEach(function (stage) {
      var wrap = stage.closest('[data-stage-wrap]') || stage;
      var btn = $('.stage__load', wrap), pause = $('.stage__pause', wrap);
      attach(stage);
      on(btn, 'click', function () { goLive(stage); });
      on(pause, 'click', function () { stopIdle(stage); setPlaying(stage, pause.getAttribute('aria-pressed') === 'true'); });
      // The 20 s motion countdown (startIdle) runs only while the stage is on screen.
      if ('IntersectionObserver' in window) {
        var so = new IntersectionObserver(function (es) {
          stage._inView = es[es.length - 1].isIntersecting;
          if (stage._inView) armIdle(stage); else holdIdle(stage);
        });
        so.observe(stage);
        ac.signal.addEventListener('abort', function () { so.disconnect(); });
      }
      var mode = stage.dataset.autoload || 'tap';
      if (caps.mobile && stage.dataset.mobile) mode = stage.dataset.mobile;
      if (!caps.auto || mode === 'tap') return;
      if (mode === 'idle') {
        var go = function () { whenStill(function () { goLive(stage); }); };
        if (document.readyState === 'complete') go(); else on(window, 'load', go);
      } else if (mode === 'visible' && 'IntersectionObserver' in window) {
        var vo = new IntersectionObserver(function (es) {
          if (es[0].isIntersecting) { vo.disconnect(); whenStill(function () { goLive(stage); }); }
        }, { rootMargin: '200px 0px' });
        vo.observe(stage);
        ac.signal.addEventListener('abort', function () { vo.disconnect(); });
      }
    });

    /* Code ↔ scene: model chips rewrite the literal and the scene */
    $$('[data-models]').forEach(function (group) {
      var stage = document.getElementById(group.dataset.models);
      var wrap = stage.closest('[data-stage-wrap]');
      $$('.chip', group).forEach(function (chip) {
        on(chip, 'click', function () {
          $$('.chip', group).forEach(function (c) { c.setAttribute('aria-pressed', String(c === chip)); });
          var name = chip.dataset.name;
          $$('[data-literal]').forEach(function (s) {
            var ext = name + (s.dataset.literal === 'usdz' ? '.usdz' : '.glb');
            var q = s.dataset.q || '"';
            s.textContent = q + (s.dataset.dir != null ? s.dataset.dir : 'models/') + ext + q;
            s.classList.remove('is-swapped'); void s.offsetWidth; s.classList.add('is-swapped');
          });
          $$('[data-anim-line]').forEach(function (l) { l.hidden = !chip.dataset.anim; });
          swapModel(stage, chip);
          var credit = wrap && $('.credit[data-credit]', wrap);   // the chips carry data-credit too
          if (credit) credit.innerHTML = chip.dataset.credit;
        });
      });
    });

    /* Live tokens in the Compose code: scaleToUnits (slider) and autoAnimate (switch). */
    $$('[data-scale-token]').forEach(function (tok) {
      var stage = document.getElementById(tok.dataset.scaleToken);
      var min = 0.5, max = 1.4;
      function set(v) {
        v = Math.round(clamp(v, min, max) * 20) / 20;
        tok.textContent = v.toFixed(2).replace(/0$/, '') + 'f';
        tok.setAttribute('aria-valuenow', v.toFixed(2)); tok.setAttribute('aria-valuetext', v.toFixed(2) + ' units');
        setScale(stage, v);
      }
      on(tok, 'keydown', function (e) {
        var v = parseFloat(tok.getAttribute('aria-valuenow'));
        var d = { ArrowRight: .05, ArrowUp: .05, ArrowLeft: -.05, ArrowDown: -.05, PageUp: .2, PageDown: -.2 }[e.key];
        if (e.key === 'Home') { e.preventDefault(); return set(min); }
        if (e.key === 'End') { e.preventDefault(); return set(max); }
        if (d) { e.preventDefault(); set(v + d); }
      });
      on(tok, 'pointerdown', function (e) {
        e.preventDefault(); tok.setPointerCapture(e.pointerId); tok.classList.add('is-dragging');
        var x0 = e.clientX, v0 = parseFloat(tok.getAttribute('aria-valuenow'));
        function move(ev) { set(v0 + (ev.clientX - x0) / 160); }
        function up() { tok.classList.remove('is-dragging'); tok.removeEventListener('pointermove', move); tok.removeEventListener('pointerup', up); tok.removeEventListener('pointercancel', up); }
        tok.addEventListener('pointermove', move); tok.addEventListener('pointerup', up); tok.addEventListener('pointercancel', up);
      });
      set(1);
    });
    $$('[data-anim-token]').forEach(function (tok) {
      var stage = document.getElementById(tok.dataset.animToken);
      function toggle() {
        var onNow = tok.getAttribute('aria-checked') !== 'true';
        tok.setAttribute('aria-checked', String(onNow)); tok.textContent = String(onNow);
        stage._animate = onNow;
        var sv = stage._sv;
        if (sv && stage.dataset.anim) { if (onNow) sv.playAnimation(parseInt(stage.dataset.anim, 10), true); else sv.stopAnimation(); sv.requestRender(); }
      }
      on(tok, 'click', toggle);
      on(tok, 'keydown', function (e) { if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); toggle(); } });
    });

    /* Scroll: header state, hero pin (desktop) and parallax (phone). */
    var nav = $('.nav'), hero = $('.hero'), pin = hero && $('.hero__pin', hero);
    var heroStage = hero && $('[data-stage]', hero);
    // Same query as the CSS (.hero pin): one source of truth for when the hero is pinned.
    var mqFx = matchMedia('(min-width: 960px) and (min-height: 600px) and (prefers-reduced-motion: no-preference)');
    var scrollFx = !!hero && mqFx.matches;
    if (hero) hero.classList.toggle('has-scroll-fx', scrollFx);
    var specs = hero ? $$('.hero__specs li', hero) : [];
    // --p goes on the two blocks that read it, not on .hero: a custom property set on the
    // hero restyled its whole subtree (canvas, chips, specs) on every scroll frame.
    var pTargets = hero ? $$('.hero__copy, .hero__stage', hero) : [];
    var lastP = -1, ticking = false;
    function frame() {
      ticking = false;
      // Every read before any write: one layout per frame, never a forced one.
      var y = scrollY, r = hero && hero.getBoundingClientRect(), navH = nav ? nav.offsetHeight : 0, p = 0;
      if (nav) {
        nav.classList.toggle('is-scrolled', y > 8);
        // Night set while the picture still shows below the header. Once only its last
        // strip is left under the header, the bar takes the page's set at once, but solid
        // (is-stage-edge): a translucent bar there let that strip through as a two-tone band.
        var over = !!r && r.bottom > navH;
        if (hero) {
          nav.classList.toggle('is-over-stage', over);
          nav.classList.toggle('is-stage-edge', !over && r.bottom > 0);
        }
        // Over a night picture the header takes the dark set, whatever the theme.
        nav.classList.toggle('night', over && hero.classList.contains('night'));
      }
      if (!hero) return;
      if (scrollFx) p = clamp(-r.top / Math.max(1, r.height - innerHeight), 0, 1);
      else p = clamp(-r.top / Math.max(1, r.height), 0, 1);
      if (p === lastP) return;            // past the hero: nothing left to move
      var pv = p.toFixed(4);
      pTargets.forEach(function (el) { el.style.setProperty('--p', pv); });
      hero.classList.toggle('is-copy-out', scrollFx && p >= 0.25);
      specs.forEach(function (li, i) { li.classList.toggle('is-on', p > 0.28 + i * 0.16); });
      // Live: the scroll turns the model (about 1.6 rad over the pin); a scene turns its camera.
      if (heroStage && heroStage.dataset.scene) {
        var turn = scrollFx ? p * 1.2 : 0;
        if (turn !== heroStage._turn) { heroStage._turn = turn; if (heroStage._scene) heroStage._scene.setTurn(turn); }
      } else if (scrollFx && heroStage && heroStage._sv && lastP >= 0) { var hsv = heroStage._sv; hsv.setCameraOrbit({ angle: hsv.getCameraOrbit().angle + (p - lastP) * 1.6 }); }
      lastP = p;
    }
    on(window, 'scroll', function () { if (!ticking) { ticking = true; requestAnimationFrame(frame); } }, { passive: true });
    on(window, 'resize', function () {
      var want = !!hero && mqFx.matches;
      if (hero && want !== scrollFx) { scrollFx = want; hero.classList.toggle('has-scroll-fx', want); lastP = -1; }
      $$('[data-scene]').forEach(frameScene);
      frame();
    });
    frame();
  }

  function unmount() {
    if (!ac) return;
    ac.abort();
    while (live.length) park(live.shift());
    doc.style.overflow = '';
  }

  window.__sv = { caps: caps, goLive: goLive, live: live, mount: mount, unmount: unmount, setScale: setScale };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mount);
  } else mount();
})();

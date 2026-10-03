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
 * setCameraOrbit(), setAutoRotate(), requestRender(), dispose().
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
    return runtime || (runtime = loadScript('/js/filament/filament.js').then(function () { return loadScript('/js/sceneview.js'); }));
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

  function attach(stage) {
    // Lock the orbit to the fit: a horizontal drag turns the model, but the
    // vertical part of a drag cannot lift the camera off the fitted height.
    // The SDK's own handlers sit on the canvas; these run after them (bubble).
    var relock = function () { applyOrbit(stage); };
    on(stage, 'mousemove', function (e) { if (e.buttons) relock(); });
    on(stage, 'touchmove', relock, { passive: true });
    // The SDK zooms on wheel and calls preventDefault, which would trap the
    // page scroll over the canvas. Stop the event before it reaches the canvas.
    on(stage, 'wheel', function (e) { e.stopPropagation(); }, { capture: true, passive: true });
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
    if (b) {
      b.setAttribute('aria-pressed', String(!playing));
      b.setAttribute('aria-label', playing ? 'Pause the rotation' : 'Resume the rotation');
    }
    if (stage._sv) { stage._sv.setAutoRotate(playing); stage._sv.requestRender(); }
  }

  function park(entry) {
    try { entry.sv.dispose(); } catch (e) {}
    clearTimeout(entry.stage._idle);
    entry.stage.classList.remove('is-live', 'is-loading');
    var old = $('canvas', entry.stage), fresh = old.cloneNode(false);
    old.replaceWith(fresh);             // a disposed canvas cannot be re-initialised
    entry.stage._sv = null; entry.stage._lock = null;
    setScale(entry.stage, entry.stage._scale || 1);
  }

  function goLive(stage) {
    if (stage._sv || stage.classList.contains('is-loading')) return Promise.resolve();
    if (!hasWebGL2()) { stage.classList.add('no-webgl'); return Promise.resolve(); }
    while (live.length >= caps.maxLive) park(live.shift());
    stage.classList.add('is-loading');
    var canvas = $('canvas', stage), signal = ac.signal;
    return loadRuntime().then(function () {
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
      stage._sv = sv; live.push({ stage: stage, sv: sv });
      attach(stage);
      return sv.loadModel(stage.dataset.src).then(function () {
        applyFit(stage, sv);
        if (stage.dataset.anim && stage._animate !== false) sv.playAnimation(parseInt(stage.dataset.anim, 10), true);
        setTimeout(function () { stage.classList.remove('is-loading'); stage.classList.add('is-live'); }, 400);
        setPlaying(stage, !caps.reduced);
        // Motion stops by itself after 20 s (WCAG 2.2.2); the pause button stops it sooner.
        if (!caps.reduced) stage._idle = setTimeout(function () { setPlaying(stage, false); }, 20000);
      });
    }).catch(function (e) {
      console.warn('[site] live 3D unavailable, keeping the poster:', e && e.message);
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
        applyFit(stage, sv);
        if (stage.dataset.anim && stage._animate !== false) sv.playAnimation(parseInt(stage.dataset.anim, 10), true);
        stage.classList.remove('is-loading');
      });
    }
  }

  /* ── Mount ─────────────────────────────────────────────── */
  function mount() {
    ac = new AbortController();
    doc.classList.add('js-ready');

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

    /* Tabs (ARIA tabs; data-sync keeps groups on the same platform) */
    function selectTab(tab, sync) {
      var list = tab.closest('[role="tablist"]');
      $$('[role="tab"]', list).forEach(function (t) {
        var sel = t === tab;
        t.setAttribute('aria-selected', String(sel)); t.tabIndex = sel ? 0 : -1;
        var p = document.getElementById(t.getAttribute('aria-controls'));
        if (p) p.hidden = !sel;
      });
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
      var status = card && $('[data-status]', card);
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
    });

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

    /* Copy */
    $$('.copy').forEach(function (b) {
      on(b, 'click', function () {
        var src = b.dataset.copy ? document.getElementById(b.dataset.copy) : $('code', b.parentElement);
        if (b.hasAttribute('data-copy-panel')) {
          var block = b.closest('.codecard');
          src = block && $('[role="tabpanel"]:not([hidden]) code', block);
        }
        var text = b.dataset.copyText || (src && src.textContent) || '';
        (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject()).catch(function () {});
        b.classList.add('is-done'); b.setAttribute('aria-label', 'Copied');
        setTimeout(function () { b.classList.remove('is-done'); b.setAttribute('aria-label', 'Copy to clipboard'); }, 1600);
      });
    });

    /* AI assistants: eight equal buttons, none selected by default. */
    $$('[data-assistants]').forEach(function (group) {
      var out = document.getElementById(group.dataset.assistants);
      $$('button', group).forEach(function (b) {
        on(b, 'click', function () {
          var was = b.getAttribute('aria-pressed') === 'true';
          $$('button', group).forEach(function (x) { x.setAttribute('aria-pressed', 'false'); });
          var tpl = document.getElementById(was ? 'mcp-any' : 'mcp-' + b.dataset.id);
          if (!was) b.setAttribute('aria-pressed', 'true');
          if (tpl && out) {
            out.innerHTML = tpl.innerHTML;
            $$('.copy', out).forEach(function (c) {
              on(c, 'click', function () {
                navigator.clipboard && navigator.clipboard.writeText(c.dataset.copyText || $('code', c.parentElement).textContent).catch(function () {});
                c.classList.add('is-done'); setTimeout(function () { c.classList.remove('is-done'); }, 1600);
              });
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

    /* Stages */
    $$('[data-stage]').forEach(function (stage) {
      var wrap = stage.closest('[data-stage-wrap]') || stage;
      var btn = $('.stage__load', wrap), pause = $('.stage__pause', wrap);
      on(btn, 'click', function () { goLive(stage); });
      on(pause, 'click', function () { clearTimeout(stage._idle); setPlaying(stage, pause.getAttribute('aria-pressed') === 'true'); });
      var mode = stage.dataset.autoload || 'tap';
      if (caps.mobile && stage.dataset.mobile) mode = stage.dataset.mobile;
      if (!caps.auto || mode === 'tap') return;
      if (mode === 'idle') {
        var go = function () { (window.requestIdleCallback || setTimeout)(function () { if (!ac.signal.aborted) goLive(stage); }, { timeout: 1500 }); };
        if (document.readyState === 'complete') go(); else on(window, 'load', go);
      } else if (mode === 'visible' && 'IntersectionObserver' in window) {
        var vo = new IntersectionObserver(function (es) {
          if (es[0].isIntersecting) { vo.disconnect(); goLive(stage); }
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
    var lastP = 0, ticking = false;
    function frame() {
      ticking = false;
      if (nav) {
        var h = nav.offsetHeight;
        nav.classList.toggle('is-scrolled', scrollY > 8);
        if (hero) nav.classList.toggle('is-over-stage', hero.getBoundingClientRect().bottom > h);
      }
      if (!hero) return;
      var r = hero.getBoundingClientRect(), p;
      if (scrollFx) p = clamp(-r.top / Math.max(1, r.height - innerHeight), 0, 1);
      else p = clamp(-r.top / Math.max(1, r.height), 0, 1);
      hero.style.setProperty('--p', p.toFixed(4));
      hero.classList.toggle('is-copy-out', scrollFx && p >= 0.25);
      specs.forEach(function (li, i) { li.classList.toggle('is-on', p > 0.28 + i * 0.16); });
      // Live: the scroll turns the model (about 1.6 rad over the pin).
      if (scrollFx && heroStage && heroStage._sv && p !== lastP) { var hsv = heroStage._sv; hsv.setCameraOrbit({ angle: hsv.getCameraOrbit().angle + (p - lastP) * 1.6 }); }
      lastP = p;
    }
    on(window, 'scroll', function () { if (!ticking) { ticking = true; requestAnimationFrame(frame); } }, { passive: true });
    on(window, 'resize', function () {
      var want = !!hero && mqFx.matches;
      if (hero && want !== scrollFx) { scrollFx = want; hero.classList.toggle('has-scroll-fx', want); }
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

/* Night circuit — the homepage showpiece scene.
 *
 * One GLB (the Car Concept on a procedural start grid, built by
 * tools/scenes/circuit/) lit, animated and filmed with the public SceneView Web API
 * only: loadEnvironment, addLight / setLight, setMaterial, playAnimation, setBloom,
 * setScreenSpaceReflections, setFog, setVignette, setPixelBudget, setOrbitLimits,
 * setCameraOrbit. Everything moves in the engine: no overlay, no video.
 *
 * site.js loads this file on demand and calls SVScenes.circuit.setup(sv, opts)
 * once the model is in. setup() returns a controller:
 * { play, pause, hold, setFrame, setTurn, setTier, dispose }. */
(function () {
  'use strict';
  var CAR = [0, 0.62, 0.25];           // what the camera looks at: mid-body, between the axles

  // The film: one slow lap of the camera around the car. It starts and ends on the pose
  // of the page's poster, so the still picture comes alive without a cut.
  // t in seconds; angle in radians (0 = dead ahead of the nose, eye at +Z).
  var POSE = { angle: 0.62, height: 0.92, radius: 6.5, fov: 30, lift: 0 };
  var SHOTS = [
    { t: 0.0, angle: POSE.angle, height: POSE.height, radius: POSE.radius, fov: POSE.fov, lift: 0.0 },
    { t: 3.6, angle: 1.5, height: 0.5, radius: 7.3, fov: 28, lift: -0.04 },    // low, along the flank
    { t: 7.2, angle: 2.8, height: 1.15, radius: 7.1, fov: 30, lift: 0.06 },    // over the tail
    { t: 10.6, angle: 4.75, height: 0.62, radius: 7.2, fov: 29, lift: 0.0 },   // the far flank, low
    { t: 14.0, angle: 6.2832 + POSE.angle, height: POSE.height, radius: POSE.radius, fov: POSE.fov, lift: 0.0 }
  ];
  var END = SHOTS[SHOTS.length - 1];
  var DRIFT = 0.035;                    // rad/s once the film is over, until the page stops it

  // The start procedure, on the film's clock (seconds). The poster shows the five reds
  // lit; the film resets them, lights them one a second, puts them out — and the car
  // answers with its headlights and brakes while the camera passes over its tail.
  var FX = {
    redsOff: 0.9,                       // the gantry goes dark
    redsOn: 1.6, redsStep: 1.0,         // column i lights at redsOn + i * redsStep
    out: 7.0,                           // lights out
    // Two high-beam pulses, each a half sine: the second starts as the first ends, so the
    // pair reads as one double flash, under three a second (#4587, as Android in #4583).
    flash: [7.0, 7.45], flashLen: 0.45,
    brakeOn: 7.0, brakeOff: 9.6,        // brake lights held while the camera is behind
    redsBack: 15.0, redsBackStep: 0.3,  // the gantry relights for the drift, as on the poster
    beaconPeriod: 1.2, beaconOn: 0.12,
    // The pit neon struggles twice: ten 120 ms slots, never below flickerFloor. It was
    // 28 slots a second down to 0.15, a strobe.
    flicker: [[0.2, 1.4], [9.0, 10.2]], flickerSlots: 10, flickerFloor: 0.6
  };
  var HEADLIGHT = 1e7, HEADLIGHT_EMIT = 10;                              // the car's own lamp material
  // At a high-beam peak, times the steady level: the spots, the lamp glass, the cones in the
  // haze. They were 2.4, 3.2 and 3.5: with bloom, the cones lit the whole haze white.
  var FLASH_LIGHT = 1.6, FLASH_LAMP = 1.8, FLASH_BEAM = 1.8;
  // Head-on glare: what is left of the spots, the glass and the cones with the camera square
  // in front of the nose. Seen end-on, the stacked cones washed the lower half of the frame
  // white as the film came round the nose. The dimming starts past the poster's angle
  // (cos 0.81), so the still and the first frame of the film stay the same picture.
  var HEAD_ON_LIGHTS = 0.6, HEAD_ON_LAMPS = 0.55, HEAD_ON_BEAMS = 0.3, HEAD_ON_FROM = 0.8, HEAD_ON_SPAN = 0.18;
  var BEAM = [0.72, 0.85, 1], BEAM_ALPHA = 0.045;                        // the cones in the haze (GLB material 'Beam')
  var BRAKE = 6e5, BRAKE_ON = 2.6e6, BRAKE_EMIT = 2;
  var START_LIGHT = 1.3e7, RED = 22, BEACON = 12, NEON = 4;

  // Catmull-Rom through the shots: no stop at a keyframe, the move reads as one take.
  function sample(key, t) {
    var n = SHOTS.length, i = 0;
    if (t <= SHOTS[0].t) return SHOTS[0][key];
    if (t >= END.t) return END[key];
    while (i < n - 2 && t > SHOTS[i + 1].t) i++;
    var a = SHOTS[i], b = SHOTS[i + 1], p = SHOTS[Math.max(0, i - 1)], q = SHOTS[Math.min(n - 1, i + 2)];
    var u = (t - a.t) / (b.t - a.t), u2 = u * u, u3 = u2 * u;
    var m0 = (b[key] - p[key]) / (b.t - p.t) * (b.t - a.t), m1 = (q[key] - a[key]) / (q.t - a.t) * (b.t - a.t);
    return (2 * u3 - 3 * u2 + 1) * a[key] + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * b[key] + (u3 - u2) * m1;
  }
  function clamp01(x) { return x < 0 ? 0 : x > 1 ? 1 : x; }
  function smooth(x) { x = clamp01(x); return x * x * (3 - 2 * x); }
  function ramp(t, from, len) { return smooth((t - from) / len); }
  // A bump: up and down over `len` seconds, from `at`.
  function bump(t, at, len) { var u = (t - at) / len; return u <= 0 || u >= 1 ? 0 : Math.sin(u * Math.PI); }
  function hash(n) { var x = Math.sin(n * 127.1 + 311.7) * 43758.5453; return x - Math.floor(x); }

  function setup(sv, opts) {
    opts = opts || {};
    var canvas = opts.canvas, disposed = false;

    /* ── Light ── */
    sv.clearLights({ keepEnvironment: true });           // the studio trio goes, the circuit's reflections stay
    // Grandstand floods: the key, from the right of the grid. The only shadow caster.
    var key = null;
    function setKey(shadows) {
      if (key) sv.removeLight(key);
      key = sv.addLight({ type: 'directional', color: [1.0, 0.95, 0.88], intensity: 52000, direction: [-0.62, -0.7, -0.36], castShadows: shadows });
    }
    setKey(true);
    // Pit-side masts: a cool rim on the far flank and the roof line.
    sv.addLight({ type: 'directional', color: [0.62, 0.78, 1.0], intensity: 26000, direction: [0.7, -0.45, 0.55] });
    // Start lights: red on the bonnet and the windscreen (follows the gantry).
    var startLight = sv.addLight({ type: 'point', color: [1.0, 0.06, 0.03], intensity: START_LIGHT, position: [0, 6.2, 13.6], falloff: 18 });
    // Garages: warm light spilling across the track from the pit side.
    sv.addLight({ type: 'point', color: [1.0, 0.6, 0.3], intensity: 4.6e7, position: [-17, 3.2, -4], falloff: 24 });
    // Headlights on the wet asphalt. Every punctual light keeps a short falloff: Filament
    // evaluates each light in every froxel its radius touches, and a 40 m radius over a
    // full-viewport canvas cost ten frames a second (measured 2026-10-14).
    var headlights = [
      sv.addLight({ type: 'spot', color: [0.72, 0.85, 1.0], intensity: HEADLIGHT, position: [0.72, 0.66, 2.35], direction: [0.06, -0.16, 1], falloff: 16, innerCone: 0.12, outerCone: 0.62 }),
      sv.addLight({ type: 'spot', color: [0.72, 0.85, 1.0], intensity: HEADLIGHT, position: [-0.72, 0.66, 2.35], direction: [-0.06, -0.16, 1], falloff: 16, innerCone: 0.12, outerCone: 0.62 })
    ];
    // Tail lights.
    var tail = sv.addLight({ type: 'point', color: [1.0, 0.04, 0.02], intensity: BRAKE, position: [0, 0.45, -3.1], falloff: 7 });

    /* ── Lens ── */
    sv.setBloom({ strength: 0.32, resolution: 512, levels: 7, threshold: true });
    sv.setVignette({ midPoint: 0.62, roundness: 0.5, feather: 0.8 });
    sv.setFog({ color: [0.05, 0.075, 0.13], density: 0.016, distance: 14, height: 0, heightFalloff: 0.035, maximumOpacity: 0.82, cutOffDistance: 620 });
    sv.setOrbitLimits({ minHeight: 0.22, maxHeight: 4.5, minRadius: 3.6, maxRadius: 16 });

    /* ── Quality ladder: a pixel budget first, effects second; steps down while frames come in late ──
     * No ambient occlusion on any rung: at night, under fog, SSAO is invisible and was
     * a fifth of the frame. FXAA stays, the car's edges show. */
    var TIERS = [
      { budget: 0.5e6, ssr: false, shadows: false },
      { budget: 0.65e6, ssr: false, shadows: true },
      { budget: 0.85e6, ssr: false, shadows: true },
      { budget: 1.2e6, ssr: true, shadows: true },
      { budget: 2.0e6, ssr: true, shadows: true }
    ];
    var tier = -1, shadowsOn = true;
    sv.setQuality({ ambientOcclusion: false, antiAliasing: true });
    sv.setPixelRatio(2);
    function setTier(n) {
      n = Math.max(0, Math.min(TIERS.length - 1, n));
      if (n === tier) return;
      tier = n;
      var T = TIERS[n];
      sv.setPixelBudget(T.budget);
      sv.setScreenSpaceReflections(T.ssr ? { thickness: 0.5, bias: 0.05, maxDistance: 9, stride: 1 } : false);
      if (T.shadows !== shadowsOn) { shadowsOn = T.shadows; setKey(shadowsOn); }
    }
    // A phone's picture is small but its GPU is too: it starts one rung under the desktop.
    setTier(opts.tier != null ? opts.tier : (opts.mobile ? 1 : 2));

    /* ── Effects: every value is a function of the film's clock, applied only when it changes ── */
    var applied = {};
    function put(k, v, fn) { if (applied[k] !== v) { applied[k] = v; fn(v); } }
    var redsCount = 0;
    function fx(t) {
      // The gantry: lit on the poster, dark at redsOff, one column a second from redsOn,
      // out at `out`, back in a sweep at redsBack.
      var lit = 0;
      for (var i = 0; i < 5; i++) {
        var s = t < FX.redsOff ? 1
          : t < FX.out ? ramp(t, FX.redsOn + i * FX.redsStep, 0.15)
          : t < FX.redsBack ? 0
          : ramp(t, FX.redsBack + i * FX.redsBackStep, 0.2);
        lit += s;
        put('red' + i, Math.round(s * RED * 100) / 100, function (v) { sv.setMaterial('Light startRed' + i, { emissiveStrength: v }); });
      }
      put('startLight', Math.round(lit / 5 * 100) / 100, function (v) { sv.setLight(startLight, { intensity: START_LIGHT * v }); });
      // Lights out: two high-beam pulses, the beams in the haze with them. place() applies
      // them, with the head-on dimming of the pose it sets.
      pulse = Math.max(bump(t, FX.flash[0], FX.flashLen), bump(t, FX.flash[1], FX.flashLen));
      // Brakes: held while the camera passes over the tail.
      var b = ramp(t, FX.brakeOn, 0.35) * (1 - ramp(t, FX.brakeOff, 0.6));
      put('brake', Math.round(b * 100) / 100, function (v) {
        sv.setLight(tail, { intensity: BRAKE + (BRAKE_ON - BRAKE) * v });
        sv.setMaterial('Brakelight', { emissiveStrength: BRAKE_EMIT * (1 + 4 * v) });
      });
      // The tower beacon, and the skyline's: one short blink a second and a bit.
      var ph = t % FX.beaconPeriod;
      put('beacon', ph < FX.beaconOn ? 1 : 0, function (v) { sv.setMaterial('Light beacon', { emissiveStrength: v ? BEACON : BEACON * 0.12 }); });
      // The pit neon struggles twice, then holds.
      var n = 1;
      for (var k = 0; k < FX.flicker.length; k++) {
        var w = FX.flicker[k];
        if (t >= w[0] && t < w[1]) n = FX.flickerFloor + (1 - FX.flickerFloor) * struggle((t - w[0]) / (w[1] - w[0]), k);
      }
      put('neon', Math.round(n * 100) / 100, function (v) { sv.setMaterial('Light ledWarm', { emissiveStrength: NEON * v }); });
      redsCount = lit;
    }
    // A tube catching, u 0 → 1 across the window: lit more and more often, steady on the
    // last slot so the window ends without a jump (Android's LightCues.ignition).
    function struggle(u, k) {
      var slot = Math.floor(u * FX.flickerSlots);
      if (slot >= FX.flickerSlots - 1) return 1;
      var lit = hash(k * 97 + slot) < u * u * 1.2, grain = hash(k * 97 + slot + 7919);
      return lit ? 0.8 + 0.2 * grain : 0.06 * grain;
    }
    // The headlights: the flash pulse from fx(), dimmed as the camera comes round the nose.
    var pulse = 0;
    function headlightsFx(angle) {
      var g = Math.round(ramp(Math.cos(angle), HEAD_ON_FROM, HEAD_ON_SPAN) * 100) / 100;
      var p = Math.round(pulse * 100) / 100;
      put('headlights', p + '/' + g, function () {
        function dim(left) { return 1 - (1 - left) * g; }
        for (var j = 0; j < 2; j++) sv.setLight(headlights[j], { intensity: HEADLIGHT * (1 + (FLASH_LIGHT - 1) * p) * dim(HEAD_ON_LIGHTS) });
        sv.setMaterial('Headlight', { emissiveStrength: HEADLIGHT_EMIT * (1 + (FLASH_LAMP - 1) * p) * dim(HEAD_ON_LAMPS) });
        sv.setMaterial('Beam', { baseColor: [BEAM[0], BEAM[1], BEAM[2], BEAM_ALPHA * (1 + (FLASH_BEAM - 1) * p) * dim(HEAD_ON_BEAMS)] });
      });
    }
    // Rain: the GLB's one animation, a seamless loop. It runs with the film; a still has it frozen.
    function rain(on) { if (on) sv.playAnimation(0, true); else sv.stopAnimation(); }

    /* ── Camera ── */
    // How the page frames the picture: `shift` metres to the right of centre at the poster
    // pose (a wide hero leaves its left to the copy), `zoom` times the film's distances.
    var shift = 0, zoom = 1;
    function place(angle, height, radius, fov, lift) {
      // The orbit looks at its target: to leave the left of a wide picture to the page's
      // copy, the target slides to the car's left as seen from the camera.
      var s = shift * (radius / POSE.radius);
      angle += turn; turned = turn;
      sv.setCameraOrbit({
        angle: angle, height: height, radius: radius * zoom, fov: fov,
        target: [CAR[0] - Math.cos(angle) * s, CAR[1] + (lift || 0), CAR[2] + Math.sin(angle) * s]
      });
      headlightsFx(angle);
    }
    function retarget() {               // after the visitor turned the camera
      var o = sv.getCameraOrbit(), s = shift * (o.radius / (POSE.radius * zoom));
      sv.setCameraOrbit({ target: [CAR[0] - Math.cos(o.angle) * s, o.target[1], CAR[2] + Math.sin(o.angle) * s] });
      headlightsFx(o.angle);
    }
    function setFrame(m, k) {
      k = k || 1;
      if (!playing) sv.setCameraOrbit({ radius: sv.getCameraOrbit().radius * k / zoom });
      shift = m; zoom = k;
      if (!playing) retarget();
    }
    // The page's scroll turns the camera on top of whatever it is doing (radians, absolute).
    function setTurn(a) {
      turn = a;
      if (playing) return;                               // the next film frame takes it
      sv.setCameraOrbit({ angle: sv.getCameraOrbit().angle + (turn - turned) }); turned = turn;
      retarget();
    }

    var playing = false, raf = 0, clock = 0, last = 0, driftAngle = END.angle, userOwns = false, turn = 0, turned = 0;
    var spent = 0, frames = 0, settle = 0;
    // Off screen, the film waits where it is. Every film frame moves the camera, and a
    // viewer asked to redraw off screen still draws one frame to keep its canvas current:
    // a film left running behind the page drew the whole circuit about 30 times a second
    // for nobody and halved the frame rate of the stage the visitor scrolled to
    // (measured 2026-10-16). The tab being hidden needs nothing: rAF stops there.
    var inView = true, io = null;
    if (canvas && typeof IntersectionObserver === 'function') {
      io = new IntersectionObserver(function (entries) {
        inView = entries[entries.length - 1].isIntersecting;
        if (inView && playing && !raf && !disposed) { resume(); }
      });
      io.observe(canvas);
    }
    function resume() { last = performance.now(); settle = 0; frames = 0; spent = 0; raf = requestAnimationFrame(tick); }
    function tick(now) {
      raf = 0;
      if (disposed || !playing || !inView) return;
      var dt = Math.min(0.1, (now - last) / 1000); last = now;
      // Late frames: after a short settle, a second of film under 48 frames steps the ladder
      // down one rung. It never climbs back: a picture that changes twice is worse than one
      // that settles a rung low.
      if (++settle > 45 && opts.adaptive !== false) {
        frames++; spent += dt;
        if (frames >= 60) { if (spent > 60 / 48 && tier > 0) { setTier(tier - 1); settle = 0; } frames = 0; spent = 0; }
      }
      clock += dt;
      fx(clock);
      if (clock < END.t) {
        place(sample('angle', clock), sample('height', clock), sample('radius', clock), sample('fov', clock), sample('lift', clock));
      } else {
        driftAngle += DRIFT * dt;
        place(driftAngle, END.height, END.radius, END.fov, END.lift);
      }
      raf = requestAnimationFrame(tick);
    }
    function play() {
      if (disposed || playing) return;
      if (userOwns) {                   // resume from where the visitor left the camera
        var o = sv.getCameraOrbit(); driftAngle = o.angle - turn; END = { t: 0, angle: o.angle - turn, height: o.height, radius: o.radius / zoom, fov: o.fov, lift: o.target[1] - CAR[1] };
        clock = Math.max(clock, FX.redsBack + 2); userOwns = false;   // the reds stay as they are
      }
      playing = true;
      rain(true);
      resume();
    }
    function pause() { playing = false; if (raf) cancelAnimationFrame(raf); raf = 0; rain(false); }
    // A still: the pose the film ends on (reduced motion, or the poster capture).
    function hold(t) {
      pause(); userOwns = false; END = SHOTS[SHOTS.length - 1]; driftAngle = END.angle; clock = t == null ? END.t : t;
      fx(clock);
      place(sample('angle', clock), sample('height', clock), sample('radius', clock), sample('fov', clock), sample('lift', clock));
    }

    // The visitor's hand on the picture ends the film: the camera is theirs. The rain goes
    // on under their hand; it stops with the page's own pause.
    function grab() { var was = playing; playing = false; if (raf) cancelAnimationFrame(raf); raf = 0; userOwns = true; if (was && opts.onGrab) opts.onGrab(); }
    function moved(e) { if (!playing && (e.buttons || e.type === 'touchmove')) retarget(); }
    if (canvas) {
      canvas.addEventListener('mousedown', grab);
      canvas.addEventListener('touchstart', grab, { passive: true });
      canvas.addEventListener('mousemove', moved);
      canvas.addEventListener('touchmove', moved, { passive: true });
    }

    fx(0);
    place(SHOTS[0].angle, SHOTS[0].height, SHOTS[0].radius, SHOTS[0].fov, SHOTS[0].lift);
    return {
      play: play, pause: pause, hold: hold, setFrame: setFrame, setTurn: setTurn, setTier: setTier,
      get tier() { return tier; }, get playing() { return playing; }, get clock() { return clock; }, duration: END.t,
      dispose: function () {
        disposed = true; pause();
        if (io) io.disconnect();
        if (canvas) {
          canvas.removeEventListener('mousedown', grab); canvas.removeEventListener('touchstart', grab);
          canvas.removeEventListener('mousemove', moved); canvas.removeEventListener('touchmove', moved);
        }
      }
    };
  }

  window.SVScenes = window.SVScenes || {};
  window.SVScenes.circuit = {
    model: '/models/platforms/NightCircuit.glb',
    // Opaque canvas on the night sky colour; the picture is the whole stage.
    create: { transparent: false, backgroundColor: [0.012, 0.016, 0.03, 1], renderMode: 'onDemand', fov: 30, autoRotate: false, iblUrl: '/environments/circuit_ibl.ktx', iblIntensity: 34000 },
    setup: setup
  };
})();

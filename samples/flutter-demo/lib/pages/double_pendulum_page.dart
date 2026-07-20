import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

/// Physics tab — the cross-platform **Double Pendulum** demo (issue #1332).
///
/// #1221 shipped this chaotic two-link pendulum on Android, iOS and Web. The
/// Flutter sample app is a *bridge-feature showcase* — the `flutter_sceneview`
/// PlatformView has no per-frame transform-mutation API, so rather than adding
/// substantial new bridge surface (tracked as a follow-up), this page renders
/// the pendulum with a Flutter [CustomPainter].
///
/// The simulation itself ([_DoublePendulumSim]) is a 1:1 Dart port of the
/// shared, platform-independent `DoublePendulum` simulation in `sceneview-core`
/// (`sceneview-core/.../physics/DoublePendulum.kt`) — same symplectic
/// (semi-implicit) Euler integrator, same Lagrangian angular-acceleration
/// equations, same `1/240 s` sub-stepping. The web demo mirrors the same math
/// in JavaScript; keep the three in sync. Adapted from
/// [@radcli14](https://github.com/radcli14)'s MIT-licensed
/// [`twolinks`](https://github.com/radcli14/twolinks).
///
/// Matches the Android / iOS / web behavior: link-length and gravity sliders
/// plus a reset button, real-time chaotic motion.
class DoublePendulumPage extends StatefulWidget {
  const DoublePendulumPage({super.key});

  @override
  State<DoublePendulumPage> createState() => _DoublePendulumPageState();
}

class _DoublePendulumPageState extends State<DoublePendulumPage>
    with SingleTickerProviderStateMixin {
  // Slider state — same ranges as the web demo's Physics panel.
  double _length1 = 0.5;
  double _length2 = 0.5;
  double _gravity = 9.8;

  late _DoublePendulumSim _sim = _seedSim();
  late final Ticker _ticker;
  Duration _lastTick = Duration.zero;
  bool _running = true;

  @override
  void initState() {
    super.initState();
    _ticker = createTicker(_onTick)..start();
  }

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }

  /// Build a fresh simulation from the current slider values. Both links start
  /// raised so the first frame already shows dramatic motion — identical
  /// seeding to the Android `DoublePendulumDemo` and the web demo.
  _DoublePendulumSim _seedSim() => _DoublePendulumSim(
        length1: _length1,
        length2: _length2,
        angle1: _DoublePendulumSim.halfPi,
        angle2: _DoublePendulumSim.halfPi * 0.6,
        gravity: _gravity,
        damping: 0.04,
      );

  void _onTick(Duration elapsed) {
    if (!_running) {
      _lastTick = elapsed;
      return;
    }
    final dt = _lastTick == Duration.zero
        ? 0.0
        : (elapsed - _lastTick).inMicroseconds / 1e6;
    _lastTick = elapsed;
    if (dt > 0) {
      setState(() => _sim.step(dt));
    }
  }

  void _reset() {
    setState(() {
      _sim = _seedSim();
      _running = true;
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Double Pendulum'),
        actions: [
          IconButton(
            tooltip: _running ? 'Pause' : 'Resume',
            icon: Icon(_running ? Icons.pause : Icons.play_arrow),
            onPressed: () => setState(() => _running = !_running),
          ),
        ],
      ),
      body: Column(
        children: [
          // -- Live chaotic simulation --
          Expanded(
            child: Container(
              width: double.infinity,
              color: theme.colorScheme.surfaceContainerHighest,
              child: CustomPaint(
                painter: _PendulumPainter(
                  sim: _sim,
                  linkColor: theme.colorScheme.primary,
                  pivotColor: theme.colorScheme.tertiary,
                  trailColor: theme.colorScheme.primary.withValues(alpha: 0.35),
                ),
                child: const SizedBox.expand(),
              ),
            ),
          ),

          // -- Controls --
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'A chaotic two-link pendulum. The integrator mirrors the '
                    'shared DoublePendulum simulation in sceneview-core (KMP) '
                    'that drives the Android, iOS and web demos.',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                  const SizedBox(height: 8),
                  _slider(
                    label: 'Upper link',
                    value: _length1,
                    min: 0.2,
                    max: 0.8,
                    suffix: '${_length1.toStringAsFixed(2)} m',
                    onChanged: (v) => setState(() {
                      _length1 = v;
                      _sim = _seedSim();
                    }),
                  ),
                  _slider(
                    label: 'Lower link',
                    value: _length2,
                    min: 0.2,
                    max: 0.8,
                    suffix: '${_length2.toStringAsFixed(2)} m',
                    onChanged: (v) => setState(() {
                      _length2 = v;
                      _sim = _seedSim();
                    }),
                  ),
                  _slider(
                    label: 'Gravity',
                    value: _gravity,
                    min: 1.6,
                    max: 20.0,
                    suffix: '${_gravity.toStringAsFixed(1)} m/s²',
                    onChanged: (v) => setState(() {
                      _gravity = v;
                      _sim = _seedSim();
                    }),
                  ),
                  const SizedBox(height: 4),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      onPressed: _reset,
                      icon: const Icon(Icons.refresh),
                      label: const Text('Reset'),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _slider({
    required String label,
    required double value,
    required double min,
    required double max,
    required String suffix,
    required ValueChanged<double> onChanged,
  }) {
    return Row(
      children: [
        SizedBox(width: 86, child: Text(label)),
        Expanded(
          child: Slider(value: value, min: min, max: max, onChanged: onChanged),
        ),
        SizedBox(
          width: 64,
          child: Text(
            suffix,
            textAlign: TextAlign.end,
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Simulation — 1:1 Dart port of sceneview-core's DoublePendulum.kt
// ---------------------------------------------------------------------------

/// Pure, platform-independent double-pendulum (two-link) physics.
///
/// Verbatim port of `io.github.sceneview.physics.DoublePendulum` /
/// `DoublePendulumState` from `sceneview-core`. Angles are measured in radians
/// from the downward vertical (`0` = straight down, positive = counter-
/// clockwise). The whole link mass is concentrated at its tip — the classic
/// textbook double pendulum. Symplectic (semi-implicit) Euler keeps the
/// energy band tight for this chaotic system.
class _DoublePendulumSim {
  _DoublePendulumSim({
    required this.length1,
    required this.length2,
    required this.angle1,
    required this.angle2,
    required this.gravity,
    this.damping = 0.0,
  });

  /// Quarter turn in radians — convenient horizontal initial angle.
  static const double halfPi = math.pi / 2;

  /// Largest dt a single integration sub-step uses — mirrors
  /// `DoublePendulum.MAX_TIME_STEP`. A raw 60 Hz frame drifts visibly;
  /// `1/240 s` keeps symplectic Euler's energy band tight.
  static const double maxTimeStep = 1 / 240;

  final double length1;
  final double length2;
  final double gravity;
  final double damping;

  /// Point mass (kg) at each link tip. Both default to 1 kg — this demo does
  /// not expose a mass slider (mirroring the Android / iOS / web demos).
  final double mass1 = 1.0;
  final double mass2 = 1.0;

  double angle1;
  double angle2;

  /// Angular velocity (rad/s) of each link. Both links start at rest.
  double omega1 = 0.0;
  double omega2 = 0.0;

  /// Advance the simulation by [deltaSeconds], sub-stepping at [maxTimeStep]
  /// resolution — mirrors `DoublePendulum.step`.
  void step(double deltaSeconds) {
    if (deltaSeconds <= 0) return;
    var remaining = math.min(deltaSeconds, 1.0); // never simulate >1s at once
    while (remaining > 0) {
      final dt = math.min(remaining, maxTimeStep);
      _integrate(dt);
      remaining -= dt;
    }
  }

  /// One symplectic-Euler integration sub-step of fixed [dt]. Verbatim port of
  /// `DoublePendulum.integrate` — see the Kotlin source for the derivation of
  /// the angular accelerations (standard point-mass double-pendulum
  /// Lagrangian form, https://en.wikipedia.org/wiki/Double_pendulum).
  void _integrate(double dt) {
    final l1 = length1, l2 = length2;
    final m1 = mass1, m2 = mass2;
    final a1 = angle1, a2 = angle2;
    final w1 = omega1, w2 = omega2;
    final g = gravity;

    final delta = a1 - a2;
    final cosD = math.cos(delta);
    final sinD = math.sin(delta);

    final den1 = l1 * (2 * m1 + m2 - m2 * math.cos(2 * delta));
    final den2 = l2 * (2 * m1 + m2 - m2 * math.cos(2 * delta));
    // Guard against the degenerate zero-length / zero-mass case.
    if (den1 == 0 || den2 == 0) return;

    final acc1 = (-g * (2 * m1 + m2) * math.sin(a1) -
            m2 * g * math.sin(a1 - 2 * a2) -
            2 * sinD * m2 * (w2 * w2 * l2 + w1 * w1 * l1 * cosD)) /
        den1;

    final acc2 = (2 *
            sinD *
            (w1 * w1 * l1 * (m1 + m2) +
                g * (m1 + m2) * math.cos(a1) +
                w2 * w2 * l2 * m2 * cosD)) /
        den2;

    // Symplectic Euler: update velocities first, then advance the angles
    // from the *new* velocities.
    final dampFactor = (1 - damping * dt).clamp(0.0, 1.0);
    omega1 = (w1 + acc1 * dt) * dampFactor;
    omega2 = (w2 + acc2 * dt) * dampFactor;
    angle1 = a1 + omega1 * dt;
    angle2 = a2 + omega2 * dt;
  }

  /// Joint position (tip of the upper link) in pivot-relative meters.
  /// +Y is up; this returns +Y up, matching `DoublePendulumState.joint`.
  math.Point<double> get joint => math.Point(
        length1 * math.sin(angle1),
        -length1 * math.cos(angle1),
      );

  /// Free tip of the lower link in pivot-relative meters (+Y up).
  math.Point<double> get tip {
    final j = joint;
    return math.Point(
      j.x + length2 * math.sin(angle2),
      j.y - length2 * math.cos(angle2),
    );
  }
}

// ---------------------------------------------------------------------------
// Renderer
// ---------------------------------------------------------------------------

/// Paints the two links + pivot of a [_DoublePendulumSim] and a short fading
/// trail behind the free tip so the chaotic motion is easy to read.
class _PendulumPainter extends CustomPainter {
  _PendulumPainter({
    required this.sim,
    required this.linkColor,
    required this.pivotColor,
    required this.trailColor,
  });

  final _DoublePendulumSim sim;
  final Color linkColor;
  final Color pivotColor;
  final Color trailColor;

  // Persistent trail of recent tip positions (canvas-space), oldest first.
  static final List<Offset> _trail = <Offset>[];
  static const int _maxTrail = 64;

  @override
  void paint(Canvas canvas, Size size) {
    // Pivot is fixed in the upper third so the swinging mass stays framed.
    final pivot = Offset(size.width / 2, size.height * 0.32);
    // Meters → pixels: keep the fully-extended pendulum within the canvas.
    final maxReach = sim.length1 + sim.length2;
    final scale = (math.min(size.width, size.height) * 0.40) /
        math.max(maxReach, 0.001);

    // +Y is up in the sim, down in canvas space → negate Y.
    Offset toCanvas(math.Point<double> p) =>
        pivot + Offset(p.x * scale, -p.y * scale);

    final joint = toCanvas(sim.joint);
    final tip = toCanvas(sim.tip);

    // -- Fading tip trail --
    _trail.add(tip);
    if (_trail.length > _maxTrail) _trail.removeAt(0);
    for (var i = 1; i < _trail.length; i++) {
      final t = i / _trail.length;
      final paint = Paint()
        ..color = trailColor.withValues(alpha: trailColor.a * t)
        ..strokeWidth = 2 + 2 * t
        ..strokeCap = StrokeCap.round;
      canvas.drawLine(_trail[i - 1], _trail[i], paint);
    }

    // -- Links --
    final linkPaint = Paint()
      ..color = linkColor
      ..strokeWidth = 6
      ..strokeCap = StrokeCap.round;
    canvas.drawLine(pivot, joint, linkPaint);
    canvas.drawLine(joint, tip, linkPaint..strokeWidth = 5);

    // -- Masses --
    canvas.drawCircle(joint, 9, Paint()..color = linkColor);
    canvas.drawCircle(tip, 11, Paint()..color = linkColor);

    // -- Fixed pivot marker --
    canvas.drawCircle(pivot, 6, Paint()..color = pivotColor);
  }

  @override
  bool shouldRepaint(_PendulumPainter oldDelegate) => true;
}

// End-to-end test of keystone.js in a simulated 3D scene: a wall 3 m in front of the phone, a
// projector throwing an off-axis picture onto it, and a phone camera with pitch/roll/yaw. Renders the
// camera frame, runs detection + level rectification + solve, applies the result, and checks that
// the picture ON THE WALL is rectangular, level and 16:9.
// Run: ../.tools/node/bin/node test/keystone-test.js
'use strict';
const K = require('../assets/web/keystone.js');

const DEG = Math.PI / 180, WALL_Z = 3;
const ALL_1000 = { ltx: 1000, lty: 1000, rtx: 1000, rty: 1000, lbx: 1000, lby: 1000, rbx: 1000, rby: 1000 };
const MODE2 = K.layout(2, false, false);   // the verified installmode-2 layout
const UNIT = [[0, 0], [1, 0], [1, 1], [0, 1]];

let seed = 11;
const rand = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);

// Rotation matrices (row-major 3x3). World/camera axes: x right, y down, z forward.
const Rx = a => [1, 0, 0, 0, Math.cos(a), -Math.sin(a), 0, Math.sin(a), Math.cos(a)];
const Ry = a => [Math.cos(a), 0, Math.sin(a), 0, 1, 0, -Math.sin(a), 0, Math.cos(a)];
const Rz = a => [Math.cos(a), -Math.sin(a), 0, Math.sin(a), Math.cos(a), 0, 0, 0, 1];
const mul = (A, B) => { const C = []; for (let r = 0; r < 3; r++) for (let c = 0; c < 3; c++) C.push(A[r * 3] * B[c] + A[r * 3 + 1] * B[3 + c] + A[r * 3 + 2] * B[6 + c]); return C; };
const mv = (M, v) => [M[0] * v[0] + M[1] * v[1] + M[2] * v[2], M[3] * v[0] + M[4] * v[1] + M[5] * v[2], M[6] * v[0] + M[7] * v[1] + M[8] * v[2]];
const T = M => [M[0], M[3], M[6], M[1], M[4], M[7], M[2], M[5], M[8]];

function makeCamera({ pitch = 0, roll = 0, yaw = 0, W, H, f, screenAngle = 0, pos = [0, 0, 0] }) {
  // Camera axes in world: yaw about y, then pitch up (forward tilts towards -y), then roll about forward.
  const R = mul(Ry(yaw * DEG), mul(Rx(pitch * DEG), Rz(roll * DEG)));
  // Gravity sensor: world up (0,-1,0) in camera coords, then in device coords for this screen angle.
  const upC = mv(T(R), [0, -1, 0]);
  const th = screenAngle * DEG, c = Math.cos(th), s = Math.sin(th);
  const right = upC[0], upward = -upC[1];
  const upDev = [c * right + s * upward, -s * right + c * upward, -upC[2]];
  return { R, W, H, f, upDev, screenAngle, pos };
}

/** Wall picture corners (metres) of the displayed frame for these keystone values. */
function wallQuad(G, values, L) {
  const panel = L.toPanel(values);
  const out = {};
  K.CORNERS.forEach(k => { out[k] = K.applyH(G, panel[k]); });
  return out;
}

function render(cam, G, values, L) {
  const Gi = K.invertH(G);
  const panel = L.toPanel(values);
  const Kf = K.invertH(K.homography(UNIT, K.CORNERS.map(k => panel[k])));
  const data = new Uint8ClampedArray(cam.W * cam.H * 4);
  const cx = cam.W / 2, cy = cam.H / 2;
  for (let y = 0; y < cam.H; y++) for (let x = 0; x < cam.W; x++) {
    const dw = mv(cam.R, [(x + 0.5 - cx) / cam.f, (y + 0.5 - cy) / cam.f, 1]);
    // A lit room like the real frames: tan wall about as bright as the picture's dim corners, and a
    // bright white ceiling above y = -1.6 m. The picture is green, brightest in the middle.
    let c = [15, 12, 10];
    if (dw[2] > 0) {
      const t = (WALL_Z - cam.pos[2]) / dw[2];
      const wy = cam.pos[1] + dw[1] * t;
      c = wy < -1.6 ? [215, 212, 205] : [150, 125, 100];
      const fr = K.applyH(Kf, K.applyH(Gi, [cam.pos[0] + dw[0] * t, wy]));
      if (fr[0] >= 0 && fr[0] <= 1 && fr[1] >= 0 && fr[1] <= 1) {
        const falloff = 1 - 0.35 * Math.hypot(fr[0] - 0.5, fr[1] - 0.5);
        c = [90 * falloff, 235 * falloff, 100 * falloff];
        // The dark "calibrating" label in the middle of the pattern (makes a hole in the region).
        if (Math.abs(fr[0] - 0.5) < 0.08 && Math.abs(fr[1] - 0.5) < 0.025) c = [40, 70, 40];
      }
    }
    const o = (y * cam.W + x) * 4;
    for (let i = 0; i < 3; i++) data[o + i] = c[i] + (rand() - 0.5) * 24;
    data[o + 3] = 255;
  }
  return { width: cam.W, height: cam.H, data };
}

function wallQuality(q) {
  return K.quadQuality(q, 16 / 9);
}

let failures = 0;
function check(name, cond, detail) {
  console.log((cond ? 'PASS ' : 'FAIL ') + name + (detail ? '  ' + detail : ''));
  if (!cond) { failures++; process.exitCode = 1; }
}

const cueCounts = {};
function measureOnce(cam, rect, G, values, truth) {
  const det = K.detectQuad(render(cam, G, values, truth), rect.roll);
  const cue = det.error ? 'failed' : det.cue;
  cueCounts[cue] = (cueCounts[cue] || 0) + 1;
  return det.error ? det : { corners: K.mapCorners(rect.H, det.corners) };
}

function scenario(name, rawWall, camOpts, start, opts = {}) {
  if (!opts.quiet) console.log('\n== ' + name);
  const log = opts.quiet ? () => {} : console.log;
  const G = K.homography(UNIT, rawWall);
  const cam = makeCamera(camOpts);
  const rect = K.levelRectifier(cam.upDev, cam.screenAngle, cam.W, cam.H, opts.assumedF || 0.75,
    opts.knowYaw ? (camOpts.yaw || 0) * DEG : 0);
  const truth = opts.truth || MODE2;
  let values = start, L = MODE2;
  if (opts.probe) {
    // What the page does: measure, nudge the "lt" values, measure, infer the flips.
    const m0 = measureOnce(cam, rect, G, values, truth);
    if (m0.error) { check(name + ': probe baseline', false, m0.error); return; }
    const probe = K.makeProbe(values, truth.installmode);
    const m1 = measureOnce(cam, rect, G, probe.values, truth);
    if (m1.error) { check(name + ': probe measure', false, m1.error); return; }
    const flips = K.inferFlips(m0.corners, m1.corners, probe);
    if (flips.error) { check(name + ': probe', false, flips.error); return; }
    check(name + ': probe found the flips', flips.flipX === truth.flipX && flips.flipY === truth.flipY,
      `found flipX=${flips.flipX} flipY=${flips.flipY}`);
    L = K.layout(truth.installmode, flips.flipX, flips.flipY);
    values = probe.values;
  }
  for (let iter = 1; iter <= 3; iter++) {
    const m = measureOnce(cam, rect, G, values, truth);
    if (m.error) { check(name + ': detect ' + iter, false, m.error); return; }
    const res = K.solve(m.corners, values, 16 / 9, L);
    if (res.error) { check(name + ': solve ' + iter, false, res.error); return; }
    const w = wallQuality(wallQuad(G, values, truth));
    log(`iter ${iter}: on wall maxAngleErr=${w.maxAngleError.toFixed(2)}° tilt=${w.tilt.toFixed(2)}° aspect=${w.aspect.toFixed(3)}  (phone pitch ${rect.pitch.toFixed(1)}°)`);
    values = res.values;
  }
  const w = wallQuality(wallQuad(G, values, truth));
  const tol = opts.tolerance || { angle: 0.5, tilt: 0.3, aspect: 0.01 };
  const p = opts.quiet ? name + ': ' : '';
  check(p + 'rectangular on the wall', w.maxAngleError < tol.angle, `maxAngleErr=${w.maxAngleError.toFixed(2)}°`);
  check(p + 'level on the wall', Math.abs(w.tilt) < tol.tilt, `tilt=${w.tilt.toFixed(2)}°`);
  check(p + '16:9 on the wall', w.aspectError < tol.aspect, `aspect=${w.aspect.toFixed(3)}`);
  log('values ' + JSON.stringify(values));
}

// Raw (uncorrected) projections on the wall, metres, y down (negative = above the phone).
const SIDE = [[-1.0, -1.25], [1.05, -1.1], [1.0, -0.05], [-0.95, 0.05]];
const BELOW = [[-1.05, -1.3], [1.05, -1.28], [0.92, -0.1], [-0.9, -0.12]];

scenario('phone upright in portrait, projector off to the side',
  SIDE, { pitch: 0, W: 960, H: 1280, f: 960 }, ALL_1000);
scenario('phone tilted up 15° towards the screen (portrait)',
  BELOW, { pitch: 15, W: 960, H: 1280, f: 960 }, ALL_1000);
scenario('phone held landscape, tilted up 10° and rolled 6°',
  SIDE, { pitch: 10, roll: 6, W: 1280, H: 960, f: 960, screenAngle: 90 },
  { ltx: 1000, lty: 1000, rtx: 1000, rty: 985, lbx: 935, lby: 955, rbx: 910, rby: 1000 });
scenario('phone held landscape the other way (screen angle -90°), tilted up 8°',
  BELOW, { pitch: 8, roll: -3, W: 1280, H: 960, f: 960, screenAngle: -90 }, ALL_1000);
scenario('phone on its side with rotation lock on (frames arrive sideways)',
  SIDE, { pitch: 8, roll: 90, W: 960, H: 1280, f: 960, screenAngle: 0 }, ALL_1000);
scenario('rotation lock, turned the other way, with the test nudge in mode 0',
  BELOW, { pitch: 10, roll: -88, W: 960, H: 1280, f: 960, screenAngle: 0 }, K.layout(0, false, false).noCorrection(),
  { truth: K.layout(0, false, false), probe: true });
scenario('focal length guessed 10% wrong, 12° pitch',
  BELOW, { pitch: 12, W: 960, H: 1280, f: 960 }, ALL_1000,
  { assumedF: 0.825, tolerance: { angle: 1.0, tilt: 0.5, aspect: 0.03 } });
scenario('phone turned 8° from the wall, wall direction unknown (known limitation: ~3° error)',
  SIDE, { pitch: 5, yaw: 8, W: 960, H: 1280, f: 960 }, ALL_1000,
  { tolerance: { angle: 3.5, tilt: 3.5, aspect: 0.05 } });
scenario('phone turned 8° from the wall, wall direction measured',
  SIDE, { pitch: 5, yaw: 8, W: 960, H: 1280, f: 960 }, ALL_1000, { knowYaw: true });
scenario('standing 1.2 m to the right, turned 20° left, tilted up 12°, wall direction measured',
  BELOW, { pitch: 12, yaw: -20, pos: [1.2, 0, 0], W: 960, H: 1280, f: 960 }, ALL_1000, { knowYaw: true });

console.log('\n== every installmode and flip, mapping unknown to the page (found with the probe)');
for (let mode = 0; mode < 4; mode++) for (const fx of [false, true]) for (const fy of [false, true]) {
  const truth = K.layout(mode, fx, fy);
  scenario(`mode ${mode} flipX=${fx} flipY=${fy}`, BELOW, { pitch: 12, W: 960, H: 1280, f: 960 },
    truth.noCorrection(), { truth, probe: true, quiet: true });
}
scenario('probe when a corner is already far in (probe goes outward)', SIDE, { pitch: 6, W: 960, H: 1280, f: 960 },
  { ltx: 780, lty: 760, rtx: 1000, rty: 1000, lbx: 1000, lby: 1000, rbx: 1000, rby: 1000 },
  { truth: MODE2, probe: true });

console.log('\n== orientation sensor conversion');
{
  const up = K.upFromOrientation(90, 0);
  check('upright portrait => up = +y', Math.abs(up[1] - 1) < 1e-9 && Math.abs(up[0]) < 1e-9 && Math.abs(up[2]) < 1e-9, JSON.stringify(up.map(v => +v.toFixed(3))));
  const flat = K.upFromOrientation(0, 0);
  check('flat face-up => up = +z', Math.abs(flat[2] - 1) < 1e-9, JSON.stringify(flat.map(v => +v.toFixed(3))));
  // beta > 90: top edge tipped towards you, screen facing down a bit, so the rear camera looks up.
  const r = K.levelRectifier(K.upFromOrientation(105, 0), 0, 960, 1280, 0.75);
  check('beta 105° => camera pitched up 15°', Math.abs(r.pitch - 15) < 1e-6, `pitch=${r.pitch.toFixed(2)}`);
  const r2 = K.levelRectifier(K.upFromOrientation(75, 0), 0, 960, 1280, 0.75);
  check('beta 75° => camera pitched down 15°', Math.abs(r2.pitch + 15) < 1e-6, `pitch=${r2.pitch.toFixed(2)}`);
  // Heading: upright phone (beta 90) turned by alpha. Turning right (clockwise from above) lowers alpha.
  const h0 = K.cameraHeading(0, 90, 0), h1 = K.cameraHeading(-10, 90, 0);
  const dh = ((h1 - h0) * 180 / Math.PI + 540) % 360 - 180;
  check('alpha -10° => camera turned 10° right', Math.abs(dh - 10) < 1e-6, `dHeading=${dh.toFixed(2)}°`);
  // Heading stays meaningful when the phone is also tilted up.
  const h2 = K.cameraHeading(-10, 100, 0);
  const dh2 = ((h2 - h0) * 180 / Math.PI + 540) % 360 - 180;
  check('heading unaffected by pitch', Math.abs(dh2 - 10) < 1e-6, `dHeading=${dh2.toFixed(2)}°`);
  const leanRight = K.upFromOrientation(90 - 1e-6, 10);  // near-upright, gamma 10 (rotated about device y)
  check('finite for gamma near upright', leanRight.every(Number.isFinite));
}

console.log('\n== panel/value round trip');
const v = { ltx: 900, lty: 950, rtx: 800, rty: 1000, lbx: 777, lby: 999, rbx: 750, rby: 870 };
const back = MODE2.fromPanel(MODE2.toPanel(v));
check('round trip (mode 2)', Object.keys(v).every(k => back[k] === v[k]));
for (let mode = 0; mode < 4; mode++) for (const fx of [false, true]) for (const fy of [false, true]) {
  const L = K.layout(mode, fx, fy);
  const w = L.fromPanel({ tl: [0.1, 0.2], tr: [0.95, 0.05], br: [0.8, 0.9], bl: [0.02, 0.77] });
  const b = L.fromPanel(L.toPanel(w));
  if (!K.KEYS.every(k => b[k] === w[k])) check(`round trip mode ${mode} flips ${fx}/${fy}`, false);
}
check('round trip (all modes and flips)', true);
{
  const L0 = K.layout(0, false, false);
  const none = L0.noCorrection();
  check('mode 0 "no correction" is all 0', K.KEYS.every(k => none[k] === 0), JSON.stringify(none));
  const n = L0.nudge(none, 'tl', 'right', 5);
  check('mode 0: top-left right = ltx 5', n.ltx === 5 && n.lty === 0);
  const m = MODE2.nudge(ALL_1000, 'tl', 'right', 5);
  check('mode 2: top-left right = ltx 995 (matches the wall test)', m.ltx === 995);
  const r = MODE2.nudge(ALL_1000, 'br', 'up', 5);
  check('mode 2: bottom-right up = rby 995 (matches the wall test)', r.rby === 995);
}

console.log('\n== steeper than the keystone range can fix');
{
  const G = K.homography(UNIT, [[-1.0, -1.5], [1.1, -0.8], [1.1, -0.6], [-1.0, 0.1]]);
  const cam = makeCamera({ W: 960, H: 1280, f: 960 });
  const rect = K.levelRectifier(cam.upDev, 0, 960, 1280, 0.75);
  const det = K.detectQuad(render(cam, G, ALL_1000, MODE2), rect.roll);
  const res = det.error ? det : K.solve(K.mapCorners(rect.H, det.corners), ALL_1000, 16 / 9, MODE2);
  check('reports it cannot fit', !!res.error, res.error || JSON.stringify(res.values));
}

console.log('detection cues used: ' + JSON.stringify(cueCounts));
console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');

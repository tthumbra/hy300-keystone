// Generates fixtures for the Swift tests from the web page's keystone.js, so both implementations are
// checked against the same answers. Run from ios/: ../.tools/node/bin/node Tests/make-fixtures.js
'use strict';
const fs = require('fs'), path = require('path');
const K = require('../../app/assets/web/keystone.js');
const out = path.join(__dirname, 'KeystoneCoreTests/Fixtures');
fs.mkdirSync(out, { recursive: true });

let seed = 2024;
const rand = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
const UNIT = [[0, 0], [1, 0], [1, 1], [0, 1]];
const quadObj = a => ({ tl: a[0], tr: a[1], br: a[2], bl: a[3] });

// ---- solver: random off-axis projections, every installmode and flip
const solveCases = [];
for (let mode = 0; mode < 4; mode++) for (const fx of [false, true]) for (const fy of [false, true]) {
  const L = K.layout(mode, fx, fy);
  for (let i = 0; i < 3; i++) {
    // Raw projection on the wall (metres, y down), a random mild keystone.
    const raw = [[-1 + rand() * 0.15, -1.2 + rand() * 0.15], [1 - rand() * 0.15, -1.2 + rand() * 0.2],
      [1 - rand() * 0.2, 0 - rand() * 0.15], [-1 + rand() * 0.2, 0 - rand() * 0.1]];
    const G = K.homography(UNIT, raw);
    const values = L.fromPanel(quadObj([[rand() * 0.1, rand() * 0.1], [1 - rand() * 0.1, rand() * 0.1],
      [1 - rand() * 0.1, 1 - rand() * 0.1], [rand() * 0.1, 1 - rand() * 0.1]]));
    const panel = L.toPanel(values);
    const observed = {};
    K.CORNERS.forEach(k => { observed[k] = K.applyH(G, panel[k]); });
    const res = K.solve(observed, values, 16 / 9, L);
    solveCases.push({ installmode: mode, flipX: fx, flipY: fy, observed, values, result: res.error ? { error: res.error } : { values: res.values, target: res.target } });
  }
}
// One that can't fit.
{
  const L = K.layout(2, false, false), values = L.noCorrection();
  const G = K.homography(UNIT, [[-1.0, -1.5], [1.1, -0.8], [1.1, -0.6], [-1.0, 0.1]]);
  const panel = L.toPanel(values), observed = {};
  K.CORNERS.forEach(k => { observed[k] = K.applyH(G, panel[k]); });
  solveCases.push({ installmode: 2, flipX: false, flipY: false, observed, values, result: { error: K.solve(observed, values, 16 / 9, L).error } });
}
fs.writeFileSync(path.join(out, 'solve.json'), JSON.stringify(solveCases));

// ---- layouts: round trips, nudges, no-correction
const layoutCases = [];
for (let mode = 0; mode < 4; mode++) for (const fx of [false, true]) for (const fy of [false, true]) {
  const L = K.layout(mode, fx, fy);
  const panel = quadObj([[0.1, 0.2], [0.95, 0.05], [0.8, 0.9], [0.02, 0.77]]);
  const values = L.fromPanel(panel);
  const nudges = [];
  for (const w of K.CORNERS) for (const d of ['left', 'right', 'up', 'down']) nudges.push({ wall: w, dir: d, result: L.nudge(values, w, d, 5) });
  layoutCases.push({ installmode: mode, flipX: fx, flipY: fy, panel, values, toPanel: L.toPanel(values), noCorrection: L.noCorrection(), nudges });
}
fs.writeFileSync(path.join(out, 'layout.json'), JSON.stringify(layoutCases));

// ---- test nudge
const probeCases = [];
for (let mode = 0; mode < 4; mode++) for (const fx of [false, true]) for (const fy of [false, true]) {
  const truth = K.layout(mode, fx, fy);
  const G = K.homography(UNIT, [[-1, -1.2], [1, -1.15], [0.95, 0], [-0.9, -0.05]]);
  for (const start of [truth.noCorrection(), truth.fromPanel(quadObj([[0.22, 0.21], [0.95, 0.05], [0.9, 0.9], [0.05, 0.95]]))]) {
    const probe = K.makeProbe(start, mode);
    const measure = v => { const p = truth.toPanel(v), o = {}; K.CORNERS.forEach(k => { o[k] = K.applyH(G, p[k]); }); return o; };
    const before = measure(start), after = measure(probe.values);
    probeCases.push({ installmode: mode, start, probe, before, after, flips: K.inferFlips(before, after, probe) });
  }
}
fs.writeFileSync(path.join(out, 'probe.json'), JSON.stringify(probeCases));

// ---- detection: synthetic frames of a lit room (tan wall, white ceiling), green and grey pictures
const detCases = [];
function frame(name, W, H, quadPx, pictureColor, wallColor, ceilingY) {
  const Hm = K.invertH(K.homography(UNIT, quadPx));
  const data = new Uint8Array(W * H * 4);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    let c = y < ceilingY ? [215, 212, 205] : wallColor;
    const f = K.applyH(Hm, [x + 0.5, y + 0.5]);
    if (f[0] >= 0 && f[0] <= 1 && f[1] >= 0 && f[1] <= 1) {
      const fall = 1 - 0.35 * Math.hypot(f[0] - 0.5, f[1] - 0.5);
      c = pictureColor.map(v => v * fall);
      if (Math.abs(f[0] - 0.5) < 0.08 && Math.abs(f[1] - 0.5) < 0.025) c = [40, 70, 40];
    }
    const o = (y * W + x) * 4;
    for (let i = 0; i < 3; i++) data[o + i] = Math.max(0, Math.min(255, Math.round(c[i] + (rand() - 0.5) * 24)));
    data[o + 3] = 255;
  }
  fs.writeFileSync(path.join(out, name + '.rgba'), data);
  const det = K.detectQuad({ width: W, height: H, data }, 0);
  detCases.push({ name, width: W, height: H, result: det.error ? { error: det.reason } : { corners: det.corners, cue: det.cue, threshold: det.threshold } });
}
frame('green-lit-room', 480, 320, [[90, 70], [400, 60], [395, 250], [100, 245]], [90, 235, 100], [150, 125, 100], 40);
frame('green-tilted', 480, 320, [[60, 90], [420, 40], [430, 280], [70, 260]], [80, 230, 90], [140, 118, 95], 25);
frame('grey-dim-room', 480, 320, [[90, 70], [400, 60], [395, 250], [100, 245]], [215, 215, 210], [60, 50, 40], 0);
frame('green-cut-off', 480, 320, [[-20, 70], [400, 60], [395, 250], [-10, 245]], [90, 235, 100], [150, 125, 100], 40);
fs.writeFileSync(path.join(out, 'detect.json'), JSON.stringify(detCases));

console.log(`fixtures: ${solveCases.length} solve, ${layoutCases.length} layout, ${probeCases.length} probe, ${detCases.length} detect`);
detCases.forEach(d => console.log('  ' + d.name + ': ' + (d.result.error || d.result.cue + ' thr ' + d.result.threshold)));

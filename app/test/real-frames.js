// Runs detectQuad on frames shared by the phone (app/frames/*.jpg + .txt from pull-frames.sh) and
// writes annotated copies to app/frames-annotated/ for looking at.
// Run: ../.tools/node/bin/node test/real-frames.js [max-annotated]
'use strict';
const fs = require('fs'), path = require('path');
const jpeg = require('./node_modules/jpeg-js');
const K = require('../assets/web/keystone.js');

const dir = path.join(__dirname, '../frames'), out = path.join(__dirname, '../frames-annotated');
fs.mkdirSync(out, { recursive: true });
const maxAnnotated = +(process.argv[2] || 12);
const files = fs.readdirSync(dir).filter(f => f.endsWith('.jpg')).sort();
const counts = {};
let annotated = 0, ms = 0;

for (const f of files) {
  let info = {};
  try { info = JSON.parse(fs.readFileSync(path.join(dir, f.replace('.jpg', '.txt')), 'utf8')); } catch (e) {}
  const img = jpeg.decode(fs.readFileSync(path.join(dir, f)), { useTArray: true, formatAsRGBA: true });
  const t0 = Date.now();
  const det = K.detectQuad(img, info.roll || 0);
  ms += Date.now() - t0;
  const key = det.error ? 'FAIL ' + det.reason : 'ok via ' + det.cue;
  counts[key] = (counts[key] || 0) + 1;
  const want = process.env.ONLY === 'ok' ? !det.error : process.env.ONLY === 'fail' ? !!det.error : true;
  if (want && annotated < maxAnnotated) {
    annotated++;
    if (!det.error) drawQuad(img, det.corners);
    fs.writeFileSync(path.join(out, f), jpeg.encode(img, 85).data);
    console.log(f, key, det.error ? '' : 'thr ' + det.threshold);
  }
}
console.log(files.length + ' frames:', counts, `avg ${(ms / files.length).toFixed(0)} ms/frame (laptop)`);

function drawQuad(img, c) {
  const pts = K.CORNERS.map(k => c[k]);
  for (let i = 0; i < 4; i++) line(img, pts[i], pts[(i + 1) % 4], [255, 0, 255]);
  pts.forEach(p => { for (let d = -8; d <= 8; d++) { dot(img, p[0] + d, p[1], [255, 0, 0]); dot(img, p[0], p[1] + d, [255, 0, 0]); } });
}
function line(img, a, b, col) {
  const n = Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1]));
  for (let i = 0; i <= n; i++) { const x = a[0] + (b[0] - a[0]) * i / n, y = a[1] + (b[1] - a[1]) * i / n; dot(img, x, y, col); dot(img, x + 1, y, col); }
}
function dot(img, x, y, col) {
  x = Math.round(x); y = Math.round(y);
  if (x < 0 || y < 0 || x >= img.width || y >= img.height) return;
  const o = (y * img.width + x) * 4;
  img.data[o] = col[0]; img.data[o + 1] = col[1]; img.data[o + 2] = col[2];
}

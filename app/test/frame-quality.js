// For shared frames: re-detect, remove the phone's tilt (level virtual camera, same maths as the page)
// and report how rectangular the picture was for each set of keystone values that was applied.
// Run: ../.tools/node/bin/node test/frame-quality.js
'use strict';
const fs = require('fs'), path = require('path');
const jpeg = require('./node_modules/jpeg-js');
const K = require('../assets/web/keystone.js');
const FOCAL = 0.72, dir = path.join(__dirname, '../frames');
const groups = new Map();
for (const f of fs.readdirSync(dir).filter(f => f.endsWith('.jpg')).sort()) {
  let info;
  try { info = JSON.parse(fs.readFileSync(path.join(dir, f.replace('.jpg', '.txt')), 'utf8')); } catch (e) { continue; }
  if (!info.orient || !info.values || info.loopPhase === 'apply' || info.loopPhase === 'settle') continue;
  const img = jpeg.decode(fs.readFileSync(path.join(dir, f)), { useTArray: true, formatAsRGBA: true });
  const up = K.upFromOrientation(info.orient.beta, info.orient.gamma);
  const rect = K.levelRectifier(up, info.screenAngle, img.width, img.height, FOCAL, (info.yawToWall || 0) * Math.PI / 180);
  const det = K.detectQuad(img, rect.roll);
  if (det.error || det.cue !== 'green') continue;
  const q = K.quadQuality(K.mapCorners(rect.H, det.corners), 16 / 9);
  const key = JSON.stringify(info.values);
  if (!groups.has(key)) groups.set(key, []);
  groups.get(key).push({ f, q, pitch: rect.pitch, yaw: info.yawToWall });
}
const med = a => { const s = a.slice().sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };
for (const [key, rows] of groups) {
  console.log(key.replace(/"/g, ''));
  console.log(`  ${rows.length} frames  corner error median ${med(rows.map(r => r.q.maxAngleError)).toFixed(2)}° ` +
    `(min ${Math.min(...rows.map(r => r.q.maxAngleError)).toFixed(2)})  tilt ${med(rows.map(r => r.q.tilt)).toFixed(2)}°  ` +
    `aspect ${med(rows.map(r => r.q.aspect)).toFixed(3)}  phone pitch ${med(rows.map(r => r.pitch)).toFixed(1)}°  facing-wall ${med(rows.map(r => r.yaw || 0)).toFixed(1)}°`);
}

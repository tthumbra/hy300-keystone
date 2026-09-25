// Draws the app icon (1024x1024, no alpha): a grey keystoned outline and the green corrected picture.
// Run from ios/: ../.tools/node/bin/node tools/make-icon.js
'use strict';
const fs = require('fs'), zlib = require('zlib'), path = require('path');
const N = 1024, px = Buffer.alloc(N * N * 3);
const set = (x, y, c) => { if (x < 0 || y < 0 || x >= N || y >= N) return; const o = (y * N + x) * 3; px[o] = c[0]; px[o + 1] = c[1]; px[o + 2] = c[2]; };
const inside = (q, x, y) => {   // convex polygon test
  let s = 0;
  for (let i = 0; i < q.length; i++) {
    const a = q[i], b = q[(i + 1) % q.length], c = (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
    if (c !== 0) { if (s && Math.sign(c) !== s) return false; s = Math.sign(c); }
  }
  return true;
};
const bg = [16, 20, 26], grey = [110, 118, 130], green = [61, 220, 111];
const keystoned = [[150, 250], [874, 190], [900, 820], [124, 760]];
const rect = [[232, 312], [792, 312], [792, 712], [232, 712]];
for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) set(x, y, bg);
// grey outline of the keystoned picture (a band between the polygon and a slightly smaller one)
const shrink = q => { const cx = 512, cy = 505; return q.map(p => [cx + (p[0] - cx) * 0.975, cy + (p[1] - cy) * 0.965]); };
const inner = shrink(keystoned);
for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) if (inside(keystoned, x, y) && !inside(inner, x, y)) set(x, y, grey);
for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) if (inside(rect, x, y)) set(x, y, green);
// PNG: 8-bit RGB
const raw = Buffer.alloc((N * 3 + 1) * N);
for (let y = 0; y < N; y++) { raw[y * (N * 3 + 1)] = 0; px.copy(raw, y * (N * 3 + 1) + 1, y * N * 3, (y + 1) * N * 3); }
const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
const crc = b => { let c = 0xffffffff; for (const v of b) c = crcTable[(c ^ v) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([len, td, c]); };
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(N, 0); ihdr.writeUInt32BE(N, 4); ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
const png = Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
const out = path.join(__dirname, '../App/Assets.xcassets/AppIcon.appiconset/icon-1024.png');
fs.writeFileSync(out, png);
console.log('wrote', out, png.length, 'bytes');

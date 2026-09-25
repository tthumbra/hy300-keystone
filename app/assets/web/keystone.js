/*
 * Keystone maths shared by the calibration page and the Node tests.
 *
 * Model. The projector's panel is the unit square (u right, v down, as seen on the wall). The keystone
 * values place the image's corners on the panel; how depends on installmode and on flips the page
 * measures with a probe (see layout()). Wall/photo positions relate to panel positions by one homography H
 * that doesn't depend on the keystone values. So from the current values plus the four observed image
 * corners we get H, pick the target rectangle in observed space, and map it back through H^-1 into
 * new values. Each new observation re-solves H, which absorbs any non-linearity in the value mapping.
 */
(function (root) {
  'use strict';

  var CORNERS = ['tl', 'tr', 'br', 'bl'];
  var KEYS = ['ltx', 'lty', 'rtx', 'rty', 'lbx', 'lby', 'rbx', 'rby'];
  var LIMIT = 0.25;   // ControlCenter allows each corner at most 25% inward on each axis

  // How ControlCenter stores values per persist.sys.installmode (from its AdjustFourActivity): each axis
  // is either direct (0 = no correction, 250 = 25% inward) or inverted (1000 = none, 750 = 25% inward).
  var X_INVERTED = [false, true, true, false], Y_INVERTED = [false, false, true, true];

  // ------------------------------------------------------------------ layouts

  /**
   * Maps the 8 stored values to the picture's corners on the panel (unit square, u right, v down, as
   * seen on the wall). installmode fixes the value encoding; flipX/flipY say whether the value named
   * "l.."/"t.." actually moves the right/bottom corner on the wall. flips are measured by the page
   * with a probe (see makeProbe/inferFlips) — on the wall in installmode 2 they were both false.
   */
  function layout(installmode, flipX, flipY) {
    var invX = X_INVERTED[installmode], invY = Y_INVERTED[installmode];
    if (invX === undefined) throw new Error('unknown installmode ' + installmode);
    function prop(wall) {
      var left = wall[1] === 'l', top = wall[0] === 't';
      if (flipX) left = !left;
      if (flipY) top = !top;
      return (left ? 'l' : 'r') + (top ? 't' : 'b');
    }
    function dec(v, inv) { return (inv ? 1000 - v : v) / 1000; }
    function enc(inward, inv) {
      var n = Math.round(1000 * Math.max(0, Math.min(LIMIT, inward)));
      return inv ? 1000 - n : n;
    }
    return {
      installmode: installmode, flipX: !!flipX, flipY: !!flipY,
      prop: prop,
      inward: function (values, key) { return dec(values[key], key[2] === 'x' ? invX : invY); },
      encode: function (key, inward) { return enc(inward, key[2] === 'x' ? invX : invY); },
      toPanel: function (values) {
        var out = {};
        CORNERS.forEach(function (w) {
          var p = prop(w), ix = dec(values[p + 'x'], invX), iy = dec(values[p + 'y'], invY);
          out[w] = [w[1] === 'l' ? ix : 1 - ix, w[0] === 't' ? iy : 1 - iy];
        });
        return out;
      },
      fromPanel: function (panel) {
        var v = {};
        CORNERS.forEach(function (w) {
          var p = prop(w), q = panel[w];
          v[p + 'x'] = enc(w[1] === 'l' ? q[0] : 1 - q[0], invX);
          v[p + 'y'] = enc(w[0] === 't' ? q[1] : 1 - q[1], invY);
        });
        return v;
      },
      noCorrection: function () {
        var v = {};
        KEYS.forEach(function (k) { v[k] = enc(0, k[2] === 'x' ? invX : invY); });
        return v;
      },
      /** Moves a wall corner one step in a wall direction ('left', 'right', 'up', 'down'). */
      nudge: function (values, wall, dir, step) {
        var v = {}, key = prop(wall) + (dir === 'left' || dir === 'right' ? 'x' : 'y');
        KEYS.forEach(function (k) { v[k] = values[k]; });
        var inwardDir = key[2] === 'x' ? (wall[1] === 'l' ? 'right' : 'left') : (wall[0] === 't' ? 'down' : 'up');
        var inward = dec(values[key], key[2] === 'x' ? invX : invY) + (dir === inwardDir ? step : -step) / 1000;
        v[key] = enc(inward, key[2] === 'x' ? invX : invY);
        return v;
      },
    };
  }

  // Where each panel corner may go: at most 25% inward on each axis.
  var BOXES = {
    tl: [0, LIMIT, 0, LIMIT],
    tr: [1 - LIMIT, 1, 0, LIMIT],
    br: [1 - LIMIT, 1, 1 - LIMIT, 1],
    bl: [0, LIMIT, 1 - LIMIT, 1],
  };

  // ------------------------------------------------------------------ mapping probe

  var PROBE = 0.06;   // probe nudge: 6% of the frame on each axis

  /**
   * Values for a probe: the corner stored under "lt" moved 6% on both axes (inward, or outward if it's
   * already far in). Which wall corner then moves, and which way, reveals the flips.
   */
  function makeProbe(values, installmode) {
    var L = layout(installmode, false, false), v = {};
    KEYS.forEach(function (k) { v[k] = values[k]; });
    var signs = {};
    ['ltx', 'lty'].forEach(function (k) {
      var inward = L.inward(values, k);
      signs[k] = inward + PROBE <= LIMIT ? 1 : -1;
      v[k] = L.encode(k, inward + signs[k] * PROBE);
    });
    return { values: v, signX: signs.ltx, signY: signs.lty };
  }

  /**
   * From picture corners measured before and after a probe (level virtual camera, y down), works out
   * which wall corner the "lt" values control. Returns {flipX, flipY} or {error}.
   */
  function inferFlips(before, after, probe) {
    var moved = CORNERS.map(function (k) {
      return { k: k, dx: after[k][0] - before[k][0], dy: after[k][1] - before[k][1] };
    });
    moved.forEach(function (m) { m.d = Math.hypot(m.dx, m.dy); });
    moved.sort(function (a, b) { return b.d - a.d; });
    var width = Math.hypot(before.tr[0] - before.tl[0], before.tr[1] - before.tl[1]);
    var m = moved[0];
    if (m.d < width * 0.01) return { error: 'The test nudge didn\'t visibly move the picture. Hold the phone still on the picture and try again.' };
    if (moved[1].d > m.d * 0.35) return { error: 'The phone moved during the test nudge. Hold it still and try again.' };
    // The probe moved the corner inward (or outward) on both axes: check that's what happened.
    var inX = m.k[1] === 'l' ? 1 : -1, inY = m.k[0] === 't' ? 1 : -1;
    if (m.dx * inX * probe.signX <= 0 || m.dy * inY * probe.signY <= 0) {
      return { error: 'The projector moved the picture in an unexpected direction during the test nudge — this keystone setup isn\'t supported yet.' };
    }
    return { flipX: m.k[1] === 'r', flipY: m.k[0] === 'b', corner: m.k };
  }

  // ------------------------------------------------------------------ homographies

  /** 3x3 homography (row-major array of 9) mapping 4 src points to 4 dst points. */
  function homography(src, dst) {
    var A = [], b = [];
    for (var i = 0; i < 4; i++) {
      var x = src[i][0], y = src[i][1], X = dst[i][0], Y = dst[i][1];
      A.push([x, y, 1, 0, 0, 0, -x * X, -y * X]); b.push(X);
      A.push([0, 0, 0, x, y, 1, -x * Y, -y * Y]); b.push(Y);
    }
    var h = solveLinear(A, b);
    if (!h) return null;
    return [h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1];
  }

  function solveLinear(A, b) {
    var n = b.length;
    for (var i = 0; i < n; i++) A[i] = A[i].concat([b[i]]);
    for (var c = 0; c < n; c++) {
      var piv = c;
      for (var r = c + 1; r < n; r++) if (Math.abs(A[r][c]) > Math.abs(A[piv][c])) piv = r;
      if (Math.abs(A[piv][c]) < 1e-12) return null;
      var t = A[c]; A[c] = A[piv]; A[piv] = t;
      for (var r2 = 0; r2 < n; r2++) {
        if (r2 === c) continue;
        var f = A[r2][c] / A[c][c];
        for (var k = c; k <= n; k++) A[r2][k] -= f * A[c][k];
      }
    }
    var x = [];
    for (var j = 0; j < n; j++) x.push(A[j][n] / A[j][j]);
    return x;
  }

  function applyH(H, p) {
    var w = H[6] * p[0] + H[7] * p[1] + H[8];
    return [(H[0] * p[0] + H[1] * p[1] + H[2]) / w, (H[3] * p[0] + H[4] * p[1] + H[5]) / w];
  }

  function invertH(m) {
    var a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    var A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    var det = a * A + b * B + c * C;
    if (Math.abs(det) < 1e-15) return null;
    return [A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
            B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
            C / det, -(a * h - b * g) / det, (a * e - b * d) / det];
  }

  function cornersArray(o) { return CORNERS.map(function (k) { return o[k]; }); }

  // ------------------------------------------------------------------ quad checks

  function angleAt(prev, p, next) {
    var ax = prev[0] - p[0], ay = prev[1] - p[1], bx = next[0] - p[0], by = next[1] - p[1];
    var cos = (ax * bx + ay * by) / (Math.hypot(ax, ay) * Math.hypot(bx, by));
    return Math.acos(Math.max(-1, Math.min(1, cos))) * 180 / Math.PI;
  }

  /** How far the observed quad is from an axis-aligned rectangle of the wanted aspect. */
  function quadQuality(o, aspect) {
    var q = cornersArray(o);
    var maxAngle = 0;
    for (var i = 0; i < 4; i++) maxAngle = Math.max(maxAngle, Math.abs(angleAt(q[(i + 3) % 4], q[i], q[(i + 1) % 4]) - 90));
    var top = Math.atan2(o.tr[1] - o.tl[1], o.tr[0] - o.tl[0]) * 180 / Math.PI;
    var width = (Math.hypot(o.tr[0] - o.tl[0], o.tr[1] - o.tl[1]) + Math.hypot(o.br[0] - o.bl[0], o.br[1] - o.bl[1])) / 2;
    var height = (Math.hypot(o.bl[0] - o.tl[0], o.bl[1] - o.tl[1]) + Math.hypot(o.br[0] - o.tr[0], o.br[1] - o.tr[1])) / 2;
    return { maxAngleError: maxAngle, tilt: top, aspect: width / height, aspectError: Math.abs(width / height / aspect - 1) };
  }

  /** Corners must be in order tl, tr, br, bl clockwise (y down) and form a convex quad. */
  function isSaneQuad(o) {
    var q = cornersArray(o);
    for (var i = 0; i < 4; i++) {
      var a = q[i], b = q[(i + 1) % 4], c = q[(i + 2) % 4];
      var cross = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
      if (cross <= 0) return false;
    }
    return true;
  }

  // ------------------------------------------------------------------ solver

  /**
   * observed: {tl,tr,br,bl} picture corners in a 2D space where the goal is an axis-aligned
   *           rectangle (pixels of the level virtual camera), y pointing down.
   * values:   the keystone values that were applied when observed.
   * L:        layout(installmode, flipX, flipY) for how values map to wall corners.
   * Returns { values, target, quality, H } or { error }.
   */
  function solve(observed, values, aspect, L) {
    aspect = aspect || 16 / 9;
    if (!isSaneQuad(observed)) return { error: 'The four corners are not in the expected order (check the photo is upright).' };
    var panel = L.toPanel(values);
    var H = homography(cornersArray(panel), cornersArray(observed));
    var Hi = H && invertH(H);
    if (!Hi) return { error: 'Could not relate the photo to the projector (corners too close together?).' };

    function preimages(cx, cy, w) {
      var h = w / aspect;
      return {
        tl: applyH(Hi, [cx - w / 2, cy - h / 2]), tr: applyH(Hi, [cx + w / 2, cy - h / 2]),
        br: applyH(Hi, [cx + w / 2, cy + h / 2]), bl: applyH(Hi, [cx - w / 2, cy + h / 2]),
      };
    }
    // Outer bound: corners must stay on the panel. Grows monotonically with w.
    function withinPanel(p) {
      return p.tl[0] >= 0 && p.tl[1] >= 0 && p.tr[0] <= 1 && p.tr[1] >= 0 &&
             p.br[0] <= 1 && p.br[1] <= 1 && p.bl[0] >= 0 && p.bl[1] <= 1;
    }
    // Inner bound: at most 25% inward.
    function withinLimits(p) {
      for (var k in BOXES) {
        var bx = BOXES[k], q = p[k];
        if (q[0] < bx[0] - 1e-9 || q[0] > bx[1] + 1e-9 || q[1] < bx[2] - 1e-9 || q[1] > bx[3] + 1e-9) return false;
      }
      return true;
    }
    function largestAt(cx, cy, wMax) {
      var lo = 0, hi = wMax;
      if (withinPanel(preimages(cx, cy, hi))) lo = hi;
      else for (var i = 0; i < 40; i++) {
        var mid = (lo + hi) / 2;
        if (withinPanel(preimages(cx, cy, mid))) lo = mid; else hi = mid;
      }
      return lo > 0 && withinLimits(preimages(cx, cy, lo)) ? lo : 0;
    }

    var full = [[0, 0], [1, 0], [1, 1], [0, 1]].map(function (p) { return applyH(H, p); });
    var xs = full.map(function (p) { return p[0]; }), ys = full.map(function (p) { return p[1]; });
    var x0 = Math.min.apply(null, xs), x1 = Math.max.apply(null, xs);
    var y0 = Math.min.apply(null, ys), y1 = Math.max.apply(null, ys);
    var wMax = x1 - x0;

    var best = null;
    var cx0 = (x0 + x1) / 2, cy0 = (y0 + y1) / 2, spanX = (x1 - x0) / 2, spanY = (y1 - y0) / 2;
    for (var round = 0; round < 4; round++) {
      var n = 24;
      for (var i = 0; i <= n; i++) {
        for (var j = 0; j <= n; j++) {
          var cx = cx0 + spanX * (2 * i / n - 1), cy = cy0 + spanY * (2 * j / n - 1);
          var w = largestAt(cx, cy, wMax);
          if (w > 0 && (!best || w > best.w)) best = { cx: cx, cy: cy, w: w };
        }
      }
      if (!best) break;
      cx0 = best.cx; cy0 = best.cy; spanX /= 6; spanY /= 6;
    }
    if (!best) return { error: 'No rectangle fits within the keystone range (the projector is at too steep an angle).' };

    var p = preimages(best.cx, best.cy, best.w);
    var h = best.w / aspect;
    return {
      values: L.fromPanel(p),
      target: {
        tl: [best.cx - best.w / 2, best.cy - h / 2], tr: [best.cx + best.w / 2, best.cy - h / 2],
        br: [best.cx + best.w / 2, best.cy + h / 2], bl: [best.cx - best.w / 2, best.cy + h / 2],
      },
      reachable: { tl: full[0], tr: full[1], br: full[2], bl: full[3] },
      quality: quadQuality(observed, aspect),
      H: H,
    };
  }

  // ------------------------------------------------------------------ outline detection

  /**
   * Finds the projected picture and its four corners. The pattern is a solid green frame, so the
   * primary cue is "greenness" (G minus the larger of R and B), which a lit tan/white/grey wall
   * doesn't have; plain brightness is the fallback. For each cue it tries a ladder of cut-offs and
   * keeps the lowest one whose region is a clean quadrilateral clear of the frame edges (the lowest
   * keeps the picture's dimmer corners; too low merges the wall in, too high leaves a hot spot).
   * img: {width, height, data: RGBA}. roll: image rotation of "up" in radians (0 = upright), used
   * only to decide which extreme point is which corner.
   * Returns {corners: {tl,tr,br,bl}, cue, threshold, area} or {error, reason}.
   */
  function detectQuad(img, roll) {
    var W = img.width, H = img.height, d = img.data, n = W * H;
    var green = new Uint8Array(n), luma = new Uint8Array(n);
    for (var i = 0, o = 0; i < n; i++, o += 4) {
      var r = d[o], g = d[o + 1], b = d[o + 2];
      luma[i] = (r * 77 + g * 150 + b * 29) >> 8;
      var gm = g - (r > b ? r : b);
      green[i] = gm > 0 ? gm : 0;
    }
    var label = new Int32Array(n), stack = new Int32Array(n);
    var worst = null;
    var cues = [['green', green], ['brightness', luma]];
    for (var c = 0; c < cues.length; c++) {
      var sig = cues[c][1], ladder = thresholdLadder(sig, n);
      if (!ladder) continue;
      var found = null, above = null;
      for (var t = 0; t < ladder.length; t++) {
        var res = regionQuad(sig, W, H, ladder[t], roll, label, stack);
        if (res.corners) { found = res; found.threshold = ladder[t]; continue; }   // lower may still pass
        if (found) break;                                                         // passed, now broken: stop
        if (!worst || rank(res.reason) > rank(worst.reason)) worst = res;
        // The working window can be narrower than a ladder step (lit rooms): when the region goes from
        // "edge still inside the picture" straight to "merged with the wall", scan that gap finely.
        if (res.reason === 'cut' && above !== null) {
          for (var ft = above - 1; ft > ladder[t]; ft--) {
            var fr = regionQuad(sig, W, H, ft, roll, label, stack);
            if (fr.corners) { found = fr; found.threshold = ft; } else if (found) break;
          }
          break;
        }
        if (res.reason !== 'cut') above = ladder[t];
      }
      if (found) { found.cue = cues[c][0]; return found; }
    }
    return worst || { error: 'The picture is too dark to see — point the phone at the projected picture.', reason: 'dark' };
  }

  // Which failure to report when nothing worked: the one that got furthest.
  function rank(reason) { return { dark: 0, small: 1, shape: 2, soft: 3, edge: 3, degenerate: 3, parallel: 3, cut: 4 }[reason] || 0; }

  /** Cut-offs from bright to dim between the signal's Otsu split and its near-maximum. */
  function thresholdLadder(sig, n) {
    var hist = new Float64Array(256);
    for (var i = 0; i < n; i++) hist[sig[i]]++;
    var t0 = otsu(hist, n), acc = 0, top = 255;
    for (var k = 255; k >= 0; k--) { acc += hist[k]; if (acc >= n * 0.01) { top = k; break; } }
    if (top < 30 || top - t0 < 12) return null;          // nothing stands out
    var out = [];
    for (var s = 0; s <= 8; s++) out.push(Math.round(top - (top - t0) * (0.3 + 0.7 * s / 8) - 1));
    out.push(Math.max(1, Math.round(t0 * 0.6)));          // one step below Otsu for low-contrast scenes
    return out;
  }

  /** Largest region above threshold t, checked and fitted with four edge lines. */
  function regionQuad(sig, W, H, t, roll, label, stack) {
    var n = W * H;
    label.fill(0);
    var bestLabel = 0, bestSize = 0, bestTouches = false, next = 0;
    for (var s0 = 0; s0 < n; s0++) {
      if (sig[s0] <= t || label[s0]) continue;
      next++;
      var sp = 0, size = 0, touches = false;
      stack[sp++] = s0; label[s0] = next;
      while (sp) {
        var idx = stack[--sp], x = idx % W, yy = (idx - x) / W;
        size++;
        if (x === 0 || yy === 0 || x === W - 1 || yy === H - 1) touches = true;
        if (x > 0 && !label[idx - 1] && sig[idx - 1] > t) { label[idx - 1] = next; stack[sp++] = idx - 1; }
        if (x < W - 1 && !label[idx + 1] && sig[idx + 1] > t) { label[idx + 1] = next; stack[sp++] = idx + 1; }
        if (yy > 0 && !label[idx - W] && sig[idx - W] > t) { label[idx - W] = next; stack[sp++] = idx - W; }
        if (yy < H - 1 && !label[idx + W] && sig[idx + W] > t) { label[idx + W] = next; stack[sp++] = idx + W; }
      }
      if (size > bestSize) { bestSize = size; bestLabel = next; bestTouches = touches; }
    }
    if (bestSize < n * 0.03) return { error: 'Can\'t see the projected picture — point the phone at it and move closer.', reason: 'small' };
    if (bestTouches) return { error: 'Part of the picture is cut off — step back so all four corners are in view.', reason: 'cut' };

    // Boundary pixels of that region (includes edges of holes; the edge fits ignore those).
    var bx = [], by = [];
    for (var j = 0; j < n; j++) {
      if (label[j] !== bestLabel) continue;
      if (label[j - 1] !== bestLabel || label[j + 1] !== bestLabel || label[j - W] !== bestLabel || label[j + W] !== bestLabel) {
        var px = j % W;
        bx.push(px + 0.5); by.push((j - px) / W + 0.5);
      }
    }

    // Rough corners: extreme boundary points along the four diagonals (rotated by roll).
    var cr = Math.cos(roll || 0), sr = Math.sin(roll || 0);
    var dirs = { tl: [-1, -1], tr: [1, -1], br: [1, 1], bl: [-1, 1] }, rough = {};
    CORNERS.forEach(function (k) {
      var dx = dirs[k][0] * cr - dirs[k][1] * sr, dy = dirs[k][0] * sr + dirs[k][1] * cr;
      var best = -Infinity, bi = 0;
      for (var q = 0; q < bx.length; q++) {
        var v = bx[q] * dx + by[q] * dy;
        if (v > best) { best = v; bi = q; }
      }
      rough[k] = [bx[bi], by[bi]];
    });

    // Refine: fit a line to each edge's boundary points, intersect neighbouring edges.
    var lines = [];
    for (var e = 0; e < 4; e++) {
      var a = rough[CORNERS[e]], b = rough[CORNERS[(e + 1) % 4]];
      var ex = b[0] - a[0], ey = b[1] - a[1], len = Math.hypot(ex, ey);
      if (len < 10) return { error: 'The picture outline isn\'t clear.', reason: 'degenerate' };
      var ux = ex / len, uy = ey / len, tol = Math.max(2, len * 0.02);
      var pts = [];
      for (var q2 = 0; q2 < bx.length; q2++) {
        var rx = bx[q2] - a[0], ry = by[q2] - a[1];
        var along = (rx * ux + ry * uy) / len, off = Math.abs(rx * -uy + ry * ux);
        if (along > 0.08 && along < 0.92 && off < tol) pts.push([bx[q2], by[q2]]);
      }
      if (pts.length < 10) return { error: 'The picture outline isn\'t clear.', reason: 'edge' };
      lines.push(fitLine(pts));
    }
    var corners = {};
    for (var c = 0; c < 4; c++) {
      var p = intersect(lines[(c + 3) % 4], lines[c]);
      if (!p) return { error: 'The picture outline isn\'t clear.', reason: 'parallel' };
      corners[CORNERS[c]] = p;
    }
    // The fitted quad should cover the region well; otherwise something else is bright too.
    var quadArea = polygonArea(cornersArray(corners));
    if (quadArea < bestSize * 0.93 || quadArea > bestSize * 1.07 || !isSaneQuad(corners)) {
      return { error: 'Something else bright is in view (a lamp or window?) — aim at just the picture.', reason: 'shape' };
    }
    // Every edge must be a sharp step. In a lit room a brightness cut-off can follow the projector's
    // soft fall-off inside the picture instead of its real edge; that "edge" has a weak step.
    var steps = edgeSteps(sig, W, H, corners);
    var strongest = Math.max.apply(null, steps), weakest = Math.min.apply(null, steps);
    // Measured on real frames: true edges step 50+ levels even where the picture is dimmest, while an
    // edge that follows the fall-off steps under 10.
    if (weakest < 20 || weakest < strongest * 0.3) {
      return { error: 'Can\'t make out the picture\'s edges clearly — dim the room lights if you can.', reason: 'soft', edgeSteps: steps, corners: undefined };
    }
    return { corners: corners, area: bestSize, edgeSteps: steps };
  }

  /** Mean signal step (inside minus outside, 3 px either side) along the middle of each edge. */
  function edgeSteps(sig, W, H, corners) {
    var q = cornersArray(corners), cx = 0, cy = 0;
    q.forEach(function (p) { cx += p[0] / 4; cy += p[1] / 4; });
    var out = [];
    for (var e = 0; e < 4; e++) {
      var a = q[e], b = q[(e + 1) % 4], len = Math.hypot(b[0] - a[0], b[1] - a[1]);
      var nx = -(b[1] - a[1]) / len, ny = (b[0] - a[0]) / len;
      var mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2;
      if (nx * (mx - cx) + ny * (my - cy) < 0) { nx = -nx; ny = -ny; }   // point outward
      var sum = 0, count = 0;
      for (var i = 1; i < 20; i++) {
        var f = 0.1 + 0.8 * i / 20, px = a[0] + (b[0] - a[0]) * f, py = a[1] + (b[1] - a[1]) * f;
        var ix = Math.round(px - 3 * nx), iy = Math.round(py - 3 * ny), ox = Math.round(px + 3 * nx), oy = Math.round(py + 3 * ny);
        if (ix < 0 || iy < 0 || ox < 0 || oy < 0 || ix >= W || ox >= W || iy >= H || oy >= H) continue;
        sum += sig[iy * W + ix] - sig[oy * W + ox];
        count++;
      }
      out.push(count ? sum / count : 0);
    }
    return out;
  }

  function otsu(hist, total) {
    var sum = 0;
    for (var i = 0; i < 256; i++) sum += i * hist[i];
    var sumB = 0, wB = 0, best = 0, t = 0;
    for (var k = 0; k < 256; k++) {
      wB += hist[k];
      if (!wB) continue;
      var wF = total - wB;
      if (!wF) break;
      sumB += k * hist[k];
      var mB = sumB / wB, mF = (sum - sumB) / wF, between = wB * wF * (mB - mF) * (mB - mF);
      if (between > best) { best = between; t = k; }
    }
    return t;
  }

  /** Total-least-squares line through points: returns {px, py, dx, dy} (point + unit direction). */
  function fitLine(pts) {
    var mx = 0, my = 0;
    pts.forEach(function (p) { mx += p[0]; my += p[1]; });
    mx /= pts.length; my /= pts.length;
    var sxx = 0, sxy = 0, syy = 0;
    pts.forEach(function (p) { var x = p[0] - mx, y = p[1] - my; sxx += x * x; sxy += x * y; syy += y * y; });
    var angle = 0.5 * Math.atan2(2 * sxy, sxx - syy);
    return { px: mx, py: my, dx: Math.cos(angle), dy: Math.sin(angle) };
  }

  function intersect(l1, l2) {
    var den = l1.dx * l2.dy - l1.dy * l2.dx;
    if (Math.abs(den) < 1e-9) return null;
    var t = ((l2.px - l1.px) * l2.dy - (l2.py - l1.py) * l2.dx) / den;
    return [l1.px + t * l1.dx, l1.py + t * l1.dy];
  }

  function polygonArea(q) {
    var a = 0;
    for (var i = 0; i < q.length; i++) { var p = q[i], r = q[(i + 1) % q.length]; a += p[0] * r[1] - r[0] * p[1]; }
    return Math.abs(a) / 2;
  }

  // ------------------------------------------------------------------ phone orientation

  /**
   * World "up" in device coordinates (x right, y towards the top of the screen, z out of the screen)
   * from DeviceOrientationEvent beta/gamma (degrees). Rotation R = Rz(alpha) Rx(beta) Ry(gamma);
   * up in device coords is R's third row, which doesn't depend on alpha.
   */
  function upFromOrientation(beta, gamma) {
    var b = beta * Math.PI / 180, g = gamma * Math.PI / 180;
    return [-Math.cos(b) * Math.sin(g), Math.sin(b), Math.cos(b) * Math.cos(g)];
  }

  /**
   * Heading of the rear camera's viewing direction, in radians clockwise from the sensor's reference
   * direction (seen from above), from DeviceOrientationEvent alpha/beta/gamma. Only differences
   * between two headings are used, so a relative (gyro) alpha is fine.
   */
  function cameraHeading(alpha, beta, gamma) {
    var a = alpha * Math.PI / 180, b = beta * Math.PI / 180, g = gamma * Math.PI / 180;
    var ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b), cg = Math.cos(g), sg = Math.sin(g);
    // Third column of R = Rz(a) Rx(b) Ry(g) (device z in world coords); the camera looks along -z.
    var zx = ca * sg + sa * sb * cg, zy = sa * sg - ca * sb * cg;
    return Math.atan2(-zx, -zy);
  }

  /**
   * Homography from camera image pixels to a virtual camera at the same spot that is level (no pitch,
   * no roll) and, if yawToWall is given, turned to face the wall squarely. upDev: world up in device
   * coords. screenAngle: screen.orientation.angle (the video frame is shown the same way as the
   * screen). fFactor: focal length as a fraction of the image's longer side (~0.75 for a phone's main
   * camera). yawToWall: radians the camera is turned right of the wall's normal (0 = facing it).
   */
  function levelRectifier(upDev, screenAngle, W, H, fFactor, yawToWall) {
    var th = (screenAngle || 0) * Math.PI / 180, c = Math.cos(th), s = Math.sin(th);
    // Image axes in device coords: right = c*x - s*y, up = s*x + c*y. Rear camera looks along -z.
    var right = c * upDev[0] - s * upDev[1], upward = s * upDev[0] + c * upDev[1];
    var upCam = [right, -upward, -upDev[2]];                      // camera: x right, y down, z forward
    var yv = normalize([-upCam[0], -upCam[1], -upCam[2]]);        // virtual "down" = gravity
    var zv = normalize([-yv[2] * yv[0], -yv[2] * yv[1], 1 - yv[2] * yv[2]]);   // forward, made horizontal
    if (yawToWall) zv = rotateAbout(zv, yv, -yawToWall);                     // turn to face the wall
    var xv = cross(yv, zv);
    var f = (fFactor || 0.75) * Math.max(W, H), cx = W / 2, cy = H / 2;
    var Kmat = [f, 0, cx, 0, f, cy, 0, 0, 1], Kinv = [1 / f, 0, -cx / f, 0, 1 / f, -cy / f, 0, 0, 1];
    var R = [xv[0], xv[1], xv[2], yv[0], yv[1], yv[2], zv[0], zv[1], zv[2]];
    return {
      H: mul3(Kmat, mul3(R, Kinv)),
      roll: Math.atan2(upCam[0], -upCam[1]),                      // how far "up" leans in the image
      pitch: Math.asin(Math.max(-1, Math.min(1, upCam[2]))) * 180 / Math.PI,    // + = camera tilted up
    };
  }

  /** Rodrigues rotation of v about unit axis k by angle a (right-handed). */
  function rotateAbout(v, k, a) {
    var c = Math.cos(a), s = Math.sin(a), kv = cross(k, v), d = k[0] * v[0] + k[1] * v[1] + k[2] * v[2];
    return [v[0] * c + kv[0] * s + k[0] * d * (1 - c), v[1] * c + kv[1] * s + k[1] * d * (1 - c), v[2] * c + kv[2] * s + k[2] * d * (1 - c)];
  }

  function normalize(v) { var l = Math.hypot(v[0], v[1], v[2]) || 1; return [v[0] / l, v[1] / l, v[2] / l]; }
  function cross(a, b) { return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]; }
  function mul3(A, B) {
    var C = [];
    for (var r = 0; r < 3; r++) for (var c = 0; c < 3; c++) C.push(A[r * 3] * B[c] + A[r * 3 + 1] * B[3 + c] + A[r * 3 + 2] * B[6 + c]);
    return C;
  }

  function mapCorners(Hm, o) {
    var out = {};
    CORNERS.forEach(function (k) { out[k] = applyH(Hm, o[k]); });
    return out;
  }

  var api = {
    CORNERS: CORNERS, KEYS: KEYS, LIMIT: LIMIT,
    layout: layout, makeProbe: makeProbe, inferFlips: inferFlips,
    homography: homography, applyH: applyH, invertH: invertH,
    quadQuality: quadQuality, isSaneQuad: isSaneQuad, solve: solve,
    detectQuad: detectQuad, upFromOrientation: upFromOrientation, levelRectifier: levelRectifier,
    cameraHeading: cameraHeading,
    mapCorners: mapCorners, polygonArea: polygonArea,
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.Keystone = api;
})(this);

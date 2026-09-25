import Foundation

/// Finds the projected calibration picture (solid green) in a camera frame. Same algorithm as
/// keystone.js detectQuad(), validated on real frames from a lit room:
/// - cue: "greenness" (G minus the larger of R and B), which lit walls, ceilings and lamps lack;
///   plain brightness is the fallback.
/// - for each cue, a ladder of cut-offs from bright to dim, keeping the lowest whose largest region is a
///   clean quadrilateral clear of the frame edges; a fine scan where the working window is narrow.
/// - each edge must be a sharp step (a cut-off following the projector's soft fall-off is rejected).
public enum QuadDetector {
    public enum Cue: String, Sendable { case green, brightness }

    public enum Failure: String, Error, Sendable {
        case dark, small, cut, shape, soft, edge, degenerate, parallel

        public var message: String {
            switch self {
            case .dark: return "Can't see the picture — point the camera at it."
            case .small: return "The picture is too small — move closer."
            case .cut: return "Part of the picture is cut off — step back so all four corners are in view."
            case .shape: return "Something else bright is in view — aim at just the picture."
            case .soft: return "Can't make out the picture's edges clearly — dim the room lights if you can."
            case .edge, .degenerate, .parallel: return "The picture's outline isn't clear."
            }
        }

        /// Which failure to report when nothing worked: the one that got furthest.
        var rank: Int {
            switch self {
            case .dark: return 0
            case .small: return 1
            case .shape: return 2
            case .soft, .edge, .degenerate, .parallel: return 3
            case .cut: return 4
            }
        }
    }

    public struct Detection: Sendable {
        /// Corners in image pixels. Labelled by `roll`; callers with 3D information may relabel.
        public var corners: Quad
        public var cue: Cue
        public var threshold: Int
        public var area: Int
        public var edgeSteps: [Double]
    }

    /// rgba: width*height*4 bytes. roll: rotation of "up" in the image, radians (0 = upright); used only
    /// to decide which extreme point is which corner.
    public static func detect(rgba: [UInt8], width W: Int, height H: Int, roll: Double = 0) -> Result<Detection, Failure> {
        let n = W * H
        var green = [UInt8](repeating: 0, count: n), luma = [UInt8](repeating: 0, count: n)
        rgba.withUnsafeBufferPointer { d in
            for i in 0..<n {
                let o = i * 4
                let r = Int(d[o]), g = Int(d[o + 1]), b = Int(d[o + 2])
                luma[i] = UInt8((r * 77 + g * 150 + b * 29) >> 8)
                let gm = g - max(r, b)
                green[i] = UInt8(max(0, gm))
            }
        }
        var label = [Int32](repeating: 0, count: n), stack = [Int32](repeating: 0, count: n)
        var worst: Failure? = nil
        for (cue, sig) in [(Cue.green, green), (Cue.brightness, luma)] {
            guard let ladder = thresholdLadder(sig) else { continue }
            var found: Detection? = nil
            var above: Int? = nil
            ladderLoop: for t in ladder {
                let res = regionQuad(sig, W, H, t, roll, &label, &stack)
                if case .success(var det) = res {
                    det.threshold = t; det.cue = cue
                    found = det
                    continue ladderLoop                           // lower may still pass
                }
                guard case .failure(let f) = res else { continue }
                if found != nil { break ladderLoop }              // passed, now broken: stop
                if worst == nil || f.rank > worst!.rank { worst = f }
                // The working window can be narrower than a ladder step (lit rooms): when the region goes
                // from "edge still inside the picture" straight to "merged with the wall", scan finely.
                if f == .cut, let a = above {
                    var ft = a - 1
                    fineLoop: while ft > t {
                        if case .success(var det) = regionQuad(sig, W, H, ft, roll, &label, &stack) {
                            det.threshold = ft; det.cue = cue
                            found = det
                        } else if found != nil {
                            break fineLoop
                        }
                        ft -= 1
                    }
                    break ladderLoop
                }
                if f != .cut { above = t }
            }
            if let f = found { return .success(f) }
        }
        return .failure(worst ?? .dark)
    }

    static func otsu(_ hist: [Double], _ total: Double) -> Int {
        var sum = 0.0
        for i in 0..<256 { sum += Double(i) * hist[i] }
        var sumB = 0.0, wB = 0.0, best = 0.0, t = 0
        for k in 0..<256 {
            wB += hist[k]
            if wB == 0 { continue }
            let wF = total - wB
            if wF == 0 { break }
            sumB += Double(k) * hist[k]
            let mB = sumB / wB, mF = (sum - sumB) / wF, between = wB * wF * (mB - mF) * (mB - mF)
            if between > best { best = between; t = k }
        }
        return t
    }

    /// Cut-offs from bright to dim between the signal's Otsu split and its near-maximum.
    static func thresholdLadder(_ sig: [UInt8]) -> [Int]? {
        let n = sig.count
        var hist = [Double](repeating: 0, count: 256)
        for v in sig { hist[Int(v)] += 1 }
        let t0 = otsu(hist, Double(n))
        var acc = 0.0, top = 255
        for k in stride(from: 255, through: 0, by: -1) {
            acc += hist[k]
            if acc >= Double(n) * 0.01 { top = k; break }
        }
        if top < 30 || top - t0 < 12 { return nil }
        var out: [Int] = []
        for s in 0...8 { out.append(Int(jsRound(Double(top) - Double(top - t0) * (0.3 + 0.7 * Double(s) / 8) - 1))) }
        out.append(max(1, Int(jsRound(Double(t0) * 0.6))))
        return out
    }

    struct Line { var px, py, dx, dy: Double }

    /// Largest region above threshold t, checked and fitted with four edge lines.
    static func regionQuad(_ sig: [UInt8], _ W: Int, _ H: Int, _ t: Int, _ roll: Double,
                           _ label: inout [Int32], _ stack: inout [Int32]) -> Result<Detection, Failure> {
        let n = W * H
        let tt = UInt8(clamping: t)
        var bestLabel: Int32 = 0, bestSize = 0, bestTouches = false, next: Int32 = 0
        sig.withUnsafeBufferPointer { s in
            label.withUnsafeMutableBufferPointer { lab in
                stack.withUnsafeMutableBufferPointer { st in
                    for i in 0..<n { lab[i] = 0 }
                    for s0 in 0..<n {
                        if s[s0] <= tt || lab[s0] != 0 { continue }
                        next += 1
                        var sp = 0, size = 0, touches = false
                        st[sp] = Int32(s0); sp += 1; lab[s0] = next
                        while sp > 0 {
                            sp -= 1
                            let idx = Int(st[sp]), x = idx % W, yy = idx / W
                            size += 1
                            if x == 0 || yy == 0 || x == W - 1 || yy == H - 1 { touches = true }
                            if x > 0 && lab[idx - 1] == 0 && s[idx - 1] > tt { lab[idx - 1] = next; st[sp] = Int32(idx - 1); sp += 1 }
                            if x < W - 1 && lab[idx + 1] == 0 && s[idx + 1] > tt { lab[idx + 1] = next; st[sp] = Int32(idx + 1); sp += 1 }
                            if yy > 0 && lab[idx - W] == 0 && s[idx - W] > tt { lab[idx - W] = next; st[sp] = Int32(idx - W); sp += 1 }
                            if yy < H - 1 && lab[idx + W] == 0 && s[idx + W] > tt { lab[idx + W] = next; st[sp] = Int32(idx + W); sp += 1 }
                        }
                        if size > bestSize { bestSize = size; bestLabel = next; bestTouches = touches }
                    }
                }
            }
        }
        if Double(bestSize) < Double(n) * 0.03 { return .failure(.small) }
        if bestTouches { return .failure(.cut) }

        // Boundary pixels of that region (includes edges of holes; the edge fits ignore those).
        var bx: [Double] = [], by: [Double] = []
        for j in 0..<n where label[j] == bestLabel {
            let l = j > 0 ? label[j - 1] : -1, r = j + 1 < n ? label[j + 1] : -1
            let u = j >= W ? label[j - W] : -1, d = j + W < n ? label[j + W] : -1
            if l != bestLabel || r != bestLabel || u != bestLabel || d != bestLabel {
                let px = j % W
                bx.append(Double(px) + 0.5); by.append(Double(j / W) + 0.5)
            }
        }

        // Rough corners: extreme boundary points along the four diagonals (rotated by roll).
        let cr = cos(roll), sr = sin(roll)
        let dirs: [Corner: (Double, Double)] = [.tl: (-1, -1), .tr: (1, -1), .br: (1, 1), .bl: (-1, 1)]
        var rough: Quad = [:]
        for k in Corner.allCases {
            let (ax, ay) = dirs[k]!
            let dx = ax * cr - ay * sr, dy = ax * sr + ay * cr
            var best = -Double.infinity, bi = 0
            for q in 0..<bx.count {
                let v = bx[q] * dx + by[q] * dy
                if v > best { best = v; bi = q }
            }
            rough[k] = P2(bx[bi], by[bi])
        }

        // Refine: fit a line to each edge's boundary points, intersect neighbouring edges.
        var lines: [Line] = []
        let order = Corner.allCases
        for e in 0..<4 {
            let a = rough[order[e]]!, b = rough[order[(e + 1) % 4]]!
            let ex = b.x - a.x, ey = b.y - a.y, len = hypot(ex, ey)
            if len < 10 { return .failure(.degenerate) }
            let ux = ex / len, uy = ey / len, tol = max(2, len * 0.02)
            var pts: [P2] = []
            for q in 0..<bx.count {
                let rx = bx[q] - a.x, ry = by[q] - a.y
                let along = (rx * ux + ry * uy) / len, off = abs(rx * -uy + ry * ux)
                if along > 0.08 && along < 0.92 && off < tol { pts.append(P2(bx[q], by[q])) }
            }
            if pts.count < 10 { return .failure(.edge) }
            lines.append(fitLine(pts))
        }
        var corners: Quad = [:]
        for c in 0..<4 {
            guard let p = intersect(lines[(c + 3) % 4], lines[c]) else { return .failure(.parallel) }
            corners[order[c]] = p
        }
        let quadArea = polygonArea(corners.ordered)
        if quadArea < Double(bestSize) * 0.93 || quadArea > Double(bestSize) * 1.07 || !isSaneQuad(corners) { return .failure(.shape) }
        // Measured on real frames: true edges step 50+ levels even where the picture is dimmest, while an
        // edge that follows the fall-off steps under 10.
        let steps = edgeSteps(sig, W, H, corners)
        let strongest = steps.max()!, weakest = steps.min()!
        if weakest < 20 || weakest < strongest * 0.3 { return .failure(.soft) }
        return .success(Detection(corners: corners, cue: .green, threshold: t, area: bestSize, edgeSteps: steps))
    }

    /// Total-least-squares line through points.
    static func fitLine(_ pts: [P2]) -> Line {
        var mx = 0.0, my = 0.0
        for p in pts { mx += p.x; my += p.y }
        mx /= Double(pts.count); my /= Double(pts.count)
        var sxx = 0.0, sxy = 0.0, syy = 0.0
        for p in pts { let x = p.x - mx, y = p.y - my; sxx += x * x; sxy += x * y; syy += y * y }
        let angle = 0.5 * atan2(2 * sxy, sxx - syy)
        return Line(px: mx, py: my, dx: cos(angle), dy: sin(angle))
    }

    static func intersect(_ l1: Line, _ l2: Line) -> P2? {
        let den = l1.dx * l2.dy - l1.dy * l2.dx
        if abs(den) < 1e-9 { return nil }
        let t = ((l2.px - l1.px) * l2.dy - (l2.py - l1.py) * l2.dx) / den
        return P2(l1.px + t * l1.dx, l1.py + t * l1.dy)
    }

    /// Mean signal step (inside minus outside, 3 px either side) along the middle of each edge.
    static func edgeSteps(_ sig: [UInt8], _ W: Int, _ H: Int, _ corners: Quad) -> [Double] {
        let q = corners.ordered
        var cx = 0.0, cy = 0.0
        for p in q { cx += p.x / 4; cy += p.y / 4 }
        var out: [Double] = []
        for e in 0..<4 {
            let a = q[e], b = q[(e + 1) % 4], len = hypot(b.x - a.x, b.y - a.y)
            var nx = -(b.y - a.y) / len, ny = (b.x - a.x) / len
            let mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2
            if nx * (mx - cx) + ny * (my - cy) < 0 { nx = -nx; ny = -ny }
            var sum = 0.0, count = 0
            for i in 1..<20 {
                let f = 0.1 + 0.8 * Double(i) / 20, px = a.x + (b.x - a.x) * f, py = a.y + (b.y - a.y) * f
                let ix = Int(jsRound(px - 3 * nx)), iy = Int(jsRound(py - 3 * ny))
                let ox = Int(jsRound(px + 3 * nx)), oy = Int(jsRound(py + 3 * ny))
                if ix < 0 || iy < 0 || ox < 0 || oy < 0 || ix >= W || ox >= W || iy >= H || oy >= H { continue }
                sum += Double(sig[iy * W + ix]) - Double(sig[oy * W + ox])
                count += 1
            }
            out.append(count > 0 ? sum / Double(count) : 0)
        }
        return out
    }
}

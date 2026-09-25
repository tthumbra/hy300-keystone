import Foundation

/// The 8 keystone values as ControlCenter stores them (persist.display.keystone_*).
public struct KeystoneValues: Equatable, Codable, Sendable {
    public static let keys = ["ltx", "lty", "rtx", "rty", "lbx", "lby", "rbx", "rby"]
    public var ltx = 0, lty = 0, rtx = 0, rty = 0, lbx = 0, lby = 0, rbx = 0, rby = 0

    public init() {}

    public init(_ d: [String: Int]) {
        for k in Self.keys { self[k] = d[k] ?? 0 }
    }

    public subscript(key: String) -> Int {
        get {
            switch key {
            case "ltx": return ltx
            case "lty": return lty
            case "rtx": return rtx
            case "rty": return rty
            case "lbx": return lbx
            case "lby": return lby
            case "rbx": return rbx
            case "rby": return rby
            default: preconditionFailure("unknown key \(key)")
            }
        }
        set {
            switch key {
            case "ltx": ltx = newValue
            case "lty": lty = newValue
            case "rtx": rtx = newValue
            case "rty": rty = newValue
            case "lbx": lbx = newValue
            case "lby": lby = newValue
            case "rbx": rbx = newValue
            case "rby": rby = newValue
            default: preconditionFailure("unknown key \(key)")
            }
        }
    }

    public var dictionary: [String: Int] { Dictionary(uniqueKeysWithValues: Self.keys.map { ($0, self[$0]) }) }

    /// Largest change of any value.
    public func maxChange(from o: KeystoneValues) -> Int { Self.keys.map { abs(self[$0] - o[$0]) }.max()! }
}

/// ControlCenter allows each corner at most 25% inward on each axis.
public let keystoneLimit = 0.25

/// How ControlCenter stores values per persist.sys.installmode (from its AdjustFourActivity): each axis
/// is either direct (0 = no correction, 250 = 25% inward) or inverted (1000 = none, 750 = 25% inward).
let xInverted = [false, true, true, false], yInverted = [false, false, true, true]

/// Maps the 8 stored values to the picture's corners on the panel (unit square, u right, v down, as
/// seen on the wall). installmode fixes the encoding; flipX/flipY say whether the values named "l.."/"t.."
/// actually move the right/bottom corner on the wall — measured with a test nudge (Probe).
public struct Layout: Sendable {
    public let installmode: Int, flipX: Bool, flipY: Bool
    let invX: Bool, invY: Bool

    public init?(installmode: Int, flipX: Bool = false, flipY: Bool = false) {
        guard (0...3).contains(installmode) else { return nil }
        self.installmode = installmode; self.flipX = flipX; self.flipY = flipY
        invX = xInverted[installmode]; invY = yInverted[installmode]
    }

    /// Value-name prefix ("lt", "rb", ...) that moves this wall corner.
    public func prop(_ wall: Corner) -> String {
        var left = wall.isLeft, top = wall.isTop
        if flipX { left.toggle() }
        if flipY { top.toggle() }
        return (left ? "l" : "r") + (top ? "t" : "b")
    }

    func isX(_ key: String) -> Bool { key.hasSuffix("x") }
    func dec(_ v: Int, _ inv: Bool) -> Double { Double(inv ? 1000 - v : v) / 1000 }
    func enc(_ inward: Double, _ inv: Bool) -> Int {
        let n = Int(jsRound(1000 * max(0, min(keystoneLimit, inward))))
        return inv ? 1000 - n : n
    }

    public func inward(_ values: KeystoneValues, _ key: String) -> Double { dec(values[key], isX(key) ? invX : invY) }
    public func encode(_ key: String, _ inward: Double) -> Int { enc(inward, isX(key) ? invX : invY) }

    public func toPanel(_ values: KeystoneValues) -> Quad {
        var out = Quad()
        for w in Corner.allCases {
            let p = prop(w), ix = dec(values[p + "x"], invX), iy = dec(values[p + "y"], invY)
            out[w] = P2(w.isLeft ? ix : 1 - ix, w.isTop ? iy : 1 - iy)
        }
        return out
    }

    public func fromPanel(_ panel: Quad) -> KeystoneValues {
        var v = KeystoneValues()
        for w in Corner.allCases {
            let p = prop(w), q = panel[w]!
            v[p + "x"] = enc(w.isLeft ? q.x : 1 - q.x, invX)
            v[p + "y"] = enc(w.isTop ? q.y : 1 - q.y, invY)
        }
        return v
    }

    public func noCorrection() -> KeystoneValues {
        var v = KeystoneValues()
        for k in KeystoneValues.keys { v[k] = enc(0, isX(k) ? invX : invY) }
        return v
    }

    public enum Direction: String, Sendable { case left, right, up, down }

    /// Moves a wall corner one step (in value units) in a wall direction.
    public func nudge(_ values: KeystoneValues, _ wall: Corner, _ dir: Direction, step: Int) -> KeystoneValues {
        var v = values
        let horizontal = dir == .left || dir == .right
        let key = prop(wall) + (horizontal ? "x" : "y")
        let inwardDir: Direction = horizontal ? (wall.isLeft ? .right : .left) : (wall.isTop ? .down : .up)
        let inv = horizontal ? invX : invY
        v[key] = enc(dec(values[key], inv) + Double(dir == inwardDir ? step : -step) / 1000, inv)
        return v
    }
}

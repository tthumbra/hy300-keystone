import CoreVideo
import Foundation

/// Turns ARKit's camera image (bi-planar full-range YCbCr 4:2:0) into a smaller RGBA image for the
/// detector. Done by hand, reading the planes directly, so rows stay in the camera's order (no flips).
enum FrameConverter {
    struct RGBA {
        var data: [UInt8]
        var width: Int
        var height: Int
        /// output pixels per camera pixel
        var scale: Double
    }

    static func rgba(_ pb: CVPixelBuffer, longSide: Int) -> RGBA {
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        let w = CVPixelBufferGetWidthOfPlane(pb, 0), h = CVPixelBufferGetHeightOfPlane(pb, 0)
        // Whole-number reduction factor (2 for the usual 1920x1440 -> 960x720).
        let f = max(1, Int((Double(max(w, h)) / Double(longSide)).rounded()))
        let ow = w / f, oh = h / f
        var out = [UInt8](repeating: 255, count: ow * oh * 4)
        guard let yBase = CVPixelBufferGetBaseAddressOfPlane(pb, 0)?.assumingMemoryBound(to: UInt8.self),
              let cBase = CVPixelBufferGetBaseAddressOfPlane(pb, 1)?.assumingMemoryBound(to: UInt8.self) else {
            return RGBA(data: out, width: ow, height: oh, scale: 1.0 / Double(f))
        }
        let yStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 0), cStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 1)
        out.withUnsafeMutableBufferPointer { o in
            for oy in 0..<oh {
                for ox in 0..<ow {
                    // Average the f x f block of luma; chroma is at half resolution.
                    var ySum = 0
                    for dy in 0..<f {
                        let row = yBase + (oy * f + dy) * yStride + ox * f
                        for dx in 0..<f { ySum += Int(row[dx]) }
                    }
                    let Y = Double(ySum) / Double(f * f)
                    let cRow = cBase + ((oy * f) / 2) * cStride + ((ox * f) / 2) * 2
                    let cb = Double(cRow[0]) - 128, cr = Double(cRow[1]) - 128
                    let r = Y + 1.402 * cr, g = Y - 0.344136 * cb - 0.714136 * cr, b = Y + 1.772 * cb
                    let i = (oy * ow + ox) * 4
                    o[i] = UInt8(max(0, min(255, r)))
                    o[i + 1] = UInt8(max(0, min(255, g)))
                    o[i + 2] = UInt8(max(0, min(255, b)))
                }
            }
        }
        return RGBA(data: out, width: ow, height: oh, scale: 1.0 / Double(f))
    }
}

import CryptoKit
import Foundation

/// What the projector's QR code encodes: https://<ip>:8443/?k=<pairing code>&fp=<cert SHA-256>.
struct ProjectorLink: Codable, Equatable {
    var host: String
    var port: Int
    var pairCode: String
    var fingerprint: String

    init?(_ text: String) {
        guard let c = URLComponents(string: text), c.scheme == "https", let host = c.host,
              let k = c.queryItems?.first(where: { $0.name == "k" })?.value, !k.isEmpty,
              let fp = c.queryItems?.first(where: { $0.name == "fp" })?.value, fp.count == 64 else { return nil }
        self.host = host
        self.port = c.port ?? 443
        self.pairCode = k
        self.fingerprint = fp.lowercased()
    }

    var baseURL: URL { URL(string: "https://\(host):\(port)")! }
}

/// The projector's certificate is self-signed: trust it only if its SHA-256 matches the QR code's.
enum PinnedTrust {
    static func evaluate(_ challenge: URLAuthenticationChallenge, fingerprint: String) -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let leaf = chain.first else {
            return (.performDefaultHandling, nil)
        }
        let der = SecCertificateCopyData(leaf) as Data
        let hex = SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
        return hex == fingerprint ? (.useCredential, URLCredential(trust: trust)) : (.cancelAuthenticationChallenge, nil)
    }
}

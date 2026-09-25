import SwiftUI
import UIKit

/// Laptop-style trackpad. One finger moves the pointer (faster swipes go further), a tap clicks, a
/// two-finger tap right-clicks (Back on Android), two fingers scroll, press-and-hold then move drags.
struct Trackpad: UIViewRepresentable {
    let remote: RemoteConnection

    func makeUIView(context: Context) -> TrackpadView {
        let v = TrackpadView()
        v.remote = remote
        return v
    }

    func updateUIView(_ uiView: TrackpadView, context: Context) { uiView.remote = remote }
}

final class TrackpadView: UIView {
    weak var remote: RemoteConnection?
    private var lastPan = CGPoint.zero, lastScroll = CGPoint.zero, lastDrag = CGPoint.zero
    private let haptic = UIImpactFeedbackGenerator(style: .light)

    static let pointsPerNotch: CGFloat = 14

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        backgroundColor = UIColor.secondarySystemBackground

        let tap = UITapGestureRecognizer(target: self, action: #selector(onTap))
        let twoTap = UITapGestureRecognizer(target: self, action: #selector(onTwoFingerTap))
        twoTap.numberOfTouchesRequired = 2
        let hold = UILongPressGestureRecognizer(target: self, action: #selector(onHold))
        hold.minimumPressDuration = 0.35
        let pan = UIPanGestureRecognizer(target: self, action: #selector(onPan))
        pan.maximumNumberOfTouches = 1
        let scroll = UIPanGestureRecognizer(target: self, action: #selector(onScroll))
        scroll.minimumNumberOfTouches = 2
        scroll.maximumNumberOfTouches = 2
        tap.require(toFail: twoTap)
        pan.require(toFail: hold)          // a quick move is a pan; holding first starts a drag
        [tap, twoTap, hold, pan, scroll].forEach(addGestureRecognizer)
    }

    required init?(coder: NSCoder) { fatalError() }

    @objc private func onTap() {
        haptic.impactOccurred()
        remote?.buttons(1)
        remote?.buttons(0)
    }

    @objc private func onTwoFingerTap() {
        haptic.impactOccurred()
        remote?.buttons(2)
        remote?.buttons(0)
    }

    @objc private func onPan(_ g: UIPanGestureRecognizer) {
        let p = g.translation(in: self)
        if g.state == .began { lastPan = .zero }
        let dx = p.x - lastPan.x, dy = p.y - lastPan.y
        lastPan = p
        // Pointer acceleration: slow moves are precise, quick flicks cross the screen.
        let v = g.velocity(in: self), speed = hypot(v.x, v.y)
        let gain = 1.6 * (1 + min(speed / 900, 2.2))
        remote?.move(Double(dx * gain), Double(dy * gain))
    }

    @objc private func onScroll(_ g: UIPanGestureRecognizer) {
        let p = g.translation(in: self)
        if g.state == .began { lastScroll = .zero }
        // Natural scrolling, like the phone: fingers up moves the content up (wheel down).
        let dy = p.y - lastScroll.y, dx = p.x - lastScroll.x
        lastScroll = p
        remote?.scroll(Double(dy / Self.pointsPerNotch), Double(-dx / Self.pointsPerNotch))
    }

    @objc private func onHold(_ g: UILongPressGestureRecognizer) {
        let p = g.location(in: self)
        switch g.state {
        case .began:
            haptic.impactOccurred(intensity: 1)
            lastDrag = p
            remote?.buttons(1)
        case .changed:
            remote?.move(Double((p.x - lastDrag.x) * 1.6), Double((p.y - lastDrag.y) * 1.6))
            lastDrag = p
        default:
            remote?.buttons(0)
        }
    }
}

/// Invisible view that raises the iPhone keyboard and sends what's typed.
struct KeyCatcher: UIViewRepresentable {
    @Binding var active: Bool
    let remote: RemoteConnection

    func makeUIView(context: Context) -> KeyCatcherView {
        let v = KeyCatcherView()
        v.remote = remote
        v.onResign = { active = false }
        return v
    }

    func updateUIView(_ v: KeyCatcherView, context: Context) {
        v.remote = remote
        DispatchQueue.main.async {
            if active && !v.isFirstResponder { v.becomeFirstResponder() }
            if !active && v.isFirstResponder { v.resignFirstResponder() }
        }
    }
}

final class KeyCatcherView: UIView, UIKeyInput {
    weak var remote: RemoteConnection?
    var onResign: (() -> Void)?

    // UITextInputTraits: plain typing, no autocorrect (every keystroke goes straight to the projector).
    var autocorrectionType: UITextAutocorrectionType = .no
    var autocapitalizationType: UITextAutocapitalizationType = .none
    var spellCheckingType: UITextSpellCheckingType = .no
    var smartQuotesType: UITextSmartQuotesType = .no
    var smartDashesType: UITextSmartDashesType = .no
    var smartInsertDeleteType: UITextSmartInsertDeleteType = .no
    var returnKeyType: UIReturnKeyType = .go

    override var canBecomeFirstResponder: Bool { true }
    var hasText: Bool { true }       // so Delete always works

    func insertText(_ text: String) {
        if text == "\n" { remote?.key(66) }          // KEYCODE_ENTER
        else { remote?.type(text) }
    }

    func deleteBackward() { remote?.key(67) }       // KEYCODE_DEL

    override func resignFirstResponder() -> Bool {
        let r = super.resignFirstResponder()
        onResign?()
        return r
    }
}

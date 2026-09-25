# HY300 keystone calibration

Camera-based keystone correction for the Magcubic HY300 projector (Allwinner H713, Android 11, not
rooted). The projector shows a green calibration picture; a phone measures it and adjusts the
projector's own 4-corner keystone until the picture is rectangular on the wall. The projector keeps
the result (including after a restart) like any keystone set from its menu.

## How it fits together

| Part | What it does |
| --- | --- |
| `app/` | Projector app (Java, no Gradle). Shows a QR code, then the calibration pattern; serves the phone web page and API over HTTPS with a self-signed certificate it makes itself. |
| `helper/` | Runs on the projector as the `shell` user (`app_process`), started by the app through the projector's own adb on `127.0.0.1:5555`. Applies keystone values by writing `persist.display.keystone_*` and nudging ControlCenter's 4-corner screen, which is the only thing allowed to push them to SurfaceFlinger. |
| `app/assets/web/` | Phone web page (any phone, no install): live camera + motion sensors, finds the picture, corrects in a level virtual camera. |
| `ios/` | Optional iPhone app (SwiftUI + ARKit): measures the picture's corners on the wall in 3D with LiDAR, so the phone's angle doesn't matter. |
| `MAPPING.md` | How the 8 keystone values map to corners, per projection mode. |

The maths (`app/assets/web/keystone.js`, ported to Swift in `ios/Sources/KeystoneCore`) works for every
projection mode: each calibration measures which way the projector moves a corner (a "test nudge") the
first time, and the projector remembers the result.

## Build and install the projector app

Needs a JDK 17, Android build-tools 34 and the API 30 `android.jar` in `.tools/` (see `app/build.sh`).

```sh
app/build.sh
adb connect <projector-ip>:5555
adb install -r app/out/keystone-calibrate.apk
```

Open **Keystone Calibrate** on the projector. The first time, the projector asks *Allow USB debugging?*
for the app's own key: tick **Always allow** and press OK. After that the laptop isn't needed.

## Calibrate with a phone browser

Scan the QR code on the projector. The page is HTTPS with the projector's own certificate, so the browser
warns once per phone (Safari: *Show Details → visit this website*). Tap **Start**, allow camera and motion,
aim at the green picture and tap **Start calibration**. Holding the phone sideways fits the picture more
easily; it doesn't need to be held still.

## iPhone app (LiDAR)

GitHub Actions builds an unsigned `KeystoneLiDAR.ipa` and publishes it as a release (see
`.github/workflows/ios.yml`). Either:

- **SideStore:** add the source `https://github.com/tthumbra/hy300-keystone/releases/latest/download/source.json`
  (Sources → +), then install **Keystone** from it. New builds show up as updates.
- **Sideloadly:** download `KeystoneLiDAR.ipa` from the latest release and install it.

Open the app and point it at the projector's QR code.

## Tests

```sh
.tools/node/bin/node app/test/keystone-test.js    # simulated 3D scenes, every projection mode
cd ios && swift test                               # Swift port checked against keystone.js answers
```

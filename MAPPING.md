# HY300 keystone prop mapping (mode 3, persist.sys.installmode=2)

Verified on the wall on 2026-09-24, starting from all 8 = 1000.

| prop | wall corner | lowering the value moves the corner |
|------|-------------|-------------------------------------|
| ltx  | top-left     | right (inward) |
| lty  | top-left     | down (inward)  |
| rtx  | top-right    | left (inward)  |
| rty  | top-right    | down (inward)  |
| lbx  | bottom-left  | right (inward) |
| lby  | bottom-left  | up (inward)    |
| rbx  | bottom-right | left (inward)  |
| rby  | bottom-right | up (inward)    |

- 1000 = corner at the full-frame position; ControlCenter only accepts 750..1000 per axis (max 25% inward).
- Assumed (not yet measured): inward distance as a fraction of frame width/height ≈ (1000 - value) / 1000. The phone loop will measure the real gain.
- Apply live + persist: ks-apply.sh ltx lty rtx rty lbx lby rbx rby

## Other installmodes (added 2026-09-25)

The table above was measured with persist.sys.installmode=2. Value encoding per installmode comes from
ControlCenter: x/y are "direct" (0 = none, 250 = 25% inward) or "inverted" (1000 = none, 750 = 25% in):
mode 0 direct/direct, 1 inverted/direct, 2 inverted/inverted, 3 direct/inverted.
Which wall corner each value moves is NOT assumed: the calibration page measures it at the start of
every calibration with a test nudge (keystone.js makeProbe/inferFlips), so any mode or physical flip works.

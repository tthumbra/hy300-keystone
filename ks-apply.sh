#!/bin/bash
# Apply + persist 8 keystone values (0-1000) live, via ControlCenter's 4-corner screen.
# usage: ks-apply.sh ltx lty rtx rty lbx lby rbx rby
set -e
[ $# -eq 8 ] || { echo "need 8 values"; exit 2; }
for v in "$@"; do [ "$v" -ge 750 ] && [ "$v" -le 1000 ] || { echo "value $v outside 750..1000"; exit 2; }; done
D="adb -s ${ADB_SERIAL:-192.168.1.130:5555} shell"
KEYS=(ltx lty rtx rty lbx lby rbx rby); WANT="$*"
cur(){ for k in "${KEYS[@]}"; do echo -n "$($D getprop persist.display.keystone_$k) "; done | sed 's/ $//'; }
set_all(){ local cmd=""; local i=0; for v in $WANT; do cmd+="setprop persist.display.keystone_${KEYS[$i]} $v;"; i=$((i+1)); done; $D "$cmd"; }
for attempt in 1 2 3; do
  set_all
  $D am start -n com.cptp.console/.MainActivity >/dev/null; sleep 2
  $D input tap 640 431; sleep 2
  $D dumpsys activity activities | grep -q "mResumedActivity.*AdjustFourActivity" || { echo "4-corner screen did not open"; exit 1; }
  # TL is selected on open; for TL, KEYCODE_DPAD_LEFT(21) = x-5, RIGHT(22) = x+5. Each press pushes all 8 values to SurfaceFlinger.
  if [ "$1" -eq 1000 ]; then $D input keyevent 22; else $D input keyevent 21 22; fi
  sleep 1
  $D input keyevent 4; sleep 2; $D input tap 825 391; sleep 1.5; $D input keyevent 3
  GOT=$(cur)
  [ "$GOT" == "$WANT" ] && { echo "applied: $GOT"; exit 0; }
  echo "attempt $attempt mismatch: got [$GOT] want [$WANT]"
done
exit 1

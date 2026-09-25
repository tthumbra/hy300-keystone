#!/bin/bash
# DEVELOPMENT: downloads frames shared by the phone page ("Share camera view") into ./frames/.
# usage: pull-frames.sh <pairing-code> [projector-ip]
set -e
cd "$(dirname "$0")"
K=${1:?pairing code (the k=... part of the QR link)}; IP=${2:-192.168.1.130}
B=https://$IP:8443; mkdir -p frames
for f in $(curl -sk -m 10 -H "X-Pair: $K" $B/api/debug-list | grep -o 'frame-[0-9]*\.\(jpg\|txt\)'); do
  [ -f frames/$f ] || curl -sk -m 10 -H "X-Pair: $K" -o frames/$f "$B/api/debug-get?name=$f"
done
ls frames | tail -6

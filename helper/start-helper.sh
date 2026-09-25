#!/bin/bash
# DEVELOPMENT ONLY: pushes the helper and (re)starts it detached as shell, for testing it with curl
# via "adb forward tcp:38300 tcp:38300" and the printed token. Normally the projector app starts its
# own copy of the helper (bundled in the APK) through adb on 127.0.0.1 and replaces this one.
set -e
cd "$(dirname "$0")"
S=${ADB_SERIAL:-192.168.1.130:5555}
TOKEN=$(openssl rand -hex 16)
adb -s $S push out/ks-helper.dex /data/local/tmp/ks-helper.dex >/dev/null 2>&1
# Separate adb call: pkill -f would otherwise match the shell running the launch line below.
adb -s $S shell 'pkill -f "[c]om.hy300.keystone.Helper"; true'
sleep 0.5
adb -s $S shell "CLASSPATH=/data/local/tmp/ks-helper.dex setsid nohup app_process /system/bin com.hy300.keystone.Helper \
  --token $TOKEN >/data/local/tmp/ks-helper.log 2>&1 </dev/null & true"
sleep 2
adb -s $S shell cat /data/local/tmp/ks-helper.log
echo "token: $TOKEN"

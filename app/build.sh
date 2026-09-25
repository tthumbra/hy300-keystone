#!/bin/bash
# Builds out/keystone-calibrate.apk (app + bundled helper) with the toolchain in ../.tools
set -e
cd "$(dirname "$0")"
T=../.tools; BT=$T/android-14; AJ=$T/android-11/android.jar
export JAVA_HOME=$T/jdk PATH=$T/jdk/bin:$PATH
rm -rf out && mkdir -p out/classes out/dex
$BT/aapt2 link -I $AJ --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 30 --target-sdk-version 30 -o out/unsigned.apk
javac --release 8 -cp $AJ:libs/core-3.5.3.jar -d out/classes \
  $(find src ../helper/src -name '*.java') 2>&1 | grep -v '^Note:' || true
[ -f out/classes/com/hy300/keystone/app/MainActivity.class ] || { echo "javac failed"; exit 1; }
$BT/d8 --min-api 30 --lib $AJ --output out/dex $(find out/classes -name '*.class') libs/core-3.5.3.jar
python3 -c "import zipfile
with zipfile.ZipFile('out/unsigned.apk','a',zipfile.ZIP_DEFLATED) as z: z.write('out/dex/classes.dex','classes.dex')"
$BT/zipalign -f 4 out/unsigned.apk out/aligned.apk
if [ ! -f signing.keystore ]; then
  keytool -genkeypair -keystore signing.keystore -storepass hy300keystone -keypass hy300keystone \
    -alias app -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=HY300 Keystone" >/dev/null
fi
$BT/apksigner sign --ks signing.keystore --ks-pass pass:hy300keystone --out out/keystone-calibrate.apk out/aligned.apk
echo "built out/keystone-calibrate.apk"

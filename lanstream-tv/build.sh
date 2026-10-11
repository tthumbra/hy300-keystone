#!/bin/bash
# Builds out/lanstream-tv.apk with the toolchain in ../.tools (same recipe as ../app/build.sh, plus res/).
set -e
cd "$(dirname "$0")"
T=../.tools; BT=$T/android-14; AJ=$T/android-11/android.jar
export JAVA_HOME=$T/jdk PATH=$T/jdk/bin:$PATH
rm -rf out && mkdir -p out/classes out/dex
$BT/aapt2 compile --dir res -o out/res.zip
$BT/aapt2 link -I $AJ --manifest AndroidManifest.xml out/res.zip \
  --min-sdk-version 30 --target-sdk-version 30 -o out/unsigned.apk
javac --release 8 -cp $AJ -d out/classes $(find src -name '*.java') 2>&1 | grep -v '^Note:' || true
[ -f out/classes/com/hy300/lanstream/MainActivity.class ] || { echo "javac failed"; exit 1; }
$BT/d8 --min-api 30 --lib $AJ --output out/dex $(find out/classes -name '*.class')
python3 -c "import zipfile
with zipfile.ZipFile('out/unsigned.apk','a',zipfile.ZIP_DEFLATED) as z: z.write('out/dex/classes.dex','classes.dex')"
$BT/zipalign -f 4 out/unsigned.apk out/aligned.apk
# Signed with the projector app's key (../app/signing.keystore, created by ../app/build.sh).
$BT/apksigner sign --ks ../app/signing.keystore --ks-pass pass:hy300keystone --out out/lanstream-tv.apk out/aligned.apk
echo "built out/lanstream-tv.apk"

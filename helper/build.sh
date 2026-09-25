#!/bin/bash
# Builds out/ks-helper.dex from src/ using the toolchain in ../.tools
set -e
cd "$(dirname "$0")"
T=../.tools
export JAVA_HOME=$T/jdk PATH=$T/jdk/bin:$PATH
rm -rf out && mkdir -p out/classes
javac --release 8 -cp $T/android-11/android.jar \
  -d out/classes $(find src -name '*.java')
$T/android-14/d8 --min-api 30 --lib $T/android-11/android.jar --output out $(find out/classes -name '*.class')
mv out/classes.dex out/ks-helper.dex
echo "built out/ks-helper.dex"

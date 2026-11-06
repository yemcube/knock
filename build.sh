#!/bin/bash
# Gradle-free build: this box is often too tight on memory for AGP. Output: out/knock.apk
set -e
cd "$(dirname "$0")"
S=/home/harsha/android-toolchain/sdk; BT=$S/build-tools/36.0.0; JAR=$S/platforms/android-36/android.jar
export JAVA_HOME=/home/harsha/android-toolchain/jdk17 PATH=/home/harsha/android-toolchain/jdk17/bin:$PATH
rm -rf out && mkdir -p out/classes out/dex out/gen out/res
$BT/aapt2 compile --dir app/src/main/res -o out/res/res.zip
$BT/aapt2 link -o out/base.apk -I "$JAR" --manifest app/src/main/AndroidManifest.xml -A app/src/main/assets --java out/gen \
  --min-sdk-version 30 --target-sdk-version 36 --version-code 6 --version-name 0.4.0 out/res/res.zip
javac -J-Xmx256m --release 17 -cp "$JAR" -d out/classes $(find app/src/main/java out/gen -name '*.java')
$BT/d8 -JXmx512m --release --min-api 30 --lib "$JAR" --output out/dex $(find out/classes -name '*.class')
python3 -c "import zipfile; zipfile.ZipFile('out/base.apk','a').write('out/dex/classes.dex','classes.dex')"
$BT/zipalign -f 4 out/base.apk out/aligned.apk
$BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --out out/knock.apk out/aligned.apk
echo "built out/knock.apk"

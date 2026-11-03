#!/bin/bash
# Builds "Super Mario Advance Dual X.Y.apk". Run tools/setup_toolchain.sh once first (sysroot, compiler-rt, mGBA + patch).
# Needs: aapt zipalign apksigner dalvik-exchange openjdk-17, android.jar (API 33) at $ANDROID_JAR.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
MGBA=${MGBA:-$HOME/src/mgba}
ANDROID_JAR=${ANDROID_JAR:-$HOME/src/platforms/android-33/android.jar}
VER=$(grep -o 'VERSION = "[^"]*"' "$ROOT/app/src/com/jack/smadual/MainActivity.java" | cut -d'"' -f2)
B=$ROOT/out/apk; rm -rf "$B"; mkdir -p "$B/gen" "$B/classes" "$B/lib/arm64-v8a"
sed -i "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VER\"/" "$ROOT/app/AndroidManifest.xml"
CODE=$(echo "$VER" | awk -F. '{print $1*1000+$2}')
sed -i "s/android:versionCode=\"[^\"]*\"/android:versionCode=\"$CODE\"/" "$ROOT/app/AndroidManifest.xml"
# native: rebuild when any glue file is newer than the library
SO=$ROOT/out/android/libsmadual.so
if [ ! -f "$SO" ] || [ -n "$REBUILD_NATIVE" ] || [ -n "$(find "$ROOT/native" -newer "$SO" -name '*.[ch]')" ]; then
  rm -f "$SO"; make -C "$ROOT/native" TARGET=android MGBA="$MGBA" -j8 android
fi
cp "$SO" "$B/lib/arm64-v8a/"
aapt package -f -m -J "$B/gen" -M "$ROOT/app/AndroidManifest.xml" -S "$ROOT/app/res" -A "$ROOT/app/assets" -I "$ANDROID_JAR" -F "$B/res.apk"
if ! javac -nowarn --release 8 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$B/classes" $(find "$B/gen" "$ROOT/app/src" -name '*.java') > "$B/javac.log" 2>&1; then
  grep -v "^Note:" "$B/javac.log"; echo "javac failed"; exit 1
fi
dalvik-exchange --dex --min-sdk-version=23 --output="$B/classes.dex" "$B/classes"
cp "$B/res.apk" "$B/unsigned.apk"
(cd "$B" && aapt add unsigned.apk classes.dex >/dev/null && aapt add -0 so unsigned.apk lib/arm64-v8a/libsmadual.so >/dev/null)
zipalign -f -p 16 "$B/unsigned.apk" "$B/aligned.apk"
KS=$ROOT/tools/smadual.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass smadual -keypass smadual -alias smadual \
  -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=Super Mario Advance Dual, O=Jack" >/dev/null 2>&1
OUT="$ROOT/out/Super Mario Advance Dual $VER.apk"
apksigner sign --ks "$KS" --ks-pass pass:smadual --key-pass pass:smadual --ks-key-alias smadual --out "$OUT" "$B/aligned.apk"
rm -f "$OUT.idsig"
apksigner verify "$OUT" && ls -la "$OUT"

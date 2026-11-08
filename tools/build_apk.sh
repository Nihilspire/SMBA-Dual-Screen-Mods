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
if [ ! -f "$SO" ] || [ -n "$REBUILD_NATIVE" ] || [ -n "$(find "$ROOT/native" -newer "$SO" -name '*.[ch]' -not -path '*/ctr/*')" ]; then
  # make can't take paths with spaces and the sysroot can't sit on a case-insensitive Windows drive (bionic has
  # headers differing only in case), so such checkouts build in a Linux-side tree linking back to native/ and tools/
  # (run its tools/setup_toolchain.sh once: bash ~/.cache/sma-dual-build/tools/setup_toolchain.sh)
  NROOT=$ROOT
  case "$ROOT" in *' '*|/mnt/*) NROOT=$HOME/.cache/sma-dual-build; mkdir -p "$NROOT/out/android"
    ln -sfn "$ROOT/native" "$NROOT/native"; ln -sfn "$ROOT/tools" "$NROOT/tools";; esac
  rm -f "$SO"; make -C "$NROOT/native" ROOT="$NROOT" TARGET=android MGBA="$MGBA" -j8 android
  [ "$NROOT" = "$ROOT" ] || { mkdir -p "$(dirname "$SO")"; cp "$NROOT/out/android/libsmadual.so" "$SO"; }
fi
cp "$SO" "$B/lib/arm64-v8a/"
aapt package -f -m -J "$B/gen" -M "$ROOT/app/AndroidManifest.xml" -S "$ROOT/app/res" -A "$ROOT/app/assets" -I "$ANDROID_JAR" -F "$B/res.apk"
# sources through a quoted argument file: the checkout's path may have spaces in it
find "$B/gen" "$ROOT/app/src" -name '*.java' -printf '"%p"\n' > "$B/sources.txt"
if ! javac -nowarn --release 8 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$B/classes" @"$B/sources.txt" > "$B/javac.log" 2>&1; then
  grep -v "^Note:" "$B/javac.log"; echo "javac failed"; exit 1
fi
dalvik-exchange --dex --min-sdk-version=23 --output="$B/classes.dex" "$B/classes"
cp "$B/res.apk" "$B/unsigned.apk"
(cd "$B" && aapt add unsigned.apk classes.dex >/dev/null && aapt add -0 so unsigned.apk lib/arm64-v8a/libsmadual.so >/dev/null)
zipalign -f -p 16 "$B/unsigned.apk" "$B/aligned.apk"
# Signing key and its password stay out of git (tools/*.keystore*): the password is the one line of $KS.pass.
# A fresh checkout gets a new key with a random password (it can't update an APK signed with the old key).
KS=$ROOT/tools/smadual.keystore
if [ ! -f "$KS" ]; then
  [ -f "$KS.pass" ] || { head -c 18 /dev/urandom | base64 | tr -d '/+=' > "$KS.pass"; chmod 600 "$KS.pass"; }
  P=$(cat "$KS.pass")
  keytool -genkeypair -keystore "$KS" -storepass "$P" -keypass "$P" -alias smadual \
    -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=Super Mario Advance Dual, O=Jack" >/dev/null 2>&1
fi
[ -f "$KS.pass" ] || { echo "missing $KS.pass (the keystore's password, one line)"; exit 1; }
OUT="$ROOT/out/Super Mario Advance Dual $VER.apk"
apksigner sign --ks "$KS" --ks-pass "file:$KS.pass" --ks-key-alias smadual --out "$OUT" "$B/aligned.apk"
rm -f "$OUT.idsig"
apksigner verify "$OUT" && ls -la "$OUT"

#!/bin/bash
# Builds "Super Mario Advance Link X.Y.apk": the Link page on its own, reading the game in RetroArch (linkapp/). No native code.
# The page is app/assets/link (shared with Dual) and the 3D view is Dual's Java: both are compiled in from app/ at build time.
# Needs: aapt zipalign apksigner dalvik-exchange openjdk-17, android.jar (API 33) at $ANDROID_JAR.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ANDROID_JAR=${ANDROID_JAR:-$HOME/src/platforms/android-33/android.jar}
L=$ROOT/linkapp; A=$ROOT/app/src/com/jack/smadual
VER=$(grep -o 'VERSION = "[^"]*"' "$L/src/com/jack/smadual/LinkActivity.java" | cut -d'"' -f2)
CODE=$(echo "$VER" | awk -F. '{print $1*1000+$2}')
sed -i "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VER\"/; s/android:versionCode=\"[^\"]*\"/android:versionCode=\"$CODE\"/" "$L/AndroidManifest.xml"
B=$ROOT/out/linkapk; rm -rf "$B"; mkdir -p "$B/gen" "$B/classes" "$B/assets/link" "$B/assets/menu"
# assets: Dual's Link page and the kit reader it uses
cp "$ROOT/app/assets/link/"* "$B/assets/link/"
cp "$ROOT/app/assets/menu/gbakit.js" "$B/assets/menu/"
aapt package -f -m -J "$B/gen" -M "$L/AndroidManifest.xml" -S "$L/res" -A "$B/assets" -I "$ANDROID_JAR" -F "$B/res.apk"
# Link's own classes, plus Dual's 3D view and game constants (Native here is Link's: see linkapp/src)
{ find "$B/gen" "$L/src" -name '*.java' -printf '"%p"\n'
  for f in Scene3D Renderer3D Gba BezelTheme; do printf '"%s"\n' "$A/$f.java"; done; } > "$B/sources.txt"
if ! javac -nowarn --release 8 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$B/classes" @"$B/sources.txt" > "$B/javac.log" 2>&1; then
  grep -v "^Note:" "$B/javac.log"; echo "javac failed"; exit 1
fi
dalvik-exchange --dex --min-sdk-version=23 --output="$B/classes.dex" "$B/classes"
cp "$B/res.apk" "$B/unsigned.apk"
(cd "$B" && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"
# Signing key and its password stay out of git (tools/*.keystore*): the password is the one line of $KS.pass.
# A fresh checkout gets a new key with a random password (it can't update an APK signed with the old key).
KS=$ROOT/tools/smalink.keystore
if [ ! -f "$KS" ]; then
  [ -f "$KS.pass" ] || { head -c 18 /dev/urandom | base64 | tr -d '/+=' > "$KS.pass"; chmod 600 "$KS.pass"; }
  P=$(cat "$KS.pass")
  keytool -genkeypair -keystore "$KS" -storepass "$P" -keypass "$P" -alias smalink \
    -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=Super Mario Advance Link, O=Jack" >/dev/null 2>&1
fi
[ -f "$KS.pass" ] || { echo "missing $KS.pass (the keystore's password, one line)"; exit 1; }
OUT="$ROOT/out/Super Mario Advance Link $VER.apk"
apksigner sign --ks "$KS" --ks-pass "file:$KS.pass" --ks-key-alias smalink --out "$OUT" "$B/aligned.apk"
rm -f "$OUT.idsig"
apksigner verify "$OUT" && ls -la "$OUT"

#!/bin/bash
# Compiles Link's plain-Java parts (transport, tracker, kit, core), Dual's Scene3D and Gba, and the desktop checks into tests/ra/out.
set -e
D=$(cd "$(dirname "$0")" && pwd); R=$D/../..; A=$R/app/src/com/jack/smadual; L=$R/linkapp/src/com/jack/smadual
O=$D/out; mkdir -p "$O/stub/android/graphics"
cat > "$O/stub/android/graphics/Bitmap.java" <<'J'
package android.graphics;
public class Bitmap { public enum Config { ARGB_8888 } public static Bitmap createBitmap(int w, int h, Config c) { return new Bitmap(); } public void setPixel(int x, int y, int c) {} }
J
javac -nowarn -d "$O" "$O/stub/android/graphics/Bitmap.java" "$D"/*.java "$L/RetroArch.java" "$L/Tracker.java" "$L/KitRom.java" "$L/LinkCore.java" "$L/Native.java" "$A/Scene3D.java" "$A/Gba.java" "$A/BezelTheme.java" 2>&1 | grep -v -e '^Note' -e JAVA_TOOL || true

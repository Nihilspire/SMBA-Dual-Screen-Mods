#!/bin/bash
# Compiles Scene3D (+ Gba, BezelTheme for constants and the metatile table reader) with this harness into tests/scene3d/out.
set -e
D=$(cd "$(dirname "$0")" && pwd); S=$D/../../app/src/com/jack/smadual
mkdir -p "$D/out"
# BezelTheme uses android.graphics.Bitmap: compile against a tiny stub
mkdir -p "$D/out/stub/android/graphics"
cat > "$D/out/stub/android/graphics/Bitmap.java" <<'J'
package android.graphics;
public class Bitmap { public enum Config { ARGB_8888 } public static Bitmap createBitmap(int w, int h, Config c) { return new Bitmap(); } public void setPixel(int x, int y, int c) {} }
J
javac -nowarn -d "$D/out" "$D/out/stub/android/graphics/Bitmap.java" "$D/SceneDump.java" "$D/SceneSeq.java" "$S/Scene3D.java" "$S/Gba.java" "$S/BezelTheme.java" "$S/Native.java" 2>&1 | grep -v -e '^Note' -e JAVA_TOOL || true

#!/bin/bash
# ThemeDump: Dual's BezelTheme on the desktop, for dualscreen.py.
set -e
D=$(cd "$(dirname "$0")" && pwd); A=$D/../../app/src/com/jack/smadual
O=$D/out; rm -rf "$O"; mkdir -p "$O/stub/android/graphics" "$O/stub/com/jack/smadual"
cat > "$O/stub/android/graphics/Bitmap.java" <<'J'
package android.graphics;
public class Bitmap {
  public enum Config { ARGB_8888 }
  public int[] pixels; private int w;
  public static Bitmap createBitmap(int w, int h, Config c) { Bitmap b = new Bitmap(); b.w = w; b.pixels = new int[w * h]; return b; }
  public void setPixel(int x, int y, int c) { pixels[y * w + x] = c; }
}
J
cat > "$O/stub/com/jack/smadual/Native.java" <<'J'
package com.jack.smadual;
final class Native { static int peek(int a) { return 0; } }
J
javac -nowarn -d "$O" "$O/stub/android/graphics/Bitmap.java" "$O/stub/com/jack/smadual/Native.java" "$D/ThemeDump.java" "$A/BezelTheme.java" "$A/Gba.java" 2>&1 | grep -v -e '^Note' -e JAVA_TOOL || true

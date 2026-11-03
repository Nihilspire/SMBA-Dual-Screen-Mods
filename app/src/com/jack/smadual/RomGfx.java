package com.jack.smadual;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.File;
import java.io.FileInputStream;

/** Graphics read from the player's own ROM at runtime (nothing from the game ships in the APK). */
final class RomGfx {
    byte[] rom;
    private final Bitmap[] glyphs = new Bitmap[40];   // ink white, shade grey, on transparent (Gba.FONT_MAP)
    private final Paint paint = new Paint();
    private final Rect src = new Rect(0, 0, 8, 8), dst = new Rect();

    boolean load(File f) {
        try {
            if (!f.exists()) return false;
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            in.close();
            if (b.length < Gba.ROM_FONT + Gba.FONT_BYTES) return false;
            rom = b;
            for (int t = 0; t < 40; t++) glyphs[t] = glyph(Gba.ROM_FONT + t * 32);
            paint.setFilterBitmap(false);
            return true;
        } catch (Exception e) { return false; }
    }

    boolean ready() { return rom != null; }

    /** One 4bpp font tile: value 15 = ink (white), 13/14 = shade (50% grey), 0 = clear. */
    private Bitmap glyph(int off) {
        Bitmap bm = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            int v = (rom[off + y * 4 + (x >> 1)] >> ((x & 1) * 4)) & 15;
            bm.setPixel(x, y, v == 15 ? 0xFFFFFFFF : v != 0 ? 0xFF808080 : 0);
        }
        return bm;
    }

    static int code(char c) {
        if (c >= 'a' && c <= 'z') c = (char) (c - 32);
        if (c == 'Q') c = 'O';
        return Gba.FONT_MAP.indexOf(c);
    }

    /** Draws text in the game's font; px is the size of one GBA pixel on screen. Color tints both tones. */
    void text(Canvas c, String s, float x, float y, float px, int color) {
        if (!ready()) return;
        paint.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.MULTIPLY));
        paint.setAlpha(color >>> 24);
        Paint dot = null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            float bx = x + i * 8 * px;
            if (ch == '%' || ch == ':' || ch == '/') {
                if (dot == null) { dot = new Paint(); dot.setColor(color); }
                if (ch == '%') {
                    c.drawRect(bx + px, y + px, bx + 3 * px, y + 3 * px, dot); c.drawRect(bx + 5 * px, y + 5 * px, bx + 7 * px, y + 7 * px, dot);
                    for (int k = 0; k < 6; k++) c.drawRect(bx + (6 - k) * px, y + (k + 1) * px, bx + (7 - k) * px, y + (k + 2) * px, dot);
                } else if (ch == ':') { c.drawRect(bx + 3 * px, y + 1 * px, bx + 5 * px, y + 3 * px, dot); c.drawRect(bx + 3 * px, y + 4 * px, bx + 5 * px, y + 6 * px, dot); }
                else for (int k = 0; k < 7; k++) c.drawRect(bx + (6 - k) * px, y + k * px, bx + (7 - k) * px, y + (k + 1) * px, dot);
                continue;
            }
            int t = code(ch);
            if (t >= 0) {
                dst.set(Math.round(bx), Math.round(y), Math.round(bx + 8 * px), Math.round(y + 8 * px));
                c.drawBitmap(glyphs[t], src, dst, paint);
            }
        }
        paint.setColorFilter(null);
        paint.setAlpha(255);
    }
}

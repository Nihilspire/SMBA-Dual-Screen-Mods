package com.jack.smadual;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Draws the GBA picture, plus area-themed borders in the space a 3:2 picture leaves on wider screens. */
final class GameView extends View {
    final Object lock = new Object();
    Bitmap frame;                       // written by the emulation thread under lock
    String aspect = "3:2";              // "3:2" (square pixels), "stretch"
    boolean bezels = true, hud = true, integer = false, showStats = false;
    MainActivity host;
    volatile BezelTheme theme;
    /** Overlay mode (3D view): borders only, drawn over the GL view with a hole where the picture is. */
    GameView source;
    volatile String osd = "";
    volatile long osdUntil;
    RomGfx gfx;

    private final Paint pix = new Paint(), fill = new Paint();
    private final Rect src = new Rect();
    final RectF dst = new RectF();
    private BezelTheme shaderFor;
    private BitmapShader shader;
    private final Matrix m = new Matrix();
    private int hudTick;
    private String hudLevel = "", hudChar = "", hudTime = "";
    private int hudHearts, hudMax, hudLives, hudCherries, hudAce;
    private boolean hudInGame;

    GameView(Context c) {
        super(c);
        pix.setFilterBitmap(false);
        pix.setDither(false);
        setBackgroundColor(0xFF000000);
    }

    void layoutGame(int w, int h) {
        float W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;
        if (aspect.equals("stretch")) { dst.set(0, 0, W, H); return; }
        float dispW = w;
        float s = Math.min(W / dispW, H / (float) h);
        if (integer && s >= 1) s = (float) Math.floor(s);
        float gw = dispW * s, gh = h * s;
        float x = (W - gw) / 2f, y = (H - gh) / 2f;
        dst.set(Math.round(x), Math.round(y), Math.round(x + gw), Math.round(y + gh));
    }

    @Override protected void onDraw(Canvas c) {
        if (source != null) { drawOverlay(c); return; }
        Bitmap f;
        synchronized (lock) {
            f = frame;
            if (f == null) { c.drawColor(0xFF000000); return; }
            src.set(0, 0, f.getWidth(), f.getHeight());
            layoutGame(f.getWidth(), f.getHeight());
            float px = dst.height() / f.getHeight();
            drawBezels(c, px);
            c.drawBitmap(f, src, dst, pix);
        }
        drawStats(c);
        drawOsd(c);
    }

    /** 3D mode: the same themed borders, cut out around the 4:3 picture, so terrain the game is still loading at
     *  the screen edges stays hidden. Also carries the stats line and on-screen messages in 3D mode. */
    private void drawOverlay(Canvas c) {
        int w = 240, h = 160;
        synchronized (source.lock) { if (source.frame != null) { w = source.frame.getWidth(); h = source.frame.getHeight(); } }
        layoutGame(w, h);
        float px = dst.height() / h;
        if (bezels) {
            c.save();
            if (android.os.Build.VERSION.SDK_INT >= 26) c.clipOutRect(dst);
            else c.clipRect(dst, android.graphics.Region.Op.DIFFERENCE);
            drawBezels(c, px);
            c.restore();
        }
        drawStats(c);
        drawOsd(c);
        if (System.currentTimeMillis() < osdUntil) postInvalidateDelayed(100);
    }

    private void drawStats(Canvas c) {
        if (showStats && host != null && host.emu != null) {
            float spx = Math.max(1, getHeight() / 200f);
            fill.setColor(0xB0000000);
            String st = host.emu.stats;
            c.drawRect(0, 0, (st.length() * 8 + 4) * spx, 12 * spx, fill);
            if (gfx != null && gfx.ready()) gfx.text(c, st, 2 * spx, 2 * spx, spx, 0xFF58F898);
            if (source != null) postInvalidateDelayed(500);
        }
    }

    /** Short message (e.g. "DEPTH 16") centred near the top of the picture. */
    void showOsd(String text) { osd = text; osdUntil = System.currentTimeMillis() + 1500; postInvalidate(); }
    private void drawOsd(Canvas c) {
        if (System.currentTimeMillis() >= osdUntil || gfx == null || !gfx.ready()) return;
        float spx = Math.max(1, Math.round(getHeight() / 180f));
        String t = osd;
        float tw = (t.length() * 8 + 8) * spx, x = (getWidth() - tw) / 2f, y = dst.top + 12 * spx;
        fill.setColor(0xC0000000);
        c.drawRect(x, y, x + tw, y + 12 * spx, fill);
        gfx.text(c, t, x + 4 * spx, y + 2 * spx, spx, 0xFFFC9838);
    }

    private void drawBezels(Canvas c, float px) {
        c.drawColor(0xFF000000);
        float W = getWidth(), H = getHeight();
        boolean space = dst.left > 2 * px || dst.top > 2 * px;
        if (!bezels || !space) return;
        BezelTheme th = source != null ? source.theme : theme;
        if (th != null && th.block != null) {
            if (shaderFor != th) {
                shader = new BitmapShader(th.block, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
                shaderFor = th;
            }
            m.reset();
            m.setScale(px, px);
            m.postTranslate(dst.left % (16 * px), dst.top % (16 * px));
            shader.setLocalMatrix(m);
            fill.setShader(shader);
            fill.setFilterBitmap(false);
            c.drawRect(0, 0, W, H, fill);
            fill.setShader(null);
            fill.setColor(0x9C000000);
            c.drawRect(0, 0, W, H, fill);
        }
        int accent = th != null ? th.accent : 0xFF3CBCFC;
        int shade = th != null ? th.shade : 0xFF0000A8;
        // Frame: shade, accent, black (in GBA pixels) around the picture
        fill.setColor(shade);
        c.drawRect(dst.left - 4 * px, dst.top - 4 * px, dst.right + 4 * px, dst.bottom + 4 * px, fill);
        fill.setColor(accent);
        c.drawRect(dst.left - 3 * px, dst.top - 3 * px, dst.right + 3 * px, dst.bottom + 3 * px, fill);
        fill.setColor(0xFF000000);
        c.drawRect(dst.left - 1 * px, dst.top - 1 * px, dst.right + 1 * px, dst.bottom + 1 * px, fill);
        if (hud && gfx != null && gfx.ready() && dst.left > 50 * px) drawHud(c, px, accent);
    }

    /** Compact status in the side borders, used when there is no Link screen. */
    private void drawHud(Canvas c, float px, int accent) {
        if (hudTick++ % 10 == 0) readHud();
        if (!hudInGame) return;
        float lx = dst.left / 2f - 24 * px, y = dst.top + 24 * px;
        for (int i = 0; i < hudMax; i++) {               // life meter: filled red / empty
            float tx = lx + i * 10 * px, ty = y - 12 * px;
            fill.setColor(0xFFF8F8F8);
            c.drawRect(tx, ty, tx + 8 * px, ty + 8 * px, fill);
            fill.setColor(i < hudHearts ? 0xFFE03828 : 0xFF283048);
            c.drawRect(tx + px, ty + px, tx + 7 * px, ty + 7 * px, fill);
        }
        gfx.text(c, "*" + hudLives, lx, y, px, 0xFFF8F8F8);
        for (int i = 0; i < 5; i++) {                       // cherries toward the next Starman
            fill.setColor(i < hudCherries ? 0xFFE03828 : 0xFF503020);
            float tx = lx + i * 8 * px + 2 * px, ty = y + 16 * px;
            c.drawRect(tx, ty, tx + 5 * px, ty + 5 * px, fill);
        }
        for (int i = 0; i < 5; i++) {                       // Ace Coins this level
            fill.setColor(((hudAce >> i) & 1) != 0 ? 0xFFF8B800 : 0xFF503020);
            float tx = lx + i * 8 * px + 2 * px, ty = y + 26 * px;
            c.drawRect(tx, ty, tx + 5 * px, ty + 5 * px, fill);
        }
        float rx = dst.right + (getWidth() - dst.right) / 2f, room = (getWidth() - dst.right) / px - 4;
        String lv = hudLevel;
        if (lv.length() * 8 <= room) gfx.text(c, lv, rx - lv.length() * 4 * px, y, px, accent);
        if (hudChar.length() * 8 <= room) gfx.text(c, hudChar, rx - hudChar.length() * 4 * px, y + 12 * px, px, 0xFFF8F8F8);
        gfx.text(c, hudTime, rx - hudTime.length() * 4 * px, y + 28 * px, px, 0xFFC0C0C0);
    }

    private final byte[] trk = new byte[64];
    private void readHud() {
        hudInGame = Gba.inGameOrPause();
        int h = Native.peek(Gba.HEALTH);
        hudHearts = h >= 0xF0 ? 0 : (h >> 4) + 1;
        hudMax = Gba.maxHearts();
        hudLives = Native.peek(Gba.LIVES);
        hudCherries = Math.min(5, Native.peek(Gba.CHERRIES));
        int lvn = Native.peek(Gba.CURRENT_LEVEL);
        hudAce = (Native.peek(Gba.ACE_LEVEL) & 31) | (lvn <= 19 ? Gba.aceSaved(lvn) : 0);   // this visit or on file
        hudLevel = Gba.levelName();
        hudChar = Gba.CHARACTERS[Native.peek(Gba.CHARACTER) & 3];
        Native.tracker(trk);
        long frames = (trk[0] & 255) | (trk[1] & 255) << 8 | (trk[2] & 255) << 16 | (long) (trk[3] & 255) << 24;
        long sec = (long) (frames / Gba.FPS);
        hudTime = String.format("%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }

    private static String two(int v) { return v < 10 ? "0" + v : String.valueOf(v); }
    private static String three(int v) { return v < 10 ? "00" + v : v < 100 ? "0" + v : String.valueOf(v); }
}

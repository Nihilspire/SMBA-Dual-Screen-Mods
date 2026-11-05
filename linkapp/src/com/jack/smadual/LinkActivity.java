package com.jack.smadual;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Super Mario Advance Link: Dual's Link screen on its own, reading the game running in RetroArch (mGBA core) through its
 * network commands on this device's port 55355. The page (assets/link) is Dual's, and gets a window.Native with Dual's calls
 * from the bridge below. It also has what only Link has: the 3D tab, because here no emulator screen can show the 3D view. The
 * window never takes input focus, so the controller stays with RetroArch. Nothing from the game ships: the player picks their
 * own ROM for the map, the art and the 3D view.
 */
public class LinkActivity extends Activity {
    static final String VERSION = "0.1";
    static final long ROM_CRC = Gba.SMA_CRC;
    static final int PICK_ROM = 1, SLOTS = 4;
    static final String[] CAMS = { "low", "tilt", "diorama", "far" };

    WebView web;
    GLSurfaceView gl;
    FrameLayout root;
    final Renderer3D r3d = new Renderer3D();
    final RetroArch ra = new RetroArch();
    final LinkCore core = new LinkCore(ra);
    SharedPreferences prefs;
    File romFile;
    volatile byte[] rom;
    volatile boolean romOk;
    volatile String romName = "", kitB64 = "";
    final Handler ui = new Handler(Looper.getMainLooper());
    private long lastSave;

    private final LinkCore.Sink sink = new LinkCore.Sink() {
        public void frame(byte[] snap, boolean inPlay) { r3d.submit(snap, null, inPlay); gl.requestRender(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        prefs = getSharedPreferences("smalink", MODE_PRIVATE);
        romFile = new File(getFilesDir(), "rom.gba");
        ra.setTarget("127.0.0.1", 55355);
        String tb = prefs.getString("trk", "");
        if (!tb.isEmpty()) { try { core.trk.load(Base64.decode(tb, Base64.NO_WRAP)); } catch (Exception e) { } }
        r3d.hold = true; r3d.minRun = 2;
        apply3d();

        root = new FrameLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Native");
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        gl = new GLSurfaceView(this);
        gl.setZOrderMediaOverlay(true);                         // above the page's window, whatever the WebView does with its layer
        gl.setEGLContextClientVersion(2);
        gl.setEGLConfigChooser(Renderer3D.CONFIG);
        gl.setPreserveEGLContextOnPause(true);
        gl.setRenderer(r3d);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        gl.setVisibility(View.GONE);
        gl.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { js("window.on3dTap&&on3dTap()"); } });
        root.addView(gl, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        immersive();
        new Thread(new Runnable() { public void run() {         // the ROM is 4 MB: not on the main thread
            loadRom();
            ui.post(new Runnable() { public void run() { web.loadUrl("file:///android_asset/link/index.html"); } });
        } }, "rom").start();
    }

    @Override protected void onStart() { super.onStart(); gl.onResume(); ra.start(); }
    @Override protected void onStop() { saveStats(); ra.stop(); gl.onPause(); super.onStop(); }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    void js(final String code) { ui.post(new Runnable() { public void run() { web.evaluateJavascript(code, null); } }); }

    void saveStats() {
        byte[] t;
        synchronized (core.trk) { t = core.trk.bytes(); }
        prefs.edit().putString("trk", Base64.encodeToString(t, Base64.NO_WRAP)).apply();
    }

    // ------------------------------------------------------------------ the player's ROM
    void loadRom() {
        try {
            if (!romFile.exists()) return;
            byte[] b = new byte[(int) romFile.length()];
            FileInputStream in = new FileInputStream(romFile);
            int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            in.close();
            setRom(b, prefs.getString("romName", ""));
        } catch (Exception e) { }
    }
    void setRom(byte[] b, String name) {
        CRC32 c = new CRC32(); c.update(b);
        romOk = c.getValue() == ROM_CRC;
        rom = b; romName = name;
        r3d.scene.rom = romOk ? b : null;
        kitB64 = romOk ? Base64.encodeToString(KitRom.build(b), Base64.NO_WRAP) : "";
    }
    void pickRom() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, PICK_ROM); } catch (Exception e) { }
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_ROM || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(new Runnable() { public void run() {
            String name = "rom";
            Cursor c = null;
            try {
                c = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
                if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
            } catch (Exception e) { } finally { if (c != null) c.close(); }
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 22);
                byte[] buf = new byte[65536]; int r; long n = 0;
                while ((r = in.read(buf)) > 0 && n <= (32 << 20)) { bo.write(buf, 0, r); n += r; }
                in.close();
                byte[] b = bo.toByteArray();
                setRom(b, name);
                if (romOk) {                                    // only keep the right game
                    FileOutputStream out = new FileOutputStream(romFile);
                    out.write(b); out.close();
                    prefs.edit().putString("romName", name).apply();
                }
                js("window.onRom&&onRom()");
            } catch (Exception e) { js("window.onRom&&onRom()"); }
        } }, "rom-pick").start();
    }

    // ------------------------------------------------------------------ 3D settings: the keys and defaults Dual's menu uses
    String cam3d() { String c = prefs.getString("cam3d", "tilt"); for (String k : CAMS) if (k.equals(c)) return c; return "tilt"; }
    void apply3d() {
        r3d.camPreset = cam3d();
        r3d.scene.depth = prefs.getInt("depth3d", 16);
        r3d.scene.back = prefs.getString("back3d", "near");
        r3d.scene.cave = prefs.getBoolean("cave3d", true);
        r3d.scene.light = prefs.getBoolean("light3d", false);
        r3d.scene.thick = prefs.getBoolean("thick3d", true);
        r3d.scene.round = prefs.getBoolean("round3d", false);
    }

    String problem() { return core.problem(rom != null, romOk); }

    /** window.Native for the page: Dual's calls, answered from RetroArch's memory, and the few only Link has. */
    final class Bridge {
        @JavascriptInterface public String version() { return VERSION; }
        @JavascriptInterface public boolean dual() { return true; }
        @JavascriptInterface public boolean link() { return true; }
        @JavascriptInterface public String problem() { return LinkActivity.this.problem(); }
        @JavascriptInterface public String raVersion() { return ra.version; }
        @JavascriptInterface public boolean running() { return problem().isEmpty() && ra.stateFresh(); }
        @JavascriptInterface public String mem(String spec) { return ra.mem(spec); }
        @JavascriptInterface public String video() { byte[] v = ra.video(); return v == null ? "" : Base64.encodeToString(v, Base64.NO_WRAP); }
        @JavascriptInterface public String kit() { return kitB64; }
        @JavascriptInterface public String rom(int off, int len) {
            byte[] r = rom;                                 // a wrong ROM's bytes would come out as a garbled font on the setup screen
            if (r == null || !romOk || off < 0 || len <= 0 || off >= r.length) return "";
            return Base64.encodeToString(r, off, Math.min(len, r.length - off), Base64.NO_WRAP);
        }
        @JavascriptInterface public String romName() { return romName; }
        @JavascriptInterface public void pickRom() { ui.post(new Runnable() { public void run() { LinkActivity.this.pickRom(); } }); }
        @JavascriptInterface public String tracker() {
            long now = System.currentTimeMillis();
            if (now - lastSave > 5000) { lastSave = now; saveStats(); }
            byte[] t;
            synchronized (core.trk) { t = core.trk.bytes(); }
            return Base64.encodeToString(t, Base64.NO_WRAP);
        }
        @JavascriptInterface public void resetStats() { synchronized (core.trk) { core.trk.clear(); } saveStats(); }
        @JavascriptInterface public boolean overlay() { return false; }
        @JavascriptInterface public void close() { }
        @JavascriptInterface public boolean devUsed() { return false; }
        /** RetroArch's own state slots 1-4. It can't list them, so LOAD is always offered. */
        @JavascriptInterface public String slots() {
            StringBuilder s = new StringBuilder("[");
            for (int i = 1; i <= SLOTS; i++) s.append(i > 1 ? "," : "").append("{\"n\":").append(i).append(",\"time\":").append(Math.max(1, prefs.getLong("slot" + i, 1))).append('}');
            return s.append(']').toString();
        }
        /** 1.22.2 has LOAD_STATE_SLOT but no SAVE_STATE_SLOT, and can't report its slot: step down until it stops at -1, up to n, then
         *  SAVE_STATE, 50 ms apart (as Shantae Link, checked live there). About 1.3 s, and it leaves RetroArch on slot n. */
        @JavascriptInterface public boolean saveSlot(int n) {
            if (n < 1 || n > SLOTS) return false;
            List<String> c = new ArrayList<String>();
            for (int i = 0; i < 20; i++) c.add("STATE_SLOT_MINUS");
            for (int i = 0; i <= n; i++) c.add("STATE_SLOT_PLUS");
            c.add("SAVE_STATE");
            ra.sendPaced(c, 50);
            prefs.edit().putLong("slot" + n, System.currentTimeMillis()).apply();
            return true;
        }
        @JavascriptInterface public String thumb(int n) { return ""; }
        @JavascriptInterface public boolean loadSlot(int n) {
            if (n < 1 || n > SLOTS) return false;
            ra.send("LOAD_STATE_SLOT " + n);
            synchronized (core.trk) { core.trk.reset(); }
            return true;
        }
        @JavascriptInterface public void menu() { ra.send("MENU_TOGGLE"); }
        @JavascriptInterface public String settings3d() {
            return "{\"cam3d\":\"" + cam3d() + "\",\"depth3d\":" + prefs.getInt("depth3d", 16) + ",\"back3d\":\"" + prefs.getString("back3d", "near")
                + "\",\"cave3d\":" + prefs.getBoolean("cave3d", true) + ",\"light3d\":" + prefs.getBoolean("light3d", false)
                + ",\"thick3d\":" + prefs.getBoolean("thick3d", true) + ",\"round3d\":" + prefs.getBoolean("round3d", false) + "}";
        }
        @JavascriptInterface public void set3d(String k, String v) {
            SharedPreferences.Editor ed = prefs.edit();
            if (k.equals("depth3d")) { try { ed.putInt(k, Math.max(4, Math.min(48, Integer.parseInt(v)))); } catch (NumberFormatException e) { return; } }
            else if (v.equals("true") || v.equals("false")) ed.putBoolean(k, v.equals("true"));
            else ed.putString(k, v);
            ed.commit();
            apply3d();
        }
        /** Puts the 3D view over part of the page (fractions of the page) or takes it away. */
        @JavascriptInterface public void view3d(final boolean on, final float x, final float y, final float w, final float h) {
            ui.post(new Runnable() { public void run() {
                if (on) {
                    int pw = root.getWidth(), ph = root.getHeight();
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(w * pw), Math.round(h * ph));
                    lp.leftMargin = Math.round(x * pw); lp.topMargin = Math.round(y * ph);
                    gl.setLayoutParams(lp);
                    if (gl.getVisibility() != View.VISIBLE) gl.setVisibility(View.VISIBLE);
                    core.view(sink);
                } else {
                    core.view(null);
                    if (gl.getVisibility() != View.GONE) gl.setVisibility(View.GONE);
                }
            } });
        }
    }
}

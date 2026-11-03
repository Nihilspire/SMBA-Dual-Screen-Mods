package com.jack.smadual;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.CRC32;

public class MainActivity extends Activity {
    static final String VERSION = "0.27";
    static final int PICK_ROM = 1;

    SharedPreferences prefs;
    File romFile, stateDir;
    final RomGfx gfx = new RomGfx();
    final Handler ui = new Handler(Looper.getMainLooper());

    DualLayout root;
    FrameLayout gamePane, linkPane;
    GameView game, border3d;
    android.opengl.GLSurfaceView glView;
    final Renderer3D r3d = new Renderer3D();
    volatile boolean view3d;
    TouchPadView pad;
    WebView menu, link;
    Presentation presentation;
    DisplayManager displays;

    Emu emu;
    volatile boolean started; // a game session exists (launcher closed at least once)
    boolean menuOpen = true;
    boolean linkOverlay;
    volatile int keyBits, axisBits, specialBits;
    int rebindTarget = -1, swallowUp = -1;
    boolean usingPad;
    final Binds binds = new Binds();

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("smadual", MODE_PRIVATE);
        romFile = new File(getFilesDir(), "rom.gba");
        File ext = getExternalFilesDir("states");
        stateDir = ext != null ? ext : new File(getFilesDir(), "states");
        stateDir.mkdirs();
        binds.load(prefs.getString("keyBinds", ""));
        gfx.load(romFile);
        romCrc = crcOf(gfx.rom);

        Native.init(getFilesDir().getAbsolutePath(), getFilesDir().getAbsolutePath());
        Native.setColorCorrection(prefs.getBoolean("colorfix", false));
        Native.setFrameBlend(prefs.getBoolean("blend", false));
        Native.setOverclock(prefs.getBoolean("overclock", true));

        root = new DualLayout(this);
        gamePane = new FrameLayout(this);
        linkPane = new FrameLayout(this);
        linkPane.setBackgroundColor(0xFF000000);
        game = new GameView(this);
        game.gfx = gfx;
        game.host = this;
        game.setFocusable(true);
        game.setFocusableInTouchMode(true);
        pad = new TouchPadView(this);
        pad.gfx = gfx;
        pad.listener = new TouchPadView.Listener() { public void onMenu() { openMenu(); } };
        menu = web("file:///android_asset/menu/index.html", new Host(), "Host");
        link = web("file:///android_asset/link/index.html", new LinkBridge(), "Native");
        gamePane.addView(game, new FrameLayout.LayoutParams(-1, -1));
        glView = new android.opengl.GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(Renderer3D.CONFIG);
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(r3d);
        glView.setRenderMode(android.opengl.GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        glView.setVisibility(View.GONE);
        gamePane.addView(glView, new FrameLayout.LayoutParams(-1, -1));
        border3d = new GameView(this);
        border3d.source = game;
        border3d.gfx = gfx;
        border3d.host = this;
        border3d.setBackgroundColor(0);
        border3d.setVisibility(View.GONE);
        gamePane.addView(border3d, new FrameLayout.LayoutParams(-1, -1));
        gamePane.addView(pad, new FrameLayout.LayoutParams(-1, -1));
        gamePane.addView(menu, new FrameLayout.LayoutParams(-1, -1));
        linkPane.addView(link, new FrameLayout.LayoutParams(-1, -1));
        root.addView(gamePane);
        root.addView(linkPane);
        setContentView(root);
        applySettings();

        displays = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        displays.registerDisplayListener(new DisplayManager.DisplayListener() {
            public void onDisplayAdded(int id) { updatePresentation(); }
            public void onDisplayRemoved(int id) { updatePresentation(); }
            public void onDisplayChanged(int id) { root.requestLayout(); }
        }, ui);
        updatePresentation();

        emu = new Emu();
        emu.start();
        ensureKit(false);
        if (prefs.getBoolean("skipLauncher", false) && romFile.exists() && new File(stateDir, "auto.st").exists()) {
            if ("working".equals(kitState)) { pendingStart = true; pendingCont = true; }   // starts when the capture is done
            else ui.post(new Runnable() { public void run() { startGame(true); } });
        }
    }

    private WebView web(String url, Object bridge, String name) {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        w.setBackgroundColor(0xFF000000);
        w.setWebViewClient(new WebViewClient());
        w.setWebChromeClient(new WebChromeClient());
        w.setVerticalScrollBarEnabled(false);
        w.setFocusable(false);                    // controller focus lock: taps never move key focus here
        w.setFocusableInTouchMode(false);
        w.setHorizontalScrollBarEnabled(false);
        w.addJavascriptInterface(bridge, name);
        w.loadUrl(url);
        return w;
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        if (started && !menuOpen) emu.setPaused(false);
        if (menuOpen && !started) evalMenu("window.musicStart&&musicStart()");
    }

    @Override protected void onPause() {
        super.onPause();
        emu.setPaused(true);
        evalMenu("window.musicStop&&musicStop()");
        if (started) emu.call(new Runnable() { public void run() { saveSlot("auto"); Native.saveFlush(); } }, 1500);
    }

    @Override protected void onDestroy() {
        if (presentation != null) presentation.dismiss();
        emu.quit();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) { immersive(); (menuOpen ? menu : game).requestFocus(); }
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        root.requestLayout();
        evalLink("window.onLayout&&onLayout()");
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------------ game control

    void startGame(final boolean cont) {
        if (!romFile.exists()) return;
        if ("working".equals(kitState)) { pendingStart = true; pendingCont = cont; return; }   // after the menu capture
        emu.call(new Runnable() { public void run() {
            if (Native.load(romFile.getAbsolutePath()) != 0) return;
            emu.newSession();
            devUsed = codeHealth || codeLives;
            File auto = new File(stateDir, "auto.st");
            if (cont && auto.exists()) Native.stateLoad(auto.getAbsolutePath());
        } }, 4000);
        started = true;
        closeMenu();
        evalLink("window.onSession&&onSession()");
    }

    void openMenu() {
        if (menuOpen) return;
        emu.setPaused(true);
        menuOpen = true;
        menu.setVisibility(View.VISIBLE);
        menu.requestFocus();
        evalMenu("window.show&&show('pause')");
    }

    void closeMenu() {
        menuOpen = false;
        menu.setVisibility(View.GONE);
        evalMenu("window.musicStop&&musicStop()");
        game.requestFocus();
        if (started) emu.setPaused(false);
    }

    void quitToLauncher() {
        boolean was = started;
        started = false;          // stop frames and RAM reads first, then save and unload between frames
        if (was) emu.call(new Runnable() { public void run() { saveSlot("auto"); Native.saveFlush(); Native.unload(); } }, 3000);
        synchronized (game.lock) { game.frame = null; }
        game.invalidate();
        evalMenu("window.show&&show('launcher')");
    }

    /** Runs on the emulation thread. */
    boolean saveSlot(String name) {
        int r = Native.stateSave(new File(stateDir, name + ".st").getAbsolutePath());
        Bitmap f = emu.lastFrame();
        if (r == 0 && f != null) {
            try {
                FileOutputStream o = new FileOutputStream(new File(stateDir, name + ".png"));
                f.compress(Bitmap.CompressFormat.PNG, 100, o);
                o.close();
            } catch (Exception ignored) {}
        }
        return r == 0;
    }

    /** Slot screenshot as a data: URL for the menu and the Link screen ("" when there is none). */
    String slotThumb(int slot) {
        File f = new File(stateDir, "slot" + slot + ".png");
        if (!f.exists()) return "";
        try {
            byte[] b = new byte[(int) f.length()];
            InputStream in = new java.io.FileInputStream(f); int n = 0; while (n < b.length) n += in.read(b, n, b.length - n); in.close();
            return "data:image/png;base64," + Base64.encodeToString(b, Base64.NO_WRAP);
        } catch (Exception e) { return ""; }
    }

    void applySettings() {
        game.aspect = prefs.getString("aspect", "3:2");
        game.bezels = prefs.getBoolean("bezels", true);
        game.integer = prefs.getBoolean("integer", false);
        game.showStats = prefs.getBoolean("stats", false);
        loadCodes();
        view3d = prefs.getBoolean("dev3d", false);
        r3d.camPreset = cam3d();
        r3d.scene.depth = prefs.getInt("depth3d", 16);
        r3d.scene.back = prefs.getString("back3d", "near");
        r3d.scene.cave = prefs.getBoolean("cave3d", true);
        r3d.scene.rom = gfx.rom;
        r3d.scene.light = prefs.getBoolean("light3d", false);
        r3d.scene.thick = prefs.getBoolean("thick3d", true);
        r3d.scene.round = prefs.getBoolean("round3d", false);
        glView.setVisibility(view3d ? View.VISIBLE : View.GONE);
        game.setVisibility(view3d ? View.INVISIBLE : View.VISIBLE);
        border3d.setVisibility(view3d ? View.VISIBLE : View.GONE);
        border3d.bezels = prefs.getBoolean("border3d", true);
        border3d.aspect = game.aspect.equals("stretch") ? "3:2" : game.aspect;
        border3d.integer = game.integer;
        border3d.showStats = game.showStats;
        border3d.hud = game.hud;
        border3d.invalidate();
        pad.opacity = prefs.getInt("padOpacity", 70);
        updatePadVisibility();
        root.requestLayout();
        game.invalidate();
    }

    void updatePadVisibility() {
        String mode = prefs.getString("touch", "auto");
        boolean show = mode.equals("on") || (mode.equals("auto") && !usingPad);
        pad.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    static final int SLOTS = 4;
    static final int DEPTH_MIN = 4, DEPTH_MAX = 48;

    // ------------------------------------------------------------------ playtest codes (hidden, Konami code)
    volatile boolean codeHealth, codeLives, devUsed;

    /** The life meter's size: 2 hearts + the mushrooms taken in this level (Gba.MUSHROOMS). */
    int maxHearts() { return Gba.maxHearts(); }

    /** Runs on the emulation thread after every frame while an always-on code is enabled. */
    void applyCodes() {
        if (!Gba.inGame()) return;                                          // only in the level itself
        if (codeHealth) {                                                   // life meter stays full
            int h = Native.peek(Gba.HEALTH), full = (maxHearts() - 1) * 0x10 + 0x0F;
            if (h < 0xF0 && h != full) Native.poke(Gba.HEALTH, full);
        }
        if (codeLives && Native.peek(Gba.LIVES) < 5) Native.poke(Gba.LIVES, 5);
    }

    /* Level warp (playtest): the game reloads the current level from the file block when character select opens, so
     * a poke in a level is undone. The warp waits for the next character select and pokes CURRENT_LEVEL there, again
     * if the game rewrites it, until the level starts. */
    volatile int pendingWarp = -1;
    private int csFrames;
    void applyWarp() {
        // 0.17: the world's graphics set (030019FC = world - 1) is chosen by the game's own level progression, so a
        // level poke alone starts e.g. 4-1 with world 1's graphics. Hold it at the target world until the level starts.
        if (!Gba.inLevel()) {
            int w = 0; for (int i = 0; i < Gba.WORLD_START.length; i++) if (pendingWarp >= Gba.WORLD_START[i]) w = i;
            Native.poke(Gba.TILESET, w); Native.poke(Gba.TILESET + 1, 0);
        }
        if (Gba.charSelect()) {
            if (++csFrames >= 4 && csFrames < 600 && Native.peek(Gba.CURRENT_LEVEL) != pendingWarp) {
                Native.poke(Gba.CURRENT_LEVEL, pendingWarp);
                Native.trackReset();                 // not a clear of the level we came from
            }
        } else {
            if (csFrames >= 4 && Gba.inLevel()) {
                final int got = Native.peek(Gba.CURRENT_LEVEL), want = pendingWarp;
                pendingWarp = -1;
                ui.post(new Runnable() { public void run() {
                    game.showOsd(got == want ? "WARPED TO " + Gba.LEVEL_NAMES[want] : "WARP FAILED");
                } });
            }
            csFrames = 0;
        }
    }

    void loadCodes() {
        boolean unlocked = prefs.getBoolean("devUnlock", false);      // hidden and off unless unlocked (Konami code)
        codeHealth = unlocked && prefs.getBoolean("codeHealth", false);
        codeLives = unlocked && prefs.getBoolean("codeLives", false);
        if (codeHealth || codeLives) devUsed = true;
    }

    /** One-shot playtest actions. Returns a short message for the menu. */
    String devAction(final String what) {
        if (!started) return "START A GAME FIRST";
        devUsed = true;
        final String[] msg = { "DONE" };
        emu.call(new Runnable() { public void run() {
            switch (what) {
                case "refill":    Native.poke(Gba.HEALTH, (maxHearts() - 1) * 0x10 + 0x0F); msg[0] = "LIFE FULL"; break;
                case "star":      Native.poke(Gba.STAR_TIMER, 0xFF); msg[0] = "STARMAN"; break;
                case "stopwatch": Native.poke(Gba.STOPWATCH, 0xFF); msg[0] = "STOPWATCH"; break;
                case "lives":     Native.poke(Gba.LIVES, Math.min(99, Native.peek(Gba.LIVES) + 5)); msg[0] = "+5 LIVES"; break;
                case "cherries":  Native.poke(Gba.CHERRIES, 4); msg[0] = "4 CHERRIES"; break;
                default:
                    if (what.startsWith("warp:")) {
                        int L = Integer.parseInt(what.substring(5));
                        if (L < 0 || L > 19) break;
                        csFrames = 0; pendingWarp = L;
                        msg[0] = Gba.charSelect() ? "WARP TO " + Gba.LEVEL_NAMES[L] + " NOW" : Gba.LEVEL_NAMES[L] + " AT CHARACTER SELECT";
                    } else if (what.startsWith("time:")) {   // set the play time to N minutes
                        long fr = (long) (Integer.parseInt(what.substring(5)) * 60 * Gba.FPS);
                        Native.setTracker(Gba.T_TIME, (int) fr);
                        msg[0] = "TIME " + (fr / 215019) + ":" + String.format("%02d", (long) (fr / (60 * Gba.FPS)) % 60);
                    }
            }
        } }, 1000);
        evalLink("window.onDev&&onDev()");
        return msg[0];
    }

    void toggle3d() {
        boolean on = !prefs.getBoolean("dev3d", false);
        prefs.edit().putBoolean("dev3d", on).apply();
        applySettings();
        (on ? border3d : game).showOsd(on ? "3D VIEW" : "2D VIEW");
    }

    /** The 3D cameras, in the order the hotkey and the menu step through them. */
    static final String[] CAMS = { "low", "tilt", "diorama", "far" };
    String cam3d() {
        String c = prefs.getString("cam3d", "tilt");
        for (String k : CAMS) if (k.equals(c)) return c;
        return "tilt";                                            // 0.21's side
    }

    void cycleCamera() {
        String c = cam3d(); int i = 0;
        while (!CAMS[i].equals(c)) i++;
        String n = CAMS[(i + 1) % CAMS.length];
        prefs.edit().putString("cam3d", n).apply();
        r3d.camPreset = n;
        if (view3d) border3d.showOsd("CAMERA " + n.toUpperCase()); else game.showOsd("3D CAMERA " + n.toUpperCase());
    }

    void changeDepth(int delta) {
        int d = Math.max(DEPTH_MIN, Math.min(DEPTH_MAX, prefs.getInt("depth3d", 16) + delta));
        prefs.edit().putInt("depth3d", d).apply();
        r3d.scene.depth = d;
        if (view3d) border3d.showOsd("DEPTH " + d); else game.showOsd("3D DEPTH " + d);
    }

    /** Applies one 3D setting from the menu or the Link page. */
    void set3d(String k, String v) {
        SharedPreferences.Editor ed = prefs.edit();
        if (k.equals("depth3d")) ed.putInt(k, Math.max(DEPTH_MIN, Math.min(DEPTH_MAX, Integer.parseInt(v))));
        else if (v.equals("true") || v.equals("false")) ed.putBoolean(k, v.equals("true"));
        else ed.putString(k, v);
        ed.apply();
        applySettings();
    }

    String settings3d() {
        try {
            JSONObject s = new JSONObject();
            s.put("dev3d", prefs.getBoolean("dev3d", false));
            s.put("cam3d", cam3d());
            s.put("depth3d", prefs.getInt("depth3d", 16));
            s.put("border3d", prefs.getBoolean("border3d", true));
            s.put("back3d", prefs.getString("back3d", "near"));
            s.put("cave3d", prefs.getBoolean("cave3d", true));
            s.put("light3d", prefs.getBoolean("light3d", false));
            s.put("thick3d", prefs.getBoolean("thick3d", true));
            s.put("round3d", prefs.getBoolean("round3d", false));
            return s.toString();
        } catch (Exception e) { return "{}"; }
    }

    void setHud() {
        boolean h = !linkVisibleSomewhere();
        game.hud = h;
        if (border3d != null) { border3d.hud = h; border3d.invalidate(); }
    }

    boolean linkVisibleSomewhere() {
        return presentation != null || root.split != 0 || root.side || linkOverlay;
    }

    // ------------------------------------------------------------------ second display (presentation)

    void updatePresentation() {
        Display target = null;
        if (prefs.getBoolean("useExternal", true)) {
            Display mine = getWindowManager().getDefaultDisplay();
            for (Display d : displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION))
                if (d.getDisplayId() != mine.getDisplayId()) { target = d; break; }
        }
        if (presentation != null && (target == null || presentation.getDisplay().getDisplayId() != target.getDisplayId())) {
            ((ViewGroup) link.getParent()).removeView(link);
            presentation.dismiss();
            presentation = null;
            linkPane.addView(link, new FrameLayout.LayoutParams(-1, -1));
        }
        if (target != null && presentation == null) {
            try {
                presentation = new LinkPresentation(this, target);
                // Controller focus lock: the Link display's window can be touched but never takes input focus,
                // so tapping it doesn't steal the controller from the game (Android moves focus to the last
                // touched display otherwise).
                presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                ((ViewGroup) link.getParent()).removeView(link);
                FrameLayout box = new FrameLayout(presentation.getContext());
                box.setBackgroundColor(0xFF000000);
                box.addView(link, new FrameLayout.LayoutParams(-1, -1));
                presentation.setContentView(box);
                presentation.show();
            } catch (Exception e) {
                presentation = null;
                if (link.getParent() == null) linkPane.addView(link, new FrameLayout.LayoutParams(-1, -1));
            }
        }
        root.requestLayout();
        setHud();
    }

    /** Presentation that hands any key or stick input it receives straight back to the game. */
    final class LinkPresentation extends Presentation {
        LinkPresentation(Context c, Display d) { super(c, d); }
        @Override public boolean dispatchKeyEvent(KeyEvent e) { return MainActivity.this.dispatchKeyEvent(e); }
        @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
            return (e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                    ? MainActivity.this.dispatchGenericMotionEvent(e) : super.dispatchGenericMotionEvent(e);
        }
    }

    // ------------------------------------------------------------------ input

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE)
            return super.dispatchKeyEvent(e);
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (e.getAction() == KeyEvent.ACTION_UP && code == swallowUp) { swallowUp = -1; return true; }
        if (isGamepad(e) && !usingPad) { usingPad = true; updatePadVisibility(); }
        if (rebindTarget >= 0 && down && e.getRepeatCount() == 0) {
            int idx = rebindTarget;
            rebindTarget = -1;
            swallowUp = code;
            binds.assign(code, idx);
            prefs.edit().putString("keyBinds", binds.save()).apply();
            evalMenu("window.onRebind&&onRebind(" + idx + ")");
            return true;
        }
        int btn = binds.button(code);
        if (menuOpen) {
            if (!down) return true;
            String nav = null;
            if (btn == 0) nav = "up"; else if (btn == 1) nav = "down"; else if (btn == 2) nav = "left"; else if (btn == 3) nav = "right";
            else if (btn == 5 || btn == 7 || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER) nav = "ok";
            else if (btn == 4 || btn == 10 || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) nav = "back";
            if (nav != null) { evalMenu("window.pad&&pad('" + nav + "')"); return true; }
            return code == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(e);
        }
        if (linkOverlay && (code == KeyEvent.KEYCODE_BACK || btn == 10)) {
            if (down) { linkOverlay = false; root.requestLayout(); setHud(); }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK && down) { openMenu(); return true; }
        if (btn < 0) return super.dispatchKeyEvent(e);
        if (btn == 10) { if (down) openMenu(); return true; }
        if (btn == 14) {
            if (down && e.getRepeatCount() == 0) toggle3d();
            return true;
        }
        if (btn == 15 || btn == 16) {
            if (down) changeDepth(btn == 15 ? 4 : -4);
            return true;
        }
        if (btn == 19) {
            if (down && e.getRepeatCount() == 0) cycleCamera();
            return true;
        }
        int bit = Gba.bit(btn);
        int special = Gba.special(btn);
        if (down) { keyBits |= bit; specialBits |= special; } else { keyBits &= ~bit; specialBits &= ~special; }
        if (down && e.getRepeatCount() == 0) {
            if (btn == 12) emu.call(new Runnable() { public void run() { saveSlot("quick"); } }, 0);
            if (btn == 13) emu.call(new Runnable() { public void run() { Native.stateLoad(new File(stateDir, "quick.st").getAbsolutePath()); } }, 0);
        }
        return true;
    }

    private static boolean isGamepad(KeyEvent e) {
        int s = e.getSource();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (s & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD && e.getDevice() != null && !e.getDevice().isVirtual();
    }

    private int lastNav;

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK || e.getAction() != MotionEvent.ACTION_MOVE)
            return super.dispatchGenericMotionEvent(e);
        if (!usingPad) { usingPad = true; updatePadVisibility(); }
        float x = e.getAxisValue(MotionEvent.AXIS_HAT_X), y = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        float sx = e.getAxisValue(MotionEvent.AXIS_X), sy = e.getAxisValue(MotionEvent.AXIS_Y);
        if (Math.abs(sx) > Math.abs(x)) x = sx;
        if (Math.abs(sy) > Math.abs(y)) y = sy;
        int b = 0;
        if (x < -0.5f) b |= Gba.LEFT; else if (x > 0.5f) b |= Gba.RIGHT;
        if (y < -0.5f) b |= Gba.UP; else if (y > 0.5f) b |= Gba.DOWN;
        if (menuOpen) {
            if (b != lastNav && b != 0) {
                String nav = (b & Gba.UP) != 0 ? "up" : (b & Gba.DOWN) != 0 ? "down" : (b & Gba.LEFT) != 0 ? "left" : "right";
                evalMenu("window.pad&&pad('" + nav + "')");
            }
            lastNav = b;
            axisBits = 0;
        } else axisBits = b;
        return true;
    }

    // ------------------------------------------------------------------ JS helpers

    void evalMenu(final String js) { ui.post(new Runnable() { public void run() { menu.evaluateJavascript(js, null); } }); }
    void evalLink(final String js) { ui.post(new Runnable() { public void run() { link.evaluateJavascript(js, null); } }); }

    String romB64(int off, int len) {
        byte[] r = gfx.rom;
        if (r == null || off < 0 || off + len > r.length) return "";
        return Base64.encodeToString(r, off, len, Base64.NO_WRAP);
    }

    JSONObject romInfo() throws Exception {
        JSONObject o = new JSONObject();
        o.put("present", romFile.exists());
        if (romFile.exists() && gfx.rom != null) {
            o.put("ok", romCrc == Gba.SMA_CRC);
            o.put("crc", Long.toHexString(romCrc));
            o.put("name", prefs.getString("romName", "rom.gba"));
        }
        return o;
    }

    static long crcOf(byte[] b) {
        if (b == null) return 0;
        CRC32 c = new CRC32(); c.update(b, 0, b.length); return c.getValue();
    }

    // ------------------------------------------------------------------ menu graphics from the ROM (first start)
    /* The menu is themed from the player's own ROM: on first start (and after a new ROM is picked) the emulation
     * thread boots the game with an empty, throwaway save folder, walks title -> file -> 1-1 -> pause on its own and
     * captures the title screen, 1-1 in play and the pause screen's video memory (native/extract.c). menu/gbakit.js
     * turns that into the backdrop, the panel, the cursor and the item icons (potion, mushroom, hearts, ...). Cached per
     * capture version + ROM CRC (version 2 = with the play shot); nothing touches the real game.sav. */
    static final int KIT_VER = 2;
    long romCrc;
    volatile String kitState = "none";        // none | working | ready | failed
    boolean pendingStart, pendingCont;

    File kitFile() { return new File(getFilesDir(), "kit-" + KIT_VER + "-" + Long.toHexString(romCrc) + ".bin"); }

    static void deleteTree(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File c : l) deleteTree(c);
        f.delete();
    }

    void ensureKit(boolean force) {
        if (!romFile.exists() || romCrc != Gba.SMA_CRC || started) { if (!started) kitState = "none"; return; }
        final File out = kitFile();
        if (out.exists() && !force) { kitState = "ready"; return; }
        if (!force && prefs.getBoolean("kitFail" + KIT_VER, false)) { kitState = "failed"; return; }
        if ("working".equals(kitState)) return;
        kitState = "working";
        emu.call(new Runnable() { public void run() {
            File tmp = new File(getCacheDir(), "kitrun");
            deleteTree(tmp); tmp.mkdirs();
            long t0 = System.currentTimeMillis();
            Native.setOverclock(false);
            int r = Native.extract(romFile.getAbsolutePath(), tmp.getAbsolutePath(), getFilesDir().getAbsolutePath(), out.getAbsolutePath());
            Native.setOverclock(prefs.getBoolean("overclock", true));
            deleteTree(tmp);
            android.util.Log.i("SMADual", "menu capture " + r + " in " + (System.currentTimeMillis() - t0) + " ms");
            File[] old = getFilesDir().listFiles();
            if (r > 0 && old != null) for (File f : old) if (f.getName().startsWith("kit-") && !f.equals(out)) f.delete();
            kitState = r > 0 ? "ready" : "failed";
            prefs.edit().putBoolean("kitFail" + KIT_VER, r <= 0).apply();
            ui.post(new Runnable() { public void run() {
                evalMenu("window.refresh&&refresh()");
                if (pendingStart) { pendingStart = false; startGame(pendingCont); }
            } });
        } }, 0);
    }

    String kitB64() {
        if (!"ready".equals(kitState)) return "";
        try {
            File f = kitFile(); byte[] b = new byte[(int) f.length()];
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f));
            in.readFully(b); in.close();
            return Base64.encodeToString(b, Base64.NO_WRAP);
        } catch (Exception e) { return ""; }
    }

    // ------------------------------------------------------------------ ROM picking

    void pickRom() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, PICK_ROM); } catch (Exception e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT); g.setType("*/*"); g.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(g, PICK_ROM);
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_ROM || res != RESULT_OK || data == null || data.getData() == null) { evalMenu("window.refresh&&refresh()"); return; }
        Uri u = data.getData();
        try {
            InputStream in = getContentResolver().openInputStream(u);
            File tmp = new File(getFilesDir(), "rom.tmp");
            OutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[65536]; int n, total = 0;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
            in.close(); out.close();
            if (total < 0x100000 || total > 0x2000000) { tmp.delete(); evalMenu("window.toast&&toast('NOT A GBA ROM')"); return; }
            tmp.renameTo(romFile);
            String name = u.getLastPathSegment();
            if (name != null && name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
            prefs.edit().putString("romName", name == null ? "rom.gba" : name).apply();
            gfx.load(romFile); r3d.scene.rom = gfx.rom;
            romCrc = crcOf(gfx.rom);
            prefs.edit().remove("kitFail" + KIT_VER).apply();
            pad.invalidate();
            ensureKit(false);
            evalLink("window.onRom&&onRom()");
        } catch (Exception e) {
            evalMenu("window.toast&&toast('COULD NOT READ FILE')");
        }
        evalMenu("window.refresh&&refresh()");
    }

    // ------------------------------------------------------------------ bridges

    /** Bridge for the launcher / pause menu page (assets/menu). */
    final class Host {
        @JavascriptInterface public String info() {
            try {
                JSONObject o = new JSONObject();
                o.put("version", VERSION);
                o.put("mode", started ? "pause" : "launcher");
                o.put("rom", romInfo());
                o.put("kit", kitState);
                o.put("auto", new File(stateDir, "auto.st").exists());
                JSONObject s = new JSONObject();
                s.put("aspect", prefs.getString("aspect", "3:2"));
                s.put("bezels", prefs.getBoolean("bezels", true));
                s.put("integer", prefs.getBoolean("integer", false));
                s.put("colorfix", prefs.getBoolean("colorfix", false));
                s.put("blend", prefs.getBoolean("blend", false));
                s.put("touch", prefs.getString("touch", "auto"));
                s.put("padOpacity", prefs.getInt("padOpacity", 70));
                s.put("split", prefs.getString("split", "auto"));
                s.put("swap", prefs.getBoolean("swap", false));
                s.put("gap", prefs.getInt("gap", 0));
                s.put("side", prefs.getBoolean("side", false));
                s.put("useExternal", prefs.getBoolean("useExternal", true));
                s.put("skipLauncher", prefs.getBoolean("skipLauncher", false));
                s.put("audio", prefs.getBoolean("audio", true));
                s.put("overclock", prefs.getBoolean("overclock", true));
                s.put("music", prefs.getBoolean("music", true));
                s.put("romMenu", prefs.getBoolean("romMenu", true));
                s.put("stats", prefs.getBoolean("stats", false));
                s.put("dev3d", prefs.getBoolean("dev3d", false));
                s.put("cam3d", cam3d());
                s.put("depth3d", prefs.getInt("depth3d", 16));
                s.put("back3d", prefs.getString("back3d", "near"));
                s.put("cave3d", prefs.getBoolean("cave3d", true));
                s.put("light3d", prefs.getBoolean("light3d", false));
                s.put("thick3d", prefs.getBoolean("thick3d", true));
                s.put("round3d", prefs.getBoolean("round3d", false));
                s.put("border3d", prefs.getBoolean("border3d", true));
                s.put("devUnlock", prefs.getBoolean("devUnlock", false));
                s.put("codeHealth", prefs.getBoolean("codeHealth", false));
                s.put("codeLives", prefs.getBoolean("codeLives", false));
                o.put("settings", s);
                o.put("devUsed", devUsed);
                o.put("screens", root.describe());
                o.put("linkShown", linkVisibleSomewhere());
                JSONArray slots = new JSONArray();
                for (int i = 1; i <= SLOTS; i++) {
                    File f = new File(stateDir, "slot" + i + ".st");
                    JSONObject sl = new JSONObject();
                    sl.put("n", i);
                    sl.put("time", f.exists() ? f.lastModified() : 0);
                    slots.put(sl);
                }
                o.put("slots", slots);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public String font() { return romB64(Gba.ROM_FONT, Gba.FONT_BYTES); }
        @JavascriptInterface public String kit() { return kitB64(); }
        @JavascriptInterface public void rebuildKit() { ui.post(new Runnable() { public void run() {
            prefs.edit().remove("kitFail" + KIT_VER).apply(); ensureKit(true); evalMenu("window.refresh&&refresh()"); } }); }
        @JavascriptInterface public String rom(int off, int len) { return romB64(off, len); }
        @JavascriptInterface public void pickRom() { ui.post(new Runnable() { public void run() { MainActivity.this.pickRom(); } }); }
        @JavascriptInterface public void start(final boolean cont) { ui.post(new Runnable() { public void run() { startGame(cont); } }); }
        @JavascriptInterface public void resume() { ui.post(new Runnable() { public void run() { if (started) closeMenu(); } }); }
        @JavascriptInterface public void reset() {
            emu.call(new Runnable() { public void run() { Native.reset(); } }, 1000);
            ui.post(new Runnable() { public void run() { closeMenu(); } });
        }
        @JavascriptInterface public boolean save(final int slot) {
            final boolean[] ok = { false };
            emu.call(new Runnable() { public void run() { ok[0] = saveSlot("slot" + slot); } }, 3000);
            return ok[0];
        }
        @JavascriptInterface public boolean load(final int slot) {
            final File f = new File(stateDir, "slot" + slot + ".st");
            if (!f.exists()) return false;
            final boolean[] ok = { false };
            if (!started) {
                ui.post(new Runnable() { public void run() {
                    startGame(false);
                    emu.call(new Runnable() { public void run() { Native.stateLoad(f.getAbsolutePath()); } }, 3000);
                } });
                return true;
            }
            emu.call(new Runnable() { public void run() { ok[0] = Native.stateLoad(f.getAbsolutePath()) == 0; } }, 3000);
            if (ok[0]) ui.post(new Runnable() { public void run() { closeMenu(); } });
            return ok[0];
        }
        @JavascriptInterface public String thumb(int slot) { return slotThumb(slot); }
        @JavascriptInterface public void quit() { ui.post(new Runnable() { public void run() { quitToLauncher(); } }); }
        @JavascriptInterface public void exit() { ui.post(new Runnable() { public void run() { finish(); } }); }
        @JavascriptInterface public void set(final String k, final String v) {
            ui.post(new Runnable() { public void run() {
                SharedPreferences.Editor ed = prefs.edit();
                if (v.equals("true") || v.equals("false")) ed.putBoolean(k, v.equals("true"));
                else if (k.equals("padOpacity") || k.equals("gap") || k.equals("depth3d")) ed.putInt(k, Integer.parseInt(v));
                else ed.putString(k, v);
                ed.apply();
                if (k.equals("colorfix")) Native.setColorCorrection(v.equals("true"));
                if (k.equals("blend")) Native.setFrameBlend(v.equals("true"));
                if (k.equals("overclock")) Native.setOverclock(v.equals("true"));
                if (k.equals("touch")) usingPad = false;
                if (k.equals("useExternal")) updatePresentation();
                applySettings();
                setHud();
            } });
        }
        @JavascriptInterface public String binds() { return binds.describe(); }
        @JavascriptInterface public String dev(String what) { return prefs.getBoolean("devUnlock", false) ? devAction(what) : "LOCKED"; }
        @JavascriptInterface public void rebind(int idx) { rebindTarget = idx; }
        @JavascriptInterface public void cancelRebind() { rebindTarget = -1; }
        @JavascriptInterface public void resetBinds() {
            binds.load("");
            prefs.edit().remove("keyBinds").apply();
        }
        @JavascriptInterface public void showLink(final boolean on) {
            ui.post(new Runnable() { public void run() {
                linkOverlay = on;
                root.requestLayout();
                setHud();
                if (on) closeMenu();
            } });
        }
    }

    /** Bridge for the Link page (assets/link). Same idea as the Link apps' window.Native, answered from emulator RAM. */
    final class LinkBridge {
        @JavascriptInterface public String version() { return VERSION; }
        @JavascriptInterface public boolean dual() { return true; }
        @JavascriptInterface public boolean running() { return started && Native.peek(0) >= 0; }
        @JavascriptInterface public String mem(String spec) { return started ? Native.mem(spec) : ""; }
        /** VRAM 0x18000 | palette 0x400 | OAM 0x400 | IO 0x60, base64, refreshed about once a second while playing. */
        @JavascriptInterface public String video() { return started ? emu.videoB64 : ""; }
        @JavascriptInterface public String kit() { return kitB64(); }
        @JavascriptInterface public String rom(int off, int len) { return romB64(off, len); }
        @JavascriptInterface public String tracker() {
            byte[] t = new byte[64]; Native.tracker(t);
            return Base64.encodeToString(t, Base64.NO_WRAP);
        }
        @JavascriptInterface public boolean overlay() { return linkOverlay; }
        @JavascriptInterface public void close() {
            ui.post(new Runnable() { public void run() { linkOverlay = false; root.requestLayout(); setHud(); } });
        }
        @JavascriptInterface public boolean devUsed() { return devUsed; }
        /** Save state slots 1-4, same files as the pause menu's SAVE/LOAD STATE. */
        @JavascriptInterface public String slots() {
            try {
                JSONArray a = new JSONArray();
                for (int i = 1; i <= SLOTS; i++) {
                    File f = new File(stateDir, "slot" + i + ".st");
                    JSONObject o = new JSONObject(); o.put("n", i); o.put("time", f.exists() ? f.lastModified() : 0); a.put(o);
                }
                return a.toString();
            } catch (Exception e) { return "[]"; }
        }
        @JavascriptInterface public boolean saveSlot(final int n) {
            if (!started || n < 1 || n > SLOTS) return false;
            final boolean[] ok = { false };
            emu.call(new Runnable() { public void run() { ok[0] = MainActivity.this.saveSlot("slot" + n); } }, 3000);
            return ok[0];
        }
        @JavascriptInterface public String thumb(int n) { return slotThumb(n); }
        @JavascriptInterface public boolean loadSlot(final int n) {
            final File f = new File(stateDir, "slot" + n + ".st");
            if (!started || !f.exists()) return false;
            final boolean[] ok = { false };
            emu.call(new Runnable() { public void run() { ok[0] = Native.stateLoad(f.getAbsolutePath()) == 0; } }, 3000);
            return ok[0];
        }
        @JavascriptInterface public String settings3d() { return MainActivity.this.settings3d(); }
        @JavascriptInterface public void set3d(final String k, final String v) {
            ui.post(new Runnable() { public void run() { MainActivity.this.set3d(k, v); } });
        }
        @JavascriptInterface public void menu() { ui.post(new Runnable() { public void run() { if (started) openMenu(); } }); }
    }

    // ------------------------------------------------------------------ emulation thread

    final class Emu extends Thread {
        private final Object pauseLock = new Object();
        private volatile boolean paused = true, quit;
        private final ConcurrentLinkedQueue<Runnable> jobs = new ConcurrentLinkedQueue<Runnable>();
        private final Bitmap[] bufs = new Bitmap[3];
        private int bufIdx, fw, fh;
        private Bitmap last;
        private final ByteBuffer px = ByteBuffer.allocateDirect(256 * 240 * 2).order(ByteOrder.nativeOrder());
        private final short[] samples = new short[16384];
        private AudioOut audio;
        final VSync vsync = new VSync();
        volatile String stats = "";
        private final BezelTheme.Snap ts = new BezelTheme.Snap();
        private final byte[] snap = new byte[Scene3D.SNAP];
        private final byte[] video = new byte[0x18000 + 0x400 + 0x400 + 0x60];
        volatile String videoB64 = "";
        private long frame;
        private boolean comboHeld;
        private int lastArea = -1, themeSamples;
        private final java.util.HashMap<Integer, BezelTheme> areaThemes = new java.util.HashMap<Integer, BezelTheme>(),
                candidates = new java.util.HashMap<Integer, BezelTheme>();
        private final java.util.HashMap<Integer, Integer> tally = new java.util.HashMap<Integer, Integer>();

        Emu() { super("emu"); }

        void setPaused(boolean p) {
            paused = p;
            if (p) vsync.stop(); else vsync.start();
            final AudioOut a = audio;
            if (a != null) { if (p) a.pause(); else { a.resync(); a.play(); } }
            synchronized (pauseLock) { pauseLock.notifyAll(); }
        }

        void quit() { quit = true; setPaused(true); }

        /** Runs r on this thread between frames, waiting up to timeoutMs for it to finish. */
        void call(final Runnable r, long timeoutMs) {
            final Object done = new Object();
            final boolean[] fin = { false };
            jobs.add(new Runnable() { public void run() {
                try { r.run(); } finally { synchronized (done) { fin[0] = true; done.notifyAll(); } }
            } });
            synchronized (pauseLock) { pauseLock.notifyAll(); }
            if (timeoutMs <= 0 || Thread.currentThread() == this) return;
            long end = System.currentTimeMillis() + timeoutMs;
            synchronized (done) {
                while (!fin[0]) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) break;
                    try { done.wait(left); } catch (InterruptedException e) { break; }
                }
            }
        }

        void newSession() {
            frame = 0; lastArea = -1;
            game.theme = null;
            double rate = Native.rate();
            if (audio != null) { audio.release(); audio = null; }
            audio = new AudioOut((int) Math.round(rate > 0 ? rate : 48000));
            if (!paused) audio.play();
        }

        Bitmap lastFrame() { return last; }

        /*
         * Pacing: one emulated frame per display refresh (or per N refreshes on 120/144 Hz screens) when the
         * display is within 2% of the NES rate, so every frame is shown exactly once. Otherwise (e.g. 90 Hz) an
         * accumulator decides how many frames to run on each refresh. Audio is resampled to follow (AudioOut).
         * Without vsync ticks (window hidden) it falls back to a timer.
         */
        @Override public void run() {
            long seen = 0, next = System.nanoTime(), statT = System.nanoTime();
            double acc = 0;
            int statFrames = 0, vsyncsSinceRun = 0;
            while (!quit) {
                Runnable j;
                while ((j = jobs.poll()) != null) j.run();
                if (paused || !started) {
                    synchronized (pauseLock) {
                        if ((paused || !started) && jobs.isEmpty() && !quit) {
                            try { pauseLock.wait(200); } catch (InterruptedException ignored) {}
                        }
                    }
                    seen = vsync.count(); next = System.nanoTime(); acc = 0;
                    continue;
                }
                double coreFps = Native.fps(); if (coreFps <= 0) coreFps = Gba.FPS;
                double hz = vsync.hz();
                int runs;
                double effFps;
                long v = vsync.waitAfter(seen, 50);
                if (v < 0) {                                    // no vsync: timer pacing
                    next += (long) (1e9 / coreFps);
                    long wait = next - System.nanoTime();
                    if (wait > 0) { try { Thread.sleep(wait / 1000000, (int) (wait % 1000000)); } catch (InterruptedException ignored) {} }
                    else if (wait < -100000000) next = System.nanoTime();
                    runs = 1; effFps = coreFps;
                } else {
                    long ticks = Math.min(v - seen, 4);
                    seen = v;
                    next = System.nanoTime();
                    int m = (int) Math.round(hz / coreFps);
                    if (m >= 1 && Math.abs(hz / m - coreFps) / coreFps < 0.02) {
                        vsyncsSinceRun += ticks;
                        runs = vsyncsSinceRun / m;              // normally 1; 2 after a missed refresh
                        vsyncsSinceRun -= runs * m;
                        effFps = hz / m;
                    } else {
                        acc += ticks * coreFps / hz;
                        runs = (int) acc; acc -= runs;
                        effFps = coreFps;
                    }
                    if (runs > 2) runs = 2;
                }
                if (runs == 0) continue;
                boolean fast = (specialBits & (1 << 3)) != 0;
                if (fast) runs *= 3;
                boolean audioOn = prefs.getBoolean("audio", true);
                int r = 0;
                for (int i = 0; i < runs; i++) {
                    int turbo = ((frame >> 1) & 1) == 0 ? 0 : ((specialBits & 1) != 0 ? Gba.B : 0) | ((specialBits & 2) != 0 ? Gba.A : 0);
                    int bits = keyBits | axisBits | pad.bits | turbo;
                    boolean combo = (bits & (Gba.SELECT | Gba.START)) == (Gba.SELECT | Gba.START);
                    if (combo && !comboHeld) {                      // SELECT+START: pause menu (once per press)
                        comboHeld = true;
                        ui.post(new Runnable() { public void run() { openMenu(); } });
                        break;
                    }
                    if (!combo) comboHeld = false;
                    if (combo) bits &= ~(Gba.SELECT | Gba.START);   // still held after the menu: the game doesn't see it
                    int rr = Native.runFrame(bits, px);
                    if (codeHealth || codeLives) applyCodes();
                    if (pendingWarp >= 0) applyWarp();
                    if (rr != 0) r = rr;
                    frame++;
                    int n = Native.audio(samples);
                    if (!fast && audioOn && audio != null) audio.write(samples, n, coreFps / effFps);
                    if (frame % 20 == 0) sampleTheme();
                    if (frame % 600 == 0) Native.saveFlush();         // the game's EEPROM save, when it changed
                }
                if (r != 0) publish(r >> 16, r & 0xFFFF);
                if (view3d) { capture3d(snap); r3d.submit(snap, px, Gba.inGame()); glView.requestRender(); }
                statFrames += runs;
                long now = System.nanoTime();
                if (now - statT >= 1000000000L) {
                    stats = String.format("%.1f FPS  %.0f HZ  AUD %d%%", statFrames * 1e9 / (now - statT), hz,
                            audio != null ? (int) (audio.fill * 100) : 0);
                    statFrames = 0; statT = now;
                    if (game.showStats) game.postInvalidate();
                }
            }
            if (audio != null) audio.release();
            vsync.stop();
        }

        private void publish(int w, int h) {
            Bitmap b = bufs[bufIdx];
            if (b == null || fw != w || fh != h) {
                fw = w; fh = h;
                for (int i = 0; i < 3; i++) bufs[i] = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
                b = bufs[bufIdx];
            }
            px.rewind();
            b.copyPixelsFromBuffer(px);
            synchronized (game.lock) { game.frame = b; }
            last = b;
            bufIdx = (bufIdx + 1) % 3;
            game.postInvalidateOnAnimation();
        }

        private void sampleTheme() {
            if (frame % 60 == 0) {
                Native.read(0x06000000, video, 0, 0x18000);
                Native.read(0x05000000, video, 0x18000, 0x400);
                Native.read(0x07000000, video, 0x18400, 0x400);
                Native.read(0x04000000, video, 0x18800, 0x60);
                videoB64 = Base64.encodeToString(video, Base64.NO_WRAP);
            }
            if (!Gba.inGame()) return;
            int area = Native.peek(Gba.CURRENT_LEVEL) * 16 + (Native.peek(Gba.AREA) & 15);
            Native.read(0x04000000, ts.io, 0, 0x60);
            Native.read(0x05000000, ts.pal, 0, 0x200);
            Native.read(0x06000000, ts.vram, 0, 0x10000);
            Native.read(Gba.LEVEL_DATA, ts.level, 0, ts.level.length);
            ts.px = Gba.u16(Gba.PLAYER_X); ts.py = Gba.u16(Gba.PLAYER_Y);
            ts.vertical = Native.peek(Gba.IS_VERTICAL) != 0;
            ts.quadMode = Gba.quadMode(Gba.u16(Gba.TILESET), Native.peek(Gba.TILE_FLAG_A), Native.peek(Gba.TILE_FLAG_B));
            // One border theme per area: sample the first ~4 seconds in an area, lock in the most frequent pick and
            // reuse it whenever the player returns, so the border no longer changes from room to room.
            BezelTheme locked = areaThemes.get(area);
            if (locked != null) { showTheme(locked); return; }
            if (area != lastArea) { lastArea = area; tally.clear(); candidates.clear(); themeSamples = 0; }
            BezelTheme t = BezelTheme.build(ts, gfx.rom, area);
            if (t == null) return;
            Integer n = tally.get(t.key);
            tally.put(t.key, n == null ? 1 : n + 1);
            if (!candidates.containsKey(t.key)) candidates.put(t.key, t);
            BezelTheme cur = game.theme;
            if (cur == null || cur.area != area) showTheme(t);         // something area-appropriate right away
            if (++themeSamples >= 12) {
                int bestKey = t.key, bestN = -1;
                for (java.util.Map.Entry<Integer, Integer> e : tally.entrySet())
                    if (e.getValue() > bestN) { bestN = e.getValue(); bestKey = e.getKey(); }
                BezelTheme best = candidates.get(bestKey);
                areaThemes.put(area, best);
                showTheme(best);
            }
        }

        private void showTheme(BezelTheme t) {
            if (game.theme == t) return;
            game.theme = t;
            game.postInvalidate();
            border3d.postInvalidate();
        }
    }

    // ------------------------------------------------------------------ key bindings

    /** keycode -> button index (Gba.BUTTON_NAMES). Setting a key that's already used swaps the two. */
    static final class Binds {
        final android.util.SparseIntArray map = new android.util.SparseIntArray();

        void defaults() {
            map.clear();
            map.put(KeyEvent.KEYCODE_DPAD_UP, 0); map.put(KeyEvent.KEYCODE_DPAD_DOWN, 1);
            map.put(KeyEvent.KEYCODE_DPAD_LEFT, 2); map.put(KeyEvent.KEYCODE_DPAD_RIGHT, 3);
            map.put(KeyEvent.KEYCODE_BUTTON_A, 4); map.put(KeyEvent.KEYCODE_BUTTON_B, 5);
            map.put(KeyEvent.KEYCODE_BUTTON_X, 8); map.put(KeyEvent.KEYCODE_BUTTON_Y, 9);
            map.put(KeyEvent.KEYCODE_BUTTON_SELECT, 6); map.put(KeyEvent.KEYCODE_BUTTON_START, 7);
            map.put(KeyEvent.KEYCODE_BUTTON_MODE, 10); map.put(KeyEvent.KEYCODE_BUTTON_THUMBL, 11);
            map.put(KeyEvent.KEYCODE_BUTTON_L1, 17); map.put(KeyEvent.KEYCODE_BUTTON_R1, 18);
            map.put(KeyEvent.KEYCODE_A, 17); map.put(KeyEvent.KEYCODE_S, 18);
            map.put(KeyEvent.KEYCODE_BUTTON_L2, 12); map.put(KeyEvent.KEYCODE_BUTTON_R2, 13);
            map.put(KeyEvent.KEYCODE_Z, 4); map.put(KeyEvent.KEYCODE_X, 5);
            map.put(KeyEvent.KEYCODE_SHIFT_RIGHT, 6); map.put(KeyEvent.KEYCODE_ENTER, 7);
            map.put(KeyEvent.KEYCODE_ESCAPE, 10); map.put(KeyEvent.KEYCODE_TAB, 11);
            map.put(KeyEvent.KEYCODE_F5, 12); map.put(KeyEvent.KEYCODE_F7, 13);
            map.put(KeyEvent.KEYCODE_F2, 14); map.put(KeyEvent.KEYCODE_BUTTON_THUMBR, 14);
            map.put(KeyEvent.KEYCODE_PAGE_UP, 15); map.put(KeyEvent.KEYCODE_PAGE_DOWN, 16);
            map.put(KeyEvent.KEYCODE_F3, 19);
        }

        void load(String json) {
            defaults();
            if (json == null || json.isEmpty()) return;
            try {
                JSONObject o = new JSONObject(json);
                map.clear();
                Iterator<String> it = o.keys();
                while (it.hasNext()) { String k = it.next(); map.put(Integer.parseInt(k), o.getInt(k)); }
                // bindings saved before 0.22 have no 3D CAMERA key: give it F3 when that's free
                if (map.indexOfValue(19) < 0 && map.get(KeyEvent.KEYCODE_F3, -1) < 0) map.put(KeyEvent.KEYCODE_F3, 19);
            } catch (Exception e) { defaults(); }
        }

        String save() {
            try {
                JSONObject o = new JSONObject();
                for (int i = 0; i < map.size(); i++) o.put(String.valueOf(map.keyAt(i)), map.valueAt(i));
                return o.toString();
            } catch (Exception e) { return ""; }
        }

        int button(int code) { return map.get(code, -1); }

        void assign(int code, int idx) {
            int prev = map.get(code, -1);
            if (prev == idx) return;
            List<Integer> old = new ArrayList<Integer>();
            for (int i = 0; i < map.size(); i++) if (map.valueAt(i) == idx) old.add(map.keyAt(i));
            if (prev >= 0 && !old.isEmpty()) {
                map.put(old.get(0), prev);          // swap
            } else {
                for (int k : old) {
                    // keep the other keyboard/pad entries for this button only if they are a different device class
                    if (isPadKey(k) == isPadKey(code)) map.delete(k);
                }
            }
            map.put(code, idx);
        }

        static boolean isPadKey(int k) { return KeyEvent.isGamepadButton(k) || (k >= KeyEvent.KEYCODE_DPAD_UP && k <= KeyEvent.KEYCODE_DPAD_CENTER); }

        String describe() {
            try {
                JSONArray a = new JSONArray();
                for (int b = 0; b < Gba.BUTTON_NAMES.length; b++) {
                    JSONObject o = new JSONObject();
                    o.put("name", Gba.BUTTON_NAMES[b]);
                    JSONArray keys = new JSONArray();
                    for (int i = 0; i < map.size(); i++) if (map.valueAt(i) == b)
                        keys.put(KeyEvent.keyCodeToString(map.keyAt(i)).replace("KEYCODE_", "").replace("BUTTON_", "").replace("_", " "));
                    o.put("keys", keys);
                    a.put(o);
                }
                return a.toString();
            } catch (Exception e) { return "[]"; }
        }
    }

    static String md5(byte[] b) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder();
            for (byte x : d.digest(b)) sb.append(String.format("%02x", x & 255));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    Rect hingeRect() { return root.hinge; }

    /** One frame for the 3D view, in Scene3D's snapshot layout. Runs on the emulation thread. */
    static void capture3d(byte[] s) {
        Native.read(0x04000000, s, Scene3D.IO, 0x60);
        Native.read(0x05000000, s, Scene3D.PAL, 0x400);
        Native.read(0x06000000, s, Scene3D.VRAM, 0x18000);
        Native.read(0x07000000, s, Scene3D.OAM, 0x400);
        Native.read(Gba.LEVEL_DATA, s, Scene3D.LVL, Scene3D.LVL_LEN);
        Native.read(Scene3D.VARS_ADDR, s, Scene3D.VARS, Scene3D.VARS_LEN);
    }
}

package com.jack.smadual;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/**
 * Experimental 3D view: draws what Scene3D builds from each emulated frame (blocks, cut-outs, back wall, the area's
 * own scenery as a far backdrop, sprites, life meter). Camera presets as in Metroid Dual.
 * The shaders are mirrored in tests/scene3d/view.html, which renders the same vertex data in headless Chromium.
 */
final class Renderer3D implements GLSurfaceView.Renderer {
    static final int SNAP = Scene3D.SNAP;

    volatile String camPreset = "tilt";
    final Scene3D scene = new Scene3D();

    private final Object lock = new Object();
    private byte[] pending = new byte[SNAP], current = new byte[SNAP];
    private final byte[] picPending = new byte[240 * 160 * 2], pic = new byte[240 * 160 * 2];
    private boolean fresh, playPending, playing;
    private int playRun;
    private final ByteBuffer picBuf = ByteBuffer.allocateDirect(240 * 160 * 2).order(ByteOrder.nativeOrder());

    /**
     * One emulated frame: the memory snapshot, the 2D picture (RGB565, 240x160) and whether the game is in actual play
     * (Gba.inGame()). Outside play — title, file and character select, the chart, pause, transitions — and whenever
     * the level data doesn't match the screen, the view shows the plain 2D picture instead of a 3D guess.
     */
    void submit(byte[] snap, ByteBuffer picture, boolean inPlay) {
        synchronized (lock) {
            System.arraycopy(snap, 0, pending, 0, SNAP);
            if (picture != null) { picture.position(0); picture.get(picPending, 0, Math.min(picPending.length, picture.remaining())); picture.position(0); }
            playPending = inPlay; fresh = true;
        }
    }
    /** True while the last drawn frame was the flat picture. */
    volatile boolean flat = true;

    private int prog, aPos, aUv, aShade, aKind, uMvp, uTex, uCave, uBg, uLight, uLightOn, uMode, uMask, uGround;
    private int atlasTex, caveTex, maskTex, hudTex, picTex, vbo;
    private int W = 1, H = 1;
    private float pitch = 16, yaw = 0, zoom = 1, camDist = 400;
    private FloatBuffer fb = ByteBuffer.allocateDirect((1 << 17) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private ByteBuffer atlasBuf, bdBuf, hudBuf, maskBuf = ByteBuffer.allocateDirect(256 * 256);
    private final FloatBuffer hudQuad = ByteBuffer.allocateDirect(6 * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    static final String VS =
        "attribute vec3 aPos; attribute vec2 aUv; attribute float aShade; attribute float aKind;\n" +
        "uniform mat4 uMvp; varying vec2 vUv; varying float vShade; varying float vKind; varying vec3 vWorld;\n" +
        "void main(){ vUv=aUv; vShade=aShade; vKind=aKind; vWorld=aPos; gl_Position=uMvp*vec4(aPos,1.0); }";
    static final String FS =
        "precision highp float;\n" +
        "varying vec2 vUv; varying float vShade; varying float vKind; varying vec3 vWorld;\n" +
        "uniform sampler2D uTex; uniform sampler2D uCave; uniform sampler2D uMask; uniform vec3 uBg;\n" +
        "uniform vec2 uLight; uniform float uLightOn; uniform int uMode; uniform vec4 uGround;\n" +
        "void main(){\n" +
        "  if (uMode == 1) {\n" +                                   // backdrop: alpha 1 scenery, faded toward the sky; 0.5 the sky itself
        "    vec4 b = texture2D(uCave, vUv);\n" +
        "    if (b.a < 0.25) discard;\n" +
        "    gl_FragColor = vec4(mix(uBg, b.rgb, 1.15 - 0.3 * b.a), 1.0); return;\n" +
        "  }\n" +
        "  if (vKind > 5.5) { gl_FragColor = vec4(vec3(vKind > 6.5 ? 0.0 : 1.0), vShade); return; }\n" +   // window brighten / darken
        "  vec4 c = texture2D(uTex, vUv);\n" +
        "  if (uMode == 2) { if (c.a < 0.5) discard; gl_FragColor = vec4(c.rgb, 1.0); return; }\n" +
        "  if (c.a < 0.5) { if (vKind < 0.5) c = vec4(uBg * 0.8, 1.0); else discard; }\n" +
        "  float shade = vShade;\n" +
        "  if (vKind > 3.5 && vKind < 4.5) {\n" +                   // pillow cloud: only white and pink, the black checker and the shadows pink
        "    float l = dot(c.rgb, vec3(0.3, 0.59, 0.11));\n" +
        "    if (l < 0.5) c.rgb = mix(vec3(1.0, 0.74, 0.87), vec3(1.0), l * 1.6);\n" +
        "    c.rgb = mix(vec3(0.98, 0.72, 0.85), c.rgb, clamp((shade - 0.4) / 0.5, 0.0, 1.0)); shade = 1.0;\n" +
        "  }\n" +
        "  if (vKind > 4.5 && texture2D(uMask, vec2((vWorld.x + 0.5) / 256.0, (-vWorld.y + 0.5) / 256.0)).r > 0.5) discard;\n" +
        "  shade *= mix(1.0, mix(1.15, 0.35, smoothstep(24.0, 160.0, distance(vWorld.xy, uLight))), uLightOn);\n" +
        "  gl_FragColor = vec4(c.rgb * shade, 1.0);\n" +
        "}";

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig cfg) {
        prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, VS));
        GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, FS));
        GLES20.glLinkProgram(prog);
        aPos = GLES20.glGetAttribLocation(prog, "aPos"); aUv = GLES20.glGetAttribLocation(prog, "aUv");
        aShade = GLES20.glGetAttribLocation(prog, "aShade"); aKind = GLES20.glGetAttribLocation(prog, "aKind");
        uMvp = GLES20.glGetUniformLocation(prog, "uMvp"); uTex = GLES20.glGetUniformLocation(prog, "uTex");
        uCave = GLES20.glGetUniformLocation(prog, "uCave"); uBg = GLES20.glGetUniformLocation(prog, "uBg");
        uLight = GLES20.glGetUniformLocation(prog, "uLight");
        uLightOn = GLES20.glGetUniformLocation(prog, "uLightOn"); uMode = GLES20.glGetUniformLocation(prog, "uMode");
        uMask = GLES20.glGetUniformLocation(prog, "uMask"); uGround = GLES20.glGetUniformLocation(prog, "uGround");
        int[] t = new int[4];
        int[] t4 = new int[5];
        GLES20.glGenTextures(5, t4, 0);
        atlasTex = t4[0]; caveTex = t4[1]; maskTex = t4[2]; hudTex = t4[3]; picTex = t4[4];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, picTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, 256, 256, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5, null);
        params();
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 256, 256, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, maskBuf);
        params();
        GLES20.glGenBuffers(1, t, 3);
        vbo = t[3];
        scene.atlasChanged = true; scene.bdChanged = true; scene.hudChanged = true;
        forceAtlas = true;
    }
    private boolean forceAtlas;

    private static void params() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private static ByteBuffer direct(ByteBuffer b, byte[] data) {
        if (b == null || b.capacity() < data.length) b = ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder());
        b.clear(); b.put(data); b.position(0);
        return b;
    }

    @Override public void onSurfaceChanged(GL10 unused, int w, int h) { W = Math.max(1, w); H = Math.max(1, h); GLES20.glViewport(0, 0, W, H); }

    /** Camera presets: pitch, yaw, zoom. 0.22 kept three (MainActivity.CAMS); 0.25 brings diorama back; side reads as tilt. */
    static float[] preset(String n) {
        switch (n) {
            case "low": return new float[] { -10, 18, 1.05f };
            case "diorama": return new float[] { 30, -24, 0.92f };
            case "far": return new float[] { 22, 0, 0.62f };
            default: return new float[] { 16, 0, 1 };
        }
    }

    // 0.22: depth precision. With a 16-bit depth buffer and the near plane at 10 (0.6-0.21), depth resolved only ~0.12
    // units at the scene (~280 from the eye), coarser than the gaps between some faces (back wall 0.01 behind the
    // blocks, light and sprite walls 0.05, a bridge's underside level with the hill tops under its ends), so on phones
    // ledges z-fought and flicked back a step as the camera moved (desktop SwiftShader has 24 bits and never showed it).
    // Now 24 bits when the device has them, and near / far hug the scene: nothing comes within ~100 of the eye (deepest
    // blocks at depth 48, nest 4) and the backdrop is at most ~500 away.
    static final float NEAR = 40, FAR = 2000;
    static final GLSurfaceView.EGLConfigChooser CONFIG = new GLSurfaceView.EGLConfigChooser() {
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay dpy) {
            for (int depth : new int[] { 24, 16 }) {
                int[] spec = { EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                               EGL10.EGL_DEPTH_SIZE, depth, EGL10.EGL_RENDERABLE_TYPE, 4 /* EGL_OPENGL_ES2_BIT */, EGL10.EGL_NONE };
                EGLConfig[] cfg = new EGLConfig[1];
                int[] n = new int[1];
                if (egl.eglChooseConfig(dpy, spec, cfg, 1, n) && n[0] > 0) return cfg[0];
            }
            throw new IllegalArgumentException("no EGL config");
        }
    };

    /** MVP for the scene (world: x right, y up, GBA screen at x 0..240, y 0..-160). Also sets camDist. */
    static float[] mvp(int W, int H, float pitch, float yaw, float zoom, int depth, float[] distOut) {
        float fov = 32f, asp = W / (float) H;
        float halfH = Math.max(80f, 120f / asp) / zoom;
        float dist = halfH / (float) Math.tan(Math.toRadians(fov / 2)) + depth;
        distOut[0] = dist;
        double p = Math.toRadians(pitch), y = Math.toRadians(yaw);
        float cx = 120, cy = -80;
        float ex = cx + (float) (dist * Math.sin(y) * Math.cos(p)), ey = cy + (float) (dist * Math.sin(p)), ez = (float) (dist * Math.cos(y) * Math.cos(p));
        float[] proj = new float[16], viewM = new float[16], out = new float[16];
        Matrix.perspectiveM(proj, 0, fov, asp, NEAR, FAR);
        Matrix.setLookAtM(viewM, 0, ex, ey, ez, cx, cy, 0, 0, 1, 0);
        Matrix.multiplyMM(out, 0, proj, 0, viewM, 0);
        return out;
    }

    @Override public void onDrawFrame(GL10 unused) {
        boolean newPic = false;
        synchronized (lock) {
            if (fresh) {
                byte[] t = current; current = pending; pending = t; fresh = false;
                System.arraycopy(picPending, 0, pic, 0, pic.length); newPic = true;
                playing = playPending;
                playRun = playing ? playRun + 1 : 0;
            }
        }
        // 3D only once the game has been in play for a few frames and the level data matches the screen
        boolean want3d = playing && playRun >= 8;
        if (want3d) { scene.setCamera(W, H, pitch, yaw, zoom); scene.build(current, camDist); }
        if (!want3d || !scene.mapped) { drawFlat(newPic); return; }
        flat = false;
        float[] pr = preset(camPreset);
        pitch += (pr[0] - pitch) * 0.12f; yaw += (pr[1] - yaw) * 0.12f; zoom += (pr[2] - zoom) * 0.12f;
        float[] d = new float[1];
        float[] m = mvp(W, H, pitch, yaw, zoom, scene.depth, d);
        camDist = d[0];
        Scene3D s = scene;

        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        if (s.atlasChanged || forceAtlas) {
            atlasBuf = direct(atlasBuf, s.atlas);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTex);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, Scene3D.ATLAS_W, Scene3D.ATLAS_H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, atlasBuf);
            params();
            s.atlasChanged = false; forceAtlas = false;
        }
        if (s.bdChanged) {
            bdBuf = direct(bdBuf, s.backdrop);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, caveTex);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, s.bdW, s.bdH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bdBuf);
            params();
            s.bdChanged = false;
        }
        if (s.hudChanged) {
            hudBuf = direct(hudBuf, s.hud);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTex);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 256, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, hudBuf);
            params();
            s.hudChanged = false;
        }
        if (s.maskChanged) {
            maskBuf.clear(); maskBuf.put(s.mask); maskBuf.position(0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 256, 256, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, maskBuf);
            s.maskChanged = false;
        }

        int sky = s.skyRgb;
        float br = ((sky >> 16) & 255) / 255f, bg = ((sky >> 8) & 255) / 255f, bb = (sky & 255) / 255f;
        GLES20.glClearColor(br, bg, bb, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_CULL_FACE);

        int nv = s.nv;
        if (fb.capacity() < nv * 7) fb = ByteBuffer.allocateDirect(s.verts.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.clear(); fb.put(s.verts, 0, nv * 7); fb.position(0);
        GLES20.glUseProgram(prog);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, nv * 7 * 4, fb, GLES20.GL_STREAM_DRAW);
        int stride = 7 * 4;
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, 0);
        GLES20.glEnableVertexAttribArray(aUv); GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, stride, 12);
        GLES20.glEnableVertexAttribArray(aShade); GLES20.glVertexAttribPointer(aShade, 1, GLES20.GL_FLOAT, false, stride, 20);
        GLES20.glEnableVertexAttribArray(aKind); GLES20.glVertexAttribPointer(aKind, 1, GLES20.GL_FLOAT, false, stride, 24);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, m, 0);
        GLES20.glUniform3f(uBg, br, bg, bb);
        int gr = s.groundRgb;
        GLES20.glUniform4f(uGround, ((gr >> 16) & 255) / 255f, ((gr >> 8) & 255) / 255f, (gr & 255) / 255f, gr < 0 ? 0 : 1);
        GLES20.glUniform1f(uLightOn, s.light ? s.lightAmt : 0);
        GLES20.glUniform2f(uLight, s.lightX, s.lightY);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTex); GLES20.glUniform1i(uTex, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, caveTex); GLES20.glUniform1i(uCave, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex); GLES20.glUniform1i(uMask, 2);
        GLES20.glUniform1i(uMode, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, s.caveStart);
        if (s.caveCount > 0) { GLES20.glUniform1i(uMode, 1); GLES20.glDrawArrays(GLES20.GL_TRIANGLES, s.caveStart, s.caveCount); }
        // sprites: their own depth pass (depth cleared first), so nothing that overlaps terrain in 2D is cut off but a
        // thick sprite (Scene3D.extrude) hides its own walls. LEQUAL: at equal depth the later (higher priority) OAM entry
        // wins, as on the GBA. No culling: the walls face every way. Blending for the window-effect quads at the end of the
        // pass (Scene3D.glow); everything else in it is opaque.
        if (s.hudStart > s.spriteStart) {
            GLES20.glUniform1i(uMode, 0);
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
            GLES20.glDepthFunc(GLES20.GL_LEQUAL);
            GLES20.glDisable(GLES20.GL_CULL_FACE);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, s.spriteStart, s.hudStart - s.spriteStart);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glDepthFunc(GLES20.GL_LESS);
        }
        // HUD layer (BG3): flat, in the 3:2 picture's own pixels
        flatQuad(hudTex);
    }

    /** The plain 2D picture in the 3:2 rectangle (same place the HUD layer goes). */
    private void drawFlat(boolean newPic) {
        flat = true;
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, picTex);
        if (newPic) {
            picBuf.clear(); picBuf.put(pic); picBuf.position(0);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 240, 160, GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5, picBuf);
        }
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glUseProgram(prog);
        flatQuad(picTex);
    }

    private void flatQuad(int tex) {
        int stride = 7 * 4;
        float asp = 240f / 160f, rw = W, rh = W / asp;
        if (rh > H) { rh = H; rw = H * asp; }
        float rx = (W - rw) / 2, ry = (H - rh) / 2;
        float[] ortho = new float[16], sc = new float[16], o2 = new float[16];
        Matrix.orthoM(ortho, 0, 0, W, H, 0, -1, 1);
        Matrix.setIdentityM(sc, 0);
        sc[0] = rw / 240f; sc[5] = rh / 160f; sc[12] = rx; sc[13] = ry;
        Matrix.multiplyMM(o2, 0, ortho, 0, sc, 0);
        float u = 240f / 256f, v = 160f / 256f;
        float[] hq = { 0, 160, 0, 0, v, 1, 4,  240, 160, 0, u, v, 1, 4,  240, 0, 0, u, 0, 1, 4,
                       0, 160, 0, 0, v, 1, 4,  240, 0, 0, u, 0, 1, 4,  0, 0, 0, 0, 0, 1, 4 };
        hudQuad.clear(); hudQuad.put(hq); hudQuad.position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glEnableVertexAttribArray(aShade); GLES20.glEnableVertexAttribArray(aKind);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, hudQuad);
        hudQuad.position(3); GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, stride, hudQuad);
        hudQuad.position(5); GLES20.glVertexAttribPointer(aShade, 1, GLES20.GL_FLOAT, false, stride, hudQuad);
        hudQuad.position(6); GLES20.glVertexAttribPointer(aKind, 1, GLES20.GL_FLOAT, false, stride, hudQuad);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, o2, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniform1i(uMode, 2);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
    }
}

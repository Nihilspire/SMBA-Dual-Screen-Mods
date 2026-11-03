package com.jack.smadual;

import java.io.*;
import java.nio.*;
import java.nio.file.*;

/** Desktop test: runs Scene3D on a drive "dump" (IWRAM | EWRAM | IO | PAL | VRAM | OAM) and writes its output for view.html.
 *  java SceneDump rom.gba in.dump out.s3d [depth] [back] [cave] [light]
 *  out: header ints (nv, caveStart, caveCount, spriteStart, hudStart, bdW, bdH, sky, mapped, lightX*100, lightY*100, lightAmt*100, depth, ground)
 *       then verts (float32 * nv*7), atlas RGBA 512x512, backdrop RGBA bdW x bdH, mask 256x256, hud RGBA 256x256 */
public class SceneDump {
    static byte[] snap(byte[] d) {
        byte[] s = new byte[Scene3D.SNAP];
        int IW = 0, EW = 0x8000, IO = 0x48000, PAL = 0x48400, VR = 0x48800, OAM = 0x60800;
        System.arraycopy(d, IO, s, Scene3D.IO, 0x60);
        System.arraycopy(d, PAL, s, Scene3D.PAL, 0x400);
        System.arraycopy(d, VR, s, Scene3D.VRAM, 0x18000);
        System.arraycopy(d, OAM, s, Scene3D.OAM, 0x400);
        System.arraycopy(d, EW, s, Scene3D.LVL, Scene3D.LVL_LEN);
        System.arraycopy(d, IW + (Scene3D.VARS_ADDR & 0x7FFF), s, Scene3D.VARS, Scene3D.VARS_LEN);
        return s;
    }
    public static void main(String[] a) throws Exception {
        Scene3D s = new Scene3D();
        s.rom = Files.readAllBytes(Paths.get(a[0]));
        byte[] f = snap(Files.readAllBytes(Paths.get(a[1])));
        if (a.length > 3) s.depth = Integer.parseInt(a[3]);
        if (a.length > 4) s.back = a[4];
        if (a.length > 5) s.cave = Boolean.parseBoolean(a[5]);
        if (a.length > 6) s.light = Boolean.parseBoolean(a[6]);
        float camDist = Float.parseFloat(System.getProperty("camDist", "300"));
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) s.build(f, camDist);     // let the light settle
        long t1 = System.nanoTime();
        ByteBuffer b = ByteBuffer.allocate(14 * 4 + s.nv * 28 + s.atlas.length + s.backdrop.length + s.mask.length + s.hud.length).order(ByteOrder.LITTLE_ENDIAN);
        int[] h = { s.nv, s.caveStart, s.caveCount, s.spriteStart, s.hudStart, s.bdW, s.bdH, s.skyRgb, s.mapped ? 1 : 0,
                    (int) (s.lightX * 100), (int) (s.lightY * 100), (int) (s.lightAmt * 100), s.depth, s.groundRgb };
        for (int v : h) b.putInt(v);
        for (int i = 0; i < s.nv * 7; i++) b.putFloat(s.verts[i]);
        b.put(s.atlas); b.put(s.backdrop); b.put(s.mask); b.put(s.hud);
        Files.write(Paths.get(a[2]), b.array());
        System.out.println("nv " + s.nv + " mapped " + s.mapped + " q " + s.quality + " cam " + s.camX + "," + s.camY + " build " + (t1 - t0) / 20 / 1000 + " us");
    }
}

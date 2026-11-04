package com.jack.smadual;

import java.io.*;
import java.nio.*;
import java.nio.file.*;

/** Desktop test: one Scene3D over a sequence of drive dumps (as the GL thread sees frames), writing selected frames
 *  in SceneDump's format.  java SceneSeq rom.gba outdir dump0 dump1 ...   (writes outdir/fNNN.s3d for every dump) */
public class SceneSeq {
    public static void main(String[] a) throws Exception {
        Scene3D s = new Scene3D();
        s.rom = Files.readAllBytes(Paths.get(a[0]));
        s.round = System.getenv("ROUND") != null;      // the ROUND SPRITES option
        // CAM=low|tilt|diorama|far builds what that camera sees (as Renderer3D, at shot.py's 3:2); unset, the screen + 32 px
        String cam = System.getenv("CAM");
        float dist = 300;
        if (cam != null) {
            float[] pr = cam.equals("low") ? new float[] { -10, 18, 1.05f } : cam.equals("diorama") ? new float[] { 30, -24, 0.92f }
                       : cam.equals("far") ? new float[] { 22, 0, 0.62f } : new float[] { 16, 0, 1 };
            dist = s.setCamera(3, 2, pr[0], pr[1], pr[2]);
        }
        long built = 0;
        for (int k = 2; k < a.length; k++) {
            byte[] f = SceneDump.snap(Files.readAllBytes(Paths.get(a[k])));
            long t0 = System.nanoTime();
            s.build(f, dist);
            built += System.nanoTime() - t0;
            ByteBuffer b = ByteBuffer.allocate(14 * 4 + s.nv * 28 + s.atlas.length + s.backdrop.length + s.mask.length + s.hud.length).order(ByteOrder.LITTLE_ENDIAN);
            int[] h = { s.nv, s.caveStart, s.caveCount, s.spriteStart, s.hudStart, s.bdW, s.bdH, s.skyRgb, s.mapped ? 1 : 0,
                        (int) (s.lightX * 100), (int) (s.lightY * 100), (int) (s.lightAmt * 100), s.depth, s.groundRgb };
            for (int v : h) b.putInt(v);
            for (int i = 0; i < s.nv * 7; i++) b.putFloat(s.verts[i]);
            b.put(s.atlas); b.put(s.backdrop); b.put(s.mask); b.put(s.hud);
            Files.write(Paths.get(a[1], String.format("f%03d.s3d", k - 2)), b.array());
            System.out.println((k - 2) + " nv " + s.nv + " mapped " + s.mapped + " q " + s.quality + " cam " + s.camX + "," + s.camY);
        }
        if (System.getenv("TIMING") != null) System.err.println("build " + built / Math.max(1, a.length - 2) / 1000 + " us a frame");
    }
}

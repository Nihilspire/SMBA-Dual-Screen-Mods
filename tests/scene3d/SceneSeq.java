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
        for (int k = 2; k < a.length; k++) {
            byte[] f = SceneDump.snap(Files.readAllBytes(Paths.get(a[k])));
            s.build(f, 300);
            ByteBuffer b = ByteBuffer.allocate(14 * 4 + s.nv * 28 + s.atlas.length + s.backdrop.length + s.mask.length + s.hud.length).order(ByteOrder.LITTLE_ENDIAN);
            int[] h = { s.nv, s.caveStart, s.caveCount, s.spriteStart, s.hudStart, s.bdW, s.bdH, s.skyRgb, s.mapped ? 1 : 0,
                        (int) (s.lightX * 100), (int) (s.lightY * 100), (int) (s.lightAmt * 100), s.depth, s.groundRgb };
            for (int v : h) b.putInt(v);
            for (int i = 0; i < s.nv * 7; i++) b.putFloat(s.verts[i]);
            b.put(s.atlas); b.put(s.backdrop); b.put(s.mask); b.put(s.hud);
            Files.write(Paths.get(a[1], String.format("f%03d.s3d", k - 2)), b.array());
            System.out.println((k - 2) + " nv " + s.nv + " mapped " + s.mapped + " q " + s.quality + " cam " + s.camX + "," + s.camY);
        }
    }
}

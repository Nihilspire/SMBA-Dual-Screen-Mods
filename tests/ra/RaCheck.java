package com.jack.smadual;

import java.io.*;
import java.nio.file.*;

/** Runs Link's transport and logic on the desktop against tests/mock_ra.py or a real RetroArch.
 *  java RaCheck rom.gba host seconds [3d|2d] [dumpdir]
 *  3d: feeds a Scene3D as the app feeds its renderer and counts how many frames map. Prints pace and what was read.
 *  With dumpdir, also writes the memory it holds at the end as a drive dump (dumpdir/mirror.bin), to compare or render. */
public class RaCheck {
    public static void main(String[] a) throws Exception {
        byte[] rom = Files.readAllBytes(Paths.get(a[0]));
        String host = a[1]; int secs = Integer.parseInt(a[2]); boolean want3d = a.length > 3 && a[3].equals("3d");
        final RetroArch ra = new RetroArch();
        ra.setTarget(host, Integer.getInteger("port", 55355));   // Windows can reserve 55355 (netsh ... show excludedportrange): -Dport=55600
        LinkCore core = new LinkCore(ra);
        final Scene3D scene = new Scene3D(); scene.rom = rom;
        final long[] c = new long[8];                      // 0 frames, 1 in play, 2 mapped, 3 build us
        LinkCore.Sink sink = (snap, inPlay) -> {
            c[0]++;
            if (!inPlay) return;
            c[1]++;
            long t = System.nanoTime();
            scene.setCamera(1240, 780, 16, 0, 1);
            scene.build(snap, 300);
            c[3] += (System.nanoTime() - t) / 1000;
            if (scene.mapped) c[2]++;
        };
        ra.start();
        long t0 = System.currentTimeMillis();
        boolean viewOn = false;
        String spec = "3001140:48,3001990:160,3001C30:160,300116F:1,3001CF0:1792,3001DE0:96,3001A3E:38";
        int hexOk = 0, hexTry = 0, vidOk = 0, vidTry = 0, shots = 0;
        while (System.currentTimeMillis() - t0 < secs * 1000L) {
            Thread.sleep(100);                             // as the page polls
            hexTry++; if (ra.mem(spec).length() > 0) hexOk++;
            if (a.length > 4 && hexTry % 15 == 0) {        // mid-run mirrors, to render: what the 3D view would have had right then
                new File(a[4]).mkdirs();
                try (FileOutputStream o = new FileOutputStream(a[4] + "/m" + (shots++) + ".bin")) { o.write(ra.iw); o.write(ra.ew); o.write(ra.io); o.write(ra.pal); o.write(ra.vram); o.write(ra.oam); }
            }
            ra.mem("2000000:5280");
            if (hexTry % 10 == 0) { vidTry++; if (ra.video() != null) vidOk++; }
            if (want3d && !viewOn && ra.linked()) { core.view(sink); viewOn = true; }
        }
        long dt = System.currentTimeMillis() - t0;
        System.out.println("status state=" + ra.state + " system=" + ra.system + " game=" + ra.game + " crc=" + ra.crc + " version=" + ra.version + " memErr=" + ra.memErr + " problem='" + core.problem(true, true) + "'");
        System.out.println(String.format("rounds %.1f/s  reads %.1f/s  memory %.0f KB/s", ra.rounds * 1000.0 / dt, ra.reads * 1000.0 / dt, ra.bytes / 1.024 / dt));
        System.out.println("mem() answered " + hexOk + "/" + hexTry + "  video() answered " + vidOk + "/" + vidTry);
        System.out.println("level " + ra.peek(Gba.CURRENT_LEVEL) + " area " + ra.peek(Gba.AREA_NO) + " mode " + ra.peek(Gba.MODE) + " sub " + ra.peek(Gba.SUB_MODE) + " state " + ra.peek(Gba.MAIN_STATE)
            + " x " + Gba.u16(Gba.PLAYER_X) + " y " + Gba.u16(Gba.PLAYER_Y) + " frame " + ra.peek(Tracker.FRAME));
        byte[] tb = core.trk.bytes();
        System.out.println("tracker time " + core.trk.t[0] + " deaths " + core.trk.t[1] + " cleared " + Integer.toHexString(core.trk.t[2]));
        if (want3d) System.out.println(String.format("3D: %d frames (%.1f/s), %d in play, %d mapped, build %d us, nv %d", c[0], c[0] * 1000.0 / dt, c[1], c[2], c[1] > 0 ? c[3] / c[1] : 0, scene.nv));
        ra.stop();
        if (a.length > 4) {
            new File(a[4]).mkdirs();
            try (FileOutputStream o = new FileOutputStream(a[4] + "/mirror.bin")) { o.write(ra.iw); o.write(ra.ew); o.write(ra.io); o.write(ra.pal); o.write(ra.vram); o.write(ra.oam); }
        }
    }
}

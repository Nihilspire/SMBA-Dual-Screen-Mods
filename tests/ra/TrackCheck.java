package com.jack.smadual;

import java.nio.file.*;

/** Link's Tracker over a series of drive dumps, as if they were reads of the running game.
 *  java TrackCheck dump0.bin dump1.bin ... : prints the tracker after each one that changed it. */
public class TrackCheck {
    public static void main(String[] a) throws Exception {
        RetroArch ra = new RetroArch();
        Tracker t = new Tracker();
        String last = "";
        for (String f : a) {
            byte[] d = Files.readAllBytes(Paths.get(f));
            System.arraycopy(d, 0, ra.iw, 0, 0x8000);
            t.update(ra);
            String s = String.format("deaths %d cleared %05X who %08X %08X warped %X flags %X", t.t[1], t.t[2], t.t[3], t.t[4], t.t[5], t.t[6]);
            if (!s.equals(last)) System.out.println(f.substring(f.lastIndexOf('/') + 1) + "  level " + ra.peek(Gba.CURRENT_LEVEL) + " char " + ra.peek(Gba.CHARACTER) + "  time " + t.t[0] + "  " + s);
            last = s;
        }
        System.out.println("end time " + t.t[0] + "  " + last);
    }
}

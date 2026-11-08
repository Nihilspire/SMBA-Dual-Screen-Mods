package com.jack.smadual;

import java.nio.file.*;
import java.util.*;

/** The border theme the app would pick for a run of drive dumps (BezelTheme, sampled as MainActivity does, most frequent wins), as text for dualscreen.py:
 *  "accent shade p0 p1 .. p255" in hex ARGB, or "none". java ThemeDump rom dump0 dump1 ... */
public class ThemeDump {
    static int u16(byte[] d, int o) { return (d[o] & 255) | (d[o + 1] & 255) << 8; }
    public static void main(String[] a) throws Exception {
        byte[] rom = Files.readAllBytes(Paths.get(a[0]));
        Map<Integer, Integer> tally = new HashMap<Integer, Integer>();
        Map<Integer, BezelTheme> pick = new HashMap<Integer, BezelTheme>();
        for (int k = 1; k < a.length; k++) {
            byte[] d = Files.readAllBytes(Paths.get(a[k]));               // IWRAM 8000 | EWRAM 40000 | IO 400 | PAL 400 | VRAM 18000 | OAM 400
            if (d[0x19B0] != 3 || d[0x1991] != 1 || d[0x1992] != 1) continue;   // Gba.inGame: the app samples only in play
            BezelTheme.Snap s = new BezelTheme.Snap();
            System.arraycopy(d, 0x48000, s.io, 0, s.io.length);
            System.arraycopy(d, 0x48400, s.pal, 0, s.pal.length);
            System.arraycopy(d, 0x48800, s.vram, 0, s.vram.length);
            System.arraycopy(d, 0x8000, s.level, 0, s.level.length);
            s.px = u16(d, 0x1C4A); s.py = u16(d, 0x1C4E);
            s.vertical = d[0x116F] != 0;
            s.quadMode = Gba.quadMode(u16(d, 0x19FC), d[0x1A29] & 255, d[0x1A4B] & 255);
            BezelTheme t = BezelTheme.build(s, rom, d[0x19EC] & 15);
            if (t == null) continue;
            tally.merge(t.key, 1, Integer::sum); pick.putIfAbsent(t.key, t);
        }
        if (tally.isEmpty()) { System.out.println("none"); return; }
        int best = Collections.max(tally.entrySet(), Map.Entry.comparingByValue()).getKey();
        BezelTheme t = pick.get(best);
        StringBuilder b = new StringBuilder(String.format("%08X %08X", t.accent, t.shade));
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) b.append(String.format(" %08X", t.block.pixels[y * 16 + x]));
        System.out.println(b);
    }
}

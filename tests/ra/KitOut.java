package com.jack.smadual;

import java.nio.file.*;

/** java KitOut rom.gba out.bin : writes the kit Link builds from the ROM, for tests/ra/kitcheck.py. */
public class KitOut {
    public static void main(String[] a) throws Exception { Files.write(Paths.get(a[1]), KitRom.build(Files.readAllBytes(Paths.get(a[0])))); }
}

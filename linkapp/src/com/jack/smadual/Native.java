package com.jack.smadual;

/** Link has no emulator: Gba.java's reads (Gba.inPlay3d and friends) are answered from the RetroArch mirror. */
final class Native {
    private Native() {}
    static volatile RetroArch ra;
    static int peek(int addr) { RetroArch r = ra; return r == null ? 0 : r.peek(addr); }
}

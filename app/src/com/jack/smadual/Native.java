package com.jack.smadual;

import java.nio.ByteBuffer;

/** JNI entry points into libsmadual.so (mGBA libretro core + glue in native/gbajni.c, gbacore.c). */
public final class Native {
    static { System.loadLibrary("smadual"); }
    private Native() {}
    public static native int init(String sysDir, String saveDir);
    public static native int load(String romPath);
    public static native void unload();
    public static native void reset();
    public static native double fps();
    public static native double rate();
    public static native void setOverclock(boolean on);
    public static native void setColorCorrection(boolean on);
    public static native void setFrameBlend(boolean on);
    /** Frames since load in which the game never waited for vblank (NO SLOWDOWN meter). */
    public static native int lagFrames();
    /** Writes the cartridge save (EEPROM) to disk if it changed. 1 written, 0 unchanged. */
    public static native int saveFlush();
    /** Runs one frame; returns (w << 16 | h) when a new picture was copied into dst. */
    public static native int runFrame(int pad, ByteBuffer dst);
    public static native int audio(short[] out);
    /** One byte of the GBA address space (02 EWRAM, 03 IWRAM, 04 IO, 05 palette, 06 VRAM, 07 OAM, 08 ROM). */
    public static native int peek(int addr);
    public static native void poke(int addr, int value);
    /** "3001C40:64,2000000:2400" -> hex string of the bytes, in order. */
    public static native String mem(String spec);
    /** n bytes from addr into out[off..]. */
    public static native void read(int addr, byte[] out, int off, int n);
    /** Save state plus the Dual tracker block (play time, deaths, levels cleared). */
    public static native int stateSave(String path);
    public static native int stateLoad(String path);
    /** Dual tracker (native/smatrack.c): 64 bytes, see Gba.T_*. */
    public static native void tracker(byte[] out);
    public static native void setTracker(int index, int value);
    /** Forget the last level seen, so a level change made by a playtest warp is not counted as a clear. */
    public static native void trackReset();
    /** First start (native/extract.c): boots rom with an empty save folder tmpDir, captures the title and pause screens
     * into outPath ("SMAX"), unloads, points battery saves back at saveDir. Bytes written, or < 0. */
    public static native int extract(String rom, String tmpDir, String saveDir, String outPath);
}

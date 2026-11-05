package com.jack.smadual;

/**
 * The play stats the game doesn't keep (native/smatrack.c in Dual): play time, deaths, levels cleared and by whom. Dual counts
 * them on every emulated frame and stores them in its save states; here they are counted from RetroArch's memory several
 * times a second while the app is running, and kept in the app's preferences. Same 64-byte block as Native.tracker() in Dual.
 *
 * What differs: time comes from the game's own frame counter 03001993 (+1 a frame in play), so missed reads and fast-forward
 * count as they do in Dual. A state loaded in RetroArch jumps the level or lives with no screen in between, which real play
 * never does, so those jumps don't count as a warp, a clear or a death.
 */
final class Tracker {
    static final int FRAME = 0x03001993;
    final int[] t = new int[16];
    private int lastLevel = -1, lastLives = -1, lastChar, prevMode = -1, prevSub, prevLevel, lastFrame = -1;
    private boolean gap;

    void reset() { lastLevel = -1; lastLives = -1; prevMode = -1; lastFrame = -1; gap = false; }
    void clear() { java.util.Arrays.fill(t, 0); reset(); }

    private void cleared(int lv, int ch) {
        if (lv < 0 || lv > 19) return;
        t[2] |= 1 << lv;
        int bit = lv * 2, w = bit < 32 ? 3 : 4;
        bit &= 31;
        t[w] = (t[w] & ~(3 << bit)) | ((ch & 3) << bit);
    }

    void update(RetroArch r) {
        int mode = r.peek(Gba.MODE), sub = r.peek(Gba.SUB_MODE), state = r.peek(Gba.MAIN_STATE), level = r.peek(Gba.CURRENT_LEVEL), ch = r.peek(Gba.CHARACTER) & 3;
        // a new file: nothing cleared on it, in the first level, with stats from another run still here
        if (mode == 3 && sub == 1 && level == 0 && t[2] != 0
            && (r.peek(Gba.CLEARED_BITS) | r.peek(Gba.CLEARED_BITS + 1) | r.peek(Gba.CLEARED_BITS + 2)) == 0) clear();
        if (mode == 3 && prevMode == 3 && prevSub == 1 && (sub == 2 || sub == 6 || level == prevLevel + 1)) cleared(prevLevel, ch);
        prevMode = mode; prevSub = sub; prevLevel = level;
        if (mode == 3 && sub == 4 && (t[6] & 2) == 0 && level == 19) {          // 7-2 has no next level: the ending starting is its clear
            cleared(19, lastLevel == 19 ? lastChar : ch);
            t[6] |= 2;
        }
        if (mode != 3 || sub != 1 || state != 1) { gap = true; lastFrame = -1; return; }
        int fr = r.peek(FRAME);
        if (lastFrame >= 0) t[0] += (fr - lastFrame) & 255;
        lastFrame = fr;
        int li = r.peek(Gba.LIVES);
        if (gap) {                                                              // only across a screen: a state load has none
            if (lastLives >= 0 && li == lastLives - 1) t[1]++;
            if (lastLevel >= 0 && level != lastLevel && level <= 19 && level > lastLevel) {
                cleared(lastLevel, lastChar);
                for (int k = lastLevel + 1; k < level; k++) t[5] |= 1 << k;     // a warp jar: the levels skipped
            }
        }
        if (level == 19) t[6] |= 1;
        lastLevel = level; lastLives = li; lastChar = ch; gap = false;
    }

    byte[] bytes() {
        byte[] b = new byte[64];
        for (int i = 0; i < 16; i++) { int v = t[i]; b[i * 4] = (byte) v; b[i * 4 + 1] = (byte) (v >> 8); b[i * 4 + 2] = (byte) (v >> 16); b[i * 4 + 3] = (byte) (v >> 24); }
        return b;
    }
    void load(byte[] b) {
        if (b == null || b.length < 64) return;
        for (int i = 0; i < 16; i++) t[i] = (b[i * 4] & 255) | (b[i * 4 + 1] & 255) << 8 | (b[i * 4 + 2] & 255) << 16 | (b[i * 4 + 3] & 255) << 24;
    }
}

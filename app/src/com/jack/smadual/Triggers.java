package com.jack.smadual;

/**
 * L2 and R2 (0 and 1 here). Analog triggers report as axes on most pads, as keys on some, and on some as both at once. However they
 * report, one pull is one press: past 60% of the way, let go under 30%, and while one report is down the other adds nothing.
 */
final class Triggers {
    static final float PULL = 0.6f, LET_GO = 0.3f;
    private final boolean[] axis = new boolean[2], key = new boolean[2];

    /** An axis reading. 1: treat it as the key going down. -1: as the key coming up. 0: nothing to do. */
    int axis(int i, float v) {
        boolean now = axis[i] ? v > LET_GO : v > PULL;
        if (now == axis[i]) return 0;
        axis[i] = now;
        return key[i] ? 0 : now ? 1 : -1;
    }

    /** A real key event. False: the axis has this pull covered, so drop the event. */
    boolean key(int i, boolean down) {
        key[i] = down;
        return !axis[i];
    }
}

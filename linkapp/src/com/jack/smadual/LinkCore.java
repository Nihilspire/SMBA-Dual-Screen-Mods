package com.jack.smadual;

/**
 * Link's logic with no Android in it, so tests/ra can run it on the desktop against a stand-in RetroArch: after each round of
 * reads it updates the stats and, while the 3D view is on screen, hands the view a frame.
 *
 * A new area, or coming back from a pause or a door, rewrites much of video memory and the reads come over a few rounds. Until
 * they have all come back the frame goes out marked "not in play", so the view keeps its last good picture rather than showing
 * the new area in the old tiles.
 */
final class LinkCore implements RetroArch.Listener {
    interface Sink { void frame(byte[] snap, boolean inPlay); }
    final RetroArch ra;
    final Tracker trk = new Tracker();
    private final byte[] snap = new byte[Scene3D.SNAP];
    private volatile Sink sink;
    private boolean lastPlay; private int lastKey = -1; private long sweepFrom;

    LinkCore(RetroArch r) { ra = r; Native.ra = r; r.setListener(this); }
    /** The 3D view is on screen (with where its frames go), or isn't (null). */
    void view(Sink s) { sink = s; ra.set3d(s != null); if (s != null) { lastPlay = false; lastKey = -1; sweepFrom = System.currentTimeMillis(); } }

    /** What is in the way of reading the game, "" if nothing. The page says what to do about each. */
    String problem(boolean haveRom, boolean romOk) {
        if (!haveRom) return "norom";
        if (!romOk) return "badrom";
        if (!ra.linked()) return "noreply";
        String st = ra.state, c = ra.crc;
        if (st.isEmpty()) return "noreply";
        if (st.equals("CONTENTLESS")) return "nogame";
        if (!c.isEmpty() && !c.equals(Long.toHexString(Gba.SMA_CRC))) return "wrong";
        if (!ra.memOk() && !ra.memErr.isEmpty()) return "nomap";
        return "";
    }

    @Override public void onRound() {
        if (!ra.stateFresh()) return;
        synchronized (trk) { trk.update(ra); }
        Sink s = sink;
        if (s == null) return;
        boolean play = Gba.inPlay3d();
        int key = ra.peek(Gba.CURRENT_LEVEL) << 16 | ra.peek(Gba.AREA_NO) << 8 | ra.peek(Gba.MAIN_STATE);
        if (play != lastPlay || (play && key != lastKey)) {
            if (play) { sweepFrom = System.currentTimeMillis(); ra.sweep(); }
            lastPlay = play; lastKey = key;
        }
        ra.snapshot(snap);
        s.frame(snap, play && ra.sweptSince(sweepFrom));
    }
}

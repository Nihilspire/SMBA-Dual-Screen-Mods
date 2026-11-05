package com.jack.smadual;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RetroArch network commands (UDP, Settings > Network > Network Commands), read as Shantae Link and Xenogears Link read them:
 * every wanted block goes out as READ_CORE_MEMORY first and the replies are collected after, so one round costs about one
 * frame (RetroArch drains all waiting datagrams each frame). Answers go into a mirror of the GBA's memory, and every read from
 * the page or the 3D view comes straight from that mirror and never waits on the network.
 *
 * mGBA's memory map (libretro.c _setupMaps) reaches IWRAM, EWRAM, IO, palette, VRAM and OAM at their GBA addresses. What is
 * read how often comes from counting which bytes change frame to frame in play (RAM-MAP.md, "RetroArch"): the game state,
 * OAM and the scroll registers all the time; the first 0x800 of VRAM (animated tiles), of OBJ VRAM (the player's frames), the
 * palette and BG0's map often; the other maps less often; the rest only changes on a new area, so it gets a slow sweep, and a
 * fast one when the game says the area changed (sweep()).
 * Plain Java (no Android classes) so it runs against tests/mock_ra.py on the desktop.
 */
public final class RetroArch implements Runnable {
    public interface Listener { void onRound(); }

    // ------------------------------------------------------------------ the mirror
    final byte[] iw = new byte[0x8000], ew = new byte[0x40000], io = new byte[0x400], pal = new byte[0x400], vram = new byte[0x18000], oam = new byte[0x400];
    private byte[] region(int a) {
        switch (a >>> 24) { case 2: return ew; case 3: return iw; case 4: return io; case 5: return pal; case 6: return vram; case 7: return oam; default: return null; }
    }
    private static int mask(byte[] r) { return r.length == 0x18000 ? 0x1FFFF : r.length - 1; }
    /** One byte of the mirror, 0 for anything outside it. */
    public int peek(int a) { byte[] r = region(a); int o = a & 0xFFFFFF; return r == null || o >= r.length ? 0 : r[o] & 255; }

    // ------------------------------------------------------------------ what is read, and how often
    static final int STATE = 0, VIDEO = 1, LEVEL = 2, OTHER = 3;
    static final class Block {
        final int addr, len, group, ms3d, msLight, off; final byte[] to;
        volatile long got, wanted; long sent;
        Block(int a, int n, int g, int m3, int ml, byte[] r) { addr = a; len = n; group = g; ms3d = m3; msLight = ml; to = r; off = a & 0xFFFFFF; }
        boolean covers(int a, int n) { return a >= addr && a + n <= addr + len; }
    }
    private final List<Block> table = new ArrayList<Block>();
    private final Map<Integer, Block> byAddr = new ConcurrentHashMap<Integer, Block>();
    private Block add(int a, int n, int g, int m3, int ml) { Block b = new Block(a, n, g, m3, ml, region(a)); synchronized (table) { table.add(b); } byAddr.put(a, b); return b; }
    private void chunks(int from, int to, int g, int m3, int ml) { for (int a = from; a < to; a += 0x1000) add(a, Math.min(0x1000, to - a), g, m3, ml); }

    public RetroArch() {
        add(0x03001140, 0x40, STATE, 33, 100);       // the file block, level, lives, vertical flag
        add(0x03001990, 0xD4, STATE, 33, 100);       // mode, sub-mode, state, frame counter, area, tile flags, coins, items
        add(0x03001C30, 0x7C0, STATE, 33, 100);      // the player, then the 16 object slots
        add(0x07000000, 0x400, VIDEO, 33, 1000);     // OAM
        add(0x04000000, 0x60, VIDEO, 33, 1000);      // display and BG registers
        add(0x05000000, 0x400, VIDEO, 66, 1000);     // palette
        add(0x06000000, 0x800, VIDEO, 66, 1000);     // BG tiles the game animates
        add(0x06010000, 0x800, VIDEO, 66, 1000);     // OBJ tiles it streams: the player's frames
        add(0x0600F000, 0x1000, VIDEO, 66, 1000);    // BG0's map
        add(0x0600C800, 0x800, VIDEO, 200, 1000);    // BG3 (the HUD)
        add(0x0600D000, 0x1000, VIDEO, 200, 1000);   // BG2
        add(0x0600E000, 0x1000, VIDEO, 200, 1000);   // BG1
        add(0x02000000, 0x14A0, LEVEL, 200, 500);    // the decoded area
        add(0x02002B20, 32, LEVEL, 200, 500);        // the far layers' colours by scanline
        chunks(0x06000800, 0x0600C800, VIDEO, 2000, 6000);
        chunks(0x06010800, 0x06018000, VIDEO, 2000, 6000);
    }

    private volatile boolean view3d;
    private volatile long burstUntil, lastSweep;
    /** The 3D view is on screen: every block, at the faster pace. */
    public void set3d(boolean on) { view3d = on; if (on) sweep(); }
    /** The area changed or play resumed: read everything again soon. */
    public void sweep() {
        long now = System.currentTimeMillis();
        lastSweep = now; burstUntil = now + 800;
        synchronized (table) { for (Block b : table) if (b.group != STATE) b.sent = 0; }
    }
    /** Every block a picture needs has been read since this time. */
    public boolean sweptSince(long t) {
        synchronized (table) { for (Block b : table) if (b.group != OTHER && b.got < t) return false; }
        return true;
    }

    // ------------------------------------------------------------------ the page's reads
    private static final class Spec { int[] addr, len; Block[][] by; }
    private final Map<String, Spec> specs = new HashMap<String, Spec>();
    private Spec spec(String s) {
        Spec sp;
        synchronized (specs) { sp = specs.get(s); }
        if (sp != null) return sp;
        String[] parts = s.split(",");
        sp = new Spec(); sp.addr = new int[parts.length]; sp.len = new int[parts.length]; sp.by = new Block[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            String[] p = parts[i].split(":");
            int a = (int) Long.parseLong(p[0].trim(), 16), n = p.length > 1 ? Integer.parseInt(p[1].trim()) : 1;
            sp.addr[i] = a; sp.len[i] = n;
            List<Block> cov = new ArrayList<Block>();
            synchronized (table) { for (Block b : table) if (a < b.addr + b.len && b.addr < a + n) cov.add(b); }
            int have = 0; for (Block b : cov) have += Math.min(a + n, b.addr + b.len) - Math.max(a, b.addr);
            if (have < n && region(a) != null) { cov.clear(); cov.add(add(a, n, OTHER, 200, 500)); }   // a read nobody planned for: its own block
            sp.by[i] = cov.toArray(new Block[0]);
        }
        synchronized (specs) { specs.put(s, sp); }
        return sp;
    }
    private static final char[] HX = "0123456789abcdef".toCharArray();
    /** "3001140:48,2000000:5280" (hex GBA addresses, decimal lengths) -> hex of the bytes in order, "" until all of them have been read. */
    public String mem(String s) {
        long now = System.currentTimeMillis();
        Spec sp;
        try { sp = spec(s); } catch (RuntimeException e) { return ""; }
        boolean ok = true; int total = 0;
        for (int i = 0; i < sp.addr.length; i++) {
            total += sp.len[i];
            if (sp.by[i].length == 0) ok = false;
            for (Block b : sp.by[i]) { b.wanted = now; if (now - b.got > FRESH_MS) ok = false; }
        }
        if (!ok) return "";
        char[] out = new char[total * 2]; int o = 0;
        for (int i = 0; i < sp.addr.length; i++) {
            byte[] r = region(sp.addr[i]); int a = sp.addr[i] & 0xFFFFFF;
            for (int k = 0; k < sp.len[i]; k++) { int v = r[a + k] & 255; out[o++] = HX[v >> 4]; out[o++] = HX[v & 15]; }
        }
        return new String(out);
    }
    /** VRAM 0x18000 | palette 0x400 | OAM 0x400 | IO 0x60 (what Dual's Link bridge hands the page), null until all of it has been read. */
    public byte[] video() {
        long now = System.currentTimeMillis(); boolean ok = true;
        synchronized (table) { for (Block b : table) if (b.group == VIDEO) { b.wanted = now; if (b.got == 0) ok = false; } }
        if (!ok) return null;
        byte[] v = new byte[0x18000 + 0x400 + 0x400 + 0x60];
        System.arraycopy(vram, 0, v, 0, 0x18000); System.arraycopy(pal, 0, v, 0x18000, 0x400);
        System.arraycopy(oam, 0, v, 0x18400, 0x400); System.arraycopy(io, 0, v, 0x18800, 0x60);
        return v;
    }
    /** One frame for the 3D view, in Scene3D's snapshot layout. */
    public void snapshot(byte[] s) {
        System.arraycopy(io, 0, s, Scene3D.IO, 0x60);
        System.arraycopy(pal, 0, s, Scene3D.PAL, 0x400);
        System.arraycopy(vram, 0, s, Scene3D.VRAM, 0x18000);
        System.arraycopy(oam, 0, s, Scene3D.OAM, 0x400);
        System.arraycopy(ew, 0, s, Scene3D.LVL, Scene3D.LVL_LEN);
        System.arraycopy(iw, Scene3D.VARS_ADDR & 0x7FFF, s, Scene3D.VARS, Scene3D.VARS_LEN);
        System.arraycopy(ew, Scene3D.SKY_ADDR & 0x3FFFF, s, Scene3D.SKY_TAB, Scene3D.SKY_LEN);
    }
    /** The game state has been read recently. */
    public boolean stateFresh() {
        long now = System.currentTimeMillis();
        synchronized (table) { for (Block b : table) if (b.group == STATE && now - b.got > FRESH_MS) return false; }
        return true;
    }

    // ------------------------------------------------------------------ transport
    static final long KEEP_MS = 2500;      // a block nobody asked for in this long is no longer read
    static final long FRESH_MS = 3000;     // a block older than this counts as missing
    private volatile String host = "127.0.0.1";
    private volatile int port = 55355;
    private volatile boolean run;
    private volatile Listener listener;
    private Thread thread;
    private final List<String> outbox = new ArrayList<String>();
    volatile String state = "", system = "", game = "", crc = "", version = "", memErr = "";
    volatile long lastReply, lastStatus, lastMem, rounds, reads, bytes;
    volatile int sent, got;

    public void setTarget(String h, int p) { host = h; port = p; lastReply = 0; }
    public void setListener(Listener l) { listener = l; }
    public synchronized void start() {
        if (run) return;
        run = true;
        thread = new Thread(this, "retroarch");
        thread.setDaemon(true);
        thread.start();
    }
    public synchronized void stop() { run = false; if (thread != null) thread.interrupt(); thread = null; }
    public boolean linked() { return System.currentTimeMillis() - lastReply < 2000; }
    /** Memory reads are answering (RetroArch refuses them when the core gives it no memory map). */
    public boolean memOk() { return System.currentTimeMillis() - lastMem < 2000; }

    /** One-off command such as MENU_TOGGLE or LOAD_STATE_SLOT 2; sent with the next round. */
    public void send(String cmd) { synchronized (outbox) { outbox.add(cmd); } }

    /** Commands sent one at a time, gapMs apart, on their own thread. RetroArch counts hotkey commands that arrive in the
     *  same frame as one press (three STATE_SLOT_PLUS sent together moved the slot by one in 1.22.2). */
    public void sendPaced(final List<String> cmds, final int gapMs) {
        Thread t = new Thread(new Runnable() { public void run() {
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                InetAddress to = InetAddress.getByName(host);
                for (String c : cmds) {
                    byte[] d = (c + "\n").getBytes("US-ASCII");
                    s.send(new DatagramPacket(d, d.length, to, port));
                    Thread.sleep(gapMs);
                }
            } catch (Exception e) {
            } finally { if (s != null) s.close(); }
        } }, "retroarch-paced");
        t.setDaemon(true);
        t.start();
    }

    @Override public void run() {
        DatagramSocket s = null;
        byte[] buf = new byte[65536];
        DatagramPacket in = new DatagramPacket(buf, buf.length);
        List<Block> now3 = new ArrayList<Block>();
        while (run) {
            try {
                if (s == null) { s = new DatagramSocket(); try { s.setReceiveBufferSize(1 << 20); } catch (Exception e) { } }
                InetAddress to = InetAddress.getByName(host);
                long now = System.currentTimeMillis();
                s.setSoTimeout(1);                                   // drop late replies from the last round
                try { for (int k = 0; k < 500; k++) { in.setLength(buf.length); s.receive(in); handle(buf, in.getLength()); } } catch (SocketTimeoutException e) { }
                boolean d3 = view3d, burst = now < burstUntil;
                List<String> cmds = new ArrayList<String>();
                synchronized (outbox) { cmds.addAll(outbox); outbox.clear(); }
                int expect = 0;
                if (now - lastStatus > 1000) { cmds.add("GET_STATUS"); expect++; if (version.isEmpty()) { cmds.add("VERSION"); expect++; } lastStatus = now; }
                // Most wanted first (the table's order), until the round's bytes run out. A first read or a new area may take more.
                synchronized (table) { for (Block b : table) if (b.got == 0 && (d3 || b.group == STATE || now - b.wanted <= KEEP_MS)) burst = true; }
                int budget = (d3 ? 13000 : 12000) * (burst ? 3 : 1), used = 0;
                now3.clear();
                synchronized (table) {
                    for (Block b : table) {
                        if (b.group != STATE && !d3 && now - b.wanted > KEEP_MS) continue;
                        if (now - b.sent < (d3 ? b.ms3d : b.msLight) || used + b.len > budget) continue;
                        now3.add(b); used += b.len;
                    }
                }
                for (Block b : now3) { b.sent = now; cmds.add("READ_CORE_MEMORY " + Integer.toHexString(b.addr) + " " + b.len); expect++; }
                for (String c : cmds) {
                    byte[] d = (c + "\n").getBytes("US-ASCII");
                    s.send(new DatagramPacket(d, d.length, to, port));
                }
                sent = now3.size();
                int replies = 0;
                long deadline = System.currentTimeMillis() + (linked() ? 120 : 300);
                while (replies < expect) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) break;
                    s.setSoTimeout((int) left);
                    in.setLength(buf.length);
                    try { s.receive(in); } catch (SocketTimeoutException e) { break; }
                    replies++;
                    lastReply = System.currentTimeMillis();
                    handle(buf, in.getLength());
                }
                got = replies;
                rounds++; reads += now3.size(); bytes += used;
                Listener l = listener;
                if (l != null && linked()) l.onRound();
                Thread.sleep(Math.max(3, (d3 ? 33 : 100) - (System.currentTimeMillis() - now)));
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (s != null) s.close();
                s = null;
                try { Thread.sleep(500); } catch (InterruptedException x) { break; }
            }
        }
        if (s != null) s.close();
    }

    private static int hexv(int c) { return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1; }
    private static boolean starts(byte[] b, int n, String t) {
        if (n < t.length()) return false;
        for (int i = 0; i < t.length(); i++) if (b[i] != t.charAt(i)) return false;
        return true;
    }
    private void handle(byte[] b, int n) {
        if (starts(b, n, "READ_CORE_MEMORY ")) {                     // READ_CORE_MEMORY 3001140 0A 1B ...  |  ... -1 no memory map defined
            int p = 17, a = 0, v;
            while (p < n && (v = hexv(b[p])) >= 0) { a = a << 4 | v; p++; }
            Block k = byAddr.get(a);
            if (k == null) return;
            if (p + 1 < n && b[p + 1] == '-') { memErr = new String(b, p + 1, n - p - 1).trim(); return; }
            int i = k.off, end = k.off + k.len, count = 0;
            while (p + 2 < n && i < end && b[p] == ' ') {
                int h = hexv(b[p + 1]), l = hexv(b[p + 2]);
                if (h < 0 || l < 0) break;
                k.to[i++] = (byte) (h << 4 | l); p += 3; count++;
            }
            if (count >= k.len) { k.got = System.currentTimeMillis(); lastMem = k.got; memErr = ""; }
        } else if (starts(b, n, "GET_STATUS ")) {
            // GET_STATUS PLAYING game_boy_advance,Super Mario Advance (USA, Europe),crc32=1e4c6d6a  |  GET_STATUS CONTENTLESS
            String t = new String(b, 11, n - 11).trim();
            int sp = t.indexOf(' ');
            state = sp < 0 ? t : t.substring(0, sp);
            String info = sp < 0 ? "" : t.substring(sp + 1);
            int c1 = info.indexOf(','), ci = info.lastIndexOf(",crc32=");
            system = c1 < 0 ? info : info.substring(0, c1);
            game = c1 < 0 ? "" : info.substring(c1 + 1, ci > c1 ? ci : info.length());
            crc = ci < 0 ? "" : info.substring(ci + 7).trim().toLowerCase();
        } else if (starts(b, n, "VERSION ")) {
            version = new String(b, 8, n - 8).trim();
        } else if (n > 0 && b[0] >= '0' && b[0] <= '9' && version.isEmpty()) {
            version = new String(b, 0, n).trim();                    // older builds answer VERSION with the bare number
        }
    }
}

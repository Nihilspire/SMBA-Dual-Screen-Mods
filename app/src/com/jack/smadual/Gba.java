package com.jack.smadual;

/** GBA constants: libretro joypad bits, Super Mario Advance (USA, Europe) RAM/ROM addresses (see RAM-MAP.md). */
final class Gba {
    private Gba() {}
    /** GBA BGR555 -> ARGB */
    static int color(int c) {
        int r = c & 31, g = (c >> 5) & 31, b = (c >> 10) & 31;
        return 0xFF000000 | ((r << 3 | r >> 2) << 16) | ((g << 3 | g >> 2) << 8) | (b << 3 | b >> 2);
    }

    // RETRO_DEVICE_ID_JOYPAD_* bits
    static final int B = 1, Y = 1 << 1, SELECT = 1 << 2, START = 1 << 3, UP = 1 << 4, DOWN = 1 << 5,
            LEFT = 1 << 6, RIGHT = 1 << 7, A = 1 << 8, X = 1 << 9, L = 1 << 10, R = 1 << 11;
    /** Bindable buttons; 8..16 are app functions (turbo, menu, fast, save and load state slot 1, 3D), 17/18 the shoulders,
     *  19 the 3D camera (added last so saved bindings keep their numbers). */
    static final String[] BUTTON_NAMES = { "UP", "DOWN", "LEFT", "RIGHT", "B", "A", "SELECT", "START", "TURBO B", "TURBO A",
            "MENU", "FAST", "SAVE SLOT 1", "LOAD SLOT 1", "3D VIEW", "3D DEPTH +", "3D DEPTH -", "L", "R", "3D CAMERA" };
    static final int[] BUTTON_BITS = { UP, DOWN, LEFT, RIGHT, B, A, SELECT, START, 0, 0, 0, 0, 0, 0, 0, 0, 0, L, R, 0 };
    static int bit(int btn) { return btn >= 0 && btn < BUTTON_BITS.length ? BUTTON_BITS[btn] : 0; }
    static int special(int btn) { return btn >= 8 && btn <= 16 ? 1 << (btn - 8) : 0; }

    // ---- Super Mario Advance (USA, Europe) RAM. IWRAM 03xxxxxx, EWRAM 02xxxxxx.
    // Game state is three bytes (1992 alone is also 1 at character select):
    static final int MODE = 0x030019B0,           // 2 title / file menus, 3 SMB2, 4 Mario Bros. Classic
            SUB_MODE = 0x03001991,                // mode 3: 0 character select / chart, 1 in a level
            MAIN_STATE = 0x03001992;              // in a level: 1 playing, 0x0C/0x0D pause, 0x0F unpausing;
                                                  // at character select: 1 choosing, 2 leaving, 5/6 chart
    static final int FILE = 0x03001140;           // the file's save block (unpacked from EEPROM)
    static final int SCORE = 0x0300114C;          // u32
    static final int ACE_BITS = 0x03001150;       // 100 bits: level L owns bits L*5..L*5+4, LSB first (pause % = count)
    static final int CLEARED_BITS = 0x0300115D;   // bit L: level L cleared on this file (3 bytes)
    static final int CURRENT_LEVEL = 0x03001160,  // 0..19 = 1-1 .. 7-2
            LIVES = 0x03001161,                    // shown as-is next to the character icon
            COINS = 0x03001A07, CHERRIES = 0x03001A26,
            ACE_LEVEL = 0x03001C35,                // bitmask of the Ace Coins taken this visit (same bit order)
            EGGS = 0x03001C37,
            PLAYER_X = 0x03001C4A, PLAYER_Y = 0x03001C4E,     // u16 world pixels
            CHARACTER = 0x03001C70,               // 0 Mario, 1 Peach, 2 Toad, 3 Luigi
            IN_AIR = 0x03001C71, HOLDING = 0x03001C74,
            HEALTH = 0x03001C76,                  // $0F 1 heart, $1F 2, $2F 3, $3F 4 (as SMB2)
            MUSHROOMS = 0x03001C77,               // mushrooms taken in this level: the meter is 2 + this many hearts
            STAR_TIMER = 0x03001C88, STOPWATCH = 0x03001CAC, SUBSPACE_TIMER = 0x03001CCF;
    static final int AREA = 0x030019EC, PAGES = 0x03001A06,  // 0/1 (see RAM-MAP); pages in the area - 1
            AREA_NO = 0x030019FE,                 // SMB2 area number 0..10 = object list index
            IS_VERTICAL = 0x0300116F;             // 1 in vertical areas
    // 0.17: the level renderer (08029EC2) redraws metatiles 0x26E..0x2C5 differently by level state: when 03001A4B = 1,
    // entry = (quad & 0x1FF) | 0x6000 (palette 6); else when the world's graphics set 030019FC (u16: 0 grass, 1 desert,
    // 3 ice) is 3 and 03001A29 = 0, entry = (quad & 0xFF) | 0x2200 (palette 2, tiles 0x200..0x2FF) — world 4's ice walls.
    // The ROM quads alone matched 4-1 only half way.
    // 0.34: 03001A29 is also how to tell a pot: 1 for a pot on the extra page, 2 for a pot that is its own area, else 0.
    // 03001A2A = 2 is Subspace (drawn mirrored), 1 a pot on the extra page, else 0.
    static final int TILESET = 0x030019FC, TILE_FLAG_A = 0x03001A29, TILE_FLAG_B = 0x03001A4B, EXTRA_KIND = 0x03001A2A;
    static int quadMode(int tileset, int flagA, int flagB) { return flagB == 1 ? 2 : tileset == 3 && flagA == 0 ? 1 : 0; }
    static void retile(int id, int mode, int[] q) {
        if (mode == 0 || id <= 0x26D || id > 0x2C5) return;
        for (int i = 0; i < 4; i++) q[i] = mode == 2 ? (q[i] & 0x1FF) | 0x6000 : (q[i] & 0xFF) | 0x2200;
    }
    static final int LEVEL_DATA = 0x02000000;     // decoded area: u16 metatile ids, 16 per row, world rows
    static final int OBJ_BASE = 0x03001CF0, OBJ_SIZE = 0x70, OBJ_SLOTS = 16;   // +0x30 X, +0x32 Y, +0x65 = 1 live, +0x66 class
    static final int ROM_OBJ_LISTS = 0x364D84;   // u32 x (20 levels * 11 areas): (u16 type, x, y) records, 0x00FF ends
    static final int ACE_COIN_FIRST = 0x6F;      // object types 0x6F..0x73 = Ace Coin 1..5 (save bit type - 0x6F)
    // Dual tracker (native/smatrack.c), index into Native.tracker() as u32
    static final int T_TIME = 0, T_DEATHS = 1, T_CLEARED = 2, T_WHO_LO = 3, T_WHO_HI = 4, T_WARPED = 5, T_FLAGS = 6;
    static final int[] WORLD_START = { 0, 3, 6, 9, 12, 15, 18 };   // world 7 has 2 levels
    static final double FPS = 59.7275;

    // ---- ROM (file offsets)
    static final long SMA_CRC = 0x1E4C6D6AL;     // whole 4 MB file
    static final int ROM_FONT = 0x2F3D7C;        // 4bpp 8x8: 0-9 A-P, x (no Q), R-Z - ? . , ; 15 = ink, 13-14 = shade
    static final String FONT_MAP = "0123456789ABCDEFGHIJKLMNOP*RSTUVWXYZ-?.,";   // * = the font's times sign; Q drawn as O
    static final int FONT_TILES = 40, FONT_BYTES = 48 * 32;
    static final int ROM_METATILE_PTRS = 0x3400E0;   // 3 pointers: metatile id >> 8 -> table of 4 u16 BG map entries per id
    static final String[] LEVEL_NAMES = { "1-1", "1-2", "1-3", "2-1", "2-2", "2-3", "3-1", "3-2", "3-3", "4-1", "4-2", "4-3",
            "5-1", "5-2", "5-3", "6-1", "6-2", "6-3", "7-1", "7-2" };
    static final String[] CHARACTERS = { "MARIO", "PEACH", "TOAD", "LUIGI" };

    static int u16(int a) { return Native.peek(a) | Native.peek(a + 1) << 8; }
    static int maxHearts() { return Math.min(4, 2 + Native.peek(MUSHROOMS)); }
    static boolean inLevel() { return Native.peek(MODE) == 3 && Native.peek(SUB_MODE) == 1; }
    static boolean inGame() { return inLevel() && Native.peek(MAIN_STATE) == 1; }
    /** In a level with the game running: playing, or on the extra page (main state 0x0A: Subspace, or a pot that has no area
     *  of its own). That page is always page 10, the level buffer's last, however many pages the area has. */
    static final int STATE_SUBSPACE = 0x0A, EXTRA_PAGE = 10;
    static boolean inPlay3d() { int s = Native.peek(MAIN_STATE); return inLevel() && (s == 1 || s == STATE_SUBSPACE); }
    static boolean inGameOrPause() { return inLevel(); }
    static boolean charSelect() { return Native.peek(MODE) == 3 && Native.peek(SUB_MODE) == 0 && Native.peek(MAIN_STATE) == 1; }
    /** The 5 Ace Coin bits the file holds for level L. */
    static int aceSaved(int L) {
        int v = 0;
        for (int i = 0; i < 5; i++) { int k = L * 5 + i; v |= ((Native.peek(ACE_BITS + (k >> 3)) >> (k & 7)) & 1) << i; }
        return v;
    }
    /** "3-2" for the current level, or "" */
    static String levelName() {
        int lv = Native.peek(CURRENT_LEVEL);
        if (lv > 19) return "";
        int w = 0; while (w < 6 && lv >= WORLD_START[w + 1]) w++;
        return (w + 1) + "-" + (lv - WORLD_START[w] + 1);
    }
    static int world(int lv) { int w = 0; while (w < 6 && lv >= WORLD_START[w + 1]) w++; return w; }
}

# spots.py WORK : plays to each showcase spot on the desktop driver and keeps a real frame there (dump + picture).
# From the level's start state in WORK/au: force the area, fly along the top so the game really scrolls and spawns what's there, drop to
# the ground at x, take a few steps. Star power while flying so nothing kills the character; off for the last frames.
import json, os, struct, subprocess, sys
W = sys.argv[1]
DRIVE = os.path.expanduser('~/sma3d/out/host/drive')
OUT = W + '/show'; os.makedirs(OUT, exist_ok=True)
#        name              level area  x     character (0 Mario 1 Peach 2 Toad 3 Luigi)  walk   note
SPOTS = [('1-1_hills',         0, 2, 1560, 0, 'R'),
         ('1-1_logs_water',    0, 2,  980, 3, 'R'),
         ('1-3_waterfall',     2, 0,  620, 2, 'R'),
         ('2-1_pyramids',      3, 0,  300, 1, 'R'),
         ('2-1_oasis',         3, 0, 2360, 0, 'L'),
         ('3-1_cliff',         6, 0,  330, 3, 'R'),
         ('4-1_ice',           9, 1,  860, 0, 'R'),
         ('4-2_whales',       10, 2,  320, 2, ''),
         ('4-2_whale_back',   10, 2,  540, 0, 'R'),
         ('4-3_snow',         11, 1,  190, 3, 'R'),
         ('5-3_logs',         14, 1, 1450, 1, ''),
         ('5-3_cave',         14, 2,  480, 3, ''),
         ('6-1_ruins',        15, 0, 1190, 0, ''),
         ('6-3_sky_pyramid',  17, 4,  560, 2, 'R'),
         ('7-1_temple',       18, 2,  230, 3, 'R'),
         ('7-2_machine',      19, 1,  500, 0, 'R')]
HOLD_Y = {'6-1_ruins': 0xA6}
def p16(a, v): return ['poke %08X %02X' % (a, v & 255), 'poke %08X %02X' % (a + 1, (v >> 8) & 255)]
def ground(lv, an, x):
    """the first standing place below the top at column x: the top of a solid cell with air above it. None if the area dump isn't there."""
    f = '%s/au/L%02d_A%d.bin' % (W, lv, an)
    if not os.path.exists(f): return None
    d = open(f, 'rb').read(); iw, ew = d[:0x8000], d[0x8000:0x48000]
    pages = iw[0x1A06] + 1; ids = struct.unpack_from('<2640H', ew, 0)
    p, c = x >> 8, (x >> 4) & 15
    if p >= pages: return None
    col = [ids[p * 240 + r * 16 + c] for r in range(15)]
    for r in range(2, 15):
        if col[r] >> 8 in (1, 2) and col[r - 1] >> 8 == 0: return r * 16
    return 14 * 16
def script(name, lv, an, x, ch, walk):
    L = ['poke 030019FE %X' % an, 'poke 03001992 0', 'run 80', 'poke 03001C70 %02X' % ch, 'run 4']
    d = open('%s/au/L%02d_A%d.bin' % (W, lv, an), 'rb').read(); x0 = struct.unpack_from('<H', d, 0x1C4A)[0]
    g = ground(lv, an, x)
    cx = x0
    while cx < x - 40:                        # along the top, as far as the game will scroll at this speed
        cx += 4
        L += p16(0x03001C4A, cx) + p16(0x03001C60, cx) + p16(0x03001C4E, 8) + p16(0x03001C62, 8) + p16(0x03001168, cx - 120) + p16(0x03001C40, cx - 120) + ['poke 03001C76 3F', 'poke 03001C88 FF', 'poke 03001C6F 00', 'run 1']
    y = (g or 160) - 34
    L += p16(0x03001C4A, cx) + p16(0x03001C60, cx) + p16(0x03001C4E, y) + p16(0x03001C62, y) + ['poke 03001C88 FF', 'poke 03001C6F 00', 'run 50']
    if name in HOLD_Y:                        # soft sand swallows whoever stands in it: hold them on top of it
        for i in range(40): L += p16(0x03001C4E, HOLD_Y[name]) + p16(0x03001C62, HOLD_Y[name]) + ['poke 03001C6F 00', 'run 1']
        L += ['poke 03001C88 00']
        for i in range(6): L += p16(0x03001C4E, HOLD_Y[name]) + p16(0x03001C62, HOLD_Y[name]) + ['run 1']
    else: L += ['run 24 ' + walk, 'poke 03001C88 00', 'poke 03001C76 3F', 'run 8 ' + walk]
    L = [l.strip() for l in L]
    L += ['dump %s/%s.bin' % (OUT, name), 'shot %s/%s.ppm' % (OUT, name), 'save %s/%s.st' % (OUT, name), 'peek 03001160 1', 'peek 030019FE 1', 'peek 03001991 2', 'peek 03001C4A 2', 'peek 03001C4E 2', 'peek 03001C6F 1', 'peek 03001C70 1']
    return L
procs = []
for s in SPOTS:
    name, lv, an = s[:3]
    f = '%s/au/L%02d_A%d.bin' % (W, lv, an)
    if not os.path.exists(f): print('no dump for', name); continue
    fn = '%s/_%s.scr' % (OUT, name)
    open(fn, 'w').write('\n'.join(['load au/L%02d.st' % lv] + script(*s)) + '\n')
    procs.append((name, subprocess.Popen([DRIVE, 'sma.gba', fn], cwd=W, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)))
    if len(procs) % 6 == 0:
        for _, q in procs[-6:]: q.wait()
for name, q in procs:
    out = q.communicate()[0]
    v = [l.split(':')[1].strip() for l in out.splitlines() if ':' in l]
    print('%-18s level %s area %s sub/state %s x %s y %s pstate %s char %s' % (name, *v[:7]) if len(v) >= 7 else (name, out[:80]))
json.dump([s[0] for s in SPOTS], open(OUT + '/names.json', 'w'))

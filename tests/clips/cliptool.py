# cliptool.py - scripted emulator runs for clips: fly (stream-safe teleport), record (dump + shot every N frames)
import subprocess, struct, os

DRIVE = '../sma-dual/out/host/drive'


def run(lines, savedir='.'):
    fn = '_ct%d.txt' % os.getpid()
    open(fn, 'w').write('\n'.join(lines) + '\n')
    r = subprocess.run([DRIVE, 'sma.gba', fn, savedir], capture_output=True, text=True)
    os.remove(fn)
    return r.stdout


def fly_lines(x0, x1, y, step=4, cam_off=100):
    """Move the player from x0 to x1 (either direction) at height y, 4 px a frame, with the camera, so the game
    streams the BG map as it goes (a plain poke leaves it stale)."""
    s, x = [], x0
    d = step if x1 >= x0 else -step
    while (x1 - x) * d > 0:
        x = x + d
        if (x1 - x) * d < 0:
            x = x1
        c = max(0, x - cam_off)
        for a, v in ((0x03001C4A, x), (0x03001C60, x), (0x03001C4E, y), (0x03001C62, y), (0x03001168, c), (0x03001C40, c)):
            s += ['poke %08X %x' % (a, v & 255), 'poke %08X %x' % (a + 1, v >> 8)]
        s += ['run 1']
    return s


def info(path):
    d = open(path, 'rb').read()
    return dict(px=struct.unpack_from('<H', d, 0x1C4A)[0], py=struct.unpack_from('<H', d, 0x1C4E)[0],
                lvl=d[0x1160], area=d[0x19FE], state=(d[0x19B0], d[0x1991], d[0x1992]), lives=d[0x1161],
                hold=d[0x1C74], sub=d[0x1CCF], hp=d[0x1C76], mush=d[0x1C77])


def record(start_state, steps, outdir, every=2, savedir='.'):
    """steps: list of (frames, buttons) or ('poke', addr, val) or ('tap', buttons). Writes outdir/dNNN.bin + sNNN.ppm
    every `every` emulator frames. Returns the frame count."""
    os.makedirs(outdir, exist_ok=True)
    lines = ['load ' + start_state]
    n = 0
    acc = 0
    for st in steps:
        if st[0] == 'poke':
            lines.append('poke %08X %x' % (st[1], st[2]))
            continue
        frames, btn = st
        for _ in range(frames):
            lines.append('run 1 ' + btn if btn else 'run 1')
            acc += 1
            if acc % every == 0:
                lines += ['dump %s/d%03d.bin' % (outdir, n), 'shot %s/s%03d.ppm' % (outdir, n)]
                n += 1
    run(lines, savedir)
    return n

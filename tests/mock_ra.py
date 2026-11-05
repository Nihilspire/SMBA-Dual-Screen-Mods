"""mock_ra.py - answers RetroArch network commands (UDP) from drive dumps, for testing Super Mario Advance Link.

The memory map is mGBA's (src/platform/libretro/libretro.c _setupMaps): IWRAM, EWRAM, IO, palette, VRAM, OAM and the ROM at their
GBA addresses. A read stops at the end of a descriptor and one outside them all is refused, as in RetroArch's command.c.

  python3 tests/mock_ra.py ROM DUMP_OR_DIR [--port 55355] [--fps 60] [--state playing|paused|contentless] [--crc HEX] [--nomap] [--log FILE]
A directory plays its *.bin dumps in name order, one per frame at --fps, looping. Datagrams are handled once a frame, all of them,
as RetroArch does, so the replies to a batch of reads all come from the same frame. As a module: ra_read(...) for page tests.
"""
import glob, os, socket, sys, time

DUMP = [(0x03000000, 0x8000, 0), (0x02000000, 0x40000, 0x8000), (0x04000000, 0x400, 0x48000), (0x05000000, 0x400, 0x48400), (0x06000000, 0x18000, 0x48800), (0x07000000, 0x400, 0x60800)]
GAME = 'Super Mario Advance (USA, Europe)'


def ra_read(dump, rom, a, n):
    """bytes, or None if no descriptor holds the address."""
    for base, size, off in DUMP:
        if base <= a < base + size:
            k = a - base
            return dump[off + k:off + min(k + n, size)]
    if 0x08000000 <= a < 0x08000000 + len(rom):
        k = a - 0x08000000
        return rom[k:k + n]
    return None


def reply(cmd, dump, rom, args):
    p = cmd.split()
    if not p: return None
    if p[0] == 'VERSION': return '1.22.2\n'
    if p[0] == 'GET_STATUS':
        if args['state'] == 'contentless': return 'GET_STATUS CONTENTLESS\n'
        return 'GET_STATUS %s game_boy_advance,%s,crc32=%s\n' % (args['state'].upper(), GAME, args['crc'])
    if p[0] == 'READ_CORE_MEMORY' and len(p) == 3:
        a, n = int(p[1], 16), int(p[2])
        if args['nomap']: return 'READ_CORE_MEMORY %x -1 no memory map defined\n' % a
        d = ra_read(dump, rom, a, n)
        if d is None: return 'READ_CORE_MEMORY %x -1 no descriptor for address\n' % a
        return 'READ_CORE_MEMORY %x %s\n' % (a, ' '.join('%02X' % v for v in d))
    return None


def main():
    a = sys.argv[1:]
    def opt(name, d=None):
        if name in a:
            i = a.index(name); v = a[i + 1]; del a[i:i + 2]; return v
        return d
    nomap = '--nomap' in a
    if nomap: a.remove('--nomap')
    port, fps, state, crc, logf = int(opt('--port', 55355)), float(opt('--fps', 60)), opt('--state', 'playing'), opt('--crc', '1e4c6d6a'), opt('--log')
    rom = open(a[0], 'rb').read()
    files = sorted(glob.glob(a[1] + '/*.bin')) if os.path.isdir(a[1]) else [a[1]]
    frames = [open(f, 'rb').read() for f in files]
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
    s.bind(('0.0.0.0', port)); s.setblocking(False)
    args = dict(state=state, crc=crc, nomap=nomap)
    log = open(logf, 'w') if logf else None
    print('mock RetroArch on', port, 'frames', len(frames), flush=True)
    i = 0; t = time.time(); nreads = nbytes = 0
    while True:
        dump = frames[i % len(frames)]
        while True:
            try: data, who = s.recvfrom(4096)
            except BlockingIOError: break
            for cmd in data.decode('ascii', 'replace').splitlines():
                r = reply(cmd, dump, rom, args)
                if log: log.write('%d %s\n' % (i, cmd)); log.flush()
                if r is not None:
                    s.sendto(r.encode(), who)
                    if cmd.startswith('READ_CORE'): nreads += 1; nbytes += int(cmd.split()[2])
        if state != 'paused': i += 1
        t += 1 / fps
        d = t - time.time()
        if d > 0: time.sleep(d)
        else: t = time.time()
        if i % 600 == 0 and state != 'paused': print('frame', i, 'reads', nreads, 'bytes', nbytes, flush=True)


if __name__ == '__main__':
    main()

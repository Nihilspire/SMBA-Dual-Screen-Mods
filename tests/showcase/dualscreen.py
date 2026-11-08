# dualscreen.py WORK OUT [gifs|stills|all] [name ...] : release pictures in the Thor's layout, the game's screen (1920x1080) over the companion's (1240x1080).
# Top: what the app draws there, from the recorded game: GameView's 3:2 picture and themed borders (BezelTheme itself, through ThemeDump), or Renderer3D's own
# shader (SceneSeq + shotseq) behind the same borders, and the app's on-screen messages. Bottom: the real Link page in headless Chromium, its 100 ms poll driven by
# hand, with Dual's bridge or Link's. Only the captions and key names in the margins are added. What to show: showcase_clips.py. Spots: spots.py with SHOW_DIR=show2.
import base64, json, os, re, shutil, subprocess, sys
from concurrent.futures import ThreadPoolExecutor
import numpy as np
from PIL import Image
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.abspath(HERE + '/../..'); S3 = ROOT + '/tests/scene3d'
sys.path.insert(0, ROOT + '/tests/ra')
WORK, OUT = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
WHAT = sys.argv[3] if len(sys.argv) > 3 else 'all'; ONLY = sys.argv[4:]
DRIVE = os.path.expanduser('~/sma3d/out/host/drive')
ROM = open(WORK + '/sma.gba', 'rb').read(); KIT = open(WORK + '/_kit.bin', 'rb').read()
KIT_LINK = open(WORK + '/ra43/kit_rom.bin', 'rb').read()
SHOW = os.environ.get('SHOW_DIR', 'show2')
TMP = WORK + '/ds'; os.makedirs(TMP, exist_ok=True)
b64 = lambda x: base64.b64encode(x).decode()
FPS, EVERY = 20, 3                     # a frame of the GIF is 3 of the game's
PW, PH = 620, 540                      # the page in css px: at 2x it is the lower screen's 1240x1080
PRESET = {'low': (-10, 18, 1.05), 'tilt': (16, 0, 1), 'diorama': (30, -24, 0.92), 'far': (22, 0, 0.62)}
BG = (18, 17, 16); GOLD = (0xF4, 0xD2, 0x7A); PALE = (0xE8, 0xE0, 0xC8)
GIFW = 960
LINK_GL = (1240, 860)
who = [0, 1, 2, 3, 0, 2, 1, 1, 3, 0, 2, 2, 3, 1, 0, 3, 2, 0, 1, 3]
TRK = dict(time=int(59.7275 * (2 * 3600 + 41 * 60 + 8)), deaths=14, cleared=0xFFFFF, lo=sum(who[i] << (i * 2) for i in range(16)), hi=sum(who[i] << ((i - 16) * 2) for i in range(16, 20)))

# ------------------------------------------------------------------ the game's font (Gba.FONT_MAP, RomGfx)
GBA = open(ROOT + '/app/src/com/jack/smadual/Gba.java').read()
FONT_MAP = re.search(r'FONT_MAP\s*=\s*"([^"]*)"', GBA).group(1); ROM_FONT = 0x2F3D7C
def glyph(ch):
    t = FONT_MAP.find(ch); g = np.zeros((8, 8), np.uint8)
    for y in range(8):
        for x in range(8):
            v = (ROM[ROM_FONT + t * 32 + y * 4 + (x >> 1)] >> ((x & 1) * 4)) & 15
            g[y, x] = 2 if v == 15 else 1 if v else 0
    return g
GLYPHS = {c: glyph(c) for c in FONT_MAP}
def text(a, s, x, y, px, color):       # a: numpy RGB. RomGfx.text: ink in the colour, shade at half
    col, half = np.array(color, np.uint8), np.array(color, np.uint8) // 2
    for i, ch in enumerate(s):
        g = GLYPHS.get('O' if ch == 'Q' else ch)
        if g is None: continue
        bx = x + i * 8 * px
        for gy in range(8):
            for gx in range(8):
                if g[gy, gx]:
                    x0, y0 = int(round(bx + gx * px)), int(round(y + gy * px)); x1, y1 = int(round(bx + (gx + 1) * px)), int(round(y + (gy + 1) * px))
                    a[y0:y1, x0:x1] = col if g[gy, gx] == 2 else half

# ------------------------------------------------------------------ the top screen: GameView
class Theme:
    def __init__(self, line):
        p = line.split()
        self.none = not p or p[0] == 'none'
        c = lambda h: ((int(h, 16) >> 16) & 255, (int(h, 16) >> 8) & 255, int(h, 16) & 255)
        self.accent, self.shade = (c('FF3CBCFC'), c('FF0000A8')) if self.none else (c(p[0]), c(p[1]))    # GameView's, with no theme yet
        self.block = None if self.none else np.array([c(v) for v in p[2:258]], np.uint8).reshape(16, 16, 3)
def layout(W, H):
    px = H / 160; gw = 240 * px; x = (W - gw) / 2
    return px, (int(round(x)), 0, int(round(x + gw)), H)
_cache = {}
def borders(W, H, th):
    k = (W, H, id(th))
    if k in _cache: return _cache[k].copy()
    px, (l, t, r, b) = layout(W, H)
    a = np.zeros((H, W, 3), np.uint8)
    if th.block is not None:
        ox, oy = l % (16 * px), t % (16 * px)
        xs = (np.floor((np.arange(W) - ox) / px).astype(int)) % 16; ys = (np.floor((np.arange(H) - oy) / px).astype(int)) % 16
        a = (th.block[ys][:, xs].astype(np.float32) * (1 - 0x9C / 255)).astype(np.uint8)
    def rect(d, col):
        x0, y0, x1, y1 = int(round(l - d * px)), int(round(t - d * px)), int(round(r + d * px)), int(round(b + d * px))
        a[max(0, y0):max(0, y1), max(0, x0):max(0, x1)] = col
    rect(4, th.shade); rect(3, th.accent); rect(1, (0, 0, 0))
    _cache[k] = a
    return a.copy()
def top_2d(W, H, ppm, th, bezels=True):
    px, (l, t, r, b) = layout(W, H)
    a = borders(W, H, th) if bezels else np.zeros((H, W, 3), np.uint8)
    a[t:b, l:r] = np.array(Image.open(ppm).convert('RGB').resize((r - l, b - t), Image.NEAREST))
    return a
def top_3d(W, H, png, th):
    px, (l, t, r, b) = layout(W, H)
    im = Image.open(png).convert('RGB')
    pic = np.array(im if im.size == (W, H) else im.resize((W, H), Image.LANCZOS))
    a = borders(W, H, th)
    a[t:b, l:r] = pic[t:b, l:r]            # border3d: the borders over the GL view, a hole where the picture is
    return a
def osd(a, s):                         # GameView.drawOsd
    H, W = a.shape[:2]
    spx = max(1, round(H / 180)); tw = (len(s) * 8 + 8) * spx; x = (W - tw) // 2; y = 12 * spx
    a[y:y + 12 * spx, x:x + tw] = (a[y:y + 12 * spx, x:x + tw].astype(np.float32) * (1 - 0xC0 / 255)).astype(np.uint8)
    text(a, s, x + 4 * spx, y + 2 * spx, spx, (0xFC, 0x98, 0x38))
def margins(fr, y0, W, bw, px, caption=(), label='', key=''):
    """left of the lower screen what this shows, right of it the key pressed and the mode on"""
    rx = (W + bw) // 2
    for j, l in enumerate(caption): text(fr, l, 5 * px / 2, y0 + (10 + j * 13) * px, px, GOLD if j == 0 else PALE)
    y = y0 + 10 * px
    if key:
        kw = (len(key) * 8 + 8) * px; kx = rx + (W - rx - kw) // 2
        fr[y:y + 14 * px, kx:kx + kw] = GOLD; text(fr, key, kx + 4 * px, y + 3 * px, px, (0x2A, 0x14, 0x04))
    y += 22 * px
    for j, l in enumerate(label.split('\n') if label else []):
        text(fr, l, rx + (W - rx - len(l) * 8 * px) // 2, y + j * 13 * px, px, GOLD)

# ------------------------------------------------------------------ the page
def dual_init(trk):
    t = b''.join(int(v).to_bytes(4, 'little') for v in ([trk.get(k, 0) for k in ('time', 'deaths', 'cleared', 'lo', 'hi', 'warped')] + [0] * 10))
    return """
function __u(s){ var b=atob(s), a=new Uint8Array(b.length); for(var i=0;i<b.length;i++) a[i]=b.charCodeAt(i); return a; }
window.__iw=new Uint8Array(0x8000); window.__ew=new Uint8Array(0x40000); window.__video=''; window.__romb64='%s'; window.__rom=null; window.__t=__u('%s'); window.__slot1=null;
function __rd(a){ var r=a>>>24; if (r===3) return __iw[a & 0x7FFF]; if (r===2) return __ew[a & 0x3FFFF]; if (r===8) { if(!__rom) __rom=__u(__romb64); return __rom[a & 0x1FFFFFF]; } return 0; }
window.Native = {
  dual:function(){return true;}, version:function(){return '0.44';}, running:function(){return true;},
  mem:function(spec){ var o=''; spec.split(',').forEach(function(p){ var a=parseInt(p.split(':')[0],16), n=+(p.split(':')[1]||1); for(var i=0;i<n;i++){ o+=('0'+__rd(a+i).toString(16)).slice(-2);} }); return o; },
  video:function(){return __video;}, kit:function(){return '%s';}, tracker:function(){ var s=''; for(var i=0;i<64;i++) s+=String.fromCharCode(__t[i]||0); return btoa(s); },
  rom:function(off,len){ if(!__rom) __rom=__u(__romb64); var s=''; for(var i=0;i<len;i++) s+=String.fromCharCode(__rom[off+i]); return btoa(s); },
  slots:function(){ return JSON.stringify([{n:1,time:__slot1?__slot1.time:0},{n:2,time:0},{n:3,time:0},{n:4,time:0}]); }, thumb:function(n){ return n===1&&__slot1?__slot1.url:''; },
  saveSlot:function(){return true;}, loadSlot:function(){return true;}, devUsed:function(){return false;}, overlay:function(){return false;}, menu:function(){}, close:function(){}
};
window.__ivs=[]; var __si=window.setInterval; window.setInterval=function(f,t){ if(t===100){ __ivs.push(f); return 0; } return __si(f,t); };
window.__set=function(iw,ew,vid){ __iw=__u(iw); __ew=__u(ew); __video=vid; }; window.__tick=function(){ __ivs.forEach(function(f){f();}); };
localStorage.setItem('sma.opts', JSON.stringify({xray:true,markers:true,zl:1}));
""" % (b64(ROM), b64(t), b64(KIT))
def link_init(trk, dump0):
    import linkpage
    return linkpage.init_js(ROM, KIT_LINK, dump0, trk=trk, tab3d=True) + """
window.__ivs=[]; var __si=window.setInterval; window.setInterval=function(f,t){ if(t===100){ __ivs.push(f); return 0; } return __si(f,t); };
window.__set=function(iw,ew,vid){ __iw=__u(iw); __ew=__u(ew); __video=vid; }; window.__tick=function(){ __ivs.forEach(function(f){f();}); };
"""
def split(d):
    io, pal, vram, oam = d[0x48000:0x48400], d[0x48400:0x48800], d[0x48800:0x60800], d[0x60800:0x60C00]
    return [b64(d[:0x8000]), b64(d[0x8000:0x48000]), b64(vram + pal + oam + io[:0x60])]
TAB4 = {'status': .125, 'map': .375, 'chart': .625, 'options': .875}
TAB5 = {'status': .123, 'map': .319, 'chart': .498, '3d': .66, 'options': .858}
def open_page(br, init, scale):
    ctx = br.new_context(viewport={'width': PW, 'height': PH}, device_scale_factor=scale)
    pg = ctx.new_page(); pg.msgs = []
    pg.on('pageerror', lambda e: pg.msgs.append(str(e)))
    pg.add_init_script(init); pg.goto('file://' + ROOT + '/app/assets/link/index.html'); pg.wait_for_timeout(200)
    return pg
def do_event(pg, e, tabs):
    k = e[1]
    if k == 'tab': pg.mouse.click(PW * tabs[e[2]], PH - 6)
    elif k == 'tap': pg.mouse.click(PW * e[2], PH * e[3])
    elif k == 'down': pg.mouse.move(PW * e[2], PH * e[3]); pg.mouse.down()
    elif k == 'move': pg.mouse.move(PW * e[2], PH * e[3])
    elif k == 'up': pg.mouse.up()

# ------------------------------------------------------------------ recording and 3D
def state_path(s): return s[1:] if s.startswith('@') else '%s/%s.st' % (SHOW, s)
def record(c, d):
    """(frames, buttons) | ('poke', addr, value) | ('mark', name) saves a state | ('loadmark', name) goes back to it. A dump and a picture every EVERY frames."""
    raw = d + '/raw'; shutil.rmtree(raw, ignore_errors=True); os.makedirs(raw)
    L = ['load ' + state_path(c['state'])]; n = acc = 0
    if not c['state'].startswith('@'): L.append('run 14')    # a spot is saved as the star runs out: past that, off camera
    for st in c['steps']:
        if st[0] == 'poke': L.append('poke %08X %x' % (st[1], st[2])); continue
        if st[0] == 'mark': L.append('save %s/%s.st' % (d, st[1])); continue
        if st[0] == 'loadmark': L.append('load %s/%s.st' % (d, st[1])); continue
        for _ in range(st[0]):
            L.append(('run 1 ' + st[1]).strip()); acc += 1
            if acc % EVERY == 0: L += ['dump %s/d%03d.bin' % (raw, n), 'shot %s/s%03d.ppm' % (raw, n)]; n += 1
    fn = d + '/_script.txt'; open(fn, 'w').write('\n'.join(L) + '\n')
    subprocess.run([DRIVE, 'sma.gba', fn], cwd=WORK, capture_output=True)
    return n
def theme_for(dumps):
    pick = dumps[:: max(1, len(dumps) // 12)]
    r = subprocess.run(['java', '-cp', HERE + '/out', 'com.jack.smadual.ThemeDump', WORK + '/sma.gba'] + pick, capture_output=True, text=True)
    lines = [l for l in r.stdout.splitlines() if l.strip()]
    return Theme(lines[-1] if lines else 'none')
def build3d(o, dumps, cam, env=None, aspect='1920x1080'):
    shutil.rmtree(o, ignore_errors=True); os.makedirs(o)
    e = dict(os.environ, CAM=cam, ASPECT=aspect, **(env or {}))
    r = subprocess.run(['java', '-cp', S3 + '/out', 'com.jack.smadual.SceneSeq', WORK + '/sma.gba', o] + dumps, env=e, capture_output=True, text=True)
    if r.returncode: print(r.stderr[-400:])
    return o
def render_frames(d, dumps, tl, out, size, aspect):
    """The frames that show 3D, each with its camera and the 3D menu's options at that moment. One pass over the clip per camera and options, as one Scene3D sees it."""
    shutil.rmtree(out, ignore_errors=True); os.makedirs(out)
    groups = {}
    for i, t in enumerate(tl):
        if t['on']: groups.setdefault((t['tgt'], json.dumps(t['mode'], sort_keys=True)), []).append(i)
    for g, ((cam, mode), frames) in enumerate(groups.items()):
        env = json.loads(mode)
        o = build3d('%s/s3d_%d' % (d, g), dumps[:max(frames) + 1], cam, env, aspect)
        sel = '%s/sel_%d' % (d, g); shutil.rmtree(sel, ignore_errors=True); os.makedirs(sel)
        for i in frames: os.link('%s/f%03d.s3d' % (o, i), '%s/f%03d.s3d' % (sel, i))
        cf = '%s/cams_%d.json' % (d, g); json.dump([tl[i]['cam'] for i in frames], open(cf, 'w'))
        e = dict(os.environ); e.pop('LIGHT', None)
        if env.get('LIGHT'): e['LIGHT'] = '1'
        subprocess.run(['python3', S3 + '/shotseq.py', sel, out, size, cf], env=e, check=True, capture_output=True)
        shutil.rmtree(o, ignore_errors=True); shutil.rmtree(sel, ignore_errors=True)

# ------------------------------------------------------------------ a clip
def timeline(c, n):
    """per frame: 3D on?, camera target, eased camera, 3D options, messages"""
    ev = sorted(c.get('top', []), key=lambda e: e[0]); start3d = c.get('view3d', False) or c.get('link3d', False)
    on, tgt = start3d, c.get('cam', 'low'); cur = list(PRESET[tgt if start3d else 'tilt'])    # Renderer3D starts from tilt and eases to the camera in use
    msg, until, key, kuntil, mode, label = '', -1, '', -1, {}, ''; run = 3 if on else 0; out = []
    for i in range(n):
        for e in ev:
            if e[0] != i: continue
            if e[1] == '3d': on = e[2]; run = 0; msg, until = ('3D VIEW' if on else '2D VIEW'), i + 30
            if e[1] == 'cam': tgt = e[2]; msg, until = 'CAMERA ' + tgt.upper(), i + 30
            if e[1] == 'osd': msg, until = e[2], i + 30
            if e[1] == 'key': key, kuntil = e[2], i + 10
            if e[1] == 'mode': mode, label = e[2], e[3]
        if on:
            run += 1
            if run >= 3:               # 8 frames of play, as Renderer3D, before 3D draws and the camera moves
                for _ in range(EVERY): cur = [a + (b - a) * 0.12 for a, b in zip(cur, PRESET[tgt])]
        out.append(dict(on=on and run >= 3, tgt=tgt, cam=[round(v, 3) for v in cur], mode=mode, label=label, msg=msg if i < until else '', key=key if i < kuntil else ''))
    return out
def make_clip(br, name, c):
    d = '%s/%s' % (TMP, name); os.makedirs(d, exist_ok=True)
    n = record(c, d)
    dumps = ['%s/raw/d%03d.bin' % (d, i) for i in range(n)]
    th = theme_for(['%s/%s/%s.bin' % (WORK, SHOW, c['theme_from'])] if c.get('theme_from') else dumps)
    tl = timeline(c, n)
    W, H, gap = 960, 540, 14
    link3d = c.get('link3d')
    if link3d: render_frames(d, dumps, tl, d + '/gl', '%dx%d' % (LINK_GL[0] // 2, LINK_GL[1] // 2), '%dx%d' % LINK_GL)
    elif any(t['on'] for t in tl): render_frames(d, dumps, tl, d + '/top3d', '%dx%d' % (W, H), '1920x1080')
    frames = d + '/frames'; shutil.rmtree(frames, ignore_errors=True); os.makedirs(frames)
    init = link_init(TRK, open(dumps[0], 'rb').read()) if link3d else dual_init(TRK)
    pg = open_page(br, init, 1); tabs = TAB5 if link3d else TAB4
    for i in range(n):
        pg.evaluate('(a) => __set(a[0], a[1], a[2])', split(open(dumps[i], 'rb').read()))
        if i == 0:
            for _ in range(20): pg.evaluate('__tick()')
            for e in c.get('events0', []): do_event(pg, [0] + e, tabs); pg.wait_for_timeout(40); pg.evaluate('__tick()')
        elif i % 2 == 0: pg.evaluate('__tick()')
        for e in c.get('events', []):
            if e[0] != i: continue
            if e[1] == 'slot1':        # the slot now holds a state: its picture and time, as Native.slots and Native.thumb give them
                tmp = d + '/_t.png'; Image.open('%s/raw/s%03d.ppm' % (d, i)).convert('RGB').save(tmp)
                pg.evaluate('(u) => { __slot1 = {time: Date.now(), url: u}; }', 'data:image/png;base64,' + b64(open(tmp, 'rb').read())); pg.evaluate('__tick()')
            else: do_event(pg, e, tabs)
        png = d + '/_p.png'; pg.screenshot(path=png)
        bot = np.array(Image.open(png).convert('RGB'))
        t = tl[i]; ppm = '%s/raw/s%03d.ppm' % (d, i)
        if link3d:                     # Link: RetroArch's plain picture on top; the GL view over the page's 3D tab
            top = top_2d(W, H, ppm, th, bezels=False)
            gl = pg.evaluate('window.__gl'); f3 = '%s/gl/%03d.png' % (d, i)
            if gl and os.path.exists(f3):
                x, y, w, h = round(gl[0] * PW), round(gl[1] * PH), round(gl[2] * PW), round(gl[3] * PH)
                bot[y:y + h, x:x + w] = np.array(Image.open(f3).convert('RGB').resize((w, h), Image.LANCZOS))
        else:
            f3 = '%s/top3d/%03d.png' % (d, i)
            top = top_3d(W, H, f3, th) if t['on'] and os.path.exists(f3) else top_2d(W, H, ppm, th)
            if t['msg']: osd(top, t['msg'])
        fr = np.full((H + gap + PH, W, 3), BG, np.uint8)
        fr[:H] = top; x0 = (W - PW) // 2; fr[H + gap:, x0:x0 + PW] = bot
        margins(fr, H + gap, W, PW, 2, c.get('caption', []), t['label'], t['key'])
        Image.fromarray(fr).save('%s/%03d.png' % (frames, i))
    errs = pg.msgs; pg.context.close()
    if errs: print(name, 'page errors', errs[:3])
    os.makedirs(OUT + '/gifs', exist_ok=True)
    out = '%s/gifs/%s.gif' % (OUT, name)
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-framerate', str(FPS), '-i', frames + '/%03d.png', '-filter_complex',
                    'scale=%d:-1:flags=neighbor,split[a][b];[a]palettegen=max_colors=256:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle' % GIFW,
                    '-loop', '0', out], check=True)
    hp = [open(f, 'rb').read(0x1C77)[0x1C76] for f in dumps]     # did anyone get hurt on camera?
    hurt = next((i for i, v in enumerate(hp) if v < hp[0] or v >= 0xF0), None)
    print('%-34s %3d frames, %.1f MB, 3D frames %d, %s, hurt %s' % (name, n, os.path.getsize(out) / 1e6, sum(t['on'] for t in tl), 'no theme' if th.none else 'level theme', 'no' if hurt is None else 'at frame %d' % hurt), flush=True)

# ------------------------------------------------------------------ stills, full size
def still(br, name, s):
    W, H, gap = 1920, 1080, 28
    d = '%s/_still_%s' % (TMP, name); shutil.rmtree(d, ignore_errors=True); os.makedirs(d)
    dump, ppm = d + '/d.bin', d + '/s.ppm'
    fn = d + '/_s.txt'                 # a moment after the spot, once the star's sparkle and the score pop-ups from flying in have gone
    open(fn, 'w').write('load %s/%s.st\nrun %d\ndump %s\nshot %s\n' % (SHOW, s['spot'], s.get('settle', 45), dump, ppm))
    subprocess.run([DRIVE, 'sma.gba', fn], cwd=WORK, capture_output=True)
    th = theme_for([dump, '%s/%s/%s.bin' % (WORK, SHOW, s['spot'])])
    if s.get('view3d'):
        env = s.get('env') or {}
        o = build3d(d + '/o', [dump], s['cam'], env)
        e = dict(os.environ, SIZE='%dx%d' % (W, H))
        subprocess.run(['python3', S3 + '/shot.py', d + '/top.png', o + '/f000.s3d', s['cam']] + (['light'] if env.get('LIGHT') else []), env=e, capture_output=True, cwd=WORK)
        top = top_3d(W, H, d + '/top.png', th)
    else: top = top_2d(W, H, ppm, th)
    pg = open_page(br, dual_init(TRK), 2)
    pg.evaluate('(a) => __set(a[0], a[1], a[2])', split(open(dump, 'rb').read()))
    for _ in range(20): pg.evaluate('__tick()')
    if s.get('tab', 'map') != 'map': do_event(pg, [0, 'tab', s['tab']], TAB4); pg.evaluate('__tick()')
    for i in range(s.get('zoom', 0)): do_event(pg, [0, 'tap', .5, .4], TAB4); pg.evaluate('__tick()')
    png = d + '/p.png'; pg.wait_for_timeout(60); pg.screenshot(path=png); errs = pg.msgs; pg.context.close()
    bot = np.array(Image.open(png).convert('RGB'))
    fr = np.full((H + gap + bot.shape[0], W, 3), BG, np.uint8); fr[:H] = top; x0 = (W - bot.shape[1]) // 2; fr[H + gap:, x0:x0 + bot.shape[1]] = bot
    margins(fr, H + gap, W, bot.shape[1], 4, s.get('caption', []))
    os.makedirs(OUT + '/stills', exist_ok=True)
    Image.fromarray(fr).save('%s/stills/%s.png' % (OUT, name))
    shutil.rmtree(d, ignore_errors=True)
    print(name, 'errors' if errs else 'ok', errs[:2], flush=True)

def contact(folder, out, cols=4, cw=480):
    fs = sorted(f for f in os.listdir(folder) if f.endswith('.png') and not f.startswith('_'))
    ims = [Image.open(folder + '/' + f).convert('RGB') for f in fs]
    if not ims: return
    ch = round(ims[0].height * cw / ims[0].width); rows = (len(ims) + cols - 1) // cols
    g = Image.new('RGB', (cols * (cw + 8) + 8, rows * (ch + 8) + 8), (40, 38, 36))
    for i, m in enumerate(ims): g.paste(m.resize((cw, ch), Image.LANCZOS), (8 + (i % cols) * (cw + 8), 8 + (i // cols) * (ch + 8)))
    g.save(out)

if __name__ == '__main__':
    import showcase_clips
    def work(job):
        kind, name, c = job
        try:
            with sync_playwright() as p:
                br = p.chromium.launch()
                (make_clip if kind == 'clip' else still)(br, name, c)
                br.close()
        except Exception as ex: print(name, 'FAILED', repr(ex)[:300], flush=True)
    jobs = []
    if WHAT in ('all', 'gifs'): jobs += [('clip', k, v) for k, v in showcase_clips.CLIPS.items() if not ONLY or k in ONLY]
    if WHAT in ('all', 'stills'): jobs += [('still', k, v) for k, v in showcase_clips.STILLS.items() if not ONLY or k in ONLY]
    with ThreadPoolExecutor(int(os.environ.get('JOBS', '5'))) as ex: list(ex.map(work, jobs))
    if WHAT in ('all', 'stills') and os.path.isdir(OUT + '/stills'): contact(OUT + '/stills', OUT + '/stills/_all_stills.png')

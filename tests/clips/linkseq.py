# linkseq.py - renders the Link page over a recorded sequence of drive dumps, frame by frame, with the page's 100 ms poll
# driven by hand (every `poll_every` frames) so the timing is the app's: 30 fps clip, 10 polls a second.
# python3 linkseq.py rom.gba dumpdir outdir WxH [events.json] [opts-json]
# events: [[frame, "tap"|"down"|"move"|"up", x, y], ...] in page pixels; applied before that frame's screenshot.
import base64, json, os, sys
from playwright.sync_api import sync_playwright
ROOT = '/home/claude/sma/sma-dual'
rom, dd, od, size = sys.argv[1:5]
events = json.load(open(sys.argv[5])) if len(sys.argv) > 5 and sys.argv[5] != '-' else []
opts = sys.argv[6] if len(sys.argv) > 6 else '{"xray":true,"markers":true,"zl":1}'
poll_every = 3
vw, vh = (int(v) for v in size.split('x'))
b64 = lambda x: base64.b64encode(x).decode()
kit = b64(open('_kit.bin', 'rb').read())
init = """
function __u(s){ var b=atob(s), a=new Uint8Array(b.length); for(var i=0;i<b.length;i++) a[i]=b.charCodeAt(i); return a; }
window.__iw=new Uint8Array(0x8000); window.__ew=new Uint8Array(0x40000); window.__video=''; window.__romb64='%s'; window.__rom=null;
window.__trk = new Uint8Array(64);
function __rd(a){ var r=a>>>24;
  if (r===3) return __iw[a & 0x7FFF]; if (r===2) return __ew[a & 0x3FFFF];
  if (r===8) { if(!__rom) __rom=__u(__romb64); return __rom[a & 0x1FFFFFF]; } return 0; }
window.Native = {
  dual: function(){return true;}, version: function(){return '0.17';}, running: function(){return true;},
  mem: function(spec){ var o=''; spec.split(',').forEach(function(p){ var a=parseInt(p.split(':')[0],16), n=+(p.split(':')[1]||1);
     for(var i=0;i<n;i++){ o+=('0'+__rd(a+i).toString(16)).slice(-2);} }); return o; },
  video: function(){ return __video; },
  tracker: function(){ var s=''; for(var i=0;i<64;i++) s+=String.fromCharCode(__trk[i]); return btoa(s); },
  rom: function(off,len){ if(!__rom) __rom=__u(__romb64); var s=''; for(var i=0;i<len;i++) s+=String.fromCharCode(__rom[off+i]); return btoa(s); },
  slots: function(){ return JSON.stringify([{n:1,time:Date.now()},{n:2,time:0},{n:3,time:0},{n:4,time:0}]); },
  thumb: function(){ return ''; }, devUsed: function(){ return false; }, kit: function(){ return '%s'; },
  overlay: function(){return false;}, menu: function(){}, close: function(){}, saveSlot: function(){return true;}, loadSlot: function(){return true;}
};
window.__ivs = []; var __si = window.setInterval;
window.setInterval = function (f, t) { if (t === 100) { __ivs.push(f); return 0; } return __si(f, t); };
window.__set = function (iw, ew, vid) { __iw = __u(iw); __ew = __u(ew); __video = vid; };
window.__tick = function () { __ivs.forEach(function (f) { f(); }); };
localStorage.setItem('sma.opts', JSON.stringify(%s));
""" % (b64(open(rom, 'rb').read()), kit, opts)
files = sorted(f for f in os.listdir(dd) if f.startswith('d') and f.endswith('.bin'))
os.makedirs(od, exist_ok=True)
with sync_playwright() as p:
    br = p.chromium.launch()
    pg = br.new_page(viewport={'width': vw, 'height': vh}, device_scale_factor=float(os.environ.get('SCALE', '1')))
    pg.add_init_script(init)
    pg.goto('file://' + ROOT + '/app/assets/link/index.html')
    pg.wait_for_timeout(300)
    for i, f in enumerate(files):
        d = open(os.path.join(dd, f), 'rb').read()
        io, pal, vram, oam = d[0x48000:0x48400], d[0x48400:0x48800], d[0x48800:0x60800], d[0x60800:0x60C00]
        pg.evaluate('(a) => __set(a[0], a[1], a[2])', [b64(d[:0x8000]), b64(d[0x8000:0x48000]), b64(vram + pal + oam + io[:0x60])])
        if i == 0:
            for _ in range(16): pg.evaluate('__tick()')      # settle: ROM tables, level data, video, icons
        elif i % poll_every == 0: pg.evaluate('__tick()')
        for e in events:
            if e[0] != i: continue
            if e[1] == 'tap': pg.mouse.click(e[2], e[3])
            elif e[1] == 'down': pg.mouse.move(e[2], e[3]); pg.mouse.down()
            elif e[1] == 'move': pg.mouse.move(e[2], e[3])
            elif e[1] == 'up': pg.mouse.move(e[2], e[3]); pg.mouse.up()
        pg.screenshot(path=os.path.join(od, '%03d.png' % i))
    br.close()

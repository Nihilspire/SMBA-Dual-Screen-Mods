# Renders the Link page in headless Chromium with a mock Native fed from a drive "dump"
# (IWRAM 0x8000 | EWRAM 0x40000 | IO 0x400 | PAL 0x400 | VRAM 0x18000 | OAM 0x400).
# python3 tests/linktest.py rom.gba dump.bin outprefix [js-seed]   (seed runs after the mock, e.g. to poke memory)
import base64, json, sys, os
from playwright.sync_api import sync_playwright
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
rom = open(sys.argv[1], 'rb').read()
d = open(sys.argv[2], 'rb').read()
out = sys.argv[3]
seed = sys.argv[4] if len(sys.argv) > 4 else ''
if seed.startswith('@'): seed = open(seed[1:]).read()   # @file: seed from a file (long seeds)
import os as _os
if _os.environ.get('KIT'): seed += "\nNative.kit=function(){return '%s';};" % base64.b64encode(open(_os.environ['KIT'],'rb').read()).decode()
b64 = lambda x: base64.b64encode(x).decode()
iw, ew = d[:0x8000], d[0x8000:0x48000]
io, pal, vram, oam = d[0x48000:0x48400], d[0x48400:0x48800], d[0x48800:0x60800], d[0x60800:0x60C00]
video = vram + pal + oam + io[:0x60]
init = """
function __u(s){ var b=atob(s), a=new Uint8Array(b.length); for(var i=0;i<b.length;i++) a[i]=b.charCodeAt(i); return a; }
window.__iw=__u('%s'); window.__ew=__u('%s'); window.__video='%s'; window.__romb64='%s'; window.__rom=null;
window.__trk = new Uint8Array(64);
function __rd(a){ var r=a>>>24;
  if (r===3) return __iw[a & 0x7FFF]; if (r===2) return __ew[a & 0x3FFFF];
  if (r===8) { if(!__rom) __rom=__u(__romb64); return __rom[a & 0x1FFFFFF]; } return 0; }
window.Native = {
  dual: function(){return true;}, version: function(){return '0.1';}, running: function(){return true;},
  mem: function(spec){ var o=''; spec.split(',').forEach(function(p){ var a=parseInt(p.split(':')[0],16), n=+(p.split(':')[1]||1);
     for(var i=0;i<n;i++){ o+=('0'+__rd(a+i).toString(16)).slice(-2);} }); return o; },
  video: function(){ return __video; },
  tracker: function(){ var s=''; for(var i=0;i<64;i++) s+=String.fromCharCode(__trk[i]); return btoa(s); },
  rom: function(off,len){ if(!__rom) __rom=__u(__romb64); var s=''; for(var i=0;i<len;i++) s+=String.fromCharCode(__rom[off+i]); return btoa(s); },
  slots: function(){ return JSON.stringify([{n:1,time:Date.now()},{n:2,time:0},{n:3,time:0},{n:4,time:0}]); },
  thumb: function(){ return ''; }, devUsed: function(){ return false; },
  overlay: function(){return false;}, menu: function(){}, close: function(){}, saveSlot: function(){return true;}, loadSlot: function(){return true;}
};
%s
""" % (b64(iw), b64(ew), b64(video), b64(rom), seed)
with sync_playwright() as p:
    b = p.chromium.launch()
    for name, w, h in (('portrait', 675, 1000), ('landscape', 1100, 675), ('small', 900, 560)):
        pg = b.new_page(viewport={'width': w, 'height': h})
        pg.add_init_script(init)
        msgs = []; pg.on('console', lambda m: msgs.append(m.text)); pg.on('pageerror', lambda e: msgs.append('ERR ' + str(e)))
        pg.goto('file://' + ROOT + '/app/assets/link/index.html')
        pg.wait_for_timeout(2500)
        pg.screenshot(path=f'{out}_{name}.png')
        if name == 'portrait':
            pg.mouse.click(w * 0.5, h * 0.55); pg.wait_for_timeout(400); pg.screenshot(path=f'{out}_whole.png')
            pg.mouse.click(w * 0.5, h - 8); pg.wait_for_timeout(400); pg.screenshot(path=f'{out}_chart.png')
            pg.mouse.click(w * 0.83, h - 8); pg.wait_for_timeout(400); pg.screenshot(path=f'{out}_opt.png')
            pg.mouse.move(w * 0.5, h * 0.85); pg.mouse.down(); pg.mouse.move(w * 0.5, h * 0.5, steps=8); pg.mouse.move(w * 0.5, h * 0.15, steps=8); pg.mouse.up()
            pg.wait_for_timeout(300); pg.screenshot(path=f'{out}_opt2.png')
        print(name, msgs[:6])
    b.close()

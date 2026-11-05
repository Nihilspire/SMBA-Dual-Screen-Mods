# linkpage.py : Link's page in headless Chromium with a stand-in for LinkActivity's bridge, fed from a drive dump or a mirror of a real
# RetroArch (RaCheck writes one: same layout). python3 tests/ra/linkpage.py rom.gba kit.bin dump.bin outdir [WxH]
# Shots: each setup problem, then the tabs with the 3D tab off and on. Also usable as a module: page(browser, ...), shot3d rect in window.__gl.
import base64, json, os, sys
from playwright.sync_api import sync_playwright
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
b64 = lambda x: base64.b64encode(x).decode()

def init_js(rom, kit, dump, problem='', trk=None, tab3d=True, settings=None, stored=None):      # stored: JS that sets up sma.opts, for other installs
    iw, ew = dump[:0x8000], dump[0x8000:0x48000]
    io, pal, vram, oam = dump[0x48000:0x48400], dump[0x48400:0x48800], dump[0x48800:0x60800], dump[0x60800:0x60C00]
    video = vram + pal + oam + io[:0x60]
    trk = trk or {}
    t = [0] * 16
    t[0], t[1], t[2], t[3], t[4], t[5] = trk.get('time', 0), trk.get('deaths', 0), trk.get('cleared', 0), trk.get('lo', 0), trk.get('hi', 0), trk.get('warped', 0)
    trkb = b''.join(int(v).to_bytes(4, 'little') for v in t)
    s3 = dict(cam3d='low', depth3d=20, back3d='near', cave3d=True, light3d=False, thick3d=True, round3d=False); s3.update(settings or {})
    if stored is None:
        stored = "try { localStorage.setItem('sma.opts', JSON.stringify({xray:true,markers:true,zl:1,tab3d:%s,tabOff02:true})); } catch (e) {}" % ('true' if tab3d else 'false')
    return """
function __u(s){ var b=atob(s), a=new Uint8Array(b.length); for(var i=0;i<b.length;i++) a[i]=b.charCodeAt(i); return a; }
window.__iw=__u('%s'); window.__ew=__u('%s'); window.__video='%s'; window.__romb64='%s'; window.__rom=null; window.__problem=%s;
window.__gl=null; window.__s3=%s; window.__sent=[];
%s
function __rd(a){ var r=a>>>24;
  if (r===3) return __iw[a & 0x7FFF]; if (r===2) return __ew[a & 0x3FFFF]; return 0; }
window.Native = {
  dual: function(){return true;}, link: function(){return true;}, version: function(){return '0.1';},
  problem: function(){return __problem;}, raVersion: function(){return __problem==='noreply'?'':'1.22.2';},
  running: function(){return __problem==='';},
  mem: function(spec){ var o=''; spec.split(',').forEach(function(p){ var a=parseInt(p.split(':')[0],16), n=+(p.split(':')[1]||1);
     for(var i=0;i<n;i++){ o+=('0'+__rd(a+i).toString(16)).slice(-2);} }); return o; },
  video: function(){ return __video; }, kit: function(){ return __problem==='norom'||__problem==='badrom' ? '' : '%s'; },
  rom: function(off,len){ if(__problem==='norom') return ''; if(!__rom) __rom=__u(__romb64); var s=''; for(var i=0;i<len;i++) s+=String.fromCharCode(__rom[off+i]); return btoa(s); },
  romName: function(){return __problem==='norom'?'':'sma.gba';}, pickRom: function(){__sent.push('pickRom');}, tracker: function(){ return '%s'; }, resetStats: function(){__sent.push('resetStats');},
  overlay: function(){return false;}, close: function(){}, devUsed: function(){return false;},
  slots: function(){ return JSON.stringify([1,2,3,4].map(function(n){return {n:n,time:1};})); },
  saveSlot: function(n){__sent.push('save '+n); return true;}, loadSlot: function(n){__sent.push('load '+n); return true;}, thumb: function(){return '';},
  menu: function(){__sent.push('MENU_TOGGLE');}, settings3d: function(){ return JSON.stringify(__s3); }, set3d: function(k,v){ __s3[k]= v==='true'?true:v==='false'?false:/^[0-9]+$/.test(v)?+v:v; __sent.push(k+'='+v); },
  view3d: function(on,x,y,w,h){ window.__gl = on ? [x,y,w,h] : null; }
};
""" % (b64(iw), b64(ew), b64(video), b64(rom), json.dumps(problem), json.dumps(s3), stored, b64(kit), b64(trkb))

def page(br, init, w=620, h=540, scale=2):
    ctx = br.new_context(viewport={'width': w, 'height': h}, device_scale_factor=scale)
    pg = ctx.new_page(); pg.msgs = []
    pg.on('pageerror', lambda e: pg.msgs.append('ERR ' + str(e)))
    pg.add_init_script(init)
    pg.goto('file://' + ROOT + '/app/assets/link/index.html')
    pg.wait_for_timeout(900)
    return pg

def tab_x(pg, name):      # the middle of a tab, from the page's own buttons is private: worked out as drawTabs does
    return pg.evaluate("""(name) => { var W = document.getElementById('c').width, H = document.getElementById('c').height;
       return [W, H]; }""", name)

if __name__ == '__main__':
    rom, kit, dump = [open(p, 'rb').read() for p in sys.argv[1:4]]
    out = sys.argv[4]; os.makedirs(out, exist_ok=True)
    w, h = [int(v) for v in (sys.argv[5] if len(sys.argv) > 5 else '620x540').split('x')]
    trk = dict(time=59.7275 * 3725, deaths=7, cleared=0x7, lo=0b100100, hi=0)
    with sync_playwright() as p:
        br = p.chromium.launch()
        bad = []
        for prob in ('norom', 'badrom', 'noreply', 'nogame', 'wrong', 'nomap', ''):
            pg = page(br, init_js(rom, kit, dump, problem=prob, trk=trk, tab3d=False), w, h)
            pg.screenshot(path='%s/state_%s.png' % (out, prob or 'playing'))
            bad += pg.msgs; pg.context.close()
        # the tabs: 3D tab on, on this screen shape
        pg = page(br, init_js(rom, kit, dump, trk=trk), w, h)
        n = pg.evaluate("document.getElementById('c').width"); print('canvas', n, pg.evaluate("document.getElementById('c').height"))
        print('gl at start', pg.evaluate('window.__gl'))
        pg.screenshot(path=out + '/tab_first.png')
        # tap across the tab strip and record which tab comes up from the picture
        for i, x in enumerate((0.1, 0.33, 0.55, 0.7, 0.9)):
            pg.mouse.click(w * x, h - 6); pg.wait_for_timeout(250)
            pg.screenshot(path='%s/tab_%d.png' % (out, i)); print('tap', x, 'gl', pg.evaluate('window.__gl'))
        bad += pg.msgs; print('errors', bad[:5]); br.close()

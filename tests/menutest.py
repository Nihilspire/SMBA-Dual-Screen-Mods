# Renders the launcher / pause menu in headless Chromium with a stub Host (font from the player's ROM).
# python3 tests/menutest.py rom.gba thumb.png outdir [kit.bin|-] [standin]
#   kit.bin: a first-start capture (drive 'extract'); standin: previews without game art (no ROM font, stand-in pieces)
import base64, sys, os
from playwright.sync_api import sync_playwright
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
rom = open(sys.argv[1], 'rb').read()
font = base64.b64encode(rom[0x2F3D7C:0x2F3D7C + 48 * 32]).decode()
thumb = 'data:image/png;base64,' + base64.b64encode(open(sys.argv[2], 'rb').read()).decode()
out = sys.argv[3]
kit = base64.b64encode(open(sys.argv[4], 'rb').read()).decode() if len(sys.argv) > 4 and sys.argv[4] != '-' else ''
standin = len(sys.argv) > 5 and sys.argv[5] == 'standin'
def init(mode, dev):
    return """
%s
var S={aspect:'3:2',bezels:true,integer:false,colorfix:false,blend:false,touch:'auto',padOpacity:70,split:'auto',swap:false,side:false,useExternal:true,skipLauncher:false,audio:true,devUnlock:%s};
window.Host={ info:function(){return JSON.stringify({version:'0.1',mode:'%s',rom:{present:true,ok:true,crc:'1e4c6d6a'},auto:true,settings:S,kit:'%s',
  screens:{how:'hinge',model:'Surface Duo',size:'1350x1800'},linkShown:true,slots:[{n:1,time:Date.now()-3600e3},{n:2,time:0},{n:3,time:Date.now()},{n:4,time:0}]});},
  font:function(){return '%s';}, kit:function(){return '%s';}, rebuildKit:function(){}, thumb:function(n){return n==2?'':'%s';}, set:function(k,v){S[k]=v==='true'?true:v==='false'?false:v;},
  binds:function(){return JSON.stringify([{name:'UP',keys:['DPAD UP']},{name:'B',keys:['A','Z']},{name:'A',keys:['B','X']},{name:'L',keys:['L1']}]);},
  reset:function(){}, quit:function(){}, rebind:function(){}, resume:function(){}, start:function(){}, save:function(){return true;}, load:function(){return true;}, dev:function(w){return 'DONE';} };
""" % ('window.STANDIN=true;' if standin else '', 'true' if dev else 'false', mode, 'ready' if kit else 'none', font, kit, thumb)
with sync_playwright() as p:
    b = p.chromium.launch()
    for mode, w, h, name in (('launcher', 900, 600, 'launcher'), ('pause', 675, 900, 'pause'), ('launcher', 1280, 720, 'launcher_wide')):
        pg = b.new_page(viewport={'width': w, 'height': h}); errs = []
        pg.on('pageerror', lambda e: errs.append(str(e)))
        pg.add_init_script(init(mode, name == 'pause'))
        pg.goto('file://' + ROOT + '/app/assets/menu/index.html'); pg.wait_for_timeout(600)
        pg.screenshot(path=f'{out}/menu_{name}.png')
        if mode == 'pause':
            for k in ['down', 'down', 'ok']: pg.evaluate(f"pad('{k}')")
            pg.wait_for_timeout(400); pg.screenshot(path=f'{out}/menu_load.png')
            pg.evaluate("show('pause')")
            for k in ['down', 'down', 'down', 'ok']: pg.evaluate(f"pad('{k}')")
            pg.wait_for_timeout(300); pg.screenshot(path=f'{out}/menu_settings.png')
            for k in ['down', 'down', 'down', 'down', 'down', 'ok', 'down', 'ok']: pg.evaluate(f"pad('{k}')")
            pg.wait_for_timeout(300); pg.screenshot(path=f'{out}/menu_dev.png')
            pg.evaluate("show('pause')")
            for k in ['down', 'down', 'down', 'ok', 'ok']: pg.evaluate(f"pad('{k}')")
            pg.wait_for_timeout(300); pg.screenshot(path=f'{out}/menu_display.png')
        print(name, errs)
    b.close()

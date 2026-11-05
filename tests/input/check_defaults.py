# check_defaults.py rom kit dump binds.json outdir : the settings a new install starts with, the Konami lock on the playtest codes, Link's 3D tab.
# The defaults are read out of the Java source (every read of each preference); the menus and Link's page run in headless Chromium.
import base64, json, os, re, sys
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = HERE + '/../..'
sys.path.insert(0, HERE + '/../ra')
import linkpage
rom, kit, dump = [open(p, 'rb').read() for p in sys.argv[1:4]]
binds = open(sys.argv[4]).read(); OUT = sys.argv[5]; os.makedirs(OUT, exist_ok=True)
bad = []; checks = 0
def want(what, got, wanted):
    global checks; checks += 1
    if got != wanted: bad.append('%s: %r, wanted %r' % (what, got, wanted)); print('WRONG', bad[-1])

# ---- every read of each setting in the source
scene = open(ROOT + '/app/src/com/jack/smadual/Scene3D.java').read()
const = {'Scene3D.DEF_CAM': re.search(r'DEF_CAM = "(\w+)"', scene).group(1), 'Scene3D.DEF_DEPTH': re.search(r'DEF_DEPTH = (\d+)', scene).group(1)}
ASKED = {'cam3d': 'low', 'depth3d': '20', 'back3d': 'near', 'cave3d': 'true', 'thick3d': 'true', 'border3d': 'true', 'light3d': 'false', 'round3d': 'false',
         'dev3d': 'false', 'devUnlock': 'false', 'codeHealth': 'false', 'codeLives': 'false'}
for f, needs in (('app/src/com/jack/smadual/MainActivity.java', ASKED), ('linkapp/src/com/jack/smadual/LinkActivity.java', {k: v for k, v in ASKED.items() if k not in ('dev3d', 'border3d', 'devUnlock', 'codeHealth', 'codeLives')})):
    src = open(ROOT + '/' + f).read(); seen = {}
    for k, d in re.findall(r'prefs\.get(?:Int|String|Boolean)\("(\w+)",\s*([^)]+)\)', src):
        if k in ASKED:
            v = const.get(d, d).strip('"'); seen[k] = seen.get(k, 0) + 1
            want('%s %s' % (os.path.basename(f), k), v, ASKED[k])
    want(os.path.basename(f) + ' reads all of them', sorted(seen), sorted(needs))
    print(os.path.basename(f), 'reads:', ' '.join('%s x%d' % kv for kv in sorted(seen.items())))
    want(os.path.basename(f) + ' no old default', re.findall(r'return "tilt"|, 16\)', src), [])
act = open(ROOT + '/app/src/com/jack/smadual/MainActivity.java').read()
want('codes need the unlock', len(re.findall(r'unlocked && prefs\.getBoolean\("code(?:Health|Lives)"', act)), 2)
want('dev actions need the unlock', 'prefs.getBoolean("devUnlock", false) ? devAction(what) : "LOCKED"' in act, True)
want('one way to devAction', len(re.findall(r'devAction\(', act)), 2)

# ---- the menus, with the settings MainActivity's info() gives a new install
S0 = dict(aspect='3:2', bezels=True, integer=False, colorfix=False, blend=False, touch='auto', padOpacity=70, split='auto', gap=0, swap=False, side=False, useExternal=True,
          skipLauncher=False, audio=True, overclock=True, music=False, romMenu=True, stats=False, dev3d=False, cam3d='low', depth3d=20, back3d='near', cave3d=True, light3d=False,
          thick3d=True, round3d=False, border3d=True, devUnlock=False, codeHealth=False, codeLives=False)
font = base64.b64encode(rom[0x2F3D7C:0x2F3D7C + 48 * 32]).decode()
def host(mode, kitb):
    return """
var S=window.S=%s; window.__dev=[]; window.__mode='%s';
window.Host={ info:function(){return JSON.stringify({version:'0.44',mode:window.__mode,rom:{present:true,ok:true,crc:'1e4c6d6a'},auto:true,settings:S,kit:'ready',screens:{how:'hinge',model:'AYN Thor',size:'1920x1080'},linkShown:true,slots:[{n:1,time:0},{n:2,time:0},{n:3,time:0},{n:4,time:0}]});},
  font:function(){return '%s';}, kit:function(){return '%s';}, rebuildKit:function(){}, thumb:function(){return '';}, set:function(k,v){S[k]=v==='true'?true:v==='false'?false:/^[0-9]+$/.test(v)?+v:v;},
  binds:function(){return %s;}, reset:function(){}, quit:function(){}, rebind:function(){}, resume:function(){}, start:function(){}, save:function(){return true;}, load:function(){return true;}, pickRom:function(){}, exit:function(){},
  dev:function(w){ window.__dev.push(w); return S.devUnlock ? 'DONE' : 'LOCKED'; } };
""" % (json.dumps(S0), mode, font, base64.b64encode(kitb).decode(), json.dumps(binds))
KONAMI = ['up', 'up', 'down', 'down', 'left', 'right', 'left', 'right', 'back', 'ok']
def pads(pg, ks):
    for k in ks: pg.evaluate("pad('%s')" % k)
    pg.wait_for_timeout(120)
def show(pg, mode): pg.evaluate("__mode='%s'; show('%s')" % (mode, mode))
def settings(pg, idx, shot):       # launcher: CONTINUE, NEW GAME, LOAD STATE, SETTINGS; settings: DISPLAY SCREENS 3D CONTROLS SOUND GAMEPLAY SYSTEM
    show(pg, 'launcher'); pads(pg, ['down'] * 3 + ['ok'] + ['down'] * idx + ['ok']); pg.screenshot(path=OUT + '/' + shot)
state = lambda pg, k: pg.evaluate('S.' + k)
with sync_playwright() as p:
    br = p.chromium.launch()
    errs = []
    pg = br.new_page(viewport={'width': 1240, 'height': 1080}); pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.add_init_script(host('launcher', kit)); pg.goto('file://' + os.path.abspath(ROOT) + '/app/assets/menu/index.html'); pg.wait_for_timeout(500)
    settings(pg, 2, 'menu_3d_new_install.png')
    settings(pg, 3, 'menu_controls.png'); pads(pg, ['down', 'down', 'ok']); pads(pg, ['down'] * 13); pg.screenshot(path=OUT + '/menu_buttons.png')
    pads(pg, ['down'] * 6); pg.screenshot(path=OUT + '/menu_buttons2.png')
    settings(pg, 5, 'menu_gameplay_locked.png')
    want('new install locked', state(pg, 'devUnlock'), False)
    # near misses don't unlock
    show(pg, 'launcher'); pads(pg, KONAMI[:-1] + ['back']); want('wrong last press', state(pg, 'devUnlock'), False)
    show(pg, 'launcher'); pads(pg, ['up', 'down'] + KONAMI[2:-1]); want('wrong start', state(pg, 'devUnlock'), False)
    show(pg, 'pause'); pads(pg, KONAMI); want('not from the pause menu', state(pg, 'devUnlock'), False)
    settings(pg, 5, 'menu_gameplay_still_locked.png'); pads(pg, KONAMI); want('not from a settings page', state(pg, 'devUnlock'), False)
    want('codes untouched while locked', [state(pg, 'codeHealth'), state(pg, 'codeLives')], [False, False])
    # the code itself, also after backing out of a load (the pause menu's way out that leaves a page open)
    show(pg, 'pause'); pads(pg, ['down', 'down', 'ok']); show(pg, 'launcher'); pads(pg, KONAMI); want('Konami unlocks', state(pg, 'devUnlock'), True)
    pg.wait_for_timeout(100); pg.screenshot(path=OUT + '/menu_konami_toast.png')
    want('unlocking switches no code on', [state(pg, 'codeHealth'), state(pg, 'codeLives')], [False, False])
    settings(pg, 5, 'menu_gameplay_unlocked.png')
    pads(pg, ['down', 'ok']); pg.screenshot(path=OUT + '/menu_codes.png')
    want('codes off on the page', [state(pg, 'codeHealth'), state(pg, 'codeLives')], [False, False])
    pads(pg, ['ok', 'down', 'ok']); want('switching them on works', [state(pg, 'codeHealth'), state(pg, 'codeLives')], [True, True])
    show(pg, 'launcher'); pads(pg, KONAMI); want('Konami again hides', state(pg, 'devUnlock'), False)
    want('hiding switches them off', [state(pg, 'codeHealth'), state(pg, 'codeLives')], [False, False])
    settings(pg, 5, 'menu_gameplay_hidden_again.png')
    want('menu errors', errs, [])

    # ---- Link's page: a new install, an install of 0.1 that had the tab on, and switching it on in 0.2
    d3 = dict(rom=rom, kit=kit, dump=dump)
    pg = linkpage.page(br, linkpage.init_js(**d3, stored=''), 1240, 1080, 1)
    want('new Link install: tab', pg.evaluate("JSON.parse(localStorage.getItem('sma.opts')).tab3d"), False)
    want('new Link install: no GL view', pg.evaluate('window.__gl'), None)
    want('new Link install: settings', pg.evaluate('JSON.parse(Native.settings3d())'), dict(cam3d='low', depth3d=20, back3d='near', cave3d=True, light3d=False, thick3d=True, round3d=False))
    pg.screenshot(path=OUT + '/link_first.png'); want('Link errors', pg.msgs, []); pg.context.close()
    old = "if (!localStorage.getItem('sma.opts')) localStorage.setItem('sma.opts', JSON.stringify({xray:true,markers:true,zl:1,tab3d:true}));"
    pg = linkpage.page(br, linkpage.init_js(**d3, stored=old), 1240, 1080, 1)
    want('Link 0.1 with the tab on: off now', pg.evaluate("JSON.parse(localStorage.getItem('sma.opts')).tab3d"), False)
    pg.evaluate("var o = JSON.parse(localStorage.getItem('sma.opts')); o.tab3d = true; localStorage.setItem('sma.opts', JSON.stringify(o));")
    pg.reload(); pg.wait_for_timeout(700)
    want('switched on in 0.2: stays on', pg.evaluate("JSON.parse(localStorage.getItem('sma.opts')).tab3d"), True)
    want('Link errors', pg.msgs, []); pg.context.close()
    br.close()
print('ALL AS EXPECTED (%d checks)' % checks if not bad else '%d WRONG of %d' % (len(bad), checks))
sys.exit(1 if bad else 0)

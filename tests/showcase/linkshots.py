# linkshots.py WORK OUTDIR : what Link looks like on the Thor: the normal game on top (RetroArch's picture), Link on the 1240x1080 screen under it.
# The page runs in headless Chromium with the stand-in bridge (tests/ra/linkpage.py) and the kit Link builds from the ROM; the 3D tab's picture is
# rendered at the size the page asked for and put where the app's GL view goes.
import json, os, subprocess, sys, shutil
from PIL import Image
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); T = HERE + '/../scene3d'
sys.path.insert(0, HERE + '/../ra')
import linkpage
W, OUT = sys.argv[1], sys.argv[2]
SHOW = W + '/' + os.environ.get('SHOW_DIR', 'show'); os.makedirs(OUT, exist_ok=True); TMP = SHOW + '/_l'; os.makedirs(TMP, exist_ok=True)
rom = open(W + '/sma.gba', 'rb').read(); kit = open(W + '/ra43/kit_rom.bin', 'rb').read()
SW, SH = 620, 540                      # css px at 2x: the Thor's lower screen
TAB = {'status': .123, 'map': .319, 'chart': .498, '3d': .66, 'options': .858}
CAM = {'low': 30, 'tilt': 90, 'diorama': 150, 'far': 210}
who = [0, 1, 2, 3, 0, 2, 1, 1, 3, 0, 2, 2, 3, 1, 0, 3, 2, 0, 1, 3]
lo = sum(who[i] << (i * 2) for i in range(16)); hi = sum(who[i] << ((i - 16) * 2) for i in range(16, 20))
TRK = dict(time=int(59.7275 * (6 * 3600 + 12 * 60 + 40)), deaths=31, cleared=0xFFFFF, lo=lo, hi=hi)
def compose(page_png, top_ppm, out, gl=None):
    top = Image.new('RGB', (1920, 1080), (0, 0, 0))
    if top_ppm: top.paste(Image.open(top_ppm).convert('RGB').resize((1620, 1080), Image.NEAREST), (150, 0))
    bot = Image.open(page_png).convert('RGB')
    if gl: bot.paste(Image.open(gl[0]).convert('RGB'), (gl[1], gl[2]))
    g = Image.new('RGB', (1920, 1080 + 28 + bot.height), (18, 17, 16))
    g.paste(top, (0, 0)); g.paste(bot, ((1920 - bot.width) // 2, 1108))
    g.save(out)
def render3d(spot, cam, w, h, name, env=None, light=False):
    d = TMP + '/' + name; os.makedirs(d, exist_ok=True); size = '%dx%d' % (w, h)
    e = dict(os.environ, CAM=cam, ASPECT=size, SIZE=size, **(env or {}))
    subprocess.run(['java', '-cp', T + '/out', 'com.jack.smadual.SceneSeq', W + '/sma.gba', d, '%s/%s.bin' % (SHOW, spot)], env=e, capture_output=True)
    out = TMP + '/' + name + '.png'
    subprocess.run(['python3', T + '/shot.py', out, d + '/f000.s3d', cam] + (['light'] if light else []), env=e, capture_output=True, cwd=W)
    return out
with sync_playwright() as p:
    br = p.chromium.launch()
    def shot(spot, tab, name, cam=None, zoom=None, scroll=0, settings=None, env=None, light=False, tab3d=True):
        dump = open('%s/%s.bin' % (SHOW, spot), 'rb').read()
        pg = linkpage.page(br, linkpage.init_js(rom, kit, dump, trk=TRK, tab3d=tab3d, settings=settings), SW, SH)
        if tab != 'map': pg.mouse.click(SW * TAB[tab], SH - 6); pg.wait_for_timeout(300)
        if cam: pg.mouse.click(SW * CAM[cam] / 240, SH * 155 / 176); pg.wait_for_timeout(200)
        for i in range(zoom or 0): pg.mouse.click(SW * .5, SH * .4); pg.wait_for_timeout(250)
        if scroll:
            pg.mouse.move(SW * .5, SH * .8); pg.mouse.down(); pg.mouse.move(SW * .5, SH * .8 - scroll, steps=8); pg.mouse.up(); pg.wait_for_timeout(250)
        png = TMP + '/' + name + '_p.png'; pg.screenshot(path=png)
        gl = pg.evaluate('window.__gl')
        layer = None
        if gl:
            x, y, w, h = [round(v * (SW * 2 if i % 2 == 0 else SH * 2)) for i, v in enumerate(gl)]
            layer = (render3d(spot, cam or 'low', w, h, name, env, light), x, y)
        compose(png, '%s/%s.ppm' % (SHOW, spot), '%s/%s.png' % (OUT, name), layer)
        if pg.msgs: print(name, pg.msgs[:3])
        pg.context.close()
        print(name, 'gl', gl, flush=True)
    # the 3D tab: what only Link has
    shot('2-1_pyramids', '3d', 'link_3d_tab_2-1_pyramids_diorama', 'diorama')
    shot('4-2_whales', '3d', 'link_3d_tab_4-2_whales_tilt', 'tilt')
    shot('4-2_whale_back', '3d', 'link_3d_tab_4-2_whales_low', 'low')
    shot('7-1_temple', '3d', 'link_3d_tab_7-1_temple_far', 'far')
    shot('1-1_logs_water', '3d', 'link_3d_tab_1-1_logs_diorama', 'diorama')
    shot('7-2_machine', '3d', 'link_3d_tab_7-2_machine_tilt', 'tilt')
    # the rest of the page, as in Dual
    shot('4-2_whales', 'map', 'link_map_4-2')
    shot('2-1_oasis', 'map', 'link_map_2-1_whole_area', zoom=3)
    shot('6-3_sky_pyramid', 'status', 'link_status_6-3')
    shot('1-3_waterfall', 'chart', 'link_chart')
    shot('4-1_ice', 'options', 'link_options_3d_on')
    shot('4-1_ice', 'options', 'link_options_states', scroll=260)
    # 3D tab off: four tabs and only the switch in OPTIONS
    dump = open(SHOW + '/4-1_ice.bin', 'rb').read()
    pg = linkpage.page(br, linkpage.init_js(rom, kit, dump, trk=TRK, tab3d=False), SW, SH)
    pg.mouse.click(SW * .875, SH - 6); pg.wait_for_timeout(300)
    png = TMP + '/off.png'; pg.screenshot(path=png); compose(png, SHOW + '/4-1_ice.ppm', OUT + '/link_options_3d_off.png'); pg.context.close()
    # the setup screen, before there's a game
    for prob, nm in (('norom', 'link_setup_choose_rom'), ('noreply', 'link_setup_no_retroarch')):
        pg = linkpage.page(br, linkpage.init_js(rom, kit, dump, problem=prob, tab3d=False), SW, SH)
        pg.screenshot(path='%s/%s.png' % (OUT, nm)); pg.context.close()
    br.close()
# one sheet of the lot
fs = sorted(f for f in os.listdir(OUT) if f.endswith('.png') and not f.startswith('_') and 'setup' not in f)
cw = 640; ims = [Image.open(OUT + '/' + f).convert('RGB') for f in fs]; chh = round(ims[0].height * cw / 1920)
g = Image.new('RGB', (4 * (cw + 8) + 8, ((len(ims) + 3) // 4) * (chh + 8) + 8), (40, 38, 36))
for i, m in enumerate(ims): g.paste(m.resize((cw, chh), Image.LANCZOS), (8 + (i % 4) * (cw + 8), 8 + (i // 4) * (chh + 8)))
g.save(OUT + '/_all_link_shots.png'); print('sheet', g.size)

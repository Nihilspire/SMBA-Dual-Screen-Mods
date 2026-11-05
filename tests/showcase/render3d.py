# render3d.py WORK OUTDIR : the 3D view of each spot from spots.py, in every camera at 1920x1080, then the 3D menu's options on a few of them.
# Scene3D and Renderer3D's own shader, run by tests/scene3d (SceneSeq + shot.py). Contact sheets on top.
import json, os, shutil, subprocess, sys
from concurrent.futures import ThreadPoolExecutor
from PIL import Image, ImageDraw, ImageFont
HERE = os.path.dirname(os.path.abspath(__file__)); T = HERE + '/../scene3d'
W, OUT = sys.argv[1], sys.argv[2]
SHOW = W + '/show'; TMP = W + '/show/_r'; os.makedirs(OUT + '/cameras', exist_ok=True); os.makedirs(OUT + '/options', exist_ok=True); os.makedirs(TMP, exist_ok=True)
names = json.load(open(SHOW + '/names.json'))
CAMS = ['low', 'tilt', 'diorama', 'far']
try: FONT = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 22)
except OSError: FONT = ImageFont.load_default()
def one(dump, cam, out, size='1920x1080', env=None, light=False):
    key = out.replace('/', '_').replace(' ', '_')
    d = TMP + '/' + key; os.makedirs(d, exist_ok=True)
    w, h = size.split('x')
    e = dict(os.environ, CAM=cam, ASPECT=size, SIZE=size, **(env or {}))
    r = subprocess.run(['java', '-cp', T + '/out', 'com.jack.smadual.SceneSeq', W + '/sma.gba', d, dump], env=e, capture_output=True, text=True)
    mapped = 'mapped true' in r.stdout
    subprocess.run(['python3', T + '/shot.py', out, d + '/f000.s3d', cam] + (['light'] if light else []), env=e, capture_output=True, text=True, cwd=W)
    shutil.rmtree(d, ignore_errors=True)
    return mapped
jobs = [(n, c) for n in names for c in CAMS]
with ThreadPoolExecutor(6) as ex:
    res = list(ex.map(lambda j: one('%s/%s.bin' % (SHOW, j[0]), j[1], '%s/cameras/%s_%s.png' % (OUT, j[0], j[1])), jobs))
print('camera renders', len(res), 'mapped', sum(res), 'not mapped', [j for j, r in zip(jobs, res) if not r])
# one sheet: a row a scene, the game's own frame and the four cameras
cw, ch = 480, 270
g = Image.new('RGB', (5 * (cw + 6) + 6, len(names) * (ch + 6) + 40), (24, 22, 20)); d = ImageDraw.Draw(g)
for i, c in enumerate(['the game'] + [c.upper() for c in CAMS]): d.text((6 + i * (cw + 6) + 6, 8), c, fill=(255, 240, 200), font=FONT)
for r, n in enumerate(names):
    y = 40 + r * (ch + 6)
    f = Image.open('%s/%s.ppm' % (SHOW, n)).convert('RGB'); f = f.resize((405, 270), Image.NEAREST)
    g.paste(f, (6 + (cw - 405) // 2, y)); d.text((12, y + 244), n.replace('_', ' '), fill=(255, 255, 255), font=FONT, stroke_width=2, stroke_fill=(0, 0, 0))
    for k, c in enumerate(CAMS):
        g.paste(Image.open('%s/cameras/%s_%s.png' % (OUT, n, c)).convert('RGB').resize((cw, ch), Image.LANCZOS), (6 + (k + 1) * (cw + 6), y))
g.save(OUT + '/_all_scenes_all_cameras.png')
# the 3D menu's options, one change at a time
OPTS = [('default', {}, False), ('round_sprites', {'ROUND': '1'}, False), ('player_light', {'LIGHT': '1'}, True), ('block_depth_32', {'DEPTH': '32'}, False),
        ('block_depth_8', {'DEPTH': '8'}, False), ('back_wall_deep', {'BACK': 'far'}, False), ('no_background', {'NOCAVE': '1'}, False), ('flat_sprites', {'NOTHICK': '1'}, False)]
PICK = [('2-1_pyramids', 'diorama'), ('4-2_whales', 'diorama'), ('5-3_cave', 'tilt')]
jobs = [(n, c, o) for n, c in PICK for o in OPTS]
with ThreadPoolExecutor(6) as ex:
    list(ex.map(lambda j: one('%s/%s.bin' % (SHOW, j[0]), j[1], '%s/options/%s_%s.png' % (OUT, j[0], j[2][0]), '1280x720', j[2][1], j[2][2]), jobs))
cw, ch = 480, 270
g = Image.new('RGB', (4 * (cw + 6) + 6, len(PICK) * 2 * (ch + 6) + 6), (24, 22, 20)); d = ImageDraw.Draw(g)
for r, (n, c) in enumerate(PICK):
    for k, o in enumerate(OPTS):
        x, y = 6 + (k % 4) * (cw + 6), 6 + (r * 2 + k // 4) * (ch + 6)
        g.paste(Image.open('%s/options/%s_%s.png' % (OUT, n, o[0])).convert('RGB').resize((cw, ch), Image.LANCZOS), (x, y))
        d.text((x + 6, y + 244), o[0].replace('_', ' ').upper(), fill=(255, 255, 255), font=FONT, stroke_width=2, stroke_fill=(0, 0, 0))
g.save(OUT + '/_options.png')
print('done')

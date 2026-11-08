# Renders a directory of SceneSeq outputs (fNNN.s3d) to PNGs with one browser. python3 shotseq.py indir outdir WxH cam
# cam: a preset name, or a JSON file with one [pitch, yaw, zoom] per frame. Frames with mapped == 0 are skipped
# (written as NNN.flat) so the caller can show the 2D picture there, as the app does.
import base64, re, sys, os, json, struct
from playwright.sync_api import sync_playwright
here = os.path.dirname(os.path.abspath(__file__))
java = open(here + '/../../app/src/com/jack/smadual/Renderer3D.java').read()
fs = re.search(r'static final String FS =\s*(.*?);\n', java, re.S).group(1)
fs = ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', fs)).encode().decode('unicode_escape')
ind, outd, size, cam = sys.argv[1:5]
vw, vh = (int(v) for v in size.split('x'))
files = sorted(f for f in os.listdir(ind) if f.endswith('.s3d'))
cams = json.load(open(cam)) if cam.endswith('.json') else None
os.makedirs(outd, exist_ok=True)
with sync_playwright() as p:
    b = p.chromium.launch(args=['--use-gl=swiftshader', '--enable-webgl', '--ignore-gpu-blocklist'])
    pg = None
    for i, f in enumerate(files):
        raw = open(os.path.join(ind, f), 'rb').read()
        n = f[1:4]
        if struct.unpack_from('<i', raw, 32)[0] == 0: open(os.path.join(outd, n + '.flat'), 'w').write('1'); continue
        if pg is None or i % 25 == 0:
            if pg: pg.close()
            pg = b.new_page(viewport={'width': vw, 'height': vh})
            pg.goto('file://' + here + '/view.html')
            pg.evaluate('(a) => { window.FS = a[0]; window.LIGHT = a[1]; window.STANDIN = false; }', [fs, bool(os.environ.get('LIGHT'))])
        c = cams[i] if cams else cam
        pg.evaluate('(a) => show(a[0], a[1])', [base64.b64encode(raw).decode(), c])
        pg.screenshot(path=os.path.join(outd, n + '.png'))
    b.close()

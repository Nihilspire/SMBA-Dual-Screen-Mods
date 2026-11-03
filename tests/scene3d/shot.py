# Renders SceneDump outputs in headless Chromium with Renderer3D's own fragment shader.
# python3 shot.py out.png file.s3d [preset] [light] [standin]   (standin: flat colours, no game art, for previews)
import base64, re, sys
from playwright.sync_api import sync_playwright
here = __file__.rsplit('/', 1)[0]
java = open(here + '/../../app/src/com/jack/smadual/Renderer3D.java').read()
fs = re.search(r'static final String FS =\s*(.*?);\n', java, re.S).group(1)
fs = ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', fs)).encode().decode('unicode_escape')
out, src = sys.argv[1], sys.argv[2]
preset = sys.argv[3] if len(sys.argv) > 3 else 'tilt'
light = 'light' in sys.argv[4:]
standin = 'standin' in sys.argv[4:]
with sync_playwright() as p:
    b = p.chromium.launch(args=['--use-gl=swiftshader', '--enable-webgl', '--ignore-gpu-blocklist'])
    import os
    vw, vh = (int(v) for v in os.environ.get('SIZE', '960x540').split('x'))
    pg = b.new_page(viewport={'width': vw, 'height': vh}); errs = []
    pg.on('pageerror', lambda e: errs.append(str(e))); pg.on('console', lambda m: errs.append(m.text))
    pg.goto('file://' + here + '/view.html')
    pg.evaluate('(a) => { window.FS = a[0]; window.LIGHT = a[1]; window.STANDIN = a[2]; }', [fs, light, standin])
    r = pg.evaluate('(a) => show(a[0], a[1])', [base64.b64encode(open(src, 'rb').read()).decode(), preset])
    pg.screenshot(path=out)
    print(r, errs)
    b.close()

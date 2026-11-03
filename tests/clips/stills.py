# stills.py - 3D render (1920x1080) with the Link screen (small layout, 900x560 css px at 2.1333x) underneath
import os, shutil, subprocess, json
from PIL import Image
R = '_r17'
scenes = [l.split() for l in open(R + '/scenes.txt') if l.strip()]
TABS = {'status': [112, 545], 'chart': [562, 545]}
extra = {'1-1_hills': 'status', '4-1_ice': 'chart', '6-1_desert': 'status', '7-2_sky': 'chart'}
os.makedirs('stills', exist_ok=True)
jobs = []
for name, dump in scenes:
    for tab in ['map'] + ([extra[name]] if name in extra else []):
        d = 'stills/_%s_%s' % (name, tab); shutil.rmtree(d, ignore_errors=True); os.makedirs(d + '/raw')
        shutil.copy(dump + '.bin', d + '/raw/d000.bin')
        ev = '-'
        if tab != 'map': json.dump([[0, 'tap'] + TABS[tab]], open(d + '/ev.json', 'w')); ev = d + '/ev.json'
        sd = 'gs1' if dump.startswith('gs1') else '.'
        jobs.append((name, tab, d, subprocess.Popen(['python3', 'linkseq.py', 'sma.gba', d + '/raw', d + '/link', '900x560', ev],
                                                     env=dict(os.environ, SCALE='2.13333'))))
        if len(jobs) % 6 == 0:
            for j in jobs[-6:]: j[3].wait()
for j in jobs: j[3].wait()
for name, tab, d, _ in jobs:
    link = Image.open(d + '/link/000.png').convert('RGB')
    link = link.resize((1920, round(1920 * link.height / link.width)), Image.LANCZOS) if link.width != 1920 else link
    cams = ['tilt', 'diorama'] if tab == 'map' else ['tilt']
    for cam in cams:
        top = Image.open('%s/Cameras/%s_%s.png' % (R, name, cam)).convert('RGB')
        fr = Image.new('RGB', (1920, 1080 + 20 + link.height), (20, 18, 16))
        fr.paste(top, (0, 0)); fr.paste(link, (0, 1100))
        fr.save('stills/%s_%s_link-%s.png' % (name, cam, tab))
print(len(os.listdir('stills')))

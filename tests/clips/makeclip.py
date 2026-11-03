# makeclip.py - one 6 s dual-screen clip: emulator recording -> 3D (SceneSeq + shotseq) + Link page (linkseq) -> stacked
# frames -> H.264. Clip definitions live in clips.py.
import json, os, subprocess, shutil, sys
from PIL import Image
from cliptool import record
S3 = '/home/claude/sma/sma-dual/tests/scene3d'
TOP_W, TOP_H, LINK_W, LINK_H, GAP = 960, 540, 960, 598, 10


def stage_record(c, d):
    shutil.rmtree(d + '/raw', ignore_errors=True)
    return record(c['state'], c['steps'], d + '/raw', every=2, savedir=c.get('savedir', '.'))


def stage_3d(c, d):
    shutil.rmtree(d + '/s3d', ignore_errors=True); os.makedirs(d + '/s3d')
    dumps = sorted(os.path.join(d, 'raw', f) for f in os.listdir(d + '/raw') if f.endswith('.bin'))
    subprocess.run(['java', '-cp', S3 + '/out', 'com.jack.smadual.SceneSeq', 'sma.gba', d + '/s3d'] + dumps, capture_output=True, check=True)
    cam = c.get('cam', 'tilt')
    if isinstance(cam, list):
        json.dump(cam, open(d + '/cam.json', 'w')); cam = os.path.abspath(d + '/cam.json')
    shutil.rmtree(d + '/top', ignore_errors=True)
    subprocess.run(['python3', S3 + '/shotseq.py', d + '/s3d', d + '/top', '%dx%d' % (TOP_W, TOP_H), cam], check=True)


def stage_link(c, d):
    shutil.rmtree(d + '/link', ignore_errors=True)
    ev = '-'
    if c.get('events'):
        json.dump(c['events'], open(d + '/events.json', 'w')); ev = d + '/events.json'
    subprocess.run(['python3', 'linkseq.py', 'sma.gba', d + '/raw', d + '/link', '900x560', ev,
                    json.dumps(c.get('opts', {'xray': True, 'markers': True, 'zl': 1}))], check=True)


def stage_compose(c, d, out):
    shutil.rmtree(d + '/frames', ignore_errors=True); os.makedirs(d + '/frames')
    n = len([f for f in os.listdir(d + '/raw') if f.endswith('.bin')])
    for i in range(n):
        fr = Image.new('RGB', (TOP_W, TOP_H + GAP + LINK_H), (20, 18, 16))
        top = d + '/top/%03d.png' % i
        if os.path.exists(top):
            fr.paste(Image.open(top).convert('RGB'), (0, 0))
        else:   # the app shows the plain 2D picture when the 3D view can't match the screen (Subspace, transitions)
            g = Image.open(d + '/raw/s%03d.ppm' % i).resize((810, 540), Image.NEAREST)
            fr.paste(g, ((TOP_W - 810) // 2, 0))
        fr.paste(Image.open(d + '/link/%03d.png' % i).convert('RGB').resize((LINK_W, LINK_H), Image.LANCZOS), (0, TOP_H + GAP))
        fr.save(d + '/frames/%03d.png' % i)
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-framerate', '30', '-i', d + '/frames/%03d.png', '-c:v', 'libx264',
                    '-preset', 'veryfast', '-crf', '18', '-pix_fmt', 'yuv420p', out], check=True)


if __name__ == '__main__':
    import clips
    name = sys.argv[1]; stages = sys.argv[2] if len(sys.argv) > 2 else 'r3lc'
    c = clips.CLIPS[name]; d = 'clips/' + name
    os.makedirs(d, exist_ok=True)
    if 'r' in stages: print(name, 'frames', stage_record(c, d))
    if '3' in stages: stage_3d(c, d)
    if 'l' in stages: stage_link(c, d)
    if 'c' in stages: stage_compose(c, d, 'clips/%s.mp4' % name)

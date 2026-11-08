"""make_art.py OUTDIR ICON.png - the 3DS HOME Menu icon (48x48, from the owner's Android launcher icon) and banner (256x128) for Super Mario Advance
Dual 3DS: only thick 3D pixel text, "SMA DUAL" / "3DS MOD", on a transparent background, as Shantae Dual 3DS's. The letters are drawn here; no game
art, logo or font."""
import os, sys
from PIL import Image

GLYPH = {
    'S': ['.###.', '#...#', '#....', '.###.', '....#', '#...#', '.###.'],
    'M': ['#...#', '##.##', '#.#.#', '#.#.#', '#...#', '#...#', '#...#'],
    'A': ['.###.', '#...#', '#...#', '#####', '#...#', '#...#', '#...#'],
    'D': ['####.', '#...#', '#...#', '#...#', '#...#', '#...#', '####.'],
    'U': ['#...#', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.'],
    'L': ['#....', '#....', '#....', '#....', '#....', '#....', '#####'],
    'O': ['.###.', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.'],
    '3': ['####.', '....#', '....#', '.###.', '....#', '....#', '####.'],
    ' ': ['.....'] * 7,
}
GRAD = [(255, 248, 196), (255, 232, 120), (252, 204, 72), (248, 160, 56), (240, 112, 48), (224, 72, 40), (192, 44, 36)]
OUTLINE = (24, 20, 72)

def word3d(im, text, x0, y0, s, depth, gap=1):
    """An extruded side going down-right (deep blue, lit at the top), a dark outline, then the yellow-to-red face."""
    cells = set()
    for i, ch in enumerate(text):
        for gy, row in enumerate(GLYPH[ch]):
            for gx, v in enumerate(row):
                if v == '#': cells.add((i * (5 + gap) + gx, gy))
    def fill(px, py, w, h, c):
        for yy in range(py, py + h):
            for xx in range(px, px + w):
                if 0 <= xx < im.width and 0 <= yy < im.height: im.putpixel((xx, yy), c)
    o = max(1, s // 3)
    for d in range(depth + o, 0, -1):
        t = d / (depth + o)
        c = (int(72 - 56 * t), int(96 - 84 * t), int(200 - 136 * t), 255)
        for (gx, gy) in cells: fill(x0 + gx * s + d - o, y0 + gy * s + d - o, s + 2 * o, s + 2 * o, c)
    for (gx, gy) in cells: fill(x0 + gx * s - o, y0 + gy * s - o, s + 2 * o, s + 2 * o, OUTLINE + (255,))
    for (gx, gy) in cells:
        for yy in range(s):
            fill(x0 + gx * s, y0 + gy * s + yy, s, 1, GRAD[min(6, (gy * s + yy) * 7 // (7 * s))] + (255,))
        if (gx, gy - 1) not in cells: fill(x0 + gx * s, y0 + gy * s, s, 1, (255, 252, 224, 255))

def width(text, s, gap=1): return (len(text) * (5 + gap) - gap) * s

def main(out, icon):
    os.makedirs(out, exist_ok=True)
    Image.open(icon).convert('RGB').resize((48, 48), Image.LANCZOS).save(os.path.join(out, 'icon.png'))
    ban = Image.new('RGBA', (256, 128), (0, 0, 0, 0))
    word3d(ban, 'SMA DUAL', (256 - width('SMA DUAL', 5)) // 2 - 3, 14, 5, 7)
    word3d(ban, '3DS MOD', (256 - width('3DS MOD', 5)) // 2 - 3, 66, 5, 7)
    ban.save(os.path.join(out, 'banner.png'))
    prev = Image.new('RGB', (256 * 2 + 48 * 4 + 24, 256), (0, 0, 0))
    bg = Image.new('RGBA', (256, 128), (150, 150, 160, 255)); bg.alpha_composite(ban)
    prev.paste(bg.convert('RGB').resize((512, 256), Image.NEAREST), (0, 0))
    prev.paste(Image.open(os.path.join(out, 'icon.png')).resize((192, 192), Image.NEAREST), (536, 32))
    prev.save(os.path.join(out, 'preview.png'))

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])

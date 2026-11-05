# kitcheck.py real_kit.bin rom_kit.bin : menu/gbakit.js on a real first-start capture and on the kit Link builds from the ROM.
# Every canvas and colour it makes must be the same, pixel for pixel.
import base64, os, sys
from playwright.sync_api import sync_playwright
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
a, b = [base64.b64encode(open(p, 'rb').read()).decode() for p in sys.argv[1:3]]
JS = """(s) => {
  const raw = Uint8Array.from(atob(s), c => c.charCodeAt(0));
  const ok = GbaKit.load(raw);
  const px = c => { const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data; let h = 0; for (let i = 0; i < d.length; i++) h = (h * 31 + d[i]) | 0; return c.width + 'x' + c.height + ':' + h; };
  const o = { ok, sky: px(GbaKit.sky), ground: px(GbaKit.ground), cursor: px(GbaKit.cursor), coin: px(GbaKit.coin), colors: JSON.stringify(GbaKit.colors) };
  for (const k in GbaKit.piece) o['piece.' + k] = px(GbaKit.piece[k]);
  for (const k in GbaKit.icons) o['icon.' + k] = px(GbaKit.icons[k]);
  return o; }"""
with sync_playwright() as p:
    br = p.chromium.launch(); pg = br.new_page()
    pg.goto('file://' + ROOT + '/app/assets/menu/index.html')
    pg.wait_for_timeout(300)
    ra, rb = pg.evaluate(JS, a), pg.evaluate(JS, b)
    br.close()
bad = [k for k in sorted(set(ra) | set(rb)) if ra.get(k) != rb.get(k)]
print('compared', len(ra), 'items:', sorted(ra)[:40])
print('real ok', ra['ok'], 'built ok', rb['ok'])
print('DIFFERENT: %s' % bad if bad else 'ALL THE SAME')

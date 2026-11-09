"""A test font for #636: glyphs for some Arabic letters, their presentation forms and the lam-alef
ligatures, mapped in its cmap, and no GSUB, GPOS or GDEF. Run with fontTools: python3 make-arabic-nogsub.py arabic-nogsub.ttf"""
import sys, unicodedata as u
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

letters = [0x0627, 0x0628, 0x062A, 0x0644, 0x0645, 0x064A, 0x0622, 0x0623, 0x0625]
cps = list(letters)
for rng in (range(0xFE70, 0xFF00),):
    for cp in rng:
        d = u.decomposition(chr(cp))
        if d.startswith("<") and all(int(x, 16) in letters for x in d.split()[1:]):
            cps.append(cp)
names = [".notdef"] + [f"u{cp:04X}" for cp in cps]
fb = FontBuilder(1000, isTTF=True)
fb.setupGlyphOrder(names)
fb.setupCharacterMap({cp: f"u{cp:04X}" for cp in cps})
glyphs = {}
for i, n in enumerate(names):
    pen = TTGlyphPen(None)
    if n != ".notdef":
        pen.moveTo((100, 0)); pen.lineTo((100, 100 + i)); pen.lineTo((400, 100 + i)); pen.lineTo((400, 0)); pen.closePath()
    glyphs[n] = pen.glyph()
fb.setupGlyf(glyphs)
fb.setupHorizontalMetrics({n: (500, 100) for n in names})
fb.setupHorizontalHeader(ascent=800, descent=-200)
fb.setupNameTable({"familyName": "ArabicNoGsub", "styleName": "Regular"})
fb.setupOS2(sTypoAscender=800, usWinAscent=800, usWinDescent=200)
fb.setupPost()
fb.save(sys.argv[1])
print(len(names), "glyphs")

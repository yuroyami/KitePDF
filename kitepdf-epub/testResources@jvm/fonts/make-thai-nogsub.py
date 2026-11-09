"""A test font for #637: glyphs for some Thai consonants and marks, and the Private Use Area
variants of the marks and descenders that fonts without GSUB carry, mapped in its cmap, and no
GSUB, GPOS or GDEF. Run with fontTools: python3 make-thai-nogsub.py thai-nogsub.ttf"""
import sys
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

cps = [0x0E01, 0x0E0D, 0x0E1B, 0x0E34, 0x0E38, 0x0E48] + [0xF700, 0xF701, 0xF705, 0xF70A, 0xF70F, 0xF713, 0xF718]
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
fb.setupNameTable({"familyName": "ThaiNoGsub", "styleName": "Regular"})
fb.setupOS2(sTypoAscender=800, usWinAscent=800, usWinDescent=200)
fb.setupPost()
fb.save(sys.argv[1])

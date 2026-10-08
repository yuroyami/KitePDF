#!/usr/bin/env python3
"""Rebuild the embedded-font Japanese publications for #620, without network access.

Requires fonttools==4.66.1. Inputs, with SHA-256 checked below:
- IDPF epub3-samples release 20230704, kusamakura-japanese-vertical-writing.epub.
  Its text/markup is CC0. Recordings are CC BY-NC-SA and are deliberately excluded.
- google/fonts commit 295d98a7a0c17c68f1341eaeea354e7960ea70d3,
  ofl/notosansjp/NotoSansJP[wght].ttf and OFL.txt, SIL Open Font License 1.1.

The EPUB retains all thirteen chapters and ruby. The PDF typesets the first chapter
in vertical columns. Both embed the same static subset, preserving vert/vrt2, vhea
and vmtx; the PDF's CID-to-glyph map selects the vertical presentation glyphs.
"""
import argparse
import hashlib
import html
import io
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET
import zipfile
import zlib

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

HASHES = {
    "source": "6d4d4ed5eda3f612e3263c54fa0f74ccfa87260f43e81bb58ea658636e52eeb7",
    "font": "c2f3b4d463500a2ddcd3849cded1fceeb9fd6d1c32e6cbecd568453ba50fc68f",
    "license": "1c05c68c34f9708415aada51f17e1b0092d2cea709bf4a94cd38114f9e73d7d9",
}
XHTML = "http://www.w3.org/1999/xhtml"
ET.register_namespace("", XHTML)
CHAPTERS = ["一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二", "十三"]


def checked(path, kind):
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != HASHES[kind]:
        raise ValueError(f"{kind} input has changed: {path}")
    return data


def base_text(node):
    """PDF text keeps ruby bases; the pronunciation and fallback parentheses stay in the EPUB."""
    out = node.text or ""
    for child in node:
        if child.tag.rsplit("}", 1)[-1] not in {"rt", "rp"}:
            out += base_text(child)
        out += child.tail or ""
    return out


def zip_member(book, name, data, compressed=True):
    entry = zipfile.ZipInfo(name, (2026, 10, 8, 0, 0, 0))
    entry.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
    entry.external_attr = 0o644 << 16
    book.writestr(entry, data)


def vertical_glyphs(font):
    mapping = {}
    gsub = font["GSUB"].table
    for tag in ("vert", "vrt2"):
        for record in gsub.FeatureList.FeatureRecord:
            if record.FeatureTag != tag:
                continue
            for index in record.Feature.LookupListIndex:
                lookup = gsub.LookupList.Lookup[index]
                for subtable in lookup.SubTable:
                    if lookup.LookupType == 7:
                        subtable = subtable.ExtSubTable
                    mapping.update(getattr(subtable, "mapping", {}))
    if not mapping:
        raise ValueError("the embedded subset lost its vertical substitutions")
    return mapping


def pdf_stream(data, entries=""):
    return f"<< {entries} /Length {len(data)} >>\nstream\n".encode() + data + b"\nendstream"


def make_pdf(path, font, font_bytes, paragraphs):
    # A complete chapter, with paragraph boundaries preserved as column breaks.
    columns = []
    for paragraph in paragraphs:
        text = re.sub(r"\s+", "", paragraph)
        columns.extend(text[i:i + 44] for i in range(0, len(text), 44))
    pages = [columns[i:i + 25] for i in range(0, len(columns), 25)]
    characters = sorted(set("".join(columns)))
    cmap = font.getBestCmap()
    substitutions = vertical_glyphs(font)
    missing = [c for c in characters if ord(c) not in cmap]
    if missing:
        raise ValueError(f"font lacks chapter characters: {missing}")
    cids = {c: i + 1 for i, c in enumerate(characters)}
    glyphs = [substitutions.get(cmap[ord(c)], cmap[ord(c)]) for c in characters]
    units = font["head"].unitsPerEm
    widths, vertical = [], []
    for glyph in glyphs:
        advance, _ = font["hmtx"].metrics[glyph]
        height, bearing = font["vmtx"].metrics[glyph]
        outline = font["glyf"][glyph]
        if not hasattr(outline, "yMax"):
            outline.recalcBounds(font["glyf"])
        top = getattr(outline, "yMax", 0) + bearing
        widths.append(round(1000 * advance / units))
        vertical.extend((-round(1000 * height / units), round(500 * advance / units), round(1000 * top / units)))
    mapping = b"\0\0" + b"".join(struct.pack(">H", font.getGlyphID(glyph)) for glyph in glyphs)
    unicode_lines = ["/CIDInit /ProcSet findresource begin", "12 dict begin", "begincmap",
                     "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def",
                     "/CMapName /Kusamakura-UCS def", "/CMapType 2 def",
                     "1 begincodespacerange", "<0000> <FFFF>", "endcodespacerange"]
    for start in range(0, len(characters), 100):
        chunk = characters[start:start + 100]
        unicode_lines.append(f"{len(chunk)} beginbfchar")
        unicode_lines.extend(f"<{cids[c]:04X}> <{c.encode('utf-16-be').hex().upper()}>" for c in chunk)
        unicode_lines.append("endbfchar")
    unicode_lines += ["endcmap", "CMapName currentdict /CMap defineresource pop", "end", "end"]
    # Objects 1..10 are fixed; each page then owns a page dictionary and a stream.
    kids = " ".join(f"{11 + 2 * i} 0 R" for i in range(len(pages)))
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R /Lang (ja) >>",
        f"<< /Type /Pages /Kids [{kids}] /Count {len(pages)} >>".encode(),
        b"<< /Type /Font /Subtype /Type0 /BaseFont /KUSAMA+NotoSansJP /Encoding /Identity-V /DescendantFonts [4 0 R] /ToUnicode 7 0 R >>",
        ("<< /Type /Font /Subtype /CIDFontType2 /BaseFont /KUSAMA+NotoSansJP "
         "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /FontDescriptor 5 0 R "
         "/CIDToGIDMap 8 0 R /DW 1000 /DW2 [880 -1000] /W [1 [" + " ".join(map(str, widths)) +
         "]] /W2 [1 [" + " ".join(map(str, vertical)) + "]] >>").encode(),
        b"<< /Type /FontDescriptor /FontName /KUSAMA+NotoSansJP /Flags 4 /FontBBox [-1000 -1000 2000 2000] /ItalicAngle 0 /Ascent 1160 /Descent -288 /CapHeight 733 /StemV 80 /FontFile2 6 0 R >>",
        pdf_stream(zlib.compress(font_bytes, 9), f"/Filter /FlateDecode /Length1 {len(font_bytes)}"),
        pdf_stream(zlib.compress("\n".join(unicode_lines).encode(), 9), "/Filter /FlateDecode"),
        pdf_stream(zlib.compress(mapping, 9), "/Filter /FlateDecode"),
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Title (Kusamakura, chapter one, vertical Japanese) /Author (Natsume Soseki) /Subject (CC0 text from IDPF; embedded Noto Sans JP, OFL-1.1) >>",
    ]
    for i, page in enumerate(pages):
        content = f"BT /H 10 Tf 36 760 Td (Kusamakura - chapter one - {i + 1}/{len(pages)}) Tj ET\n"
        for j, column in enumerate(page):
            text = "".join(f"{cids[c]:04X}" for c in column)
            content += f"BT /F1 14 Tf 1 0 0 1 {558 - 20 * j} 720 Tm <{text}> Tj ET\n"
        content += "BT /H 8 Tf 36 36 Td (Text: CC0. Noto Sans JP: SIL OFL 1.1. KitePDF corpus, issue 620.) Tj ET\n"
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R /H 9 0 R >> >> /Contents {12 + 2 * i} 0 R >>".encode())
        objects.append(pdf_stream(content.encode()))
    out = bytearray(b"%PDF-1.7\n%\xE2\xE3\xCF\xD3\n")
    offsets = []
    for i, obj in enumerate(objects, 1):
        offsets.append(len(out)); out += f"{i} 0 obj\n".encode() + obj + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    for offset in offsets:
        out += f"{offset:010d} 00000 n \n".encode()
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R /Info 10 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    path.write_bytes(out)
    return len(pages)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("font", type=Path)
    parser.add_argument("license", type=Path)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    source = checked(args.source, "source")
    font_source = checked(args.font, "font")
    notice = checked(args.license, "license")
    chapters = []
    with zipfile.ZipFile(io.BytesIO(source)) as upstream:
        for title in CHAPTERS:
            root = ET.fromstring(upstream.read(f"OPS/xhtml/{title}.xhtml"))
            body = root.find(f"{{{XHTML}}}body")
            if body is None:
                raise ValueError(f"chapter {title} has no body")
            if any(e.tag.rsplit("}", 1)[-1] in {"audio", "video", "script", "img"} for e in body.iter()):
                raise ValueError("review an additional chapter asset before reproducing it")
            chapters.append((title, body))
    text = "".join("".join(body.itertext()) for _, body in chapters)
    font = TTFont(io.BytesIO(font_source), recalcTimestamp=False)
    font = instantiateVariableFont(font, {"wght": 400}, inplace=True)
    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.name_legacy = True
    options.glyph_names = True
    sub = subset.Subsetter(options=options)
    sub.populate(text=text)
    sub.subset(font)
    font.recalcTimestamp = False
    font["head"].created = font["head"].modified = 2082844800
    vertical_glyphs(font)
    encoded = io.BytesIO(); font.save(encoded); font_bytes = encoded.getvalue()
    css = '@font-face{font-family:CorpusJapanese;src:url("fonts/NotoSansJP.ttf")}body{font-family:CorpusJapanese;font-size:1em;line-height:1.8;writing-mode:vertical-rl}rt{font-size:0.5em}h1{font-size:1.5em}p{margin:0 1em 0 0}'
    epub_path = args.root / "corpus/epub/kusamakura-embedded-vertical.epub"
    with zipfile.ZipFile(epub_path, "w") as book:
        zip_member(book, "mimetype", "application/epub+zip", False)
        zip_member(book, "META-INF/container.xml", '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        items, spine, links = [], [], []
        for i, (title, body) in enumerate(chapters, 1):
            name = f"chapter-{i:02}.xhtml"
            content = f'<?xml version="1.0" encoding="utf-8"?><html xmlns="{XHTML}" xml:lang="ja" lang="ja"><head><title>{title}</title><link rel="stylesheet" href="vertical.css"/></head>' + ET.tostring(body, encoding="unicode") + '</html>'
            zip_member(book, "EPUB/" + name, content)
            items.append(f'<item id="c{i}" href="{name}" media-type="application/xhtml+xml"/>')
            spine.append(f'<itemref idref="c{i}"/>')
            links.append(f'<li><a href="{name}">{title}</a></li>')
        nav = f'<html xmlns="{XHTML}" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="ja"><head><title>草枕</title></head><body><nav epub:type="toc"><h1>草枕</h1><ol>{"".join(links)}</ol></nav></body></html>'
        zip_member(book, "EPUB/nav.xhtml", nav)
        zip_member(book, "EPUB/vertical.css", css)
        zip_member(book, "EPUB/fonts/NotoSansJP.ttf", font_bytes)
        zip_member(book, "EPUB/OFL.txt", notice)
        rights = 'Text and chapter markup: CC0, IDPF EPUB 3 Samples (20230704). All thirteen chapters retained. Recordings, illustrations, old styles and the placeholder font omitted. Noto Sans JP subset: SIL Open Font License 1.1; see OFL.txt. Vertical corpus edition for KitePDF issue 620.'
        package = f'''<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book">urn:kitepdf:corpus:kusamakura-vertical-20261008</dc:identifier><dc:title>草枕</dc:title><dc:creator>夏目漱石</dc:creator><dc:language>ja</dc:language><dc:rights>{html.escape(rights)}</dc:rights><meta property="dcterms:modified">2026-10-08T00:00:00Z</meta></metadata><manifest>{''.join(items)}<item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/><item id="css" href="vertical.css" media-type="text/css"/><item id="font" href="fonts/NotoSansJP.ttf" media-type="font/ttf"/><item id="license" href="OFL.txt" media-type="text/plain"/></manifest><spine page-progression-direction="rtl">{''.join(spine)}</spine></package>'''
        zip_member(book, "EPUB/package.opf", package)
    paragraphs = [base_text(e) for e in chapters[0][1].iter() if e.tag.rsplit("}", 1)[-1] == "p"]
    if not paragraphs:
        raise ValueError("chapter one has no paragraphs")
    pdf_path = args.root / "corpus/pdf/kusamakura-embedded-vertical.pdf"
    pages = make_pdf(pdf_path, font, font_bytes, paragraphs)
    print(f"{len(chapters)} EPUB chapters, {pages} PDF pages, {len(font.getGlyphOrder())} embedded glyphs, {len(vertical_glyphs(font))} vertical substitutions")
    for path in (epub_path, pdf_path):
        data = path.read_bytes(); print(path.name, len(data), hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    main()

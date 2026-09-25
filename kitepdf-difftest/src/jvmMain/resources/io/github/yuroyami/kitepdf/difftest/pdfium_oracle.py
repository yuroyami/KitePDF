"""PDFium driver for the KitePDF differential tests.

Run it with a Python that has pypdfium2 installed:

    python pdfium_oracle.py version
    python pdfium_oracle.py pages <pdf>
    python pdfium_oracle.py render <pdf> <out-dir> <dpi> <first-page> <last-page>
    python pdfium_oracle.py text <pdf> <out-dir> <first-page> <last-page>

Pages are 1-based, and a last page past the end stops at the last page.
Each result is one line on stdout, with fields separated by tabs:

    version   pdfium <version> pypdfium2 <version>
    pages     pages <count>
    render    ok <page> <png> <width> <height>      or   fail <page> <message>
    text      ok <page> <txt> <characters>           or   fail <page> <message>

A document that does not open prints "error <message>" and exits with 3.
"""

import os
import struct
import sys
import zlib

import pypdfium2 as pdfium
import pypdfium2.raw as pdfium_c


def emit(*fields):
    line = "\t".join(str(f).replace("\t", " ").replace("\r", " ").replace("\n", " ") for f in fields)
    sys.stdout.write(line + "\n")
    sys.stdout.flush()


def write_png(path, width, height, stride, buffer):
    """Writes RGB rows of `stride` bytes as an 8-bit RGB PNG, with the standard library only."""
    rows = memoryview(buffer).cast("B")
    row_bytes = width * 3
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        raw += rows[y * stride:y * stride + row_bytes]

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    with open(path, "wb") as out:
        out.write(b"\x89PNG\r\n\x1a\n")
        out.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)))
        out.write(chunk(b"IDAT", zlib.compress(bytes(raw), 1)))
        out.write(chunk(b"IEND", b""))


def open_document(path, forms):
    try:
        document = pdfium.PdfDocument(path)
        if forms:
            # A viewer shows form fields, so the render does too.
            document.init_forms()
        return document
    except Exception as e:
        emit("error", f"{type(e).__name__}: {e}")
        sys.exit(3)


def page_range(document, first, last):
    return range(max(1, first), min(last, len(document)) + 1)


def render(path, out_dir, dpi, first, last):
    document = open_document(path, forms=True)
    for number in page_range(document, first, last):
        try:
            page = document[number - 1]
            # Annotations and form fields on, anti-aliasing on, no LCD text, opaque white paper, RGB bytes.
            bitmap = page.render(
                scale=dpi / 72.0,
                may_draw_forms=True,
                draw_annots=True,
                fill_color=(255, 255, 255, 255),
                force_bitmap_format=pdfium_c.FPDFBitmap_BGR,
                rev_byteorder=True,
            )
            png = os.path.join(out_dir, f"p{number}.png")
            write_png(png, bitmap.width, bitmap.height, bitmap.stride, bitmap.buffer)
            emit("ok", number, png, bitmap.width, bitmap.height)
        except Exception as e:
            emit("fail", number, f"{type(e).__name__}: {e}")


def text(path, out_dir, first, last):
    document = open_document(path, forms=False)
    for number in page_range(document, first, last):
        try:
            text_page = document[number - 1].get_textpage()
            # Only the text inside the page box, which is the text a reader can see.
            content = text_page.get_text_bounded()
            txt = os.path.join(out_dir, f"p{number}.txt")
            with open(txt, "w", encoding="utf-8") as out:
                out.write(content)
            emit("ok", number, txt, text_page.count_chars())
        except Exception as e:
            emit("fail", number, f"{type(e).__name__}: {e}")


def main(argv):
    command = argv[1] if len(argv) > 1 else ""
    if command == "version":
        from pypdfium2.version import PDFIUM_INFO, PYPDFIUM_INFO
        emit("pdfium", PDFIUM_INFO, "pypdfium2", PYPDFIUM_INFO)
    elif command == "pages" and len(argv) == 3:
        emit("pages", len(open_document(argv[2], forms=False)))
    elif command == "render" and len(argv) == 7:
        render(argv[2], argv[3], int(argv[4]), int(argv[5]), int(argv[6]))
    elif command == "text" and len(argv) == 6:
        text(argv[2], argv[3], int(argv[4]), int(argv[5]))
    else:
        sys.stderr.write(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

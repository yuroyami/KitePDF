# Hand-built base documents for the KitePDF editor probes (classic xref, uncompressed).
def build(objs, root=1, extra_trailer=b""):
    out = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    offsets = {}
    for n, body in objs.items():
        offsets[n] = len(out)
        out += f"{n} 0 obj\n".encode() + body + b"\nendobj\n"
    size = max(objs) + 1
    xref = len(out)
    out += f"xref\n0 {size}\n".encode() + b"0000000000 65535 f \n"
    for n in range(1, size):
        out += (f"{offsets[n]:010d} 00000 n \n" if n in offsets else "0000000000 65535 f \n").encode()
    out += f"trailer\n<< /Size {size} /Root {root} 0 R".encode() + extra_trailer + f" >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return bytes(out)

def stream(dict_body, data):
    return b"<< " + dict_body + f" /Length {len(data)} >>\nstream\n".encode() + data + b"\nendstream"

HELV = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"

# 1. A text field form, /DA names Helv.
form = {
    1: b"<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] /DA (/Helv 0 Tf 0 g) /DR << /Font << /Helv 6 0 R >> >> >> >>",
    2: b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    3: b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 300] /Annots [4 0 R] /Contents 5 0 R >>",
    4: b"<< /Type /Annot /Subtype /Widget /FT /Tx /T (Name) /Rect [50 200 350 230] /DA (/Helv 12 Tf 0 g) >>",
    5: stream(b"", b" "),
    6: HELV,
}
open("base_form.pdf", "wb").write(build(form))

# 2. ActualText around the text that will be redacted.
content = (b"/Span << /ActualText (TOPSECRETACTUAL) >> BDC\n"
           b"BT /F1 24 Tf 72 700 Td (Visible words) Tj ET\nEMC\n"
           b"BT /F1 24 Tf 72 100 Td (Footer stays) Tj ET\n")
actual = {
    1: b"<< /Type /Catalog /Pages 2 0 R >>",
    2: b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    3: b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
    4: stream(b"", content),
    5: HELV,
}
open("base_actualtext.pdf", "wb").write(build(actual))

# 3. One form XObject drawn on two pages.
formx = stream(b"/Type /XObject /Subtype /Form /BBox [0 0 612 792] /Resources << /Font << /F1 7 0 R >> >>",
               b"BT /F1 24 Tf 72 700 Td (SHAREDFORMTEXT) Tj ET")
shared = {
    1: b"<< /Type /Catalog /Pages 2 0 R >>",
    2: b"<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
    3: b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /XObject << /Fx 6 0 R >> /Font << /F1 7 0 R >> >> /Contents 5 0 R >>",
    4: b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /XObject << /Fx 6 0 R >> /Font << /F1 7 0 R >> >> /Contents 8 0 R >>",
    5: stream(b"", b"/Fx Do\nBT /F1 24 Tf 72 100 Td (PageOneFooter) Tj ET"),
    6: formx,
    7: HELV,
    8: stream(b"", b"/Fx Do\nBT /F1 24 Tf 72 100 Td (PageTwoFooter) Tj ET"),
}
open("base_sharedform.pdf", "wb").write(build(shared))

# 4. Graft source: page 1 has a link to page 3; page 3 has distinctive content.
graft = {
    1: b"<< /Type /Catalog /Pages 2 0 R >>",
    2: b"<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>",
    3: b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 9 0 R >> >> /Contents 6 0 R /Annots [10 0 R] >>",
    4: b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 9 0 R >> >> /Contents 7 0 R >>",
    5: b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 9 0 R >> >> /Contents 8 0 R >>",
    6: stream(b"", b"BT /F1 24 Tf 72 700 Td (SOURCEPAGEONE) Tj ET"),
    7: stream(b"", b"BT /F1 24 Tf 72 700 Td (SOURCEPAGETWOSECRET) Tj ET"),
    8: stream(b"", b"BT /F1 24 Tf 72 700 Td (SOURCEPAGETHREESECRET) Tj ET"),
    9: HELV,
    10: b"<< /Type /Annot /Subtype /Link /Rect [72 690 300 720] /Border [0 0 0] /Dest [5 0 R /Fit] >>",
}
open("base_graft_source.pdf", "wb").write(build(graft))
target = {
    1: b"<< /Type /Catalog /Pages 2 0 R >>",
    2: b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    3: b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
    4: stream(b"", b"BT /F1 24 Tf 72 700 Td (TARGETPAGE) Tj ET"),
    5: HELV,
}
open("base_graft_target.pdf", "wb").write(build(target))

# 5. Inherited CropBox on the Pages root, two pages.
crop = {
    1: b"<< /Type /Catalog /Pages 2 0 R >>",
    2: b"<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 612 792] /CropBox [50 50 400 500] >>",
    3: b"<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 7 0 R >> >> /Contents 5 0 R >>",
    4: b"<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 7 0 R >> >> /Contents 6 0 R >>",
    5: stream(b"", b"BT /F1 24 Tf 72 400 Td (CropOne) Tj ET"),
    6: stream(b"", b"BT /F1 24 Tf 72 400 Td (CropTwo) Tj ET"),
    7: HELV,
}
open("base_cropbox.pdf", "wb").write(build(crop))

# 6. A direct /AcroForm dictionary in the catalog, with one text field.
direct = {
    1: b"<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] /DA (/Helv 0 Tf 0 g) /NeedAppearances true >> >>",
    2: b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    3: b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 300] /Annots [4 0 R] /Contents 5 0 R >>",
    4: b"<< /Type /Annot /Subtype /Widget /FT /Tx /T (Existing) /Rect [50 200 350 230] /V (kept?) >>",
    5: stream(b"", b" "),
}
open("base_direct_acroform.pdf", "wb").write(build(direct))
print("bases written")

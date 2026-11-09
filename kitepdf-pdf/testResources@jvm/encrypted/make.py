"""Writes the encrypted fixtures of EncryptedFixturesTest with qpdf, through pikepdf (#908).

Run it with a Python that has pikepdf: python3 make.py. Each file has one page that reads
"Encrypted page one", the title "Encrypted fixture" and, for the cleartext-metadata one, an XMP
packet. The user password is "user" and the owner password "owner", except in owner-only.pdf,
whose user password is empty.
"""
import pathlib
import pikepdf

HERE = pathlib.Path(__file__).resolve().parent


def source():
    pdf = pikepdf.new()
    font = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Font, Subtype=pikepdf.Name.Type1, BaseFont=pikepdf.Name.Helvetica))
    page = pdf.add_blank_page(page_size=(300, 200))
    page.Resources = pikepdf.Dictionary(Font=pikepdf.Dictionary(F1=font))
    page.Contents = pdf.make_stream(b"BT /F1 14 Tf 20 100 Td (Encrypted page one) Tj ET")
    pdf.docinfo["/Title"] = "Encrypted fixture"
    pdf.docinfo["/Producer"] = "qpdf " + pikepdf.__libqpdf_version__
    return pdf


def write(name, encryption, object_streams, metadata=False):
    pdf = source()
    if metadata:
        with pdf.open_metadata(set_pikepdf_as_editor=False) as meta:
            meta["dc:title"] = "Encrypted fixture"
    mode = pikepdf.ObjectStreamMode.generate if object_streams else pikepdf.ObjectStreamMode.disable
    pdf.save(HERE / name, encryption=encryption, object_stream_mode=mode, static_id=True)


for objstm in (False, True):
    suffix = "-objstm" if objstm else ""
    write(f"rc4-128{suffix}.pdf", pikepdf.Encryption(user="user", owner="owner", R=3, aes=False, metadata=False), objstm)
    write(f"aes-128{suffix}.pdf", pikepdf.Encryption(user="user", owner="owner", R=4, aes=True), objstm)
    write(f"aes-256{suffix}.pdf", pikepdf.Encryption(user="user", owner="owner", R=6), objstm)
write("aes-128-cleartext-metadata.pdf", pikepdf.Encryption(user="user", owner="owner", R=4, aes=True, metadata=False), False, metadata=True)
write("owner-only.pdf", pikepdf.Encryption(user="", owner="owner", R=4, aes=True), False)

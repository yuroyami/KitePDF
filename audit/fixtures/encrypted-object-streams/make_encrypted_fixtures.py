"""Makes the four fixtures of PDF-PARSE-02 with pikepdf 10: the same one-page document with object
streams on or off and encryption off, RC4/AES R4 or AES-256 R6 (empty user password, owner password
"owner"). KitePDF 0.13.0 opens plain_objstm.pdf and enc_classic_r4.pdf and fails on the two encrypted
files that keep their objects in object streams.

    python3 -m pip install pikepdf
    python3 make_encrypted_fixtures.py
"""
import pikepdf

pdf = pikepdf.new()
pdf.add_blank_page(page_size=(300, 200))
page = pdf.pages[0]
font = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Font, Subtype=pikepdf.Name.Type1, BaseFont=pikepdf.Name.Helvetica))
page.Resources = pikepdf.Dictionary(Font=pikepdf.Dictionary(F1=font))
page.Contents = pdf.make_stream(b"BT /F1 24 Tf 20 100 Td (Hello objstm) Tj ET")
pdf.docinfo["/Title"] = "Encrypted with object streams"
generate = pikepdf.ObjectStreamMode.generate
pdf.save("plain_objstm.pdf", object_stream_mode=generate)
pdf.save("enc_objstm_r6.pdf", encryption=pikepdf.Encryption(user="", owner="owner", R=6), object_stream_mode=generate)
pdf.save("enc_objstm_r4.pdf", encryption=pikepdf.Encryption(user="", owner="owner", R=4), object_stream_mode=generate)
pdf.save("enc_classic_r4.pdf", encryption=pikepdf.Encryption(user="", owner="owner", R=4), object_stream_mode=pikepdf.ObjectStreamMode.disable)

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


# qpdf writes no crypt filter of /Identity, so these two are built here by ISO 32000-1, 7.6.3:
# a V4, R4 file whose /StdCF is AESV2 and whose strings (identity-strings.pdf) or streams
# (identity-streams.pdf) take /Identity and stay as they are (7.6.5, Table 25).
import hashlib
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

PAD = bytes.fromhex("28BF4E5E4E758A4164004E56FFFA01082E2E00B6D0683E802F0CA9FE6453697A")


def rc4(key, data):
    s = list(range(256))
    j = 0
    for i in range(256):
        j = (j + s[i] + key[i % len(key)]) % 256
        s[i], s[j] = s[j], s[i]
    out = bytearray()
    i = j = 0
    for byte in data:
        i = (i + 1) % 256
        j = (j + s[i]) % 256
        s[i], s[j] = s[j], s[i]
        out.append(byte ^ s[(s[i] + s[j]) % 256])
    return bytes(out)


def padded(password):
    return (password.encode("latin-1") + PAD)[:32]


def owner_entry(owner, user):
    """Algorithm 3: /O for R3 and later, a 16-byte key."""
    h = hashlib.md5(padded(owner)).digest()
    for _ in range(50):
        h = hashlib.md5(h).digest()
    out = rc4(h, padded(user))
    for i in range(1, 20):
        out = rc4(bytes(b ^ i for b in h), out)
    return out


def file_key(user, o, p, first_id):
    """Algorithm 2 for R4 with the metadata encrypted."""
    h = hashlib.md5(padded(user) + o + p.to_bytes(4, "little", signed=True) + first_id).digest()
    for _ in range(50):
        h = hashlib.md5(h).digest()
    return h


def user_entry(key, first_id):
    """Algorithm 5: /U for R3 and later."""
    out = rc4(key, hashlib.md5(PAD + first_id).digest())
    for i in range(1, 20):
        out = rc4(bytes(b ^ i for b in key), out)
    return out + bytes(16)


def aes(key, num, data):
    """Algorithm 1 with AESV2: the object key, a fixed IV for a reproducible file, and PKCS #5 padding."""
    k = hashlib.md5(key + num.to_bytes(3, "little") + (0).to_bytes(2, "little") + b"sAlT").digest()
    iv = hashlib.md5(b"iv" + num.to_bytes(4, "little")).digest()
    n = 16 - len(data) % 16
    enc = Cipher(algorithms.AES(k), modes.CBC(iv)).encryptor()
    return iv + enc.update(data + bytes([n]) * n) + enc.finalize()


def write_identity(name, identity_strings):
    first_id = hashlib.md5(name.encode()).digest()
    p = -3904
    o = owner_entry("owner", "user")
    key = file_key("user", o, p, first_id)
    u = user_entry(key, first_id)
    content = b"BT /F1 14 Tf 20 100 Td (Encrypted page one) Tj ET"
    title = b"Encrypted fixture"
    stream = content if not identity_strings else aes(key, 4, content)
    title_obj = b"(" + title + b")" if identity_strings else b"<" + aes(key, 6, title).hex().encode() + b">"
    stm, strf = (b"/StdCF", b"/Identity") if identity_strings else (b"/Identity", b"/StdCF")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Title " + title_obj + b" >>",
        b"<< /Filter /Standard /V 4 /R 4 /Length 128 /CF << /StdCF << /CFM /AESV2 /AuthEvent /DocOpen /Length 16 >> >> "
        + b"/StmF " + stm + b" /StrF " + strf + b" /O <" + o.hex().encode() + b"> /U <" + u.hex().encode() + b"> /P "
        + str(p).encode() + b" >>",
    ]
    out = bytearray(b"%PDF-1.6\n")
    offsets = []
    for i, body in enumerate(objects):
        offsets.append(len(out))
        out += str(i + 1).encode() + b" 0 obj\n" + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 " + str(len(objects) + 1).encode() + b"\n0000000000 65535 f \n"
    for off in offsets:
        out += b"%010d 00000 n \n" % off
    ident = first_id.hex().encode()
    out += b"trailer\n<< /Size " + str(len(objects) + 1).encode() + b" /Root 1 0 R /Info 6 0 R /Encrypt 7 0 R /ID [<" + ident + b"> <" + ident + b">] >>\n"
    out += b"startxref\n" + str(xref).encode() + b"\n%%EOF\n"
    (HERE / name).write_bytes(bytes(out))


write_identity("identity-strings.pdf", True)
write_identity("identity-streams.pdf", False)

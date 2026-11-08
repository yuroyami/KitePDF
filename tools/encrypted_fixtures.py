#!/usr/bin/env python3
"""Writes the encrypted PDFs of kitepdf-pdf's EncryptedFixtures, made by qpdf through pikepdf (#908).

Each fixture is one page that shows its own name in Helvetica, with the same name as the /Info title
and as the XMP dc:title, saved by qpdf with one security handler:

- R3 RC4-128, R4 AES-128 and R6 AES-256, each with a classic cross-reference table and again with a
  cross-reference stream and object streams;
- R4 AES-128 with /EncryptMetadata false;
- R4 and R6 with an owner password and an empty user password;
- R4 and R6 with /StmF /Identity and /StrF /StdCF (#796), which qpdf cannot write: this script encrypts
  their strings itself, by ISO 32000-1, 7.6.3 (algorithms 2, 3 and 5) for R4 and ISO 32000-2, 7.6.4.3
  (algorithms 2.B, 8, 9 and 10) for R6, and qpdf then opens each and reads the title back, or the
  script stops.

The user password is "user" and the owner password "owner". The output is a Kotlin file of Base64
constants, so the tests run on every target. qpdf picks a fresh IV and /ID for each run, so the bytes
differ from run to run; the tests check what the files hold, not their digests.

Usage: python3 tools/encrypted_fixtures.py > kitepdf-pdf/test/io/github/yuroyami/kitepdf/EncryptedFixtures.kt
It needs pikepdf (pip install pikepdf), which bundles qpdf.
"""
import base64
import io
import sys

import hashlib
import os

import pikepdf
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from pikepdf import Dictionary, Encryption, Name, ObjectStreamMode

PAD = bytes.fromhex("28BF4E5E4E758A4164004E56FFFA01082E2E00B6D0683E802F0CA9FE6453697A")


def rc4(key, data):
    s = list(range(256))
    j = 0
    for i in range(256):
        j = (j + s[i] + key[i % len(key)]) % 256
        s[i], s[j] = s[j], s[i]
    out = bytearray()
    i = j = 0
    for b in data:
        i = (i + 1) % 256
        j = (j + s[i]) % 256
        s[i], s[j] = s[j], s[i]
        out.append(b ^ s[(s[i] + s[j]) % 256])
    return bytes(out)


def aes_cbc(key, iv, data):
    enc = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
    return enc.update(data) + enc.finalize()


def hash_2b(password, salt, udata):
    """ISO 32000-2, 7.6.4.3.4, algorithm 2.B."""
    k = hashlib.sha256(password + salt + udata).digest()
    i = 0
    while True:
        e = aes_cbc(k[:16], k[16:32], (password + k + udata) * 64)
        k = (hashlib.sha256, hashlib.sha384, hashlib.sha512)[sum(e[:16]) % 3](e).digest()
        i += 1
        if i >= 64 and e[-1] <= i - 32:
            return k[:32]


def r6_entries(user, owner, p_value):
    """The file key and the /U, /UE, /O, /OE and /Perms of an R6 handler (algorithms 8, 9 and 10)."""
    key = os.urandom(32)
    vsalt, ksalt, ovsalt, oksalt = (os.urandom(8) for _ in range(4))
    u = hash_2b(user, vsalt, b"") + vsalt + ksalt
    ue = aes_cbc(hash_2b(user, ksalt, b""), bytes(16), key)
    o = hash_2b(owner, ovsalt, u) + ovsalt + oksalt
    oe = aes_cbc(hash_2b(owner, oksalt, u), bytes(16), key)
    block = p_value.to_bytes(4, "little", signed=True) + b"\xff\xff\xff\xffTadb" + os.urandom(4)
    enc = Cipher(algorithms.AES(key), modes.ECB()).encryptor()
    perms = enc.update(block) + enc.finalize()
    return key, u, ue, o, oe, perms


def identity_streams_fixture(name, user, owner, revision):
    """R4 or R6 with streams left plain (/StmF /Identity) and strings in AES (/StrF /StdCF)."""
    padded = lambda p: (p.encode() + PAD)[:32]
    file_id = hashlib.md5(name.encode()).digest()
    p_value = -3904
    # Algorithm 3: the owner entry.
    h = hashlib.md5(padded(owner)).digest()
    for _ in range(50):
        h = hashlib.md5(h).digest()
    o = rc4(h, padded(user))
    for i in range(1, 20):
        o = rc4(bytes(b ^ i for b in h), o)
    # Algorithm 2: the file key.
    h = hashlib.md5(padded(user) + o + p_value.to_bytes(4, "little", signed=True) + file_id).digest()
    for _ in range(50):
        h = hashlib.md5(h).digest()
    key = h
    # Algorithm 5: the user entry.
    u = rc4(key, hashlib.md5(PAD + file_id).digest())
    for i in range(1, 20):
        u = rc4(bytes(b ^ i for b in key), u)
    u += bytes(16)
    if revision == 6:
        key, u, ue, o, oe, perms = r6_entries(user.encode(), owner.encode(), p_value)

    def string(num, text):
        # AESV2 keys each object (algorithm 1); AESV3 uses the file key itself.
        k = key if revision == 6 else hashlib.md5(key + num.to_bytes(3, "little") + (0).to_bytes(2, "little") + b"sAlT").digest()
        data = text.encode()
        pad = 16 - len(data) % 16
        iv = os.urandom(16)
        enc = Cipher(algorithms.AES(k), modes.CBC(iv)).encryptor()
        return "<" + (iv + enc.update(data + bytes([pad]) * pad) + enc.finalize()).hex() + ">"

    content = ("BT /F1 20 Tf 40 700 Td (" + name + ") Tj ET").encode()
    xmp = ('<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
           '<rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title><rdf:Alt>'
           '<rdf:li xml:lang="x-default">' + name + '</rdf:li></rdf:Alt></dc:title></rdf:Description></rdf:RDF></x:xmpmeta>').encode()
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R /Metadata 7 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        ("<< /Title " + string(6, name) + " >>").encode(),
        b"<< /Type /Metadata /Subtype /XML /Length %d >>\nstream\n" % len(xmp) + xmp + b"\nendstream",
        ("<< /Filter /Standard /V 4 /R 4 /Length 128 /P %d /O <%s> /U <%s> "
         "/CF << /StdCF << /CFM /AESV2 /AuthEvent /DocOpen /Length 16 >> >> /StmF /Identity /StrF /StdCF >>"
         % (p_value, o.hex(), u.hex())).encode() if revision == 4 else
        ("<< /Filter /Standard /V 5 /R 6 /Length 256 /P %d /O <%s> /U <%s> /OE <%s> /UE <%s> /Perms <%s> "
         "/CF << /StdCF << /CFM /AESV3 /AuthEvent /DocOpen /Length 32 >> >> /StmF /Identity /StrF /StdCF >>"
         % (p_value, o.hex(), u.hex(), oe.hex(), ue.hex(), perms.hex())).encode(),
    ]
    out = bytearray(b"%PDF-1.6\n")
    offsets = []
    for i, body in enumerate(objects):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % (i + 1) + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    for off in offsets:
        out += b"%010d 00000 n \n" % off
    out += ("trailer\n<< /Size %d /Root 1 0 R /Info 6 0 R /Encrypt 8 0 R /ID [<%s> <%s>] >>\nstartxref\n%d\n%%%%EOF\n"
            % (len(objects) + 1, file_id.hex(), file_id.hex(), xref)).encode()
    data = bytes(out)
    # qpdf is the oracle: it must open the file with each password and read the title and the page.
    for password in (user, owner):
        with pikepdf.open(io.BytesIO(data), password=password) as check:
            assert str(check.docinfo["/Title"]) == name, "qpdf reads another title"
            assert name.encode() in check.pages[0].Contents.read_bytes(), "qpdf reads another page"
    return data


def fixture(name, encryption, object_streams):
    pdf = pikepdf.new()
    font = pdf.make_indirect(Dictionary(Type=Name.Font, Subtype=Name.Type1, BaseFont=Name.Helvetica))
    content = pdf.make_stream(b"BT /F1 20 Tf 40 700 Td (" + name.encode() + b") Tj ET")
    pdf.pages.append(pikepdf.Page(Dictionary(
        Type=Name.Page, MediaBox=[0, 0, 612, 792], Resources=Dictionary(Font=Dictionary(F1=font)), Contents=content,
    )))
    pdf.docinfo["/Title"] = name
    with pdf.open_metadata(set_pikepdf_as_editor=False) as meta:
        meta["dc:title"] = name
    out = io.BytesIO()
    mode = ObjectStreamMode.generate if object_streams else ObjectStreamMode.disable
    pdf.save(out, encryption=encryption, object_stream_mode=mode, qdf=False)
    return out.getvalue()


def main():
    # R3 has no /EncryptMetadata: pikepdf asks for metadata=False, and qpdf encrypts the metadata stream all the same.
    rc4 = dict(R=3, aes=False, metadata=False)
    aes128 = dict(R=4, aes=True)
    aes256 = dict(R=6)
    cases = []
    for label, kw in (("R3 RC4-128", rc4), ("R4 AES-128", aes128), ("R6 AES-256", aes256)):
        for streams in (False, True):
            name = label + (" object streams" if streams else " classic")
            cases.append((name, Encryption(user="user", owner="owner", **kw), streams))
    cases.append(("R4 AES-128 plain metadata", Encryption(user="user", owner="owner", metadata=False, **aes128), False))
    cases.append(("R4 AES-128 owner only", Encryption(user="", owner="owner", **aes128), False))
    cases.append(("R6 AES-256 owner only", Encryption(user="", owner="owner", **aes256), True))

    made = [(name, fixture(name, enc, streams), streams) for name, enc, streams in cases]
    for name, revision in (("R4 AES-128 identity streams", 4), ("R6 AES-256 identity streams", 6)):
        made.append((name, identity_streams_fixture(name, "user", "owner", revision), False))

    w = sys.stdout.write
    w("package io.github.yuroyami.kitepdf\n\n")
    w("import kotlin.io.encoding.Base64\nimport kotlin.io.encoding.ExperimentalEncodingApi\n\n")
    w("/**\n * Encrypted PDFs that qpdf %s wrote, through pikepdf %s (#908), and one with /StmF /Identity that\n" % (pikepdf.__libqpdf_version__, pikepdf.__version__))
    w(" * qpdf cannot write but opens (#796). Written by\n")
    w(" * `tools/encrypted_fixtures.py`; regenerate them with it, not by hand. Each shows its [Fixture.name] on its\n")
    w(" * page, as its /Info title and as its XMP dc:title. The user password is \"user\", or empty where\n")
    w(" * [Fixture.userPassword] is, and the owner password \"owner\".\n */\n")
    w("@OptIn(ExperimentalEncodingApi::class)\ninternal object EncryptedFixtures {\n\n")
    w("    class Fixture(val name: String, val userPassword: String, val revision: Int, val objectStreams: Boolean, val encryptsMetadata: Boolean, base64: String) {\n")
    w("        val bytes: ByteArray by lazy { Base64.decode(base64) }\n    }\n\n")
    w("    val all: List<Fixture> = listOf(\n")
    for name, raw, streams in made:
        data = base64.b64encode(raw).decode()
        revision = int(name[1])
        user = "" if "owner only" in name else "user"
        meta = "false" if "plain metadata" in name else "true"
        w('        Fixture("%s", "%s", %d, %s, %s,\n' % (name, user, revision, "true" if streams else "false", meta))
        w('            "%s"),\n' % data)
    w("    )\n}\n")


if __name__ == "__main__":
    main()

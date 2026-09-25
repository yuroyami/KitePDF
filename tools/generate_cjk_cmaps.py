#!/usr/bin/env python3
"""Writes PredefinedCMapData.kt and CidUnicodeData.kt from the Adobe CMap resources in MuPDF.

    python3 tools/generate_cjk_cmaps.py

The input is mupdf-master/resources/cmaps, which holds Adobe's cmap-resources
(BSD-3-Clause). PredefinedCMapData.kt gets every predefined CMap that maps codes to
CIDs, except Identity-H and Identity-V, which the code handles. CidUnicodeData.kt gets
the four Adobe-*-UCS2 CMaps, which map CIDs to Unicode.

Each table is raw DEFLATE of a list of unsigned LEB128 numbers, in Base64. A
difference that can be negative is zigzag-coded. The script decodes every table it
writes and compares it with the source before it writes anything.
"""

import base64
import os
import re
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CMAPS = os.path.join(ROOT, "mupdf-master", "resources", "cmaps")
FONT_DIR = os.path.join(ROOT, "kitepdf-core", "src", "commonMain", "kotlin", "io", "github", "yuroyami",
                        "kitepdf", "core", "font")
ORDERINGS = ["Japan1", "GB1", "CNS1", "Korea1"]


def read(name):
    return open(os.path.join(CMAPS, name), encoding="latin-1").read()


def parse_cid_cmap(name):
    """The WMode, usecmap, ordering, codespaces, single codes and ranges of a code-to-CID CMap."""
    t = read(name)
    wmode = re.search(r"/WMode\s+(\d+)", t)
    use = re.search(r"/([A-Za-z0-9-]+)\s+usecmap", t)
    ordering = re.search(r"/Ordering\s*\(([^)]*)\)", t)
    codespaces = []
    for blk in re.findall(r"begincodespacerange(.*?)endcodespacerange", t, re.S):
        for lo, hi in re.findall(r"<([0-9a-fA-F]+)>\s*<([0-9a-fA-F]+)>", blk):
            assert len(lo) == len(hi), name
            codespaces.append((bytes.fromhex(lo), bytes.fromhex(hi)))
    chars = []
    for blk in re.findall(r"begincidchar(.*?)endcidchar", t, re.S):
        chars += [(int(c, 16), int(cid)) for c, cid in re.findall(r"<([0-9a-fA-F]+)>\s+(\d+)", blk)]
    ranges = []
    for blk in re.findall(r"begincidrange(.*?)endcidrange", t, re.S):
        ranges += [(int(lo, 16), int(hi, 16), int(cid))
                   for lo, hi, cid in re.findall(r"<([0-9a-fA-F]+)>\s*<([0-9a-fA-F]+)>\s+(\d+)", blk)]
    assert "bfchar" not in t and "bfrange" not in t, name
    return {
        "wmode": int(wmode.group(1)) if wmode else 0,
        "usecmap": use.group(1) if use else None,
        "ordering": ordering.group(1) if ordering and ordering.group(1) in ORDERINGS else None,
        "codespaces": codespaces,
        "chars": sorted(chars),
        "ranges": sorted(ranges),
    }


def parse_unicode_cmap(ordering):
    """CID to UTF-16 code units, from Adobe-<ordering>-UCS2."""
    t = read(f"Adobe-{ordering}-UCS2")
    table = {}

    def units(h):
        return [int(h[i:i + 4], 16) for i in range(0, len(h), 4)]

    for blk in re.findall(r"beginbfchar(.*?)endbfchar", t, re.S):
        for cid, uni in re.findall(r"<([0-9a-fA-F]+)>\s*<([0-9a-fA-F]+)>", blk):
            table[int(cid, 16)] = units(uni)
    for blk in re.findall(r"beginbfrange(.*?)endbfrange", t, re.S):
        for lo, hi, dst in re.findall(r"<([0-9a-fA-F]+)>\s*<([0-9a-fA-F]+)>\s*(<[0-9a-fA-F]+>|\[[^\]]*\])", blk):
            lo, hi = int(lo, 16), int(hi, 16)
            if dst.startswith("<"):
                first = units(dst[1:-1])
                for i in range(hi - lo + 1):
                    table[lo + i] = first[:-1] + [first[-1] + i]
            else:
                for i, uni in enumerate(re.findall(r"<([0-9a-fA-F]+)>", dst)):
                    table[lo + i] = units(uni)
    assert "cidchar" not in t and "cidrange" not in t, ordering
    return table


def zigzag(n):
    return n << 1 if n >= 0 else ((-n) << 1) - 1


def unzigzag(n):
    return n >> 1 if n & 1 == 0 else -((n + 1) >> 1)


def leb(values):
    out = bytearray()
    for n in values:
        assert n >= 0
        while True:
            b = n & 0x7F
            n >>= 7
            if n:
                out.append(b | 0x80)
            else:
                out.append(b)
                break
    return bytes(out)


def unleb(data):
    values, n, shift = [], 0, 0
    for b in data:
        n |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            values.append(n)
            n, shift = 0, 0
    assert shift == 0
    return values


def pack(values):
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    return base64.b64encode(c.compress(leb(values)) + c.flush()).decode()


def unpack(text):
    return unleb(zlib.decompress(base64.b64decode(text), -15))


def encode_cid_cmap(m):
    """codespace count, then per codespace its width and bytes; the single codes; the ranges."""
    v = [len(m["codespaces"])]
    for lo, hi in m["codespaces"]:
        v += [len(lo)] + list(lo) + list(hi)
    v.append(len(m["chars"]))
    code = cid = 0
    for c, i in m["chars"]:
        v += [c - code, zigzag(i - cid)]
        code, cid = c, i
    v.append(len(m["ranges"]))
    lo_prev = next_cid = 0
    for lo, hi, i in m["ranges"]:
        v += [lo - lo_prev, hi - lo, zigzag(i - next_cid)]
        lo_prev, next_cid = lo, i + hi - lo + 1
    return pack(v)


def decode_cid_cmap(text):
    v = iter(unpack(text))
    codespaces = []
    for _ in range(next(v)):
        w = next(v)
        codespaces.append((bytes(next(v) for _ in range(w)), bytes(next(v) for _ in range(w))))
    chars, code, cid = [], 0, 0
    for _ in range(next(v)):
        code += next(v)
        cid += unzigzag(next(v))
        chars.append((code, cid))
    ranges, lo, next_cid = [], 0, 0
    for _ in range(next(v)):
        lo += next(v)
        hi = lo + next(v)
        cid = next_cid + unzigzag(next(v))
        ranges.append((lo, hi, cid))
        next_cid = cid + hi - lo + 1
    assert next(v, None) is None
    return codespaces, chars, ranges


def encode_unicode(table):
    """count, then per CID the gap from the previous CID, the unit count and each unit's difference."""
    v = [len(table)]
    prev_cid, prev_unit = -1, 0
    for cid in sorted(table):
        units = table[cid]
        v += [cid - prev_cid - 1, len(units)]
        for u in units:
            v.append(zigzag(u - prev_unit))
            prev_unit = u
        prev_cid = cid
    return pack(v)


def decode_unicode(text):
    v = iter(unpack(text))
    table, cid, unit = {}, -1, 0
    for _ in range(next(v)):
        cid += next(v) + 1
        units = []
        for _ in range(next(v)):
            unit += unzigzag(next(v))
            units.append(unit)
        table[cid] = units
    assert next(v, None) is None
    return table


def kotlin_string(text, indent):
    lines = [text[i:i + 110] for i in range(0, len(text), 110)] or [""]
    pad = " " * indent
    return "(\n" + " +\n".join(f'{pad}    "{l}"' for l in lines) + f"\n{pad})"


def license_header():
    t = read("Adobe-Japan1-UCS2")
    lines = [l[len("%%Copyright:"):].strip() for l in t.splitlines() if l.startswith("%%Copyright:")]
    lines = [l for l in lines if not (l and set(l) <= {"-"})]
    return [(" * " + l).rstrip() for l in lines]


def write_cid_cmaps():
    # The Adobe-*-UCS2 CMaps map CIDs to Unicode, not codes to CIDs.
    names = sorted(n for n in os.listdir(CMAPS) if n not in ("Identity-H", "Identity-V") and not n.startswith("Adobe-"))
    entries = []
    for name in names:
        m = parse_cid_cmap(name)
        blob = encode_cid_cmap(m)
        assert decode_cid_cmap(blob) == (m["codespaces"], m["chars"], m["ranges"]), name
        entries.append((name, m, blob))
    out = ["// Generated by tools/generate_cjk_cmaps.py from the Adobe CMap resources in MuPDF. Do not hand-edit.",
           "package io.github.yuroyami.kitepdf.core.font", "", "/*",
           " * Adobe cmap-resources.", " *"]
    out += license_header()
    out += [" */", "",
            "/**",
            " * The predefined CMaps that map character codes to CIDs (ISO 32000-1, 9.7.5.2), from",
            " * Adobe's cmap-resources. Identity-H and Identity-V are not here: the code handles them.",
            " *",
            " * Each CMap names the CMap it builds on with `usecmap`, so a shared base such as",
            " * UniJIS-X is stored once. [Entry.ordering] is the Adobe character collection of the",
            " * CMap, or null for a base that does not name one. Each [Entry.table] is raw DEFLATE",
            " * of unsigned LEB128 numbers, in Base64: the codespace ranges, then the single codes,",
            " * then the code ranges, each as a difference from the one before. [TableCMapReader]",
            " * reads them.",
            " */",
            "internal object PredefinedCMapData {",
            "",
            "    class Entry(val wmode: Int, val usecmap: String?, val ordering: String?, val table: String)",
            "",
            "    val entries: Map<String, Entry> by lazy {",
            "        mapOf("]
    for i, (name, m, _) in enumerate(entries):
        use = f'"{m["usecmap"]}"' if m["usecmap"] else "null"
        ordering = f'"{m["ordering"]}"' if m["ordering"] else "null"
        out.append(f'            "{name}" to Entry({m["wmode"]}, {use}, {ordering}, T{i}),')
    out += ["        )", "    }", ""]
    for i, (name, _, blob) in enumerate(entries):
        out.append(f"    // {name}")
        out.append(f"    private val T{i} = " + kotlin_string(blob, 4))
        out.append("")
    out[-1] = "}"
    path = os.path.join(FONT_DIR, "PredefinedCMapData.kt")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
    print(f"wrote {path}: {len(entries)} CMaps, {sum(len(b) for *_, b in entries)} Base64 characters")


def write_unicode_tables():
    tables = []
    for ordering in ORDERINGS:
        table = parse_unicode_cmap(ordering)
        blob = encode_unicode(table)
        assert decode_unicode(blob) == table, ordering
        tables.append((ordering, table, blob))
    out = ["// Generated by tools/generate_cjk_cmaps.py from the Adobe CMap resources in MuPDF. Do not hand-edit.",
           "package io.github.yuroyami.kitepdf.core.font", "", "/*",
           " * Adobe cmap-resources.", " *"]
    out += license_header()
    out += [" */", "",
            "/**",
            " * The Unicode text of each CID in the four Adobe CJK character collections, from the",
            " * Adobe-Japan1-UCS2, Adobe-GB1-UCS2, Adobe-CNS1-UCS2 and Adobe-Korea1-UCS2 CMaps.",
            " * ISO 32000-1, 9.10.2 uses them for a composite font without a ToUnicode map.",
            " *",
            " * Each table is raw DEFLATE of unsigned LEB128 numbers, in Base64: the entry count,",
            " * then for each CID its gap from the CID before, its number of UTF-16 units and each",
            " * unit as a difference from the unit before. [CidUnicode] reads them.",
            " */",
            "internal object CidUnicodeData {",
            "",
            "    val tables: Map<String, String> by lazy {",
            "        mapOf("]
    for ordering, _, _ in tables:
        out.append(f'            "{ordering}" to {ordering.upper()},')
    out += ["        )", "    }", ""]
    for ordering, _, blob in tables:
        out.append(f"    private val {ordering.upper()} = " + kotlin_string(blob, 4))
        out.append("")
    out[-1] = "}"
    path = os.path.join(FONT_DIR, "CidUnicodeData.kt")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
    print(f"wrote {path}: {', '.join(f'{o} {len(t)} CIDs' for o, t, _ in tables)}, "
          f"{sum(len(b) for *_, b in tables)} Base64 characters")


if __name__ == "__main__":
    write_cid_cmaps()
    write_unicode_tables()

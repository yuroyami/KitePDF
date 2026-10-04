#!/usr/bin/env python3
"""Writes IdnaData.kt from the Unicode 17 files that UTS #46 and NFC need (#520).

    mkdir -p ~/.cache/kitepdf/ucd-17 && cd ~/.cache/kitepdf/ucd-17
    curl -O https://www.unicode.org/Public/17.0.0/idna/IdnaMappingTable.txt
    curl -O https://www.unicode.org/Public/17.0.0/ucd/UnicodeData.txt
    curl -O https://www.unicode.org/Public/17.0.0/ucd/DerivedNormalizationProps.txt
    curl -O https://www.unicode.org/Public/17.0.0/ucd/extracted/DerivedCombiningClass.txt
    curl -O https://www.unicode.org/Public/17.0.0/ucd/extracted/DerivedJoiningType.txt
    python3 tools/generate_idna_tables.py [folder]

Each table is a string of numbers in base64 VLQ, as source maps write them: five bits to a
digit of A-Z, a-z, 0-9, + and /, with 32 added to each digit but the last of a number. ASCII
keeps the constants small in a class file and the same on every Kotlin target.
"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "kitepdf-epub", "src", "commonMain", "kotlin", "io", "github", "yuroyami",
                      "kitepdf", "epub", "script", "IdnaData.kt")
VERSION = "17.0.0"
DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
# A JVM class file holds no string constant over 65535 bytes.
CHUNK = 60000


def vlq(n):
    assert n >= 0
    out = ""
    while True:
        d = n & 31
        n >>= 5
        if n:
            out += DIGITS[d | 32]
        else:
            return out + DIGITS[d]


def zigzag(n):
    return n * 2 if n >= 0 else -n * 2 - 1


def rows(path):
    """The data lines of a UCD file: its fields, split at ; and trimmed, without the comment."""
    for line in open(path, encoding="utf-8"):
        line = line.split("#")[0].strip()
        if line:
            yield [f.strip() for f in line.split(";")]


def span(field):
    first, _, last = field.partition("..")
    return int(first, 16), int(last or first, 16)


def check_version(path):
    head = open(path, encoding="utf-8").read(400)
    assert VERSION in head or path.endswith("UnicodeData.txt"), f"{path} is not Unicode {VERSION}"


# The kinds of run of the IDNA table.
VALID, IGNORED, DISALLOWED, MAPPED, SHIFTED, ALTERNATING, DEVIATION = range(7)


def idna_table(folder):
    """
    Each run of IdnaMappingTable.txt in code point order: the distance of its first code point
    from the last run's, then its kind:

    - 0 valid, 1 ignored, 2 disallowed: the whole run has the status.
    - 3 mapped: each code point maps to one string, given as its index in the pool.
    - 4 shifted: each code point maps to the one at a distance from it, given in zigzag.
    - 5 alternating: the first code point and every second one after it map as a shifted run
      does, the others are valid, as Latin and Cyrillic pair their capitals and small letters.
    - 6 deviation: each code point is a deviation whose mapping is in the pool.

    The pool holds each mapping as its length, then the zigzag distance of each code point from
    the one before it.
    """
    path = os.path.join(folder, "IdnaMappingTable.txt")
    check_version(path)
    status = [None] * 0x110000
    mapping = [()] * 0x110000
    for f in rows(path):
        a, b = span(f[0])
        m = tuple(int(x, 16) for x in f[2].split()) if len(f) > 2 and f[2] else ()
        for cp in range(a, b + 1):
            assert status[cp] is None, "two rows for one code point"
            status[cp] = f[1]
            mapping[cp] = m
    assert None not in status, "the table leaves a gap"

    def shift(cp):
        """The distance a code point maps by, or None when it does not map to one code point."""
        if status[cp] == "mapped" and len(mapping[cp]) == 1:
            return mapping[cp][0] - cp
        return None

    pool = {}
    pool_text = []
    out = []
    prev = 0
    cp = 0
    while cp < 0x110000:
        st = status[cp]
        d = shift(cp)
        if d is not None:
            n = 1
            while cp + n < 0x110000 and shift(cp + n) == d:
                n += 1
            pairs = 0
            while cp + 2 * pairs + 1 < 0x110000 and shift(cp + 2 * pairs) == d and status[cp + 2 * pairs + 1] == "valid":
                pairs += 1
            if pairs >= 2 and 2 * pairs > n:
                # An alternating run ends on its last valid code point, unless the pattern goes on with a mapped one.
                length = 2 * pairs + (1 if cp + 2 * pairs < 0x110000 and shift(cp + 2 * pairs) == d else 0)
                kind, value = ALTERNATING, zigzag(d)
            else:
                length, kind, value = n, SHIFTED, zigzag(d)
        elif st in ("mapped", "deviation"):
            m = mapping[cp]
            length = 1
            while cp + length < 0x110000 and status[cp + length] == st and mapping[cp + length] == m and shift(cp + length) is None:
                length += 1
            if m not in pool:
                pool[m] = len(pool)
                text = vlq(len(m))
                last = 0
                for x in m:
                    text += vlq(zigzag(x - last))
                    last = x
                pool_text.append(text)
            kind, value = (MAPPED if st == "mapped" else DEVIATION), pool[m]
        else:
            length = 1
            while cp + length < 0x110000 and status[cp + length] == st:
                length += 1
            kind, value = {"valid": VALID, "ignored": IGNORED, "disallowed": DISALLOWED}[st], None
        out.append(vlq(cp - prev) + vlq(kind) + ("" if value is None else vlq(value)))
        prev = cp
        cp += length
    return "".join(out), "".join(pool_text), len(out), len(pool)


def unicode_data(folder):
    path = os.path.join(folder, "UnicodeData.txt")
    data = {}
    for f in rows(path):
        data[int(f[0], 16)] = f
    return data


def combining_classes(folder):
    """Each run of code points of one nonzero Canonical_Combining_Class: its distance from the end of the last run, its length less one, its class."""
    path = os.path.join(folder, "DerivedCombiningClass.txt")
    check_version(path)
    spans = []
    for f in rows(path):
        a, b = span(f[0])
        ccc = int(f[1])
        if ccc:
            spans.append((a, b, ccc))
    spans.sort()
    merged = []
    for a, b, c in spans:
        if merged and merged[-1][2] == c and merged[-1][1] + 1 == a:
            merged[-1] = (merged[-1][0], b, c)
        else:
            merged.append((a, b, c))
    out = []
    end = 0
    for a, b, c in merged:
        out.append(vlq(a - end) + vlq(b - a) + vlq(c))
        end = b + 1
    return "".join(out), len(merged), {cp: c for a, b, c in merged for cp in range(a, b + 1)}


def decompositions(folder, data):
    """
    Each canonical decomposition of UnicodeData.txt in code point order: the distance of its code
    point from the last one's, then its length times two, plus one when the code point is a
    Full_Composition_Exclusion, then the zigzag distance of each part from the code point.
    """
    path = os.path.join(folder, "DerivedNormalizationProps.txt")
    check_version(path)
    excluded = set()
    for f in rows(path):
        if f[1] == "Full_Composition_Exclusion":
            a, b = span(f[0])
            excluded.update(range(a, b + 1))
    out = []
    prev = 0
    count = 0
    for cp in sorted(data):
        field = data[cp][5]
        if not field or field.startswith("<"):
            continue
        parts = [int(x, 16) for x in field.split()]
        assert 1 <= len(parts) <= 2
        out.append(vlq(cp - prev) + vlq(len(parts) * 2 + (1 if cp in excluded else 0)) + "".join(vlq(zigzag(p - cp)) for p in parts))
        prev = cp
        count += 1
    return "".join(out), count


def marks(data):
    """Each run of General_Category Mark (Mn, Mc, Me): its distance from the end of the last run and its length less one."""
    cps = sorted(cp for cp, f in data.items() if f[2] in ("Mn", "Mc", "Me"))
    assert not any(data[cp][1].endswith("First>") for cp in cps), "a mark range in First and Last lines"
    runs = []
    for cp in cps:
        if runs and runs[-1][1] + 1 == cp:
            runs[-1][1] = cp
        else:
            runs.append([cp, cp])
    out = []
    end = 0
    for a, b in runs:
        out.append(vlq(a - end) + vlq(b - a))
        end = b + 1
    return "".join(out), len(runs)


JOINING = {"L": 1, "R": 2, "D": 3, "T": 4, "C": 5}


def joining_types(folder):
    """Each run of one Joining_Type but U: its distance from the end of the last run, its length less one, its type (L 1, R 2, D 3, T 4, C 5)."""
    path = os.path.join(folder, "DerivedJoiningType.txt")
    check_version(path)
    spans = []
    for f in rows(path):
        a, b = span(f[0])
        if f[1] != "U":
            spans.append((a, b, JOINING[f[1]]))
    spans.sort()
    merged = []
    for a, b, t in spans:
        if merged and merged[-1][2] == t and merged[-1][1] + 1 == a:
            merged[-1] = (merged[-1][0], b, t)
        else:
            merged.append((a, b, t))
    out = []
    end = 0
    for a, b, t in merged:
        out.append(vlq(a - end) + vlq(b - a) + vlq(t))
        end = b + 1
    return "".join(out), len(merged)


def constant(name, text, doc):
    """A Kotlin val joining the string in chunks a class file can hold."""
    chunks = [text[i:i + CHUNK] for i in range(0, len(text), CHUNK)] or [""]
    lines = [f"    /** {doc} */"]
    if len(chunks) == 1:
        lines.append(f'    const val {name}: String = "{chunks[0]}"')
    else:
        parts = []
        for i, c in enumerate(chunks):
            lines.append(f'    private const val {name}_{i}: String = "{c}"')
            parts.append(f"{name}_{i}")
        lines.append(f"    val {name}: String get() = {' + '.join(parts)}")
    return lines


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.cache/kitepdf/ucd-17")
    idna, pool, idna_runs, pool_size = idna_table(folder)
    data = unicode_data(folder)
    ccc, ccc_runs, _ = combining_classes(folder)
    decomp, decomp_count = decompositions(folder, data)
    mark, mark_runs = marks(data)
    joining, joining_runs = joining_types(folder)
    out = [
        "// Generated by tools/generate_idna_tables.py from the Unicode 17 data files. Do not hand-edit.",
        "package io.github.yuroyami.kitepdf.epub.script",
        "",
        "/**",
        f" * The Unicode {VERSION} tables of UTS #46 and of NFC, for the host names of [WhatwgUrl] (#520): the",
        " * IDNA mapping table, the canonical decompositions and combining classes, the combining marks and",
        " * the joining types. Each is a string of numbers in base64 VLQ, as source maps write them; the",
        " * generator says what each number is. [Idna] and [Nfc] decode them on first use.",
        " */",
        "internal object IdnaData {",
        "",
    ]
    out += constant("MAPPING", idna, f"The {idna_runs} runs of IdnaMappingTable.txt.")
    out += [""]
    out += constant("MAPPING_POOL", pool, f"The {pool_size} mappings of more than one code point, or of a range.")
    out += [""]
    out += constant("COMBINING_CLASSES", ccc, f"The {ccc_runs} runs of a nonzero Canonical_Combining_Class.")
    out += [""]
    out += constant("DECOMPOSITIONS", decomp, f"The {decomp_count} canonical decompositions, with the Full_Composition_Exclusion of each.")
    out += [""]
    out += constant("MARKS", mark, f"The {mark_runs} runs of General_Category Mark.")
    out += [""]
    out += constant("JOINING_TYPES", joining, f"The {joining_runs} runs of a Joining_Type other than U.")
    out += ["}", ""]
    with open(TARGET, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    sizes = {"mapping": len(idna), "pool": len(pool), "ccc": len(ccc), "decompositions": len(decomp), "marks": len(mark), "joining": len(joining)}
    print(TARGET, sizes, sum(sizes.values()))


if __name__ == "__main__":
    main()

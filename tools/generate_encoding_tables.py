#!/usr/bin/env python3
"""Writes EncodingData.kt from the files of the Encoding Standard (#532).

    git clone https://github.com/whatwg/encoding ~/.cache/kitepdf/whatwg-encoding
    python3 tools/generate_encoding_tables.py [folder]

The tables in the repository are from commit a985b62a9b45c17da3e17a9f0a0b4e30c34c4a8a of it, and
each index names the identifier its file gives. WhatwgEncodingTest checks every pointer of every
index against the files in that folder when it is there.

The tables are what the decoders need: the labels of every encoding, the index of each
single-byte encoding, the indexes of the CJK decoders, and the ranges of gb18030. Each is a
string of numbers in base64 VLQ, as IdnaData's are: five bits to a digit of A-Z, a-z, 0-9, + and
/, with 32 added to each digit but the last of a number. The encoders of the legacy encodings are
not here, as a script reaches only the one of UTF-8.
"""

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "kitepdf-epub", "src", "commonMain", "kotlin", "io", "github", "yuroyami",
                      "kitepdf", "epub", "script", "EncodingData.kt")
DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
# A JVM class file holds no string constant over 65535 bytes.
CHUNK = 60000
# The indexes the decoders of the CJK encodings read.
MULTI_BYTE = ["big5", "euc-kr", "gb18030", "jis0208", "jis0212"]
# ISO-8859-8-I decodes with the index of ISO-8859-8, as the standard's table of single-byte
# encodings says; the two differ only in the direction a page takes from them.
INDEX_OF = {"iso-8859-8-i": "iso-8859-8"}


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


def index(folder, name):
    """An index file as its pointers and code points, in pointer order."""
    pairs = []
    for line in open(os.path.join(folder, f"index-{name}.txt"), encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        fields = line.split("\t")
        pairs.append((int(fields[0]), int(fields[1], 16)))
    pairs.sort()
    return pairs


def identifier(folder, name):
    for line in open(os.path.join(folder, f"index-{name}.txt"), encoding="utf-8"):
        m = re.match(r"# Identifier: ([0-9a-f]+)", line)
        if m:
            return m.group(1)
    raise ValueError(name)


def labels(encodings):
    """Each encoding as its name, then its labels, all separated by spaces, one encoding to a line."""
    return "\n".join(" ".join([e["name"]] + e["labels"]) for group in encodings for e in group["encodings"])


def single_byte(folder, name):
    """
    The 128 code points of pointers 0 to 127, each as the zigzag distance from the code point
    before it. A pointer the index leaves out is -1, which moves the code point before it nowhere.
    """
    table = dict(index(folder, name))
    out, prev = [], 0
    for pointer in range(128):
        cp = table.get(pointer, -1)
        out.append(vlq(zigzag(cp - prev)))
        if cp >= 0:
            prev = cp
    return "".join(out)


def multi_byte(folder, name):
    """
    The count of pointers the decoder sizes its array for, then each run of consecutive pointers
    the index has: how many pointers it skips after the run before it, how long it is, and the
    zigzag distance of each of its code points from the one before it.
    """
    pairs = index(folder, name)
    runs = []
    for pointer, cp in pairs:
        if runs and runs[-1][-1][0] == pointer - 1:
            runs[-1].append((pointer, cp))
        else:
            runs.append([(pointer, cp)])
    out = [vlq(pairs[-1][0] + 1)]
    prev_pointer, prev_cp = -1, 0
    for run in runs:
        out.append(vlq(run[0][0] - prev_pointer - 1) + vlq(len(run)))
        for _, cp in run:
            out.append(vlq(zigzag(cp - prev_cp)))
            prev_cp = cp
        prev_pointer = run[-1][0]
    return "".join(out)


def ranges(folder):
    """The gb18030 ranges: each pointer and code point as its distance from the pair before it."""
    out, prev_pointer, prev_cp = [], 0, 0
    for pointer, cp in index(folder, "gb18030-ranges"):
        out.append(vlq(pointer - prev_pointer) + vlq(cp - prev_cp))
        prev_pointer, prev_cp = pointer, cp
    return "".join(out)


def kotlin_string(text):
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("\n", "\\n") + '"'


def constant(name, text, doc):
    parts = [text[i:i + CHUNK] for i in range(0, len(text), CHUNK)] or [""]
    if len(parts) == 1:
        return f"/** {doc} */\ninternal const val {name}: String = {kotlin_string(parts[0])}\n"
    names = [f"{name}_{i}" for i in range(len(parts))]
    out = f"/** {doc} */\ninternal val {name}: String get() = " + " + ".join(names) + "\n"
    for n, part in zip(names, parts):
        out += f"\nprivate const val {n}: String = {kotlin_string(part)}\n"
    return out


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.cache/kitepdf/whatwg-encoding")
    encodings = json.load(open(os.path.join(folder, "encodings.json"), encoding="utf-8"))
    singles = [e["name"] for group in encodings if group["heading"] == "Legacy single-byte encodings"
               for e in group["encodings"]]
    sections = [constant("ENCODING_LABELS", labels(encodings),
                         "Each encoding of the standard, one to a line: its name, then every label that names it.")]
    single_text = "\n".join(name + " " + single_byte(folder, INDEX_OF.get(name.lower(), name.lower())) for name in singles)
    sections.append(constant("SINGLE_BYTE_INDEXES", single_text,
                             "The index of each single-byte encoding, one to a line: its name, then its table."))
    for name in MULTI_BYTE:
        sections.append(constant("INDEX_" + name.upper().replace("-", "_"), multi_byte(folder, name),
                                 f"index-{name}.txt, identifier {identifier(folder, name)[:16]}."))
    sections.append(constant("GB18030_RANGES", ranges(folder),
                             f"index-gb18030-ranges.txt, identifier {identifier(folder, 'gb18030-ranges')[:16]}."))
    header = (
        "package io.github.yuroyami.kitepdf.epub.script\n\n"
        "// Generated by tools/generate_encoding_tables.py from the Encoding Standard. Do not edit.\n"
        "//\n"
        "// The labels and the indexes the decoders of WhatwgEncoding read, each a string of numbers in\n"
        "// base64 VLQ as the generator describes. The indexes are under the BSD 3-Clause License of the\n"
        "// WHATWG, the license of the standard's repository.\n\n"
    )
    with open(TARGET, "w", encoding="utf-8") as f:
        f.write(header + "\n".join(sections))
    print(f"wrote {TARGET}: {os.path.getsize(TARGET)} bytes")


if __name__ == "__main__":
    main()

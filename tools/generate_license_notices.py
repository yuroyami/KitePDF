#!/usr/bin/env python3
"""Writes the third-party licence texts that kitepdf-core's jar carries in META-INF (#703).

The licences of the URW fonts (SIL OFL 1.1, condition 2) and of the hyphenation patterns (MIT,
BSD-3-Clause, LPPL and notices of their own) ask for their notice in every copy, binary copies
included. Their texts are the header comments of the generated source files, which the compiler
drops, so this copies them into resources/META-INF. Run it from the repository root after
regenerating those sources, and commit the result; CI checks that it is up to date.

    python3 tools/generate_license_notices.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CORE = ROOT / "kitepdf-core/src/io/github/yuroyami/kitepdf/core"
OUT = ROOT / "kitepdf-core/resources/META-INF"


def header(path: pathlib.Path) -> str:
    """The first block comment of [path], without its comment markers."""
    text = path.read_text(encoding="utf-8")
    m = re.search(r"/\*\*?(.*?)\*/", text, re.S)
    if not m:
        sys.exit(f"no header comment in {path}")
    lines = []
    for line in m.group(1).splitlines():
        line = re.sub(r"^\s*\* ?", "", line)
        lines.append(line.rstrip())
    body = "\n".join(lines).strip("\n")
    return re.sub(r"\n{3,}", "\n\n", body) + "\n"


# The BSD-style license of PDFium (https://pdfium.googlesource.com/pdfium/+/refs/heads/main/LICENSE),
# whose binary-form condition asks for this text in the documentation of a distribution.
PDFIUM_LICENSE = """Copyright 2014 The PDFium Authors

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

   * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
   * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
   * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
"""


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    fonts = header(CORE / "font/Standard14FontData.kt")
    (OUT / "LICENSE-URW-FONTS").write_text(fonts, encoding="utf-8")
    (OUT / "LICENSE-PDFIUM").write_text(header(CORE / "render/AdobeCmykTable.kt") + "\n" + PDFIUM_LICENSE, encoding="utf-8")
    parts = []
    for f in sorted((CORE / "text/hyphen").glob("Hyph*.kt")):
        parts.append("=" * 78 + "\n" + f.name + "\n" + "=" * 78 + "\n\n" + header(f))
    (OUT / "LICENSE-HYPHENATION").write_text("\n".join(parts), encoding="utf-8")


if __name__ == "__main__":
    main()

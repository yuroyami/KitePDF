#!/usr/bin/env python3
"""Writes the hyphenation pattern sets of kitepdf-core from a checkout of hyph-utf8 (#207).

    git clone --depth 1 https://github.com/hyphenation/tex-hyphen /path/to/tex-hyphen
    python3 tools/generate_hyphenation.py /path/to/tex-hyphen

hyph-utf8 is the project that keeps TeX's hyphenation patterns, one set for each language, in
plain UTF-8 next to a TeX file whose header names the authors, the licence and the hyphen minimums
in YAML. For each set in SETS this writes one Kotlin object to core/text/hyphen/: the patterns of
`hyph-<id>.pat.txt` and the exceptions of `hyph-<id>.hyp.txt`, carried unmodified, under a header
that cites the copyright and the licence word for word, with the upstream commit. A JVM class file
holds no string constant over 64 KB, so the text goes in chunks below that, split between lines.

It also writes HyphenPatternSets.kt, which maps a set's hyph-utf8 name to its object, and
HyphenationGolden.kt in the module's tests: words of each language with the break points that
an independent implementation of Liang's algorithm, below, finds in them. That implementation
looks substrings up in a dictionary instead of walking a trie, and reads the upstream files
instead of the Kotlin strings, so a packing error or a trie bug cannot confirm itself.

Which sets are bundled is a licence decision, and EXCLUDED records each set that is not, and why.
KitePDF is Apache-2.0, so a set offered only under the GPL or the LGPL, or under no licence at
all, stays out.
"""

import os
import re
import subprocess
import sys
import textwrap
import unicodedata

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORE = os.path.join(ROOT, "kitepdf-core", "src")
PACKAGE_PATH = os.path.join("io", "github", "yuroyami", "kitepdf", "core", "text", "hyphen")
MAIN_DIR = os.path.join(CORE, "commonMain", "kotlin", PACKAGE_PATH)
TEST_FILE = os.path.join(CORE, "commonTest", "kotlin", "io", "github", "yuroyami", "kitepdf", "core",
                         "HyphenationGolden.kt")
UPSTREAM_DIR = os.path.join("hyph-utf8", "tex", "generic", "hyph-utf8", "patterns")

# Every set bundled, by its hyph-utf8 name.
SETS = [
    "af", "as", "be", "bg", "bn", "ca", "cop", "cu", "cy", "da", "de-1901", "de-1996", "de-ch-1901",
    "el-monoton", "el-polyton", "en-gb", "en-us", "eo", "es", "et", "eu", "fi", "fi-x-school", "fr",
    "fur", "ga", "gl", "grc", "gu", "hi", "hr", "hsb", "hu", "ia", "is", "it", "ka", "kk", "kmr",
    "kn", "la", "la-x-classic", "la-x-liturgic", "lt", "ml", "mn-cyrl", "mr", "nb", "nl", "oc", "or",
    "pa", "pi", "pl", "pms", "pt", "rm", "ru", "sa", "sh-cyrl", "sh-latn", "sk", "sl", "sq", "sv",
    "ta", "te", "tk", "tr", "uk", "zh-latn-pinyin",
]

# Every set of hyph-utf8 left out, and why. The script fails on a set that is in neither list,
# so a set that upstream adds gets a decision before it ships.
EXCLUDED = {
    "cs": "GPL only",
    "mk": "GPL only",
    "id": "GPL only",
    "sr-cyrl": "GPL only; sh-cyrl covers Serbian in Cyrillic under the LPPL",
    "hy": "LGPL only",
    "lv": "LGPL or GPL only",
    "ro": "no licence stated",
    "mn-cyrl-x-lmc": "no licence stated; mn-cyrl covers Mongolian",
    "nn": "the same file as nb",
    "no": "the same file as nb",
    "ar": "no patterns: Arabic is not hyphenated",
    "fa": "no patterns: Persian is not hyphenated",
    "he": "no patterns: Hebrew is not hyphenated",
    "vi": "no patterns: Vietnamese is not hyphenated",
    "th": "Thai breaks a line between words with no visible hyphen, which the hyphenator always adds",
    "mul-ethi": "Ethiopic breaks a line with no visible hyphen, which the hyphenator always adds",
    "grc-x-ibycus": "patterns for the Ibycus 8-bit transliteration, not for Unicode text",
}

# Real words for the golden test, at least one with a letter beyond ASCII where the language has
# one (#303). Words a set does not break are kept: they test that nothing is found.
WORDS = {
    "af": ["verantwoordelikheid", "ontwikkeling", "universiteit", "regering", "skêrpunt"],
    "as": ["অসমীয়া", "বিশ্ববিদ্যালয়", "চৰকাৰ"],
    "be": ["беларуская", "універсітэт", "гаспадарка", "суб'ект"],
    "bg": ["университет", "България", "правителство", "държава"],
    "bn": ["বাংলাদেশ", "বিশ্ববিদ্যালয়", "সরকার"],
    "ca": ["universitat", "desenvolupament", "Barcelona", "informació"],
    "cop": ["ⲡⲛⲟⲩⲧⲉ", "ⲉⲕⲕⲗⲏⲥⲓⲁ", "ⲡⲣⲱⲙⲉ"],
    "cu": ["господь", "благословенъ", "царствие"],
    "cy": ["prifysgol", "llywodraeth", "Cymraeg", "cymdeithas"],
    "da": ["universitet", "regeringen", "sundhedsvæsen", "kærlighed"],
    "de-1901": ["Schiffahrt", "Zucker", "Wissenschaft", "Universität"],
    "de-1996": ["Krankenhaus", "Universität", "Wissenschaft", "Bundesregierung", "Straße"],
    "de-ch-1901": ["Strasse", "Zucker", "Universität", "Wissenschaft"],
    "el-monoton": ["πανεπιστήμιο", "κυβέρνηση", "ελευθερία", "ΑΘΗΝΑ"],
    "el-polyton": ["ἐλευθερία", "πανεπιστήμιον", "κυβέρνησις"],
    "en-gb": ["hyphenation", "organisation", "programme", "information", "behaviour"],
    "en-us": ["hyphenation", "information", "university", "presentation", "associate", "computer"],
    "eo": ["universitato", "esperanto", "ŝanĝiĝis", "registaro"],
    "es": ["universidad", "información", "desarrollo", "pingüino"],
    "et": ["ülikool", "valitsus", "raamatukogu", "õigusriik"],
    "eu": ["unibertsitatea", "euskara", "gobernua", "hizkuntza"],
    "fi": ["yliopisto", "hallitus", "kirjasto", "äänestys"],
    "fi-x-school": ["yliopisto", "hallitus", "kirjasto", "äänestys"],
    "fr": ["université", "gouvernement", "hyphénation", "l'université", "aujourd’hui"],
    "fur": ["universitât", "furlan", "lenghe", "l'universitât"],
    "ga": ["ollscoil", "rialtas", "Gaeilge", "príomhchathair"],
    "gl": ["universidade", "goberno", "información", "galego"],
    "grc": ["ἀνθρώπων", "φιλοσοφία", "βασιλεύς", "ἐκκλησία"],
    "gu": ["ગુજરાતી", "વિશ્વવિદ્યાલય", "સરકાર"],
    "hi": ["हिन्दी", "विश्वविद्यालय", "सरकार", "प्रधानमंत्री"],
    "hr": ["sveučilište", "Hrvatska", "vlada", "ministarstvo"],
    "hsb": ["uniwersita", "serbšćina", "knihownja"],
    "hu": ["egyetem", "kormány", "Magyarország", "szövetség", "kötelezettség"],
    "ia": ["interlingua", "universitate", "governamento"],
    "is": ["háskóli", "ríkisstjórn", "íslenska", "þjóðfélag"],
    "it": ["università", "governo", "informazione", "dell'università"],
    "ka": ["საქართველო", "უნივერსიტეტი", "მთავრობა"],
    "kk": ["университет", "Қазақстан", "үкімет", "мемлекеттік"],
    "kmr": ["zanîngeh", "kurmancî", "hikûmet", "pirtûkxane"],
    "kn": ["ಕನ್ನಡ", "ವಿಶ್ವವಿದ್ಯಾಲಯ", "ಸರ್ಕಾರ"],
    "la": ["universitas", "civitatem", "philosophia", "æternitas"],
    "la-x-classic": ["universitas", "civitatem", "philosophia", "aeternitas"],
    "la-x-liturgic": ["Dominus", "benedictus", "sanctificetur", "misericordia"],
    "lt": ["universitetas", "vyriausybė", "lietuvių", "nepriklausomybė"],
    "ml": ["മലയാളം", "സർവകലാശാല", "സർക്കാർ"],
    "mn-cyrl": ["Монгол", "засгийн", "Улаанбаатар", "сургууль"],
    "mr": ["मराठी", "विद्यापीठ", "महाराष्ट्र"],
    "nb": ["universitet", "regjeringen", "sannsynligvis", "kjærlighet"],
    "nl": ["ziekenhuis", "universiteit", "ontwikkeling", "coördinatie"],
    "oc": ["universitat", "occitan", "lenga", "l'universitat"],
    "or": ["ଓଡ଼ିଆ", "ବିଶ୍ୱବିଦ୍ୟାଳୟ", "ସରକାର"],
    "pa": ["ਪੰਜਾਬੀ", "ਯੂਨੀਵਰਸਿਟੀ", "ਸਰਕਾਰ"],
    "pi": ["dhammapada", "buddhassa", "saṅgha"],
    "pl": ["uniwersytet", "rzeczpospolita", "źdźbło", "Wrocław", "przedsiębiorstwo"],
    "pms": ["piemontèis", "università", "lenga"],
    "pt": ["universidade", "informação", "desenvolvimento", "coração"],
    "rm": ["universitad", "rumantsch", "regenza"],
    "ru": ["университет", "правительство", "государство", "объединение"],
    "sa": ["संस्कृतम्", "महाभारत", "रामायण"],
    "sh-cyrl": ["универзитет", "Београд", "влада", "министарство"],
    "sh-latn": ["univerzitet", "Beograd", "vlada", "ministarstvo", "đubrivo"],
    "sk": ["univerzita", "vláda", "Bratislava", "slovenčina"],
    "sl": ["univerza", "Ljubljana", "slovenščina", "vlada"],
    "sq": ["universiteti", "Shqipëria", "qeveria", "gjuha"],
    "sv": ["universitet", "regeringen", "sjuksköterska", "kärlek"],
    "ta": ["தமிழ்", "பல்கலைக்கழகம்", "அரசாங்கம்"],
    "te": ["తెలుగు", "విశ్వవిద్యాలయం", "ప్రభుత్వం"],
    "tk": ["uniwersitet", "Türkmenistan", "hökümet", "ýaşaýyş"],
    "tr": ["üniversite", "hükümet", "İstanbul", "çocuklarımız", "IŞIKLI"],
    "uk": ["університет", "Україна", "незалежність", "сім'я", "м’ясо"],
    "zh-latn-pinyin": ["zhongguo", "beijingdaxue", "zhōngguó", "xiānsheng"],
}

# Upstream text is carried as written, except that an em dash becomes a hyphen: KitePDF keeps
# no em dash in any file (CONTRIBUTING.md).
EM_DASH = "\u2014"
CHUNK_BYTES = 60000
WIDTH = 100


def class_name(set_id):
    return "Hyph" + "".join(p[:1].upper() + p[1:] for p in set_id.split("-"))


def read_meta(path):
    """The YAML at the top of a hyph-utf8 TeX file: its comment lines up to the `% ====` rule."""
    lines = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.startswith("%") or line.startswith("% ====="):
                break
            line = line.rstrip("\n")
            lines.append(line[2:] if line.startswith("% ") else line[1:])
    meta = yaml.safe_load("\n".join(lines))
    assert isinstance(meta, dict), f"{path}: no metadata"
    return meta


def licence_paragraphs(licence):
    """The licence of a set as paragraphs of text, with each name, version, address and text."""
    items = licence if isinstance(licence, list) else [licence]
    out = []
    for item in items:
        if item is None:
            continue
        if isinstance(item, str):
            out.append(item)
            continue
        if len(item) == 1 and next(iter(item.values())) is None:
            # A sentence that ends in a colon reads to YAML as a key with no value.
            out.append(f"{next(iter(item))}:")
            continue
        name = item.get("name")
        if name:
            head = str(name)
            if item.get("version"):
                head += f" {item['version']}"
                if item.get("or_later"):
                    head += " or later"
            if item.get("url"):
                head += f" ({item['url']})"
            out.append(head + ".")
        text = item.get("text")
        if isinstance(text, list):
            text = " ".join(str(t) for t in text if t is not None)
        if text:
            # A folded block keeps a blank line as one line break, which is where a paragraph ends.
            out.extend(p.strip() for p in str(text).split("\n") if p.strip())
    return out


def clean(s):
    s = s.replace(EM_DASH, "-")
    # A Kotlin block comment nests, so neither marker may stand in the header.
    return s.replace("/*", "/ *").replace("*/", "* /")


def kdoc(paragraphs):
    lines = ["/**"]
    for i, p in enumerate(paragraphs):
        if i:
            lines.append(" *")
        p = clean(" ".join(p.split()))
        lines.extend(" * " + l for l in textwrap.wrap(p, WIDTH - 3, break_long_words=False, break_on_hyphens=False))
    lines.append(" */")
    return "\n".join(lines)


def chunks(lines):
    """[lines] joined in runs that each stay under a class file's constant limit (modified UTF-8)."""
    out, cur, size = [], [], 0
    for line in lines:
        n = sum(3 if ord(c) >= 0x800 else 2 if ord(c) >= 0x80 or ord(c) == 0 else 1 for c in line) + 1
        n += sum(3 for c in line if ord(c) > 0xFFFF)  # a surrogate pair is two 3-byte units
        if cur and size + n > CHUNK_BYTES:
            out.append(cur)
            cur, size = [], 0
        cur.append(line)
        size += n
    if cur:
        out.append(cur)
    return out


def read_lines(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        lines = [l.strip() for l in f.read().split("\n")]
    lines = [l for l in lines if l]
    for l in lines:
        assert "$" not in l and '"""' not in l and EM_DASH not in l, f"{path}: a line a raw string cannot hold: {l}"
    return lines


def write_set(upstream, set_id, commit):
    meta = read_meta(os.path.join(upstream, UPSTREAM_DIR, "tex", f"hyph-{set_id}.tex"))
    txt = os.path.join(upstream, UPSTREAM_DIR, "txt")
    patterns = read_lines(os.path.join(txt, f"hyph-{set_id}.pat.txt"))
    exceptions = read_lines(os.path.join(txt, f"hyph-{set_id}.hyp.txt"))
    assert patterns, f"{set_id}: no patterns"
    mins = (meta.get("hyphenmins") or {}).get("typesetting") or (meta.get("hyphenmins") or {}).get("generation")
    left, right = int(mins["left"]), int(mins["right"])
    assert left >= 1 and right >= 1, f"{set_id}: hyphen minimums {left}, {right}"

    files = f"hyph-{set_id}.pat.txt" + (f" and hyph-{set_id}.hyp.txt" if exceptions else "")
    paragraphs = [
        f"{meta.get('title', 'Hyphenation patterns')}, from the hyph-utf8 project "
        f"(https://github.com/hyphenation/tex-hyphen, commit {commit}, "
        f"hyph-utf8/tex/generic/hyph-utf8/patterns/txt/{files}).",
    ]
    copyright = meta.get("copyright")
    if isinstance(copyright, list):
        copyright = " ".join(str(c) for c in copyright)
    if copyright:
        paragraphs.append(str(copyright).rstrip(".") + ".")
    else:
        names = [a.get("name") for a in meta.get("authors") or [] if isinstance(a, dict) and a.get("name")]
        if names:
            paragraphs.append("By " + ", ".join(names) + ".")
    lic = licence_paragraphs(meta.get("licence"))
    assert lic, f"{set_id}: no licence"
    paragraphs.append("Licence: " + lic[0])
    paragraphs.extend(lic[1:])
    paragraphs.append(
        "The patterns and exceptions are carried unmodified; only their packing into Kotlin strings is "
        "new. One Knuth-Liang pattern, or one hyphenated exception word, per line. Generated by "
        "tools/generate_hyphenation.py; do not edit by hand."
    )

    name = class_name(set_id)
    out = [f"// Generated by tools/generate_hyphenation.py from hyph-utf8. Do not hand-edit.",
           "package io.github.yuroyami.kitepdf.core.text.hyphen", "", kdoc(paragraphs),
           f"internal object {name} : HyphenPatterns {{",
           f"    override val minPrefix: Int get() = {left}",
           f"    override val minSuffix: Int get() = {right}", ""]

    def strings(prop, lines, prefix):
        groups = chunks(lines)
        if not groups:
            out.append(f'    override val {prop}: String get() = ""')
            return []
        if len(groups) == 1:
            out.append(f"    override val {prop}: String get() = {prefix}0")
        else:
            out.append(f"    override val {prop}: String")
            out.append("        get() = buildString {")
            for i in range(len(groups)):
                if i:
                    out.append("            append('\\n')")
                out.append(f"            append({prefix}{i})")
            out.append("        }")
        return groups

    pat_groups = strings("patterns", patterns, "P")
    hyp_groups = strings("exceptions", exceptions, "E")
    for prefix, groups in (("P", pat_groups), ("E", hyp_groups)):
        for i, g in enumerate(groups):
            out.append("")
            out.append(f'    private const val {prefix}{i} = """')
            out.append("\n".join(g) + '"""')
    out.append("}")
    with open(os.path.join(MAIN_DIR, f"{name}.kt"), "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
    return {"id": set_id, "left": left, "right": right, "patterns": patterns, "exceptions": exceptions,
            "lang": (meta.get("language") or {}).get("name", set_id)}


def write_registry(sets, commit):
    out = [
        "// Generated by tools/generate_hyphenation.py from hyph-utf8. Do not hand-edit.",
        "package io.github.yuroyami.kitepdf.core.text.hyphen",
        "",
        "/**",
        " * One hyphenation pattern set of the hyph-utf8 project",
        f" * (https://github.com/hyphenation/tex-hyphen, commit {commit}).",
        " */",
        "internal interface HyphenPatterns {",
        "    /** TeX's `lefthyphenmin`: the fewest characters a break leaves before it. */",
        "    val minPrefix: Int",
        "",
        "    /** TeX's `righthyphenmin`: the fewest characters a break leaves after it. */",
        "    val minSuffix: Int",
        "",
        "    /** The Knuth-Liang patterns, one to a line. */",
        "    val patterns: String",
        "",
        "    /** The exception words, one to a line with a hyphen at each break; empty when the set has none. */",
        "    val exceptions: String",
        "}",
        "",
        "/** Every bundled set, by the name hyph-utf8 gives its files (`hyph-<name>.pat.txt`). */",
        "internal object HyphenPatternSets {",
        "    /** The name of every bundled set. */",
        "    val ids: List<String> = listOf(",
    ]
    for i in range(0, len(sets), 8):
        out.append("        " + ", ".join(f'"{s["id"]}"' for s in sets[i:i + 8]) + ",")
    out += [
        "    )",
        "",
        "    /** The set named [id], or null when none is bundled. Only that set's strings load. */",
        "    fun byId(id: String): HyphenPatterns? = when (id) {",
    ]
    for s in sets:
        out.append(f'        "{s["id"]}" -> {class_name(s["id"])}')
    out += ["        else -> null", "    }", "}"]
    with open(os.path.join(MAIN_DIR, "HyphenPatternSets.kt"), "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")


# The reference implementation of the golden test: Liang's algorithm with a dictionary of patterns.

def lower_char(c):
    # Kotlin's Char.lowercaseChar maps one character to one; Python's lower gives İ two.
    return c.lower()[0] if len(c.lower()) != 1 else c.lower()


def parse_pattern(p):
    letters, points = [], [0]
    for c in p:
        if c.isdigit() and c.isascii():
            points[-1] = int(c)
        else:
            letters.append(c)
            points.append(0)
    return "".join(letters), points


def reference(s, word):
    left, right = s["left"], s["right"]
    if len(word) < left + right:
        return []
    low = "".join(lower_char(c) for c in word)
    if "table" not in s:
        s["table"] = dict(parse_pattern(p) for p in s["patterns"])
        s["maxlen"] = max(len(k) for k in s["table"])
        s["exc"] = {}
        for e in s["exceptions"]:
            letters = "".join(lower_char(c) for c in e if c != "-")
            pts, n = [], 0
            for c in e:
                if c == "-":
                    pts.append(n)
                else:
                    n += 1
            s["exc"][letters] = pts
    if low in s["exc"]:
        found = [i for i in s["exc"][low] if left <= i <= len(word) - right]
    else:
        w = "." + low + "."
        values = [0] * (len(w) + 1)
        for start in range(len(w)):
            for end in range(start + 1, min(len(w), start + s["maxlen"]) + 1):
                pts = s["table"].get(w[start:end])
                if pts:
                    for k, v in enumerate(pts):
                        values[start + k] = max(values[start + k], v)
        found = [i for i in range(left, len(word) - right + 1) if values[i + 1] % 2 == 1]
    return [i for i in found if not unicodedata.category(word[i]).startswith("M")]


def pseudo_words(s):
    """Words made of pattern letters, taken evenly from the whole file, so every chunk is read."""
    frags = []
    for p in s["patterns"]:
        letters = parse_pattern(p)[0].strip(".")
        if len(letters) >= 3 and "." not in letters:
            frags.append(letters)
    picks = [frags[i * len(frags) // 12] for i in range(12)] if len(frags) >= 12 else frags
    return [picks[i] + picks[(i + 1) % len(picks)] for i in range(len(picks))]


def kt_escape(s):
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("\t", "\\t")


def write_golden(sets):
    lines = []
    for s in sets:
        for word in WORDS[s["id"]] + pseudo_words(s):
            lines.append(f"{s['id']}\t{word}\t{','.join(map(str, reference(s, word)))}")
    groups = chunks(lines)
    out = [
        "// Generated by tools/generate_hyphenation.py from hyph-utf8. Do not hand-edit.",
        "package io.github.yuroyami.kitepdf.core",
        "",
        "/**",
        " * Words of every bundled hyphenation set with the break points that the reference",
        " * implementation in tools/generate_hyphenation.py finds in them: one line each, the set,",
        " * the word and the break indices, separated by tabs. The reference looks patterns up in a",
        " * dictionary read from the upstream files, so it shares neither the trie nor the Kotlin",
        " * strings with the code under test. The words after each set's real ones are made of",
        " * pattern letters taken evenly from the whole file, so a lost chunk shows.",
        " */",
        "internal object HyphenationGolden {",
        "    val lines: List<String>",
        "        get() = buildList {",
    ]
    for i in range(len(groups)):
        out.append(f'            addAll(G{i}.split(\'\\n\'))')
    out.append("        }")
    for i, g in enumerate(groups):
        out.append("")
        body = "\\n\" +\n        \"".join(kt_escape(l) for l in g)
        out.append(f'    private const val G{i} =\n        "{body}"')
    out.append("}")
    with open(TEST_FILE, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    upstream = sys.argv[1]
    commit = subprocess.check_output(["git", "-C", upstream, "rev-parse", "HEAD"], text=True).strip()
    txt = os.path.join(upstream, UPSTREAM_DIR, "txt")
    available = sorted(f[len("hyph-"):-len(".pat.txt")] for f in os.listdir(txt) if f.endswith(".pat.txt"))
    available += [t[len("hyph-"):-len(".tex")] for t in os.listdir(os.path.join(upstream, UPSTREAM_DIR, "tex"))
                  if t.endswith(".tex") and t[len("hyph-"):-len(".tex")] not in available]
    unknown = sorted(set(available) - set(SETS) - set(EXCLUDED))
    assert not unknown, f"sets with no decision, add each to SETS or EXCLUDED: {unknown}"
    missing = sorted(set(SETS) - set(available))
    assert not missing, f"sets that upstream no longer has: {missing}"
    assert sorted(WORDS) == sorted(SETS), "every set needs test words"

    for f in os.listdir(MAIN_DIR):
        if re.fullmatch(r"Hyph[A-Z]\w*\.kt", f):
            os.remove(os.path.join(MAIN_DIR, f))
    sets = [write_set(upstream, set_id, commit) for set_id in SETS]
    write_registry(sets, commit)
    write_golden(sets)
    print(f"{len(sets)} sets from hyph-utf8 {commit}")


if __name__ == "__main__":
    main()

# Security policy

KitePDF opens documents it did not write and runs the scripts inside them. A PDF, a book, a comic, an
SVG or an XPS file is untrusted input, often from a network, often chosen by someone else, so every
parser and every script host is attack surface.

## Reporting a vulnerability

Report privately through GitHub's
[security advisories](https://github.com/yuroyami/KitePDF/security/advisories/new),
not in a public issue. Do not attach the file to a public issue either. A useful report includes the
document (or a script that makes it), the platform it happens on, the call that you made, and what
KitePDF does with it.

Expect a first response within a week. Fixes go into the next release; there are no backports to
older versions.

## What counts as a vulnerability

A damaged or hostile document becomes a skip, a placeholder or a `KiteFormatException`. It never
stops the page, the document or the app. Report any of these:

- a document that hangs the process, or that runs for a time unrelated to its size
- memory use far out of proportion to the document, past the budgets that the parsers and renderers
  set, or an `OutOfMemoryError` from a small file
- a stack overflow, which a native target cannot catch, or any error that escapes `open`, a render,
  text extraction or search
- a redaction that keeps content: text, an image, a font, an annotation or metadata that the redacted
  region covers
- a signature check that reports trust, a certification level or an unchanged document where it
  should not
- a document script that escapes `PdfScriptPolicy` or `EpubScriptPolicy`: one that outruns its
  budget, reaches the network or the file system, or reads what the host did not give it
- a password, or the value of a password field, that KitePDF shows, saves or hands to an
  accessibility service in clear

## What does not

- **A refusal.** A document KitePDF cannot open, or a feature it does not support, is a normal issue.
- **Wrong pixels or wrong text.** A page that renders differently from MuPDF is a correctness bug;
  file it as a normal issue, with the document attached when its license allows it.
- **Resource use inside the documented budget.** A real 300 dpi scan really does need hundreds of
  megabytes to draw at full size.

## Reducing your own exposure

- Open untrusted documents with `PdfScriptPolicy.DENY` and `EpubScriptPolicy(enabled = false)`, unless
  the reader needs their scripts.
- Render on a worker with a `KiteCancellation`, so a slow page can be dropped.
- Give a server that renders uploaded files a deadline and a memory limit of its own.

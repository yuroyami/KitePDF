# GPT audit of KitePDF

Audited on 2026-11-04 at KitePDF `c4e7574`, KiteJS `5882331` and ImageKodec `aba04d0`. Consumed into GitHub issues on 2026-11-05.

Each finding was checked against the code, proven by a probe or a complete trace, and checked against the existing issues. Findings that the four audits share became one issue. Every issue made from this file ends with a line "Found by the November 2026 audits: ... GPT <id> ...", so a search of the issues for the id finds it. Proposals became `kind:feature` issues, which end with "Proposed by the November 2026 audits". What is below is all that is left.

## Could not be settled here

Claims that need a device, a Mac, Windows or a release build to prove or refute. Each says what would settle it.

| Where | Claim | What would settle it |
| --- | --- | --- |
| 597, item 3 | Read-aloud teardown clears the narration mark on an unowned scope and a per-instance mutex, which may race a new reading | Not reproduced: needs a scene test with a narrated EPUB that recreates KiteReadAloud while a clear is pending. Impact is bounded: show() reapplies the mark for every clip (applyMark per clip), so a lost mark lasts at most one clip. |
| 597, item 4 | A failure inside read() leaves a completed reading job, so play() never starts again | No library call on that path was found to throw: locate() catches, clipsOf/mediaOverlayOf and bookItem are lenient, clip playback catches Exception. Only a host callback (onClip, onFinished) or an unexpected layout exception in markNarration would reach it, which also crashes the composition scope. Needs a reproduction with a throwing chapter before filing. |
| 281, item 2 | Page hit geometry during pager motion is the centred letterbox, not where the moving page is | Needs a scene test (SceneTestDriver with QueuedEffects) that taps, long-presses or starts a selection while the pager animates, to see whether a gesture reaches the hit test mid-motion and lands on the wrong spot. GPT also left it unpromoted. |

## Refuted

Claims that the code, a probe or a reference reader contradicted. They were not filed; a "(part)" finding was filed for the rest of its claims.

| Id | Why it was not filed |
| --- | --- |
| line 902, item 1 | Canvas2D gradient created before or after the drawing CTM: GPT itself rejected it: Chromium 151 gave identical pixels either way. Nothing to file. |
| line 1112, item 3 | SVG and CBZ do not balance beginPage and endPage when a render throws: No observable consequence: SvgImage pops its own clips in finally before the exception leaves, and endPage on every canvas only cleans leftovers. The exception itself escaping renderTo is #737; a lost layer inside a raster step on KiteRasterCanvas is #765. |

## Proposals not filed

Proposals of this audit that were judged too general for an issue, or that need an owner decision first.

| Line | Proposal | Why |
| --- | --- | --- |
| 1367, item 1 | One canonical render identity for page, tile, thumbnail and semantic data | No deliverable beyond the filed defects. |
| 1367, item 2 | A shared graphics/text-state model for renderer, extractor, writer and redaction | No deliverable beyond the filed defects; the audit itself warns against a wholesale rewrite. |
| 1367, item 3 | End-to-end resource ownership and bounded diagnostics | Covered by concrete issues. |
| 1367, item 6 | DjVu and CHM handlers (and a generic 'more ebook readers') | GPT and SOL ask for demand to decide; no audit names a DjVu or CHM scope. |

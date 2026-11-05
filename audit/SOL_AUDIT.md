# SOL audit of KitePDF, ImageKodec and KiteJS

Audited on 2026-11-04 at KitePDF `c4e7574`, KiteJS `5882331` and ImageKodec `aba04d0`. Consumed into GitHub issues on 2026-11-05.

Each finding was checked against the code, proven by a probe or a complete trace, and checked against the existing issues. Findings that the four audits share became one issue. Every issue made from this file ends with a line "Found by the November 2026 audits: ... SOL <id> ...", so a search of the issues for the id finds it. Proposals became `kind:feature` issues, which end with "Proposed by the November 2026 audits". What is below is all that is left.

## Refuted

Claims that the code, a probe or a reference reader contradicted. They were not filed; a "(part)" finding was filed for the rest of its claims.

| Id | Why it was not filed |
| --- | --- |
| line 699, item 2 | Per-intent ICC/image memo arrays and line-art memo fields are unsynchronized: Same claim as FABLE PDF-RENDER-25, refuted: a lost race builds an equal immutable variant twice; OPUS also lists these as documented benign races (CharText, IccProfile.renderings, KiteImageData.renderings, KiteImageSamples.failed). |

## Proposals not filed

Proposals of this audit that were judged too general for an issue, or that need an owner decision first.

| Line | Proposal | Why |
| --- | --- | --- |
| 1039, item 1 | A shared staged-object resolver and copy-on-write resource model | No deliverable beyond the filed defects. |
| 1039, item 2 | One full-suspend script queue and authoritative render/layout revisions | No deliverable beyond the filed defects. |
| 1039, item 7 | DjVu and CHM handlers (and a generic 'more ebook readers') | GPT and SOL ask for demand to decide; no audit names a DjVu or CHM scope. |

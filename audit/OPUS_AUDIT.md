# OPUS audit of KitePDF, KiteJS and ImageKodec

Audited on 2026-11-04 at KitePDF `c4e7574`, KiteJS `5882331` and ImageKodec `aba04d0`. Consumed into GitHub issues on 2026-11-05.

Each finding was checked against the code, proven by a probe or a complete trace, and checked against the existing issues. Findings that the four audits share became one issue. Every issue made from this file ends with a line "Found by the November 2026 audits: ... OPUS <id> ...", so a search of the issues for the id finds it. Proposals became `kind:feature` issues, which end with "Proposed by the November 2026 audits". What is below is all that is left.

## Not yet filed

### KP-01: A type 5 mesh shading with zero bits per coordinate and per component never finishes parsing

Status: proven, and held back with the other security findings until they are reported.

**A type 5 mesh shading with zero bits per coordinate and per component never finishes parsing**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: critical
- Confidence: Confirmed

#### What happens

- `MeshShadingParser.parseTriangles` reads the rows of a lattice-form mesh while `bs.remaining >= minVertexBits * perRow`, where `minVertexBits` is `2 * BitsPerCoordinate + ncomp * BitsPerComponent`. The two widths come from the dictionary unchecked.
- With `/BitsPerCoordinate 0 /BitsPerComponent 0`, `minVertexBits` is 0, a row reads no bits, `remaining` never falls, and the condition stays true, even for an empty stream. Each row adds two triangles per pair of vertices, without end. A negative width does the same.
- The free-form branch of the same function stops at `MAX_TRIANGLES` (65,536), and the patch parser stops at `MAX_PATCHES`. The lattice branch has no cap, so with valid widths it still allocates triangles in proportion to the decoded data.
- The shading only has to sit in the page's `/Resources`: `render` parses every shading at its start (`loadShadings`), whether or not an `sh` uses it, and it does so again on every render of the page, `structuredText` included.
- On the JVM the loop ends when the heap is full. `KiteShading.parse` runs the parser in `runCatching`, which catches the `OutOfMemoryError`, and the shading becomes `Unsupported`. Until then the heap fills, and other threads that allocate at that moment can fail. On Kotlin/Native and in a browser, running out of memory ends the process.

#### What should happen

- A width that ISO 32000-1, 8.7.4.5.5 does not allow (it lists 1 to 32 bits for coordinates and 1 to 16 for components, and 8.7.4.5.6 takes them for a lattice) makes the shading unusable, and it paints nothing.
- The lattice branch stops at `MAX_TRIANGLES`, as the free-form branch does.

#### How to reproduce

`pdfs/mesh5-zero-bits.pdf`. The page resources are `/Shading << /Sh0 5 0 R >>`, the content is `/Sh0 sh`, and:

```
5 0 obj << /ShadingType 5 /ColorSpace /DeviceGray /BitsPerCoordinate 0 /BitsPerComponent 0
           /VerticesPerRow 2 /Decode [0 612 0 792 0 1] /Length 0 >>
stream

endstream
```

Probe for `kitepdf-core/test@jvm`:

```kotlin
class LatticeMeshProbeTest {
    @Test fun a_lattice_with_zero_bit_widths_parses_quickly() {
        val dict = PdfDictionary(linkedMapOf<String, PdfObject>(
            "ShadingType" to PdfInt(5), "ColorSpace" to PdfName("DeviceGray"),
            "BitsPerCoordinate" to PdfInt(0), "BitsPerComponent" to PdfInt(0), "VerticesPerRow" to PdfInt(2),
        ))
        val worker = kotlin.concurrent.thread { KiteShading.parse(PdfStream(dict, ByteArray(0)), IndirectResolver { null }) }
        worker.join(5_000)
        assertFalse(worker.isAlive, "the parse still runs after 5 s")   // fails today
    }
}
```

Run it alone: the thread goes on allocating after the assertion, until the heap is full.

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/MeshShading.kt:51`: the bit widths, read unchecked.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/MeshShading.kt:125`: the lattice loop, with no cap.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/MeshShading.kt:150`: the free-form loop, with its cap.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteShading.kt:247`: `runCatching` around the parse.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:307`: `render` parses the shadings first.

#### Notes

Read from source. `mapValue` divides by `2^bits - 1`, which is 0 here, so the coordinates are NaN, and nothing throws to end the loop.

Measured on the JVM at `c4e7574` by the lead: `mesh5-zero-bits.pdf` under `AwtPdfRasterizer` was still running after 25 s.

### KP-02: A type 4 function grows its operand stack without limit, so a function of 370 bytes exhausts memory

Status: proven, and held back with the other security findings until they are reported.

**A type 4 function grows its operand stack without limit, so a function of 370 bytes exhausts memory**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: KP-16 (the nesting part)

#### What happens

- `PostScriptCalc` keeps its operands in an `ArrayList` with no size limit. `copy` duplicates the top n entries, so `{ 1 copy 2 copy 4 copy ... 536870912 copy }` doubles the stack 30 times, to 2^30 entries, from a program of about 370 bytes. Near 2^29 entries the list and the copy it makes to grow need more than the 3 GB heap of the JVM gate, and `Type4.evaluateInternal` then copies the whole stack once more (`stack.mapNotNull`).
- `KiteFunction.evaluate` catches `RuntimeException` only, so the `OutOfMemoryError` leaves the renderer. As a tint transform, the function runs at the `cs` that selects the space, since `DeviceN.defaultColor` evaluates it, again at every `sc`, and for the colours of every image in that space.
- The compiler and the interpreter also recurse once per `{` level, with no limit. On the JVM the compile runs in `runCatching`, which catches the `StackOverflowError`, and the function becomes `Unsupported`. On Kotlin/Native, where a secondary thread has 512 KB of stack (CLAUDE.md, #450), a function of a few thousand nested braces overflows it while the page's colour spaces load, and the process ends.

#### What should happen

- The calculator stops at a fixed stack size and a fixed nesting depth, and the function then gives zeros, as a malformed function does now (#128). MuPDF's calculator keeps at most 100 operands (`PS_STACK_SIZE` in `pdf-function.c`).

#### How to reproduce

`pdfs/type4-copy-doubling.pdf`: the page selects `[/Separation /Spot /DeviceGray 5 0 R]` with `/CS0 cs 1 sc`, and function 5 is `<< /FunctionType 4 /Domain [0 1] /Range [0 1] >>` with the program above. `pdfs/type4-deep-nesting.pdf` holds 200,000 nested braces instead.

A probe for `kitepdf-core/test` that measures the stack without running out of memory:

```kotlin
class CalculatorStackProbeTest {
    @Test fun the_operand_stack_is_bounded() {
        val program = "{ " + (0 until 20).joinToString(" ") { "${1 shl it} copy" } + " }"
        val stack = ArrayList<Any>().apply { add(1.0) }
        PostScriptCalc.exec(PostScriptCalc.compile(program), stack)
        assertTrue(stack.size <= 100, "the stack holds ${stack.size}")   // 1048576 today
    }

    @Test fun nesting_is_bounded() {
        // On the JVM this throws StackOverflowError today; on Kotlin/Native it ends the process.
        PostScriptCalc.compile("{".repeat(100_000) + "}".repeat(100_000))
    }
}
```

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteFunction.kt:451`: `copy`, with no bound on the result.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteFunction.kt:65`: `evaluate` catches `RuntimeException` only.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteFunction.kt:381`: `parseBlockBody`, recursive with no depth limit (and `exec` at line 398 through `if`/`ifelse`).
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteColorSpace.kt:194`: `DeviceN.defaultColor` evaluates the tint transform.

#### Notes

Read from source. Related: #128 and #149, which fixed other calculator faults.

Measured on the JVM at `c4e7574` by the lead: `type4-copy-doubling.pdf` under `AwtPdfRasterizer` threw OutOfMemoryError after 27.7 s and 4.5 GiB of allocation. `type4-deep-nesting.pdf` (200,000 nested braces) rendered on the JVM, even on a thread with a 512 KB stack, because `runCatching` around the compile catches the StackOverflowError; the Kotlin/Native half was not run.

### KP-03: A TrueType glyph built from repeated composite components takes exponential time and memory to parse

Status: proven, and held back with the other security findings until they are reported.

**A TrueType glyph built from repeated composite components takes exponential time and memory to parse**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: critical
- Confidence: Confirmed

#### What happens

- `TrueTypeFont.parseCompositeGlyph` parses each component with `parseGlyph` again. Within one parse there is no cache. It guards the depth (`maxCompositeDepth`, 8) and cycles (the `active` set), but not repetition.
- So eight levels of composites, each with 10 components that all name the next level, parse the last glyph 10^8 times, and each level copies every point it collects into its `merged` list. The font is under 1 KB. The first draw of that glyph runs for minutes and fills the heap; `parseOrEmpty` catches only `PdfFormatException`, so an `OutOfMemoryError` leaves the renderer.
- The charstring interpreters have the same shape. `CharstringInterpreter.execute` and `Type1CharstringInterpreter` limit the subroutine depth to 10, not the number of calls, so a subroutine that calls the next one 10 times, ten levels down, runs 10^10 calls, and a leaf that draws a line fills `pathBuilder` with as many segments.
- `Type1CharstringInterpreter.seac` renders the base and the accent with a fresh interpreter, whose `depth` starts at 0 again. A glyph whose `seac` names itself as the base recurses until the stack overflows. `Type1Font.outlineForGlyphName` catches that with `runCatching` on the JVM; on Kotlin/Native it ends the process.

#### What should happen

- Each glyph program runs within a budget, such as a total of points or segments and of components or subroutine calls, past which the glyph is empty. A `seac` whose base or accent is itself a `seac` draws nothing more.
- Poppler (FreeType) renders the proof-of-concept page in 20 ms: the glyph draws nothing, and the blue square next to it draws.

#### How to reproduce

`fonts/fanout.ttf` (and `fonts2/fanout.ttf`, the same font with valid `maxp` limits of 10 components and depth 8): glyphs 1 to 8 are composites, each with 10 components (flags `0x0022`, the last `0x0002`, byte offsets 0 0) naming the next glyph, and glyph 9 is a square of 4 points. Its `cmap` maps `A` to glyph 1. `pdfs/tt-fanout.pdf` and `pdfs/tt-fanout-validmaxp.pdf` draw `(A) Tj` in it, then a blue square.

```kotlin
// kitepdf-core/test@jvm
class CompositeFanOutProbeTest {
    @Test fun a_wide_composite_tree_parses_quickly() {
        val font = TrueTypeFont.parse(java.io.File("audit-repro/L3/fonts/fanout.ttf").readBytes())
        val worker = kotlin.concurrent.thread { font.outlinePath(1) }
        worker.join(5_000)
        assertFalse(worker.isAlive, "glyph 1 still parses after 5 s")   // fails today
    }
}
```

Run it alone, for the same reason as the mesh probe.

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/TrueTypeFont.kt:156`: `maxCompositeDepth`, the only bound.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/TrueTypeFont.kt:334`: each component parses again.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/TrueTypeFont.kt:183`: `parseOrEmpty` catches `PdfFormatException` only.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/CharstringInterpreter.kt:92` and `:159`: depth limit, no call count.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/font/Type1CharstringInterpreter.kt:302` and `:331`: `seac` and `appendGlyph`.

#### Notes

Read from source, and the PoC rendered with Poppler. The lead may want to file the CFF/Type 1 call count and the Type 1 `seac` recursion as separate issues; they share the cause, which is that no glyph program has a total budget.

Measured on the JVM at `c4e7574` by the lead: `tt-fanout.pdf` under `AwtPdfRasterizer` was still running after 30 s.

### KP-04: A later revision can change the certification level and the field lock that judge it

Status: proven, and held back with the other security findings until they are reported.

**A later revision can change the certification level and the field lock that judge it**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #448

#### What happens

`PdfSignature.validate` judges the changes after signing by a certification level and a field lock. It reads both from the document as it is now, so the revision under judgement sets its own rules.

- The level: `changesAfterSigning` takes `document.signatures.firstNotNullOfOrNull { it.certificationLevel }`. `document` is the whole file, and `signatures` lists the signature fields in `/AcroForm /Fields` order. `PdfSignature.of` reads `certificationLevel` from any `/V` dictionary that has a `DocMDP` reference, without checking the signature and without looking at the catalog's `/Perms /DocMDP` (ISO 32000-1, 12.8.2.2 and 12.8.4).
- So a revision can add a signature field first in `/Fields`, whose `/V` holds `/Reference [<< /TransformMethod /DocMDP /TransformParams << /P 3 >> >>]`. Every change of that revision is then judged at level 3, also on a document that was certified at level 1. The new field and its dictionary pass as "a new signature" (`effective >= 2`), the new `/Fields` order passes as a form change, and an annotation passes at level 3.
- The lock: `PdfSignature.of` reads the FieldMDP lock from the signature field's own `/Lock` first, in the current document. A revision can rewrite that field with a looser `/Lock`, such as `/Action /Include /Fields [(Nothing)]`. `classify` files the rewrite as "the signing of field X" because `Lock` is in `SIGNING_KEYS`, and it does not check that the field was already signed. The values of the fields that the original lock covered can then change, and each change reads as permitted (ISO 32000-1, 12.8.2.4).
- A third path has the same cause: `V` is in `SIGNING_KEYS` too, so a revision can point an already signed certification field at a new signature dictionary with no `DocMDP` reference, and the level falls back to 3.

`areChangesPermitted` is then true for a certified or locked document whose content changed.

#### What should happen

- The certification level is the one of the signature that the catalog's `/Perms /DocMDP` names in the signed revision, and only when that signature is valid (ISO 32000-1, 12.8.2.2, Table 254). A signature that a later revision adds does not change it.
- The FieldMDP lock comes from the signed revision: the `/Lock` of the field as it was signed, or the FieldMDP reference inside the signed signature dictionary.
- A change to `/Lock` or `/V` of a field that was already signed is not "signing". It is a change that no level allows.

#### How to reproduce

JVM test, with the setup of `RevisionChangesTest` (`root`, `signer`, `signed(o)`):

```kotlin
@Test
fun aRevisionCannotRaiseTheCertificationLevel() {
    val o = PdfOptions(certification = 1)
    val note = "<< /Type /Annot /Subtype /FreeText /Rect [10 10 190 60] /Contents (PAID IN FULL) /DA (/Helv 12 Tf 0 g) /P 3 0 R >>"
    // Control: the annotation alone is not permitted at level 1.
    val plain = SigFixtures.update(signed(o), mapOf(
        9 to note,
        3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [4 0 R 9 0 R] /Contents 6 0 R >>",
    ))
    assertFalse(PdfDocument.open(plain).signatures.single().validate(listOf(root.cert)).areChangesPermitted)

    // The same annotation, with a signature field first in /Fields that claims level 3.
    val raised = SigFixtures.update(signed(o), mapOf(
        1 to "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [7 0 R 4 0 R] /SigFlags 3 >> /Perms << /DocMDP 5 0 R >> >>",
        3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [4 0 R 7 0 R 9 0 R] /Contents 6 0 R >>",
        7 to "<< /Type /Annot /Subtype /Widget /FT /Sig /T (Fake) /Rect [0 0 0 0] /F 132 /P 3 0 R /V 8 0 R >>",
        8 to "<< /Type /Sig /Filter /Adobe.PPKLite /SubFilter /adbe.pkcs7.detached /Reference [<< /Type /SigRef /TransformMethod /DocMDP /TransformParams << /Type /TransformParams /P 3 /V /1.2 >> >>] /ByteRange [0 1 2 1] /Contents <00> >>",
        9 to note,
    ))
    val sig = PdfDocument.open(raised).signatures.first { it.field.fullyQualifiedName == "Signature1" }
    assertEquals(1, sig.certificationLevel)
    val result = sig.validate(listOf(root.cert))
    assertEquals(Status.Valid, result.status)
    assertFalse(result.areChangesPermitted)   // fails today: true
}

@Test
fun aRevisionCannotLoosenTheLock() {
    val o = PdfOptions(lock = "<< /Type /SigFieldLock /Action /All >>", textField = "before")
    val n = SigFixtures.numbers(o)
    val pdf = SigFixtures.update(signed(o), mapOf(
        n.signatureField to "<< /Type /Annot /Subtype /Widget /FT /Sig /T (Signature1) /Rect [0 0 0 0] /F 132 /P 3 0 R /V 5 0 R /Lock << /Type /SigFieldLock /Action /Include /Fields [(Nothing)] >> >>",
        n.textField to "<< /Type /Annot /Subtype /Widget /FT /Tx /T (Name) /Rect [20 150 180 170] /F 4 /P 3 0 R /V (after) >>",
    ))
    val result = PdfDocument.open(pdf).signatures.single().validate(listOf(root.cert))
    assertEquals(Status.Valid, result.status)
    assertFalse(result.areChangesPermitted)   // fails today: true, with "the signing of field Signature1" permitted
}
```

Object numbers: with `certification = 1` and no text field, `SigFixtures.numbers(o)` gives content 6, so 7, 8 and 9 are free.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfSignature.kt:80` (the level), `:122` (`certificationLevel` from any `DocMDP` reference), `:125` (the lock from the current field)
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/RevisionChanges.kt:101` (a signed field's change filed as signing), `:249` (`SIGNING_KEYS`)

#### Notes

Read from source. `RevisionChangesTest.aSecondSignatureIsPermittedFromLevel2` adds a second signature after the certification one, so the certification level comes first there and the test cannot see this.

### KP-05: A revision that rewrites an object stream changes the objects inside it, and RevisionChanges reports nothing

Status: proven, and held back with the other security findings until they are reported.

**A revision that rewrites an object stream changes the objects inside it, and RevisionChanges reports nothing**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #448

#### What happens

`RevisionChanges.between` compares only the objects whose cross-reference entries differ (`if (oldEntry == newEntry) continue`). It also skips any object stream or cross-reference stream (`isContainer`).

An object inside an object stream has a type 2 entry: the number of its object stream and its index (ISO 32000-1, 7.5.8.3). A revision that writes a new version of object stream N, under the same number, changes every member of N. The members' entries stay equal, `XrefEntry.Compressed` being a data class, so they are skipped. N itself is skipped as a container. `between` returns an empty list.

So a signed file whose dictionaries live in object streams (fonts, annotations, the page tree, ExtGStates, OCG dictionaries) can have any of them changed after signing. `validate` reports `isModifiedAfterSigning = true` with `changes = []`, so `areChangesPermitted` is true, also at certification level 1. For example, a new `/Encoding /Differences` on a font turns "1000" into "9000" on the page.

#### What should happen

When an object stream changes, every object whose entry names it is compared, as if its own entry had changed. A member whose value differs is classified like any other change.

#### How to reproduce

Common test:

```kotlin
@OptIn(KiteRawApi::class)
@Test
fun a_rewritten_object_stream_is_a_change() {
    val packed = PdfDocument.open(
        TestPdf.onePage(
            "BT /F1 24 Tf 20 100 Td (1000) Tj ET",
            resources = "/Font << /F1 5 0 R >>",
            extra = listOf("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
        ),
    ).edit().saveRewritten(useObjectStreams = true)
    val old = PdfDocument.open(packed)
    val container = old.xref.values.filterIsInstance<XrefEntry.Compressed>().first().containingObjectStream
    val stm = old.resolve(PdfReference(container, 0)) as PdfStream
    val n = stm.dict.getInt("N")!!.toInt()
    val first = stm.dict.getInt("First")!!.toInt()
    val text = FilterChain.decode(stm).decodeToString()
    val pairs = text.substring(0, first).trim().split(Regex("\\s+"))
    val bodies = (0 until n).map { i ->
        val from = first + pairs[2 * i + 1].toInt()
        val to = if (i + 1 < n) first + pairs[2 * i + 3].toInt() else text.length
        text.substring(from, to).trim()
            .replace("/BaseFont /Helvetica", "/BaseFont /Helvetica /Encoding << /Differences [49 /nine] >>")
    }
    val header = StringBuilder()
    var at = 0
    for (i in 0 until n) { header.append("${pairs[2 * i]} $at "); at += bodies[i].length + 1 }
    val data = header.toString() + bodies.joinToString("\n", postfix = "\n")
    val changed = appendRevision(
        packed,
        mapOf(container.toInt() to "<< /Type /ObjStm /N $n /First ${header.length} /Length ${data.length} >>\nstream\n$data\nendstream"),
        size = old.trailer.getInt("Size")!!.toInt(),
        root = (old.trailer["Root"] as PdfReference).objectNumber.toInt(),
    )
    val new = PdfDocument.open(changed)
    assertTrue("9000" in new.pages[0].extractText(), "the revision changed the page")
    assertTrue(RevisionChanges.between(old, new, level = 1, lock = null).isNotEmpty())   // fails today: empty
}
```

The same holds through `PdfSignature.validate` for a signed file: sign `packed` with `PdfSigner`, then append the same revision.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/RevisionChanges.kt:57` (entry comparison), `:62` (containers skipped)

#### Notes

Read from source. No test of `RevisionChangesTest` uses an object stream. Many writers since PDF 1.5 store dictionaries in object streams, KitePDF's own `saveRewritten(useObjectStreams = true)` among them, so this covers a large share of signed files.

### KP-06: A revision that adds or removes an annotation or a field through /Annots or /Fields reports no change

Status: proven, and held back with the other security findings until they are reported.

**A revision that adds or removes an annotation or a field through /Annots or /Fields reports no change**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #448

#### What happens

`RevisionChanges.classify` returns null (no report) for a page whose only changed key is `/Annots`, for an indirect `/Annots` array (`Role.ANNOTS`), for an indirect `/Fields` array (`Role.FIELDS`), and for an `/AcroForm` whose changed keys are all in `FORM_KEYS`, which holds `Fields`. The objects that such a change adds or takes away are reported only when their own entries change.

So two changes pass unreported at every level:

- Removing an annotation or a widget from `/Annots`, or a field from `/Fields`. The removed object did not change, so nothing compares it. Level 1 allows no change and level 2 allows no annotation change (ISO 32000-1, 12.8.2.2, Table 254), and no level allows removing a form field: level 3 adds only annotation creation, deletion and modification.
- Adding an object that the signed revision already held but did not use, such as an annotation that no page listed. `anObjectThatNothingReachesIsNoChange` lets such an orphan into the signed revision, and a later `/Annots` entry makes it visible without any changed object.

This is the "hide" and "hide-and-replace" shape of the shadow attacks on PDF signatures: an opaque annotation that covers text is taken off the page after a level 1 certification, and `areChangesPermitted` stays true.

#### What should happen

Compare the members of `/Annots` and `/Fields` (and `/CO`) between the two revisions. An annotation that leaves or joins a page is an annotation change (level 3), and a widget or field that leaves or joins is a form change that no level allows.

#### How to reproduce

Common test:

```kotlin
@Test
fun an_annotation_taken_off_the_page_is_a_change() {
    val base = TestPdf.onePage(
        "0 0 1 rg 20 20 160 40 re f",
        pageEntries = "/Annots [5 0 R]",
        extra = listOf("<< /Type /Annot /Subtype /Square /Rect [10 10 190 70] /IC [1 1 1] /Contents (covers the bar) >>"),
    )
    val old = PdfDocument.open(base)
    val new = PdfDocument.open(appendRevision(base, mapOf(3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << >> /Contents 4 0 R >>"), size = 6))
    assertEquals(0, new.pages[0].annotations.size)
    assertTrue(RevisionChanges.between(old, new, level = 1, lock = null).isNotEmpty())   // fails today: empty
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/RevisionChanges.kt:112` (`ANNOTS`, `FIELDS`), `:113` (`FORM`), `:114` (`PAGE`)

#### Notes

Read from source. `RevisionChangesTest.anAnnotationIsPermittedAtLevel3` only adds a new annotation object, which is reported through its own entry.

### KP-07: A certificate without the CA flag can issue a signer certificate that validate calls trusted

Status: proven, and held back with the other security findings until they are reported.

**A certificate without the CA flag can issue a signer certificate that validate calls trusted**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #634

#### What happens

`SignatureValidator.chainOf` takes any certificate whose subject is the last one's issuer and whose key verifies its signature. `X509.parse` reads basicConstraints into `isCa`, but nothing reads `isCa`. Key usage is not parsed at all, and no path length is checked.

So anyone who holds an end-entity certificate under a trusted root can sign a certificate for any name with that end-entity key. `validate(listOf(root))` returns `Valid`, `isTrusted = true`, and `signer.commonName` is the forged name. The same holds for a document timestamp: any trusted end-entity certificate can sign a TSTInfo with any time, and the extended key usage `id-kp-timeStamping` that RFC 3161, 2.3 requires is not checked.

#### What should happen

Every certificate that issues another one in the chain has basicConstraints `cA = TRUE`, a key usage with `keyCertSign` when key usage is present, and a path length that allows the chain (RFC 5280, 6.1.4 (k), (l), (m), (n)). A document timestamp's signer has `id-kp-timeStamping`. A chain that breaks a rule is not trusted, and the result says why.

#### How to reproduce

JVM test:

```kotlin
@Test
fun anEndEntityCannotIssueATrustedSigner() {
    val root = SigFixtures.certificate("Root", SigFixtures.rsaKeys(), issuer = null, ca = true, serial = 1)
    val alice = SigFixtures.certificate("Alice", SigFixtures.rsaKeys(), issuer = root, ca = false, serial = 2)
    val ceo = SigFixtures.certificate("CEO", SigFixtures.rsaKeys(), issuer = alice, ca = false, serial = 3)
    val pdf = SigFixtures.signedPdf(PdfOptions()) { SigFixtures.cms(ceo, listOf(ceo, alice, root), it) }
    val result = PdfDocument.open(pdf).signatures.single().validate(listOf(root.cert))
    assertEquals(listOf("CEO", "Alice", "Root"), result.chain.map { it.commonName })
    assertFalse(result.isTrusted)   // fails today: true
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/sig/SignatureValidator.kt:202` (`chainOf`), `:217` (`trusted`)
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/sig/X509.kt:67` (`isCa` parsed, never used)

#### Notes

Read from source. `docs/reading.md` warns about the validity dates (#634) but not about this. `SigFixtures.certificate(ca = false)` already writes a critical basicConstraints without `cA`.

Measured on the JVM at `c4e7574` by the lead: with `SigFixtures`, a chain Root (CA) to Alice (not a CA) to CEO, signed by CEO, gives `status=Valid`, `isTrusted=true`, chain `[CEO, Alice, Root]`, the same as a control chain whose intermediate is a CA.

### KP-08: Redaction keeps the /ActualText of the text it removes, and extractText() still returns that text

Status: proven, and held back with the other security findings until they are reported.

**Redaction keeps the /ActualText of the text it removes, and extractText() still returns that text**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed

#### What happens

`RedactionEngine.run` passes `BDC`, `BMC` and `EMC` through unchanged (`else -> out.add(op)`). A marked-content sequence can carry the text it encloses as `/ActualText` (ISO 32000-1, 14.9.4), inline or through `/Resources /Properties`. Redaction replaces the `Tj` with a `TJ` advance and keeps the `BDC` with its string.

`TextExtractor` writes the `/ActualText` of a `BDC` in place of what the sequence encloses, whether or not any text is left inside it. So after `redactRegion` and `saveRewritten`, `page.extractText()` returns the redacted words. Any other tool reads them from the content stream too.

The same holds for `/Alt` and `/E` in a property list, and for `/ActualText`, `/Alt` and `/E` on the structure elements of a tagged PDF (14.9.3, 14.9.5), which describe a redacted figure or text. LibreOffice writes a picture's alternate text as `/Alt` on its `/Figure` structure element, so a redacted photo of a passport keeps "Passport of Jane Doe" in the file. `docs/editing.md` says "no tool can extract them" and lists neither case.

#### What should happen

A marked-content sequence whose text redaction removes loses `/ActualText`, `/Alt` and `/E`, inline or in `/Properties` (a property list that other sequences share gets a copy). A structure element whose content leaves the page loses the same entries.

#### How to reproduce

Common test:

```kotlin
@Test
fun redaction_removes_the_actual_text_of_what_it_removes() {
    val pdf = RawPdf.page("BT /F1 24 Tf 100 700 Td /Span << /ActualText (SSN 123-45-6789) >> BDC (SSN 123-45-6789) Tj EMC ET".encodeToByteArray())
    val doc = PdfDocument.open(pdf)
    val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(90.0, 690.0, 400.0, 730.0)) }.saveRewritten()
    assertFalse("123-45-6789" in PdfDocument.open(out).pages[0].extractText())   // fails today
}
```

JVM test, for the structure tree, with the LibreOffice file:

```kotlin
@Test
fun redactingAPictureDropsItsAlternateText() {
    val doc = PdfDocument.open(java.io.File("audit-repro/L4/lo/alt.pdf").readBytes())
    val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(50.0, 700.0, 220.0, 790.0)) }.saveRewritten()
    // "Pass" in UTF-16BE after the BOM, as PdfObjectWriter writes a hex string.
    assertFalse("FEFF0050006100730073" in out.decodeToString())   // fails today
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/RedactionEngine.kt:307`
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/text/TextExtractor.kt:109`
- `docs/editing.md`, "What redaction removes"

#### Notes

Read from source, and checked that LibreOffice 24.2 writes `/Alt` on the `/Figure` element (object 7 of `alt.pdf`). Tagged PDFs, PDF/UA files and OCR text layers use `/ActualText`; the corpus has `public-pdfa-actualtext-ocr.pdf`.

Measured on the JVM at `c4e7574` by the lead: after `redactRegion` over the run and `saveRewritten()`, `extractText()` of the output returns `SSN 123-45-6789`.

### KP-09: Redaction keeps the data of a removed image when the page shares or inherits its /Resources

Status: proven, and held back with the other security findings until they are reported.

**Redaction keeps the data of a removed image when the page shares or inherits its /Resources**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed

#### What happens

`redactRegions` drops the `Do` of an image in a region, then copies the page's effective resources onto the page and prunes the image from that copy (`prunePageResourceXObjects`). `saveRewritten` then relies on reachability to drop the image stream.

When the resources dictionary is shared, the original still names the image: an indirect `/Resources` that several pages use, or `/Resources` on a `/Pages` node that the pages inherit (ISO 32000-1, 7.7.3.4). The image stays reachable and the rewrite writes its data. For inherited resources this holds even for a one-page file, because the catalog still reaches the `/Pages` node. The code comment at the bake says the opposite ("so the image XObject entry is actually removed"). `docs/editing.md` says images go "with their data".

LibreOffice 24.2 writes exactly this layout: one resources dictionary that lists every picture of the document, named by `/Resources` on each page and on the `/Pages` node. A photo or a scanned signature redacted on page 1 of such a file stays in the rewritten file.

#### What should happen

After the rewrite, an image that no page, form, pattern or annotation draws is not in the file. Either prune the shared dictionary when nothing else draws the image, or give the other pages and the `/Pages` node their own copies without the entry.

#### How to reproduce

JVM test, with the LibreOffice file:

```kotlin
@OptIn(KiteRawApi::class)
@Test
fun aRedactedLibreOfficePictureLeavesTheFile() {
    val doc = PdfDocument.open(java.io.File("audit-repro/L4/lo/doc.pdf").readBytes())
    val out = PdfDocument.open(doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(50.0, 700.0, 220.0, 790.0)) }.saveRewritten())
    val images = out.xref.keys.mapNotNull { out.resolve(PdfReference(it, 0)) as? PdfStream }.filter { it.dict.getName("Subtype") == "Image" }
    assertEquals(0, images.size)   // fails today: 1
}
```

Common test, without LibreOffice:

```kotlin
@Test
fun a_redacted_image_in_inherited_resources_leaves_the_file() {
    val pdf = TestPdf.build(listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 612 792] /Resources << /XObject << /Im1 5 0 R >> >> >>",
        "<< /Type /Page /Parent 2 0 R /Contents 6 0 R >>",
        "<< /Type /Page /Parent 2 0 R /Contents 7 0 R >>",
        TestPdf.stream("SECRETIMAGEBYTES", "/Type /XObject /Subtype /Image /Width 16 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8"),
        TestPdf.stream("q 160 0 0 10 100 700 cm /Im1 Do Q"),
        TestPdf.stream("q Q"),
    ))
    val doc = PdfDocument.open(pdf)
    val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(90.0, 690.0, 300.0, 720.0)) }.saveRewritten()
    assertFalse(RawPdf.containsBytes(out, "SECRETIMAGEBYTES".encodeToByteArray()))   // fails today
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:761`, `:762` (effective resources), `:885` to `:891` (the comment, the copy and the prune)
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditorRedaction.kt` (`prunePageResourceXObjects`)

#### Notes

Read from source. Made `doc.pdf` with LibreOffice and read its objects: pages 1 and 10 and the `/Pages` node 14 all name `/Resources 21 0 R`, which holds `/XObject << /Im8 8 0 R >>`. No redaction test in the repository draws an image at all, so the "with their data" promise has no test. `RedactionInheritedResourcesTest` covers a form without `/Resources`, not a page that inherits them.

Measured on the JVM at `c4e7574` by the lead: the output of `saveRewritten(useObjectStreams = false)` still contains the image bytes `SECRETIMAGEBYTES`.

### KP-10: Entities that expand to nothing escape the expansion budget, so a 600-byte document hangs every XML reader

Status: proven, and held back with the other security findings until they are reported.

**Entities that expand to nothing escape the expansion budget, so a 600-byte document hangs every XML reader**

- Repo: KitePDF
- Labels: area:core, area:epub, area:svg, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #571

#### What happens

- `KiteXml` expands the general entities that the internal subset of a DOCTYPE declares (#571). `DeclaredEntities.expand` checks its budget before an expansion and spends it afterwards, by the length of the text that the entity became (`budget -= it.length`).
- An entity whose replacement text is empty becomes `""` and spends nothing. An external entity returns `""` before any check at all.
- The depth limit is 16, and each level may name the level below it any number of times. Ten references at each of twelve levels make about 1.1 x 10^11 calls of `expand`, and the budget stays at 1,000,000 the whole time.
- The document that does this is 577 bytes: one line of the subset for each level.
- `KiteXml.tokenize` reads every XML file of KitePDF: the EPUB `container.xml`, the OPF, `encryption.xml`, the chapters, the nav, NCX and SMIL files, SVG files, XPS parts, the CBZ `ComicInfo.xml`, and the `/RV` rich text of a PDF field. So `EpubDocument.open` with such a `container.xml` or OPF never returns, and neither does `SvgDocument.open`. Nothing in the tokenizer checks a cancellation.
- The test `nested_entities_stop_expanding_at_a_limit` covers entities that expand to `lol`, which spend the budget.

#### What should happen

- Each expansion costs something, whatever it expands to, so the work of one document stays within the budget: count every expansion against it, or charge at least one unit for each.
- XML 1.0 leaves this limit to the processor. The JDK's parser, for one, counts entity expansions (`jdk.xml.entityExpansionLimit`), not only characters.

#### How to reproduce

```kotlin
// kitepdf-core, test/
@Test
fun entities_that_expand_to_nothing_still_spend_the_budget() {
    val subset = buildString {
        append("""<!ENTITY a "">""")
        for (k in 1 until 12) append("""<!ENTITY ${'a' + k} "${"&${'a' + k - 1};".repeat(10)}">""")
    }
    val xml = "<!DOCTYPE svg [$subset]><svg><text>&l;</text></svg>"
    val mark = kotlin.time.TimeSource.Monotonic.markNow()
    KiteXml.parse(xml)
    assertTrue(mark.elapsedNow().inWholeSeconds < 2, "took ${mark.elapsedNow()}") // runs for hours today
}
```

The same document passed as bytes to `SvgDocument.open` does not return either. With `<!ENTITY a SYSTEM "x">` as the first declaration, the external entity does the same.

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/xml/KiteXml.kt:353-369`: `DeclaredEntities.expand`, the budget and the depth limit.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/xml/KiteXml.kt:233-261`: `decodeEntities`, which calls it for each reference.
- `kitepdf-core/test/io/github/yuroyami/kitepdf/core/xml/KiteXmlTest.kt:136`: the test that only uses entities with text.

#### Notes

Read from source, and ran a Python port of `decodeEntities` and `expand` on the 577-byte document: 3,000,000 calls in 2.6 s with the budget unchanged, of the 1.1 x 10^11 calls that the whole run needs. The port is `audit-repro/L5/entity_sim.py`, and the document is `audit-repro/L5/empty-laughs.svg`.

Measured on the JVM at `c4e7574` by the lead: `SvgDocument.open` on the document of the probe with 6, 7 and 8 levels returns after 91, 155 and 558 ms; with 12 levels (600 bytes) it was still running after 25 s. An EPUB whose `container.xml` carries the 12-level subset and references the last entity: `KiteDoc.open` was still running after 25 s.

### KP-11: An SVG whose use elements fan out paints exponentially many shapes, so a 150-byte file hangs its render, its text and its links

Status: proven, and held back with the other security findings until they are reported.

**An SVG whose use elements fan out paints exponentially many shapes, so a 150-byte file hangs its render, its text and its links**

- Repo: KitePDF
- Labels: area:svg, area:epub, kind:bug
- Severity: critical
- Confidence: Confirmed

#### What happens

- `SvgImage.walk` stops at depth 32 (its comment says "`<use>` cycles"), and that is the only guard against `<use>`. A `<use>` and its target take two levels, so `<use>` nests about 15 times.
- Nothing counts the elements that `<use>` instances make. A group with ten `<use href="#previous">` at each of 15 levels walks 10^15 elements.
- A cycle is not an error either. `<g id="a"><use href="#a"/><use href="#a"/><use href="#a"/><use href="#a"/></g>` walks the group 4^15, about 10^9 times, each with its four uses. The test `a_use_cycle_stops_instead_of_hanging` has one `<use>` in its cycle, so it costs 16 walks.
- `render` stops between elements when a viewer cancels, but `SvgImage.links` and `SvgImage.textContent` walk with `stop = null`. So `SvgPage.textContent()` and `SvgPage.hyperlinks`, and the text of an EPUB page with an inline SVG (`svgTexts`), cannot be stopped at all.
- `boundsOfElement`, which an `objectBoundingBox` clip path calls, follows `<use>` the same way.

#### What should happen

- SVG 2, 5.6 (the `use` element): a `<use>` whose references lead back to itself or to an ancestor is in error and renders nothing.
- The elements that the `<use>` instances of one image make stay within a budget, as browsers keep them, so fan-out cannot multiply past it.

#### How to reproduce

```kotlin
// kitepdf-svg, test/SvgFeaturesTest.kt (which has fills())
@Test
fun use_fan_out_is_bounded() {
    fun bomb(levels: Int) = buildString {
        append("<svg width='10' height='10'><defs><rect id='g0' width='1' height='1'/>")
        for (k in 1..levels) { append("<g id='g$k'>"); repeat(10) { append("<use href='#g${k - 1}'/>") }; append("</g>") }
        append("</defs><use href='#g$levels'/></svg>")
    }
    assertTrue(fills(bomb(5)).size <= 10_000) // 100,000 fills today, and bomb(9) asks for 10^9
}
```

The cycle, as a file:

```xml
<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10">
  <g id="a"><rect width="1" height="1"/><use href="#a"/><use href="#a"/><use href="#a"/><use href="#a"/></g>
</svg>
```

`SvgDocument.open(bytes).pages[0].textContent()` does not return.

#### Where

- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:224`: the depth check, with `MAX_DEPTH` at line 1704.
- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:363-380`: `drawUse`.
- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:145`: `links`, which renders with `stop = null`.
- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgImage.kt:668-683`: `boundsOfElement`.
- `kitepdf-svg/src/io/github/yuroyami/kitepdf/svg/SvgDocument.kt:85` and `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:3462`: the text passes.
- `kitepdf-svg/test/io/github/yuroyami/kitepdf/svg/SvgFeaturesTest.kt:58`: the cycle test.

#### Notes

Read from source. The counts are arithmetic over the depth check: the deepest group of the cycle is at depth 31, reached 4^15 times.

Measured on the JVM at `c4e7574` by the lead: opening and rendering the fan-out file with 10 uses per level: 4 levels 293 ms and 35 MiB allocated, 5 levels 324 ms and 194 MiB, 6 levels 2.3 s and 1,951 MiB, 9 levels still running after 25 s. The four-use cycle: still running after 25 s.

### KP-12: A table cell's colspan or rowspan, or a col's span, is not clamped as HTML clamps it, so one attribute runs the layout out of memory

Status: proven, and held back with the other security findings until they are reported.

**A table cell's colspan or rowspan, or a col's span, is not clamped as HTML clamps it, so one attribute runs the layout out of memory**

- Repo: KitePDF
- Labels: area:epub, kind:bug
- Severity: critical
- Confidence: Confirmed

#### What happens

- `BoxBuilder` reads `colspan` and `rowspan` as any positive `Int`, and so does it read the `span` of a `col` or `colgroup`.
- `placeCells` adds one boxed `Long` to a `HashSet` for every slot that a cell covers, `rowspan` times `colspan` entries. `<td colspan="2147483647">` asks for two billion, and `colspan="100000" rowspan="100000"` for ten billion. With `border-collapse: collapse`, `collapseBorders` fills a `HashMap` over the same slots.
- `scanColWidths` puts one map entry for each column that a `span` covers.
- The layout then makes arrays with one value per column, such as `DoubleArray(cols)` in `layoutTable`.
- The key of a slot is `row * 100_000 + col`, so in a table wider than 100,000 columns the cells of one row also land on the slots of the next.
- The DOM that a book's scripts see clamps `colSpan` to 1 to 1000 and `rowSpan` to 0 to 65534, as HTML does. The layout does not.

#### What should happen

HTML, 4.9.12.1 (forming a table): a `colspan` above 1000 counts as 1000, a `rowspan` above 65534 as 65534, and a `span` above 1000 as 1000. A `rowspan` of 0 spans to the end of the row group.

#### How to reproduce

```kotlin
// kitepdf-epub, test/BoxLayoutTest.kt (which has layout())
@Test
fun a_huge_colspan_is_clamped() {
    val root = layout("""<table><tr><td colspan="2147483647">x</td></tr></table>""")
    assertTrue(root.borderBoxHeight > 0.0) // OutOfMemoryError, or minutes of work, today
}
```

A chapter of a book with that table does the same in `EpubDocument.pageCount` or in the viewer.

#### Where

- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:763-764`: `colspan` and `rowspan`.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:617` and `:624`: `span`.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:712-724` (`placeCells`), `:645-657` (`collapseBorders`), `:606-633` (`scanColWidths`).
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxLayout.kt:1536-1546`: the column arrays.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/DomElements.kt:961`: the clamped DOM reflection.

#### Notes

Read from source.

Measured on the JVM at `c4e7574` by the lead: a one-chapter EPUB with `<td colspan="2147483647">` threw OutOfMemoryError on a 3 GB heap after 17 s, in `BoxBuilder.placeCells` (`Long.valueOf`).

### KP-13: Frames that repeat one document are followed once per frame, so a book of eight small files hangs isScripted and the page paint

Status: proven, and held back with the other security findings until they are reported.

**Frames that repeat one document are followed once per frame, so a book of eight small files hangs isScripted and the page paint**

- Repo: KitePDF
- Labels: area:epub, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #528, #613

#### What happens

- `ParsedEpub.runsScripts` lists the path of every `iframe` and `object` of a document, repeats included, and reads and tokenizes the framed document once for each entry. Only the length of the chain is capped, at 8 documents (`MAX_FRAME_NESTING`).
- So chapter c0 with 20 `<iframe src="c1.xhtml">`, c1 with 20 frames of c2, and so on to c7, make `isScripted(0)` tokenize c7 20^7 times, about 1.3 x 10^9.
- `isScripted` is public, and `scriptedChapters` calls it for every chapter. `KiteDocView` calls it on the UI thread for a tap on an EPUB page while a script handler is set (`scriptedTap`), and on the script lane for each chapter that the reader reaches. Its result is kept only once it returns.
- The paint has the same shape. `paintFrameBox` paints each frame box, and the page of the frame paints its own frame boxes, down to `MAX_FRAME_DEPTH` (8). The layouts are cached by path and size, but the paints multiply: 20^7 frame paints for one page of c0.
- `framesOf` keeps each path once, so only these two walks fan out.

#### What should happen

- Each distinct document is read once: the frames of a document as a set, and the answer kept per path.
- A chapter paints a bounded number of frames in all, not only a bounded depth of them. WebKit and Chromium stop at 1000 frames for a page.

#### How to reproduce

```kotlin
// kitepdf-epub, test/EmbedTest.kt (which has book())
@Test
fun repeated_frames_are_read_once() {
    val doc = book((0..7).map { i -> if (i < 7) """<iframe src="c${i + 1}.xhtml"></iframe>""".repeat(20) else "<p>last</p>" })
    val mark = kotlin.time.TimeSource.Monotonic.markNow()
    doc.isScripted(0)
    assertTrue(mark.elapsedNow().inWholeSeconds < 2) // about 1.3 x 10^9 tokenizations today
}
```

With 5 frames per document instead of 20, the call makes 5^7 = 78,125 tokenizations and returns in seconds, which shows the growth. `page(doc).renderTo(RecordingCanvas())` on that book paints c7 5^7 times.

#### Where

- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:166-179`: `runsScripts`, with `MAX_FRAME_NESTING` at line 941.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:1944-1952`: `paintFrameBox`, and `framePage` at line 414.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:528` and `KiteDocViewState.kt:1013`: the viewer's calls.

#### Notes

Read from source.

Measured on the JVM at `c4e7574` by the lead: opening and rendering every page of the eight-chapter book: 3 frames per chapter 260 ms, 5 frames 355 ms and 239 MiB allocated, 20 frames still running after 25 s.

### KP-14: A tiling pattern or a soft mask that paints with itself recurses until the stack overflows

Status: proven, and held back with the other security findings until they are reported.

**A tiling pattern or a soft mask that paints with itself recurses until the stack overflows**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #55, #188, #450

#### What happens

- `renderTilingPattern` replays the cell with `dispatch` and the cell's own resources. A cell whose resources name the same pattern, and which fills or strokes with it, calls `renderTilingPattern` again. Nothing counts this nesting: `formDepth` covers forms and Type 3 glyphs only, `MAX_TILES` bounds one level, and `MAX_DISPATCHED_OPS` (20 million) allows millions of levels of four operators each, far past any stack.
- A soft mask group renders in a new `PageRenderer(target, resolver)` (`renderMaskGroup`). That renderer starts with `formDepth = 0`, an op budget of its own and no `KiteCancellation`. The group resets the soft mask on entry (ISO 32000-1, 11.6.6), but `gs` sets it again (`applyExtGState` has no guard), so a group whose content selects the ExtGState that names it, then paints, renders itself again in another new renderer. Each level also opens two layers of the mask box before it recurses: `KiteRasterCanvas.applySoftMask` allocates two `IntArray(width * height)`, and `ComposeCanvas` two `saveLayer`.
- The same recursion runs through a pattern stroke (`strokeWithPattern`) and a pattern fill of text (`glyphShapes`, then `paintFill`).
- The recursion ends only when the stack overflows, or, for the soft mask, when memory runs out first. `render` has a `try`/`finally` and no `catch`, so nothing on the page after the fill draws.
- Kotlin/Native: the process ends. Opening such a page in `KiteDocView` on iOS or macOS ends the app.
- JVM and Android: a StackOverflowError (or OutOfMemoryError) leaves `renderTo`. The viewer's raster guard catches Throwable and shows a placeholder after two attempts. The tiling case also recurses during text extraction, since `StructuredTextExtractor` runs the same renderer on a collector canvas, so `page.structuredText`, `page.textContent()` and `doc.search(...)` throw StackOverflowError into the app.
- The soft mask sub-renderer also drops the caller's cancellation and op budget, so a raster that the viewer cancelled keeps drawing every soft mask group of the page (#188), and each mask group gets 20 million operators of its own.
- MuPDF stops both: `pdf_run_xobject` keeps a cycle list for every xobject it runs, soft mask groups included ("Avoid infinite recursion", `pdf-op-run.c`), and a pattern runs under its clip and layer nesting limit ("layer/clip nesting too deep"). Poppler renders both files below.

#### What should happen

- A pattern that is already painting, and a mask group that is already rendering, paint nothing when they come round again, as MuPDF's cycle list does. ISO 32000-1, 8.7.3 and 11.6.5 do not describe such a cycle, so this is salvage.
- The renderer of a mask group inherits the caller's form depth, op budget and cancellation.

#### How to reproduce

Self tiling (`selftiling.pdf`):

```
3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << /Pattern << /P1 5 0 R >> >> /Contents 4 0 R >>
4 0 obj (content) /Pattern cs /P1 scn 0 0 200 200 re f
5 0 obj << /Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 20 20] /XStep 20 /YStep 20
          /Resources << /Pattern << /P1 5 0 R >> >> /Length .. >>
stream
/Pattern cs /P1 scn 0 0 10 10 re f
endstream
```

Self soft mask (`selfmask.pdf`):

```
3 0 obj << /Type /Page ... /Resources << /ExtGState << /G1 5 0 R >> >> /Contents 4 0 R >>
4 0 obj (content) /G1 gs 0 0 200 200 re f
5 0 obj << /Type /ExtGState /SMask << /S /Luminosity /G 6 0 R >> >>
6 0 obj << /Type /XObject /Subtype /Form /BBox [0 0 200 200] /Group << /S /Transparency /CS /DeviceGray >>
          /Resources << /ExtGState << /G1 5 0 R >> >> /Length .. >>
stream
/G1 gs 1 g 0 0 200 200 re f
endstream
```

Probe, `kitepdf-pdf`, `test@jvm`, in a class whose name ends in `Test`:

```kotlin
for (name in listOf("selftiling.pdf", "selfmask.pdf")) {
    val doc = PdfDocument.open(File("$dir/$name").readBytes())
    println("$name render: " + runCatching { doc.pages[0].renderTo(KiteRasterCanvas(200, 200)) }.exceptionOrNull())
}
val tiling = PdfDocument.open(File("$dir/selftiling.pdf").readBytes())
println("text: " + runCatching { tiling.pages[0].structuredText }.exceptionOrNull())
```

Expected: StackOverflowError for each (OutOfMemoryError is possible for the mask). The same render on `-p macosArm64` ends the test binary with a signal.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1836` `renderTilingPattern`, `:1869` (the cell loads its own patterns), `:1909` (the replay), `:1698` and `:1783` (fill and stroke call it).
- `PageRenderer.kt:1954` `withSoftMask`, `:2008`, `:2097` `renderMaskGroup`, `:2107` the new renderer with no depth, budget or cancellation.
- `PageRenderer.kt:1029` (the form depth guard these paths skip), `:1293` and `:2860` (the per-renderer op budget).
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/GraphicsState.kt:108` `applyExtGState`.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteRasterCanvas.kt:205-215` (two full layers per level).

#### Notes

Read from source. The tests cover a tiling pattern (`TilingPatternTest`) and soft masks (`SoftMaskBackdropTest`, `TransparencyTest`), but none that refers to itself. MuPDF reference: `pdf_run_xobject` and `pdf_show_pattern` in `source/pdf/pdf-op-run.c` (1.27.2).

Measured on the JVM at `c4e7574` by the lead: `selftiling.pdf` throws StackOverflowError out of `renderTo` and out of `structuredText` on a thread with the default stack; the PoC of the rendering lane (`tiling-self-recursion.pdf`, a pattern that fills with itself, followed by a blue square) still overflows on a thread with a 512 MiB stack, after 13.9 s and 2.8 GiB of allocation; `selfmask.pdf` throws StackOverflowError under `AwtPdfRasterizer`. A `RecordingCanvas` does not reach the soft mask case. Found by two lanes.

### KP-15: A `/Kids` that names its own node sends the page label, named destination and form field walks into endless recursion

Status: proven, and held back with the other security findings until they are reported.

**A `/Kids` that names its own node sends the page label, named destination and form field walks into endless recursion**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #55, #450

#### What happens

Three walkers follow `/Kids` through `resolve` with neither a visited set nor a depth:

- `PageLabelTree.walk`, behind `PdfPage.label`.
- `DestinationCatalog.walkNameTree`, behind `PdfDocument.resolveDestination`, `PdfDocument.outline` and every PDF link tap in the viewer (`KiteDocView.kt:565`). `outline` resolves the destination of each entry, and the first call builds the whole `/Names /Dests` tree, even when every entry has an explicit destination.
- `PdfFormField.walk`, for kids that carry `/T`, behind `formFields`, `widgetOwners`, `PdfPage.widgetAt`, `signatures` and the form APIs. Each level also builds the dotted name again (`a.a.a...`), so the strings on the stack grow with the square of the depth.

A node `5 0 obj << /Kids [5 0 R] >>` recurses until the stack overflows.

- Kotlin/Native: the process ends, on the first `label`, outline, link tap or form access.
- JVM and Android: StackOverflowError, an Error, leaves `page.label`, `doc.outline` and `doc.formFields`. `skipIfBroken` in `PdfFormField` catches `Exception` only, and a `lazy` that throws runs again on the next access. `KiteOutlinePanel` reads `state.document.outline` as a default argument, in composition on the UI thread, so showing the outline panel of such a file crashes the app on every platform.
- The generic `NameTreeWalker` and `NumberTreeWalker` (`parser/NameTree.kt`) carry a depth cap of 64. These three walkers do not use them.
- MuPDF keeps a `pdf_cycle_list` while it loads a name tree (`pdf_load_name_tree_imp` in `pdf-nametree.c`). Poppler opens and renders all three files below.

#### What should happen

Each walk keeps the object numbers it has entered and skips a kid it has seen, and keeps a depth cap as a backstop, as `walkPageTree` does. A cycle is malformed (ISO 32000-1, 7.9.6, 7.9.7 and 12.7.3.1 describe trees), so the walk salvages what it reached.

#### How to reproduce

```
% page labels (pagelabels-cycle.pdf)
1 0 obj << /Type /Catalog /Pages 2 0 R /PageLabels 5 0 R >>
5 0 obj << /Kids [5 0 R] >>

% named destinations, with one outline entry whose destination is explicit (dests-cycle.pdf)
1 0 obj << /Type /Catalog /Pages 2 0 R /Names << /Dests 5 0 R >> /Outlines 6 0 R >>
5 0 obj << /Kids [5 0 R] >>
6 0 obj << /Type /Outlines /First 7 0 R /Last 7 0 R /Count 1 >>
7 0 obj << /Title (Start) /Parent 6 0 R /Dest [3 0 R /Fit] >>

% form fields (field-cycle.pdf)
1 0 obj << /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [5 0 R] >> >>
5 0 obj << /T (a) /FT /Tx /Kids [5 0 R] >>
```

Probe, `kitepdf-pdf`, `test@jvm`:

```kotlin
println(runCatching { open("pagelabels-cycle.pdf").pages[0].label }.exceptionOrNull())   // StackOverflowError
println(runCatching { open("dests-cycle.pdf").outline }.exceptionOrNull())               // StackOverflowError
println(runCatching { open("field-cycle.pdf").formFields }.exceptionOrNull())            // StackOverflowError or OutOfMemoryError
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PageLabels.kt:100-129` (`walk`), reached from `PdfDocument.kt:504` and `PdfPage.kt:61`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDestination.kt:72` and `:76-98` (`walkNameTree`), reached from `PdfDocument.kt:513`, `:549-558` and `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteDocView.kt:565`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfFormField.kt:335-367` (`walk`), `:351` (the name), `:228-234` (`skipIfBroken` catches `Exception`).
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/KiteWidgets.kt:280` (the outline read in composition).

#### Notes

Read from source. The tests cover cycles in object streams, forms (`TextExtractorScopesTest`, `RedactionFormCloneTest`), colour spaces (`SeparationLabTest`), visibility expressions (`AnnotationRulesTest`) and article threads (`ArticleThreadTest`), not in these three trees.

Measured on the JVM at `c4e7574` by the lead: with the default thread stack, `outline` throws StackOverflowError in `DestinationCatalog.walkNameTree`, `formFields` in `PdfFormField.walk`, `signatures` in `PdfFormField.resolveDictRef`, and `PdfPage.label` in `PageLabelTree.walk`.

### KP-16: A function that names itself, or a Type 4 program of deep braces, recurses until the stack overflows

Status: proven, and held back with the other security findings until they are reported.

**A function that names itself, or a Type 4 program of deep braces, recurses until the stack overflows**

- Repo: KitePDF
- Labels: area:core, area:pdf, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #128, #450

#### What happens

- `KiteFunction.parse` follows a function array and the `/Functions` of a stitching function (Type 3) through `resolve`, with no cycle list and no depth. A shading whose `/Function 6 0 R` is `6 0 obj [6 0 R]`, or `6 0 obj << /FunctionType 3 /Domain [0 1] /Functions [6 0 R] /Bounds [] /Encode [0 1] >>`, recurses until the stack overflows.
- `PostScriptCalc.parseBlockBody` recurses once for each `{` of a Type 4 program, with no limit, and `exec` once for each nested `if` or `ifelse`. A tint transform of 200,000 `{` is 400 KB of program and about 1 KB after Flate.
- Functions are parsed when a page loads its resources (`loadShadings`, `loadColorSpaces`, a soft mask's `/TR`), so a render and a text pass both reach them.
- Kotlin/Native: the process ends. On a 512 KB pool thread of an Apple platform a few thousand braces are enough. No `runCatching` helps there.
- JVM: most paths overflow and then salvage, because a `runCatching` (which catches Throwable) sits around `compile`, around each shading (`KiteShading.parseDict`) and around each colour space (`ContentStreamParser.colorSpaces`). The soft mask transfer function has none: `loadExtGStates` calls `ExtGState.parse`, which calls `KiteFunction.parse` for `/TR`, so a `/TR` cycle throws StackOverflowError out of `render` (the viewer shows a placeholder) and out of `structuredText`, `textContent()` and `doc.search` into the app.
- MuPDF: `pdf_load_function_imp` keeps a `pdf_cycle_list` and throws "recursive function", and `parse_code` stops at 100 levels with "too much nesting in calculator function" (`pdf-function.c`). Poppler, which has no such limit, ends with a segmentation fault on `ps-nesting.pdf`, which shows that the file reaches deep recursion in a real reader.

#### What should happen

`parse` keeps the functions it has entered and returns null for a function that refers to itself, and the calculator parser stops at a fixed depth, as MuPDF does (ISO 32000-1, 7.10.4 and 7.10.5).

#### How to reproduce

`functioncycle-array.pdf` and `functioncycle-stitch.pdf`: a page with content `/Sh1 sh` and `/Shading << /Sh1 5 0 R >>`, where `5 0 obj << /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 200 0] /Function 6 0 R >>` and object 6 is one of the two forms above.

`ps-nesting.pdf`: content `/CS1 cs 0.5 scn 0 0 200 200 re f`, `/ColorSpace << /CS1 [/Separation /Spot /DeviceGray 5 0 R] >>`, and object 5 a Flate stream `<< /FunctionType 4 /Domain [0 1] /Range [0 1] >>` holding 200,000 `{` then 200,000 `}`.

`functioncycle-tr.pdf`: content `/G1 gs 0 0 200 200 re f`, `5 0 obj << /Type /ExtGState /SMask << /S /Luminosity /G 7 0 R /TR 6 0 R >> >>`, `6 0 obj [6 0 R]`, and object 7 a transparency group form.

Probe, `kitepdf-pdf`, `test@jvm`:

```kotlin
val tr = open("functioncycle-tr.pdf")
println(runCatching { tr.pages[0].renderTo(KiteRasterCanvas(200, 200)) }.exceptionOrNull())   // StackOverflowError
println(runCatching { tr.pages[0].structuredText }.exceptionOrNull())                          // StackOverflowError
```

The other three files draw on the JVM after the overflow is caught, and each one ends a `-p macosArm64` or `-p linuxX64` test binary that renders its page.

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteFunction.kt:265-279` (`parse`, the array case at `:272`), `:307` (stitching sub-functions), `:316` (the `runCatching` around `compile`).
- `KiteFunction.kt:351` `compile`, `:381-395` `parseBlockBody`, `:398` `exec`, `:479-480` `if` and `ifelse`.
- Callers: `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteShading.kt:263`, `:284`, `:303` (inside the `runCatching` of `parseDict`, `:247`), `KiteColorSpace.kt:405`, `:415` (inside the `runCatching` of `kitepdf-pdf/.../content/ContentStream.kt:90`), `MeshShading.kt:54`, `ExtGState.kt:133` (no catch on the way from `PageRenderer.kt:507-512`).

#### Notes

Read from source. `FunctionEvalTest` has no nested or self-referring case. The colour space walk has its own cap of 8 (`KiteColorSpace.kt:322`), which does not cover the function inside.

Measured on the JVM at `c4e7574` by the lead: the stitching and array cycles render on the JVM (the `runCatching` of the shading and colour space paths recovers); the `/TR` cycle throws StackOverflowError out of `renderTo` and out of `structuredText`; `ps-nesting.pdf` renders on the JVM even on a 512 KB stack (the compile's `runCatching` catches the overflow). The Kotlin/Native crash was not run.

### KP-17: The parser's nesting cap and the resolution depth cap multiply, so a 150 KB file needs about 130,000 stack frames

Status: proven, and held back with the other security findings until they are reported.

**The parser's nesting cap and the resolution depth cap multiply, so a 150 KB file needs about 130,000 stack frames**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: critical
- Confidence: Likely
- Related: #450

#### What happens

- `Parser` allows 256 nested arrays and dictionaries, counted per parser.
- A stream dictionary whose `/Length` is a reference resolves it while the parser is still inside its containers. `resolveInPlace` parses the target with a new `Parser`, whose count starts at 0, and `withCycleClaim` allows 256 such levels.
- So object 10 made of 255 nested arrays around `<< /Length 11 0 R >> stream`, with object 11 the same shape, and so on for 256 objects, recurses about 256 x (2 x 255 + 10), or 133,000 frames deep. Each cap was tested alone: `ResolutionGuardTest.deep_length_chain_hits_depth_cap_not_stack` builds the chain without containers, about 2,500 frames, and passes on native targets.
- JVM: the innermost `runCatching` (in `resolveStreamLengthOrNull` or `resolve`) catches the StackOverflowError, the stream falls back to the `endstream` scan, and the walk recovers.
- Kotlin/Native: nothing catches it. 133,000 frames fit in the 8 MB main thread only if a frame is under 64 bytes, and the viewer resolves on a 512 KB pool thread on Apple platforms. If the catalog's `/Pages` points into the chain, `pageCount` ends the process right after `open`.
- Poppler opens and renders the file.

#### What should happen

One depth budget covers the whole recursion: the parser of a nested resolve starts from the depth of the parser that asked for it, or the resolution depth counts container levels too, so the product stays within what a 512 KB stack holds.

#### How to reproduce

Probe, `kitepdf-pdf`, `test`, run with `-p macosArm64` or `-p linuxX64`:

```kotlin
private fun nestedLengthChainPdf(arrays: Int = 255, chain: Int = 256): ByteArray {
    val sb = StringBuilder("%PDF-1.4\n")
    val offsets = HashMap<Int, Int>()
    fun obj(n: Int, body: String) { offsets[n] = sb.length; sb.append("$n 0 obj\n$body\nendobj\n") }
    obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
    obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>")
    for (i in 0 until chain) {
        val n = 10 + i
        val length = if (i + 1 < chain) "${n + 1} 0 R" else "1"
        obj(n, "[".repeat(arrays) + "<< /Length $length >>\nstream\nx\nendstream" + "]".repeat(arrays))
    }
    val size = 10 + chain
    val xrefAt = sb.length
    sb.append("xref\n0 $size\n0000000000 65535 f \n")
    for (n in 1 until size) {
        sb.append(offsets[n]?.let { it.toString().padStart(10, '0') + " 00000 n \n" } ?: "0000000000 65535 f \n")
    }
    sb.append("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")
    return sb.toString().encodeToByteArray()
}

@Test fun nested_containers_times_length_chain() {
    PdfDocument.open(nestedLengthChainPdf()).resolve(PdfReference(10, 0))
}
```

Expected: returns on the JVM, ends the native test binary with a signal. The same file is `nested-length-chain.pdf` (with a page that draws object 10 as an XObject).

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/Parser.kt:52-60` (`nested`), `:115`, `:153`, `:302` (`MAX_NESTING`, per parser), `:206` and `:252-265` (`/Length` resolved from inside the containers).
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:820-848` (`withCycleClaim`), `:851-866` (`resolveInPlace`, a new parser at `:860`), `:1014` (`MAX_RESOLUTION_DEPTH`).
- `kitepdf-pdf/test/io/github/yuroyami/kitepdf/ResolutionGuardTest.kt:105` (the chain tested without containers).

#### Notes

Read from source and counted. Likely, not Confirmed: the frame size of `readArray` and `readObject` on Kotlin/Native was not measured; the probe above settles it.

Measured on the JVM at `c4e7574` by the lead: `nested-length-chain.pdf` opens and renders on the JVM on a thread with a 512 KB stack, so the crash is a Kotlin/Native matter and stays Likely.

### KP-18: A redacted copy of an encrypted PDF can only be saved without encryption, and nothing can encrypt it again

Status: proven, and held back with the other security findings until they are reported.

**A redacted copy of an encrypted PDF can only be saved without encryption, and nothing can encrypt it again**

- Repo: KitePDF
- Labels: area:pdf, kind:api
- Severity: high
- Confidence: Confirmed

#### What happens

Each piece is documented, but together they leave no safe path:

- A redaction must be saved with `saveRewritten()`. `saveIncremental()` throws `IllegalStateException` after `redactRegions`, so the old content cannot stay in the file.
- `saveRewritten()` drops the `/Encrypt` dictionary and writes every object in plain text (the class KDoc of `PdfEditor` says so; the KDoc of `saveRewritten()` itself does not).
- `PdfEditor` has no way to encrypt its output, and `PdfBuilder.encrypt` only applies to a new document. There is no API that takes existing bytes and encrypts them.
- `docs/editing.md` says "Encrypt the file again before you share it". KitePDF cannot do that.

So an app that redacts a confidential, password-protected PDF hands back a copy that opens without any password, with all the content that was not redacted. The permissions go with it: a file opened with the user password, whose `/P` forbids changes, can be edited and rewritten, and the copy has no restrictions at all.

#### What should happen

- `saveRewritten()` keeps the encryption of the document by default: the same handler, a new `/ID` second element, new IVs, and the `/Encrypt` dictionary written unencrypted, as `saveIncremental()` already does. A parameter can opt out.
- Or `PdfEditor` gets an `encrypt(userPassword, ownerPassword, permissions, random)` that applies to the rewritten output, and `PdfBuilder`'s encryptor is shared with it.
- The KDoc of `saveRewritten()` states what happens to the encryption.
- Decide whether `edit()` respects `/P` when the document opened with the user password only. ISO 32000-1, 7.6.3.1 asks a conforming reader to restrict access according to the permissions in the file. Document the choice either way.

#### How to reproduce

Measured on the JVM at `c4e7574`:

```kotlin
val rnd = SecureRandom().asKotlinRandom()
val pdf = PdfBuilder().page { text(StandardFont.Helvetica, 12.0, 72.0, 700.0, "Secret page text") }
    .encrypt(userPassword = "user", ownerPassword = "owner",
        permissions = PdfPermissions.allowAll.copy(canModifyContents = false), random = rnd)
    .build(compress = true)
val rewritten = PdfDocument.open(pdf, "user").edit(rnd).apply { setInfo(title = "Rewritten") }.saveRewritten()
PdfDocument.open(rewritten).isEncrypted   // false: no password needed
```

`pdfinfo` reports the input as `Encrypted: yes (... change:no ... algorithm:AES-256)` and the rewritten file as `Encrypted: no`. `pdftotext` reads "Secret page text" from it without a password.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:52-57`: the class KDoc.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:1069-1077`: `saveRewritten()` and its KDoc.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfBuilder.kt:61-75`: the only encrypt entry point.
- `docs/editing.md:294-295`: the warning that asks for something the API cannot do.

#### Notes

Measured on the JVM, checked with Poppler 24.02 (`pdfinfo`, `pdftotext`).

### KP-21: A content stream operator can take any number of operands, and the operation cache counts them as nothing

Status: proven, and held back with the other security findings until they are reported.

**A content stream operator can take any number of operands, and the operation cache counts them as nothing**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #118, #218, #224

#### What happens

- `ContentStreamParser.parse` pushes every operand onto `operandStack` until the next operator, with no limit (`ContentStream.kt:163`). At the operator it copies the whole stack into the `Operation` (`:154`). `MAX_OPS_PER_STREAM` counts operations, not operands, so it does not apply.
- Each integer operand is a new `PdfInt`: 24 bytes on the JVM, plus 4 bytes for its slot in the list. `0 ` repeated 5,000,000 times and then `n` is 10 MB of content, about 10 KB as Flate, and the parse holds about 140 MB in one `Operation`. 50,000,000 operands (100 MB decoded, well under the 512 MiB filter cap) need about 1.4 GB.
- `ContentStreamParser.retainedBytes` counts 104 bytes per operation plus the bytes of strings, arrays and inline images (`:99` to `:114`). An integer, real, name or dictionary operand counts 0. So the 140 MB operation is counted as 104 bytes and enters the operation cache (`PdfDocument.kt:395`, budget 16 MB). The trim removes it only when other content fills the budget. Ten such pages keep about 1.4 GB alive in a cache that believes it holds 1 KB, and `dropOperationCache()` is the only way out.
- On Android the 5,000,000-operand parse can already run out of a 192 MB heap. The rasterizer catches `OutOfMemoryError` (#219), but any other thread that allocates in that moment can die with it. On iOS a process that grows by 1.4 GB is ended by the system.
- MuPDF keeps content operands on a fixed stack of 32 numbers (`float stack[32]` in `interpret.h`) and raises a syntax error, which it recovers from, past it (`pdf-interpret.c`, `"stack overflow"`).

#### What should happen

- An operator keeps a bounded number of operands, as MuPDF keeps 32. The most any operator of ISO 32000-1, Annex A needs is `SCN` or `scn` in a DeviceN space: one per colorant, at most 32 (Annex C, Table C.1), plus a pattern name. Past a small fixed bound the parser drops operands and warns once.
- `retainedBytes` counts every operand, so the cache budget means what it says.

#### How to reproduce

Probe, `kitepdf-pdf`, `test@jvm` (the class name must end with `Test`):

```kotlin
class OperandBudgetTest {
    @Test
    fun operands_have_a_limit_and_a_cost() {
        val content = "0 ".repeat(5_000_000) + "n"
        val rt = Runtime.getRuntime()
        System.gc(); val before = rt.totalMemory() - rt.freeMemory()
        val ops = ContentStreamParser.parse(content.encodeToByteArray())
        System.gc(); val after = rt.totalMemory() - rt.freeMemory()
        println("operands=${ops.single().operands.size} retained=${(after - before) shr 20} MB estimate=${ContentStreamParser.retainedBytes(ops)} B")
        // today: operands=5000000 retained=~140 MB estimate=104 B
    }
}
```

To see the cache keep it, put the same content in a page of a test PDF as one Flate stream, render the page twice with `NoopCanvas`, and check that `document.operationParseCount` stays at 1 while the used heap stays about 140 MB higher after a GC.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/content/ContentStream.kt:154`, `:163` (no limit), `:68` (`MAX_OPS_PER_STREAM`), `:99` to `:114` (`retainedBytes`, `stringBytes`).
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:382` to `:400` (`operations`, the cache that trusts the estimate).

#### Notes

Read from source. Memory per operand is the JVM layout with compressed pointers; Kotlin/JS keeps a `Long` as an object of its own, so a `PdfInt` costs more there.

### KP-22: Repair scans to the end of the file once for every stream that has no endstream, so a 4.5 MB file blocks open for minutes

Status: proven, and held back with the other security findings until they are reported.

**Repair scans to the end of the file once for every stream that has no endstream, so a 4.5 MB file blocks open for minutes**

- Repo: KitePDF
- Labels: area:pdf, kind:perf
- Severity: high
- Confidence: Confirmed

#### What happens

- Pass 1 of `PdfRepair.rebuild` finds the next `obj` keyword with a byte search that starts 3 bytes after the last one (`PdfRepair.kt:59`), not after the end of the object it just parsed, and parses every object it finds in full (`:65` to `:69`).
- `Parser.readDictionaryThenMaybeStream` looks for `endstream` from the stream start when `/Length` is missing, indirect (repair has no resolver) or wrong (`Parser.kt:212` to `:214`). When there is no `endstream` after it, `ByteReader.indexOf` runs to the end of the file and the parse throws.
- So each stream without `endstream` costs a scan of the rest of the file. 200,000 objects `N 0 obj<<>>stream` in a 4.5 MB file make 455 billion byte positions to check (counted by the generator script below). At about one nanosecond a position that is about 8 minutes on the JVM, longer on Kotlin/Native and JS, and four times as long for each doubling of the file.
- `open` runs repair on the caller's thread, with no cancellation and no budget. The normal path has the same cost when a page's `/Contents` array names many such streams: each one scans to the end once when the page content is first decoded.
- MuPDF's repair lexes forward from where the last object ended. After a stream that has no `endstream` its scan is at the end of the file, so the pass ends (`pdf_repair_obj` and the main loop of `pdf_repair_xref`, `pdf-repair.c`). The work is linear.

#### What should happen

The repair scan continues after the end of the object it parsed (after `endstream` or `endobj`), and an `endstream` search that reaches the end of the file ends the scan for the objects after it, as MuPDF does. The cost of repair stays linear in the size of the file.

#### How to reproduce

```
python3 -I audit-repro/L2/scripts/make_no_endstream.py out.pdf 200000
# bytes 4489015 objects 200000 positions scanned 454947583805
```

Probe, `kitepdf-pdf`, `test` (any platform), timing two sizes to show the square:

```kotlin
class RepairScanCostTest {
    private fun file(n: Int) = buildString {
        append("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n2 0 obj << /Type /Pages /Kids [] /Count 0 >> endobj\n")
        for (i in 3 until n + 3) append("$i 0 obj<<>>stream\n")
    }.encodeToByteArray()

    @Test
    fun repair_is_linear() {
        for (n in listOf(25_000, 50_000)) {
            val t = kotlin.time.measureTime { PdfDocument.openOrNull(file(n)) }
            println("$n objects: $t")   // today the second line is about 4 times the first
        }
    }
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/PdfRepair.kt:56` to `:69` (the search resumes inside the object).
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/Parser.kt:212` to `:214` (the `endstream` scan).
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/ByteReader.kt:97` to `:106` (`indexOf`).

#### Notes

Read from source; the position count comes from the generator. The same resume point makes repair read objects inside stream data, see the finding on repair copies below.

Measured on the JVM at `c4e7574` by the lead: `PdfDocument.open` on `no-endstream.pdf` (4.5 MB, written by `audit-repro/L2/scripts/make_no_endstream.py` and not committed) was still running after 20 s. Poppler's `pdfinfo` gives up on the same file in 85 ms.

### KP-23: An Indexed colour space with a large hival allocates hival + 1 colours

Status: proven, and held back with the other security findings until they are reported.

**An Indexed colour space with a large hival allocates hival + 1 colours**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: high
- Confidence: Confirmed

#### What happens

- `KiteColorSpace.resolveIndexed` keeps `hival` as the file gives it. `Indexed.lut` makes an array of `hival + 1` colours the first time a colour is read, and `cs` reads one through `defaultColor()`.
- `hival` 2147483647 overflows to `Array(-2147483648)`, which throws `NegativeArraySizeException`. `dispatch` and `render` do not catch it, so the page stops drawing at that `cs`, and the exception leaves `renderTo` and `structuredText`.
- `hival` 1000000000 asks for a billion references, 4 GB or more, which is past the heap of the JVM gate and of any phone. On Kotlin/Native and in a browser, running out of memory ends the process.

#### What should happen

ISO 32000-1, 8.6.6.3 limits hival to 0 to 255. A larger value is clamped to 255, so the palette never has more than 256 entries. Neither MuPDF nor PDFium accepts a hival above 255.

#### How to reproduce

`pdfs/indexed-hival-max.pdf`: `/ColorSpace << /CS0 [/Indexed /DeviceRGB 2147483647 <FF0000>] >>` and `/CS0 cs 0 sc 0 0 100 100 re f 0 0 1 rg 100 100 100 100 re f`. Expected: a red square and a blue one. `pdfs/indexed-hival-1e9.pdf` uses 1000000000.

```kotlin
// kitepdf-core/test
@Test fun a_huge_hival_is_clamped() {
    val red = KiteColorSpace.Indexed(KiteColorSpace.DeviceRGB, Int.MAX_VALUE, byteArrayOf(-1, 0, 0)).defaultColor()
    assertEquals(RgbColor(1.0, 0.0, 0.0), red)   // NegativeArraySizeException today
}
```

#### Where

- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteColorSpace.kt:150`: `Array(hival.coerceAtLeast(0) + 1)`.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteColorSpace.kt:451`: `hival` read with no upper bound.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1376`: `cs` builds the table through `defaultColor()`.

#### Notes

Read from source. `DefaultColorSpaces.imageSpace` (line 85) copies the same `hival` into a new `Indexed`.

Measured on the JVM at `c4e7574` by the lead: under `AwtPdfRasterizer`, `indexed-hival-1e9.pdf` throws OutOfMemoryError and `indexed-hival-max.pdf` throws `NegativeArraySizeException: -2147483648`, both from `KiteColorSpace.Indexed.lut` at the `cs` operator, so the whole render fails.

### KP-25: RevisionChanges calls a change to page content a field or annotation appearance when an appearance also names the object

Status: proven, and held back with the other security findings until they are reported.

**RevisionChanges calls a change to page content a field or annotation appearance when an appearance also names the object**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #448

#### What happens

`placesOf` walks the form first. For each field it marks every object that the field's `/AP` reaches as `WIDGET_LOOK` (`reach` follows every key but `/P` and `/Parent`). Then it walks the pages and marks every object an annotation's `/AP` reaches as `ANNOTATION_LOOK`. It never marks a page's content streams or resources, and the first mark wins.

So an object that both the page and an appearance use takes the appearance's role. A change to it is "the appearance of field X" (`FieldValue`, permitted from level 2) or "the appearance of a ... annotation" (`Annotation`, permitted at level 3, which is the rule for every approval signature). This holds for a font or a form XObject that a page and an appearance share, and for a content stream that an appearance's `/Resources` names without drawing it.

A document prepared this way before signing can have its page content replaced after signing, and `areChangesPermitted` is true. This is the "replace" shadow attack.

#### What should happen

An object that a page or anything outside the appearance also uses keeps the strictest role, or is classified as page content. One way: mark the page content and resources first, and let `reach` from `/AP` only claim objects that nothing else reaches.

#### How to reproduce

Common test:

```kotlin
@Test
fun page_content_that_an_appearance_names_is_not_a_field_value() {
    val base = TestPdf.build(listOf(
        "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [5 0 R] >> >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R /Annots [5 0 R] >>",
        TestPdf.stream("0 0 1 rg 20 20 50 50 re f"),
        "<< /Type /Annot /Subtype /Widget /FT /Tx /T (Name) /Rect [20 150 180 170] /P 3 0 R /AP << /N 6 0 R >> >>",
        TestPdf.stream("", "/Type /XObject /Subtype /Form /BBox [0 0 160 20] /Resources << /XObject << /X 4 0 R >> >>"),
    ))
    val old = PdfDocument.open(base)
    val content = "1 0 0 rg 20 20 160 120 re f"
    val new = PdfDocument.open(appendRevision(base, mapOf(4 to "<< /Length ${content.length} >>\nstream\n$content\nendstream"), size = 7))
    val change = RevisionChanges.between(old, new, level = 2, lock = null).single()
    assertEquals(PdfRevisionChange.Kind.Other, change.kind)   // fails today: FieldValue
    assertFalse(change.isPermitted)                          // fails today: true
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/RevisionChanges.kt:182` (form walk first), `:211` (pages second), `:216` to `:232` (`reach`), `:110`, `:111`

#### Notes

Read from source.

### KP-27: Redacting a form XObject on one page also redacts every other page that draws it

Status: proven, and held back with the other security findings until they are reported.

**Redacting a form XObject on one page also redacts every other page that draws it**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Confirmed

#### What happens

`recurseIntoForms` decides whether a form may be rewritten in place from the `Do` operations of the current page only (`pristine`). When every invocation on that page meets a region, `redactFormXObject` claims the original object and stages the rewrite over it (`target = formRef`). Another page that draws the same form, through its own resources or shared ones, now draws the redacted copy, and gets no black box.

The KDoc and `docs/editing.md` promise the opposite: "A place that no region meets keeps the original form." A letterhead, logo or footer drawn as one form on every page loses its content on every page when page 1 is redacted.

#### What should happen

A form that any other page, form, pattern or annotation can draw is cloned for the redacted invocation, as a form with a pristine invocation is today. Only a form that nothing else reaches is rewritten in place.

#### How to reproduce

Common test:

```kotlin
@Test
fun redacting_page_one_keeps_the_form_that_page_two_draws() {
    val pdf = TestPdf.build(listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /XObject << /Fm1 6 0 R >> >> /Contents 7 0 R >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /XObject << /Fm1 6 0 R >> >> /Contents 8 0 R >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        TestPdf.stream("BT /F1 24 Tf 100 700 Td (ACME LETTERHEAD) Tj ET", "/Type /XObject /Subtype /Form /BBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >>"),
        TestPdf.stream("q /Fm1 Do Q"),
        TestPdf.stream("q /Fm1 Do Q"),
    ))
    val doc = PdfDocument.open(pdf)
    val out = PdfDocument.open(doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(90.0, 690.0, 400.0, 730.0)) }.saveRewritten())
    assertTrue("ACME LETTERHEAD" in out.pages[1].extractText())   // fails today
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditorRedaction.kt:159` (`pristine` from this page only), `:238`, `:239`, `:275`

#### Notes

Read from source. Every test of `RedactionFormCloneTest` has one page.

Measured on the JVM at `c4e7574` by the lead: after a redaction on page 1 only, page 2's `extractText()` is empty: `ACME LETTERHEAD` is gone from both pages. `docs/editing.md` (What redaction removes) promises a copy of the form for each place that needs a different redaction.

### KP-28: Redaction keeps the page's thumbnail and the private data of the application that made the page

Status: proven, and held back with the other security findings until they are reported.

**Redaction keeps the page's thumbnail and the private data of the application that made the page**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Confirmed

#### What happens

`redactRegions` rebuilds the page dictionary with `withEntry`, which keeps every other key. Two of them hold the page as it was:

- `/Thumb` is an image of the whole page (ISO 32000-1, 12.3.4). It shows the redacted region, photos and large text included.
- `/PieceInfo` holds private data of the application that made the page (14.5). Illustrator's "preserve editing capabilities" stores the whole artwork there, text included.

`saveRewritten` reaches both through the page and writes them. `docs/editing.md` does not list either.

#### What should happen

A redacted page loses `/Thumb` (or gets a thumbnail drawn from the redacted page) and `/PieceInfo`. The docs say so.

#### How to reproduce

Common test:

```kotlin
@Test
fun redaction_drops_the_thumbnail_and_private_data_of_the_page() {
    val pdf = TestPdf.build(listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R /Thumb 6 0 R /PieceInfo << /Illustrator << /Private 7 0 R >> >> >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        TestPdf.stream("BT /F1 24 Tf 100 700 Td (SECRET) Tj ET"),
        TestPdf.stream("THUMBPIXELSOFTHEPAGE", "/Width 20 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8"),
        TestPdf.stream("%AI private: BT (SECRET) Tj ET"),
    ))
    val doc = PdfDocument.open(pdf)
    val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(90.0, 690.0, 300.0, 730.0)) }.saveRewritten()
    assertFalse(RawPdf.containsBytes(out, "THUMBPIXELSOFTHEPAGE".encodeToByteArray()))   // fails today
    assertFalse(RawPdf.containsBytes(out, "(SECRET)".encodeToByteArray()))              // fails today
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:884` to `:897` (the page rebuilt with every key kept)
- `docs/editing.md`, "What redaction removes"

#### Notes

Read from source. Both entries are uncommon in office documents, but a redaction that keeps them leaks the region in full.

Measured on the JVM at `c4e7574` by the lead: the rewritten output still contains the thumbnail bytes and the private `(SECRET)` stream.

### KP-29: Revocation data of any date counts: a CRL that expired years before the signing says Good

Status: proven, and held back with the other security findings until they are reported.

**Revocation data of any date counts: a CRL that expired years before the signing says Good**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #634, #447

#### What happens

`Crl.parse` checks that `thisUpdate` is present and skips `nextUpdate`. `Crl.check` says Good for every serial the list does not name. `OcspResponse` skips `producedAt` and never reads `thisUpdate` or `nextUpdate`. `revocationOf` never compares a time with the signing time.

A signer whose certificate was revoked can embed a CRL or an OCSP response that the issuer published before the revocation, in `/DSS` or in `adbe-revocationInfoArchival`. `PdfCertificate.revocation` is then `Good`. KitePDF fetches nothing, so an app that relies on what the file carries is told the certificate is good.

The CRL scope is not checked either: a CRL with an `issuingDistributionPoint` that covers only CA certificates or one partition (RFC 5280, 5.2.5) says Good for every certificate outside it.

#### What should happen

A CRL or OCSP response counts only when its validity covers the time it is used for (RFC 5280, 6.3.3 (a)(1); RFC 6960, 4.2.2.1): for a signature, its `thisUpdate` is at or after the signing time, or the result says the data predates it. A CRL whose scope does not cover the certificate does not count.

#### How to reproduce

JVM test, in `RevocationTest` (its `validate` helper signs at 2026-09-28):

```kotlin
@Test
fun aCrlThatExpiredBeforeTheSigningSaysNothing() {
    fun crl(issuer: SigFixtures.Identity, thisUpdate: String, nextUpdate: String): ByteArray {
        val tbs = SigFixtures.seq(SigFixtures.int(1), issuer.signatureAlgorithm, issuer.name, SigFixtures.utcTime(thisUpdate), SigFixtures.utcTime(nextUpdate))
        return SigFixtures.seq(tbs, issuer.signatureAlgorithm, SigFixtures.bits(issuer.sign(tbs)))
    }
    val stale = crl(root, "200101000000Z", "200201000000Z")
    assertEquals(PdfRevocation.Unknown, validate(PdfOptions(dssCrls = listOf(stale))).signer?.revocation)   // fails today: Good
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/sig/Revocation.kt:27`, `:41`, `:42`, `:88`, `:136`
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/crypto/sig/SignatureValidator.kt` (`revocationOf`)

#### Notes

Read from source. #447 notes that "a revocation check needs the time of signing too"; #634 covers only the certificates' own dates.

### KP-31: A WOFF or WOFF2 font can make the layout inflate and hold gigabytes, because no table size is checked against the font's total

Status: proven, and held back with the other security findings until they are reported.

**A WOFF or WOFF2 font can make the layout inflate and hold gigabytes, because no table size is checked against the font's total**

- Repo: KitePDF
- Labels: area:epub, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #218, #224

#### What happens

- WOFF 1.0: a table whose `compLength` is below its `origLength` inflates with `maxOutputBytes = FilterChain.MAX_DECODED_STREAM`, the 512 MiB cap of PDF streams, and is cut to `origLength` only afterwards. So a table that declares 600 KB over a 500 KB zlib stream inflates up to 500 MiB first. (A table whose `compLength` is not below its `origLength` is taken as stored, so it does not inflate.) Nothing checks the declared `origLength` of the tables against the header's `totalSfntSize`, or against any ceiling for a font.
- Every inflated table stays in `payloads` until the SFNT is put together, and two directory entries may point at the same compressed bytes. A 510 KB WOFF with four entries over one zlib stream of 500 MiB, each declaring an `origLength` of 500 MiB, holds 2 GiB of tables and then asks for a 2 GiB output array (`ByteArray(pos)`).
- WOFF2: `Brotli.decode(compressed, maxOutputBytes = FilterChain.MAX_DECODED_STREAM)`. The table directory already gives the size of the decompressed block (`srcOffset`, the sum of the table lengths), but nothing caps the decode with it. The output buffer doubles up to 512 MiB, so the peak is about 768 MiB, plus the `copyOf` of the result. A meta-block of Brotli holds up to 16 MiB, so a stream of a few hundred bytes describes that much output.
- An app on Android has a heap of 192 to 512 MB, and iOS ends an app that grows that far. The JVM suites run on a 3 GB heap (CLAUDE.md, #218), and no test decodes a font that declares less than it holds.
- The `runCatching` around `Zlib.decode` catches an `OutOfMemoryError` there, but not the one of `ByteArray(pos)`, and another thread that allocates at that moment fails too.

#### What should happen

- WOFF 1.0 gives the length of each uncompressed table in the table directory (`origLength`), and the size of the whole font in the header (`totalSfntSize`). Inflate each table with its `origLength` as the cap, and check that the tables fit `totalSfntSize`.
- WOFF2: decode the Brotli block with the size the table directory gives as the cap, as the reference decoder sizes its output from the directory.
- A font has a ceiling of its own far below 512 MiB.

#### How to reproduce

`audit-repro/L5/make_woff_bomb.py <dir>` writes the 510 KB `bomb.woff` above. Then:

```kotlin
// kitepdf-epub, test@jvm/
FontProgram.parse(File("bomb.woff").readBytes()) // holds 2 GiB, then asks for 2 GiB more
```

A book whose style sheet names it in an `@font-face` does the same on the layout of its first chapter.

#### Where

- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/Woff.kt:44-56`: each table, inflated with the stream cap; `:74`: the output array.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/Woff2.kt:81-83`: the Brotli block.
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/filters/Filters.kt:65`: `MAX_DECODED_STREAM`.

#### Notes

Read from source, and built the WOFF file with Python's zlib (509,728 bytes). No Brotli stream was built, since this machine has no Brotli encoder; the WOFF2 part rests on the cap that the code passes.

Measured on the JVM at `c4e7574` by the lead: a one-chapter EPUB whose `<style>` names `bomb.woff` in an `@font-face` (510 KB; four entries over one 500 MiB zlib stream, `origLength` 500 MiB each, `totalSfntSize` 92) fails with OutOfMemoryError on a 3 GB heap, 0.45 s into the layout of its chapter. The first version of this finding said that a table which declares 4 bytes still inflates; it does not (`compLength >= origLength` is read as stored), and the text above is corrected.

### KP-33: Two regular expressions over a book's CSS backtrack quadratically, so one style sheet stalls the layout of its chapters for minutes

Status: proven, and held back with the other security findings until they are reported.

**Two regular expressions over a book's CSS backtrack quadratically, so one style sheet stalls the layout of its chapters for minutes**

- Repo: KitePDF
- Labels: area:epub, kind:bug
- Severity: high
- Confidence: Confirmed

#### What happens

- `ParsedEpub` rewrites every style sheet and `<style>` block with two regular expressions before it parses them: `IMPORT_RE` in `inlineImports` and `URL_RE` in `absoluteUrls`. `remoteCssUrls` runs `URL_RE` over every `style` attribute and declaration as well.
- In `IMPORT_RE`, `([^"')\s;]+)` and the later `[^;{]*;` can both take the same letters. After `@import ` with no `;` behind it, the engine tries every split between the two, which is quadratic in the length of the text.
- In `URL_RE`, `([^"')]*)` runs to the end of the text when no `"`, `'` or `)` follows, then gives back one character at a time. A text of `url(` repeated starts a new attempt at each `url(`, which is quadratic too.
- The rewrite runs on the thread that parses the chapter, with no cancellation, once per style sheet and once per `<style>` block.

#### What should happen

The rewrite is linear in the length of the style sheet: it walks the tokens of `CssTokenizer`, or uses patterns whose parts cannot take the same characters, so that a failed match costs one pass.

#### How to reproduce

The same patterns in Python's backtracking engine, which works as `java.util.regex` does:

| Input | Length | Time |
|---|---|---|
| `"@import " + "a" * n` | 20,000 | 2.4 s |
| `"@import " + "a" * n` | 40,000 | 9.4 s |
| `"url(" * n` | 40,000 | 5.0 s |
| `"url(" * n` | 80,000 | 19.8 s |

In Kotlin on the JVM:

```kotlin
// kitepdf-epub, test/
@Test
fun a_long_unterminated_import_rewrites_in_linear_time() {
    val mark = kotlin.time.TimeSource.Monotonic.markNow()
    ParsedEpub.absoluteUrls("url(".repeat(50_000), "OEBPS")
    assertTrue(mark.elapsedNow().inWholeSeconds < 1, "took ${mark.elapsedNow()}")
}
```

A book whose style sheet is `@import ` followed by 500 KB without a `;`, or 500 KB of `url(`, does not lay out the chapters that link it.

#### Where

- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:875-878`: `IMPORT_RE`, used by `inlineImports` at line 637.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:860`: `URL_RE`, used by `absoluteUrls` at line 851 and `remoteCssUrls` at line 863.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubParse.kt:501` and `:555-559`: where they run on each style sheet.

#### Notes

Measured the two patterns, copied as written, with Python 3's `re`: `audit-repro/L5/import_re.py` and `url_re.py`. The JVM timing is the probe above.

### KP-35: A dash pattern finer than a pixel makes the AWT canvas stroke millions of dashes, and a finer one ends the page render

Status: proven, and held back with the other security findings until they are reported.

**A dash pattern finer than a pixel makes the AWT canvas stroke millions of dashes, and a finer one ends the page render**

- Repo: KitePDF
- Labels: area:renderer, backend:awt, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #106, #107

#### What happens

- The renderer passes the dash array of `d` (and of `/D` in an ExtGState) to the canvas unchanged, except an all-zero array (`PageRenderer.kt:1346`, `GraphicsState.kt:127`).
- `awtDash` keeps every positive element, however small, and `strokePath` hands it to `BasicStroke` (`AwtCanvas.kt:125-131`, `:1618-1621`). Java2D's Marlin dasher then emits one dash for each element along the whole path. The count is the path length divided by the dash length, so it does not depend on the scale.
- Measured with JDK 21's Marlin, as `AwtCanvas` strokes (device-space path, width and dashes scaled by 2, antialiasing on, `STROKE_PURE`), one line of 1,000 points on a 1,224 by 1,584 image:

| Dash array (user units) | Dashes | Time of one `g.draw` |
|---|---|---|
| `[0.001 0.001]` | 0.5 million | 0.4 s |
| `[0.0001 0.0001]` | 5 million | 3.4 s |
| `[0.00001 0.00001]` | 50 million | 8 s, then `ArrayIndexOutOfBoundsException: array exceeds maximum capacity !` from `Renderer.addLine` |

- Nothing catches the exception: `withComposite` only restores the composite, the content stream has no per-operator catch, and `AwtPdfRasterizer.renderToImage` has none. So the whole page fails after the wait.
- A page of 50 such lines (`tiny-dash-x50.pdf`, 1.4 KB, 186 million dashes) costs about two minutes at the rate measured above. The time adds up with each stroke, so a small file holds `AwtPdfRasterizer` for as long as it likes.
- The other canvases do not have the problem. The pure-Kotlin stroker draws solid past 100,000 dashes (`StrokeOutline.kt:42-44`, `MAX_DASHES`), and Skia, under `SkiaCanvas`, `ComposeCanvas`, `AndroidNativeCanvas` and Chrome's Canvas2D, gives up dashing past one million dashes and strokes the path solid (`SkDashPath`, `kMaxDashCount`).

#### What should happen

A dash pattern too fine to show strokes solid on AWT too, by the rule the pure-Kotlin stroker already has, or by MuPDF's (`fz_flatten_dash_path` strokes without dashes when the pattern is shorter than 0.01 units or half a device pixel). ISO 32000-1, 8.4.3.6 sets no lower limit, so the choice is the reader's.

#### How to reproduce

`fixtures/tiny-dash-1e-4.pdf` and `fixtures/tiny-dash-1e-5.pdf` are one page each with this content (`1e-5` for the second):

```
2 w [0.0001 0.0001] 0 d 0 0 m 612 792 l S
```

Probe for `kitepdf-native-renderer`, `test@jvm` (the class name must end in `Test`):

```kotlin
class TinyDashTest {
    private fun page(name: String) = PdfDocument.open(
        java.io.File("audit-repro/L6/fixtures/$name").readBytes(),
    ).pages[0]

    @Test fun aDashFinerThanAPixelStrokesInBoundedTime() {
        val start = System.nanoTime()
        AwtPdfRasterizer.renderToImage(page("tiny-dash-1e-4.pdf"), scale = 2.0)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 500, "one stroke took $ms ms")          // about 3,400 ms today
    }

    @Test fun aFinerDashStillRendersThePage() {
        AwtPdfRasterizer.renderToImage(page("tiny-dash-1e-5.pdf"), scale = 2.0)   // throws ArrayIndexOutOfBoundsException
    }
}
```

The standalone measurement is `L6/dashprobe/DashProbe.java` (`javac DashProbe.java && java -Djava.awt.headless=true DashProbe`).

#### Where

- `kitepdf-native-renderer/src@jvm/io/github/yuroyami/kitepdf/nativerenderer/AwtCanvas.kt:125-131` (the stroke) and `:1618-1621` (`awtDash`).
- `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/StrokeOutline.kt:42-44`, `:57`: the guard the AWT path lacks.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt:1346`: the dash array reaches the canvas as written.

#### Notes

Measured the Java2D part with a standalone JDK 21 program that mirrors `AwtCanvas.strokePath`; KitePDF itself was not run. Skia's one-million-dash limit is quoted from its source (`SkDashPath.cpp`), not measured here. Poppler's `pdftoppm` is slow on the same files too (22 s and 16 s), so this is a weak spot of rasterizers in general, but KitePDF's own stroker and every Skia-based canvas already guard against it. CoreGraphics was not checked. MuPDF's rule is quoted from memory, since `mupdf-master/` is not in this checkout.

Measured on the JVM at `c4e7574` by the lead: with `AwtPdfRasterizer`, `tiny-dash-1e-4.pdf` (one line) took 4.8 s; `tiny-dash-1e-5.pdf` threw `ArrayIndexOutOfBoundsException: array exceeds maximum capacity` from Marlin's `Renderer.addLine` after 11.7 s, and the page failed; `tiny-dash-x50.pdf` was still running after 25 s.

### KP-37: `calculateNow()` inside a calculation starts a new calculation round, and the deadline never fires

Status: proven, and held back with the other security findings until they are reported.

**`calculateNow()` inside a calculation starts a new calculation round, and the deadline never fires**

- Repo: KitePDF
- Labels: area:javascript, plan:javascript, kind:bug
- Severity: high
- Confidence: Confirmed

#### What happens

`this.calculateNow()` reaches `PdfScriptHost.action("calculateNow")`, which calls `runCalculations()`. On the script thread `onScriptThread` runs the block in place, so a calculate script that calls `calculateNow()` runs `calculateAll()` again from inside its own event. That round runs the same script, which calls `calculateNow()` again. Nothing stops the nesting until the native stack limit (8 MiB of a 16 MiB script thread) throws a RangeError in the innermost script, which the runner records and goes on from.

With one such field each commit nests rounds until the stack limit, and the innermost run fails with a RangeError that the runner records. With two such fields every level runs both, and each of them nests again, so the work doubles with each level of depth and the call does not return.

The per-event deadline does not stop it. `PdfScriptRunner.evaluate` sets `eventStartedAt = now()` on every nested evaluation and never restores it, so the outer event's clock restarts each time a nested event starts, and `deadlinePassed()` compares against the newest start. The default policy sets no instruction budget and no document budget.

PDFium ignores a calculation request while a calculation runs: `CPDFSDK_InteractiveForm::OnCalculate` returns at once when `busy_` is set, and `CJS_Document::calculateNow` calls `OnCalculate`. So a form that calls `this.calculateNow()` from a calculation script works in Chrome.

#### What should happen

A `calculateNow()` that comes while a calculation round runs does nothing, as in PDFium. A nested evaluation does not reset the deadline of the event around it.

#### How to reproduce

A `kitepdf-javascript` test on the JVM (`test@jvm`). Fields `a` and `b`, each with the calculate script `this.calculateNow(); event.value = 1;`, and `/CO [a b]`:

```kotlin
@Test
fun calculate_now_inside_a_calculation_does_not_recurse() {
    val doc = PdfDocument.open(pdf)
    val runner = PdfScriptRunner(doc, policy = PdfScriptPolicy(budgetMillis = 1_000))
    val worker = thread(isDaemon = true) { runner.runCalculations() }
    worker.join(30_000)
    val stillRunning = worker.isAlive
    // close() waits for the script thread on the JVM, so it gets a thread of its own here.
    thread(isDaemon = true) { runner.close() }
    assertFalse(stillRunning, "the calculation round is still nesting")   // fails today
    assertTrue(runner.failures.isEmpty())
}
```

#### Where

- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptHost.kt:318` `"calculateNow"`, `:328` `calculateNow`.
- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:228` binds it to `runCalculations()`, `:534` `runCalculations`, `:539` `calculateAll` (no guard), `:698` `eventStartedAt = now()` on every nested call, `:718` `deadlinePassed`.
- PDFium `fpdfsdk/cpdfsdk_interactiveform.cpp` `OnCalculate` (`if (busy_) return;`), `fxjs/cjs_document.cpp` `calculateNow`.

#### Notes

Read from source, and the PDFium sources fetched from pdfium.googlesource.com (not committed). The exponential case is inferred from the code path, not run. I did not work out whether `close()` ends it: closing makes each later poll stop the script that runs, which ends one nested script per 256 checks, while the rounds outside it go on with their remaining fields.

### KP-38: A document can set any number of timers, and one pump runs every due timer without a limit

Status: proven, and held back with the other security findings until they are reported.

**A document can set any number of timers, and one pump runs every due timer without a limit**

- Repo: KitePDF
- Labels: area:javascript, plan:javascript, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #241, #368

#### What happens

`PdfScriptHost.setTimer` adds each `app.setInterval` and `app.setTimeOut` to a map with no cap. `pumpTimers` takes every due timer and evaluates each in turn. The deadline is per evaluation, and a tiny timer script never comes near it, so nothing bounds the pump as a whole. A period of 0 makes a repeating timer due at every pump. So an open action such as

```js
for (var i = 0; i < 100000; i++) app.setInterval('1', 0);
```

which finishes inside its own five seconds, makes every later pump compile and run 100,000 scripts. In the glue alone, without the Kotlin and JNI or WebAssembly layers above it, 100,000 such scripts take 0.78 s on this machine (`qjs/timers.c`), and the viewer pumps once a frame while a timer waits.

- On JavaScript and Wasm the pump runs on the page's only thread. Where the runtime has no WebAssembly stack switching (KitePDF's docs: only Chrome 137 and Node 25 and newer have it), `evaluatePausing` is a plain `evaluate`, so the page gets no frame until the whole pump is over, and the pump starts again on the next frame. Where it has stack switching, each evaluation resumes through the event loop and the page can draw, but `QuickJsKiteJs.pausingCall` starts a new 16 ms slice for each call, so no single short script pauses, and the runner's other calls wait behind the pump for the `turns` mutex.
- On the JVM, Android and Kotlin/Native the script thread runs pumps back to back, so every keystroke, commit and tap script of that document waits behind them. `close()` waits for the pump in progress, since the evaluations in it never reach a poll that would see `closed`.

#### What should happen

A pump stops after a time slice and leaves the rest of the due timers for the next one, and a document has a bounded number of timers, so a document cannot take the thread from the host, as `pumpTimers`'s KDoc says: "Nothing runs on its own: a document cannot take the thread from the host."

#### How to reproduce

```kotlin
@Test
fun one_pump_has_a_limit(): TestResult = scriptTest {
    var now = 0L
    PdfScriptRunner(doc, clock = { now }).use { runner ->
        runner.run(PdfAction.JavaScript("for (var i = 0; i < 100000; i++) app.setInterval('1', 0)", PdfDictionary(emptyMap())))
        val start = TimeSource.Monotonic.markNow()
        runner.pumpTimers(now)
        assertTrue(start.elapsedNow() < 100.milliseconds)   // today it runs 100,000 scripts in this one call
    }
}
```

#### Where

- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptHost.kt:345` `setTimer`, `:364` `dueTimers`.
- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:659` `pumpTimers`, `:694` `evaluate`.
- KiteJS `kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/QuickJsKiteJs.kt:174` `pausingCall`, which restarts the slice per call.

#### Notes

Read from source; the cost per script measured in C only. The Kotlin layers add to it, so the real pump is slower than the figure above.

### KP-39: The name tree and revision walkers stop at depth 64 but keep no visited set, so a node that lists itself twice takes 2^64 steps

Status: proven, and held back with the other security findings until they are reported.

**The name tree and revision walkers stop at depth 64 but keep no visited set, so a node that lists itself twice takes 2^64 steps**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Confirmed
- Related: #448

#### What happens

- `NameTreeWalker.walk` and `NumberTreeWalker.walk` stop at depth 64, but visit a kid each time a `/Kids` array lists it. `5 0 obj << /Kids [5 0 R 5 0 R] >>` makes 2 calls at depth 1, 4 at depth 2, and about 2^65 in all. At 10 million calls a second that is about 100,000 years.
- `NameTreeWalker` reads `/Names /EmbeddedFiles` for `PdfDocument.attachments`, and `/Names /JavaScript` for `documentJavaScripts`, which `PdfScriptRunner` reads when it starts.
- `RevisionChanges.walkField` and `walkPages` have the same shape. `PdfSignature.validate()` runs them on both revisions when the file changed after signing, so validating a crafted signed file never returns.
- The calling thread spins for good: an app that lists attachments on the UI thread stops responding, and a viewer with document scripts never starts them.
- Poppler reports "loop in NameTree" on the same file and goes on. MuPDF keeps a cycle list (`pdf-nametree.c`).

#### What should happen

Each walk keeps the object numbers it has entered and skips a kid it has seen. The depth cap stays as a backstop.

#### How to reproduce

`nametree-fanout.pdf`: `1 0 obj << /Type /Catalog /Pages 2 0 R /Names << /EmbeddedFiles 5 0 R /JavaScript 5 0 R >> >>` and `5 0 obj << /Kids [5 0 R 5 0 R] >>`.

```kotlin
val doc = open("nametree-fanout.pdf")
val worker = thread { doc.attachments }
worker.join(10_000)
println("still running after 10 s: ${worker.isAlive}")   // true
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/NameTree.kt:32-58`, `:74-99` (both walks), `:38`, `:80` (the depth check, the only guard).
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfAttachment.kt:47`, `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:623-626`.
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/RevisionChanges.kt:161-209` (`walkField`, `walkPages`), reached from `PdfSignature.kt:81`.

#### Notes

Read from source. `RevisionChanges.reach` in the same file has a work budget (`MAX_REACH`), so it ends.

Measured on the JVM at `c4e7574` by the lead: on `nametree-fanout.pdf`, `attachments` and `documentJavaScripts` were still running after 15 s.

### KP-41: An object stream's /N sizes an array before anything checks it

Status: proven, and held back with the other security findings until they are reported.

**An object stream's /N sizes an array before anything checks it**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: high
- Confidence: Likely

#### What happens

- `PdfDocument.decodeObjectStream` reads `/N` and allocates `IntArray(n)` before it reads the first header pair (`PdfDocument.kt:913`, `:924`). `PdfRepair.objStreamMembers` allocates `ArrayList(n)` the same way (`PdfRepair.kt:142`, `:145`). Nothing bounds `n` by the size of the decoded header.
- `/N 2147483647` asks for 8 GB (`IntArray`) and 8 to 16 GB (an `ArrayList` of references, by pointer size).
- JVM and Android: the `OutOfMemoryError` comes at once and the `runCatching` in `resolve` and in repair catches it, so the members read as null and `open` fails with "Repair found no catalog". A value such as 300,000,000 (1.2 GB) is granted by a desktop heap, zeroed and dropped, and that happens again for every member that is resolved, because a failed resolve is not cached (`PdfDocument.kt:770`, a cached null is parsed again).
- Kotlin/Native ends the process when it cannot map an allocation ("Out of memory trying to allocate ... Aborting."), and Kotlin/Wasm traps on an array that large. Neither reaches the `runCatching`. This is the step not run here.
- MuPDF refuses a count above `PDF_MAX_OBJECT_NUMBER` (8,388,607) before it allocates (`pdf_load_obj_stm`, `pdf-xref.c`). Poppler prints "Too many objects in an object stream" for the file below and does not open it.

#### What should happen

`/N` is checked before any allocation: each header pair takes at least 4 bytes of the decoded stream, so `n <= decoded.size / 4`, and `n` stays under MuPDF's limit. The offsets grow as pairs are read.

#### How to reproduce

`make_objstm_huge_n.py` writes `objstm-huge-n.pdf` (catalog, page tree and page in an object stream with `/N 2147483647`) and, with an argument of 3, the control file `objstm-n3.pdf`, which poppler opens.

Probe, `kitepdf-pdf`, `test`, run with `-p jvm` and with `-p linuxX64` or `-p macosArm64`. It builds the same file without Flate, so it needs no file access:

```kotlin
class ObjectStreamCountTest {
    private fun file(n: Long): ByteArray {
        val members = listOf(
            "<< /Type /Catalog /Pages 3 0 R >>",
            "<< /Type /Pages /Kids [4 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 3 0 R /MediaBox [0 0 612 792] >>",
        )
        val header = StringBuilder(); val body = StringBuilder()
        members.forEachIndexed { i, m -> header.append("${i + 2} ${body.length} "); body.append(m).append('\n') }
        val stm = "$header\n$body"
        val out = ByteArrayBuilder()
        fun w(s: String) = out.append(s.encodeToByteArray())
        w("%PDF-1.5\n")
        val o1 = out.size()
        w("1 0 obj\n<< /Type /ObjStm /N $n /First ${header.length + 1} /Length ${stm.length} >>\nstream\n$stm\nendstream\nendobj\n")
        val xref = out.size()
        val rows = ByteArrayBuilder()
        fun row(t: Int, a: Int, b: Int) { rows.append(t.toByte()); rows.appendU32BE(a); rows.appendU16BE(b) }
        row(0, 0, 65535); row(1, o1, 0); for (i in members.indices) row(2, 1, i); row(1, xref, 0)
        val r = rows.toByteArray()
        w("5 0 obj\n<< /Type /XRef /Size 6 /W [1 4 2] /Root 2 0 R /Length ${r.size} >>\nstream\n")
        out.append(r)
        w("\nendstream\nendobj\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    @Test fun control_opens() { assertEquals(1, PdfDocument.open(file(3)).pageCount) }

    @Test fun a_huge_count_is_refused_without_allocating() {
        // JVM: returns null after two caught OutOfMemoryErrors. Native: expected to end the test process.
        println(PdfDocument.openOrNull(file(2_147_483_647L)))
    }
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/PdfDocument.kt:913` (`/N`), `:924` (`IntArray(n)`), `:936` (`HashMap(n)`), `:770` (a failed resolve is tried again).
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/parser/PdfRepair.kt:142` to `:145`.

#### Notes

The allocation is read from source. The Native abort and the Wasm trap are the runtimes' behaviour for a failed huge allocation, not run here.

### KP-42: A book's script can build a tree deeper than the parser allows, and the layout and the script DOM walk it recursively

Status: proven, and held back with the other security findings until they are reported.

**A book's script can build a tree deeper than the parser allows, and the layout and the script DOM walk it recursively**

- Repo: KitePDF
- Labels: area:epub, area:javascript, kind:bug
- Severity: high
- Confidence: Likely
- Related: #450

#### What happens

- #450 capped the depth of a chapter's tree at 32 levels, in `HtmlParser`, because the layout recurses once for each level and a Kotlin/Native debug build takes about 10 KB of stack for each level (CLAUDE.md). A secondary thread on Apple platforms has 512 KB.
- A tree that a book's scripts change does not go through that cap. `appendChild` in a loop, or `innerHTML` in an XHTML chapter (which `XmlReader.fragment` parses with no depth limit), make a tree of any depth.
- When the script returns, `commit` hands `ScriptDom.snapshot()` to the layout. `snapshot` copies the tree recursively (`copy`), and `innerHTML` registers its text nodes recursively first (`registerTexts`). `replaceChapterTree` then lays the chapter out on the script thread (16 MB of stack), and another document over the book, such as one after a font size change, lays it out on the raster dispatcher.
- The layout walks the tree recursively in `ParsedEpub.walkStyleSources`, `containsScript`, `Paginator.gatherUnits`, `collect` and `numberPaintOrder`. `gatherUnits` and `BoxBuilder` (`childAncestors`) also copy the list of ancestors at each level, so a depth of `d` holds `d * d / 2` references at once: 50 million for a depth of 10,000.
- On Apple platforms a stack overflow ends the process with no exception. On the JVM and Android, a `StackOverflowError` or `OutOfMemoryError` from the script thread passes through `EpubScriptRunner.call` and through the viewer's `scriptCall`, which catches `Exception` only, so it ends the coroutine of `KiteDocView` and the app.

#### What should happen

The tree that the layout reads keeps the parser's limit whoever made it: `snapshot` moves an element deeper than `HtmlParser.MAX_DEPTH` up, as `HtmlParser.parse` does, and the DOM's own walks (`registerTexts`, `copy`, `appendText`, `find`, `fixParents`, the XML serializer) do not use the call stack for each level.

#### How to reproduce

The layout part, in `kitepdf-epub`, `test@jvm/`:

```kotlin
@Test
fun a_scripted_tree_deeper_than_the_parser_allows_lays_out() {
    val doc = EpubDocument.open(EpubFixtures.epub("<p>x</p>"))
    val root = KiteXmlNode.Element("#root", emptyMap())
    val html = KiteXmlNode.Element("html", emptyMap()).also { it.parent = root; root.children.add(it) }
    var at = KiteXmlNode.Element("body", emptyMap()).also { it.parent = html; html.children.add(it) }
    repeat(20_000) { val d = KiteXmlNode.Element("div", emptyMap()); d.parent = at; at.children.add(d); at = d }
    at.children.add(KiteXmlNode.Text("deep"))
    var failure: Throwable? = null
    // The stack of a secondary thread on Apple platforms.
    val t = Thread(null, { try { doc.replaceChapterTree(0, root) } catch (e: Throwable) { failure = e } }, "probe", 512L * 1024)
    t.start(); t.join()
    assertNull(failure) // StackOverflowError or OutOfMemoryError expected today
}
```

The script part: an XHTML chapter whose script runs `var e = document.body; for (var i = 0; i < 20; i++) { e.innerHTML = '<div>'.repeat(10000) + '</div>'.repeat(10000); while (e.firstElementChild) e = e.firstElementChild; }` makes a tree 200,000 levels deep in linear time, through `EpubScriptRunner`.

#### Where

- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/HtmlParser.kt:52`: the cap, applied by the parser only.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/script/ScriptDom.kt:1178-1234` (`snapshot`, `copy`), `:1167` (`registerTexts`), `:490` (`appendText`), `:875` (`fixParents`).
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubScriptSession.kt:590-596`: `commit`.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubDocument.kt:318-322`: `replaceChapterTree`, which lays out on the caller's thread.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/Paginator.kt:259-260` and `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/BoxBuilder.kt:137`: the lists of ancestors.
- `kitepdf-compose-viewer/src/io/github/yuroyami/kitepdf/compose/ScriptCall.kt:13-19`: `scriptCall` catches `Exception` only.

#### Notes

Read from source. Not run: the depth at which each platform overflows is not measured, which is why this is Likely.

Found by two lanes. Neither could run a Kotlin/Native or Apple target, so the crash there stays Likely.

### KP-60: RevisionChanges reports a filled choice field or rich text field as a forbidden change

Status: proven, and held back with the other security findings until they are reported.

**RevisionChanges reports a filled choice field or rich text field as a forbidden change**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: medium
- Confidence: Confirmed
- Related: #448

#### What happens

`FILLING_KEYS` is `V`, `AS` and `AP`. Filling a list box or a combo box also sets `/I`, the selected indices (ISO 32000-1, 12.7.4.4), and filling a rich text field sets `/RV` (12.7.3.4). A field whose changed keys include either is classified `Other` ("field X"), which no level permits.

KitePDF's own editor writes both: `setChoiceValue` sets `/I` and `setTextFieldValue` sets `/RV` on a rich text field. So a certified form at level 2, filled in with KitePDF or Acrobat, reads as illegally changed.

#### What should happen

`/I` and `/RV` count as filling (`FILLING_KEYS`), as does `/TI` for a list box that only scrolled.

#### How to reproduce

Common test:

```kotlin
@Test
fun choosing_an_option_is_filling_a_form() {
    val base = TestPdf.onePage(
        "q Q",
        pageEntries = "/Annots [5 0 R]",
        catalogEntries = "/AcroForm << /Fields [5 0 R] /DA (/Helv 0 Tf 0 g) >>",
        extra = listOf("<< /Type /Annot /Subtype /Widget /FT /Ch /Ff 131072 /T (Pick) /Opt [(a) (b)] /V (a) /Rect [10 10 150 30] /P 3 0 R >>"),
    )
    val old = PdfDocument.open(base)
    val new = PdfDocument.open(old.edit().apply { setChoiceValue(old.formField("Pick")!!, "b") }.saveIncremental())
    val changes = RevisionChanges.between(old, new, level = 2, lock = null)
    assertTrue(changes.all { it.isPermitted }, changes.toString())   // fails today: "field Pick" is Other
}
```

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/RevisionChanges.kt:246`

#### Notes

Read from source. `RevisionChangesTest.fillingAFieldIsPermittedFromLevel2` only changes `/V` of a text field.

### KP-62: A stamp or a redaction box lands in the page's leftover graphics state when the page leaves a q open

Status: proven, and held back with the other security findings until they are reported.

**A stamp or a redaction box lands in the page's leftover graphics state when the page leaves a q open**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

`stampPage` writes `q`, the page content, `Q`, then the stamp. `redactRegions` writes `q`, the rewritten content, `Q`, then the black boxes. When the page content has a `q` without its `Q`, which real files have (#47 is the form XObject case), the wrapper's `Q` only closes the page's own last `q`. The graphics state at that `q` stays in force, so a `cm` or a clip that the page set before it applies to the stamp and to the boxes.

For a page that starts `0.5 0 0 0.5 0 0 cm q ...` with no `Q`, a stamp at (100, 100) draws at (50, 50) at half size, and a redaction box covers half the region, at another place on the page. The removed content stays removed, but the box can cover text that should stay, and the region shows uncovered.

#### What should happen

Count the nesting of the content and close every open `q` before the stamp and the boxes, as MuPDF's content filter does.

#### How to reproduce

Common test:

```kotlin
@Test
fun a_stamp_ignores_a_q_that_the_page_left_open() {
    val pdf = TestPdf.onePage("0.5 0 0 0.5 0 0 cm q 0 0 1 rg 0 0 50 50 re f", mediaBox = "0 0 300 300")
    val doc = PdfDocument.open(pdf)
    val out = doc.edit().apply {
        stampPage(doc.pages[0]) { setFillRgb(1.0, 0.0, 0.0); rectangle(100.0, 100.0, 50.0, 50.0).fill() }
    }.saveIncremental()
    val red = TestPdf.calls(out).filterIsInstance<RecordingCanvas.Call.Fill>().single { it.color == RgbColor(1.0, 0.0, 0.0) }
    assertEquals(50.0, red.path.bounds(red.ctm)!!.width, 1e-6)   // fails today: 25.0
}
```

For the redaction box: `RawPdf.page("0.5 0 0 0.5 0 0 cm q BT /F1 24 Tf 200 1400 Td (SECRET) Tj ET".encodeToByteArray())`, redact `KiteRectangle(90.0, 690.0, 300.0, 730.0)`, save with `saveRewritten()`: the black fill is 105 points wide instead of 210.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/PdfEditor.kt:252` (stamp), `:863` and `:878` (redaction)

#### Notes

Read from source.

### KP-63: Password and comb text fields draw their value as plain text

Status: proven, and held back with the other security findings until they are reported.

**Password and comb text fields draw their value as plain text**

- Repo: KitePDF
- Labels: area:pdf, kind:bug
- Severity: medium
- Confidence: Confirmed

#### What happens

Neither `FieldAppearance.build` (used by `setTextFieldValue`) nor `FieldAppearance.synthesize` (used by the renderer for a live or missing value) reads the Password or the Comb flag (ISO 32000-1, 12.7.4.3, Table 228).

- Password (bit 14): the value is drawn in clear. `KiteFormInput` masks the typing with `PasswordVisualTransformation`, but once the value is committed, `liveWidgetAppearance` draws it on the page, and `setTextFieldValue` writes it into the saved `/AP` where any reader shows it. The spec asks that the value be "echoed in some unreadable form, such as asterisks".
- Comb (bit 25, with `/MaxLen`): the value is drawn as one run instead of one character in each of `MaxLen` equal cells. Forms that ask for a tax number, a date or a code in boxes show the characters bunched at the left.

#### What should happen

A password field draws one bullet or asterisk per character. A comb field divides its width into `MaxLen` cells and centres one character in each.

#### How to reproduce

Common test:

```kotlin
@Test
fun a_password_field_does_not_show_its_value() {
    val base = TestPdf.onePage(
        "q Q",
        pageEntries = "/Annots [5 0 R]",
        catalogEntries = "/AcroForm << /Fields [5 0 R] /DA (/Helv 12 Tf 0 g) >>",
        extra = listOf("<< /Type /Annot /Subtype /Widget /FT /Tx /Ff 8192 /T (Pin) /Rect [10 10 150 30] /P 3 0 R >>"),
    )
    val doc = PdfDocument.open(base)
    val out = PdfDocument.open(doc.edit().apply { setTextFieldValue(doc.formField("Pin")!!, "hunter2") }.saveIncremental())
    val widget = out.resolve(PdfReference(5, 0)) as PdfDictionary
    val ap = out.resolve(widget.getDict("AP", out)!!["N"] as PdfReference) as PdfStream
    assertFalse("hunter2" in FilterChain.decode(ap).decodeToString())   // fails today
}
```

For comb, the same field with `/Ff 16777216 /MaxLen 5` and the value `12345` gives one `(12345) Tj`.

#### Where

- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/writer/FieldAppearance.kt:59` (`build`), `:216` (`synthesize`)
- `kitepdf-pdf/src/io/github/yuroyami/kitepdf/render/PageRenderer.kt` (`liveWidgetAppearance`)

#### Notes

Read from source. No code in `kitepdf-pdf` reads either flag.

### KP-71: The subsequent-sibling combinator costs the cube of the number of siblings, and every sibling test scans from the first child

Status: proven, and held back with the other security findings until they are reported.

**The subsequent-sibling combinator costs the cube of the number of siblings, and every sibling test scans from the first child**

- Repo: KitePDF
- Labels: area:epub, kind:perf
- Severity: medium
- Confidence: Confirmed
- Related: #549

#### What happens

- `previousElement` finds the sibling before an element by scanning the parent's children from the start, so one call costs the element's position.
- The `~` combinator calls it once for each sibling it walks back over. For the element at position `i`, matching `h1 ~ p` costs about `i * i / 2`, and a chapter of `n` paragraphs costs about `n^3 / 6`. With 3,000 paragraphs under one `body`, and their white space text between them, that is about 10^10 steps for one rule.
- `+`, `:first-child`, `:nth-child` and the other sibling tests cost the position of the element, so a rule such as `p + p`, the most common one in book CSS, costs `n^2 / 2` per chapter.
- The cascade matches every rule for every element at each layout, and a font size change lays the chapter out again. `querySelectorAll` of the book's scripts uses the same matcher.

#### What should happen

A sibling step costs O(1): the matcher keeps each element's index among its siblings for the layout of a chapter, or the tree links its siblings. Then `~` costs the number of siblings it walks, and `+` one step.

#### How to reproduce

```kotlin
// kitepdf-epub, test/BoxLayoutTest.kt (which has layout())
@Test
fun a_subsequent_sibling_rule_is_not_cubic() {
    val html = "<h1>t</h1>" + "<p>x</p>\n".repeat(3000)
    val mark = kotlin.time.TimeSource.Monotonic.markNow()
    layout(html, css = "h1 ~ p { text-indent: 1em }")
    assertTrue(mark.elapsedNow().inWholeSeconds < 2, "took ${mark.elapsedNow()}")
}
```

#### Where

- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/Selector.kt:512-519`: `previousElement`.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/Selector.kt:452-460`: the `~` combinator.
- `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/css/Selector.kt:206-221`: `:nth-child` and its kin.

#### Notes

Read from source. The cost is counted, not timed.

### KP-83: `PdfScriptRunner` keeps every failure for as long as the document is open, and copies the whole list on each one

Status: proven, and held back with the other security findings until they are reported.

**`PdfScriptRunner` keeps every failure for as long as the document is open, and copies the whole list on each one**

- Repo: KitePDF
- Labels: area:javascript, plan:javascript, kind:perf
- Severity: medium
- Confidence: Confirmed

#### What happens

`recordFailure` appends to `failureList` and then copies the whole list into `failureCopy`. Nothing ever removes an entry. A timer that throws each time it runs, for example `app.setInterval("update()", 100)` where `update` is not defined, adds a failure ten times a second for as long as the viewer pumps it, and each addition copies the list again, so the cost of each failure grows with the number before it.

Each entry is a `KiteScriptException` whose cause is a KiteJS `JsError`, and `JsError.value` keeps a handle to the thrown error object inside the engine. So the list also pins every thrown error object in the QuickJS heap, which counts against the engine's 512 MiB `MEMORY_LIMIT`: when it is reached, every script of the document fails with "the engine ran out of memory".

The EPUB side fixed exactly this: `EpubScriptSession` keeps the last `MAX_FAILURES` (100) entries, "since a timer that throws each time it runs would otherwise grow it for as long as the book is open".

#### What should happen

The PDF runner keeps a bounded number of failures, as the EPUB session does.

#### How to reproduce

```kotlin
@Test
fun a_timer_that_always_throws_keeps_a_bounded_list(): TestResult = scriptTest {
    var now = 0L
    PdfScriptRunner(doc, clock = { now }).use { runner ->
        runner.run(PdfAction.JavaScript("app.setInterval('update()', 10)", PdfDictionary(emptyMap())))
        repeat(10_000) { now += 10; runner.pumpTimers(now) }
        assertTrue(runner.failures.size <= 100)   // today 10,000
    }
}
```

#### Where

- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:168` `failureList`, `:174` `recordFailure`.
- Compare `kitepdf-epub/src/io/github/yuroyami/kitepdf/epub/EpubScriptSession.kt:377` `recordFailure` and `:1048` `MAX_FAILURES`.
- `kitejs-api/src/io/github/yuroyami/kitejs/api/JsException.kt` `JsError.value` (KiteJS), which holds the engine handle.

#### Notes

Read from source.

### KP-98: An EPUB can hold eight QuickJS engines of 512 MiB each, so one book can take 4 GiB of native memory

Status: proven, and held back with the other security findings until they are reported.

**An EPUB can hold eight QuickJS engines of 512 MiB each, so one book can take 4 GiB of native memory**

- Repo: KitePDF
- Labels: area:javascript, area:epub, kind:bug
- Severity: medium
- Confidence: Likely

#### What happens

Every `KiteJsScriptEngine` opens QuickJS with `memoryLimit = MEMORY_LIMIT`, 512 MiB, sized for "a compiled program's heap". `EpubScriptRunner` gives each chapter an engine of its own and keeps up to `LIVE_CHAPTERS` (8) of them open. A chapter script such as

```js
window.keep = []; for (;;) keep.push(new Array(100000).fill(1));
```

stops at its own limit with "the engine ran out of memory", but the array stays reachable from the chapter's global object until that engine closes. A book whose first eight chapters each do this holds about 4 GiB of native memory once the reader has opened them. On a phone that ends the app, which the KDoc rules out: "A document cannot take the app's memory: past the limit its allocation fails and the call throws."

#### What should happen

One limit for the whole book, or a per-engine limit small enough that `LIVE_CHAPTERS` engines fit what a reader app can spend.

#### How to reproduce

On the JVM, a book of eight chapters, each with the script above; open the chapters one by one with `EpubScriptRunner.chapterOpened(i)` and watch the process's resident size (QuickJS allocates with `malloc`, so the Java heap does not show it).

#### Where

- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/KiteJsScriptEngine.kt:61` `memoryLimit = MEMORY_LIMIT`, `:183` `MEMORY_LIMIT`.
- `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/EpubScriptRunner.kt:196` one engine per chapter, `:209` `liveChapters`, `:293` `LIVE_CHAPTERS`.

#### Notes

Read from source, not run. The memory limit itself works: the probe build stops `new Array(4000000).fill(1)` with "out of memory" at a 64 MiB limit.

### KP-129: On mingwX64, KiteDoc.openFile cannot open a path with a character outside ASCII

Status: could not be settled in the Linux container the audit was consumed in.

**On mingwX64, KiteDoc.openFile cannot open a path with a character outside ASCII**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: low
- Confidence: Likely

#### What happens

- `readPosixFile` calls `fopen(path, "rb")` (`src@mingw/.../PosixFileIo.kt:26`). Kotlin/Native passes a `String` to a `const char*` parameter as UTF-8.
- The Windows C runtime reads a narrow file name in the ANSI code page of the process, not UTF-8, unless the program opts into UTF-8. So `C:\Users\Zoë\book.pdf` reaches `CreateFileA` as `ZoÃ«` and fails, and `openFile` throws `cannot open file`. Every path under a user profile with a non-ASCII name fails.

#### What should happen

The mingw source set opens the file with `_wfopen` and the path as UTF-16 (`path.wcstr`), as the JVM and Apple openers already handle any name.

#### How to reproduce

On Windows, a mingwX64 test: write a PDF to `"${tmp}\\Zoë.pdf"` and call `KiteDoc.openFile` on it. Expected: the document. Likely: `KiteFormatException("cannot open file: …")`.

#### Where

- `kitepdf/src@mingw/io/github/yuroyami/kitepdf/document/PosixFileIo.kt:26`.

#### Notes

Read from source; not run on Windows. The step not proven here is that the Kotlin/Native mingw runtime does not switch the process to the UTF-8 code page. The comment in the three copies names `posixLlp64Main`, which is `src@mingw`.

### KJ-01: One built-in call runs past the instruction budget and the interrupt hook, so a single statement hangs the engine

Status: proven, and held back with the other security findings until they are reported.

**One built-in call runs past the instruction budget and the interrupt hook, so a single statement hangs the engine**

- Repo: KiteJS
- Labels: area:quickjs, area:interpreter, QuickJS, Rhino, upstream:quickjs-ng, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: KitePDF #242

#### What happens

QuickJS polls the interrupt handler only at calls and backward jumps (`js_poll_interrupts`). Several array built-ins loop over `length` in C without polling. For `join`, `toLocaleString`, `reverse` and `copyWithin`, called on a plain object, `length` can be up to 2^53 - 1 and no element has to exist, so the loop allocates nothing and does not end. Neither `instructionBudget` nor `interruptWhen` is asked while it runs. (`fill`, `with`, `toReversed`, `toSorted` and `toSpliced` do not poll either, but they allocate as they go, so the memory limit ends them.)

I built the vendored QuickJS-ng and `kitejs_quickjs.c` with gcc and ran the glue with an interrupt hook that answers "stop" once one second has passed, the way KitePDF's `budgetMillis` deadline answers:

| Script | Result |
|---|---|
| `for(;;){}` | stopped after 1.0 s, 1,271,132 polls |
| `Array.prototype.join.call({length: 1e8}, '')` | returned after 2.3 s, 1 poll |
| `Array.prototype.reverse.call({length: 1e8})` | returned after 2.9 s, 1 poll |
| `Array.prototype.copyWithin.call({length: 1e8}, 0, 1)` | returned after 5.1 s, 1 poll |
| `Array.prototype.join.call({length: 2**53 - 1}, '')` | still running when killed after 20 s |
| `reverse`, `copyWithin(0, 1)` and `toLocaleString` on `{length: 2**53 - 1}` | each still running when killed after 8 s |

The time is linear in the length: at 2.3 s per 1e8 elements, the last rows would run for years. With an instruction budget of 1000 the result is the same, as the budget is only charged at a poll.

In KitePDF a document script is untrusted input that runs on QuickJS with a 5 second deadline. One line in a keystroke, calculate or open script blocks the runner's script thread for good. `PdfScriptRunner.close()` then blocks its caller too: on the JVM, Android and Kotlin/Native it queues the close on the script thread and waits (`closeHere`, `thread.call { ... }` then `executor.submit(...).get()`), so a host that closes the runner from `onDispose` on the main thread hangs there (an ANR on Android). On JavaScript and Wasm the engine runs on the page's only thread and can pause only at a poll, so the tab freezes.

Rhino has the same gap: `NativeArray.js_reverse` loops `for (i in 0 until half)` over a `Long` half of the length with no `addInstructionCount`, and only the interpreter and `NativeRegExp` charge the budget (read from source, not run).

#### What should happen

The budget and `interruptWhen` stop any script, including one built-in call that loops, as `docs/limits.md` promises: "When one call passes the budget it gives up and throws JsEngineError". KitePDF's `KiteJsScriptEngine` promises the same: "a script that never returns fails with a KiteScriptException instead of hanging the app".

#### How to reproduce

`audit-repro/L7/qjs/hang.c`, linked against the vendored sources:

```sh
./hang "Array.prototype.join.call({length: 2**53 - 1}, '')"   # never returns; the hook says stop after 1 s
```

As a KiteJS test in `kitejs-quickjs` (common), which hangs today:

```kotlin
@Test
fun aBuiltInLoopStopsAtTheBudget() {
    KiteJs(QuickJs) { instructionBudget = 100_000 }.use { js ->
        val start = TimeSource.Monotonic.markNow()
        assertFailsWith<JsEngineError> { js.evaluate("Array.prototype.join.call({ length: 2 ** 53 - 1 }, '')") }
        assertTrue(start.elapsedNow() < 5.seconds)
    }
}
```

The same with `interruptWhen = { deadline.hasPassedNow() }` instead of a budget, and the same on Rhino with `Array.prototype.reverse.call({ length: 2 ** 53 - 1 })`.

#### Where

- `kitejs-quickjs/native/quickjs/quickjs.c:44139` `js_array_join` (also `toLocaleString`), `:44315` `js_array_reverse`, `:44616` `js_array_copyWithin`: no `js_poll_interrupts` in the loop (compare `js_array_lastIndexOf` at `:43984`, which polls).
- `kitejs-quickjs/native/kitejs_quickjs.c:163` `interrupt_handler`: only reached from a poll.
- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/NativeArray.kt:898` `js_join`, the non-dense path of `js_reverse` after it.
- KitePDF: `kitepdf-javascript/src/io/github/yuroyami/kitepdf/javascript/PdfScriptRunner.kt:779` `close`, `:790` `closeHere`; `src@jvm/.../ScriptThread.jvm.kt` `call`.

#### Notes

Ran the QuickJS part (see above). The Rhino part and the KitePDF consequences are read from source. KiteJS already patches QuickJS (`native/kitejs-quickjs.patch`), so a poll in these loops fits the existing patch.

Measured on the JVM at `c4e7574` by the lead: through KitePDF, `PdfScriptRunner(doc).runDocumentOpen()` on a PDF whose `/OpenAction` script is `Array.prototype.join.call({length: 2**53 - 1}, '')` was still running after 25 s, while `for(;;){}` is stopped after 5.1 s. `runner.close()` from another thread was then still blocked after 10 s.

### KJ-03: `asyncKiteJs(QuickJs)` on Apple runs a 512 KiB stack limit on a 512 KiB thread, so deep recursion crashes the process

Status: proven, and held back with the other security findings until they are reported.

**`asyncKiteJs(QuickJs)` on Apple runs a 512 KiB stack limit on a 512 KiB thread, so deep recursion crashes the process**

- Repo: KiteJS
- Labels: area:api, area:quickjs, QuickJS, kind:bug
- Severity: high
- Confidence: Likely

#### What happens

`asyncKiteJs` opens the engine on an `EngineThread` unless the caller passes a dispatcher. On Kotlin/Native that is `newSingleThreadContext("KiteJS engine")`, a Worker with the platform's default stack, which is 512 KiB for a secondary thread on iOS and macOS. QuickJS's default `maxStackSize` is also 512 KiB, measured from where the outermost call enters (`kite_enter` calls `JS_UpdateStackTop`), and the Worker has already used part of its stack by then. So a script that recurses without end runs off the end of the thread's stack before QuickJS's check throws its RangeError, and the process dies with a stack overflow signal.

`QuickJsConfig.maxStackSize` says that "a secondary thread on iOS at 512 KiB, needs a smaller value", but here KiteJS makes both the thread and the default, and `asyncKiteJs` neither lowers the limit nor documents it. KitePDF ran into the same platform default and starts its script threads with 16 MiB for that reason (`ScriptThread.native.kt`: "since a Worker takes the platform's default").

#### What should happen

`asyncKiteJs(QuickJs)` with defaults turns runaway recursion into a catchable RangeError on every target, as `ErrorsContract.deepRecursionIsARangeErrorTheScriptCanCatch` requires of an engine on the test thread: either the `EngineThread` gets a stack that fits the limit, or the limit fits the thread.

#### How to reproduce

A `kitejs-coroutines` test on `macosArm64` or `iosSimulatorArm64`:

```kotlin
@Test
fun deepRecursionOnTheEngineThreadIsARangeError() = runTest {
    val js = asyncKiteJs(QuickJs)
    try {
        assertEquals("RangeError", js.evaluate("try { (function f() { return f() + 1 })() } catch (e) { e.name }").asString())
    } finally {
        js.close()
    }
}
```

Expected today: the test process ends with a stack overflow (EXC_BAD_ACCESS) instead of a failed assertion.

#### Where

- `kitejs-coroutines/src/io/github/yuroyami/kitejs/coroutines/AsyncKiteJs.kt:288` `asyncKiteJs`, `dispatcher: CoroutineDispatcher = EngineThread()`.
- `kitejs-coroutines/src@native/io/github/yuroyami/kitejs/coroutines/EngineThread.native.kt` `newSingleThreadContext`.
- `kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/QuickJs.kt:61` `maxStackSize = 512L * 1024`.
- `kitejs-quickjs/native/kitejs_quickjs.c:395` `kite_enter`.

#### Notes

Read from source. Not run: this machine cannot build Apple targets. The 512 KiB default comes from Apple's pthread default, from `QuickJsConfig`'s own KDoc and from KitePDF's CLAUDE.md (#450). No coroutine test recurses deeply.

### KJ-04: `JSON.parse` on Rhino recurses once per nesting level with no limit, so deep input overflows the host stack

Status: proven, and held back with the other security findings until they are reported.

**`JSON.parse` on Rhino recurses once per nesting level with no limit, so deep input overflows the host stack**

- Repo: KiteJS
- Labels: area:interpreter, Rhino, kind:bug
- Severity: high
- Confidence: Likely
- Related: #139, #119, #120

#### What happens

`JsonParser.readValue` calls `readArray` or `readObject`, which call `readValue` again for each element, one Kotlin frame pair per nesting level. Nothing counts the depth, and the parser never calls `checkInterpreterInvocation` or the native stack probe, because no interpreter frame is entered. `'['.repeat(n)` costs one call of budget, so a short script feeds the parser any depth:

```js
JSON.parse('['.repeat(1e6) + ']'.repeat(1e6))
```

As #139 lays out for the source parser, what the host sees then depends on the target: a `StackOverflowError` on the JVM and Android, which `RhinoKiteJs.call` catches as a Throwable and reports as an engine error; a crash of the whole process on Kotlin/Native; the platform's own stack error on JS and a trap on Wasm.

`JSON.stringify` has the same shape (`NativeJSON.str`, `jo` and `ja` recurse per level, and `state.stack.contains` is a linear scan per level), for an object that a loop nests deeply.

#### What should happen

Deep JSON ends in a RangeError the script can catch, as on QuickJS, whose JSON parser checks the stack (`json_parse_value` runs `js_check_stack_overflow`; the probe build answers `RangeError: Maximum call stack size exceeded` for `JSON.parse('['.repeat(200000) + ']'.repeat(200000))`).

#### How to reproduce

On Rhino, on `linuxX64` or `macosArm64`:

```kotlin
KiteJs(Rhino).use { js ->
    js.evaluate("try { JSON.parse('['.repeat(1e6) + ']'.repeat(1e6)); 'parsed' } catch (e) { e.name }")
}
```

Expected: `"RangeError"`. Today the native test process ends.

#### Where

- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/json/JsonParser.kt:29` `readValue`, `:47` `readObject`, `:88` `readArray`.
- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/NativeJSON.kt:76` the call, `:204` `str`, `:264` `jo`, `:302` `ja`.
- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/Context.kt:112` `checkInterpreterInvocation`, which these paths never reach.

#### Notes

Read from source. Not run: the Rhino build needs `./kotlin`. #139 covers the source parser only and says so; this is the JSON parser, a separate path with the same missing guard. Upstream Rhino 1.9.1's `JsonParser.java` has no depth limit either, so the port inherited it; upstream only ever runs on the JVM, where it ends in a `StackOverflowError`.

### IK-01: JBIG2 halftone region decode runs for a time unrelated to the input size (DoS)

Status: proven, and held back with the other security findings until they are reported.

**JBIG2 halftone region decode runs for a time unrelated to the input size (DoS)**

- Repo: ImageKodec
- Labels: area:jbig2, kind:bug
- Severity: critical
- Confidence: Confirmed
- Related: #102 (the hardening pass that added `rowsShown` and the region-width cap but did not cover this path), KitePDF #621 (no halftone corpus file, so no sweep covers it)

#### What happens
`Jbig2Decoder` decodes a halftone region by iterating every cell of the gray
grid and calling `drawInto` once per cell with a pattern bitmap:

```
for (mg in 0 until hgh) for (ng in 0 until hgw) {       // up to 2^26 cells
    ...
    drawInto(region, pats[minOf(g, pats.size - 1)], x, y, hCombOp)
}
```

`drawInto` loops over the whole source pattern (`src.h * src.w`) regardless of
how much of it lands inside the small destination region:

```
for (yy in 0 until src.h) for (xx in 0 until src.w) { ... if (px >= dst.w ...) continue ... }
```

The grid cell count is capped at `hgw.toLong() * hgh > 1 shl 26` (line 582), and
the pattern side comes from the pattern dictionary's HDPW/HDPH, each a u8 (up to
255). Neither the product `grid cells * pattern area` nor the total halftone
work is bounded. A halftone segment with HGW=HGH=8192 (2^26 cells), HRX=HRY=0
(so every cell draws at (0,0)), referencing a pattern dictionary of one 255x255
pattern (GRAYMAX=0), forces 2^26 * 255 * 255 ≈ 4.4e12 inner iterations from a
JBIG2 stream well under 1 KiB, into a page a few hundred pixels wide. The MQ
arithmetic decoder feeds made-up 1-bits once the data ends (T.88 E.3.4), so the
gray-plane decode (`decodeGeneric` over 2^26 pixels, which also has no exhaustion
check) and the draw loop both run in full with almost no real coded data.

The grayscale `decodeGeneric` loop (line 611 path) does not consult
`mq.exhausted` and has no work cap of its own either, so even reaching the draw
loop already costs ~67M MQ-bit decodes from near-zero input.

This reaches KitePDF directly: `KiteImageData` calls `Jbig2Decoder.decode` for a
`/JBIG2Decode` image (`render/KiteImageData.kt:399`) and for a JBIG2 stencil
`/Mask` (`render/KiteImageData.kt:757`), both during page render. KitePDF wraps
the call in `runCatching`, but a hang is not an exception, so the lenient-salvage
wrapper cannot stop it. Opening a crafted PDF hangs the renderer. SECURITY.md
lists "a decode that does not terminate, or that runs for a time unrelated to the
input size" as a reportable vulnerability, and AGENTS.md notes `FuzzTest` never
drives `Jbig2Decoder`, so the fuzzer cannot find this.

#### What should happen
The halftone decode must be bounded by a work budget that is a function of the
input size (or at least the output page), the way `rowsShown` already bounds
generic/text regions. Options: bound `grid cells * pattern area` against a
ceiling before the draw loop; clip the `drawInto` loop bounds to the destination
(`for yy in max(0,-y0) until min(src.h, dst.h-y0)`), which also fixes the
per-cell waste; and give the gray-plane `decodeGeneric` an `mq.exhausted` early
stop as the symbol-dictionary loop has (`Jbig2Decoder.kt:713`). Malformed or
oversized halftone input must fail as `ImageDecodeException` (null from the
public overload), not hang.

#### How to reproduce
Build an embedded JBIG2 stream (no file header) of three segments for the lead
to run through `Jbig2Decoder.decodeChecked(data, null, 100, 100)`:

1. Page info (type 48): width 100, height 100, flags 0.
2. Pattern dictionary (type 16): flags 0x00 (HDMMR 0, template 0), HDPW 255,
   HDPH 255, GRAYMAX 0 (one 255x255 pattern), then a few bytes of coded data.
3. Immediate halftone region (type 22, referring to segment 2): region info
   100x100 at (0,0) combOp 0, flags 0x00, HGW 8192, HGH 8192, HGX 0, HGY 0,
   HRX 0, HRY 0, then a few bytes of coded data.

Expected: a prompt `ImageDecodeException`/null. Actual: ~4.4e12 iterations
(minutes to hours).

Measured through KitePDF on the JVM at `c4e7574` (imagekodec 0.3.0 from Maven
Central): a one-page PDF whose only image is a 100 by 100 `/JBIG2Decode` image
holding the three segments above (114 bytes of JBIG2, 792 bytes of PDF), rendered
with `AwtPdfRasterizer.renderToImage(page, 1.0)`. Only HGW and HGH change:

| Grid | JBIG2 bytes | Render time |
| --- | --- | --- |
| 16 by 16 | 114 | 30 ms |
| 32 by 32 | 114 | 91 ms |
| 64 by 64 | 114 | 395 ms |
| 128 by 128 | 114 | 1,297 ms |

The time grows about four times with each doubling of the grid side, so it
follows the cell count, not the input. The decoder accepts 2^26 cells (8192 by
8192), 4,096 times the last row: about 1.5 hours of CPU for one render of one
page, and the viewer renders again after a zoom.

#### Where
- `imagekodec/src/io/github/yuroyami/imagekodec/codec/Jbig2Decoder.kt:582` (grid cap, no product cap), `:589-592` (skip-map loop, same unbounded grid), `:611` (gray-plane decode, no exhaustion/work check), `:617-626` (draw loop), `:461-468` (`drawInto`, loops whole pattern without clipping).
- Reached from `kitepdf-core/src/io/github/yuroyami/kitepdf/core/render/KiteImageData.kt:399` and `:757`.

#### Notes
Read from source, loops traced end to end, and measured on the JVM as above. #102 hardened generic and text
regions (`rowsShown`, the >4x-page-width refusal) but did not touch the halftone
grid/pattern product. The grid cap alone (2^26) bounds the gray grid memory, not
the composite work.

## Could not be settled here

Claims that need a device, a Mac, Windows or a release build to prove or refute. Each says what would settle it.

| Where | Claim | What would settle it |
| --- | --- | --- |
| 6888, item 15 | CoreGraphics with a tiny dash pattern and a negative dash phase | Needs a Mac: render a 0.001-point dash pattern and a negative phase on CoreGraphicsCanvas and time it. Related: #770 and the P07 AWT dash draft. |
| 6888, item 16 | The transparency layer of each translucent XPS element may be page-sized | Needs a measurement of layer sizes for an XPS page with many translucent elements on Skia or Android. |
| 6888, item 17 | A custom PdfScriptHandler that suspends mid-call may break the viewer's one-call-at-a-time contract | Needs a scene test with a handler that suspends in keystroke while the reader types again. Related filed issues: #741, #735, #739. |
| 6888, item 18 | Whether close() ends nested calculateNow rounds | Needs a probe that closes a runner from another thread during nested rounds; not run. |
| 6888, item 19 | Whether AGP packages KiteJS's .so files from the new plain natives jar | Needs an Android app build against the next KiteJS release. |

## Refuted

Claims that the code, a probe or a reference reader contradicted. They were not filed; a "(part)" finding was filed for the rest of its claims.

| Id | Why it was not filed |
| --- | --- |
| KP-18 (part) | A documented design choice, not a defect; KP-18 asked to document the choice either way, and it is documented. The owner/user distinction an app needs to enforce it is drafted separately. |
| KP-45 (part) | Only the SinglePage part is refuted: the slot keeps its rasterizer across page changes, except when it passes through a chapter placeholder. The Paged and Spread parts are filed as #660. |
| KP-85 (part) | Not a defect: the references disagree and only PDFium differs (CONTRIBUTING: a difference that only PDFium shows is not a failure). Also the AFPercent_Format variant of the same claim. |
| KP-89 (part) | Only this part of KP-89 is refuted; the defect itself is filed as #805 (KiteRasterCanvas measured, Compose/CoreGraphics/Canvas2D traced). |
| KP-99 (part) | Partial refutation; the viewer part is filed as #689. |
| KJ-01 (part) | A large join on Rhino ends in an OutOfMemoryError that the facade reports, not a hang; reverse, indexOf, lastIndexOf, includes and copyWithin are the Rhino hangs (drafted). |
| line 6888, item 10 | A module's freeCompilerArgs may replace the template's -Xexport-kdoc on native targets: The iOS klib download hit HTTP 429; macOS uses the same kind of override, so the lists merge. |
| line 6888, item 21 | A NaN opacity from SVG could fail the page: OPUS read Compose 1.13.0-alpha02's Color: a NaN alpha becomes 0, so the shape paints nothing instead of throwing. Found sound. |

## Proposals not filed

Proposals of this audit that were judged too general for an issue, or that need an owner decision first.

| Line | Proposal | Why |
| --- | --- | --- |
| 6867, item 14 | DRM (Readium LCP) | Owner decision: concrete scope, but licensing decides whether it can exist in KitePDF. |

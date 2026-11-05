# Audit fixtures

Small files that the FABLE audit used to run its claims. #793, #796 and #817 point here; the writer files reproduce the issues named below. They are audit material, not corpus documents, so they are not in `corpus/manifest.json`.

- `encrypted-object-streams/`: the four one-page PDFs behind #793 (FABLE PDF-PARSE-02), made by `make_encrypted_fixtures.py` with pikepdf 10.17. `PdfDocument.open(bytes, "")` opens `plain_objstm.pdf` and `enc_classic_r4.pdf` and fails on `enc_objstm_r4.pdf` and `enc_objstm_r6.pdf` with "Repair found no catalog that leads to a page tree". #796 and #817 build on the same `/Encrypt` dictionaries.
- `writer/`: the inputs of the audit's writer probes (`base_*.pdf`, made by `make_bases.py`), the editor's outputs (`out_*.pdf`) and two incremental-save shapes (`inc_*.pdf`). `Probe.java` made the outputs. `out_actualtext_redacted.pdf` keeps `/ActualText (TOPSECRETACTUAL)` after a redaction (#1008); `out_sharedform_redacted.pdf` lost the shared form's text on both pages (#1007); `out_graft.pdf` carries `SOURCEPAGETWOSECRET` and `SOURCEPAGETHREESECRET` after one page was grafted (#1015); `out_cafe.pdf` shows the missing `/Encoding` (#668).

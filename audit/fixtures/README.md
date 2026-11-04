# Audit fixtures

Small files that the FABLE audit used to run its claims. They are audit material, not corpus documents, so they are not in `corpus/manifest.json`.

- `encrypted-object-streams/`: the four one-page PDFs behind PDF-PARSE-02, made by `make_encrypted_fixtures.py` with pikepdf 10.17. `PdfDocument.open(bytes, "")` opens `plain_objstm.pdf` and `enc_classic_r4.pdf` and fails on `enc_objstm_r4.pdf` and `enc_objstm_r6.pdf` with "Repair found no catalog that leads to a page tree".
- `writer/`: the PDF-WRITE lane's inputs (`base_*.pdf`, made by `make_bases.py`), the editor's outputs (`out_*.pdf`) and two incremental-save shapes (`inc_*.pdf`). `out_actualtext_redacted.pdf` keeps `/ActualText (TOPSECRETACTUAL)` after a redaction (PDF-WRITE-01); `out_sharedform_redacted.pdf` lost the shared form's text on both pages (PDF-WRITE-02); `out_graft.pdf` carries `SOURCEPAGETWOSECRET` and `SOURCEPAGETHREESECRET` after one page was grafted (PDF-WRITE-04); `out_cafe.pdf` shows the missing `/Encoding` (PDF-WRITE-05).

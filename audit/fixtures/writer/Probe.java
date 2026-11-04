import io.github.yuroyami.kitepdf.PdfDocument;
import io.github.yuroyami.kitepdf.PdfFormField;
import io.github.yuroyami.kitepdf.PdfPage;
import io.github.yuroyami.kitepdf.PdfPermissions;
import io.github.yuroyami.kitepdf.core.KiteRectangle;
import io.github.yuroyami.kitepdf.writer.ContentStreamBuilder;
import io.github.yuroyami.kitepdf.writer.PdfBuilder;
import io.github.yuroyami.kitepdf.writer.PdfEditor;
import io.github.yuroyami.kitepdf.writer.PdfSigner;
import io.github.yuroyami.kitepdf.writer.StandardFont;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Probe {
    static byte[] read(String n) throws Exception { return Files.readAllBytes(Path.of(n)); }
    static void write(String n, byte[] b) throws Exception { Files.write(Path.of(n), b); System.out.println("wrote " + n + " (" + b.length + " bytes)"); }
    static PdfDocument open(byte[] b) { return PdfDocument.Companion.open(b, new byte[0], false); }
    static Function1<ContentStreamBuilder, Unit> text(StandardFont f, double size, double x, double y, String s) {
        return csb -> { csb.text(f, size, x, y, s); return Unit.INSTANCE; };
    }

    public static void main(String[] a) throws Exception {
        // 1. Builder text with Latin-1 and WinAnsi-only characters through a standard font.
        byte[] cafe = new PdfBuilder().page(612.0, 792.0, text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "café € John’s – end")).build(true);
        write("out_cafe.pdf", cafe);
        System.out.println("  KitePDF extracts: " + open(cafe).getPages().get(0).extractText().trim());

        // 2. AES-256 R6.
        byte[] r6 = new PdfBuilder().setInfo("Secret Report", null, null, null, null, null)
            .encrypt("hunter2", "owner-pw", PdfPermissions.Companion.getAllowAll(), true, kotlin.random.RandomKt.Random(42))
            .page(612.0, 792.0, text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "Classified payload")).build(true);
        write("out_r6.pdf", r6);

        // 3. Text field value with typographic characters.
        PdfDocument form = open(read("base_form.pdf"));
        PdfEditor fe = form.edit(null);
        fe.setTextFieldValue(form.formField("Name"), "John’s café €");
        write("out_field.pdf", fe.saveIncremental());

        // 4. Redaction over text wrapped in /ActualText.
        PdfDocument act = open(read("base_actualtext.pdf"));
        PdfEditor ae = act.edit(null);
        ae.redactRegions(act.getPages().get(0), List.of(new KiteRectangle(60.0, 680.0, 400.0, 740.0)));
        byte[] actOut = ae.saveRewritten(false);
        write("out_actualtext_redacted.pdf", actOut);
        System.out.println("  KitePDF extracts after redaction: " + open(actOut).getPages().get(0).extractText().replace('\n', '|'));

        // 5. Shared form XObject: redact on page 1 only.
        PdfDocument sh = open(read("base_sharedform.pdf"));
        System.out.println("  shared base page2 before: " + sh.getPages().get(1).extractText().replace('\n', '|'));
        PdfEditor se = sh.edit(null);
        se.redactRegions(sh.getPages().get(0), List.of(new KiteRectangle(60.0, 680.0, 400.0, 740.0)));
        byte[] shOut = se.saveRewritten(false);
        write("out_sharedform_redacted.pdf", shOut);
        PdfDocument sh2 = open(shOut);
        System.out.println("  shared page1 after: " + sh2.getPages().get(0).extractText().replace('\n', '|'));
        System.out.println("  shared page2 after: " + sh2.getPages().get(1).extractText().replace('\n', '|'));

        // 6. Graft page 0 (with a link to page 3) into another document.
        PdfDocument src = open(read("base_graft_source.pdf"));
        PdfDocument tgt = open(read("base_graft_target.pdf"));
        PdfEditor ge = tgt.edit(null);
        ge.appendPage(src, 0);
        byte[] gOut = ge.saveRewritten(false);
        write("out_graft.pdf", gOut);
        PdfDocument g2 = open(gOut);
        System.out.println("  graft result pages=" + g2.getPageCount() + " staged objects=" + ge.getPendingObjectCount());

        // 7. Inherited CropBox after removePage.
        PdfDocument cr = open(read("base_cropbox.pdf"));
        System.out.println("  cropbox before: " + cr.getPages().get(0).getCropBox());
        PdfEditor ce = cr.edit(null);
        ce.removePage(cr.getPages().get(1));
        byte[] cOut = ce.saveIncremental();
        write("out_cropbox.pdf", cOut);
        System.out.println("  cropbox after removePage: " + open(cOut).getPages().get(0).getCropBox());

        // 8. Stamp, then prepare a signature.
        PdfDocument sd = open(cafe);
        PdfEditor sde = sd.edit(null);
        sde.stampPage(sd.getPages().get(0), text(StandardFont.CourierBold, 36.0, 150.0, 400.0, "STAMPTEXT"));
        PdfSigner signer = new PdfSigner(sd, sde, 1024);
        signer.prepareSignature("Sig1", null, 0);
        byte[] signed = signer.saveForSigning().getBytes();
        write("out_stamp_then_sign.pdf", signed);
        System.out.println("  stamp+sign extracts: " + open(signed).getPages().get(0).extractText().replace('\n', '|'));

        // 9. Signature into a document whose /AcroForm is a direct dictionary.
        PdfDocument dd = open(read("base_direct_acroform.pdf"));
        System.out.println("  direct acroform fields before: " + dd.getFormFields());
        PdfEditor dde = dd.edit(null);
        PdfSigner s2 = new PdfSigner(dd, dde, 1024);
        s2.prepareSignature("Sig1", null, 0);
        byte[] dOut = s2.saveForSigning().getBytes();
        write("out_direct_acroform_signed.pdf", dOut);
        System.out.println("  direct acroform fields after: " + open(dOut).getFormFields());
    }
}

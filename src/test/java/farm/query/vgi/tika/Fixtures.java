package farm.query.vgi.tika;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.ByteArrayOutputStream;

/** Generates tiny, real PDF/DOCX documents in-memory for the extraction tests. */
final class Fixtures {

    private Fixtures() {}

    static byte[] pdf(String text) {
        return pdf(new String[] {text});
    }

    /** A PDF with one page per supplied string (each page carries that text). */
    static byte[] pdf(String[] pageTexts) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(text);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A valid PDF carrying no pages at all (n_pages == 0). */
    static byte[] zeroPagePdf() {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A password-encrypted PDF (no user password supplied → parse fails). */
    static byte[] encryptedPdf(String text) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            AccessPermission ap = new AccessPermission();
            StandardProtectionPolicy spp =
                    new StandardProtectionPolicy("owner-secret", "user-secret", ap);
            spp.setEncryptionKeyLength(128);
            doc.protect(spp);
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A large-ish PDF: {@code pages} pages each carrying a paragraph of text. */
    static byte[] largePdf(int pages) {
        String[] texts = new String[pages];
        for (int i = 0; i < pages; i++) {
            texts[i] = "Page " + (i + 1) + " of a large document with searchable text content.";
        }
        return pdf(texts);
    }

    static byte[] docx(String text) {
        try (XWPFDocument d = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph p = d.createParagraph();
            XWPFRun r = p.createRun();
            r.setText(text);
            d.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A file with a valid PDF header but a corrupt body — triggers a parse error. */
    static byte[] corruptPdf() {
        return ("%PDF-1.4\n" + "not a valid pdf body ").getBytes();
    }

    /** Random-looking bytes with no recognizable format signature. */
    static byte[] garbage() {
        byte[] b = new byte[256];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) ((i * 37 + 11) & 0xFF);
        }
        return b;
    }
}

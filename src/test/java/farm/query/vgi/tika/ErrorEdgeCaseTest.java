package farm.query.vgi.tika;

import farm.query.vgi.function.Arguments;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Error and edge-case coverage for the Tika engine and the table/scalar functions. */
class ErrorEdgeCaseTest {

    private final TikaEngine engine = new TikaEngine();

    private static Arguments bytesArg(byte[] doc) {
        return new Arguments(List.of(doc), Map.of(), List.of(new ArrowType.Binary()));
    }

    // ---- empty / missing input --------------------------------------------

    @Test void emptyBytesDoNotCrash() {
        TikaEngine.ExtractResult r = engine.extract(new byte[0], null, false, "eng");
        // Tika raises ZeroByteFileException on empty input; the contract is that
        // it is captured per-row (no crash, NULL content + populated error).
        assertNull(r.content());
        assertNotNull(r.error());
        assertTrue(r.error().contains("ZeroByte") || r.error().contains("0 bytes"));
    }

    @Test void emptyBytesMetadataOnly() {
        TikaEngine.ExtractResult r = engine.metadataOnly(new byte[0], null);
        // Same: error captured, never thrown.
        assertNull(r.content());
        assertNotNull(r.error());
    }

    // ---- unsupported / garbage --------------------------------------------

    @Test void garbageBytesDetectAsOctetStream() {
        // No recognizable signature → Tika's octet-stream default.
        String mime = engine.detectMime(Fixtures.garbage(), null);
        assertEquals("application/octet-stream", mime);
    }

    @Test void garbageBytesExtractDoesNotThrow() {
        // Garbage parses as plain bytes (text/plain or octet-stream); the
        // contract is that it never throws and captures any error per-row.
        TikaEngine.ExtractResult r = engine.extract(Fixtures.garbage(), null, false, "eng");
        assertNotNull(r.mime());
    }

    // ---- corrupt -----------------------------------------------------------

    @Test void corruptPdfCapturesError() {
        TikaEngine.ExtractResult r = engine.extract(Fixtures.corruptPdf(), null, false, "eng");
        assertNull(r.content());
        assertNotNull(r.error());
    }

    // ---- encrypted ---------------------------------------------------------

    @Test void encryptedPdfCapturesError() {
        TikaEngine.ExtractResult r = engine.extract(Fixtures.encryptedPdf("secret body"), null, false, "eng");
        // Tika cannot decrypt without the password → error captured, no body.
        assertNotNull(r.error());
        assertNull(r.content());
    }

    // ---- zero-page ---------------------------------------------------------

    @Test void zeroPagePdfParses() {
        TikaEngine.ExtractResult r = engine.extract(Fixtures.zeroPagePdf(), null, false, "eng");
        assertNull(r.error());
        assertEquals("application/pdf", r.mime());
        // Body is empty (no pages) but extraction succeeds.
        assertTrue(r.content() == null || r.content().isBlank());
    }

    // ---- large doc ---------------------------------------------------------

    @Test void largePdfExtractsAllPages() {
        TikaEngine.ExtractResult r = engine.extract(Fixtures.largePdf(40), null, false, "eng");
        assertNull(r.error());
        assertEquals(Integer.valueOf(40), r.nPages());
        assertTrue(r.content().contains("Page 1 "));
        assertTrue(r.content().contains("Page 40 "));
    }

    // ---- strict re-raise ---------------------------------------------------

    @Test void strictExtractReRaisesOnCorrupt() {
        Arguments args = new Arguments(
                List.of(Fixtures.corruptPdf()),
                Map.of("strict", true),
                List.of(new ArrowType.Binary()));
        assertThrows(RuntimeException.class,
                () -> TestSupport.invoke(new ExtractFunction(engine), args));
    }

    @Test void strictExtractSucceedsOnValid() {
        Arguments args = new Arguments(
                List.of(Fixtures.pdf("clean body")),
                Map.of("strict", true),
                List.of(new ArrowType.Binary()));
        var result = TestSupport.invoke(new ExtractFunction(engine), args);
        assertNull(result.rows().get(0).get("error"));
    }

    @Test void strictMetadataReRaisesOnCorrupt() {
        Arguments args = new Arguments(
                List.of(Fixtures.corruptPdf()),
                Map.of("strict", true),
                List.of(new ArrowType.Binary()));
        assertThrows(RuntimeException.class,
                () -> TestSupport.invoke(new MetadataFunction(engine), args));
    }

    @Test void nonStrictMetadataCapturesError() {
        var result = TestSupport.invoke(new MetadataFunction(engine), bytesArg(Fixtures.corruptPdf()));
        assertNotNull(result.rows().get(0).get("error"));
    }

    // ---- OCR guard ---------------------------------------------------------

    @Test void ocrReturnsNullWhenTesseractAbsent() {
        // When Tesseract is not on PATH, ocr() must yield null, never throw.
        if (!engine.tesseractAvailable()) {
            String text = engine.ocr(Fixtures.pdf("nope"), null, "eng");
            assertNull(text);
        } else {
            // Tesseract present: OCR over a text PDF should not throw.
            engine.ocr(Fixtures.pdf("hello"), null, "eng");
        }
    }
}

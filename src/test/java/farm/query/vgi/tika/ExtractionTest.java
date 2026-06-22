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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractionTest {

    private final TikaEngine engine = new TikaEngine();

    private static Arguments bytesArg(byte[] doc) {
        return new Arguments(List.of(doc), Map.of(), List.of(new ArrowType.Binary()));
    }

    // ---- TikaEngine (the core) --------------------------------------------

    @Test void extractsTextAndMimeFromPdf() {
        TikaEngine.ExtractResult r = engine.extract(Fixtures.pdf("Hello from a PDF."), null, false, "eng");
        assertNull(r.error());
        assertEquals("application/pdf", r.mime());
        assertTrue(r.content().contains("Hello from a PDF."));
        assertEquals(Integer.valueOf(1), r.nPages());
        assertEquals("application/pdf", r.meta().get("Content-Type"));
    }

    @Test void extractsTextFromDocx() {
        TikaEngine.ExtractResult r = engine.extract(Fixtures.docx("Revenue grew in DOCX."), null, false, "eng");
        assertNull(r.error());
        assertTrue(r.mime().contains("wordprocessingml.document"));
        assertTrue(r.content().contains("Revenue grew in DOCX."));
    }

    @Test void capturesParseErrorPerRow() {
        TikaEngine.ExtractResult r = engine.extract(Fixtures.corruptPdf(), null, false, "eng");
        assertNull(r.content());
        assertNotNull(r.error());
    }

    @Test void detectsMime() {
        assertEquals("application/pdf", engine.detectMime(Fixtures.pdf("x"), null));
        assertTrue(engine.detectMime(Fixtures.docx("x"), null).contains("wordprocessingml"));
    }

    @Test void metadataOnlyHasNoBody() {
        TikaEngine.ExtractResult r = engine.metadataOnly(Fixtures.pdf("body text here"), null);
        assertNull(r.content());
        assertEquals("application/pdf", r.mime());
    }

    // ---- ExtractFunction (table function) ---------------------------------

    @Test void extractFunctionEmitsOneRowWithSchema() {
        var result = TestSupport.invoke(new ExtractFunction(engine), bytesArg(Fixtures.pdf("Table function text.")));
        assertEquals(1, result.totalRows());
        assertEquals(TikaSchemas.EXTRACT_SCHEMA, result.schema);
        Map<String, Object> row = result.rows().get(0);
        assertEquals("application/pdf", row.get("mime"));
        assertTrue(row.get("content").toString().contains("Table function text."));
        assertNull(row.get("error"));
        @SuppressWarnings("unchecked")
        Map<String, String> meta = (Map<String, String>) row.get("meta");
        assertEquals("application/pdf", meta.get("Content-Type"));
    }

    @Test void extractFunctionByPageHasPageColumn() {
        Arguments args = new Arguments(
                List.of(Fixtures.pdf("Page text.")),
                Map.of("by_page", true),
                List.of(new ArrowType.Binary()));
        var result = TestSupport.invoke(new ExtractFunction(engine), args);
        assertEquals(TikaSchemas.EXTRACT_BY_PAGE_SCHEMA, result.schema);
        assertEquals(Integer.valueOf(1), result.rows().get(0).get("page"));
    }

    @Test void extractFunctionCapturesErrorNotThrows() {
        var result = TestSupport.invoke(new ExtractFunction(engine), bytesArg(Fixtures.corruptPdf()));
        Map<String, Object> row = result.rows().get(0);
        assertNull(row.get("content"));
        assertNotNull(row.get("error"));
    }

    // ---- MetadataFunction --------------------------------------------------

    @Test void metadataFunctionOmitsContent() {
        var result = TestSupport.invoke(new MetadataFunction(engine), bytesArg(Fixtures.pdf("hidden body")));
        assertEquals(TikaSchemas.METADATA_SCHEMA, result.schema);
        assertFalse(result.schema.getFields().stream().anyMatch(f -> f.getName().equals("content")));
        assertEquals("application/pdf", result.rows().get(0).get("mime"));
    }
}

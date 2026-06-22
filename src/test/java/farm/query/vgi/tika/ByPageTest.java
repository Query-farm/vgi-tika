package farm.query.vgi.tika;

import farm.query.vgi.function.Arguments;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real per-page splitting (PDFBox) and the non-PDF fallback. */
class ByPageTest {

    private final TikaEngine engine = new TikaEngine();

    private static Arguments byPage(byte[] doc) {
        return new Arguments(List.of(doc), Map.of("by_page", true), List.of(new ArrowType.Binary()));
    }

    // ---- engine-level ------------------------------------------------------

    @Test void splitsMultiPagePdfIntoOneResultPerPage() {
        byte[] doc = Fixtures.pdf(new String[] {"First page alpha.", "Second page beta.", "Third page gamma."});
        List<TikaEngine.PageResult> pages = engine.extractByPage(doc, null, false, "eng");
        assertEquals(3, pages.size());
        assertEquals(1, pages.get(0).page());
        assertEquals(2, pages.get(1).page());
        assertEquals(3, pages.get(2).page());
        assertTrue(pages.get(0).result().content().contains("alpha"));
        assertTrue(pages.get(1).result().content().contains("beta"));
        assertTrue(pages.get(2).result().content().contains("gamma"));
        // Page 1 carries the document metadata; later pages skip it.
        assertNull(pages.get(0).result().error());
        assertEquals(Integer.valueOf(3), pages.get(0).result().nPages());
        assertEquals(Integer.valueOf(3), pages.get(2).result().nPages());
    }

    @Test void perPageContentIsActuallyDifferent() {
        byte[] doc = Fixtures.pdf(new String[] {"OnlyOnPageOne", "OnlyOnPageTwo"});
        List<TikaEngine.PageResult> pages = engine.extractByPage(doc, null, false, "eng");
        assertEquals(2, pages.size());
        assertTrue(pages.get(0).result().content().contains("OnlyOnPageOne"));
        assertTrue(!pages.get(0).result().content().contains("OnlyOnPageTwo"));
        assertTrue(pages.get(1).result().content().contains("OnlyOnPageTwo"));
        assertTrue(!pages.get(1).result().content().contains("OnlyOnPageOne"));
    }

    @Test void nonPdfFallsBackToSinglePage() {
        List<TikaEngine.PageResult> pages = engine.extractByPage(Fixtures.docx("DOCX body text."), null, false, "eng");
        assertEquals(1, pages.size());
        assertTrue(pages.get(0).result().content().contains("DOCX body text."));
    }

    @Test void corruptPdfByPageCapturesError() {
        List<TikaEngine.PageResult> pages = engine.extractByPage(Fixtures.corruptPdf(), null, false, "eng");
        assertEquals(1, pages.size());
        assertNotNull(pages.get(0).result().error());
        assertNull(pages.get(0).result().content());
    }

    // ---- table-function level ----------------------------------------------

    @Test void extractFunctionByPageEmitsRowPerPage() {
        byte[] doc = Fixtures.pdf(new String[] {"P1 text", "P2 text", "P3 text", "P4 text"});
        var result = TestSupport.invoke(new ExtractFunction(engine), byPage(doc));
        assertEquals(TikaSchemas.EXTRACT_BY_PAGE_SCHEMA, result.schema);
        assertEquals(4, result.totalRows());
        var rows = result.rows();
        assertEquals(Integer.valueOf(1), rows.get(0).get("page"));
        assertEquals(Integer.valueOf(4), rows.get(3).get("page"));
        assertEquals(Integer.valueOf(4), rows.get(0).get("n_pages"));
        assertTrue(rows.get(2).get("content").toString().contains("P3 text"));
    }

    @Test void extractFunctionByPageSinglePagePdf() {
        var result = TestSupport.invoke(new ExtractFunction(engine), byPage(Fixtures.pdf("just one page")));
        assertEquals(1, result.totalRows());
        assertEquals(Integer.valueOf(1), result.rows().get(0).get("page"));
    }
}

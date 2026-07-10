package farm.query.vgi.tika;

import farm.query.vgi.function.Arguments;
import farm.query.vgirpc.AnnotatedBatch;
import farm.query.vgirpc.OutputCollector;
import farm.query.vgirpc.wire.Allocators;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end-ish coverage for the {@code extract_all} table-in-out over a path column. */
class ExtractAllTest {

    private final TikaEngine engine = new TikaEngine();

    private static Field utf8Field(String name) {
        return new Field(name, new FieldType(true, new ArrowType.Utf8(), null), null);
    }

    /** Drive extract_all with an input batch of (id, path) rows; collect output rows. */
    private List<Map<String, Object>> run(Schema inputSchema, VectorSchemaRoot inputRoot, Map<String, Object> named) {
        ExtractAllFunction fn = new ExtractAllFunction();
        Arguments args = new Arguments(List.of(), named, List.of());

        // Resolve the bound output schema the same way the worker does.
        String idColumn = named.containsKey("id") ? String.valueOf(named.get("id")) : "";
        Schema outSchema = ExtractAllFunction.outputSchema(inputSchema, idColumn);

        var initParams = new farm.query.vgi.tableinout.TableInOutInitParams(
                // trailing BoundStorage (vgi 0.4.0) + byte[] storage-handle (vgi
                // 0.16.0) components — both unused by this in-process test driver.
                fn.name(), args, inputSchema, outSchema, Map.<String, Object>of(),
                Allocators.root(), null, null);

        var state = fn.createExchange(initParams);
        OutputCollector collector = new OutputCollector(outSchema, "test", true);
        try (AnnotatedBatch batch = new AnnotatedBatch(inputRoot, Map.of())) {
            state.onInputBatch(batch, collector, null);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OutputCollector.Entry e : collector.entries()) {
            if (e.isData()) {
                rows.addAll(TestSupport.rowsOf(e.root()));
                e.root().close();
            }
        }
        return rows;
    }

    @Test void extractsColumnOfPathsWithIdPassthrough(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("a.pdf");
        Path docx = dir.resolve("b.docx");
        Files.write(pdf, Fixtures.pdf("Alpha PDF body."));
        Files.write(docx, Fixtures.docx("Beta DOCX body."));

        Schema in = new Schema(List.of(utf8Field("id"), utf8Field("path")));
        try (VectorSchemaRoot root = VectorSchemaRoot.create(in, Allocators.root())) {
            root.allocateNew();
            VarCharVector idv = (VarCharVector) root.getVector("id");
            VarCharVector pv = (VarCharVector) root.getVector("path");
            idv.setSafe(0, new Text("doc-1"));
            pv.setSafe(0, new Text(pdf.toString()));
            idv.setSafe(1, new Text("doc-2"));
            pv.setSafe(1, new Text(docx.toString()));
            root.setRowCount(2);

            Map<String, Object> named = new LinkedHashMap<>();
            named.put("id", "id");
            List<Map<String, Object>> rows = run(in, root, named);

            assertEquals(2, rows.size());
            // id passthrough preserved on each output row.
            assertEquals("doc-1", rows.get(0).get("id"));
            assertEquals("doc-2", rows.get(1).get("id"));
            assertEquals("application/pdf", rows.get(0).get("mime"));
            assertTrue(rows.get(0).get("content").toString().contains("Alpha PDF body."));
            assertTrue(rows.get(1).get("mime").toString().contains("wordprocessingml"));
            assertTrue(rows.get(1).get("content").toString().contains("Beta DOCX body."));
            assertNull(rows.get(0).get("error"));
        }
    }

    @Test void capturesPerRowErrorForMissingFile(@TempDir Path dir) throws Exception {
        Path good = dir.resolve("good.pdf");
        Files.write(good, Fixtures.pdf("Good doc."));
        Path missing = dir.resolve("does-not-exist.pdf");

        Schema in = new Schema(List.of(utf8Field("id"), utf8Field("path")));
        try (VectorSchemaRoot root = VectorSchemaRoot.create(in, Allocators.root())) {
            root.allocateNew();
            VarCharVector idv = (VarCharVector) root.getVector("id");
            VarCharVector pv = (VarCharVector) root.getVector("path");
            idv.setSafe(0, new Text("ok"));
            pv.setSafe(0, new Text(good.toString()));
            idv.setSafe(1, new Text("bad"));
            pv.setSafe(1, new Text(missing.toString()));
            root.setRowCount(2);

            Map<String, Object> named = new LinkedHashMap<>();
            named.put("id", "id");
            List<Map<String, Object>> rows = run(in, root, named);

            assertEquals(2, rows.size());
            // The whole batch does not fail; the bad row carries an error, NULL content.
            assertNull(rows.get(0).get("error"));
            assertNotNull(rows.get(1).get("error"));
            assertNull(rows.get(1).get("content"));
            assertEquals("bad", rows.get(1).get("id"));
        }
    }

    @Test void schemaPrependsIdColumn() {
        Schema in = new Schema(List.of(utf8Field("id"), utf8Field("path")));
        Schema out = ExtractAllFunction.outputSchema(in, "id");
        assertEquals("id", out.getFields().get(0).getName());
        assertNotNull(out.findField("content"));
        assertNotNull(out.findField("meta"));
        assertTrue(out.findField("meta").getType() instanceof ArrowType.Map);
    }

    @Test void resolveDocColumnPicksFirstNonId() {
        Schema in = new Schema(List.of(utf8Field("id"), utf8Field("body")));
        assertEquals("body", ExtractAllFunction.resolveDocColumn(in, "", "id"));
        assertEquals("path", ExtractAllFunction.resolveDocColumn(
                new Schema(List.of(utf8Field("id"), utf8Field("path"))), "path", "id"));
    }
}

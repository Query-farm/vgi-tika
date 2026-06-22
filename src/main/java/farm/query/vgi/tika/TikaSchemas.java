package farm.query.vgi.tika;

import farm.query.vgi.types.Schemas;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.complex.impl.UnionMapWriter;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.Text;

import java.util.List;
import java.util.Map;

/**
 * Shared Arrow schemas + batch-builder for the extraction family
 * ({@code extract}, {@code extract_all}, {@code metadata}).
 *
 * <p>The {@code meta} column is a DuckDB {@code MAP(VARCHAR, VARCHAR)} — over
 * Arrow that is {@code Map<entries: struct<key: utf8 not null, value: utf8>>}.
 */
public final class TikaSchemas {

    private TikaSchemas() {}

    /** Full extract output: (content, mime, n_pages, lang, meta, error). */
    public static final Schema EXTRACT_SCHEMA = new Schema(List.of(
            commented("content", Schemas.UTF8, "Extracted plain-text body of the document, or NULL on error."),
            commented("mime", Schemas.UTF8, "Detected media type (e.g. application/pdf)."),
            commented("n_pages", Schemas.INT32, "Page / slide / sheet count when the format reports it, else NULL."),
            commented("lang", Schemas.UTF8, "Document language code if Tika reported one in the metadata."),
            mapField("meta", "Full Tika metadata bag as a MAP(VARCHAR, VARCHAR)."),
            commented("error", Schemas.UTF8, "Parse error message for this row, or NULL on success.")));

    /** by_page variant: extract output plus a leading {@code page} column. */
    public static final Schema EXTRACT_BY_PAGE_SCHEMA = new Schema(List.of(
            commented("page", Schemas.INT32, "1-based page / slide / sheet ordinal."),
            commented("content", Schemas.UTF8, "Extracted plain-text body of this page, or NULL on error."),
            commented("mime", Schemas.UTF8, "Detected media type (e.g. application/pdf)."),
            commented("n_pages", Schemas.INT32, "Total page count of the source document."),
            commented("lang", Schemas.UTF8, "Document language code if Tika reported one."),
            mapField("meta", "Full Tika metadata bag as a MAP(VARCHAR, VARCHAR)."),
            commented("error", Schemas.UTF8, "Parse error message for this row, or NULL on success.")));

    /** metadata() output: extract schema minus content. */
    public static final Schema METADATA_SCHEMA = new Schema(List.of(
            commented("mime", Schemas.UTF8, "Detected media type (e.g. application/pdf)."),
            commented("n_pages", Schemas.INT32, "Page / slide / sheet count when reported, else NULL."),
            commented("lang", Schemas.UTF8, "Document language code if Tika reported one."),
            mapField("meta", "Full Tika metadata bag as a MAP(VARCHAR, VARCHAR)."),
            commented("error", Schemas.UTF8, "Parse error message for this row, or NULL on success.")));

    static Field commented(String name, ArrowType type, String comment) {
        return new Field(name, new FieldType(true, type, null, Map.of("comment", comment)), null);
    }

    /** A nullable MAP(VARCHAR, VARCHAR) field carrying a column comment. */
    static Field mapField(String name, String comment) {
        Field key = new Field(MapVector.KEY_NAME,
                new FieldType(false, Schemas.UTF8, null), null);
        Field value = new Field(MapVector.VALUE_NAME,
                new FieldType(true, Schemas.UTF8, null), null);
        Field entries = new Field(MapVector.DATA_VECTOR_NAME,
                new FieldType(false, new ArrowType.Struct(), null), List.of(key, value));
        return new Field(name,
                new FieldType(true, new ArrowType.Map(false), null, Map.of("comment", comment)),
                List.of(entries));
    }

    /** Write one metadata MAP cell at {@code row} into a {@link MapVector}. */
    static void writeMap(MapVector mapVector, int row, Map<String, String> entries) {
        UnionMapWriter writer = mapVector.getWriter();
        writer.setPosition(row);
        writer.startMap();
        if (entries != null) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                if (e.getKey() == null) continue;
                writer.startEntry();
                writer.key().varChar().writeVarChar(e.getKey());
                if (e.getValue() != null) {
                    writer.value().varChar().writeVarChar(e.getValue());
                } else {
                    writer.value().varChar().writeNull();
                }
                writer.endEntry();
            }
        }
        writer.endMap();
    }

    // ---- batch builders ----------------------------------------------------

    /**
     * Build a one-row {@link VectorSchemaRoot} for {@link #EXTRACT_SCHEMA} (or
     * a zero-row root when the document split into pages handled elsewhere).
     */
    static VectorSchemaRoot singleRow(BufferAllocator alloc, TikaEngine.ExtractResult r) {
        VectorSchemaRoot root = VectorSchemaRoot.create(EXTRACT_SCHEMA, alloc);
        root.allocateNew();
        setUtf8(root, "content", 0, r.content());
        setUtf8(root, "mime", 0, r.mime());
        setInt(root, "n_pages", 0, r.nPages());
        setUtf8(root, "lang", 0, r.lang());
        writeMap((MapVector) root.getVector("meta"), 0, r.meta());
        setUtf8(root, "error", 0, r.error());
        root.setRowCount(1);
        return root;
    }

    static VectorSchemaRoot metadataRow(BufferAllocator alloc, TikaEngine.ExtractResult r) {
        VectorSchemaRoot root = VectorSchemaRoot.create(METADATA_SCHEMA, alloc);
        root.allocateNew();
        setUtf8(root, "mime", 0, r.mime());
        setInt(root, "n_pages", 0, r.nPages());
        setUtf8(root, "lang", 0, r.lang());
        writeMap((MapVector) root.getVector("meta"), 0, r.meta());
        setUtf8(root, "error", 0, r.error());
        root.setRowCount(1);
        return root;
    }

    static void setUtf8(VectorSchemaRoot root, String col, int row, String value) {
        VarCharVector v = (VarCharVector) root.getVector(col);
        if (value == null) {
            v.setNull(row);
        } else {
            v.setSafe(row, new Text(value));
        }
    }

    static void setInt(VectorSchemaRoot root, String col, int row, Integer value) {
        IntVector v = (IntVector) root.getVector(col);
        if (value == null) {
            v.setNull(row);
        } else {
            v.setSafe(row, value);
        }
    }
}

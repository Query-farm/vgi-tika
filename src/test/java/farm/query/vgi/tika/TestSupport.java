package farm.query.vgi.tika;

import farm.query.vgi.function.Arguments;
import farm.query.vgi.internal.SchemaUtil;
import farm.query.vgi.protocol.BindResponse;
import farm.query.vgi.table.TableBindParams;
import farm.query.vgi.table.TableFunction;
import farm.query.vgi.table.TableInitParams;
import farm.query.vgi.table.TableProducerState;
import farm.query.vgirpc.OutputCollector;
import farm.query.vgirpc.wire.Allocators;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal in-process table-function driver, mirroring vgi-trains-java's TestSupport. */
public final class TestSupport {

    private TestSupport() {}

    public static Result invoke(TableFunction fn, Arguments args) {
        TableBindParams bind = new TableBindParams(fn.name(), args, null, Map.of());
        BindResponse bindResp = fn.onBind(bind);
        Schema outputSchema = SchemaUtil.deserializeSchema(bindResp.output_schema());

        TableInitParams init = new TableInitParams(
                fn.name(), args, outputSchema, Map.of(),
                Allocators.root(),
                null, List.of(), List.of(),
                null, null, null, null, null, null,
                new byte[0], null, null, null,
                // vgi 0.4.0 added the AT-syntax (atUnit, atValue) and storage
                // record components — unused by this in-process test driver.
                null, null, null, null);

        TableProducerState state = fn.createProducer(init);

        List<List<Map<String, Object>>> batches = new ArrayList<>();
        while (true) {
            OutputCollector collector = new OutputCollector(outputSchema, "test", true);
            state.produceTick(collector, null);
            if (!collector.entries().isEmpty()
                    && collector.entries().stream().anyMatch(OutputCollector.Entry::isData)) {
                OutputCollector.Entry data = collector.dataEntry();
                VectorSchemaRoot root = data.root();
                batches.add(rowsOf(root));
                root.close();
            }
            if (collector.finished()) break;
        }
        return new Result(outputSchema, batches);
    }

    static List<Map<String, Object>> rowsOf(VectorSchemaRoot root) {
        int n = root.getRowCount();
        List<Map<String, Object>> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (var f : root.getSchema().getFields()) {
                FieldVector v = root.getVector(f.getName());
                r.put(f.getName(), readCell(v, i));
            }
            rows.add(r);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static Object readCell(FieldVector v, int row) {
        if (v.isNull(row)) return null;
        if (v instanceof MapVector) {
            // Decode MAP<utf8,utf8> into a plain Map for assertions.
            Map<String, String> out = new LinkedHashMap<>();
            Object obj = v.getObject(row);
            if (obj instanceof List<?> entries) {
                for (Object e : entries) {
                    if (e instanceof Map<?, ?> kv) {
                        Object k = kv.get("key");
                        Object val = kv.get("value");
                        out.put(String.valueOf(k), val == null ? null : String.valueOf(val));
                    }
                }
            }
            return out;
        }
        Object raw = v.getObject(row);
        if (raw instanceof Text t) return t.toString();
        return raw;
    }

    public static final class Result {
        public final Schema schema;
        public final List<List<Map<String, Object>>> batches;

        Result(Schema schema, List<List<Map<String, Object>>> batches) {
            this.schema = schema;
            this.batches = batches;
        }

        public int totalRows() {
            int n = 0;
            for (var b : batches) n += b.size();
            return n;
        }

        public List<Map<String, Object>> rows() {
            List<Map<String, Object>> all = new ArrayList<>();
            for (var b : batches) all.addAll(b);
            return all;
        }
    }
}

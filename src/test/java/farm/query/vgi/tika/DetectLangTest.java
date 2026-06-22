package farm.query.vgi.tika;

import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.util.Text;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** detect_lang / detect_lang_conf scalar coverage. */
class DetectLangTest {

    private static final String ENGLISH =
            "The quick brown fox jumps over the lazy dog. This is a sufficiently long "
                    + "English sentence so the language detector can settle on a confident result.";

    private final org.apache.arrow.memory.RootAllocator alloc = new org.apache.arrow.memory.RootAllocator();

    private VarCharVector textVec(String... values) {
        VarCharVector v = new VarCharVector("text", alloc);
        v.allocateNew();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) v.setNull(i);
            else v.setSafe(i, new Text(values[i]));
        }
        v.setValueCount(values.length);
        return v;
    }

    @Test void detectsEnglishLanguageCode() {
        try (VarCharVector in = textVec(ENGLISH, null, "");
             VarCharVector out = new VarCharVector("out", alloc)) {
            out.allocateNew();
            new DetectLangFunction().compute(in, out);
            assertNotNull(out.getObject(0));
            assertTrue(out.getObject(0).toString().equals("en"));
            assertTrue(out.isNull(1)); // null in -> null out
            assertTrue(out.isNull(2)); // blank in -> null out
        }
    }

    @Test void confidenceIsBetweenZeroAndOne() {
        try (VarCharVector in = textVec(ENGLISH, null, "");
             Float8Vector out = new Float8Vector("out", alloc)) {
            out.allocateNew();
            new DetectLangConfFunction().compute(in, out);
            double conf = out.get(0);
            assertTrue(conf > 0.0 && conf <= 1.0, "confidence in (0,1], got " + conf);
            assertTrue(out.isNull(1));
            assertTrue(out.isNull(2));
        }
    }
}

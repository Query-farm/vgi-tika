package farm.query.vgi.tika;

import farm.query.vgi.function.ArgSpec;
import farm.query.vgi.function.TypeBoundPredicate;
import org.apache.arrow.vector.types.pojo.ArrowType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared helpers for the per-object discovery/description metadata that the
 * {@code vgi-lint} strict profile (0.26.0) expects on <b>every</b> function and
 * table.
 *
 * <p>Each function/table surfaces these in its {@code FunctionMetadata.tags}:
 * <ul>
 *   <li>{@code vgi.title} (VGI124) — human-friendly display name (must not
 *       normalize-equal the machine name, VGI125)</li>
 *   <li>{@code vgi.doc_llm} (VGI112) — a Markdown narrative for an LLM/agent
 *       audience</li>
 *   <li>{@code vgi.doc_md} (VGI113) — a Markdown narrative for human docs
 *       (distinct content from {@code vgi.doc_llm})</li>
 *   <li>{@code vgi.keywords} (VGI126/VGI138) — a JSON array of search-term/synonym
 *       strings</li>
 * </ul>
 *
 * <p>Per-object {@code vgi.source_url} is intentionally <b>not</b> emitted: the
 * linter (VGI139) wants {@code source_url} only on the catalog object, so the
 * single repo-level source link lives on the catalog (see {@code Main}).
 */
public final class Meta {

    private Meta() {}

    // Doc-carrying ArgSpec builders (VGI312). The stock ArgSpec.any/named/table
    // factories leave the per-argument doc empty; these mirror those factories
    // exactly (same flag/default semantics) but populate the canonical record's
    // `doc` component so every argument's description serializes to the linter.

    /** An {@code any}-typed positional argument with a per-argument doc (VGI312). */
    public static ArgSpec anyArg(String name, int position, String doc) {
        return new ArgSpec(
                name, position, new ArrowType.Null(), doc,
                /*isConst*/ false, /*hasDefault*/ false, /*defaultValue*/ "",
                List.<TypeBoundPredicate>of(),
                /*varargs*/ false, /*anyType*/ true, /*tableInput*/ false);
    }

    /** A named (defaulted) argument with a per-argument doc (VGI312). */
    public static ArgSpec namedArg(String name, ArrowType type, String defaultValue, String doc) {
        return new ArgSpec(
                name, -1, type, doc,
                /*isConst*/ true, /*hasDefault*/ true, defaultValue,
                List.<TypeBoundPredicate>of(),
                /*varargs*/ false, /*anyType*/ false, /*tableInput*/ false);
    }

    /** A table-input argument with a per-argument doc (VGI312). */
    public static ArgSpec tableArg(String name, int position, String doc) {
        return new ArgSpec(
                name, position, new ArrowType.Null(), doc,
                /*isConst*/ false, /*hasDefault*/ false, /*defaultValue*/ "",
                List.<TypeBoundPredicate>of(),
                /*varargs*/ false, /*anyType*/ false, /*tableInput*/ true);
    }

    /**
     * Build the four standard per-object discovery/description tags.
     *
     * @param title    human display name (VGI124/VGI125)
     * @param docLlm   Markdown narrative aimed at LLMs/agents (VGI112)
     * @param docMd    Markdown narrative for human docs (VGI113); must differ from {@code docLlm}
     * @param keywords search terms/synonyms, emitted as a JSON array (VGI126/VGI138)
     */
    public static Map<String, String> objectTags(
            String title, String docLlm, String docMd, String... keywords) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("vgi.title", title);
        tags.put("vgi.doc_llm", docLlm);
        tags.put("vgi.doc_md", docMd);
        tags.put("vgi.keywords", keywordsJson(keywords));
        // Per-object vgi.source_url is intentionally omitted (VGI139): the
        // source link lives only on the catalog object.
        return tags;
    }

    /** Render keyword terms as a JSON array of strings, e.g. {@code ["a","b"]} (VGI138). */
    public static String keywordsJson(String... keywords) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < keywords.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonString(keywords[i]));
        }
        return sb.append(']').toString();
    }

    /** Minimal JSON string escaper for the keywords array. */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}

package farm.query.vgi.tika;

import java.util.LinkedHashMap;
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
 *   <li>{@code vgi.keywords} (VGI126) — comma-separated search terms/synonyms</li>
 *   <li>{@code vgi.source_url} (VGI128) — link to the implementing source file</li>
 * </ul>
 */
public final class Meta {

    private Meta() {}

    /** Base GitHub blob URL for source files in this repo (pinned to {@code main}). */
    private static final String SOURCE_BASE =
            "https://github.com/Query-farm/vgi-tika/blob/main/src/main/java/farm/query/vgi/tika";

    /** Build the implementation {@code vgi.source_url} for a file under the tika package. */
    public static String sourceUrl(String fileName) {
        return SOURCE_BASE + "/" + fileName;
    }

    /**
     * Build the five standard per-object discovery/description tags.
     *
     * @param title    human display name (VGI124/VGI125)
     * @param docLlm   Markdown narrative aimed at LLMs/agents (VGI112)
     * @param docMd    Markdown narrative for human docs (VGI113); must differ from {@code docLlm}
     * @param keywords comma-separated search terms (VGI126)
     * @param fileName implementing source file, e.g. {@code "ExtractFunction.java"} (VGI128)
     */
    public static Map<String, String> objectTags(
            String title, String docLlm, String docMd, String keywords, String fileName) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("vgi.title", title);
        tags.put("vgi.doc_llm", docLlm);
        tags.put("vgi.doc_md", docMd);
        tags.put("vgi.keywords", keywords);
        tags.put("vgi.source_url", sourceUrl(fileName));
        return tags;
    }
}

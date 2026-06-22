package farm.query.vgi.tika;

import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.ContentHandler;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin, thread-safe wrapper around Apache Tika's {@link AutoDetectParser}.
 *
 * <p>One instance is shared across every function and call. Tika's parser and
 * detector are stateless and safe to reuse; the per-parse state lives in the
 * {@link Metadata} / {@link ParseContext} created per call. The first parse pays
 * the class-load cost (PDFBox/POI), which the persistent VGI worker amortizes
 * across queries.
 */
public final class TikaEngine {

    private static final TikaEngine SHARED = new TikaEngine();

    /** Tika caps extracted text at 100k chars by default; -1 = unbounded. */
    private static final int WRITE_LIMIT = -1;

    private final AutoDetectParser parser;
    private final Detector detector;

    public TikaEngine() {
        this(TikaConfig.getDefaultConfig());
    }

    public TikaEngine(TikaConfig config) {
        this.parser = new AutoDetectParser(config);
        this.detector = config.getDetector();
    }

    public static TikaEngine shared() {
        return SHARED;
    }

    /** Result of a single document parse. {@code error} is non-null on failure. */
    public record ExtractResult(
            String content,
            String mime,
            Integer nPages,
            String lang,
            Map<String, String> meta,
            String error) {

        static ExtractResult failure(String error) {
            return new ExtractResult(null, null, null, null, Map.of(), error);
        }
    }

    // ---- bytes / path acquisition -----------------------------------------

    /** Open a path as a stream, or wrap an in-memory byte[]. Caller closes. */
    private static InputStream open(byte[] bytes, Path path) throws IOException {
        if (path != null) {
            return Files.newInputStream(path);
        }
        return new ByteArrayInputStream(bytes != null ? bytes : new byte[0]);
    }

    // ---- full extraction (text + metadata) --------------------------------

    public ExtractResult extract(byte[] bytes, Path path, boolean ocrEnabled, String ocrLang) {
        Metadata metadata = new Metadata();
        if (path != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, path.getFileName().toString());
        }
        ContentHandler handler = new BodyContentHandler(WRITE_LIMIT);
        ParseContext context = parseContext(ocrEnabled, ocrLang);
        try (InputStream in = open(bytes, path)) {
            parser.parse(in, handler, metadata, context);
        } catch (Exception e) {
            return ExtractResult.failure(describe(e));
        }
        return resultFrom(handler.toString(), metadata, null);
    }

    /**
     * Per-page extraction. For PDFs we use PDFBox's {@link PDFTextStripper} to
     * split the text-layer body on real page boundaries, yielding one result per
     * page. For every other format (and for image-only / scanned PDFs handled via
     * OCR) Tika has no page-boundary signal, so we fall back to a single result
     * carrying the whole body with {@code page = n_pages}.
     *
     * <p>Each per-page result repeats the document-level {@code mime}, {@code lang},
     * and total {@code n_pages}; the {@code meta} bag rides only on the first page
     * to avoid duplicating the (potentially large) document metadata on every row.
     */
    public List<PageResult> extractByPage(byte[] bytes, Path path, boolean ocrEnabled, String ocrLang) {
        ExtractResult whole = extract(bytes, path, ocrEnabled, ocrLang);
        if (whole.error() != null) {
            return List.of(new PageResult(whole.nPages() != null ? whole.nPages() : 1, whole));
        }

        boolean isPdf = "application/pdf".equals(whole.mime());
        // Only split a PDF's text layer when OCR is off; with OCR the body comes
        // from Tika's Tesseract path, which PDFTextStripper cannot reproduce.
        if (isPdf && !ocrEnabled) {
            List<PageResult> pages = splitPdfPages(bytes, path, whole);
            if (pages != null) {
                return pages;
            }
        }

        int total = whole.nPages() != null ? whole.nPages() : 1;
        return List.of(new PageResult(total, whole));
    }

    /**
     * Split a PDF's text layer into one {@link PageResult} per page. Returns
     * {@code null} on any failure so the caller can fall back to whole-document
     * behaviour (never throws — by_page must not regress error capture).
     */
    private List<PageResult> splitPdfPages(byte[] bytes, Path path, ExtractResult whole) {
        try (PDDocument doc = load(bytes, path)) {
            int total = doc.getNumberOfPages();
            if (total <= 0) {
                return List.of(new PageResult(0, whole));
            }
            PDFTextStripper stripper = new PDFTextStripper();
            List<PageResult> pages = new ArrayList<>(total);
            for (int p = 1; p <= total; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String pageText = stripper.getText(doc);
                // Document metadata (the meta bag + lang) rides on page 1 only.
                Map<String, String> meta = p == 1 ? whole.meta() : Map.of();
                String lang = p == 1 ? whole.lang() : null;
                ExtractResult pageResult = new ExtractResult(
                        pageText, whole.mime(), total, lang, meta, null);
                pages.add(new PageResult(p, pageResult));
            }
            return pages;
        } catch (Exception e) {
            return null;
        }
    }

    private static PDDocument load(byte[] bytes, Path path) throws IOException {
        if (path != null) {
            return Loader.loadPDF(path.toFile());
        }
        return Loader.loadPDF(bytes != null ? bytes : new byte[0]);
    }

    /** One page of a by-page extraction: a 1-based ordinal plus its row payload. */
    public record PageResult(int page, ExtractResult result) {}

    /** Metadata-only extraction (no body text). */
    public ExtractResult metadataOnly(byte[] bytes, Path path) {
        Metadata metadata = new Metadata();
        if (path != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, path.getFileName().toString());
        }
        // A no-op handler: parse for side effects on Metadata only.
        ContentHandler handler = new BodyContentHandler(0);
        ParseContext context = parseContext(false, null);
        try (InputStream in = open(bytes, path)) {
            parser.parse(in, handler, metadata, context);
        } catch (org.apache.tika.exception.WriteLimitReachedException limit) {
            // Expected: handler limit is 0, we only wanted metadata.
        } catch (Exception e) {
            return new ExtractResult(null, null, null, null, Map.of(), describe(e));
        }
        ExtractResult full = resultFrom(null, metadata, null);
        return new ExtractResult(null, full.mime(), full.nPages(), full.lang(), full.meta(), null);
    }

    private ExtractResult resultFrom(String content, Metadata metadata, String error) {
        Map<String, String> meta = new LinkedHashMap<>();
        for (String name : metadata.names()) {
            meta.put(name, String.join("; ", metadata.getValues(name)));
        }
        String mime = metadata.get(Metadata.CONTENT_TYPE);
        Integer nPages = parseIntOrNull(metadata.get("xmpTPg:NPages"));
        if (nPages == null) {
            nPages = parseIntOrNull(metadata.get("Page-Count"));
        }
        String lang = metadata.get(TikaCoreProperties.LANGUAGE);
        return new ExtractResult(content, mime, nPages, lang, meta, error);
    }

    // ---- mime detection ----------------------------------------------------

    public String detectMime(byte[] bytes, Path path) {
        Metadata metadata = new Metadata();
        if (path != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, path.getFileName().toString());
        }
        try (InputStream in = org.apache.tika.io.TikaInputStream.get(open(bytes, path))) {
            MediaType type = detector.detect(in, metadata);
            return type == null ? null : type.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- OCR ---------------------------------------------------------------

    /** Run image/scanned-PDF OCR via Tesseract. Returns null if OCR is unavailable. */
    public String ocr(byte[] bytes, Path path, String lang) {
        if (!tesseractAvailable()) {
            return null;
        }
        Metadata metadata = new Metadata();
        ContentHandler handler = new BodyContentHandler(WRITE_LIMIT);
        ParseContext context = parseContext(true, lang);
        try (InputStream in = open(bytes, path)) {
            parser.parse(in, handler, metadata, context);
        } catch (Exception e) {
            return null;
        }
        return handler.toString();
    }

    public boolean tesseractAvailable() {
        try {
            return new TesseractOCRParser().hasTesseract();
        } catch (Exception e) {
            return false;
        }
    }

    // ---- parse context wiring ---------------------------------------------

    private ParseContext parseContext(boolean ocrEnabled, String ocrLang) {
        ParseContext context = new ParseContext();
        // Recursive parsing of embedded docs uses the same auto-detect parser.
        context.set(Parser.class, parser);

        TesseractOCRConfig ocrConfig = new TesseractOCRConfig();
        if (ocrEnabled && ocrLang != null && !ocrLang.isBlank()) {
            ocrConfig.setLanguage(ocrLang);
        }
        if (!ocrEnabled) {
            ocrConfig.setSkipOcr(true);
        }
        context.set(TesseractOCRConfig.class, ocrConfig);

        PDFParserConfig pdfConfig = new PDFParserConfig();
        // Only OCR scanned PDFs (no text layer) when OCR is explicitly enabled.
        pdfConfig.setOcrStrategy(ocrEnabled
                ? PDFParserConfig.OCR_STRATEGY.OCR_AND_TEXT_EXTRACTION
                : PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdfConfig.setExtractInlineImages(false);
        context.set(PDFParserConfig.class, pdfConfig);

        return context;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        String type = t.getClass().getSimpleName();
        return (msg == null || msg.isBlank()) ? type : type + ": " + msg;
    }
}

package farm.query.vgi.tika;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the committed SQL E2E fixtures under {@code test/sql/data/} using the
 * same in-memory PDF/DOCX builders the JUnit tests use. Run via the Gradle
 * {@code generateSqlFixtures} task (the Makefile {@code test-sql} target invokes
 * it before running haybarn-unittest) so the fixtures are reproducible from
 * source rather than opaque committed binaries that can drift.
 *
 * <p>Usage: {@code SqlFixtureGenerator <output-dir>} (defaults to test/sql/data).
 */
public final class SqlFixtureGenerator {

    private SqlFixtureGenerator() {}

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "test/sql/data");
        Files.createDirectories(dir);

        // hello.pdf — single page, known text and known page count.
        Files.write(dir.resolve("hello.pdf"), Fixtures.pdf("Hello from a PDF fixture."));

        // multipage.pdf — three pages with distinct, assertable per-page text.
        Files.write(dir.resolve("multipage.pdf"),
                Fixtures.pdf(new String[] {
                        "First page alpha content.",
                        "Second page beta content.",
                        "Third page gamma content."}));

        // hello.docx — known body text, distinct MIME.
        Files.write(dir.resolve("hello.docx"), Fixtures.docx("Revenue grew sharply in the DOCX fixture."));

        // english.txt — a paragraph for detect_lang / detect_lang_conf.
        Files.writeString(dir.resolve("english.txt"),
                "The quick brown fox jumps over the lazy dog. This is a sufficiently long "
                        + "English sentence so the language detector can settle on a confident result.\n");

        // corrupt.pdf — valid header, garbage body → per-row parse error.
        Files.write(dir.resolve("corrupt.pdf"), Fixtures.corruptPdf());

        System.out.println("Wrote SQL fixtures to " + dir.toAbsolutePath());
    }
}

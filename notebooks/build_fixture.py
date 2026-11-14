"""Create deterministic synthetic PDF, DOCX, HTML and diagnostic text fixtures."""

from pathlib import Path
from textwrap import wrap
from xml.sax.saxutils import escape
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def pdf(pages: list[str]) -> bytes:
    """Write a minimal PDF with explicit text layers and a genuinely blank page."""
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    kids = []
    for text in pages:
        page_id = len(objects) + 1
        stream_id = page_id + 1
        kids.append(f"{page_id} 0 R")
        objects.append(
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents {stream_id} 0 R >>".encode()
        )
        lines = wrap(text, width=80)
        ops = ["BT /F1 11 Tf 50 740 Td 15 TL"]
        for line in lines:
            quoted = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            ops.append(f"({quoted}) Tj T*")
        ops.append("ET")
        stream = "\n".join(ops).encode("ascii")
        objects.append(f"<< /Length {len(stream)} >>\nstream\n".encode() + stream + b"\nendstream")
    objects[1] = f"<< /Type /Pages /Count {len(pages)} /Kids [{' '.join(kids)}] >>".encode()
    result = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(result))
        result.extend(f"{i} 0 obj\n".encode() + obj + b"\nendobj\n")
    xref = len(result)
    result.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
    for pos in offsets[1:]:
        result.extend(f"{pos:010d} 00000 n \n".encode())
    result.extend(
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    )
    return bytes(result)


def build(directory: Path) -> None:
    """Write all authored fixtures; none contains account or customer data."""
    directory.mkdir(parents=True, exist_ok=True)
    pages = [
        "Synthetic field guide. Before indexing a document collection, preserve its identifiers and extraction outcomes. Keep failed inputs visible beside successful ones. A returned text body is evidence of extraction, not evidence that every visual element, table, diagram or embedded attachment was understood.",
        "OK",
        "",
        "Synthetic checklist. Confirm the source identifier, record the page boundary, and keep the original document for later review.",
    ]
    (directory / "field-guide.pdf").write_bytes(pdf(pages))
    paragraphs = [
        "Synthetic support note. A document can contain several paragraphs without exposing reliable physical page boundaries to the extractor.",
        "Store this result as one whole-document chunk and preserve its source identifier. Do not call that row a verified physical page.",
    ]
    parts = {
        "[Content_Types].xml": '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
        "_rels/.rels": '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
        "word/document.xml": '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
        + "".join("<w:p><w:r><w:t>" + escape(t) + "</w:t></w:r></w:p>" for t in paragraphs)
        + "<w:sectPr/></w:body></w:document>",
    }
    with ZipFile(directory / "support-note.docx", "w") as z:
        for name, value in parts.items():
            info = ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            z.writestr(info, value)
    (directory / "brief.html").write_text(
        "<!doctype html><html><head><title>Synthetic brief</title></head><body><p>Synthetic brief. Check document provenance before reusing extracted text in another workflow.</p></body></html>\n"
    )
    (directory / "whitespace.txt").write_text("   \n\t\n   \n")
    (directory / "corrupt.pdf").write_bytes(
        b"%PDF-1.4\nSynthetic deliberately truncated PDF without objects or trailer.\n"
    )


if __name__ == "__main__":
    build(Path(__file__).resolve().parent / "assets")

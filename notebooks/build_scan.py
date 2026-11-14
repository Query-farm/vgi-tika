"""Regenerate a synthetic raster document; no real scan or third-party image."""

from pathlib import Path
import hashlib
import json
from PIL import Image, ImageDraw, ImageFont
from matplotlib.font_manager import findfont

ROOT = Path(__file__).resolve().parent
LINES = [
    "LIBRARY RETURN CHECKLIST",
    "Return the blue field guide by Friday.",
    "Keep the receipt inside the book.",
    "Call the library if the cover is damaged.",
]
image = Image.new("RGB", (1800, 500), "white")
draw = ImageDraw.Draw(image)
font = ImageFont.truetype(findfont("DejaVu Sans"), 48)
for index, line in enumerate(LINES):
    draw.text((60, 50 + 100 * index), line, font=font, fill="black")
path = ROOT / "assets" / "scan-card.png"
image.save(path, format="PNG", optimize=False)
manifest = {
    "provenance": "Authored synthetic raster text; no camera, real document, or downloaded image.",
    "generator": "build_scan.py; Pillow text using Matplotlib DejaVu Sans 48px on white RGB canvas",
    "dimensions_pixels": [1800, 500],
    "expected_lines": LINES,
    "expected_tokens": " ".join(LINES).lower().split(),
    "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
}
(ROOT / "assets" / "scan-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

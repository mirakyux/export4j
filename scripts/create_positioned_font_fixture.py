# SPDX-License-Identifier: Apache-2.0
"""Generate the original geometric test font; requires requirements-verification.txt."""
from pathlib import Path

from fontTools.feaLib.builder import addOpenTypeFeaturesFromString
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen


def generate():
    builder = FontBuilder(1000, isTTF=True)
    builder.setupGlyphOrder([".notdef", "space", "A", "B"])
    builder.setupCharacterMap({32: "space", 65: "A", 66: "B"})
    glyphs = {}
    for name, width in [(".notdef", 0), ("space", 0), ("A", 400), ("B", 700)]:
        pen = TTGlyphPen(None)
        if width:
            pen.moveTo((0, 0))
            pen.lineTo((width, 0))
            pen.lineTo((width, 700))
            pen.lineTo((0, 700))
            pen.closePath()
        glyphs[name] = pen.glyph()
    builder.setupGlyf(glyphs)
    builder.setupHorizontalMetrics({".notdef": (600, 0), "space": (300, 0), "A": (600, 0), "B": (900, 0)})
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable({"familyName": "Export4j Positioned Test", "styleName": "Regular",
                            "uniqueFontIdentifier": "Export4jPositionedTest", "fullName": "Export4j Positioned Test",
                            "psName": "Export4jPositionedTest", "version": "Version 1.0",
                            "licenseDescription": "SPDX-License-Identifier: Apache-2.0"})
    builder.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    builder.setupPost()
    addOpenTypeFeaturesFromString(builder.font, """
        languagesystem DFLT dflt;
        languagesystem latn dflt;
        feature liga { sub A by B; } liga;
    """)
    builder.font.recalcTimestamp = False
    builder.font["head"].created = builder.font["head"].modified = 3786912000
    target = Path(__file__).resolve().parents[1] / "export4j-backend-pdf/src/test/resources/fonts/PositionedTest.ttf"
    target.parent.mkdir(parents=True, exist_ok=True)
    builder.save(target)


if __name__ == "__main__":
    generate()

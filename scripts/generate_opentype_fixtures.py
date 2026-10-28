# SPDX-License-Identifier: Apache-2.0
"""Rebuild project-authored geometric OpenType test fixtures, using pinned fontTools."""
from pathlib import Path

from fontTools.cffLib import FDArrayIndex, FDSelect, FontDict
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib.tables.otTables import CompositeMode, PaintFormat

ROOT = Path(__file__).resolve().parents[1] / "assets" / "fonts"


def common(builder, names, cmap, family):
    builder.setupGlyphOrder(names)
    builder.setupCharacterMap(cmap)
    builder.setupHorizontalMetrics({name: (1000, 0) for name in names})
    builder.setupHorizontalHeader(ascent=900, descent=-100)
    builder.setupNameTable({"familyName": family, "styleName": "Regular",
                           "uniqueFontIdentifier": family, "fullName": family,
                           "psName": family.replace(" ", ""),
                           "copyright": "Copyright Export4j contributors. Apache-2.0."})
    builder.setupOS2(sTypoAscender=900, sTypoDescender=-100, usWinAscent=900,
                     usWinDescent=100, sCapHeight=800)
    builder.setupPost()


def cid_font():
    names = [".notdef", "cid00001", "cid00002", "cid00003", "cid00004"]
    builder = FontBuilder(1000, isTTF=False)
    common(builder, names, {65: names[1], 66: names[2], 0x4E2D: names[3],
                            0x1F600: names[4]}, "Export4j CID Test")
    strings = {}
    for index, name in enumerate(names):
        pen = T2CharStringPen(1000, None)
        pen.moveTo((50, 0)); pen.lineTo((100 + index * 120, 800))
        pen.lineTo((900, 0)); pen.closePath()
        strings[name] = pen.getCharString()
    builder.setupCFF("Export4jCIDTest", {"FullName": "Export4j CID Test",
                     "FamilyName": "Export4j CID Test", "Weight": "Regular"}, strings, {})
    top = builder.font["CFF "].cff.topDictIndex[0]
    top.ROS = ("Adobe", "Identity", 0); top.CIDCount = len(names)
    array = FDArrayIndex(); fd = FontDict(); fd.Private = top.Private
    fd.FontName = "Export4jCIDTest"; array.append(fd); top.FDArray = array
    select = FDSelect(format=3); select.gidArray = [0] * len(names); top.FDSelect = select
    top.CharStrings.fdArray = array; top.CharStrings.fdSelect = select
    for char in top.CharStrings.values():
        char.private = fd.Private
    del top.Private
    builder.font["head"].created = builder.font["head"].modified = 3818448000
    builder.save(ROOT / "Export4j-CID-Test.otf")


def solid(index, alpha=1):
    return {"Format": PaintFormat.PaintSolid, "PaletteIndex": index, "Alpha": alpha}


def glyph(paint, name="box"):
    return {"Format": PaintFormat.PaintGlyph, "Glyph": name, "Paint": paint}


def color_fonts():
    names = [".notdef", "box", "left", "right"] + [f"color{i}" for i in range(32)]
    for version in (0, 1):
        builder = FontBuilder(1000, isTTF=True)
        common(builder, names, {65 + i: f"color{i}" for i in range(32)},
               f"Export4j Color V{version} Test")
        shapes = {}
        for name in names:
            pen = TTGlyphPen(None)
            if name in ("box", "left", "right"):
                x0, x1 = (0, 500) if name == "left" else (500, 1000) if name == "right" else (0, 1000)
                pen.moveTo((x0, 0)); pen.lineTo((x1, 0)); pen.lineTo((x1, 800)); pen.lineTo((x0, 800)); pen.closePath()
            shapes[name] = pen.glyph()
        builder.setupGlyf(shapes)
        builder.font["hmtx"].metrics["right"] = (1000, 500)
        builder.setupCPAL([[(1, 0, 0, 1), (0, 0, 1, 1)],
                           [(0, 1, 0, 1), (1, 1, 0, 1)]])
        if version == 0:
            layers = {f"color{i}": [("left", 0), ("right", 1)] for i in range(32)}
        else:
            line = {"Extend": 0, "ColorStop": [
                {"StopOffset": 0, "PaletteIndex": 0, "Alpha": 1},
                {"StopOffset": 1, "PaletteIndex": 1, "Alpha": 1}]}
            layers = {"color0": glyph(solid(0)),
                      "color1": glyph({"Format": PaintFormat.PaintLinearGradient,
                                      "ColorLine": line, "x0": 0, "y0": 0, "x1": 1000,
                                      "y1": 0, "x2": 0, "y2": 800}),
                      "color2": glyph({"Format": PaintFormat.PaintRadialGradient,
                                      "ColorLine": line, "x0": 500, "y0": 400, "r0": 0,
                                      "x1": 500, "y1": 400, "r1": 600}),
                      "color3": glyph({"Format": PaintFormat.PaintSweepGradient,
                                      "ColorLine": line, "centerX": 500, "centerY": 400,
                                      "startAngle": 0, "endAngle": 360})}
            for mode in CompositeMode:
                layers[f"color{4 + mode.value}"] = {
                    "Format": PaintFormat.PaintComposite, "CompositeMode": mode,
                    "SourcePaint": glyph(solid(0, .75), "left"),
                    "BackdropPaint": glyph(solid(1, .75), "right")}
        builder.setupCOLR(layers, version=version)
        builder.font["head"].created = builder.font["head"].modified = 3818448000
        builder.save(ROOT / f"Export4j-ColorV{version}-Test.ttf")


if __name__ == "__main__":
    cid_font()
    color_fonts()

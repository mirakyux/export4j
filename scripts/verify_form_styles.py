# SPDX-License-Identifier: Apache-2.0
"""Independent editable appearance, complete state, default compatibility and automatic save checks.

This bounded fixture editor preserves non-text AP drawing and regenerates only its text.
It is automatic pypdf verification, not a human reader or a general PDF form editor.
"""
from __future__ import annotations

import argparse
import hashlib
from importlib import metadata
import json
from pathlib import Path
import re
import struct

from pypdf import PdfReader, PdfWriter
from pypdf._font import Font
from pypdf.generic import ArrayObject, ContentStream, DecodedStreamObject, DictionaryObject, NameObject, NumberObject, TextStringObject
from verify_advanced_pdf import difference, render_pdfium, render_poppler, require, widgets
from verify_production import contact_sheet
from verify_semantic_tables import close
from verify_streaming_rich_text import same_color

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/build/form-styles"
OUTPUT = ROOT / "build/verification/form-styles"
BLACK, BLUE, AMBER = (0, 0, 0), (.1, .2, .6), (.6, .25, .1)
PALE_BLUE, PALE_AMBER = (.9, .95, 1), (1, .96, .86)
OLD_BORDER, OLD_MARK = (.6, .7, .8), (.2, .3, .4)


def spec(kind, value, page, x, y, width, height, color=BLACK, background=None, border=0,
         border_color=BLACK, size=None, align=None, options=(), legacy=False, full_font=None):
    return dict(kind=kind, value=value, page=page, rect=(x, y, width, height), color=color,
                background=background, border=border, borderColor=border_color, size=size,
                align=align, options=options, legacy=legacy, fullFont=full_font)


def example_specs():
    values = {
        "ascii_center": spec("/Tx", "Centered review", 1, 174, 68, 280, 28, BLUE, PALE_BLUE, 1.5, BLUE, 14, 1),
        "ascii_reset": spec("/Tx", "Explicit black and transparent", 1, 174, 112, 280, 28, size=12, align=2),
        "ascii_choice": spec("/Ch", "Review", 1, 174, 156, 280, 28, AMBER, PALE_AMBER, 2, AMBER, 12, 2,
                             ("Draft", "Review", "Accepted")),
        "blue_check": spec("/Btn", "/Yes", 1, 174, 200, 24, 24, BLUE, PALE_BLUE, 1.5, BLUE),
        "amber_radio": spec("/Btn", "/Final", 1, 174, 244, 200, 24, AMBER, PALE_AMBER, 2, AMBER,
                            options=("Draft", "Final")),
        "small_check": spec("/Btn", "/Yes", 1, 174, 288, 8, 8, BLUE),
        "small_radio": spec("/Btn", "/A", 1, 174, 322, 80, 8, AMBER, PALE_AMBER, .8, AMBER, options=("A", "B")),
        "unicode_name": spec("/Tx", "完整字体：初始值", 2, 174, 68, 280, 30, BLUE, PALE_BLUE, 1.5, BLUE, 13, 0,
                             full_font="LXGWWenKaiTC-Regular.ttf"),
        "unicode_choice": spec("/Ch", "审核", 2, 174, 112, 280, 28, AMBER, PALE_AMBER, 2, AMBER, 12, 1,
                               ("草稿", "审核", "完成"), full_font="LXGWWenKaiTC-Regular.ttf"),
        "unicode_notes": spec("/Tx", "可编辑中文备注\n第二行。", 2, 174, 156, 280, 50, size=12, align=0,
                              full_font="LXGWWenKaiTC-Regular.ttf"),
    }
    for suffix, x in (("legacy", 174), ("explicit", 314)):
        values["default_text_" + suffix] = spec("/Tx", "Same default", 2, x, 240, 120, 26,
                background=(1, 1, 1), border=.7, border_color=OLD_BORDER, size=10, legacy=True)
        values["default_choice_" + suffix] = spec("/Ch", "Two", 2, x, 284, 120, 26, size=10,
                options=("One", "Two"), legacy=True)
        values["default_check_" + suffix] = spec("/Btn", "/Yes", 2, x, 328, 18, 18, OLD_MARK,
                (1, 1, 1), 1, OLD_MARK, legacy=True)
        values["default_radio_" + suffix] = spec("/Btn", "/Left", 2, x, 372, 100, 18, OLD_MARK,
                (1, 1, 1), 1, OLD_MARK, options=("Left", "Right"), legacy=True)
    return values


def consumer_specs():
    return {
        "styled_text": spec("/Tx", "Café", 0, 24, 60, 240, 28, BLUE, PALE_BLUE, 1, BLUE, 13, 1,
                            full_font="LiberationSans-Regular.ttf"),
        "styled_choice": spec("/Ch", "Beta", 0, 24, 105, 240, 28, size=12, align=2, options=("Alpha", "Beta")),
        "styled_check": spec("/Btn", "/Yes", 0, 24, 150, 20, 20, (.8, .1, .2)),
        "styled_radio": spec("/Btn", "/Second", 0, 24, 190, 100, 20, BLUE, PALE_BLUE, .8, BLUE,
                             options=("First", "Second")),
    }


def appearance_parts(stream, reader):
    fill, stroke, width, stack, points, rectangle = BLACK, BLACK, 1, [], [], None
    result = {"fills": [], "strokes": [], "text": []}
    font_name, font_size, text_x, text_y = "", 0, 0, 0
    for operands, operator in ContentStream(stream, reader).operations:
        if operator == b"q": stack.append((fill, stroke, width))
        elif operator == b"Q": fill, stroke, width = stack.pop()
        elif operator in (b"rg", b"sc", b"scn"):
            fill = tuple(map(float, operands)) if len(operands) == 3 else (float(operands[0]),) * 3
        elif operator == b"g": fill = (float(operands[0]),) * 3
        elif operator in (b"RG", b"SC", b"SCN"):
            stroke = tuple(map(float, operands)) if len(operands) == 3 else (float(operands[0]),) * 3
        elif operator == b"G": stroke = (float(operands[0]),) * 3
        elif operator == b"w": width = float(operands[0])
        elif operator == b"re": rectangle, points = tuple(map(float, operands)), []
        elif operator == b"m": points, rectangle = [tuple(map(float, operands))], None
        elif operator == b"l": points.append(tuple(map(float, operands)))
        elif operator in (b"S", b"s"):
            result["strokes"].append(dict(color=stroke, width=width, rectangle=rectangle, points=points))
            points, rectangle = [], None
        elif operator in (b"f", b"f*"):
            if rectangle is not None: result["fills"].append(dict(color=fill, rectangle=rectangle))
            points, rectangle = [], None
        elif operator in (b"n", b"W", b"W*"): points, rectangle = [], None
        elif operator == b"BT": text_x, text_y = 0, 0
        elif operator == b"Tf": font_name, font_size = str(operands[0]), float(operands[1])
        elif operator == b"Tm": text_x, text_y = float(operands[4]), float(operands[5])
        elif operator in (b"Td", b"TD"): text_x, text_y = text_x + float(operands[0]), text_y + float(operands[1])
        elif operator in (b"Tj", b"TJ"):
            result["text"].append(dict(font=font_name, size=font_size, color=fill, x=text_x, y=text_y))
    require(not stack, "form AP graphics scopes are unbalanced")
    return result


def da(field, form):
    source = str(field.get("/DA", form["/DA"]))
    match = re.search(r"/([^\s]+)\s+([\d.]+)\s+Tf", source)
    require(match is not None, "form default appearance lacks an explicit font/size")
    colors = re.findall(r"([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+rg", source)
    gray = re.findall(r"([\d.]+)\s+g", source)
    require(colors or gray, "form default appearance lacks color")
    color = tuple(map(float, colors[-1])) if colors else (float(gray[-1]),) * 3
    return "/" + match[1], float(match[2]), color


def text_lines(parts):
    """Collect baseline starts; PDFBox may emit one Tj per word or ideograph."""
    lines, previous_x = [], None
    for item in parts["text"]:
        if not lines or abs(item["y"] - lines[-1]["y"]) > .001:
            if lines: require(item["y"] < lines[-1]["y"], "form text baseline order differs")
            lines.append(item)
            previous_x = None
        if previous_x is not None: require(item["x"] >= previous_x, "form fixture text moved backwards on a line")
        previous_x = item["x"]
    return lines


def drawing(parts, config, on=False, button=False):
    background = config["background"]
    require(len(parts["fills"]) == (0 if background is None else 1), "form AP background missing or painted despite transparency")
    if background is not None: require(same_color(parts["fills"][0]["color"], background), "form AP background RGB differs")
    frames = [item for item in parts["strokes"] if item["rectangle"] is not None]
    require(len(frames) == (1 if config["border"] > 0 else 0), "form AP fixed/zero/absent border differs")
    require(all(item["width"] > 0 for item in parts["strokes"]), "form AP contains a PDF hairline stroke")
    if not button: require(len(parts["strokes"]) == len(frames), "text form AP contains an unexpected stroke")
    for frame in frames:
        close(frame["width"], config["border"], "form AP border width differs")
        require(same_color(frame["color"], config["borderColor"]), "form AP border RGB differs")
    if button:
        marks = [item for item in parts["strokes"] if item["rectangle"] is None]
        require(len(marks) == int(on), "form AP noBorder erased mark or Off state gained mark")
        for mark in marks:
            require(len(mark["points"]) == 3 and same_color(mark["color"], config["color"]), "form mark geometry/color differs")
            expected_width = min(1, min(config["rect"][2:]) / 10)
            if config["options"]: expected_width = min(1, min(config["rect"][2] / len(config["options"]), config["rect"][3]) / 10)
            close(mark["width"], expected_width, "form mark width no longer follows actual widget")


def dictionary_style(field, widget, form, config):
    if config["kind"] != "/Btn":
        _, size, color = da(field, form)
        close(size, config["size"], "form DA fixed size differs")
        require(same_color(color, config["color"]), "form DA RGB differs")
        require(int(field.get("/Q", 0)) == (config["align"] or 0), "form field alignment differs")
        if config["align"] is not None: require("/Q" in field, "explicit form alignment was not retained")
    if config["legacy"] and config["kind"] in ("/Btn", "/Ch"):
        require("/MK" not in widget and "/BS" not in widget, "empty style changed legacy button/choice dictionaries")
        return
    characteristics = widget.get("/MK", {})
    if config["background"] is None: require("/BG" not in characteristics, "transparent form retained background metadata")
    else: require(same_color(tuple(map(float, characteristics["/BG"])), config["background"]), "form MK background differs")
    close(float(widget.get("/BS", {}).get("/W", 0)), config["border"], "form BS fixed/adaptive/zero border differs")
    if config["border"] > 0:
        require(same_color(tuple(map(float, characteristics["/BC"])), config["borderColor"]), "form MK border RGB differs")
    else: require("/BC" not in characteristics, "zero/disabled form border retained color and can regain a frame")


def ttf_glyphs(data, text):
    """Read this fixture's explicit TrueType Unicode cmap; reject unknown formats."""
    require(data[:4] == b"\x00\x01\x00\x00", "form fixture requires a standalone TrueType font")
    u16 = lambda offset: struct.unpack_from(">H", data, offset)[0]
    u32 = lambda offset: struct.unpack_from(">I", data, offset)[0]
    count = u16(4)
    require(12 + 16 * count <= len(data), "TrueType table directory leaves explicit bytes")
    tables = [struct.unpack_from(">4sIII", data, 12 + 16 * index) for index in range(count)]
    cmap = [entry for entry in tables if entry[0] == b"cmap"]
    require(len(cmap) == 1, "TrueType Unicode cmap is not unique")
    _, _, base, length = cmap[0]
    require(base + length <= len(data) and u16(base) == 0, "TrueType cmap bounds/version differs")
    records = u16(base + 2)
    require(4 + 8 * records <= length, "TrueType cmap records exceed table")
    choices = []
    for index in range(records):
        platform, encoding, relative = struct.unpack_from(">HHI", data, base + 4 + 8 * index)
        offset = base + relative
        require(offset + 2 <= base + length, "TrueType cmap subtable leaves table")
        kind = u16(offset)
        if (platform == 0 or (platform == 3 and encoding in (1, 10))) and kind in (4, 12):
            extent = u16(offset + 2) if kind == 4 else u32(offset + 4)
            require(offset + extent <= base + length, "TrueType Unicode subtable leaves table")
            choices.append(((kind == 12, platform == 3, encoding == 10), offset, extent, kind))
    require(choices, "form fixture has no supported Unicode cmap")
    _, offset, extent, kind = max(choices)
    result = []
    if kind == 12:
        groups = u32(offset + 12)
        require(groups <= 100000 and 16 + 12 * groups <= extent, "TrueType cmap group budget/bounds exceeded")
        spans = [struct.unpack_from(">III", data, offset + 16 + 12 * index) for index in range(groups)]
        for char in text:
            point = ord(char)
            found = [first + point - start for start, end, first in spans if start <= point <= end]
            require(len(found) == 1 and 0 < found[0] <= 65535, "fixture character lacks one TrueType glyph")
            result.append(found[0])
    else:
        segments = u16(offset + 6) // 2
        require(segments > 0 and 16 + 8 * segments <= extent, "TrueType cmap segment bounds exceeded")
        end_base, start_base = offset + 14, offset + 16 + 2 * segments
        delta_base, range_base = offset + 16 + 4 * segments, offset + 16 + 6 * segments
        for char in text:
            point = ord(char)
            found = [index for index in range(segments) if u16(start_base + 2 * index) <= point <= u16(end_base + 2 * index)]
            require(point <= 65535 and len(found) == 1, "fixture character lacks one BMP cmap segment")
            index, glyph = found[0], point
            distance = u16(range_base + 2 * index)
            if distance:
                position = range_base + 2 * index + distance + 2 * (point - u16(start_base + 2 * index))
                require(position + 2 <= offset + extent, "TrueType glyph offset leaves cmap")
                glyph = u16(position)
            glyph = (glyph + u16(delta_base + 2 * index)) & 65535 if glyph else 0
            require(glyph > 0, "fixture character maps to missing glyph")
            result.append(glyph)
    return result


def appearance_glyphs(stream, reader, font_name):
    glyphs, selected = [], None
    for operands, operator in ContentStream(stream, reader).operations:
        if operator == b"Tf": selected = str(operands[0])
        if operator not in (b"Tj", b"TJ"): continue
        require(selected == font_name, "form appearance selected another explicit font")
        strings = [operands[0]] if operator == b"Tj" else [item for item in operands[0] if isinstance(item, (str, bytes))]
        for value in strings:
            raw = value.original_bytes if isinstance(value, str) else bytes(value)
            require(len(raw) % 2 == 0, "Identity-H appearance has an incomplete CID")
            glyphs.extend(item[0] for item in struct.iter_unpack(">H", raw))
    return glyphs


def check(path, configs, expected_pages, changed=None):
    reader = PdfReader(path, strict=True)
    require(len(reader.pages) == expected_pages, "form style page count differs")
    form = reader.trailer["/Root"]["/AcroForm"]
    require(not getattr(form.get("/NeedAppearances"), "value", False), "form appearances require deferred reader regeneration")
    fields = {str(reference.get_object()["/T"]): reference.get_object() for reference in form["/Fields"]}
    require(set(fields) == set(configs) and set(reader.get_fields()) == set(configs), "form canonical field tree differs")
    found, states, font_sources, aliases = widgets(reader), 0, {}, []
    require(len(found) == sum(len(config["options"]) if config["kind"] == "/Btn" and config["options"] else 1 for config in configs.values()),
            "form page widget count differs")
    for name, config in configs.items():
        field = fields[name]
        value = changed.get(name, config["value"]) if changed else config["value"]
        require(field["/FT"] == config["kind"] and str(field["/V"]) == value, "form canonical type/current value differs: " + name)
        require(str(field["/DV"]) == config["value"], "form original default value changed: " + name)
        require(not int(field.get("/Ff", 0)) & 1, "styled field became read-only")
        members = [(page, widget) for page, owner, widget in found if owner["/T"] == name]
        for index, (page, widget) in enumerate(members):
            require(page == config["page"] and widget.get("/P").idnum == reader.pages[page].indirect_reference.idnum
                    and int(widget.get("/F", 0)) & 4, "form widget lost final physical page or print flag")
            x, y, width, height = config["rect"]
            if config["kind"] == "/Btn" and config["options"]:
                step = width / len(config["options"])
                x, width, height = x + index * step, min(step, height), min(step, height)
            left, bottom, right, top = map(float, widget["/Rect"])
            for actual, expected in zip((left, float(reader.pages[page].mediabox.height) - top, right - left, top - bottom), (x, y, width, height), strict=True):
                close(actual, expected, "form widget final geometry/contents offset differs")
            dictionary_style(field, widget, form, config)
            normal = widget["/AP"]["/N"].get_object()
            if config["kind"] == "/Btn":
                option = "/" + config["options"][index] if config["options"] else "/Yes"
                require(set(normal) == {"/Off", option}, "form AP button states differ")
                require(str(widget["/AS"]) == (option if value == option else "/Off"), "widget state disagrees with canonical button value")
                for state, reference in normal.items():
                    stream = reference.get_object()
                    require(list(map(float, stream["/BBox"])) == [0, 0, width, height], "button AP bounds differ")
                    drawing(appearance_parts(stream, reader), config, state != "/Off", True)
                    states += 1
            else:
                require(list(map(float, normal["/BBox"])) == [0, 0, width, height], "text AP bounds differ")
                parts = appearance_parts(normal, reader)
                drawing(parts, config)
                require(parts["text"] and all(item["size"] == config["size"] and same_color(item["color"], config["color"])
                        for item in parts["text"]), "painted form text size/color differs from DA")
                font_name, _, _ = da(field, form)
                resource = form["/DR"]["/Font"][font_name]
                visible = reader.pages[page].extract_xform_text(normal)
                if config["fullFont"]:
                    require(resource["/Subtype"] == "/Type0" and "+" not in str(resource["/BaseFont"]), "editable Unicode font was subset or lost")
                    descendant = resource["/DescendantFonts"][0]
                    require(resource["/Encoding"] == "/Identity-H" and descendant["/CIDToGIDMap"] == "/Identity",
                            "complete fixture font must use direct Identity-H glyph mapping")
                    data = descendant["/FontDescriptor"]["/FontFile2"].get_data()
                    source = (ROOT / "assets/fonts" / config["fullFont"]).read_bytes()
                    require(data == source, "editable Unicode font does not retain complete explicit font bytes")
                    font_sources[config["fullFont"]] = hashlib.sha256(data).hexdigest()
                    require(appearance_glyphs(normal, reader, font_name) == ttf_glyphs(source, value.replace("\n", "")),
                            "form AP original CIDs differ from explicit source text glyphs: " + name)
                    expected_text, extracted_text = "".join(value.split()), "".join(visible.split())
                    require(len(expected_text) == len(extracted_text), "form AP text extraction length differs: " + name)
                    for expected_char, actual_char in zip(expected_text, extracted_text, strict=True):
                        if expected_char == actual_char: continue
                        require((expected_char, actual_char) == ("值", "値"), "unrecognized form AP text extraction alias: " + name)
                        pair = ttf_glyphs(source, expected_char + actual_char)
                        require(pair[0] == pair[1], "form AP known alias changed source glyph")
                        aliases.append(dict(field=name, canonicalValue=value, appearanceText=visible, sourceFontSha256=font_sources[config["fullFont"]],
                                            sourceCodePoint="U+503C", extractedCodePoint="U+5024", glyphId=pair[0]))
                else:
                    require("".join(visible.split()) == "".join(value.split()), "form AP paints stale text/choice value: " + name)
                    require(resource["/BaseFont"] == "/Helvetica", "ASCII field lost legacy Helvetica")
                metrics = Font.from_font_resource(resource)
                for item, line in zip(text_lines(parts), value.split("\n"), strict=True):
                    advance = metrics.text_width(line) * config["size"] / 1000
                    require(0 <= item["x"] <= width and 0 <= item["y"] <= height and item["x"] + advance <= width,
                            "painted form text leaves actual widget")
                    # PDFBox 3.0.8 AppearanceGeneratorHelper insets the clipping box, then
                    # the content box, each by max(1 pt, border width). Keep exact geometry.
                    content_inset = 2 * max(1, config["border"])
                    if config["align"] == 0: close(item["x"], content_inset, "painted form text is not left-aligned")
                    if config["align"] == 1: close(item["x"], (width - advance) / 2, "painted form text is not centered")
                    if config["align"] == 2: close(item["x"] + advance, width - content_inset, "painted form text is not right-aligned")
                states += 1
        if config["kind"] == "/Ch":
            require(list(field["/Opt"]) == list(config["options"]), "styled dropdown options differ")
    return {"pages": expected_pages, "fields": len(fields), "widgets": len(found), "appearances": states,
            "completeFonts": font_sources, "knownTextExtractionAliases": aliases}


def appearances(widget):
    normal = widget["/AP"]["/N"].get_object()
    if "/BBox" in normal: return {"normal": normal.get_data()}
    return {str(key): value.get_object().get_data() for key, value in normal.items()}


def property_value(value):
    if value is None: return None
    value = value.get_object() if hasattr(value, "get_object") else value
    if isinstance(value, dict): return {str(key): property_value(item) for key, item in value.items()}
    if isinstance(value, list): return [property_value(item) for item in value]
    return value


def default_compatibility(path):
    found = widgets(PdfReader(path, strict=True))
    for kind in ("text", "choice", "check", "radio"):
        left = [(owner, widget) for _, owner, widget in found if owner["/T"] == f"default_{kind}_legacy"]
        right = [(owner, widget) for _, owner, widget in found if owner["/T"] == f"default_{kind}_explicit"]
        require(len(left) == len(right) and left, "legacy/default paired widgets missing")
        for (a, aw), (b, bw) in zip(left, right, strict=True):
            for key in ("/FT", "/Ff", "/DA", "/Q", "/V", "/DV", "/Opt"):
                require(property_value(a.get(key)) == property_value(b.get(key)), "empty style changed legacy field property " + key)
            for key in ("/MK", "/BS", "/AS"):
                require(property_value(aw.get(key)) == property_value(bw.get(key)), "empty style changed legacy widget property " + key)
            require(appearances(aw) == appearances(bw), "empty style changed actual legacy AP drawing")


def reverse_cmap(font):
    if font["/Subtype"] != "/Type0": return None
    require(font["/Encoding"] == "/Identity-H", "bounded fixture editor requires Identity-H")
    reverse, cmap = {}, font["/ToUnicode"].get_data().decode("ascii")
    for block in re.findall(r"beginbfrange(.*?)endbfrange", cmap, re.S):
        for start, end, destination in re.findall(r"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>", block):
            for cid in range(int(start, 16), int(end, 16) + 1):
                char = (int(destination, 16) + cid - int(start, 16)).to_bytes(len(destination) // 2, "big").decode("utf-16-be")
                reverse.setdefault(char, cid.to_bytes(2, "big"))
    for block in re.findall(r"beginbfchar(.*?)endbfchar", cmap, re.S):
        for cid, char in re.findall(r"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>", block):
            reverse.setdefault(bytes.fromhex(char).decode("utf-16-be"), bytes.fromhex(cid))
    return reverse


def automatic_save(source, target, configs, changed):
    writer = PdfWriter()
    writer.clone_document_from_reader(PdfReader(source, strict=True))
    form = writer.root_object["/AcroForm"]
    found = widgets(writer)
    for reference in form["/Fields"]:
        field = reference.get_object()
        name, old_value = str(field["/T"]), str(field["/V"])
        if name not in changed: continue
        value = changed[name]
        members = [widget for _, owner, widget in found if owner["/T"] == name]
        if field["/FT"] == "/Btn":
            field[NameObject("/V")] = NameObject(value)
            for widget in members:
                widget[NameObject("/AS")] = NameObject(value if value in widget["/AP"]["/N"] else "/Off")
            continue
        font_name, size, _ = da(field, form)
        font = form["/DR"]["/Font"][font_name]
        metrics, reverse = Font.from_font_resource(font), reverse_cmap(font)
        require(len(members) == 1, "bounded text fixture editor requires one widget")
        widget, alignment = members[0], int(field.get("/Q", 0))
        old = widget["/AP"]["/N"].get_object()
        data, parts = old.get_data(), appearance_parts(old, writer)
        boxes = re.findall(rb"\bBT\b.*?\bET\b", data, re.S)
        baselines = text_lines(parts)
        require(len(boxes) == 1 and len(baselines) == len(value.split("\n")) == len(old_value.split("\n")),
                "bounded fixture editor requires one text object and unchanged line count")
        commands = ["BT", str(field.get("/DA", form["/DA"]))]
        for before, previous, line in zip(baselines, old_value.split("\n"), value.split("\n"), strict=True):
            if reverse is None: encoded = line.encode("cp1252")
            else:
                require(all(char in reverse for char in line), "new fixture characters absent from complete embedded font")
                encoded = b"".join(reverse[char] for char in line)
            delta = (metrics.text_width(previous) - metrics.text_width(line)) * size / 1000
            x = before["x"] + (delta / 2 if alignment == 1 else delta if alignment == 2 else 0)
            commands.append(f"1 0 0 1 {x:.6f} {before['y']:.6f} Tm <{encoded.hex()}> Tj")
        commands.append("ET")
        stream = DecodedStreamObject()
        stream.update({key: value for key, value in old.items() if key not in ("/Length", "/Filter", "/DecodeParms")})
        stream.set_data(data.replace(boxes[0], "\n".join(commands).encode("ascii"), 1))
        field[NameObject("/V")] = TextStringObject(value)
        if field["/FT"] == "/Ch": field[NameObject("/I")] = ArrayObject([NumberObject(field["/Opt"].index(value))])
        widget[NameObject("/AP")] = DictionaryObject({NameObject("/N"): writer._add_object(stream)})
    with target.open("wb") as output: writer.write(output)
    checked = check(target, configs, len(writer.pages), changed)
    # Background/border/clipping and all button state streams must survive the editor unchanged.
    before, after = widgets(PdfReader(source)), widgets(PdfReader(target))
    for (_, af, aw), (_, bf, bw) in zip(before, after, strict=True):
        for key in ("/DA", "/Q", "/Ff", "/DV"):
            require(property_value(af.get(key)) == property_value(bf.get(key)), "automatic fill changed field style/default")
        for key in ("/MK", "/BS"):
            require(property_value(aw.get(key)) == property_value(bw.get(key)), "automatic fill changed widget style")
        a, b = appearances(aw), appearances(bw)
        if af["/FT"] == "/Btn" or af["/T"] not in changed: require(a == b, "automatic fill changed an existing AP state")
        else:
            strip = lambda value: re.sub(rb"\bBT\b.*?\bET\b", b"", value, flags=re.S)
            require(strip(a["normal"]) == strip(b["normal"]), "automatic text fill changed non-text AP drawing")
    return checked


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: metadata.version(name) for name in ("pypdf", "pypdfium2", "Pillow")}
    require(versions == {"pypdf": "6.10.0", "pypdfium2": "5.13.0", "Pillow": "12.3.0"}, "use the pinned verification environment")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    directory = args.consumer_build_directory
    if directory is None: directory = Path((ROOT / "build/verification/consumer-build-directory.txt").read_text(encoding="utf-8").strip())
    if not directory.is_absolute(): directory = ROOT / directory
    original, consumer = FIXTURES / "styled.pdf", directory / "output/consumer-form-style.pdf"
    config, caller = example_specs(), consumer_specs()
    report = {"tools": versions, "consumerBuildDirectory": str(directory), "styled": check(original, config, 3), "consumer": check(consumer, caller, 1)}
    source_reader = PdfReader(original, strict=True)
    require([source_reader.get_destination_page_number(item) for item in source_reader.outline] == [1, 2],
            "form style bookmarks lost final contents offset")
    require(not any(page == 0 for page, _, _ in widgets(source_reader)), "form widget was placed on the contents page")
    targets = [item.get_object()["/Dest"][0].idnum for item in source_reader.pages[0].get("/Annots", [])
               if item.get_object().get("/Subtype") == "/Link"]
    require(targets == [source_reader.pages[index].indirect_reference.idnum for index in (1, 2)],
            "form style contents links lost final body destinations")
    fonts = json.loads((FIXTURES / "fonts.json").read_text(encoding="utf-8"))["fonts"]
    require(len(fonts) == 2, "form style explicit font manifest differs")
    for font in fonts:
        data = (ROOT / "assets/fonts" / font["resource"]).read_bytes()
        require(font["sha256"] == hashlib.sha256(data).hexdigest() and font["bytes"] == len(data), "form style source font differs")
    report["fontSources"] = fonts
    default_compatibility(original)
    report["legacyEmptyStyleCompatibility"] = "all four field dictionaries and complete AP states identical"
    changed = {"ascii_center": "Saved center", "ascii_reset": "Saved black", "ascii_choice": "Accepted",
               "blue_check": "/Off", "amber_radio": "/Draft", "small_check": "/Off", "small_radio": "/B",
               "unicode_name": "新增字符：测试", "unicode_choice": "完成", "unicode_notes": "保存后的中文备注\n新增一行。"}
    caller_changed = {"styled_text": "Résumé Ω", "styled_choice": "Alpha", "styled_check": "/Off", "styled_radio": "/First"}
    saved, caller_saved = OUTPUT / "styled-saved.pdf", OUTPUT / "consumer-saved.pdf"
    report["styled-saved"] = automatic_save(original, saved, config, changed)
    report["consumer-saved"] = automatic_save(consumer, caller_saved, caller, caller_changed)
    report["automaticSave"] = "pypdf values/AP saved and reopened; not a human reader test"
    contacts = []
    for name, path, count in (("styled", original, 3), ("styled-saved", saved, 3), ("consumer", consumer, 1), ("consumer-saved", caller_saved, 1)):
        first, second = render_poppler(path, OUTPUT / name / "poppler"), render_pdfium(path, OUTPUT / name / "pdfium")
        require(len(first) == len(second) == count, "form renderer omitted a complete page")
        comparisons = [difference(a, b, 2) for a, b in zip(first, second, strict=True)]
        require(all(value["meanAbsoluteChannelError"] < 4 and value["changedPixelFraction"] < .06 for value in comparisons),
                "form style independent renderers disagree")
        report.setdefault(name, {})["rendererDifferences"] = comparisons
        contacts.extend((f"{name} {index}", image) for index, image in enumerate(first, 1))
    require(len(contacts) == 8, "form complete original/saved/caller page count differs")
    contact_sheet(contacts, OUTPUT)
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"pages": len(contacts), "formStyles": "passed", "automaticSave": "passed", "humanReader": "not run"}, indent=2))


if __name__ == "__main__":
    main()

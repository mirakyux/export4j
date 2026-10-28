# SPDX-License-Identifier: Apache-2.0
"""Create original font programs and explicit-metric PDF controls without Export4j/PDFBox."""
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import struct
from pypdf import PdfReader, PdfWriter
from pypdf.generic import ArrayObject, DecodedStreamObject, DictionaryObject, FloatObject, NameObject, NumberObject, TextStringObject

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-extended-font-fixtures"
FONT = ROOT / "assets/fonts/LiberationSans-Regular.ttf"


def dictionary(**values):
    return DictionaryObject({NameObject("/" + name): value for name, value in values.items()})


def numbers(values):
    return ArrayObject([FloatObject(value) for value in values])


def stream(writer, data, **values):
    result = DecodedStreamObject()
    result.set_data(data.encode("ascii") if isinstance(data, str) else data)
    result.update(dictionary(**values))
    return writer._add_object(result.flate_encode())


def descriptor(writer, name, embedded):
    result = dictionary(Type=NameObject("/FontDescriptor"), FontName=NameObject("/" + name), Flags=NumberObject(32),
                        FontBBox=numbers([-200, -300, 1200, 1000]), Ascent=NumberObject(900), Descent=NumberObject(-250),
                        CapHeight=NumberObject(700), ItalicAngle=NumberObject(0), StemV=NumberObject(80))
    if embedded:
        result[NameObject("/FontFile2")] = stream(writer, FONT.read_bytes(), Length1=NumberObject(FONT.stat().st_size))
    return result


def cmap(writer, codespace, mappings, unicode=True):
    return stream(writer, "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /Export4jFixture def "
                  f"/CMapType {2 if unicode else 1} def /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> def "
                  f"/WMode 0 def 1 begincodespacerange {codespace} endcodespacerange {mappings} "
                  "endcmap CMapName currentdict /CMap defineresource pop end end")


def glyph_id(codepoint):
    """Read the supplied TTF's format-4 Unicode cmap using the documented SFNT layout."""
    data = FONT.read_bytes()
    count = struct.unpack_from(">H", data, 4)[0]
    tables = {data[12 + i * 16:16 + i * 16]: struct.unpack_from(">II", data, 20 + i * 16) for i in range(count)}
    offset = tables[b"cmap"][0]
    entries = struct.unpack_from(">H", data, offset + 2)[0]
    for index in range(entries):
        platform, encoding, relative = struct.unpack_from(">HHI", data, offset + 4 + index * 8)
        table = offset + relative
        if platform != 3 or encoding != 1 or struct.unpack_from(">H", data, table)[0] != 4:
            continue
        segments = struct.unpack_from(">H", data, table + 6)[0] // 2
        ends = table + 14
        starts = ends + 2 * segments + 2
        deltas = starts + 2 * segments
        ranges = deltas + 2 * segments
        for segment in range(segments):
            start = struct.unpack_from(">H", data, starts + 2 * segment)[0]
            end = struct.unpack_from(">H", data, ends + 2 * segment)[0]
            if start <= codepoint <= end:
                delta = struct.unpack_from(">h", data, deltas + 2 * segment)[0]
                distance = struct.unpack_from(">H", data, ranges + 2 * segment)[0]
                if distance == 0:
                    return (codepoint + delta) & 65535
                gid = struct.unpack_from(">H", data, ranges + 2 * segment + distance + 2 * (codepoint - start))[0]
                return (gid + delta) & 65535 if gid else 0
    raise AssertionError("fixture font needs an explicit BMP Unicode cmap")


def font(writer, name, embedded=False, malformed=False, missing=False):
    if name == "differences":
        differences = ArrayObject(([NumberObject(128)] if not malformed else []) + [NameObject("/Euro")])
        value = dictionary(Type=NameObject("/Font"), Subtype=NameObject("/TrueType"), BaseFont=NameObject("/ExternalSimple"),
                           FontDescriptor=writer._add_object(descriptor(writer, "ExternalSimple", embedded)),
                           FirstChar=NumberObject(0), LastChar=NumberObject(255), Widths=numbers([900] * 256),
                           Encoding=dictionary(BaseEncoding=NameObject("/WinAnsiEncoding"), Differences=differences),
                           ToUnicode=cmap(writer, "<00> <FF>", "2 beginbfchar <80> <20AC> <41> <0041> endbfchar"))
        return writer._add_object(value), "804180", "41"
    custom = name == "cid-custom"
    ids = [7, 9] if custom else [1, 2]
    widths = ArrayObject([NumberObject(ids[0]), numbers([-1 if malformed else 900]), NumberObject(ids[1]), numbers([350])])
    child = dictionary(Type=NameObject("/Font"), Subtype=NameObject("/CIDFontType2"), BaseFont=NameObject("/ExternalCID"),
                       CIDSystemInfo=dictionary(Registry=TextStringObject("Adobe"), Ordering=TextStringObject("Identity"), Supplement=NumberObject(0)),
                       FontDescriptor=writer._add_object(descriptor(writer, "ExternalCID", embedded)), DW=NumberObject(1000), W=widths)
    if embedded:
        gids = bytearray((max(ids) + 1) * 2)
        for cid, character in zip(ids, "AB", strict=True):
            struct.pack_into(">H", gids, cid * 2, glyph_id(ord(character)))
        child[NameObject("/CIDToGIDMap")] = stream(writer, bytes(gids))
    first = "DBFFDFFF" if missing else "0041"
    value = dictionary(Type=NameObject("/Font"), Subtype=NameObject("/Type0"), BaseFont=NameObject("/ExternalCID"),
                       Encoding=cmap(writer, "<00> <FF>", "2 begincidchar <01> 7 <02> 9 endcidchar", False) if custom else NameObject("/Identity-H"),
                       DescendantFonts=ArrayObject([writer._add_object(child)]),
                       ToUnicode=cmap(writer, "<00> <FF>" if custom else "<0000> <FFFF>",
                           f"2 beginbfchar <01> <{first}> <02> <0042> endbfchar" if custom else f"2 beginbfchar <0001> <{first}> <0002> <0042> endbfchar"))
    return writer._add_object(value), "010201" if custom else "000100020001", "01" if custom else "0001"


def charstring(*values):
    result = bytearray()
    operators = {"hsbw": 13, "rmoveto": 21, "rlineto": 5, "closepath": 9, "endchar": 14}
    for value in values:
        if isinstance(value, str):
            result.append(operators[value])
        elif -107 <= value <= 107:
            result.append(value + 139)
        elif 108 <= value <= 1131:
            result.extend([(value - 108) // 256 + 247, (value - 108) % 256])
        elif -1131 <= value <= -108:
            result.extend([(-value - 108) // 256 + 251, (-value - 108) % 256])
        else:
            raise AssertionError("original Type1 fixture number is outside compact range")
    return bytes(result)


def type1_font(writer, malformed=False):
    clear = (b"%!PS-AdobeFont-1.0: Export4jFixture 1.0\n8 dict begin\n/FontName /Export4jFixture def\n"
             b"/FontType 1 def\n/PaintType 0 def\n/FontMatrix [0.001 0 0 0.001 0 0] def\n"
             b"/FontBBox [0 0 600 700] def\n/Encoding StandardEncoding def\ncurrentdict end\ncurrentfile eexec\n")
    private = bytearray(b"\0\0\0\0dup /Private 8 dict dup begin /lenIV -1 def "
                        b"/RD {string currentfile exch readstring pop} executeonly def /ND {noaccess def} executeonly def "
                        b"/NP {noaccess put} executeonly def /Subrs 0 array def end readonly put dup /CharStrings 2 dict dup begin\n")
    programs = {".notdef": charstring(0, 600, "hsbw", "endchar"),
                "A": charstring(0, 600, "hsbw", 50, 0, "rmoveto", 0, 700, "rlineto", 500, 0, "rlineto", 0, -700, "rlineto", -500, 0, "rlineto", "closepath", "endchar")}
    for name, data in programs.items():
        private.extend(f"/{name} {len(data)} RD ".encode("ascii") + data + b" ND\n")
    private.extend(b"end readonly put dup /FontName get exch definefont pop mark currentfile closefile\n")
    state = 55665
    for index, value in enumerate(private):
        cipher = value ^ (state >> 8)
        private[index] = cipher
        state = ((cipher + state) * 52845 + 22719) & 65535
    details = descriptor(writer, "Export4jFixture", False)
    details[NameObject("/FontFile")] = stream(writer, clear + private, Length1=NumberObject(1 if malformed else len(clear)), Length2=NumberObject(len(private)), Length3=NumberObject(0))
    return writer._add_object(dictionary(Type=NameObject("/Font"), Subtype=NameObject("/Type1"), BaseFont=NameObject("/Export4jFixture"),
        FontDescriptor=writer._add_object(details), FirstChar=NumberObject(65), LastChar=NumberObject(65), Widths=numbers([600]), Encoding=NameObject("/WinAnsiEncoding"))), "41", "41"


def type3_font(writer, missing=False, malformed_encoding=False):
    procedures = DictionaryObject({NameObject("/Other" if missing else "/A"): stream(writer, "600 0 0 0 600 700 d1\n50 0 500 700 re f\n")})
    return writer._add_object(dictionary(Type=NameObject("/Font"), Subtype=NameObject("/Type3"), Name=NameObject("/Export4jStaticFixture"),
        FirstChar=NumberObject(65), LastChar=NumberObject(65), Widths=numbers([600]), FontMatrix=numbers([.001, 0, 0, .001, 0, 0]),
        FontBBox=numbers([0, 0, 600, 700]), Encoding=dictionary(Differences=ArrayObject([FloatObject(65.5) if malformed_encoding else NumberObject(65), NameObject("/A")])),
        CharProcs=procedures, Resources=dictionary(), ToUnicode=cmap(writer, "<00> <FF>", "1 beginbfchar <41> <0041> endbfchar"))), "41", "41"


def save(name, factory, records):
    writer = PdfWriter()
    writer.metadata = None
    selected, first, last = factory(writer)
    page = writer.add_blank_page(240, 120)
    page[NameObject("/Resources")] = dictionary(Font=dictionary(F1=selected))
    page[NameObject("/Contents")] = stream(writer, f"BT /F1 20 Tf 20 80 Td <{first}> Tj ET\nBT /F1 15 Tf 20 30 Td <{last}> Tj ET\n")
    encoded = BytesIO()
    writer.write(encoded)
    data = encoded.getvalue()
    (OUTPUT / f"{name}.pdf").write_bytes(data)
    records[name] = {"sha256": sha256(data).hexdigest(), "pages": len(PdfReader(BytesIO(data)).pages)}


def save_shared(embedded, records):
    writer = PdfWriter()
    writer.metadata = None
    first, _, _ = font(writer, "cid-identity", embedded)
    original = first.get_object()
    child = original["/DescendantFonts"][0].get_object()
    second_child = DictionaryObject(dict(child)) if embedded else child
    if embedded:
        gids = bytearray(6)
        for cid, character in ((1, "B"), (2, "A")):
            struct.pack_into(">H", gids, cid * 2, glyph_id(ord(character)))
        second_child[NameObject("/CIDToGIDMap")] = stream(writer, bytes(gids))
    second = dictionary(Type=NameObject("/Font"), Subtype=NameObject("/Type0"), BaseFont=NameObject("/ExternalSecond"),
                        Encoding=cmap(writer, "<00> <FF>", "2 begincidchar <01> 1 <02> 2 endcidchar", False),
                        DescendantFonts=ArrayObject([writer._add_object(second_child)]) if embedded else original["/DescendantFonts"],
                        ToUnicode=cmap(writer, "<00> <FF>", "2 beginbfchar <01> <0042> <02> <0041> endbfchar"))
    page = writer.add_blank_page(240, 120)
    page[NameObject("/Resources")] = dictionary(Font=dictionary(F1=first, F2=writer._add_object(second)))
    page[NameObject("/Contents")] = stream(writer, "BT /F1 20 Tf 20 80 Td <00010002> Tj ET\nBT /F2 15 Tf 20 30 Td <0102> Tj ET\n")
    encoded = BytesIO()
    writer.write(encoded)
    data = encoded.getvalue()
    name = "cid-shared" + ("-control" if embedded else "")
    (OUTPUT / f"{name}.pdf").write_bytes(data)
    records[name] = {"sha256": sha256(data).hexdigest(), "pages": 1}


def conflicting_cmap(writer, malformed=None):
    selected, _, _ = font(writer, "cid-custom")
    root = selected.get_object()
    second = "0101" if malformed == "prefix" else "02" if malformed == "coverage" else "0001"
    spaces = "1 begincodespacerange <01> <01>" if malformed == "coverage" else f"2 begincodespacerange <01> <01> <{second}> <{second}>"
    for key, mapping, kind in (("Encoding", f"2 begincidchar <01> 7 <{second}> 9 endcidchar", 1),
                               ("ToUnicode", f"2 beginbfchar <01> <0041> <{second}> <0042> endbfchar", 2)):
        root[NameObject("/" + key)] = stream(writer, "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /Export4jMixed def "
            f"/CMapType {kind} def /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> def /WMode 0 def "
            f"{spaces} endcodespacerange {mapping} "
            "endcmap CMapName currentdict /CMap defineresource pop end end")
    return selected, "01" + second, "01"


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    records = {}
    for name in ("differences", "cid-identity", "cid-custom"):
        for embedded in (False, True):
            save(name + ("-control" if embedded else ""), lambda writer, n=name, e=embedded: font(writer, n, e), records)
    for embedded in (False, True):
        save_shared(embedded, records)
    save("type1", type1_font, records)
    save("type3", type3_font, records)
    save("malformed-differences", lambda writer: font(writer, "differences", malformed=True), records)
    save("malformed-cid-width", lambda writer: font(writer, "cid-identity", malformed=True), records)
    save("missing-cid-glyph", lambda writer: font(writer, "cid-identity", missing=True), records)
    save("malformed-type1-length", lambda writer: type1_font(writer, True), records)
    save("missing-type3-charproc", lambda writer: type3_font(writer, True), records)
    save("malformed-type3-encoding", lambda writer: type3_font(writer, malformed_encoding=True), records)
    save("conflicting-cid-code-lengths", conflicting_cmap, records)
    save("malformed-cid-codespace", lambda writer: conflicting_cmap(writer, "coverage"), records)
    save("malformed-cid-prefix", lambda writer: conflicting_cmap(writer, "prefix"), records)
    (OUTPUT / "manifest.json").write_text(json.dumps({"fontSha256": sha256(FONT.read_bytes()).hexdigest(), "files": records}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"fixtures": len(records), "pages": sum(item["pages"] for item in records.values())}))


if __name__ == "__main__":
    main()

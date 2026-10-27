# SPDX-License-Identifier: Apache-2.0
"""Create deterministic external-style CFF PDFs without using Export4j or PDFBox."""
from io import BytesIO
from pathlib import Path
import struct
from pypdf import PdfWriter
from pypdf.generic import (
    ArrayObject, DecodedStreamObject, DictionaryObject, FloatObject,
    NameObject, NumberObject, TextStringObject,
)

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-font-fixtures"


def dictionary(**items):
    return DictionaryObject({NameObject("/" + key): value for key, value in items.items()})


def index(items):
    offsets = [1]
    for item in items:
        offsets.append(offsets[-1] + len(item))
    return struct.pack(">HB", len(items), 4) + b"".join(struct.pack(">I", n) for n in offsets) + b"".join(items)


def integer(number):
    return b"\x1d" + struct.pack(">i", number)


def cid_program():
    """Original CFF: .notdef, a rectangle (CID 1), a triangle (CID 2)."""
    name = index([b"Export4jCID"])
    strings = index([b"Adobe", b"Identity"])
    def charstring(points):
        return (b"\x1c\x02\x58\xbd\x8b\x15" +
                b"".join(b"\x1c" + struct.pack(">h", n) for n in points) + b"\x05\x0e")
    glyphs = index([b"\x0e", charstring([500, 0, 0, 700, -500, 0]), charstring([250, 700, 250, -700])])
    charset = 4 + len(name) + 61 + len(strings) + 2
    charstrings = charset + 5
    fd_select = charstrings + len(glyphs)
    fd_array = fd_select + 4
    top = (integer(391) + integer(392) + integer(0) + b"\x0c\x1e" + integer(3) + b"\x0c\x22" +
           integer(charset) + b"\x0f" + integer(charstrings) + b"\x11" +
           integer(fd_array) + b"\x0c\x24" + integer(fd_select) + b"\x0c\x25")
    private = integer(0) + integer(fd_array + 22) + b"\x12"
    return (b"\x01\x00\x04\x04" + name + index([top]) + strings + b"\x00\x00" +
            b"\x00\x00\x01\x00\x02" + glyphs + b"\x00\x00\x00\x00" + index([private]))


def cff_table(otf):
    for offset in range(12, 12 + struct.unpack_from(">H", otf, 4)[0] * 16, 16):
        tag, _, start, length = struct.unpack_from(">4sIII", otf, offset)
        if tag == b"CFF ":
            return otf[start:start + length]
    raise ValueError("explicit fixture font has no CFF table")


def pdf(program, wrapper, cid=False, split=False):
    writer = PdfWriter(); writer.metadata = None
    page = writer.add_blank_page(240, 120)
    stream = DecodedStreamObject(); stream.set_data(program); stream[NameObject("/Subtype")] = NameObject("/" + wrapper)
    stream_ref = writer._add_object(stream.flate_encode())
    base = NameObject("/Export4jCID" if cid else "/SourceSans3-Regular")
    descriptor = dictionary(Type=NameObject("/FontDescriptor"), FontName=base, Flags=NumberObject(4 if cid else 32),
                            FontBBox=ArrayObject([NumberObject(n) for n in (-200, -300, 1200, 1000)]),
                            Ascent=NumberObject(900), Descent=NumberObject(-250), CapHeight=NumberObject(700),
                            ItalicAngle=NumberObject(0), StemV=NumberObject(80), FontFile3=stream_ref)
    if cid:
        child = dictionary(Type=NameObject("/Font"), Subtype=NameObject("/CIDFontType0"), BaseFont=base,
                           FontDescriptor=writer._add_object(descriptor), DW=NumberObject(600),
                           CIDSystemInfo=dictionary(Registry=TextStringObject("Adobe"), Ordering=TextStringObject("Identity"), Supplement=NumberObject(0)))
        unicode = DecodedStreamObject(); unicode.set_data(
            b"/CIDInit /ProcSet findresource begin 12 dict begin begincmap "
            b"/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def "
            b"/CMapName /Export4jUnicode def /CMapType 2 def "
            b"1 begincodespacerange <0000> <FFFF> endcodespacerange "
            b"2 beginbfchar <0001> <0041> <0002> <0042> endbfchar "
            b"endcmap CMapName currentdict /CMap defineresource pop end end")
        font = dictionary(Type=NameObject("/Font"), Subtype=NameObject("/Type0"), BaseFont=base,
                          Encoding=NameObject("/Identity-H"), DescendantFonts=ArrayObject([writer._add_object(child)]), ToUnicode=writer._add_object(unicode))
        commands = b"BT /Cff 14 Tf 20 80 Td <00010002> Tj ET"
    else:
        font = dictionary(Type=NameObject("/Font"), Subtype=NameObject("/Type1"), BaseFont=base,
                          FontDescriptor=writer._add_object(descriptor), Encoding=NameObject("/WinAnsiEncoding"),
                          FirstChar=NumberObject(32), LastChar=NumberObject(126),
                          Widths=ArrayObject([FloatObject(600) for _ in range(95)]))
        commands = b"BT /Cff 14 Tf 20 80 Td (CFF SECRET KEEP) Tj 0 -30 Td (CFF BODY) Tj ET"
        if split:
            commands = b"BT /Cff 14 Tf 20 80 Td (CFF ) Tj (SEC) Tj [-50 (RET)] TJ ( KEEP) Tj 0 -30 Td (CFF BODY) Tj ET"
    page[NameObject("/Resources")] = dictionary(Font=dictionary(Cff=writer._add_object(font)))
    content = DecodedStreamObject(); content.set_data(commands); page[NameObject("/Contents")] = writer._add_object(content)
    output = BytesIO(); writer.write(output)
    return output.getvalue()


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    otf = (ROOT / "assets/fonts/SourceSans3-Regular.otf").read_bytes()
    for name, program, wrapper, cid in (("type1c", cff_table(otf), "Type1C", False),
                                       ("opentype-cff", otf, "OpenType", False),
                                       ("cid-cff", cid_program(), "CIDFontType0C", True)):
        (OUTPUT / (name + ".pdf")).write_bytes(pdf(program, wrapper, cid))
    (OUTPUT / "split-shows.pdf").write_bytes(pdf(cff_table(otf), "Type1C", split=True))
    print("Created 4 deterministic CFF PDF fixtures")


if __name__ == "__main__":
    main()

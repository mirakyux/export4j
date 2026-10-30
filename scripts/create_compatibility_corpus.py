#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Create small independent OOXML and malformed-input controls for coordinate consumers."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import zipfile


def package(method=zipfile.ZIP_DEFLATED, document=None, name="word/document.xml"):
    document = document or (b'<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
                            b'<w:body><w:p><w:r><w:t>CORPUS-ORIGINAL</w:t></w:r></w:p>'
                            b'<w:p><w:r><w:rPr><w:color w:val="123456"/></w:rPr>'
                            b'<w:t>CORPUS-KEEP</w:t></w:r></w:p></w:body></w:document>')
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=method) as archive:
        archive.comment = "Independent OOXML control".encode()
        parts = {"[Content_Types].xml": '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>', name: document}
        for part, content in parts.items():
            entry = zipfile.ZipInfo(part, date_time=(1980, 1, 1, 0, 0, 0))
            entry.create_system = 0
            entry.external_attr = 0x20
            archive.writestr(entry, content, compress_type=method)
    return output.getvalue()


def create(output: Path):
    output.mkdir(parents=True, exist_ok=False)
    ordinary = package()
    end = ordinary.rfind(b"PK\x05\x06")
    central = struct.unpack_from("<I", ordinary, end + 16)[0]
    altered_name = bytearray(ordinary); altered_name[central + 46] ^= 1
    altered_crc = bytearray(ordinary); altered_crc[central + 16] ^= 1
    altered_offset = bytearray(ordinary); struct.pack_into("<I", altered_offset, central + 42, 1)
    altered_count = bytearray(ordinary); struct.pack_into("<H", altered_count, end + 10, 1)
    cases = {"word-deflated.docx": ordinary, "word-stored.docx": package(zipfile.ZIP_STORED),
             "bad-truncated.docx": ordinary[:central], "bad-central-name.docx": altered_name,
             "bad-central-crc.docx": altered_crc, "bad-local-offset.docx": altered_offset,
             "bad-entry-count.docx": altered_count, "bad-trailing.docx": ordinary + bytes(1024),
             "bad-traversal.docx": package(name="../escape.xml"),
             "bad-doctype.docx": package(document=b'<!DOCTYPE x [<!ENTITY e SYSTEM "https://example.invalid/entity">]><x>&e;</x>')}
    inventory = {}
    for name, content in cases.items():
        (output / name).write_bytes(content)
        inventory[name] = {"sha256": hashlib.sha256(content).hexdigest(), "bytes": len(content),
                           "expected": "reject" if name.startswith("bad-") else "accept"}
    (output / "manifest.json").write_text(json.dumps({"schema": 1, "generator": "JDK-independent Python ZIP/OOXML",
                                                   "files": inventory}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"fixtures": len(cases), "accepted": 2, "rejected": len(cases) - 2}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("build/verification/compatibility-fixtures"))
    create(parser.parse_args().output)

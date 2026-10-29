# SPDX-License-Identifier: Apache-2.0
"""Independently inspect standard Word XML, package relationships and semantic editing artifacts."""
from __future__ import annotations
import argparse
from datetime import datetime
import json
from pathlib import Path, PurePosixPath
import posixpath
import zipfile
import xml.etree.ElementTree as ET

W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
A = "http://schemas.openxmlformats.org/drawingml/2006/main"
WPS = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
NS = {"w": W, "wp": WP, "a": A, "wps": WPS}


def inspect(path: Path, decided: bool) -> dict:
    with zipfile.ZipFile(path) as package:
        assert package.testzip() is None, "ZIP CRC mismatch"
        names = set(package.namelist())
        assert len(names) == len(package.namelist()), "duplicate package paths"
        roots = {name: ET.fromstring(package.read(name)) for name in names if name.endswith((".xml", ".rels"))}
        for name, root in roots.items():
            if not name.endswith(".rels"):
                continue
            owner = "" if name == "_rels/.rels" else name.replace("/_rels/", "/")[:-5]
            seen = set()
            for relationship in root:
                assert relationship.attrib["Id"] not in seen, "duplicate relationship ID"
                seen.add(relationship.attrib["Id"])
                if relationship.get("TargetMode") == "External":
                    assert relationship.attrib["Type"].endswith("/hyperlink"), "external resource"
                    continue
                target = posixpath.normpath(posixpath.join(posixpath.dirname(owner), relationship.attrib["Target"]))
                assert target in names, f"missing relationship target: {target}"
        main = roots["word/document.xml"]
        for root in roots.values():
            for element in root.iter():
                if (date := element.get(f"{{{W}}}date")) is not None:
                    assert len(date.split("T", 1)[1].split(":", 2)) == 3, "Word dates must include seconds"
                    datetime.fromisoformat(date.replace("Z", "+00:00"))
        for old in main.findall(".//w:pPrChange/w:pPr", NS):
            assert old.find("w:rPr", NS) is None, "prior paragraph properties cannot contain run properties"
        assert len(main.findall(".//wp:anchor", NS)) == 2, "expected two real floating drawings"
        assert len(main.findall(".//wps:txbx/w:txbxContent", NS)) == 1, "missing typed textbox"
        textbox = main.find(".//wps:txbx/w:txbxContent", NS)
        assert textbox is not None and textbox.find("w:tbl", NS) is not None, "textbox table lost"
        transform = main.find(".//a:xfrm[@rot]", NS)
        assert transform is not None and transform.get("rot") == "1500000" and transform.get("flipH") == "1", "image transform changed"
        crop = main.find(".//a:srcRect", NS)
        assert crop is not None and crop.get("l") == "10000" and crop.get("r") == "5000", "crop fractions changed"
        controls = main.findall(".//w:sdt", NS)
        assert len(controls) == 11, f"expected eleven modern controls, got {len(controls)}"
        form_data = main.findall(".//w:ffData", NS)
        assert len(form_data) == 3, "legacy form structure missing"
        assert any(e.find("w:textInput/w:maxLength", NS) is not None for e in form_data), "legacy constraint missing"
        assert "FORM-READY" in "".join(main.itertext()), "actual edited legacy value absent"
        text_form = next(e for e in form_data if e.find("w:textInput", NS) is not None)
        assert text_form.find("w:textInput/w:default", NS).get(f"{{{W}}}val") == "DEFAULT", "legacy default was replaced with its current input"
        list_form = next(e.find("w:ddList", NS) for e in form_data if e.find("w:ddList", NS) is not None)
        assert list_form.find("w:result", NS).get(f"{{{W}}}val") == "1", "legacy current selection index missing"
        assert list_form.find("w:default", NS).get(f"{{{W}}}val") == "0", "legacy default selection changed"
        comments = roots["word/comments.xml"].findall("w:comment", NS)
        assert len(comments) == 1
        starts = main.findall(".//w:commentRangeStart", NS)
        ends = main.findall(".//w:commentRangeEnd", NS)
        assert len(starts) == len(ends) == 1 and starts[0].get(f"{{{W}}}id") == ends[0].get(f"{{{W}}}id"), "comment range mismatch"
        assert any("TOC" in (e.text or "") for e in main.findall(".//w:instrText", NS)), "native TOC field missing"
        sections = main.findall(".//w:sectPr", NS)
        assert len(sections) == 2
        refs = []
        for section in sections:
            headers = section.findall("w:headerReference", NS)
            footers = section.findall("w:footerReference", NS)
            assert {e.get(f"{{{W}}}type") for e in headers} == {"default", "first", "even"}
            assert {e.get(f"{{{W}}}type") for e in footers} == {"default", "first", "even"}
            assert section.find("w:titlePg", NS) is not None
            refs.append({(e.tag, e.get(f"{{{W}}}type")): e.get(f"{{{R}}}id") for e in headers + footers})
        assert refs[0] == refs[1], "shared band inheritance/deduplication changed"
        settings = roots["word/settings.xml"]
        assert settings.find("w:evenAndOddHeaders", NS) is not None
        assert settings.find("w:updateFields", NS) is None, "native output must not request a global update that resets user form inputs"
        assert all(field.get(f"{{{W}}}dirty") not in {"true", "1", "on"} for root in roots.values() for field in root.findall(".//w:fldSimple", NS)), "ordinary fields must not force global recalculation"
        assert all(field.get(f"{{{W}}}dirty") not in {"true", "1", "on"} for field in main.findall(".//w:fldChar", NS)), "explicitly refreshed TOC cache is still marked dirty"
        assert any("PAGE" in e.get(f"{{{W}}}instr", "") for root in roots.values() for e in root.findall(".//w:fldSimple", NS)), "native PAGE field missing"

        def depth(node: ET.Element, current: int = 0) -> int:
            value = current + (node.tag == f"{{{W}}}tbl")
            return max([value] + [depth(child, value) for child in node])

        assert depth(main) >= 4, "four-level nested table lost"
        texts = [e.text or "" for e in main.findall(".//w:t", NS)]
        assert "DEEP-READY" in texts and "DEEP-OLD" not in texts, "deep semantic paragraph edit missing"
        assert "TEXTBOX-READY" in texts and "TEXTBOX-OLD" not in texts, "real textbox edit missing"
        for table in main.findall(".//w:tbl", NS):
            borders = table.find("w:tblPr/w:tblBorders", NS)
            assert borders is not None and len(borders) == 6
            assert {b.get(f"{{{W}}}val") for b in borders} <= {"nil", "single"}
        revisions = sum(len(main.findall(f".//w:{kind}", NS)) for kind in ("ins", "del", "moveFrom", "moveTo", "rPrChange", "pPrChange"))
        if decided:
            assert revisions == 0, "accepted/rejected review markup remains"
            for name in names:
                if name.endswith(".xml"):
                    payload = package.read(name)
                    assert b"DELETE-PRIVATE" not in payload and b"INSERTED-PRIVATE" not in payload, "rejected source retained in package"
        else:
            assert revisions >= 6
            assert len(main.findall(".//w:moveFromRangeStart", NS)) == len(main.findall(".//w:moveToRangeStart", NS)) == 1
            assert all(e.find(".//w:t", NS) is not None and e.find(".//w:delText", NS) is None for e in main.findall(".//w:moveFrom", NS)), "moved source requires ordinary text"
            assert all(e.find(".//w:delText", NS) is not None for e in main.findall(".//w:del", NS)), "deleted source requires deleted text"
        return {"file": path.name, "drawings": 2, "modernControls": len(controls), "legacyForms": len(form_data), "tableDepth": depth(main), "sections": len(sections), "revisions": revisions, "zipParts": len(names)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    arguments = parser.parse_args()
    records = [inspect(arguments.directory / "semantic-edited.docx", False), inspect(arguments.directory / "semantic-decided.docx", True)]
    (arguments.directory / "independent-structure.json").write_text(json.dumps({"passed": True, "artifacts": records}, indent=2), encoding="utf-8")
    print(json.dumps({"passed": True, "artifacts": records}))


if __name__ == "__main__":
    main()

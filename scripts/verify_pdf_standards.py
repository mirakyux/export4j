# SPDX-License-Identifier: Apache-2.0
"""Independently validate every archive/UA fixture and real offline PAdES trust/evidence operation."""
import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
from asn1crypto import x509
from pypdf import PdfReader
from pyhanko.pdf_utils.reader import PdfFileReader
from pyhanko.sign.validation import (validate_pdf_signature, validate_pdf_ltv_signature,
    validate_pdf_timestamp, RevocationInfoValidationType)
from pyhanko_certvalidator import ValidationContext
from pyhanko.sign.diff_analysis import DiffResult
from verify_advanced_pdf import difference, require
from verify_pdf_raster import references
from verify_production import contact_sheet
from verify_variable_columns import ink_coverage

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-standards"
FIXTURES = ROOT / "build/verification/pdf-standards-fixtures"


def compliance(path, flavour, executable):
    result = subprocess.run([str(executable), "--format", "xml", "--flavour", flavour, str(path)],
                            capture_output=True, timeout=90, check=False)
    target = OUTPUT / f"{path.stem}-verapdf.xml"
    target.write_bytes(result.stdout)
    report = ET.fromstring(result.stdout)
    validations = report.findall(".//validationReport")
    require(len(validations) == 1 and validations[0].get("isCompliant") == "true",
            f"independent {flavour} compliance failed: {path.name}")
    jobs = report.findall(".//job")
    require(len(jobs) == 1 and Path(jobs[0].findtext("item/name")).resolve() == path.resolve(),
            "compliance report describes another file")
    return {"profile": validations[0].get("profileName"), "sha256": sha256(path.read_bytes()).hexdigest()}


def tagged(path, expected=None):
    reader = PdfReader(path, strict=True)
    catalog = reader.trailer["/Root"]
    root = catalog["/StructTreeRoot"]
    require(catalog["/MarkInfo"]["/Marked"] and catalog["/ViewerPreferences"]["/DisplayDocTitle"],
            "accessible catalog lacks marked/title preferences")
    page_ids = {page.indirect_reference.idnum: index for index, page in enumerate(reader.pages)}
    text_by_mcid = {}
    for page_index, page in enumerate(reader.pages):
        stack = []
        def operator(name, operands, cm, tm):
            if name in (b"BMC", b"BDC"):
                props = operands[1] if len(operands) > 1 else {}
                if not hasattr(props, "get"):
                    props = page["/Resources"]["/Properties"][props]
                stack.append(props.get("/MCID", stack[-1] if stack else None))
            elif name == b"EMC":
                require(bool(stack), "independent marked stack underflow")
                stack.pop()
        def text(value, cm, tm, font, size):
            if value.strip() and stack and stack[-1] is not None:
                text_by_mcid.setdefault((page_index, int(stack[-1])), []).append(value)
        page.extract_text(visitor_operand_before=operator, visitor_text=text)
        require(not stack, "independent marked stack is unbalanced")
    ids, headers, roles, logical, references_seen = {}, [], [], [], set()
    def visit(value, inherited_page=None):
        value = value.get_object() if hasattr(value, "get_object") else value
        if isinstance(value, list):
            for child in value:
                visit(child, inherited_page)
            return
        if isinstance(value, int):
            key = (inherited_page, value)
            require(key not in references_seen, "tagged content has duplicate owners")
            references_seen.add(key)
            if key in text_by_mcid:
                logical.append("".join(text_by_mcid[key]).strip())
            return
        require(hasattr(value, "get"), "invalid independent tag child")
        if value.get("/Type") == "/MCR":
            page = value.get("/Pg")
            visit(int(value["/MCID"]), page_ids[page.idnum] if page else inherited_page)
            return
        role = str(value.get("/S", "")); roles.append(role)
        identifier = value.get("/ID")
        if identifier:
            require(str(identifier) not in ids, "duplicate independent structure ID")
            ids[str(identifier)] = role
        if role == "/Figure":
            require(bool(value.get("/Alt")), "independent figure alt text is empty")
            logical.append(str(value["/Alt"]))
        attributes = value.get("/A", [])
        if not isinstance(attributes, list):
            attributes = [attributes]
        for attribute in attributes:
            attribute = attribute.get_object()
            if hasattr(attribute, "get"):
                headers.extend(str(item) for item in attribute.get("/Headers", []))
                if role == "/TH":
                    require(attribute.get("/Scope") in ("/Column", "/Row", "/Both"), "table header lacks scope")
        page = value.get("/Pg")
        if "/K" in value:
            visit(value["/K"], page_ids[page.idnum] if page else inherited_page)
    visit(root["/K"])
    require(all(ids.get(header) == "/TH" for header in headers) and len(headers) >= 4, "table header associations differ")
    require(all(role in roles for role in ["/H1", "/P", "/L", "/LI", "/Lbl", "/LBody", "/Figure", "/Table", "/TH", "/TD"]), "accessible semantic roles are missing")
    if expected is not None:
        require(logical == expected, "independent logical reading order differs")
    require(set(text_by_mcid).issubset(references_seen), "selected text has no structure owner")
    return {"pages": len(reader.pages), "logicalText": logical, "headerReferences": len(headers), "markedContents": len(references_seen)}


def context_kwargs():
    root = x509.Certificate.load((FIXTURES / "root.der").read_bytes())
    return dict(trust_roots=[root], moment=datetime(2026, 1, 4, tzinfo=timezone.utc), allow_fetching=False,
                revocation_mode="require", ocsps=[(FIXTURES / "signer-good.ocsp").read_bytes(), (FIXTURES / "tsa-good.ocsp").read_bytes()],
                crls=[(FIXTURES / "good.crl").read_bytes()])


def signatures(path):
    report = []
    with path.open("rb") as source:
        reader = PdfFileReader(source)
        kwargs = context_kwargs()
        for signature in reader.embedded_regular_signatures:
            require(str(signature.sig_object["/SubFilter"]) == "/ETSI.CAdES.detached", "PAdES does not use detached CAdES")
            if "lta" in path.stem:
                result = validate_pdf_ltv_signature(signature, RevocationInfoValidationType.PADES_LTA, validation_context_kwargs=kwargs)
            elif "lt" in path.stem:
                result = validate_pdf_ltv_signature(signature, RevocationInfoValidationType.PADES_LT, validation_context_kwargs=kwargs)
            else:
                result = validate_pdf_signature(signature, ValidationContext(**kwargs), ValidationContext(**kwargs))
            require(result.bottom_line, f"independent PAdES trust/long-term validation rejected {path.name}")
            report.append({"summary": result.summary(), "intact": result.intact, "trusted": result.trusted, "docmdpOk": result.docmdp_ok})
        timestamps = [item for item in reader.embedded_signatures if str(item.sig_object.get("/SubFilter")) == "/ETSI.RFC3161"]
        require(len(timestamps) == (2 if "renewed" in path.stem else 1 if "lta" in path.stem else 0), "document timestamp renewal count differs")
        for signature in timestamps:
            result = validate_pdf_timestamp(signature, ValidationContext(**kwargs))
            require(result.intact and result.valid and result.trusted and result.docmdp_ok is not False and isinstance(result.diff_result, DiffResult), "independent document timestamp validation or modification policy failed")
            report.append({"documentTimestamp": True, "summary": result.summary()})
    require(bool(report), "independent PAdES verification found no signature")
    return report


def rejected_signatures(path):
    kwargs = context_kwargs()
    with path.open("rb") as source:
        signature = PdfFileReader(source).embedded_regular_signatures[0]
        result = validate_pdf_signature(signature, ValidationContext(**kwargs))
        require(not result.bottom_line and not result.intact, "independent CMS verification accepted changed signed bytes")
    rejected = []
    for name in ["signer-revoked.ocsp", "signer-unknown.ocsp", "signer-stale.ocsp"]:
        kwargs = context_kwargs(); kwargs["crls"] = []; kwargs["ocsps"] = [(FIXTURES / name).read_bytes()]
        with path.with_name("pades-b.pdf").open("rb") as source:
            signature = PdfFileReader(source).embedded_regular_signatures[0]
            try:
                result = validate_pdf_signature(signature, ValidationContext(**kwargs))
                require(not result.bottom_line, f"independent revocation policy accepted {name}")
            except (ValueError, RuntimeError):
                pass
        rejected.append(name)
    return rejected


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consumer-build-directory", type=Path)
    parser.add_argument("--verapdf", type=Path, default=ROOT / "build/verification/verapdf/verapdf.bat")
    args = parser.parse_args()
    base = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    source = base / "output/pdf-standards"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report, tiles, render = {"compliance": {}, "tagged": {}, "pades": {}}, [], {}
    expected = (source / "expected-reading-order.txt").read_text().splitlines()
    model_expected = ["Model heading", "Model reading order", "-", "Bullet body", "1.", "Number body",
                      "Blue model marker", "Name", "Count"]
    model_expected.extend(value for row in range(12) for value in (f"Item {row}", f"Count {row}"))
    names = []
    for profile in ["a1b", "a2b", "a3b", "a4f"]:
        for suffix in ["", "-converted"]:
            name = profile + suffix; path = source / (name + ".pdf"); names.append(name)
            report["compliance"][name] = compliance(path, profile[1:], args.verapdf)
            reader = PdfReader(path, strict=True)
            require(len(reader.pages) == 2 and not reader.is_encrypted, "archive page count/encryption differs")
            text = "".join(page.extract_text() for page in reader.pages)
            if suffix:
                require(not text.strip() and all(len(page.images) == 1 for page in reader.pages), "explicit raster conversion retained source text/resources")
            else:
                require("Accessible export" in text and "Pencils" in text and "End of accessible document." in text, "archive source text lost")
            attachments = list(reader.attachments)
            require(attachments == (["inventory.csv"] if profile in ("a3b", "a4f") else []), "archive associated files differ")
            if attachments:
                require(reader.attachments["inventory.csv"] == [b"Item,Quantity\nPencils,4\nPaper,8\n"], "archive associated-file bytes changed")
    for name in ["ua1", "ua1-retitled", "ua1-model"]:
        path = source / (name + ".pdf"); names.append(name)
        report["compliance"][name] = compliance(path, "ua1", args.verapdf)
        report["tagged"][name] = tagged(path, model_expected if name == "ua1-model" else expected)
    names.append("a2b-alpha"); report["compliance"]["a2b-alpha"] = compliance(source / "a2b-alpha.pdf", "2b", args.verapdf)
    require(report["tagged"]["ua1"]["logicalText"] == report["tagged"]["ua1-retitled"]["logicalText"], "metadata edit changed logical reading order")
    for name in ["pades-b", "pades-t", "pades-lt", "pades-lt-crl", "pades-lta", "pades-lta-renewed", "pades-certified-1-lta", "pades-certified-2-lta", "pades-certified-3-lta"]:
        path = source / (name + ".pdf"); names.append(name); report["pades"][name] = signatures(path)
        require("Accessible export" in "".join(page.extract_text() for page in PdfReader(path).pages), "PAdES update changed visible source")
    report["rejectedEvidence"] = rejected_signatures(source / "pades-tampered.pdf")
    require((source / "pades-rejected.pdf").read_bytes() == b"OLD TARGET", "failed signing/evidence update replaced its old target")
    for name in names:
        path = source / (name + ".pdf")
        first, second = references(path, OUTPUT / (name + "-render")); require(len(first) == len(second) > 0, "renderer page counts differ")
        comparisons = []
        for index, (a, b) in enumerate(zip(first, second, strict=True)):
            delta = difference(a, b, blur_radius=1); delta["uncoveredInkFractions"] = ink_coverage(a, b)
            require(max(delta["uncoveredInkFractions"]) < .001, f"renderers disagree on visible ink: {name}")
            comparisons.append(delta); tiles.extend([(f"{name} Poppler {index+1}", a), (f"{name} PDFium {index+1}", b)])
        render[name] = comparisons
    contact_sheet(tiles, OUTPUT); report["rendering"] = render; report["pages"] = sum(len(value) for value in render.values())
    report["fixtureSha256"] = {name: sha256((source / (name + ".pdf")).read_bytes()).hexdigest() for name in names}
    (OUTPUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"PDF standards: {len(report['compliance'])} veraPDF results; semantic order/headers/alt; B/T/LT/LTA/renewal and revoked/unknown/stale/tamper refusal; {report['pages']} pages dual rendered")


if __name__ == "__main__":
    main()

# SPDX-License-Identifier: Apache-2.0
"""Rebuild the example catalog from a local, byte-exact ManuWeave snapshot.

No upstream Python code is imported or executed, and no network access is used.
Only index.tsv, provenance.xml, MIT.txt and profiles/*.xml are managed; README.md
is never changed. Use --check to compare without creating or writing any files.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import html
import json
from pathlib import Path
import re
from xml.sax.saxutils import escape, quoteattr

ROOT = Path(__file__).resolve().parents[1]
COMMIT = "e48d7b6d571e133d0bc86fc7cf61b343fa9cb64b"
README_SHA256 = "ef819ae09d685ae67b4dd089a0990b2a0529f818a585c0be3424846799ea7e06"
# SHA-256 of sorted relative-path<TAB>SHA-256<LF> records for README.md,
# LICENSE, the 277 profile JSON files and the nine preset JSON files.
SOURCE_MANIFEST_SHA256 = "94dc39dcc7f80eaecacb77f4099d4009038dfa51fb70122d93e56f406780bc91"
GROUP_COUNTS = {"Journals": 16, "Publishers": 57, "ACM": 41, "IEEE": 52,
                "Chinese journals": 10, "Elsevier": 5, "Wiley": 16,
                "More journals": 80, "General templates": 9}
DEFAULT_OUTPUT = ROOT / "examples/src/main/resources/templates/manuweave"


def scalar(value):
    return value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def flatten(values, prefix, result):
    for key, value in values.items():
        name = prefix + "." + key
        if isinstance(value, dict) and value:
            flatten(value, name, result)
        else:
            result[name] = scalar(value)


def xml_properties(values):
    entries = ["<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
               "<!DOCTYPE properties SYSTEM \"http://java.sun.com/dtd/properties.dtd\">", "<properties>"]
    entries.extend("  <entry key=" + quoteattr(key) + ">" + escape(value) + "</entry>"
                   for key, value in sorted(values.items()))
    return "\n".join(entries + ["</properties>", ""]).encode("utf-8")


def read_snapshot(source):
    """Validate fixed source bytes before generating or touching destination files."""
    readme = (source / "README.md").read_bytes()
    if hashlib.sha256(readme).hexdigest() != README_SHA256:
        raise ValueError("README.md bytes do not match the pinned ManuWeave commit " + COMMIT)
    paths = [path for path in (source / "src/manuweave/data").rglob("*.json")
             if path.parent.name in ("profiles", "presets")]
    profiles = {}
    for path in paths:
        if not re.fullmatch(r"[a-z0-9-]+", path.stem) or path.stem in profiles:
            raise ValueError("Invalid or duplicate upstream profile ID")
        profiles[path.stem] = (path.relative_to(source).as_posix(), path.read_bytes())
    if Counter(path.parent.name for path in paths) != {"profiles": 277, "presets": 9}:
        raise ValueError("Expected exactly 277 profile JSON files and nine preset JSON files")
    license_bytes = (source / "LICENSE").read_bytes()
    source_bytes = {"README.md": readme, "LICENSE": license_bytes}
    source_bytes.update({relative: content for relative, content in profiles.values()})
    manifest = "".join(relative + "\t" + hashlib.sha256(content).hexdigest() + "\n"
                       for relative, content in sorted(source_bytes.items()))
    if hashlib.sha256(manifest.encode("utf-8")).hexdigest() != SOURCE_MANIFEST_SHA256:
        raise ValueError("Template or license bytes do not match the pinned ManuWeave commit " + COMMIT)
    return readme, license_bytes, profiles


def read_catalog(readme):
    group, rows = "", []
    for line in readme.decode("utf-8").splitlines():
        match = re.fullmatch(r"<summary><strong>(.+?) \((\d+)\)</strong></summary>", line)
        if match:
            group = html.unescape(match[1])
        match = re.fullmatch(r"\| (.*?) \| `([^`]+)` \| \[[^]]+\]\((https?://.*?)\) \|", line)
        if match:
            label, identifier, url = map(html.unescape, match.groups())
            rows.append((group, identifier, label, url))
    if len(rows) != 286 or len({row[1] for row in rows}) != 286:
        raise ValueError("Expected 286 unique Profile IDs in the pinned README")
    if dict(Counter(row[0] for row in rows)) != GROUP_COUNTS:
        raise ValueError("README template groups do not match the pinned catalog")
    return rows


def example_metadata(identifier, original):
    """Select fictional demonstration content without claiming submission compliance."""
    evidence = " ".join(str(value) for value in (
        original["name"], original.get("extracted_format", {}).get("mode", ""),
        original.get("extracted_format", {}).get("source_mode", ""),
        original.get("latex", {}).get("options", [])))
    anonymous = bool(re.search(r"anonymous|double.?blind|(?:^|[\s,\[])blind(?:$|[\s,\]])", evidence, re.I))
    chinese = identifier.startswith(("zh-", "cn-")) or original.get("latex", {}).get("documentclass", "").startswith("ctex")
    kind = {"zh-master": "masters-thesis", "zh-phd": "doctoral-dissertation",
            "zh-undergraduate": "undergraduate-thesis", "en-report": "technical-report",
            "en-conference": "conference-paper"}.get(identifier, "article")
    if identifier in ("acm-sigconf", "acm-sigconf-review-anonymous", "acm-sigplan", "ieee-conference",
                      "ieee-conference-compsoc", "ieee-word-a4-2019", "ieee-word-letter-2019",
                      "springer-llncs-author-year", "springer-llncs-numeric"):
        kind = "conference-paper"
    return {"example.language": "zh" if chinese else "en", "example.kind": kind,
            "example.anonymous": scalar(anonymous)}


def profile_properties(row, source_path, source_bytes):
    group, identifier, label, url = row
    original = json.loads(source_bytes)
    if original["id"] != identifier:
        raise ValueError("Mismatched upstream profile ID: " + identifier)
    values = {"id": identifier, "name": original["name"], "readme-name": label,
              "group": group, "source": url, "upstream-path": source_path,
              "upstream.commit": COMMIT, "upstream.sha256": hashlib.sha256(source_bytes).hexdigest(),
              "role.names": scalar(list(original["rules"])), **example_metadata(identifier, original)}
    for key in ("version", "compliance", "format_origin", "manuscript_stage", "stage", "manuscript_type",
                "journal_id", "formal_title", "manuscript_format", "journal_title", "catalog_journal_id",
                "current_coverage"):
        if key in original:
            values[key] = scalar(original[key])
    for section in ("page", "sections", "running", "tables", "latex", "journal"):
        if section in original:
            flatten(original[section], section, values)
    for role, properties in original["rules"].items():
        flatten(properties, "role." + role, values)
    for section, keys in {
        "authored_style": ("author", "kind"),
        "extracted_format": ("mode", "source_mode", "source_configuration", "line_numbering", "font_options",
                             "citations", "references", "equations"),
        "template": ("family", "source_version", "license", "source_url", "engine_support"),
    }.items():
        selected = {key: original[section][key] for key in keys if key in original.get(section, {})}
        flatten(selected, section, values)
    return values


def expected_files(source):
    readme, license_bytes, profiles = read_snapshot(source)
    rows = read_catalog(readme)
    if {row[1] for row in rows} != profiles.keys():
        raise ValueError("Profile JSON files do not match the README Profile IDs")
    result, index = {}, []
    for row in rows:
        source_path, source_bytes = profiles[row[1]]
        values = profile_properties(row, source_path, source_bytes)
        result["profiles/" + row[1] + ".xml"] = xml_properties(values)
        index_row = (row[0], row[1], values["name"], row[3], source_path)
        if any("\t" in field or "\n" in field for field in index_row):
            raise ValueError("Invalid tab-separated index value: " + row[1])
        index.append("\t".join(index_row))
    result["index.tsv"] = ("\n".join(index) + "\n").encode("utf-8")
    result["MIT.txt"] = license_bytes
    result["provenance.xml"] = xml_properties({
        "upstream.url": "https://github.com/jiassia/manuweave", "upstream.commit": COMMIT,
        "upstream.readme.sha256": README_SHA256, "profile.count": "286",
        "profile.source-count": "277", "preset.source-count": "9",
        "index.columns": "group\tid\tname\tsource\tupstream-path", "index.header": "false",
        "groups": scalar(dict(Counter(row[0] for row in rows))),
    })
    return result


def check_files(output, expected):
    differences = []
    for relative, content in expected.items():
        target = output / relative
        if not target.is_file():
            differences.append("missing: " + relative)
        elif target.read_bytes() != content:
            differences.append("changed: " + relative)
    if (output / "profiles").is_dir():
        differences.extend("unexpected: " + path.relative_to(output).as_posix()
                           for path in sorted((output / "profiles").rglob("*.xml"))
                           if path.relative_to(output).as_posix() not in expected)
    return differences


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True,
                        help="Local exact-byte export of ManuWeave commit " + COMMIT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT,
                        help="Resource directory; defaults to examples/src/main/resources/templates/manuweave")
    parser.add_argument("--check", action="store_true", help="Compare all managed bytes without writing")
    args = parser.parse_args()
    try:
        expected = expected_files(args.source)
        if args.check:
            differences = check_files(args.output, expected)
            if differences:
                parser.exit(1, "Catalog differs:\n" + "\n".join(differences) + "\n")
            print("PASS: 286 profiles and all catalog/license bytes match the pinned source")
        else:
            for relative, content in expected.items():
                target = args.output / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
            print("Wrote 286 profiles, index.tsv, provenance.xml and MIT.txt")
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()

# SPDX-License-Identifier: Apache-2.0
"""Parse HTML and CommonMark/GFM independently; inspect examples and the latest Maven caller."""
from __future__ import annotations

import argparse
import base64
import csv
from dataclasses import dataclass, field
from hashlib import sha256
from html.parser import HTMLParser
from importlib.metadata import version
import json
from pathlib import Path

from markdown_it import MarkdownIt

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/web-exports"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


@dataclass
class Node:
    tag: str
    attrs: dict = field(default_factory=dict)
    children: list = field(default_factory=list)

    def text(self):
        return "".join(child.text() if isinstance(child, Node) else child for child in self.children)

    def find(self, tag):
        return [child for child in self.children if isinstance(child, Node) for child in
                ([child] if child.tag == tag else []) + child.find(tag)]


class Tree(HTMLParser):
    def __init__(self, value):
        super().__init__(convert_charrefs=True)
        self.root = Node("root")
        self.stack = [self.root]
        self.feed(value)
        self.close()
        require(len(self.stack) == 1, "unclosed output elements")

    def handle_starttag(self, tag, attrs):
        node = Node(tag, dict(attrs))
        self.stack[-1].children.append(node)
        if tag not in {"meta", "img", "br", "hr", "col"}:
            self.stack.append(node)

    def handle_endtag(self, tag):
        require(self.stack[-1].tag == tag, "unbalanced output element " + tag)
        self.stack.pop()

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in {"meta", "img", "br", "hr", "col"}:
            self.handle_endtag(tag)

    def handle_data(self, value):
        self.stack[-1].children.append(value)


def safe(tree):
    root = tree.root
    for tag in ("script", "iframe", "object", "embed", "form", "base", "link"):
        require(not root.find(tag), "unexpected executable/resource element " + tag)
    def walk(node):
        for key in node.attrs:
            require(not key.startswith("on"), "event-handler attribute")
        for child in node.children:
            if isinstance(child, Node):
                walk(child)
    walk(root)
    for link in root.find("a"):
        require(link.attrs.get("href", "").startswith(("https://", "http://", "mailto:")), "unsafe destination")


def read(path, markdown=False):
    raw = path.read_bytes()
    text = raw.decode("utf-8", errors="strict")
    if markdown:
        text = MarkdownIt("commonmark").enable("table").enable("strikethrough").render(text)
        preview = "<!doctype html><html><head><meta charset=\"utf-8\">" \
                  "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" \
                  "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'\">" \
                  "<style>body{font:16px/1.5 sans-serif;max-width:960px;margin:24px auto;padding:0 16px;overflow-wrap:anywhere}" \
                  "table{border-collapse:collapse;width:100%}td,th{border:1px solid #bbb;padding:8px}img{max-width:100%}</style>" \
                  "</head><body>" + text + "</body></html>"
        (OUTPUT / (path.stem + "-markdown.html")).write_text(preview, encoding="utf-8")
    tree = Tree(text)
    safe(tree)
    return tree.root, {"file": path.name, "sha256": sha256(raw).hexdigest(), "bytes": len(raw)}


def rich(root, markdown, logo):
    content = root.text()
    for token in ("BULLET-ONLY", "STEP-ONLY", "RESTART-ONLY", "MERGED-HEADER", "BEFORE-NESTED",
                  "NESTED-ONLY", "AFTER-NESTED", "VERTICAL-ONLY", "FINAL-CELL", "CLOSING-ONLY"):
        require(content.count(token) == 1, "missing/duplicated source " + token)
    require(content.index("BEFORE-NESTED") < content.index("NESTED-ONLY") < content.index("AFTER-NESTED"), "nested source order")
    require("Literal markup: <script>alert(1)</script> & | * [text] 😀" in content, "source markup/entities changed")
    require("aBOLD!NEXTz &amp;" in content, "adjacent styled run text/entities changed")
    require("[unresolved PAGE]" in content, "field fallback missing")
    require([node.text() for node in root.find("h1")] == ["Web export report"], "heading semantics")
    require(len(root.find("img")) == 2, "image count")
    for image in root.find("img"):
        uri = image.attrs["src"]
        require(uri.startswith("data:image/png;base64,"), "image must be embedded PNG")
        require(base64.b64decode(uri.split(",", 1)[1], validate=True) == logo, "embedded image bytes")
        require(image.attrs["alt"] == "Blue square logo", "image alternate text")
    require({node.attrs["href"] for node in root.find("a")} == {
        "https://example.com/a%20b?q=1&x=2", "https://example.com/report"}, "link destination changed")
    if markdown:
        require([node.text() for node in root.find("strong")] == [" EXPORT4J", "explicit styles", "BOLD!", "NEXT"], "emphasis run boundaries")
        require([node.attrs.get("start", "1") for node in root.find("ol")] == ["3", "8"], "number restart ignored by CommonMark")
        require(len(root.find("table")) == 1, "nested table was not flattened")
        require(len(root.find("th")) == 2 and len(root.find("td")) == 4, "GFM rectangular coverage")
    else:
        require(len(root.find("section")) == 2 and len(root.find("header")) == len(root.find("footer")) == 1, "section/region structure")
        require(len(root.find("table")) == 2, "nested HTML table missing")
        require(any(node.attrs.get("colspan") == "2" for node in root.find("th")), "column span lost")
        require(any(node.attrs.get("rowspan") == "2" for node in root.find("td")), "row span lost")
        require(any("font-weight:700" in node.attrs.get("style", "") and node.text() == "BOLD!"
                    for node in root.find("span")), "explicit role CSS missing")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path)
    args = parser.parse_args()
    versions = {name: version(name) for name in ("markdown-it-py", "mdurl")}
    require(versions == {"markdown-it-py": "4.0.0", "mdurl": "0.1.2"}, "install pinned verification requirements")
    caller = args.consumer_build_directory or Path((ROOT / "build/verification/consumer-build-directory.txt").read_text().strip())
    OUTPUT.mkdir(parents=True, exist_ok=True)
    examples = ROOT / "examples/build/web-exports"
    reports = []
    for markdown, suffix in ((False, "html"), (True, "md")):
        root, report = read(examples / ("report." + suffix), markdown)
        rich(root, markdown, (examples / "logo.png").read_bytes())
        reports.append(report)
        root, report = read(examples / ("content." + suffix), markdown)
        require("PLAIN-ONLY & | <tag> 中文" in root.text(), "strict content escaped incorrectly")
        require([node.text().strip() for node in root.find("td")] == ["CELL-ONLY", "2 | 3"], "strict table pipe escaping")
        reports.append(report)
        root, report = read(caller / "output" / ("consumer-web." + suffix), markdown)
        require("CALLER-ONLY <script> & | 中文" in root.text(), "caller text changed")
        require([node.text().strip() for node in root.find("td")] == ["CALLER-CELL", "1 | 2"], "caller table")
        require(len(root.find("h2")) == len(root.find("ul")) == len(root.find("hr")) == 1, "caller block semantics")
        reports.append(report)
    with (examples / "diagnostics.tsv").open(encoding="utf-8", newline="") as source:
        rows = list(csv.DictReader(source, delimiter="\t"))
    require(not any(row["target"] == "strict" for row in rows), "strict simple export warned")
    require([row["feature"] for row in rows if row["target"] == "html"] ==
            ["PAGE_REGIONS", "PAGE_REGIONS", "INLINE_FIELD", "PAGE_NUMBERING"], "HTML loss diagnostics differ")
    fields = [row for row in rows if row["feature"] == "INLINE_FIELD"]
    require(len(fields) == 2 and all(row["region"] == "FOOTER" and row["offset"] == "5" for row in fields), "UTF-16 diagnostic location")
    result = {"versions": versions, "files": reports, "diagnostics": len(rows), "checks": "passed"}
    (OUTPUT / "report.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()

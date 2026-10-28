# SPDX-License-Identifier: Apache-2.0
"""Original geometric controls made independently of Export4j and PDFBox."""
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
from pypdf import PdfWriter
from pypdf.generic import NameObject
from create_pdf_compatibility_fixtures import dictionary, simple_font, stream

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/verification/pdf-automatic-fixtures"


def text(value, x, top, size=12):
    escaped = value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    return f"BT /F1 {size} Tf 1 0 0 1 {x} {800-top} Tm ({escaped}) Tj ET\n"


def rule(x1, y1, x2, y2):
    return f"{x1} {800-y1} m {x2} {800-y2} l S\n"


def grid(xs, ys, omitted=()):
    result = "0.5 w\n"
    for row, y in enumerate(ys):
        for col in range(len(xs)-1):
            if ("h", row, col) not in omitted:
                result += rule(xs[col], y, xs[col+1], y)
    for col, x in enumerate(xs):
        for row in range(len(ys)-1):
            if ("v", row, col) not in omitted:
                result += rule(x, ys[row], x, ys[row+1])
    return result


def cells(xs, ys, values):
    return "".join(text(value, xs[col]+5, ys[row]+20) for row, col, value in values)


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    records = {}

    def save(name, pages, expected):
        writer = PdfWriter()
        writer.metadata = None
        font = simple_font(writer, embedded=True)
        for content in pages:
            page = writer.add_blank_page(600, 800)
            page[NameObject("/Resources")] = dictionary(Font=dictionary(F1=font))
            page[NameObject("/Contents")] = stream(writer, content.encode("ascii"))
        encoded = BytesIO()
        writer.write(encoded)
        data = encoded.getvalue()
        (OUTPUT / (name+".pdf")).write_bytes(data)
        records[name] = {"sha256": sha256(data).hexdigest(), "pages": len(pages), **expected}

    pages = []
    body = []
    for page in range(2):
        content = text("REPORT HEADER", 40, 30, 10)+text(f"Page {page+1}", 280, 780, 10)
        content += text(f"CHAPTER {page+1}", 40, 100, 20)
        # Content operators are deliberately interleaved right/left and bottom/top.
        content += text(f"RIGHT {page+1} first segment", 330, 150)+text(f"LEFT {page+1} first segment", 40, 150)
        content += text(f"RIGHT {page+1} continued segment", 330, 167)+text(f"LEFT {page+1} continued segment", 40, 167)
        pages.append(content)
        body.extend([f"CHAPTER {page+1}", f"LEFT {page+1} first segment LEFT {page+1} continued segment", f"RIGHT {page+1} first segment RIGHT {page+1} continued segment"])
    save("columns-bands", pages, {"body": body, "headers": ["REPORT HEADER"]*2, "footers": ["Page 1", "Page 2"], "tables": 0, "columns": True})

    content = text("THREE COLUMNS", 40, 80, 20)
    expected = ["THREE COLUMNS"]
    for band, y in (("TOP", 140), ("BOTTOM", 350)):
        if band == "BOTTOM":
            content += text("NEXT BAND", 40, 280, 20)
            expected.append("NEXT BAND")
        for x, label in ((40, "LEFT"), (230, "MIDDLE"), (420, "RIGHT")):
            content += text(label+" "+band+" one", x, y)+text(label+" "+band+" two", x, y+17)
            expected.append(label+" "+band+" one "+label+" "+band+" two")
    save("three-column-bands", [content], {"body": expected, "tables": 0, "columns": True})

    content = text("SEMANTIC TITLE", 40, 80, 22)+text("Paragraph first physical line", 40, 130)+text("and its second physical line.", 40, 147)
    content += text("- Bullet alpha", 40, 200)+text("wrapped bullet content", 55, 217)+text("- Bullet beta", 40, 245)
    content += text("3. Number gamma", 40, 290)+text("4. Number delta", 40, 320)
    save("paragraph-lists", [content], {"body": ["SEMANTIC TITLE", "Paragraph first physical line and its second physical line.", "Bullet alpha wrapped bullet content", "Bullet beta", "Number gamma", "Number delta"], "tables": 0, "bullets": 2, "numbers": [3, 4]})

    xs, ys = [50, 180, 310, 440], [100, 140, 180, 220]
    values = [(r, c, f"R{r+1}C{c+1}") for r in range(3) for c in range(3)]
    for name, lines in (("ruled", grid(xs, ys)), ("broken-rule", grid(xs, ys).replace(rule(180, 140, 180, 180), rule(180, 140, 180, 158)+rule(180, 160, 180, 180)))):
        save(name, [text("BEFORE TABLE", 50, 60)+lines+cells(xs, ys, values)+text("AFTER TABLE", 50, 270)],
             {"body": ["BEFORE TABLE"]+[v for _, _, v in values]+["AFTER TABLE"], "tables": 1, "grid": [3, 3]})

    omitted = {( "v", 0, 2), ("h", 2, 0)}
    values = [(0, 0, "HEAD"), (0, 1, "MERGED HEADER"), (1, 0, "ROWSPAN"), (1, 1, "B2"), (1, 2, "C2"), (2, 1, "B3"), (2, 2, "C3")]
    save("merged", [grid(xs, ys, omitted)+cells(xs, ys, values)], {"body": [v for _, _, v in values], "tables": 1, "grid": [3, 3], "columnSpans": [2], "rowSpans": [2]})

    outerx, outery = [50, 300, 550], [100, 280]
    innerx, innery = [70, 170, 270], [160, 200, 240]
    inner = [(r, c, f"N{r+1}{c+1}") for r in range(2) for c in range(2)]
    content = grid(outerx, outery)+text("OUTER BEFORE", 60, 130)+grid(innerx, innery)+cells(innerx, innery, inner)+text("OUTER RIGHT", 320, 130)
    save("nested", [content], {"body": ["OUTER BEFORE"]+[v for _, _, v in inner]+["OUTER RIGHT"], "tables": 2, "nested": True})

    values = [("Name", "Count", "Total"), ("Alpha", "10", "25"), ("Beta", "20", "35"), ("Gamma", "30", "45")]
    content = text("WIRELESS DATA", 50, 70, 20)
    for r, row in enumerate(values):
        for x, value in zip((50, 200, 350), row, strict=True):
            content += text(value, x, 130+r*20)
    content += text("AFTER WIRELESS", 50, 260)
    save("wireless", [content], {"body": ["WIRELESS DATA"]+[v for row in values for v in row]+["AFTER WIRELESS"], "tables": 1, "grid": [4, 3]})

    header = "MERGED HEADER ACROSS TWO"
    content = text(header, 50, 110)+text("Total", 350, 110)
    for r, row in enumerate(values[1:]):
        for x, value in zip((50, 200, 350), row, strict=True):
            content += text(value, x, 130+r*20)
    save("wireless-merged", [content], {"body": [header, "Total"]+[v for row in values[1:] for v in row], "tables": 1, "grid": [4, 3], "columnSpans": [2]})

    pages, expected = [], []
    for page, ys in enumerate(([620, 660, 700, 740], [100, 140, 180, 220])):
        xs = [50, 200, 350]
        values = [(0, 0, "NAME"), (0, 1, "VALUE"), (1, 0, f"P{page+1}A"), (1, 1, str(page*10+1)), (2, 0, f"P{page+1}B"), (2, 1, str(page*10+2))]
        content = grid(xs, ys)+cells(xs, ys, values)
        pages.append(content)
        expected.extend([v for r, _, v in values if page == 0 or r != 0])
    save("continued", pages, {"body": expected, "tables": 1, "grid": [5, 2], "headerRows": 1, "continuation": True})

    content = ""
    expected = []
    left, right = [], []
    for r in range(4):
        a, b = f"Left prose contains several words row {r}.", f"Right prose contains several words row {r}."
        content += text(a, 35, 130+r*22, 10)+text(b, 330, 130+r*22, 10)
        left.append(a)
        right.append(b)
    save("aligned-prose", [content], {"body": left+right, "tables": 0, "uncertain": True})

    xs, ys = [50, 200, 350], [100, 140, 180]
    values = [(0, 0, "A1"), (0, 1, "B1"), (1, 0, "A2"), (1, 1, "B2")]
    save("explicit-override", [grid(xs, ys)+cells(xs, ys, values)], {"body": ["A1 B1\nA2 B2"], "tables": 1, "grid": [1, 1], "explicit": True})

    # An L-shaped open region cannot be represented by rectangular cell spans.
    omitted = {("v", 0, 1), ("h", 1, 0)}
    save("ambiguous-grid", [grid(xs, ys, omitted)+cells(xs, ys, values)], {"body": ["A1 B1", "A2 B2"], "tables": 0, "uncertain": True})
    (OUTPUT / "manifest.json").write_text(json.dumps({"files": records}, indent=2)+"\n", encoding="utf-8")
    print(json.dumps({"fixtures": len(records), "pages": sum(v["pages"] for v in records.values())}))


if __name__ == "__main__":
    main()

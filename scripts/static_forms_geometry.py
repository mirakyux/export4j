# SPDX-License-Identifier: Apache-2.0
"""Independent final-PDF vector control contour recognition."""
from verify_advanced_pdf import require
from verify_inline_images import compose
from verify_multi_floats import point


def writing_lines(reader):
    result = []
    for page_index, page in enumerate(reader.pages):
        matrix, stroke, stack, points = (1, 0, 0, 1, 0, 0), 1, [], []
        for operands, operator in page.get_contents().operations:
            if operator == b"q":
                stack.append((matrix, stroke))
            elif operator == b"Q":
                matrix, stroke = stack.pop()
            elif operator == b"cm":
                matrix = compose(matrix, tuple(map(float, operands)))
            elif operator == b"w":
                stroke = float(operands[0])
            elif operator in (b"m", b"l"):
                points.append(point(matrix, *map(float, operands)))
            elif operator in (b"c", b"re", b"h"):
                points.append(None)
            elif operator in (b"S", b"f", b"f*", b"n"):
                if operator == b"S" and len(points) == 2 and all(p is not None for p in points):
                    (x1, y1), (x2, y2) = points
                    if abs(y1-y2) < .003:
                        result.append(dict(page=page_index, left=min(x1,x2), right=max(x1,x2),
                                           y=float(page.mediabox.height)-y1, stroke=stroke))
                points = []
        require(not stack, "unbalanced writing line graphics")
    return result


def form_marks(reader):
    """Recognize filled square rings, tick polygons and four-cubic round rings in final PDF paths.
    Normalized contours are checked, rather than counting arbitrary text glyph paths as marks.
    """
    marks = []
    for page_index, page in enumerate(reader.pages):
        matrix, stack, paths, current = (1, 0, 0, 1, 0, 0), [], [], []
        height = float(page.mediabox.height)
        for operands, operator in page.get_contents().operations:
            if operator == b"q":
                stack.append(matrix)
            elif operator == b"Q":
                matrix = stack.pop()
            elif operator == b"cm":
                matrix = compose(matrix, tuple(map(float, operands)))
            elif operator == b"m":
                if current:
                    paths.append(current)
                x, y = point(matrix, *map(float, operands))
                current = [("m", x, height - y)]
            elif operator in (b"l", b"c"):
                values = list(map(float, operands))
                points = [point(matrix, values[i], values[i + 1]) for i in range(0, len(values), 2)]
                current.append((operator.decode(), *(v for x, y in points for v in (x, height - y))))
            elif operator == b"h":
                current.append(("h",))
            elif operator in (b"f", b"f*", b"S", b"n"):
                if current:
                    paths.append(current)
                if operator in (b"f", b"f*") and len(paths) in (2, 3):
                    outer, inner = paths[:2]
                    if [p[0] for p in outer] == ["m", "l", "l", "l", "h"] and len(inner) == 5:
                        xs = [p[1] for p in outer[:-1]]
                        ys = [p[2] for p in outer[:-1]]
                        left, top, size = min(xs), min(ys), max(xs) - min(xs)
                        expected = [(0, 0), (1, 0), (1, 1), (0, 1), (.08, .08), (.08, .92), (.92, .92), (.92, .08)]
                        coords = [(p[1], p[2]) for p in outer[:-1] + inner[:-1] if len(p) == 3]
                        if size > 0 and len(coords) == 8 and all(abs((x - left) / size - a) < .003
                                and abs((y - top) / size - b) < .003 for (x, y), (a, b) in zip(coords, expected, strict=True)):
                            selected = len(paths) == 3
                            if selected:
                                tick = paths[2]
                                require([p[0] for p in tick] == ["m", "l", "l", "l", "l", "l", "h"], "malformed static tick")
                                points = [(.16, .48), (.38, .70), (.84, .22), (.93, .31), (.38, .88), (.07, .57)]
                                require(all(abs((p[1] - left) / size - x) < .003 and abs((p[2] - top) / size - y) < .003
                                        for p, (x, y) in zip(tick[:-1], points, strict=True)), "selected tick geometry differs")
                            marks.append(dict(page=page_index, x=left, y=top, size=size, selected=selected, kind="checkbox"))
                    elif [p[0] for p in outer] == ["m", "c", "c", "c", "c", "h"] and len(inner) == 6:
                        right, cy = outer[0][1:]
                        cx, bottom = outer[1][-2:]
                        radius = right - cx
                        if radius > 0 and abs(bottom - cy - radius) < .003:
                            k = .5522847498307936
                            expected = [(1, 0), (1, k, k, 1, 0, 1), (-k, 1, -1, k, -1, 0),
                                        (-1, -k, -k, -1, 0, -1), (k, -1, 1, -k, 1, 0)]
                            good = all(len(p) - 1 == len(e) and all(abs((v - (cx if i % 2 == 0 else cy)) / radius - t) < .003
                                       for i, (v, t) in enumerate(zip(p[1:], e, strict=True))) for p, e in zip(outer[:-1], expected, strict=True))
                            inner_expected = [( .84, 0), (.84, -k * .84, k * .84, -.84, 0, -.84),
                                              (-k * .84, -.84, -.84, -k * .84, -.84, 0),
                                              (-.84, k * .84, -k * .84, .84, 0, .84), (k * .84, .84, .84, k * .84, .84, 0)]
                            good = good and all(len(p) - 1 == len(e) and all(abs((v - (cx if i % 2 == 0 else cy)) / radius - t) < .003
                                       for i, (v, t) in enumerate(zip(p[1:], e, strict=True))) for p, e in zip(inner[:-1], inner_expected, strict=True))
                            if good:
                                if len(paths) == 3:
                                    require(len(paths[2]) == 6 and abs((paths[2][0][1] - cx) / radius - .52) < .003, "malformed radio selection")
                                marks.append(dict(page=page_index, x=cx-radius, y=cy-radius, size=radius*2, selected=len(paths) == 3, kind="radio"))
                paths, current = [], []
            elif operator == b"re":
                paths, current = [], []
        require(not stack, "unbalanced static form graphics")
    return marks


# Static Form API

Static check marks, choice groups, labels/values and fill-in lines are in `export4j-components`. All lengths use points, with a top-left local-coordinate origin. Output supports PDF, SVG and Raster. These components do not generate editable fields or have DOCX semantic mappings. Use `Navigation` field entry points for editable forms.

## Authoring Forms

~~~java
var form = Documents.builder()
        .title("检查记录")
        .column(column -> column.gap(6)
                .formText("报告类型：☑ 首次报告  □ 跟踪报告")
                .choices(group -> group
                        .kind(ChoiceGroup.Kind.RADIO)
                        .option("通过", true)
                        .option("需复核", false))
                .labelValue("病例编号", "DEMO-001",
                        field -> field.labelWidth(72).gap(8))
                .fillLine(line -> line.height(18).bottomInset(3)))
        .build();
~~~

The required project types are `Documents` and `ChoiceGroup`; export still supplies fonts and a backend explicitly. See [StaticFormsExample](../../examples/src/main/java/com/mirakyux/export4j/examples/StaticFormsExample.java) for a runnable sample and [AdrFormExample](../../examples/src/main/java/com/mirakyux/export4j/examples/AdrFormExample.java) for a dense Chinese form. Both use fictional data.

## Component Contracts

| Type | Behavior and entry points |
| --- | --- |
| `FormText` | Retains ordinary text; recognizes `□`/`☑` as unchecked/checked boxes and `○`/`●` as unselected/selected radio dots; `builder().append(...).checkbox(...).radio(...)` builds them directly |
| `ChoiceGroup` | `.option(label, selected)` declares options; defaults to checkboxes; radio groups allow at most one selected option; `.vertical(true)` starts each option on a new line |
| `CheckMark` | Independent fixed-size vector mark with `.kind(...)`, `.selected(...)` and `.size(...)`; defaults to a 9 pt square |
| `LabelValue` | A fixed `.labelWidth(...)` left column; the right column wraps after deducting `.gap(...)`; consumed labels do not repeat on continuation pages; defaults are 72 pt/8 pt |
| `FillLine` | Draws a fill-in line across the viewport width; defaults are height 18 pt, bottom margin 3 pt and stroke width 0.5 pt; the stroke stays entirely inside the viewport |

`FormText`, `ChoiceGroup` and `LabelValue` continue by complete lines across pages or columns, skipping a short first area that cannot fit one line. Horizontal choice groups wrap as ordinary inline text, including within long labels; moving each option as an indivisible unit is not promised. Radio groups can have no default selection; multiple defaults fail explicitly.

The four marker characters in `FormText` are reserved syntax. Use `FlowText` for ordinary font dots. `ChoiceGroup` labels cannot contain those markers, preventing labels from becoming additional choices. Other characters still use explicit font groups; missing-glyph errors or placeholder-warning policies remain in effect.

Form text follows the shared OpenType path, supporting bidirectional text, combining characters and UTF-16 diagnostics. Squares/dots do not request font glyphs. Marks reserve a width equal to font size, with a visible size of 72% of font size. Text, marks and font groups share a fixed line box; insufficient space fails explicitly without automatic font shrinking. The Arabic-font example explicitly uses larger line height.

## Merged Cells and Fixed Regions

~~~java
var options = FormText.builder("☑ 第一项\n□ 第二项\n☑ 第三项").build();
var table = GridTable.builder().columns(1, 3).allowRowSplit(true)
        .row(row -> row.cell("说明", cell -> cell.rowSpan(2))
                .cell(options, cell -> cell.rowSpan(2)))
        .row(row -> { })
        .build();
~~~

`FormText` and `ChoiceGroup` participate in the standard merged-cell preparation flow, measuring once and continuing by complete lines. Every fragment retains borders, padding and repeating headers; marks and short-cell labels do not repeat. Default `allowRowSplit(false)` still moves the entire merged-row group together.

Independent `CheckMark` and `FillLine` are atomic components. Fixed page regions accept only one fragment; overheight `FormText` fails explicitly. No support is added for arbitrary custom `RegionFlow` or automatic merged-cell splitting with embedded `LabelValue`. The standard child-component boundaries in the [continuation guide (Chinese)](../grid-continuation-api.md) still apply.

## Layout Extension and Restrictions

The text module adds the neutral value `InlineGraphic(width, ascent, descent, ink)`. Layout entry points accept an explicit graphics map keyed by UTF-16 positions of original U+FFFC characters. Graphic positions cannot overlap images/fields, split glyph clusters or cross style boundaries. Outlines are positioned during measurement; backends draw them directly without deciding line breaks again.

Local outline control points must stay within reserved dimensions. Each graphic permits at most 10,000 path segments and 1,024 logical UTF-16 units; each text run permits at most 1,024 graphics. Total outline segments remain subject to the existing 2,000,000-segment budget. Logical text rejects invalid Unicode and control characters. Explicitly injected simple shapers reject inline graphics; only the default shared layout entry point supports them.

`InlineGraphic` adds no semantic-model type. Static selection state enters logical text, but this does not establish universal reader search/selection compatibility or PDF/UA. Diagnostics for fonts and actual labels still use original UTF-16 positions without exposing internal library logs.

Positioned `TableComponent` is only for single-viewport static tables. Its constructor rejects fields, text/image external links and named font roles to avoid lost context or silently disappearing links. Use top-level semantic `Table` for those features. This early rejection was introduced before candidate release and is the current behavioral contract. Ordinary rich text, images without external links and single-page size constraints remain supported.

See [ADR-0020 (Chinese)](../adr/0020-static-form-components.md) for the decision and [Phase 16 (Chinese)](../verification/phase-16-static-forms.md) for verification.

# Document themes and named styles

Themes support a global font role and color, with separate overrides for body text, six heading levels, cells, headers and footers. XML appearance values not declared locally inherit from the theme; existing [TextDefaults (Chinese)](../xml-text-defaults-api.md) remain complete explicit overrides. See [ADR-0068 (Chinese)](../adr/0068-theme-text-appearance.md) and [phase 60 output evidence (Chinese)](../verification/phase-60-theme-appearance.md).

`DocumentTheme` puts reusable body, six-level heading and semantic-table formats in one immutable value. It can also store complete named paragraph and inline styles. Values resolve during authoring; the resulting `Document` retains neither theme names nor later lookups. See [ADR-0037 (Chinese)](../adr/0037-authoring-theme-snapshots.md) and [phase 30 local evidence (Chinese)](../verification/phase-30-authoring-themes.md).

## Define once and reuse report structure

~~~java
var theme = DocumentTheme.builder()
        .bodyFormat(format -> format.fontSize(11).lineHeight(20).spaceAfter(6))
        .headingFormat(1, format -> format.fontSize(18).lineHeight(28))
        .cellFormat(format -> format.fontSize(10).lineHeight(18))
        .tableFormat(format -> format.padding(4).spaceAfter(8))
        .paragraphStyle("note", format -> format.fontSize(10).lineHeight(18))
        .inlineStyle("emphasis", style -> style.fontRole("bold").rgb(0x224488).underline(true))
        .build();

var report = Documents.builder()
        .theme(theme)
        .heading("季度报告", 1)
        .paragraph("普通正文使用主题默认值。")
        .paragraph("数据来自合成样例。", theme.paragraphStyle("note"))
        .paragraph(List.of(
                new InlineText("结论：", theme.inlineStyle("emphasis")),
                new InlineText("已完成复核。", InlineStyle.of(11))))
        .table(table -> table.header("项目", "结果").row("复核状态", "通过"))
        .column(column -> column
                .text("组件承接正文的排版默认值。")
                .text("这一行明确使用 12 pt。", text -> text.fontSize(12)))
        .build();
~~~

Use `Document.builder().theme(theme)` for purely semantic documents. `Documents` adds components at the same entry point and forwards rich paragraphs, headings and lists. In the example, `bold` is only a font-role name: register an explicitly loaded real font with `DocumentEngine.Builder.fontRole`. Themes neither load fonts nor access resources implicitly. Without appearance configuration, the explicit default font group and black remain in use; set global appearance with `.fontRole(...)` / `.rgb(...)`.

The default theme uses the same existing defaults as convenience entry points without a theme. Unconfigured heading levels use `Heading.defaultFormat(level)`. Unless a cell format is explicitly set, `build()` derives `CellFormat` from the final body's size, line height, alignment and line-break policy.

## Font role and color cascade

```java
var brand = DocumentTheme.builder().fontRole("brand").rgb(0x224488)
        .headingRgb(1, 0x884422).cellFontRole("regular").footerRgb(0x666666).build();
var revised = brand.toBuilder().bodyRgb(0).inheritCellFontRole().build();
```

`TextAppearance(fontRole, rgb)` is a complete value containing no geometry or resources. An empty role selects the explicit default font group; other roles match `[A-Za-z_][A-Za-z_0-9]{0,255}`. RGB is 24-bit. `text()`, `bodyText()`, `headingText(level)` and `cellText()` return resolved values; `style(size)` produces a complete undecorated InlineStyle. The theme does not load fonts.

Properties override in global → domain → local order. Domains are body, cell, heading levels 1–6, header and footer; appearance does not propagate between domains. Call methods such as `bodyFontRole` / `bodyRgb` separately; heading methods also take a level. `inheritBodyFontRole` / `inheritBodyRgb` and their other-domain counterparts clear only one declaration and preserve the other property. Empty roles and black block the corresponding global property; `toBuilder()` and equality retain this intent.

String paragraphs, headings, convenience tables and list bodies use the current domain appearance. Convenience list markers use the body domain too. Complete InlineContent, TableCell and `.add(Block)` retain their explicit values. `Table.Builder.textAppearance(value)` affects only subsequently added string rows, not existing rows or complete cells.

Components receive body defaults through outer StyleScope / FontRoleScope values. Local colors and inner font scopes take precedence. Complete rich runs retain size, color and named roles; an empty role uses the current scope's default font group under the existing component contract. `.bodyFormat(...)` changes geometry only, preserving appearance; another `.theme(...)` replaces defaults for subsequent content.

Header/footer geometry remains independent. Undeclared appearance inherits global values; a complete `headerStyle` / `footerStyle` or its callback fixes both appearance properties. A callback starts from the currently resolved values, and later global changes do not update that snapshot. Individual inherit methods can restore inheritance. Named InlineStyle values remain complete and do not cascade automatically. Background/foreground and editable-field themes have separate definitions.

## Complete values and snapshots

Paragraph, table, cell and inline styles in a theme are complete values. Builders accept either a complete value or a synchronous callback. They save the built result immediately when the callback returns and retain no child builder. Themes and name maps can be shared across tasks; builders are task-confined.

A new named paragraph starts from the body at definition time. A new named inline style starts from `InlineStyle.of(size)` using the body size at definition time. Reconfiguring a name starts from its current value. Later body changes do not rewrite existing named styles; `toBuilder()` creates an independent builder.

For example, an 11 pt `emphasis` stays 11 pt inside an 18 pt heading. Explicitly derive another size with `theme.inlineStyle("emphasis").toBuilder().fontSize(18).build()`. Sizes, black, zero shifts and empty roles all have explicit meaning; default numbers are not treated as “unset”.

The explicitness of cell formats is retained too. Without `.cellFormat(...)`, the cell format follows the theme's final body. Once a complete value or callback is supplied, that value remains independent of later body changes. `toBuilder()` preserves the choice, so explicit and implicit cell formats can produce unequal themes even when their current numeric values match.

Names are case-sensitive and must match `[A-Za-z_][A-Za-z_0-9-]{0,63}`. Paragraph and inline maps each allow at most 128 distinct names; updating an existing name does not increase the count. Invalid, excessive or unknown names fail immediately; unknown names do not fall back to body styles.

## Overrides and call order

| Content | Selected format |
| --- | --- |
| Convenience body paragraphs and lists | Complete body snapshot from the last `.theme(...)` or subsequent `.bodyFormat(...)` |
| Convenience headings | Current theme's complete format for the level, retaining unchanged pagination policies |
| Convenience semantic tables | Theme cell/table defaults first, then the caller's table callback |
| `.add(Block)`, prebuilt cells or tables | Complete supplied values |
| Explicit `InlineStyle` | Complete size, color, role, shift and decoration |
| New components | Current body's six text-layout properties, color and default font group; local defaults and explicit child properties still take precedence |

`.bodyFormat(...)` affects subsequently added body paragraphs, lists, convenience cells and component layout only. It does not change headings, table padding or named styles. Another `.theme(...)` replaces theme defaults for subsequent content without modifying blocks already added.

~~~java
var document = Documents.builder()
        .theme(themeA)
        .paragraph("使用 A 的正文。")
        .bodyFormat(format -> format.fontSize(12).lineHeight(22))
        .paragraph("随后使用 12 pt／22 pt。")
        .theme(themeB)
        .paragraph("随后使用 B 的正文。")
        .build();
~~~

Components receive only size, fixed line height, first/subsequent-line indentation, alignment and line-break policy. Paragraph spacing and adjacent-block pagination policies still belong to semantic blocks. Nested `StyleScope`, grid-cell defaults and explicit child properties follow the [style-default rules (Chinese)](../style-defaults-api.md). Methods such as `.inheritFontSize()` restore defaults from the actual measurement context without looking up theme names. Component-grid borders, backgrounds, padding and header appearance remain explicit; semantic `TableFormat` does not apply to `GridTable`.

## Output and scope

Themes resolve to existing models during authoring; PDF, SVG and DOCX keep their respective output contracts. DOCX supports the semantic subset and explicitly rejects components. Converting PDF components into ordinary tables is not lossless export. Standard OOXML expands implicit text in ordinary paragraphs into one explicit default run; round-trip comparisons must distinguish equivalent representations from real formatting loss.

Java templates can use model prototypes built from themes; binding replaces data without reapplying themes. Single-pass long tables can explicitly take `theme.cellFormat()` and named `InlineStyle` values. The initial theme batch did not include XML/HTML/CSS name selection, layout-time cascading, global default colors or font roles, page themes, editable-field styles or brand-resource management; it did not close all of GAP-04. The global/domain appearance support and later XML integration described in this guide have separate evidence.

Theme changes to font size, line height or padding can change page counts. Fixed line boxes, complete lines and pagination progress are still checked. Unknown roles, missing-glyph policies and diagnostic provenance retain their contracts. Font variants are not synthesized, content is not shrunk and line heights are not enlarged automatically.

## Examples and independent verification

[ThemeExample](../../examples/src/main/java/com/mirakyux/export4j/examples/ThemeExample.java) generates blue and compact themes from the same report function and compares no theme with the default theme. Semantic pages cover six heading levels, body text, named notes, real bold emphasis, convenience tables and explicit 12 pt cells. Component pages cover local defaults, explicit black/zero indentation, restored inheritance and snapshots for subsequent content.

This example defines its theme in 18 nonempty physical lines, its semantic report in 25 lines and its component page in 23 lines, including method declarations and braces. The semantic report keeps five concrete-format setter calls; size 12 and line height 20 each appear twice to prove that explicit cells and paragraphs are independent of the theme. Heading, paragraph and table structure stays directly in the report function. These counts illustrate reuse in this example, not an overall usability ranking. The component page deliberately demonstrates overrides, retaining 15 concrete-format setters and five repeated expressions; demonstration line counts are not the shortest everyday form.

~~~powershell
.\gradlew.bat :examples:themes
build/verification/python-phase13/Scripts/python.exe scripts/verify_themes.py
~~~

The independent script checks actual PDF font sizes, colors, line heights, indents, decorations and cell geometry; SVG source text, outline sizes and colors; and native DOCX paragraph formats, heading levels, font roles and table padding. No-theme/default-theme comparisons use actual drawing values rather than PDF bytes. All sample pages are rendered with Poppler and PDFium and reviewed individually; visual baselines are not refreshed automatically. The isolated consumer reads the successful-build directory pointer by default, or accepts `--consumer-build-directory`.

The XML entry point passed [phase 31 (Chinese)](../verification/phase-31-xml-themes.md), supporting the same DocumentTheme and static p/span names. See the [XML theme guide (Chinese)](../xml-themes-api.md) for independent scopes and overrides.

## Page-layer defaults

`backgroundStyle` / `foregroundStyle` supply independent complete text defaults for [fixed page layers (Chinese)](../page-overlays-api.md). Initial geometry is 10/15 pt; undeclared appearance inherits the global values. Named fontRole/rgb setters and corresponding inherit methods change one property; complete values/callbacks fix both, and toBuilder retains intent. Local p/span values take precedence; tables still use the cell domain. See [phase 62 (Chinese)](../verification/phase-62-page-overlays.md).

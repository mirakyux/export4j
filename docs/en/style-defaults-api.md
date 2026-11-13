# Component Default Style API

`Documents.bodyFormat` provides consistent typography for subsequently added text components, nested columns, grid text and static forms. Properties left undeclared by a component Builder inherit; explicit declarations always take precedence, including an explicit 12 pt, zero indent or black. See [Phase 19 (Chinese)](../verification/phase-19-style-defaults.md) for historical acceptance of complete-style inheritance and [ADR-0023 (Chinese)](../adr/0023-component-style-defaults.md) for its design. Property-level override rules are covered in [ADR-0030 (Chinese)](../adr/0030-property-text-inheritance.md) and [Phase 23 verification (Chinese)](../verification/phase-23-property-styles.md).

## Setting Body Defaults

~~~java
var document = Documents.builder()
        .bodyFormat(format -> format.fontSize(10).lineHeight(20))
        .column(column -> column.gap(6)
                .text("情况说明")
                .column(inner -> inner.text("嵌套内容"))
                .grid(grid -> grid.columns(1, 3)
                        .row(row -> row.cells("编号", "DEMO-001")))
                .formText("☑ 已核对 □ 需补充")
                .choices(group -> group.option("通过", true).option("暂缓", false))
                .labelValue("备注", "这是合成样例")
                .text("显式小标题", text -> text.style(ParagraphStyle.of(12))))
        .build();
~~~

`bodyFormat` is snapshotted when content is added. Later calls do not modify earlier content. `.add(Block)` retains an already-built semantic block's original formatting without reinterpreting its intent. Prebuilt Builder components added through `.component(...)` can still inherit because their immutable values retain unset-state markers.

Properties mapped to components are font size, fixed line height, first-line indent, subsequent-line indent, alignment and line-breaking policy. Before/after paragraph spacing, `keepTogether` and block-adjacency policies remain managed by semantic paragraphs and are not mapped to component properties. Headings, contents and already-built semantic tables retain their explicit formatting. Semantic table convenience methods still use body-cell formatting under existing `Document.Builder` rules.

## Local Scopes

`StyleScope` provides defaults to its descendants. Nested scopes override only properties or colors they declare. Named setters declare only the corresponding property; complete `.style(...)` still declares the entire paragraph style. Fonts remain explicitly controlled through `FontScope`; the two scopes can be interleaved.

~~~java
var section = StyleScope.builder(FlowColumn.builder()
        .text("此文字继承外层字号并使用红色")
        .formText("☑ 核对完成")
        .text("此文字固定为黑色", text -> text.color(RgbColor.BLACK))
        .build())
        .color(new RgbColor(1, 0, 0))
        .build();
~~~

Direct layout can also supply defaults through `ComponentContext.withTextDefaults(style, color)`. Custom components must read defaults themselves and pass the original context to children; the library cannot infer unset properties inside arbitrary user components.

## Overrides and Restoring Inheritance

| Entry point | Behavior |
| --- | --- |
| `FlowText.builder("内容").build()` | Style and color are both unset and inherit from the current scope; without a scope, defaults are 12 pt, 18 pt line height and black |
| `.fontSize(12)` | Overrides only font size; line height, indents, alignment, line-breaking policy and color continue to inherit |
| `.style(ParagraphStyle.of(12))` | Explicitly fixes the complete style at 12 pt; color can still inherit |
| `.color(RgbColor.BLACK)` | Explicitly fixes color at black; the complete style can still inherit |
| Property-level reset methods such as `.inheritFontSize()` | Clear only that declaration; the other five properties in a complete style remain explicit |
| `.inheritStyle()`/`.inheritColor()` | Clear all typography declarations/the color declaration and restore inheritance at layout time |
| `.toBuilder()` | Retains original unset/explicit state, so changing content does not accidentally fix default values |
| Legacy direct constructors | Supplied style and color are explicit; intent is never inferred from whether a value equals a default |

`.style(callback)` retains its existing complete-explicit-style semantics. For example, `.style(s -> s.alignment(TextAlignment.CENTER))` on a new text Builder uses local 12 pt/18 pt values before fixing alignment to center. To override alignment alone, use `.alignment(TextAlignment.CENTER)` directly. The callback executes immediately; output retains neither the callback nor a mutable child Builder.

## Setting Individual Properties

Use named methods directly when other parent properties should remain inherited:

~~~java
var document = Documents.builder()
        .bodyFormat(format -> format.fontSize(10).lineHeight(24).firstLineIndent(18))
        .column(column -> column
                .text("小标题", text -> text.fontSize(14))
                .text("右对齐正文", text -> text.alignment(TextAlignment.RIGHT))
                .text("恢复父级字号", text -> text.fontSize(14).inheritFontSize()))
        .build();
~~~

The first item changes only font size, retaining the parent's 24 pt fixed line height and 18 pt first-line indent. Font size does not silently enlarge an inherited line box; insufficient space fails explicitly. Unlike the local default-line-height behavior of `ParagraphStyle.Builder.fontSize`, partial settings on text components use the actual layout context as their base.

| Individual declaration | Restore inheritance for that property |
| --- | --- |
| `fontSize(double)` | `inheritFontSize()` |
| `lineHeight(double)` | `inheritLineHeight()` |
| `firstLineIndent(double)` | `inheritFirstLineIndent()` |
| `subsequentLineIndent(double)` | `inheritSubsequentLineIndent()` |
| `alignment(TextAlignment)` | `inheritAlignment()` |
| `lineBreakPolicy(LineBreakPolicy)` | `inheritLineBreakPolicy()` |

These six method pairs apply consistently to seven text-component types, `StyleScope` and `GridTable.CellBuilder`. All four lengths use points. Indents must be nonnegative; font size and line height must be positive finite values. Zero indent, 12 pt, `LEFT` and `STANDARD` are also explicit declarations. Explicit child properties take precedence over cell/scope defaults, while undeclared properties continue inheriting through the hierarchy. `StyleScope.builder(child).fontSize(14)` changes only descendant default font size; explicitly set descendant font sizes still take precedence.

Call order has explicit meaning: after `.style(ParagraphStyle.of(12)).fontSize(14)`, the other five properties remain explicit; only a subsequent `.inheritLineHeight()` restores parent line height. `.inheritStyle()` clears six declarations but leaves independent color untouched. `toBuilder()` retains property-level state. Legacy `.style(callback)` remains a complete override rather than becoming a partial cascade. See [ADR-0030 (Chinese)](../adr/0030-property-text-inheritance.md); the runnable entry point is `:examples:propertyStyles`.

## Grids and Continuation

`GridTable.defaults(cell -> cell.textStyle(style).textColor(color))` snapshots defaults when rows/cells are added. Cell Builders also support the same six named setters and property-level resets. For example, `.defaults(cell -> cell.fontSize(11))` retains outer line height; `textStyle(style)` remains a complete override. Local cell settings take precedence over grid defaults. `inheritTextStyle()`/`inheritTextColor()` clear all cell typography/color declarations. Cell settings pass as descendant defaults to standard string cells, custom-component cells and `textFactory` results; explicitly configured child text still takes precedence.

Inheritance resolves before measurement. Line heights, header heights, text colors and floating/wrapped grids use resolved values; continuation pages and backends do not decide styles again. `StyleScope` preserves short first areas, full continuation areas, semantic slots and diagnostics.

Later `.defaults(...)` calls do not rewrite earlier rows. Children produced by `textFactory` still prioritize their own declarations.

## Support and Restrictions

This work covers `FlowText`, `TextComponent`, `OpenTypeText`, `FormText`, `ChoiceGroup`, `LabelValue` and `PageNumberComponent`, plus columns, rows, grids, multicolumn flows and floating/wrapping layouts combining them. Headers/footers can explicitly contain `StyleScope`; body `bodyFormat` does not modify independent page templates.

Already-built `Table`/`TableComponent`/`SemanticTable` values have complete semantic formatting and retain it. Editable AcroForm fields, independent vector marks, borders, backgrounds, images, page templates and contents are not automatically reset by text defaults. DOCX still uses the semantic model; nested-component export has not been added.

Insufficient fixed line boxes, missing glyphs, invalid dimensions and pagination that cannot advance continue to fail explicitly. Missing-glyph policies, actual font variants, UTF-16 source positions and structured diagnostics retain their original contracts. `TextInheritance` retains `NONE`/`ALL` and its two-argument constructor, adding an immutable `overrides()` property set. `style()` uses the parent style as its base and local values for properties in that set. Legacy component constructor calls remain source-compatible. Legacy `TextInheritance` record deconstruction patterns need the added component; reflected components, `toString` and hashes also change. These changes were introduced before candidate release. See [Java API and version compatibility (Chinese)](../api-compatibility.md) for declared compatibility requirements of the currently released `0.1.0-rc.2`.

Equality compares declaration structure; components with identical values but different inheritance intent are unequal. Local values retained after restoring inheritance still participate in equality. `style()`/`color()` return configured values; effective values resolve in a concrete layout context.

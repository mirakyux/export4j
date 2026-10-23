# 文档主题与命名样式 API

主题支持全局字体角色与颜色，以及正文、六级标题、单元格和页眉／页脚的分域覆盖。XML 未声明的外观继承主题，原有 [TextDefaults](xml-text-defaults-api.md) 仍是完整显式覆盖。设计见 [ADR-0068](adr/0068-theme-text-appearance.md)，输出证据见[阶段六十](verification/phase-60-theme-appearance.md)。

`DocumentTheme` 将反复使用的正文、六级标题和语义表格格式放进一个不可变值，也可保存具名段落、行内完整样式。编写文档时解析这些值，生成的 `Document` 不保留主题名称或后续查询。决策见 [ADR-0037](adr/0037-authoring-theme-snapshots.md)，本地证据见[阶段三十验收](verification/phase-30-authoring-themes.md)。

## 定义一次，复用报告结构

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

纯语义文档使用 `Document.builder().theme(theme)`。`Documents` 在同一入口加入组件，并转发富文本段落、标题和列表。示例中的 `bold` 只是字体角色名，仍须在 `DocumentEngine.Builder.fontRole` 注册调用方显式加载的真实字体；主题不加载字体或隐式访问资源。未配置外观时仍使用默认字体和黑色；全局外观通过 `.fontRole(...)`／`.rgb(...)` 设置。

默认主题与不配置主题的便利入口使用同一组旧默认值。未设置的标题级别沿用 `Heading.defaultFormat(level)`；未显式设置格格式时，主题在 `build()` 时从最终正文的字号、行高、对齐与断行规则派生 `CellFormat`。

## 字体角色与颜色级联

```java
var brand = DocumentTheme.builder().fontRole("brand").rgb(0x224488)
        .headingRgb(1, 0x884422).cellFontRole("regular").footerRgb(0x666666).build();
var revised = brand.toBuilder().bodyRgb(0).inheritCellFontRole().build();
```

`TextAppearance(fontRole, rgb)` 是不含几何与资源的完整值。空角色选择显式默认字体组，其他角色匹配 `[A-Za-z_][A-Za-z_0-9]{0,255}`；RGB 为 24 位。`text()`、`bodyText()`、`headingText(level)` 和 `cellText()` 返回已解析值，`style(size)` 可生成完整无装饰 InlineStyle。主题不加载字体。

全局 → 分域 → 局部按属性覆盖。域包含 body、cell、1～6 级 heading、header 和 footer；域之间不传递外观。分别调用 `bodyFontRole`／`bodyRgb` 等方法，标题方法另带 level。`inheritBodyFontRole`／`inheritBodyRgb` 及其他域对应方法只清除单项声明，另一属性保持。空角色和黑色会阻断相应全局属性；`toBuilder()` 与相等性保留这一意图。

字符串段落、标题、便利表格和列表正文采用当前分域外观，便利列表标记也采用正文域。完整 InlineContent、TableCell 和 `.add(Block)` 保持显式值。`Table.Builder.textAppearance(value)` 只影响随后加入的字符串行，不改已加入行或完整格。

组件通过外层 StyleScope／FontRoleScope 采用正文默认。局部颜色和内层字体作用域优先，富文本完整运行的字号、颜色及具名角色保持；其空角色按组件原契约使用当前作用域默认字体组。`.bodyFormat(...)` 只改几何，保留外观；再次 `.theme(...)` 替换随后内容的默认值。

页眉／页脚原有几何仍独立。未声明外观继承全局值；完整 `headerStyle`／`footerStyle` 或其回调固定两个外观属性。回调从当前解析值开始，随后更改全局值不回写该快照；单项 inherit 方法可重新启用继承。命名 InlineStyle 继续是完整值，不自动级联。背景／前景及可编辑字段主题另行定义。

## 完整值与快照

主题中的段落、表格、格和行内样式都是完整值。Builder 提供完整值和同步回调两种重载；回调结束后立即保存构建结果，不保留子 Builder。主题与名称 Map 可跨任务共享，Builder 只在当前任务内使用。

新具名段落以定义当时的正文为基底，新具名行内样式以定义当时的正文字号生成 `InlineStyle.of(size)`。再次配置同名样式时，以当前同名值为基底。随后修改正文不会回写既有具名样式；`toBuilder()` 创建独立 Builder。

例如，11 pt 的 `emphasis` 放进 18 pt 标题后仍为 11 pt。需要另一尺寸时，显式派生 `theme.inlineStyle("emphasis").toBuilder().fontSize(18).build()`。字号、黑色、零偏移和空字体角色均有明确含义，不用默认数值猜测“尚未设置”。

格格式的显式性同样保留。未调用 `.cellFormat(...)` 时，它跟随主题最终正文；一旦设置完整值或回调，就保持该值，后续改正文不影响它。`toBuilder()` 保留这一选择，因此显式与隐式格格式即使当前数值相同，也可能不是相等的主题值。

名称区分大小写，须匹配 `[A-Za-z_][A-Za-z_0-9-]{0,63}`。段落、行内样式 Map 各最多 128 个不同名称；同名更新不增加数量。非法、超额或未知名称立即失败，未知名称不会回退到正文。

## 覆盖与调用顺序

| 内容 | 采用的格式 |
| --- | --- |
| 便利正文、列表 | 最后一次 `.theme(...)` 或其后 `.bodyFormat(...)` 的完整正文快照 |
| 便利标题 | 当前主题对应级别的完整格式，保留未改的分页策略 |
| 便利语义表格 | 先初始化主题格／表格式，再执行调用方表格回调 |
| `.add(Block)`、已构建的格或表 | 保留传入的完整值 |
| 显式 `InlineStyle` | 保留完整字号、颜色、角色、偏移和装饰 |
| 新组件 | 承接当前正文六项文字属性、颜色与默认字体组，局部默认值及子组件显式属性继续优先 |

`.bodyFormat(...)` 只影响随后加入的正文、列表、便利格和组件排版，不改标题、表格 padding 或具名样式。再次 `.theme(...)` 替换随后内容的主题默认值，不修改此前加入的块。

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

组件只接收字号、固定行高、首行／续行缩进、对齐及断行规则，段间距与邻接分页策略仍属于语义块。嵌套 `StyleScope`、网格格默认值和子组件显式属性沿用[样式默认值规则](style-defaults-api.md)。`.inheritFontSize()` 等方法恢复实际测量上下文中的默认值，不查找主题名称。组件网格的边框、背景、内边距和表头外观仍显式配置，语义 `TableFormat` 不套到 `GridTable`。

## 输出与范围

主题在编写时已解析为既有模型，PDF、SVG 和 DOCX 沿用各自输出契约。DOCX 支持的是语义子集；组件仍明确拒绝，不能把 PDF 组件转成普通表格后称为无损导出。标准 OOXML 会把普通段落的隐式文字展开为一个显式默认运行，往返核对须区分等价表示与真实格式丢失。

Java 模板可使用按主题构建的模型原型；绑定只替换数据，不再次套主题。单向长表可显式取 `theme.cellFormat()` 和具名 `InlineStyle`。本批没有 XML／HTML／CSS 名称选择、布局时级联、全局默认颜色或字体角色、页面主题、可编辑字段样式及品牌资源管理，不据此关闭整个 GAP-04。

主题改变字号、行高或内边距可能改变页数，仍执行固定行框、整行和分页推进校验。未知字体角色、缺字策略及诊断来源保持原契约，不合成字体变体、缩小内容或自动扩大行高。

## 示例与独立复核

[ThemeExample](../examples/src/main/java/com/mirakyux/export4j/examples/ThemeExample.java)用同一报告函数生成蓝色、紧凑两套主题，同时比较无主题与默认主题。语义页覆盖六级标题、正文、具名备注、真实粗体强调、便利表格和显式 12 pt 格；组件页覆盖局部默认值、显式黑色／零缩进、恢复继承及后续内容快照。

此样例的主题定义为 18 个非空物理行，语义报告编写为 25 行，组件页为 23 行，均包含方法声明和括号。语义报告保留 5 次具体格式 setter，其中字号 12 和行高 20 各重复一次，用来证明显式格与显式段落独立于主题。标题、段落和表格结构直接留在报告函数中；这说明本例的复用方式，不作为总体便利度的排名依据。组件页专门演示局部覆盖，保留 15 次具体格式 setter、5 次相同表达式的重复，不能把演示代码行数当作最短日常写法。

~~~powershell
.\gradlew.bat :examples:themes
build/verification/python-phase13/Scripts/python.exe scripts/verify_themes.py
~~~

独立脚本检查最终 PDF 实际字号、颜色、行距、缩进、装饰与格几何，检查 SVG 原文／轮廓尺寸／颜色，以及 DOCX 原生段落格式、标题级别、字体角色和表格内边距。无主题与默认主题比较实际绘制值，不比较 PDF 文件字节。全部小样页由 Poppler 与 PDFium 渲染，并逐页复查；不自动刷新视觉基线。隔离调用方默认读取成功构建目录指针，也可传 `--consumer-build-directory`。

XML 入口已通过[阶段三十一验收](verification/phase-31-xml-themes.md)，支持同一 DocumentTheme 和 p／span 静态名称；独立作用域及覆盖规则见 [XML 主题指南](xml-themes-api.md)。

## 页面图层默认值

`backgroundStyle`／`foregroundStyle` 为[固定页面图层](page-overlays-api.md)提供独立的完整文字默认值，初始几何 10／15 pt，未声明外观继承全局。具名 fontRole／rgb 和对应 inherit 方法只改一项，完整值／回调固定两项，toBuilder 保留意图。局部 p／span 优先；表格仍采用 cell 域。证据见[阶段六十二](verification/phase-62-page-overlays.md)。

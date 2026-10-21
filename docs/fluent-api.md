# 链式构建 API

报告结构也可用[声明式 XML 模板](xml-template-api.md)表达；`XmlTemplate.builder()` 链式配置页面、默认格式、资源白名单及两层预算，编译后复用现有语义模板和导出链。

公共编写和配置入口统一采用 `builder()`、具名设置、`build()`。嵌套结构使用同步配置回调。普通语义报告仍可使用 `Document.builder()`；混合组件报告使用 `Documents.builder()`，二者最终都生成同一个 `Document`。

完整密集表单见 [`AdrFormExample`](../examples/src/main/java/com/mirakyux/export4j/examples/AdrFormExample.java)。本指南中的长度均为 point，页面与组件采用左上角原点。字体、资源和后端仍由调用方显式提供。

## 编写结构

~~~java
var document = Documents.builder()
        .title("检查报告")
        .pageSettings(page -> page.size(595.3, 841.9).margins(36))
        .heading("基本信息", 1)
        .column(column -> column.gap(8)
                .text("病例编号：DEMO-001")
                .grid(table -> table.columns(1, 2, 2)
                        .defaults(cell -> cell.padding(4))
                        .row(row -> row.minHeight(32)
                                .cell("患者", cell -> cell.rowSpan(2))
                                .cells("姓名", "年龄"))
                        .row(row -> row.cells("示例患者", "75 岁"))))
        .pageBreak()
        .paragraph("补充说明。")
        .build();
~~~

嵌套回调在当前线程执行。调用方不用计算单元格起始行列，也不用给普通组件填写块 ID。需要引用的组件使用 `.component("patient", component)` 显式命名。底层扩展可以使用 `Document.Builder.block(id -> ...)` 获得自动 ID；工厂必须保留收到的 ID。

`Documents.Builder` 是组件层便利入口，不增加文档类型，不将组件依赖引入语义模型。高级语义类型仍可通过 `.add(block)` 加入；分页、表格、混合内容遍历等原有契约继续适用。

列内 `.text()` 按完整行自然续页，配置使用 `FlowText.Builder`；需要最小高度或整块移动时使用 `.fixedText()`。`.grid()` 明确表达组件合并表格，列内旧 `.table()` 保留兼容；顶层 `.table()` 继续表达语义表格。迁移与边界见[续页指南](grid-continuation-api.md)。

静态表单可直接使用 `.formText(...)`、`.choices(...)`、`.labelValue(...)` 和 `.fillLine(...)`。标记使用矢量，不要求字体包含勾选框字形；详细编写与续页边界见[静态表单指南](static-forms-api.md)。`Documents.bodyFormat` 为随后加入的组件提供排版默认值，未设置属性在嵌套中继承，显式样式优先；局部作用域、快照和完整样式覆盖规则见[默认样式指南](style-defaults-api.md)。

文字、静态表单和 `StyleScope` Builder 可直接使用 `.fontSize(14)`、`.lineHeight(24)`、`.firstLineIndent(0)`、`.subsequentLineIndent(0)`、`.alignment(...)`、`.lineBreakPolicy(...)`，只覆盖对应属性；`GridTable.CellBuilder` 使用同名入口。`.inheritFontSize()` 等逐项恢复仅清除该项声明。旧 `.style(value/callback)`／格 `.textStyle(value)` 继续完整显式；其后的具名修改不会自动恢复其他属性继承。逐项字号不自动调整继承的固定行距。

网格行可用 `.semanticTable(table, cell -> cell.rowSpan(2))` 放入语义内容，最终字段、图片链接和来源随长合并格续排。外网格与内 Table 的拆行开关独立，具体规则见[语义格指南](grid-semantic-flow-api.md)。

列内 `.semanticTable(table)` 保留与顶层相同的语义单元格和最终字段／外链，并接入区域续排、逐区表头和显式完整行拆分；组件网格继续用 `.grid(...)`。固定盒仍要求单视口，本功能不增加 DOCX 嵌套表格支持，见[语义组合指南](semantic-components-api.md)。

## 表格

语义表格保持矩形单元格、来源信息和 DOCX 映射能力：

~~~java
var document = Document.builder()
        .bodyFormat(format -> format.fontSize(11).lineHeight(17))
        .table(table -> table
                .header("指标", "数值")
                .row("收入", "120")
                .format(format -> format.padding(5).allowRowSplit(true)))
        .build();
~~~

未设置列宽时，从首行推导等比例列。`.columns(ColumnWidth...)` 可设置固定、比例或有界自动列宽；`.row(TableCell...)` 可放入富文本、图片或字段。`.header(...)` 必须在正文行之前调用，至少保留一个正文行。

语义列合并使用 TableCell.builder().columnSpan(n)，或 mixed(...).toBuilder().columnSpan(n)，并由 Table.Builder.headerCells(...)／row(...) 组合，见[列合并 API](column-spans-api.md)。DOCX 写标准 gridSpan；跨 Auto 列合并拒绝。

语义跨行合并继续链式 `.rowSpan(n)`，全覆盖正文／表头行分别用 `.coveredRow()`／`.coveredHeaderRow()`；新锚点自动跳过上行占用。二维查询使用 `table.grid()`，纵向连通组整体分页，标准 DOCX vMerge 与静态 XML 保持同源，见[跨行合并 API](row-spans-api.md)。

组件跨行／跨列表格使用 `GridTable.Builder`：

- `.columns(double...)` 设置相对权重，默认单列。`.row(...)` 声明一条实际行。
- `.cell(...)` 从当前行左向右放置，跳过之前跨行格占据的位置。`.rowSpan(...)`、`.columnSpan(...)` 声明合并。
- `.cells(String...)` 是连续普通文本格的短入口。
- `.defaults(...)` 对后续行取快照；单个格的配置覆盖默认值。
- `.minHeight(...)` 设置整行最小高度，内容可继续增高。单元格 `.verticalAlignment(...)` 在最终行高内生效。
- `.headerRows(...)` 设置重复表头数。所有被跨行覆盖的行也须用 `.row(...)` 显式声明；整行被占据时可使用空回调。

默认单元格为 12 pt 文字、4 pt 内边距、白底、黑色 0.5 pt 边框、顶部对齐。正文文字可用 `.textStyle(...)`、`.textColor(...)` 设置。`.cell(component, ...)` 放入自定义组件；`.textFactory(...)` 完全替代标准文字渲染器，其字体、颜色、诊断和线程安全由自定义实现负责。

空槽、越界、跨度重叠、跨越表头边界及非法尺寸会明确失败，不自动补空格。行高计算先满足单行约束，再按跨度、行、列的固定顺序满足合并约束；每个子组件只测量一次。相同单元格集合的几何不再依赖声明顺序，绘制仍保留输入顺序。

默认跨行连通行组仍整组分页，超高行组明确失败。显式 `.allowRowSplit(true)` 可让标准文字格及受支持混合流按完整行续页／栏，图片与固定组件仍整块输出；完整契约见[续页指南](grid-continuation-api.md)。

## 页区与导出

~~~java
var fonts = FontGroup.builder()
        .font(chineseFace)
        .font(latinFace)
        .missingGlyphPolicy(MissingGlyphPolicy.ERROR)
        .build();
var engine = DocumentEngine.builder(fonts)
        .pageTemplate(template -> template
                .footer(footer -> footer.height(18).gap(6)
                        .content(PageNumberComponent.builder("{page} / {pages}").build())))
        .build();

var result = engine.export(document)
        .backend(PdfBackend.builder()
                .options(options -> options.storage(PdfOptions.Storage.TEMP_FILE))
                .build())
        .options(options -> options
                .timeout(Duration.ofSeconds(30))
                .maxOutputBytes(10_000_000))
        .to(Path.of("report.pdf"));
~~~

示例需导入相应项目类型，以及 JDK 的 `Duration`、`Path`；`chineseFace`、`latinFace` 是调用方加载的物理字体。

引擎的默认页模板、导航和注释作用于短 `layout`、`write` 以及 `export` 入口；未配置时与旧入口一致，使用空页区和空导航。显式传入模板／导航／注释的高级 `layout` 重载继续使用其参数。分节的短入口将同一默认模板应用到每节；不同节模板使用既有高级重载。

`PageBand.content(component)` 填满页区视口，不要求调用方创建比例定位。背景、前景和复杂位置仍接受短 `Placement`／`Position` 值对象。页区在正文分页之前预留，最终页码在分页之后合成。

`export` 只保存调用配置，在 `.to(...)` 时才开始布局和输出，必须显式配置后端。路径输出使用同目录临时文件及原子替换，失败保留旧文件并清理临时文件；父目录须已存在，不支持原子移动时明确失败。流输出借用调用方流，失败可能已输出部分字节。超时和取消覆盖同步布局及输出，不创建后台线程。

## 覆盖范围

| 层次 | 链式入口 |
| --- | --- |
| 语义编写 | `Document`、`Table`、`TableCell`、`Section`、`SectionedDocument`、`DocumentTemplate` |
| 组件编写 | `Documents`、`FlowColumn`、`RowComponent`、`GridTable` |
| 静态表单 | `FormText`、`ChoiceGroup`、`CheckMark`、`LabelValue`、`FillLine` |
| 页面与分页 | `PageSettings`、`PageBand`、`PageTemplate`、`SectionTemplate`、`PaginationPolicy` |
| 文字与字体 | `ParagraphFormat`、`CellFormat`、`TableFormat`、`InlineStyle`、`ParagraphStyle`、`FontGroup`、`TextComponent`、`FlowText`、`OpenTypeText`、`FontScope` |
| 图片与图形 | `InlineImage`、`ImageComponent`、`ShapeComponent`、`LineComponent`、`BoxComponent`、`WatermarkComponent` |
| 布局组合 | `ContinuousColumns`、`MultiColumn`、`FloatText`、`MultiFloatText` |
| 页码与交互集合 | `InlineField`、`PageNumberComponent`、`TableOfContents`、`Navigation` |
| 离线文本模板 | `XmlTemplate`、`MarkdownTemplate`；结构留在模板文件，Java 只配置数据、显式资源及输出 |
| 既有静态 PDF | 独立 `PdfAdapter`、`PdfInput.pages(...)`、`PdfLimits`；页选择／重排／合并，无第三方公共类型 |
| 执行与后端 | `DocumentEngine`、`ExecutionOptions`、`PdfBackend`、`PdfOptions`、`PdfLimits`、`PdfEncryption`、`RasterBackend`、`DocxAdapter`、`DocxLimits` |

`DocumentTemplate` 提供 `.block(...)`、`.when(...)`、`.each(...)`，嵌套分支回调使用 `Nodes`，仍只支持原有安全绑定表达式；动态行使用 `DocumentTemplate.Rows.builder()`。`Navigation` 链式累积书签、评论和字段，文本框、选项框、单选组和复选框也各有 Builder；定位目标继续使用短值对象。`MultiFloatText.Builder.floating(...)` 支持嵌套浮动矩形设置，PDF 权限可用 `PdfEncryption.Permissions.builder()` 具名声明。`Section.Builder.blocks(...)` 可接收普通或组件文档的块；跨节的块 ID 仍须全局唯一。

已有简单构造器、静态工厂和 `withXxx` 保留。`PageSize`、`PageMargins`、`Point`、`Rect` 等短值对象继续直接构造；布局结果、绘制指令、异常和 SPI 不为了语法统一而增加 Builder。

## 默认值、快照与兼容性

所有 Builder 限定单个任务使用；`build()` 产生不可变结构快照。集合在设置和构建边界防御性复制。嵌套回调返回后再修改所保留的行、格、默认配置或分支 Builder，不改变已经加入父结构的内容。自定义组件、回调和字体资源仍按其自身线程安全与资源契约借用。

段落和单元格格式新建时默认 12 pt／18 pt。设置字号会按 1.5 倍更新行距，直到显式设置行距；随后改变字号不会覆盖该行距。`toBuilder()` 将原对象的行距视为显式值，因此修改字号时保留原行距。引擎仍拒绝容纳不了字体或图片的固定行框。

`GridTable` 新增 `minimumRowHeights`，`TextComponent` 新增 `minimumHeight`；旧构造签名保留并使用零最小高度。record 反射分量及字符串表示包含新增属性。`GridTable` 的行高顺序缺陷修复可能减少旧调用中过量分配的高度，因而影响分页；本次不更新既有视觉基线。

当前项目尚未发布。本地隔离 Maven 调用方验证覆盖新增 DSL、页脚、预算、PDF 临时缓存及原子输出，不等于正式发布兼容性承诺。

模板调用见 [XML](xml-template-api.md)及 [Markdown](markdown-template-api.md)。[PDF 适配器](pdf-adapter-api.md)处理既有静态页，不恢复语义模型；它的 PdfLimits 位于 `adapter.pdf`，与 PDF 输出后端的同名限制类型属于不同契约，按所用入口显式导入。

## 单向长表导出

`StreamingTable.builder(id)` 只配置固定列、有限表头、页设置和 padding，不接受正文集合。`engine.export(spec, sourceFactory).backend(pageBackend).limits(...).diagnostics(...).options(...).to(target)` 在输出时才打开一次性源，返回扁平计数，应用只需实现 `TableRowSource.next()` 与自己的资源关闭。

源先关闭再 finish，借用目标流不关闭；路径失败保留旧目标。具名选项在 to 入口快照，重入修改仅影响下次导出。普通 Table 及支持未来引用／交互的完整文档入口保持。初版只支持固定列普通文字整行分页，PDFBox 页树仍增长，详见[长表指南](streaming-table-api.md)与[阶段二十四](verification/phase-24-streaming-tables.md)。

语义和组件文档均可使用 `.theme(DocumentTheme)`，主题定义集中在完整格式值中，正文链保持标题／段落／表格结构。具名样式查询返回现有不可变 ParagraphFormat／InlineStyle，避免逐组件重复传参，完整优先级见[主题指南](themes-api.md)。

组件可通过 `FontRoleScope.builder(content).fontRole(name)` 选择完整已注册字体组，固定富文本使用 `RichTextComponent.builder().add(text, inlineStyle)`。每个运行的 InlineStyle 是完整显式值，段落继承仅控制几何，见[组件文字指南](component-text-api.md)。

`NumberedItem`／`BulletItem` 提供具名 Builder、`.markerStyle(value/callback)` 和 `.defaultMarkerStyle()`；标记与正文分别配置，`toBuilder` 保留解析值，见[列表指南](list-markers-api.md)。XML 使用独立 `.bodyText(...)`／`.headingText(level, ...)`／`.cellText(...)` 配置角色和颜色，与几何配置顺序独立，见[XML 默认值指南](xml-text-defaults-api.md)。

# 基础表格 API

组件 `GridTable` 后续已支持连续分栏的逐栏表头和合并行组分配，见[混合分栏 API](mixed-columns-api.md)。以下语义 `Table` 的行拆分／来源契约保持独立。

当前表格支持固定／比例／有界自动列宽、文本／富文本／行内图像／动态字段单元格、行列合并、内边距、自动行高、整行／连通行组跨页及重复表头。合并同时贯通静态模板和标准 DOCX gridSpan／vMerge，见[列合并指南](column-spans-api.md)和[跨行合并指南](row-spans-api.md)；普通文本合并覆盖 Auto 见[自动列合并 API](automatic-merged-columns-api.md)，块格／内表仍有独立限制。表格和段落共用有序文档块，字体栈由调用方显式提供。模型只依赖 JDK，布局不写文件，PDF 后端不参与尺寸计算。

## 运行示例

在仓库根目录执行：

~~~powershell
.\gradlew.bat build :examples:flowPdf :examples:tablePdf
~~~

表格示例输出 `examples/build/export4j-table-example.pdf`，包含标题、32 条正文记录、重复表头及表后段落。实现见 [TablePdfExample](../examples/src/main/java/com/mirakyux/export4j/examples/TablePdfExample.java)。字体配置和输出步骤沿用[文档布局 API](layout-api.md)。

## 创建混合文档

~~~java
import java.util.List;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;

var format = CellFormat.of(12);
var table = new Table("results",
        List.of(new ColumnWidth.Fixed(80), new ColumnWidth.Fraction(1)),
        List.of(new TableRow(List.of(new TableCell("编号", format), new TableCell("说明", format))),
                new TableRow(List.of(new TableCell("01", format), new TableCell("单元格会按列宽自动换行。", format)))),
        1, TableFormat.DEFAULT);
var source = new Document(DocumentMetadata.EMPTY, PageSettings.A4,
        List.of(new Paragraph("intro", "结果表格", ParagraphFormat.of(14)), table));
~~~

`Document.blocks()` 是完整顺序。顶层段落和表格的 ID 在同一文档中唯一。表格至少包含一列和一个正文行，二维网格须由真实锚点矩形恰好覆盖；默认行列跨度为 1。无纵向合并时每行 columnSpan 总和等于列数；有前行占用时只声明新增锚点，全覆盖行用 coveredRow。完整 Table 验证覆盖、边界及表头分界，二维来源使用 Table.grid。`headerRows` 指定开头连续表头物理行数，允许为 0，必须小于总行数；空表格和纯表头表格在构造时失败。

行、列索引和块索引均从 0 开始。单元格使用块 ID、行索引与列索引定位，不额外分配字符串 ID。单元格可为空，空文本仍占一行；显式空行与尾部换行遵循文本层契约。

## 列宽、内边距与行高

跨行合并先满足单行格的高度，再按跨度／行／列顺序补足合并约束；覆盖行不重复添加 padding。纵向连通组默认整体移页／移区，显式 allowRowSplit 可按原始 owner 续排；无纵向合并的完整行拆分保持。具体零基础行、IR 省略覆盖行、来源和迁移规则见[跨行合并指南](row-spans-api.md)。

| 配置 | 行为 |
| --- | --- |
| `ColumnWidth.Fixed(points)` | 固定正宽度，单位 point |
| `ColumnWidth.Fraction(weight)` | 正权重，按比例分配扣除固定列后的内容宽度 |
| `ColumnWidth.Auto(minPoints, maxPoints)` | 按内容估算首选宽度，页面不足时收缩至下限；测量探测不重复计入输出行预算 |
| 全部为固定列 | 保持固定总宽度，左对齐，不自动拉伸 |
| `CellFormat(fontSize, lineHeight, alignment)` | 独立字号、固定基线间距及左／中／右对齐 |
| `TableFormat(padding, spaceBefore, spaceAfter)` | 全表统一单元格内边距，以及表格块前后间距 |
| `TableFormat.builder().drawBorders(false)` | 关闭单元格轮廓；几何、文字、表头背景和语义关系保持 |

固定列宽之和不得超过页面内容宽度。有比例列时，剩余宽度必须为正。比例权重先归一化，避免大权重求和溢出；无法表示的极小列宽明确失败。所有配置长度必须有限，列宽、权重和文字尺寸为正，内边距与间距非负。

内边距使用 `PageMargins(top, right, bottom, left)`，单位 point。每个单元格的文字宽度等于覆盖列宽之和减去一次左右内边距，必须为正。行高等于该行最大文字布局高度，加上上下内边距。所有单元格顶对齐，同一行使用相同行高；没有固定行高、内容裁切或自动缩小。

`CellFormat.of(size)` 默认左对齐，行距为字号的 1.5 倍。`TableFormat.DEFAULT` 使用四边 6 pt 内边距、表前 0 pt、表后 6 pt。文字仍受显式字体回退、行框和复杂塑形限制。

边框默认开启，原有三参／四参构造器保持此默认。关闭轮廓的设置跨普通分页、嵌套格、变宽区域及组件的最终字段解析保留；每个内表独立决定自己的轮廓。XML 用 `draw-borders="false"` 保存，默认开启时省略该属性。DOCX 用标准 `w:tblBorders` 的六边 `nil` 表达关闭，HTML 显式输出对应边框样式；不把此开关解释为任意线型、颜色或逐边样式。

## 单元格动态字段

`TableCell.mixed(content, format)` 接受 `InlineField` 的页码、总页数、目标页码与编号引用四种字段。自动列宽探测使用预留宽度，最终值不会改变列宽或重新分页。重复表头按每次所在页面解析，拆行字段跟随其完整文字行；原始单元格字段位置保存在 `fields()`。

最终编排由 `DocumentEngine` 或 `ComponentDocumentRenderer` 完成。生成值诊断保留块、最终页、原始行列和字段标记位置；未知目标、缺字或宽度不足明确失败。单元格注释标记尚未支持；DOCX 原生字段已映射，但重复表头 PAGE 因 Word 复用缓存而明确拒绝导出，见[DOCX 字段 API](docx-fields-api.md)。完整契约见[字段 API](inline-fields-api.md)及[阶段十一验收](verification/phase-11-table-fields.md)。

## 分页规则

1. 每个单元格只换行一次，页间复用已有行布局及字形；不会因续页改变列宽或断点。
2. 默认正文整行不可分割，当前页不足时换页。显式 `TableFormat.allowRowSplit=true` 时按各单元格完整文字行拆分，见[生产能力 API](production-api.md)。
3. 初次放置表格时，全部表头与第一行正文必须一起放下；当前页不足时整体移动到新页。
4. 每张续页先放置全部表头，再消费至少一行正文。没有单独生成的表头页，也不在表格结束后生成空白页。
5. 默认任一正文行加表头高于新页内容区域时失败；开启拆行时，表头与最小文字片段必须放入新页，否则失败。
6. 表前表后间距与相邻段落间距相加；页边缘间距舍弃。续页不重新应用表前间距。

这套规则允许页尾留白，但保证正文文字按原顺序出现一次。语义 `Table` 支持列合并，支持行合并和[有界递归块格／内表](block-cells-api.md)，尚不提供表格整体保持、最少正文行数或横向分页。`TableCell.rich(inlines, format)` 支持行内角色、颜色、上下标和装饰，`TableCell.mixed(content, format)` 支持行内 PNG／JPEG；外链由最终行片段解析，图片续页及重复表头见[图像 API](inline-images-api.md)。需要组件跨行合并及任意块图文格时使用 `GridTable`，见[进阶 API](advanced-api.md#可跨页组件与合并表格)；其来源不同于语义表格，完整行续排须显式开启。显式拆行的 `CellFragment.firstLineIndex` 与 `TableRowFragment.continuedRow` 保留续页身份，旧构造器仍默认从第 0 行开始且非续行。

## 布局来源与绘制

`LayoutPage.elements()` 保存所有 `LayoutFragment`，顺序与语义块及跨页片段一致。`ParagraphFragment` 和 `TableFragment` 实现这个封闭层次。

| 类型 | 来源与坐标 |
| --- | --- |
| `TableFragment` | 原块 ID、原块索引、页面内左上角、片段宽高及有序行 |
| `TableRowFragment` | 原行索引、是否表头、是否重复表头及完整单元格 |
| `CellFragment` | 物理网格起始列（不是压缩源列表下标）、覆盖全部列的边框区域和文字原点，以及格内局部文字布局 |

`repeatedHeader=true` 表示续页额外呈现的原表头，不能算作新增正文。首次表头的 `header=true`、`repeatedHeader=false`。原始行号和单元格 UTF-16 范围始终不变，回退诊断也保留在单元格布局中。

`DocumentPainter` 按 `elements()` 顺序绘制段落和表格。当前默认呈现为黑色文字、细黑网格和浅灰表头；富文本使用行内明确指定的颜色，没有可配置的边框主题。描边置于单元格区域内部，后端只执行已有绘制命令。

## 预算与失败

`DocumentLayoutLimits` 继续提供原四参数构造器，新增完整七参数配置：

~~~java
new com.mirakyux.export4j.layout.core.DocumentLayoutLimits(
        10_000, 1_000_000, 100_000, 1_000, 10_000, 256, 100_000);
~~~

| 字段 | 默认值 | 计数范围 |
| --- | --- | --- |
| `maxParagraphs` | 10,000 | 历史字段名，现限制所有顶层块，含表格 |
| `maxCodePoints` | 1,000,000 | 所有原文 Unicode 码点，段落与单元格共享；表头原文计一次 |
| `maxLines` | 100,000 | 段落和单元格生成行，加上每次重复表头的行 |
| `maxPages` | 1,000 | 整份文档页面 |
| `maxTableRows` | 10,000 | 每张表的原始行，含表头 |
| `maxTableColumns` | 256 | 每张表的列 |
| `maxCells` | 100,000 | 文档内所有表格的原始单元格总数 |

每个单元格继续受到文本层独立预算约束。以上是结构预算；时间、取消与输出字节另由[执行控制](capabilities-api.md#取消超时与文件写入)配置，总进程内存尚无硬限制。

`DocumentLayoutException` 增加 `INVALID_GEOMETRY`，用于列宽超出页面或扣除内边距后没有正文字宽度。超高行或表头与正文组合失败使用 `CONTENT_TOO_TALL`；预算使用 `BUDGET_EXCEEDED`；文字塑形、缺字及行框错误使用 `TEXT_LAYOUT_FAILED`，保留原因。

异常提供 `blockId()`、`blockIndex()`、`pageIndex()`、`rowIndex()`、`columnIndex()` 和 `sourceIndex()`。来源位置是对应单元格原文 UTF-16 偏移；不适用索引为 `-1`。测量表格时的页索引是进入该块时的处理页面，不预言失败单元格最终应分配到哪一页；页预算失败指向无法创建的新页和下一个正文行。错误消息不默认包含正文。

## 兼容性

保留段落构造用法，包括 `List<Paragraph>`。`Document.paragraphs()` 和 `LayoutPage.fragments()` 现在是仅含顶层段落的不可变兼容视图。处理完整混合内容必须迁移到 `blocks()` 和 `elements()`。

`ParagraphFragment.paragraphIndex()` 是原文档块索引，混合文档中可能不连续；`blockId()` 和 `blockIndex()` 是统一入口。异常原有的 `paragraphId()`／`paragraphIndex()` 是块 ID／索引别名，表格失败也返回表格标识。

这是尚未发布的 `0.1.0-SNAPSHOT` 公共契约演进。Record 组件与泛型类型发生变化，不能据此宣称任意反射代码或已有二进制完全兼容。设计见 [ADR-0005](adr/0005-basic-tables.md)，实际证据见[表格验收](verification/phase-3-tables.md)。

行内内容内表支持父格实际宽度下的[有界自动列](nested-automatic-tables-api.md)；含块格表自身的 Auto 仍拒绝，不能把此能力扩大为任意块树首选宽度或单向源自动列。

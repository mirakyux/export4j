# 文档布局 API

当前布局 API 将普通文本段落和基础表格排入统一页面，支持页边距、段落分页、间距及保持整块。表格还支持固定／比例列宽、自动行高、整行跨页和重复表头，详见[表格 API](table-api.md)。调用方显式传入字体，布局过程不写文件，也不依赖 PDF 后端。

## 运行自动分页报告

仓库根目录执行：

~~~powershell
.\gradlew.bat build :examples:flowPdf
~~~

输出 `examples/build/export4j-flow-example.pdf`。完整代码见 [FlowPdfExample](../examples/src/main/java/com/mirakyux/export4j/examples/FlowPdfExample.java)。

该示例使用语义段落生成多页中英文报告，再按实际页数追加页眉和页码绘制。它保留原有后处理方式；正式页区预留和最终页码组件现由[组件 API](components-api.md)提供。

## 最小调用路径

以下片段在仓库根目录执行，调用方需要布局核心与 PDF 后端模块：

~~~java
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.DocumentPainter;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;

var fonts = List.of(
        TrueTypeFontLoader.load(Files.readAllBytes(Path.of("assets/fonts/LiberationSans-Regular.ttf"))),
        TrueTypeFontLoader.load(Files.readAllBytes(Path.of("assets/fonts/LXGWWenKaiTC-Regular.ttf"))));
var paragraph = new Paragraph("body", "中文与 English 混排。\n".repeat(120), ParagraphFormat.of(12));
var source = new Document(new DocumentMetadata("自动分页", ""), PageSettings.A4, List.of(paragraph));
var layout = new DocumentLayouter().layout(source, fonts);
var display = DocumentPainter.paint(layout);
try (var output = Files.newOutputStream(Path.of("flow.pdf"))) {
    new PdfBackend().write(display, output);
}
~~~

`Document` 保存语义，`DocumentLayout` 保存页面及文字位置，`DisplayDocument` 保存绘制命令。生成 PDF 只是最后一个可替换步骤。

## 语义模型

模型模块仅依赖 JDK，不引用字体、文本、绘制或 PDF 类型。

| 类型 | 契约 |
| --- | --- |
| `Document` | 元数据、统一页面设置、按顺序排列的 `Block`；可为空 |
| `Paragraph` | 文档内唯一的非空白 ID、原文、段落格式；空正文有效 |
| `PageMargins` | top / right / bottom / left，单位 point，有限且非负 |
| `PageSettings` | 页面尺寸及页边距；必须留出正宽高的内容区域 |
| `ParagraphFormat` | 字号、基线间距、缩进、对齐、段前段后间距与保持整块 |
| `Table` | 文档内唯一 ID、固定／比例／自动列、矩形文本或富文本单元格及表头行数 |

`PageSettings.A4` 使用 A4 和四边 54 pt 页边距。`ParagraphFormat.of(size)` 默认行距为字号的 1.5 倍、左对齐、无缩进、段前 0 pt、段后 6 pt、允许跨页。

完整格式签名：

~~~java
new ParagraphFormat(fontSize, lineHeight, firstLineIndent, subsequentLineIndent,
        ParagraphAlignment.LEFT, spaceBefore, spaceAfter, keepTogether);
~~~

字号和行距必须有限且为正，其余长度有限且非负。页面内容宽度必须大于两种缩进，行距须容纳整个字体栈的行框。布局核心将语义格式映射到文本层 `ParagraphStyle`，保持模型独立。

本次一个文档使用同一套页面设置与全局有序字体栈，正文为普通字符串。字符级样式、分节页面、字体族语义和图片节点尚未实现。完整混合内容使用 `Document.blocks()`；`paragraphs()` 是仅含顶层段落的兼容视图。

## 分页规则

1. 每段按完整内容宽度换行一次，确定字形、advance、行宽和原文范围。
2. 从页面内容区域左上角开始，按完整行放置。剩余高度不足一行时，将下一行放到新页。
3. 首行缩进仅作用于整个段落的第一行。跨页片段继续使用原来的行及后续行缩进，不重新断行。
4. `keepTogether=true` 时整个段落作为不可分割内容。当前页不足时移到新页；新页也不足时明确失败，不裁切、不缩小。
5. 同页相邻段落的前后间距相加。页首不保留段前间距，页尾的段后间距不创建空白页；因间距或保持整块而换页时，页边缘间距被舍弃。
6. 段落内部的显式空行和尾部换行仍消耗行高，可能产生只有空行的页面。这是正文语义，与无故生成空白页不同。
7. 空文档输出一个无段落片段的页面。空段落输出一个空行。

行框使用文本模块统一的字体栈 ascent / descent。片段高度为行数 × 行距，基线以片段左上角为局部原点。浮点比较只允许约 1 ULP 的舍入差，例如 `3 × 0.1` 与 `0.3`；不允许可见内容溢出。

每轮分页循环必须消耗至少一行，或从已有内容的页面转到新页。新页无法放下一行时立即失败；页数预算限制额外页分配。

## 布局结果与绘制

`DocumentLayout` 保存元数据及从 0 顺序编号的不可变页面。`LayoutPage.elements()` 保存页面尺寸、内容区域与按顺序排列的段落／表格片段。`LayoutPage.fragments()` 是只含段落片段的兼容视图，不能用于完整混合内容遍历。

每个段落片段包含：

- `paragraphId` 与 `paragraphIndex`：原语义段落标识及从 0 开始的文档块索引；统一入口为 `blockId()`／`blockIndex()`。
- `firstLineIndex`：该片段第一行在整个段落中的行号。
- `origin`：该片段在页面内的左上角位置，单位 point。
- `layout`：片段局部 `ParagraphLayout`，保留原文 UTF-16 范围与已有字形 run。

片段的字体回退诊断只保留本片段的原文范围，可通过外层页码与段落标识定位。布局列表与字体数据是不可变快照，可共享。源文本中的 CR/LF 与软换行空格范围继续遵循[文本 API](text-api.md)。

`DocumentPainter.paint(layout)` 按完整片段顺序将正文以黑色转换成绘制命令，表格附带细网格和浅灰表头，不重新测量、换行或分页。自定义装饰可在生成 Display List 后追加命令；不会自动占据或改变内容区域。

## 预算与错误

默认文档预算：

| 项目 | 默认值 |
| --- | --- |
| 顶层块数，历史字段名 `maxParagraphs` | 10,000 |
| 原文 Unicode 码点总数，含 CR/LF | 1,000,000 |
| 生成行数，包含单元格和重复表头 | 100,000 |
| 页面数 | 1,000 |
| 每表原始行数／列数 | 10,000／256 |
| 全文原始单元格数 | 100,000 |

文本层每段的预算继续生效，默认为 100,000 个码点、10,000 行。需要时显式配置两个层级：

~~~java
var layouter = new DocumentLayouter(new com.mirakyux.export4j.text.ParagraphLayouter(),
        new com.mirakyux.export4j.layout.core.DocumentLayoutLimits(1_000, 200_000, 20_000, 200));
~~~

`DocumentLayoutException` 提供稳定代码：

| 代码 | 行为 |
| --- | --- |
| `BUDGET_EXCEEDED` | 块、码点、行数、页面、表格结构或内部文本预算超限 |
| `CONTENT_TOO_TALL` | 一行、保持整块的段落或表头加正文行无法放入新页 |
| `TEXT_LAYOUT_FAILED` | 缺字、复杂塑形、行框或缩进等文本布局失败 |
| `INVALID_GEOMETRY` | 表格列宽超过内容区，或扣除内边距后文字宽度不为正 |
| `UNSUPPORTED_COMPONENT` | 自定义 Block 没有核心可识别的布局适配器 |
| `COMPONENT_LAYOUT_FAILED` | 组件返回空值或报告非法参数，保留组件异常原因 |

异常附带块 ID、块索引、处理中的页面索引和原文 UTF-16 索引；表格增加行列索引，来源位置属于该单元格。原段落 ID／索引访问器保留为块来源别名。索引从 0 开始，`-1` 表示不适用；请求新页超限时页索引指向无法创建的新页。预检文本总量时尚未分配页面。

文本失败保留原始异常原因，可读取 `TextLayoutException.code()`。默认异常消息不包含正文；布局失败不返回部分结果。无效模型参数在构造时失败，必填空值使用 `NullPointerException`。

这些是结构预算，不是超时、内存总量上限或不可信输入沙箱。PDF 后端仍执行独立的输出预算。

## 当前边界

默认段落仍可在任意完整行之间分页；`PaginationPolicy` 可配置最低片段行数、保持相邻和段前分页，`PageBreak` 支持显式分页。分节、富文本和合并图文表格组件已实现，见[进阶 API](advanced-api.md)。后续已实现矩形绕排及公共复杂塑形，见[能力指南](capabilities-api.md)。当前不包含单行拆页或完整中文禁则。

页眉页脚、定位绘制和正文组件通过核心 SPI 接入。普通 `ComponentBlock` 保持整块，`PageableComponent` 返回可跨页的有序片段，并可适应首片段剩余高度，不依赖最终页码；`ComponentFragment` 保留块来源、局部绘制与诊断。组件绘制中的码点和命令计入对应预算，自定义 SPI 的执行本身不受沙箱控制。可调用的标准实现与导航编排见[组件 API](components-api.md)。

`RegionFlow` 在相同宽度下接受最多 16 个短区前缀及完整续区，返回带区域归属的局部片段；`ContinuousColumns` 由此从当前页剩余区域分栏，续页使用完整栏高。文字按完整行、`FlowColumn` 混合流按子组件片段、`GridTable` 按合并行组分配，完整契约见[连续分栏 API](continuous-columns-api.md)和[混合分栏 API](mixed-columns-api.md)。

正文 `Paragraph` 和语义 `TableCell` 的行内字段保存为 `FieldSpan`，测量后为 `TextLine.fields()` 中的原子 `FieldBox`。原文 UTF-16 范围包含一个字段标记；移动完整行时必须保留盒子。只有上层编排器能在目录和脚注页数确定后解析字段，直接绘制未解析字段会失败，见[行内字段 API](inline-fields-api.md)。

固定字体、输入、页面设置、样式和 JDK 断行规则时，布局结果可复现。共享布局器要求注入的段落布局器和字体 SPI 也满足线程安全契约；构建器及输出流仍限定在各自任务内。

架构决定见 [ADR-0004](adr/0004-paragraph-pagination.md)，验证证据见[阶段三段落分页验收](verification/phase-3-paragraphs.md)。

后续表格和混合顺序扩展见 [ADR-0005](adr/0005-basic-tables.md)与[表格验收](verification/phase-3-tables.md)。

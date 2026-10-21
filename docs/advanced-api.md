# 进阶布局、语义与字体策略 API

本文说明 PDF 首版的新增能力。基础绘制、定位和导航分别见[绘制 API](graphics-api.md)、[布局 API](layout-api.md)与[组件 API](components-api.md)。长度使用 point，坐标原点位于左上角。

完整可执行用例见 [AdvancedPdfExample](../examples/src/main/java/com/mirakyux/export4j/examples/AdvancedPdfExample.java)：

~~~powershell
.\gradlew.bat build :examples:advancedPdf
~~~

输出 `examples/build/export4j-advanced-example.pdf`，包含 7 页、横竖混排的三个分节、自动目录、合并表格、脚注、尾注、嵌套续页和中文可编辑表单。[验收记录](verification/phase-3-advanced.md)说明独立检查与视觉基线。

## 字体组与缺字策略

`FontGroup` 包含有序 `List<FontFace>`、`MissingGlyphPolicy` 和占位符码点。单字体也按一个成员的字体组配置。先按顺序查找全部成员；只有整个组都缺字时才应用策略。

| 策略 | 行为 |
| --- | --- |
| `ERROR` | 默认策略；抛出 `TextLayoutException`，代码为 `MISSING_GLYPH` |
| `PLACEHOLDER_WITH_WARNING` | 使用组内字体绘制指定占位符，每个替换码点产生一条 `WARNING` 诊断 |

以下片段中的 `latin`、`chinese` 是调用方显式加载的 `FontFace`，加载方式见[文本 API](text-api.md)。

~~~java
import java.util.List;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextDiagnostic;

var strict = FontGroup.strict(List.of(latin, chinese));
var tolerant = FontGroup.placeholder(List.of(latin), 0x25a1);
var result = new ParagraphLayouter().layout("Latin 与中文", tolerant, 300, ParagraphStyle.of(12));
for (var diagnostic : result.diagnostics()) {
    if (diagnostic.severity() == TextDiagnostic.Severity.WARNING) {
        System.err.printf("%s U+%04X -> U+%04X [%d,%d)%n", diagnostic.code(),
                diagnostic.sourceCodePoint(), diagnostic.replacementCodePoint(),
                diagnostic.sourceStart(), diagnostic.sourceEnd());
    }
}
~~~

占位符可使用 `□`（U+25A1）或 `?`，必须能由组内至少一个字体提供；需要替换时占位符也缺字，仍报 `MISSING_GLYPH`，不会递归替换。非法 Unicode 仍明确失败。默认段落现在按需执行公共 OpenType 塑形；字形簇须由单个显式字体覆盖，跨样式边界等限制不被占位策略掩盖，见[公共文本](capabilities-api.md#公共文本和字体)。

`MISSING_GLYPH_REPLACED` 保存原码点、替换码点、所选字体标识及原文 UTF-16 范围。一个补充平面字符替换成 BMP 占位符后，来源范围仍占两个 UTF-16 单元。PDF 显示和提取的是占位符，原文需要由调用方另外保留。`FONT_FALLBACK` 是 `INFO`；从其他成员成功取字不属于缺字警告。

库返回结构化诊断，由调用方选择日志、标准错误或告警系统；不会自行配置日志或打印正文。综合示例显式输出警告。组件编排结果使用 `ComponentDocumentLayout.diagnostics()` 汇总正文、目录、页区、单元格、注释文本和引用的文字诊断；此处的注释文本指脚注／尾注，不包含 PDF 评论或可编辑表单。

`DocumentLayouter.layout(document, group)` 与 `ComponentDocumentRenderer.layout(document, template, navigation, group)` 接受字体组。分节入口还接受 `Map<String, FontGroup>`，不同字体角色可以采用不同策略。原 `List<FontFace>` 重载保持严格报错。自定义 `TextShaper` 需显式实现替换策略；默认接口不会假装支持。

PDF 表单使用独立的显式 `FontData` 配置，其初始值缺字仍报错。可编辑字段不能因替换而丢失实际填写值。

## 分页策略与标题

`ParagraphFormat.withPagination(PaginationPolicy)` 配置分页约束：

~~~java
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.ParagraphFormat;

var balanced = ParagraphFormat.of(12).withPagination(PaginationPolicy.BALANCED);
var headingFormat = ParagraphFormat.of(18).withPagination(new PaginationPolicy(true, false, 1, 1));
~~~

| 字段 | 含义 |
| --- | --- |
| `keepWithNext` | 当前段落整体与后继块的最小有效首片段放在同页，连续标记组成保持链 |
| `pageBreakBefore` | 非空当前页在此段之前结束 |
| `minLinesBeforeBreak` | 实际拆分页时，分页前片段至少保留的行数 |
| `minLinesAfterBreak` | 实际拆分页时，分页后至少保留的行数 |

`DEFAULT` 为两端各 1 行，`BALANCED` 为各 2 行。未发生拆分的短段落不受最低行数限制。`keepTogether` 仍控制整段不可拆页；保持链本身超过新页时明确失败。末尾的 `keepWithNext` 当前同样按整段保留处理。

`PageBreak(id)` 提供显式分页。前导、连续和末尾分页不会产生额外空白页。显式分页和后继的 `pageBreakBefore` 优先于保持相邻。分页只切分已测量的完整行，不重新塑形或重置首行缩进。

`Heading(id, text, level)` 支持 1～6 级，默认整段保留并保持相邻；带 `format` 的构造器使用调用方明确的格式。`Navigation.fromHeadings(document)` 按源码顺序生成书签与前置目录，层级从 1 开始且不能跳级，目标沿用标题 ID。

## 富文本与命名样式

`Paragraph(id, List<InlineText>, format)` 将多个行内片段一次换行。`InlineStyle` 包含字体角色、字号、24 位 RGB、基线偏移、下划线和删除线。正基线偏移向下，可表达下标；负值向上，可表达上标。

~~~java
import java.util.List;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;

var rich = new Paragraph("rich", List.of(
        new InlineText("普通文字 ", InlineStyle.of(12)),
        new InlineText("粗体", new InlineStyle("bold", 12, 0x285780, 0, true, false))),
        ParagraphFormat.of(12));
~~~

字体角色由布局入口的映射解析，空角色使用默认组，未知角色明确失败。粗体、斜体使用真实字体文件，不合成假粗体或假斜体；中文回退字体的字形也不会自动变粗。行距必须容纳所有字号及基线偏移，否则失败。

`StyleSheet(Map<String, ParagraphFormat>, Map<String, InlineStyle>)` 提供不可变命名样式，通过 `paragraph(name)`／`inline(name)` 显式解析。当前不提供继承、CSS 级联或自动主题系统。旧段落和字形 run 构造器继续可用，record 结构变化仍要求检查反射／序列化与二进制兼容性。

## 分节与显示页码

`Section(id, settings, blocks, firstPageNumber)` 和 `SectionedDocument(metadata, sections)` 支持不同纸张、方向和页边距。每节从新页开始，全文档块 ID 唯一。

`firstPageNumber == 0` 继续前节页码，正数重新起算。第一节继续编号时包含前置目录页偏移。物理页索引始终从 0 开始且不重置；显示页码允许重置。

`SectionTemplate(first, odd, even)` 分别指定节首页、显示奇数页、显示偶数页模板。正文提前预留三个模板中最大的页眉／页脚区域，较小模板不会侵入正文。每节传入一个模板；统一模板用 `SectionTemplate.uniform(template)`。

~~~java
var result = new ComponentDocumentRenderer().layout(sectionedDocument, templates,
        Navigation.fromHeadings(sectionedDocument), defaultFontGroup, fontRoles, notes);
~~~

以上参数类型依次为 `SectionedDocument`、`List<SectionTemplate>`、`Navigation`、`FontGroup`、`Map<String, FontGroup>`、`List<Note>`。`PageNumberComponent` 的 `{page}` 使用显示页码，`{pages}` 使用最终物理页总数。目录标签和页引用使用目标节的显示号码，链接及书签使用真实最终页对象；混合纸张目标按对应页面尺寸校验。

## 可跨页组件与合并表格

核心 SPI `PageableComponent` 返回有序、非空、不可分割的 `ComponentLayout` 片段；每个片段必须放得进新页。适配的 `ComponentBlock` 首片段可以使用当前页剩余高度，后续片段从新页开始。普通 `Component` 仍按整块移动。

`layoutParts(context, firstPageHeight)` 接受 0～新页视口高度，不能为 NaN 或无穷值。如果连一个原子单元都放不下，允许返回新页首片段，由核心移动到新页。片段不依赖最终页码。固定页区或表格单元格中调用 `layout()` 时必须只有一个片段，否则明确失败。

| 组件 | 行为与边界 |
| --- | --- |
| `FlowText` | 只换行一次，按完整行分页，支持两端最低行数 |
| `FlowColumn` | 纵向嵌套、间隔、水平对齐，各续页重复内边距，保留子片段顺序 |
| `RowComponent` | 等宽列、内边距、间隔和垂直对齐；整行不可拆页 |
| `GridTable` | 比例列宽、横纵合并、组件单元格、重复表头和跨页行组 |

`GridTable.Cell(row, column, rowSpan, columnSpan, content, padding, verticalAlignment, background, border, borderWidth)` 显式描述左上网格位置和样式。每个网格槽恰好被一个单元格覆盖；重叠、未覆盖、跨出边界和跨越表头边界的合并明确失败。最多 1,000,000 个槽。

新代码可用 `GridTable.builder().columns(...).row(...)` 自动推导位置，设置行最小高度与默认格样式。单行约束优先，合并约束按固定顺序求解，行高不依赖格声明顺序；详见[链式 API](fluent-api.md)。

默认纵向合并连通的行组成整体分页单元，该组与重复表头无法同时放入新页时失败。表头复用测量结果。显式 `allowRowSplit(true)` 支持标准文字、复杂文字及受控混合列按完整行／原子组件续页，见[续页指南](grid-continuation-api.md)。单元格内容可用文本、图片、SVG、Box、Row 或 Column；固定组件保持整块。当前不是任意可拆分的嵌套表格，也没有表格横向分页。

语义 `Table` 支持富文本、图片、字段、[列合并](column-spans-api.md)及[跨行合并](row-spans-api.md)，保留原始锚点行／物理列与格内来源，映射标准 DOCX gridSpan／vMerge；纵向连通组首批整体移动。`GridTable` 保留组件块内容及显式合并格续排，其绘制组合不等于语义 Table 的来源或 DOCX 模型。

## 编号、题注、引用与注释

`NumberedItem(id, listId, text, restartAt, format)` 对相同列表 ID 连续计数，0 表示继续，正数重置；计数跨节延续。`Caption(id, kind, text, format)` 按类别独立编号，例如 `Figure 1`。

`CrossReference(id, prefix, targetId, kind, format)` 的 `PAGE` 引用最终显示页码，`NUMBER` 引用编号条目／题注。引用支持有界多行预留，编号使用真实标签，页码按最多 10 位数字保守测量；分页后解析文字与内部链接。未知目标或无编号目标的数字引用明确失败。多级列表和详细边界见[生产能力 API](production-api.md)。

`Note(id, paragraphId, text, kind)` 的 `FOOTNOTE` 和 `ENDNOTE` 共用按传入顺序生成的 `[n]` 编号；正文追加上标标记。可引用段落、编号项或题注，不能附着任意组件。

旧调用的脚注跟随段落末片段；正文可用 `InlineField` 的 `NOTE_MARKER` 指定实际行内位置，见[行内字段 API](inline-fields-api.md)。每节单调预留最多 32 次，带高上限为原正文 40%。长脚注按完整行续页，必要时追加仅含脚注的页面；当页新增脚注至少显示首行，最小容量不足明确失败。无脚注页可能留白。尾注追加至最后一节继续正文分页。多栏脚注与 Word 级脚注排版仍未实现。

## 图片解码与 SVG

`ImageDecoder.decode(encoded, maxBytes, maxPixels)` 解码调用方提供的 PNG／JPEG 字节。先检查编码大小和尺寸，再解码；使用 JDK ImageIO 内存输入，不读取文件或 URL，不使用磁盘缓存。

`RgbImage` 可携带与像素数相同的 straight alpha 字节数组，原三参数构造器保持不透明。PDF 后端以 `/SMask` 输出透明通道。

`SvgComponent.parse(xml)` 是离线矢量子集，要求 `viewBox`，等比缩放并裁剪至该区域。支持 `g`、矩形、圆／椭圆、线、折线／多边形及显式绝对 `M/L/C/Z` 路径，颜色为六位十六进制 RGB 或 `none`。

输入最多 1,048,576 个 UTF-16 单元、10,000 个元素、64 层递归。尺寸只由 viewBox 和组件视口决定，根 `width`／`height` 属性当前拒绝。拒绝 DTD、外部资源、脚本、CSS、transform、相对／隐式路径命令及其他不支持的属性，避免静默丢失效果。这是 SVG 输入组件，不是完整 SVG 解析器或 SVG 输出后端。

## 中文表单、下拉框与单选组

`Navigation.TextField` 新增 `List<FontData>` 参数：空列表沿用 ASCII Helvetica，提供一个明确字体允许中文初始值和后续编辑。字体完整嵌入，不能只子集化初始文字，否则新增字符可能不可填写。默认值、当前值和外观均保留。

`Navigation.ChoiceField(target, name, options, value, fonts)` 为单选封闭下拉框，选项唯一且当前值必须存在于列表。`RadioGroup(target, name, options, value)` 建立一个规范父字段与多个 widget，选项为 ASCII 导出名，矩形内等间隔排列；外观当前使用方框，字段语义为单选。

~~~java
var textField = new Navigation.TextField(target, "name", "中文姓名", false, List.of(chinese.data()));
var choice = new Navigation.ChoiceField(target, "status", List.of("草稿", "审核", "完成"), "审核", List.of(chinese.data()));
var radio = new Navigation.RadioGroup(target, "mode", List.of("draft", "final"), "draft");
~~~

字体列表最多一个，不支持表单内字体回退；必须覆盖所需字符且满足嵌入许可。拒绝非法代理项和控制字符，多行文本例外允许 LF。初始值缺字失败，不采用正文的占位符策略。长值可由阅读器滚动显示，当前不承诺全部文字在框内可见，也未加入签名、脚本或自动扁平化。

## 兼容性与未覆盖范围

旧段落格式、段落、字形 run、组件上下文、布局结果和文本表单构造器保留兼容重载；新增 record 组件、枚举和 sealed 子类型仍可能影响反射、序列化、二进制和 exhaustive switch。项目尚未发布稳定 API。

显式复杂文字组件、矩形绕排、多栏、DOCX 子集与 SVG／Raster 输出已在后续批次实现，见[生产能力 API](production-api.md)和 [ADR-0008](adr/0008-production-capabilities.md)。完整中文禁则、完整 SVG 输入、Word 级兼容与通用 PDF 编辑仍未实现；显式简单塑形器的严格契约保持有效；默认公共路径已按需支持复杂文本，见[能力指南](capabilities-api.md)。

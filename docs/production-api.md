# 生产能力 API 与支持矩阵

本文对应 2026-10-14 起的本地实现。公共 API 尚未稳定，项目已统一命名为 Export4j，包名与 Maven group 为 `com.mirakyux.export4j`。资源与输出流由调用方提供，库不扫描系统字体、不联网下载资源、不打印警告。

## 统一调用入口

`DocumentEngine` 位于 `com.mirakyux.export4j.components`，通过 `DocumentBackend` 接受后端，不绑定 PDFBox。Builder 限定在单个任务内，`build()` 返回不可变快照；引擎可并发复用，自定义组件及后端须遵守自己的并发契约。

当前还支持引擎默认页模板／导航／注释，以及 `export(document).backend(...).options(...).to(...)`。具名构建与原子输出契约见[链式 API](fluent-api.md)。

~~~java
var latin = TrueTypeFontLoader.load(Files.readAllBytes(fontPath));
var fonts = FontGroup.strict(List.of(latin));
var engine = DocumentEngine.builder(fonts).build();
var document = Document.builder(new DocumentMetadata("报告", "作者"), PageSettings.A4)
        .heading("title", "Report", 1, ParagraphFormat.of(16))
        .paragraph("body", "Hello", ParagraphFormat.of(12))
        .build();
try (var output = Files.newOutputStream(pdfPath)) {
    var result = engine.write(document, new PdfBackend(), output);
    // 调用方决定如何显示 result.contextualDiagnostics()。
}
~~~

示例所用类型来自项目公共 API；完整可编译示例见 [`ProductionExample`](../examples/src/main/java/com/mirakyux/export4j/examples/ProductionExample.java) 与[隔离调用方](../verification/consumer/src/main/java/consumer/Consumer.java)。段落中的文字必须由显式字体覆盖。

多节使用 `engine.layout(sectionedDocument, templates, navigation, notes)`，模板数量与节对应。取得 `display()` 后交给所选后端。布局先完成再开始输出；后端写入过程中失败可能留下部分字节，调用方可使用 `writeAtomically` 完成临时文件及原子替换，见[受控导出](capabilities-api.md#取消超时与文件写入)。借用流不会被关闭。

## 字体组和复杂文字

| 路径 | 支持与约束 |
| --- | --- |
| 语义段落、`FlowText`、`TextComponent` | 默认自适应简单／OpenType 路径；组合字符、双向文字及 CFF 在文本层确定轮廓，显式注入简单塑形器仍保持严格限制 |
| `OpenTypeText` | 使用传入字体的 JDK OpenType 排版，支持 kerning、连字、组合字符、阿拉伯语连接字形、双向混排和常见中文标点禁则 |
| `FontScope(component, fontGroup)` | 在局部组件及续页片段内覆盖字体组，保留页上下文与缺字策略 |

~~~java
var text = new OpenTypeText("AVATAR a\u0301 العربية 123", style, RgbColor.BLACK);
var block = new ComponentBlock("shaped", new FontScope(text, complexFonts), 0, 0);
~~~

`style` 为文本模块的 `ParagraphStyle`，`complexFonts` 必须覆盖全部文字。七参数构造器可显式设置 `Direction.AUTO`／`LEFT_TO_RIGHT`／`RIGHT_TO_LEFT` 和 kerning、ligatures、cjkPunctuation 开关。固定行距必须容纳实际上升与下降高度，不自动缩字。

字体组默认 `ERROR`；`FontGroup.placeholder(fonts, codePoint)` 尝试整组后替换并返回 `MISSING_GLYPH_REPLACED`／`WARNING`。占位符也必须被字体支持。原码点与原 UTF-16 范围保留；一个字形簇必须由同一物理字体覆盖。非法代理项或无法完整覆盖的字形簇仍失败，不回退到系统字体。

`OpenTypeText` 最多 200,000 个输入码点、100,000 行及 2,000,000 个轮廓段。显式组件采用静态 TrueType，公共路径另支持静态 CFF；不支持彩色 emoji、可变字体轴、竖排或全部 OpenType 特性。常见标点禁则不等于完整东亚排版标准。

复杂文字在布局阶段生成轮廓；PDF 输出带 `/ActualText` 与不可见文字锚点。PDFium 已验证能提取样本逻辑文字，但整页提取顺序可能与视觉顺序不同；搜索、选择、复制依赖阅读器实现。未实现完整标签树或 PDF/UA，不能把轮廓文字当作通用无障碍方案。

## 多栏、绕排、表格、脚注和引用

| 功能 | 调用与边界 |
| --- | --- |
| 多栏 | `MultiColumn(pageableChild, columns, gap)`，2～16 栏，先从上到下再向右；按新页完整视口分栏，旧构造器不平衡，第四参数 `true` 启用有界末页平衡；剩余高度不足时移到新页 |
| 连续分栏 | `ContinuousColumns(regionFlow, columns, gap)`，从当前页剩余高度开始，续页完整栏高，末片段按实际高度接续；支持文字、`FlowColumn` 混合流和 `GridTable` 表格，详见[连续分栏 API](continuous-columns-api.md)与[混合分栏 API](mixed-columns-api.md) |
| 图文绕排 | `FloatText(...)` 保留单矩形入口；`MultiFloatText(text, floats)` 支持首片段最多 64 个左右矩形同时避让、错位／堆叠及整组移页；续页恢复全宽，详见[多浮动 API](multi-floats-api.md) |
| 文本表格拆行 | `new TableFormat(padding, before, after, true)` 显式开启；每个单元格按完整行推进，重复表头，完成的短单元格不重复文字 |
| 脚注续页 | 每节带高上限为原正文 40%，按完整行续页，可追加仅含脚注的页面；当页新增脚注至少显示首行，最小容量不足明确失败 |
| 换行引用 | `CrossReference` 预留多行，编号使用真实标签，页码按最多 10 位数字保守预留，最终分页后解析文字与链接 |

`TableFormat` 旧三参数构造器继续默认整行不可拆。`CellFragment.firstLineIndex()` 保存单元格起始行，`TableRowFragment.continuedRow()` 标识续行；合并 `GridTable` 默认连通组整体分页，显式 `allowRowSplit(true)` 支持受控完整行续排，见[续页指南](grid-continuation-api.md)。拆行不是横向分页或任意嵌套合并表格拆分。

显式 `InlineField.Kind.NOTE_MARKER` 让脚注跟随实际标记所在行；没有显式标记时沿用自动段末上标及末片段归属，见[行内字段 API](inline-fields-api.md)。统一节内预留可能造成留白，最多迭代 32 次。当较早脚注续页与新脚注竞争时，先为新增脚注保留一行，其余容量按待续顺序使用。不同脚注可以交错续页，未实现 Word 级脚注编号重置、续页提示和多栏脚注。

## 多级列表、样式继承和诊断

`NumberedItem(..., format, level)` 的 level 为 1～9，旧构造器使用第一级。相同 listId 按层级计数；回到上层时清空更深计数，不能跳过尚未出现的父级。`restartAt=0` 继续计数，正数重新开始。`BulletItem(id, text, marker, level, format)` 保存显式符号与层级，符号同样受字体缺字策略控制。

~~~java
var styles = StyleSheet.builder()
        .inline("base", InlineStyle.of(12))
        .inline("emphasis", "base", parent -> new InlineStyle(
                "bold", parent.fontSize(), 0x285080,
                parent.baselineShift(), true, parent.strikeThrough()))
        .paragraph("body", ParagraphFormat.of(12))
        .build();
~~~

继承在 `build()` 时解析，允许先声明子样式；未知父级、循环或重名明确失败。样式由调用方显式应用，字体角色须由 `DocumentEngine.builder(...).fontRole(name, group)` 配置，不能用假粗体代替真实字体变体。

`ComponentDocumentLayout.contextualDiagnostics()` 返回 `ContextualDiagnostic`：块 ID、最终从 0 开始的物理页索引、region、表格行列及原 `TextDiagnostic`。正文、表格、组件、目录、页区、脚注与引用警告均可查询。无块归属的页区 ID 为空，行列不可用为 -1；当前脚注诊断没有独立 noteId。原文位置使用 UTF-16；生成字段使用局部 UTF-16，并由 `INLINE_FIELD:<原始标记位置>` 区分。不能把物理页索引当作显示页码。此入口汇总警告，参数错误及不可恢复的布局失败仍抛出明确异常。

## 静态 SVG 和 PNG

~~~java
try (var output = Files.newOutputStream(svgPath)) {
    new SvgBackend().write(layout.display(), output);
}
try (var output = Files.newOutputStream(zipPath)) {
    new RasterBackend(144).write(layout.display(), output);
}
~~~

SVG 自包含，文字为已确定的轮廓，图片以内嵌 PNG 保存。多页 SVG 垂直堆叠，页间 20 pt，各页独立裁剪。PNG 后端默认写 ZIP，文件名从 `page-00001.png` 开始；`writePage(...)` 输出单页 PNG。DPI 显式传入，范围 1～1200。

现有 `GlyphText` 使用既定字形编号转轮廓，不重新塑形。标准 14 字体的 `DrawCommand.Text` 没有物理字节，需向后端构造器提供显式 `Map<FontFamily, FontData>`，缺失则失败。

两种后端都拒绝非空 `DocumentExtras`，不能静默丢弃字段、书签、评论与链接。调用方若明确需要静态副本，可以显式构造不含 extras 的 DisplayDocument。预算为最多 10,000 页、2,000,000 条命令／码点、5,000,000 个路径段、50,000,000 个源图片像素和 14,400 pt 页面边长；Raster 总输出不超过 100,000,000 像素。

## DOCX 语义子集

`DocxAdapter` 位于 `com.mirakyux.export4j.adapter.docx`。`write(Document/SectionedDocument, OutputStream)` 导出 OOXML，`read(InputStream, Policy)` 返回 `ReadResult`，含 `SectionedDocument` 和损失诊断。

| 结构 | 当前范围 |
| --- | --- |
| 段落与行内 | 文本、字号、RGB、角色、单线下划线、删除线、基线偏移、换行、tab、嵌入 PNG／JPEG 与 HTTP／HTTPS／mailto 超链接 |
| 标题 | 1～6 级，导入 Heading 样式；不支持继承 Word 默认样式格式 |
| 列表 | 自有多级十进制／符号列表往返；外部自定义十进制标签、起始值及覆盖明确报损或拒绝 |
| 表格 | 矩形文本／富文本／行内图片单元格、固定／比例／自动列宽、统一内边距、表头、拆行设置；不支持合并／嵌套与任意块图文格 |
| 分页与分节 | 显式分页、nextPage 分节、不同纸张与页边距、起始页码 |
| 元数据 | 标题和作者 |
| 行内字段 | PAGE／NUMPAGES／PAGEREF／编号 REF，含正文与普通单元格；显式宽度导入，重复表头 PAGE 拒绝导出 |

自有 `od:*` 忽略命名空间保留 ID、列表身份与重启、比例列宽等模型信息；常规 OOXML 编号、样式、关系与内容类型仍写入文件，独立 python-docx 已验证可读取。Word 重新保存可能丢弃扩展信息，不能据此承诺自有 ID 永久保留。

`STRICT` 拒绝未知标记、未知文档部件和不能保留的格式；`ALLOW_LOSS` 返回有界诊断。未知段落继承、样式默认值、非支持对齐、行距规则及外部编号覆盖不能静默变成默认值。当前针对标准 `word/document.xml` 路径，未实现任意包关系重定位或完整 Word 样式级联；一般 Word 文档常需损失模式和诊断审查。

宏内容类型和 VBA 部件在两种模式都拒绝。外部超链接关系映射为被动 URI，其他外部关系报损或拒绝，不加载目标；DTD、实体、路径穿越与重复 ZIP 名拒绝。默认限制：压缩输入 32 MiB、每条目 32 MiB、总展开 128 MiB、4,096 条目、100,000 块、XML 深度 128；可用 `DocxLimits` 收紧。损失诊断最多 10,000 条。导出不支持组件块、题注或块级交叉引用时，在写流前失败。

本地 Word 16.0 已验证字段样例更新、另存和 PDF 导出；一般 Word／LibreOffice 的视觉兼容尚未验证。不支持页眉页脚、复杂图片变换、目录、评论、表单、修订、嵌套字段或任意 Word 无损互转。行内图像与媒体范围见[图像 API](inline-images-api.md)。导入后的模型由项目自身布局，不复现 Word 排版算法。

`InlineField` 原生字段映射、复杂字段导入、宽度与书签约束见[DOCX 字段 API](docx-fields-api.md)。导出初始缓存为空，由 Word 更新；不把 PDF 解析值写成 Word 页码。

## 本地制品验证

`export4j-bom` 对齐 12 个库；`publishLocalBundle` 只写入 `build/repository`，包含二进制、源码、Javadoc、POM 和 Gradle 元数据。`sbom` 生成 `build/reports/sbom.cdx.json`，记录实际运行制品与 SHA-256；不包含测试工具、JDK 或示例字体，资源另见[字体记录](../assets/fonts/README.md)。

[`verify_local_consumer.ps1`](../scripts/verify_local_consumer.ps1) 在独立 Gradle 工程中仅通过 Maven 坐标与 BOM 编译运行，不使用 project 依赖。完整命令、验证数值和范围见[验收记录](verification/phase-4-production.md)。

新增模板、富文本单元格、外链、自动列宽、字体集合、执行控制及 PDF 安全／归档／签名详见[能力指南](capabilities-api.md)和 [ADR-0009](adr/0009-shared-text-templates-and-controlled-export.md)。

# 字体和文本 API

多脚本、缺字替换后的原文簇、双向续行及阅读器搜索／选区实测见[文字语料](text-corpus-api.md)和 [ADR-0026](adr/0026-source-clusters-and-shaping-context.md)。查询成功、逻辑原文与逐字选区几何分别判断；轮廓锚点不提供通用提取或精确选区保证。

当前 API 支持静态 TrueType／CFF、显式集合成员与自适应简单／OpenType 排版，提供显式字体回退、测量、段落换行、缩进、对齐和固定行距。布局结果可直接转为 PDF 绘制命令；页面可由调用方创建，也可由[文档布局 API](layout-api.md)自动分配。

## 运行示例

仓库根目录执行：

~~~powershell
.\gradlew.bat build :examples:textPdf
~~~

生成 `examples/build/export4j-text-example.pdf`。完整代码见 [TextPdfExample](../examples/src/main/java/com/mirakyux/export4j/examples/TextPdfExample.java)。示例使用仓库内已登记来源和许可的[字体资源](../assets/fonts/README.md)，无需系统字体或运行时下载。

## 加载、布局与绘制

以下片段在仓库根目录运行，字体文件由调用方显式读取：

~~~java
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.TextLayoutPainter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

var latin = TrueTypeFontLoader.load(Files.readAllBytes(
        Path.of("assets/fonts/LiberationSans-Regular.ttf")));
var chinese = TrueTypeFontLoader.load(Files.readAllBytes(
        Path.of("assets/fonts/LXGWWenKaiTC-Regular.ttf")));
var style = new ParagraphStyle(14, 24, 28, 0, TextAlignment.LEFT);
var layout = new ParagraphLayouter().layout(
        "中文与 English 混排。先测量与换行，再输出 PDF。", List.of(latin, chinese), 523, style);
var page = new DisplayListBuilder(PageSize.A4);
TextLayoutPainter.draw(page, layout, new Point(36, 60), RgbColor.BLACK);
var document = new DisplayDocument(DocumentMetadata.EMPTY, List.of(page.build()));
try (var output = Files.newOutputStream(Path.of("text.pdf"))) {
    new PdfBackend().write(document, output);
}
~~~

字体模块提供 `FontFace`、`FontData` 与 em 单位的度量；文本模块提供 point 单位的字形 advance 和行布局。绘制核心及 PDF 后端依赖会传递这些项目模块，公共 API 不含第三方类型。

## 字体加载与回退

`TrueTypeFontLoader.load(byte[])` 复制输入字节，默认最多 32 MiB。借用流版本 `load(InputStream, int maxBytes)` 最多读取 `maxBytes + 1` 字节以判断超限，不关闭调用方流。调用方需自行关闭文件流；加载器释放自身解析资源。

`FontData` 保存不可变字节、SHA-256 标识及是否允许子集化。返回的字节数组也是副本。已加载 `FontFace` 没有打开的句柄，可共享；自定义 SPI 实现须自行遵守线程安全契约。

按字体组顺序选择第一个支持码点的字体，选用其他字体时产生 `FONT_FALLBACK`／`INFO` 诊断，包含原文 UTF-16 范围与所选字体标识。`FontGroup.strict(fonts)` 默认缺字报错；`FontGroup.placeholder(fonts, 0x25a1)` 显式启用占位符及 `MISSING_GLYPH_REPLACED`／`WARNING`。先尝试整个组，占位符本身缺字仍失败，原文 UTF-16 来源范围保持不变。不扫描系统字体。嵌入标志之外的许可证义务仍由调用方核查。

`FontCollectionLoader.load(bytes, memberIndex)` 显式提取 TTC／OTC 的零基成员；集合和成员各有字节预算。静态 OTF/CFF 由 `TrueTypeFontLoader` 加载，默认段落通过轮廓输出。可变字体、CFF2、竖排和彩色字体尚未支持，CFF 不能用于 PDF 可编辑字段。

## 段落几何与来源范围

`ParagraphStyle(fontSize, lineHeight, firstLineIndent, subsequentLineIndent, alignment)` 的长度均为 point。字号和行距为有限正数，缩进为有限非负数，宽度必须大于两种缩进。`ParagraphStyle.of(size)` 默认左对齐、无缩进、行距为字号的 1.5 倍。

第一行使用首行缩进，其余行使用后续行缩进；显式换行后的行仍属于同一段落。LEFT、CENTER、RIGHT 在扣除对应缩进后的区域内对齐。

整个配置字体栈的最大 ascent 与最大 descent 定义行框，复杂路径同时考虑 JDK 的物理字体度量。`ParagraphLayouter.minimumLineHeight(group, size)` 提供兼容两类路径的下限。即使某行未使用回退字体，基线间距也保持不变。第一行基线为所选路径的最大 ascent；后续基线每次增加 `lineHeight`。行距须容纳行框，字体自带 line gap 不再叠加。布局高度为行数 × 行距，额外行距保留在行框下方。

`ParagraphLayout` 和 `TextLine` 保存局部坐标、宽度、简单字形 `runs()` 或中立 `outlines()` 以及诊断。调整基线须使用 `atBaseline()` 保留两类绘制结果。`TextLayoutPainter` 的 origin 是段落左上角；`drawGlyphRun` 的位置是 run 的基线左端。

LF、CR 和 CRLF 产生显式换行，空输入及末尾换行保留空行。行的源范围包含换行时消耗的 U+0020 分隔空格，不包含 CR/LF；显式换行之前未参与软换行的空格保留绘制。软换行去除行尾分隔空格，并消耗下一行前的分隔空格。断行不拆开代理对。

换行采用 ROOT 区域设置的 JDK 断行规则；无合法断点时按码点紧急断行。普通中文标点用例已覆盖，但极窄区域可能违反完整中文禁则，当前不承诺 UAX #14 或专业中文排版一致性。

## 塑形支持边界

`TextShaper` 是扩展接口。`SimpleTextShaper` 对普通水平拉丁、汉字、希腊、西里尔、假名与预组合韩文逐码点映射，不实现 kerning、连字、字形簇、字形偏移、GSUB/GPOS 或双向排序。显式注入该 shaper 时保持严格限制。默认 `ParagraphLayouter` 对复杂文字与 CFF 改走公共 JDK OpenType 路径，语义段落、单元格和固定页区可共享塑形结果。纯拉丁文字显式 kerning／连字开关仍使用组件 `OpenTypeText`，详见[能力指南](capabilities-api.md)。

简单塑形器拒绝复杂或双向脚本、组合标记和多码点序列。默认公共路径支持样例中的阿拉伯语、希伯来语与组合字符，每个字形簇必须由一个显式物理字体覆盖；跨样式边界的字形簇明确失败。普通补充平面码点可以参与布局，但实际字体必须提供字形；这不代表支持多码点 emoji 序列或彩色 emoji。调用方可显式预先做 Unicode 规范化，库不隐式改写原文。

## 预算与错误

默认每次段落布局最多 100,000 个 Unicode 码点、10,000 行；公共 OpenType 路径另限制最多 2,000,000 个轮廓段。以下示例显式选择简单塑形器及自定义限制：

~~~java
var layouter = new ParagraphLayouter(new com.mirakyux.export4j.text.SimpleTextShaper(),
        new com.mirakyux.export4j.text.TextLayoutLimits(20_000, 2_000));
~~~

| 异常 | 稳定代码 | 含义 |
| --- | --- | --- |
| `FontLoadException`（I/O 异常） | `INVALID_FONT` | 无效或不支持的字体格式 |
| `FontLoadException` | `EMBEDDING_RESTRICTED` | 禁止轮廓嵌入 |
| `FontLoadException` | `BUDGET_EXCEEDED` | 字体原始字节超限 |
| `TextLayoutException` | `INVALID_TEXT` | 孤立代理项或不支持的控制字符 |
| `TextLayoutException` | `MISSING_GLYPH` | 配置字体均缺字 |
| `TextLayoutException` | `UNSUPPORTED_SHAPING` | 需要尚未提供的塑形能力 |
| `TextLayoutException` | `GLYPH_TOO_WIDE` | 一个字形已超过行内宽度 |
| `TextLayoutException` | `LINE_HEIGHT_TOO_SMALL` | 行距小于配置字体栈行框 |
| `TextLayoutException` | `BUDGET_EXCEEDED` | 码点或行数超限 |

文本异常携带从 0 开始的原文 UTF-16 索引，不包含正文。无效尺寸等入口参数使用普通 `IllegalArgumentException`；空值使用 `NullPointerException`。PDF 输出预算、结构化错误与流所有权见[绘制 API](graphics-api.md)。

## 确定性与扩展

固定字体字节、回退顺序、文本、宽度、样式和 JDK 断行规则时，重复布局结果一致；不以 PDF 文件字节一致作为验收条件。基础布局器可共享，注入的自定义 shaper 必须线程安全。

字体字节复制、解析及文本预算约束特定规模，不构成不可信输入沙箱或执行时间限制。文本模块本身不分页；语义 Document 与自动分页位于模型及布局模块。当前没有字体全局缓存；`StyledSpan`、`InlineText` 与 `InlineStyle` 已支持行内字体角色、字号、颜色、上下标和装饰，详见[进阶 API](advanced-api.md)。

初始公共接口变化见 [ADR-0003](adr/0003-font-text-layout.md)；字体组策略、富文本接口及兼容重载见 [ADR-0007](adr/0007-advanced-layout-and-font-policy.md)。警告由调用方遍历 `diagnostics()` 并选择输出方式，库不自行打印日志；完整代码见[字体组与缺字策略](advanced-api.md#字体组与缺字策略)。

后续行内图像通过默认自适应路径参与共享换行，`TextLine.images()` 保存图片几何，`atBaseline()` 在分页时保留它。U+FFFC 图片标记不请求字体字形；固定行框必须容纳字体与图片，显式注入的文字塑形器拒绝图片。来源、预算与 DOCX 媒体规则见[行内图像 API](inline-images-api.md)。

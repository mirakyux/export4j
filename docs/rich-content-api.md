# 混合内容与跨格式公式

当前开发版提供 `MathContent`，将明确声明的文本、Markdown 或 HTML 编译一次，同时得到可交给 `DocumentEngine` 的视觉文档和可交给 `DocxAdapter.writeMath` 的原生 Word 数学文档。此入口不包含在已发布 RC3 中。

## 一次编译，两个目标

```java
var compiler = MathContent.builder()
        .textFonts(MathTextFonts.of(latinFontBytes, chineseFontBytes))
        .defaults(ContentOptions.builder()
                .format(ContentOptions.Format.MARKDOWN)
                .rawHtml(true).formulas(true).tables(true).build())
        .fallbacks(List.of(MathContent.Fallback.IMAGE, MathContent.Fallback.TEXT))
        .build();
var result = compiler.read(markdown);
engine.write(result.document(), new PdfBackend(), pdfOutput);
var word = DocxAdapter.builder()
        .defaultFonts(new DocxFontFamilies("Liberation Serif", "Noto Serif SC"))
        .lineSpacing(DocxAdapter.LineSpacing.OBJECTS_AT_LEAST)
        .build();
word.writeMath(result.docx(), docxOutput);
// 检查 result.degradations()，它逐式、逐目标列出实际降级。
```

以上类型分别位于 `com.mirakyux.export4j.math`、`model`、`components`、`backend.pdf` 和 `adapter.docx` 包。应用显式提供正文物理字体、输出流和图片；`defaultFonts` 声明 Word 收件端字体族，不会搜索或嵌入系统字体。输出流仍归调用方所有。

标题、有序／无序列表、普通段落和表格中的行内图片、矢量及数学对象通过同一混合内容模型保留；列表前缀不会改写正文 UTF-16 锚点。重复表头中的原生数学使用真实物理单元格锚点，不需要把表头改造成块单元格。独立展示公式编译为独立居中段落；Word 使用标准 `m:oMathPara`。

Java 的文档便利入口使用 `headingContent(content, level)`、`bulletContent(content)` 和 `numberedContent(listId, content)` 接收 `List<? extends InlineContent>`，加入时快照内容并解析当前格式。原有 `heading`／`bullet`／`numbered` 的 `List<InlineText>` 签名和行为保留；需要显式 ID、嵌套层级或重启编号时使用对应块的 `mixed(...)`／Builder。

`MathContent.Result` 同时持有两个不可变目标及公式出现清单。目标表示在编写边界生成，PDF 后端不解释 TeX，核心模型不依赖数学库。相同 TeX 的每次出现分别绑定；编译身份在中间模型的值压缩后仍保持，最终视觉文档不含假槽。

## 开关、代码与资源

`format` 始终明确选取 TEXT、MARKDOWN 或 HTML。MARKDOWN 下的 `rawHtml(true)` 是独立、可继承的原始 HTML 开关，默认关闭；XML 对应 `raw-html`。开启后只映射既有离线 HTML 子集，不执行脚本，也不访问资源。公式、表格分别开关，不依据正文猜测。

`MathContent` 默认使用 MARKDOWN 并开启公式；普通 `ContentImporter` 默认使用 TEXT 并关闭公式。公式字号来自每个位置最终解析的 `ContentOptions`，包括单元格和局部外观覆盖；`MathContent.Builder.options(MathOptions)` 只提供公式预算等参数，不以其中的 `fontSize`／`display` 覆盖源位置。原生 DOCX 要求半 pt 字号，12.3pt 等不能准确表达的外观以 `UNSUPPORTED_APPEARANCE` 进入显式候选；没有候选则失败，不静默取整。

支持的代码块和行内代码映射为保留空白及换行的文字，内部美元符号不参与公式识别；不提供语法高亮或代码语言元数据。HTML 属性、代码、资源目标和受保护链接不参与公式配对。未知活动结构、属性和样式仍依照严格导入契约失败。

用 `image(key, InlineImage)` 登记准确资源键。原图尺寸、解码和质量政策由调用方显式选择；公式图片候选由库从已验证轮廓生成。普通 `<td>` 首行不推断为表头；全表均为表头时保留行内容及表头外观，作为一次性普通表输出。

## 降级与几何

默认候选列表为空，表示严格失败。显式 IMAGE 候选把受支持轮廓转为 PNG；默认每 pt 4 像素，支持配置，原始轮廓质量不降低。放置时只允许等比缩放，默认最小比例 0.5，可用 `minimumImageScale` 声明。不能放置时继续下一候选；TEXT 保留完整 TeX 及行内／展示分隔符。

报告包含公式索引、VISUAL／DOCX 目标、实际候选和原因，不含正文。两个目标的支持子集不同，不能把矢量可绘制等同于 OMML 可编辑。预算、取消和超时不能通过降级绕过。每式支持子集及上限继续见[数学 API](math-api.md)；不提供任意 LaTeX、宏、外联、MathML 或 OCR。

本入口默认扩展对象段落的行框以容纳实际图片及轮廓。编译器不持有正文物理字体及基线度量，因此在对象基线两侧保留原文字行框的安全余量，可能增加行距；不能把字号当作文字 ascent，也不能忽略文字 descent。`expandObjectLines(false)` 严格保留原固定行距，放不下由布局失败。公式拟合扣除首行／后续行中的较大缩进及列表层级缩进；连续分栏采用最窄列作保守预检，栅格采用实际跨列宽度及间隙。分栏／栅格的语义容器保留，DOCX 尚不支持的布局仍明确拒绝。

含本次公式的表格使用明确 Fixed／Fraction 列及实际跨度、内边距；这类表的 Auto 列需要先由调用方确定列宽。不含本次公式的普通 Auto 表保持原行为。此入口不使用猜测宽度承诺 Word 几何；原生 Word 数学的最终字体度量和分页由阅读软件决定，轮廓宽度检查不能替代阅读器验收。

## 公式中的中文与显式字体

`MathTextFonts.of(byte[]... fonts)` 为 `\text{...}`／`\textrm{...}` 提供按顺序选字的显式物理字体。最多 8 份，单份不超过 32 MiB，总计不超过 64 MiB。建立配置时先校验总字节预算，再快照并使用不保留结果的字体加载器验证静态独立 TTF／OpenType CFF。可变字体、集合字体、受限／仅位图嵌入和要求完整字体程序嵌入的字体均拒绝，不能通过 Java2D 绕过既有字体权限契约。

文字子集为 BMP 内从左到右的 Latin、汉字、假名、韩文、希腊文和西里尔文字及常用数字／标点；不支持组合字符、复杂脚本、补充平面字符或方向控制。缺字明确失败，不搜索系统字体，不注册全局字体，也不改写上游字体映射。数学符号仍使用随数学模块提供的固定字体；裸中文不因此成为数学语法。

例如 `(\bar{x} \pm s) / \text{岁}` 可通过上述显式字体生成真实轮廓；`bar`／`overline` 当前属于矢量子集，不能据此声称原生 Word 数学已支持。Word 目标需要调用方声明 IMAGE／TEXT 候选，否则严格失败。轮廓入口可直接使用 `TexMath.vector(tex, options, textFonts)`，源内容入口可使用 `TexMath.processor(options, textFonts)`。

`MathTextFonts` 仅保留编码字节的私有快照，不永久保留解析字体。每次含文字组的矢量准备在调用内解析，每份至多一次，结束后局部字体即可释放；这不是跨公式解析缓存。`MathContent`／processor 会持有传入配置，其字节随配置存活，需释放这些调用方引用才可回收；正文 `FontFaceCache` 的到期不会释放该配置。

## Java 与 XML 组合

```java
var result = compiler.compile(importer -> XmlTemplate.builder()
        .contentParser(importer).build()
        .compile(xmlSource).render(data));
```

回调同步执行、不被保存，可组合既有 Java `DocumentTemplate.Source` 或 XML `content`／`scope`。所有源内容由同一显式解析器处理；最终结果必须包含本次实际编译的每个公式，丢弃或重复出现会失败。XML 模板模块由应用按需引入，数学模块不反向依赖它。

`limits(ImportLimits)`、`maxFormulas` 和 `maxVectorSegments` 具有明确单位并保持累计；可合理提高总容量，不再把旧 Markdown 便利入口的 256 式默认值当作不可提高的业务门槛。默认解析工作预算不等于总堆或 RSS 上限。

## 字体空闲释放

长期单例可使用 `DocumentEngineProvider` 保存 `FontSource` 配置，在同步 `withEngine` 内完成输出；provider 不永久保存 `FontFace`、`FontGroup` 或引擎。默认拥有有界字体缓存，显式传入的缓存由应用关闭。缓存到期只解除缓存引用；返回的布局、输出会话或应用自行保留的引擎仍可能持有字体。不会强制 GC，也不保证 RSS 到期立即下降。具体入口见[字体缓存](font-cache-api.md)。

实际构建、制品消费和独立结构／渲染范围见[本批验收](verification/rich-content.md)。

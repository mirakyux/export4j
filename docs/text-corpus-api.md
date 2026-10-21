# 多脚本文字与阅读器语料

两条文字入口继续保持简洁：普通语义段落由默认 ParagraphLayouter 按需塑形；需要显式方向、kerning 或连字配置时使用 OpenTypeText。调用方只需显式提供能覆盖整簇的物理字体，无需手动分成阿拉伯字母或组合标记。

~~~java
var fonts = FontGroup.strict(List.of(arabicFont, latinFont));
var document = Document.builder()
        .bodyFormat(ParagraphFormat.builder().fontSize(16).lineHeight(40).build())
        .paragraph("العَرَبِيَّة لا سلام 123 English")
        .build();
DocumentEngine.builder(fonts).build().export(document)
        .backend(new PdfBackend()).to(outputPath);
~~~

尺寸以 point 计。字体由调用方显式加载，不扫描系统字体；固定行距须容纳整个字体组。完整代码见 [TextCorpusExample](../examples/src/main/java/com/mirakyux/export4j/examples/TextCorpusExample.java)。

## 簇与换行

缺字替换仍由 FontGroup 选择 `ERROR` 或 `PLACEHOLDER_WITH_WARNING`。占位符不能改变原文簇的原子性；例如 `a` 加缺失组合标记被显示为 `a?`，二者仍保持同一个原文簇，放不下时失败。返回诊断继续使用原文 UTF-16 索引。

复杂路径同时考虑原文保守簇和物理字体实际字形簇。ROOT Character BreakIterator 加天城文 virama／ZWJ 链补充只是一项明确子集，字体实际连接簇还会约束断点；不宣称 UAX #29 完整实现。复杂文字不能被任意切成独立字符，再通过多次绘制拼接。

续行继承所在段落的双向基方向；显式换行建立新段方向。同字体内容共同塑形后再应用可分离的颜色、上下标及装饰。样式边界穿过实际连字／组合簇时失败，调用方应在完整簇边界设置样式。普通文字的原有简单度量保持，显式配置字距的行为不变。

## 可复现语料

运行 `:examples:textCorpus`，再执行 `python scripts/verify_text_corpus.py`。验证工具须使用[固定版本](../scripts/requirements-verification.txt)。

| 语料 | 显式主字体 | 检查重点 |
| --- | --- | --- |
| 拉丁组合文字 | Liberation Sans | 组合标记、多个词与显式换行 |
| 希伯来文 | Liberation Sans | 元音标记、数字和拉丁混排 |
| 阿拉伯文 | Noto Naskh Arabic | 连接字形、元音、lam-alef 与双向内容 |
| 天城文 | Noto Sans Devanagari | virama 连接簇、前置元音、换行 |
| 泰文 | Noto Sans Thai | 组合标记与 ROOT 断行的实际结果 |
| 中文 | LXGW WenKai TC | 标点、拉丁混排及普通路径对照 |

所有文案由项目自编，字体原始来源、许可、版本与 SHA-256 见[字体记录](../assets/fonts/README.md)。每组同时通过语义段落与组件生成独立 PDF，manifest 保存原文、查询词、主字体和实际页数。不能以一次字体覆盖推断整个脚本的全部排版特性。

## 提取与选择限制

验证报告把逻辑来源、渲染、提取、查询与选区分别记录。PDFium 查询词及其选中文字精确匹配，只证明该引擎版本的样本结果。轮廓锚点的选区高度很小，不能作为精确逐字选区证据；pypdf 不消费这些 ActualText 时，只会提取到不可见 `x`。报告保留这些差异，不反转字符串或移除失败用例以显示通过。

脚本记录 PDFium 引擎 API，没有模拟真人在阅读器界面中搜索／拖选。Poppler 始终参与渲染；若本机没有 pdftotext，报告的 Poppler 提取值为 null。完整阅读器、辅助阅读器、PDF/UA、其他 JDK／操作系统、完整 OpenType、彩色 emoji 与竖排均不在本批合规声明内。

设计依据见 [ADR-0026](adr/0026-source-clusters-and-shaping-context.md)，总验收见[阶段二十二](verification/phase-22-text-and-adapters.md)。

# 工程笔记：PDF 原文编码与字形标识分离

状态：已实现

## 先说结论

普通 TrueType 文字的 PDF 字符编码按原文 Unicode 分配，不能直接把字体 GID 当作原文标识。同一字形可以对应多个 Unicode；字形、原文和显式 advance 必须分别保留。

## 问题

真实字体可将 U+4E1F「丟」与 U+4E22「丢」映射到同一 GID。直接用 GID 作为 CID 时，两者只能共用一项 ToUnicode，导致页面看起来正确，提取、搜索或选区却混淆原文。仅检查字形位置或生成成功无法发现该缺陷。

## 决策

[PdfUnicodeFont](../../../../export4j-backend-pdf/src/main/java/com/mirakyux/export4j/backend/pdf/PdfUnicodeFont.java) 为不同 Unicode 标量分配不同的两字节 CID，通过 CIDToGIDMap 选择实际嵌入字形，以 ToUnicode 保留原文，并同步生成宽度与 CIDSet。实际 GID 仍由字体编码校验，不能信任调用方声明或用 ActualText 代替正确字符映射。

CID 0 留给 `.notdef`；分配时避开 D800–DFFF，防止部分 Identity-H 消费者先按 UTF-16 解码产生代理项歧义。因此每个字体资源容纳 63,487 个不同码点，超出后使用另一字体资源，共享字体程序。补充平面原文在 ToUnicode 中使用完整 UTF-16 对。

[PdfBackend](../../../../export4j-backend-pdf/src/main/java/com/mirakyux/export4j/backend/pdf/PdfBackend.java) 对一个 GlyphRun 保持一个 BT／初始 Tm／ET。切换字体资源前，先输出旧字体下的完整 TJ，包括最后一个字形的自定义 advance，再切 Tf。重新按绝对坐标启动文字对象会引入额外量化和栅格相位差。

需要子集化的原字体仅在保存前显式子集化一次，Unicode 包装字体不参与自动子集化。子集快照保留原来的字体程序表示，编码层与程序层的复用分别处理，见[文字与 PDF 复用取舍](../simplification/2026-11-12-bounded-text-pdf-reuse.md)。公共使用边界见[文字 API](../../../../docs/text-api.md)。

## 考虑过的备选方案

- 继续以 GID 编码，只依赖 ActualText：不能修复底层 ToUnicode 的多对一冲突，无法替代原生搜索、选区与提取验证。
- 假设单字体的两字节编码永不耗尽：一个 GID 可以被许多不同 Unicode 共享，真实字形数量不能限定原文码点数量。
- 切字体时重新 BT／Tm：字形几何可通过检查，但高倍独立渲染曾出现抗锯齿相位差；保留连续文字状态可避免该差异。

## 后果

原文与字形可以分别保真，普通、逐页和磁盘流式输出采用相同规则；子集与完整嵌入都需要独立字符元数据。代价是额外 CID 映射和 ToUnicode，以及容量耗尽时增加字体资源，不能据此宣称所有阅读器均无限制。

## 验证

- [EmbeddedTextTest](../../../../export4j-backend-pdf/src/test/java/com/mirakyux/export4j/backend/pdf/EmbeddedTextTest.java) 覆盖同 GID 的不同 Unicode、补充平面、子集／完整嵌入和普通／逐页输出。
- [PdfSpoolFontReuseTest](../../../../export4j-backend-pdf/src/test/java/com/mirakyux/export4j/backend/pdf/PdfSpoolFontReuseTest.java) 覆盖复用后的原文、不同编码与共享程序。
- [PdfUnicodeFontTest](../../../../export4j-backend-pdf/src/test/java/com/mirakyux/export4j/backend/pdf/PdfUnicodeFontTest.java) 在运行时构造有界 cmap，验证 63,500 个不同码点及返回旧资源的两次切换，共 63,502 glyph，并检查边界额外 1/32 pt 推进、完整原文、位置与程序共享。不增加字体二进制或测试依赖。
- 独立样例中，pypdf、PDFium、Poppler 精确提取原文；PDFium 搜索与选区检查通过，28 个整页及 4 个 128 倍边界窗口的两引擎各自前后像素一致。PDF/A-2b 和 PDF/UA-1 样例通过 veraPDF，结果仅限对应样例。

极小字号的容量样例需要分为多个文字对象，Poppler 提取另需显式高 DPI；保留完整码点、位置和像素断言，不能将读取器限制解释为允许丢字。验证层次见[输出验收技能](../../../skills/verify-export4j-output/SKILL.md)。

## 适用范围

适用于 PDF 后端普通 TrueType 字形文字的编码与资源生命周期。CFF 保持既有编码路径；复杂塑形、轮廓文字、表单字体和通用阅读器兼容性不由本笔记扩展。

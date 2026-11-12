# 工程笔记：文字与 PDF 优化保留资源和输出契约

状态：已实现

## 先说结论

减少重复复制、逐字查找和 PDF 序列化工作时，复用必须有明确所有权、生命周期与上限。缓存命中仍须验证实际字形、处理取消并计费源工作量；输出压缩只能省略语义等价的表示。

## 问题

字体载入、单样式段落和重复表格文字会重复分配或查找；逐页 PDF 还可能反复解析字体、构建子集和写出相同字体资源。只追求耗时或文件变小，会掩盖外部数组被接管、跨文档对象泄漏、预算绕过或显式字距丢失等回归。

## 决策

- [FontData／TrueTypeFontLoader](../../../../export4j-font-core/src/main/java/com/mirakyux/export4j/font/TrueTypeFontLoader.java) 仅通过包内所有权转移接管 loader 私有快照；调用方数组及可覆写流返回的数组仍先复制，保持加载后不可变。
- [ParagraphLayouter](../../../../export4j-text-core/src/main/java/com/mirakyux/export4j/text/ParagraphLayouter.java) 对单 StyledSpan 省去逐字样式查找表，多样式段保留原路径。格式、字体回退、软／硬换行及 UTF-16 来源不变。
- [PdfBackend](../../../../export4j-backend-pdf/src/main/java/com/mirakyux/export4j/backend/pdf/PdfBackend.java) 合并连续自然 advance 的字符串，保留每个非零 TJ；不能因数值格式化后接近零就丢弃调用方位移。矩形 `re` 仅在起点、边序、绕向及序列化后端点保持一致时替代 `m/l/h`，其余路径保留原表示。

字体复用分别管理以下对象，不将它们合成一个无界或全局缓存：

| 对象 | 范围与上限 | 保留的约束 |
| --- | --- | --- |
| [字形映射](../../../../export4j-backend-pdf/src/main/java/com/mirakyux/export4j/backend/pdf/PdfGlyphMappingCache.java) | 文档／Writer 内，最多 16 字体、每字体 256 码点 | 超限正常计算，每个 glyph 仍核对实际 GID；Writer 关闭清空，普通调用结束释放 |
| [解析后的字体程序](../../../../export4j-backend-pdf/src/main/java/com/mirakyux/export4j/backend/pdf/PdfFontPrograms.java) | 会话内，最多 8 项、32 MiB 编码源字节 | 当前页借用项固定到页关闭；超限回到页所有的解析对象；会话关闭释放解析资源 |
| [子集快照](../../../../export4j-backend-pdf/src/main/java/com/mirakyux/export4j/backend/pdf/PdfFontSubsets.java) | 会话内，最多 64 项、16 MiB；每字体页级最多 8,192 个 Unicode／GID 对 | 跨页仅保留不可变快照，不保留已关闭页面的 PDFont／COS 对象；不能缓存时正常生成 |
| [spool 字体根与 FontFile2](../../../../export4j-backend-pdf/src/main/java/com/mirakyux/export4j/backend/pdf/PdfSpoolBackend.java) | 两个独立的 256 项 LRU | 程序相同不代表 ToUnicode 相同；命中仍遍历完整源对象图计费，关闭时清空索引 |

可缓存的页级 Unicode 集合用于预留稳定 CID，避免字符出现顺序不同破坏字体根复用；这不替代实际字形校验。动态编码的不同 ToUnicode 可以只共享程序，原文与编码分离的原因见[Unicode 修复笔记](../bug-fix/2026-11-12-pdf-unicode-glyph-aliases.md)。

## 考虑过的备选方案

- 直接接管外部数组：调用方可能在加载后修改，破坏字体不可变契约。
- 跨页保存 PDFBox 字体／COS 对象：它们依赖原文档与流的生命周期，不能代替独立快照。
- 只按字体程序合并字体根：相同程序可能配有不同 Unicode 编码，会重现原文混淆。
- 舍弃很小的非零 TJ 或无条件使用矩形 `re`：会改变调用方显式位移或最终序列化几何。

## 后果

常见重复工作可以减少，超出缓存上限仍保持正确输出。限额约束的是对应账本或源字节，不是进程 RSS、字体解析后的真实堆占用或 PDFBox 页树的全程恒定内存。

性能判断需要固定输入、分离计时与渲染等重负载、使用多个独立 JVM，并同时检查内容及几何。主线程分配不等于堆或 RSS；有限样例和有重叠的耗时区间不能证明稳定提速或长期无泄漏。

## 验证

- [TrueTypeFontLoaderTest](../../../../export4j-font-core/src/test/java/com/mirakyux/export4j/font/TrueTypeFontLoaderTest.java) 的 `streamReturningCallerOwnedBytesStillProducesAnIndependentImmutableFace` 覆盖流返回外部数组的所有权边界。
- [ParagraphLayouterTest](../../../../export4j-text-core/src/test/java/com/mirakyux/export4j/text/ParagraphLayouterTest.java) 的 `oneStyledSpanRetainsFormatFallbackAndUtf16RangesAcrossSoftAndHardBreaks` 覆盖单样式优化的来源与格式。
- [EmbeddedTextTest](../../../../export4j-backend-pdf/src/test/java/com/mirakyux/export4j/backend/pdf/EmbeddedTextTest.java) 覆盖字符串合并、非零 advance、真实 GID 校验、跨字体／跨文档隔离与超限后正确性。
- [PdfBackendTest](../../../../export4j-backend-pdf/src/test/java/com/mirakyux/export4j/backend/pdf/PdfBackendTest.java) 的 `compactsRectangularPathsOnlyWhenTheirSerializedEndpointsAndEdgeOrderStayExact` 及有限数值边界回归，验证矩形压缩的严格条件。
- [PdfSpoolFontReuseTest](../../../../export4j-backend-pdf/src/test/java/com/mirakyux/export4j/backend/pdf/PdfSpoolFontReuseTest.java) 验证不同子集、原文编码、完整程序共享、LRU 淘汰及缓存命中仍计费完整源对象图。

这些回归防止编译检查无法发现的内容、所有权与几何变化。独立输出验证继续遵循[输出验收技能](../../../skills/verify-export4j-output/SKILL.md)，不以缓存命中或文件体积代替内容正确性。

## 适用范围

适用于字体载入、段落样式查找及 PDF 普通／逐页／spool 输出的内部优化。此处上限是当前实现取舍，不是新增公共 API 或普遍容量承诺；更改时应同步源码、行为回归和相关结论。

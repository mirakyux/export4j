# 工程笔记：文字与 PDF 优化保留资源和输出契约

状态：已实现

## 先说结论

减少重复复制、逐字查找和 PDF 序列化工作时，复用必须有明确所有权、生命周期与上限。缓存命中仍须验证实际字形、处理取消并计费源工作量；输出压缩只能省略语义等价的表示。

## 问题

字体载入、单样式段落和重复表格文字会重复分配或查找；逐页 PDF 还可能反复解析字体、构建子集和写出相同字体资源。只追求耗时或文件变小，会掩盖外部数组被接管、跨文档对象泄漏、预算绕过或显式字距丢失等回归。

## 决策

- [FontData／TrueTypeFontLoader](../../../../export4j-font-core/src/main/java/com/mirakyux/export4j/font/TrueTypeFontLoader.java) 仅通过包内所有权转移接管 loader 私有快照；调用方数组及可覆写流返回的数组仍先复制，保持加载后不可变。
- 同一程序跨角色使用时仍应复用已加载的 `FontFace`。当前加载器还按完整内容哈希自动复用不可变加载结果，空闲后解除缓存引用；跨导出的解析字体独占借用及主动释放以 [ADR-0114](../../../../docs/adr/0114-idle-expiring-font-caches.md) 为准。
- [ExplicitPhysicalFontCache](../../../../export4j-text-core/src/main/java/com/mirakyux/export4j/text/ExplicitPhysicalFontCache.java) 向 JDK 提供不可变 `FontData` 的独立只读游标，避免 `bytes()` 的完整克隆；流遵守无符号单字节、偏移、零长度及 EOF 契约。JDK 后续解析及临时资源仍由其管理。
- FontBox 使用未修改的官方 3.0.8 运行制品，版本由现有版本目录及依赖锁固定；不编译或内嵌上游源码，也不保留补丁构建分支。
- [ParagraphLayouter](../../../../export4j-text-core/src/main/java/com/mirakyux/export4j/text/ParagraphLayouter.java) 对单 StyledSpan 省去逐字样式查找表，多样式段保留原路径。格式、字体回退、软／硬换行及 UTF-16 来源不变。
- [PdfBackend](../../../../export4j-backend-pdf/src/main/java/com/mirakyux/export4j/backend/pdf/PdfBackend.java) 合并连续自然 advance 的字符串，保留每个非零 TJ；不能因数值格式化后接近零就丢弃调用方位移。矩形 `re` 仅在起点、边序、绕向及序列化后端点保持一致时替代 `m/l/h`，其余路径保留原表示。

字体复用分别管理以下对象，不将它们合成一个无界或全局缓存：

| 对象 | 范围与上限 | 保留的约束 |
| --- | --- | --- |
| 基础物理字体 | 进程内，最多 32 程序、64 MiB 源字节权重，空闲 5 分钟过期 | 按 SHA-256 去重，同步 miss；仅保存基础 Font 和权重，不保留 FontData 或派生字号，超限正常加载 |
| [字形映射](../../../../export4j-backend-pdf/src/main/java/com/mirakyux/export4j/backend/pdf/PdfGlyphMappingCache.java) | 文档／Writer 内，最多 16 字体、每字体 256 码点 | 超限正常计算，每个 glyph 仍核对实际 GID；Writer 关闭清空，普通调用结束释放 |
| [解析后的字体程序](../../../../export4j-backend-pdf/src/main/java/com/mirakyux/export4j/backend/pdf/PdfFontPrograms.java) | 会话内最多 8 项／32 MiB；另有相同默认容量、空闲 5 分钟过期的跨导出解析池 | 当前页借用项固定到页关闭；同一解析对象不同时交给多个会话；超限回到本次独立解析，解析资源按租约归还或关闭 |
| [子集快照](../../../../export4j-backend-pdf/src/main/java/com/mirakyux/export4j/backend/pdf/PdfFontSubsets.java) | 会话内，最多 64 项、16 MiB；每字体页级最多 8,192 个 Unicode／GID 对 | 跨页仅保留不可变快照，不保留已关闭页面的 PDFont／COS 对象；不能缓存时正常生成 |
| [spool 字体根与 FontFile2](../../../../export4j-backend-pdf/src/main/java/com/mirakyux/export4j/backend/pdf/PdfSpoolBackend.java) | 两个独立的 256 项 LRU | 程序相同不代表 ToUnicode 相同；命中仍遍历完整源对象图计费，关闭时清空索引 |

可缓存的页级 Unicode 集合用于预留稳定 CID，避免字符出现顺序不同破坏字体根复用；这不替代实际字形校验。动态编码的不同 ToUnicode 可以只共享程序，原文与编码分离的原因见[Unicode 修复笔记](../bug-fix/2026-11-12-pdf-unicode-glyph-aliases.md)。

## 考虑过的备选方案

- 直接接管外部数组：调用方可能在加载后修改，破坏字体不可变契约。
- 跨页保存 PDFBox 字体／COS 对象：它们依赖原文档与流的生命周期，不能代替独立快照。
- 只按字体程序合并字体根：相同程序可能配有不同 Unicode 编码，会重现原文混淆。
- 舍弃很小的非零 TJ 或无条件使用矩形 `re`：会改变调用方显式位移或最终序列化几何。
- 将 FontBox 解析改成文件输入或 `parseEmbedded`：3.0.8 的这两种完整解析路径仍创建内部快照；只读头部入口不能替代用于 PDF 嵌入的完整程序。反射、运行时包内注入及强删解析表会破坏依赖和生命周期契约，不采用。
- 将 Path 输入包装为 `FilterInputStream`，接管其继承 `readNBytes` 的结果以省一次 clone：独立分配测量发现，包装使文件流失去 JDK 的按文件大小读取优化，分块分配和拼接抵消了所省的 clone，且增加小对象；未保留该方案。减少显式 clone 数量不等于减少总分配。
- 将 FontBox 的 `glyf` 复制改为共享快照视图：局部实验减少了解析分配，但未建立稳定的中文进程峰值降幅。独立保留字形表或复合字形描述可能延长整个字体快照的存活；同包源码内嵌还增加依赖冲突与后续升级责任，因此没有采用为生产依赖，也不维护可选构建分支。

## 后果

常见重复工作可以减少，超出缓存上限仍保持正确输出。限额约束的是对应账本或源字节，不是进程 RSS、字体解析后的真实堆占用或 PDFBox 页树的全程恒定内存。

性能判断需要固定输入、分离计时与渲染等重负载、使用多个独立 JVM，并同时检查内容及几何。主线程分配不等于堆或 RSS；有限样例和有重叠的耗时区间不能证明稳定提速或长期无泄漏。

官方 FontBox 3.0.8 的完整解析仍会复制源程序，`glyf` 初始化还会产生临时整表副本和保留副本。直接借用 Export4j 源字节不代表依赖内部也无复制，`close()` 也不保证仍被 PDF 字体对象引用的数组立刻回收。调用方重复加载、解析分配和长期存活应分别测量，局部实验不作为正式集成性能结论。

## 验证

- [TrueTypeFontLoaderTest](../../../../export4j-font-core/src/test/java/com/mirakyux/export4j/font/TrueTypeFontLoaderTest.java) 的 `streamReturningCallerOwnedBytesStillProducesAnIndependentImmutableFace` 覆盖流返回外部数组的所有权边界。
- [ParagraphLayouterTest](../../../../export4j-text-core/src/test/java/com/mirakyux/export4j/text/ParagraphLayouterTest.java) 的 `oneStyledSpanRetainsFormatFallbackAndUtf16RangesAcrossSoftAndHardBreaks` 覆盖单样式优化的来源与格式。
- [ExplicitPhysicalFontCacheTest](../../../../export4j-text-core/src/test/java/com/mirakyux/export4j/text/ExplicitPhysicalFontCacheTest.java) 验证真实 TTF／CFF 字体、程序哈希复用、并发 miss、LRU、源字节限额及超限不驻留。克隆消除的收益另用独立 JVM 和主线程分配计量确认，不把缓存身份断言当作内存下降证据。
- [EmbeddedTextTest](../../../../export4j-backend-pdf/src/test/java/com/mirakyux/export4j/backend/pdf/EmbeddedTextTest.java) 覆盖字符串合并、非零 advance、真实 GID 校验、跨字体／跨文档隔离与超限后正确性。
- [PdfBackendTest](../../../../export4j-backend-pdf/src/test/java/com/mirakyux/export4j/backend/pdf/PdfBackendTest.java) 的 `compactsRectangularPathsOnlyWhenTheirSerializedEndpointsAndEdgeOrderStayExact` 及有限数值边界回归，验证矩形压缩的严格条件。
- [PdfSpoolFontReuseTest](../../../../export4j-backend-pdf/src/test/java/com/mirakyux/export4j/backend/pdf/PdfSpoolFontReuseTest.java) 验证不同子集、原文编码、完整程序共享、LRU 淘汰及缓存命中仍计费完整源对象图。

这些回归防止编译检查无法发现的内容、所有权与几何变化。独立输出验证继续遵循[输出验收技能](../../../skills/verify-export4j-output/SKILL.md)，不以缓存命中或文件体积代替内容正确性。

## 适用范围

适用于字体载入、段落样式查找及 PDF 普通／逐页／spool 输出的优化。内部会话缓存的上限是实现取舍；公开加载缓存与 PDF 池通过[字体缓存 API](../../../../docs/font-cache-api.md)配置。更改时应同步源码、行为回归和相关结论。

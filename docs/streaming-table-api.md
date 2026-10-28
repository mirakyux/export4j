# 单向长表 API

本页描述默认 `export` 的固定列单次源路径，适用于普通／富文本及显式图片、整行或完整文字行分页的大量记录。正文由调用方逐行提供，无须构造完整 Table。显式 `allowRowSpans(true)` 还可启用[有界纵向合并连通组](streaming-row-spans-api.md)，默认模式不与拆行组合。需要递归块格、跨页 owner 或自动宽／未来信息时，使用[语义／重放入口](streaming-replay-api.md)。基础实现边界见 [ADR-0031](adr/0031-single-pass-table-output.md)，真实字体角色和局部样式见[单向长表富文本 API](streaming-rich-text-api.md)及 [ADR-0036](adr/0036-streaming-rich-text.md)。长度单位均为 point，原点左上，页索引从零开始。

## 链式导出

~~~java
var format = CellFormat.builder().fontSize(10).lineHeight(16).build();
var spec = StreamingTable.builder("orders")
        .pageSettings(PageSettings.A4)
        .columns(90, 300, 120)
        .cellFormat(format)
        .header("编号", "说明", "状态")
        .padding(3)
        .build();

var result = engine.export(spec, () -> openOrderRows(format))
        .backend(PdfBackend.builder()
                .options(options -> options.storage(PdfOptions.Storage.TEMP_FILE)).build())
        .limits(limits -> limits.maxCellCodePoints(2_000).maxRowCodePoints(5_000))
        .diagnostics(this::recordWarning)
        .options(options -> options.timeout(Duration.ofSeconds(60)))
        .to(Path.of("orders.pdf"));
~~~

`openOrderRows` 由应用实现，返回 TableRowSource。`next()` 每次返回一行，结束时返回 Optional.empty()；返回 null 属于错误。普通 TableRow／TableCell 复用自身格式；规格的 `cellFormat` 只影响随后添加的便捷表头。列宽必须留出正文字宽，总宽可小于正文宽，但不得超过。表头始终完整；正文默认整行，显式 allowRowSplit(true) 支持[完整文字行续排](streaming-row-split-api.md)。超高原子行明确失败，空源恰好生成一页。

源工厂在 `to()` 时打开，每次导出须创建新的源。引擎先开后端，再开源；成功时先关闭源，再完成输出和关闭写出器。库不管理源借用的数据库连接，连接所有权由应用明确。to() 开始时快照后端、预算、回调和执行配置；回调重入改请求只影响下一次导出。请求与源应在单个任务内使用，规格和无可变共享资源的引擎可以复用。库不创建后台线程或隐式获取资源。

`StreamingExportSummary` 只含正文行数、物理页数、源单元格／码点数、输出行、顶层绘制命令、诊断和输出字节。源计数含表头一次；输出行／诊断包含重复表头。字节是实际提交给目标流的字节，不能当作后端临时磁盘占用。诊断包含 FONT_FALLBACK／INFO 与 MISSING_GLYPH_REPLACED／WARNING，同步交付给回调，不保留无限列表；回调自身保留的内存由应用负责。

## 预算、失败与所有权

StreamingTableLimits 组合 DocumentLayoutLimits 和逐格／逐行码点、运行数、累计诊断、实际字段呈现及生成值上限。`maxCellInlines`／`maxRowInlines` 默认分别为 4,096／16,384，包含空运行；没有显式运行的普通格按一个有效运行计费。源表头与正文共同占表格行／格／码点预算，重复表头占输出行及诊断预算，不重复占源运行数预算。规格不含段落块，因此 maxParagraphs 不参与此路径。PDF 累计页、命令、字体、文字、图片与路径预算仍由 PdfLimits 控制。取消、超时和输出字节使用 ExecutionOptions。

未知总行数／页数时 `Progress.total()` 为 -1，先检查 `hasKnownTotal()`，不能直接计算百分比。源读取、回调及自定义后端均为协作式同步调用，无法强制抢占阻塞代码。

格错误通过 DocumentLayoutException 给出表 ID、物理页、源行／列与格内 UTF-16 偏移；行索引包含前置表头。诊断使用 ContextualDiagnostic，源文字 region 为 STREAMING_TABLE；生成字段值使用 INLINE_FIELD:<原字段UTF16位置>，诊断范围属于生成值。诊断／页交付失败立即停止读源；默认路径最多已预读一行，显式纵向合并路径最多已读取一个受预算限制的连通组，不预读下一独立组。原始 IOException 保留，关闭异常遵守 try-with-resources 的 suppressed 顺序。

流入口借用目标流且不关闭，失败可能留下部分字节。路径入口要求父目录存在、文件系统支持同目录原子替换；任何读取、布局、关闭、输出、取消或字节预算失败都保留旧目标，清理临时文件，不降级复制。

## 逐页扩展与限制

高级调用可直接使用 StreamingTableLayouter.layout，将 LayoutPage 同步交给 PageConsumer；核心不关闭源或接收器。DocumentPainter.paintPage 转为 DisplayPage 后交 PageWriter.accept。必须显式 finish，再 close；close 不完成输出。失败会话不能继续接受页。SPI 不保存传入页，但格式对象可以随页数增长。

本页默认模式支持普通文字格、InlineText 富文本及显式 PNG／JPEG 图片，沿用显式 FontGroup 的严格缺字或占位诊断。可通过 DocumentEngine.Builder.fontRole 注册真实字体变体；高级布局使用带角色 Map 的重载，旧重载保持空映射。被动文字／图片外链通过逐页链接通道传递，见[图文 API](streaming-media-api.md)。PAGE 与准确声明总数的 PAGES 已支持，见[字段 API](streaming-fields-api.md)。正文纵向合并须显式启用 `allowRowSpans(true)`，见[有界纵向合并 API](streaming-row-spans-api.md)；默认模式不与拆行组合，表头纵跨、块格、自动宽度和嵌套仍拒绝。配置过的页模板、导航、注释也拒绝，避免静默忽略。未知总页数、目录／未来引用、组脚注和自动宽由显式重放路径处理；分栏／平衡、DOCX 及自动签名没有因此自动接入流规格。

普通 `PdfBackend` 仍保留页树／COS 对象，TEMP_FILE 只转移流存储。需要逐页释放 PDF 对象、递归语义格、跨页纵向 owner 或显式未来语义时，使用[有界语义长表与重放 API](streaming-replay-api.md)，并选择 `PdfSpoolBackend`。该入口还提供有限前缀 Auto 探测、稳定快照摘要核对、最终总页数、目录／引用／书签和组脚注；原 `export` 的单次行预读行为保持。容量结果分别报告布局、真实 PDF 和进程／堆指标，有限测试不代表永久无泄漏。单向源不替代既有完整文档 API。

## 固定列横向合并

表头和逐次提供的正文行可使用 `TableCell.columnSpan`。每行的真实锚点按顺序完整覆盖固定列，允许各行有不同锚点数量：

~~~java
var row = new TableRow(List.of(
        TableCell.builder().text("合并前两列").format(format).columnSpan(2).build(),
        new TableCell("第三列", format)));
~~~

跨度宽度为所覆盖列宽之和，只扣一次左右内边距，不生成内部假格或重复文字。IR、异常和缺字诊断仍使用物理起始列：上例第二个锚点的列号为 2。每行全部检查后才测量，整行移动时复用测量结果，表头也只测量一次。

`sourceCells` 只计真实锚点；`DocumentLayoutLimits.maxCells` 计全部覆盖槽，不能通过大跨度绕过预算。原文、运行和码点按锚点计一次，重复表头继续占实际输出行／诊断预算。仅横向合并不需要读取未来行，最多一行预读的契约保持；显式纵向合并改用有界连通组。正文完整行拆分见[拆行 API](streaming-row-split-api.md)。横向合并实现与独立证据见[ADR-0072](adr/0072-single-pass-horizontal-spans.md)及[阶段六十四](verification/phase-64-streaming-column-spans.md)。

AES-256 与静态 PDF/A-2b 已接入逐页后端，见[单向安全输出](streaming-protection-api.md)；归档仍拒绝链接，签名在最终文件上显式处理。

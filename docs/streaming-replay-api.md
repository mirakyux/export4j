# 有界语义长表、重放与逐页 PDF

`DocumentEngine.exportSemantic` 单次读取语义行源，支持 `Paragraph`／`Table` 块格、递归嵌套以及完整行拆分的纵向 owner。`exportReplay` 在相同有界布局上增加显式快照重放，可计算未知总页数、未来引用、目录、书签和组脚注。`PdfSpoolBackend` 将 PDF 对象限制在单页工作集，使用磁盘索引完成整份文件。

## 单次读取语义行源

```java
var spec = StreamingTable.builder("orders")
        .columns(80, 240, 120)
        .header("批次", "内容", "金额")
        .semanticCells(true)
        .allowRowSpans(true)
        .allowRowSplit(true)
        .build();
Path temporaryParent = Files.createDirectories(Path.of("work"));
engine.exportSemantic(spec, () -> openOrderSnapshotRows())
        .limits(limits -> limits.maxBufferedRows(32).maxBufferedCodePoints(100_000))
        .backend(new PdfSpoolBackend(temporaryParent))
        .to(Path.of("orders.pdf"));
```

`engine` 使用覆盖源文字和生成数字的显式字体组。`openOrderSnapshotRows()` 由应用提供，每次返回新拥有的 `TableRowSource`。格内容使用现有 `TableCell.builder().blocks(...)`，子树规则与普通语义表相同，最多 16 层表格。各层表格的拆行开关独立，原子子树放不下时仍明确失败。正文列跨度、纵跨度、真实物理列、原文 UTF-16、子块序号、行内图文及外链都保留。

单次源支持实际 `PAGE`。`PAGES` 需要准确的 `expectedPages(n)`，完成前核对实际总数；未知时使用 `exportReplay`。单次源只打开一次，关闭来源后才完成后端。跨页后重复表头只计实际输出预算，后续独立组不会插入额外表头或强制新页。

## 显式重放与自动宽

```java
var replay = StreamingReplayPlan.builder()
        .columns(List.of(new ColumnWidth.Auto(80, 120),
                         new ColumnWidth.Fraction(1),
                         new ColumnWidth.Fixed(80)), 64, 1_000_000)
        .rowTarget("summary", 120, "121")
        .blockTarget("details", "details-paragraph", "1")
        .bookmark("明细", "details", 1)
        .bookmark("汇总", "summary", 1)
        .contents("目录 / Contents")
        .footnote("price-note", 0, "金额按本次快照计算。")
        .build();
engine.exportReplay(spec, () -> openOrderSnapshotRows())
        .plan(replay)
        .backend(new PdfSpoolBackend(temporaryParent))
        .to(Path.of("orders.pdf"));
```

无自动宽探测时开源两次：首遍确定分页和声明目标，第二遍输出。配置 `columns` 时增加一次明确的前缀探测，最多读取指定正文行数和规范序列化字节；探测必须包含完整的纵向连通组，不能为了补齐组突破上限。空正文可按表头和明确最小值计算宽度。探测结果固定为 point 列宽，后面的内容按该宽度换行，不重新调整前页。探测列数必须与规格列数相同。

行源工厂须重开同一业务快照。可使用数据库快照或由应用拥有的稳定文件；不应重新查询不断变化的数据。库比较探测前缀和完整语义 SHA-256，文字、格式、运行、跨度、块树和图片内容标识均参与，任一变动使导出失败。工厂、读取和关闭均同步调用，各遍来源在下一遍前关闭。没有自动创建数据库事务、隐式文件读取或全量正文缓存。

`rowTarget` 从 0 开始计正文物理行，不包含表头；目标落在完全覆盖行时指向覆盖 owner 的首次呈现。`blockTarget` 指向指定语义子块的首次片段。`REFERENCE_PAGE`／`REFERENCE_NUMBER` 字段使用声明的目标 ID，编号文字由目标的 `number` 提供。未声明、未出现、没有编号或重复源块 ID 均明确失败。字段继续占原文的一个 U+FFFC，使用固定 pt 预留框；解析不会改变行边界，值放不下时失败。

`PAGES` 使用包含目录的最终物理总数，目录、书签及引用目标统一偏移；可选 `expectedPages` 也核对最终总数。目录只由有限显式书签生成，不扫描并缓存全部正文来猜测标题。

## 组脚注与预算

`footnote(id, bodyRow, text)` 锚定一个纵向连通组的首个正文行，在该组首次呈现页为完整注释预留空间，仅输出一次。普通独立行也是一行组。源文字可显式放置 `REFERENCE_NUMBER` 字段，目标为脚注 ID，显示生成的 `[n]`；标记预留宽度由应用指定。脚注不能隐式锚定组内部后续行，也不会被偷偷移到组首；这类声明失败。注释过高而没有正文空间也失败，不裁切或缩小。

`StreamingTableLimits` 限制单叶／单源行的码点和运行、连通组行数／码点、全部源节点／覆盖槽／码点、物理页、输出行、字段、生成文字、链接和诊断。真实锚点只计一次源，嵌套节点计 `maxParagraphs`，重复表头计实际呈现。目录文字、脚注和字段值共同占生成文字预算；输出脚注／目录行也占总输出行预算。诊断同步交付，不保留无界警告列表；嵌套路径及源 UTF-16 保留。

跨组唯一性检查保留已见语义块 ID，数量受 `maxParagraphs` 限制；它不保存完整正文，但也不属于单页对象图。大量独立子块需要按实际 ID 数和长度安排内存，不能把普通行容量样例解释为任意语义树使用同样内存。

## PDF 对象与临时盘

`PdfSpoolBackend` 每次只保留一个渲染页的 PDFBox 对象图，接受后释放；xref 和页索引保存在唯一自有临时目录，页树叶最多保留 256 个页引用。`PdfSpoolLimits` 另外限制页、累计命令、每页／总对象、单页中间文件、临时索引与输出字节。导航声明为有限列表，未来页对象在磁盘索引中提前预留。格式对象不会因累计保留全部 PDFBox 页面而增长。

每页独立嵌入所用字体，文件可能大于共享全局字体的输出。普通 PDF 阅读器仍接收标准 PDF 1.7 对象和 xref；该后端不自动生成 PDF/A／PDF/UA、加密或签名。需要这些模式时使用其专用配置和验收流程。声明更多资源、并发会话或更高预算会提高实际内存，不能把有限堆样例解释为任意输入的固定进程内存。

`PdfSpoolLimits.maxTemporaryBytes` 约束后端当前中间页和磁盘索引，不包括路径入口正在写入的完整原子目标；后者由 `maxOutputBytes` 及执行字节预算约束。容量采样把两者一并计入临时磁盘。应用应另外保留旧目标和最终新文件所需空间，不能把索引预算当作整次导出的总磁盘需求。

后端必须接收 `PageNavigation` 才能保留目录／未来引用的内部链接和书签；不支持的写出器默认明确拒绝非空导航。普通 `PdfBackend` 继续可用于已有配置，累积文档对象的边界没有被误写成该后端也自动有界。

临时父目录须存在且由应用拥有。来源、写出器、临时页和索引由本次导出释放，目标流始终借用。路径输出使用同目录原子替换；布局、输入变更、关闭、预算、取消和输出异常均保留旧目标并清理自有临时项。借用流上的失败可能留下已写字节，应用应以整次成功作为提交条件。没有自动重试。

`StreamingTable` 仍是专门的流规格，不作为普通 `Block` 进行 XML、DOCX 或模型恢复序列化。重复表头仍不支持纵向跨度，已配置的全局页模板和完整文档导航／注释需改为此入口的明确计划。

可复现调用方任务为 `streamingReplay`；独立结构和双渲染检查使用 `python scripts/verify_streaming_replay.py --consumer-build-directory <本次构建目录>`。容量和连续退出验证使用 `scripts/measure_streaming_replay.ps1`，再用 `scripts/verify_streaming_replay_capacity.py` 核对全部行与抽样渲染。设计见 [ADR-0102](adr/0102-bounded-semantic-streaming-replay-and-pdf-spool.md)。

完整实现、11 页组合样例、两轮独立容量及采样限制见[阶段一百零二验收](verification/phase-102-semantic-streaming-replay-and-pdf-spool.md)。报告区分采样堆使用、进程工作集、私有字节、句柄和临时磁盘；10 万行样例只证明该固定字体与输入规模，未执行跨平台或无限期运行验收。

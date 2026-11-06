# 普通文档逐页导出

`DocumentEngine.exportPaged` 在导出时逐页交付布局、绘制和后端，返回 `PagedExportSummary`，适用于不需要保留完整布局结果的调用方。现有 `layout`／`export` 继续返回完整结果。输入可以是 `Document`、`PageDocument` 或 `SectionedDocument`。

本入口属于开发源码，已发布的 `0.1.0-rc.1` 不包含它。

## 调用

```java
import java.nio.file.Files;
import java.nio.file.Path;
import com.mirakyux.export4j.backend.pdf.PdfSpoolBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.Document;

// fonts 来自调用方显式加载的字体，document 为不可变语义模型。
var engine = DocumentEngine.builder(fonts).build();
var temporaryParent = Files.createDirectories(Path.of("work"));
var summary = engine.exportPaged(document)
        .backend(new PdfSpoolBackend(temporaryParent))
        .to(Path.of("report.pdf"));
System.out.println(summary.pages());
System.out.println(summary.outputBytes());
```

`to` 接受 `Path`、`File`、字符串本地路径及借用的 `OutputStream`。路径使用同目录临时文件与原子替换；规划、重放、取消、关闭或输出失败时保留旧目标。输出流不关闭，失败可能留下部分字节。临时父目录须存在且归调用方拥有，后端清理自己的索引目录。

`limits`、`componentLimits` 和 `options` 分别配置布局、组件与执行预算。`diagnostics` 同步交付上下文诊断；摘要保留计数，不收集全部警告。请求在 `to` 开始时快照配置，自定义回调在调用线程执行。

大文档需按输入显式配置这些独立预算，以及后端的页数、指令和输出限制。默认 `ComponentLimits` 为 10,000 个布局放置、1,000,000 条绘制指令和 10,000 个交互；增加页数上限不会自动增加它们。超出预算时导出明确失败，不代表发生内存不足。

## 按需输入内容块

不需要预先保存完整模型时，可由 `BlockSource` 按次产生普通内容块：

```java
import java.util.Optional;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BlockSource;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;

BlockSource.Factory source = () -> new BlockSource() {
    private int index;
    @Override public Optional<Block> next() {
        if (index == 100) { return Optional.empty(); }
        int number = ++index;
        return Optional.of(new Paragraph("record-" + number,
                "Record " + number, ParagraphFormat.of(10)));
    }
};
var summary = engine.exportReplay(PageSettings.A4, source)
        .backend(new PdfSpoolBackend(temporaryParent))
        .to(Path.of("records.pdf"));
```

`exportReplay` 明确打开来源两次：首遍确定分页及轻量摘要，第二遍解析最终页字段并立即输出。两次必须读取相同业务快照；来源工厂不能重新查询不断变化的数据。文字、格式、原始来源及布局变化使导出失败。来源的默认 `close` 不做操作；自有连接、游标或文件应由实现关闭，外部借用资源仍由调用方负责。

若应用准确知道最终物理页数，可使用 `engine.exportSemantic(settings, source).expectedPages(count)`，只打开来源并布局一次。`count` 必须为正数，结束前精确核对；声明过大或过小均失败，不缩小内容或补空白页凑数。未知总数应显式选择 `exportReplay`。

两种请求均支持 `metadata`、`limits`、`componentLimits`、`options`、`diagnostics` 及四种 `to` 目标。请求继承引擎的固定 `pageTemplate`，单遍的配置快照、同步取消、源关闭先于 `finish`、路径原子替换和输出流借用契约保持。

该入口支持普通段落、标题、列表、题注、表格、分栏、栅格、组件、图片、外链和 `PAGE`／`PAGES`，每次只保留当前页来源、最大块和必要 keep 链。跨节设置、目录、未来引用、脚注重排、评论和可编辑表单继续使用完整模型入口；不支持的声明明确拒绝。

`BlockSource` 按块释放输入，单个 `Table` 仍含完整行列表；巨大长表需要按行释放时使用 [TableRowSource](streaming-replay-api.md)。全局 ID 索引和重放页摘要仍随受限节点／页数增长。底层 `DocumentLayouter.layoutBlocks` 借用来源及回调，每页同时交付原始来源快照，调用方自行承担输出原子性及来源关闭。

## 完整模型的分页及语义

引擎先规划，再重放输出。普通段落、标题、列表、表格、分栏、组件、图片及链接沿用现有模型和排版规则。分节纸张、显示页码、首页／奇偶页区、总页数字段、目录、书签、未来引用和脚注使用最终分页信息；物理页索引与显示页码分别保留。

没有脚注重排时，正文通常经过分页规划、输出预检和实际输出三遍布局；脚注预留可能增加有限轮规划。这样可以提前确定最终目标、检查全局预算并拒绝不确定的重放，同时减少完成页的对象保留，但会增加布局和绘制成本。需要保留完整布局结果或优先考虑小文档延迟时，继续使用 `layout`／`export`。

`PageDocument` 的模型页区与非空 Java `pageTemplate` 冲突。分节文档声明模型页区时，也不能同时设置非空 Java 模板；需要明确选择时使用接收逐节 `SectionTemplate`、`Navigation` 及 `Note` 列表的重载。自定义组件须确定性地重复测量，规划与重放不一致时导出失败。

评论和可编辑表单不在 `PageWriter` 的表达范围内，非空声明会在打开写出器前拒绝。需要这类交互时继续使用完整 `export` 入口。输出标准和认证能力由所选后端决定，逐页入口不扩大它们的支持范围。

## 内存边界

完成页的 Layout IR、Display List 和已消费块的测量缓存会释放，图片／边框缓存跨页复用且有上限。`exportPaged` 的不可变输入模型仍完整存在；块流入口按需读取和释放输入。两者的最大块、表格或组件准备工作集、keep 链和有限索引仍占内存。完整模型的大量源节点和导航会增加索引内存；脚注计划还保留已经测量的文字切片，内存随脚注文本增长。

完整入口也会释放已消费块的测量缓存，并在单次布局内有界共享完全相等的普通字形布局。共享发生在测量及预算计费之后，保留完整的字体、来源、几何、样式和诊断；图片、字段及轮廓布局不进入该共享缓存。字段解析保留未变化的页面及片段，释放已被替换的装配引用。完整入口仍返回全部 IR 和绘制结果，内存需求会随文档增长。

`PdfBackend` 会累计文档格式对象；`PdfSpoolBackend` 复制完成的页对象后关闭单页 PDFBox 文档，使用磁盘索引构造最终文件。它不生成中间单页 PDF 文件，但保留字体子集最终化和单页序列化字节检查。相同字体及完整 Unicode／原字形 ID 映射可复用有界的最终化子集快照，每页重建独立字体对象，省去重复子集生成。临时工作预算保守计入当前页的序列化等价大小，原子目标完整字节另受输出预算约束。

数据库等单次行源继续使用 [exportSemantic／exportReplay](streaming-replay-api.md)。逐页处理完整模型与逐行读取外部来源是两种输入契约，容量结论须分别验证。

设计见 [ADR-0111](adr/0111-page-at-a-time-document-export.md)和 [ADR-0112](adr/0112-incremental-block-source.md)，已执行范围见[逐页导出验收](verification/paged-document-export.md)。

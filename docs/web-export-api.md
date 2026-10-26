# HTML 与 Markdown 导出

`export4j-adapter-web` 直接将 `Document`、`PageDocument` 或 `SectionedDocument` 转为 HTML5 或 Markdown。两种输出均为 UTF-8，不需要浏览器、字体文件或第三方 Java 运行依赖。浏览器／Markdown 阅读器负责显示与换行；该适配器不复用 PDF 坐标或承诺相同分页。

这与[Markdown 模板输入](markdown-template-api.md)是独立方向。导出的 GFM 表格、图片与安全生成标签不保证能由严格模板输入语法重新导入。通用文档输入使用[Markdown 导入器](markdown-import-api.md)或[离线 HTML/CSS 导入器](html-import-api.md)；跨格式串联与完整损失报告见[统一转换](document-conversion-api.md)。设计见 [ADR-0050](adr/0050-semantic-web-exports.md)，完整样例见 [WebExportsExample](../examples/src/main/java/com/mirakyux/export4j/examples/WebExportsExample.java)，证据见[阶段四十三](verification/phase-43-web-exports.md)。

## 依赖与调用

本地 BOM 对齐后添加 `com.mirakyux.export4j:export4j-adapter-web`。当前仍为未正式发布的开发快照，构建与隔离消费见[发布规范](releasing.md)。

```java
import com.mirakyux.export4j.adapter.web.ExportFeature;
import com.mirakyux.export4j.adapter.web.ExportPolicy;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.adapter.web.UnsupportedAction;
import com.mirakyux.export4j.model.Document;
import java.nio.file.Path;
import java.util.List;

var document = Document.builder()
        .heading("Quarterly report / 季度报告", 1)
        .paragraph("Text, links and table content remain searchable.")
        .table(List.of("Metric", "Value"), List.of(List.of("Revenue", "120")))
        .build();

var html = new HtmlAdapter().export(document).to(Path.of("report.html"));
var policy = ExportPolicy.builder()
        .defaultAction(UnsupportedAction.ERROR)
        .on(ExportFeature.TABLE_STRUCTURE, UnsupportedAction.WARN)
        .on(ExportFeature.TABLE_GEOMETRY, UnsupportedAction.IGNORE)
        .build();
var markdown = MarkdownAdapter.builder().policy(policy).build()
        .export(document).to(Path.of("report.md"));
// markdown.diagnostics() 返回 WARN 的结构化诊断，由应用决定如何展示或记录。
```

以上代码放在可抛出 `IOException` 的方法内。文件父目录须存在；`to(OutputStream)` 借用流，不关闭或刷新。`ExportResult.bytesWritten()` 是实际 UTF-8 字节数，`diagnostics()` 是不可变列表。适配器、策略与请求均不可变，可在回调自身安全的条件下并发复用；Builder 仅限当前线程使用。

## 不支持内容的动作策略

默认所有功能为 `ERROR`。`ExportPolicy.builder().defaultAction(action)` 设置默认动作，`.on(feature, action)` 覆盖特定功能。策略只控制格式无法表达的内容，不处理非法输入、执行失败或 I/O。

| 动作 | 可观察行为 |
| --- | --- |
| `ERROR` | 抛出 `ExportException`，`code()` 为 `UNSUPPORTED`；所有语义检查和完整 UTF-8 拼装在写入调用方流之前完成，旧路径目标也保持不变 |
| `WARN` | 应用下表的明确降级，继续输出；每个遇到的损失位置返回 `ExportDiagnostic`，不会向标准输出打印 |
| `IGNORE` | 使用与 WARN 相同的降级内容，不添加该损失的诊断 |

超出诊断预算也会失败，不会自动转为 IGNORE。非法 Unicode／控制字符、编号缺少父级或溢出、预算、取消、超时及写入失败始终报错。`ExportException.Code` 区分 `UNSUPPORTED`、`LIMIT_EXCEEDED`、`INVALID_TEXT` 和 `INVALID_NUMBERING`；执行控制另抛模型层 `ExecutionException`，I/O 保留原始异常及原因。

诊断包含功能键、节索引、`BODY/HEADER/FOOTER/DOCUMENT` 区域、原始根块索引、块 ID、物理格路径和 UTF-16 偏移。索引从零开始，`-1` 表示不适用。消息不拼接正文、URL 或文件路径；ID 是调用方提供的数据，应用仍应按自身日志策略处理。

## 支持与降级矩阵

未触发损失的内容直接导出。下表“降级”均需由 WARN 或 IGNORE 授权。

| 功能键／内容 | HTML | Markdown |
| --- | --- | --- |
| 标题、段落、普通文字 | h1～h6／p，保留源码文字 | ATX 标题／段落，转义 Markdown 与 HTML 元字符 |
| 文字与图片外链 | 标准 a，保留显式被动链接 | 标准链接，保留已有百分号编码；目标中的分隔符按语法编码 |
| `METADATA` | 标准 title／author | 降级：省略元数据，不重复写入正文 |
| `PAGE_GEOMETRY` | 默认 A4 解释为网页流默认值；其他纸张／边距降级为流默认值 | 同左；不输出物理纸张几何 |
| `PAGE_NUMBERING` | 非零起始显示页码降级为省略编号设置 | 同左 |
| `PAGE_REGIONS` | 降级：每节按 header／正文／footer 输出一次；不固定或逐页重复 | 降级：每节按原顺序保留区内内容一次 |
| `PAGE_BREAK` | hr 与打印分页提示；实际分页由浏览器决定 | 降级：主题分隔线 |
| `INLINE_FIELD` | 降级：`[unresolved KIND]`，不伪造最终页码或引用值 | 同左 |
| `CAPTION` | 降级：未编号的标题文字 | 同左 |
| `CROSS_REFERENCE` | 降级：前缀加 `[unresolved reference]` | 同左 |
| `CUSTOM_BLOCK` | 降级：省略自定义块；不执行其布局／绘制或调用 toString | 同左；绘制组件不能还原成语义内容 |
| `FONT_ROLE` | 显式 FontStyle 映射到 CSS；未映射角色降级为默认样式 | 映射为粗体／斜体语义；未映射角色降级为普通文字 |
| `TEXT_STYLE` | 字号、颜色、基线偏移、下划线、删除线映射 CSS | 删除线及映射的强调保留；字号、颜色、下划线、基线偏移降级为省略 |
| `PARAGRAPH_STYLE` | 对齐、缩进、间距、固定行距、分页约束与禁则映射 CSS 提示 | 接受默认正文／对应级别标题；非默认段落／格几何降级为普通流 |
| `LIST_MARKER` | 保留显式标记、层级缩进和独立标记样式 | 一级默认 bullet／十进制为原生列表；重启编号使用空注释分隔；自定义、多级或超出九位原生编号降级为显式标记文字；独立标记样式降级为默认 |
| 表格与 `TABLE_STRUCTURE` | 原生 colspan／rowspan、thead、真实锚点及两层块格内表 | 一行表头的矩形 GFM 表格；零表头补空表头，多表头后续行转正文；合并／块格降级为锚点内容一次、覆盖槽留空、内表文字按行列顺序展开 |
| `TABLE_GEOMETRY` | 固定／比例／Auto 列宽、padding 和行分页约束映射 CSS 提示 | 等权列、默认表格式直接支持；其他列宽、padding 与分页降级为省略，内表也检查 |
| `IMAGE_GEOMETRY` | 嵌入原 PNG／JPEG 字节，保留尺寸、基线、alt 和链接 | 图片／alt／链接保留为 data URI；尺寸与基线降级为阅读器默认显示 |

HTML 的 CSS 是显示提示：浏览器决定实际字体可用性、列宽、塑形、禁则与打印分页。不会嵌入或扫描系统字体，也不会获取网络字体。`FontStyle("Arial", 700, false)` 是明确的网页字体族与粗体映射，不等于 PDF 的真实字体字节。Markdown 只使用映射中的 `weight >= 600` 和 `italic`，不保留字体族。

Markdown 目标为 CommonMark 加 GFM 表格／删除线。为保留换行和相邻样式边界，会生成固定的 `br/strong/em/del` 行内标签；源码中的 HTML 始终转义。禁用原始 HTML 的阅读器可能不显示这些样式，禁用 data URI 的阅读器可能不显示图片。嵌入图片会增大文件，不额外生成旁车资源目录。

## 安全、预算与执行

HTML 是自包含静态文件，CSP 禁止脚本与外部资源，仅允许 data 图片和内联 CSS。正文、元数据、属性和字体族均按各自上下文转义。外链复用模型的 HTTP／HTTPS／mailto 白名单，只输出目标，不访问链接。Markdown 同样将源文字当纯文本，不运行模板或源 HTML。导出自身不会扫描文件或发起网络请求。

`ExportLimits.builder()` 可以配置以下独立预算：

| 预算 | 默认上限 |
| --- | --- |
| 节／语义块／表格槽 | 1,000／20,000／200,000 |
| 源码点／行内项 | 2,000,000／100,000 |
| 唯一图片／原编码图片字节 | 1,024／64 MiB |
| UTF-8 输出／诊断 | 64 MiB／10,000 |

文字计费包含正文、ID、角色、标记、URL、图片 alt、字段目标及元数据。真实表格锚点只计一次，块格后代继续计费；共享页区源按区域去重，输出中每次实际呈现仍计输出字节。图片按资源摘要去重原始字节，data URI 的每次输出分别计费。输出先在内存中有界拼装，不是流式导出，也不保证硬 RSS 上限或恶意输入沙箱。

请求支持 `.execution(ExecutionOptions)`，与外层同步作用域共同观察取消／超时，不创建后台线程。完整语义及大小检查后才写入流；物理 I/O 或写入期间取消仍可能留下部分流内容，调用方应丢弃失败输出。路径使用同目录临时文件和原子替换，无非原子复制回退，失败清理临时文件并保留旧目标。

## 运行与验证

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:webExports
powershell -NoProfile -File scripts/verify_local_consumer.ps1
python scripts/verify_web_exports.py
```

Python 须使用按 `scripts/requirements-verification.txt` 安装的项目隔离环境。脚本用独立 CommonMark/GFM 解析器及 HTML 解析器检查内容唯一性、合并／嵌套顺序、编号重启、强调边界、原图字节、链接和诊断位置，并在 `build/verification/web-exports` 写入 Markdown 渲染预览及报告。Linux/macOS 使用 `./gradlew`；独立消费者的构建命令见[发布规范](releasing.md)。

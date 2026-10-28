# 统一导入、编辑与转换

`DocumentConversion` 将已支持的导入、语义编辑和导出串联起来，统一损失策略与结果报告。它支持 Markdown／HTML／DOCX 输入和[受限 PDF 语义恢复](pdf-semantic-import-api.md)，以及 DOCX／HTML／Markdown 和显式布局后端目标。PDF 原对象／页级编辑仍使用各自的独立入口。

## 转换范围

转换路径为源格式导入、统一语义模型、目标格式导出；编辑为可选步骤。下表表示已有入口可组合，实际转换仍遵循源与目标各自的支持子集及损失处理规则。

| 输入 | 可组合目标 | 导入边界 |
| --- | --- | --- |
| HTML／CSS | DOCX、HTML、Markdown、PDF | 离线元素、属性和静态 CSS 子集；资源由调用方明确提供 |
| Markdown | DOCX、HTML、Markdown、PDF | 通用 Markdown 导入子集，与严格模板语法独立 |
| DOCX | DOCX、HTML、Markdown、PDF | 语义子集，未建模 OOXML 需明确接受简化；不保留任意原包结构 |
| PDF | DOCX、HTML、Markdown、重新排版的 PDF | 受限静态 PDF 语义恢复；表格需显式网格，不执行 OCR，不保持原版面 |
| 静态 XML 模板包 | XML 模板包、DOCX、HTML、Markdown、PDF | `XmlTemplateBundle.conversion()`；只用于无需绑定数据的模板，沿用现有 XML 编译／绑定预算 |

上述语义输入均可选择 [XmlTemplateWriter.conversionTarget()](xml-model-saving-api.md) 保存为既有 XML／普通 ZIP。目标独立检查不支持的页区、注释、块及属性，按明确策略拒绝或报损；字体／许可由调用方另行传入，不随转换源隐式复制。原动态模板应保存源码，需要绑定数据时先显式 render。

PDF 目标需要显式字体和 `DocumentEngine`。SVG／PNG 也可选作布局目标，但继续受对应后端的页数、交互数据等限制；当前没有将任意 SVG／PNG 恢复为可编辑文档的导入入口。图像资源加载与文档语义导入是不同能力。

## 使用

```java
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.nio.file.Path;

var policy = ProcessingPolicy.builder()
        .defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .build();
var prepared = new HtmlImporter()
        .conversion("<p id='title'>Old title</p><p style='color:red'>Keep</p>")
        .policy(policy)
        .edit(editor -> editor.replaceText("Old title", "New title"))
        .prepare(new MarkdownAdapter().conversionTarget());

var preview = prepared.preview();
var result = prepared.to(Path.of("report.md"));
assert preview.issues().equals(result.report().issues());
```

导入器上的 `.conversion(source)`、DOCX 的 `.conversion(packageBytes)` 和 `DocumentConversion.from(document)` 返回同一请求类型。DOCX 输入字节在入口复制；Web 导入器保留显式资源快照。预检时读取一次源，调用一次编辑回调，编码一次目标。请求和回调均限当前任务线程使用。

PDF 示例：以 `DocumentEngine.builder(explicitFonts).build().conversionTarget("PDF", new PdfBackend())` 作为目标。字体、角色及运行时配置来自引擎快照；模型中的页区和[导航声明](portable-navigation-api.md)由默认引擎自动消费，与非空运行时配置冲突时拒绝。注释从转换输入传入，引擎预配置注释会冲突并被拒绝。后端中立的格式键由调用方如实指定；适配器内置键为 HTML、MARKDOWN、DOCX 和 XML_TEMPLATE。

## 动作与实际损失

覆盖顺序为**操作、功能、格式、默认值**，见[共享编辑策略](semantic-editing-api.md)。导入／导出操作键为 `IMPORT`／`EXPORT`，编辑使用具体操作键。默认 ERROR；WARN 和 IGNORE 执行相同的受支持处理，IGNORE 仅不出现在 `warnings()` 中，仍在 `issues()`／`losses()` 中。

| 动作 | 遇到已定义的不兼容内容时 |
| --- | --- |
| `ERROR` | 拒绝转换，在准备阶段报错，不写入最终目标 |
| `WARN` | 按明确规则简化或丢弃，继续转换，并返回警告和实际损失 |
| `IGNORE` | 使用与 WARN 相同的处理，保留实际损失记录，不将其列为警告 |

动作与处理方式独立：`DEGRADE` 表示简化，例如保留文字但去掉颜色；`DISCARD` 表示丢弃，例如删除没有目标映射的注释。允许简化不自动授权丢弃；必须为相应功能配置该入口支持的处理方式。可只对特定属性放宽，其余保持严格拒绝。

| 目标或来源 | 明确处理 |
| --- | --- |
| HTML／Markdown 输入 | 延续对应[HTML](html-import-api.md)／[Markdown](markdown-import-api.md)源位置与功能键 |
| DOCX 输入 | 未映射 OOXML 为 `OOXML_MARKUP`／DEGRADE；位置 region 为 `PACKAGE_PART:<包内逻辑名>`，UTF-16 为不适用 |
| PDF 输入 | `PdfAdapter.importDocument(input).conversion()`；阅读顺序／逐行段落等推断必须接受 PDF_LAYOUT／DEGRADE，表格显式提供网格，其他损失见[恢复矩阵](pdf-semantic-import-api.md) |
| Web 输出 | 延续[Web 支持表](web-export-api.md)；CUSTOM_BLOCK／METADATA／PAGE_NUMBERING 为 DISCARD，其他已有简化映射为 DEGRADE |
| DOCX 输出 | 自定义 `FONT_ROLE` 为 DEGRADE，保留字号、颜色、链接及对象位置；标准空角色／bold／italic／boldItalic 保留 |
| DOCX 题注／独立引用 | `CAPTION` + DEGRADE 保留 ID、文字和格式，去掉题注类别／自动编号；`CROSS_REFERENCE` + DEGRADE 保留 ID、前缀和格式，目标改为明确的 `[unresolved reference]` 文字，不生成动态引用 |
| DOCX／Web 注释 | 外部 Note 内容没有目标映射，`NOTES` 必须明确 DISCARD；源候选仍保留注释。DOCX 同时删除显式 NOTE_MARKER，按原文一个 UTF-16 单元单独报告 `NOTE_MARKER_REMOVED`；Web 的标记继续按 `INLINE_FIELD` 明确降级为未解析文字 |
| 布局输出 | `MISSING_GLYPH_REPLACED` 必须明确 DEGRADE，并保留原文 UTF-16、块和格路径；显式字体栈回退不视为内容损失 |

未编辑内容同样完整检查。导出位置为原文已知点位或范围；没有可确定范围时不伪造字符区间。要求不可用处理会失败，例如没有源包通道的 PRESERVE、把样式降级配置为 DISCARD。非法结构、宏、安全限制、权限、预算、取消、超时和 I/O 不受动作策略压制。

上述 DOCX 处理仅适用于 `conversionTarget()`；直接 `DocxAdapter.write` 仍严格拒绝题注、独立引用及未映射标记。原生行内 PAGEREF／REF 继续遵守[字段子集](docx-fields-api.md)，不能把题注降为文本后仍声称保留其自动编号引用。注释／引用的实际文件重导入及三动作证据见[阶段八十九](verification/phase-89-navigation-recovery.md)。

模型导航在 DOCX／HTML／Markdown 中须单独声明 NAVIGATION／DISCARD，默认拒绝；WARN／IGNORE 都报告实际丢弃。统一转换与恢复不可把已丢导航藏回恢复包，完整组合与真实 Word 保存回读见[阶段九十](verification/phase-90-recovery-closure.md)。

## 预检、预算与输出

`prepare` 已完成完整导入、编辑和编码，返回不可变候选及确切编码；预检可能因目标不支持、预算或编码错误失败。`Prepared.preview()` 是 PREVIEW，成功 `to` 返回 ACTUAL；发布失败不返回成功报告。多次发布使用同一编码，不重新检查源。

默认上限为 1,000 个编辑回调、10,000 项总诊断、64 MiB 编码，可用 `DocumentConversion.Limits` 调整；编辑另有 `EditLimits`，导入器和目标保留自己的额度。编码采用有界内存快照，布局、解析器和后端仍可能保留其工作数据，不承诺硬 RSS 上限。

`.execution(options)` 在准备和发布期间同步检查取消与超时。`.to(Path)` 要求父目录已存在，写同目录临时文件后原子替换，不降级成复制；失败保留旧文件并清理临时文件。`.to(OutputStream)` 借用流不关闭、不刷新，物理写失败可留下部分字节。

自定义 Reader／Writer 为可信代码，须报告全部实际损失、遵守检查点和资源所有权；Writer 返回 ACTUAL 报告。统一入口不推断自定义输出格式或替其证明无损。

完整可运行调用见 [DocumentConversionExample](../examples/src/main/java/com/mirakyux/export4j/examples/DocumentConversionExample.java)，设计见 [ADR-0054](adr/0054-prepared-document-conversion.md)。

# 统一导入、编辑与转换

`DocumentConversion` 将已支持的导入、语义编辑和导出串联起来，统一损失策略与结果报告。它支持 Markdown／HTML／DOCX 输入和[受限 PDF 语义恢复](pdf-semantic-import-api.md)，以及 DOCX／HTML／Markdown 和显式布局后端目标。PDF 原对象／页级编辑仍使用各自的独立入口。

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

PDF 示例：以 `DocumentEngine.builder(explicitFonts).build().conversionTarget("PDF", new PdfBackend())` 作为目标。字体、角色、页区和导航来自引擎快照；注释从转换输入传入，引擎预配置注释会冲突并被拒绝。后端中立的格式键由调用方如实指定；适配器内置键为 HTML、MARKDOWN 和 DOCX。

## 动作与实际损失

覆盖顺序为**操作、功能、格式、默认值**，见[共享编辑策略](semantic-editing-api.md)。导入／导出操作键为 `IMPORT`／`EXPORT`，编辑使用具体操作键。默认 ERROR；WARN 和 IGNORE 执行相同的受支持处理，IGNORE 仅不出现在 `warnings()` 中，仍在 `issues()`／`losses()` 中。

| 目标或来源 | 明确处理 |
| --- | --- |
| HTML／Markdown 输入 | 延续对应[HTML](html-import-api.md)／[Markdown](markdown-import-api.md)源位置与功能键 |
| DOCX 输入 | 未映射 OOXML 为 `OOXML_MARKUP`／DEGRADE；位置 region 为 `PACKAGE_PART:<包内逻辑名>`，UTF-16 为不适用 |
| PDF 输入 | `PdfAdapter.importDocument(input).conversion()`；阅读顺序／逐行段落等推断必须接受 PDF_LAYOUT／DEGRADE，表格显式提供网格，其他损失见[恢复矩阵](pdf-semantic-import-api.md) |
| Web 输出 | 延续[Web 支持表](web-export-api.md)；CUSTOM_BLOCK／METADATA／PAGE_NUMBERING 为 DISCARD，其他已有简化映射为 DEGRADE |
| DOCX 输出 | 自定义 `FONT_ROLE` 为 DEGRADE，保留字号、颜色、链接及对象位置；标准空角色／bold／italic／boldItalic 保留 |
| DOCX／Web 注释 | 外部 Note 内容没有目标映射，`NOTES` 必须明确 DISCARD；源候选仍保留注释 |
| 布局输出 | `MISSING_GLYPH_REPLACED` 必须明确 DEGRADE，并保留原文 UTF-16、块和格路径；显式字体栈回退不视为内容损失 |

未编辑内容同样完整检查。导出位置为原文已知点位或范围；没有可确定范围时不伪造字符区间。要求不可用处理会失败，例如没有源包通道的 PRESERVE、把样式降级配置为 DISCARD。非法结构、宏、安全限制、权限、预算、取消、超时和 I/O 不受动作策略压制。

## 预检、预算与输出

`prepare` 已完成完整导入、编辑和编码，返回不可变候选及确切编码；预检可能因目标不支持、预算或编码错误失败。`Prepared.preview()` 是 PREVIEW，成功 `to` 返回 ACTUAL；发布失败不返回成功报告。多次发布使用同一编码，不重新检查源。

默认上限为 1,000 个编辑回调、10,000 项总诊断、64 MiB 编码，可用 `DocumentConversion.Limits` 调整；编辑另有 `EditLimits`，导入器和目标保留自己的额度。编码采用有界内存快照，布局、解析器和后端仍可能保留其工作数据，不承诺硬 RSS 上限。

`.execution(options)` 在准备和发布期间同步检查取消与超时。`.to(Path)` 要求父目录已存在，写同目录临时文件后原子替换，不降级成复制；失败保留旧文件并清理临时文件。`.to(OutputStream)` 借用流不关闭、不刷新，物理写失败可留下部分字节。

自定义 Reader／Writer 为可信代码，须报告全部实际损失、遵守检查点和资源所有权；Writer 返回 ACTUAL 报告。统一入口不推断自定义输出格式或替其证明无损。

完整可运行调用见 [DocumentConversionExample](../examples/src/main/java/com/mirakyux/export4j/examples/DocumentConversionExample.java)，设计见 [ADR-0054](adr/0054-prepared-document-conversion.md)。

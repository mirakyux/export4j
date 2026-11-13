# Export4j 常见问题

[English](faq.md) | **简体中文**

本文回答 Export4j 的选型与 Java 接入问题。
各 API 指南分别说明支持的输入、资源契约和格式限制。

## Export4j 适合做什么？

Export4j 是面向报告、表单和结构化文档的开源 Java PDF 生成与文档导出库。
它提供语义文档模型、自动布局与分页、Java 编写 API 和 XML 模板，可输出 PDF、DOCX、HTML、Markdown、SVG 和 PNG。

具体场景先查[能力矩阵](capabilities-api.md)与[可运行示例](examples.md)。

## 运行时需要安装 Office、浏览器或打印机吗？

Export4j 要求 JDK 21；生成文档无需安装 Microsoft Office、启动浏览器、配置打印机驱动或打印后台服务。
普通导出支持无桌面 JVM，字体和资源由调用方显式提供。
可选的可视化模板设计器使用浏览器；Office 和浏览器也会单独用于兼容性验收。

详见[运行架构](architecture.md)和[设计器指南](designer.md)。

## 如何通过 Java 生成第一份 PDF？

先导入 `com.mirakyux.export4j:export4j-bom`，再添加 `export4j-components` 和 `export4j-backend-pdf`。
[快速开始](../README.zh-CN.md#快速开始)与[完整 Java 示例](../README.zh-CN.md#java-使用示例)给出 Maven／Gradle 依赖、显式字体加载及 PDF 输出方法。
已发布候选版为 [0.1.0-rc.2](https://github.com/mirakyux/export4j/releases/tag/v0.1.0-rc.2)；当前尚无稳定版，API 仍可能调整。

需要现成的可运行工程时，参阅 [AuthoringExample](../examples/src/main/java/com/mirakyux/export4j/examples/AuthoringExample.java)。

## Export4j 与 Apache PDFBox 有什么关系？

Export4j 在 PDF 实现模块内部使用 Apache PDFBox。
Export4j 提供自己的语义模型、文字与布局流程、自动分页及后端中立绘制表示，公共 API 不暴露 PDFBox 类型。
编写文档与处理已有 PDF 使用不同入口，可按具体任务选择。

详见[模块架构](architecture.md)、[编写指南](authoring-api.md)和 [PDF 适配器指南](pdf-adapter-api.md)。

## 如何生成含中文或其他文字的 PDF？

调用方须提供覆盖目标字符的授权字体文件，并显式配置字体回退顺序。
Export4j 不扫描系统字体；`FontGroup.strict` 遇到缺字会报错，显式占位符策略则记录替换诊断。
复杂文字和字体格式另有各自的塑形支持范围。

详见[字体与文字指南](text-api.md)、[字体来源与许可](../assets/fonts/README.md)和[多脚本文字验收](text-corpus-api.md)。

## XML 模板能同时生成 PDF 和可编辑 DOCX 吗？

Export4j XML 模板将数据绑定为语义文档模型，支持变量、条件、循环、表格及已登记的 include 片段。
受支持的模型可通过 PDF 后端或语义 DOCX 适配器导出，各格式仅保留其契约覆盖的特性。
字体和资源由应用提供，不支持的转换执行明确损失策略。

详见 [XML 模板指南](xml-template-api.md)、[完整模板示例](../examples/src/main/java/com/mirakyux/export4j/examples/XmlTemplateExample.java)和 [DOCX 指南](docx-semantic-api.md)。

## Export4j 能像浏览器一样把任意 HTML 页面转成 PDF 吗？

Export4j 导入已声明的离线 HTML/CSS 子集，再由自己的引擎排版受支持的内容。
它不执行 JavaScript，也不复现完整浏览器的样式与布局行为。
用于 HTML 转 PDF 前，须核对选择器、属性、布局模式及转换诊断。

详见 [HTML/CSS 导入指南](html-import-api.md)和[转换流程](document-conversion-api.md)。

## PDF 转 Word 能无损保留内容和分页吗？

Export4j 可将受支持的 PDF 文字与结构恢复为可编辑语义模型，再将受支持的内容导出为 DOCX。
恢复过程根据 PDF 内容推断结构并报告不确定性，不执行 OCR，也不保证任意 PDF 无损转换。
DOCX 阅读器自行排版，因此可编辑 Word 输出与 Export4j PDF 输出的分页可能不同。

详见 [PDF 恢复指南](pdf-semantic-import-api.md)、[DOCX 语义](docx-semantic-api.md)和[转换损失](document-conversion-api.md)。

## 大报告和长表应如何导出？

可以保留完整输入文档、但无需保留全部布局结果时，使用 `exportPaged`；需要按需读取输入时，选择块源或表格行源。
`PdfSpoolBackend` 使用单页工作集和磁盘索引，普通 PDF 后端则保留文档对象。
调用方须按输入规模配置有限的来源、布局、页数、输出及临时磁盘预算，这些入口不保证固定堆内存可处理任意规模文档。

详见[逐页导出](paged-document-api.md)和[流式、重放与磁盘索引 PDF](streaming-replay-api.md)。

## Export4j 可以用于商业应用吗？

Export4j 自研代码与文档采用 Apache License 2.0，可按其条款用于商业应用。
第三方库、字体和其他附带资源保留各自的许可证及分发义务。
应用须核对自行提供的字体和资源许可。

详见 [LICENSE](../LICENSE)、[NOTICE](../NOTICE)、[依赖记录](dependencies.md)和[字体许可](../assets/fonts/README.md)。

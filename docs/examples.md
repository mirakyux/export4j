# 示例索引

从仓库根目录运行 Gradle 任务。Windows 使用 `.\gradlew.bat`，Linux/macOS 使用 `bash ./gradlew`；需要 JDK 21，首次构建需要下载固定依赖。示例显式加载仓库字体，来源和许可见[字体记录](../assets/fonts/README.md)。

## 常用任务

```powershell
.\gradlew.bat :examples:authoring :examples:xmlTemplates :examples:production
```

选择与目标场景相关的任务，无须一次执行全部示例。表中输出目录相对于仓库根目录。

| 示例任务 | 输出目录 | 展示内容 |
| --- | --- | --- |
| `:examples:authoring` | `examples/build/authoring/` | Java 编写、文字排版与语义 DOCX |
| `:examples:xmlTemplates` | `examples/build/xml-templates/` | XML 报告模板与数据绑定 |
| `:examples:xmlTemplateBundle` | `examples/build/xml-template-bundle/` | 编辑、保存和重开普通 ZIP 中的 XML 源码／资源，再绑定不同数据 |
| `:examples:recoveryClosure` | `examples/build/recovery-closure/` | 导航／注释／分节／图层／表格／图片组合恢复、实际编辑及有损多轮 |
| `:examples:xmlModelSaving` | `examples/build/xml-model-saving/` | 语义模型保存为 XML，再重开编辑导出；三轮无编辑控制及明确损失报告 |
| `:examples:xmlRegionSaving` | `examples/build/xml-region-saving/` | 共享页区、变体／图层及保存／重开／编辑控制 |
| `:examples:xmlCrossReferenceSaving` | `examples/build/xml-cross-reference-saving/` | 独立引用三轮保存、跨节页码／编号、目标改写和 Web 损失 |
| `:examples:xmlSemanticSaving` | `examples/build/xml-semantic-saving/` | 注释／题注／文字外链完整绑定、三轮控制、实际题注编辑及 Web 损失 |
| `:examples:pageOverlays` | `examples/build/page-overlays/` | XML 背景／前景、可见遮挡关系及明确转换损失 |
| `:examples:xmlPageRegions` | `examples/build/xml-page-regions/` | XML 页眉页脚主题，以及含目录的最终页码 |
| `:examples:xmlSections` | `examples/build/xml-sections/` | 图片／表格页区、分节纸张／页码和原生 DOCX 严格往返 |
| `:examples:webExports` | `examples/build/web-exports/` | HTML／Markdown、嵌入图片及按功能报错／警告／忽略 |
| `:examples:documentConversion` | `examples/build/document-conversion/` | 导入、编辑、实际损失预检和四格式输出 |
| `:examples:pdfObjectEditing` | `examples/build/pdf-object-editing/` | 被动字段／对象及关系重映射选页／合并 |
| `:examples:pdfContentEditing` | `examples/build/pdf-content-editing/` | 真实文字替换及新建纯图片 PDF 的区域脱敏 |
| `:examples:pdfSemanticImport` | `examples/build/pdf-semantic-import/` | 恢复 PDF 标题、显式表格和图片，再编辑与转换 |
| `:examples:production` | `examples/build/production/` | 分页、分栏、绕排、表格与多格式输出 |
| `:examples:staticForms` | `examples/build/static-forms/` | 密集静态表单与矢量控件 |
| `:examples:streamingTable` | `examples/build/streaming-table/` | 单向长表输出 |
| `:examples:variableTables` | `examples/build/variable-tables/` | 按实际栏宽布局的语义行、合并 owner 和原子嵌套格 |
| `:examples:variableSplitTables` | `examples/build/variable-split-tables/` | 行内格在变宽栏间按完整行续排，保留最终字段、图片和链接 |
| `:examples:verifyPdfSignatures` | `examples/build/pdf-signature-verification/` | 显式离线 CMS 校验、未签追加及签名字节篡改区分 |

## 原生 DOCX 与 HTML/CSS 布局

这两组组合样例位于独立 Maven 调用方，消费本地发布坐标。先执行 `build sbom publishLocalBundle`，再为每次验证选择新的 `<validation-directory>`：

```powershell
.\gradlew.bat build sbom publishLocalBundle
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=build/<validation-directory>" build docxSemanticComplete htmlCssLayout resolvedLibraries --refresh-dependencies --no-daemon --console=plain
```

输出位于 `verification/consumer/build/<validation-directory>/output/` 的 `docx-semantic-complete/` 和 `html-css-layout/`。运行生成任务不等于完成 Word 或浏览器兼容性验收；应用及独立渲染步骤分别见 [DOCX 专项](verification/phase-103-native-docx-semantic-editing.md)和 [CSS 专项](verification/phase-104-html-css-layout.md)。

## 选择 API 与验证

- 需要完整 Java 调用时，从[示例源码](../examples/src/main/java/com/mirakyux/export4j/examples)及对应指南开始；上表任务以 [Gradle 配置](../examples/build.gradle.kts)为准。
- 需要判断输入范围、拒绝条件或格式损失时，查[文档导航](README.md)及相应 API，不将一个样例视为完整格式支持。
- 需要复现阶段结果时，查对应[验收记录](verification)中的工具版本、命令与边界；生成物和原始日志保留在构建目录。

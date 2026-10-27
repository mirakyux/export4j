# ADR-0091：标准容器中的恢复信息按调用显式写入

状态：已采纳。延续 [ADR-0087](0087-bound-standard-output-recovery.md) 的模型／资源绑定，以及 [ADR-0090](0090-embedded-png-model-recovery.md) 的单文件可选入口。

## 背景

HTML、Markdown、SVG 和单页 PNG 已能携带恢复信息。GAP-24.2 还需处理 PDF、DOCX 和 PNG 页 ZIP，并回答标准阅读器兼容、文件大小及外部编辑器重存后的行为。

## 决定

恢复信息始终是显式选择：`writeDocument` 写普通标准文件；`writeCompanion` 写单独 ZIP；`writeEmbedded` 才写带内嵌信息的标准文件。PDF 的实现依赖 PDFBox，调用方显式传入 `PdfRecoveryCodec`；模板模块只定义不含第三方类型的 `EmbeddedRecoveryCodec`，PDF 适配器依赖模板模块，核心不反向依赖 PDF 适配器。

DOCX 和 PNG 页 ZIP 使用标准 ZIP 成员。新增成员插入于原中央目录之前，原成员的局部记录和中央目录记录逐字节保持；只追加恢复成员并修正 EOCD。DOCX 使用 `customXml/export4j-recovery.xml` 保存 Base64 编码的现有恢复 ZIP，继承 DOCX 的 XML 内容类型；PNG 页 ZIP 使用 `export4j/recovery.zip` 保存原始恢复 ZIP。恢复时移除新增成员并还原原始目录，沿用 version 1 清单对原始文件的准确长度和 SHA-256。ZIP64、多磁盘、加密及超预算 ZIP 明确拒绝。外部应用重新压缩且保留私有成员时，以当前成员重建不含旧恢复信息的 ZIP，旧模型绑定失效。

PDF 使用标准 EmbeddedFiles 名称树中的 `export4j-recovery.zip` 附件，增量保存以保留原 PDF 作为前缀。PDFBox 每次增量保存会随机更新 trailer 的第二个文档 ID；编码器仅固定这个新增 ID 为原 PDF 与恢复 ZIP 的摘要值，以便准确核对整个单文件。恢复时重新生成封装并逐字节比对；外部应用修改 PDF 的任意其他字节时，旧模型绑定失效。若应用整体重写但保留附件，回退入口从当前 PDF 移除附件后再交给显式当前文件读取器；丢失附件则按缺失恢复信息处理。已加密或已签名 PDF 不加入恢复附件。

## 兼容、体积和边界

- 标准 DOCX 能由 Microsoft Word 打开并保持可见文字；本轮 `SaveAs2` 未返回，普通 DOCX 对照也未返回，Word 另存后的恢复保留情况未通过验收。标准 PNG 页 ZIP 能由 ZIP／PNG 读取器解码；PDF 附件与页面分别由独立 PDF 工具检查。普通文件默认不增加任何恢复数据。
- 内嵌包会使文件增大；字体和其他显式资源以恢复所需字节随包携带。DOCX 的 XML Base64 额外增加编码体积。具体样例增量登记在[阶段八十六](../verification/phase-86-standard-container-recovery.md)，不把样例比例视为所有文档的固定开销。
- 摘要只防止误用过期信息，不认证提供者。编辑器可能保留、改写或移除私有成员／附件；系统只在字节绑定匹配时恢复旧模型。WARN／IGNORE 需要可信当前文件读取器，没有读取器时不凭空恢复。
- 有损导出不藏较丰富源模型；签名、加密、归档、资源预算与取消不能由损失策略绕过。内嵌 PDF 附件不自动继承 PDF/A、PDF/UA 合规声明；相关输出须走单独标准验收。
- 自动猜测格式或从任意图像／PDF 页面识别原语义不属于内嵌恢复。格式键由调用方显式传入；一般复杂模型往返仍按 GAP-24.1／24.4 独立验收。

API 见[恢复指南](../model-recovery-api.md)，结果见[阶段八十六](../verification/phase-86-standard-container-recovery.md)。

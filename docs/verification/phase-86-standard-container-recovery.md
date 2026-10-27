# 阶段八十六验收：标准容器可选内嵌模型恢复

本阶段完成 **GAP-24.2 中 PDF、DOCX 和 PNG 页 ZIP 的显式内嵌恢复**。普通标准文件、独立伴随 ZIP 和单文件内嵌是调用方可选择的三种输出；没有恢复信息时，文件仍按原格式使用。内嵌数据只恢复 Export4j 保存的语义模型，不从任意 PDF 页面、DOCX 包或图片像素推断原模型。

## 容器与兼容边界

| 格式 | 内嵌位置 | 标准读取器检查 | 外部重存 |
| --- | --- | --- | --- |
| PDF | `EmbeddedFiles` 名称树中的 ZIP 附件，增量保存 | pypdf 提取页面文字和附件；Poppler／PDFium 双渲染 | 改写后旧绑定失效；签名、加密和带输出意图的 PDF 拒绝加入附件，PDF/A／PDF/UA 未单独认证 |
| DOCX | `customXml/export4j-recovery.xml` 标准 ZIP 部件 | python-docx 读取段落；Microsoft Word 打开并显示预期文字 | 本轮 Word `SaveAs2` 未返回；普通 DOCX 对照也同样未返回，无法判定原因。外部重压且保留部件会判过期 |
| PNG 页 ZIP | `export4j/recovery.zip` ZIP 成员 | Python ZIP 检查与 Pillow 页面完整解码 | 重压或改页后旧绑定失效；恢复成员可能被外部工具删除 |

普通 `writeDocument` 不增加恢复数据；`writeCompanion` 另存普通 ZIP；`writeEmbedded` 才在标准容器中写入信息。DOCX／PNG 页 ZIP 保留原成员的局部记录和中央目录记录，PDF 保留原文件前缀。解包恢复后核对 version 1 清单的格式、长度、SHA-256 及模型／资源摘要。缺失／过期默认拒绝；显式 WARN／IGNORE + DEGRADE 仍须由调用方提供可信当前文件读取器。损坏、预算、加密／签名和取消不受损失策略放行。

## 可复现入口

Java 21、Gradle Wrapper 9.2.1；独立 Python 环境按 `scripts/requirements-verification.txt` 安装。本轮在 Windows 使用 Microsoft Word 16、Pillow 12.3.0、pypdf 6.10.0、pypdfium2 5.13.0，并使用 Poppler 与 PDFium 双渲染。生成文件和原始报告位于忽略的 `build/`。

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:embeddedPdfRecovery :examples:embeddedZipRecovery :examples:embeddedPngRecovery :examples:embeddedHtmlRecovery :examples:embeddedCommentRecovery :examples:modelRecovery
python scripts/verify_embedded_containers.py --prepare-external
.\gradlew.bat :examples:embeddedPdfRecoveryExternal :examples:embeddedZipRecoveryExternal
.\gradlew.bat -p verification/consumer -PconsumerBuildDirectory=build/<fresh-consumer-directory> clean build run embeddedContainerRecovery resolvedLibraries --refresh-dependencies --write-locks
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify_embedded_containers_word.ps1
python scripts/verify_embedded_containers.py --consumer-build-directory verification/consumer/build/<fresh-consumer-directory>
```

本轮全仓测试报告合计 1,577 项，0 失败／错误／跳过；构建、SBOM、本地制品和全部上述样例通过。新的隔离 Maven 调用方从本轮制品恢复、改字、重导出并再次打开 PDF／DOCX／PNG 页 ZIP；23 个运行组件、15 个自研库和 15 项 BOM 约束通过独立核对。独立验证器检查七格式实际文件，三种新容器的准确源字节与模型／字体／许可摘要，18 条外部修改／移除恢复信息的三动作记录，以及 8 页 PDF 的 Poppler／PDFium 双渲染；8 页拼图目视确认文字未缺失或遮挡。Word 打开本轮 DOCX 且正文正确；两次内嵌 DOCX `SaveAs2` 与普通 DOCX 对照均未返回，因此本轮无法验证 Word 保存回读，也不能据此判断内嵌部件是否造成问题。

PDF 适配器通过公开依赖传递 `EmbeddedRecoveryCodec` 和恢复预算所需的模板模块。另用只声明 `export4j-adapter-pdf` 坐标的隔离调用方编译公共恢复 API，生成 POM 的模板依赖为 `compile`，避免完整示例中显式声明模板依赖掩盖缺失的公共类型。

## 文件大小

以下是带一个显式字体及其许可的**小文档样例**，并非固定倍率或上限。增量主要来自恢复 ZIP 中的字体；DOCX 的 Base64 XML 另有编码开销。

| 格式 | 普通文件 | 内嵌文件 | 增量 |
| --- | ---: | ---: | ---: |
| PDF | 6,712 B | 219,079 B | 212,367 B |
| DOCX | 3,001 B | 288,772 B | 285,771 B |
| PNG 页 ZIP | 5,934 B | 220,215 B | 214,281 B |
| HTML | 1,750 B | 287,328 B | 285,578 B |
| Markdown | 48 B | 285,634 B | 285,586 B |
| SVG | 37,970 B | 323,552 B | 285,582 B |
| 单页 PNG | 11,109 B | 225,279 B | 214,170 B |

需要普通阅读、较小文件或后续第三方编辑时，调用方可选择普通文件或伴随包。内嵌包包含可读模型、字体及许可，不继承标准文件的加密或访问控制；分发与保密仍由应用处理。更多复杂语义保存和跨格式往返属于 GAP-24.1／24.4；任意外部应用保存后的内嵌保留不作保证。

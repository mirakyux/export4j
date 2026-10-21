# 阶段十二验收：DOCX 原生字段与 Word 实测

## 本批范围

正文和普通语义单元格支持 PAGE、NUMPAGES、PAGEREF 与完整段落编号 REF。导出写入标准字段、书签及更新设置，初始缓存为空；导入支持简单字段和段落内扁平复杂字段，保留样式、字段预留宽度及 UTF-16 位置。标准 Word 字段可用三参数 `read` 显式指定默认宽度。

字段不依赖 PDF 布局来确定 Word 页码。未知指令不执行，严格模式拒绝，损失模式返回支持的缓存文字及诊断。嵌套／未闭合控制符、非法宽度、坏书签和预算超限明确失败。设计见 [ADR-0016](../adr/0016-native-docx-fields.md)，调用见[DOCX 字段 API](../docx-fields-api.md)。

## 构建与测试

本地 Windows、Java 21、Gradle 9.2.1，生产和测试编译均使用 `-Xlint:all -Werror`。完整构建、SBOM、隔离制品发布和 Maven 调用方验证通过。累计 234 项测试，无失败、错误或跳过；本批新增 13 项字段测试，并调整原有拒绝用例为不支持的目标。

测试覆盖四类字段与前向引用、任意 Unicode 块 ID、标准书签／设置／关系、媒体和外链共存、复杂指令拆分、旧缓存忽略、空缓存样式、显式宽度／非法元数据、未知指令／开关／锁定字段、坏控制符、目标错误、单元格多段落来源重定位、写出前失败与预算。

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:docxFields :examples:production :examples:inlineImages :examples:inlineFields :examples:tablePdf :examples:advancedPdf :examples:capabilities -PsignerOpenSsl=openssl -PsignerCertificate=build/verification/signing/dev-cert.pem -PsignerKey=build/verification/signing/dev-key.pem --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
& scripts/verify_docx_word.ps1
python scripts/verify_docx_fields.py --word
python scripts/verify_production.py --reader-copy examples/build/production/reader-filled.pdf
python scripts/verify_inline_images.py
python scripts/verify_inline_fields.py
python scripts/verify_advanced_pdf.py
& .\build\verification\verapdf\verapdf.bat --format xml --flavour 2b examples/build/capabilities/archive.pdf > build/verification/docx-fields-verapdf.xml
python scripts/verify_capabilities.py --openssl openssl --verapdf-report build/verification/docx-fields-verapdf.xml
~~~

最后一次 Java 构建日志为 `build/verification/docx-fields-final-build.log`。成功调用方使用独立目录 `verification/consumer/build/validation-b8227b4463f4`，成功目录指针已更新；新增 `consumer-fields.docx` 及 2 页 `consumer-docx-fields.pdf`。

受影响文档的 190 个本地链接通过，新增 Python 脚本语法及修改 Java 的 SPDX／未使用导入检查通过。审查结果见 `build/verification/docx-fields-audit.log`。

## 独立验证与新样例

`examples/build/docx-fields/fields.docx` 包含 9 个原生页码／引用字段、2 个书签、1 张嵌入 PNG 和 1 个外链。ZIP／XML 与 python-docx 独立检查字段指令、空缓存、有效宽度、书签配对、设置关系及内容类型。

| 输出 | 页数 | 可观察结果 |
| --- | --- | --- |
| 严格导入模型的 `imported.pdf` | 3 | 第 1 页当前页码 1、总页数 3；目标页码 7、编号 4；第 2 页重复表头保留总页数和目标引用；第 3 页编号目标为 `4. NUMBER-TARGET` |
| 隔离调用方的 `consumer-docx-fields.pdf` | 2 | 正文与单元格均为当前页码 1、总页数 2、目标页码 2 |
| 本机 Word 生成的 `word.pdf` | 3 | 原生字段更新后显示相同样例值，两个表头呈现保留总页数 3、目标页码 7 和编号 4 |

严格导入 PDF 保留 7 个内部引用和 1 个外链，6 个引用指向第 3 个物理页，表格起点引用指向第 1 页。普通表格行没有丢失或重复。

全部 8 个新输出页均使用 Poppler／PDFium 渲染并目视复查，无非预期丢失、重叠或截断。模糊后通道平均绝对差最大值分别为 1.793016、0.105617 和 0.887308，均通过既有阈值。报告与两张联系页位于 `build/verification/docx-fields/`。

## Word 实测与修正

本机 Microsoft Word 16.0 在私有实例中打开生成文件，禁用宏、不加入最近文件，更新字段后另存 DOCX 并导出 PDF。两次 `Fields.Update` 均返回 0；识别到 9 个页码／引用字段及 1 个超链接字段。报告保存源 DOCX SHA-256，独立验证核对源文件和字段指令，避免复用其他输出的旧证据。

实测发现重复表头 PAGE 复用缓存：第一页为 1，第二页仍为 1。本引擎按每次呈现解析 PAGE，因此导出这种组合会改变语义，现已在写入任何字节前拒绝。实验报告和 PDF 保存在 `build/verification/docx-fields-repeated-header-word-report.json`／`docx-fields-repeated-header-word.pdf`，正式样例改为重复总页数与固定目标引用，普通正文行继续包含 PAGE。

另一个边界是块中间书签：Word PAGEREF 可指向跨页段落内的某一位置，而模型引用目标为块起点。导入不再把中间书签静默改成块起点；严格模式拒绝，损失模式保留缓存文字并诊断。

Word 保存后的样例把 9 个简单字段转为 27 个复杂字段控制标记，且移除了 `od:width`。这验证了复杂字段解析与显式宽度入口的必要性；一般 Word 文档仍受外部样式、编号覆盖和额外部件的损失规则约束，本批没有宣称保存后的任意 Word 文件可严格无损导入。

## 既有能力回归

- 原进阶 7 页与历史视觉基线逐页零像素差，基线文件未更新。
- 生产、行内图片 4 页及原行内字段 12 页独立验证通过；真实 Edge 保存表单使用既有证据，未新增阅读器人工填写操作。
- 新生成 PDF/A-2b 通过 veraPDF：144 项规则、10,629 项检查，失败均为 0。
- 新生成开发签名通过 OpenSSL 验签，篡改副本被拒绝；开发证书信任未验证。
- Java 未新增第三方运行依赖。新图片由样例代码生成。

## 未覆盖范围

DOCX 仍缺注释标记、题注编号、页眉页脚、目录、评论、表单、修订、嵌套字段和一般 Word 无损互转。Word 自行换行和分页，不保证与 PDF 一致；本批 Word 字段样例实测也不代表一般 Word／LibreOffice 外观兼容完成。

项目包名及制品坐标保持 `com.mirakyux.export4j`。

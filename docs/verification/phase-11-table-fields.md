# 阶段十一验收：语义表格字段与精度回归

## 本批范围

`TableCell.mixed` 支持当前显示页码、最终物理页总数、目标页码和编号引用，可与文字、行内图片及外部链接混排。重复表头按每次实际所在页解析；拆行单元格保留完整行、原文 UTF-16、原行列、续行标志与起始行号。模板绑定保留字段并展开目标 ID。

最终解析不改变列宽、行框或分页。生成值诊断和失败保留实际物理页、块、原始行列和字段位置；整篇共享字段、生成码点及链接预算。设计见 [ADR-0015](../adr/0015-semantic-table-fields.md)，调用见[字段 API](../inline-fields-api.md)与[表格 API](../table-api.md)。

## 构建与验证

本地 Java 21、Gradle 9.2.1，生产与测试编译均使用 `-Xlint:all -Werror`。完整构建、SBOM、隔离制品和示例任务通过，累计 221 项测试，无失败、错误或跳过。

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:textPdf :examples:flowPdf :examples:tablePdf :examples:componentPdf :examples:inlineFields :examples:production :examples:advancedPdf :examples:inlineImages :examples:mixedColumns --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
python scripts/verify_inline_fields.py
python scripts/verify_advanced_pdf.py
python scripts/verify_production.py --reader-copy examples/build/production/reader-filled.pdf
python scripts/verify_inline_images.py
python scripts/verify_mixed_columns.py
.\gradlew.bat -g build/gradle-validation :examples:multiFloats :examples:continuousColumns --no-daemon --console=plain
python scripts/verify_multi_floats.py
python scripts/verify_continuous_columns.py
.\gradlew.bat -g build/gradle-validation :examples:capabilities -PsignerOpenSsl=openssl -PsignerCertificate=build/verification/signing/dev-cert.pem -PsignerKey=build/verification/signing/dev-key.pem --no-daemon --console=plain
& .\build\verification\verapdf\verapdf.bat --format xml --flavour 2b examples/build/capabilities/archive.pdf > build/verification/table-fields-verapdf.xml
python scripts/verify_capabilities.py --openssl openssl --verapdf-report build/verification/table-fields-verapdf.xml
~~~

新增测试覆盖重复表头最终页码、目录偏移、分节重置后的向前引用、拆行字段来源与几何、自动列宽、字段后外链、生成值缺字策略与行列诊断、宽度／未知目标失败、重复表头预算、模板绑定及 DOCX 写入前拒绝。

## 新样例与独立渲染

`examples/build/inline-fields/tables.pdf` 共 4 页，配套 SVG 和 144 DPI PNG ZIP：

| 物理页 | 可观察结果 |
| --- | --- |
| 1 | 目录链接指向第 2 页标题 |
| 2 | 表头 `HEADER page=2 total=4`；目标 `TARGET=7 Figure 1`；CELL-01 至 CELL-06 的当前页均为 2；图片显示一次 |
| 3 | 表头 `HEADER page=3 total=4`；目标不变；CELL-07 至 CELL-12 的当前页均为 3；最后一行保留外部链接；图片不重复 |
| 4 | 重置显示页码为 7 的题注目标 |

独立脚本检查全部 17 个逻辑行、12 行正文唯一性、2 次表头、4 个内部引用、1 个外链及 1 张图片。行顶、引用矩形、目标点和外链位置均在 0.003 pt 容差内。全部 4 页已目视复查，无丢失、重叠或非预期截断。

原正文字段 4 页、脚注 3 页、双向文字 1 页继续通过，字段样例合计 12 页。Poppler／PDFium 最大模糊后通道平均绝对差为 1.692623，PNG／PDF 为 1.763622，均通过既有阈值。报告和联系页保存在 `build/verification/inline-fields/`。

## 复查发现与修正

自动列宽使用测得行宽后，JDK `TextMeasurer.getLineBreakIndex` 对含原子字段的行可能提前断开。用实际 `getLayout` 行宽校正候选断点，仍以完整字形簇推进，并严格核对可用宽度。回归样例从非预期的 3 页恢复为 2 页，12 个原始文字行均未拆坏；不放宽字段宽度检查。

进阶历史基线原有第 4／5 页差异分别涉及 245／343 个像素，集中在左列 GROUP 文本的 P 字形。控制实验仅将 12 个 GROUP 字形串的微小 TJ 调整置零，即恢复两页基线零差异。源码证据表明，后端先把两个绝对游标转为 float 再相减，使相对字距受起点量化影响；修正为直接从模型字形 advance 计算相对推进，保留显式字距和范围验证。

修正后重新生成的 7 页进阶样例与原历史 Poppler 基线全部零差异，未修改任何基线文件。新增测试验证不同绝对起点的同一字形串具有一致相对字距；原显式 advance 测试继续通过。控制实验及差异裁切保存在忽略目录 `build/verification/baseline-investigation/`，最终结果见 `examples/build/advanced-verification/report.json`。

## 隔离调用方与其他回归

只通过本地 Maven 坐标调用的新输出位于 `verification/consumer/build/validation-4db213a54bf5/`，成功目录指针已更新。两页字段 PDF 同时包含 `CONSUMER-PAGE=1 TOTAL=2 TARGET=2` 和 `CONSUMER-CELL=1 TOTAL=2 TARGET=2`，两条内部链接均指向第二页。结构与双渲染器检查通过，全部两页已目视复查。

生产 16 页、进阶 7 页、行内图像 4 页和混合分栏 8 页的独立验证通过。公共断行和后端变更另回归浮动绕排 8 页、连续分栏 9 页，以及公共复杂文本／模板／CFF／加密／归档／签名样例，均通过独立结构及渲染检查。

重新生成的归档文件通过 veraPDF PDF/A-2b 校验：144 条规则、10,629 项检查通过，失败均为 0。当前开发证书签名通过 OpenSSL 验签，篡改副本被拒绝；证书信任链不在此测试范围。工具路径和开发签名材料沿用[阶段五本地验证配置](phase-5-capabilities.md)，不进入分发制品。

`reader-filled.pdf` 复用此前真实 Edge 填写保存证据，本批没有新增阅读器交互验证。234 个文档链接、Python 语法和修改代码的导入检查通过。

## 支持边界

单元格 `NOTE_MARKER`、组件／连续栏内字段、字段自动宽度重排、DOCX 字段映射和结构化可访问性尚未实现。静态预览显式去除交互，PDF 保留目录和链接。独立结构及渲染检查不代表全部阅读器或跨系统兼容。

本地隔离发布只写入忽略目录。
